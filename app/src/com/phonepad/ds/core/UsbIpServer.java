package com.phonepad.ds.core;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * USB/IP 服务端（协议 1.1.1），对外导出一个虚拟 DualSense，总线号 "1-1"。
 * 只监听本机回环地址：电脑经 adb forward 从 USB 线连进来，Wi-Fi 上看不到。
 */
public final class UsbIpServer {

    public interface Listener {
        void onConnectionChanged(boolean connected);

        void onOutput(DualSense.Output out);

        void onServerError(String message);
    }

    public static final String BUS_ID = "1-1";
    private static final int VERSION = 0x0111;
    private static final int OP_REQ_DEVLIST = 0x8005, OP_REP_DEVLIST = 0x0005;
    private static final int OP_REQ_IMPORT = 0x8003, OP_REP_IMPORT = 0x0003;
    private static final int CMD_SUBMIT = 1, CMD_UNLINK = 2, RET_SUBMIT = 3, RET_UNLINK = 4;
    private static final int EPIPE = -32, ECONNRESET = -104;
    private static final int SPEED_HIGH = 3;
    private static final long REPORT_PERIOD_NS = 4_000_000L;   // 没变化时 250Hz
    private static final long MIN_GAP_NS = 1_000_000L;         // 有变化时最快 1ms 一包

    private final PadState state;
    private final byte[] mac;
    private final Listener listener;
    private final int port;
    private final long startNanos = System.nanoTime();

    private volatile boolean running;
    private ServerSocket serverSocket;
    private Session current;

    public UsbIpServer(PadState state, byte[] mac, Listener listener, int port) {
        this.state = state;
        this.mac = mac.clone();
        this.listener = listener;
        this.port = port;
        state.setChangeListener(new Runnable() {
            @Override
            public void run() {
                Session s;
                synchronized (UsbIpServer.this) {
                    s = current;
                }
                if (s != null) s.markDirty();
            }
        });
    }

    public synchronized boolean isConnected() {
        return current != null;
    }

