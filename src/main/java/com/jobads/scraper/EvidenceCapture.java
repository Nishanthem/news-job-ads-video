package com.jobads.scraper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobads.model.EvidenceItem;
import org.openqa.selenium.By;
import org.openqa.selenium.OutputType;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.chrome.ChromeDriver;
import org.openqa.selenium.chrome.ChromeOptions;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Captures proof-of-source evidence for scraped ads using a headless Chrome browser.
 *
 * <p>For every ad found on a page it saves:
 * <ul>
 *   <li>a screenshot of the actual ad element as it appears on the source site,</li>
 *   <li>the original ad image file, when the ad itself is an image (e.g. e-paper clippings),</li>
 * </ul>
 * plus a full-page screenshot of the source page for context. A {@code manifest.json} ties each
 * piece of evidence to its source URL and a capture timestamp. None of this is shown in the video —
 * it exists only so the original ad can be produced if its authenticity is ever questioned.
 */
public class EvidenceCapture {

    private final ObjectMapper mapper = new ObjectMapper();
    private final String chromeBinary;

    public EvidenceCapture() {
        // Allow overriding the Chrome binary location for environments where it is not auto-detected.
        String fromProp = System.getProperty("chrome.binary");
        String fromEnv = System.getenv("CHROME_BINARY");
        this.chromeBinary = fromProp != null ? fromProp : fromEnv;
    }

    /**
     * Capture evidence for every configured site into {@code evidenceDir}. Returns the manifest of
     * captured items (also written to {@code evidenceDir/manifest.json}).
     */
    public List<EvidenceItem> captureAll(List<SiteConfig> sites, Path evidenceDir, int maxPerSite)
            throws IOException {
        Files.createDirectories(evidenceDir);
        List<EvidenceItem> manifest = new ArrayList<>();

        ChromeDriver driver = newDriver();
        try {
            int siteIndex = 0;
            for (SiteConfig site : sites) {
                siteIndex++;
                try {
                    manifest.addAll(captureSite(driver, site, siteIndex, evidenceDir, maxPerSite));
                } catch (RuntimeException e) {
                    System.err.println("[evidence] failed for " + site.getUrl() + ": " + e.getMessage());
                }
            }
        } finally {
            driver.quit();
        }

        Path manifestFile = evidenceDir.resolve("manifest.json");
        mapper.writerWithDefaultPrettyPrinter().writeValue(manifestFile.toFile(), manifest);
        System.out.println("[evidence] wrote " + manifest.size() + " item(s) + manifest to " + evidenceDir);
        return manifest;
    }

    private List<EvidenceItem> captureSite(ChromeDriver driver, SiteConfig site, int siteIndex,
                                           Path evidenceDir, int maxPerSite) {
        List<EvidenceItem> items = new ArrayList<>();
        driver.get(site.getUrl());

        // Full-page screenshot of the source page for context.
        try {
            String b64 = (String) ((Map<?, ?>) driver.executeCdpCommand(
                    "Page.captureScreenshot", Map.of("format", "png", "captureBeyondViewport", true)))
                    .get("data");
            byte[] png = Base64.getDecoder().decode(b64);
            Files.write(evidenceDir.resolve(String.format("page-%02d.png", siteIndex)), png);
        } catch (Exception e) {
            // Fall back to a viewport screenshot if CDP is unavailable.
            try {
                byte[] png = driver.getScreenshotAs(OutputType.BYTES);
                Files.write(evidenceDir.resolve(String.format("page-%02d.png", siteIndex)), png);
            } catch (IOException ignored) {
                // best effort
            }
        }

        List<WebElement> ads = driver.findElements(By.cssSelector(site.getItemSelector()));
        int adIndex = 0;
        for (WebElement ad : ads) {
            if (maxPerSite > 0 && items.size() >= maxPerSite) {
                break;
            }
            adIndex++;
            String title = childText(ad, site.getTitleSelector());
            if (title.isBlank()) {
                continue;
            }
            EvidenceItem item = new EvidenceItem();
            item.setTitle(title);
            item.setSource(site.getName());
            item.setPageUrl(site.getUrl());
            item.setAdLink(childLink(ad, site.getLinkSelector()));
            item.setCapturedAt(Instant.now().toString());

            // 1) Screenshot of the actual ad element.
            try {
                ((org.openqa.selenium.JavascriptExecutor) driver)
                        .executeScript("arguments[0].scrollIntoView({block:'center'});", ad);
                byte[] shot = ad.getScreenshotAs(OutputType.BYTES);
                String name = String.format("ad-%02d-%03d.png", siteIndex, adIndex);
                Files.write(evidenceDir.resolve(name), shot);
                item.setScreenshotFile(name);
            } catch (Exception e) {
                System.err.println("[evidence] screenshot failed for '" + title + "': " + e.getMessage());
            }

            // 2) Original ad image, if the ad is/contains an image (e.g. e-paper clipping).
            String imgSrc = firstImageSrc(ad);
            if (!imgSrc.isBlank()) {
                String name = String.format("ad-%02d-%03d-original%s", siteIndex, adIndex,
                        extensionOf(imgSrc));
                if (downloadImage(imgSrc, evidenceDir.resolve(name))) {
                    item.setOriginalImageFile(name);
                }
            }

            items.add(item);
        }
        System.out.println("[evidence] " + site.getName() + ": captured " + items.size() + " ad(s)");
        return items;
    }

    private ChromeDriver newDriver() {
        ChromeOptions options = new ChromeOptions();
        if (chromeBinary != null && !chromeBinary.isBlank()) {
            options.setBinary(chromeBinary);
        }
        options.addArguments(
                "--headless=new",
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-gpu",
                "--hide-scrollbars",
                "--window-size=1366,2200");
        ChromeDriver driver = new ChromeDriver(options);
        driver.manage().timeouts().pageLoadTimeout(Duration.ofSeconds(30));
        driver.manage().timeouts().implicitlyWait(Duration.ofSeconds(5));
        return driver;
    }

    private static String childText(WebElement parent, String selector) {
        if (selector == null || selector.isBlank()) {
            return parent.getText();
        }
        List<WebElement> els = parent.findElements(By.cssSelector(selector));
        return els.isEmpty() ? "" : els.get(0).getText().trim();
    }

    private static String childLink(WebElement parent, String selector) {
        try {
            WebElement el = (selector == null || selector.isBlank())
                    ? parent : parent.findElement(By.cssSelector(selector));
            String href = el.getAttribute("href");
            return href == null ? "" : href;
        } catch (RuntimeException e) {
            return "";
        }
    }

    private static String firstImageSrc(WebElement parent) {
        List<WebElement> imgs = parent.findElements(By.tagName("img"));
        if (imgs.isEmpty()) {
            return "";
        }
        String src = imgs.get(0).getAttribute("src");
        return src == null ? "" : src;
    }

    private boolean downloadImage(String url, Path dest) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0 (compatible; JobAdsBot/1.0)")
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() == 200 && resp.body().length > 0) {
                Files.write(dest, resp.body());
                return true;
            }
        } catch (Exception e) {
            System.err.println("[evidence] image download failed " + url + ": " + e.getMessage());
        }
        return false;
    }

    private static String extensionOf(String url) {
        int q = url.indexOf('?');
        String clean = q >= 0 ? url.substring(0, q) : url;
        int dot = clean.lastIndexOf('.');
        int slash = clean.lastIndexOf('/');
        if (dot > slash && dot >= 0 && clean.length() - dot <= 5) {
            return clean.substring(dot);
        }
        return ".png";
    }
}
