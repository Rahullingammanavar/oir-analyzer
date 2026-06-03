package com.olympus.oir.parser;

import com.olympus.oir.model.*;
import com.olympus.oir.util.ByteUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Core binary parser for OIR File Units.
 *
 * ── Verified header layout (confirmed via hex dump of real Olympus OIR v1.5 file) ──
 *
 *   0x0000 [16B] ①② Magic Word  "OLYMPUSRAWFORMAT"
 *   0x0010 [ 8B] ③  Header attribute count (= 10, Magic counts as 2)  ← NOT version!
 *   0x0018 [ 8B] ④  OIR Version  [lower32=minor | upper32=major]
 *                   e.g. bytes 05 00 00 00 01 00 00 00 → minor=5, major=1 → v1.5
 *   0x0020 [ 8B] ⑤  File Size (bytes of this File Unit)
 *   0x0028 [ 8B] ⑥  Index Range offset (bytes from file start)
 *   0x0030 [ 8B] ⑦  Total Blocks in Data Range
 *   0x0038 [ 8B] ⑧  Number of Block attribute types (spec says 6)
 *   0x0040 [ 8B] ⑨  THUMBNAIL_METAINFO byte offset
 *   0x0048 [ 8B] ⑩  Reserved
 *   Header ends at 0x0050 = 80 bytes (verified: Block[0] starts at 0x0050)
 *
 * ── Key findings from real file ────────────────────────────────────────────────────
 *   - Block ordering: IMAGE_METAINFO(3)+IMAGE_BITMAP(4) pairs, then COMMIT_LINE(5),
 *     THUMBNAIL_METAINFO(2), IMAGESET_METAINFO(0) — XML is in the LAST block!
 *   - Only IMAGESET_METAINFO and RESOURCE_METAINFO are loaded into RAM.
 *     IMAGE_BITMAP blocks are NEVER loaded (raw pixel data, ~1.1 GB total).
 *
 * All values little-endian (OIR spec section 8.1).
 */
public class OirParser {

    private static final Logger LOG = Logger.getLogger(OirParser.class.getName());

    /** Index Range sentinel: 0xFFFFFFFF (= -1 as signed Java int). */
    public static final int INDEX_RANGE_SENTINEL = -1;

    private final File file;

    public OirParser(File file) { this.file = file; }

    // ── parse() ───────────────────────────────────────────────────────────────

    public ParsedOirFile parse() throws IOException, OirParseException {
        ParsedOirFile result = new ParsedOirFile();
        result.setSourceFile(file);

        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {

            // 1. Header
            OirHeader header = readHeader(raf, result);
            result.setHeader(header);

            if (!header.isMagicValid()) {
                throw new OirParseException(
                    "Invalid magic word: expected \"OLYMPUSRAWFORMAT\", found: \""
                    + header.getMagicWord() + "\"");
            }

            LOG.info("OIR version:   " + header.getVersionString());
            LOG.info("File size:     " + header.getFileSize());
            LOG.info("Total blocks:  " + header.getTotalBlocks());
            LOG.info("Index range:   0x" + Long.toHexString(header.getIndexRangeOffset()).toUpperCase());
            LOG.info("Thumbnail at:  0x" + Long.toHexString(header.getThumbnailMetainfoOffset()).toUpperCase());

            // 2. Index Range → block offsets
            List<Long> blockOffsets = readIndexRange(raf, header, result);

            // 3. Blocks (only load metadata blocks, never IMAGE_BITMAP)
            List<OirBlock> blocks = readBlocks(raf, blockOffsets, result);
            result.setBlocks(blocks);

            // 4. Parse XML sections from IMAGESET_METAINFO / RESOURCE_METAINFO
            //    Spec: "If there are multiple IMAGESET_METAINFOs in one File Unit,
            //           those in the latter Block are enabled."
            //    Strategy: parse all, but later blocks OVERWRITE earlier ones for the same sectionId.
            SectionParser sp = new SectionParser();
            for (OirBlock block : blocks) {
                if (block.isImagesetMetainfo() && block.getData() != null) {
                    try {
                        List<OirSection> secs = sp.parseSections(block.getData(), header);
                        // replaceSection = in-place swap — preserves order (later block wins per spec)
                        for (OirSection sec : secs) {
                            result.replaceSection(sec);
                        }
                        LOG.info("IMAGESET_METAINFO block #" + block.getBlockIndex()
                            + " → parsed " + secs.size() + " sections (overwrite mode)");
                    } catch (Exception ex) {
                        result.addWarning("Section parse error block #" +
                            block.getBlockIndex() + ": " + ex.getMessage());
                        LOG.warning("Section parse error: " + ex);
                    }
                }

                if (block.isResourceMetainfo() && block.getData() != null) {
                    try {
                        List<OirSection> rs = sp.parseResourceSections(block.getData(), header);
                        if (result.getSections().stream()
                                .noneMatch(s -> s.getSectionId() == OirSection.FRAME_PROPERTIES))
                            rs.forEach(result::addSection);
                    } catch (Exception ex) {
                        result.addWarning("Resource section error: " + ex.getMessage());
                    }
                }
            }
            LOG.info("Total sections parsed: " + result.getSections().size());


            // 5. Thumbnail (reads directly from file, not from block data list)
            extractThumbnail(raf, header, result);
        }

        return result;
    }