    public void start() {
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "usbip-accept");
        t.setDaemon(true);
        t.start();
    }

    public void stop() {
        running = false;
        ServerSocket ss;
        Session s;
        synchronized (this) {
            ss = serverSocket;
            s = current;
        }
        closeQuietly(ss);
        if (s != null) s.close();
    }

    private void acceptLoop() {
        while (running) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                // 必须是 IPv4 的 127.0.0.1：adb forward 连的是它。安卓上 getLoopbackAddress() 返回的是 ::1。
                ss.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), port));
                synchronized (this) {
                    serverSocket = ss;
                }
                while (running) {
                    final Socket sock = ss.accept();
                    Thread t = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            handleClient(sock);
                        }
                    }, "usbip-client");
                    t.setDaemon(true);
                    t.start();
                }
            } catch (IOException e) {
                if (!running) return;
                listener.onServerError("端口 " + port + " 打不开：" + e.getMessage());
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private void handleClient(Socket sock) {
        try {
            sock.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(sock.getInputStream());
            OutputStream out = new BufferedOutputStream(sock.getOutputStream(), 4096);
            in.readUnsignedShort(); // 版本号，usbip-win2 和 Linux 都发 0x0111，不同也照常应答
            int code = in.readUnsignedShort();
            in.readInt(); // status
            if (code == OP_REQ_DEVLIST) {
                ByteBuffer b = ByteBuffer.allocate(12 + 312 + 4);
                b.putShort((short) VERSION).putShort((short) OP_REP_DEVLIST).putInt(0).putInt(1);
                putDevice(b);
                b.put((byte) 0x03).put((byte) 0).put((byte) 0).put((byte) 0); // 接口：HID
                out.write(b.array());
                out.flush();
                sock.close();
            } else if (code == OP_REQ_IMPORT) {
                byte[] busid = new byte[32];
                in.readFully(busid);
                String id = cString(busid);
                if (!BUS_ID.equals(id)) {
                    ByteBuffer b = ByteBuffer.allocate(8);
                    b.putShort((short) VERSION).putShort((short) OP_REP_IMPORT).putInt(1);
                    out.write(b.array());
                    out.flush();
                    sock.close();
                    return;
                }
                ByteBuffer b = ByteBuffer.allocate(8 + 312);
                b.putShort((short) VERSION).putShort((short) OP_REP_IMPORT).putInt(0);
                putDevice(b);
                out.write(b.array());
                out.flush();
                Session s = new Session(sock, in, out);
                Session old;
                synchronized (this) {
                    old = current;
                    current = s;
                }
                if (old != null) old.close(); // 新连接顶掉旧的（比如电脑那边断线后重连）
                listener.onConnectionChanged(true);
                s.run();
                boolean wasCurrent;
                synchronized (this) {
                    wasCurrent = current == s;
                    if (wasCurrent) current = null;
                }
                if (wasCurrent) listener.onConnectionChanged(false);
            } else {
                sock.close();
            }
        } catch (IOException e) {
            closeQuietly(sock);
        }
    }

    private void putDevice(ByteBuffer b) {
        byte[] path = new byte[256];
        byte[] p = "/sys/devices/platform/phonepad/usb1/1-1".getBytes(Charset.forName("US-ASCII"));
        System.arraycopy(p, 0, path, 0, p.length);
        byte[] bus = new byte[32];
        byte[] q = BUS_ID.getBytes(Charset.forName("US-ASCII"));
        System.arraycopy(q, 0, bus, 0, q.length);
        b.put(path).put(bus);
        b.putInt(1).putInt(2).putInt(SPEED_HIGH);
        b.putShort((short) DualSense.VID).putShort((short) DualSense.PID).putShort((short) DualSense.BCD_DEVICE);
        b.put((byte) 0).put((byte) 0).put((byte) 0); // class/subclass/protocol
        b.put((byte) 1).put((byte) 1).put((byte) 1); // bConfigurationValue, bNumConfigurations, bNumInterfaces
    }

    private static String cString(byte[] b) {
        int n = 0;
        while (n < b.length && b[n] != 0) n++;
        return new String(b, 0, n, Charset.forName("US-ASCII"));
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }

    /** 一次 import 之后的 URB 往来。读线程处理命令，发送线程按节奏回中断 IN。 */
    private final class Session {
        private final Socket sock;
        private final DataInputStream in;
        private final OutputStream out;
        private final Object lock = new Object();      // 保护 pending / dirty
        private final Object writeLock = new Object(); // 保证一条回包完整写出
        private final ArrayDeque<int[]> pending = new ArrayDeque<>(); // {seqnum, 长度}
        private volatile boolean alive = true;
        private boolean dirty = true;
        private long lastSent;
        private int counter;
        private int packetSeq;
        private final byte[] report = new byte[DualSense.INPUT_REPORT_SIZE];

        Session(Socket sock, DataInputStream in, OutputStream out) {
            this.sock = sock;
            this.in = in;
            this.out = out;
        }

        void markDirty() {
            synchronized (lock) {
                dirty = true;
                lock.notifyAll();
            }
        }

        void close() {
            alive = false;
            synchronized (lock) {
                lock.notifyAll();
            }
            closeQuietly(sock);
        }

        void run() {
            Thread sender = new Thread(new Runnable() {
                @Override
                public void run() {
                    sendLoop();
                }
            }, "usbip-sender");
            sender.setDaemon(true);
            sender.start();
            try {
                readLoop();
            } catch (IOException ignored) {
            } finally {
                close();
            }
        }

        private void readLoop() throws IOException {
            byte[] setup = new byte[8];
            while (alive) {
                int command = in.readInt();
                int seqnum = in.readInt();
                in.readInt(); // devid
                int direction = in.readInt();
                int ep = in.readInt();
                if (command == CMD_SUBMIT) {
                    in.readInt(); // transfer_flags
                    int length = in.readInt();
                    in.readInt(); // start_frame
                    int packets = in.readInt();
                    in.readInt(); // interval
                    in.readFully(setup);
                    byte[] data = null;
                    if (direction == 0 && length > 0) {
                        data = new byte[length];
                        in.readFully(data);
                    }
                    if (packets > 0) {
                        skip(packets * 16L); // 没有等时端点，正常不会出现
                    }
                    handleSubmit(seqnum, direction, ep, length, setup, data);
                } else if (command == CMD_UNLINK) {
                    int victim = in.readInt();
                    skip(24);
                    handleUnlink(seqnum, victim);
                } else {
                    throw new IOException("未知命令 " + command);
                }
            }
        }

        private void skip(long n) throws IOException {
            while (n > 0) {
                long k = in.skip(n);
                if (k <= 0) {
                    in.readByte();
                    k = 1;
                }
                n -= k;
            }
        }

        private void handleSubmit(int seq, int dir, int ep, int length, byte[] setup, byte[] data) throws IOException {
            if (ep == 0) {
                if (dir == 1) {
                    byte[] r = controlIn(setup);
                    if (r == null) sendRet(seq, EPIPE, null, 0, 0);
                    else sendRet(seq, 0, r, Math.min(r.length, length), 0);
                } else {
                    boolean ok = controlOut(setup, data);
                    sendRet(seq, ok ? 0 : EPIPE, null, 0, ok ? length : 0);
                }
            } else if (ep == (DualSense.EP_IN & 0x0F) && dir == 1) {
                synchronized (lock) {
                    pending.addLast(new int[]{seq, length});
                    lock.notifyAll();
                }
            } else if (ep == DualSense.EP_OUT && dir == 0) {
                if (data != null) deliverOutput(data, 0, data.length);
                sendRet(seq, 0, null, 0, length);
            } else {
                sendRet(seq, EPIPE, null, 0, 0);
            }
        }

        private void handleUnlink(int seq, int victim) throws IOException {
            synchronized (writeLock) {
                boolean removed = false;
                synchronized (lock) {
                    for (Iterator<int[]> it = pending.iterator(); it.hasNext(); ) {
                        if (it.next()[0] == victim) {
                            it.remove();
                            removed = true;
                            break;
                        }
                    }
                }
                ByteBuffer b = ByteBuffer.allocate(48);
                b.putInt(RET_UNLINK).putInt(seq).putInt(0).putInt(0).putInt(0);
                b.putInt(removed ? ECONNRESET : 0);
                out.write(b.array());
                out.flush();
            }
        }

        private void sendLoop() {
            try {
                while (alive) {
                    synchronized (lock) {
                        while (alive) {
                            if (!pending.isEmpty()) {
                                long since = System.nanoTime() - lastSent;
                                if ((dirty && since >= MIN_GAP_NS) || since >= REPORT_PERIOD_NS) break;
                                long waitNs = (dirty ? MIN_GAP_NS : REPORT_PERIOD_NS) - since;
                                lock.wait(waitNs / 1_000_000L, (int) (waitNs % 1_000_000L));
                            } else {
                                lock.wait(100);
                            }
                        }
                    }
                    if (!alive) break;
                    synchronized (writeLock) {
                        int[] urb;
                        synchronized (lock) {
                            urb = pending.pollFirst();
                            if (urb == null) continue;
                            dirty = false;
                            lastSent = System.nanoTime();
                        }
                        int ts = (int) ((lastSent - startNanos) * 3 / 1000); // 1/3 微秒
                        state.writeInputReport(report, counter++, packetSeq++, ts);
                        writeRet(urb[0], 0, report, Math.min(report.length, urb[1]), 0);
                    }
                }
            } catch (InterruptedException | IOException e) {
                close();
            }
        }

        private void sendRet(int seq, int status, byte[] data, int dataLen, int outLen) throws IOException {
            synchronized (writeLock) {
                writeRet(seq, status, data, dataLen, outLen);
            }
        }

        /** 调用方持有 writeLock。IN 方向 actual_length = dataLen 并附数据；OUT 方向 actual_length = outLen。 */
        private void writeRet(int seq, int status, byte[] data, int dataLen, int outLen) throws IOException {
            int n = data != null ? dataLen : 0;
            ByteBuffer b = ByteBuffer.allocate(48 + n);
            b.putInt(RET_SUBMIT).putInt(seq).putInt(0).putInt(0).putInt(0);
            b.putInt(status).putInt(data != null ? dataLen : outLen).putInt(0).putInt(0).putInt(0);
            b.putLong(0);
            if (n > 0) b.put(data, 0, n);
            out.write(b.array());
            out.flush();
        }

        private byte[] controlIn(byte[] s) {
            int type = s[0] & 0xFF, req = s[1] & 0xFF;
            int value = (s[2] & 0xFF) | ((s[3] & 0xFF) << 8);
            int dt = value >> 8, idx = value & 0xFF;
            if (req == 0x06 && type == 0x80) {           // GET_DESCRIPTOR（设备）
                switch (dt) {
                    case 0x01: return DualSense.deviceDescriptor();
                    case 0x02: return DualSense.configDescriptor();
                    case 0x03: return DualSense.stringDescriptor(idx);
                    case 0x06: return DualSense.deviceQualifier();
                    default: return null;
                }
            }
            if (req == 0x06 && type == 0x81) {           // GET_DESCRIPTOR（接口）
                if (dt == 0x22) return DualSense.REPORT_DESCRIPTOR;
                if (dt == 0x21) return DualSense.hidDescriptor();
                return null;
            }
            if (req == 0x00 && (type == 0x80 || type == 0x81 || type == 0x82)) return new byte[]{0, 0}; // GET_STATUS
            if (req == 0x08 && type == 0x80) return new byte[]{1};      // GET_CONFIGURATION
            if (req == 0x0A && type == 0x81) return new byte[]{0};      // GET_INTERFACE
            if (type == 0xA1) {
                if (req == 0x01) {                                       // GET_REPORT
                    if (dt == 0x01 && idx == 0x01) {
                        byte[] r = new byte[DualSense.INPUT_REPORT_SIZE];
                        long now = System.nanoTime();
                        state.writeInputReport(r, counter, packetSeq, (int) ((now - startNanos) * 3 / 1000));
                        return r;
                    }
                    if (dt == 0x03) return DualSense.featureReport(idx, mac);
                    return null;
                }
                if (req == 0x02) return new byte[]{0};                   // GET_IDLE
                if (req == 0x03) return new byte[]{1};                   // GET_PROTOCOL
            }
            return null;
        }

        private boolean controlOut(byte[] s, byte[] data) {
            int type = s[0] & 0xFF, req = s[1] & 0xFF;
            int value = (s[2] & 0xFF) | ((s[3] & 0xFF) << 8);
            if (type == 0x00 && (req == 0x09 || req == 0x05 || req == 0x01 || req == 0x03)) return true; // SET_CONFIGURATION 等
            if ((type == 0x01 || type == 0x02) && (req == 0x0B || req == 0x01 || req == 0x03)) return true; // SET_INTERFACE / CLEAR_FEATURE
            if (type == 0x21) {
                if (req == 0x0A || req == 0x0B) return true;             // SET_IDLE / SET_PROTOCOL
                if (req == 0x09) {                                       // SET_REPORT
                    if ((value >> 8) == 0x02 && data != null) deliverOutput(data, 0, data.length);
                    return true;
                }
            }
            return false;
        }

        private void deliverOutput(byte[] d, int off, int len) {
            DualSense.Output o = DualSense.parseOutput(d, off, len);
            if (o != null) listener.onOutput(o);
        }
    }
}
