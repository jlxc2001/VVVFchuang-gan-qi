package com.jlxc.mikuvvvf;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.SystemClock;

import java.util.Locale;

/**
 * Phone-only speed provider for phones without MainApp vehicle CAN data.
 *
 * Modes:
 * - ACCEL_ONLY: estimate speed by integrating the phone linear acceleration.
 * - GPS_ONLY: use Android Location speed directly.
 * - FUSION: use acceleration for fast response and GPS as the absolute anchor.
 *
 * V11 adds a stop-detection state machine. Pure accelerometer integration can drift,
 * so braking impulse + near-zero acceleration are used to infer "vehicle has stopped".
 * In FUSION mode, GPS is treated as a slow anchor and low-speed confirmation only;
 * delayed non-zero GPS readings are not allowed to immediately wake the sound after
 * the accelerometer has confidently detected a stop.
 *
 * The provider deliberately sends an already-smoothed target speed to VvvfSynthEngine;
 * the original audio-engine smoothing is still kept and remains the final anti-stutter layer.
 */
public class PhoneSensorDataProvider implements SensorEventListener, LocationListener {
    public enum Mode {
        OFF,
        ACCEL_ONLY,
        GPS_ONLY,
        FUSION
    }

    private enum MotionState {
        STOPPED,
        ACCELERATING,
        CRUISING,
        BRAKING,
        STOP_CANDIDATE,
        STOP_CONFIRMED
    }

    public interface Listener {
        void onPhoneSensorData(PhoneSensorSnapshot snapshot);
        void onPhoneSensorStatus(String text);
    }

    public static class PhoneSensorSnapshot {
        public final boolean valid;
        public final float speedKmh;
        public final float accelMps2;
        public final long timestampMs;
        public final String source;
        public final double engineTauSeconds;
        public final String rawSummary;

        PhoneSensorSnapshot(boolean valid, float speedKmh, float accelMps2, long timestampMs,
                            String source, double engineTauSeconds, String rawSummary) {
            this.valid = valid;
            this.speedKmh = speedKmh;
            this.accelMps2 = accelMps2;
            this.timestampMs = timestampMs;
            this.source = source;
            this.engineTauSeconds = engineTauSeconds;
            this.rawSummary = rawSummary;
        }
    }

    private static final double MAX_SPEED_MPS = 72.222; // 260km/h
    private static final double ACCEL_DEADBAND_MPS2 = 0.045;
    private static final double START_ACCEL_MPS2 = 0.16;
    private static final double BRAKE_ACCEL_MPS2 = -0.14;
    private static final double STRONG_BRAKE_MPS2 = -0.24;
    private static final double CRUISE_ACCEL_MPS2 = 0.10;
    private static final long STOP_STABLE_MIN_NS = 650_000_000L;
    private static final long STOP_CONFIRM_MIN_NS = 1_050_000_000L;
    private static final long GPS_FRESH_NS = 2_500_000_000L;
    private static final long GPS_DELAY_HOLD_AFTER_STOP_NS = 3_500_000_000L;

    private final Context context;
    private final Listener listener;
    private final SensorManager sensorManager;
    private final LocationManager locationManager;

    private Sensor linearSensor;
    private Sensor accelSensor;
    private boolean usingLinearAcceleration = false;

    private volatile Mode mode = Mode.OFF;
    private volatile boolean running = false;
    private volatile boolean accelDirectionInverted = false;
    private volatile String statusText = "Phone sensor idle";

    private final float[] gravity = new float[]{0f, 0f, 0f};
    private final float[] linear = new float[]{0f, 0f, 0f};

    private int axisIndex = -1;
    private int axisSign = 1;
    private int candidateAxis = -1;
    private long candidateSinceNs = 0L;
    private boolean axisLocked = false;

    private double speedMps = 0.0;
    private double lastReportedSpeedKmh = 0.0;
    private double gpsSpeedMps = 0.0;
    private double lastGpsSpeedMps = 0.0;
    private long lastGpsNs = 0L;
    private long lastSensorNs = 0L;
    private long lastReportNs = 0L;
    private long sampleCount = 0L;
    private boolean hasGpsSpeed = false;
    private boolean hasLocationPermission = false;

