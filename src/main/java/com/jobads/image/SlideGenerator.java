package com.jobads.image;

import com.jobads.model.JobPosting;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
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

    private static final Color BG = new Color(0x0F1B2D);
    private static final Color HEADER = new Color(0x1565C0);
    private static final Color ACCENT = new Color(0xFFC107);
    private static final Color TEXT = new Color(0xFFFFFF);
    private static final Color MUTED = new Color(0xB0BEC5);

    private final String brand;

    public SlideGenerator(String brand) {
        this.brand = brand == null ? "Job Alerts" : brand;
    }

    /**
     * Render an intro / title card for the start of the video.
     */
    public Path renderIntro(String title, String subtitle, Path outDir, String fileName)
            throws IOException {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = setup(img);

        g.setColor(BG);
        g.fillRect(0, 0, WIDTH, HEIGHT);
        g.setColor(ACCENT);
        g.fillRect(0, HEIGHT / 2 - 4, WIDTH, 8);

        g.setColor(TEXT);
        g.setFont(new Font("SansSerif", Font.BOLD, 64));
        drawCentered(g, title, HEIGHT / 2 - 60);

        g.setColor(MUTED);
        g.setFont(new Font("SansSerif", Font.PLAIN, 32));
        drawCentered(g, subtitle, HEIGHT / 2 + 70);

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

        // Background
        g.setColor(BG);
        g.fillRect(0, 0, WIDTH, HEIGHT);

        // Header bar
        g.setColor(HEADER);
        g.fillRect(0, 0, WIDTH, 110);
        g.setColor(TEXT);
        g.setFont(new Font("SansSerif", Font.BOLD, 40));
        g.drawString(brand, 50, 70);
        g.setColor(ACCENT);
        g.setFont(new Font("SansSerif", Font.BOLD, 26));
        String counter = "Job " + index + " / " + total;
        int cw = g.getFontMetrics().stringWidth(counter);
        g.drawString(counter, WIDTH - cw - 50, 68);

        // Title (wrapped)
        int y = 190;
        g.setColor(ACCENT);
        Font titleFont = new Font("SansSerif", Font.BOLD, 46);
        y = drawWrapped(g, nonEmpty(job.getTitle(), "Job Vacancy"), 60, y, WIDTH - 120, titleFont);

        y += 30;
        // Detail rows
        y = detail(g, "Company", job.getCompany(), y);
        y = detail(g, "Location", job.getLocation(), y);
        y = detail(g, "Qualification", job.getQualification(), y);
        y = detail(g, "Salary", job.getSalary(), y);
        y = detail(g, "Contact", job.getContact(), y);
        y = detail(g, "Last Date", job.getLastDate(), y);

        // Footer / source attribution
        g.setColor(MUTED);
        g.setFont(new Font("SansSerif", Font.ITALIC, 22));
        String src = "Source: " + nonEmpty(job.getSource(), "newspaper listing");
        g.drawString(src, 60, HEIGHT - 50);

        g.dispose();
        return write(img, outDir, fileName);
    }

    private int detail(Graphics2D g, String label, String value, int y) {
        if (value == null || value.isBlank()) {
            return y;
        }
        g.setColor(MUTED);
        g.setFont(new Font("SansSerif", Font.BOLD, 28));
        g.drawString(label + ":", 60, y);

        g.setColor(TEXT);
        Font valueFont = new Font("SansSerif", Font.PLAIN, 30);
        int newY = drawWrapped(g, value, 320, y, WIDTH - 380, valueFont);
        return Math.max(newY, y) + 22;
    }

    private static Graphics2D setup(BufferedImage img) {
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        return g;
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
