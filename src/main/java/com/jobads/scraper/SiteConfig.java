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

    /** CSS selector (relative to the item) for the qualification. Optional. */
    private String qualificationSelector;

    /** CSS selector (relative to the item) for the salary. Optional. */
    private String salarySelector;

    /** CSS selector (relative to the item) for the contact. Optional. */
    private String contactSelector;

    /** CSS selector (relative to the item) for the last/closing date. Optional. */
    private String lastDateSelector;

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

    public String getQualificationSelector() {
        return qualificationSelector;
    }

    public void setQualificationSelector(String qualificationSelector) {
        this.qualificationSelector = qualificationSelector;
    }

    public String getSalarySelector() {
        return salarySelector;
    }

    public void setSalarySelector(String salarySelector) {
        this.salarySelector = salarySelector;
    }

    public String getContactSelector() {
        return contactSelector;
    }

    public void setContactSelector(String contactSelector) {
        this.contactSelector = contactSelector;
    }

    public String getLastDateSelector() {
        return lastDateSelector;
    }

    public void setLastDateSelector(String lastDateSelector) {
        this.lastDateSelector = lastDateSelector;
    }
}
