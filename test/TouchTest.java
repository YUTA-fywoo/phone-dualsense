import com.phonepad.ds.core.PadLayout;
import com.phonepad.ds.core.PadState;
import com.phonepad.ds.core.TouchLogic;

/** 在电脑上模拟触屏，按 Mi 11 横屏 2400×1080 检查触控判定。 */
public class TouchTest {

    static int failures = 0;
    static int checks = 0;

    static void check(boolean ok, String what) {
        checks++;
        if (!ok) failures++;
        System.out.println((ok ? "  通过 " : "  失败 ") + what);
    }

    static PadLayout L;
    static TouchLogic T;
    static long now;

    static void fresh() {
        L = new PadLayout();
        L.compute(2400, 1080);
        T = new TouchLogic(L);
        now = 10_000;
    }

    static void down(int id, float x, float y) {
        T.down(id, x, y, now);
        T.update(now);
    }

    static void move(int id, float x, float y) {
        T.move(id, x, y);
        T.update(now);
    }

    static void up(int id) {
        T.up(id, now);
        T.update(now);
    }

    static void at(long t) {
        now = t;
        T.update(now);
    }

    static boolean has(int bit) {
        return (T.buttons & bit) == bit;
    }

    static float lx() {
        return T.sticks[0].x;
    }

    static String f(float v) {
        return String.format("%.2f", v);
    }

