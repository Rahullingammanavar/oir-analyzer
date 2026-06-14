package com.olympus.oir.extractor;

import com.olympus.oir.model.*;
import com.olympus.oir.util.ByteUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.DataBufferUShort;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

/**
 * Reconstructs images for a given frame and channel by seeking to and stitching
 * their constituent fragments from the raw IMAGE_BITMAP blocks on disk.
 */
public class ImageExtractor {

    private static final Logger LOG = Logger.getLogger(ImageExtractor.class.getName());

    public static class ChannelInfo {
        public final String id;
        public final String name;
        public final int order;

        public ChannelInfo(String id, String name, int order) {
            this.id = id;
            this.name = name;
            this.order = order;
        }

        @Override
        public String toString() {
            return String.format("Channel %d (%s)", order, name);
        }
    }

    /**
     * Extracts channel definitions from IMAGE_PROPERTIES XML.
     */
    public static List<ChannelInfo> getChannels(ParsedOirFile parsedFile) {
        List<ChannelInfo> list = new ArrayList<>();
        try {
            Optional<String> xmlOpt = parsedFile.getImagePropertiesXml();
            if (xmlOpt.isPresent()) {
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                dbf.setNamespaceAware(true);
                Document doc = dbf.newDocumentBuilder().parse(
                    new ByteArrayInputStream(xmlOpt.get().getBytes(StandardCharsets.UTF_8))
                );
                NodeList channels = doc.getElementsByTagNameNS("*", "channel");
                for (int i = 0; i < channels.getLength(); i++) {
                    Element ch = (Element) channels.item(i);
                    String id = ch.getAttribute("id");
                    String detectorId = ch.getAttribute("detectorId");
                    String enableStr = ch.getAttribute("enable");
                    String orderStr = ch.getAttribute("order");

                    if ("true".equalsIgnoreCase(enableStr) && !id.isEmpty()) {
                        int order = 1;
                        try {
                            order = Integer.parseInt(orderStr);
                        } catch (NumberFormatException ignored) {}
                        list.add(new ChannelInfo(id, detectorId, order));
                    }
                }
            }
        } catch (Exception ex) {
            LOG.warning("Failed to extract channels from IMAGE_PROPERTIES XML: " + ex.getMessage());
        }

        // Sort by order
        list.sort(Comparator.comparingInt(c -> c.order));
        return list;
    }

    /**
     * Retrieves the frame list indices from Section 6 (FRAME_LOCATION).
     */
    public static List<String> getFrames(ParsedOirFile parsedFile) {
        List<String> list = new ArrayList<>();
        parsedFile.findSection(OirSection.FRAME_LOCATION).ifPresent(sec -> {
            for (Map.Entry<String, Object> entry : sec.getLoopEntries()) {
                list.add(entry.getKey());
            }
        });
        // Sort naturally if they are like t001_0_1, t002_0_1
        list.sort(Comparator.naturalOrder());
        return list;
    }

    /**
     * Extracts the image dimensions (width, height) from IMAGE_PROPERTIES XML.
     */
    public static int[] getDimensions(ParsedOirFile parsedFile) {
        int width = 512;
        int height = 512;
        try {
            Optional<String> xmlOpt = parsedFile.getImagePropertiesXml();
            if (xmlOpt.isPresent()) {
                DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
                dbf.setNamespaceAware(true);
                Document doc = dbf.newDocumentBuilder().parse(
                    new ByteArrayInputStream(xmlOpt.get().getBytes(StandardCharsets.UTF_8))
                );
                NodeList wNodes = doc.getElementsByTagNameNS("*", "width");
                if (wNodes.getLength() > 0) {
                    width = Integer.parseInt(wNodes.item(0).getTextContent().trim());
                }
                NodeList hNodes = doc.getElementsByTagNameNS("*", "height");
                if (hNodes.getLength() > 0) {
                    height = Integer.parseInt(hNodes.item(0).getTextContent().trim());
                }
            }
        } catch (Exception ex) {
            LOG.warning("Failed to parse dimensions, using 512x512 fallback: " + ex.getMessage());
        }
        return new int[]{width, height};
    }

