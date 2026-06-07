package com.jobads.audio;

import com.jobads.model.JobPosting;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Turns a {@link JobPosting} into a short spoken voice-over (a WAV file) using an offline
 * text-to-speech engine, so the generated video can be listened to as well as read.
 *
 * <p>No API keys are required. Engine preference:
 * <ol>
 *   <li><b>Piper</b> with an Indian-English neural voice ({@code en_IN-spicor-medium}) — natural
 *       Indian accent. The model must be present on disk; set {@code PIPER_MODEL} to override the
 *       search path.</li>
 *   <li><b>espeak-ng</b> — lighter, robotic but reads numbers correctly.</li>
 *   <li><b>pico2wave</b> — SVOX Pico, smooth but reads numbers digit-by-digit and has no Indian
 *       accent.</li>
 * </ol>
 * If no engine is available the app simply produces a silent video.
 */
public class Narrator {

    private static final String[] MONTHS = {
            "January", "February", "March", "April", "May", "June",
            "July", "August", "September", "October", "November", "December"
    };

    private final String engine;    // "piper", "espeak-ng", "pico2wave", or null
    private final String piperModel; // path to .onnx (only when engine == "piper")

    public Narrator() {
        String model = findPiperModel();
        if (model != null && commandExists("piper")) {
            this.engine = "piper";
            this.piperModel = model;
        } else if (commandExists("espeak-ng")) {
            this.engine = "espeak-ng";
            this.piperModel = null;
        } else if (commandExists("pico2wave")) {
            this.engine = "pico2wave";
            this.piperModel = null;
        } else {
            this.engine = null;
            this.piperModel = null;
        }
    }

    public boolean isAvailable() {
        return engine != null;
    }

    public String engineName() {
        if (engine == null) return "none";
        return "piper".equals(engine) ? "piper (en_IN-spicor, Indian English)" : engine;
    }

    /** Build the spoken line for the intro card. */
    public String introText(String brand, int count) {
        return sanitize(brand) + ". Here are " + count + " job "
                + (count == 1 ? "opening" : "openings") + " for you. Let's begin.";
    }

    /**
     * Build the spoken line for a single job. Qualification and contact are shown on the slide to
     * read but omitted from speech (emails/phones read character-by-character are painful to hear).
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
        sb.append("How to apply, ").append(spokenApply(job)).append(". ");
        if (notBlank(job.getSource())) {
            sb.append("Source, ").append(spokenSource(job.getSource())).append(".");
        }
        return sb.toString();
    }

    /**
     * Spoken form of "how to apply". Emails, links and phone numbers are read out
     * character-by-character by a TTS engine (painful to hear), so for those we point the listener
     * to the on-screen details; plain instructions (e.g. "Walk-in interview") are read as-is.
     */
    static String spokenApply(JobPosting job) {
        String raw = job.getApplyInfo();
        String lower = raw.toLowerCase();
        boolean hasEmail = raw.contains("@");
        boolean hasUrl = lower.contains("http") || lower.contains("www.")
                || lower.matches(".*\\b[a-z0-9.-]+\\.(com|in|org|gov|net|edu|co)\\b.*");
        boolean hasPhone = raw.replaceAll("[^0-9]", "").length() >= 7;
        if (hasEmail || hasUrl) {
            return "apply using the link and contact details shown on screen";
        }
        if (hasPhone) {
            return "apply using the phone number shown on screen";
        }
        return sanitize(raw);
    }

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
            if ("piper".equals(engine)) {
                pb = new ProcessBuilder("piper",
                        "--model", piperModel,
                        "--output_file", outWav.toAbsolutePath().toString());
                pb.redirectErrorStream(true);
                Process p = pb.start();
                try (OutputStream os = p.getOutputStream()) {
                    os.write(text.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
                p.getInputStream().readAllBytes();
                int exit = p.waitFor();
                return exit == 0 && Files.exists(outWav) ? outWav : null;
            } else if ("espeak-ng".equals(engine)) {
                pb = new ProcessBuilder("espeak-ng", "-v", "en-us", "-s", "150",
                        "-w", outWav.toAbsolutePath().toString(), text);
            } else {
                pb = new ProcessBuilder("pico2wave", "-l", "en-US",
                        "-w", outWav.toAbsolutePath().toString(), text);
            }
            pb.redirectErrorStream(true);
            Process p = pb.start();
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

    // ---- text normalisation ----

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
        String s = text;
        // Currency expansion — "Rs." and "₹" should be spoken as "rupees".
        s = s.replaceAll("(?i)Rs\\.?\\s*", "rupees ");
        s = s.replace("₹", "rupees ");
        s = s.replace("&", " and ")
                .replace("/", " ")
                .replace("\n", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (s.length() > 200) {
            s = s.substring(0, 200);
        }
        return s;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    // ---- engine detection ----

    /**
     * Look for a Piper Indian-English voice model on disk. Check {@code PIPER_MODEL} env var first,
     * then well-known locations.
     */
    private static String findPiperModel() {
        String envModel = System.getenv("PIPER_MODEL");
        if (envModel != null && Files.isRegularFile(Paths.get(envModel))) {
            return envModel;
        }
        String home = System.getProperty("user.home", "/home/ubuntu");
        String[] candidates = {
                home + "/piper-voices/en_IN-spicor-medium.onnx",
                home + "/.local/share/piper-voices/en_IN-spicor-medium.onnx",
                "piper-voices/en_IN-spicor-medium.onnx",
        };
        for (String c : candidates) {
            if (Files.isRegularFile(Paths.get(c))) {
                return c;
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
