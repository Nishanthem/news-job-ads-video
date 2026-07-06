# news-job-ads-video

A small Java application that helps job seekers by turning **job advertisements from newspaper /
job websites into a clean video** that can be posted on YouTube, WhatsApp, etc.

To avoid reproducing copyrighted newspaper artwork, the app **does not screenshot the original
ads**. Instead it extracts only the *facts* of each job (role, company, location, qualification,
salary, contact, last date) and renders its **own branded slide** for every job. The slides are then
stitched into an MP4 with `ffmpeg`. The video also gets a spoken **voice-over** of each job (offline
text-to-speech) so it can be listened to as well as read; each slide is shown for as long as its
narration plays.

```
scrape (or load sample)  ->  filter job ads  ->  render one slide per job (Java2D)  ->  ffmpeg  ->  jobs.mp4
                                                  \-> narrate each job (TTS) ----------/
```

## Example output

A generated job slide and intro card (shown in the video):

![Sample job slide](docs/sample-slide.png)
![Sample intro card](docs/sample-intro.png)

## Proof-of-source evidence (authenticity)

The video only ever shows our own re-rendered slides. But for **trust**, when scraping real sites the
app also saves a separate `evidence/` archive so you can prove where each ad came from if anyone
questions it. For every ad it captures:

- a **screenshot of the actual ad** as it appears on the source page (via headless Chrome),
- the **original ad image** when the listing is itself an image (e.g. e-paper clippings),
- a full-page screenshot of the source page for context, and
- a **`manifest.json`** recording the source name, page URL, ad link and a capture **timestamp**.

Example captured ad screenshot:

![Sample evidence ad](docs/sample-evidence-ad.png)

This archive is **not** included in the video — it is your proof, kept on the side.

## Requirements

- Java 17+
- Maven 3.6+
- `ffmpeg` on your `PATH` (used to build the video, mux the narration audio, and synthesise the background music)
- *(Optional, only for `--input`)* tools to read PDF/image job ads:
  - `pdftotext` and `pdftoppm` from **poppler-utils** (PDF text + rasterising scanned PDFs)
  - **tesseract** OCR with the English + Malayalam packs (`tesseract-ocr tesseract-ocr-mal`) for images/scans
- A text-to-speech engine for narration (optional, any one):
  1. **Piper** (`pip install piper-tts`) + the Indian-English voice model `en_IN-spicor-medium`
     (natural Indian accent, recommended). Place the `.onnx` + `.onnx.json` in
     `~/piper-voices/` or set `PIPER_MODEL=/path/to/model.onnx`.
  2. *(Optional)* **Malayalam voice** `ml_IN-meera-medium` for Mathrubhumi/Malayalam-language slides.
     Place it in `~/piper-voices/` alongside the English model. When present the app automatically
     narrates slides containing Malayalam text with the Malayalam voice.
  3. **espeak-ng** (`sudo apt install espeak-ng`) — lighter, robotic but correct numbers.
  4. **pico2wave** (`sudo apt install libttspico-utils`) — smooth but reads numbers digit-by-digit.
  If none is installed the app just produces a silent video.
- **Google Chrome / Chromium** installed (only needed for evidence capture when scraping real sites;
  not needed for `--sample`). The matching `chromedriver` is fetched automatically by Selenium
  Manager. If Chrome is in a non-standard location, set `CHROME_BINARY=/path/to/chrome` (or
  `-Dchrome.binary=...`).

On Ubuntu/Debian:
```bash
sudo apt-get install -y maven ffmpeg espeak-ng libttspico-utils
# Optional, for reading PDF/image job ads (--input) and Malayalam rendering/OCR:
sudo apt-get install -y poppler-utils tesseract-ocr tesseract-ocr-mal fonts-noto-core
pip install piper-tts   # recommended for Indian-English neural voice
# Download the Indian English voice model:
mkdir -p ~/piper-voices
curl -sL 'https://huggingface.co/navgurukul-ai-labs/text-to-speech-en-IN-piper/resolve/main/en_IN-dataset%3Dspicor-english-base%3Dljspeech-epochs%3D1089.onnx' -o ~/piper-voices/en_IN-spicor-medium.onnx
curl -sL 'https://huggingface.co/navgurukul-ai-labs/text-to-speech-en-IN-piper/resolve/main/en_IN-dataset%3Dspicor-english-base%3Dljspeech-epochs%3D1089.onnx.json' -o ~/piper-voices/en_IN-spicor-medium.onnx.json
# (Optional) Download the Malayalam voice model for Mathrubhumi Thozhil Vartha:
curl -sL 'https://huggingface.co/rhasspy/piper-voices/resolve/main/ml/ml_IN/meera/medium/ml_IN-meera-medium.onnx' -o ~/piper-voices/ml_IN-meera-medium.onnx
curl -sL 'https://huggingface.co/rhasspy/piper-voices/resolve/main/ml/ml_IN/meera/medium/ml_IN-meera-medium.onnx.json' -o ~/piper-voices/ml_IN-meera-medium.onnx.json
```

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

### 3. Import job ads you already have as PDF or image

Give the app a single file or a folder of them; it extracts the details and renders the same clean,
copyright-safe slides (the original file is **not** shown in the video).

