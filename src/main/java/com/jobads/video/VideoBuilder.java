package com.jobads.video;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Combines the rendered slide images into an MP4 using the system {@code ffmpeg} binary. Slides are
 * stitched together with a short crossfade ({@code xfade}) so the video flows instead of hard-cutting
 * between static frames. When narration is supplied, each slide is shown for as long as its spoken
 * line plays and the crossfades happen during the silent lead/trail padding, so audio stays in sync.
 */
public class VideoBuilder {

    /** Crossfade length between consecutive slides, in seconds. */
    private static final double TRANSITION = 0.4;

    private final int secondsPerSlide;
    private final int fps;
    private Path bgmPath;
    private double bgmVolume = 0.12;

    public VideoBuilder(int secondsPerSlide, int fps) {
        this.secondsPerSlide = secondsPerSlide;
        this.fps = fps;
    }

    public void setBgm(Path bgmPath, double volume) {
        this.bgmPath = bgmPath;
        this.bgmVolume = Math.max(0.0, Math.min(1.0, volume));
    }

    /**
     * Build a silent video (no narration). Each slide is shown for {@code secondsPerSlide}.
     *
     * @param slides ordered list of PNG paths
     * @param output destination .mp4 path
     * @return the output path
     */
    public Path build(List<Path> slides, Path output) throws IOException, InterruptedException {
        if (slides == null || slides.isEmpty()) {
            throw new IllegalArgumentException("No slides to build a video from");
        }
        requireFfmpeg();

        List<Double> durations = new ArrayList<>();
        for (int i = 0; i < slides.size(); i++) {
            durations.add((double) secondsPerSlide);
        }
        double total = durations.stream().mapToDouble(Double::doubleValue).sum();
        double t = transitionFor(durations);

        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg");
        cmd.add("-y");
        cmd.addAll(imageInputs(slides, durations, t));

        StringBuilder filter = new StringBuilder(videoXfadeFilter(slides.size(), durations, t, total));
        boolean hasBgm = bgmPath != null && Files.isRegularFile(bgmPath);
        if (hasBgm) {
            cmd.add("-stream_loop");
            cmd.add("-1");
            cmd.add("-i");
            cmd.add(bgmPath.toAbsolutePath().toString());
            int bgmIdx = slides.size();
            filter.append(";[").append(bgmIdx).append(":a]volume=")
                    .append(fmt(bgmVolume)).append("[aout]");
        }

        cmd.add("-filter_complex");
        cmd.add(filter.toString());
        cmd.add("-map");
        cmd.add("[vout]");
        if (hasBgm) {
            cmd.add("-map");
            cmd.add("[aout]");
            cmd.add("-c:a");
            cmd.add("aac");
            cmd.add("-b:a");
            cmd.add("128k");
        }
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-pix_fmt");
        cmd.add("yuv420p");
        cmd.add("-t");
        cmd.add(fmt(total));
        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add(output.toAbsolutePath().toString());

        runFfmpeg(cmd);
        return output;
    }

