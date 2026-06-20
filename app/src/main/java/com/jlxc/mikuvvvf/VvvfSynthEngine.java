package com.jlxc.mikuvvvf;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.content.Context;

import java.io.InputStream;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

public class VvvfSynthEngine {
    public enum Style {
        SAMPLE_VVVF_0_140,
        GTO,
        IGBT,
        SIEMENS_GZ_GTO,
        AIRCRAFT_TURBINE,
        POP_BANG_TURBO,
        NATURAL_ASPIRATED,
        ROTARY,
        SUPERCHARGED_V8
    }

    public interface StatusListener {
        void onStatus(String text);
    }

    private static final int SAMPLE_RATE = 48000;
    private static final double TWO_PI = Math.PI * 2.0;
    private static final double SAMPLE_VVVF_MAX_SPEED_KMH = 140.0;
    private static final double SAMPLE_VVVF_START_TRIM_SEC = 0.55;
    private static final double SAMPLE_VVVF_LOOP_SEC = 0.880;
    private static final double SAMPLE_VVVF_ACCEL_LOOP_SEC = 1.420;
    private static final double SAMPLE_VVVF_DECEL_LOOP_SEC = 1.180;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Random random = new Random(20260620L);

    private float[] sampleVvvf;
    private int sampleVvvfStartFrame = 0;
    private int sampleVvvfEndFrame = 0;
    private int sampleVvvfRate = SAMPLE_RATE;
    private String sampleVvvfStatus = "sample not loaded";
    private double sampleLoopPhase = 0.0;
    private double sampleLoopCenterFrame = -1.0;

    private Thread audioThread;
    private AudioTrack audioTrack;
    private StatusListener statusListener;

    private volatile float targetSpeedKmh = 0f;
    private volatile float volume = 0.55f;
    private volatile Style style = Style.SAMPLE_VVVF_0_140;
    private volatile boolean muted = false;

    // Optional true vehicle data. STATE speed rpm throttle can feed these.
    private volatile float externalRpm = -1f;
    private volatile float externalThrottle = -1f;
    private volatile long externalStateNs = 0L;

    // Data source / anti-stutter smoothing. Hook updates are intentionally low-rate
    // (>= 500ms), so the audio engine must glide between samples rather than snap.
    private volatile boolean hookInputFresh = false;
    private volatile long hookInputNs = 0L;
    private volatile String inputSourceName = "MANUAL/UDP";
    private volatile double speedTauSeconds = 0.16;

    private double smoothedSpeed = 0.0;
    private double smoothedAccel = 0.0;
    private double smoothedRpm = 900.0;
    private double smoothedThrottle = 0.0;
    private volatile float displaySpeedKmh = 0f;
    private volatile float displayRpm = 900f;
    private volatile float displayThrottle = 0f;
    private volatile float displayAccel = 0f;

    // Rail / turbine oscillator phases.
    private double motorPhase = 0.0;
    private double carrierPhase = 0.0;
    private double subCarrierPhase = 0.0;
    private double transitionPhase = 0.0;

    // Engine-specific phases. These are intentionally separate from VVVF carrier phases.
    private double crankPhase = 0.0;
    private double firingPhase = 0.0;
    private double exhaustPhase = 0.0;
    private double intakePhase = 0.0;
    private double blowerPhase = 0.0;
    private double engineRumblePhase = 0.0;

    private double popEnvelope = 0.0;
    private double shiftEnvelope = 0.0;
    private double overrunEnvelope = 0.0;
    private int lastRenderedStage = -100;
    private long lastSpeedSetNs = 0L;
    private float lastSpeedInput = 0f;

    public VvvfSynthEngine() {
    }

    public VvvfSynthEngine(Context context) {
        loadSampleVvvf(context);
    }

    public String getSampleVvvfStatus() {
        return sampleVvvfStatus;
    }

    public void setStatusListener(StatusListener listener) {
        this.statusListener = listener;
    }

    public void start() {
        if (!running.compareAndSet(false, true)) return;
        audioThread = new Thread(this::audioLoop, "MikuVVVF-AudioThread");
        audioThread.setPriority(Thread.MAX_PRIORITY);
        audioThread.start();
    }

    public void stop() {
        running.set(false);
        if (audioThread != null) {
            audioThread.interrupt();
            try { audioThread.join(500); } catch (InterruptedException ignored) {}
            audioThread = null;
        }
        releaseTrack();
    }

    public boolean isRunning() {
        return running.get();
    }

    public void setSpeedKmh(float speedKmh) {
        setSpeedInternal(speedKmh, -1f, -1f, false, "MANUAL/UDP", 0.16);
    }

    public void setVehicleState(float speedKmh, float rpm, float throttle) {
        setSpeedInternal(speedKmh, rpm, throttle, false, "STATE/UDP", 0.18);
    }

    public void setVehicleStateFromHook(float speedKmh, float rpm, float throttle, String source) {
        setSpeedInternal(speedKmh, rpm, throttle, true, source == null ? "HOOK" : source, 0.72);
    }

    public void setPhoneSensorSpeedKmh(float speedKmh, String source, double tauSeconds) {
        setSpeedInternal(speedKmh, -1f, -1f, false, source == null ? "PHONE_SENSOR" : source, tauSeconds);
    }

    private void setSpeedInternal(float speedKmh, float rpm, float throttle, boolean fromHook, String source, double tauSeconds) {
        if (Float.isNaN(speedKmh) || Float.isInfinite(speedKmh)) return;
        speedKmh = Math.max(0f, Math.min(260f, speedKmh));

        long now = System.nanoTime();
        if (lastSpeedSetNs != 0L) {
            double dt = Math.max(0.10, (now - lastSpeedSetNs) / 1_000_000_000.0);
            double rawAccel = (speedKmh - lastSpeedInput) / dt;
            // Hook data arrives at 500ms+ intervals; use heavier accel smoothing so sample playback
            // and engine load do not pulse when the raw speed changes by 1 km/h per poll.
            double accelAlpha = fromHook ? 0.10 : 0.28;
            smoothedAccel = smoothedAccel * (1.0 - accelAlpha) + rawAccel * accelAlpha;
        }
        lastSpeedSetNs = now;
        lastSpeedInput = speedKmh;
        targetSpeedKmh = speedKmh;
        inputSourceName = source == null ? (fromHook ? "HOOK" : "MANUAL/UDP") : source;
        speedTauSeconds = Math.max(0.06, Math.min(1.50, tauSeconds));
        hookInputFresh = fromHook;
        if (fromHook) hookInputNs = now;

        if (!Float.isNaN(rpm) && !Float.isInfinite(rpm) && rpm > 300f) {
            externalRpm = Math.max(600f, Math.min(9800f, rpm));
            externalStateNs = now;
        }
        if (!Float.isNaN(throttle) && !Float.isInfinite(throttle)) {
            // Accept either 0..1 or 0..100.
            float t = throttle > 1.5f ? throttle / 100f : throttle;
            externalThrottle = Math.max(0f, Math.min(1f, t));
            externalStateNs = now;
        }
    }

