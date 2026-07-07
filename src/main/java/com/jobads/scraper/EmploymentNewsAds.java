package com.jobads.scraper;

import com.jobads.input.DocumentImporter;
import com.jobads.model.JobPosting;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pulls job advertisements from the <strong>official, free</strong> Employment News
 * "Web Advertisement" page. That page lists ~50 current recruitment ads per week, each linking to an
 * official notification PDF hosted on {@code employmentnews.gov.in}. The full weekly e-paper is a
 * paid subscription, but these individual advertisement PDFs are public.
 *
 * <p>The PDFs are scans (no text layer), so the details are obtained with OCR via
 * {@link DocumentImporter}. The organisation name and issue label come from the listing page (clean
 * text), while the position / qualification / last date are extracted from the PDF.
 */
public class EmploymentNewsAds {

    private static final String LISTING_URL =
            "https://employmentnews.gov.in/NewEmp/MoreContentS.aspx?n=WebAdvertisement";
    /** PDFs resolve on the non-www host (the www host 404s for /writereaddata/). */
    private static final String PDF_BASE = "https://employmentnews.gov.in/writereaddata/";
    private static final String SOURCE_NAME = "Employment News";

    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/133.0.0.0 Safari/537.36";
    private static final int TIMEOUT_MS = 40_000;

    private final DocumentImporter importer = new DocumentImporter("eng", SOURCE_NAME);

    /**
     * Collect up to {@code max} Employment News advertisements (OCR'ing each linked PDF). Network or
     * OCR failures on individual ads are logged and skipped so one bad ad never aborts the run.
     */
    public List<JobPosting> collect(int max) {
        List<JobPosting> jobs = new ArrayList<>();
        Map<String, String> ads = listAds();
        if (ads.isEmpty()) {
            System.err.println("[employment-news] no advertisements found on the listing page");
            return jobs;
        }
        System.out.println("[employment-news] found " + ads.size()
                + " advertisement(s); reading up to " + max + " PDF(s) with OCR");

        Path tmp;
        try {
            tmp = Files.createTempDirectory("en-ads-");
        } catch (IOException e) {
            System.err.println("[employment-news] cannot create temp dir: " + e.getMessage());
            return jobs;
        }

        int done = 0;
        for (Map.Entry<String, String> e : ads.entrySet()) {
            if (done >= max) {
                break;
            }
            String pdfUrl = e.getKey();
            String orgLabel = e.getValue();
            String org = cleanOrg(orgLabel);
            try {
                Path pdf = download(pdfUrl, tmp, done);
                if (pdf == null) {
                    continue;
                }
                String text = importer.readText(pdf);
                if (text == null || text.isBlank()) {
                    System.err.println("[employment-news] no readable text in " + pdfUrl);
                    continue;
                }
                // Reuse the importer's parser for generic fields (e.g. last date), then override the
                // headline and company with the reliable, clean listing data.
                JobPosting job = importer.parse(text, pdf);
                job.setSource(SOURCE_NAME);
                job.setLink(pdfUrl);
                job.setCompany(!org.isBlank() ? org : "");

                String position = extractPosition(text);
                if (position != null && !position.isBlank()) {
                    job.setTitle(position);
                } else if (!org.isBlank()) {
                    // No clear position line: use the organisation as the headline and drop the
                    // duplicate company row.
                    job.setTitle(org);
                    job.setCompany("");
                } else {
                    job.setTitle("Recruitment notification");
                }
                job.setApplyHow(buildApplyInfo(text, pdfUrl));
                jobs.add(job);
                done++;
                System.out.println("[employment-news] " + org + " -> " + job.getTitle());
            } catch (Exception ex) {
                System.err.println("[employment-news] skipped " + pdfUrl + ": " + ex.getMessage());
            }
        }
        return jobs;
    }

    /** Read the listing page and return an ordered map of {@code pdfUrl -> organisation label}. */
    private Map<String, String> listAds() {
        Map<String, String> ads = new LinkedHashMap<>();
        Document doc;
        try {
            doc = Jsoup.connect(LISTING_URL).userAgent(USER_AGENT).timeout(TIMEOUT_MS).get();
        } catch (IOException e) {
            System.err.println("[employment-news] failed to load listing: " + e.getMessage());
            return ads;
        }
        Elements anchors = doc.select("a[id*=RepEditorial_hh_][href*=writereaddata/]");
        for (Element a : anchors) {
            String href = a.attr("href");
            int slash = href.lastIndexOf('/');
            if (slash < 0) {
                continue;
            }
            String file = href.substring(slash + 1);
            if (!file.toLowerCase().endsWith(".pdf")) {
                continue;
            }
            String label = a.text().trim();
            // Skip non-vacancy links (e.g. the advertisement-policy PDF).
            if (label.isEmpty() || label.toLowerCase().contains("advertisement policy")) {
                continue;
            }
            ads.putIfAbsent(PDF_BASE + file, label);
        }
        return ads;
    }

