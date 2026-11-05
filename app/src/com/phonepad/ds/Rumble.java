package com.phonepad.ds;

import android.content.Context;
import android.media.AudioAttributes;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;

/** 把电脑发来的手柄震动换成手机马达震动；没在震的时候按键给一下短促反馈。只在主线程调用。 */
final class Rumble {

    private static final AudioAttributes ATTRS = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();

    private final Vibrator vib;
    private final boolean amplitude;
    private int level;
    private long startedAt;

    Rumble(Context c) {
        Vibrator v = (Vibrator) c.getSystemService(Context.VIBRATOR_SERVICE);
        vib = (v != null && v.hasVibrator()) ? v : null;
        amplitude = vib != null && vib.hasAmplitudeControl();
    }

    /** left = 低频大马达，right = 高频小马达，0..255。 */
    void set(int left, int right) {
        if (vib == null) return;
        int v = Math.max(left, right * 3 / 4);
        int lv = v < 8 ? 0 : 1 + v * 15 / 255;   // 分 16 档，档位不变就不重发，避免马达咔哒声
        if (!amplitude && lv < 4) lv = 0;
        long now = android.os.SystemClock.uptimeMillis();
        if (lv == level && (lv == 0 || now - startedAt < 25_000)) return;
        level = lv;
        startedAt = now;
        if (lv == 0) {
            vib.cancel();
            return;
        }
        int a = amplitude ? Math.min(255, lv * 16) : VibrationEffect.DEFAULT_AMPLITUDE;
        vib.vibrate(VibrationEffect.createOneShot(30_000, a), ATTRS);
    }

    void tick() {
        if (vib == null || level != 0) return;
        VibrationEffect e;
        if (Build.VERSION.SDK_INT >= 29) e = VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK);
        else e = VibrationEffect.createOneShot(12, amplitude ? 110 : VibrationEffect.DEFAULT_AMPLITUDE);
        vib.vibrate(e, ATTRS);
    }

    void stop() {
        level = 0;
        if (vib != null) vib.cancel();
    }
}
