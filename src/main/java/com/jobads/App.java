package com.jobads;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobads.audio.Narrator;
import com.jobads.filter.JobFilter;
import com.jobads.image.SlideGenerator;
import com.jobads.model.JobPosting;
import com.jobads.scraper.EvidenceCapture;
import com.jobads.scraper.JobScraper;
import com.jobads.scraper.SiteConfig;
import com.jobads.video.VideoBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point. Wires the pipeline together:
 *
 * <pre>
 *   scrape (or load sample) -> filter job ads -> render one slide per job -> ffmpeg -> jobs.mp4
 * </pre>
 *
 * Usage:
 * <pre>
 *   java -jar news-job-ads-video.jar [options]
 *     --sample              use bundled sample-jobs.json instead of scraping (default if no sources)
 *     --sources &lt;file&gt;      path to a sources.json describing sites to scrape
 *     --out &lt;file&gt;          output video path (default: jobs.mp4)
 *     --brand &lt;text&gt;        brand/title shown in the header (default: "Job Alerts")
 *     --seconds &lt;n&gt;         seconds each slide is shown (default: 5)
 *     --fps &lt;n&gt;             video frames per second (default: 25)
 *     --limit &lt;n&gt;           cap the number of jobs (default: no limit)
 *     --evidence &lt;dir&gt;      directory to save proof-of-source evidence (default: &lt;out&gt;/evidence)
 *     --no-evidence         skip capturing evidence screenshots
 *     --no-audio            skip spoken narration (otherwise on when a TTS engine is available)
 *     --disclaimer &lt;text&gt;   custom disclaimer shown/narrated after the intro (has a default)
 *     --no-disclaimer       omit the disclaimer card
 * </pre>
 *
 * <p>When scraping real sites, the app also captures an evidence archive (a screenshot of each
 * actual ad, the original ad image when present, and a {@code manifest.json}) so the original source
 * can be shown if an ad's authenticity is questioned. The video itself only ever contains our own
 * rendered slides.
 */
public class App {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String DEFAULT_DISCLAIMER =
            "This video is for information only. Job details are summarised from publicly "
            + "available newspaper and job-portal listings, with the source shown on each slide. "
            + "Please verify every detail on the official source before applying. We are not the "
            + "recruiter and do not charge any fee.";

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        boolean useSample = opts.containsKey("sample") || !opts.containsKey("sources");

        String brand = opts.getOrDefault("brand", "Job Alerts");
        Path output = Paths.get(opts.getOrDefault("out", "jobs.mp4"));
        int seconds = Integer.parseInt(opts.getOrDefault("seconds", "5"));
        int fps = Integer.parseInt(opts.getOrDefault("fps", "25"));
        int limit = Integer.parseInt(opts.getOrDefault("limit", "-1"));

        List<JobPosting> jobs;
        if (useSample) {
            System.out.println("[app] running in SAMPLE mode (no network).");
            System.out.println("[app] note: evidence capture is skipped in sample mode "
                    + "(there is no real source page to screenshot).");
            jobs = loadSample();
        } else {
            List<SiteConfig> sites = loadSources(Paths.get(opts.get("sources")));
            List<JobPosting> scraped = new JobScraper().scrapeAll(sites);
            jobs = new JobFilter().filter(scraped);
            System.out.println("[app] " + jobs.size() + " job ad(s) after filtering.");

            if (!opts.containsKey("no-evidence")) {
                Path evidenceDir = Paths.get(opts.getOrDefault("evidence", defaultEvidenceDir(output)));
                int evidenceCap = limit > 0 ? limit : 25;
                System.out.println("[app] capturing proof-of-source evidence (up to " + evidenceCap
                        + " per site) to " + evidenceDir);
                try {
                    new EvidenceCapture().captureAll(sites, evidenceDir, evidenceCap);
                } catch (Exception e) {
                    System.err.println("[app] evidence capture failed (continuing without it): "
                            + e.getMessage());
                }
            }
        }

        if (limit > 0 && jobs.size() > limit) {
            jobs = jobs.subList(0, limit);
        }
        if (jobs.isEmpty()) {
            System.err.println("[app] No jobs to render. Nothing to do.");
            return;
        }

        boolean wantDisclaimer = !opts.containsKey("no-disclaimer");
        String disclaimerText = opts.getOrDefault("disclaimer", DEFAULT_DISCLAIMER);

        Path workDir = Files.createTempDirectory("job-slides-");
        System.out.println("[app] rendering " + jobs.size() + " slide(s) to " + workDir);
        SlideGenerator slideGenerator = new SlideGenerator(brand);
        Narrator narrator = new Narrator();

        // Build slides and their matching narration lines in lock-step so the audio always lines up.
        List<Path> slides = new ArrayList<>();
        List<String> narrations = new ArrayList<>();

        int slideNo = 0;
        slides.add(slideGenerator.renderIntro(brand, jobs.size() + " job openings for you",
                workDir, String.format("slide-%03d.png", slideNo++)));
        narrations.add(narrator.introText(brand, jobs.size()));

        if (wantDisclaimer) {
            slides.add(slideGenerator.renderDisclaimer(disclaimerText, workDir,
                    String.format("slide-%03d.png", slideNo++)));
            narrations.add(disclaimerText);
        }

        for (int i = 0; i < jobs.size(); i++) {
            slides.add(slideGenerator.renderJob(jobs.get(i), i + 1, jobs.size(), workDir,
                    String.format("slide-%03d.png", slideNo++)));
            narrations.add(narrator.jobText(jobs.get(i), i + 1, jobs.size()));
        }

        VideoBuilder videoBuilder = new VideoBuilder(seconds, fps);
        boolean wantAudio = !opts.containsKey("no-audio");

        if (wantAudio && narrator.isAvailable()) {
            System.out.println("[app] generating narration with " + narrator.engineName()
                    + " (use --no-audio to disable)");
            List<Path> audios = new ArrayList<>();
            for (int i = 0; i < narrations.size(); i++) {
                audios.add(narrator.synth(narrations.get(i),
                        workDir.resolve(String.format("voice-%03d.wav", i))));
            }
            System.out.println("[app] building narrated video with ffmpeg -> " + output.toAbsolutePath());
            videoBuilder.buildWithAudio(slides, audios, output);
        } else {
            if (wantAudio) {
                System.out.println("[app] no TTS engine found (install pico2wave or espeak-ng for "
                        + "narration); building a silent video.");
            }
            System.out.println("[app] building video with ffmpeg -> " + output.toAbsolutePath());
            videoBuilder.build(slides, output);
        }

        System.out.println("[app] DONE. Video written to: " + output.toAbsolutePath());
    }

    private static String defaultEvidenceDir(Path output) {
        Path parent = output.toAbsolutePath().getParent();
        Path dir = (parent == null) ? Paths.get("evidence") : parent.resolve("evidence");
        return dir.toString();
    }

    private static List<JobPosting> loadSample() throws IOException {
        try (InputStream in = App.class.getResourceAsStream("/sample-jobs.json")) {
            if (in == null) {
                throw new IOException("sample-jobs.json not found on classpath");
            }
            JobPosting[] arr = MAPPER.readValue(in, JobPosting[].class);
            return new ArrayList<>(Arrays.asList(arr));
        }
    }

    private static List<SiteConfig> loadSources(Path path) throws IOException {
        SiteConfig[] arr = MAPPER.readValue(path.toFile(), SiteConfig[].class);
        return new ArrayList<>(Arrays.asList(arr));
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String key = a.substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    opts.put(key, args[++i]);
                } else {
                    opts.put(key, "true");
                }
            }
        }
        return opts;
    }
}
