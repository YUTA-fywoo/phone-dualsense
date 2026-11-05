package com.phonepad.ds.core;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;

/**
 * 虚拟 DualSense 的 USB 身份：设备/配置/字符串描述符、HID 报告描述符、特性报告，以及电脑发来的输出报告解析。
 * 只保留 HID 接口（真手柄还有音频接口，手机用不上）；端点地址、报告 ID 和长度都按真手柄来。
 */
public final class DualSense {

    public static final int VID = 0x054C;
    public static final int PID = 0x0CE6;
    public static final int BCD_DEVICE = 0x0100;
    public static final int EP_IN = 0x84;
    public static final int EP_OUT = 0x03;
    public static final int INPUT_REPORT_SIZE = 64;
    public static final int OUTPUT_REPORT_SIZE = 48;

    private DualSense() {
    }

    /** {报告 ID, 厂商用途, 数据字节数（不含 ID）}，和真手柄的特性报告表一致。 */
    static final int[][] FEATURES = {
            {0x05, 0x33, 40}, {0x08, 0x34, 47}, {0x09, 0x24, 19}, {0x0A, 0x25, 26},
            {0x20, 0x26, 63}, {0x21, 0x27, 4}, {0x22, 0x40, 63}, {0x80, 0x28, 63},
            {0x81, 0x29, 63}, {0x82, 0x2A, 9}, {0x83, 0x2B, 63}, {0x84, 0x2C, 63},
            {0x85, 0x2D, 2}, {0xA0, 0x2E, 1}, {0xE0, 0x2F, 63}, {0xF0, 0x30, 63},
            {0xF1, 0x31, 63}, {0xF2, 0x32, 15}, {0xF4, 0x35, 63}, {0xF5, 0x36, 3},
    };

    public static final byte[] REPORT_DESCRIPTOR = buildReportDescriptor();