    public String getInputSourceName() {
        long ageMs = hookInputFresh && hookInputNs != 0L ? (System.nanoTime() - hookInputNs) / 1_000_000L : -1L;
        if (ageMs >= 0) return inputSourceName + " " + ageMs + "ms";
        return inputSourceName;
    }

    public float getTargetSpeedKmh() {
        return targetSpeedKmh;
    }

    public float getDisplaySpeedKmh() {
        return displaySpeedKmh;
    }

    public float getDisplayRpm() {
        return displayRpm;
    }

    public float getDisplayThrottle() {
        return displayThrottle;
    }

    public float getDisplayAccel() {
        return displayAccel;
    }

    public void setVolume(float value) {
        volume = Math.max(0f, Math.min(1f, value));
    }

    public float getVolume() {
        return volume;
    }

    public void setMuted(boolean value) {
        muted = value;
    }

    public boolean isMuted() {
        return muted;
    }

    public void setStyle(Style value) {
        if (value != null) {
            style = value;
            lastRenderedStage = -100;
            shiftEnvelope = 0.0;
            popEnvelope = 0.0;
            overrunEnvelope = 0.0;
            sampleLoopPhase = 0.0;
            sampleLoopCenterFrame = -1.0;
        }
    }

    public Style getStyle() {
        return style;
    }

    public String getStageName() {
        return getStageName(targetSpeedKmh, style);
    }

    private void audioLoop() {
        int minBuffer = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int frames = Math.max(1024, minBuffer / 2);
        short[] buffer = new short[frames];

        try {
            audioTrack = createTrack(Math.max(minBuffer, frames * 2));
            audioTrack.play();
            notifyStatus("AudioTrack started");

            while (running.get()) {
                fillBuffer(buffer, frames);
                audioTrack.write(buffer, 0, frames);
            }
        } catch (Throwable t) {
            notifyStatus("Audio error: " + t.getMessage());
        } finally {
            releaseTrack();
            notifyStatus("AudioTrack stopped");
        }
    }

