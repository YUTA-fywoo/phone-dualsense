import com.phonepad.ds.core.DualSense;
import com.phonepad.ds.core.PadState;
import com.phonepad.ds.core.UsbIpServer;

/**
 * 不用手机，在电脑上直接跑同一份 USB/IP 服务端（127.0.0.1:32400），输入自动动起来。
 * 用来在装好 usbip-win2 的电脑上验证 Windows / Steam 是否认成 DualSense：
 *   java -cp build/test PcHarness
 *   usbip --tcp-port 32400 attach -r 127.0.0.1 -b 1-1
 */
public class PcHarness {
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 32400;
        final PadState st = new PadState();
        st.resetMotion();
        UsbIpServer server = new UsbIpServer(st, new byte[]{0x02, 0x11, 0x22, 0x33, 0x44, 0x55}, new UsbIpServer.Listener() {
            public void onConnectionChanged(boolean c) { System.out.println(c ? "电脑已接入" : "电脑已断开"); }
            public void onOutput(DualSense.Output o) {
                StringBuilder sb = new StringBuilder("输出报告:");
                if (o.hasRumble) sb.append(" 震动 左=").append(o.rumbleLeft).append(" 右=").append(o.rumbleRight);
                if (o.hasLightbar) sb.append(String.format(" 灯条 #%02X%02X%02X", o.red, o.green, o.blue));
                if (o.hasPlayerLeds) sb.append(" 玩家灯=").append(Integer.toBinaryString(o.playerLeds));
                if (o.hasMicLed) sb.append(" 麦克风灯=").append(o.micLed);
                System.out.println(sb);
            }
            public void onServerError(String m) { System.out.println("错误: " + m); }
        }, port);
        server.start();
        System.out.println("USB/IP 服务端在 127.0.0.1:" + port + "，busid " + UsbIpServer.BUS_ID + "，Ctrl+C 结束");

        int[] seq = {PadState.CROSS, PadState.CIRCLE, PadState.SQUARE, PadState.TRIANGLE, PadState.L1, PadState.R1,
                PadState.L2, PadState.R2, PadState.CREATE, PadState.OPTIONS, PadState.L3, PadState.R3, PadState.PS,
                PadState.TOUCHPAD, PadState.MUTE, PadState.UP, PadState.RIGHT, PadState.DOWN, PadState.LEFT};
        long t0 = System.currentTimeMillis();
        boolean[] ta = new boolean[2];
        int[] tid = new int[2], tx = new int[2], ty = new int[2];
        while (true) {
            double t = (System.currentTimeMillis() - t0) / 1000.0;
            int b = seq[(int) (t / 0.6) % seq.length];
            int l2 = (b & PadState.L2) != 0 ? 255 : 0, r2 = (b & PadState.R2) != 0 ? 255 : 0;
            ta[0] = true;
            tid[0] = 1;
            tx[0] = (int) (960 + 800 * Math.cos(t));
            ty[0] = (int) (540 + 400 * Math.sin(t));
            st.setTouchInput(b,
                    (int) (128 + 127 * Math.cos(t * 2)), (int) (128 + 127 * Math.sin(t * 2)),
                    (int) (128 + 127 * Math.sin(t * 3)), 128, l2, r2, ta, tid, tx, ty);
            Thread.sleep(16);
        }
    }
}
