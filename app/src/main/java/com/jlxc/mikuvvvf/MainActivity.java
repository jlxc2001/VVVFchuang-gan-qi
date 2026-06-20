package com.jlxc.mikuvvvf;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int ID_SAMPLE = 1000;
    private static final int ID_GTO = 1001;
    private static final int ID_IGBT = 1002;
    private static final int ID_SIEMENS = 1003;
    private static final int ID_AIRCRAFT = 1004;
    private static final int ID_POP_BANG = 1005;
    private static final int ID_NA = 1006;
    private static final int ID_ROTARY = 1007;
    private static final int ID_SUPERCHARGED_V8 = 1008;

    private static final int ID_MODE_FUSION = 2000;
    private static final int ID_MODE_ACCEL = 2001;
    private static final int ID_MODE_GPS = 2002;
    private static final int ID_MODE_HOOK = 2003;
    private static final int ID_MODE_MANUAL = 2004;

    private HudView hudView;
    private TextView speedText;
    private TextView unitText;
    private TextView settingsInfoText;
    private SeekBar speedSeek;
    private SeekBar volumeSeek;
    private Switch muteSwitch;
    private RadioButton modeFusionButton;
    private RadioButton modeAccelButton;
    private RadioButton modeGpsButton;
    private RadioButton modeHookButton;
    private RadioButton modeManualButton;
    private RadioButton sampleButton;
    private RadioButton gtoButton;
    private RadioButton igbtButton;
    private RadioButton siemensButton;
    private RadioButton aircraftButton;
    private RadioButton popBangButton;
    private RadioButton naButton;
    private RadioButton rotaryButton;
    private RadioButton superchargedV8Button;
    private Button autoButton;
    private Dialog settingsDialog;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean autoTest = false;
    private float autoSpeed = 0f;
    private int autoDirection = 1;
    private float displaySpeed = 0f;
    private String currentStyle = "SAMPLE_VVVF_0_140";
    private String currentSource = "PHONE_GPS+ACCEL";
    private String currentInputMode = "PHONE_FUSION";
    private String currentSensorStatus = "Phone sensor idle";

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (intent == null || !VvvfSoundService.ACTION_STATUS.equals(intent.getAction())) return;
            float speed = intent.getFloatExtra(VvvfSoundService.EXTRA_STATUS_SPEED, displaySpeed);
            displaySpeed = speed;
            currentStyle = intent.getStringExtra(VvvfSoundService.EXTRA_STATUS_STYLE);
            currentSource = intent.getStringExtra(VvvfSoundService.EXTRA_STATUS_SOURCE);
            currentInputMode = intent.getStringExtra(VvvfSoundService.EXTRA_STATUS_INPUT_MODE);
            currentSensorStatus = intent.getStringExtra(VvvfSoundService.EXTRA_STATUS_SENSOR);
            updateHudSpeed(speed);
            updateInfoText();
        }
    };

    private final Runnable autoRunnable = new Runnable() {
        @Override public void run() {
            if (!autoTest) return;
            autoSpeed += autoDirection * 0.10f;
            if (autoSpeed >= 140f) { autoSpeed = 140f; autoDirection = -1; }
            if (autoSpeed <= 0f) { autoSpeed = 0f; autoDirection = 1; }
            if (speedSeek != null) speedSeek.setProgress(Math.round(autoSpeed * 10f));
            sendSpeed(autoSpeed);
            handler.postDelayed(this, 90);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        maybeRequestRuntimePermissions();
        setContentView(buildHudView());
        startSoundService();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(VvvfSoundService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(statusReceiver, filter);
    }

    @Override
    protected void onStop() {
        try { unregisterReceiver(statusReceiver); } catch (Throwable ignored) {}
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        autoTest = false;
        handler.removeCallbacks(autoRunnable);
        if (settingsDialog != null) {
            try { settingsDialog.dismiss(); } catch (Throwable ignored) {}
            settingsDialog = null;
        }
        super.onDestroy();
    }

    private View buildHudView() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.setKeepScreenOn(true);
        root.setOnLongClickListener(v -> {
            showSettingsDialog();
            return true;
        });

        hudView = new HudView(this);
        root.addView(hudView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout speedBox = new LinearLayout(this);
        speedBox.setOrientation(LinearLayout.VERTICAL);
        speedBox.setGravity(Gravity.CENTER);
        speedBox.setPadding(dp(12), dp(12), dp(12), dp(12));

        speedText = new TextView(this);
        speedText.setText("0.0");
        speedText.setTextSize(116);
        speedText.setGravity(Gravity.CENTER);
        speedText.setIncludeFontPadding(false);
        speedText.setTextColor(Color.rgb(60, 255, 245));
        speedText.setShadowLayer(dp(12), 0, 0, Color.rgb(0, 210, 255));
        speedBox.addView(speedText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        unitText = new TextView(this);
        unitText.setText("km/h");
        unitText.setTextSize(26);
        unitText.setGravity(Gravity.CENTER);
        unitText.setTextColor(Color.rgb(120, 245, 255));
        unitText.setLetterSpacing(0.18f);
        unitText.setShadowLayer(dp(7), 0, 0, Color.rgb(0, 160, 255));
        speedBox.addView(unitText, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        FrameLayout.LayoutParams boxLp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        root.addView(speedBox, boxLp);

        return root;
    }

    private void updateHudSpeed(float speed) {
        if (Float.isNaN(speed) || Float.isInfinite(speed)) speed = 0f;
        speed = Math.max(0f, Math.min(260f, speed));
        if (speedText != null) speedText.setText(String.format(Locale.US, "%.1f", speed));
        if (hudView != null) hudView.setSpeed(speed);
    }

    private void showSettingsDialog() {
        if (settingsDialog != null && settingsDialog.isShowing()) return;

        settingsDialog = new Dialog(this);
        settingsDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        settingsDialog.setContentView(buildSettingsContent());
        Window window = settingsDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams();
            lp.copyFrom(window.getAttributes());
            lp.width = Math.min(getResources().getDisplayMetrics().widthPixels - dp(64), dp(760));
            lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
            lp.gravity = Gravity.CENTER;
            window.setAttributes(lp);
        }
        settingsDialog.show();
        updateInfoText();
    }

    private View buildSettingsContent() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        scroll.setBackgroundColor(Color.TRANSPARENT);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(18), dp(20), dp(18));
        root.setBackgroundColor(Color.rgb(3, 13, 18));
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Miku VVVF 战斗 HUD 设置");
        title.setTextSize(22);
        title.setTextColor(Color.rgb(80, 255, 245));
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        title.setShadowLayer(dp(7), 0, 0, Color.rgb(0, 160, 255));
        root.addView(title, matchWrap());

        TextView sub = new TextView(this);
        sub.setText("调试项已隐藏到这里 · 长按主界面打开 · 默认主界面只显示车速");
        sub.setTextSize(12);
        sub.setTextColor(Color.rgb(130, 210, 215));
        sub.setGravity(Gravity.CENTER_HORIZONTAL);
        sub.setPadding(0, dp(6), 0, dp(12));
        root.addView(sub, matchWrap());

        LinearLayout buttons1 = new LinearLayout(this);
        buttons1.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(buttons1, matchWrap());

        Button start = tacticalButton("启动服务");
        buttons1.addView(start, weightWrap(1));
        start.setOnClickListener(v -> startSoundService());

        Button stop = tacticalButton("停止服务");
        buttons1.addView(stop, weightWrap(1));
        stop.setOnClickListener(v -> {
            autoTest = false;
            updateAutoButtonText();
            Intent i = new Intent(this, VvvfSoundService.class);
            i.setAction(VvvfSoundService.ACTION_STOP);
            startServiceCompat(i);
        });

        Button zero = tacticalButton("归零");
        buttons1.addView(zero, weightWrap(1));
        zero.setOnClickListener(v -> {
            autoTest = false;
            updateAutoButtonText();
            if (speedSeek != null) speedSeek.setProgress(0);
            sendSpeed(0f);
        });

        TextView sourceLabel = label("车速数据源");
        root.addView(sourceLabel, matchWrap());

        RadioGroup sourceGroup = new RadioGroup(this);
        sourceGroup.setOrientation(RadioGroup.VERTICAL);
        sourceGroup.setPadding(0, 0, 0, dp(2));
        modeFusionButton = addModeRadio(sourceGroup, ID_MODE_FUSION, "GPS + 加速度融合（推荐手机上车用，GPS 定绝对速度，加速度补响应）");
        modeAccelButton = addModeRadio(sourceGroup, ID_MODE_ACCEL, "仅加速度估算（无 GPS 也能响，但会慢慢漂移，建议先校准）");
        modeGpsButton = addModeRadio(sourceGroup, ID_MODE_GPS, "仅 GPS 车速（最稳，但速度变化会比实车慢一点）");
        modeHookButton = addModeRadio(sourceGroup, ID_MODE_HOOK, "MainApp Hook 原车车速（车机专用，最低 500ms 轮询）");
        modeManualButton = addModeRadio(sourceGroup, ID_MODE_MANUAL, "手动 / UDP / ADB 调试");
        setCheckedModeButton(currentInputMode == null ? "PHONE_FUSION" : currentInputMode);
        root.addView(sourceGroup, matchWrap());
        sourceGroup.setOnCheckedChangeListener((group, checkedId) -> sendInputMode(modeFromId(checkedId)));

        TextView sourceHint = smallHint("手机模式会把传感器速度喂给原来的音频平滑层；VVVF 采样窗口连续滑动算法没有删。加速度模式依赖手机固定安装，第一次加速后会自动锁定前进轴向。方向反了就点下面的反转。");
        root.addView(sourceHint, matchWrap());

        LinearLayout sensorButtons = new LinearLayout(this);
        sensorButtons.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(sensorButtons, matchWrap());
        Button sensorReset = tacticalButton("加速度校准/归零");
        sensorButtons.addView(sensorReset, weightWrap(1));
        sensorReset.setOnClickListener(v -> sendSensorReset());
        Button sensorInvert = tacticalButton("反转加速度方向");
        sensorButtons.addView(sensorInvert, weightWrap(1));
        sensorInvert.setOnClickListener(v -> sendToggleAccelDirection());

        TextView speedLabel = label("离车调试速度");
        root.addView(speedLabel, matchWrap());
        speedSeek = new SeekBar(this);
        speedSeek.setMax(2400);
        speedSeek.setProgress(Math.round(displaySpeed * 10f));
        speedSeek.setPadding(0, dp(6), 0, dp(4));
        root.addView(speedSeek, matchWrap());
        speedSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float speed = progress / 10f;
                if (fromUser) {
                    autoTest = false;
                    updateAutoButtonText();
                    sendSpeed(speed);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        autoButton = tacticalButton("自动 0→140→0 测试（会切到手动模式）");
        root.addView(autoButton, matchWrap());
        autoButton.setOnClickListener(v -> {
            autoTest = !autoTest;
            if (autoTest) {
                sendInputMode("MANUAL");
                if (modeManualButton != null) modeManualButton.setChecked(true);
                autoSpeed = speedSeek == null ? displaySpeed : speedSeek.getProgress() / 10f;
                autoDirection = autoSpeed >= 140f ? -1 : 1;
                handler.removeCallbacks(autoRunnable);
                handler.post(autoRunnable);
            } else {
                handler.removeCallbacks(autoRunnable);
            }
            updateAutoButtonText();
        });

        TextView styleLabel = label("声音风格");
        root.addView(styleLabel, matchWrap());

        RadioGroup styleGroup = new RadioGroup(this);
        styleGroup.setOrientation(RadioGroup.VERTICAL);
        styleGroup.setPadding(0, 0, 0, dp(2));

        sampleButton = addStyleRadio(styleGroup, ID_SAMPLE, "真实采样 VVVF 0→140km/h / 你提供的录音");
        siemensButton = addStyleRadio(styleGroup, ID_SIEMENS, "广东地铁西门子 GTO / 广州 1 号线 A1 味");
        gtoButton = addStyleRadio(styleGroup, ID_GTO, "通用 GTO 粗糙老电车");
        igbtButton = addStyleRadio(styleGroup, ID_IGBT, "通用 IGBT 顺滑现代电车");
        aircraftButton = addStyleRadio(styleGroup, ID_AIRCRAFT, "飞机 / 涡扇引擎推进感");
        popBangButton = addStyleRadio(styleGroup, ID_POP_BANG, "改偏时点火 / 涡轮回火放炮");
        naButton = addStyleRadio(styleGroup, ID_NA, "自然吸气 / 高转进气声");
        rotaryButton = addStyleRadio(styleGroup, ID_ROTARY, "转子发动机 / 高转蜂鸣");
        superchargedV8Button = addStyleRadio(styleGroup, ID_SUPERCHARGED_V8, "地狱猫风格 / 机械增压 V8");
        setCheckedStyleButton(currentStyle == null ? "SAMPLE_VVVF_0_140" : currentStyle);
        root.addView(styleGroup, matchWrap());
        styleGroup.setOnCheckedChangeListener((group, checkedId) -> sendStyle(styleFromId(checkedId)));

        TextView volLabel = label("音量");
        root.addView(volLabel, matchWrap());
        volumeSeek = new SeekBar(this);
        volumeSeek.setMax(100);
        volumeSeek.setProgress(55);
        root.addView(volumeSeek, matchWrap());
        volumeSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) sendVolume(progress / 100f);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        muteSwitch = new Switch(this);
        muteSwitch.setText("静音");
        muteSwitch.setTextSize(15);
        muteSwitch.setTextColor(Color.rgb(210, 250, 250));
        root.addView(muteSwitch, matchWrap());
        muteSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                sendMute(isChecked);
            }
        });

        settingsInfoText = new TextView(this);
        settingsInfoText.setTextSize(12);
        settingsInfoText.setTextColor(Color.rgb(120, 200, 205));
        settingsInfoText.setPadding(0, dp(12), 0, dp(10));
        settingsInfoText.setSingleLine(false);
        root.addView(settingsInfoText, matchWrap());
        updateInfoText();

        Button close = tacticalButton("关闭设置");
        root.addView(close, matchWrap());
        close.setOnClickListener(v -> {
            if (settingsDialog != null) settingsDialog.dismiss();
        });

        return scroll;
    }

    private void updateAutoButtonText() {
        if (autoButton != null) autoButton.setText(autoTest ? "停止自动测试" : "自动 0→140→0 测试（会切到手动模式）");
    }

    private Button tacticalButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(Color.rgb(15, 245, 235));
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setBackgroundColor(Color.rgb(5, 35, 43));
        return b;
    }

    private RadioButton addStyleRadio(RadioGroup group, int id, String text) {
        RadioButton b = new RadioButton(this);
        b.setId(id);
        b.setText(text);
        b.setTextSize(14);
        b.setTextColor(Color.rgb(215, 250, 250));
        group.addView(b, matchWrap());
        return b;
    }

    private RadioButton addModeRadio(RadioGroup group, int id, String text) {
        RadioButton b = new RadioButton(this);
        b.setId(id);
        b.setText(text);
        b.setTextSize(14);
        b.setTextColor(Color.rgb(215, 250, 250));
        group.addView(b, matchWrap());
        return b;
    }

    private void setCheckedModeButton(String mode) {
        String s = mode == null ? "" : mode.toUpperCase(Locale.US);
        if (s.contains("ACCEL") && !s.contains("FUSION")) modeAccelButton.setChecked(true);
        else if (s.contains("GPS") && !s.contains("FUSION")) modeGpsButton.setChecked(true);
        else if (s.contains("HOOK") || s.contains("MAINAPP")) modeHookButton.setChecked(true);
        else if (s.contains("MANUAL") || s.contains("UDP")) modeManualButton.setChecked(true);
        else modeFusionButton.setChecked(true);
    }

    private String modeFromId(int checkedId) {
        if (checkedId == ID_MODE_ACCEL) return "PHONE_ACCEL";
        if (checkedId == ID_MODE_GPS) return "PHONE_GPS";
        if (checkedId == ID_MODE_HOOK) return "MAINAPP_HOOK";
        if (checkedId == ID_MODE_MANUAL) return "MANUAL";
        return "PHONE_FUSION";
    }

    private String selectedInputMode() {
        if (modeAccelButton != null && modeAccelButton.isChecked()) return "PHONE_ACCEL";
        if (modeGpsButton != null && modeGpsButton.isChecked()) return "PHONE_GPS";
        if (modeHookButton != null && modeHookButton.isChecked()) return "MAINAPP_HOOK";
        if (modeManualButton != null && modeManualButton.isChecked()) return "MANUAL";
        return currentInputMode == null ? "PHONE_FUSION" : currentInputMode;
    }

    private void setCheckedStyleButton(String style) {
        String s = style == null ? "" : style.toUpperCase(Locale.US);
        if (s.contains("SIEMENS")) siemensButton.setChecked(true);
        else if (s.contains("IGBT")) igbtButton.setChecked(true);
        else if (s.contains("AIR")) aircraftButton.setChecked(true);
        else if (s.contains("POP")) popBangButton.setChecked(true);
        else if (s.contains("NATURAL")) naButton.setChecked(true);
        else if (s.contains("ROTARY")) rotaryButton.setChecked(true);
        else if (s.contains("SUPER")) superchargedV8Button.setChecked(true);
        else if (s.equals("GTO")) gtoButton.setChecked(true);
        else sampleButton.setChecked(true);
    }

    private String styleFromId(int checkedId) {
        if (checkedId == ID_SAMPLE) return "SAMPLE_VVVF_0_140";
        if (checkedId == ID_SIEMENS) return "SIEMENS_GZ_GTO";
        if (checkedId == ID_IGBT) return "IGBT";
        if (checkedId == ID_AIRCRAFT) return "AIRCRAFT_TURBINE";
        if (checkedId == ID_POP_BANG) return "POP_BANG_TURBO";
        if (checkedId == ID_NA) return "NATURAL_ASPIRATED";
        if (checkedId == ID_ROTARY) return "ROTARY";
        if (checkedId == ID_SUPERCHARGED_V8) return "SUPERCHARGED_V8";
        return "GTO";
    }

    private TextView label(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(15);
        v.setTextColor(Color.rgb(85, 255, 245));
        v.setPadding(0, dp(16), 0, dp(4));
        v.setLetterSpacing(0.08f);
        return v;
    }

    private TextView smallHint(String text) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(12);
        v.setTextColor(Color.rgb(120, 200, 205));
        v.setPadding(0, dp(2), 0, dp(8));
        return v;
    }

    private void startSoundService() {
        Intent i = new Intent(this, VvvfSoundService.class);
        i.setAction(VvvfSoundService.ACTION_START);
        startServiceCompat(i);
        sendVolume(volumeSeek == null ? 0.55f : volumeSeek.getProgress() / 100f);
        if (sampleButton == null || sampleButton.isChecked()) sendStyle("SAMPLE_VVVF_0_140");
        else if (siemensButton != null && siemensButton.isChecked()) sendStyle("SIEMENS_GZ_GTO");
        else if (gtoButton != null && gtoButton.isChecked()) sendStyle("GTO");
        else if (igbtButton != null && igbtButton.isChecked()) sendStyle("IGBT");
        else if (aircraftButton != null && aircraftButton.isChecked()) sendStyle("AIRCRAFT_TURBINE");
        else if (popBangButton != null && popBangButton.isChecked()) sendStyle("POP_BANG_TURBO");
        else if (naButton != null && naButton.isChecked()) sendStyle("NATURAL_ASPIRATED");
        else if (rotaryButton != null && rotaryButton.isChecked()) sendStyle("ROTARY");
        else if (superchargedV8Button != null && superchargedV8Button.isChecked()) sendStyle("SUPERCHARGED_V8");
        sendInputMode(selectedInputMode());
    }

    private void sendSpeed(float speed) {
        Intent i = new Intent(this, VvvfSoundService.class);
        i.setAction(VvvfSoundService.ACTION_SET_SPEED);
        i.putExtra(VvvfSoundService.EXTRA_SPEED, speed);
        startServiceCompat(i);
    }

    private void sendStyle(String style) {
        currentStyle = style;
        Intent i = new Intent(this, VvvfSoundService.class);
        i.setAction(VvvfSoundService.ACTION_SET_STYLE);
        i.putExtra(VvvfSoundService.EXTRA_STYLE, style);
        startServiceCompat(i);
    }

    private void sendVolume(float volume) {
        Intent i = new Intent(this, VvvfSoundService.class);
        i.setAction(VvvfSoundService.ACTION_SET_VOLUME);
        i.putExtra(VvvfSoundService.EXTRA_VOLUME, volume);
        startServiceCompat(i);
    }

    private void sendMute(boolean mute) {
        Intent i = new Intent(this, VvvfSoundService.class);
        i.setAction(VvvfSoundService.ACTION_SET_MUTE);
        i.putExtra(VvvfSoundService.EXTRA_MUTE, mute);
        startServiceCompat(i);
    }

    private void sendHookEnabled(boolean enabled) {
        Intent i = new Intent(this, VvvfSoundService.class);
        i.setAction(VvvfSoundService.ACTION_SET_HOOK);
        i.putExtra(VvvfSoundService.EXTRA_HOOK_ENABLED, enabled);
        startServiceCompat(i);
    }

    private void sendInputMode(String mode) {
        currentInputMode = TextUtils.isEmpty(mode) ? "PHONE_FUSION" : mode;
        Intent i = new Intent(this, VvvfSoundService.class);
        i.setAction(VvvfSoundService.ACTION_SET_INPUT_MODE);
        i.putExtra(VvvfSoundService.EXTRA_INPUT_MODE, currentInputMode);
        startServiceCompat(i);
    }

    private void sendSensorReset() {
        Intent i = new Intent(this, VvvfSoundService.class);
        i.setAction(VvvfSoundService.ACTION_RESET_SENSOR);
        startServiceCompat(i);
    }

    private void sendToggleAccelDirection() {
        Intent i = new Intent(this, VvvfSoundService.class);
        i.setAction(VvvfSoundService.ACTION_TOGGLE_ACCEL_DIRECTION);
        startServiceCompat(i);
    }

    private void startServiceCompat(Intent i) {
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
    }

    private void updateInfoText() {
        if (settingsInfoText == null || settingsDialog == null || !settingsDialog.isShowing()) return;
        String ip = getLocalIpv4();
        String host = TextUtils.isEmpty(ip) ? "手机IP" : ip;
        String mode = TextUtils.isEmpty(currentInputMode) ? "PHONE_FUSION" : currentInputMode;
        String src = TextUtils.isEmpty(currentSource) ? "PHONE_GPS+ACCEL" : currentSource;
        String sensor = TextUtils.isEmpty(currentSensorStatus) ? "Phone sensor idle" : currentSensorStatus;
        String text = "当前：" + (currentStyle == null ? "SAMPLE_VVVF_0_140" : currentStyle)
                + " · 数据源=" + mode + " · " + src + "\n"
                + "传感器：" + sensor + "\n\n"
                + "局域网 UDP 指令：\n"
                + "  echo MODE FUSION | nc -u " + host + " 47230\n"
                + "  echo MODE GPS | nc -u " + host + " 47230\n"
                + "  echo MODE ACCEL | nc -u " + host + " 47230\n"
                + "  echo MODE HOOK | nc -u " + host + " 47230\n"
                + "  echo MODE MANUAL | nc -u " + host + " 47230\n"
                + "  echo CALIBRATE | nc -u " + host + " 47230\n"
                + "  echo ACCEL_INVERT TOGGLE | nc -u " + host + " 47230\n"
                + "  echo SPEED 45 | nc -u " + host + " 47230\n"
                + "  echo STYLE SAMPLE_VVVF_0_140 | nc -u " + host + " 47230\n"
                + "  echo PING | nc -u " + host + " 47230\n\n"
                + "ADB 调试：\n"
                + "  adb shell am broadcast -a com.jlxc.mikuvvvf.SET_MODE --es mode PHONE_FUSION\n"
                + "  adb shell am broadcast -a com.jlxc.mikuvvvf.SET_MODE --es mode PHONE_GPS\n"
                + "  adb shell am broadcast -a com.jlxc.mikuvvvf.SET_MODE --es mode PHONE_ACCEL\n"
                + "  adb shell am broadcast -a com.jlxc.mikuvvvf.RESET_SENSOR\n"
                + "  adb shell am broadcast -a com.jlxc.mikuvvvf.TOGGLE_ACCEL\n"
                + "  adb shell am broadcast -a com.jlxc.mikuvvvf.SET_SPEED --ef speed 45\n"
                + "  adb shell am broadcast -a com.jlxc.mikuvvvf.SET_STYLE --es style SAMPLE_VVVF_0_140\n"
                + "  adb shell am broadcast -a com.jlxc.mikuvvvf.STOP\n\n"
                + "提示：GPS 模式需要定位权限；纯加速度模式没有绝对速度，会有漂移，适合短时间测试。主界面无按钮，设置入口：长按屏幕。";
        settingsInfoText.setText(text);
    }

    private String getLocalIpv4() {
        try {
            for (NetworkInterface nif : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!nif.isUp() || nif.isLoopback()) continue;
                for (java.net.InetAddress addr : Collections.list(nif.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) return addr.getHostAddress();
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    private void maybeRequestRuntimePermissions() {
        if (Build.VERSION.SDK_INT < 23) return;
        List<String> permissions = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!permissions.isEmpty()) {
            requestPermissions(permissions.toArray(new String[0]), 39);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 39) {
            startSoundService();
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams weightWrap(float weight) {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static class HudView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thin = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private float speed = 0f;

        HudView(Context context) {
            super(context);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.SQUARE);
            thin.setStyle(Paint.Style.STROKE);
            thin.setStrokeCap(Paint.Cap.SQUARE);
            glow.setStyle(Paint.Style.STROKE);
            glow.setStrokeCap(Paint.Cap.ROUND);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        }

        void setSpeed(float value) {
            speed = value;
            invalidate();
        }

        @Override protected void onDraw(Canvas c) {
            super.onDraw(c);
            int w = getWidth();
            int h = getHeight();
            float cx = w / 2f;
            float cy = h / 2f;
            float base = Math.min(w, h);

            c.drawColor(Color.BLACK);

            glow.setColor(Color.argb(55, 0, 210, 255));
            glow.setStrokeWidth(base * 0.010f);
            c.drawCircle(cx, cy, base * 0.39f, glow);
            c.drawCircle(cx, cy, base * 0.26f, glow);

            thin.setColor(Color.argb(120, 35, 255, 245));
            thin.setStrokeWidth(Math.max(1f, base * 0.0020f));
            for (int i = 0; i < 13; i++) {
                float y = h * (0.10f + i * 0.067f);
                c.drawLine(w * 0.07f, y, w * 0.18f, y, thin);
                c.drawLine(w * 0.82f, y, w * 0.93f, y, thin);
            }

            paint.setColor(Color.rgb(60, 255, 245));
            paint.setStrokeWidth(Math.max(2f, base * 0.0035f));

            float bracketW = w * 0.18f;
            float bracketH = h * 0.18f;
            drawCorner(c, w * 0.08f, h * 0.12f, bracketW, bracketH, true, true);
            drawCorner(c, w * 0.92f, h * 0.12f, bracketW, bracketH, false, true);
            drawCorner(c, w * 0.08f, h * 0.88f, bracketW, bracketH, true, false);
            drawCorner(c, w * 0.92f, h * 0.88f, bracketW, bracketH, false, false);

            path.reset();
            path.moveTo(cx - base * 0.43f, cy);
            path.lineTo(cx - base * 0.30f, cy - base * 0.07f);
            path.lineTo(cx - base * 0.15f, cy - base * 0.07f);
            path.moveTo(cx + base * 0.43f, cy);
            path.lineTo(cx + base * 0.30f, cy - base * 0.07f);
            path.lineTo(cx + base * 0.15f, cy - base * 0.07f);
            path.moveTo(cx - base * 0.43f, cy + base * 0.02f);
            path.lineTo(cx - base * 0.30f, cy + base * 0.09f);
            path.lineTo(cx - base * 0.15f, cy + base * 0.09f);
            path.moveTo(cx + base * 0.43f, cy + base * 0.02f);
            path.lineTo(cx + base * 0.30f, cy + base * 0.09f);
            path.lineTo(cx + base * 0.15f, cy + base * 0.09f);
            c.drawPath(path, paint);

            thin.setColor(Color.argb(190, 80, 255, 245));
            thin.setStrokeWidth(Math.max(1f, base * 0.0025f));
            c.drawLine(cx - base * 0.055f, cy, cx + base * 0.055f, cy, thin);
            c.drawLine(cx, cy - base * 0.055f, cx, cy + base * 0.055f, thin);
            c.drawCircle(cx, cy, base * 0.075f, thin);

            drawSpeedArc(c, cx, cy, base);
            drawScanLines(c, w, h, base);
        }

        private void drawCorner(Canvas c, float x, float y, float bw, float bh, boolean left, boolean top) {
            float sx = left ? 1f : -1f;
            float sy = top ? 1f : -1f;
            c.drawLine(x, y, x + sx * bw, y, paint);
            c.drawLine(x, y, x, y + sy * bh, paint);
            c.drawLine(x + sx * bw * 0.72f, y + sy * bh * 0.14f, x + sx * bw, y + sy * bh * 0.14f, thin);
            c.drawLine(x + sx * bw * 0.14f, y + sy * bh * 0.72f, x + sx * bw * 0.14f, y + sy * bh, thin);
        }

        private void drawSpeedArc(Canvas c, float cx, float cy, float base) {
            float r = base * 0.43f;
            RectF oval = new RectF(cx - r, cy - r, cx + r, cy + r);
            thin.setColor(Color.argb(80, 60, 255, 245));
            thin.setStrokeWidth(Math.max(1f, base * 0.0025f));
            c.drawArc(oval, 205, 130, false, thin);
            c.drawArc(oval, -25, 130, false, thin);

            paint.setStrokeWidth(Math.max(2f, base * 0.004f));
            paint.setColor(Color.rgb(60, 255, 245));
            float sweep = Math.max(0f, Math.min(1f, speed / 260f)) * 130f;
            c.drawArc(oval, 205, sweep, false, paint);

            for (int i = 0; i <= 14; i++) {
                double deg = Math.toRadians(205 + i * (130.0 / 14.0));
                float r1 = r - base * (i % 2 == 0 ? 0.038f : 0.024f);
                float r2 = r;
                float x1 = cx + (float) Math.cos(deg) * r1;
                float y1 = cy + (float) Math.sin(deg) * r1;
                float x2 = cx + (float) Math.cos(deg) * r2;
                float y2 = cy + (float) Math.sin(deg) * r2;
                c.drawLine(x1, y1, x2, y2, thin);
            }
        }

        private void drawScanLines(Canvas c, int w, int h, float base) {
            thin.setColor(Color.argb(38, 80, 255, 245));
            thin.setStrokeWidth(1f);
            float gap = Math.max(8f, base * 0.018f);
            for (float y = 0; y < h; y += gap) {
                c.drawLine(0, y, w, y, thin);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            return super.onTouchEvent(event);
        }
    }
}
