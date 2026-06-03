package com.olympus.oir.util;

import com.olympus.oir.model.BlockAttribute;
import com.olympus.oir.util.ByteUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

/**
 * Generates a minimal, structurally valid OIR file for testing purposes.
 *
 * The generated file (version 1.5) contains:
 *   - Header Range (80 bytes, v1.x)
 *   - IMAGESET_METAINFO block (with FILE_INFORMATION + IMAGE_PROPERTIES sections)
 *   - THUMBNAIL_METAINFO block (with a tiny 1×1 white BMP)
 *   - RESOURCE_METAINFO block (with dummy FrameProperties XML)
 *   - IMAGE_METAINFO block    (fragment descriptor)
 *   - IMAGE_BITMAP block      (1×1 pixel = 2 bytes)
 *   - COMMIT_LINE block       (zero data)
 *   - Index Range             (sentinel + 6 block offsets)
 */
public class MockOirGenerator {

    private static final Logger LOG = Logger.getLogger(MockOirGenerator.class.getName());

    // OIR version constants
    private static final int VERSION_MAJOR = 1;
    private static final int VERSION_MINOR = 5;

    // A minimal 8×8 white BMP (24-bit) for the thumbnail
    // Real BMP header + pixel data
    private static final byte[] MINI_BMP = createMini8x8Bmp();

    /**
     * Generate a mock OIR file at the specified path.
     *
     * @param outputFile destination path (e.g., "test.oir")
     * @throws IOException on write errors
     */
    public void generate(File outputFile) throws IOException {
        LOG.info("Generating mock OIR file: " + outputFile.getAbsolutePath());

        // ── Build each block's data ──────────────────────────────────────
        byte[] imagesetData  = buildImagesetData();
        byte[] thumbnailData = buildThumbnailData();
        byte[] resourceData  = buildResourceData();
        byte[] imgMetaData   = buildImageMetaData();
        byte[] imgBitmapData = buildBitmapData();
        // COMMIT_LINE has zero data

        // ── Wrap each data payload in a block ─────────────────────────────
        byte[] imagesetBlock  = wrapBlock(BlockAttribute.IMAGESET_METAINFO,  imagesetData);
        byte[] thumbnailBlock = wrapBlock(BlockAttribute.THUMBNAIL_METAINFO, thumbnailData);
        byte[] resourceBlock  = wrapBlock(BlockAttribute.RESOURCE_METAINFO,  resourceData);
        byte[] imgMetaBlock   = wrapBlock(BlockAttribute.IMAGE_METAINFO,     imgMetaData);
        byte[] imgBitmapBlock = wrapBlock(BlockAttribute.IMAGE_BITMAP,       imgBitmapData);
        byte[] commitBlock    = wrapBlock(BlockAttribute.COMMIT_LINE,        new byte[0]);

        byte[][] allBlocks = {
            imagesetBlock, thumbnailBlock, resourceBlock,
            imgMetaBlock, imgBitmapBlock, commitBlock
        };
        int totalBlocks = allBlocks.length;

        // ── Calculate offsets ─────────────────────────────────────────────
        int headerSize = 80; // v1.x
        long[] blockOffsets = new long[totalBlocks];
        long cursor = headerSize;

        for (int i = 0; i < totalBlocks; i++) {
            blockOffsets[i] = cursor;
            cursor += allBlocks[i].length;
        }

        long indexRangeOffset = cursor;
        long thumbnailOffset  = blockOffsets[1]; // thumbnail is block #1

        // Index Range size = 4 (sentinel) + totalBlocks × 8
        long fileSize = indexRangeOffset + 4 + (totalBlocks * 8L);

        // ── Build header ──────────────────────────────────────────────────
        byte[] header = buildHeader(fileSize, indexRangeOffset, totalBlocks,
                                    thumbnailOffset, headerSize);

        // ── Build Index Range ─────────────────────────────────────────────
        byte[] indexRange = buildIndexRange(blockOffsets);

        // ── Write file ────────────────────────────────────────────────────
        try (FileOutputStream fos = new FileOutputStream(outputFile)) {
            fos.write(header);
            for (byte[] block : allBlocks) {
                fos.write(block);
            }
            fos.write(indexRange);
        }

        LOG.info(String.format(
            "Mock OIR written: %d bytes, %d blocks, indexRange=0x%X",
            fileSize, totalBlocks, indexRangeOffset));
    }

    // ── Header builder ─────────────────────────────────────────────────────

    private byte[] buildHeader(long fileSize, long indexRangeOffset,
                               long totalBlocks, long thumbnailOffset,
                               int headerSize) {
        byte[] h = new byte[headerSize];
        int pos = 0;

        // Magic word (16 bytes)
        byte[] magic = "OLYMPUSRAWFORMAT".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, h, pos, 16);
        pos += 16; // 0x0010

