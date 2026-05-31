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
            "appointment", "employment", "career", "staff needed"
    };

    private static final String[] NOISE_KEYWORDS = {
            "subscribe", "advertise with us", "privacy policy", "cookie", "sign in", "log in",
            "terms of service", "newsletter"
    };

    /** Keep an ad if it contains a job keyword and no obvious noise keyword. */
    public boolean isJobAd(JobPosting job) {
        String haystack = (safe(job.getTitle()) + " " + safe(job.getCompany()) + " "
                + safe(job.getLocation())).toLowerCase(Locale.ROOT);

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
        return false;
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
