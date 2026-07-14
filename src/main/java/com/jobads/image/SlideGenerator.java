package com.jobads.image;

import com.jobads.model.JobPosting;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders one clean, branded slide image per job using Java2D. The slide contains only the textual
 * facts of the job, so the resulting video never reproduces copyrighted newspaper artwork.
 */
public class SlideGenerator {

    public static final int WIDTH = 1280;
    public static final int HEIGHT = 720;

    // Palette
    private static final Color BG_TOP = new Color(0x0B1220);
    private static final Color BG_BOTTOM = new Color(0x172A44);
    private static final Color HEADER_TOP = new Color(0x1E6FE0);
    private static final Color HEADER_BOTTOM = new Color(0x0D3E8F);
    private static final Color PANEL_FILL = new Color(255, 255, 255, 16);
    private static final Color PANEL_STROKE = new Color(255, 255, 255, 30);
    private static final Color ACCENT = new Color(0xFFC107);
    private static final Color TEXT = new Color(0xFFFFFF);
    private static final Color MUTED = new Color(0x9FB3C8);

    private static final int MARGIN = 60;
    private static final int HEADER_H = 110;

    private final String brand;
    private String dateText = "";

    public SlideGenerator(String brand) {
        this.brand = brand == null ? "RightRoads Job News" : brand;
    }

    /** Optional date label shown on the intro / thumbnail (e.g. "10 July 2026"). */
    public void setDateText(String dateText) {
        this.dateText = dateText == null ? "" : dateText;
    }

    /**
     * Render an intro / title card for the start of the video.
     */
    public Path renderIntro(String title, String subtitle, Path outDir, String fileName)
            throws IOException {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = setup(img);
        paintBackground(g);

        // Accent bars framing the title.
        g.setColor(ACCENT);
        g.fillRect(MARGIN, HEIGHT / 2 - 96, 90, 8);

        g.setColor(TEXT);
        g.setFont(new Font("SansSerif", Font.BOLD, 74));
        g.drawString(title, MARGIN, HEIGHT / 2 - 20);

        g.setColor(ACCENT);
        g.setFont(new Font("SansSerif", Font.BOLD, 40));
        g.drawString(subtitle, MARGIN, HEIGHT / 2 + 48);

        if (!dateText.isBlank()) {
            g.setColor(MUTED);
            g.setFont(new Font("SansSerif", Font.PLAIN, 30));
            g.drawString(dateText, MARGIN, HEIGHT / 2 + 108);
        }

        g.dispose();
        return write(img, outDir, fileName);
    }

    /**
     * Render a disclaimer card (shown right after the intro). The body text is wrapped to fit.
     */
    public Path renderDisclaimer(String body, Path outDir, String fileName) throws IOException {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = setup(img);
        paintBackground(g);
        paintHeader(g, null);
        paintPanel(g);

        g.setColor(ACCENT);
        g.setFont(new Font("SansSerif", Font.BOLD, 48));
        g.drawString("Disclaimer", MARGIN + 30, 210);

        g.setColor(TEXT);
        Font bodyFont = new Font("SansSerif", Font.PLAIN, 34);
        drawWrapped(g, body, MARGIN + 30, 270, WIDTH - 2 * (MARGIN + 30), bodyFont);

        g.dispose();
        return write(img, outDir, fileName);
    }

    /**
     * Render a single job slide.
     */
    public Path renderJob(JobPosting job, int index, int total, Path outDir, String fileName)
            throws IOException {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = setup(img);
        paintBackground(g);
        paintHeader(g, "Job " + index + " / " + total);
        paintPanel(g);

        int left = MARGIN + 30;

        // Title (wrapped)
        int y = 175;
        g.setColor(ACCENT);
        Font titleFont = new Font("SansSerif", Font.BOLD, 42);
        y = drawWrapped(g, nonEmpty(job.getTitle(), "Job Vacancy"), left, y,
                WIDTH - 2 * left, titleFont);

        y += 34;
        // Detail rows
        y = detail(g, "Company", job.getCompany(), y);
        y = detail(g, "Location", job.getLocation(), y);
        y = detail(g, "Qualification", job.getQualification(), y);
        y = detail(g, "Salary", job.getSalary(), y);
        y = detail(g, "Last Date", job.getLastDate(), y);

        // How-to-apply block. The narration always tells the viewer how to apply
        // (JobPosting#getApplyInfo), so the slide must too — show whatever the source
        // actually provides, falling back to the explicit instruction when it has no
        // scraped contact/link (otherwise these rows silently disappear).
        boolean shownApply = false;
        if (job.getContact() != null && !job.getContact().isBlank()) {
            y = detail(g, "Contact", job.getContact(), y);
            shownApply = true;
        }
        if (job.getLink() != null && !job.getLink().isBlank()) {
            y = detail(g, "Apply Link", job.getLink(), y, ACCENT);
            shownApply = true;
        }
        if (job.getApplyHow() != null && !job.getApplyHow().isBlank()) {
            y = detail(g, "How to Apply", job.getApplyHow(), y, ACCENT);
        } else if (!shownApply) {
            y = detail(g, "How to Apply", job.getApplyInfo(), y, ACCENT);
        }

        // Footer / source attribution
        g.setColor(MUTED);
        g.setFont(new Font("SansSerif", Font.ITALIC, 22));
        String src = "Source: " + nonEmpty(job.getSource(), "newspaper listing");
        g.drawString(src, left, HEIGHT - 44);

        g.dispose();
        return write(img, outDir, fileName);
    }