    private AudioTrack createTrack(int bufferBytes) {
        if (Build.VERSION.SDK_INT >= 23) {
            return new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(SAMPLE_RATE)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferBytes)
                    .build();
        }
        return new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes, AudioTrack.MODE_STREAM);
    }

    private void releaseTrack() {
        if (audioTrack == null) return;
        try { audioTrack.pause(); } catch (Throwable ignored) {}
        try { audioTrack.flush(); } catch (Throwable ignored) {}
        try { audioTrack.release(); } catch (Throwable ignored) {}
        audioTrack = null;
    }

    private void fillBuffer(short[] out, int frames) {
        final double rpmAlpha = 1.0 - Math.exp(-1.0 / (SAMPLE_RATE * 0.055));
        final double throttleAlpha = 1.0 - Math.exp(-1.0 / (SAMPLE_RATE * 0.090));
        Style currentStyle = style;

        for (int i = 0; i < frames; i++) {
            long nowNs = System.nanoTime();
            if (hookInputFresh && hookInputNs != 0L && nowNs - hookInputNs > 2_500_000_000L) {
                hookInputFresh = false;
                speedTauSeconds = 0.22;
                inputSourceName = "HOOK stale / fallback";
            }
            double tau = Math.max(0.035, speedTauSeconds);
            double speedAlpha = 1.0 - Math.exp(-1.0 / (SAMPLE_RATE * tau));
            smoothedSpeed += (targetSpeedKmh - smoothedSpeed) * speedAlpha;
            smoothedAccel *= hookInputFresh ? 0.9999982 : 0.999995;

            double speed = smoothedSpeed;
            int stage = calcStage(speed, currentStyle);
            if (stage != lastRenderedStage) {
                if (lastRenderedStage != -100) {
                    shiftEnvelope = Math.max(shiftEnvelope, isRailStyle(currentStyle) ? 0.65 : 1.0);
                    if (currentStyle == Style.POP_BANG_TURBO || currentStyle == Style.SUPERCHARGED_V8) {
                        popEnvelope = Math.max(popEnvelope, 0.75);
                    }
                }
                lastRenderedStage = stage;
            }

            double wave;
            double amp;

            if (currentStyle == Style.SAMPLE_VVVF_0_140) {
                wave = renderSampleVvvf(speed, smoothedAccel);
                double driveGain;
                if (speed < 0.8) {
                    driveGain = speed / 0.8;
                } else if (smoothedAccel > 0.20) {
                    driveGain = 1.0;
                } else if (smoothedAccel < -0.20) {
                    driveGain = 0.58;
                } else {
                    driveGain = 0.72;
                }
                amp = muted ? 0.0 : volume * driveGain * 1.05;
            } else if (isRailStyle(currentStyle)) {
                double motorHz = calcMotorHz(speed, currentStyle);
                double carrierHz = calcCarrierHz(speed, currentStyle, motorHz);
                double subCarrierHz = calcSubCarrierHz(speed, currentStyle, motorHz);
                advanceRailPhases(motorHz, carrierHz, subCarrierHz);
                wave = renderRailWave(currentStyle, stage, speed);
                wave = addRailTransition(currentStyle, speed, wave);

                double accel = smoothedAccel;
                double driveGain;
                if (speed < 0.7) {
                    driveGain = speed / 0.7;
                } else if (accel > 0.35) {
                    driveGain = 0.90 + Math.min(0.36, accel / 42.0);
                } else if (accel < -0.35) {
                    double regen = Math.sin(carrierPhase * 0.55 + motorPhase * 1.8)
                            + 0.35 * Math.sin(subCarrierPhase * 0.72);
                    wave = wave * 0.66 + regen * 0.34;
                    driveGain = 0.72 + Math.min(0.26, -accel / 52.0);
                } else {
                    driveGain = 0.32 + Math.min(0.24, speed / 150.0);
                }
                double highSpeedDamping = speed > 92.0 ? Math.max(0.50, 1.0 - (speed - 92.0) / 190.0) : 1.0;
                amp = muted ? 0.0 : volume * 0.40 * driveGain * highSpeedDamping;
            } else {
                EngineState es = calcEngineState(speed, currentStyle);
                smoothedRpm += (es.rpm - smoothedRpm) * rpmAlpha;
                double wantedThrottle = es.throttle;
                smoothedThrottle += (wantedThrottle - smoothedThrottle) * throttleAlpha;

                advanceEnginePhases(smoothedRpm, currentStyle);
                if (currentStyle == Style.AIRCRAFT_TURBINE) {
                    // V6 accidentally let the aircraft renderer use rail oscillator phases
                    // without advancing them. Restore the V4/V5 turbine whine behavior by
                    // advancing the fan / compressor / rumble phases independently.
                    double fanHz = 28.0 + smoothedRpm * 0.018;
                    double compressorHz = 520.0 + smoothedRpm * 0.54;
                    double rumbleHz = 16.0 + smoothedRpm * 0.006;
                    advanceRailPhases(fanHz, compressorHz, rumbleHz);
                }
                wave = renderEngineOrAircraft(currentStyle, stage, speed, smoothedRpm, smoothedThrottle, smoothedAccel);

                double loadGain;
                if (currentStyle == Style.AIRCRAFT_TURBINE) {
                    loadGain = 0.32 + clamp(speed / 155.0, 0.0, 0.78) + Math.max(0.0, Math.min(0.16, smoothedAccel / 80.0));
                } else {
                    loadGain = 0.42 + 0.42 * smoothedThrottle + 0.12 * clamp((smoothedRpm - 1200.0) / 6500.0, 0.0, 1.0);
                    if (smoothedAccel < -0.40) loadGain = 0.42 + Math.min(0.20, -smoothedAccel / 80.0);
                }
                amp = muted ? 0.0 : volume * 0.54 * clamp(loadGain, 0.15, 1.05);
            }

            shiftEnvelope *= 0.99955;
            popEnvelope *= 0.99920;
            overrunEnvelope *= 0.99935;
            double sample = softClip(wave * amp);
            out[i] = (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, sample * 32767.0));
        }
        displaySpeedKmh = (float) smoothedSpeed;
        displayRpm = (float) smoothedRpm;
        displayThrottle = (float) smoothedThrottle;
        displayAccel = (float) smoothedAccel;
    }


    private void loadSampleVvvf(Context context) {
        if (context == null) {
            sampleVvvfStatus = "sample context null";
            return;
        }
        try (InputStream raw = context.getResources().openRawResource(R.raw.vvvf_0_140);
             BufferedInputStream in = new BufferedInputStream(raw, 64 * 1024)) {
            byte[] id = new byte[4];
            if (readFully(in, id, 0, 4) != 4 || !"RIFF".equals(new String(id, "US-ASCII"))) {
                sampleVvvfStatus = "sample invalid RIFF";
                return;
            }
            readLeInt(in); // RIFF chunk size
            if (readFully(in, id, 0, 4) != 4 || !"WAVE".equals(new String(id, "US-ASCII"))) {
                sampleVvvfStatus = "sample invalid WAVE";
                return;
            }

            int channels = 0;
            int rate = 0;
            int bits = 0;

            while (true) {
                if (readFully(in, id, 0, 4) != 4) break;
                int chunkSize = readLeInt(in);
                String chunk = new String(id, "US-ASCII");
                if ("fmt ".equals(chunk)) {
                    int audioFormat = readLeShort(in);
                    channels = readLeShort(in);
                    rate = readLeInt(in);
                    readLeInt(in); // byte rate
                    readLeShort(in); // block align
                    bits = readLeShort(in);
                    skipFully(in, chunkSize - 16);
                    if (audioFormat != 1 || channels < 1 || bits != 16) {
                        sampleVvvfStatus = "sample must be PCM 16-bit WAV";
                        return;
                    }
                } else if ("data".equals(chunk)) {
                    if (channels < 1 || rate < 8000 || bits != 16) {
                        sampleVvvfStatus = "sample fmt missing";
                        return;
                    }
                    int frames = chunkSize / (channels * 2);
                    float[] mono = new float[frames];
                    byte[] frameBuf = new byte[Math.max(2, channels * 2)];
                    for (int i = 0; i < frames; i++) {
                        if (readFully(in, frameBuf, 0, channels * 2) != channels * 2) {
                            break;
                        }
                        int sum = 0;
                        for (int ch = 0; ch < channels; ch++) {
                            int lo = frameBuf[ch * 2] & 0xff;
                            int hi = frameBuf[ch * 2 + 1];
                            short v = (short) ((hi << 8) | lo);
                            sum += v;
                        }
                        mono[i] = (sum / (float) channels) / 32768f;
                    }
                    sampleVvvf = mono;
                    sampleVvvfRate = rate;
                    sampleVvvfStartFrame = Math.max(0, Math.min(frames - 2, (int) (SAMPLE_VVVF_START_TRIM_SEC * rate)));
                    sampleVvvfEndFrame = Math.max(sampleVvvfStartFrame + 2, frames - 2);
                    sampleVvvfStatus = String.format(Locale.US, "sample loaded: %.1fs, %dHz, %dch, 0-140km/h",
                            frames / (double) rate, rate, channels);
                    return;
                } else {
                    skipFully(in, chunkSize);
                }
                if ((chunkSize & 1) != 0) skipFully(in, 1);
            }
            sampleVvvfStatus = "sample data chunk not found";
        } catch (Throwable t) {
            sampleVvvfStatus = "sample load failed: " + t.getMessage();
        }
    }

    private double renderSampleVvvf(double speed, double accel) {
        float[] src = sampleVvvf;
        if (src == null || src.length < 4096) {
            // Fallback if the raw WAV failed to load.
            double motorHz = calcMotorHz(speed, Style.SIEMENS_GZ_GTO);
            double carrierHz = calcCarrierHz(speed, Style.SIEMENS_GZ_GTO, motorHz);
            double subCarrierHz = calcSubCarrierHz(speed, Style.SIEMENS_GZ_GTO, motorHz);
            advanceRailPhases(motorHz, carrierHz, subCarrierHz);
            return renderGuangzhouSiemensGtoWave(calcStage(speed, Style.SIEMENS_GZ_GTO), speed,
                    Math.sin(motorPhase), 0.50 + 0.50 * Math.abs(Math.sin(motorPhase)));
        }

        double usable = Math.max(4.0, sampleVvvfEndFrame - sampleVvvfStartFrame - 2.0);
        double norm = clamp(speed / SAMPLE_VVVF_MAX_SPEED_KMH, 0.0, 1.0);
        // Keep low-speed material a little longer. This avoids jumping past the first Siemens/GTO
        // step when the vehicle begins moving from 0km/h.
        double eased = norm < 0.22 ? norm * 0.86 : 0.1892 + (norm - 0.22) * 1.04;
        eased = clamp(eased, 0.0, 1.0);
        double targetCenter = sampleVvvfStartFrame + eased * usable;

        if (sampleLoopCenterFrame < 0.0) {
            sampleLoopCenterFrame = targetCenter;
        }

        // V6 used the target center directly. When speed changed, the grain window jumped every
        // buffer and produced a "du-du-du" artifact. V7 uses a magnetic moving center: speed only
        // pulls the sample window toward the target; the audio window itself stays continuous.
        double absAccel = Math.abs(accel);
        double tauSec;
        double maxCenterMovePerOutputSample;
        if (accel > 0.25) {
            tauSec = 0.36;
            maxCenterMovePerOutputSample = 2.20;
        } else if (accel < -0.25) {
            tauSec = 0.58;
            maxCenterMovePerOutputSample = 1.15;
        } else {
            tauSec = 0.95;
            maxCenterMovePerOutputSample = 0.42;
        }
        double centerAlpha = 1.0 - Math.exp(-1.0 / (SAMPLE_RATE * tauSec));
        double wantedMove = (targetCenter - sampleLoopCenterFrame) * centerAlpha;
        sampleLoopCenterFrame += clamp(wantedMove, -maxCenterMovePerOutputSample, maxCenterMovePerOutputSample);
        sampleLoopCenterFrame = clamp(sampleLoopCenterFrame, sampleVvvfStartFrame + 32.0, sampleVvvfEndFrame - 32.0);

        double loopSec = accel > 0.25 ? SAMPLE_VVVF_ACCEL_LOOP_SEC
                : (accel < -0.25 ? SAMPLE_VVVF_DECEL_LOOP_SEC : SAMPLE_VVVF_LOOP_SEC);
        int loopFrames = (int) (loopSec * sampleVvvfRate);
        loopFrames = Math.max(12000, Math.min(loopFrames, Math.max(12000, src.length / 3)));
        double half = loopFrames * 0.5;

        // Phase is also continuous. The center may move, but the read phase never resets.
        double phaseRate;
        if (accel > 0.25) {
            phaseRate = 0.96 + clamp(accel / 28.0, 0.0, 0.26);
        } else if (accel < -0.25) {
            phaseRate = 0.64; // deceleration uses the same acceleration sample gently; no hard reverse scrub.
        } else {
            phaseRate = 0.82;
        }
        sampleLoopPhase += phaseRate / loopFrames;
        sampleLoopPhase -= Math.floor(sampleLoopPhase);

        // Two overlapping read heads create an equal-power local loop. Because the center is now
        // smoothed, acceleration/deceleration no longer causes repeated hard seeks.
        double p1 = sampleLoopPhase;
        double p2 = p1 + 0.5;
        if (p2 >= 1.0) p2 -= 1.0;
        double e1 = Math.pow(Math.sin(Math.PI * p1), 2.0);
        double e2 = Math.pow(Math.sin(Math.PI * p2), 2.0);
        double pos1 = sampleLoopCenterFrame - half + p1 * loopFrames;
        double pos2 = sampleLoopCenterFrame - half + p2 * loopFrames;
        double y = (sampleAt(src, pos1) * e1 + sampleAt(src, pos2) * e2) / Math.max(0.001, e1 + e2);

        // Light texture only. Keep the uploaded recording dominant.
        double body = 0.010 * Math.sin(engineRumblePhase) + (random.nextDouble() - 0.5) * Math.min(0.010, speed / 12000.0);
        engineRumblePhase = wrap(engineRumblePhase + TWO_PI * (10.0 + speed * 0.065) / SAMPLE_RATE);
        double accelGain = accel > 0.25 ? 1.08 : (accel < -0.25 ? 0.72 : 0.88);
        return y * accelGain * 1.20 + body;
    }

    private double sampleAt(float[] src, double pos) {
        int lo = (int) Math.floor(pos);
        if (lo < 0) lo = 0;
        if (lo >= src.length - 1) lo = src.length - 2;
        double f = pos - lo;
        return src[lo] * (1.0 - f) + src[lo + 1] * f;
    }

    private int readFully(InputStream in, byte[] b, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(b, off + total, len - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private int readLeShort(InputStream in) throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        if (b1 < 0) throw new IOException("unexpected EOF");
        return (b1 << 8) | b0;
    }

    private int readLeInt(InputStream in) throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        int b2 = in.read();
        int b3 = in.read();
        if (b3 < 0) throw new IOException("unexpected EOF");
        return (b3 << 24) | (b2 << 16) | (b1 << 8) | b0;
    }

    private void skipFully(InputStream in, int n) throws IOException {
        if (n <= 0) return;
        long left = n;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                if (in.read() < 0) break;
                skipped = 1;
            }
            left -= skipped;
        }
    }


    private void advanceRailPhases(double motorHz, double carrierHz, double subCarrierHz) {
        motorPhase += TWO_PI * motorHz / SAMPLE_RATE;
        carrierPhase += TWO_PI * carrierHz / SAMPLE_RATE;
        subCarrierPhase += TWO_PI * subCarrierHz / SAMPLE_RATE;
        motorPhase = wrap(motorPhase);
        carrierPhase = wrap(carrierPhase);
        subCarrierPhase = wrap(subCarrierPhase);
    }

    private void advanceEnginePhases(double rpm, Style currentStyle) {
        double crankHz = rpm / 60.0;
        double firingHz;
        switch (currentStyle) {
            case SUPERCHARGED_V8:
                firingHz = crankHz * 4.0; // 8 cylinders, 4-stroke: 4 events per crank rev.
                break;
            case POP_BANG_TURBO:
                firingHz = crankHz * 2.0; // inline-4 style.
                break;
            case NATURAL_ASPIRATED:
                firingHz = crankHz * 3.0; // inline-6 / high-rev flavor.
                break;
            case ROTARY:
                firingHz = crankHz * 3.0; // 2-rotor has dense, buzzy event pattern.
                break;
            default:
                firingHz = crankHz * 1.2;
                break;
        }
        double exhaustHz = firingHz * (currentStyle == Style.SUPERCHARGED_V8 ? 0.50 : 1.0);
        double intakeHz = crankHz * (currentStyle == Style.ROTARY ? 6.0 : 2.5);
        double blowerHz;
        if (currentStyle == Style.SUPERCHARGED_V8) blowerHz = 650.0 + rpm * 0.58;
        else if (currentStyle == Style.POP_BANG_TURBO) blowerHz = 900.0 + rpm * 0.38;
        else if (currentStyle == Style.NATURAL_ASPIRATED) blowerHz = 320.0 + rpm * 0.18;
        else if (currentStyle == Style.ROTARY) blowerHz = 520.0 + rpm * 0.42;
        else blowerHz = 80.0 + rpm * 0.04;

        crankPhase = wrap(crankPhase + TWO_PI * crankHz / SAMPLE_RATE);
        firingPhase = wrap(firingPhase + TWO_PI * firingHz / SAMPLE_RATE);
        exhaustPhase = wrap(exhaustPhase + TWO_PI * exhaustHz / SAMPLE_RATE);
        intakePhase = wrap(intakePhase + TWO_PI * intakeHz / SAMPLE_RATE);
        blowerPhase = wrap(blowerPhase + TWO_PI * blowerHz / SAMPLE_RATE);
        engineRumblePhase = wrap(engineRumblePhase + TWO_PI * (18.0 + rpm * 0.006) / SAMPLE_RATE);
    }

    private boolean isRailStyle(Style s) {
        return s == Style.GTO || s == Style.IGBT || s == Style.SIEMENS_GZ_GTO;
    }

    private double renderRailWave(Style currentStyle, int stage, double speed) {
        double gating = 0.50 + 0.50 * Math.abs(Math.sin(motorPhase));
        double motor = Math.sin(motorPhase)
                + 0.30 * Math.sin(2.0 * motorPhase)
                + 0.14 * Math.sin(3.0 * motorPhase)
                + 0.07 * Math.sin(5.0 * motorPhase);

        if (currentStyle == Style.SIEMENS_GZ_GTO) {
            return renderGuangzhouSiemensGtoWave(stage, speed, motor, gating);
        } else if (currentStyle == Style.GTO) {
            return renderGtoWave(stage, motor, gating);
        } else {
            return renderIgbtWave(stage, motor, gating);
        }
    }

    private double addRailTransition(Style currentStyle, double speed, double wave) {
        double transition;
        if (currentStyle == Style.SIEMENS_GZ_GTO) {
            transition = max5(stagePulse(speed, 5.5, 1.4), stagePulse(speed, 18.0, 2.3),
                    stagePulse(speed, 32.0, 2.5), stagePulse(speed, 52.0, 3.5), stagePulse(speed, 78.0, 5.0));
        } else if (currentStyle == Style.GTO) {
            transition = max3(stagePulse(speed, 8.0, 2.0), stagePulse(speed, 24.0, 2.8),
                    Math.max(stagePulse(speed, 42.0, 3.0), stagePulse(speed, 68.0, 4.0)));
        } else {
            transition = max3(stagePulse(speed, 16.0, 3.0), stagePulse(speed, 36.0, 4.0), stagePulse(speed, 78.0, 6.0));
        }
        transition = Math.max(transition, shiftEnvelope * 0.6);
        double chirpHz = currentStyle == Style.SIEMENS_GZ_GTO ? 520.0 + speed * 31.0
                : (currentStyle == Style.GTO ? 1100.0 + speed * 33.0 : 1800.0 + speed * 42.0);
        transitionPhase = wrap(transitionPhase + TWO_PI * chirpHz / SAMPLE_RATE);
        return wave * (1.0 - 0.18 * transition) + Math.sin(transitionPhase + motorPhase * 0.4) * 0.40 * transition;
    }

    private double renderGuangzhouSiemensGtoWave(int stage, double speed, double motor, double gating) {
        double squareCarrier = Math.sin(carrierPhase) >= 0 ? 1.0 : -1.0;
        double subSquare = Math.sin(subCarrierPhase) >= 0 ? 1.0 : -1.0;
        double sineCarrier = Math.sin(carrierPhase + 1.15 * Math.sin(motorPhase));
        double sineSub = Math.sin(subCarrierPhase + 0.65 * Math.sin(2.0 * motorPhase));
        double rumble = Math.sin(motorPhase * 0.48) + 0.30 * Math.sin(motorPhase * 0.96);
        double railNoise = (random.nextDouble() - 0.5) * (0.018 + Math.min(0.030, speed / 2600.0));

        switch (stage) {
            case 0:
                return 0.18 * motor + 0.42 * squareCarrier * (0.30 + 0.70 * gating)
                        + 0.24 * saw(carrierPhase) * gating + 0.20 * rumble + railNoise;
            case 1:
                return 0.22 * motor + 0.48 * squareCarrier * gating + 0.22 * sineSub
                        + 0.12 * saw(carrierPhase) + 0.10 * rumble + railNoise;
            case 2:
                return 0.28 * motor + 0.44 * Math.sin(carrierPhase + 2.8 * Math.sin(motorPhase)) * gating
                        + 0.25 * subSquare * (0.45 + 0.55 * gating) + 0.08 * rumble + railNoise * 0.85;
            case 3:
                return 0.26 * motor + 0.50 * Math.sin(carrierPhase + 1.9 * Math.sin(2.0 * motorPhase)) * gating
                        + 0.18 * Math.sin(1.5 * carrierPhase + motorPhase) + 0.11 * subSquare + railNoise * 0.70;
            case 4:
                return 0.20 * motor + 0.55 * sineCarrier + 0.22 * Math.sin(2.0 * carrierPhase + 0.5 * motorPhase)
                        + 0.08 * sineSub + railNoise * 0.55;
            default:
                return 0.15 * motor + 0.46 * Math.sin(carrierPhase + 0.35 * Math.sin(motorPhase))
                        + 0.18 * Math.sin(1.75 * carrierPhase) + 0.06 * rumble + railNoise * 0.42;
        }
    }

    private double renderGtoWave(int stage, double motor, double gating) {
        double squareCarrier = Math.sin(carrierPhase) >= 0 ? 1.0 : -1.0;
        double subSquare = Math.sin(subCarrierPhase) >= 0 ? 1.0 : -1.0;
        double sawCarrier = saw(carrierPhase);
        double roughNoise = (random.nextDouble() - 0.5) * 0.030;

        switch (stage) {
            case 0:
                return 0.22 * motor + 0.48 * squareCarrier * gating + 0.26 * sawCarrier * (0.45 + 0.55 * gating) + roughNoise;
            case 1:
                return 0.26 * motor + 0.52 * squareCarrier * gating + 0.16 * subSquare * (0.35 + 0.65 * gating)
                        + 0.08 * sawCarrier + roughNoise;
            case 2:
                return 0.34 * motor + 0.40 * Math.sin(carrierPhase + 2.2 * Math.sin(motorPhase)) * gating
                        + 0.22 * subSquare + roughNoise * 0.8;
            case 3:
                return 0.30 * motor + 0.48 * Math.sin(carrierPhase + 1.4 * Math.sin(3.0 * motorPhase)) * gating
                        + 0.14 * Math.sin(subCarrierPhase + motorPhase) + roughNoise * 0.55;
            default:
                return 0.22 * motor + 0.42 * Math.sin(carrierPhase + 0.7 * Math.sin(motorPhase))
                        + 0.18 * Math.sin(1.5 * carrierPhase + 0.5 * motorPhase) + roughNoise * 0.35;
        }
    }

    private double renderIgbtWave(int stage, double motor, double gating) {
        double smoothCarrier = Math.sin(carrierPhase + 1.8 * Math.sin(motorPhase));
        double harmonicCarrier = Math.sin(2.0 * carrierPhase + 0.7 * Math.sin(motorPhase));
        double fineNoise = (random.nextDouble() - 0.5) * 0.010;

        switch (stage) {
            case 0:
                return 0.34 * motor + 0.42 * smoothCarrier * gating + 0.12 * harmonicCarrier + fineNoise;
            case 1:
                return 0.32 * motor + 0.48 * smoothCarrier * gating + 0.16 * harmonicCarrier + fineNoise;
            case 2:
                return 0.30 * motor + 0.46 * Math.sin(carrierPhase + 0.9 * Math.sin(2.0 * motorPhase))
                        + 0.20 * Math.sin(subCarrierPhase + motorPhase) + fineNoise;
            default:
                return 0.22 * motor + 0.50 * Math.sin(carrierPhase + 0.45 * Math.sin(motorPhase))
                        + 0.12 * harmonicCarrier + fineNoise * 0.6;
        }
    }

    private double renderEngineOrAircraft(Style currentStyle, int stage, double speed, double rpm, double throttle, double accel) {
        switch (currentStyle) {
            case AIRCRAFT_TURBINE:
                return renderAircraftTurbine(speed, rpm, throttle, accel);
            case POP_BANG_TURBO:
                return renderPopBangTurbo(stage, speed, rpm, throttle, accel);
            case NATURAL_ASPIRATED:
                return renderNaturalAspirated(stage, rpm, throttle, accel);
            case ROTARY:
                return renderRotary(stage, rpm, throttle, accel);
            case SUPERCHARGED_V8:
                return renderSuperchargedV8(stage, speed, rpm, throttle, accel);
            default:
                return 0.0;
        }
    }

    private double renderAircraftTurbine(double speed, double rpm, double throttle, double accel) {
        double spool = clamp(speed / 150.0 + throttle * 0.35 + Math.max(0.0, accel) / 240.0, 0.0, 1.0);
        double fan = Math.sin(motorPhase) + 0.35 * Math.sin(2.0 * motorPhase) + 0.18 * Math.sin(3.0 * motorPhase);
        double turbineWhine = Math.sin(carrierPhase + 0.20 * Math.sin(motorPhase))
                + 0.28 * Math.sin(1.52 * carrierPhase);
        double rumble = Math.sin(subCarrierPhase) + 0.45 * Math.sin(0.52 * subCarrierPhase);
        double air = (random.nextDouble() - 0.5) * (0.10 + 0.26 * spool);
        double compressor = Math.sin(carrierPhase * 0.48 + Math.sin(motorPhase) * 0.40);
        return 0.18 * rumble + 0.34 * fan + (0.22 + 0.38 * spool) * turbineWhine
                + 0.18 * compressor + air;
    }

    private double renderPopBangTurbo(int stage, double speed, double rpm, double throttle, double accel) {
        if (accel < -0.35 || throttle < 0.18) overrunEnvelope = Math.max(overrunEnvelope, 0.70);
        if ((accel < -0.45 || throttle < 0.12) && random.nextDouble() < 0.00016) popEnvelope = Math.max(popEnvelope, 1.0);
        if (shiftEnvelope > 0.35 && random.nextDouble() < 0.00025) popEnvelope = Math.max(popEnvelope, 0.85);

        double rev = clamp((rpm - 900.0) / 6200.0, 0.0, 1.0);
        double pulse = cylinderPulse(firingPhase, 8.5) + 0.38 * cylinderPulse(firingPhase + 0.82, 12.0)
                + 0.20 * cylinderPulse(firingPhase + 1.92, 18.0);
        double lowExhaust = 0.52 * pulse + 0.22 * Math.sin(crankPhase) + 0.18 * Math.sin(engineRumblePhase);
        double intake = bandNoise(0.07 + 0.15 * throttle) * (0.25 + 0.55 * rev);
        double turbo = Math.sin(blowerPhase + 0.08 * Math.sin(intakePhase)) * clamp((rpm - 2100.0) / 4200.0, 0.0, 1.0);
        double wastegate = Math.sin(blowerPhase * 0.47 + 1.3 * Math.sin(crankPhase)) * Math.max(0.0, 0.55 - throttle);
        double crackle = popEnvelope * ((random.nextDouble() - 0.5) * 1.55 + 0.40 * Math.sin(engineRumblePhase * 0.35));
        double overrun = overrunEnvelope * (random.nextDouble() - 0.5) * 0.32;
        double shiftCut = shiftEnvelope * (random.nextDouble() - 0.5) * 0.40;
        return 0.70 * lowExhaust + 0.24 * turbo + 0.12 * wastegate + 0.25 * intake + 0.35 * crackle + overrun + shiftCut;
    }

    private double renderNaturalAspirated(int stage, double rpm, double throttle, double accel) {
        double rev = clamp((rpm - 900.0) / 7600.0, 0.0, 1.0);
        // NA should be intake and exhaust pulses, not electric whine.
        double pulse = cylinderPulse(firingPhase, 10.0) + 0.32 * cylinderPulse(firingPhase + 1.05, 16.0)
                + 0.18 * cylinderPulse(firingPhase + 2.30, 26.0);
        double exhaustTone = 0.46 * pulse + 0.25 * Math.sin(crankPhase * 2.0) + 0.12 * Math.sin(crankPhase * 3.0);
        double intakeRoar = resonator(intakePhase, 0.42 + 0.35 * rev) * (0.25 + 0.95 * throttle) * (0.25 + 0.95 * rev);
        double valveTrain = 0.05 * Math.sin(blowerPhase) * rev + bandNoise(0.035 * rev);
        double decelBurble = Math.max(0.0, -accel / 55.0) * (0.10 * Math.sin(exhaustPhase * 0.50) + bandNoise(0.035));
        double shiftDip = -0.18 * shiftEnvelope * Math.sin(crankPhase);
        return 0.66 * exhaustTone + 0.44 * intakeRoar + valveTrain + decelBurble + shiftDip;
    }

    private double renderRotary(int stage, double rpm, double throttle, double accel) {
        double rev = clamp((rpm - 1100.0) / 8200.0, 0.0, 1.0);
        // Rotary: smoother than piston, dense buzzing exhaust, less cylinder thump.
        double buzz = Math.sin(firingPhase) + 0.36 * Math.sin(2.0 * firingPhase) + 0.17 * Math.sin(3.0 * firingPhase);
        double portPulse = cylinderPulse(firingPhase + 0.35 * Math.sin(crankPhase), 5.5)
                + 0.25 * cylinderPulse(firingPhase + 2.1, 10.0);
        double intake = resonator(intakePhase, 0.70 + 0.20 * rev) * (0.25 + throttle);
        double apex = 0.08 * Math.sin(blowerPhase) * rev + bandNoise(0.045 + 0.05 * rev);
        double overrun = Math.max(0.0, -accel / 70.0) * bandNoise(0.12) * (0.4 + rev);
        return 0.46 * buzz + 0.42 * portPulse + 0.33 * intake + apex + overrun;
    }

    private double renderSuperchargedV8(int stage, double speed, double rpm, double throttle, double accel) {
        if ((shiftEnvelope > 0.35 || accel < -0.50) && random.nextDouble() < 0.00012) popEnvelope = Math.max(popEnvelope, 0.50);
        double rev = clamp((rpm - 700.0) / 6100.0, 0.0, 1.0);
        // Cross-plane V8 style: low uneven thump + wide exhaust harmonics.
        double v8Beat = cylinderPulse(firingPhase, 7.0)
                + 0.50 * cylinderPulse(firingPhase + 0.78, 8.0)
                + 0.30 * cylinderPulse(firingPhase + 2.15, 10.0)
                + 0.18 * cylinderPulse(firingPhase + 3.42, 12.0);
        double bass = 0.38 * Math.sin(crankPhase * 0.5) + 0.28 * Math.sin(crankPhase) + 0.16 * Math.sin(engineRumblePhase);
        double exhaust = 0.58 * v8Beat + bass;
        double blower = Math.sin(blowerPhase + 0.10 * Math.sin(crankPhase)) * (0.20 + 0.85 * throttle) * clamp((rpm - 1300.0) / 4700.0, 0.0, 1.0);
        double belt = 0.08 * Math.sin(0.48 * blowerPhase + 0.5 * Math.sin(crankPhase)) * rev;
        double tireRoad = bandNoise(Math.min(0.09, speed / 2200.0));
        double pop = popEnvelope * ((random.nextDouble() - 0.5) * 0.65 + 0.18 * Math.sin(engineRumblePhase * 0.7));
        double shiftCut = shiftEnvelope * (-0.20 * exhaust + bandNoise(0.10));
        return 0.78 * exhaust + 0.28 * blower + belt + tireRoad + pop + shiftCut;
    }

    private EngineState calcEngineState(double speed, Style currentStyle) {
        long now = System.nanoTime();
        boolean useExternal = externalRpm > 300f && (now - externalStateNs) < 1_500_000_000L;
        double throttle;
        if (externalThrottle >= 0f && (now - externalStateNs) < 1_500_000_000L) {
            throttle = externalThrottle;
        } else if (smoothedAccel > 0.35) {
            throttle = clamp(0.45 + smoothedAccel / 42.0, 0.0, 1.0);
        } else if (smoothedAccel < -0.35) {
            throttle = 0.04;
        } else {
            throttle = 0.20 + Math.min(0.20, speed / 190.0);
        }

        if (currentStyle == Style.AIRCRAFT_TURBINE) {
            double rpm = 1000.0 + clamp(speed / 160.0, 0.0, 1.0) * 5200.0 + throttle * 1200.0;
            return new EngineState(rpm, throttle);
        }

        if (useExternal) {
            return new EngineState(externalRpm, throttle);
        }

        int gear = calcGear(speed, currentStyle);
        double maxRpm;
        double minRpm;
        switch (currentStyle) {
            case ROTARY:
                minRpm = 1300.0; maxRpm = 9000.0; break;
            case NATURAL_ASPIRATED:
                minRpm = 950.0; maxRpm = 7800.0; break;
            case SUPERCHARGED_V8:
                minRpm = 750.0; maxRpm = 6500.0; break;
            case POP_BANG_TURBO:
            default:
                minRpm = 900.0; maxRpm = 6900.0; break;
        }
        double[] ranges = {0, 36, 66, 98, 135, 178, 260};
        if (currentStyle == Style.SUPERCHARGED_V8) ranges = new double[]{0, 42, 78, 118, 162, 212, 260};
        if (currentStyle == Style.ROTARY) ranges = new double[]{0, 34, 62, 94, 128, 168, 260};
        int idx = Math.max(0, Math.min(gear - 1, ranges.length - 2));
        double lo = ranges[idx];
        double hi = ranges[idx + 1];
        double x = clamp((speed - lo) / Math.max(1.0, hi - lo), 0.0, 1.0);
        double rpm = minRpm + x * (maxRpm - minRpm);
        rpm -= shiftEnvelope * 900.0;
        if (speed < 0.8) rpm = minRpm + throttle * 650.0;
        return new EngineState(clamp(rpm, minRpm, maxRpm), throttle);
    }

    private int calcGear(double speed, Style s) {
        if (s == Style.SUPERCHARGED_V8) {
            if (speed < 42) return 1;
            if (speed < 78) return 2;
            if (speed < 118) return 3;
            if (speed < 162) return 4;
            if (speed < 212) return 5;
            return 6;
        }
        if (s == Style.ROTARY) {
            if (speed < 34) return 1;
            if (speed < 62) return 2;
            if (speed < 94) return 3;
            if (speed < 128) return 4;
            if (speed < 168) return 5;
            return 6;
        }
        if (speed < 36) return 1;
        if (speed < 66) return 2;
        if (speed < 98) return 3;
        if (speed < 135) return 4;
        if (speed < 178) return 5;
        return 6;
    }

    private int calcStage(double speed, Style currentStyle) {
        switch (currentStyle) {
            case SAMPLE_VVVF_0_140:
                if (speed < 20.0) return 0;
                if (speed < 55.0) return 1;
                if (speed < 90.0) return 2;
                if (speed < 120.0) return 3;
                return 4;
            case SIEMENS_GZ_GTO:
                if (speed < 5.5) return 0;
                if (speed < 18.0) return 1;
                if (speed < 32.0) return 2;
                if (speed < 52.0) return 3;
                if (speed < 78.0) return 4;
                return 5;
            case GTO:
                if (speed < 8.0) return 0;
                if (speed < 24.0) return 1;
                if (speed < 42.0) return 2;
                if (speed < 68.0) return 3;
                return 4;
            case IGBT:
                if (speed < 16.0) return 0;
                if (speed < 36.0) return 1;
                if (speed < 78.0) return 2;
                return 3;
            case AIRCRAFT_TURBINE:
                if (speed < 25.0) return 0;
                if (speed < 70.0) return 1;
                if (speed < 130.0) return 2;
                return 3;
            default:
                return calcGear(speed, currentStyle);
        }
    }

    private String getStageName(double speed, Style currentStyle) {
        switch (currentStyle) {
            case SAMPLE_VVVF_0_140:
                switch (calcStage(speed, currentStyle)) {
                    case 0: return "真实采样 VVVF · 低速起步";
                    case 1: return "真实采样 VVVF · 一/二阶段";
                    case 2: return "真实采样 VVVF · 中高速段";
                    case 3: return "真实采样 VVVF · 高速同步";
                    default: return "真实采样 VVVF · 120-140km/h";
                }
            case SIEMENS_GZ_GTO:
                switch (calcStage(speed, currentStyle)) {
                    case 0: return "GZ-Siemens 起步脉冲";
                    case 1: return "GZ-Siemens 一段音阶";
                    case 2: return "GZ-Siemens 二阶段锁相";
                    case 3: return "GZ-Siemens 三阶段啸叫";
                    case 4: return "GZ-Siemens 高速同步";
                    default: return "GZ-Siemens 弱磁巡航";
                }
            case GTO:
                switch (calcStage(speed, currentStyle)) {
                    case 0: return "GTO 起步脉冲";
                    case 1: return "GTO 异步上扫";
                    case 2: return "GTO 同步一段";
                    case 3: return "GTO 同步二段";
                    default: return "GTO 弱磁巡航";
                }
            case IGBT:
                switch (calcStage(speed, currentStyle)) {
                    case 0: return "IGBT 低速顺滑";
                    case 1: return "IGBT 异步上扫";
                    case 2: return "IGBT 同步啸叫";
                    default: return "IGBT 高速轻鸣";
                }
            case AIRCRAFT_TURBINE:
                switch (calcStage(speed, currentStyle)) {
                    case 0: return "涡扇起转";
                    case 1: return "涡扇推力上升";
                    case 2: return "涡扇高速推进";
                    default: return "涡扇巡航";
                }
            case POP_BANG_TURBO:
                return "Turbo 真实排气脉冲 · " + calcGear(speed, currentStyle) + "挡";
            case NATURAL_ASPIRATED:
                return "NA 进气/排气脉冲 · " + calcGear(speed, currentStyle) + "挡";
            case ROTARY:
                return "Rotary 转子蜂鸣 · " + calcGear(speed, currentStyle) + "挡";
            case SUPERCHARGED_V8:
                return "V8 机械增压/低频排气 · " + calcGear(speed, currentStyle) + "挡";
            default:
                return "Unknown";
        }
    }

    private double calcMotorHz(double speed, Style style) {
        double hz;
        if (style == Style.SIEMENS_GZ_GTO) {
            if (speed < 5.5) hz = quantize(7.0 + speed * 1.35, 1.3);
            else if (speed < 18.0) hz = quantize(15.0 + (speed - 5.5) * 2.75, 2.7);
            else if (speed < 32.0) hz = quantize(49.0 + (speed - 18.0) * 1.95, 3.8);
            else if (speed < 52.0) hz = quantize(78.0 + (speed - 32.0) * 1.20, 5.2);
            else if (speed < 78.0) hz = 103.0 + (speed - 52.0) * 0.86;
            else hz = 126.0 + (speed - 78.0) * 0.45;
            return clamp(hz, 0.0, 185.0);
        } else if (style == Style.GTO) {
            if (speed < 8.0) hz = quantize(8.0 + speed * 1.15, 1.8);
            else if (speed < 24.0) hz = quantize(17.0 + (speed - 8.0) * 2.35, 3.2);
            else if (speed < 42.0) hz = quantize(56.0 + (speed - 24.0) * 1.25, 4.6);
            else if (speed < 68.0) hz = 80.0 + (speed - 42.0) * 0.96;
            else hz = 105.0 + (speed - 68.0) * 0.58;
            return clamp(hz, 0.0, 190.0);
        } else {
            if (speed < 16.0) hz = 10.0 + speed * 1.70;
            else if (speed < 36.0) hz = 38.0 + (speed - 16.0) * 1.95;
            else if (speed < 78.0) hz = 78.0 + (speed - 36.0) * 1.18;
            else hz = 128.0 + (speed - 78.0) * 0.62;
            return clamp(hz, 0.0, 235.0);
        }
    }

    private double calcCarrierHz(double speed, Style style, double motorHz) {
        if (style == Style.SIEMENS_GZ_GTO) {
            if (speed < 5.5) return quantize(120.0 + speed * 38.0, 24.0);
            if (speed < 18.0) {
                double[] notes = {300.0, 360.0, 430.0, 520.0, 620.0, 740.0, 880.0};
                double pos = (speed - 5.5) / 1.85;
                int idx = (int) clamp(Math.floor(pos), 0, notes.length - 1);
                double glide = pos - Math.floor(pos);
                double next = notes[Math.min(notes.length - 1, idx + 1)];
                return notes[idx] * (1.0 - glide * 0.28) + next * (glide * 0.28);
            }
            if (speed < 32.0) return clamp(motorHz * 15.0, 760.0, 1280.0);
            if (speed < 52.0) return clamp(motorHz * 23.5, 1450.0, 2350.0);
            if (speed < 78.0) return clamp(motorHz * 31.0, 2600.0, 3850.0);
            return clamp(3750.0 + (speed - 78.0) * 5.2, 3750.0, 4700.0);
        } else if (style == Style.GTO) {
            if (speed < 8.0) return 180.0 + speed * 48.0;
            if (speed < 24.0) return 520.0 + (speed - 8.0) * 82.0;
            if (speed < 42.0) return clamp(motorHz * 18.0, 980.0, 1650.0);
            if (speed < 68.0) return clamp(motorHz * 27.0, 2200.0, 3300.0);
            return clamp(3150.0 + (speed - 68.0) * 7.5, 3150.0, 4500.0);
        } else {
            if (speed < 16.0) return 760.0 + speed * 48.0;
            if (speed < 36.0) return 1500.0 + (speed - 16.0) * 56.0;
            if (speed < 78.0) return clamp(motorHz * 34.0, 2500.0, 4300.0);
            return clamp(4800.0 + (speed - 78.0) * 6.0, 4800.0, 5800.0);
        }
    }

    private double calcSubCarrierHz(double speed, Style style, double motorHz) {
        if (style == Style.SIEMENS_GZ_GTO) {
            if (speed < 18.0) return 90.0 + speed * 8.0;
            if (speed < 52.0) return motorHz * 7.0;
            return 760.0 + speed * 5.0;
        } else if (style == Style.GTO) {
            return Math.max(70.0, motorHz * 5.5);
        } else {
            return 900.0 + speed * 10.0;
        }
    }

    private double cylinderPulse(double phase, double sharpness) {
        double x = (1.0 + Math.sin(phase)) * 0.5;
        double p = Math.pow(x, sharpness);
        // Add a small negative pressure tail like exhaust pulses through a pipe.
        return p - 0.20 * Math.pow((1.0 + Math.sin(phase - 0.75)) * 0.5, sharpness * 0.45);
    }

    private double harshPulse(double phase, double sharpness) {
        double pulse = cylinderPulse(phase, sharpness);
        return Math.tanh(pulse * 3.5);
    }

    private double resonator(double phase, double brightness) {
        return Math.sin(phase) * 0.54
                + Math.sin(2.0 * phase + 0.2) * 0.26 * brightness
                + Math.sin(3.0 * phase + 0.6) * 0.13 * brightness
                + Math.sin(5.0 * phase) * 0.07 * brightness;
    }

    private double bandNoise(double amount) {
        // Cheap noise helper. Not a real filter, but it prevents pure organ/electric tones.
        return (random.nextDouble() - 0.5) * amount;
    }

    private double saw(double phase) {
        return 2.0 * (phase / TWO_PI - Math.floor(phase / TWO_PI + 0.5));
    }

    private double quantize(double value, double step) {
        if (step <= 0.0) return value;
        return Math.round(value / step) * step;
    }

    private double stagePulse(double speed, double center, double width) {
        double d = Math.abs(speed - center) / Math.max(0.001, width);
        return Math.exp(-d * d);
    }

    private double max3(double a, double b, double c) {
        return Math.max(a, Math.max(b, c));
    }

    private double max5(double a, double b, double c, double d, double e) {
        return Math.max(Math.max(a, b), Math.max(Math.max(c, d), e));
    }

    private double softClip(double x) {
        return Math.tanh(x * 1.22) / Math.tanh(1.22);
    }

    private double clamp(double x, double lo, double hi) {
        return Math.max(lo, Math.min(hi, x));
    }

    private double wrap(double phase) {
        if (phase > TWO_PI || phase < -TWO_PI) phase %= TWO_PI;
        if (phase < 0) phase += TWO_PI;
        return phase;
    }

    private void notifyStatus(String text) {
        StatusListener listener = statusListener;
        if (listener != null) {
            try { listener.onStatus(text); } catch (Throwable ignored) {}
        }
    }

    private static class EngineState {
        final double rpm;
        final double throttle;
        EngineState(double rpm, double throttle) {
            this.rpm = rpm;
            this.throttle = throttle;
        }
    }
}
