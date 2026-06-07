package com.jobads.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * A single job advertisement reduced to its plain facts.
 *
 * <p>We deliberately keep only the textual details (never the original ad image) so that the video
 * we generate contains our own rendered slides rather than copyrighted newspaper artwork.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class JobPosting {

    private String title;
    private String company;
    private String location;
    private String qualification;
    private String salary;
    private String contact;
    private String lastDate;
    private String source;
    private String link;
    /** Explicit "how to apply" instruction, when the source provides one. */
    private String applyHow;

    public JobPosting() {
        // for Jackson
    }

    public JobPosting(String title, String company, String location, String qualification,
                      String salary, String contact, String lastDate, String source, String link) {
        this.title = title;
        this.company = company;
        this.location = location;
        this.qualification = qualification;
        this.salary = salary;
        this.contact = contact;
        this.lastDate = lastDate;
        this.source = source;
        this.link = link;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCompany() {
        return company;
    }

    public void setCompany(String company) {
        this.company = company;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getQualification() {
        return qualification;
    }

    public void setQualification(String qualification) {
        this.qualification = qualification;
    }

    public String getSalary() {
        return salary;
    }

    public void setSalary(String salary) {
        this.salary = salary;
    }

    public String getContact() {
        return contact;
    }

    public void setContact(String contact) {
        this.contact = contact;
    }

    public String getLastDate() {
        return lastDate;
    }

    public void setLastDate(String lastDate) {
        this.lastDate = lastDate;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getLink() {
        return link;
    }

    public void setLink(String link) {
        this.link = link;
    }

    public String getApplyHow() {
        return applyHow;
    }

    public void setApplyHow(String applyHow) {
        this.applyHow = applyHow;
    }

    /**
     * A best-effort "how to apply" instruction for the slide/narration: prefers an explicit
     * {@code applyHow}, then the scraped contact, then the application link, otherwise a generic
     * pointer back to the source. Never returns blank.
     */
    public String getApplyInfo() {
        if (applyHow != null && !applyHow.isBlank()) {
            return applyHow.trim();
        }
        if (contact != null && !contact.isBlank()) {
            return contact.trim();
        }
        if (link != null && !link.isBlank()) {
            return "Apply online at " + link.trim();
        }
        return "See the original notification in "
                + (source != null && !source.isBlank() ? source.trim() : "the newspaper listing");
    }

    @Override
    public String toString() {
        return "JobPosting{title='" + title + "', company='" + company + "', location='" + location
                + "', source='" + source + "'}";
    }
}
