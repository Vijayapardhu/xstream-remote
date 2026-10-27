package com.xstream.remote;

import android.content.Context;
import android.os.Build;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;

/**
 * One place for every vibration the app makes, including the home-screen widget.
 *
 * On Android 12+ (API 31) the patterns are built from vibration primitives
 * (thud / click / tick). A "thud" is a low-frequency, motor-driven hit, which
 * reads as much deeper than a short one-shot. Devices that do not expose those
 * primitives fall back to amplitude-shaped waveforms on Android 8+ and to a
 * plain duration on older releases.
 */
final class Haptics {

    private Haptics() {
    }

    static void play(Context context, String kind) {
        if (context == null) {
            return;
        }
        String type = kind == null ? "tap" : kind;

        try {
            if (Settings.System.getInt(context.getContentResolver(),
                    Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) {
                return;
            }
        } catch (Exception ignored) {
            // Some OEM builds restrict the setting. The app toggle still applies.
        }

        Vibrator vibrator = vibrator(context);
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }

        try {
            if (Build.VERSION.SDK_INT < 26) {
                vibrator.vibrate(legacyMs(type));
                return;
            }
            VibrationEffect effect = build(vibrator, type);
            if (effect == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 33) {
                vibrator.vibrate(effect,
                        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH));
            } else {
                vibrator.vibrate(effect);
            }
        } catch (Exception ignored) {
            // A bad pattern or a revoked permission must never crash a key press.
        }
    }

    private static Vibrator vibrator(Context context) {
        if (Build.VERSION.SDK_INT >= 31) {
            VibratorManager manager =
                    (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            if (manager != null) {
                return manager.getDefaultVibrator();
            }
        }
        return (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
    }

    private static VibrationEffect build(Vibrator vibrator, String type) {
        if (Build.VERSION.SDK_INT >= 31) {
            VibrationEffect composed = compose(vibrator, type);
            if (composed != null) {
                return composed;
            }
        }
        return waveform(vibrator, type);
    }

    /**
     * Deep, composed patterns. PRIMITIVE_THUD gives the low body; CLICK and TICK
     * add the edge. A missing primitive falls back to the waveform below.
     */
    private static VibrationEffect compose(Vibrator vibrator, String type) {
        int[] primitives = {
                VibrationEffect.Composition.PRIMITIVE_THUD,
                VibrationEffect.Composition.PRIMITIVE_CLICK,
                VibrationEffect.Composition.PRIMITIVE_TICK
        };
        if (!vibrator.areAllPrimitivesSupported(primitives)) {
            return null;
        }
        VibrationEffect.Composition c = VibrationEffect.startComposition();
        switch (type) {
            case "tick":
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.75f, 0)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f, 8);
                break;
            case "key":
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.62f, 0)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.55f, 12);
                break;
            case "ok":
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f, 0)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.62f, 30)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.34f, 66);
                break;
            case "long":
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.95f, 0)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.55f, 26);
                break;
            case "back":
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.85f, 0)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f, 32);
                break;
            case "success":
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.72f, 0)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.6f, 28)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f, 70);
                break;
            case "error":
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f, 0)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1.0f, 84);
                break;
            default:
                c.addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.95f, 0)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.55f, 10);
                break;
        }
        return c.compose();
    }

    private static VibrationEffect waveform(Vibrator vibrator, String type) {
        long[] timings;
        int[] amplitudes;
        switch (type) {
            case "tick":
                timings = new long[]{0, 18};
                amplitudes = new int[]{0, 175};
                break;
            case "key":
                timings = new long[]{0, 26};
                amplitudes = new int[]{0, 225};
                break;
            case "ok":
                timings = new long[]{0, 20, 12, 30};
                amplitudes = new int[]{0, 255, 0, 195};
                break;
            case "long":
                timings = new long[]{0, 44};
                amplitudes = new int[]{0, 250};
                break;
            case "back":
                timings = new long[]{0, 18, 30, 18};
                amplitudes = new int[]{0, 195, 0, 140};
                break;
            case "success":
                timings = new long[]{0, 18, 26, 22, 38, 42};
                amplitudes = new int[]{0, 205, 0, 120, 0, 255};
                break;
            case "error":
                timings = new long[]{0, 30, 42, 32};
                amplitudes = new int[]{0, 235, 0, 235};
                break;
            default:
                timings = new long[]{0, 22};
                amplitudes = new int[]{0, 200};
                break;
        }
        if (vibrator.hasAmplitudeControl()) {
            return VibrationEffect.createWaveform(timings, amplitudes, -1);
        }
        return VibrationEffect.createWaveform(timings, -1);
    }

    private static long legacyMs(String type) {
        switch (type) {
            case "tick":
                return 20;
            case "key":
                return 30;
            case "ok":
                return 45;
            case "long":
                return 55;
            case "back":
                return 36;
            case "success":
                return 80;
            case "error":
                return 70;
            default:
                return 22;
        }
    }
}
