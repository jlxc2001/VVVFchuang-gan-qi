package com.jlxc.mikuvvvf;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;

import com.ts.can.carinfo.ICarInfoService;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reads the same MainApp vehicle data source used by jlxc2001/CarDataHook.
 *
 * Safety notes:
 * - Polling is clamped to >= 500ms.
 * - Only requestCarBaseInfo() is called from CarInfoService.
 * - requestCarDoorInfo()/GetCarDoorInfo() are intentionally not called.
 * - TsCarService speed is used only as a fallback when base speed is unavailable.
 */
public class VehicleDataProvider {
    public interface Listener {
        void onVehicleData(VehicleSnapshot snapshot);
        void onVehicleProviderStatus(String text);
    }

    public static class VehicleSnapshot {
        public final boolean valid;
        public final float speedKmh;
        public final float rpm;
        public final float throttle;
        public final long timestampMs;
        public final String source;
        public final String rawSummary;

        VehicleSnapshot(boolean valid, float speedKmh, float rpm, float throttle, long timestampMs, String source, String rawSummary) {
            this.valid = valid;
            this.speedKmh = speedKmh;
            this.rpm = rpm;
            this.throttle = throttle;
            this.timestampMs = timestampMs;
            this.source = source;
            this.rawSummary = rawSummary;
        }
    }

    private static final String PKG_MAIN_UI = "com.ts.MainUI";
    private static final String ACTION_CAR_INFO = "com.ts.can.carinfo.CarInfoService";
    private static final String CLS_CAR_INFO = "com.ts.can.carinfo.CarInfoService";
    private static final String ACTION_SPEECH_CAR = "com.ts.tsspeechlib.car.TsCarService";
    private static final String CLS_SPEECH_CAR = "com.ts.tsspeechlib.car.TsCarService";
    private static final String TOKEN_SPEECH_CAR = "com.ts.tsspeechlib.car.ITsSpeechCar";