    // ── Header parsing ─────────────────────────────────────────────────────────

    private OirHeader readHeader(RandomAccessFile raf, ParsedOirFile result) throws IOException {
        OirHeader header = new OirHeader();

        // ① ② Magic Word — 16 bytes at 0x0000
        raf.seek(0x0000);
        byte[] magic = new byte[16];
        raf.readFully(magic);
        header.setMagicWord(new String(magic, StandardCharsets.US_ASCII));

        if (!header.isMagicValid()) return header;

        // ③ Header attribute count at 0x0010 (= 10, fixed; Magic counts as 2 of the 10)
        //    THIS IS NOT THE VERSION NUMBER — common mistake.
        raf.seek(0x0010);
        long headerAttrCount = ByteUtils.readInt64(raf);
        LOG.fine("Header attr count = " + headerAttrCount); // should be 10

        // ④ OIR Version at 0x0018 (8 bytes):
        //    "the major version in the integer type is included in the upper 32 bits,
        //     and the minor version in the integer type is included in the lower 32 bits"
        //    In little-endian memory: lower32 (minor) is at the lower address (0x18),
        //    upper32 (major) is at the higher address (0x1C).
        raf.seek(0x0018);
        int versionMinor = ByteUtils.readInt32(raf); // lower32 @ 0x18 = minor (e.g. 5)
        int versionMajor = ByteUtils.readInt32(raf); // upper32 @ 0x1C = major (e.g. 1)
        header.setVersionMajor(versionMajor);
        header.setVersionMinor(versionMinor);
        // → version "1.5" for the test file ✓

        // ⑤ File Size at 0x0020
        raf.seek(0x0020);
        header.setFileSize(ByteUtils.readInt64(raf));

        // ⑥ Index Range offset at 0x0028
        header.setIndexRangeOffset(ByteUtils.readInt64(raf));

        // ⑦ Total Blocks at 0x0030
        header.setTotalBlocks(ByteUtils.readInt64(raf));

        // ⑧ Block attribute count at 0x0038 (spec says 6)
        header.setBlockAttributeCount(ByteUtils.readInt64(raf));

        // ⑨ THUMBNAIL_METAINFO offset at 0x0040
        header.setThumbnailMetainfoOffset(ByteUtils.readInt64(raf));

        // ⑩ Reserved at 0x0048
        header.setReserved1(ByteUtils.readInt64(raf));

        // Header ends at 0x0050 (80 bytes). Verified: Block[0] starts at 0x0050.
        header.setHeaderSize(80);

        // OIR v2.1+ has a 96-byte header with extra product-version fields
        if (header.isV21OrLater()) {
            header.setHeaderSize(96);
            raf.seek(0x0050);
            header.setProductId(ByteUtils.readInt64(raf));
            header.setProductVersionMajor(ByteUtils.readInt32(raf));
            header.setProductVersionMinor(ByteUtils.readInt32(raf));
            header.setReserved2(ByteUtils.readInt64(raf));
        }

        return header;
    }