    /**
     * Render a closing "subscribe" call-to-action card for the end of the video.
     */
    public Path renderOutro(Path outDir, String fileName) throws IOException {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = setup(img);
        paintBackground(g);

        g.setColor(TEXT);
        g.setFont(new Font("SansSerif", Font.BOLD, 66));
        drawCentered(g, "Subscribe for daily", HEIGHT / 2 - 70);
        drawCentered(g, "job updates", HEIGHT / 2 + 10);

        // Subscribe pill
        int pillW = 360;
        int pillH = 76;
        int px = (WIDTH - pillW) / 2;
        int py = HEIGHT / 2 + 70;
        g.setColor(new Color(0xE53935));
        g.fillRoundRect(px, py, pillW, pillH, 38, 38);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 34));
        drawCentered(g, "SUBSCRIBE", py + 50);

        g.setColor(MUTED);
        g.setFont(new Font("SansSerif", Font.PLAIN, 28));
        drawCentered(g, "Like • Share • " + brand, HEIGHT - 90);

        g.dispose();
        return write(img, outDir, fileName);
    }

    /**
     * Render a YouTube thumbnail (1280x720): a bold job count + date, high-contrast for feeds.
     */
    public Path renderThumbnail(int jobCount, Path outDir, String fileName) throws IOException {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = setup(img);
        paintBackground(g);

        // Left accent block
        g.setColor(ACCENT);
        g.fillRect(0, 0, 24, HEIGHT);

        g.setColor(TEXT);
        g.setFont(new Font("SansSerif", Font.BOLD, 150));
        g.drawString(jobCount + " JOBS", MARGIN, 250);

        g.setColor(ACCENT);
        g.setFont(new Font("SansSerif", Font.BOLD, 84));
        g.drawString("Latest Openings", MARGIN, 360);

        if (!dateText.isBlank()) {
            g.setColor(TEXT);
            g.setFont(new Font("SansSerif", Font.BOLD, 48));
            g.drawString(dateText, MARGIN, 450);
        }

        // Brand strip at the bottom
        g.setColor(HEADER_BOTTOM);
        g.fillRect(0, HEIGHT - 110, WIDTH, 110);
        g.setColor(TEXT);
        g.setFont(new Font("SansSerif", Font.BOLD, 46));
        g.drawString(brand, MARGIN, HEIGHT - 42);

        g.dispose();
        return write(img, outDir, fileName);
    }

    private int detail(Graphics2D g, String label, String value, int y) {
        return detail(g, label, value, y, MUTED);
    }

    private int detail(Graphics2D g, String label, String value, int y, Color labelColor) {
        if (value == null || value.isBlank()) {
            return y;
        }
        int left = MARGIN + 30;
        int valueX = left + 250;
        int valueMax = WIDTH - valueX - MARGIN - 10;
        int lineH = 32;

        Font labelFont = new Font("SansSerif", Font.BOLD, 24);
        Font valueFont = new Font("SansSerif", Font.PLAIN, 26);
        List<String> lines = wrapLines(g, value, valueFont, valueMax);

        // Label and the first value line share a baseline; extra lines stack below.
        g.setFont(labelFont);
        g.setColor(labelColor);
        g.drawString(label + ":", left, y);

        g.setFont(valueFont);
        g.setColor(TEXT);
        for (int k = 0; k < lines.size(); k++) {
            g.drawString(lines.get(k), valueX, y + k * lineH);
        }
        int rows = Math.max(1, lines.size());
        return y + (rows - 1) * lineH + 38;
    }

    /** Greedy word-wrap of {@code text} to {@code maxWidth} using the given font's metrics. */
    private static List<String> wrapLines(Graphics2D g, String text, Font font, int maxWidth) {
        java.awt.FontMetrics fm = g.getFontMetrics(font);
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : text.trim().split("\\s+")) {
            String trial = cur.length() == 0 ? word : cur + " " + word;
            if (fm.stringWidth(trial) > maxWidth && cur.length() > 0) {
                lines.add(cur.toString());
                cur = new StringBuilder(word);
            } else {
                cur = new StringBuilder(trial);
            }
        }
        if (cur.length() > 0) {
            lines.add(cur.toString());
        }
        return lines;
    }

    private static Graphics2D setup(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
    }

    private static void paintBackground(Graphics2D g) {
        g.setPaint(new GradientPaint(0, 0, BG_TOP, 0, HEIGHT, BG_BOTTOM));
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setPaint(TEXT);
    }

    private void paintHeader(Graphics2D g, String badge) {
        g.setPaint(new GradientPaint(0, 0, HEADER_TOP, 0, HEADER_H, HEADER_BOTTOM));
        g.fillRect(0, 0, WIDTH, HEADER_H);
        g.setColor(ACCENT);
        g.fillRect(0, HEADER_H, WIDTH, 5);

        g.setColor(TEXT);
        g.setFont(new Font("SansSerif", Font.BOLD, 40));
        g.drawString(brand, MARGIN, 70);

        if (badge != null && !badge.isBlank()) {
            g.setFont(new Font("SansSerif", Font.BOLD, 26));
            int bw = g.getFontMetrics().stringWidth(badge);
            int pad = 20;
            int pillW = bw + pad * 2;
            int pillH = 44;
            int px = WIDTH - MARGIN - pillW;
            int py = (HEADER_H - pillH) / 2;
            g.setColor(new Color(0, 0, 0, 60));
            g.fillRoundRect(px, py, pillW, pillH, 22, 22);
            g.setColor(ACCENT);
            g.drawString(badge, px + pad, py + 31);
        }
    }

    private static void paintPanel(Graphics2D g) {
        int top = HEADER_H + 25;
        g.setColor(PANEL_FILL);
        g.fillRoundRect(MARGIN, top, WIDTH - 2 * MARGIN, HEIGHT - top - 30, 28, 28);
        g.setStroke(new BasicStroke(2f));
        g.setColor(PANEL_STROKE);
        g.drawRoundRect(MARGIN, top, WIDTH - 2 * MARGIN, HEIGHT - top - 30, 28, 28);
    }

    private static void drawCentered(Graphics2D g, String text, int y) {
        if (text == null) {
            return;
        }
        int w = g.getFontMetrics().stringWidth(text);
        g.drawString(text, (WIDTH - w) / 2, y);
    }

    /**
     * Draw text wrapped to {@code maxWidth}. Returns the y coordinate of the baseline of the last
     * line drawn.
     */
    private static int drawWrapped(Graphics2D g, String text, int x, int y, int maxWidth, Font font) {
        if (text == null || text.isBlank()) {
            return y;
        }
        g.setFont(font);
        AttributedString attr = new AttributedString(text);
        attr.addAttribute(TextAttribute.FONT, font);
        AttributedCharacterIterator it = attr.getIterator();
        FontRenderContext frc = g.getFontRenderContext();
        LineBreakMeasurer measurer = new LineBreakMeasurer(it, frc);

        float drawY = y;
        while (measurer.getPosition() < it.getEndIndex()) {
            TextLayout layout = measurer.nextLayout(maxWidth);
            drawY += layout.getAscent();
            layout.draw(g, x, drawY);
            drawY += layout.getDescent() + layout.getLeading();
        }
        return (int) drawY;
    }

    private static Path write(BufferedImage img, Path outDir, String fileName) throws IOException {
        File out = outDir.resolve(fileName).toFile();
        ImageIO.write(img, "png", out);
        return out.toPath();
    }

    private static String nonEmpty(String value, String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    /** Convenience: render the whole set (intro + one slide per job) and return the file paths. */
    public List<Path> renderAll(List<JobPosting> jobs, Path outDir, String title, String subtitle)
            throws IOException {
        List<Path> slides = new ArrayList<>();
        slides.add(renderIntro(title, subtitle, outDir, "slide-000.png"));
        for (int i = 0; i < jobs.size(); i++) {
            String name = String.format("slide-%03d.png", i + 1);
            slides.add(renderJob(jobs.get(i), i + 1, jobs.size(), outDir, name));
        }
        return slides;
    }
}
