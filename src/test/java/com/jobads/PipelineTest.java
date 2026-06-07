package com.jobads;

import com.jobads.audio.Narrator;
import com.jobads.filter.JobFilter;
import com.jobads.image.SlideGenerator;
import com.jobads.model.JobPosting;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PipelineTest {

    @Test
    void filterKeepsJobAdsAndDropsNoise() {
        JobFilter filter = new JobFilter();

        JobPosting job = new JobPosting();
        job.setTitle("Staff Nurse vacancy - apply now");
        assertTrue(filter.isJobAd(job), "should keep an obvious job ad");

        JobPosting noise = new JobPosting();
        noise.setTitle("Subscribe to our newsletter");
        assertFalse(filter.isJobAd(noise), "should drop noise");

        JobPosting boardListing = new JobPosting();
        boardListing.setTitle("Senior Python Developer");
        boardListing.setCompany("Payne, Roberts and Davis");
        assertTrue(filter.isJobAd(boardListing), "should keep structured job-board listing");
    }

    @Test
    void slideGeneratorWritesPng() throws Exception {
        Path dir = Files.createTempDirectory("slides-test-");
        SlideGenerator gen = new SlideGenerator("Test Brand");

        JobPosting job = new JobPosting();
        job.setTitle("Accountant cum Office Assistant");
        job.setCompany("Sri Lakshmi Traders");
        job.setLocation("Chennai");
        job.setSource("Unit Test");

        Path slide = gen.renderJob(job, 1, 1, dir, "slide-001.png");
        assertTrue(Files.exists(slide), "slide image should exist");
        assertTrue(Files.size(slide) > 0, "slide image should not be empty");
    }

    @Test
    void renderAllProducesIntroPlusOnePerJob() throws Exception {
        Path dir = Files.createTempDirectory("slides-test-all-");
        SlideGenerator gen = new SlideGenerator("Test Brand");

        JobPosting a = new JobPosting();
        a.setTitle("Job A");
        JobPosting b = new JobPosting();
        b.setTitle("Job B");

        List<Path> slides = gen.renderAll(List.of(a, b), dir, "Title", "Subtitle");
        // intro + 2 jobs
        assertTrue(slides.size() == 3, "should render intro + one slide per job");
        for (Path p : slides) {
            assertTrue(Files.exists(p));
        }
    }

    @Test
    void narratorBuildsConciseSpokenText() {
        Narrator narrator = new Narrator();

        JobPosting job = new JobPosting();
        job.setTitle("Lead Consultant");
        job.setCompany("National Disaster Management Authority");
        job.setLastDate("22/05/2026");
        job.setSalary("Rs. 25,000 - 35,000 / month");
        job.setQualification("M.Tech with 10 years experience");
        job.setContact("careers@example.com");
        job.setSource("Employment News (employmentnews.gov.in)");

        String spoken = narrator.jobText(job, 1, 9);
        assertTrue(spoken.contains("Job 1 of 9"));
        assertTrue(spoken.contains("Lead Consultant"));
        assertTrue(spoken.contains("22 May 2026"), "date should be humanised for speech");
        // Currency should be expanded for natural reading.
        assertTrue(spoken.contains("rupees"), "Rs. should be expanded to rupees");
        assertFalse(spoken.contains("Rs."), "Rs. abbreviation should be gone");
        // "How to apply" must be narrated, but the raw email is not read out aloud.
        assertTrue(spoken.contains("How to apply"), "how-to-apply should be narrated");
        assertFalse(spoken.contains("M.Tech"), "qualification should not be narrated");
        assertFalse(spoken.contains("@"), "contact email should not be read out aloud");
        assertFalse(spoken.contains("employmentnews.gov.in"), "URL should be stripped from source");
    }

    @Test
    void applyInfoFallsBackSensibly() {
        // Explicit applyHow wins.
        JobPosting a = new JobPosting();
        a.setApplyHow("Walk-in interview on Monday");
        a.setContact("ignored@example.com");
        assertEquals("Walk-in interview on Monday", a.getApplyInfo());

        // No applyHow -> use contact.
        JobPosting b = new JobPosting();
        b.setContact("hr@firm.example");
        assertEquals("hr@firm.example", b.getApplyInfo());

        // No applyHow/contact -> use link.
        JobPosting c = new JobPosting();
        c.setLink("https://jobs.example/posting-1");
        assertTrue(c.getApplyInfo().contains("https://jobs.example/posting-1"));

        // Nothing -> generic pointer to the source, never blank.
        JobPosting d = new JobPosting();
        d.setSource("Employment News");
        assertTrue(d.getApplyInfo().contains("Employment News"));
        assertFalse(d.getApplyInfo().isBlank());
    }

    @Test
    void narratorHumanisesDates() {
        assertEquals("15 June 2026", Narrator.spokenDate("15/06/2026"));
        assertEquals("1 January 2027", Narrator.spokenDate("01-01-2027"));
        // Non dd/mm/yyyy values are passed through unchanged.
        assertEquals("Open until filled", Narrator.spokenDate("Open until filled"));
    }

    @Test
    void containsMalayalamDetectsScript() {
        assertTrue(Narrator.containsMalayalam("ഒഴിവ് 133 vacancies"), "should detect Malayalam");
        assertTrue(Narrator.containsMalayalam("Job 1. പി.എസ്.സി. വിജ്ഞാപനം"), "mixed text");
        assertFalse(Narrator.containsMalayalam("Lead Consultant"), "pure English -> false");
        assertFalse(Narrator.containsMalayalam(""), "empty -> false");
    }

    @Test
    void filterKeepsMalayalamJobNotifications() {
        JobFilter filter = new JobFilter();

        JobPosting mlJob = new JobPosting();
        mlJob.setTitle("സഹകരണസംഘത്തിൽ 133 ഒഴിവ്; അപേക്ഷ ക്ഷണിച്ചു");
        assertTrue(filter.isJobAd(mlJob), "Malayalam job with ഒഴിവ should be kept");

        JobPosting mlPsc = new JobPosting();
        mlPsc.setTitle("16 കാറ്റഗറികളിൽ പി.എസ്.സി. വിജ്ഞാപനം");
        assertTrue(filter.isJobAd(mlPsc), "PSC notification in Malayalam should be kept");
    }
}
