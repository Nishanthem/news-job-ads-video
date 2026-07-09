package com.jobads.input;

import com.jobads.model.JobPosting;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Imports job advertisements the user already has as <strong>PDF</strong> or <strong>image</strong>
 * files, extracts the text, and turns it into {@link JobPosting} facts so the app can render its own
 * clean, copyright-safe slide (exactly like a scraped job).
 *
 * <p>Text is obtained with offline command-line tools that are widely available:
 * <ul>
 *   <li><b>PDFs</b> &mdash; {@code pdftotext} (poppler-utils). If the PDF has no text layer (i.e. it
 *       is a scan), the pages are rasterised with {@code pdftoppm} and OCR'd.</li>
 *   <li><b>Images</b> &mdash; {@code tesseract} OCR with the {@code eng+mal} language pack so both
 *       English and Malayalam ads are read.</li>
 * </ul>
 *
 * <p>Field extraction is heuristic: it understands common "Label: value" lines (Title, Company,
 * Location, Qualification, Salary, Last date, Contact, How to apply) and otherwise falls back to the
 * first meaningful line as the title. OCR is not perfect, so the extracted details are meant to be
 * reviewed before publishing.
 */
public class DocumentImporter {

    private static final String[] IMAGE_EXTS = {
            ".png", ".jpg", ".jpeg", ".tif", ".tiff", ".bmp", ".webp", ".gif"
    };

    /** OCR languages passed to tesseract; English + Malayalam. */
    private final String ocrLanguages;

    /**
     * Source label to stamp on imported jobs (e.g. the newspaper name) when the document itself
     * does not carry a {@code Source:} line. {@code null}/blank means no source is shown.
     */
    private final String sourceName;

    public DocumentImporter() {
        this("eng+mal", null);
    }

    public DocumentImporter(String ocrLanguages) {
        this(ocrLanguages, null);
    }

    public DocumentImporter(String ocrLanguages, String sourceName) {
        this.ocrLanguages = ocrLanguages;
        this.sourceName = sourceName;
    }

    /**
     * Import every PDF/image at {@code inputPath}. If it is a directory, all supported files inside
     * are imported (sorted by name); if it is a single file, just that one.
     */
    public List<JobPosting> importPath(Path inputPath) throws IOException {
        if (!Files.exists(inputPath)) {
            throw new IOException("Input path does not exist: " + inputPath);
        }
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(inputPath)) {
            try (Stream<Path> s = Files.list(inputPath)) {
                s.filter(Files::isRegularFile)
                 .filter(DocumentImporter::isSupported)
                 .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                 .forEach(files::add);
            }
        } else {
            files.add(inputPath);
        }

