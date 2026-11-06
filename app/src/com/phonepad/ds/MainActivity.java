package com.phonepad.ds;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.drawable.ColorDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.Surface;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Toast;

import com.phonepad.ds.core.DualSense;
import com.phonepad.ds.core.PadState;
import com.phonepad.ds.core.TouchLogic;
import com.phonepad.ds.core.UsbIpServer;

import java.security.SecureRandom;

public final class MainActivity extends Activity
        implements UsbIpServer.Listener, SensorEventListener, PadView.Callbacks {

    /** 手机上的 USB/IP 端口。电脑端 adb forward tcp:32400 tcp:3240。 */
    static final int PORT = 3240;

    private static final int DIALOG_THEME = android.R.style.Theme_DeviceDefault_Dialog_Alert;
    /** 音量键可以映射成的按键，0 = 不映射（照常调音量）。 */
    private static final int[] VOL_BITS = {0, PadState.L2, PadState.R2, PadState.L1, PadState.R1, PadState.L3, PadState.R3,
            PadState.CROSS, PadState.CIRCLE, PadState.SQUARE, PadState.TRIANGLE};
    private static final String[] VOL_NAMES = {"不映射（照常调音量）", "L2", "R2", "L1", "R1", "L3", "R3", "×", "○", "□", "△"};

    private final Handler main = new Handler(Looper.getMainLooper());
    private PadState state;
    private UsbIpServer server;
    private PadView view;
    private Rumble rumble;
    private SharedPreferences prefs;
    private SensorManager sensors;
    private Sensor gyro, accel;
    private DisplayManager displays;
    private boolean motionOn, vibOn, hapticsOn, resumed;
    private int volUp, volDown;
    private boolean volUpHeld, volDownHeld;
    private volatile int rotation = Surface.ROTATION_90;
    private final float[] lastAccel = {0, 0, SensorManager.GRAVITY_EARTH};
    private long lastBack;

    private final DisplayManager.DisplayListener displayListener = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int id) {
        }

        @Override
        public void onDisplayRemoved(int id) {
        }

        @Override
        public void onDisplayChanged(int id) {
            updateRotation(); // 两个横屏方向之间转 180° 不会触发 onConfigurationChanged，只能靠这里
        }
    };

    private final Runnable batteryTick = new Runnable() {
        @Override
        public void run() {
            Intent i = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (i != null) {
                int level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 100);
                int scale = Math.max(1, i.getIntExtra(BatteryManager.EXTRA_SCALE, 100));
                int st = i.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
                state.setBattery(level * 100 / scale, st == BatteryManager.BATTERY_STATUS_CHARGING,
                        st == BatteryManager.BATTERY_STATUS_FULL);
            }
            main.postDelayed(this, 30_000);
        }
    };

    @Override
    protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("pad", Context.MODE_PRIVATE);
        motionOn = prefs.getBoolean("motion", true);
        vibOn = prefs.getBoolean("vibration", true);
        hapticsOn = prefs.getBoolean("haptics", true);
        volUp = validVol(prefs.getInt("volUp", PadState.L2));
        volDown = validVol(prefs.getInt("volDown", PadState.R2));

        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        w.setBackgroundDrawable(new ColorDrawable(0xFF101216));
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }

        state = new PadState();
        state.resetMotion();
        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        accel = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        view = new PadView(this, state, this);
        view.setLayoutAdjustments(prefs.getString("layout", null));
        updateVolumeHint();
        setContentView(view);
        rumble = new Rumble(this);

        displays = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        displays.registerDisplayListener(displayListener, main);
        updateRotation();

        server = new UsbIpServer(state, loadMac(), this, PORT);
        server.start();
        batteryTick.run();
        hideSystemBars();
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        hideSystemBars();
        updateRotation();
        registerSensors();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        sensors.unregisterListener(this);
        state.resetMotion();
        releaseVolumeKeys();
        view.cancelTouches();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        main.removeCallbacks(batteryTick);
        displays.unregisterDisplayListener(displayListener);
        server.stop();
        rumble.stop();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemBars();
        else releaseVolumeKeys(); // 弹出对话框等情况下收不到松开事件
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (view.isEditing()) {
            onEditDone();
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastBack < 2000) {
            super.onBackPressed();
        } else {
            lastBack = now;
            Toast.makeText(this, "再按一次返回键退出（退出后电脑上的手柄会断开）", Toast.LENGTH_SHORT).show();
        }
    }

    @SuppressWarnings("deprecation")
    private void hideSystemBars() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
    }

    @SuppressWarnings("deprecation")
    private void updateRotation() {
        rotation = getWindowManager().getDefaultDisplay().getRotation();
    }

    private byte[] loadMac() {
        String hex = prefs.getString("mac", null);
        byte[] mac = new byte[6];
        if (hex != null && hex.length() == 12) {
            for (int i = 0; i < 6; i++) mac[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            return mac;
        }
        new SecureRandom().nextBytes(mac);
        mac[0] = (byte) ((mac[0] & 0xFC) | 0x02); // 本地管理、单播
        StringBuilder sb = new StringBuilder();
        for (byte b : mac) sb.append(String.format("%02x", b & 0xFF));
        prefs.edit().putString("mac", sb.toString()).apply();
        return mac;
    }

    // ================= 体感 =================

    private void registerSensors() {
        sensors.unregisterListener(this);
        if (!motionOn || gyro == null || !resumed) {
            state.resetMotion();
            return;
        }
        sensors.registerListener(this, gyro, 5000);       // 200Hz，再高要额外权限
        if (accel != null) sensors.registerListener(this, accel, 5000);
    }

    @Override
    public void onSensorChanged(SensorEvent e) {
        float[] v = e.values;
        if (e.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            lastAccel[0] = v[0];
            lastAccel[1] = v[1];
            lastAccel[2] = v[2];
            return; // 陀螺仪那边一起发
        }
        if (e.sensor.getType() != Sensor.TYPE_GYROSCOPE) return;
        float[] g = toPad(v[0], v[1], v[2]);
        float[] a = toPad(lastAccel[0], lastAccel[1], lastAccel[2]);
        float k = (float) Math.toDegrees(1) * PadState.GYRO_PER_DPS;     // rad/s -> 原始值
        float q = PadState.ACCEL_PER_G / SensorManager.GRAVITY_EARTH;     // m/s² -> 原始值
        state.setMotion(Math.round(g[0] * k), Math.round(g[1] * k), Math.round(g[2] * k),
                Math.round(a[0] * q), Math.round(a[1] * q), Math.round(a[2] * q));
    }

    /**
     * 手机坐标系 -> DualSense 坐标系。把横屏手机当成手柄正面：
     * X = 屏幕向右，Y = 垂直屏幕向外（手柄平放时朝上），Z = 屏幕向下（朝向玩家）。
     */
    private float[] toPad(float x, float y, float z) {
        float sx, sy;
        switch (rotation) {
            case Surface.ROTATION_90: sx = -y; sy = x; break;
            case Surface.ROTATION_180: sx = -x; sy = -y; break;
            case Surface.ROTATION_270: sx = y; sy = -x; break;
            default: sx = x; sy = y; break;
        }
        return new float[]{sx, z, -sy};
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    // ================= 界面回调 =================

    @Override
    public void onHaptic(int kind) {
        if (!hapticsOn) return;
        if (kind == TouchLogic.HAPTIC_PRESS) rumble.press();
        else rumble.tick();
    }

    @Override
    public void onLayoutEdited(String serialized) {
        prefs.edit().putString("layout", serialized).apply();
    }

    @Override
    public void onEditDone() {
        view.setEditing(false);
        Toast.makeText(this, "布局已保存", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onOpenSettings() {
        view.cancelTouches();
        if (isFinishing()) return;
        String[] items = {
                "体感：" + (gyro == null ? "这台手机没有陀螺仪" : (motionOn ? "开" : "关")),
                "游戏震动（电脑发来的）：" + (vibOn ? "开" : "关"),
                "按键触感：" + (hapticsOn ? "开" : "关"),
                "音量+ 键：" + volName(volUp),
                "音量− 键：" + volName(volDown),
                "调整布局（拖动、双指缩放）",
                "恢复默认布局",
                "退出（电脑上的手柄会断开）",
        };
        new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle("设置")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        onSetting(which);
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private void onSetting(int which) {
        switch (which) {
            case 0:
                if (gyro == null) {
                    Toast.makeText(this, "这台手机没有陀螺仪，体感用不了", Toast.LENGTH_SHORT).show();
                    return;
                }
                motionOn = !motionOn;
                prefs.edit().putBoolean("motion", motionOn).apply();
                registerSensors();
                onOpenSettings();
                break;
            case 1:
                vibOn = !vibOn;
                prefs.edit().putBoolean("vibration", vibOn).apply();
                if (!vibOn) rumble.stop();
                onOpenSettings();
                break;
            case 2:
                hapticsOn = !hapticsOn;
                prefs.edit().putBoolean("haptics", hapticsOn).apply();
                if (hapticsOn) rumble.press();
                onOpenSettings();
                break;
            case 3:
            case 4:
                chooseVolume(which == 3);
                break;
            case 5:
                view.setEditing(true);
                Toast.makeText(this, "拖动部件移动位置，两根手指捏合缩放，调好点“完成”", Toast.LENGTH_LONG).show();
                break;
            case 6:
                view.resetLayout();
                Toast.makeText(this, "已恢复默认布局", Toast.LENGTH_SHORT).show();
                break;
            default:
                finish();
                break;
        }
    }

    private void chooseVolume(final boolean up) {
        int cur = up ? volUp : volDown, sel = 0;
        for (int i = 0; i < VOL_BITS.length; i++) if (VOL_BITS[i] == cur) sel = i;
        new AlertDialog.Builder(this, DIALOG_THEME)
                .setTitle(up ? "音量+ 键当作" : "音量− 键当作")
                .setSingleChoiceItems(VOL_NAMES, sel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if (up) volUp = VOL_BITS[which];
                        else volDown = VOL_BITS[which];
                        prefs.edit().putInt("volUp", volUp).putInt("volDown", volDown).apply();
                        releaseVolumeKeys();
                        updateVolumeHint();
                        d.dismiss();
                        onOpenSettings();
                    }
                })
                .setNegativeButton("取消", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        onOpenSettings();
                    }
                })
                .show();
    }

    // ================= 音量键当实体按键 =================

    private static int validVol(int bit) {
        for (int b : VOL_BITS) if (b == bit) return bit;
        return 0;
    }

    private static String volName(int bit) {
        for (int i = 0; i < VOL_BITS.length; i++) if (VOL_BITS[i] == bit) return VOL_NAMES[i];
        return VOL_NAMES[0];
    }

    private void updateVolumeHint() {
        if (volUp == 0 && volDown == 0) {
            view.setVolumeHint("");
            return;
        }
        String up = volUp == 0 ? "调音量" : volName(volUp), down = volDown == 0 ? "调音量" : volName(volDown);
        view.setVolumeHint("手机音量键：音量+ = " + up + "，音量− = " + down);
    }

    /** 音量键返回映射的按键位，不映射返回 0，不是音量键返回 -1。 */
    private int volumeBit(int keyCode) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) return volUp;
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) return volDown;
        return -1;
    }

    private void pushVolumeKeys() {
        view.setExternalButtons((volUpHeld ? volUp : 0) | (volDownHeld ? volDown : 0));
    }

    private void releaseVolumeKeys() {
        if (!volUpHeld && !volDownHeld) return;
        volUpHeld = volDownHeld = false;
        pushVolumeKeys();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int bit = volumeBit(keyCode);
        if (bit <= 0) return super.onKeyDown(keyCode, event);
        if (event.getRepeatCount() == 0) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) volUpHeld = true;
            else volDownHeld = true;
            pushVolumeKeys();
        }
        return true; // 吃掉，不让系统调音量
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        int bit = volumeBit(keyCode);
        if (bit <= 0) return super.onKeyUp(keyCode, event);
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) volUpHeld = false;
        else volDownHeld = false;
        pushVolumeKeys();
        return true;
    }

    // ================= USB/IP 回调（在网络线程里） =================

    @Override
    public void onConnectionChanged(final boolean connected) {
        main.post(new Runnable() {
            @Override
            public void run() {
                view.setConnected(connected);
                if (!connected) rumble.stop();
            }
        });
    }

    @Override
    public void onOutput(final DualSense.Output o) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (o.hasRumble && vibOn) rumble.set(o.rumbleLeft, o.rumbleRight);
                if (o.hasLightbar) view.setLightbar(o.red, o.green, o.blue);
                if (o.hasPlayerLeds) view.setPlayerLeds(o.playerLeds);
                if (o.hasMicLed) view.setMicLed(o.micLed);
            }
        });
    }

    @Override
    public void onServerError(final String message) {
        main.post(new Runnable() {
            @Override
            public void run() {
                view.setError(message);
            }
        });
    }
}
