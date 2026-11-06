package com.phonepad.ds.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 触屏判定：手指落下时归到某个部件，之后按位置算出按键、摇杆和触摸板。纯 Java，电脑上测。
 *
 * 为了盲操：
 * - 左右两半里没有被别的部件占用的地方都是摇杆区，手指落在哪里哪里就是摇杆中心（浮动摇杆）。
 * - 每个摇杆只认一根手指。后来的手指只有在原来那根基本没推动时才接管，
 *   所以手掌或握持的手指压在屏幕上不会把摇杆“卡住”。
 * - 上下边缘（曲面屏）一窄条里落下的手指直接忽略。
 * - △○×□ 和十字键按扇区判定，整个扇区都算，边界带迟滞，避免抖动来回切换。
 * - PS 和麦克风键要按住 0.25 秒才生效，碰一下不算。
 * - 每次有新按键按下、摇杆被抓住、摇杆推到底，都给一次触感事件。
 */
public final class TouchLogic {

    public static final int HAPTIC_NONE = 0, HAPTIC_TICK = 1, HAPTIC_PRESS = 2;

    public static final long HOLD_GUARD_MS = 250, GEAR_HOLD_MS = 600;
    public static final long PAD_LONG_MS = 500, PAD_TAP_MS = 220, PAD_PULSE_MS = 110;
    public static final long DOUBLE_TAP_MS = 300, TAP_MAX_MS = 220;
    /** 原来的手指推动不到这个比例时，后来的手指可以接管摇杆。 */
    public static final float TAKEOVER_MAX_DEFLECT = 0.25f;
    /** 没拿到摇杆的手指移动超过这个距离（单位 s）就尝试接管。 */
    public static final float TAKEOVER_MOVE = 0.035f;

    public static final int K_SHOULDER_L = 1, K_SHOULDER_R = 2, K_FACE = 3, K_DPAD = 4, K_STICK = 5,
            K_TOUCHPAD = 6, K_SINGLE = 7, K_HOLD = 8, K_GEAR = 9;

    private static final int[] FACE_ORDER = {PadState.CIRCLE, PadState.CROSS, PadState.SQUARE, PadState.TRIANGLE};

    public static final class Ptr {
        public int id, kind, bit, side;
        public float x, y, downX, downY;
        /** 摇杆的浮动原点。 */
        public float ox, oy;
        public float maxTravel;
        public long downTime;
        public boolean moved, longPress, stickClick, fired;
        public int slot = -1, touchId;
        /** 这根手指上一轮按下的键，用于边界迟滞。 */
        public int bits;
    }

    public static final class Stick {
        public Ptr owner;
        /** 输出 -1..1。 */
        public float x, y;
        public boolean click;
        boolean atEdge;
        long lastTapUp = Long.MIN_VALUE / 2;
        float lastTapX, lastTapY;
    }

    private final PadLayout L;
    private final List<Ptr> ptrs = new ArrayList<>();
    private final List<Ptr> ptrsView = Collections.unmodifiableList(ptrs);
    public final Stick[] sticks = {new Stick(), new Stick()};

    // ---- 输出 ----
    public int buttons;
    public final boolean[] tActive = new boolean[2];
    public final int[] tId = new int[2], tX = new int[2], tY = new int[2];

    private int external;
    private int lastTouchButtons;
    private long padPulseUntil;
    private int touchCounter;
    private int haptic;
    private boolean gearRequest;

    public TouchLogic(PadLayout layout) {
        L = layout;
    }

    public List<Ptr> pointers() {
        return ptrsView;
    }

    /** 实体键（音量键）映射过来的按键，和触屏的合并输出。 */
    public void setExternal(int bits) {
        external = bits;
        buttons = lastTouchButtons | external;
    }

    public int l2() {
        return (buttons & PadState.L2) != 0 ? 255 : 0;
    }

    public int r2() {
        return (buttons & PadState.R2) != 0 ? 255 : 0;
    }

    /** 取出并清掉待发的触感事件（同一轮里取最强的一种）。 */
    public int takeHaptic() {
        int h = haptic;
        haptic = HAPTIC_NONE;
        return h;
    }

    public boolean takeGearRequest() {
        boolean g = gearRequest;
        gearRequest = false;
        return g;
    }

    private void haptic(int kind) {
        if (kind > haptic) haptic = kind;
    }

    // ================= 输入 =================

