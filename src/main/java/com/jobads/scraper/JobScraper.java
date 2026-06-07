package com.jobads.scraper;

import com.jobads.model.JobPosting;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Downloads a page with jsoup and extracts the textual details of each job ad according to a
 * {@link SiteConfig}. Only plain text is collected — never the original ad image.
 */
public class JobScraper {

    private static final String USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/133.0.0.0 Safari/537.36";
    private static final int TIMEOUT_MS = 30_000;
    /** Some sites (e.g. flaky government servers) intermittently return 500/timeouts; retry a few times. */
    private static final int MAX_ATTEMPTS = 3;
    private static final long RETRY_BACKOFF_MS = 3_000;

    /**
     * Scrape a single configured site. Network/parse failures are logged and result in an empty list
     * so that one broken site never aborts the whole run.
     */
    public List<JobPosting> scrape(SiteConfig site) {
        List<JobPosting> jobs = new ArrayList<>();
        Document doc = fetch(site.getUrl());
        if (doc != null) {
            Elements items = doc.select(site.getItemSelector());
            for (Element item : items) {
                String title = text(item, site.getTitleSelector(), item.text());
                if (title == null || title.isBlank()) {
                    continue;
                }
                String company = text(item, site.getCompanySelector(), "");
                String location = text(item, site.getLocationSelector(), "");
                String qualification = text(item, site.getQualificationSelector(), "");
                String salary = text(item, site.getSalarySelector(), "");
                String contact = text(item, site.getContactSelector(), "");
                String lastDate = text(item, site.getLastDateSelector(), "");
                String link = link(item, site.getLinkSelector());
                String applyHow = text(item, site.getApplyHowSelector(),
                        site.getApplyHow() != null ? site.getApplyHow() : "");

                JobPosting job = new JobPosting();
                job.setTitle(title.trim());
                job.setCompany(company.trim());
                job.setLocation(location.trim());
                job.setQualification(qualification.trim());
                job.setSalary(salary.trim());
                job.setContact(contact.trim());
                job.setLastDate(lastDate.trim());
                job.setSource(site.getName());
                job.setLink(link);
                job.setApplyHow(applyHow.trim());
                jobs.add(job);
            }
        }
        return jobs;
    }

    /** Fetch a URL with a few retries to tolerate intermittent server errors / timeouts. */
    private static Document fetch(String url) {
        IOException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return Jsoup.connect(url)
                        .userAgent(USER_AGENT)
                        .timeout(TIMEOUT_MS)
                        .get();
            } catch (IOException e) {
                last = e;
                System.err.println("[scraper] attempt " + attempt + "/" + MAX_ATTEMPTS
                        + " failed for " + url + ": " + e.getMessage());
                if (attempt < MAX_ATTEMPTS) {
                    try {
                        Thread.sleep(RETRY_BACKOFF_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        System.err.println("[scraper] giving up on " + url
                + (last != null ? " (" + last.getMessage() + ")" : ""));
        return null;
    }

    /** Scrape every configured site and concatenate the results. */
    public List<JobPosting> scrapeAll(List<SiteConfig> sites) {
        List<JobPosting> all = new ArrayList<>();
        for (SiteConfig site : sites) {
            List<JobPosting> jobs = scrape(site);
            System.out.println("[scraper] " + site.getName() + ": found " + jobs.size() + " item(s)");
            all.addAll(jobs);
        }
        return all;
    }

    private static String text(Element item, String selector, String fallback) {
        if (selector == null || selector.isBlank()) {
            return fallback;
        }
        Element el = item.selectFirst(selector);
        return el != null ? el.text() : fallback;
    }

    private static String link(Element item, String selector) {
        Element el = (selector == null || selector.isBlank()) ? item : item.selectFirst(selector);
        if (el == null) {
            return "";
        }
        String href = el.absUrl("href");
        return href.isBlank() ? el.attr("href") : href;
    }
}
