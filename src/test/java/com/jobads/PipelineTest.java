package com.jobads;

import com.jobads.filter.JobFilter;
import com.jobads.image.SlideGenerator;
import com.jobads.model.JobPosting;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

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
}