    private MotionState motionState = MotionState.STOPPED;
    private long motionStateSinceNs = 0L;
    private long nearZeroSinceNs = 0L;
    private long brakeStableSinceNs = 0L;
    private long lastStopConfirmedNs = 0L;
    private long gpsMovingSinceNs = 0L;
    private double driveImpulseMps = 0.0;
    private double brakeImpulseMps = 0.0;
    private double speedAtBrakeStartMps = 0.0;
    private double maxSpeedDuringRunMps = 0.0;
    private double lastLongitudinalAccelMps2 = 0.0;

    public PhoneSensorDataProvider(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.sensorManager = (SensorManager) this.context.getSystemService(Context.SENSOR_SERVICE);
        this.locationManager = (LocationManager) this.context.getSystemService(Context.LOCATION_SERVICE);
        if (sensorManager != null) {
            linearSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
            accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
    }

    public void setMode(Mode newMode) {
        if (newMode == null) newMode = Mode.OFF;
        if (mode == newMode && running) return;
        mode = newMode;
        restartForMode();
    }

    public Mode getMode() {
        return mode;
    }

    public boolean isAccelDirectionInverted() {
        return accelDirectionInverted;
    }

    public void setAccelDirectionInverted(boolean inverted) {
        accelDirectionInverted = inverted;
        setStatus("加速度方向=" + (accelDirectionInverted ? "反向" : "正常"));
    }

    public void toggleAccelDirection() {
        setAccelDirectionInverted(!accelDirectionInverted);
    }

    public void resetCalibration() {
        axisIndex = -1;
        axisSign = 1;
        candidateAxis = -1;
        candidateSinceNs = 0L;
        axisLocked = false;
        lastSensorNs = 0L;
        lastReportNs = 0L;
        speedMps = mode == Mode.FUSION && hasGpsSpeed ? gpsSpeedMps : 0.0;
        lastReportedSpeedKmh = speedMps * 3.6;
        gravity[0] = gravity[1] = gravity[2] = 0f;
        linear[0] = linear[1] = linear[2] = 0f;
        resetMotionState(SystemClock.elapsedRealtimeNanos(), speedMps <= 0.30 ? MotionState.STOPPED : MotionState.CRUISING);
        setStatus("加速度零点/轴向/停车状态已重置");
    }

    public String getStatusText() {
        long gpsAgeMs = lastGpsNs == 0L ? -1L : (SystemClock.elapsedRealtimeNanos() - lastGpsNs) / 1_000_000L;
        String axis;
        if (axisIndex < 0) axis = "axis=auto";
        else axis = String.format(Locale.US, "axis=%c%s%s", (char)('X' + axisIndex), axisSign > 0 ? "+" : "-", axisLocked ? "" : "? 非锁定");
        String gps = gpsAgeMs >= 0 ? String.format(Locale.US, "GPS=%.1fkm/h %dms", gpsSpeedMps * 3.6, gpsAgeMs) : "GPS=--";
        return String.format(Locale.US, "%s | mode=%s | %.1fkm/h | %s | %s | state=%s | brake=%.2fm/s | invert=%s | %s",
                statusText, mode.name(), lastReportedSpeedKmh, gps, axis, motionState.name(), brakeImpulseMps,
                accelDirectionInverted ? "ON" : "OFF", usingLinearAcceleration ? "linear" : "accel-gravity");
    }

    public void start() {
        restartForMode();
    }

    public void stop() {
        running = false;
        unregisterSensors();
        unregisterLocation();
        setStatus("Phone sensor stopped");
    }

    private void restartForMode() {
        unregisterSensors();
        unregisterLocation();
        running = mode != Mode.OFF;
        if (!running) {
            setStatus("Phone sensor OFF");
            return;
        }

        long nowNs = SystemClock.elapsedRealtimeNanos();
        if (motionStateSinceNs == 0L) resetMotionState(nowNs, speedMps <= 0.30 ? MotionState.STOPPED : MotionState.CRUISING);
        if (needsAcceleration(mode)) registerAcceleration();
        if (needsGps(mode)) registerLocation();
        setStatus("Phone sensor mode=" + mode.name());
    }

    private boolean needsAcceleration(Mode m) {
        return m == Mode.ACCEL_ONLY || m == Mode.FUSION;
    }

    private boolean needsGps(Mode m) {
        return m == Mode.GPS_ONLY || m == Mode.FUSION;
    }

    private void registerAcceleration() {
        if (sensorManager == null) {
            setStatus("未找到 SensorManager");
            return;
        }
        Sensor sensor = linearSensor != null ? linearSensor : accelSensor;
        if (sensor == null) {
            setStatus("未找到加速度传感器");
            return;
        }
        usingLinearAcceleration = sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION;
        try {
            // Around 50Hz is enough for sound binding and avoids wasting CPU on old phones.
            boolean ok = sensorManager.registerListener(this, sensor, 20_000);
            setStatus(ok ? "加速度传感器已启动" : "加速度传感器启动失败");
        } catch (Throwable t) {
            setStatus("加速度传感器异常: " + t.getClass().getSimpleName());
        }
    }

    private void unregisterSensors() {
        try {
            if (sensorManager != null) sensorManager.unregisterListener(this);
        } catch (Throwable ignored) {}
    }

    private void registerLocation() {
        hasLocationPermission = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                || hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (!hasLocationPermission) {
            setStatus("缺少定位权限，GPS 车速不可用");
            return;
        }
        if (locationManager == null) {
            setStatus("未找到 LocationManager");
            return;
        }
        try {
            boolean gpsEnabled = false;
            boolean networkEnabled = false;
            try { gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER); } catch (Throwable ignored) {}
            try { networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER); } catch (Throwable ignored) {}
            if (gpsEnabled) locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 250L, 0f, this);
            if (networkEnabled) locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 800L, 0f, this);
            if (!gpsEnabled && !networkEnabled) setStatus("定位服务未开启");
            else setStatus("GPS/定位车速已启动");
        } catch (SecurityException se) {
            setStatus("定位权限被系统拒绝");
        } catch (Throwable t) {
            setStatus("定位启动异常: " + t.getClass().getSimpleName());
        }
    }

    private void unregisterLocation() {
        try {
            if (locationManager != null) locationManager.removeUpdates(this);
        } catch (Throwable ignored) {}
    }

    private boolean hasPermission(String permission) {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!running || !needsAcceleration(mode) || event == null || event.values == null || event.values.length < 3) return;
        long nowNs = event.timestamp > 0L ? event.timestamp : SystemClock.elapsedRealtimeNanos();
        if (lastSensorNs == 0L) {
            lastSensorNs = nowNs;
            return;
        }
        double dt = (nowNs - lastSensorNs) / 1_000_000_000.0;
        lastSensorNs = nowNs;
        if (dt <= 0.0 || dt > 0.35) return;

        if (event.sensor != null && event.sensor.getType() == Sensor.TYPE_LINEAR_ACCELERATION) {
            linear[0] = event.values[0];
            linear[1] = event.values[1];
            linear[2] = event.values[2];
        } else {
            // Fallback for phones without TYPE_LINEAR_ACCELERATION.
            final float alpha = 0.92f;
            gravity[0] = alpha * gravity[0] + (1f - alpha) * event.values[0];
            gravity[1] = alpha * gravity[1] + (1f - alpha) * event.values[1];
            gravity[2] = alpha * gravity[2] + (1f - alpha) * event.values[2];
            linear[0] = event.values[0] - gravity[0];
            linear[1] = event.values[1] - gravity[1];
            linear[2] = event.values[2] - gravity[2];
        }

        updateAxisAutoLock(nowNs);
        double longitudinalAccel = getLongitudinalAccel();
        if (Math.abs(longitudinalAccel) < ACCEL_DEADBAND_MPS2) longitudinalAccel = 0.0;
        lastLongitudinalAccelMps2 = longitudinalAccel;

        integrateAcceleration(longitudinalAccel, dt, nowNs);
        reportIfNeeded(nowNs, (float) longitudinalAccel, false);
    }

    private void updateAxisAutoLock(long nowNs) {
        if (axisLocked) return;
        double ax = Math.abs(linear[0]);
        double ay = Math.abs(linear[1]);
        double az = Math.abs(linear[2]);
        int best = 0;
        double bestValue = ax;
        double second = ay;
        if (ay > bestValue) { second = bestValue; bestValue = ay; best = 1; }
        else second = ay;
        if (az > bestValue) { second = bestValue; bestValue = az; best = 2; }
        else if (az > second) second = az;

        if (bestValue < 0.18 || bestValue < second * 1.12) return;
        if (candidateAxis != best) {
            candidateAxis = best;
            candidateSinceNs = nowNs;
            return;
        }
        if (candidateSinceNs != 0L && nowNs - candidateSinceNs > 450_000_000L) {
            axisIndex = best;
            // In pure accelerometer mode there is no absolute direction. Treat the first stable
            // non-trivial movement as forward; the setting panel also has a reverse button.
            axisSign = linear[best] >= 0f ? 1 : -1;
            axisLocked = true;
            setStatus("加速度轴向锁定: " + (char)('X' + axisIndex) + (axisSign > 0 ? "+" : "-"));
        }
    }

    private double getLongitudinalAccel() {
        int idx = axisIndex >= 0 ? axisIndex : dominantAxis();
        if (axisIndex < 0) return 0.0;
        double a = linear[idx] * axisSign;
        if (accelDirectionInverted) a = -a;
        if (mode == Mode.FUSION && hasGpsSpeed) {
            maybeLearnDirectionFromGps(a);
            if (accelDirectionInverted) a = -linear[idx] * axisSign;
        }
        return clamp(a, -6.0, 6.0);
    }

    private int dominantAxis() {
        double ax = Math.abs(linear[0]);
        double ay = Math.abs(linear[1]);
        double az = Math.abs(linear[2]);
        if (ax >= ay && ax >= az) return 0;
        if (ay >= ax && ay >= az) return 1;
        return 2;
    }

    private void maybeLearnDirectionFromGps(double accelAfterSign) {
        long nowNs = SystemClock.elapsedRealtimeNanos();
        if (lastGpsNs == 0L || nowNs - lastGpsNs > GPS_FRESH_NS) return;
        double gpsDelta = gpsSpeedMps - lastGpsSpeedMps;
        if (Math.abs(gpsDelta) < 0.35 || Math.abs(accelAfterSign) < 0.35) return;
        // If GPS says speed is rising while accel says braking, or the opposite, flip once.
        if (gpsDelta * accelAfterSign < -0.18) {
            accelDirectionInverted = !accelDirectionInverted;
            setStatus("已根据 GPS 自动修正加速度方向");
        }
    }

    private void integrateAcceleration(double accelMps2, double dt, long nowNs) {
        if (mode == Mode.GPS_ONLY) return;

        updateMotionStateBeforeIntegration(accelMps2, dt, nowNs);

        if (motionState == MotionState.STOP_CONFIRMED && shouldHoldZeroDespiteGpsLag(nowNs)) {
            speedMps = 0.0;
            return;
        }

        speedMps += accelMps2 * dt;
        speedMps = clamp(speedMps, 0.0, MAX_SPEED_MPS);
        maxSpeedDuringRunMps = Math.max(maxSpeedDuringRunMps, speedMps);

        boolean gpsFresh = hasGpsSpeed && nowNs - lastGpsNs < GPS_FRESH_NS;
        if (mode == Mode.FUSION && gpsFresh) {
            if (isStopLikeState() && Math.abs(accelMps2) < CRUISE_ACCEL_MPS2 && gpsSpeedMps > 0.70) {
                // After an accelerometer-confirmed stop, Android GPS often continues to report
                // the old moving speed for a moment. Do not let that delayed value wake the VVVF.
                if (lastStopConfirmedNs == 0L || nowNs - lastStopConfirmedNs > GPS_DELAY_HOLD_AFTER_STOP_NS) {
                    double correctionAlpha = clamp(dt / 2.20, 0.0, 0.035);
                    speedMps += (gpsSpeedMps - speedMps) * correctionAlpha;
                }
            } else {
                // Complementary filter: acceleration responds instantly, GPS slowly pulls it back
                // to the real absolute speed so long-term drift does not build up.
                double correctionAlpha = clamp(dt / 1.25, 0.0, 0.080);
                speedMps += (gpsSpeedMps - speedMps) * correctionAlpha;
                if (gpsSpeedMps < 0.45 && Math.abs(accelMps2) < 0.12) speedMps *= 0.82;
            }
        } else if (mode == Mode.ACCEL_ONLY) {
            // Pure integration inevitably drifts. This small leakage keeps idle drift from
            // slowly moving the VVVF playback when the phone is stationary.
            double leak = Math.exp(-dt * (isStopLikeState() ? 0.65 : 0.020));
            speedMps *= leak;
        }

        updateMotionStateAfterIntegration(accelMps2, nowNs);
    }

    private void updateMotionStateBeforeIntegration(double accelMps2, double dt, long nowNs) {
        boolean nearZero = Math.abs(accelMps2) < CRUISE_ACCEL_MPS2;
        if (nearZero) {
            if (nearZeroSinceNs == 0L) nearZeroSinceNs = nowNs;
        } else {
            nearZeroSinceNs = 0L;
        }

        if (accelMps2 > START_ACCEL_MPS2) {
            if (motionState == MotionState.STOPPED || motionState == MotionState.STOP_CONFIRMED || motionState == MotionState.STOP_CANDIDATE) {
                driveImpulseMps = 0.0;
                brakeImpulseMps = 0.0;
                speedAtBrakeStartMps = 0.0;
                maxSpeedDuringRunMps = Math.max(speedMps, gpsSpeedMps);
            }
            driveImpulseMps += accelMps2 * dt;
            brakeStableSinceNs = 0L;
            setMotionState(MotionState.ACCELERATING, nowNs);
            return;
        }

        if (accelMps2 < BRAKE_ACCEL_MPS2) {
            if (motionState != MotionState.BRAKING) {
                speedAtBrakeStartMps = Math.max(speedMps, gpsSpeedMps * 0.65);
                if (speedAtBrakeStartMps < 0.55) speedAtBrakeStartMps = Math.max(maxSpeedDuringRunMps, driveImpulseMps);
                brakeImpulseMps = 0.0;
                brakeStableSinceNs = 0L;
                setMotionState(MotionState.BRAKING, nowNs);
            }
            brakeImpulseMps += (-accelMps2) * dt;
            return;
        }

        if (nearZero) {
            if (motionState == MotionState.BRAKING) {
                if (brakeStableSinceNs == 0L) brakeStableSinceNs = nowNs;
                if (nowNs - brakeStableSinceNs >= STOP_STABLE_MIN_NS && brakingLooksLikeStop(nowNs)) {
                    setMotionState(MotionState.STOP_CANDIDATE, nowNs);
                }
            } else if (motionState == MotionState.ACCELERATING || motionState == MotionState.CRUISING) {
                setMotionState(MotionState.CRUISING, nowNs);
            } else if (motionState == MotionState.STOPPED && speedMps > 0.55) {
                setMotionState(MotionState.CRUISING, nowNs);
            }
        } else if (motionState == MotionState.ACCELERATING || motionState == MotionState.BRAKING) {
            // Small non-zero vibration while the car is rolling: keep the current state.
        } else if (speedMps > 0.75) {
            setMotionState(MotionState.CRUISING, nowNs);
        }
    }

    private void updateMotionStateAfterIntegration(double accelMps2, long nowNs) {
        if (mode == Mode.GPS_ONLY) return;

        boolean nearZeroStable = nearZeroSinceNs != 0L && nowNs - nearZeroSinceNs >= STOP_CONFIRM_MIN_NS;
        boolean gpsFresh = hasGpsSpeed && nowNs - lastGpsNs < GPS_FRESH_NS;
        boolean gpsSaysStopped = gpsFresh && gpsSpeedMps < 0.75;
        boolean gpsSaysMovingFast = gpsFresh && gpsSpeedMps > 4.50;

        if (motionState == MotionState.STOP_CANDIDATE) {
            int score = 0;
            if (nearZeroStable) score += 2;
            if (speedMps < 1.25) score += 2;
            if (brakeImpulseMps >= Math.max(0.70, speedAtBrakeStartMps * 0.72)) score += 2;
            if (gpsSaysStopped) score += 2;
            if (gpsSaysMovingFast && brakeImpulseMps < Math.max(1.20, speedAtBrakeStartMps * 0.92)) score -= 2;

            if (score >= 4) {
                confirmStop(nowNs, gpsSaysStopped ? "GPS低速确认" : "刹车后平稳确认");
                return;
            }

            if (accelMps2 < STRONG_BRAKE_MPS2) {
                setMotionState(MotionState.BRAKING, nowNs);
                return;
            }
            if (accelMps2 > START_ACCEL_MPS2) {
                setMotionState(MotionState.ACCELERATING, nowNs);
                return;
            }
        }

        if ((motionState == MotionState.STOPPED || motionState == MotionState.STOP_CONFIRMED)
                && Math.abs(accelMps2) < CRUISE_ACCEL_MPS2 && speedMps < 0.38) {
            speedMps = 0.0;
        }

        if (speedMps > 2.0 && motionState == MotionState.STOP_CONFIRMED && accelMps2 > START_ACCEL_MPS2) {
            setMotionState(MotionState.ACCELERATING, nowNs);
        }
    }

    private boolean brakingLooksLikeStop(long nowNs) {
        boolean gpsFresh = hasGpsSpeed && nowNs - lastGpsNs < GPS_FRESH_NS;
        boolean gpsLow = gpsFresh && gpsSpeedMps < 1.25;
        double effectiveBrakeStart = Math.max(speedAtBrakeStartMps, 0.60);
        boolean brakeEnough = brakeImpulseMps >= Math.max(0.62, effectiveBrakeStart * 0.56);
        boolean localSpeedLow = speedMps < Math.max(1.90, effectiveBrakeStart * 0.36);
        boolean strongBrakeToLow = brakeImpulseMps >= Math.max(1.25, effectiveBrakeStart * 0.75) && speedMps < 3.20;
        return gpsLow || (brakeEnough && localSpeedLow) || strongBrakeToLow;
    }

    private void confirmStop(long nowNs, String reason) {
        speedMps = 0.0;
        brakeStableSinceNs = nowNs;
        nearZeroSinceNs = nowNs;
        lastStopConfirmedNs = nowNs;
        setMotionState(MotionState.STOP_CONFIRMED, nowNs);
        setStatus("停车确认: " + reason);
    }

    private boolean isStopLikeState() {
        return motionState == MotionState.STOPPED || motionState == MotionState.STOP_CANDIDATE || motionState == MotionState.STOP_CONFIRMED;
    }

    private boolean shouldHoldZeroDespiteGpsLag(long nowNs) {
        if (motionState != MotionState.STOP_CONFIRMED) return false;
        if (Math.abs(lastLongitudinalAccelMps2) > START_ACCEL_MPS2) return false;
        if (!hasGpsSpeed || lastGpsNs == 0L) return true;
        if (gpsSpeedMps < 0.85) return true;
        return lastStopConfirmedNs != 0L && nowNs - lastStopConfirmedNs < GPS_DELAY_HOLD_AFTER_STOP_NS;
    }

    private void setMotionState(MotionState newState, long nowNs) {
        if (newState == null) newState = MotionState.STOPPED;
        if (motionState == newState) return;
        motionState = newState;
        motionStateSinceNs = nowNs;
        if (newState == MotionState.CRUISING) {
            brakeStableSinceNs = 0L;
        } else if (newState == MotionState.STOPPED || newState == MotionState.STOP_CONFIRMED) {
            maxSpeedDuringRunMps = 0.0;
            driveImpulseMps = 0.0;
            brakeImpulseMps = 0.0;
            speedAtBrakeStartMps = 0.0;
        }
    }

    private void resetMotionState(long nowNs, MotionState initialState) {
        motionState = initialState == null ? MotionState.STOPPED : initialState;
        motionStateSinceNs = nowNs;
        nearZeroSinceNs = 0L;
        brakeStableSinceNs = 0L;
        lastStopConfirmedNs = motionState == MotionState.STOP_CONFIRMED || motionState == MotionState.STOPPED ? nowNs : 0L;
        driveImpulseMps = 0.0;
        brakeImpulseMps = 0.0;
        speedAtBrakeStartMps = 0.0;
        maxSpeedDuringRunMps = Math.max(speedMps, gpsSpeedMps);
        lastLongitudinalAccelMps2 = 0.0;
    }

    private void reportIfNeeded(long nowNs, float accelMps2, boolean force) {
        if (!force && lastReportNs != 0L && nowNs - lastReportNs < 48_000_000L) return;
        lastReportNs = nowNs;
        double outKmh = clamp(speedMps * 3.6, 0.0, 260.0);
        // A provider-level smoothing layer before the audio-engine smoothing. GPS-only needs
        // heavier smoothing because Android GPS speed often arrives at low frequency.
        double alpha;
        double tau;
        String src;
        switch (mode) {
            case GPS_ONLY:
                alpha = 0.28;
                tau = 0.78;
                src = "PHONE_GPS";
                break;
            case FUSION:
                alpha = isStopLikeState() ? 0.24 : 0.42;
                tau = isStopLikeState() ? 0.36 : 0.22;
                src = "PHONE_GPS+ACCEL_STOP_DETECT";
                break;
            case ACCEL_ONLY:
            default:
                alpha = isStopLikeState() ? 0.22 : 0.36;
                tau = isStopLikeState() ? 0.34 : 0.18;
                src = "PHONE_ACCEL_STOP_DETECT";
                break;
        }
        if (force) alpha = Math.max(alpha, 0.52);
        lastReportedSpeedKmh += (outKmh - lastReportedSpeedKmh) * alpha;
        if (lastReportedSpeedKmh < 0.16 && outKmh <= 0.02) lastReportedSpeedKmh = 0.0;
        sampleCount++;
        String raw = String.format(Locale.US, "#%d mode=%s state=%s speed=%.1f gps=%.1f accel=%.2f brake=%.2f start=%.2f %s",
                sampleCount, mode.name(), motionState.name(), lastReportedSpeedKmh, gpsSpeedMps * 3.6, accelMps2,
                brakeImpulseMps, speedAtBrakeStartMps, getStatusText());
        if (listener != null) {
            listener.onPhoneSensorData(new PhoneSensorSnapshot(true, (float) lastReportedSpeedKmh, accelMps2,
                    System.currentTimeMillis(), src, tau, raw));
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    @Override
    public void onLocationChanged(Location location) {
        if (!running || !needsGps(mode) || location == null) return;
        long nowNs = SystemClock.elapsedRealtimeNanos();
        double newGpsMps;
        if (location.hasSpeed()) {
            newGpsMps = location.getSpeed();
        } else {
            // Location without speed is not very useful for real-time sound, but keep it harmless.
            newGpsMps = gpsSpeedMps;
        }
        if (Double.isNaN(newGpsMps) || Double.isInfinite(newGpsMps)) return;
        newGpsMps = clamp(newGpsMps, 0.0, MAX_SPEED_MPS);
        lastGpsSpeedMps = gpsSpeedMps;
        gpsSpeedMps = newGpsMps;
        lastGpsNs = nowNs;
        hasGpsSpeed = true;

        if (gpsSpeedMps > 1.20) {
            if (gpsMovingSinceNs == 0L) gpsMovingSinceNs = nowNs;
        } else {
            gpsMovingSinceNs = 0L;
        }

        if (mode == Mode.GPS_ONLY) {
            speedMps = gpsSpeedMps;
            if (gpsSpeedMps < 0.35) {
                speedMps = 0.0;
                setMotionState(MotionState.STOPPED, nowNs);
            } else {
                setMotionState(MotionState.CRUISING, nowNs);
            }
            reportIfNeeded(nowNs, 0f, true);
        } else if (mode == Mode.FUSION) {
            if (motionState == MotionState.STOP_CONFIRMED && Math.abs(lastLongitudinalAccelMps2) < CRUISE_ACCEL_MPS2) {
                if (gpsSpeedMps < 0.85) {
                    speedMps = 0.0;
                } else if (lastStopConfirmedNs != 0L && nowNs - lastStopConfirmedNs < GPS_DELAY_HOLD_AFTER_STOP_NS) {
                    // Ignore delayed GPS speed shortly after an accelerometer stop confirmation.
                } else if (gpsMovingSinceNs != 0L && nowNs - gpsMovingSinceNs > 2_000_000_000L) {
                    // If GPS keeps saying we are moving for long enough, accept that the previous
                    // accelerometer stop inference was too aggressive.
                    speedMps = Math.max(speedMps, gpsSpeedMps * 0.35);
                    setMotionState(MotionState.CRUISING, nowNs);
                }
            } else {
                if (speedMps <= 0.01) speedMps = gpsSpeedMps;
                else speedMps = speedMps * 0.82 + gpsSpeedMps * 0.18;
                if (gpsSpeedMps < 0.65 && Math.abs(lastLongitudinalAccelMps2) < CRUISE_ACCEL_MPS2) {
                    if (motionState == MotionState.BRAKING || motionState == MotionState.STOP_CANDIDATE) {
                        confirmStop(nowNs, "GPS低速辅助");
                    }
                }
            }
            reportIfNeeded(nowNs, 0f, true);
        }
    }

    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) {
    }

    @Override
    public void onProviderEnabled(String provider) {
        setStatus(provider + " 已开启");
    }

    @Override
    public void onProviderDisabled(String provider) {
        setStatus(provider + " 已关闭");
    }

    private void setStatus(String text) {
        statusText = text == null ? "" : text;
        if (listener != null) listener.onPhoneSensorStatus(getStatusText());
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