    /**
     * Compose a rich "How to Apply" line from the notification text: application mode, fee, an
     * application/website link if present, and always the link to the official notification PDF
     * (the "link to the ad"). Falls back to a sensible default when little is detected.
     */
    public static String buildApplyInfo(String text, String pdfUrl) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        String mode = com.jobads.input.AdTextExtractor.applyMode(text);
        String fee = com.jobads.input.AdTextExtractor.applicationFee(text);
        String link = com.jobads.input.AdTextExtractor.applyLink(text, "employmentnews.gov.in");

        parts.add(!mode.isBlank() ? mode : "Apply as per the official notification");
        if (!fee.isBlank()) {
            parts.add(fee);
        }
        if (!link.isBlank()) {
            parts.add("Form / details: " + link);
        }
        if (pdfUrl != null && !pdfUrl.isBlank()) {
            parts.add("Official ad (PDF): " + pdfUrl);
        }
        return String.join(". ", parts);
    }

    /**
     * Try to pull a human-readable position/headline out of OCR'd notification text. Government ads
     * commonly phrase it as "Advertisement for the position of X", "recruitment to the post of X",
     * or "applications are invited for ... post(s) of X". Returns {@code null} when nothing clean is
     * found (the caller then falls back to the organisation name).
     */
    public static String extractPosition(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String flat = text.replaceAll("\\s+", " ").trim();
        java.util.regex.Pattern[] patterns = {
            java.util.regex.Pattern.compile(
                "(?:advertisement|applications?\\s+(?:are\\s+)?invited)\\b.*?\\b"
                        + "(?:for|to)\\b.*?\\bpost[s]?\\s+of\\s+(.+?)(?:\\.|,|;| in | on |$)",
                java.util.regex.Pattern.CASE_INSENSITIVE),
            java.util.regex.Pattern.compile(
                "\\bposition\\s+of\\s+(.+?)(?:\\.|,|;| in | on |$)",
                java.util.regex.Pattern.CASE_INSENSITIVE),
            java.util.regex.Pattern.compile(
                "\\brecruitment\\s+(?:to\\s+the\\s+)?(?:post[s]?\\s+of\\s+)?(.+?)(?:\\.|,|;| in | on |$)",
                java.util.regex.Pattern.CASE_INSENSITIVE),
            java.util.regex.Pattern.compile(
                "\\bpost[s]?\\s+of\\s+(.+?)(?:\\.|,|;| in | on |$)",
                java.util.regex.Pattern.CASE_INSENSITIVE)
        };
        for (java.util.regex.Pattern p : patterns) {
            java.util.regex.Matcher m = p.matcher(flat);
            if (m.find()) {
                String cand = tidy(m.group(1));
                if (isCleanPosition(cand)) {
                    return cand;
                }
            }
        }
        return null;
    }

    private static String tidy(String s) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        // Drop bracketed/administrative tails that OCR appends after the actual title.
        t = t.replaceAll("\\(.*$", "");
        t = t.replaceAll("(?i)\\b(advt|advertisement|number\\s+of\\s+post|no\\.?\\s+of\\s+post|"
                + "vacanc|reservation|on\\s+contract|purely\\s+temporary).*$", "");
        // Cut at the first run of digits (e.g. "01", counts, dates) — rarely part of a job title.
        t = t.replaceAll("\\d.*$", "");
        // Strip leading filler left over from the phrase match.
        t = t.replaceAll("(?i)^(of|the|a|an)\\s+", "");
        t = t.replaceAll("[:\\-–—,\\s]+$", "").trim();
        if (t.length() > 80) {
            t = t.substring(0, 80).trim();
        }
        return t;
    }

    /** A usable position is short, mostly letters, and not OCR gibberish. */
    private static boolean isCleanPosition(String s) {
        if (s == null || s.length() < 4 || s.length() > 80) {
            return false;
        }
        long letters = s.chars().filter(Character::isLetter).count();
        long spaces = s.chars().filter(c -> c == ' ').count();
        // Require enough letters and a plausible word count.
        return letters >= s.length() * 0.6 && spaces <= 8;
    }

    /** Strip the trailing "( Issue no .. )" from a listing label to get the organisation name. */
    public static String cleanOrg(String label) {
        if (label == null) {
            return "";
        }
        int paren = label.indexOf('(');
        String org = (paren > 0 ? label.substring(0, paren) : label).trim();
        return org.replaceAll("\\s+", " ");
    }

    private Path download(String url, Path dir, int index) {
        try {
            Connection.Response r = Jsoup.connect(url)
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MS)
                    .ignoreContentType(true)
                    .maxBodySize(0)
                    .execute();
            byte[] body = r.bodyAsBytes();
            if (body.length < 1000) {
                System.err.println("[employment-news] tiny/empty PDF, skipping: " + url);
                return null;
            }
            Path out = dir.resolve(String.format("ad-%02d.pdf", index));
            Files.write(out, body);
            return out;
        } catch (IOException e) {
            System.err.println("[employment-news] download failed for " + url + ": " + e.getMessage());
            return null;
        }
    }
}