    public void down(int id, float x, float y, long now) {
        Ptr old = find(id);
        if (old != null) up(id, now);
        if (x < L.edgeX || x > L.W - L.edgeX || y < L.edgeY || y > L.H - L.edgeY) return;

        Ptr p = new Ptr();
        p.id = id;
        p.x = p.downX = p.ox = x;
        p.y = p.downY = p.oy = y;
        p.downTime = now;

        if (L.gear.containsGrown(x, y, 0.6f)) {
            p.kind = K_GEAR;
        } else if (L.inShoulderZone(x, y, L.l2, L.l1, 1)) {
            p.kind = K_SHOULDER_L;
        } else if (L.inShoulderZone(x, y, L.r2, L.r1, 1)) {
            p.kind = K_SHOULDER_R;
        } else if (L.create.containsGrown(x, y, 0.7f)) {
            p.kind = K_SINGLE;
            p.bit = PadState.CREATE;
        } else if (L.options.containsGrown(x, y, 0.7f)) {
            p.kind = K_SINGLE;
            p.bit = PadState.OPTIONS;
        } else if (L.pad.contains(x, y)) {
            int slot = freeSlot();
            if (slot < 0) return;
            p.kind = K_TOUCHPAD;
            p.slot = slot;
            p.touchId = (touchCounter++) & 0x7F;
        } else if (PadLayout.dist(x, y, L.psX, L.psY) < L.psR * 1.5f) {
            p.kind = K_HOLD;
            p.bit = PadState.PS;
        } else if (L.mic.containsGrown(x, y, 0.8f)) {
            p.kind = K_HOLD;
            p.bit = PadState.MUTE;
        } else if (L.inDpadZone(x, y)) {
            p.kind = K_DPAD;
        } else if (L.inFaceZone(x, y)) {
            p.kind = K_FACE;
        } else {
            p.kind = K_STICK;
            p.side = L.stickSide(x);
        }
        ptrs.add(p);
        if (p.kind == K_STICK) stickDown(p, now);
    }

    public void move(int id, float x, float y) {
        Ptr p = find(id);
        if (p == null) return;
        p.x = x;
        p.y = y;
        p.maxTravel = Math.max(p.maxTravel, PadLayout.dist(x, y, p.downX, p.downY));
        if (p.kind == K_TOUCHPAD && p.maxTravel > L.pad.w() * 0.035f) p.moved = true;
        if (p.kind == K_STICK) stickMove(p);
    }

    public void up(int id, long now) {
        Ptr p = find(id);
        if (p == null) return;
        ptrs.remove(p);
        if (p.kind == K_STICK) {
            stickUp(p, now);
        } else if (p.kind == K_TOUCHPAD && !p.moved && !p.longPress && now - p.downTime < PAD_TAP_MS) {
            padPulseUntil = now + PAD_PULSE_MS; // 轻点 = 按一下触摸板
        }
    }

    public void cancelAll() {
        ptrs.clear();
        for (Stick st : sticks) {
            st.owner = null;
            st.atEdge = false;
            st.lastTapUp = Long.MIN_VALUE / 2;
        }
        padPulseUntil = 0;
    }

    private Ptr find(int id) {
        for (int i = 0; i < ptrs.size(); i++) {
            if (ptrs.get(i).id == id) return ptrs.get(i);
        }
        return null;
    }

    private int freeSlot() {
        boolean u0 = false, u1 = false;
        for (Ptr q : ptrs) {
            if (q.kind == K_TOUCHPAD) {
                if (q.slot == 0) u0 = true;
                if (q.slot == 1) u1 = true;
            }
        }
        return !u0 ? 0 : (!u1 ? 1 : -1);
    }

    // ================= 摇杆 =================

    private void stickDown(Ptr p, long now) {
        Stick st = sticks[p.side];
        // 双击并按住 = L3/R3。前一下必须是短促的轻点，平时抬起再放下拇指不会误按
        p.stickClick = now - st.lastTapUp < DOUBLE_TAP_MS
                && PadLayout.dist(p.x, p.y, st.lastTapX, st.lastTapY) < L.stickRadius(p.side) * 1.2f;
        if (p.stickClick) st.lastTapUp = Long.MIN_VALUE / 2;
        if (st.owner == null || magnitude(st.owner) < TAKEOVER_MAX_DEFLECT) {
            st.owner = p;
            haptic(HAPTIC_TICK);
        }
    }

    private void stickMove(Ptr p) {
        float reach = L.stickReach(p.side);
        float ddx = p.x - p.ox, ddy = p.y - p.oy, d = (float) Math.hypot(ddx, ddy);
        boolean tryTakeover = d > TAKEOVER_MOVE * L.s;
        if (d > reach) { // 手指拉出行程时原点跟着走，反向推不用先回中
            p.ox += ddx * (d - reach) / d;
            p.oy += ddy * (d - reach) / d;
        }
        Stick st = sticks[p.side];
        if (st.owner != p && tryTakeover && (st.owner == null || magnitude(st.owner) < TAKEOVER_MAX_DEFLECT)) {
            st.owner = p;
            haptic(HAPTIC_TICK);
        }
    }

