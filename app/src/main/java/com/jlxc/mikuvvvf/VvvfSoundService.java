package com.jlxc.mikuvvvf;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class VvvfSoundService extends Service {
    public static final String ACTION_START = "com.jlxc.mikuvvvf.action.START";
    public static final String ACTION_STOP = "com.jlxc.mikuvvvf.action.STOP";
    public static final String ACTION_SET_SPEED = "com.jlxc.mikuvvvf.action.SET_SPEED";
    public static final String ACTION_SET_STYLE = "com.jlxc.mikuvvvf.action.SET_STYLE";
    public static final String ACTION_SET_VOLUME = "com.jlxc.mikuvvvf.action.SET_VOLUME";
    public static final String ACTION_SET_MUTE = "com.jlxc.mikuvvvf.action.SET_MUTE";
    public static final String ACTION_SET_HOOK = "com.jlxc.mikuvvvf.action.SET_HOOK";
    public static final String ACTION_SET_INPUT_MODE = "com.jlxc.mikuvvvf.action.SET_INPUT_MODE";
    public static final String ACTION_RESET_SENSOR = "com.jlxc.mikuvvvf.action.RESET_SENSOR";
    public static final String ACTION_TOGGLE_ACCEL_DIRECTION = "com.jlxc.mikuvvvf.action.TOGGLE_ACCEL_DIRECTION";
    public static final String ACTION_STATUS = "com.jlxc.mikuvvvf.action.STATUS";

    public static final String EXTRA_SPEED = "speed";
    public static final String EXTRA_STYLE = "style";
    public static final String EXTRA_VOLUME = "volume";
    public static final String EXTRA_MUTE = "mute";
    public static final String EXTRA_HOOK_ENABLED = "hook_enabled";
    public static final String EXTRA_INPUT_MODE = "input_mode";
    public static final String EXTRA_STATUS_SPEED = "status_speed";
    public static final String EXTRA_STATUS_TARGET_SPEED = "status_target_speed";
    public static final String EXTRA_STATUS_RPM = "status_rpm";
    public static final String EXTRA_STATUS_THROTTLE = "status_throttle";
    public static final String EXTRA_STATUS_ACCEL = "status_accel";
    public static final String EXTRA_STATUS_STYLE = "status_style";
    public static final String EXTRA_STATUS_STAGE = "status_stage";
    public static final String EXTRA_STATUS_SOURCE = "status_source";
    public static final String EXTRA_STATUS_HOOK = "status_hook";
    public static final String EXTRA_STATUS_INPUT_MODE = "status_input_mode";
    public static final String EXTRA_STATUS_SENSOR = "status_sensor";

    public static final int UDP_PORT = 47230;

    private static final String CHANNEL_ID = "miku_vvvf_sound";
    private static final int NOTIFICATION_ID = 3939;

    public enum InputMode {
        MANUAL,
        MAINAPP_HOOK,
        PHONE_ACCEL,
        PHONE_GPS,
        PHONE_FUSION
    }

    private VvvfSynthEngine engine;
    private final AtomicBoolean udpRunning = new AtomicBoolean(false);
    private Thread udpThread;
    private DatagramSocket udpSocket;
    private VehicleDataProvider vehicleDataProvider;
    private PhoneSensorDataProvider phoneSensorDataProvider;
    private volatile String hookStatus = "Hook idle";
    private volatile String phoneStatus = "Phone sensor idle";
    private volatile InputMode inputMode = InputMode.PHONE_FUSION;

    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private final Runnable statusRunnable = new Runnable() {
        @Override public void run() {
            broadcastStatus();
            statusHandler.postDelayed(this, 250);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
        engine = new VvvfSynthEngine(getApplicationContext());
        engine.setStatusListener(text -> {});

        vehicleDataProvider = new VehicleDataProvider(getApplicationContext(), new VehicleDataProvider.Listener() {
            @Override public void onVehicleData(VehicleDataProvider.VehicleSnapshot snapshot) {
                if (snapshot == null || !snapshot.valid || inputMode != InputMode.MAINAPP_HOOK) return;
                engine.setVehicleStateFromHook(snapshot.speedKmh, snapshot.rpm, snapshot.throttle, snapshot.source);
                hookStatus = snapshot.rawSummary;
            }
            @Override public void onVehicleProviderStatus(String text) {
                hookStatus = text == null ? "" : text;
            }
        });
        vehicleDataProvider.setPollIntervalMs(500);

        phoneSensorDataProvider = new PhoneSensorDataProvider(getApplicationContext(), new PhoneSensorDataProvider.Listener() {
            @Override public void onPhoneSensorData(PhoneSensorDataProvider.PhoneSensorSnapshot snapshot) {
                if (snapshot == null || !snapshot.valid || !isPhoneInputMode(inputMode)) return;
                engine.setPhoneSensorSpeedKmh(snapshot.speedKmh, snapshot.source, snapshot.engineTauSeconds);
                phoneStatus = snapshot.rawSummary;
            }
            @Override public void onPhoneSensorStatus(String text) {
                phoneStatus = text == null ? "" : text;
            }
        });

        applyInputMode(InputMode.PHONE_FUSION);
        startForeground(NOTIFICATION_ID, buildNotification("Ready · Phone FUSION · UDP " + UDP_PORT + " · " + engine.getSampleVvvfStatus()));
        startUdpServer();
        engine.start();
        statusHandler.removeCallbacks(statusRunnable);
        statusHandler.post(statusRunnable);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                stopSelfSafely();
                return START_NOT_STICKY;
            } else if (ACTION_SET_SPEED.equals(action)) {
                applyInputMode(InputMode.MANUAL);
                engine.setSpeedKmh(intent.getFloatExtra(EXTRA_SPEED, engine.getTargetSpeedKmh()));
                updateNotification();
            } else if (ACTION_SET_STYLE.equals(action)) {
                String styleName = intent.getStringExtra(EXTRA_STYLE);
                applyStyle(styleName);
                updateNotification();
            } else if (ACTION_SET_VOLUME.equals(action)) {
                engine.setVolume(intent.getFloatExtra(EXTRA_VOLUME, engine.getVolume()));
                updateNotification();
            } else if (ACTION_SET_MUTE.equals(action)) {
                engine.setMuted(intent.getBooleanExtra(EXTRA_MUTE, engine.isMuted()));
                updateNotification();
            } else if (ACTION_SET_HOOK.equals(action)) {
                boolean on = intent.getBooleanExtra(EXTRA_HOOK_ENABLED, false);
                applyInputMode(on ? InputMode.MAINAPP_HOOK : InputMode.MANUAL);
                updateNotification();
            } else if (ACTION_SET_INPUT_MODE.equals(action)) {
                applyInputMode(parseInputMode(intent.getStringExtra(EXTRA_INPUT_MODE), inputMode));
                updateNotification();
            } else if (ACTION_RESET_SENSOR.equals(action)) {
                if (phoneSensorDataProvider != null) phoneSensorDataProvider.resetCalibration();
                updateNotification();
            } else if (ACTION_TOGGLE_ACCEL_DIRECTION.equals(action)) {
                if (phoneSensorDataProvider != null) phoneSensorDataProvider.toggleAccelDirection();
                updateNotification();
            } else {
                updateNotification();
            }
        }
        if (!engine.isRunning()) engine.start();
        if (!udpRunning.get()) startUdpServer();
        ensureProvidersRunning();
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        statusHandler.removeCallbacks(statusRunnable);
        stopUdpServer();
        if (vehicleDataProvider != null) {
            vehicleDataProvider.stop();
            vehicleDataProvider = null;
        }
        if (phoneSensorDataProvider != null) {
            phoneSensorDataProvider.stop();
            phoneSensorDataProvider = null;
        }
        engine.stop();
        super.onDestroy();
    }

    private void stopSelfSafely() {
        statusHandler.removeCallbacks(statusRunnable);
        stopUdpServer();
        if (vehicleDataProvider != null) vehicleDataProvider.stop();
        if (phoneSensorDataProvider != null) phoneSensorDataProvider.stop();
        engine.stop();
        stopForeground(true);
        stopSelf();
    }

    private void ensureProvidersRunning() {
        applyInputMode(inputMode);
    }

    private void applyInputMode(InputMode mode) {
        if (mode == null) mode = InputMode.PHONE_FUSION;
        inputMode = mode;

        if (vehicleDataProvider != null) {
            vehicleDataProvider.setEnabled(mode == InputMode.MAINAPP_HOOK);
            if (mode == InputMode.MAINAPP_HOOK) vehicleDataProvider.start();
        }

        if (phoneSensorDataProvider != null) {
            switch (mode) {
                case PHONE_ACCEL:
                    phoneSensorDataProvider.setMode(PhoneSensorDataProvider.Mode.ACCEL_ONLY);
                    break;
                case PHONE_GPS:
                    phoneSensorDataProvider.setMode(PhoneSensorDataProvider.Mode.GPS_ONLY);
                    break;
                case PHONE_FUSION:
                    phoneSensorDataProvider.setMode(PhoneSensorDataProvider.Mode.FUSION);
                    break;
                default:
                    phoneSensorDataProvider.setMode(PhoneSensorDataProvider.Mode.OFF);
                    break;
            }
        }
    }

    private boolean isPhoneInputMode(InputMode mode) {
        return mode == InputMode.PHONE_ACCEL || mode == InputMode.PHONE_GPS || mode == InputMode.PHONE_FUSION;
    }

    private InputMode parseInputMode(String name, InputMode fallback) {
        if (name == null) return fallback == null ? InputMode.PHONE_FUSION : fallback;
        String s = name.trim().toUpperCase(Locale.US);
        if (s.contains("FUSION") || s.contains("BOTH") || s.contains("MIX") || s.contains("融合") || s.contains("GPS_ACCEL")) return InputMode.PHONE_FUSION;
        if (s.equals("ACCEL") || s.contains("ACCEL_ONLY") || s.contains("PHONE_ACCEL") || s.contains("加速度")) return InputMode.PHONE_ACCEL;
        if (s.equals("GPS") || s.contains("GPS_ONLY") || s.contains("PHONE_GPS") || s.contains("定位")) return InputMode.PHONE_GPS;
        if (s.contains("HOOK") || s.contains("MAINAPP") || s.contains("原车") || s.contains("CAN")) return InputMode.MAINAPP_HOOK;
        if (s.contains("MANUAL") || s.contains("UDP") || s.contains("手动")) return InputMode.MANUAL;
        try { return InputMode.valueOf(s); } catch (Throwable ignored) {}
        return fallback == null ? InputMode.PHONE_FUSION : fallback;
    }

    private void startUdpServer() {
        if (!udpRunning.compareAndSet(false, true)) return;
        udpThread = new Thread(() -> {
            byte[] buf = new byte[512];
            try {
                udpSocket = new DatagramSocket(UDP_PORT);
                udpSocket.setReuseAddress(true);
                while (udpRunning.get()) {
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    udpSocket.receive(packet);
                    String msg = new String(packet.getData(), packet.getOffset(), packet.getLength(), StandardCharsets.UTF_8).trim();
                    handleUdpMessage(msg, packet.getAddress(), packet.getPort());
                }
            } catch (Throwable ignored) {
            } finally {
                closeUdpSocket();
                udpRunning.set(false);
            }
        }, "MikuVVVF-UDP");
        udpThread.start();
    }

    private void stopUdpServer() {
        udpRunning.set(false);
        closeUdpSocket();
        if (udpThread != null) {
            udpThread.interrupt();
            try { udpThread.join(300); } catch (InterruptedException ignored) {}
            udpThread = null;
        }
    }

    private void closeUdpSocket() {
        if (udpSocket != null) {
            try { udpSocket.close(); } catch (Throwable ignored) {}
            udpSocket = null;
        }
    }

    private void handleUdpMessage(String msg, InetAddress remote, int remotePort) {
        if (msg == null || msg.length() == 0) return;
        String[] parts = msg.split("\\s+");
        String cmd = parts[0].toUpperCase(Locale.US);
        try {
            switch (cmd) {
                case "SPEED":
                    if (parts.length >= 2) {
                        applyInputMode(InputMode.MANUAL);
                        engine.setSpeedKmh(Float.parseFloat(parts[1]));
                    }
                    break;
                case "STATE":
                    applyInputMode(InputMode.MANUAL);
                    // STATE speed rpm throttle. rpm/throttle are optional.
                    if (parts.length >= 4) {
                        engine.setVehicleState(Float.parseFloat(parts[1]), Float.parseFloat(parts[2]), Float.parseFloat(parts[3]));
                    } else if (parts.length >= 3) {
                        engine.setVehicleState(Float.parseFloat(parts[1]), Float.parseFloat(parts[2]), -1f);
                    } else if (parts.length >= 2) {
                        engine.setSpeedKmh(Float.parseFloat(parts[1]));
                    }
                    break;
                case "STYLE":
                    if (parts.length >= 2) applyStyle(parts[1]);
                    break;
                case "VOLUME":
                    if (parts.length >= 2) engine.setVolume(Float.parseFloat(parts[1]));
                    break;
                case "MUTE":
                    engine.setMuted(parts.length < 2 || !"0".equals(parts[1]));
                    break;
                case "UNMUTE":
                    engine.setMuted(false);
                    break;
                case "HOOK":
                    if (parts.length < 2 || !("0".equals(parts[1]) || "OFF".equalsIgnoreCase(parts[1]) || "FALSE".equalsIgnoreCase(parts[1]))) {
                        applyInputMode(InputMode.MAINAPP_HOOK);
                    } else {
                        applyInputMode(InputMode.MANUAL);
                    }
                    break;
                case "MODE":
                case "SOURCE":
                    if (parts.length >= 2) applyInputMode(parseInputMode(parts[1], inputMode));
                    break;
                case "CALIBRATE":
                case "RESET_SENSOR":
                case "ZERO_SENSOR":
                    if (phoneSensorDataProvider != null) phoneSensorDataProvider.resetCalibration();
                    break;
                case "ACCEL_INVERT":
                case "INVERT_ACCEL":
                    if (phoneSensorDataProvider != null) {
                        if (parts.length >= 2) {
                            String v = parts[1].toUpperCase(Locale.US);
                            if ("1".equals(v) || "ON".equals(v) || "TRUE".equals(v)) phoneSensorDataProvider.setAccelDirectionInverted(true);
                            else if ("0".equals(v) || "OFF".equals(v) || "FALSE".equals(v)) phoneSensorDataProvider.setAccelDirectionInverted(false);
                            else phoneSensorDataProvider.toggleAccelDirection();
                        } else {
                            phoneSensorDataProvider.toggleAccelDirection();
                        }
                    }
                    break;
                case "POLL":
                    if (parts.length >= 2 && vehicleDataProvider != null) vehicleDataProvider.setPollIntervalMs(Integer.parseInt(parts[1]));
                    break;
                case "PING":
                    replyUdp("PONG MIKU_VVVF " + inputMode.name() + " " + engine.getTargetSpeedKmh() + " " + getCombinedProviderStatus(), remote, remotePort);
                    break;
                case "STOP":
                    applyInputMode(InputMode.MANUAL);
                    engine.setSpeedKmh(0f);
                    engine.setMuted(true);
                    break;
            }
            updateNotification();
        } catch (Throwable ignored) {
        }
    }

    private void replyUdp(String text, InetAddress remote, int remotePort) {
        try {
            if (udpSocket == null || remote == null) return;
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            udpSocket.send(new DatagramPacket(data, data.length, remote, remotePort));
        } catch (Throwable ignored) {}
    }

    private void applyStyle(String styleName) {
        if (styleName == null) return;
        String s = styleName.trim().toUpperCase(Locale.US);
        if (s.contains("SAMPLE") || s.contains("REAL") || s.contains("VVVF_SAMPLE") || s.contains("真实") || s.contains("采样") || s.contains("录音")) {
            engine.setStyle(VvvfSynthEngine.Style.SAMPLE_VVVF_0_140);
        } else if (s.contains("AIR") || s.contains("PLANE") || s.contains("JET") || s.contains("TURBINE") || s.contains("飞机") || s.contains("涡扇")) {
            engine.setStyle(VvvfSynthEngine.Style.AIRCRAFT_TURBINE);
        } else if (s.contains("POP") || s.contains("BANG") || s.contains("ANTI") || s.contains("TURBO") || s.contains("偏时") || s.contains("回火") || s.contains("放炮")) {
            engine.setStyle(VvvfSynthEngine.Style.POP_BANG_TURBO);
        } else if (s.contains("NATURAL") || s.contains("ASPIRATED") || s.equals("NA") || s.contains("自然吸气") || s.contains("自吸")) {
            engine.setStyle(VvvfSynthEngine.Style.NATURAL_ASPIRATED);
        } else if (s.contains("ROTARY") || s.contains("WANKEL") || s.contains("转子")) {
            engine.setStyle(VvvfSynthEngine.Style.ROTARY);
        } else if (s.contains("SUPERCHARGED") || s.contains("HELLCAT") || s.contains("V8") || s.contains("地狱猫") || s.contains("机械增压")) {
            engine.setStyle(VvvfSynthEngine.Style.SUPERCHARGED_V8);
        } else if (s.contains("SIEMENS") || s.contains("GUANGZHOU") || s.contains("GZ") || s.contains("A1") || s.contains("广东") || s.contains("广州") || s.contains("西门子")) {
            engine.setStyle(VvvfSynthEngine.Style.SIEMENS_GZ_GTO);
        } else if (s.contains("IGBT")) {
            engine.setStyle(VvvfSynthEngine.Style.IGBT);
        } else if (s.contains("GTO")) {
            engine.setStyle(VvvfSynthEngine.Style.GTO);
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Miku VVVF Sound",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Speed-bound VVVF / engine sound service");
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(R.drawable.ic_stat_vvvf)
                .setContentTitle("Miku VVVF Fighter HUD")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void broadcastStatus() {
        if (engine == null) return;
        Intent i = new Intent(ACTION_STATUS);
        i.setPackage(getPackageName());
        i.putExtra(EXTRA_STATUS_SPEED, engine.getDisplaySpeedKmh());
        i.putExtra(EXTRA_STATUS_TARGET_SPEED, engine.getTargetSpeedKmh());
        i.putExtra(EXTRA_STATUS_RPM, engine.getDisplayRpm());
        i.putExtra(EXTRA_STATUS_THROTTLE, engine.getDisplayThrottle());
        i.putExtra(EXTRA_STATUS_ACCEL, engine.getDisplayAccel());
        i.putExtra(EXTRA_STATUS_STYLE, engine.getStyle().name());
        i.putExtra(EXTRA_STATUS_STAGE, engine.getStageName());
        i.putExtra(EXTRA_STATUS_SOURCE, engine.getInputSourceName());
        i.putExtra(EXTRA_STATUS_HOOK, hookStatus);
        i.putExtra(EXTRA_STATUS_INPUT_MODE, inputMode.name());
        i.putExtra(EXTRA_STATUS_SENSOR, phoneStatus);
        sendBroadcast(i);
    }

    private String getCombinedProviderStatus() {
        String sensor = phoneSensorDataProvider == null ? phoneStatus : phoneSensorDataProvider.getStatusText();
        String hook = vehicleDataProvider == null ? hookStatus : vehicleDataProvider.getStatusText();
        return "sensor=" + sensor + " hook=" + hook;
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            String text = String.format(Locale.US, "%s · %s · %.1f km/h · %s · %s · vol %.0f%% · UDP %d%s",
                    inputMode.name(), engine.getStyle().name(), engine.getTargetSpeedKmh(), engine.getStageName(), engine.getInputSourceName(),
                    engine.getVolume() * 100f, UDP_PORT, engine.isMuted() ? " · muted" : "");
            nm.notify(NOTIFICATION_ID, buildNotification(text));
        }
    }
}