    // ── Index Range ────────────────────────────────────────────────────────────

    /**
     * Reads the Index Range to collect all block byte-offsets.
     * Layout: [4B 0xFFFFFFFF sentinel] + [totalBlocks × 8B LE int64 offsets]
     */
    private List<Long> readIndexRange(RandomAccessFile raf, OirHeader header,
                                       ParsedOirFile result) throws IOException {
        List<Long> offsets = new ArrayList<>();
        long irOffset = header.getIndexRangeOffset();

        if (irOffset <= 0 || irOffset >= raf.length()) {
            result.addWarning("Invalid Index Range offset: 0x"
                + Long.toHexString(irOffset).toUpperCase());
            return offsets;
        }

        raf.seek(irOffset);

        // Sentinel = 0xFFFFFFFF = -1 in signed Java int
        byte[] s4 = new byte[4];
        raf.readFully(s4);
        int sentinel = ByteUtils.readInt32(s4, 0);
        if (sentinel != INDEX_RANGE_SENTINEL) {
            result.addWarning(String.format(
                "Sentinel mismatch at 0x%X: expected 0xFFFFFFFF, got 0x%08X",
                irOffset, Integer.toUnsignedLong(sentinel)));
        }

        long totalBlocks = header.getTotalBlocks();
        for (long i = 0; i < totalBlocks; i++) {
            if (raf.getFilePointer() + 8 > raf.length()) {
                result.addWarning("Index Range truncated at entry " + i);
                break;
            }
            offsets.add(ByteUtils.readInt64(raf));
        }

        LOG.info("Index Range: read " + offsets.size() + " block offsets");
        return offsets;
    }

    // ── Block parsing ──────────────────────────────────────────────────────────

    /**
     * Reads all blocks listed in the Index Range.
     *
     * MEMORY STRATEGY (critical for large OIR files):
     *   - IMAGESET_METAINFO (attr=0): load data — contains all XML sections.
     *   - RESOURCE_METAINFO (attr=1): load data — per-frame XML.
     *   - THUMBNAIL_METAINFO (attr=2): handled separately in extractThumbnail(); skip here.
     *   - IMAGE_METAINFO (attr=3): register only, do NOT load (fragment position data).
     *   - IMAGE_BITMAP (attr=4): NEVER load — raw pixel data (~1.1 GB total in test file).
     *   - COMMIT_LINE (attr=5): dataSize=0, nothing to load.
     */
    private List<OirBlock> readBlocks(RandomAccessFile raf, List<Long> blockOffsets,
                                       ParsedOirFile result) throws IOException {
        List<OirBlock> blocks = new ArrayList<>();

        for (int i = 0; i < blockOffsets.size(); i++) {
            long offset = blockOffsets.get(i);

            if (offset < 0 || offset + 8 > raf.length()) {
                result.addWarning("Block #" + i + " invalid offset 0x"
                    + Long.toHexString(offset).toUpperCase() + " — skipping");
                continue;
            }

            raf.seek(offset);
            int dataSize     = ByteUtils.readInt32(raf);
            int rawBlockAttr = ByteUtils.readInt32(raf);

            OirBlock block = new OirBlock(i, offset, dataSize, rawBlockAttr);

            if (block.getAttribute() == null) {
                result.addWarning("Block #" + i + " unknown attr: " + rawBlockAttr);
            }

            // Selective loading: only metadata blocks need to be in RAM
            if (dataSize > 0 && shouldLoadBlock(block)) {
                if (offset + 8 + dataSize > raf.length()) {
                    result.addWarning("Block #" + i + " data extends past EOF — skipping load");
                } else {
                    byte[] data = new byte[dataSize];
                    int read = raf.read(data);
                    if (read < dataSize)
                        result.addWarning("Block #" + i + ": expected " + dataSize
                            + " bytes, read " + read);
                    block.setData(data);
                }
            }

            blocks.add(block);
            if (i % 1000 == 0) LOG.fine("Parsed block #" + i + " of " + blockOffsets.size());
        }

        LOG.info("Blocks parsed: " + blocks.size()
            + " total, "
            + blocks.stream().filter(b -> b.getData() != null).count()
            + " loaded into memory");
        return blocks;
    }

