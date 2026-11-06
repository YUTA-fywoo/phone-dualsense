package com.phonepad.ds;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import com.phonepad.ds.core.PadLayout;
import com.phonepad.ds.core.PadState;
import com.phonepad.ds.core.TouchLogic;

import java.util.ArrayList;
import java.util.List;

/**
 * 整个屏幕就是手柄正面。位置在 {@link PadLayout}，触屏判定在 {@link TouchLogic}，这里只负责
 * 把触摸事件交给它们、把结果写进 PadState、画界面，以及布局编辑模式。
 */
public final class PadView extends View {

    public interface Callbacks {
        void onHaptic(int kind);

        void onOpenSettings();

        void onLayoutEdited(String serialized);

        void onEditDone();
    }

    private final PadState state;
    private final Callbacks cb;
    private final PadLayout L = new PadLayout();
    private final TouchLogic logic = new TouchLogic(L);
    private final Runnable timer = new Runnable() {
        @Override
        public void run() {
            apply();
        }
    };

    private float W, H, s;

    // ---- 布局编辑 ----
    private boolean editing;
    private int editGroup = -1, editPtr = -1, pinchPtr = -1;
    private float lastX, lastY, pinchD0, pinchS0;
    private final RectF btnDone = new RectF(), btnReset = new RectF();

    // ---- 电脑发来的显示状态 ----
    private boolean connected;
    private String error;
    private int lightbar = 0xFF3A4150;
    private int playerLeds;
    private int micLed;
    private String volumeHint = "";

    // ---- 画笔 ----
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Rect tmpRect = new Rect();
    private final RectF tmpF = new RectF();
    private Paint bgPaint;

    private static final int BG = 0xFF101216;
    private static final int BTN = 0xFF252931;
    private static final int BTN_EDGE = 0xFF343944;
    private static final int LABEL = 0xFF9AA3B2;
    private static final int PRESSED = 0xFFE9EDF5;
    private static final int ACCENT = 0xFF4C7BD9;
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

    /** 触摸板上显示的音量键说明，空串表示不显示。 */
    public void setVolumeHint(String hint) {
        volumeHint = hint == null ? "" : hint;
        invalidate();
    }

    /** 音量键等实体键映射过来的按键。 */
    public void setExternalButtons(int bits) {
        logic.setExternal(bits);
        apply();
    }

    public void setLayoutAdjustments(String serialized) {
        L.deserialize(serialized);
        if (W > 0) {
            L.compute(W, H);
            updateExclusion();
        }
        invalidate();
    }

    public void resetLayout() {
        L.resetAdjustments();
        if (W > 0) L.compute(W, H);
        layoutChanged();
    }

    public boolean isEditing() {
        return editing;
    }

    public void setEditing(boolean on) {
        editing = on;
        editGroup = -1;
        pinchPtr = -1;
        cancelTouches();
    }

    /** 松开所有触屏输入（打开设置、进入编辑时用）。 */
    public void cancelTouches() {
        logic.cancelAll();
        apply();
    }

    // ================= 布局 =================

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        W = w;
        H = h;
        s = Math.min(H, W / 2.0f);
        L.compute(w, h);

        bgPaint = new Paint();
        bgPaint.setShader(new RadialGradient(W / 2, H * 0.45f, Math.max(W, H) * 0.7f,
                0xFF1A1D23, BG, Shader.TileMode.CLAMP));

