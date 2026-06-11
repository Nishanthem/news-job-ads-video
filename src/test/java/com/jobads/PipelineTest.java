package com.jobads;

import com.jobads.audio.Narrator;
import com.jobads.filter.JobFilter;
import com.jobads.image.SlideGenerator;
import com.jobads.input.DocumentImporter;
import com.jobads.model.JobPosting;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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
        assertTrue(filter.isJobAd(mlPsc), "PSC notification (വിജ്ഞാപനം) should be kept");

        // Career advice articles and rank lists should be dropped.
        JobPosting advice = new JobPosting();
        advice.setTitle("സർക്കാർ ജോലിയിലേക്ക് എളുപ്പവഴി");
        assertFalse(filter.isJobAd(advice), "career advice article should be dropped");

        JobPosting rankList = new JobPosting();
        rankList.setTitle("കേരള പി.എസ്.സി. റാങ്ക് ലിസ്റ്റുകൾ");
        assertFalse(filter.isJobAd(rankList), "PSC rank list (not a job opening) should be dropped");
    }

    @Test
    void importerParsesLabelledText() {
        String text = String.join("\n",
                "Title: Staff Nurse",
                "Company: Government General Hospital",
                "Location: Ernakulam",
                "Qualification: B.Sc Nursing",
                "Salary: Rs. 35,000 per month",
                "Last date: 25-06-2026",
                "How to apply: Apply online at the hospital portal",
                "Contact: recruit@gghospital.gov.in");

        JobPosting job = new DocumentImporter().parse(text, Paths.get("nurse.pdf"));
        assertEquals("Staff Nurse", job.getTitle());
        assertEquals("Government General Hospital", job.getCompany());
        assertEquals("Ernakulam", job.getLocation());
        assertEquals("B.Sc Nursing", job.getQualification());
        assertEquals("25-06-2026", job.getLastDate());
        assertEquals("recruit@gghospital.gov.in", job.getContact());
        assertTrue(job.getApplyInfo().contains("Apply online"), "applyHow should be picked up");
        // No source is fabricated for imported files when none is known.
        assertTrue(job.getSource() == null || job.getSource().isBlank(),
                "source should be blank when not provided");
    }

    @Test
    void importerUsesSourceLineThenSourceNameFallback() {
        // A "Source:" line in the document wins.
        String withSource = String.join("\n",
                "Title: Lab Technician",
                "Source: Malayala Manorama");
        JobPosting a = new DocumentImporter("eng", "Fallback Daily").parse(withSource, Paths.get("a.pdf"));
        assertEquals("Malayala Manorama", a.getSource());

        // Without a Source line, the run-wide source name is used.
        String noSource = "Title: Lab Technician";
        JobPosting b = new DocumentImporter("eng", "Fallback Daily").parse(noSource, Paths.get("b.pdf"));
        assertEquals("Fallback Daily", b.getSource());
    }

    @Test
    void importerFallsBackToFirstLineAndFindsDateAndEmail() {
        // No explicit Title/Contact labels: title = first line, contact = email found in body,
        // last date = date near the "last date" phrase.
        String text = String.join("\n",
                "Walk-in for Computer Operators",
                "Some descriptive paragraph about the role.",
                "The last date to apply is 30-06-2026.",
                "Send CV to careers@firm.example");

        JobPosting job = new DocumentImporter().parse(text, Paths.get("ad.png"));
        assertEquals("Walk-in for Computer Operators", job.getTitle());
        assertEquals("30-06-2026", job.getLastDate());
        assertEquals("careers@firm.example", job.getContact());
    }

    @Test
    void importerKeepsMalayalamValues() {
        String text = String.join("\n",
                "Title: ലാസ്റ്റ് ഗ്രേഡ് സർവന്റ് ഒഴിവ്",
                "Company: കേരള പബ്ലിക് സർവീസ് കമ്മീഷൻ",
                "Last date: 20-06-2026");

        JobPosting job = new DocumentImporter().parse(text, Paths.get("ml.pdf"));
        assertEquals("ലാസ്റ്റ് ഗ്രേഡ് സർവന്റ് ഒഴിവ്", job.getTitle());
        assertTrue(Narrator.containsMalayalam(job.getTitle()), "title should retain Malayalam");
        assertEquals("കേരള പബ്ലിക് സർവീസ് കമ്മീഷൻ", job.getCompany());
    }
}