    /**
     * Reads, stitches, and reconstructs the raw 16-bit pixel data for the specified frame and channel.
     * Returns a 16-bit short array containing the pixel intensities.
     */
    public short[] extractRawPixels(ParsedOirFile parsedFile, String frameIndex, String channelId) throws IOException {
        int[] dims = getDimensions(parsedFile);
        int width = dims[0];
        int height = dims[1];
        short[] pixels = new short[width * height];

        // 1. Get fragment count for this channel from Section 8
        int fragmentCount = 1;
        Optional<OirSection> sec8 = parsedFile.findSection(OirSection.FRAME_FRAGMENTS_PER_CHANNEL);
        if (sec8.isPresent()) {
            for (Map.Entry<String, Object> entry : sec8.get().getLoopEntries()) {
                if (entry.getKey().equals(channelId)) {
                    fragmentCount = (Integer) entry.getValue();
                    break;
                }
            }
        }

        // 2. Find block offsets of IMAGE_METAINFO for each fragment from Section 7
        int[] metaBlockNos = new int[fragmentCount];
        Optional<OirSection> sec7 = parsedFile.findSection(OirSection.FRAME_FRAGMENT_LOCATION);
        if (sec7.isPresent()) {
            for (int f = 0; f < fragmentCount; f++) {
                String key = frameIndex + "_" + channelId + "_" + f;
                boolean found = false;
                for (Map.Entry<String, Object> entry : sec7.get().getLoopEntries()) {
                    if (entry.getKey().equals(key)) {
                        metaBlockNos[f] = (Integer) entry.getValue();
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    throw new IOException("Missing fragment metadata mapping for key: " + key);
                }
            }
        } else {
            throw new IOException("FRAME_FRAGMENT_LOCATION section not found in OIR file.");
        }

        // 3. Open OIR file and read each fragment
        try (RandomAccessFile raf = new RandomAccessFile(parsedFile.getSourceFile(), "r")) {
            for (int f = 0; f < fragmentCount; f++) {
                int metaBlockNo = metaBlockNos[f];
                if (metaBlockNo < 0 || metaBlockNo >= parsedFile.getBlocks().size()) {
                    throw new IOException("Invalid block number " + metaBlockNo + " for fragment " + f);
                }

                OirBlock metaBlock = parsedFile.getBlocks().get(metaBlockNo);
                OirBlock bitmapBlock = parsedFile.getBlocks().get(metaBlockNo + 1);

                if (!metaBlock.isImageMetainfo()) {
                    throw new IOException("Block #" + metaBlockNo + " is not IMAGE_METAINFO (got: " + metaBlock.getAttribute() + ")");
                }
                if (!bitmapBlock.isImageBitmap()) {
                    throw new IOException("Block #" + (metaBlockNo + 1) + " is not IMAGE_BITMAP (got: " + bitmapBlock.getAttribute() + ")");
                }

                // Read start position in image & data size from IMAGE_METAINFO
                raf.seek(metaBlock.getDataOffset());
                int startPos = ByteUtils.readInt32(raf); // start byte offset in stitched pixel array
                int rawDataSize = ByteUtils.readInt32(raf); // fragment size in bytes

                // Seek and read from the next block (IMAGE_BITMAP)
                raf.seek(bitmapBlock.getDataOffset());
                byte[] rawBytes = new byte[rawDataSize];
                raf.readFully(rawBytes);

                // Convert bytes to shorts (little-endian) and place in reconstructed array
                ByteBuffer buffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN);
                int shortOffset = startPos / 2; // 16-bit words
                int numShorts = rawDataSize / 2;

                for (int i = 0; i < numShorts; i++) {
                    if (shortOffset + i < pixels.length) {
                        pixels[shortOffset + i] = buffer.getShort();
                    }
                }
            }
        }

        return pixels;
    }

    /**
     * Converts a 16-bit short pixel array to a standard grayscale 8-bit BufferedImage with auto-scaling/contrast.
     */
    public BufferedImage convertTo8BitGrayscale(short[] rawPixels, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_BYTE_GRAY);
        byte[] data = ((DataBufferByte) image.getRaster().getDataBuffer()).getData();

        // Find min/max values for optimal scaling/contrast enhancement
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (short p : rawPixels) {
            int val = p & 0xFFFF;
            if (val < min) min = val;
            if (val > max) max = val;
        }

        int range = max - min;
        if (range <= 0) range = 1;

        for (int i = 0; i < rawPixels.length; i++) {
            int val = rawPixels[i] & 0xFFFF;
            int scaled = (val - min) * 255 / range;
            data[i] = (byte) scaled;
        }

        return image;
    }

    /**
     * Converts raw 16-bit pixel data to a 16-bit unsigned short BufferedImage.
     */
    public BufferedImage convertTo16BitGrayscale(short[] rawPixels, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_USHORT_GRAY);
        short[] data = ((DataBufferUShort) image.getRaster().getDataBuffer()).getData();
        System.arraycopy(rawPixels, 0, data, 0, rawPixels.length);
        return image;
    }
}
