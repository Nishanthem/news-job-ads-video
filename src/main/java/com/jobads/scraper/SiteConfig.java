package com.jobads.scraper;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Describes how to scrape a single website. Every newspaper / job board has a different HTML layout,
 * so the CSS selectors are configured per site in {@code sources.json} rather than hard-coded.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SiteConfig {

    /** Human readable source name shown on the slide (e.g. "The Hindu Classifieds"). */
    private String name;

    /** Page to download. */
    private String url;

    /** CSS selector that matches the container element of each job ad. */
    private String itemSelector;

    /** CSS selector (relative to the item) for the job title. Optional. */
    private String titleSelector;

    /** CSS selector (relative to the item) for the company. Optional. */
    private String companySelector;

    /** CSS selector (relative to the item) for the location. Optional. */
    private String locationSelector;

    /** CSS selector (relative to the item) for the link. Optional. */
    private String linkSelector;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getItemSelector() {
        return itemSelector;
    }

    public void setItemSelector(String itemSelector) {
        this.itemSelector = itemSelector;
    }

    public String getTitleSelector() {
        return titleSelector;
    }

    public void setTitleSelector(String titleSelector) {
        this.titleSelector = titleSelector;
    }

    public String getCompanySelector() {
        return companySelector;
    }

    public void setCompanySelector(String companySelector) {
        this.companySelector = companySelector;
    }

    public String getLocationSelector() {
        return locationSelector;
    }

    public void setLocationSelector(String locationSelector) {
        this.locationSelector = locationSelector;
    }

    public String getLinkSelector() {
        return linkSelector;
    }

    public void setLinkSelector(String linkSelector) {
        this.linkSelector = linkSelector;
    }
}