    public static void main(String[] args) {
        System.out.println("[1] 原来的死区现在都能抓到左摇杆");
        float[][] pts = {{500, 950}, {450, 1040}, {1150, 1030}, {150, 1000}, {650, 380}};
        for (float[] p : pts) {
            fresh();
            down(1, p[0], p[1]);
            move(1, p[0] + 100, p[1]);
            check(lx() > 0.6f && T.takeHaptic() != TouchLogic.HAPTIC_NONE,
                    "(" + (int) p[0] + "," + (int) p[1] + ") 向右滑 100 像素 lx=" + f(lx()));
        }

        System.out.println("[2] 整屏扫描：除边缘防误触带外每个落点都有归属");
        int total = 0, assigned = 0, stickLowerLeft = 0, lowerLeft = 0;
        for (int x = 0; x < 2400; x += 20) {
            for (int y = 0; y < 1080; y += 20) {
                fresh();
                down(1, x, y);
                boolean guarded = x < L.edgeX || x > L.W - L.edgeX || y < L.edgeY || y > L.H - L.edgeY;
                if (guarded) continue;
                total++;
                if (T.pointers().size() == 1) assigned++;
                if (x < 1200 && y > 540) {
                    lowerLeft++;
                    if (T.pointers().get(0).kind == TouchLogic.K_STICK) stickLowerLeft++;
                }
            }
        }
        check(assigned == total, "有归属 " + assigned + "/" + total);
        check(stickLowerLeft * 100 / lowerLeft >= 60, "左下四分之一里摇杆区占 " + stickLowerLeft * 100 / lowerLeft + "%");

        System.out.println("[3] 手掌先压着，拇指后来直接接管");
        fresh();
        down(9, 150, 1030);   // 手掌
        down(1, 700, 850);    // 拇指
        move(1, 780, 850);
        check(Math.abs(lx() - 80 / L.lReach) < 0.01f, "拇指推 80 像素 lx=" + f(lx()));
        move(9, 155, 1032);
        check(Math.abs(lx() - 80 / L.lReach) < 0.01f, "手掌轻微挪动不影响 lx=" + f(lx()));

        System.out.println("[4] 拇指推着时后来的手掌不抢");
        fresh();
        down(1, 700, 850);
        move(1, 800, 850);
        float before = lx();
        down(9, 150, 1030);
        move(9, 190, 1030);
        check(Math.abs(lx() - before) < 0.001f && before > 0.6f, "lx 保持 " + f(lx()));

        System.out.println("[5] 拇指没推时被手掌抢走，一动就拿回来");
        fresh();
        down(1, 700, 850);
        down(9, 150, 1030);
        check(T.sticks[0].owner.id == 9, "手掌后落下、拇指没推，手掌拿到摇杆");
        move(1, 760, 850);
        check(T.sticks[0].owner.id == 1 && lx() > 0.35f, "拇指移动 60 像素后拿回 lx=" + f(lx()));

        System.out.println("[6] 拿着摇杆的手指抬起，交给剩下的手指且从中心算起");
        fresh();
        down(9, 150, 1030);
        down(1, 700, 850);
        move(1, 800, 850);
        up(1);
        check(T.sticks[0].owner != null && T.sticks[0].owner.id == 9 && lx() == 0, "交给手掌，lx=0");

        System.out.println("[7] 两根手指在同一摇杆，只认拿着的那根");
        fresh();
        down(1, 700, 850);
        move(1, 820, 850);
        down(2, 400, 900);
        move(2, 300, 900);
        check(lx() > 0.7f, "第二根手指往左也不影响 lx=" + f(lx()));

        System.out.println("[8] 拉出行程时中心跟着走，反向立刻有反应");
        fresh();
        down(1, 600, 900);
        move(1, 900, 900);
        check(lx() > 0.999f, "推满 lx=" + f(lx()));
        move(1, 700, 900);
        check(lx() < -0.2f, "往回 200 像素就变成向左 lx=" + f(lx()));

        System.out.println("[9] 边缘防误触");
        fresh();
        down(1, 500, 20);
        move(1, 600, 30);
        down(2, 1800, 1065);
        move(2, 1700, 1065);
        check(T.pointers().isEmpty() && T.buttons == 0 && lx() == 0 && T.sticks[1].x == 0, "上下边缘落下的手指全部忽略");

        System.out.println("[10] △○×□ 扇区判定");
        fresh();
        float fx = L.faceX, fy = L.faceY, o = L.faceOff;
        down(1, fx, fy + o);
        check(T.buttons == PadState.CROSS && T.takeHaptic() == TouchLogic.HAPTIC_PRESS, "按 × 并有按键触感");
        move(1, fx + o, fy);
        check(T.buttons == PadState.CIRCLE, "滑到 ○ 只剩 ○");
        up(1);
        down(1, fx + o * 0.7071f, fy + o * 0.7071f);
        check(T.buttons == (PadState.CIRCLE | PadState.CROSS), "压在 ○ 和 × 正中间两个都按");
        up(1);
        down(1, (float) (fx + o * Math.cos(Math.toRadians(25))), (float) (fy + o * Math.sin(Math.toRadians(25))));
        check(T.buttons == PadState.CIRCLE, "偏离中线 20° 只按 ○");
        up(1);
        down(1, fx, fy);
        check(T.buttons == 0, "按在四键正中心什么都不按");
        up(1);
        down(1, fx - 200, fy);
        check(T.buttons == PadState.SQUARE, "□ 左侧 47 像素外也算 □");
        up(1);
        down(1, fx, fy - o);
        down(2, fx, fy + o);
        check(T.buttons == (PadState.TRIANGLE | PadState.CROSS), "两根手指分别按 △ 和 × 同时生效");

        System.out.println("[11] △○×□ 边界迟滞");
        fresh();
        float r = o * 0.4f;
        down(1, (float) (fx + r * Math.cos(Math.toRadians(42))), (float) (fy + r * Math.sin(Math.toRadians(42))));
        check(T.buttons == PadState.CIRCLE, "靠近中心 42° 是 ○");
        move(1, (float) (fx + r * Math.cos(Math.toRadians(48))), (float) (fy + r * Math.sin(Math.toRadians(48))));
        check(T.buttons == PadState.CIRCLE, "抖到 48° 仍是 ○");
        move(1, (float) (fx + r * Math.cos(Math.toRadians(53))), (float) (fy + r * Math.sin(Math.toRadians(53))));
        check(T.buttons == PadState.CROSS, "到 53° 才换成 ×");

        System.out.println("[12] 十字键");
        fresh();
        float dx = L.dpadX, dy = L.dpadY;
        down(1, dx, dy - 150);
        check(T.buttons == PadState.UP, "上");
        T.takeHaptic();
        move(1, (float) (dx + 150 * Math.cos(Math.toRadians(315))), (float) (dy + 150 * Math.sin(Math.toRadians(315))));
        check(T.buttons == (PadState.UP | PadState.RIGHT) && T.takeHaptic() == TouchLogic.HAPTIC_PRESS, "滑到右上，多按下的方向有触感");
        up(1);
        down(1, (float) (dx + 150 * Math.cos(Math.toRadians(28))), (float) (dy + 150 * Math.sin(Math.toRadians(28))));
        check(T.buttons == PadState.RIGHT, "28° 是右");
        move(1, (float) (dx + 150 * Math.cos(Math.toRadians(33))), (float) (dy + 150 * Math.sin(Math.toRadians(33))));
        check(T.buttons == PadState.RIGHT, "抖到 33° 仍是右");
        move(1, (float) (dx + 150 * Math.cos(Math.toRadians(40))), (float) (dy + 150 * Math.sin(Math.toRadians(40))));
        check(T.buttons == (PadState.RIGHT | PadState.DOWN), "40° 变成右下");
        up(1);
        down(1, dx, dy);
        check(T.buttons == 0, "正中心不按");
        up(1);
        down(1, dx + 240, dy + 30);
        check(T.pointers().get(0).kind == TouchLogic.K_STICK, "十字键朝摇杆一侧外沿归摇杆");
        up(1);
        down(1, dx - 240, dy);
        check(T.buttons == PadState.LEFT, "十字键左外沿（靠屏幕边）仍算左");

        System.out.println("[13] PS 键按住才生效");
        fresh();
        down(1, L.psX, L.psY);
        at(now + 100);
        check(!has(PadState.PS), "按 0.1 秒不生效");
        up(1);
        at(now + 400);
        check(!has(PadState.PS), "松开后也没按过");
        down(1, L.psX, L.psY);
        check(T.nextTimer(now) == now + TouchLogic.HOLD_GUARD_MS, "安排了 0.25 秒后的计时");
        at(now + 260);
        check(has(PadState.PS), "按住 0.26 秒生效");
        up(1);
        check(!has(PadState.PS), "松开");

        System.out.println("[14] 双击摇杆区并按住 = L3");
        fresh();
        down(1, 700, 850);
        at(now + 100);
        up(1);
        at(now + 150);
        down(1, 705, 852);
        check(has(PadState.L3), "轻点后 0.15 秒再按住 → L3");
        up(1);
        check(!has(PadState.L3), "松开 L3");
        at(now + 1000);
        down(1, 700, 850);
        at(now + 500);
        move(1, 800, 850);
        up(1);
        at(now + 100);
        down(1, 700, 850);
        check(!has(PadState.L3), "正常推完抬起再放下不算 L3");
        up(1);
        at(now + 1000);
        down(1, 700, 850);
        at(now + 150);
        move(1, 760, 850);
        up(1);
        at(now + 100);
        down(1, 760, 850);
        check(!has(PadState.L3), "短促但推动过的一下也不算");

        System.out.println("[15] 触摸板");
        fresh();
        down(1, 1200, 300);
        check(T.tActive[0] && T.tX[0] == 960, "触点在触摸板中间 x=" + T.tX[0]);
        at(now + 100);
        up(1);
        check(has(PadState.TOUCHPAD), "轻点按下触摸板");
        at(now + 120);
        check(!has(PadState.TOUCHPAD), "0.11 秒后松开");
        down(1, 1200, 300);
        at(now + 510);
        check(has(PadState.TOUCHPAD), "按住不动 0.5 秒 = 按下");

        System.out.println("[16] 触感事件");
        fresh();
        down(1, 700, 850);
        check(T.takeHaptic() == TouchLogic.HAPTIC_TICK, "抓到摇杆轻震一下");
        move(1, 760, 850);
        check(T.takeHaptic() == TouchLogic.HAPTIC_NONE, "推一半不震");
        move(1, 860, 850);
        check(T.takeHaptic() == TouchLogic.HAPTIC_TICK, "推到底轻震一下");
        move(1, 870, 850);
        check(T.takeHaptic() == TouchLogic.HAPTIC_NONE, "保持推满不重复震");

        System.out.println("[17] 设置键长按");
        fresh();
        down(1, L.gear.cx(), L.gear.cy());
        at(now + 300);
        up(1);
        check(!T.takeGearRequest(), "按 0.3 秒不打开");
        down(1, L.gear.cx(), L.gear.cy());
        at(now + 610);
        check(T.takeGearRequest() && !T.takeGearRequest(), "按住 0.6 秒打开一次");

        System.out.println("[18] 肩键");
        fresh();
        down(1, 264, 100);
        check(T.buttons == PadState.L2 && T.l2() == 255, "L2，扳机值 255");
        move(1, 264, 250);
        check(T.buttons == PadState.L1, "往下滑到 L1");
        up(1);
        down(1, 264, 40);
        check(T.buttons == PadState.L2, "贴近上沿仍是 L2");
        down(2, 2136, 250);
        check(T.buttons == (PadState.L2 | PadState.R1), "同时按 R1");

        System.out.println("[19] 音量键合并");
        fresh();
        T.setExternal(PadState.R2);
        check(has(PadState.R2) && T.r2() == 255, "R2 来自音量键");
        down(1, fx, fy + o);
        check(T.buttons == (PadState.R2 | PadState.CROSS), "和触屏按键合并");
        check(T.takeHaptic() == TouchLogic.HAPTIC_PRESS, "触屏按键有触感");
        T.setExternal(0);
        check(T.buttons == PadState.CROSS, "松开音量键");

        System.out.println("[20] 布局调整");
        fresh();
        float oldX = L.dpadX;
        L.moveGroup(PadLayout.G_DPAD, 100, 0);
        L.setScale(PadLayout.G_L_STICK, 1.3f);
        String saved = L.serialize();
        PadLayout L2 = new PadLayout();
        L2.deserialize(saved);
        L2.compute(2400, 1080);
        check(Math.abs(L2.dpadX - (oldX + 100)) < 0.5f && Math.abs(L2.lReach - 0.14f * 1080 * 1.3f) < 0.5f,
                "保存再读回：十字键 x=" + f(L2.dpadX) + " 左摇杆行程=" + f(L2.lReach));
        L.moveGroup(PadLayout.G_FACE, 1000, 0);
        check(L.faceX == 2400, "拖出屏幕时中心停在边上");
        L.moveGroup(PadLayout.G_FACE, -100, 0);
        check(Math.abs(L.faceX - 2300) < 0.5f, "往回拖立刻跟着动");
        PadLayout bad = new PadLayout();
        bad.deserialize("垃圾数据");
        check(bad.isDefault(), "格式不对时保持默认");
        check(L.groupAt(L.dpadX, L.dpadY) == PadLayout.G_DPAD && L.groupAt(L.lsX, L.lsY) == PadLayout.G_L_STICK,
                "编辑时能点中十字键和摇杆");

        System.out.println();
        System.out.println(failures == 0 ? "全部通过：" + checks + " 项" : "失败 " + failures + " / " + checks + " 项");
        System.exit(failures == 0 ? 0 : 1);
    }
}