        // Version: major=1 (upper 32), minor=5 (lower 32)
        ByteUtils.writeInt32(h, pos,     VERSION_MAJOR);
        ByteUtils.writeInt32(h, pos + 4, VERSION_MINOR);
        pos += 8; // 0x0018

        // File size
        ByteUtils.writeInt64(h, pos, fileSize);
        pos += 8; // 0x0020

        // Index Range offset
        ByteUtils.writeInt64(h, pos, indexRangeOffset);
        pos += 8; // 0x0028

        // Total blocks
        ByteUtils.writeInt64(h, pos, totalBlocks);
        pos += 8; // 0x0030

        // Block attribute count (fixed = 6)
        ByteUtils.writeInt64(h, pos, 6L);
        pos += 8; // 0x0038

        // THUMBNAIL_METAINFO offset
        ByteUtils.writeInt64(h, pos, thumbnailOffset);
        pos += 8; // 0x0040

        // Reserved (8 bytes)
        ByteUtils.writeInt64(h, pos, 0L);

        return h;
    }

    // ── Index Range builder ────────────────────────────────────────────────

    private byte[] buildIndexRange(long[] blockOffsets) {
        int size = 4 + blockOffsets.length * 8;
        byte[] ir = new byte[size];

        // Sentinel 0xFFFFFFFF
        ByteUtils.writeInt32(ir, 0, 0xFFFFFFFF);

        for (int i = 0; i < blockOffsets.length; i++) {
            ByteUtils.writeInt64(ir, 4 + i * 8, blockOffsets[i]);
        }
        return ir;
    }

    // ── Block data builders ────────────────────────────────────────────────

    /** IMAGESET_METAINFO data: FILE_INFORMATION + IMAGE_PROPERTIES sections */
    private byte[] buildImagesetData() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // Section 1: FILE_INFORMATION (v1.1)
        String fileInfoXml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<fileInformation>\n" +
            "  <dataName>MockOIR_Test</dataName>\n" +
            "  <createdDate>2024-01-01</createdDate>\n" +
            "  <application>OIR File Analyzer Mock Generator</application>\n" +
            "  <comment>This is a mock OIR file for testing purposes.</comment>\n" +
            "</fileInformation>";
        writeSectionXml(baos, 1, 1, 1, fileInfoXml);

        // Section 2: IMAGE_PROPERTIES (v1.1)
        String imagePropsXml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<imageProperties>\n" +
            "  <objective>10x / 0.30 NA</objective>\n" +
            "  <magnification>10</magnification>\n" +
            "  <pixelSize unit=\"um\">0.621</pixelSize>\n" +
            "  <imageSizeX>512</imageSizeX>\n" +
            "  <imageSizeY>512</imageSizeY>\n" +
            "  <bitDepth>16</bitDepth>\n" +
            "  <channels count=\"1\">\n" +
            "    <channel id=\"mock-channel-0001\">\n" +
            "      <dyeName>DAPI</dyeName>\n" +
            "      <excitationWavelength>405</excitationWavelength>\n" +
            "      <emissionWavelength>450</emissionWavelength>\n" +
            "    </channel>\n" +
            "  </channels>\n" +
            "  <zStack>\n" +
            "    <slices>1</slices>\n" +
            "    <stepSize unit=\"um\">1.0</stepSize>\n" +
            "  </zStack>\n" +
            "  <timeLapse>\n" +
            "    <frames>1</frames>\n" +
            "    <interval unit=\"s\">0</interval>\n" +
            "  </timeLapse>\n" +
            "</imageProperties>";
        writeSectionXml(baos, 2, 1, 1, imagePropsXml);

        return baos.toByteArray();
    }

    /** THUMBNAIL_METAINFO data: format string + BMP image binary */
    private byte[] buildThumbnailData() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        // Format string: OIR String (4B length + "BMP")
        writeOirString(baos, "BMP");
        // Image data: OIR Binary (4B length + BMP bytes)
        writeOirBinary(baos, MINI_BMP);
        return baos.toByteArray();
    }

    /** RESOURCE_METAINFO data: FrameProperties section (v1.1) */
    private byte[] buildResourceData() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        String framePropsXml =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
            "<frameProperties>\n" +
            "  <frameIndex>t001_0_1</frameIndex>\n" +
            "  <acquisitionTime>2024-01-01T12:00:00</acquisitionTime>\n" +
            "  <positionX unit=\"um\">0.0</positionX>\n" +
            "  <positionY unit=\"um\">0.0</positionY>\n" +
            "  <positionZ unit=\"um\">0.0</positionZ>\n" +
            "</frameProperties>";
        writeSectionXml(baos, 1, 1, 1, framePropsXml);
        return baos.toByteArray();
    }

    /** IMAGE_METAINFO data: startPosition, dataSize, channelId */
    private byte[] buildImageMetaData() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // Start position in image (int): 0 (fragment starts at byte 0)
        writeInt32LE(baos, 0);
        // Data size of fragment (int): 2 bytes (our 1×1 pixel)
        writeInt32LE(baos, 2);
        // Channel ID (OIR String)
        writeOirString(baos, "mock-channel-0001");

        return baos.toByteArray();
    }

    /** IMAGE_BITMAP data: raw pixel fragment (1×1 pixel, 16-bit = 2 bytes) */
    private byte[] buildBitmapData() {
        return new byte[]{(byte) 0xFF, (byte) 0xFF}; // max intensity pixel
    }

    // ── Section writer helpers ─────────────────────────────────────────────

    private void writeSectionXml(ByteArrayOutputStream baos, int sectionId,
                                  int vMajor, int vMinor, String xml) throws IOException {
        writeInt32LE(baos, sectionId);
        writeInt32LE(baos, vMajor);
        writeInt32LE(baos, vMinor);
        writeInt32LE(baos, 0); // internal
        writeInt32LE(baos, 0); // internal
        writeOirString(baos, xml);
    }

    // ── Block wrapper ──────────────────────────────────────────────────────

    private byte[] wrapBlock(BlockAttribute attr, byte[] data) {
        byte[] block = new byte[8 + data.length];
        ByteUtils.writeInt32(block, 0, data.length);
        ByteUtils.writeInt32(block, 4, attr.getCode());
        System.arraycopy(data, 0, block, 8, data.length);
        return block;
    }

    // ── Low-level write helpers ────────────────────────────────────────────

    private void writeInt32LE(ByteArrayOutputStream baos, int value) {
        byte[] buf = new byte[4];
        ByteUtils.writeInt32(buf, 0, value);
        baos.write(buf, 0, 4);
    }

    private void writeOirString(ByteArrayOutputStream baos, String s) throws IOException {
        byte[] encoded = ByteUtils.encodeOirString(s);
        baos.write(encoded);
    }

    private void writeOirBinary(ByteArrayOutputStream baos, byte[] data) throws IOException {
        byte[] lenBytes = new byte[4];
        ByteUtils.writeInt32(lenBytes, 0, data.length);
        baos.write(lenBytes);
        baos.write(data);
    }

    // ── BMP generator ─────────────────────────────────────────────────────

    /**
     * Create a minimal 8×8 white BMP (24-bit) in memory.
     * BMP structure: 14-byte file header + 40-byte DIB header + pixel data
     */
    private static byte[] createMini8x8Bmp() {
        int width = 8, height = 8;
        int rowSize = ((width * 3 + 3) / 4) * 4; // 4-byte aligned
        int pixelDataSize = rowSize * height;
        int fileSize = 54 + pixelDataSize;

        byte[] bmp = new byte[fileSize];
        // BMP file header (14 bytes)
        bmp[0] = 'B'; bmp[1] = 'M';
        ByteUtils.writeInt32(bmp, 2, fileSize);     // file size
        ByteUtils.writeInt32(bmp, 6, 0);             // reserved
        ByteUtils.writeInt32(bmp, 10, 54);            // pixel data offset

        // DIB header (BITMAPINFOHEADER, 40 bytes)
        ByteUtils.writeInt32(bmp, 14, 40);           // header size
        ByteUtils.writeInt32(bmp, 18, width);        // width
        ByteUtils.writeInt32(bmp, 22, -height);      // height (negative = top-down)
        // planes (2B) = 1, bitCount (2B) = 24
        bmp[26] = 1; bmp[27] = 0;
        bmp[28] = 24; bmp[29] = 0;
        ByteUtils.writeInt32(bmp, 30, 0);            // compression (none)
        ByteUtils.writeInt32(bmp, 34, pixelDataSize);
        ByteUtils.writeInt32(bmp, 38, 2835);         // X ppm
        ByteUtils.writeInt32(bmp, 42, 2835);         // Y ppm
        ByteUtils.writeInt32(bmp, 46, 0);            // colors in table
        ByteUtils.writeInt32(bmp, 50, 0);            // important colors

        // Pixel data: all white (255, 255, 255 per pixel, BGR order)
        for (int i = 54; i < bmp.length; i++) {
            bmp[i] = (byte) 0xFF;
        }
        return bmp;
    }
}