    /**
     * Build the video with a narration track. Each slide is shown for as long as its narration
     * audio plays (plus a short lead/trail pause), and the spoken audio is muxed into the MP4.
     *
     * @param slides ordered PNG paths
     * @param audios per-slide WAV paths; an entry may be {@code null} if a slide has no narration
     * @param output destination .mp4 path
     */
    public Path buildWithAudio(List<Path> slides, List<Path> audios, Path output)
            throws IOException, InterruptedException {
        if (slides == null || slides.isEmpty()) {
            throw new IllegalArgumentException("No slides to build a video from");
        }
        if (slides.size() != audios.size()) {
            throw new IllegalArgumentException("slides and audios must be the same size");
        }
        requireFfmpeg();

        double leadSec = 0.4;
        double trailSec = 0.8;
        Path tmpDir = Files.createTempDirectory("narration-");
        List<Path> paddedAudios = new ArrayList<>();
        List<Double> durations = new ArrayList<>();

        for (int i = 0; i < slides.size(); i++) {
            Path audio = audios.get(i);
            double speech = (audio != null) ? probeDuration(audio) : 0.0;
            // Fall back to the fixed per-slide time when a slide has no narration.
            double total = (speech > 0) ? leadSec + speech + trailSec : secondsPerSlide;
            durations.add(total);

            Path padded = tmpDir.resolve("a-" + i + ".wav");
            if (speech > 0) {
                // Lead silence (adelay) + pad with trailing silence, then cut to the exact length.
                int delayMs = (int) Math.round(leadSec * 1000);
                runFfmpeg(ff("-y", "-i", audio.toAbsolutePath().toString(),
                        "-af", "adelay=" + delayMs + "|" + delayMs + ",apad",
                        "-t", fmt(total), "-ar", "22050", "-ac", "1",
                        padded.toAbsolutePath().toString()));
            } else {
                // Pure silence matching the slide duration.
                runFfmpeg(ff("-y", "-f", "lavfi", "-i",
                        "anullsrc=channel_layout=mono:sample_rate=22050",
                        "-t", fmt(total), padded.toAbsolutePath().toString()));
            }
            paddedAudios.add(padded);
        }

        // Concatenate the per-slide audio into one continuous track.
        Path audioList = tmpDir.resolve("audio.txt");
        StringBuilder asb = new StringBuilder();
        for (Path a : paddedAudios) {
            asb.append("file '").append(a.toAbsolutePath()).append("'\n");
        }
        Files.writeString(audioList, asb.toString(), StandardCharsets.UTF_8);
        Path combinedAudio = tmpDir.resolve("combined.wav");
        runFfmpeg(ff("-y", "-f", "concat", "-safe", "0",
                "-i", audioList.toAbsolutePath().toString(),
                "-c", "copy", combinedAudio.toAbsolutePath().toString()));

        double totalDur = durations.stream().mapToDouble(Double::doubleValue).sum();
        double t = transitionFor(durations);

        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg");
        cmd.add("-y");
        cmd.addAll(imageInputs(slides, durations, t));
        // Narration is the next input, background music (optional) after it.
        int voiceIdx = slides.size();
        cmd.add("-i");
        cmd.add(combinedAudio.toAbsolutePath().toString());
        boolean hasBgm = bgmPath != null && Files.isRegularFile(bgmPath);
        if (hasBgm) {
            cmd.add("-stream_loop");
            cmd.add("-1");
            cmd.add("-i");
            cmd.add(bgmPath.toAbsolutePath().toString());
        }

        StringBuilder filter = new StringBuilder(videoXfadeFilter(slides.size(), durations, t, totalDur));
        String audioMap;
        if (hasBgm) {
            int bgmIdx = slides.size() + 1;
            filter.append(";[").append(voiceIdx).append(":a]volume=1.0[voice];")
                    .append("[").append(bgmIdx).append(":a]volume=").append(fmt(bgmVolume)).append("[bgm];")
                    .append("[voice][bgm]amix=inputs=2:duration=first[aout]");
            audioMap = "[aout]";
        } else {
            audioMap = voiceIdx + ":a";
        }

        cmd.add("-filter_complex");
        cmd.add(filter.toString());
        cmd.add("-map");
        cmd.add("[vout]");
        cmd.add("-map");
        cmd.add(audioMap);
        cmd.add("-c:v");
        cmd.add("libx264");
        cmd.add("-pix_fmt");
        cmd.add("yuv420p");
        cmd.add("-c:a");
        cmd.add("aac");
        cmd.add("-b:a");
        cmd.add("128k");
        cmd.add("-t");
        cmd.add(fmt(totalDur));
        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add(output.toAbsolutePath().toString());

        runFfmpeg(cmd);

        deleteQuietly(tmpDir);
        return output;
    }

    /** {@code -loop 1 -t (d_i + transition) -framerate fps -i slide_i} for every slide. */
    private List<String> imageInputs(List<Path> slides, List<Double> durations, double t) {
        List<String> args = new ArrayList<>();
        for (int i = 0; i < slides.size(); i++) {
            args.add("-loop");
            args.add("1");
            args.add("-framerate");
            args.add(Integer.toString(fps));
            args.add("-t");
            args.add(fmt(durations.get(i) + t));
            args.add("-i");
            args.add(slides.get(i).toAbsolutePath().toString());
        }
        return args;
    }

