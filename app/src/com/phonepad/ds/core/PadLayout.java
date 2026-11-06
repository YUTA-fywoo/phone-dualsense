package com.phonepad.ds.core;

import java.util.Locale;

/**
 * 手柄各部件在横屏上的位置（像素，原点左上）。纯 Java，电脑上可以直接测。
 * 布局照 DualSense：左上 L1/L2，右上 R1/R2，左边十字键，右边 △○×□，中间触摸板，下面两个摇杆。
 * 六组部件可以由用户挪动和缩放：偏移以 s（屏幕短边的尺度）为单位存，换手机也大致成比例。
 */
public final class PadLayout {

    public static final int G_L_SHOULDER = 0, G_R_SHOULDER = 1, G_DPAD = 2, G_FACE = 3,
            G_L_STICK = 4, G_R_STICK = 5, GROUPS = 6;
    public static final String[] GROUP_NAMES = {"L1 / L2", "R1 / R2", "十字键", "△ ○ × □", "左摇杆", "右摇杆"};
    public static final float MIN_SCALE = 0.6f, MAX_SCALE = 1.6f;

    public static final class Box {
        public float l, t, r, b;

        public void set(float l, float t, float r, float b) {
            this.l = l;
            this.t = t;
            this.r = r;
            this.b = b;
        }

        public float cx() { return (l + r) / 2; }

        public float cy() { return (t + b) / 2; }

        public float w() { return r - l; }

        public float h() { return b - t; }

        public boolean contains(float x, float y) {
            return x >= l && x <= r && y >= t && y <= b;
        }

        /** 四周各放大宽高的 f/2 之后是否包含该点。 */
        public boolean containsGrown(float x, float y, float f) {
            float gx = w() * f / 2, gy = h() * f / 2;
            return x >= l - gx && x <= r + gx && y >= t - gy && y <= b + gy;
        }
    }

    /** 用户调整：每组的偏移（单位 s）和缩放。 */
    public final float[] dx = new float[GROUPS], dy = new float[GROUPS], scale = new float[GROUPS];

    public float W, H, s;
    public final Box l1 = new Box(), l2 = new Box(), r1 = new Box(), r2 = new Box();
    public final Box pad = new Box(), create = new Box(), options = new Box(), mic = new Box(), gear = new Box();
    public float dpadX, dpadY, dpadR, faceX, faceY, faceOff, btnR;
    /** 摇杆静止时画在哪里、画多大；reach 是推满一格需要的手指位移（像素）。 */
    public float lsX, lsY, rsX, rsY, lStickR, rStickR, lReach, rReach;
    public float psX, psY, psR;
    /** 边缘防误触：上下（曲面屏那两边）和左右这么宽的带里落下的手指不算。 */
    public float edgeY, edgeX;

    public PadLayout() {
        resetAdjustments();
    }

    public void resetAdjustments() {
        for (int g = 0; g < GROUPS; g++) {
            dx[g] = 0;
            dy[g] = 0;
            scale[g] = 1;
        }
    }

