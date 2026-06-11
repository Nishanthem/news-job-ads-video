package com.jobads.audio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Generates a short, loopable, royalty-free background-music bed using {@code ffmpeg}'s expression
 * synthesiser ({@code aevalsrc}). Because the audio is synthesised from scratch, it is free of any
 * third-party copyright and therefore safe to publish on YouTube.
 *
 * <p>The bed is a lively <strong>tabla</strong> groove: each stroke (bass {@code ge}, ringing
 * {@code na}, sharp {@code tin}, and the combined {@code dha}) is approximated with decaying sine
 * partials and the strokes are laid out on an eight-beat cycle. The caller loops and volume-ducks
 * it under the narration; users who want a different track can instead supply their own file via
 * {@code --music}.
 */
public final class BackgroundMusic {

    private BackgroundMusic() {
    }

    /** Tabla stroke expressions (functions of time {@code t}, one drum hit each). */
    private static final String GE  = "0.95*exp(-13*t)*sin(2*PI*90*t)";
    private static final String NA  = "0.55*exp(-6*t)*sin(2*PI*330*t)+0.22*exp(-9*t)*sin(2*PI*742*t)";
    private static final String TIN = "0.5*exp(-10*t)*sin(2*PI*500*t)+0.18*exp(-12*t)*sin(2*PI*1000*t)";
    private static final String DHA = "0.9*exp(-12*t)*sin(2*PI*92*t)+0.5*exp(-6*t)*sin(2*PI*330*t)"
            + "+0.2*exp(-9*t)*sin(2*PI*742*t)";

    /** The eight-beat tabla cycle (Keherwa-style groove). */
    private static final String[] PATTERN = {DHA, GE, NA, TIN, NA, GE, DHA, NA};

    /** Milliseconds between strokes (300 ms ~ a brisk, danceable tabla tempo). */
    private static final int BEAT_MS = 300;
    /** How long each synthesised stroke is allowed to ring. */
    private static final double STROKE_SECONDS = 0.45;
    /** Overall loop gain, calibrated so the ducked bed is audible but well under the narration. */
    private static final double LOOP_VOLUME = 0.30;

    /**
     * Synthesise the default tabla loop into {@code dir/bg-music-loop.wav}.
     *
     * @return the generated loop path, or {@code null} if synthesis failed (caller continues
     *         without music).
     */
    public static Path generateDefaultLoop(Path dir) {
        Path out = dir.resolve("bg-music-loop.wav");
        double loopSeconds = (PATTERN.length * BEAT_MS) / 1000.0;

        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg");
        cmd.add("-y");

        // One aevalsrc input per stroke in the cycle.
        for (String stroke : PATTERN) {
            cmd.add("-f");
            cmd.add("lavfi");
            cmd.add("-i");
            cmd.add(String.format(Locale.US, "aevalsrc=%s:d=%.2f:s=44100", stroke, STROKE_SECONDS));
        }

        // Delay each stroke to its beat position, then mix and level.
        StringBuilder fc = new StringBuilder();
        for (int i = 0; i < PATTERN.length; i++) {
            int delay = i * BEAT_MS;
            fc.append('[').append(i).append(']')
              .append("adelay=").append(delay).append('|').append(delay)
              .append("[a").append(i).append("];");
        }
        for (int i = 0; i < PATTERN.length; i++) {
            fc.append("[a").append(i).append(']');
        }
        fc.append("amix=inputs=").append(PATTERN.length).append(":normalize=0:duration=longest,")
          .append(String.format(Locale.US, "volume=%.2f,", LOOP_VOLUME))
          .append("aformat=sample_rates=44100:channel_layouts=stereo[out]");

        cmd.add("-filter_complex");
        cmd.add(fc.toString());
        cmd.add("-map");
        cmd.add("[out]");
        cmd.add("-t");
        cmd.add(String.format(Locale.US, "%.2f", loopSeconds));
        cmd.add(out.toAbsolutePath().toString());

        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0) {
                System.err.println("[music] could not synthesise background music (continuing "
                        + "without it):\n" + log);
                return null;
            }
            return out;
        } catch (IOException e) {
            System.err.println("[music] background-music synthesis failed: " + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