    /**
     * Returns true only for blocks whose payload we need in RAM.
     * IMAGE_BITMAP and IMAGE_METAINFO are skipped to avoid loading gigabytes.
     */
    private boolean shouldLoadBlock(OirBlock block) {
        BlockAttribute attr = block.getAttribute();
        if (attr == null) return false;
        return switch (attr) {
            case IMAGESET_METAINFO  -> true;   // XML sections — must load
            case RESOURCE_METAINFO  -> true;   // per-frame XML — load if present
            case THUMBNAIL_METAINFO -> false;  // handled separately in extractThumbnail()
            case IMAGE_METAINFO     -> false;  // fragment position data — not needed
            case IMAGE_BITMAP       -> false;  // raw pixels — never load (too large)
            case COMMIT_LINE        -> false;  // dataSize=0 always
        };
    }

    // ── Thumbnail extraction ───────────────────────────────────────────────────

    /**
     * Reads thumbnail directly from the file using the header's thumbnail offset.
     *
     * VERIFIED block data layout (from hex dump of real Olympus OIR v1.5):
     *   [4B FIXED]  Format string — always exactly 4 bytes, e.g. "BMP " (space-padded)
     *               NOT an OIR length-prefixed string. Spec says "included with 4 bytes".
     *   [remaining] Raw image data — BMP bytes starting with "BM" magic.
     *               NOT OIR Binary format. Image size = blockDataSize - 4.
     *
     * Confirmed: bytes at data start = 42 4D 50 20 (="BMP ") then 42 4D (BMP "BM" header).
     */
    private void extractThumbnail(RandomAccessFile raf, OirHeader header,
                                   ParsedOirFile result) throws IOException {
        long thumbOffset = header.getThumbnailMetainfoOffset();
        if (thumbOffset <= 0 || thumbOffset >= raf.length()) {
            result.addWarning("No valid THUMBNAIL_METAINFO offset in header.");
            return;
        }

        raf.seek(thumbOffset);
        int dataSize     = ByteUtils.readInt32(raf);  // total block payload bytes
        int rawBlockAttr = ByteUtils.readInt32(raf);  // should be 2 (THUMBNAIL_METAINFO)

        if (BlockAttribute.fromCode(rawBlockAttr) != BlockAttribute.THUMBNAIL_METAINFO) {
            result.addWarning(String.format(
                "Block at thumbnailOffset 0x%X is not THUMBNAIL_METAINFO (attr=%d)",
                thumbOffset, rawBlockAttr));
            return;
        }
        if (dataSize <= 4) {
            result.addWarning("THUMBNAIL_METAINFO block is too small (" + dataSize + " bytes).");
            return;
        }

        // ① Format string: exactly 4 FIXED bytes (e.g. "BMP ", 0x42 4D 50 20)
        byte[] fmtBytes = new byte[4];
        raf.readFully(fmtBytes);
        String fmt = new String(fmtBytes, StandardCharsets.US_ASCII).trim(); // → "BMP"
        result.setThumbnailFormat(fmt);

        // ② Image data: raw bytes, length = dataSize - 4 (no OIR length prefix!)
        int imgDataLen = dataSize - 4;
        byte[] imgData = new byte[imgDataLen];
        raf.readFully(imgData);

        if (imgData.length > 0) {
            result.setThumbnailBytes(imgData);
            LOG.info("Thumbnail: format=" + fmt + ", rawBytes=" + imgData.length
                + " (BMP magic=" + String.format("%02X%02X", imgData[0], imgData[1]) + ")");
        } else {
            result.addWarning("THUMBNAIL_METAINFO image data is empty.");
        }
    }


    // ── Exception ──────────────────────────────────────────────────────────────

    public static class OirParseException extends Exception {
        public OirParseException(String message) { super(message); }
        public OirParseException(String message, Throwable cause) { super(message, cause); }
    }
}