    private final Context context;
    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);

    private volatile ICarInfoService carInfoService;
    private volatile IBinder speechBinder;
    private volatile boolean carInfoBound;
    private volatile boolean speechBound;
    private volatile boolean enabled = true;
    private volatile int pollIntervalMs = 500;
    private volatile String statusText = "Hook idle";

    private Thread pollThread;
    private long pollCount = 0;
    private long lastBindAttemptMs = 0;
    private float lastSpeed = 0f;

    private final ServiceConnection carInfoConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            carInfoService = ICarInfoService.Stub.asInterface(service);
            carInfoBound = true;
            setStatus("CarInfoService 已绑定: " + name.flattenToShortString());
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            carInfoService = null;
            carInfoBound = false;
            setStatus("CarInfoService 断开: " + name.flattenToShortString());
        }
    };

    private final ServiceConnection speechConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            speechBinder = service;
            speechBound = true;
            setStatus("TsCarService 已绑定: " + name.flattenToShortString());
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            speechBinder = null;
            speechBound = false;
            setStatus("TsCarService 断开: " + name.flattenToShortString());
        }
    };

    public VehicleDataProvider(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        setStatus(enabled ? "Hook enabled" : "Hook disabled");
        if (enabled) bindIfNeeded(true);
    }

    public boolean isEnabled() { return enabled; }

    public void setPollIntervalMs(int ms) {
        if (ms < 500) ms = 500;
        if (ms > 5000) ms = 5000;
        pollIntervalMs = ms;
        setStatus("Hook poll interval=" + pollIntervalMs + "ms");
    }

    public int getPollIntervalMs() { return pollIntervalMs; }

    public String getStatusText() {
        return statusText + " | CarInfo=" + (carInfoBound ? "OK" : "--") + " TsCar=" + (speechBound ? "OK" : "--") + " poll=" + pollIntervalMs + "ms";
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        bindIfNeeded(true);
        pollThread = new Thread(this::pollLoop, "MikuVVVF-HookProvider");
        pollThread.start();
    }

    public void stop() {
        running.set(false);
        if (pollThread != null) {
            pollThread.interrupt();
            try { pollThread.join(500); } catch (InterruptedException ignored) {}
            pollThread = null;
        }
        unbindAll();
    }

    private void pollLoop() {
        while (running.get()) {
            long start = System.currentTimeMillis();
            try {
                if (enabled) {
                    bindIfNeeded(false);
                    readOnce();
                }
            } catch (Throwable t) {
                setStatus("Hook read error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
            long took = System.currentTimeMillis() - start;
            long sleep = Math.max(50, pollIntervalMs - took);
            try { Thread.sleep(sleep); } catch (InterruptedException ignored) {}
        }
    }

    private void bindIfNeeded(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastBindAttemptMs < 3000) return;
        lastBindAttemptMs = now;
        if (!carInfoBound) bindCarInfo();
        if (!speechBound) bindSpeech();
    }

    private void bindCarInfo() {
        Intent intent = new Intent(ACTION_CAR_INFO);
        intent.setPackage(PKG_MAIN_UI);
        intent.setClassName(PKG_MAIN_UI, CLS_CAR_INFO);
        try {
            boolean ok = context.bindService(intent, carInfoConnection, Context.BIND_AUTO_CREATE);
            setStatus("bind CarInfoService result=" + ok);
        } catch (Throwable t) {
            setStatus("bind CarInfoService 异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void bindSpeech() {
        Intent intent = new Intent(ACTION_SPEECH_CAR);
        intent.setPackage(PKG_MAIN_UI);
        intent.setClassName(PKG_MAIN_UI, CLS_SPEECH_CAR);
        try {
            boolean ok = context.bindService(intent, speechConnection, Context.BIND_AUTO_CREATE);
            setStatus("bind TsCarService result=" + ok);
        } catch (Throwable t) {
            setStatus("bind TsCarService 异常: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    private void unbindAll() {
        try { if (carInfoBound) context.unbindService(carInfoConnection); } catch (Throwable ignored) {}
        try { if (speechBound) context.unbindService(speechConnection); } catch (Throwable ignored) {}
        carInfoBound = false;
        speechBound = false;
        carInfoService = null;
        speechBinder = null;
    }

    private void readOnce() {
        int[] base = null;
        Integer speechSpeed = null;
        ICarInfoService cis = carInfoService;
        if (cis != null && carInfoBound) {
            try {
                base = cis.requestCarBaseInfo();
            } catch (Throwable t) {
                setStatus("requestCarBaseInfo 异常: " + t.getClass().getSimpleName());
            }
        }
        IBinder sb = speechBinder;
        if (sb != null && speechBound) {
            speechSpeed = transactInt(sb, TOKEN_SPEECH_CAR, 22);
        }

        boolean valid = base != null && base.length > 0 && base[0] == 1;
        float speed = get(base, 2, speechSpeed != null ? speechSpeed : lastSpeed);
        float rpm = get(base, 3, estimateRpmFromSpeed(speed));
        if (speed < 0) speed = 0;
        if (speed > 260) speed = 260;
        if (rpm < 0) rpm = 0;
        if (rpm > 10000) rpm = 10000;

        // No direct throttle field is known in the current hook data; keep -1 so the synth can infer load smoothly.
        float throttle = -1f;
        lastSpeed = speed;
        pollCount++;
        String source = base != null ? "CarInfoService.requestCarBaseInfo" : (speechSpeed != null ? "TsCarService.speed" : "fallback-last");
        String raw = String.format(Locale.US, "#%d valid=%s speed=%.1f rpm=%.0f baseLen=%s src=%s",
                pollCount, valid, speed, rpm, base == null ? "null" : String.valueOf(base.length), source);
        if (listener != null) listener.onVehicleData(new VehicleSnapshot(valid, speed, rpm, throttle, System.currentTimeMillis(), source, raw));
    }

    private Integer transactInt(IBinder binder, String token, int code) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(token);
            boolean ok = binder.transact(code, data, reply, 0);
            if (!ok) return null;
            reply.readException();
            return reply.readInt();
        } catch (Throwable ignored) {
            return null;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private static float get(int[] a, int index, float def) {
        return a != null && a.length > index ? a[index] : def;
    }

    private static float estimateRpmFromSpeed(float speed) {
        return Math.max(750f, Math.min(4500f, 800f + speed * 38f));
    }

    private void setStatus(String text) {
        statusText = text == null ? "" : text;
        if (listener != null) listener.onVehicleProviderStatus(getStatusText());
    }
}
