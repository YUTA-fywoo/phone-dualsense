package com.phonepad.ds.core;

/**
 * 手柄当前状态。界面线程写按键、摇杆和触摸板，传感器线程写体感，发送线程读出来拼成 DualSense 输入报告。
 * 所有读写都在 this 上同步。
 */
public final class PadState {

    // 内部按键位（不是 DualSense 报告里的位，拼报告时再换算）
    public static final int SQUARE = 1;
    public static final int CROSS = 1 << 1;
    public static final int CIRCLE = 1 << 2;
    public static final int TRIANGLE = 1 << 3;
    public static final int L1 = 1 << 4;
    public static final int R1 = 1 << 5;
    public static final int L2 = 1 << 6;
    public static final int R2 = 1 << 7;
    public static final int CREATE = 1 << 8;
    public static final int OPTIONS = 1 << 9;
    public static final int L3 = 1 << 10;
    public static final int R3 = 1 << 11;
    public static final int PS = 1 << 12;
    public static final int TOUCHPAD = 1 << 13;
    public static final int MUTE = 1 << 14;
    public static final int UP = 1 << 16;
    public static final int RIGHT = 1 << 17;
    public static final int DOWN = 1 << 18;
    public static final int LEFT = 1 << 19;

    /** 加速度 1g 对应的原始值，和校准报告里写的一致。 */
    public static final int ACCEL_PER_G = 8192;
    /** 陀螺仪 1 度/秒对应的原始值，和校准报告里写的一致。 */
    public static final int GYRO_PER_DPS = 16;

    private int buttons;
    private int lx = 128, ly = 128, rx = 128, ry = 128, l2, r2;
    private final boolean[] touchActive = new boolean[2];
    private final int[] touchId = new int[2];
    private final int[] touchX = new int[2];
    private final int[] touchY = new int[2];
    private short gyroX, gyroY, gyroZ;
    private short accelX, accelY = ACCEL_PER_G, accelZ;
    private int battery = 0x1A; // 高 4 位 1=充电中，低 4 位 0..10 电量

    private volatile Runnable changeListener;

    public void setChangeListener(Runnable r) {
        changeListener = r;
    }

    private void changed() {
        Runnable r = changeListener;
        if (r != null) r.run();
    }

    /** 界面一次性写入所有触摸来的输入。 */
    public void setTouchInput(int buttons, int lx, int ly, int rx, int ry, int l2, int r2,
                              boolean[] tActive, int[] tId, int[] tX, int[] tY) {
        synchronized (this) {
            this.buttons = buttons;
            this.lx = clamp8(lx);
            this.ly = clamp8(ly);
            this.rx = clamp8(rx);
            this.ry = clamp8(ry);
            this.l2 = clamp8(l2);
            this.r2 = clamp8(r2);
            for (int i = 0; i < 2; i++) {
                touchActive[i] = tActive[i];
                touchId[i] = tId[i] & 0x7F;
                touchX[i] = Math.max(0, Math.min(1919, tX[i]));
                touchY[i] = Math.max(0, Math.min(1079, tY[i]));
            }
        }
        changed();
    }

    /** 体感数据，已经换算成 DualSense 坐标系和原始单位。 */
    public void setMotion(int gx, int gy, int gz, int ax, int ay, int az) {
        synchronized (this) {
            gyroX = clamp16(gx);
            gyroY = clamp16(gy);
            gyroZ = clamp16(gz);
            accelX = clamp16(ax);
            accelY = clamp16(ay);
            accelZ = clamp16(az);
        }
        changed();
    }

    /** 关掉体感时，报一个平放静止的手柄。 */
    public void resetMotion() {
        setMotion(0, 0, 0, 0, ACCEL_PER_G, 0);
    }

    public void setBattery(int percent, boolean charging, boolean full) {
        int level = Math.max(0, Math.min(10, (percent + 5) / 10));
        int status = full ? 2 : (charging ? 1 : 0);
        synchronized (this) {
            battery = (status << 4) | level;
        }
    }