    private void stickUp(Ptr p, long now) {
        Stick st = sticks[p.side];
        if (st.owner != p) return;
        st.owner = null;
        float reach = L.stickReach(p.side);
        if (now - p.downTime < TAP_MAX_MS && p.maxTravel < reach * 0.25f && !p.stickClick) {
            st.lastTapUp = now;
            st.lastTapX = p.x;
            st.lastTapY = p.y;
        } else {
            st.lastTapUp = Long.MIN_VALUE / 2;
        }
        // 这一侧还有别的手指（比如压着的手掌），交给最后按下的那根，从它现在的位置重新算中心
        Ptr next = null;
        for (Ptr q : ptrs) {
            if (q.kind == K_STICK && q.side == p.side && (next == null || q.downTime >= next.downTime)) next = q;
        }
        if (next != null) {
            st.owner = next;
            next.ox = next.x;
            next.oy = next.y;
        }
    }

    private float magnitude(Ptr p) {
        float reach = L.stickReach(p.side);
        return Math.min(1f, (float) Math.hypot(p.x - p.ox, p.y - p.oy) / reach);
    }

    // ================= 计算输出 =================

    /** 根据所有手指重新算一遍。now 用于长按、按住生效这些计时。 */
    public void update(long now) {
        int b = 0;
        tActive[0] = tActive[1] = false;
        for (Ptr p : ptrs) {
            switch (p.kind) {
                case K_SHOULDER_L:
                    p.bits = shoulderBits(p, L.l2, L.l1, PadState.L2, PadState.L1);
                    b |= p.bits;
                    break;
                case K_SHOULDER_R:
                    p.bits = shoulderBits(p, L.r2, L.r1, PadState.R2, PadState.R1);
                    b |= p.bits;
                    break;
                case K_FACE:
                    p.bits = faceBits(p);
                    b |= p.bits;
                    break;
                case K_DPAD:
                    p.bits = dpadBits(p);
                    b |= p.bits;
                    break;
                case K_TOUCHPAD:
                    if (!p.moved && !p.longPress && now - p.downTime >= PAD_LONG_MS) p.longPress = true; // 按住不动 = 按下
                    tActive[p.slot] = true;
                    tId[p.slot] = p.touchId;
                    tX[p.slot] = Math.round((p.x - L.pad.l) / L.pad.w() * 1919f);
                    tY[p.slot] = Math.round((p.y - L.pad.t) / L.pad.h() * 1079f);
                    if (p.longPress) b |= PadState.TOUCHPAD;
                    break;
                case K_SINGLE:
                    if (singleBox(p).containsGrown(p.x, p.y, 1.2f)) b |= p.bit;
                    break;
                case K_HOLD:
                    if (now - p.downTime >= HOLD_GUARD_MS && holdInside(p)) b |= p.bit;
                    break;
                case K_GEAR:
                    if (!p.fired && now - p.downTime >= GEAR_HOLD_MS && L.gear.containsGrown(p.x, p.y, 1.2f)) {
                        p.fired = true;
                        gearRequest = true;
                    }
                    break;
                default:
                    break;
            }
        }
        for (int side = 0; side < 2; side++) {
            Stick st = sticks[side];
            if (st.owner == null) {
                st.x = st.y = 0;
                st.click = false;
                st.atEdge = false;
                continue;
            }
            float reach = L.stickReach(side);
            float vx = (st.owner.x - st.owner.ox) / reach, vy = (st.owner.y - st.owner.oy) / reach;
            float mag = (float) Math.hypot(vx, vy);
            if (mag > 1) {
                vx /= mag;
                vy /= mag;
                mag = 1;
            }
            st.x = vx;
            st.y = vy;
            st.click = st.owner.stickClick;
            if (st.click) b |= side == 0 ? PadState.L3 : PadState.R3;
            if (!st.atEdge && mag >= 0.97f) {
                st.atEdge = true;
                haptic(HAPTIC_TICK); // 推到底
            } else if (st.atEdge && mag < 0.85f) {
                st.atEdge = false;
            }
        }
        if (now < padPulseUntil) b |= PadState.TOUCHPAD;
        if ((b & ~lastTouchButtons) != 0) haptic(HAPTIC_PRESS);
        lastTouchButtons = b;
        buttons = b | external;
    }

