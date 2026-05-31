package com.jobads.audio;

import com.jobads.model.JobPosting;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * Turns a {@link JobPosting} into a short spoken voice-over (a WAV file) using an offline
 * text-to-speech engine, so the generated video can be listened to as well as read.
 *
 * <p>No API keys are required: it uses whichever local engine is available — preferring
 * {@code pico2wave} (SVOX Pico, natural English) and falling back to {@code espeak-ng}.
 * If neither is installed the app simply produces a silent video.
 */
public class Narrator {

    private static final String[] MONTHS = {
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
    };

    private final String engine; // "pico2wave", "espeak-ng", or null

    public Narrator() {
        this.engine = detectEngine();
    }

    /** @return true if a local TTS engine was found. */
    public boolean isAvailable() {
        return engine != null;
    }

    /** @return the name of the detected engine, or {@code "none"}. */
    public String engineName() {
        return engine == null ? "none" : engine;
    }

    /** Build the spoken line for the intro card. */
    public String introText(String brand, int count) {
        return sanitize(brand) + ". Here are " + count + " job "
                + (count == 1 ? "opening" : "openings") + " for you. Let's begin.";
    }

    /**
     * Build the spoken line for a single job. Kept concise on purpose: the qualification and
     * contact details are shown on the slide to read, but are not read aloud (an email/phone read
     * out character by character makes each clip very long and tiring to listen to).
     */
    public String jobText(JobPosting job, int index, int total) {
        StringBuilder sb = new StringBuilder();
        sb.append("Job ").append(index).append(" of ").append(total).append(". ");
        sb.append(sanitize(job.getTitle())).append(". ");
        if (notBlank(job.getCompany())) {
            sb.append("Organisation, ").append(sanitize(job.getCompany())).append(". ");
        }
        if (notBlank(job.getLocation())) {
            sb.append("Location, ").append(sanitize(job.getLocation())).append(". ");
        }
        if (notBlank(job.getSalary())) {
            sb.append("Salary, ").append(sanitize(job.getSalary())).append(". ");
        }
        if (notBlank(job.getLastDate())) {
            sb.append("Last date to apply, ").append(spokenDate(job.getLastDate())).append(". ");
        }
        if (notBlank(job.getSource())) {
            sb.append("Source, ").append(spokenSource(job.getSource())).append(".");
        }
        return sb.toString();
    }

    /**
     * Spoken form of the source name: drop any parenthetical (often a website/URL that a TTS engine
     * reads slowly letter by letter), e.g. "Employment News (employmentnews.gov.in)" -> "Employment
     * News".
     */
    static String spokenSource(String source) {
        String s = source.replaceAll("\\(.*?\\)", " ");
        return sanitize(s);
    }

    /**
     * Synthesize {@code text} to {@code outWav}. Returns the path on success or {@code null} on
     * failure (so the caller can fall back to a silent slide).
     */
    public Path synth(String text, Path outWav) {
        if (engine == null || text == null || text.isBlank()) {
            return null;
        }
        try {
            ProcessBuilder pb;
            if ("pico2wave".equals(engine)) {
                pb = new ProcessBuilder("pico2wave", "-l", "en-US",
                        "-w", outWav.toAbsolutePath().toString(), text);
            } else {
                // espeak-ng: -s words per minute (slightly slower for clarity)
                pb = new ProcessBuilder("espeak-ng", "-v", "en-us", "-s", "150",
                        "-w", outWav.toAbsolutePath().toString(), text);
            }
            Process p = pb.redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            int exit = p.waitFor();
            return exit == 0 ? outWav : null;
        } catch (IOException e) {
            System.err.println("[narrator] synth failed: " + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Convert dd/mm/yyyy (or dd-mm-yyyy) to a spoken date like "15 May 2026". */
    public static String spokenDate(String raw) {
        String s = raw.trim();
        String[] parts = s.split("[/\\-.]");
        if (parts.length == 3) {
            try {
                int d = Integer.parseInt(parts[0].trim());
                int m = Integer.parseInt(parts[1].trim());
                int y = Integer.parseInt(parts[2].trim());
                if (m >= 1 && m <= 12 && d >= 1 && d <= 31) {
                    return d + " " + MONTHS[m - 1] + " " + y;
                }
            } catch (NumberFormatException ignore) {
                // fall through
            }
        }
        return sanitize(raw);
    }

    /** Make text friendlier for a TTS engine. */
    static String sanitize(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replace("&", " and ")
                .replace("/", " ")
                .replace("\n", " ")
                .replaceAll("\\s+", " ")
                .trim();
        // Very long lines tire the listener; keep the spoken version reasonable.
        if (s.length() > 180) {
            s = s.substring(0, 180);
        }
        return s;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String detectEngine() {
        for (String e : new String[] {"pico2wave", "espeak-ng"}) {
            if (commandExists(e)) {
                return e;
            }
        }
        return null;
    }

    private static boolean commandExists(String cmd) {
        try {
            Process p = new ProcessBuilder("sh", "-c", "command -v " + cmd)
                    .redirectErrorStream(true)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            return p.waitFor() == 0 && !out.isBlank();
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