        float bw = 0.24f * s, bh = 0.085f * s, cy = L.pad.cy() + 0.075f * s, gap = 0.03f * s;
        btnDone.set(W / 2 - gap / 2 - bw, cy - bh / 2, W / 2 - gap / 2, cy + bh / 2);
        btnReset.set(W / 2 + gap / 2, cy - bh / 2, W / 2 + gap / 2 + bw, cy + bh / 2);
        updateExclusion();
    }

    private void updateExclusion() {
        if (Build.VERSION.SDK_INT < 29 || W <= 0) return;
        // 两侧边缘滑动会触发系统返回手势，十字键和 △○×□ 附近排除掉
        List<Rect> ex = new ArrayList<>();
        float zl = L.dpadZone(), zr = L.faceZone();
        ex.add(new Rect(0, (int) (L.dpadY - zl), (int) (L.dpadX + L.dpadR), (int) (L.dpadY + zl)));
        ex.add(new Rect((int) (L.faceX - L.faceOff), (int) (L.faceY - zr), (int) W, (int) (L.faceY + zr)));
        setSystemGestureExclusionRects(ex);
    }

    private void layoutChanged() {
        updateExclusion();
        cb.onLayoutEdited(L.serialize());
        invalidate();
    }

    // ================= 触摸 =================

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (editing) {
            editTouch(e);
            return true;
        }
        long now = SystemClock.uptimeMillis();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                int i = e.getActionIndex();
                logic.down(e.getPointerId(i), e.getX(i), e.getY(i), now);
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) logic.move(e.getPointerId(i), e.getX(i), e.getY(i));
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int i = e.getActionIndex();
                logic.move(e.getPointerId(i), e.getX(i), e.getY(i));
                logic.up(e.getPointerId(i), now);
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                logic.cancelAll();
                break;
            default:
                break;
        }
        apply();
        return true;
    }

    /** 重新计算输出，写进 PadState，发触感，安排下一次计时。 */
    private void apply() {
        long now = SystemClock.uptimeMillis();
        logic.update(now);
        TouchLogic.Stick a = logic.sticks[0], b = logic.sticks[1];
        state.setTouchInput(logic.buttons, axis(a.x), axis(a.y), axis(b.x), axis(b.y), logic.l2(), logic.r2(),
                logic.tActive, logic.tId, logic.tX, logic.tY);
        int h = logic.takeHaptic();
        if (h != TouchLogic.HAPTIC_NONE) cb.onHaptic(h);
        removeCallbacks(timer);
        long t = logic.nextTimer(now);
        if (t >= 0) postDelayed(timer, Math.max(1, t - now));
        invalidate();
        if (logic.takeGearRequest()) cb.onOpenSettings();
    }

    private static int axis(float v) {
        return Math.round(127.5f + v * 127.5f);
    }

    private void editTouch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                float x = e.getX(), y = e.getY();
                editGroup = -1;
                pinchPtr = -1;
                if (btnDone.contains(x, y)) {
                    cb.onEditDone();
                    return;
                }
                if (btnReset.contains(x, y)) {
                    resetLayout();
                    return;
                }
                editGroup = L.groupAt(x, y);
                editPtr = e.getPointerId(0);
                lastX = x;
                lastY = y;
                break;
            }
            case MotionEvent.ACTION_POINTER_DOWN: {
                if (editGroup < 0 || pinchPtr >= 0) break;
                int i = e.getActionIndex(), j = e.findPointerIndex(editPtr);
                if (j < 0) break;
                pinchPtr = e.getPointerId(i);
                pinchD0 = Math.max(1f, (float) Math.hypot(e.getX(i) - e.getX(j), e.getY(i) - e.getY(j)));
                pinchS0 = L.scale[editGroup];
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (editGroup < 0) break;
                int j = e.findPointerIndex(editPtr);
                if (j < 0) break;
                if (pinchPtr >= 0) {
                    int i = e.findPointerIndex(pinchPtr);
                    if (i >= 0) {
                        float d = (float) Math.hypot(e.getX(i) - e.getX(j), e.getY(i) - e.getY(j));
                        L.setScale(editGroup, pinchS0 * d / pinchD0);
                    }
                } else {
                    L.moveGroup(editGroup, e.getX(j) - lastX, e.getY(j) - lastY);
                    lastX = e.getX(j);
                    lastY = e.getY(j);
                }
                invalidate();
                break;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                int id = e.getPointerId(e.getActionIndex());
                if (id == pinchPtr) {
                    pinchPtr = -1;
                } else if (id == editPtr) {
                    if (pinchPtr >= 0) {
                        editPtr = pinchPtr;
                        pinchPtr = -1;
                    } else {
                        editGroup = -1;
                        break;
                    }
                } else {
                    break;
                }
                int j = e.findPointerIndex(editPtr);
                if (j >= 0) {
                    lastX = e.getX(j);
                    lastY = e.getY(j);
                }
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (editGroup >= 0) layoutChanged();
                editGroup = -1;
                pinchPtr = -1;
                break;
            default:
                break;
        }
    }

    // ================= 绘制 =================

    @Override
    protected void onDraw(Canvas c) {
        if (bgPaint == null) return;
        c.drawRect(0, 0, W, H, bgPaint);
        int b = logic.buttons;
        long now = SystemClock.uptimeMillis();

        drawShoulder(c, L.l2, "L2", (b & PadState.L2) != 0);
        drawShoulder(c, L.l1, "L1", (b & PadState.L1) != 0);
        drawShoulder(c, L.r2, "R2", (b & PadState.R2) != 0);
        drawShoulder(c, L.r1, "R1", (b & PadState.R1) != 0);

        drawTouchpad(c, (b & PadState.TOUCHPAD) != 0);
        drawGear(c, logic.holdProgress(TouchLogic.K_GEAR, 0, now));
        drawCreate(c, (b & PadState.CREATE) != 0);
        drawOptions(c, (b & PadState.OPTIONS) != 0);
        drawPs(c, (b & PadState.PS) != 0, logic.holdProgress(TouchLogic.K_HOLD, PadState.PS, now));
        drawMic(c, (b & PadState.MUTE) != 0, logic.holdProgress(TouchLogic.K_HOLD, PadState.MUTE, now));
        drawDpad(c, b);
        drawFace(c, b);
        drawStick(c, 0, (b & PadState.L3) != 0, "L3");
        drawStick(c, 1, (b & PadState.R3) != 0, "R3");
        if (editing) drawEditor(c);

        // 按住类按键的进度圈要动起来
        float g = logic.holdProgress(TouchLogic.K_GEAR, 0, now);
        float p1 = logic.holdProgress(TouchLogic.K_HOLD, PadState.PS, now);
        float p2 = logic.holdProgress(TouchLogic.K_HOLD, PadState.MUTE, now);
        if ((g >= 0 && g < 1) || (p1 >= 0 && p1 < 1) || (p2 >= 0 && p2 < 1)) postInvalidateOnAnimation();
    }

    private void drawShoulder(Canvas c, PadLayout.Box r, String label, boolean on) {
        tmpF.set(r.l, r.t, r.r, r.b);
        float rad = r.h() * 0.42f;
        fill.setColor(on ? PRESSED : BTN);
        c.drawRoundRect(tmpF, rad, rad, fill);
        stroke.setColor(on ? PRESSED : BTN_EDGE);
        stroke.setStrokeWidth(0.006f * s);
        c.drawRoundRect(tmpF, rad, rad, stroke);
        text.setColor(on ? BG : LABEL);
        text.setTextSize(r.h() * 0.42f);
        drawCentered(c, label, r.cx(), r.cy());
    }

    private void drawTouchpad(Canvas c, boolean clicked) {
        PadLayout.Box p = L.pad;
        float rad = 0.045f * s;
        // 灯条：触摸板左右两侧
        float lw = 0.012f * s, inset = 0.02f * s;
        fill.setColor(lightbar);
        fill.setAlpha(70);
        c.drawRoundRect(p.l - inset - lw * 2.4f, p.t + rad, p.l - inset + lw * 1.4f, p.b - rad, lw * 2, lw * 2, fill);
        c.drawRoundRect(p.r + inset - lw * 1.4f, p.t + rad, p.r + inset + lw * 2.4f, p.b - rad, lw * 2, lw * 2, fill);
        fill.setAlpha(255);
        c.drawRoundRect(p.l - inset - lw, p.t + rad, p.l - inset, p.b - rad, lw, lw, fill);
        c.drawRoundRect(p.r + inset, p.t + rad, p.r + inset + lw, p.b - rad, lw, lw, fill);

        tmpF.set(p.l, p.t, p.r, p.b);
        fill.setColor(clicked ? 0xFF3A404C : 0xFF1D2027);
        c.drawRoundRect(tmpF, rad, rad, fill);
        stroke.setColor(clicked ? PRESSED : 0xFF30353F);
        stroke.setStrokeWidth(0.006f * s);
        c.drawRoundRect(tmpF, rad, rad, stroke);

        for (TouchLogic.Ptr q : logic.pointers()) {
            if (q.kind != TouchLogic.K_TOUCHPAD) continue;
            fill.setColor(0x55FFFFFF);
            c.drawCircle(q.x, q.y, 0.05f * s, fill);
        }

        if (!editing) {
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
            float cy = p.t + 0.065f * s, lh = 0.042f * s;
            text.setTextSize(0.04f * s);
            text.setColor(0xFFB8C0CC);
            float tw = Math.min(text.measureText(line), p.w() * 0.8f);
            fill.setColor(dot);
            c.drawCircle(p.cx() - tw / 2 - 0.03f * s, cy, 0.013f * s, fill);
            drawCentered(c, line, p.cx(), cy);
            text.setTextSize(0.027f * s);
            text.setColor(0xFF5D6472);
            cy += 0.06f * s;
            drawCentered(c, connected ? "滑动 = 触摸板，轻点 = 按下触摸板" : "在电脑上运行“连接手柄.bat”", p.cx(), cy);
            drawCentered(c, "两边空白处都是摇杆，手指落在哪里哪里就是中心", p.cx(), cy + lh);
            drawCentered(c, "双击摇杆区并按住 = L3/R3，PS 和麦克风键要按住", p.cx(), cy + lh * 2);
            if (!volumeHint.isEmpty()) drawCentered(c, volumeHint, p.cx(), cy + lh * 3);
        }

        // 玩家指示灯：触摸板下沿 5 个点
        float dy = p.b - 0.03f * s, gap = 0.04f * s, r = 0.009f * s;
        for (int i = 0; i < 5; i++) {
            boolean lit = (playerLeds & (1 << i)) != 0;
            fill.setColor(lit ? 0xFFF4F7FF : 0xFF2A2E36);
            c.drawCircle(p.cx() + (i - 2) * gap, dy, r, fill);
        }
    }

    private void drawGear(Canvas c, float progress) {
        if (editing) return;
        float cx = L.gear.cx(), cy = L.gear.cy(), r = L.gear.w() / 2;
        boolean on = progress >= 0;
        stroke.setColor(on ? PRESSED : 0xFF4A505C);
        stroke.setStrokeWidth(0.007f * s);
        c.drawCircle(cx, cy, r * 0.32f, stroke);
        for (int i = 0; i < 8; i++) {
            double a = Math.PI / 4 * i;
            float cs = (float) Math.cos(a), sn = (float) Math.sin(a);
            c.drawLine(cx + cs * r * 0.5f, cy + sn * r * 0.5f, cx + cs * r * 0.72f, cy + sn * r * 0.72f, stroke);
        }
        if (on) drawProgress(c, cx, cy, r * 1.05f, progress);
    }

    private void drawProgress(Canvas c, float cx, float cy, float r, float progress) {
        stroke.setColor(ACCENT);
        stroke.setStrokeWidth(0.008f * s);
        tmpF.set(cx - r, cy - r, cx + r, cy + r);
        c.drawArc(tmpF, -90, 360 * progress, false, stroke);
    }

    private void drawCreate(Canvas c, boolean on) {
        PadLayout.Box r = L.create;
        drawPill(c, r, on);
        stroke.setColor(on ? BG : LABEL);
        stroke.setStrokeWidth(0.008f * s);
        float cx = r.cx(), cy = r.cy(), h = r.h() * 0.16f;
        // Create 键：三道向上散开的短线，下端不相交，免得看成箭头
        c.drawLine(cx, cy + h * 0.5f, cx, cy - h * 1.0f, stroke);
        c.drawLine(cx - h * 0.45f, cy + h * 0.7f, cx - h * 1.25f, cy - h * 0.5f, stroke);
        c.drawLine(cx + h * 0.45f, cy + h * 0.7f, cx + h * 1.25f, cy - h * 0.5f, stroke);
        label(c, "CREATE", cx, r.b + 0.035f * s);
    }

    private void drawOptions(Canvas c, boolean on) {
        PadLayout.Box r = L.options;
        drawPill(c, r, on);
        stroke.setColor(on ? BG : LABEL);
        stroke.setStrokeWidth(0.008f * s);
        float cx = r.cx(), cy = r.cy(), w = r.w() * 0.26f, g = r.h() * 0.13f;
        for (int i = -1; i <= 1; i++) c.drawLine(cx - w, cy + i * g, cx + w, cy + i * g, stroke);
        label(c, "OPTIONS", cx, r.b + 0.035f * s);
    }

    private void drawPill(Canvas c, PadLayout.Box r, boolean on) {
        tmpF.set(r.l, r.t, r.r, r.b);
        float rad = Math.min(r.w(), r.h()) / 2;
        fill.setColor(on ? PRESSED : BTN);
        c.drawRoundRect(tmpF, rad, rad, fill);
        stroke.setColor(on ? PRESSED : BTN_EDGE);
        stroke.setStrokeWidth(0.005f * s);
        c.drawRoundRect(tmpF, rad, rad, stroke);
    }

    private void label(Canvas c, String t, float x, float y) {
        text.setColor(0xFF5D6472);
        text.setTextSize(0.028f * s);
        c.drawText(t, x, y, text);
    }

    private void drawPs(Canvas c, boolean on, float progress) {
        fill.setColor(on ? PRESSED : BTN);
        c.drawCircle(L.psX, L.psY, L.psR, fill);
        stroke.setColor(on ? PRESSED : BTN_EDGE);
        stroke.setStrokeWidth(0.006f * s);
        c.drawCircle(L.psX, L.psY, L.psR, stroke);
        text.setColor(on ? BG : 0xFFC9D0DC);
        text.setTextSize(L.psR * 0.72f);
        drawCentered(c, "PS", L.psX, L.psY);
        if (progress >= 0 && !on) drawProgress(c, L.psX, L.psY, L.psR * 1.25f, progress);
    }

    private void drawMic(Canvas c, boolean on, float progress) {
        PadLayout.Box r = L.mic;
        boolean led = micLed != 0;
        tmpF.set(r.l, r.t, r.r, r.b);
        float rad = r.h() / 2;
        fill.setColor(on ? PRESSED : (led ? 0xFFFF8A3D : BTN));
        c.drawRoundRect(tmpF, rad, rad, fill);
        stroke.setColor(on ? BG : (led ? BG : LABEL));
        stroke.setStrokeWidth(0.005f * s);
        float cx = r.cx(), cy = r.cy(), h = r.h() * 0.2f;
        // 话筒：胶囊形头部 + 下方 U 形托架 + 短柄
        tmpF.set(cx - h * 0.55f, cy - h * 1.6f, cx + h * 0.55f, cy + h * 0.2f);
        c.drawRoundRect(tmpF, h * 0.55f, h * 0.55f, stroke);
        tmpF.set(cx - h * 1.05f, cy - h * 1.2f, cx + h * 1.05f, cy + h * 0.75f);
        c.drawArc(tmpF, 0, 180, false, stroke);
        c.drawLine(cx, cy + h * 0.75f, cx, cy + h * 1.4f, stroke);
        if (progress >= 0 && !on) drawProgress(c, cx, cy, r.w() * 0.7f, progress);
    }

    private void drawDpad(Canvas c, int b) {
        int[] bits = {PadState.UP, PadState.RIGHT, PadState.DOWN, PadState.LEFT};
        float R = L.dpadR, x0 = L.dpadX, y0 = L.dpadY;
        float w = R * 0.62f, len = R * 0.78f, inner = R * 0.2f;
        for (int i = 0; i < 4; i++) {
            boolean on = (b & bits[i]) != 0;
            c.save();
            c.rotate(90 * i, x0, y0);
            // 朝中心带尖的方块（PS 风格的分体十字键）
            path.reset();
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
        float x = L.faceX, y = L.faceY, o = L.faceOff;
        drawFaceButton(c, x, y - o, 0, C_TRIANGLE, (b & PadState.TRIANGLE) != 0);
        drawFaceButton(c, x + o, y, 1, C_CIRCLE, (b & PadState.CIRCLE) != 0);
        drawFaceButton(c, x, y + o, 2, C_CROSS, (b & PadState.CROSS) != 0);
        drawFaceButton(c, x - o, y, 3, C_SQUARE, (b & PadState.SQUARE) != 0);
    }

    private void drawFaceButton(Canvas c, float x, float y, int kind, int color, boolean on) {
        float btnR = L.btnR;
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

    /** 没人碰时画在默认位置；手指按住时底座跟到手指落下的地方，原位置留一个淡影。 */
    private void drawStick(Canvas c, int side, boolean click, String name) {
        TouchLogic.Stick st = logic.sticks[side];
        float restX = side == 0 ? L.lsX : L.rsX, restY = side == 0 ? L.lsY : L.rsY;
        float R = L.stickRadius(side), reach = L.stickReach(side);
        float x = restX, y = restY;
        if (st.owner != null && !editing) {
            x = st.owner.ox;
            y = st.owner.oy;
            stroke.setColor(0xFF22262D);
            stroke.setStrokeWidth(0.006f * s);
            c.drawCircle(restX, restY, R, stroke);
        }
        fill.setColor(0xFF181B21);
        c.drawCircle(x, y, R, fill);
        stroke.setColor(st.owner != null ? 0xFF3A4352 : 0xFF2C3038);
        stroke.setStrokeWidth(0.01f * s);
        c.drawCircle(x, y, R, stroke);
        float kr = R * 0.55f;
        float px = x + st.x * reach, py = y + st.y * reach;
        fill.setColor(click ? PRESSED : 0xFF30353F);
        c.drawCircle(px, py, kr, fill);
        stroke.setColor(click ? PRESSED : 0xFF454B57);
        stroke.setStrokeWidth(0.008f * s);
        c.drawCircle(px, py, kr * 0.72f, stroke);
        text.setColor(click ? BG : 0xFF5D6472);
        text.setTextSize(0.03f * s);
        drawCentered(c, name, px, py);
    }

    private final Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);

    private void drawEditor(Canvas c) {
        fill.setColor(0x66000000);
        c.drawRect(0, 0, W, H, fill);
        dash.setStyle(Paint.Style.STROKE);
        dash.setStrokeWidth(0.006f * s);
        dash.setPathEffect(new DashPathEffect(new float[]{0.03f * s, 0.02f * s}, 0));
        for (int g = 0; g < PadLayout.GROUPS; g++) {
            boolean sel = g == editGroup;
            dash.setColor(sel ? ACCENT : 0xFF8A93A3);
            float cx, cy;
            switch (g) {
                case PadLayout.G_L_SHOULDER:
                case PadLayout.G_R_SHOULDER: {
                    PadLayout.Box up = g == PadLayout.G_L_SHOULDER ? L.l2 : L.r2;
                    PadLayout.Box lo = g == PadLayout.G_L_SHOULDER ? L.l1 : L.r1;
                    float pd = 0.02f * s;
                    tmpF.set(up.l - pd, up.t - pd, up.r + pd, lo.b + pd);
                    c.drawRoundRect(tmpF, pd * 2, pd * 2, dash);
                    cx = tmpF.centerX();
                    cy = tmpF.bottom + 0.04f * s;
                    break;
                }
                case PadLayout.G_DPAD:
                    c.drawCircle(L.dpadX, L.dpadY, L.dpadR * 1.1f, dash);
                    cx = L.dpadX;
                    cy = L.dpadY + L.dpadR * 1.1f + 0.04f * s;
                    break;
                case PadLayout.G_FACE: {
                    float r = L.faceOff + L.btnR * 1.3f;
                    c.drawCircle(L.faceX, L.faceY, r, dash);
                    cx = L.faceX;
                    cy = L.faceY + r + 0.04f * s;
                    break;
                }
                default: {
                    int side = g == PadLayout.G_L_STICK ? 0 : 1;
                    float r = L.stickRadius(side) * 1.1f;
                    cx = side == 0 ? L.lsX : L.rsX;
                    c.drawCircle(cx, side == 0 ? L.lsY : L.rsY, r, dash);
                    cy = (side == 0 ? L.lsY : L.rsY) - r - 0.02f * s;
                    break;
                }
            }
            text.setColor(sel ? 0xFFD6E2FF : 0xFFB8C0CC);
            text.setTextSize(0.03f * s);
            String t = PadLayout.GROUP_NAMES[g] + "  " + Math.round(L.scale[g] * 100) + "%";
            drawCentered(c, t, cx, Math.min(cy, H - 0.03f * s));
        }

        text.setColor(0xFFD6E2FF);
        text.setTextSize(0.034f * s);
        drawCentered(c, "拖动部件移动位置，两根手指捏合缩放", L.pad.cx(), L.pad.t + 0.07f * s);
        text.setTextSize(0.028f * s);
        text.setColor(0xFF8A93A3);
        drawCentered(c, "摇杆缩放会同时改变推满需要的距离", L.pad.cx(), L.pad.t + 0.12f * s);
        drawButton(c, btnDone, "完成", true);
        drawButton(c, btnReset, "恢复默认", false);
    }

    private void drawButton(Canvas c, RectF r, String t, boolean primary) {
        float rad = r.height() / 2;
        fill.setColor(primary ? ACCENT : 0xFF2A2F38);
        c.drawRoundRect(r, rad, rad, fill);
        text.setColor(0xFFF4F7FF);
        text.setTextSize(r.height() * 0.42f);
        drawCentered(c, t, r.centerX(), r.centerY());
    }

    private void drawCentered(Canvas c, String t, float x, float y) {
        text.getTextBounds(t, 0, t.length(), tmpRect);
        c.drawText(t, x, y - tmpRect.exactCenterY(), text);
    }
}