```bash
java -jar target/news-job-ads-video.jar --input my-ads/ --out jobs.mp4 --brand "Daily Job Alerts"
```

- **PDFs** are read with `pdftotext`; scanned PDFs (no text layer) are rasterised and OCR'd.
- **Images** (`.png/.jpg/.jpeg/.tif/.bmp/.webp`) are read with `tesseract` OCR (English + Malayalam).
- The importer understands `Label: value` lines (`Title`, `Company`, `Location`, `Qualification`,
  `Salary`, `Last date`, `How to apply`, `Contact`); otherwise it uses the first line as the title.
- OCR is not perfect (especially Malayalam on scans) — review the extracted details before publishing.
- `--input` can be combined with `--sources`; imported ads bypass the keyword filter.
- **Source label:** imported files have no known source, so by default no source line is shown. Add a
  `Source: <name>` line inside the document, or pass `--source-name "Times of India"` to label every
  imported ad in the run.

```bash
java -jar target/news-job-ads-video.jar --input my-ads/ --source-name "Times of India" --out jobs.mp4
```

### 4. Pull ads from Employment News (official free notification PDFs)

The Government of India's **Employment News** publishes a free ["Web Advertisement"](https://employmentnews.gov.in/NewEmp/MoreContentS.aspx?n=WebAdvertisement)
page listing ~50 current recruitment ads per week, each linking to an official notification PDF. The
full weekly e-paper is a paid subscription, but these individual advertisement PDFs are public.

```bash
java -jar target/news-job-ads-video.jar --employment-news --brand "Government Job Alerts" --out gov-jobs.mp4
```

- The listing gives a clean organisation name; the linked PDF is downloaded and (since these are
  scans with no text layer) **OCR'd** to pull the position title and last date.
- The headline is the extracted position when one is found (e.g. *"Finance-cum-Accounts Officer"*),
  otherwise the organisation name; `Source: Employment News` is shown on every slide.
- OCR of ~50 PDFs is slow, so it is capped with `--en-limit <n>` (default `12`).
- Combine with `--sources` and/or `--input` to mix these ads with scraped/imported ones.

```bash
java -jar target/news-job-ads-video.jar --employment-news --en-limit 25 --out gov-jobs.mp4
```

### Background music

A lively, **royalty-free** tabla groove plays quietly under the narration by default (it is
synthesised from scratch with ffmpeg, so it is YouTube-safe). Override or disable it:

```bash
java -jar target/news-job-ads-video.jar --sample --music my-track.mp3   # use your own track
java -jar target/news-job-ads-video.jar --sample --no-music             # turn music off
```

### Options

| Flag | Default | Meaning |
|------|---------|---------|
| `--sample` | off | Use bundled sample data instead of scraping |
| `--sources <file>` | – | Path to a `sources.json` of sites to scrape |
| `--out <file>` | `jobs.mp4` | Output video path |
| `--brand <text>` | `Job Alerts` | Title shown in the slide header |
| `--seconds <n>` | `5` | Seconds each slide is shown (used for slides without narration; with narration each slide lasts as long as its voice-over) |
| `--fps <n>` | `25` | Video frame rate |
| `--limit <n>` | none | Cap the number of jobs |
| `--evidence <dir>` | `<out>/evidence` | Where to save proof-of-source evidence |
| `--no-evidence` | off | Skip capturing evidence screenshots |
| `--input <path>` | – | A PDF/image file (or folder of them) to read job ads from |
| `--employment-news` | off | Also pull ads from the official Employment News "Web Advertisement" page (downloads & OCRs the free notification PDFs) |
| `--en-limit <n>` | `12` | Max Employment News PDFs to OCR per run |
| `--source-name <text>` | – | Source label for imported jobs (e.g. the newspaper name); if omitted, no source is shown for imported files |
| `--no-audio` | off | Skip the spoken narration (otherwise on when a TTS engine is available) |
| `--music <file>` | synthesised bed | Background-music file looped quietly under the video |
| `--no-music` | off | Disable background music |
| `--disclaimer <text>` | built-in default | Disclaimer card shown & narrated right after the intro |
| `--no-disclaimer` | off | Omit the disclaimer card |

## Project layout

```
src/main/java/com/jobads/
  App.java                  # entry point – wires the pipeline
  model/JobPosting.java     # plain job facts (no images)
  scraper/JobScraper.java   # jsoup-based scraping
  scraper/SiteConfig.java   # per-site selector config
  filter/JobFilter.java     # keep job ads, drop noise
  scraper/EvidenceCapture.java # screenshot the real ads as proof (headless Chrome)
  input/DocumentImporter.java # read PDF/image job ads (pdftotext + tesseract OCR)
  image/SlideGenerator.java # render a clean slide per job (Java2D)
  audio/Narrator.java       # spoken voice-over per job (offline TTS)
  audio/BackgroundMusic.java # synthesise a royalty-free music bed (ffmpeg)
  video/VideoBuilder.java   # combine slides into mp4 via ffmpeg (+ mux narration + music)
  model/EvidenceItem.java   # one manifest entry of captured proof
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

`ffmpeg` (and Chrome/Chromium, if you want evidence capture) must be available in the runtime — e.g.
as Lambda layers or baked into the container image.
