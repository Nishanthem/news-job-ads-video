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
            "Mozilla/5.0 (compatible; JobAdsBot/1.0; +https://github.com/Nishanthem/news-job-ads-video)";
    private static final int TIMEOUT_MS = 15_000;

    /**
     * Scrape a single configured site. Network/parse failures are logged and result in an empty list
     * so that one broken site never aborts the whole run.
     */
    public List<JobPosting> scrape(SiteConfig site) {
        List<JobPosting> jobs = new ArrayList<>();
        try {
            Document doc = Jsoup.connect(site.getUrl())
                    .userAgent(USER_AGENT)
                    .timeout(TIMEOUT_MS)
                    .get();

            Elements items = doc.select(site.getItemSelector());
            for (Element item : items) {
                String title = text(item, site.getTitleSelector(), item.text());
                if (title == null || title.isBlank()) {
                    continue;
                }
                String company = text(item, site.getCompanySelector(), "");
                String location = text(item, site.getLocationSelector(), "");
                String link = link(item, site.getLinkSelector());

                JobPosting job = new JobPosting();
                job.setTitle(title.trim());
                job.setCompany(company.trim());
                job.setLocation(location.trim());
                job.setSource(site.getName());
                job.setLink(link);
                jobs.add(job);
            }
        } catch (IOException e) {
            System.err.println("[scraper] failed to scrape " + site.getUrl() + ": " + e.getMessage());
        }
        return jobs;
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
