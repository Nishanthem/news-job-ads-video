package com.jobads.video;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Combines the rendered slide images into an MP4 using the system {@code ffmpeg} binary via the
 * concat demuxer. Each slide is shown for a fixed number of seconds.
 */
public class VideoBuilder {

    private final int secondsPerSlide;
    private final int fps;
    private Path bgmPath;
    private double bgmVolume = 0.15;

    public VideoBuilder(int secondsPerSlide, int fps) {
        this.secondsPerSlide = secondsPerSlide;
        this.fps = fps;
    }

    public void setBgm(Path bgmPath, double volume) {
        this.bgmPath = bgmPath;
        this.bgmVolume = Math.max(0.0, Math.min(1.0, volume));
    }

    /**
     * Build the video.
     *
     * @param slides ordered list of PNG paths
     * @param output destination .mp4 path
     * @return the output path
     */
    public Path build(List<Path> slides, Path output) throws IOException, InterruptedException {
        if (slides == null || slides.isEmpty()) {
            throw new IllegalArgumentException("No slides to build a video from");
        }
        if (!isFfmpegAvailable()) {
            throw new IllegalStateException(
                    "ffmpeg was not found on PATH. Install ffmpeg to generate the video.");
        }

        Path listFile = Files.createTempFile("slides-", ".txt");
        StringBuilder sb = new StringBuilder();
        for (Path slide : slides) {
            // The concat demuxer needs each image followed by its duration.
            sb.append("file '").append(slide.toAbsolutePath()).append("'\n");
            sb.append("duration ").append(secondsPerSlide).append("\n");
        }
        // Repeat the last frame once (without duration) so its duration is actually applied.
        sb.append("file '").append(slides.get(slides.size() - 1).toAbsolutePath()).append("'\n");
        Files.writeString(listFile, sb.toString(), StandardCharsets.UTF_8);

        if (bgmPath != null && Files.isRegularFile(bgmPath)) {
            double totalDur = (double) slides.size() * secondsPerSlide;
            List<String> cmd = new ArrayList<>();
            cmd.addAll(List.of("ffmpeg", "-y",
                    "-f", "concat", "-safe", "0", "-i", listFile.toAbsolutePath().toString(),
                    "-stream_loop", "-1", "-i", bgmPath.toAbsolutePath().toString(),
                    "-vf", "fps=" + fps + ",format=yuv420p",
                    "-filter_complex", "[1:a]volume=" + fmt(bgmVolume) + "[bgm]",
                    "-map", "0:v", "-map", "[bgm]",
                    "-c:v", "libx264", "-c:a", "aac", "-b:a", "128k",
                    "-t", fmt(totalDur),
                    "-movflags", "+faststart",
                    output.toAbsolutePath().toString()));
            runFfmpeg2(cmd, listFile);
        } else {
            List<String> cmd = new ArrayList<>();
            cmd.add("ffmpeg");
            cmd.add("-y");
            cmd.add("-f");
            cmd.add("concat");
            cmd.add("-safe");
            cmd.add("0");
            cmd.add("-i");
            cmd.add(listFile.toAbsolutePath().toString());
            cmd.add("-vf");
            cmd.add("fps=" + fps + ",format=yuv420p");
            cmd.add("-movflags");
            cmd.add("+faststart");
            cmd.add(output.toAbsolutePath().toString());

            Process process = new ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start();
            String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = process.waitFor();
            Files.deleteIfExists(listFile);

            if (exit != 0) {
                throw new IOException("ffmpeg failed (exit " + exit + "):\n" + log);
            }
        }
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
        if (!isFfmpegAvailable()) {
            throw new IllegalStateException(
                    "ffmpeg was not found on PATH. Install ffmpeg to generate the video.");
        }

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
                runFfmpeg(List.of("-y", "-i", audio.toAbsolutePath().toString(),
                        "-af", "adelay=" + delayMs + "|" + delayMs + ",apad",
                        "-t", fmt(total), "-ar", "22050", "-ac", "1",
                        padded.toAbsolutePath().toString()));
            } else {
                // Pure silence matching the slide duration.
                runFfmpeg(List.of("-y", "-f", "lavfi", "-i",
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
        runFfmpeg(List.of("-y", "-f", "concat", "-safe", "0",
                "-i", audioList.toAbsolutePath().toString(),
                "-c", "copy", combinedAudio.toAbsolutePath().toString()));

        // Image concat list with per-slide durations.
        Path imageList = tmpDir.resolve("slides.txt");
        StringBuilder isb = new StringBuilder();
        for (int i = 0; i < slides.size(); i++) {
            isb.append("file '").append(slides.get(i).toAbsolutePath()).append("'\n");
            isb.append("duration ").append(fmt(durations.get(i))).append("\n");
        }
        isb.append("file '").append(slides.get(slides.size() - 1).toAbsolutePath()).append("'\n");
        Files.writeString(imageList, isb.toString(), StandardCharsets.UTF_8);

        if (bgmPath != null && Files.isRegularFile(bgmPath)) {
            double totalDur = durations.stream().mapToDouble(Double::doubleValue).sum();
            runFfmpeg(List.of("-y",
                    "-f", "concat", "-safe", "0", "-i", imageList.toAbsolutePath().toString(),
                    "-i", combinedAudio.toAbsolutePath().toString(),
                    "-stream_loop", "-1", "-i", bgmPath.toAbsolutePath().toString(),
                    "-filter_complex",
                    "[1:a]volume=1.0[voice];[2:a]volume=" + fmt(bgmVolume) + "[bgm];"
                            + "[voice][bgm]amix=inputs=2:duration=first[aout]",
                    "-vf", "fps=" + fps + ",format=yuv420p",
                    "-map", "0:v", "-map", "[aout]",
                    "-c:v", "libx264", "-c:a", "aac", "-b:a", "128k",
                    "-t", fmt(totalDur),
                    "-movflags", "+faststart",
                    output.toAbsolutePath().toString()));
        } else {
            runFfmpeg(List.of("-y",
                    "-f", "concat", "-safe", "0", "-i", imageList.toAbsolutePath().toString(),
                    "-i", combinedAudio.toAbsolutePath().toString(),
                    "-vf", "fps=" + fps + ",format=yuv420p",
                    "-c:v", "libx264", "-c:a", "aac", "-b:a", "128k",
                    "-movflags", "+faststart", "-shortest",
                    output.toAbsolutePath().toString()));
        }

        deleteQuietly(tmpDir);
        return output;
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

    private static void runFfmpeg(List<String> args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg");
        cmd.addAll(args);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) {
            throw new IOException("ffmpeg failed:\n" + log);
        }
    }

    private static void runFfmpeg2(List<String> fullCmd, Path cleanup)
            throws IOException, InterruptedException {
        Process p = new ProcessBuilder(fullCmd).redirectErrorStream(true).start();
        String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = p.waitFor();
        if (cleanup != null) Files.deleteIfExists(cleanup);
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
                "sine=frequency=220:duration=10,volume=0.3"
                        + ",aecho=0.8:0.88:60:0.4"
                        + ",lowpass=f=800"
                        + ",highpass=f=100",
                "-f", "lavfi", "-i",
                "sine=frequency=330:duration=10,volume=0.15"
                        + ",aecho=0.8:0.9:80:0.3"
                        + ",lowpass=f=1000",
                "-filter_complex", "[0:a][1:a]amix=inputs=2:duration=longest[out]",
                "-map", "[out]",
                "-t", "10",
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
