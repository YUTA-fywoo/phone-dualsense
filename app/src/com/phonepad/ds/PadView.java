package com.phonepad.ds;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;

import com.phonepad.ds.core.PadState;

import java.util.ArrayList;
import java.util.List;

/**
 * 整个屏幕就是手柄正面，布局照 DualSense：左上 L1/L2，右上 R1/R2，左边十字键，右边 △○×□，
 * 中间触摸板，两侧 Create / Options，下方 PS 键和麦克风键，下面两个摇杆。
 */
public final class PadView extends View {

    public interface Callbacks {
        void onToggleMotion();

        void onToggleVibration();

        void onButtonsPressed(int newlyPressed);
    }

    private static final int K_NONE = 0, K_SHOULDER_L = 1, K_SHOULDER_R = 2, K_FACE = 3, K_DPAD = 4,
            K_STICK_L = 5, K_STICK_R = 6, K_TOUCHPAD = 7, K_SINGLE = 8;

    private static final class Ptr {
        int kind;
        int bit;
        float x, y, downX, downY, ox, oy;
        long downTime;
        boolean moved, stickClick, longPress;
        int slot = -1, touchId;
        RectF rect;
    }

    private final PadState state;
    private final Callbacks cb;
    private final SparseArray<Ptr> ptrs = new SparseArray<>();

    // ---- 布局 ----
    private float W, H, s;
    private final RectF rL1 = new RectF(), rL2 = new RectF(), rR1 = new RectF(), rR2 = new RectF();
    private final RectF rPad = new RectF(), rCreate = new RectF(), rOptions = new RectF(), rMic = new RectF();
    private final RectF rChipMotion = new RectF(), rChipVib = new RectF();
    private float dpadX, dpadY, dpadR, faceX, faceY, faceOff, btnR;
    private float lsX, lsY, rsX, rsY, stickR, psX, psY, psR;

    // ---- 交互状态 ----
    private long lastUpL, lastUpR;
    private float lastUpLx, lastUpLy, lastUpRx, lastUpRy;
    private int touchCounter;
    private long padClickUntil;
    private int lastButtons;
    private final float[] knob = new float[4]; // 两个摇杆的偏移 -1..1

    // ---- 电脑发来的显示状态 ----
    private boolean connected;
    private String error;
    private int lightbar = 0xFF3A4150;
    private int playerLeds;
    private int micLed;
    private boolean motionOn = true, motionAvailable = true, vibOn = true;

    // ---- 画笔 ----
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Rect tmpRect = new Rect();
    private Paint bgPaint;

    private static final int BG = 0xFF101216;
    private static final int BTN = 0xFF252931;
    private static final int BTN_EDGE = 0xFF343944;
    private static final int LABEL = 0xFF9AA3B2;
    private static final int PRESSED = 0xFFE9EDF5;
    private static final int C_TRIANGLE = 0xFF3FD4B6;
    private static final int C_CIRCLE = 0xFFFF6B78;
    private static final int C_CROSS = 0xFF7DA7FF;
    private static final int C_SQUARE = 0xFFE891DA;

    public PadView(Context c, PadState state, Callbacks cb) {
        super(c);
        this.state = state;
        this.cb = cb;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        setHapticFeedbackEnabled(false);
    }

    // ================= 外部更新 =================

    public void setConnected(boolean c) {
        connected = c;
        if (c) error = null;
        if (!c) {
            lightbar = 0xFF3A4150;
            playerLeds = 0;
            micLed = 0;
        }
        invalidate();
    }

    public void setError(String e) {
        error = e;
        invalidate();
    }

    public void setLightbar(int r, int g, int b) {
        // 真手柄的 LED 很亮，0x40 也看得清；屏幕上按色相把亮度拉满，不然暗色几乎看不见
        int m = Math.max(r, Math.max(g, b));
        lightbar = m == 0 ? 0xFF3A4150 : Color.rgb(r * 255 / m, g * 255 / m, b * 255 / m);
        invalidate();
    }

    public void setPlayerLeds(int mask) {
        playerLeds = mask;
        invalidate();
    }

    public void setMicLed(int mode) {
        micLed = mode;
        invalidate();
    }