    public synchronized int getButtons() {
        return buttons;
    }

    /**
     * 按 DualSense USB 输入报告 0x01 的格式写 64 字节（含报告 ID）。
     * 偏移参考 Linux hid-playstation 和 SDL 的 PS5 驱动。
     */
    public synchronized void writeInputReport(byte[] o, int counter, int packetSeq, int sensorTimestamp) {
        java.util.Arrays.fill(o, 0, 64, (byte) 0);
        o[0] = 0x01;
        o[1] = (byte) lx;
        o[2] = (byte) ly;
        o[3] = (byte) rx;
        o[4] = (byte) ry;
        o[5] = (byte) l2;
        o[6] = (byte) r2;
        o[7] = (byte) counter;

        int b = buttons;
        int b0 = hat(b);
        if ((b & SQUARE) != 0) b0 |= 0x10;
        if ((b & CROSS) != 0) b0 |= 0x20;
        if ((b & CIRCLE) != 0) b0 |= 0x40;
        if ((b & TRIANGLE) != 0) b0 |= 0x80;
        int b1 = 0;
        if ((b & L1) != 0) b1 |= 0x01;
        if ((b & R1) != 0) b1 |= 0x02;
        if ((b & L2) != 0) b1 |= 0x04;
        if ((b & R2) != 0) b1 |= 0x08;
        if ((b & CREATE) != 0) b1 |= 0x10;
        if ((b & OPTIONS) != 0) b1 |= 0x20;
        if ((b & L3) != 0) b1 |= 0x40;
        if ((b & R3) != 0) b1 |= 0x80;
        int b2 = 0;
        if ((b & PS) != 0) b2 |= 0x01;
        if ((b & TOUCHPAD) != 0) b2 |= 0x02;
        if ((b & MUTE) != 0) b2 |= 0x04;
        o[8] = (byte) b0;
        o[9] = (byte) b1;
        o[10] = (byte) b2;
        o[11] = 0;
        le32(o, 12, packetSeq);

        le16(o, 16, gyroX);
        le16(o, 18, gyroY);
        le16(o, 20, gyroZ);
        le16(o, 22, accelX);
        le16(o, 24, accelY);
        le16(o, 26, accelZ);
        le32(o, 28, sensorTimestamp);

        for (int i = 0; i < 2; i++) {
            int p = 33 + i * 4;
            int x = touchX[i], y = touchY[i];
            o[p] = (byte) ((touchActive[i] ? 0x00 : 0x80) | (touchId[i] & 0x7F));
            o[p + 1] = (byte) (x & 0xFF);
            o[p + 2] = (byte) (((x >> 8) & 0x0F) | ((y & 0x0F) << 4));
            o[p + 3] = (byte) ((y >> 4) & 0xFF);
        }

        o[53] = (byte) battery;
        o[54] = 0x08; // SDL 注释：0x08 = 走 USB
    }

    private static int hat(int b) {
        boolean u = (b & UP) != 0, d = (b & DOWN) != 0, l = (b & LEFT) != 0, r = (b & RIGHT) != 0;
        if (u && d) { u = false; d = false; }
        if (l && r) { l = false; r = false; }
        if (u) return r ? 1 : (l ? 7 : 0);
        if (d) return r ? 3 : (l ? 5 : 4);
        if (r) return 2;
        if (l) return 6;
        return 8;
    }

    static void le16(byte[] o, int p, int v) {
        o[p] = (byte) v;
        o[p + 1] = (byte) (v >> 8);
    }

    static void le32(byte[] o, int p, int v) {
        o[p] = (byte) v;
        o[p + 1] = (byte) (v >> 8);
        o[p + 2] = (byte) (v >> 16);
        o[p + 3] = (byte) (v >> 24);
    }

    private static int clamp8(int v) {
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    private static short clamp16(int v) {
        return (short) (v < -32768 ? -32768 : (v > 32767 ? 32767 : v));
    }
}