    /**
     * Build the {@code filter_complex} fragment that crossfades the slide inputs into a single
     * {@code [vout]} stream, with a fade from/to black at the very start/end. Each slide is padded
     * by {@code t}; the crossfade eats that padding so slide i's steady time equals its narration
     * duration and transitions land in the silent gaps between spoken lines.
     */
    private String videoXfadeFilter(int n, List<Double> durations, double t, double total) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append("[").append(i).append(":v]fps=").append(fps)
                    .append(",format=yuv420p,setsar=1[v").append(i).append("];");
        }
        String last;
        if (n == 1) {
            last = "v0";
        } else {
            double offset = 0.0;
            String prev = "v0";
            for (int j = 1; j < n; j++) {
                offset += durations.get(j - 1);
                String out = "x" + j;
                sb.append("[").append(prev).append("][v").append(j).append("]")
                        .append("xfade=transition=fade:duration=").append(fmt(t))
                        .append(":offset=").append(fmt(offset)).append("[").append(out).append("];");
                prev = out;
            }
            last = prev;
        }
        double fadeOutStart = Math.max(0.0, total - 0.6);
        sb.append("[").append(last).append("]format=yuv420p,")
                .append("fade=t=in:st=0:d=0.5,")
                .append("fade=t=out:st=").append(fmt(fadeOutStart)).append(":d=0.6[vout]");
        return sb.toString();
    }

    /** Keep the crossfade shorter than the shortest slide so offsets stay valid. */
    private static double transitionFor(List<Double> durations) {
        double min = durations.stream().mapToDouble(Double::doubleValue).min().orElse(TRANSITION);
        return Math.max(0.15, Math.min(TRANSITION, min / 2.0 - 0.05));
    }

    private static String fmt(double seconds) {
        return String.format(Locale.US, "%.3f", seconds);
    }

    private static double probeDuration(Path media) {
        try {
            Process p = new ProcessBuilder("ffprobe", "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=nokey=1:noprint_wrappers=1",
                    media.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            p.waitFor();
            return out.isBlank() ? 0.0 : Double.parseDouble(out);
        } catch (IOException | NumberFormatException e) {
            return 0.0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0.0;
        }
    }

    private static List<String> ff(String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg");
        for (String a : args) {
            cmd.add(a);
        }
        return cmd;
    }

    private static void runFfmpeg(List<String> fullCmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(fullCmd).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        if (exit != 0) {
            throw new IOException("ffmpeg failed (exit " + exit + "):\n" + log);
        }
    }

    /**
     * Generate a simple ambient background music track using ffmpeg's audio synthesis.
     * Produces a gentle pad sound suitable for playing behind narration.
     */
    public static Path generateDefaultBgm(Path outputDir) throws IOException, InterruptedException {
        Path bgm = outputDir.resolve("default-bgm.mp3");
        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg");
        cmd.addAll(List.of("-y", "-f", "lavfi", "-i",
                "sine=frequency=220:duration=12,volume=0.3"
                        + ",aecho=0.8:0.88:60:0.4"
                        + ",lowpass=f=800"
                        + ",highpass=f=100",
                "-f", "lavfi", "-i",
                "sine=frequency=330:duration=12,volume=0.15"
                        + ",aecho=0.8:0.9:80:0.3"
                        + ",lowpass=f=1000",
                "-filter_complex",
                "[0:a][1:a]amix=inputs=2:duration=longest,afade=t=in:st=0:d=2[out]",
                "-map", "[out]",
                "-t", "12",
                "-c:a", "libmp3lame", "-b:a", "128k",
                bgm.toAbsolutePath().toString()));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IOException("Failed to generate default BGM:\n" + log);
        }
        return bgm;
    }

    private static void deleteQuietly(Path dir) {
        try (var paths = Files.walk(dir)) {
            paths.sorted((a, b) -> b.getNameCount() - a.getNameCount())
                    .forEach(pth -> {
                        try {
                            Files.deleteIfExists(pth);
                        } catch (IOException ignore) {
                            // best effort
                        }
                    });
        } catch (IOException ignore) {
            // best effort
        }
    }

    private static void requireFfmpeg() {
        if (!isFfmpegAvailable()) {
            throw new IllegalStateException(
                    "ffmpeg was not found on PATH. Install ffmpeg to generate the video.");
        }
    }

    private static boolean isFfmpegAvailable() {
        try {
            Process p = new ProcessBuilder("ffmpeg", "-version")
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
}