        List<JobPosting> jobs = new ArrayList<>();
        for (Path f : files) {
            try {
                String text = extractText(f);
                if (text == null || text.isBlank()) {
                    System.err.println("[import] no readable text in " + f.getFileName()
                            + " (skipped)");
                    continue;
                }
                // A consolidated UPSC advertisement lists many vacancies in one PDF; split it into
                // one job per post rather than treating the whole file as a single ad.
                if (UpscAdvertisement.looksLike(text)) {
                    List<JobPosting> vacancies = UpscAdvertisement.parse(text, sourceName, null);
                    if (!vacancies.isEmpty()) {
                        jobs.addAll(vacancies);
                        System.out.println("[import] " + f.getFileName() + " -> UPSC advertisement, "
                                + vacancies.size() + " vacancies");
                        continue;
                    }
                }
                JobPosting job = parse(text, f);
                jobs.add(job);
                System.out.println("[import] " + f.getFileName() + " -> " + job.getTitle());
            } catch (IOException e) {
                System.err.println("[import] failed to read " + f.getFileName() + ": "
                        + e.getMessage());
            }
        }
        return jobs;
    }

    private static boolean isSupported(Path p) {
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) {
            return true;
        }
        for (String ext : IMAGE_EXTS) {
            if (name.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /** Publicly extract raw text from a single PDF/image (PDF text layer, else OCR). */
    public String readText(Path file) throws IOException {
        return extractText(file);
    }

    /** Extract raw text from a single PDF or image file. */
    String extractText(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".pdf")) {
            String text = pdfToText(file);
            // A scanned PDF yields little/no text; fall back to OCR of rasterised pages.
            if (text == null || text.replaceAll("\\s", "").length() < 20) {
                String ocr = ocrPdf(file);
                if (ocr != null && !ocr.isBlank()) {
                    return ocr;
                }
            }
            return text;
        }
        return ocrImage(file);
    }

    private String pdfToText(Path pdf) throws IOException {
        return run(List.of("pdftotext", "-layout", pdf.toAbsolutePath().toString(), "-"));
    }

    private String ocrImage(Path image) throws IOException {
        return run(List.of("tesseract", image.toAbsolutePath().toString(), "stdout",
                "-l", ocrLanguages));
    }

    /** Rasterise a (scanned) PDF to PNGs and OCR each page. */
    private String ocrPdf(Path pdf) throws IOException {
        Path tmp = Files.createTempDirectory("pdf-ocr-");
        try {
            Path prefix = tmp.resolve("page");
            run(List.of("pdftoppm", "-png", "-r", "200",
                    pdf.toAbsolutePath().toString(), prefix.toAbsolutePath().toString()));
            StringBuilder sb = new StringBuilder();
            try (Stream<Path> pages = Files.list(tmp)) {
                List<Path> pngs = pages.filter(p -> p.getFileName().toString().endsWith(".png"))
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .toList();
                for (Path png : pngs) {
                    String t = ocrImage(png);
                    if (t != null) {
                        sb.append(t).append('\n');
                    }
                }
            }
            return sb.toString();
        } finally {
            deleteQuietly(tmp);
        }
    }

    // --- text -> JobPosting --------------------------------------------------

    private static final Pattern EMAIL = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.-]+");
    private static final Pattern PHONE = Pattern.compile("(?:\\+?\\d[\\d\\-\\s]{8,}\\d)");
    private static final Pattern DATE = Pattern.compile(
            "\\b(\\d{1,2}[\\-/.\\s](?:\\d{1,2}|[A-Za-z]{3,9})[\\-/.\\s]\\d{2,4})\\b");

    /**
     * Best-effort parse of extracted text into a {@link JobPosting}. Recognises "Label: value"
     * lines first, then falls back to sensible defaults.
     */
    public JobPosting parse(String text, Path file) {
        JobPosting job = new JobPosting();

        List<String> lines = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (!line.isEmpty()) {
                lines.add(line);
            }
        }

        for (String line : lines) {
            int colon = line.indexOf(':');
            if (colon <= 0 || colon > 40) {
                continue;
            }
            String label = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (value.isEmpty()) {
                continue;
            }
            applyLabel(job, label, value);
        }

        // Title fallback: first meaningful line that is not itself a label line.
        if (isBlank(job.getTitle())) {
            for (String line : lines) {
                if (line.length() >= 3 && line.indexOf(':') < 0) {
                    job.setTitle(trimTo(line, 120));
                    break;
                }
            }
        }
        if (isBlank(job.getTitle()) && !lines.isEmpty()) {
            job.setTitle(trimTo(lines.get(0), 120));
        }
        if (isBlank(job.getTitle())) {
            job.setTitle(file.getFileName().toString());
        }

        // Fallbacks for last date / contact when not explicitly labelled.
        if (isBlank(job.getLastDate())) {
            for (String line : lines) {
                String low = line.toLowerCase(Locale.ROOT);
                if (low.contains("last date") || low.contains("closing date")
                        || low.contains("apply before") || low.contains("deadline")) {
                    Matcher m = DATE.matcher(line);
                    if (m.find()) {
                        job.setLastDate(m.group(1));
                        break;
                    }
                }
            }
        }
        if (isBlank(job.getContact())) {
            Matcher em = EMAIL.matcher(text);
            if (em.find()) {
                job.setContact(em.group());
            } else {
                Matcher ph = PHONE.matcher(text);
                if (ph.find()) {
                    job.setContact(ph.group().trim());
                }
            }
        }

        if (isBlank(job.getOpenings())) {
            String n = AdTextExtractor.numberOfPosts(text);
            if (!n.isBlank()) {
                job.setOpenings(n);
            }
        }

        // Source: prefer a "Source:" line from the document, else the run-wide source name, else
        // leave it blank (so the slide/narration omit the source rather than showing a placeholder).
        if (isBlank(job.getSource()) && !isBlank(sourceName)) {
            job.setSource(sourceName.trim());
        }
        return job;
    }

    private void applyLabel(JobPosting job, String label, String value) {
        if (matches(label, "title", "post", "post name", "position", "job title", "name of post",
                "designation", "vacancy")) {
            if (isBlank(job.getTitle())) {
                job.setTitle(trimTo(value, 120));
            }
        } else if (matches(label, "company", "organisation", "organization", "department",
                "employer", "recruiter", "establishment", "office")) {
            if (isBlank(job.getCompany())) {
                job.setCompany(value);
            }
        } else if (matches(label, "location", "place", "venue", "work location", "city", "district")) {
            if (isBlank(job.getLocation())) {
                job.setLocation(value);
            }
        } else if (matches(label, "qualification", "qualifications", "eligibility",
                "educational qualification", "education")) {
            if (isBlank(job.getQualification())) {
                job.setQualification(value);
            }
        } else if (matches(label, "salary", "pay", "pay scale", "remuneration", "stipend", "ctc",
                "package")) {
            if (isBlank(job.getSalary())) {
                job.setSalary(value);
            }
        } else if (matches(label, "contact", "email", "e-mail", "phone", "mobile", "tel",
                "telephone")) {
            if (isBlank(job.getContact())) {
                job.setContact(value);
            }
        } else if (matches(label, "last date", "closing date", "last date to apply", "deadline",
                "apply before", "last date of application", "last date for application")) {
            if (isBlank(job.getLastDate())) {
                job.setLastDate(value);
            }
        } else if (matches(label, "no of posts", "no. of posts", "number of posts", "total posts",
                "no of vacancies", "no. of vacancies", "number of vacancies", "vacancies",
                "openings", "no of positions", "number of positions")) {
            if (isBlank(job.getOpenings())) {
                String n = AdTextExtractor.numberOfPosts(label + ": " + value);
                job.setOpenings(!n.isBlank() ? n : trimTo(value, 40));
            }
        } else if (matches(label, "how to apply", "apply", "application", "mode of apply",
                "to apply", "how to register")) {
            if (isBlank(job.getApplyHow())) {
                job.setApplyHow(value);
            }
        } else if (matches(label, "source", "published in", "newspaper", "portal", "publication")) {
            if (isBlank(job.getSource())) {
                job.setSource(value);
            }
        }
    }

    private static boolean matches(String label, String... keys) {
        for (String k : keys) {
            if (label.equals(k)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String trimTo(String s, int max) {
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max).trim();
    }

    private String run(List<String> cmd) throws IOException {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
            byte[] out = p.getInputStream().readAllBytes();
            byte[] err = p.getErrorStream().readAllBytes();
            int exit = p.waitFor();
            if (exit != 0) {
                throw new IOException(cmd.get(0) + " failed (exit " + exit + "): "
                        + new String(err, StandardCharsets.UTF_8).trim());
            }
            return new String(out, StandardCharsets.UTF_8);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running " + cmd.get(0));
        }
    }

    private static void deleteQuietly(Path dir) {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignore) {
                    // best effort
                }
            });
        } catch (IOException ignore) {
            // best effort
        }
    }
}
