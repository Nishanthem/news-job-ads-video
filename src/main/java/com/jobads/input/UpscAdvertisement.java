package com.jobads.input;

import com.jobads.model.JobPosting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser for a <strong>consolidated UPSC recruitment advertisement</strong> PDF (e.g.
 * "Advertisement No. 07/2026"). Unlike a single-ad notification, one of these documents lists many
 * separate vacancies in a "VACANCY DETAILS" section, each of the form:
 *
 * <pre>
 * 1.  (Vacancy No. 26060701227) Six vacancies for the post of Joint Director
 *     (Crops Development Directorate), Department of Agriculture and Farmers Welfare,
 *     Ministry of Agriculture and Farmers Welfare.
 * </pre>
 *
 * <p>Each such entry is turned into its own {@link JobPosting} so the app renders one clean slide
 * per post (title, ministry/department, number of openings, common closing date, and how to apply
 * via the online recruitment portal). The number of vacancies is written in words and converted to
 * a digit. Fee and closing date are stated once for the whole advertisement and applied to every
 * post.
 */
public final class UpscAdvertisement {

    private static final String APPLY_PORTAL = "https://upsconline.nic.in/ora/";

    /** Each vacancy header: index, vacancy number, then "<count> vacanc(y/ies) for the post of ...". */
    private static final Pattern VACANCY = Pattern.compile(
            "(?:\\d{1,3})\\.\\s*\\(Vacancy No\\.?\\s*(\\d{6,})\\)\\s*(.+?)"
                    + "(?=RESERVATION POSITION|ESSENTIAL QUALIFICATION|\\n\\s*\\n)",
            Pattern.DOTALL);

    private static final Pattern BODY = Pattern.compile(
            "^\\s*([A-Za-z][A-Za-z\\- ]*?)\\s+vacanc(?:y|ies)\\s+for the post of\\s+(.+)$",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private static final Pattern CLOSING_DATE = Pattern.compile(
            "(?i)1800\\s*Hrs\\s*on\\s*(\\d{1,2}[-./]\\d{1,2}[-./]\\d{2,4})");

    private static final Pattern FEE = Pattern.compile("(?i)fee of\\s*Rs\\.?\\s*(\\d{1,4})");

    private UpscAdvertisement() {
    }

    /**
     * Heuristic: does this extracted text look like a consolidated UPSC advertisement with multiple
     * numbered vacancies (rather than a single ordinary notification)?
     */
    public static boolean looksLike(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String upper = text.toUpperCase(Locale.ROOT);
        if (!upper.contains("UNION PUBLIC SERVICE COMMISSION")) {
            return false;
        }
        int hits = 0;
        Matcher m = Pattern.compile("\\(Vacancy No\\.?\\s*\\d{6,}\\)").matcher(text);
        while (m.find()) {
            if (++hits >= 2) {
                return true;
            }
        }
        return false;
    }

    /**
     * Split the advertisement text into one {@link JobPosting} per vacancy.
     *
     * @param text       the extracted PDF text
     * @param sourceName source label to stamp (defaults to "UPSC" when null/blank)
     * @param adLink     link to the official advertisement PDF, or null
     */
    public static List<JobPosting> parse(String text, String sourceName, String adLink) {
        List<JobPosting> jobs = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return jobs;
        }
        String source = (sourceName != null && !sourceName.isBlank()) ? sourceName.trim() : "UPSC";

        String closingDate = firstGroup(CLOSING_DATE, text);
        String fee = firstGroup(FEE, text);
        String applyHow = buildApplyHow(fee, adLink);

        Matcher m = VACANCY.matcher(text);
        while (m.find()) {
            String body = m.group(2).replaceAll("\\s+", " ").trim();
            Matcher b = BODY.matcher(body);
            if (!b.find()) {
                continue;
            }
            String countWords = b.group(1).trim();
            String descriptor = b.group(2).trim().replaceAll("\\.*$", "").trim();

            JobPosting job = new JobPosting();
            job.setTitle(postName(descriptor));
            job.setCompany(organisation(descriptor));
            String n = numberFromWords(countWords);
            if (!n.isBlank()) {
                job.setOpenings(n);
            }
            if (closingDate != null && !closingDate.isBlank()) {
                job.setLastDate(closingDate);
            }
            job.setApplyHow(applyHow);
            job.setLink(APPLY_PORTAL);
            job.setSource(source);
            jobs.add(job);
        }
        return jobs;
    }