    public void compute(float w, float h) {
        W = w;
        H = h;
        s = Math.min(H, W / 2f);
        float m = 0.045f * s;
        edgeY = 0.03f * H;
        edgeX = 0.008f * W;

        float tpW = Math.min(0.40f * W, 0.98f * s);
        pad.set(W / 2 - tpW / 2, 0.115f * H, W / 2 + tpW / 2, 0.44f * H);
        float gr = 0.034f * s, gy = (edgeY + pad.t) / 2;
        gear.set(W / 2 - gr, gy - gr, W / 2 + gr, gy + gr);

        // 肩键：L2 在上，L1 在下，手指可以在两排之间滑
        float shW = Math.min(0.40f * s, pad.l - m - 0.16f * s);
        float shTop = 0.035f * H;
        placeShoulders(l2, l1, m, shTop, shW, G_L_SHOULDER);
        placeShoulders(r2, r1, W - m - shW, shTop, shW, G_R_SHOULDER);

        // Create / Options 固定在肩键和触摸板之间（不随肩键组移动）
        float cx = (m + shW + pad.l) / 2, pw = 0.055f * s, ph = 0.11f * s;
        create.set(cx - pw / 2, 0.10f * H, cx + pw / 2, 0.10f * H + ph);
        cx = (W - m - shW + pad.r) / 2;
        options.set(cx - pw / 2, create.t, cx + pw / 2, create.b);

        float baseDpadR = 0.19f * s;
        float baseDpadX = m + 0.06f * s + baseDpadR;
        float shBottom = shTop + 0.245f * s;
        float baseDpadY = Math.max(0.55f * H, shBottom + baseDpadR + 0.05f * s);
        dpadR = baseDpadR * scale[G_DPAD];
        dpadX = clampX(baseDpadX, G_DPAD);
        dpadY = clampY(baseDpadY, G_DPAD);
        btnR = 0.072f * s * scale[G_FACE];
        faceOff = 0.142f * s * scale[G_FACE];
        faceX = clampX(W - baseDpadX, G_FACE);
        faceY = clampY(baseDpadY, G_FACE);

        float baseStickR = 0.175f * s;
        float baseLsY = H - baseStickR - 0.07f * s;
        float baseLsX = Math.max(baseDpadX + baseDpadR + baseStickR * 0.95f, 0.355f * W);
        baseLsX = Math.min(baseLsX, W / 2 - baseStickR - 0.13f * s);
        lStickR = baseStickR * scale[G_L_STICK];
        rStickR = baseStickR * scale[G_R_STICK];
        lReach = 0.14f * s * scale[G_L_STICK];
        rReach = 0.14f * s * scale[G_R_STICK];
        lsX = clampX(baseLsX, G_L_STICK);
        lsY = clampY(baseLsY, G_L_STICK);
        rsX = clampX(W - baseLsX, G_R_STICK);
        rsY = clampY(baseLsY, G_R_STICK);

        psR = 0.058f * s;
        psX = W / 2;
        psY = pad.b + 0.075f * s + psR;
        float mw = 0.10f * s, mh = 0.05f * s, my = psY + psR + 0.05f * s;
        mic.set(W / 2 - mw / 2, my, W / 2 + mw / 2, my + mh);
    }

    private void placeShoulders(Box upper, Box lower, float left, float top, float w, int g) {
        float k = scale[g];
        float h2 = 0.115f * s * k, gap = 0.03f * s * k, h1 = 0.10f * s * k, ww = w * k;
        float total = h2 + gap + h1;
        float cx = clampX(left + w / 2, g), cy = clampY(top + 0.245f * s / 2, g);
        upper.set(cx - ww / 2, cy - total / 2, cx + ww / 2, cy - total / 2 + h2);
        lower.set(cx - ww / 2, upper.b + gap, cx + ww / 2, upper.b + gap + h1);
    }

    /** 基准位置加上用户偏移，中心不许出屏；出屏时把偏移改回屏内，拖回来不会有一段没反应。 */
    private float clampX(float base, int g) {
        float v = base + dx[g] * s, c = Math.max(0, Math.min(W, v));
        if (c != v) dx[g] = (c - base) / s;
        return c;
    }

    private float clampY(float base, int g) {
        float v = base + dy[g] * s, c = Math.max(0, Math.min(H, v));
        if (c != v) dy[g] = (c - base) / s;
        return c;
    }

    // ================= 判定区 =================

    /** 十字键判定圆的半径。朝摇杆那一侧另有收窄，见 {@link #inDpadZone}。 */
    public float dpadZone() {
        return dpadR * 1.25f;
    }

    public float faceZone() {
        return faceOff + btnR * 1.8f;
    }

    /** 十字键判定：圆内，且朝左摇杆方向不超出十字键本身，免得想推摇杆的拇指按到方向键。 */
    public boolean inDpadZone(float x, float y) {
        float ddx = x - dpadX, ddy = y - dpadY;
        if (ddx * ddx + ddy * ddy > dpadZone() * dpadZone()) return false;
        return projection(ddx, ddy, lsX - dpadX, lsY - dpadY) < dpadR;
    }

    public boolean inFaceZone(float x, float y) {
        float ddx = x - faceX, ddy = y - faceY;
        if (ddx * ddx + ddy * ddy > faceZone() * faceZone()) return false;
        return projection(ddx, ddy, rsX - faceX, rsY - faceY) < faceOff + btnR;
    }

