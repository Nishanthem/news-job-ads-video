package com.jobads.audio;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Generates a short, loopable, royalty-free background-music bed using {@code ffmpeg}'s sine
 * synthesiser. Because the audio is synthesised from scratch (a gentle four-chord pad), it is
 * free of any third-party copyright and therefore safe to publish on YouTube.
 *
 * <p>The bed is intentionally soft and slow (a vi&ndash;IV&ndash;I&ndash;V progression: Am&ndash;F&ndash;C&ndash;G)
 * so it sits quietly under the narration. The caller loops and volume-ducks it to the final length;
 * users who want a richer track can instead supply their own file via {@code --music}.
 */
public final class BackgroundMusic {

    private BackgroundMusic() {
    }

    /** One chord = three harmonious sine frequencies (Hz). */
    private static final double[][] CHORDS = {
            {220.00, 261.63, 329.63}, // A minor
            {174.61, 220.00, 261.63}, // F major
            {261.63, 329.63, 392.00}, // C major
            {196.00, 246.94, 293.66}, // G major
    };

    private static final double CHORD_SECONDS = 4.0;
    private static final double FADE_SECONDS = 0.6;

    /**
     * Synthesise the default ambient loop (~16s) into {@code dir/bg-music-loop.wav}.
     *
     * @return the generated loop path, or {@code null} if synthesis failed (caller continues
     *         without music).
     */
    public static Path generateDefaultLoop(Path dir) {
        Path out = dir.resolve("bg-music-loop.wav");
        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg");
        cmd.add("-y");

        // One lavfi sine input per note across all chords.
        for (double[] chord : CHORDS) {
            for (double freq : chord) {
                cmd.add("-f");
                cmd.add("lavfi");
                cmd.add("-i");
                cmd.add(String.format(Locale.US,
                        "sine=frequency=%.2f:sample_rate=44100:duration=%.2f", freq, CHORD_SECONDS));
            }
        }

        StringBuilder fc = new StringBuilder();
        int idx = 0;
        double fadeOutStart = CHORD_SECONDS - FADE_SECONDS;
        for (int c = 0; c < CHORDS.length; c++) {
            fc.append('[').append(idx++).append(']')
              .append('[').append(idx++).append(']')
              .append('[').append(idx++).append(']')
              .append("amix=inputs=3:normalize=0,")
              .append("volume=0.22,")
              .append(String.format(Locale.US, "afade=t=in:st=0:d=%.2f,", FADE_SECONDS))
              .append(String.format(Locale.US, "afade=t=out:st=%.2f:d=%.2f", fadeOutStart, FADE_SECONDS))
              .append("[c").append(c).append("];");
        }
        for (int c = 0; c < CHORDS.length; c++) {
            fc.append("[c").append(c).append(']');
        }
        fc.append("concat=n=").append(CHORDS.length).append(":v=0:a=1,")
          .append("lowpass=f=1100,")
          .append("aformat=sample_rates=44100:channel_layouts=stereo[out]");

        cmd.add("-filter_complex");
        cmd.add(fc.toString());
        cmd.add("-map");
        cmd.add("[out]");
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