    /** 下一次需要不等手指动就重新计算的时间（长按、按住生效、轻点脉冲结束），没有返回 -1。 */
    public long nextTimer(long now) {
        long t = Long.MAX_VALUE;
        for (Ptr p : ptrs) {
            if (p.kind == K_HOLD && now - p.downTime < HOLD_GUARD_MS) t = Math.min(t, p.downTime + HOLD_GUARD_MS);
            if (p.kind == K_GEAR && !p.fired) t = Math.min(t, p.downTime + GEAR_HOLD_MS);
            if (p.kind == K_TOUCHPAD && !p.moved && !p.longPress) t = Math.min(t, p.downTime + PAD_LONG_MS);
        }
        if (padPulseUntil > now) t = Math.min(t, padPulseUntil);
        return t == Long.MAX_VALUE ? -1 : t;
    }

    /** PS、麦克风、设置键按住的进度 0..1，画进度圈用；没按返回 -1。 */
    public float holdProgress(int kind, int bit, long now) {
        float best = -1;
        long dur = kind == K_GEAR ? GEAR_HOLD_MS : HOLD_GUARD_MS;
        for (Ptr p : ptrs) {
            if (p.kind != kind || (kind == K_HOLD && p.bit != bit)) continue;
            best = Math.max(best, Math.min(1f, (now - p.downTime) / (float) dur));
        }
        return best;
    }

    private PadLayout.Box singleBox(Ptr p) {
        return p.bit == PadState.CREATE ? L.create : L.options;
    }

    private boolean holdInside(Ptr p) {
        if (p.bit == PadState.PS) return PadLayout.dist(p.x, p.y, L.psX, L.psY) < L.psR * 2.2f;
        return L.mic.containsGrown(p.x, p.y, 1.4f);
    }

    /** upper 是 L2/R2（最上一排），lower 是 L1/R1。 */
    private int shoulderBits(Ptr p, PadLayout.Box upper, PadLayout.Box lower, int bitUpper, int bitLower) {
        if (!L.inShoulderZone(p.x, p.y, upper, lower, 2.2f)) return 0;
        float mid = (upper.b + lower.t) / 2, hyst = (lower.t - upper.b) * 0.8f;
        if (p.bits == bitUpper) mid += hyst;
        else if (p.bits == bitLower) mid -= hyst;
        return p.y < mid ? bitUpper : bitLower;
    }

    /**
     * △○×□ 按扇区判定：手指在哪个键的 90° 扇区里就是哪个键。两个相邻键的分界线左右 8° 内两个都按
     * （已经同时按下时放宽到 12°），中心一小块和远处不算。
     */
    private int faceBits(Ptr p) {
        float ddx = p.x - L.faceX, ddy = p.y - L.faceY, r = (float) Math.hypot(ddx, ddy);
        if (r < L.faceOff * 0.28f || r > L.faceZone() * 1.35f) return 0;
        double a = Math.toDegrees(Math.atan2(ddy, ddx));
        if (a < 0) a += 360;
        int k = Math.min(3, (int) (a / 90));
        double dd = a - (90 * k + 45);
        int A = FACE_ORDER[k], B = FACE_ORDER[(k + 1) % 4];
        boolean chordNow = p.bits == (A | B);
        if (r > L.faceOff * 0.5f && Math.abs(dd) < (chordNow ? 12 : 8)) return A | B;
        int single = dd < 0 ? A : B;
        if ((p.bits == A || p.bits == B) && p.bits != single && Math.abs(dd) < 6) return p.bits;
        return single;
    }

    /** 十字键 8 方向：正方向各 60°，斜方向各 30°；当前方向附近 7° 内不切换。 */
    private int dpadBits(Ptr p) {
        float ddx = p.x - L.dpadX, ddy = p.y - L.dpadY, r = (float) Math.hypot(ddx, ddy);
        if (r < L.dpadR * 0.2f || r > L.dpadR * 2.2f) return 0;
        double a = Math.toDegrees(Math.atan2(ddy, ddx));
        int cur = dpadSector(a);
        if (p.bits != 0 && p.bits != cur && (dpadSector(a - 7) == p.bits || dpadSector(a + 7) == p.bits)) return p.bits;
        return cur;
    }

    static int dpadSector(double a) {
        a = ((a % 360) + 360) % 360;
        if (a < 30 || a >= 330) return PadState.RIGHT;
        if (a < 60) return PadState.RIGHT | PadState.DOWN;
        if (a < 120) return PadState.DOWN;
        if (a < 150) return PadState.DOWN | PadState.LEFT;
        if (a < 210) return PadState.LEFT;
        if (a < 240) return PadState.LEFT | PadState.UP;
        if (a < 300) return PadState.UP;
        return PadState.UP | PadState.RIGHT;
    }
}