    private static String buildApplyHow(String fee, String adLink) {
        StringBuilder sb = new StringBuilder("Apply online at ").append(APPLY_PORTAL).append(". ");
        if (fee != null && !fee.isBlank()) {
            sb.append("Fee Rs. ").append(fee)
              .append(" (no fee for Women/SC/ST/PwBD candidates). ");
        }
        if (adLink != null && !adLink.isBlank()) {
            sb.append("Official ad: ").append(adLink.trim());
        }
        return sb.toString().trim();
    }

    /** The post name: everything up to the first comma, or up to " in <organisation>". */
    private static String postName(String descriptor) {
        String first = descriptor.split(",", 2)[0].trim();
        Matcher in = Pattern.compile("(?i)\\s+in\\s+").matcher(first);
        if (in.find()) {
            String before = first.substring(0, in.start()).trim();
            if (before.length() >= 3) {
                return before;
            }
        }
        return first;
    }

    /**
     * The employing organisation: the last "Ministry of ..."/"Department of ..."/"Administration
     * of ..." chunk, else the last comma-separated part, else the whole descriptor.
     */
    private static String organisation(String descriptor) {
        String[] parts = descriptor.split(",");
        for (int i = parts.length - 1; i >= 0; i--) {
            String p = parts[i].trim();
            String low = p.toLowerCase(Locale.ROOT);
            if (low.startsWith("ministry of") || low.startsWith("department of")
                    || low.startsWith("administration of") || low.startsWith("office of")
                    || low.startsWith("government of")) {
                return p;
            }
        }
        if (parts.length > 1) {
            return parts[parts.length - 1].trim();
        }
        return descriptor.trim();
    }

    private static String firstGroup(Pattern p, String text) {
        Matcher m = p.matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    // --- English number words -> integer (supports up to a few thousand) --------------------

    private static String numberFromWords(String words) {
        if (words == null || words.isBlank()) {
            return "";
        }
        String cleaned = words.toLowerCase(Locale.ROOT)
                .replace('-', ' ')
                .replaceAll("\\band\\b", " ")
                .trim();
        long total = 0;
        long current = 0;
        boolean any = false;
        for (String token : cleaned.split("\\s+")) {
            Long unit = UNITS.apply(token);
            if (unit != null) {
                current += unit;
                any = true;
            } else if ("hundred".equals(token)) {
                current = (current == 0 ? 1 : current) * 100;
                any = true;
            } else if ("thousand".equals(token)) {
                total += (current == 0 ? 1 : current) * 1000;
                current = 0;
                any = true;
            } else {
                // unknown word -> not a number expression
                return "";
            }
        }
        long value = total + current;
        return (any && value >= 1 && value <= 99999) ? String.valueOf(value) : "";
    }

    private interface WordValue {
        Long apply(String token);
    }

    private static final WordValue UNITS = token -> {
        switch (token) {
            case "zero": return 0L;
            case "one": return 1L;
            case "two": return 2L;
            case "three": return 3L;
            case "four": return 4L;
            case "five": return 5L;
            case "six": return 6L;
            case "seven": return 7L;
            case "eight": return 8L;
            case "nine": return 9L;
            case "ten": return 10L;
            case "eleven": return 11L;
            case "twelve": return 12L;
            case "thirteen": return 13L;
            case "fourteen": return 14L;
            case "fifteen": return 15L;
            case "sixteen": return 16L;
            case "seventeen": return 17L;
            case "eighteen": return 18L;
            case "nineteen": return 19L;
            case "twenty": return 20L;
            case "thirty": return 30L;
            case "forty": return 40L;
            case "fifty": return 50L;
            case "sixty": return 60L;
            case "seventy": return 70L;
            case "eighty": return 80L;
            case "ninety": return 90L;
            default: return null;
        }
    };
}
