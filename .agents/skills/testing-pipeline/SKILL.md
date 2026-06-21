---
name: testing-news-job-ads-video
description: Test the news-job-ads-video CLI pipeline end-to-end. Use when verifying scraping, filtering, video generation, narration, or background music changes.
---

# Testing the news-job-ads-video Pipeline

## Prerequisites
- Java 17 (OpenJDK)
- Maven 3.x
- FFmpeg (4.x+)
- Piper TTS with `en_IN-spicor-medium` model (optional — for narration tests)

## Build
```bash
cd /home/ubuntu/repos/news-job-ads-video
mvn clean package -q
```

## Quick Smoke Test (no network)
```bash
java -jar target/news-job-ads-video.jar --sample --no-audio --no-evidence --limit 3 --out test.mp4
```
Expect: exit 0, `test.mp4` exists, console shows `DONE`.

## Testing Video with Narration
```bash
java -jar target/news-job-ads-video.jar --sample --limit 3 --no-evidence --out narrated.mp4
```
Expect: 2 streams (video h264 + audio aac). Verify with:
```bash
ffprobe -v error -show_entries stream=codec_type,codec_name narrated.mp4
```

## Testing Background Music
Three modes to test:

### Auto-generated BGM
```bash
java -jar target/news-job-ads-video.jar --sample --bgm-default --no-audio --limit 2 --no-evidence --out bgm.mp4
```
Expect: 2 streams, console shows `generating default background music...` and `background music enabled`.

### Custom BGM file
```bash
ffmpeg -y -f lavfi -i "sine=frequency=440:duration=1" -c:a libmp3lame /tmp/custom-bgm.mp3
java -jar target/news-job-ads-video.jar --sample --bgm /tmp/custom-bgm.mp3 --no-audio --limit 2 --no-evidence --out custom.mp4
```
Expect: 2 streams, console does NOT show `generating default`.

### BGM + Narration mixed
```bash
java -jar target/news-job-ads-video.jar --sample --bgm-default --limit 3 --no-evidence --out mixed.mp4
```
Expect: 2 streams, file size larger than narration-only video (BGM adds audio data).

### Volume control
Compare `--bgm-volume 0.8` vs `--bgm-volume 0.05` — louder version should produce a larger file.

### Bad BGM path (graceful fallback)
```bash
java -jar target/news-job-ads-video.jar --sample --bgm /tmp/nonexistent.mp3 --no-audio --limit 2 --no-evidence --out fallback.mp4
```
Expect: exit 0, console shows `WARNING: --bgm file not found`, output has 1 stream (no audio).

## Testing Live Scraping
```bash
java -jar target/news-job-ads-video.jar --sources src/main/resources/sources.json --no-audio --no-evidence --limit 5 --out scrape.mp4
```
Expect: console shows item counts from all 5 sources (Employment News, FreeJobAlert, SarkariResult, Times Ascent, Mathrubhumi). Total after filtering should be >100.

## Key Assertions via ffprobe
```bash
# Stream count (1=video-only, 2=video+audio)
ffprobe -v error -show_entries format=nb_streams -of default=nokey=1:noprint_wrappers=1 output.mp4

# Stream details
ffprobe -v error -show_entries stream=codec_type,codec_name output.mp4

# Duration
ffprobe -v error -show_entries format=duration -of default=nokey=1:noprint_wrappers=1 output.mp4

# File size comparison (useful for volume tests)
stat -c%s output.mp4
```

## Notes
- This is a CLI app — all testing is shell-based, no browser/GUI needed
- Use `--sample` for offline testing (uses bundled sample-jobs.json)
- Use `--no-evidence` to skip Selenium/headless Chrome evidence capture
- Use `--no-audio` to skip TTS narration (faster tests)
- Live scraping tests hit real websites — results may vary slightly over time
- The `--bgm-default` flag generates ambient music via ffmpeg's `sine` lavfi source — no external files needed
