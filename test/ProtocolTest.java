import com.phonepad.ds.core.DualSense;
import com.phonepad.ds.core.PadState;
import com.phonepad.ds.core.UsbIpServer;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 在电脑上扮演 usbip 客户端，把枚举、读描述符、读特性报告、收输入报告、取消、写输出报告都走一遍。 */
public class ProtocolTest {

    static int failures = 0;
    static int checks = 0;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures++;
        System.out.println((ok ? "  通过 " : "  失败 ") + what);
    }

    public static void main(String[] args) throws Exception {
        final int port = 32411;
        PadState state = new PadState();
        final List<DualSense.Output> outputs = new CopyOnWriteArrayList<>();
        final List<Boolean> conn = new CopyOnWriteArrayList<>();
        byte[] mac = {(byte) 0xA0, 0x5A, 0x5D, 0x12, 0x34, 0x56};
        UsbIpServer server = new UsbIpServer(state, mac, new UsbIpServer.Listener() {
            public void onConnectionChanged(boolean c) { conn.add(c); }
            public void onOutput(DualSense.Output o) { outputs.add(o); }
            public void onServerError(String m) { System.out.println("服务端错误: " + m); }
        }, port);
        server.start();
        Thread.sleep(300);

        System.out.println("[1] OP_REQ_DEVLIST");
        try (Socket s = new Socket("127.0.0.1", port)) {
            DataInputStream in = new DataInputStream(s.getInputStream());
            s.getOutputStream().write(ByteBuffer.allocate(8).putShort((short) 0x0111).putShort((short) 0x8005).putInt(0).array());
            int ver = in.readUnsignedShort(), code = in.readUnsignedShort(), st = in.readInt(), n = in.readInt();
            check(ver == 0x0111 && code == 0x0005 && st == 0 && n == 1, "设备列表应答头 ver=" + hex(ver) + " code=" + hex(code) + " n=" + n);
            byte[] dev = new byte[312];
            in.readFully(dev);
            ByteBuffer d = ByteBuffer.wrap(dev);
            String busid = cstr(dev, 256, 32);
            d.position(288);
            int busnum = d.getInt(), devnum = d.getInt(), speed = d.getInt();
            int vid = d.getShort() & 0xFFFF, pid = d.getShort() & 0xFFFF;
            check("1-1".equals(busid) && vid == 0x054C && pid == 0x0CE6 && speed == 3,
                    "busid=" + busid + " VID:PID=" + hex(vid) + ":" + hex(pid) + " speed=" + speed + " bus/dev=" + busnum + "/" + devnum);
            byte[] itf = new byte[4];
            in.readFully(itf);
            check(itf[0] == 3, "接口类别 HID (" + itf[0] + ")");
            check(in.read() == -1, "列表发完后服务端关闭连接");
        }

        System.out.println("[2] OP_REQ_IMPORT 错误的 busid");
        try (Socket s = new Socket("127.0.0.1", port)) {
            DataInputStream in = new DataInputStream(s.getInputStream());
            ByteBuffer b = ByteBuffer.allocate(40).putShort((short) 0x0111).putShort((short) 0x8003).putInt(0);
            b.put("9-9".getBytes(Charset.forName("US-ASCII")));
            s.getOutputStream().write(b.array());
            in.readInt();
            check(in.readInt() != 0, "错误 busid 被拒绝");
        }

        System.out.println("[3] OP_REQ_IMPORT 1-1");
        Socket s = new Socket("127.0.0.1", port);
        s.setTcpNoDelay(true);
        DataInputStream in = new DataInputStream(s.getInputStream());
        OutputStream out = s.getOutputStream();
        ByteBuffer ib = ByteBuffer.allocate(40).putShort((short) 0x0111).putShort((short) 0x8003).putInt(0);
        ib.put("1-1".getBytes(Charset.forName("US-ASCII")));
        out.write(ib.array());
        int ver = in.readUnsignedShort(), code = in.readUnsignedShort(), st = in.readInt();
        byte[] udev = new byte[312];
        in.readFully(udev);
        check(ver == 0x0111 && code == 0x0003 && st == 0, "导入成功 code=" + hex(code) + " status=" + st);
        Thread.sleep(50);
        check(conn.size() == 1 && conn.get(0), "服务端回调已连接");

        Client c = new Client(in, out);

        System.out.println("[4] 标准描述符");
        Ret r = c.control(0x80, 0x06, 0x0100, 0, 18, null);
        check(r.status == 0 && r.data.length == 18, "设备描述符 18 字节");
        int vid = u16(r.data, 8), pid = u16(r.data, 10);
        check(vid == 0x054C && pid == 0x0CE6 && r.data[7] == 64, "设备描述符 VID:PID " + hex(vid) + ":" + hex(pid));
        r = c.control(0x80, 0x06, 0x0100, 0, 8, null);
        check(r.data.length == 8, "按 wLength 截短（8 字节）");
        r = c.control(0x80, 0x06, 0x0200, 0, 9, null);
        int total = u16(r.data, 2);
        check(r.data.length == 9 && total == 41, "配置描述符头 wTotalLength=" + total);
        r = c.control(0x80, 0x06, 0x0200, 0, 255, null);
        check(r.data.length == total, "完整配置描述符 " + r.data.length + " 字节");
        int reportDescLen = -1, epIn = -1, epOut = -1, epCount = 0;
        for (int p = 0; p < r.data.length; p += r.data[p] & 0xFF) {
            int t = r.data[p + 1] & 0xFF;
            if (t == 0x21) reportDescLen = u16(r.data, p + 7);
            if (t == 0x05) {
                epCount++;
                int a = r.data[p + 2] & 0xFF;
                if ((a & 0x80) != 0) epIn = a; else epOut = a;
                check((r.data[p + 3] & 3) == 3 && u16(r.data, p + 4) == 64, "端点 " + hex(a) + " 中断 64 字节 bInterval=" + r.data[p + 6]);
            }
        }
        check(epCount == 2 && epIn == 0x84 && epOut == 0x03, "端点 0x84 / 0x03");
        r = c.control(0x80, 0x06, 0x0600, 0, 10, null);
        check(r.status == 0 && r.data.length == 10, "设备限定描述符");
        r = c.control(0x80, 0x06, 0x0300, 0, 255, null);
        check(r.data.length == 4 && u16(r.data, 2) == 0x0409, "语言 ID 0x0409");
        r = c.control(0x80, 0x06, 0x0301, 0x0409, 255, null);
        check("Sony Interactive Entertainment".equals(utf16(r.data)), "厂商字符串: " + utf16(r.data));
        r = c.control(0x80, 0x06, 0x0302, 0x0409, 255, null);
        check("DualSense Wireless Controller".equals(utf16(r.data)), "产品字符串: " + utf16(r.data));
        r = c.control(0x80, 0x06, 0x03EE, 0, 18, null);
        check(r.status == -32, "MS OS 字符串 0xEE 回 STALL");
        r = c.control(0x80, 0x06, 0x0F00, 0, 5, null);
        check(r.status == -32, "BOS 描述符回 STALL");

        System.out.println("[5] 配置和 HID 类请求");
        r = c.control(0x00, 0x09, 1, 0, 0, null);
        check(r.status == 0, "SET_CONFIGURATION 1");
        r = c.control(0x21, 0x0A, 0, 0, 0, null);
        check(r.status == 0, "SET_IDLE");
        r = c.control(0x81, 0x06, 0x2200, 0, reportDescLen + 64, null);
        check(r.status == 0 && r.data.length == reportDescLen, "HID 报告描述符 " + r.data.length + " 字节（HID 描述符里写的 " + reportDescLen + "）");

        Map<String, Integer> sizes = parseReportSizes(r.data);
        System.out.println("    解析出的报告长度（位）: " + sizes);
        check(sizes.get("in:1") == 63 * 8, "输入报告 1 = 63 字节");
        check(sizes.get("out:2") == 47 * 8, "输出报告 2 = 47 字节");
        int[][] expectFeature = {{0x05, 40}, {0x09, 19}, {0x20, 63}, {0x08, 47}, {0xF2, 15}, {0xA0, 1}};
        for (int[] f : expectFeature) {
            check(sizes.get("feat:" + f[0]) == f[1] * 8, "特性报告 " + hex(f[0]) + " = " + f[1] + " 字节");
        }

        System.out.println("[6] 特性报告 GET_REPORT");
        int[] ids = {0x05, 0x09, 0x20, 0x08, 0x22, 0xF2};
        for (int id : ids) {
            int want = sizes.get("feat:" + id) / 8 + 1;
            r = c.control(0xA1, 0x01, 0x0300 | id, 0, want, null);
            check(r.status == 0 && r.data.length == want && (r.data[0] & 0xFF) == id, "特性 " + hex(id) + " 回 " + r.data.length + " 字节（描述符要求 " + want + "）");
        }
        r = c.control(0xA1, 0x01, 0x0305, 0, 41, null);
        check(s16(r.data, 7) == 8640 && s16(r.data, 9) == -8640 && s16(r.data, 19) == 540 && s16(r.data, 27) == 8192,
                "校准数据 pitch+=" + s16(r.data, 7) + " pitch-=" + s16(r.data, 9) + " speed=" + s16(r.data, 19) + " accY+=" + s16(r.data, 27));
        r = c.control(0xA1, 0x01, 0x0309, 0, 20, null);
        check((r.data[1] & 0xFF) == 0x56 && (r.data[6] & 0xFF) == 0xA0, "配对信息里的 MAC（低字节在前）");
        r = c.control(0xA1, 0x01, 0x0320, 0, 64, null);
        check(u16(r.data, 44) == 0x0630, "固件信息 update version " + hex(u16(r.data, 44)));
        r = c.control(0xA1, 0x01, 0x0377, 0, 64, null);
        check(r.status == -32, "不存在的特性报告 0x77 回 STALL");

        System.out.println("[7] 中断 IN 输入报告");
        boolean[] ta = {true, false};
        state.setTouchInput(PadState.CROSS | PadState.UP | PadState.RIGHT | PadState.L1 | PadState.PS,
                0, 255, 200, 50, 255, 0, ta, new int[]{5, 0}, new int[]{1000, 0}, new int[]{500, 0});
        state.setMotion(160, -320, 0, 100, 8192, -200);
        long t0 = System.nanoTime();
        for (int i = 0; i < 3; i++) c.submitIn(4, 64);
        int[] counters = new int[3];
        long[] times = new long[3];
        byte[] last = null;
        for (int i = 0; i < 3; i++) {
            Ret x = c.read();
            times[i] = (System.nanoTime() - t0) / 1000;
            check(x.status == 0 && x.data.length == 64 && x.data[0] == 1, "输入报告 #" + i + " 64 字节 ID=1");
            counters[i] = x.data[7] & 0xFF;
            last = x.data;
        }
        System.out.println("    计数器 " + counters[0] + "," + counters[1] + "," + counters[2] + "  收到时间(微秒) " + times[0] + "," + times[1] + "," + times[2]);
        check(counters[1] == (counters[0] + 1) % 256 && counters[2] == (counters[1] + 1) % 256, "计数器递增");
        check(last[1] == 0 && (last[2] & 0xFF) == 255 && (last[3] & 0xFF) == 200 && last[4] == 50, "摇杆字节");
        check((last[5] & 0xFF) == 255 && last[6] == 0, "扳机字节");
        check((last[8] & 0x0F) == 1 && (last[8] & 0x20) != 0, "十字键右上(hat=1) + 叉键");
        check((last[9] & 0x01) != 0 && (last[10] & 0x01) != 0, "L1 + PS");
        check(s16(last, 16) == 160 && s16(last, 18) == -320 && s16(last, 24) == 8192 && s16(last, 26) == -200, "陀螺仪/加速度");
        int tx = (last[34] & 0xFF) | ((last[35] & 0x0F) << 8), ty = ((last[35] & 0xFF) >> 4) | ((last[36] & 0xFF) << 4);
        check((last[33] & 0x80) == 0 && (last[33] & 0x7F) == 5 && tx == 1000 && ty == 500, "触点 1: id=5 x=" + tx + " y=" + ty);
        check((last[37] & 0x80) != 0, "触点 2 未按下");
        check((last[53] & 0x0F) <= 10, "电量字节 " + hex(last[53] & 0xFF));

        System.out.println("[8] 节奏：没变化时约 4ms 一包");
        long a = System.nanoTime();
        for (int i = 0; i < 50; i++) {
            c.submitIn(4, 64);
            c.read();
        }
        double avgMs = (System.nanoTime() - a) / 1e6 / 50;
        System.out.printf("    50 包平均间隔 %.2f ms%n", avgMs);
        check(avgMs > 3.0 && avgMs < 6.5, "平均间隔在 3~6.5ms");
        c.submitIn(4, 64);
        c.read(); // 先对齐一次发包节奏，下一包的定时点在 4ms 后
        a = System.nanoTime();
        c.submitIn(4, 64);
        state.setTouchInput(PadState.CIRCLE, 128, 128, 128, 128, 0, 0, new boolean[2], new int[2], new int[2], new int[2]);
        Ret ch = c.read();
        double lat = (System.nanoTime() - a) / 1e6;
        System.out.printf("    状态变化后 %.2f ms 收到%n", lat);
        check((ch.data[8] & 0x40) != 0, "变化后的报告里有圆键");
        check(lat < 3.5, "状态变化后不用等满 4ms 就发出");

        System.out.println("[9] CMD_UNLINK");
        int victim = c.submitIn(4, 64);
        int unlinkSeq = c.unlink(victim);
        Ret u = c.read();
        if (u.command == 3 && u.seq == victim) {
            // 已经先回了 RET_SUBMIT，再收 RET_UNLINK，状态应为 0
            Ret u2 = c.read();
            check(u2.command == 4 && u2.seq == unlinkSeq && u2.status == 0, "URB 已完成后再取消：RET_UNLINK status=" + u2.status);
        } else {
            check(u.command == 4 && u.seq == unlinkSeq && u.status == -104, "取消挂起的 URB：RET_UNLINK status=" + u.status);
            int probe = c.submitIn(4, 64);
            Ret p = c.read();
            check(p.command == 3 && p.seq == probe, "被取消的 URB 没有再回 RET_SUBMIT（下一包 seq=" + p.seq + "，期望 " + probe + "）");
        }

        System.out.println("[10] 输出报告（震动、灯条、玩家灯）");
        byte[] rep = new byte[48];
        rep[0] = 0x02;
        rep[1] = 0x03;                 // 震动有效
        rep[2] = 0x04 | 0x10 | 0x01;   // 灯条、玩家灯、麦克风灯有效
        rep[3] = (byte) 0x40;          // 右马达
        rep[4] = (byte) 0xC0;          // 左马达
        rep[9] = 1;
        rep[44] = 0x04;
        rep[45] = 0x00; rep[46] = (byte) 0x80; rep[47] = (byte) 0xFF;
        Ret w = c.submitOut(3, rep);
        check(w.status == 0 && w.actualLength == 48, "中断 OUT 应答 actual_length=" + w.actualLength);
        Thread.sleep(20);
        check(outputs.size() == 1, "服务端解析出 1 条输出");
        if (!outputs.isEmpty()) {
            DualSense.Output o = outputs.get(0);
            check(o.hasRumble && o.rumbleLeft == 0xC0 && o.rumbleRight == 0x40, "震动 左=" + o.rumbleLeft + " 右=" + o.rumbleRight);
            check(o.hasLightbar && o.red == 0 && o.green == 0x80 && o.blue == 0xFF, "灯条 RGB");
            check(o.hasPlayerLeds && o.playerLeds == 4 && o.hasMicLed && o.micLed == 1, "玩家灯 / 麦克风灯");
        }
        rep[3] = 0; rep[4] = 0;
        r = c.control(0x21, 0x09, 0x0202, 0, 0, rep);
        check(r.status == 0 && outputs.size() == 2, "SET_REPORT 发输出报告也能收到");

        System.out.println("[11] 第二个客户端顶掉第一个");
        Socket s2 = new Socket("127.0.0.1", port);
        s2.getOutputStream().write(ib.array());
        DataInputStream in2 = new DataInputStream(s2.getInputStream());
        in2.readInt();
        check(in2.readInt() == 0, "第二次导入成功");
        in2.readFully(new byte[312]);
        boolean closed = false;
        try {
            s.setSoTimeout(1000);
            while (true) if (in.read() == -1) { closed = true; break; }
        } catch (IOException e) {
            closed = true;
        }
        check(closed, "旧连接被关闭");
        Client c2 = new Client(in2, s2.getOutputStream());
        c2.submitIn(4, 64);
        check(c2.read().data.length == 64, "新连接能收输入报告");
        s2.close();
        Thread.sleep(200);
        check(!server.isConnected() && !conn.get(conn.size() - 1), "断开后回调未连接");

        server.stop();
        System.out.println();
        System.out.println("共 " + checks + " 项检查，失败 " + failures + " 项。");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---------- 小工具 ----------

    static final class Ret {
        int command, seq, status, actualLength;
        byte[] data = new byte[0];
    }

    static final class Client {
        final DataInputStream in;
        final OutputStream out;
        int seq = 1;

        Client(DataInputStream in, OutputStream out) {
            this.in = in;
            this.out = out;
        }

        Ret control(int type, int req, int value, int index, int length, byte[] data) throws IOException {
            boolean dirIn = (type & 0x80) != 0;
            byte[] setup = {(byte) type, (byte) req, (byte) value, (byte) (value >> 8), (byte) index, (byte) (index >> 8),
                    (byte) length, (byte) (length >> 8)};
            if (!dirIn && data != null) {
                setup[6] = (byte) data.length;
                setup[7] = (byte) (data.length >> 8);
            }
            int n = dirIn ? length : (data == null ? 0 : data.length);
            submit(0, dirIn, n, setup, dirIn ? null : data);
            return read();
        }

        int submitIn(int ep, int length) throws IOException {
            return submit(ep, true, length, new byte[8], null);
        }

        Ret submitOut(int ep, byte[] data) throws IOException {
            submit(ep, false, data.length, new byte[8], data);
            return read();
        }

        int submit(int ep, boolean dirIn, int length, byte[] setup, byte[] data) throws IOException {
            int sq = seq++;
            lastWasOut = !dirIn;
            ByteBuffer b = ByteBuffer.allocate(48 + (data == null ? 0 : data.length));
            b.putInt(1).putInt(sq).putInt(0x10002).putInt(dirIn ? 1 : 0).putInt(ep);
            b.putInt(dirIn ? 0x200 : 0).putInt(length).putInt(0).putInt(ep == 0 ? 0 : -1).putInt(ep == 0 ? 0 : 6);
            b.put(setup);
            if (data != null) b.put(data);
            out.write(b.array());
            out.flush();
            return sq;
        }

        int unlink(int victim) throws IOException {
            int sq = seq++;
            ByteBuffer b = ByteBuffer.allocate(48);
            b.putInt(2).putInt(sq).putInt(0x10002).putInt(0).putInt(4).putInt(victim);
            out.write(b.array());
            out.flush();
            return sq;
        }

        Ret read() throws IOException {
            Ret r = new Ret();
            r.command = in.readInt();
            r.seq = in.readInt();
            in.readInt();
            in.readInt();
            in.readInt();
            r.status = in.readInt();
            if (r.command == 3) {
                r.actualLength = in.readInt();
                in.readInt();
                in.readInt();
                in.readInt();
                in.readLong();
                // 只有 IN 方向带数据；这里用 actual_length 和请求方向无法区分，靠约定：OUT 请求我们不读数据
                if (r.actualLength > 0 && !lastWasOut) {
                    r.data = new byte[r.actualLength];
                    in.readFully(r.data);
                }
            } else {
                in.readFully(new byte[24]);
            }
            return r;
        }

        boolean lastWasOut;
    }

    /** 极简 HID 报告描述符解析：按报告 ID 统计输入/输出/特性的总位数。 */
    static Map<String, Integer> parseReportSizes(byte[] d) {
        Map<String, Integer> m = new TreeMap<>();
        int reportSize = 0, reportCount = 0, reportId = 0;
        List<int[]> stack = new ArrayList<>();
        for (int i = 0; i < d.length; ) {
            int prefix = d[i] & 0xFF;
            int size = prefix & 3;
            if (size == 3) size = 4;
            int tag = prefix & 0xFC;
            int v = 0;
            for (int k = 0; k < size; k++) v |= (d[i + 1 + k] & 0xFF) << (8 * k);
            i += 1 + size;
            switch (tag) {
                case 0x74: reportSize = v; break;
                case 0x94: reportCount = v; break;
                case 0x84: reportId = v; break;
                case 0xA4: stack.add(new int[]{reportSize, reportCount, reportId}); break;
                case 0xB4: { int[] t = stack.remove(stack.size() - 1); reportSize = t[0]; reportCount = t[1]; reportId = t[2]; break; }
                case 0x80: m.merge("in:" + reportId, reportSize * reportCount, Integer::sum); break;
                case 0x90: m.merge("out:" + reportId, reportSize * reportCount, Integer::sum); break;
                case 0xB0: m.merge("feat:" + reportId, reportSize * reportCount, Integer::sum); break;
                default: break;
            }
        }
        return m;
    }

    static int u16(byte[] b, int p) { return (b[p] & 0xFF) | ((b[p + 1] & 0xFF) << 8); }
    static int s16(byte[] b, int p) { return (short) u16(b, p); }
    static String hex(int v) { return String.format("0x%04X", v); }
    static String cstr(byte[] b, int off, int max) {
        int n = 0;
        while (n < max && b[off + n] != 0) n++;
        return new String(b, off, n, Charset.forName("US-ASCII"));
    }
    static String utf16(byte[] d) { return d.length < 2 ? "" : new String(d, 2, (d[0] & 0xFF) - 2, Charset.forName("UTF-16LE")); }
}