    private static float projection(float x, float y, float tx, float ty) {
        float len = (float) Math.hypot(tx, ty);
        return len < 1e-3f ? 0 : (x * tx + y * ty) / len;
    }

    /** 肩键组的判定区：两排键外扩一圈（extra 倍），贴着屏幕上沿时一直延到边。 */
    public boolean inShoulderZone(float x, float y, Box upper, Box lower, float extra) {
        float padX = lower.w() * 0.12f * extra, padY = lower.h() * 0.35f * extra;
        float left = Math.min(lower.l, upper.l) - padX, right = Math.max(lower.r, upper.r) + padX;
        float top = upper.t < 0.2f * H ? 0 : upper.t - padY, bottom = lower.b + padY;
        return x >= left && x <= right && y >= top && y <= bottom;
    }

    public float stickReach(int side) {
        return side == 0 ? lReach : rReach;
    }

    public float stickRadius(int side) {
        return side == 0 ? lStickR : rStickR;
    }

    /** 落点归哪一个摇杆：按两个摇杆静止位置的中线分。 */
    public int stickSide(float x) {
        return x < (lsX + rsX) / 2 ? 0 : 1;
    }

    // ================= 布局编辑 =================

    /** 编辑布局时手指按到了哪一组，没有返回 -1。 */
    public int groupAt(float x, float y) {
        if (inShoulderZone(x, y, l2, l1, 1)) return G_L_SHOULDER;
        if (inShoulderZone(x, y, r2, r1, 1)) return G_R_SHOULDER;
        if (dist(x, y, dpadX, dpadY) < dpadR * 1.1f) return G_DPAD;
        if (dist(x, y, faceX, faceY) < faceOff + btnR * 1.3f) return G_FACE;
        if (dist(x, y, lsX, lsY) < lStickR * 1.1f) return G_L_STICK;
        if (dist(x, y, rsX, rsY) < rStickR * 1.1f) return G_R_STICK;
        return -1;
    }

    public void moveGroup(int g, float pxDx, float pxDy) {
        dx[g] += pxDx / s;
        dy[g] += pxDy / s;
        compute(W, H);
    }

    public void setScale(int g, float k) {
        scale[g] = Math.max(MIN_SCALE, Math.min(MAX_SCALE, k));
        compute(W, H);
    }

    public boolean isDefault() {
        for (int g = 0; g < GROUPS; g++) {
            if (dx[g] != 0 || dy[g] != 0 || scale[g] != 1) return false;
        }
        return true;
    }

    public String serialize() {
        StringBuilder sb = new StringBuilder();
        for (int g = 0; g < GROUPS; g++) {
            if (g > 0) sb.append(';');
            sb.append(String.format(Locale.ROOT, "%.4f,%.4f,%.3f", dx[g], dy[g], scale[g]));
        }
        return sb.toString();
    }

    /** 读回 {@link #serialize()} 的结果；格式不对就保持默认。 */
    public void deserialize(String str) {
        resetAdjustments();
        if (str == null || str.isEmpty()) return;
        String[] parts = str.split(";");
        if (parts.length != GROUPS) return;
        float[] ndx = new float[GROUPS], ndy = new float[GROUPS], nsc = new float[GROUPS];
        try {
            for (int g = 0; g < GROUPS; g++) {
                String[] v = parts[g].split(",");
                if (v.length != 3) return;
                ndx[g] = Float.parseFloat(v[0]);
                ndy[g] = Float.parseFloat(v[1]);
                nsc[g] = Float.parseFloat(v[2]);
                if (Float.isNaN(ndx[g]) || Float.isNaN(ndy[g]) || Float.isNaN(nsc[g])) return;
            }
        } catch (NumberFormatException e) {
            return;
        }
        for (int g = 0; g < GROUPS; g++) {
            dx[g] = Math.max(-2.5f, Math.min(2.5f, ndx[g]));
            dy[g] = Math.max(-1.5f, Math.min(1.5f, ndy[g]));
            scale[g] = Math.max(MIN_SCALE, Math.min(MAX_SCALE, nsc[g]));
        }
    }

    static float dist(float x, float y, float cx, float cy) {
        return (float) Math.hypot(x - cx, y - cy);
    }
}