    public void setToggles(boolean motion, boolean motionAvail, boolean vib) {
        motionOn = motion;
        motionAvailable = motionAvail;
        vibOn = vib;
        invalidate();
    }

    // ================= 布局 =================

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        W = w;
        H = h;
        s = Math.min(H, W / 2.0f);
        float m = 0.045f * s;

        float tpW = Math.min(0.40f * W, 0.98f * s);
        rPad.set(W / 2 - tpW / 2, 0.04f * H, W / 2 + tpW / 2, 0.42f * H);

        float shW = Math.min(0.40f * s, rPad.left - m - 0.16f * s);
        rL2.set(m, 0.035f * H, m + shW, 0.035f * H + 0.115f * s);
        rL1.set(m, rL2.bottom + 0.03f * s, m + shW, rL2.bottom + 0.03f * s + 0.10f * s);
        rR2.set(W - m - shW, rL2.top, W - m, rL2.bottom);
        rR1.set(W - m - shW, rL1.top, W - m, rL1.bottom);

        float cx = (rL2.right + rPad.left) / 2, pw = 0.055f * s, ph = 0.11f * s;
        rCreate.set(cx - pw / 2, 0.10f * H, cx + pw / 2, 0.10f * H + ph);
        cx = (rR2.left + rPad.right) / 2;
        rOptions.set(cx - pw / 2, rCreate.top, cx + pw / 2, rCreate.bottom);

        dpadR = 0.19f * s;
        dpadX = m + 0.06f * s + dpadR;
        dpadY = Math.max(0.55f * H, rL1.bottom + dpadR + 0.05f * s);
        faceX = W - dpadX;
        faceY = dpadY;
        btnR = 0.072f * s;
        faceOff = 0.142f * s;

        stickR = 0.175f * s;
        lsY = H - stickR - 0.07f * s;
        lsX = Math.max(dpadX + dpadR + stickR * 0.95f, 0.355f * W);
        lsX = Math.min(lsX, W / 2 - stickR - 0.13f * s);
        rsX = W - lsX;
        rsY = lsY;

        psR = 0.058f * s;
        psX = W / 2;
        psY = rPad.bottom + 0.075f * s + psR;
        float mw = 0.10f * s, mh = 0.05f * s;
        float my = psY + psR + 0.05f * s;
        rMic.set(W / 2 - mw / 2, my, W / 2 + mw / 2, my + mh);

        float chipW = 0.15f * s, chipH = 0.072f * s, gap = 0.025f * s;
        float cy = H - 0.04f * s - chipH;
        rChipMotion.set(W / 2 - gap / 2 - chipW, cy, W / 2 - gap / 2, cy + chipH);
        rChipVib.set(W / 2 + gap / 2, cy, W / 2 + gap / 2 + chipW, cy + chipH);

        bgPaint = new Paint();
        bgPaint.setShader(new RadialGradient(W / 2, H * 0.45f, Math.max(W, H) * 0.7f,
                0xFF1A1D23, BG, Shader.TileMode.CLAMP));

