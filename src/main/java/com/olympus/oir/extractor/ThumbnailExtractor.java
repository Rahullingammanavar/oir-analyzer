package com.olympus.oir.extractor;

import com.olympus.oir.model.ParsedOirFile;

import javafx.embed.swing.SwingFXUtils;
import javafx.scene.image.Image;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.logging.Logger;

/**
 * Extracts and decodes the 24-bit BMP thumbnail from a parsed OIR file.
 *
 * Per spec (section 6.1.2.4 — THUMBNAIL_METAINFO):
 *   - Format string is always "BMP" in current OIR versions
 *   - Image data is a standard Windows BMP file binary
 *
 * Uses javax.imageio (built into JDK) to decode BMP.
 * Converts to JavaFX Image via SwingFXUtils.
 */
public class ThumbnailExtractor {

    private static final Logger LOG = Logger.getLogger(ThumbnailExtractor.class.getName());

    /**
     * Decode the thumbnail bytes stored in a {@link ParsedOirFile} into a JavaFX {@link Image}.
     *
     * @param parsedFile the parsed OIR file containing thumbnail bytes
     * @return a JavaFX Image, or null if no thumbnail is available or decoding fails
     */
    public Image extractToFxImage(ParsedOirFile parsedFile) {
        byte[] thumbBytes = parsedFile.getThumbnailBytes();

        if (thumbBytes == null || thumbBytes.length == 0) {
            LOG.warning("No thumbnail bytes available in parsed file.");
            return null;
        }

        String fmt = parsedFile.getThumbnailFormat();
        LOG.info("Decoding thumbnail: format=" + fmt + ", size=" + thumbBytes.length + " bytes");

        try {
            BufferedImage bimg = decodeImage(thumbBytes, fmt);
            if (bimg == null) {
                LOG.warning("ImageIO returned null — unrecognised format or corrupt data.");
                return null;
            }
            return SwingFXUtils.toFXImage(bimg, null);
        } catch (IOException ex) {
            LOG.severe("Failed to decode thumbnail: " + ex.getMessage());
            return null;
        }
    }

    /**
     * Decode thumbnail bytes into a {@link BufferedImage}.
     * Supports BMP (and any format registered with javax.imageio).
     */
    public BufferedImage decodeImage(byte[] imageBytes, String format) throws IOException {
        try (InputStream is = new ByteArrayInputStream(imageBytes)) {
            BufferedImage img = ImageIO.read(is);
            if (img != null) return img;
        }

        // Fallback: if format hints at BMP but ImageIO.read() failed on the raw bytes,
        // try wrapping with a BMP header check
        LOG.warning("Standard ImageIO read failed. Attempting raw read...");
        try (InputStream is = new ByteArrayInputStream(imageBytes)) {
            BufferedImage img = ImageIO.read(new javax.imageio.stream.MemoryCacheImageInputStream(is));
            return img;
        }
    }

    /**
     * Save the raw thumbnail bytes to a file.
     *
     * @param parsedFile  source parsed OIR file
     * @param outputFile  destination file (e.g., thumbnail.bmp)
     * @throws IOException on I/O error
     */
    public void saveToFile(ParsedOirFile parsedFile, File outputFile) throws IOException {
        byte[] thumbBytes = parsedFile.getThumbnailBytes();
        if (thumbBytes == null || thumbBytes.length == 0) {
            throw new IOException("No thumbnail data to save.");
        }
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            fos.write(thumbBytes);
        }
        LOG.info("Thumbnail saved to: " + outputFile.getAbsolutePath());
    }

    /**
     * Get a summary string describing the thumbnail.
     */
    public String getSummary(ParsedOirFile parsedFile) {
        byte[] tb = parsedFile.getThumbnailBytes();
        if (tb == null) return "No thumbnail";
        return String.format("Format: %s | Size: %d bytes (%.1f KB)",
                parsedFile.getThumbnailFormat(),
                tb.length,
                tb.length / 1024.0);
    }
}
