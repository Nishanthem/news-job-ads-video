package com.jobads.filter;

import com.jobads.model.JobPosting;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Heuristic filter that keeps only items that look like genuine job advertisements and drops obvious
 * noise. Keyword lists are intentionally simple and easy to extend.
 */
public class JobFilter {

    private static final String[] JOB_KEYWORDS = {
            "hiring", "vacancy", "vacancies", "wanted", "recruitment", "recruit", "job", "jobs",
            "position", "apply", "walk-in", "walk in", "required", "openings", "opening",
            "appointment", "employment", "career", "staff needed",
            // Malayalam roots so Malayalam-language sources (e.g. Mathrubhumi Thozhil Vartha) are kept.
            // Case folding does not affect Malayalam, so these are matched as-is.
            "ഒഴിവ",        // vacancy
            "വിജ്ഞാപന",    // notification
            "അപേക്ഷ",      // application / apply
            "നിയമന",       // appointment
            "റിക്രൂട്ട",     // recruitment
            "തസ്തിക",       // post / position
            "ജോലി",        // job
            "പി.എസ്.സി"    // PSC (Public Service Commission)
    };

    private static final String[] NOISE_KEYWORDS = {
            "subscribe", "advertise with us", "privacy policy", "cookie", "sign in", "log in",
            "terms of service", "newsletter"
    };

    /**
     * Keep an ad when it has no obvious noise keyword AND either:
     * <ul>
     *   <li>it contains a job keyword (typical of newspaper classifieds like "wanted"/"vacancy"), or</li>
     *   <li>it is a well-structured listing with both a title and a company (typical of job boards).</li>
     * </ul>
     */
    public boolean isJobAd(JobPosting job) {
        String title = safe(job.getTitle());
        String company = safe(job.getCompany());
        String haystack = (title + " " + company + " " + safe(job.getLocation()))
                .toLowerCase(Locale.ROOT);

        for (String noise : NOISE_KEYWORDS) {
            if (haystack.contains(noise)) {
                return false;
            }
        }
        for (String keyword : JOB_KEYWORDS) {
            if (haystack.contains(keyword)) {
                return true;
            }
        }
        // Structured listing: a real job entry usually pairs a title with a company name.
        return !title.isBlank() && !company.isBlank();
    }

    /** Return only the items that look like job ads. */
    public List<JobPosting> filter(List<JobPosting> jobs) {
        List<JobPosting> kept = new ArrayList<>();
        for (JobPosting job : jobs) {
            if (isJobAd(job)) {
                kept.add(job);
            }
        }
        return kept;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }
}