        if (Build.VERSION.SDK_INT >= 29) {
            List<Rect> ex = new ArrayList<>();
            int top = (int) (dpadY - dpadR * 1.3f), bottom = (int) (dpadY + dpadR * 1.3f);
            ex.add(new Rect(0, top, (int) (dpadX + dpadR), bottom));
            ex.add(new Rect((int) (faceX - dpadR), top, w, bottom));
            setSystemGestureExclusionRects(ex);
        }
    }

    // ================= 触摸 =================

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        long now = SystemClock.uptimeMillis();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                down(e.getPointerId(i), e.getX(i), e.getY(i), now);
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    Ptr p = ptrs.get(e.getPointerId(i));
                    if (p != null) move(p, e.getX(i), e.getY(i));
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int i = e.getActionIndex();
                up(e.getPointerId(i), now);
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                ptrs.clear();
                break;
            default:
                break;
        }
        recompute();
        return true;
    }

    private void down(int id, float x, float y, long now) {
        Ptr p = new Ptr();
        p.x = p.downX = p.ox = x;
        p.y = p.downY = p.oy = y;
        p.downTime = now;

        if (grow(rChipMotion, 0.2f).contains(x, y)) {
            cb.onToggleMotion();
            return;
        }
        if (grow(rChipVib, 0.2f).contains(x, y)) {
            cb.onToggleVibration();
            return;
        }
        if (shoulderBits(x, y, rL1, rL2, PadState.L1, PadState.L2) != 0) p.kind = K_SHOULDER_L;
        else if (shoulderBits(x, y, rR1, rR2, PadState.R1, PadState.R2) != 0) p.kind = K_SHOULDER_R;
        else if (grow(rCreate, 0.9f).contains(x, y)) single(p, PadState.CREATE, rCreate, 0.9f);
        else if (grow(rOptions, 0.9f).contains(x, y)) single(p, PadState.OPTIONS, rOptions, 0.9f);
        else if (rPad.contains(x, y)) {
            int slot = freeSlot();
            if (slot < 0) return;
            p.kind = K_TOUCHPAD;
            p.slot = slot;
            p.touchId = (touchCounter++) & 0x7F;
            final int pid = id;
            final long dt = p.downTime;
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    Ptr q = ptrs.get(pid);
                    if (q != null && q.kind == K_TOUCHPAD && q.downTime == dt && !q.moved) {
                        q.longPress = true; // 按住不动 = 按下触摸板
                        recompute();
                    }
                }
            }, 500);
        } else if (dist(x, y, psX, psY) < psR * 1.6f) {
            RectF r = new RectF(psX - psR, psY - psR, psX + psR, psY + psR);
            single(p, PadState.PS, r, 0.6f);
        } else if (grow(rMic, 0.8f).contains(x, y)) single(p, PadState.MUTE, rMic, 0.8f);
        else if (dist(x, y, dpadX, dpadY) < dpadR * 1.3f) p.kind = K_DPAD;
        else if (dist(x, y, faceX, faceY) < faceOff + btnR * 1.6f) p.kind = K_FACE;
        else {
            float dl = dist(x, y, lsX, lsY), dr = dist(x, y, rsX, rsY);
            if (dl <= dr && dl < stickR * 1.8f) {
                p.kind = K_STICK_L;
                p.stickClick = now - lastUpL < 300 && dist(x, y, lastUpLx, lastUpLy) < stickR * 1.2f;
            } else if (dr < dl && dr < stickR * 1.8f) {
                p.kind = K_STICK_R;
                p.stickClick = now - lastUpR < 300 && dist(x, y, lastUpRx, lastUpRy) < stickR * 1.2f;
            } else {
                return;
            }
        }
        ptrs.put(id, p);
    }

    private void single(Ptr p, int bit, RectF r, float growBy) {
        p.kind = K_SINGLE;
        p.bit = bit;
        p.rect = grow(r, growBy);
    }

    private void move(Ptr p, float x, float y) {
        p.x = x;
        p.y = y;
        if (p.kind == K_TOUCHPAD && dist(x, y, p.downX, p.downY) > rPad.width() * 0.035f) p.moved = true;
        if (p.kind == K_STICK_L || p.kind == K_STICK_R) {
            float dx = x - p.ox, dy = y - p.oy, d = (float) Math.hypot(dx, dy);
            if (d > stickR) { // 手指拉出范围时原点跟着走，反向推不用先回中
                p.ox += dx * (d - stickR) / d;
                p.oy += dy * (d - stickR) / d;
            }
        }
    }

    private void up(int id, long now) {
        Ptr p = ptrs.get(id);
        if (p == null) return;
        ptrs.remove(id);
        if (p.kind == K_STICK_L) {
            lastUpL = now;
            lastUpLx = p.x;
            lastUpLy = p.y;
        } else if (p.kind == K_STICK_R) {
            lastUpR = now;
            lastUpRx = p.x;
            lastUpRy = p.y;
        } else if (p.kind == K_TOUCHPAD && !p.moved && !p.longPress && now - p.downTime < 220) {
            padClickUntil = now + 110; // 轻点 = 按一下触摸板
            postDelayed(new Runnable() {
                @Override
                public void run() {
                    recompute();
                }
            }, 120);
        }
    }

    private int freeSlot() {
        boolean[] used = new boolean[2];
        for (int i = 0; i < ptrs.size(); i++) {
            Ptr q = ptrs.valueAt(i);
            if (q.kind == K_TOUCHPAD && q.slot >= 0) used[q.slot] = true;
        }
        return !used[0] ? 0 : (!used[1] ? 1 : -1);
    }

    /** 根据所有手指重新算一遍状态，写进 PadState。 */
    private void recompute() {
        int b = 0;
        float lx = 0, ly = 0, rx = 0, ry = 0;
        boolean[] ta = new boolean[2];
        int[] tid = new int[2], tx = new int[2], ty = new int[2];
        for (int i = 0; i < ptrs.size(); i++) {
            Ptr p = ptrs.valueAt(i);
            switch (p.kind) {
                case K_SHOULDER_L:
                    b |= shoulderBits(p.x, p.y, rL1, rL2, PadState.L1, PadState.L2);
                    break;
                case K_SHOULDER_R:
                    b |= shoulderBits(p.x, p.y, rR1, rR2, PadState.R1, PadState.R2);
                    break;
                case K_FACE:
                    b |= faceBits(p.x, p.y);
                    break;
                case K_DPAD:
                    b |= dpadBits(p.x, p.y);
                    break;
                case K_STICK_L:
                    lx = clampUnit((p.x - p.ox) / stickR);
                    ly = clampUnit((p.y - p.oy) / stickR);
                    if (p.stickClick) b |= PadState.L3;
                    break;
                case K_STICK_R:
                    rx = clampUnit((p.x - p.ox) / stickR);
                    ry = clampUnit((p.y - p.oy) / stickR);
                    if (p.stickClick) b |= PadState.R3;
                    break;
                case K_TOUCHPAD:
                    ta[p.slot] = true;
                    tid[p.slot] = p.touchId;
                    tx[p.slot] = Math.round((p.x - rPad.left) / rPad.width() * 1919f);
                    ty[p.slot] = Math.round((p.y - rPad.top) / rPad.height() * 1079f);
                    if (p.longPress) b |= PadState.TOUCHPAD;
                    break;
                case K_SINGLE:
                    if (p.rect.contains(p.x, p.y)) b |= p.bit;
                    break;
                default:
                    break;
            }
        }
        // 摇杆圆形限幅
        float ml = (float) Math.hypot(lx, ly);
        if (ml > 1) { lx /= ml; ly /= ml; }
        float mr = (float) Math.hypot(rx, ry);
        if (mr > 1) { rx /= mr; ry /= mr; }
        knob[0] = lx; knob[1] = ly; knob[2] = rx; knob[3] = ry;

        if (SystemClock.uptimeMillis() < padClickUntil) b |= PadState.TOUCHPAD;
        int l2 = (b & PadState.L2) != 0 ? 255 : 0;
        int r2 = (b & PadState.R2) != 0 ? 255 : 0;
        state.setTouchInput(b, axis(lx), axis(ly), axis(rx), axis(ry), l2, r2, ta, tid, tx, ty);

        int pressed = b & ~lastButtons;
        lastButtons = b;
        if (pressed != 0) cb.onButtonsPressed(pressed);
        invalidate();
    }

    private static int axis(float v) {
        return Math.round(127.5f + v * 127.5f);
    }

    private static float clampUnit(float v) {
        return v < -1 ? -1 : (v > 1 ? 1 : v);
    }

    /** r1 是 L1/R1（第二排），r2 是 L2/R2（最上面一排）。手指可以在两排之间滑。 */
    private int shoulderBits(float x, float y, RectF r1, RectF r2, int bit1, int bit2) {
        float padY = r1.height() * 0.35f, padX = r1.width() * 0.12f;
        float left = Math.min(r1.left, r2.left) - padX, right = Math.max(r1.right, r2.right) + padX;
        float top = r2.top - padY, bottom = r1.bottom + padY;
        if (x < left || x > right || y < top || y > bottom) return 0;
        float mid = (r2.bottom + r1.top) / 2;
        return y < mid ? bit2 : bit1;
    }

    private int faceBits(float x, float y) {
        float[][] c = {
                {faceX, faceY - faceOff, PadState.TRIANGLE},
                {faceX + faceOff, faceY, PadState.CIRCLE},
                {faceX, faceY + faceOff, PadState.CROSS},
                {faceX - faceOff, faceY, PadState.SQUARE},
        };
        int best = -1, second = -1;
        float d1 = Float.MAX_VALUE, d2 = Float.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            float d = dist(x, y, c[i][0], c[i][1]);
            if (d < d1) {
                second = best; d2 = d1;
                best = i; d1 = d;
            } else if (d < d2) {
                second = i; d2 = d;
            }
        }
        if (d1 > btnR * 2.2f) return 0;
        int bits = (int) c[best][2];
        // 手指压在两个相邻键中间时两个都按下
        if (second >= 0 && d2 < d1 * 1.3f && d2 < btnR * 1.75f) bits |= (int) c[second][2];
        return bits;
    }

    private int dpadBits(float x, float y) {
        float dx = x - dpadX, dy = y - dpadY, d = (float) Math.hypot(dx, dy);
        if (d < dpadR * 0.18f || d > dpadR * 2.2f) return 0;
        double a = Math.toDegrees(Math.atan2(dy, dx));
        if (a < 0) a += 360;
        if (a < 30 || a >= 330) return PadState.RIGHT;
        if (a < 60) return PadState.RIGHT | PadState.DOWN;
        if (a < 120) return PadState.DOWN;
        if (a < 150) return PadState.DOWN | PadState.LEFT;
        if (a < 210) return PadState.LEFT;
        if (a < 240) return PadState.LEFT | PadState.UP;
        if (a < 300) return PadState.UP;
        return PadState.UP | PadState.RIGHT;
    }

    private static float dist(float x, float y, float cx, float cy) {
        return (float) Math.hypot(x - cx, y - cy);
    }

    private static RectF grow(RectF r, float f) {
        float gx = r.width() * f / 2, gy = r.height() * f / 2;
        return new RectF(r.left - gx, r.top - gy, r.right + gx, r.bottom + gy);
    }

    // ================= 绘制 =================

    @Override
    protected void onDraw(Canvas c) {
        if (bgPaint == null) return;
        c.drawRect(0, 0, W, H, bgPaint);
        int b = lastButtons;

        drawShoulder(c, rL2, "L2", (b & PadState.L2) != 0);
        drawShoulder(c, rL1, "L1", (b & PadState.L1) != 0);
        drawShoulder(c, rR2, "R2", (b & PadState.R2) != 0);
        drawShoulder(c, rR1, "R1", (b & PadState.R1) != 0);

        drawTouchpad(c, (b & PadState.TOUCHPAD) != 0);
        drawCreate(c, (b & PadState.CREATE) != 0);
        drawOptions(c, (b & PadState.OPTIONS) != 0);
        drawPs(c, (b & PadState.PS) != 0);
        drawMic(c, (b & PadState.MUTE) != 0);
        drawDpad(c, b);
        drawFace(c, b);
        drawStick(c, lsX, lsY, knob[0], knob[1], (b & PadState.L3) != 0, "L3");
        drawStick(c, rsX, rsY, knob[2], knob[3], (b & PadState.R3) != 0, "R3");
        drawStatus(c);
    }

    private void drawShoulder(Canvas c, RectF r, String label, boolean on) {
        float rad = r.height() * 0.42f;
        fill.setColor(on ? PRESSED : BTN);
        c.drawRoundRect(r, rad, rad, fill);
        stroke.setColor(on ? PRESSED : BTN_EDGE);
        stroke.setStrokeWidth(0.006f * s);
        c.drawRoundRect(r, rad, rad, stroke);
        text.setColor(on ? BG : LABEL);
        text.setTextSize(r.height() * 0.42f);
        drawCentered(c, label, r.centerX(), r.centerY());
    }

    private void drawTouchpad(Canvas c, boolean clicked) {
        float rad = 0.045f * s;
        // 灯条：触摸板左右两侧
        float lw = 0.012f * s, inset = 0.02f * s;
        fill.setColor(lightbar);
        fill.setAlpha(70);
        c.drawRoundRect(rPad.left - inset - lw * 2.4f, rPad.top + rad, rPad.left - inset + lw * 1.4f, rPad.bottom - rad, lw * 2, lw * 2, fill);
        c.drawRoundRect(rPad.right + inset - lw * 1.4f, rPad.top + rad, rPad.right + inset + lw * 2.4f, rPad.bottom - rad, lw * 2, lw * 2, fill);
        fill.setAlpha(255);
        c.drawRoundRect(rPad.left - inset - lw, rPad.top + rad, rPad.left - inset, rPad.bottom - rad, lw, lw, fill);
        c.drawRoundRect(rPad.right + inset, rPad.top + rad, rPad.right + inset + lw, rPad.bottom - rad, lw, lw, fill);

        fill.setColor(clicked ? 0xFF3A404C : 0xFF1D2027);
        c.drawRoundRect(rPad, rad, rad, fill);
        stroke.setColor(clicked ? PRESSED : 0xFF30353F);
        stroke.setStrokeWidth(0.006f * s);
        c.drawRoundRect(rPad, rad, rad, stroke);

        // 手指位置
        for (int i = 0; i < ptrs.size(); i++) {
            Ptr p = ptrs.valueAt(i);
            if (p.kind != K_TOUCHPAD) continue;
            fill.setColor(0x55FFFFFF);
            c.drawCircle(p.x, p.y, 0.05f * s, fill);
        }
        // 连接状态和提示写在触摸板中间（两摇杆之间放不下）
        String line;
        int dot;
        if (error != null) {
            line = error;
            dot = 0xFFFF6B6B;
        } else if (connected) {
            line = "已连接电脑";
            dot = 0xFF4CD964;
        } else {
            line = "等待电脑连接";
            dot = 0xFF8A93A3;
        }
        float cy = rPad.centerY() - 0.06f * s;
        text.setTextSize(0.04f * s);
        text.setColor(0xFFB8C0CC);
        float tw = Math.min(text.measureText(line), rPad.width() * 0.8f);
        fill.setColor(dot);
        c.drawCircle(rPad.centerX() - tw / 2 - 0.03f * s, cy, 0.013f * s, fill);
        drawCentered(c, line, rPad.centerX(), cy);
        text.setTextSize(0.03f * s);
        text.setColor(0xFF5D6472);
        drawCentered(c, connected ? "滑动 = 触摸板   轻点 = 按下触摸板" : "在电脑上运行“连接手柄.bat”",
                rPad.centerX(), cy + 0.065f * s);
        drawCentered(c, "双击摇杆并按住 = L3 / R3", rPad.centerX(), cy + 0.115f * s);

        // 玩家指示灯：触摸板下沿 5 个点
        float dy = rPad.bottom - 0.035f * s, gap = 0.04f * s, r = 0.009f * s;
        for (int i = 0; i < 5; i++) {
            boolean lit = (playerLeds & (1 << i)) != 0;
            fill.setColor(lit ? 0xFFF4F7FF : 0xFF2A2E36);
            c.drawCircle(rPad.centerX() + (i - 2) * gap, dy, r, fill);
        }
    }

    private void drawCreate(Canvas c, boolean on) {
        drawPill(c, rCreate, on);
        stroke.setColor(on ? BG : LABEL);
        stroke.setStrokeWidth(0.008f * s);
        float cx = rCreate.centerX(), cy = rCreate.centerY(), h = rCreate.height() * 0.16f;
        // Create 键：三道向上散开的短线，下端不相交，免得看成箭头
        c.drawLine(cx, cy + h * 0.5f, cx, cy - h * 1.0f, stroke);
        c.drawLine(cx - h * 0.45f, cy + h * 0.7f, cx - h * 1.25f, cy - h * 0.5f, stroke);
        c.drawLine(cx + h * 0.45f, cy + h * 0.7f, cx + h * 1.25f, cy - h * 0.5f, stroke);
        label(c, "CREATE", cx, rCreate.bottom + 0.035f * s);
    }

    private void drawOptions(Canvas c, boolean on) {
        drawPill(c, rOptions, on);
        stroke.setColor(on ? BG : LABEL);
        stroke.setStrokeWidth(0.008f * s);
        float cx = rOptions.centerX(), cy = rOptions.centerY(), w = rOptions.width() * 0.26f, g = rOptions.height() * 0.13f;
        for (int i = -1; i <= 1; i++) c.drawLine(cx - w, cy + i * g, cx + w, cy + i * g, stroke);
        label(c, "OPTIONS", cx, rOptions.bottom + 0.035f * s);
    }

    private void drawPill(Canvas c, RectF r, boolean on) {
        float rad = Math.min(r.width(), r.height()) / 2;
        fill.setColor(on ? PRESSED : BTN);
        c.drawRoundRect(r, rad, rad, fill);
        stroke.setColor(on ? PRESSED : BTN_EDGE);
        stroke.setStrokeWidth(0.005f * s);
        c.drawRoundRect(r, rad, rad, stroke);
    }

    private void label(Canvas c, String t, float x, float y) {
        text.setColor(0xFF5D6472);
        text.setTextSize(0.028f * s);
        c.drawText(t, x, y, text);
    }

    private void drawPs(Canvas c, boolean on) {
        fill.setColor(on ? PRESSED : BTN);
        c.drawCircle(psX, psY, psR, fill);
        stroke.setColor(on ? PRESSED : BTN_EDGE);
        stroke.setStrokeWidth(0.006f * s);
        c.drawCircle(psX, psY, psR, stroke);
        text.setColor(on ? BG : 0xFFC9D0DC);
        text.setTextSize(psR * 0.72f);
        drawCentered(c, "PS", psX, psY);
    }

    private void drawMic(Canvas c, boolean on) {
        boolean led = micLed != 0;
        float rad = rMic.height() / 2;
        fill.setColor(on ? PRESSED : (led ? 0xFFFF8A3D : BTN));
        c.drawRoundRect(rMic, rad, rad, fill);
        stroke.setColor(on ? BG : (led ? BG : LABEL));
        stroke.setStrokeWidth(0.005f * s);
        float cx = rMic.centerX(), cy = rMic.centerY(), h = rMic.height() * 0.2f;
        // 话筒：胶囊形头部 + 下方 U 形托架 + 短柄
        RectF head = new RectF(cx - h * 0.55f, cy - h * 1.6f, cx + h * 0.55f, cy + h * 0.2f);
        c.drawRoundRect(head, h * 0.55f, h * 0.55f, stroke);
        RectF arc = new RectF(cx - h * 1.05f, cy - h * 1.2f, cx + h * 1.05f, cy + h * 0.75f);
        c.drawArc(arc, 0, 180, false, stroke);
        c.drawLine(cx, cy + h * 0.75f, cx, cy + h * 1.4f, stroke);
    }

    private void drawDpad(Canvas c, int b) {
        int[] bits = {PadState.UP, PadState.RIGHT, PadState.DOWN, PadState.LEFT};
        float w = dpadR * 0.62f, len = dpadR * 0.78f, inner = dpadR * 0.2f;
        for (int i = 0; i < 4; i++) {
            boolean on = (b & bits[i]) != 0;
            c.save();
            c.rotate(90 * i, dpadX, dpadY);
            // 朝中心带尖的方块（PS 风格的分体十字键）
            path.reset();
            float x0 = dpadX, y0 = dpadY;
            float top = y0 - inner - len, base = y0 - inner - w * 0.32f, rr = w * 0.2f;
            path.moveTo(x0 - w / 2, top + rr);
            path.quadTo(x0 - w / 2, top, x0 - w / 2 + rr, top);
            path.lineTo(x0 + w / 2 - rr, top);
            path.quadTo(x0 + w / 2, top, x0 + w / 2, top + rr);
            path.lineTo(x0 + w / 2, base);
            path.lineTo(x0, y0 - inner * 0.6f);
            path.lineTo(x0 - w / 2, base);
            path.close();
            fill.setColor(on ? PRESSED : BTN);
            c.drawPath(path, fill);
            stroke.setColor(on ? PRESSED : BTN_EDGE);
            stroke.setStrokeWidth(0.006f * s);
            c.drawPath(path, stroke);
            // 小三角
            float ty = top + len * 0.38f, ts = w * 0.17f;
            path.reset();
            path.moveTo(x0, ty - ts);
            path.lineTo(x0 + ts, ty + ts * 0.6f);
            path.lineTo(x0 - ts, ty + ts * 0.6f);
            path.close();
            fill.setColor(on ? BG : LABEL);
            c.drawPath(path, fill);
            c.restore();
        }
    }

    private void drawFace(Canvas c, int b) {
        drawFaceButton(c, faceX, faceY - faceOff, 0, C_TRIANGLE, (b & PadState.TRIANGLE) != 0);
        drawFaceButton(c, faceX + faceOff, faceY, 1, C_CIRCLE, (b & PadState.CIRCLE) != 0);
        drawFaceButton(c, faceX, faceY + faceOff, 2, C_CROSS, (b & PadState.CROSS) != 0);
        drawFaceButton(c, faceX - faceOff, faceY, 3, C_SQUARE, (b & PadState.SQUARE) != 0);
    }

    private void drawFaceButton(Canvas c, float x, float y, int kind, int color, boolean on) {
        fill.setColor(on ? color : BTN);
        c.drawCircle(x, y, btnR, fill);
        stroke.setColor(on ? color : BTN_EDGE);
        stroke.setStrokeWidth(0.006f * s);
        c.drawCircle(x, y, btnR, stroke);
        stroke.setColor(on ? 0xFFFFFFFF : color);
        stroke.setStrokeWidth(0.011f * s);
        float g = btnR * 0.42f;
        switch (kind) {
            case 0: // △
                path.reset();
                path.moveTo(x, y - g * 1.05f);
                path.lineTo(x + g, y + g * 0.7f);
                path.lineTo(x - g, y + g * 0.7f);
                path.close();
                c.drawPath(path, stroke);
                break;
            case 1: // ○
                c.drawCircle(x, y, g * 0.9f, stroke);
                break;
            case 2: // ×
                c.drawLine(x - g * 0.8f, y - g * 0.8f, x + g * 0.8f, y + g * 0.8f, stroke);
                c.drawLine(x + g * 0.8f, y - g * 0.8f, x - g * 0.8f, y + g * 0.8f, stroke);
                break;
            default: // □
                c.drawRect(x - g * 0.78f, y - g * 0.78f, x + g * 0.78f, y + g * 0.78f, stroke);
                break;
        }
    }

    private void drawStick(Canvas c, float x, float y, float kx, float ky, boolean click, String name) {
        fill.setColor(0xFF181B21);
        c.drawCircle(x, y, stickR, fill);
        stroke.setColor(0xFF2C3038);
        stroke.setStrokeWidth(0.01f * s);
        c.drawCircle(x, y, stickR, stroke);
        float travel = stickR * 0.42f, kr = stickR * 0.55f;
        float px = x + kx * travel, py = y + ky * travel;
        fill.setColor(click ? PRESSED : 0xFF30353F);
        c.drawCircle(px, py, kr, fill);
        stroke.setColor(click ? PRESSED : 0xFF454B57);
        stroke.setStrokeWidth(0.008f * s);
        c.drawCircle(px, py, kr * 0.72f, stroke);
        text.setColor(click ? BG : 0xFF5D6472);
        text.setTextSize(0.03f * s);
        drawCentered(c, name, px, py);
    }

    private void drawStatus(Canvas c) {
        drawChip(c, rChipMotion, motionAvailable ? (motionOn ? "体感 开" : "体感 关") : "无陀螺仪", motionOn && motionAvailable);
        drawChip(c, rChipVib, vibOn ? "震动 开" : "震动 关", vibOn);
    }

    private void drawChip(Canvas c, RectF r, String t, boolean on) {
        float rad = r.height() / 2;
        fill.setColor(on ? 0xFF233047 : 0xFF1C1F25);
        c.drawRoundRect(r, rad, rad, fill);
        stroke.setColor(on ? 0xFF4C7BD9 : 0xFF2E333C);
        stroke.setStrokeWidth(0.005f * s);
        c.drawRoundRect(r, rad, rad, stroke);
        text.setColor(on ? 0xFFD6E2FF : 0xFF6B7380);
        text.setTextSize(r.height() * 0.42f);
        drawCentered(c, t, r.centerX(), r.centerY());
    }

    private void drawCentered(Canvas c, String t, float x, float y) {
        text.getTextBounds(t, 0, t.length(), tmpRect);
        c.drawText(t, x, y - tmpRect.exactCenterY(), text);
    }
}