    private static byte[] buildReportDescriptor() {
        int[] head = {
                0x05, 0x01,        // Usage Page (Generic Desktop)
                0x09, 0x05,        // Usage (Game Pad)
                0xA1, 0x01,        // Collection (Application)
                0x85, 0x01,        //   Report ID (1)
                0x09, 0x30,        //   Usage (X)
                0x09, 0x31,        //   Usage (Y)
                0x09, 0x32,        //   Usage (Z)
                0x09, 0x35,        //   Usage (Rz)
                0x09, 0x33,        //   Usage (Rx)
                0x09, 0x34,        //   Usage (Ry)
                0x15, 0x00,        //   Logical Minimum (0)
                0x26, 0xFF, 0x00,  //   Logical Maximum (255)
                0x75, 0x08,        //   Report Size (8)
                0x95, 0x06,        //   Report Count (6)
                0x81, 0x02,        //   Input (Data,Var,Abs)
                0x06, 0x00, 0xFF,  //   Usage Page (Vendor 0xFF00)
                0x09, 0x20,        //   Usage (0x20)
                0x95, 0x01,        //   Report Count (1)
                0x81, 0x02,        //   Input
                0x05, 0x01,        //   Usage Page (Generic Desktop)
                0x09, 0x39,        //   Usage (Hat switch)
                0x15, 0x00,        //   Logical Minimum (0)
                0x25, 0x07,        //   Logical Maximum (7)
                0x35, 0x00,        //   Physical Minimum (0)
                0x46, 0x3B, 0x01,  //   Physical Maximum (315)
                0x65, 0x14,        //   Unit (Degrees)
                0x75, 0x04,        //   Report Size (4)
                0x95, 0x01,        //   Report Count (1)
                0x81, 0x42,        //   Input (Data,Var,Abs,Null State)
                0x65, 0x00,        //   Unit (None)
                0x05, 0x09,        //   Usage Page (Button)
                0x19, 0x01,        //   Usage Minimum (1)
                0x29, 0x0F,        //   Usage Maximum (15)
                0x15, 0x00,        //   Logical Minimum (0)
                0x25, 0x01,        //   Logical Maximum (1)
                0x75, 0x01,        //   Report Size (1)
                0x95, 0x0F,        //   Report Count (15)
                0x81, 0x02,        //   Input
                0x06, 0x00, 0xFF,  //   Usage Page (Vendor 0xFF00)
                0x09, 0x21,        //   Usage (0x21)
                0x95, 0x0D,        //   Report Count (13)
                0x81, 0x02,        //   Input
                0x06, 0x00, 0xFF,  //   Usage Page (Vendor 0xFF00)
                0x09, 0x22,        //   Usage (0x22)
                0x15, 0x00,        //   Logical Minimum (0)
                0x26, 0xFF, 0x00,  //   Logical Maximum (255)
                0x75, 0x08,        //   Report Size (8)
                0x95, 0x34,        //   Report Count (52)
                0x81, 0x02,        //   Input
                0x85, 0x02,        //   Report ID (2)
                0x09, 0x23,        //   Usage (0x23)
                0x95, 0x2F,        //   Report Count (47)
                0x91, 0x02,        //   Output (Data,Var,Abs)
        };
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int v : head) b.write(v);
        for (int[] f : FEATURES) {
            b.write(0x85); b.write(f[0]);   // Report ID
            b.write(0x09); b.write(f[1]);   // Usage
            b.write(0x95); b.write(f[2]);   // Report Count
            b.write(0xB1); b.write(0x02);   // Feature (Data,Var,Abs)
        }
        b.write(0xC0);                      // End Collection
        return b.toByteArray();
    }

    public static byte[] deviceDescriptor() {
        return new byte[]{
                18, 0x01, 0x00, 0x02,          // bLength, DEVICE, bcdUSB 2.00
                0x00, 0x00, 0x00, 64,          // 类别由接口决定, bMaxPacketSize0
                (byte) VID, (byte) (VID >> 8), (byte) PID, (byte) (PID >> 8),
                (byte) BCD_DEVICE, (byte) (BCD_DEVICE >> 8),
                1, 2, 0,                       // iManufacturer, iProduct, iSerialNumber
                1                              // bNumConfigurations
        };
    }

    public static byte[] deviceQualifier() {
        return new byte[]{10, 0x06, 0x00, 0x02, 0x00, 0x00, 0x00, 64, 1, 0};
    }

    public static byte[] hidDescriptor() {
        int n = REPORT_DESCRIPTOR.length;
        return new byte[]{9, 0x21, 0x11, 0x01, 0x00, 1, 0x22, (byte) n, (byte) (n >> 8)};
    }

    public static byte[] configDescriptor() {
        byte[] hid = hidDescriptor();
        int total = 9 + 9 + hid.length + 7 + 7;
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] cfg = {9, 0x02, (byte) total, (byte) (total >> 8), 1, 1, 0, (byte) 0xC0, (byte) 0xFA};
        byte[] itf = {9, 0x04, 0, 0, 2, 0x03, 0x00, 0x00, 0};
        // 高速设备，bInterval 6 = 2^5 个微帧 = 4ms，和真手柄一样 250Hz
        byte[] epIn = {7, 0x05, (byte) EP_IN, 0x03, 64, 0, 6};
        byte[] epOut = {7, 0x05, (byte) EP_OUT, 0x03, 64, 0, 6};
        b.write(cfg, 0, cfg.length);
        b.write(itf, 0, itf.length);
        b.write(hid, 0, hid.length);
        b.write(epIn, 0, epIn.length);
        b.write(epOut, 0, epOut.length);
        return b.toByteArray();
    }

    /** 字符串描述符；没有的返回 null（回 STALL）。 */
    public static byte[] stringDescriptor(int index) {
        if (index == 0) return new byte[]{4, 0x03, 0x09, 0x04};
        String s;
        if (index == 1) s = "Sony Interactive Entertainment";
        else if (index == 2) s = "DualSense Wireless Controller";
        else return null;
        byte[] u = s.getBytes(Charset.forName("UTF-16LE"));
        byte[] d = new byte[2 + u.length];
        d[0] = (byte) d.length;
        d[1] = 0x03;
        System.arraycopy(u, 0, d, 2, u.length);
        return d;
    }

    /** 特性报告内容（第 0 字节是报告 ID）。不认识的 ID 返回 null。 */
    public static byte[] featureReport(int id, byte[] mac) {
        int size = -1;
        for (int[] f : FEATURES) if (f[0] == id) size = f[2] + 1;
        if (size < 0) return null;
        byte[] r = new byte[size];
        r[0] = (byte) id;
        switch (id) {
            case 0x05: { // 体感校准：陀螺仪 16 LSB/(度/秒)，加速度 8192 LSB/g，零偏 0
                int p = 1;
                for (int i = 0; i < 3; i++) { PadState.le16(r, p, 0); p += 2; }           // pitch/yaw/roll bias
                for (int i = 0; i < 3; i++) {                                               // plus/minus
                    PadState.le16(r, p, 540 * PadState.GYRO_PER_DPS); p += 2;
                    PadState.le16(r, p, -540 * PadState.GYRO_PER_DPS); p += 2;
                }
                PadState.le16(r, p, 540); p += 2;                                          // gyro speed plus
                PadState.le16(r, p, 540); p += 2;                                          // gyro speed minus
                for (int i = 0; i < 3; i++) {                                               // accel x/y/z plus/minus
                    PadState.le16(r, p, PadState.ACCEL_PER_G); p += 2;
                    PadState.le16(r, p, -PadState.ACCEL_PER_G); p += 2;
                }
                break;
            }
            case 0x09: // 配对信息：1..6 是手柄 MAC（低字节在前），Steam 拿它当序列号
                for (int i = 0; i < 6; i++) r[1 + i] = mac[5 - i];
                break;
            case 0x20: { // 固件信息
                byte[] date = "Jun 19 2024".getBytes(Charset.forName("US-ASCII"));
                byte[] time = "10:21:56".getBytes(Charset.forName("US-ASCII"));
                System.arraycopy(date, 0, r, 1, date.length);
                System.arraycopy(time, 0, r, 12, time.length);
                PadState.le16(r, 20, 0x0004);        // firmware type
                PadState.le16(r, 22, 0x0001);        // software series
                PadState.le32(r, 24, 0x00000614);    // hardware info
                PadState.le32(r, 28, 0x0110002A);    // firmware version
                PadState.le16(r, 44, 0x0630);        // update version（SDL 拿它判断新版震动）
                break;
            }
            default:
                break;
        }
        return r;
    }

    /** 电脑发来的输出报告 0x02 里有用的部分。 */
    public static final class Output {
        public boolean hasRumble, hasLightbar, hasPlayerLeds, hasMicLed;
        public int rumbleLeft, rumbleRight;     // 左 = 低频大马达，右 = 高频小马达
        public int red, green, blue;
        public int playerLeds;                  // 低 5 位
        public int micLed;                      // 0 灭 1 亮 2 呼吸
    }

    /** data[off] 应该是报告 ID 0x02。格式不对返回 null。 */
    public static Output parseOutput(byte[] d, int off, int len) {
        if (len < OUTPUT_REPORT_SIZE || d[off] != 0x02) return null;
        Output o = new Output();
        int f0 = d[off + 1] & 0xFF, f1 = d[off + 2] & 0xFF, f2 = d[off + 39] & 0xFF;
        if ((f0 & 0x03) != 0 || (f2 & 0x04) != 0) {
            o.hasRumble = true;
            o.rumbleRight = d[off + 3] & 0xFF;
            o.rumbleLeft = d[off + 4] & 0xFF;
        }
        if ((f1 & 0x01) != 0) {
            o.hasMicLed = true;
            o.micLed = d[off + 9] & 0xFF;
        }
        if ((f1 & 0x04) != 0) {
            o.hasLightbar = true;
            o.red = d[off + 45] & 0xFF;
            o.green = d[off + 46] & 0xFF;
            o.blue = d[off + 47] & 0xFF;
        }
        if ((f1 & 0x10) != 0) {
            o.hasPlayerLeds = true;
            o.playerLeds = d[off + 44] & 0x1F;
        }
        return o;
    }
}
