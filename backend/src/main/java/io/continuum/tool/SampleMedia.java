package io.continuum.tool;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Base64;

/**
 * Real sample files, generated in process, for probing a newly added tool.
 *
 * <p>Continuum used to have no sample for audio or documents, so an audio tool
 * could not be probed at all and sat in DRAFT until the developer went and found
 * a clip. The reasoning behind that was right — the alternative at the time was
 * sending {@code {"audioBase64": ""}}, and a probe that proves nothing is worse
 * than no probe, because the tool ends up marked READY on the strength of a
 * request no real provider could have processed.
 *
 * <p>What changed is that these are <b>genuine files</b>, not empty strings: a
 * well-formed WAV containing an actual tone, a PNG with actual text drawn on it,
 * a PDF with a real text layer. A speech provider can decode the WAV; an OCR
 * provider can read the PNG. So the probe exercises the real path — credential,
 * URL, request shape, response parsing — which is exactly what a probe is for.
 *
 * <p>What a probe still cannot prove is that the tool is <em>good</em>. A
 * transcriber handed a pure tone will correctly report no speech, and that is a
 * successful probe: the shape was learned. It is not a claim that the provider
 * transcribes well.
 */
public final class SampleMedia {

    private SampleMedia() {
    }

    /** Text drawn on the image and written into the PDF, for a legible probe. */
    private static final String PROBE_TEXT = "CONTINUUM PROBE 12345";

    private static volatile String cachedWav;
    private static volatile String cachedPng;
    private static volatile String cachedPdf;

    /**
     * One second of a quiet 440Hz tone: 8kHz, mono, 16-bit PCM.
     *
     * <p>A tone rather than silence because some providers reject a file with no
     * signal in it as corrupt, and a probe that fails for that reason would send
     * a developer looking for a problem with their credential.
     */
    public static String wavBase64() {
        String c = cachedWav;
        if (c != null) {
            return c;
        }
        int rate = 8000;
        int samples = rate;
        int dataBytes = samples * 2;

        ByteBuffer buf = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes());
        buf.putInt(36 + dataBytes);
        buf.put("WAVE".getBytes());
        buf.put("fmt ".getBytes());
        buf.putInt(16);            // PCM header size
        buf.putShort((short) 1);   // PCM, uncompressed
        buf.putShort((short) 1);   // mono
        buf.putInt(rate);
        buf.putInt(rate * 2);      // byte rate
        buf.putShort((short) 2);   // block align
        buf.putShort((short) 16);  // bits per sample
        buf.put("data".getBytes());
        buf.putInt(dataBytes);

        for (int i = 0; i < samples; i++) {
            // Low amplitude: audible to a decoder, not unpleasant if played.
            double v = Math.sin(2 * Math.PI * 440 * i / rate) * 6000;
            buf.putShort((short) v);
        }

        cachedWav = Base64.getEncoder().encodeToString(buf.array());
        return cachedWav;
    }

    /**
     * A PNG with legible text on it, so an OCR probe has something to read.
     *
     * <p>The 1x1 pixel Continuum used for every image tool is fine for a
     * detector — there is genuinely nothing in it to detect — but it makes an
     * OCR probe meaningless, and some services reject it outright.
     */
    public static String pngWithTextBase64() {
        String c = cachedPng;
        if (c != null) {
            return c;
        }
        BufferedImage img = new BufferedImage(520, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, 520, 120);
            g.setColor(Color.BLACK);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 40));
            g.drawString(PROBE_TEXT, 24, 72);
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            cachedPng = Base64.getEncoder().encodeToString(out.toByteArray());
            return cachedPng;
        } catch (Exception e) {
            // Headless environments without an image writer fall back to the
            // pixel: a worse probe, but better than failing to probe at all.
            return null;
        }
    }

    /** A one-page PDF with a real text layer. */
    public static String pdfWithTextBase64() {
        String c = cachedPdf;
        if (c != null) {
            return c;
        }
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD), 24);
                cs.newLineAtOffset(60, 700);
                cs.showText(PROBE_TEXT);
                cs.endText();
            }
            doc.save(out);
            cachedPdf = Base64.getEncoder().encodeToString(out.toByteArray());
            return cachedPdf;
        } catch (Exception e) {
            return null;
        }
    }

    /** The text a working OCR or extraction tool should find in the samples. */
    public static String probeText() {
        return PROBE_TEXT;
    }
}
