package com.jobads.input;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort extraction of "how to apply" details from the (often OCR'd) free text of a recruitment
 * notification: the application mode, the application fee, and any application/website link. These
 * are heuristics tuned for Indian government job advertisements and are intentionally conservative —
 * they return blank/{@code null} rather than guess when nothing clear is found.
 */
public final class AdTextExtractor {

    private AdTextExtractor() {
    }

    /** e.g. "Apply online", "Apply by post", "Apply by email", or "" when unclear. */
    public static String applyMode(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String t = text.toLowerCase();
        if (t.contains("apply online") || t.contains("online application")
                || t.contains("online mode") || t.contains("submit") && t.contains("online")
                || t.contains("apply through the online") || t.contains("online portal")) {
            return "Apply online";
        }
        if (t.contains("by email") || t.contains("by e-mail") || t.contains("through email")
                || t.contains("e-mail the") || t.contains("email your")) {
            return "Apply by email";
        }
        if (t.contains("by post") || t.contains("through post") || t.contains("speed post")
                || t.contains("registered post") || t.contains("by hand") || t.contains("hard copy")) {
            return "Apply by post";
        }
        if (t.contains("prescribed format") || t.contains("prescribed proforma")
                || t.contains("prescribed pro forma") || t.contains("prescribed application")) {
            return "Apply in the prescribed format";
        }
        return "";
    }

    /**
     * e.g. "Rs. 500", "No application fee", or "" when unclear. Recognises an explicit fee amount
     * near the word "fee", or a fee exemption / "no fee".
     */
    public static String applicationFee(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ");
        String lower = flat.toLowerCase();

        // Fee exemption / free.
        if (lower.matches(".*\\bno\\s+(application\\s+)?fee\\b.*")
                || lower.matches(".*\\bfee\\b.{0,30}\\bexempt.*")
                || lower.matches(".*\\bexempt.{0,30}\\bfee\\b.*")
                || lower.matches(".*\\bnil\\b.{0,15}\\bfee\\b.*")) {
            return "No application fee";
        }
        // Amount near the word "fee": "application fee ... Rs. 500" or "Rs.500 as application fee".
        Pattern[] feePatterns = {
            Pattern.compile("(?i)(?:application\\s+)?fee[^\\d₹]{0,40}?(?:rs\\.?|inr|₹|rupees)\\s*([\\d,]{2,7})"),
            Pattern.compile("(?i)(?:rs\\.?|inr|₹|rupees)\\s*([\\d,]{2,7})[^\\d]{0,40}?(?:application\\s+)?fee")
        };
        for (Pattern p : feePatterns) {
            Matcher m = p.matcher(flat);
            if (m.find()) {
                String amount = m.group(1).replaceAll("[^\\d]", "");
                if (amount.length() >= 2 && amount.length() <= 6) {
                    return "Fee: Rs. " + amount;
                }
            }
        }
        return "";
    }

    /**
     * Best-effort application/website link found in the text (e.g. an organisation's recruitment
     * portal). OCR frequently mangles URLs, so this only returns a candidate that looks like a clean
     * domain. {@code excludeHost} (e.g. "employmentnews.gov.in") is skipped. Returns "" when none.
     */
    public static String applyLink(String text, String excludeHost) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String flat = text.replaceAll("\\s+", " ");
        Matcher m = Pattern.compile(
                "(?i)\\b((?:https?://)?(?:www\\.)?[a-z0-9][a-z0-9.-]{2,}\\."
                        + "(?:gov\\.in|nic\\.in|ac\\.in|edu\\.in|res\\.in|org\\.in|co\\.in|org|com|net|in)"
                        + "(?:/[^\\s,;)]*)?)").matcher(flat);
        String best = "";
        while (m.find()) {
            String url = m.group(1).replaceAll("[.,;:)]+$", "").trim();
            String host = url.replaceAll("(?i)^https?://", "").replaceAll("(?i)^www\\.", "");
            String hostOnly = host.split("/")[0].toLowerCase();
            if (excludeHost != null && hostOnly.contains(excludeHost.toLowerCase())) {
                continue;
            }
            if (hostOnly.length() < 5 || !hostOnly.contains(".")) {
                continue;
            }
            // Prefer official domains; otherwise keep the first plausible one.
            boolean official = hostOnly.endsWith(".gov.in") || hostOnly.endsWith(".nic.in")
                    || hostOnly.endsWith(".ac.in") || hostOnly.endsWith(".edu.in")
                    || hostOnly.endsWith(".res.in");
            if (official) {
                return url;
            }
            if (best.isEmpty()) {
                best = url;
            }
        }
        return best;
    }
}
