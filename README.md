# news-job-ads-video

A small Java application that helps job seekers by turning **job advertisements from newspaper /
job websites into a clean video** that can be posted on YouTube, WhatsApp, etc.

To avoid reproducing copyrighted newspaper artwork, the app **does not screenshot the original
ads**. Instead it extracts only the *facts* of each job (role, company, location, qualification,
salary, contact, last date) and renders its **own branded slide** for every job. The slides are then
stitched into an MP4 with `ffmpeg`.

```
scrape (or load sample)  ->  filter job ads  ->  render one slide per job (Java2D)  ->  ffmpeg  ->  jobs.mp4
```

## Example output

A generated job slide and intro card:

![Sample job slide](docs/sample-slide.png)
![Sample intro card](docs/sample-intro.png)

## Requirements

- Java 17+
- Maven 3.6+
- `ffmpeg` on your `PATH` (used to build the video)

On Ubuntu/Debian: `sudo apt-get install -y maven ffmpeg`

## Build

```bash
mvn clean package
```

This produces a runnable fat-jar at `target/news-job-ads-video.jar`.

## Run

### 1. Try it immediately with sample data (no network needed)

```bash
java -jar target/news-job-ads-video.jar --sample --out jobs.mp4 --brand "Daily Job Alerts"
```

This reads [`src/main/resources/sample-jobs.json`](src/main/resources/sample-jobs.json), renders a
slide per job and writes `jobs.mp4`.

### 2. Scrape real websites

Describe each site in a `sources.json` (see
[`src/main/resources/sources.json`](src/main/resources/sources.json) for the format) and run:

```bash
java -jar target/news-job-ads-video.jar --sources sources.json --out jobs.mp4
```

Every site has a different HTML layout, so you set the CSS selectors per site:

```json
[
  {
    "name": "Example Job Board",
    "url": "https://example-job-board.com/listings",
    "itemSelector": "div.job-card",
    "titleSelector": "h2.job-title",
    "companySelector": ".company-name",
    "locationSelector": ".job-location",
    "linkSelector": "a"
  }
]
```

To find the right selectors: open the page in a browser, right-click an ad → **Inspect**, and note
the element/class that wraps each listing.

### Options

| Flag | Default | Meaning |
|------|---------|---------|
| `--sample` | off | Use bundled sample data instead of scraping |
| `--sources <file>` | – | Path to a `sources.json` of sites to scrape |
| `--out <file>` | `jobs.mp4` | Output video path |
| `--brand <text>` | `Job Alerts` | Title shown in the slide header |
| `--seconds <n>` | `5` | Seconds each slide is shown |
| `--fps <n>` | `25` | Video frame rate |
| `--limit <n>` | none | Cap the number of jobs |

## Project layout

```
src/main/java/com/jobads/
  App.java                  # entry point – wires the pipeline
  model/JobPosting.java     # plain job facts (no images)
  scraper/JobScraper.java   # jsoup-based scraping
  scraper/SiteConfig.java   # per-site selector config
  filter/JobFilter.java     # keep job ads, drop noise
  image/SlideGenerator.java # render a clean slide per job (Java2D)
  video/VideoBuilder.java   # combine slides into mp4 via ffmpeg
```

## Legal / ethical note

Always check a website's **Terms of Service** and `robots.txt` before scraping it, and prefer
sources that offer an RSS feed or official API. This tool intentionally re-renders the job details
into original slides rather than copying the newspaper's own ad images.

## Deploying on AWS (optional)

Because the job is a periodic batch (e.g. produce one video per day), the simplest deployment is:

- **AWS Lambda + EventBridge schedule** to run the jar on a cron, writing the video to **S3**; or
- **ECS Fargate scheduled task** running the Docker image; or
- **Elastic Beanstalk** if you later wrap it in a web service.

`ffmpeg` must be available in the runtime (e.g. a Lambda layer or baked into the container image).
