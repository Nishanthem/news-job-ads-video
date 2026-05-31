package com.jobads.video;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Combines the rendered slide images into an MP4 using the system {@code ffmpeg} binary via the
 * concat demuxer. Each slide is shown for a fixed number of seconds.
 */
public class VideoBuilder {

    private final int secondsPerSlide;
    private final int fps;

    public VideoBuilder(int secondsPerSlide, int fps) {
        this.secondsPerSlide = secondsPerSlide;
        this.fps = fps;
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
        return output;
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
