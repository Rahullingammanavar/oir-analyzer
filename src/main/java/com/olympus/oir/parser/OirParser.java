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
 * Now includes support for multi-file datasets (e.g. image.oir, image_0001.oir).
 */
public class OirParser {

    private static final Logger LOG = Logger.getLogger(OirParser.class.getName());

    public static final int INDEX_RANGE_SENTINEL = -1;

    private final File mainFile;

    public OirParser(File file) { this.mainFile = file; }

    public ParsedOirFile parse() throws IOException, OirParseException {
        ParsedOirFile result = new ParsedOirFile();
        result.setSourceFile(mainFile);

        // 1. Discover all companion files (e.g., file.oir, file_0001.oir, file_0002.oir)
        List<File> fileUnits = discoverCompanionFiles(mainFile);
        LOG.info("Discovered " + fileUnits.size() + " file units for " + mainFile.getName());

        List<OirBlock> allBlocks = new ArrayList<>();
        int globalBlockIndex = 0;

        // 2. Parse blocks from each file unit
        for (int i = 0; i < fileUnits.size(); i++) {
            File f = fileUnits.get(i);
            try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                
                OirHeader header = readHeader(raf, result);
                if (!header.isMagicValid()) {
                    if (i == 0) {
                        throw new OirParseException("Invalid magic word in main file: " + header.getMagicWord());
                    } else {
                        result.addWarning("Invalid magic word in companion file " + f.getName() + " — skipping");
                        continue;
                    }
                }
                
                // Keep the first file's header as the master header
                if (i == 0) {
                    result.setHeader(header);
                    extractThumbnail(raf, header, result);
                }

                List<Long> blockOffsets = readIndexRange(raf, header, result);
                List<OirBlock> unitBlocks = readBlocks(f, raf, blockOffsets, globalBlockIndex, result);
                
                allBlocks.addAll(unitBlocks);
                globalBlockIndex += unitBlocks.size();
            }
        }
        result.setBlocks(allBlocks);
        LOG.info("Total blocks collected across all files: " + allBlocks.size());

        // 3. Parse XML sections from IMAGESET_METAINFO / RESOURCE_METAINFO across all files
        SectionParser sp = new SectionParser();
        for (OirBlock block : allBlocks) {
            if (block.isImagesetMetainfo() && block.getData() != null) {
                try {
                    List<OirSection> secs = sp.parseSections(block.getData(), result.getHeader());
                    for (OirSection sec : secs) {
                        result.replaceSection(sec);
                    }
                } catch (Exception ex) {
                    result.addWarning("Section parse error in " + block.getSourceFile().getName() + " block #" + block.getBlockIndex() + ": " + ex.getMessage());
                }
            }
        }

        // 4. Parse per-frame FrameProperties
        int resourceFrameIndex = 0;
        for (OirBlock block : allBlocks) {
            if (block.isResourceMetainfo() && block.getData() != null) {
                try {
                    List<OirSection> rs = sp.parseResourceSections(block.getData(), result.getHeader());
                    for (OirSection sec : rs) {
                        String xml = sec.getXmlContent();
                        String frameKey = String.format("%07d", resourceFrameIndex);
                        result.addFrameProperties(frameKey, xml);
                        if (resourceFrameIndex == 0) {
                            result.addSection(sec);
                        }
                    }
                    resourceFrameIndex++;
                } catch (Exception ex) {
                    result.addWarning("Resource section error block #" + block.getBlockIndex() + ": " + ex.getMessage());
                    resourceFrameIndex++; 
                }
            }
        }
        LOG.info("Total RESOURCE_METAINFO frames collected: " + result.getFrameCount());

        return result;
    }

    // ── Companion file discovery ───────────────────────────────────────────────

    /**
     * Finds the main file and any _0001, _0002 sequentially appended files.
     */
    private List<File> discoverCompanionFiles(File mainFile) {
        List<File> files = new ArrayList<>();
        files.add(mainFile);

        String name = mainFile.getName();
        String parent = mainFile.getParent();
        if (parent == null) parent = "";

        // E.g. "image.oir" -> base="image", ext=".oir"
        int dotIdx = name.lastIndexOf('.');
        String baseName = (dotIdx > 0) ? name.substring(0, dotIdx) : name;
        String ext = (dotIdx > 0) ? name.substring(dotIdx) : "";

        int counter = 1;
        while (true) {
            String companionName = String.format("%s_%04d%s", baseName, counter, ext);
            File companion = new File(parent, companionName);
            if (companion.exists() && companion.isFile()) {
                files.add(companion);
                counter++;
            } else {
                break;
            }
        }
        return files;
    }

    // ── Header parsing ─────────────────────────────────────────────────────────

    private OirHeader readHeader(RandomAccessFile raf, ParsedOirFile result) throws IOException {
        OirHeader header = new OirHeader();
        raf.seek(0x0000);
        byte[] magic = new byte[16];
        raf.readFully(magic);
        header.setMagicWord(new String(magic, StandardCharsets.US_ASCII));

        if (!header.isMagicValid()) return header;

        raf.seek(0x0010);
        ByteUtils.readInt64(raf); // skip attr count

        raf.seek(0x0018);
        int versionMinor = ByteUtils.readInt32(raf);
        int versionMajor = ByteUtils.readInt32(raf);
        header.setVersionMajor(versionMajor);
        header.setVersionMinor(versionMinor);

        raf.seek(0x0020);
        header.setFileSize(ByteUtils.readInt64(raf));

        header.setIndexRangeOffset(ByteUtils.readInt64(raf));
        header.setTotalBlocks(ByteUtils.readInt64(raf));
        header.setBlockAttributeCount(ByteUtils.readInt64(raf));
        header.setThumbnailMetainfoOffset(ByteUtils.readInt64(raf));
        header.setReserved1(ByteUtils.readInt64(raf));

        header.setHeaderSize(80);

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

    private List<Long> readIndexRange(RandomAccessFile raf, OirHeader header, ParsedOirFile result) throws IOException {
        List<Long> offsets = new ArrayList<>();
        long irOffset = header.getIndexRangeOffset();

        if (irOffset <= 0 || irOffset >= raf.length()) return offsets;

        raf.seek(irOffset);
        byte[] s4 = new byte[4];
        raf.readFully(s4);
        int sentinel = ByteUtils.readInt32(s4, 0);
        if (sentinel != INDEX_RANGE_SENTINEL) {
            result.addWarning(String.format("Sentinel mismatch at 0x%X", irOffset));
        }

        long totalBlocks = header.getTotalBlocks();
        for (long i = 0; i < totalBlocks; i++) {
            if (raf.getFilePointer() + 8 > raf.length()) break;
            offsets.add(ByteUtils.readInt64(raf));
        }
        return offsets;
    }

    // ── Block parsing ──────────────────────────────────────────────────────────

    private List<OirBlock> readBlocks(File sourceFile, RandomAccessFile raf, List<Long> blockOffsets, 
                                      int globalBlockIndexStart, ParsedOirFile result) throws IOException {
        List<OirBlock> blocks = new ArrayList<>();

        for (int i = 0; i < blockOffsets.size(); i++) {
            long offset = blockOffsets.get(i);
            int currentGlobalIndex = globalBlockIndexStart + i;

            if (offset < 0 || offset + 8 > raf.length()) {
                result.addWarning(sourceFile.getName() + " Block #" + currentGlobalIndex + " invalid offset.");
                continue;
            }

            raf.seek(offset);
            int dataSize     = ByteUtils.readInt32(raf);
            int rawBlockAttr = ByteUtils.readInt32(raf);

            OirBlock block = new OirBlock(sourceFile, currentGlobalIndex, offset, dataSize, rawBlockAttr);

            if (dataSize > 0 && shouldLoadBlock(block)) {
                if (offset + 8 + dataSize > raf.length()) {
                    result.addWarning("Block #" + currentGlobalIndex + " data extends past EOF.");
                } else {
                    byte[] data = new byte[dataSize];
                    raf.readFully(data);
                    block.setData(data);
                }
            }
            blocks.add(block);
        }
        return blocks;
    }

    private boolean shouldLoadBlock(OirBlock block) {
        BlockAttribute attr = block.getAttribute();
        if (attr == null) return false;
        return switch (attr) {
            case IMAGESET_METAINFO, RESOURCE_METAINFO -> true;
            case THUMBNAIL_METAINFO, IMAGE_METAINFO, IMAGE_BITMAP, COMMIT_LINE -> false;
        };
    }

    // ── Thumbnail extraction ───────────────────────────────────────────────────

    private void extractThumbnail(RandomAccessFile raf, OirHeader header, ParsedOirFile result) throws IOException {
        long thumbOffset = header.getThumbnailMetainfoOffset();
        if (thumbOffset <= 0 || thumbOffset >= raf.length()) return;

        raf.seek(thumbOffset);
        int dataSize     = ByteUtils.readInt32(raf);
        int rawBlockAttr = ByteUtils.readInt32(raf);

        if (BlockAttribute.fromCode(rawBlockAttr) != BlockAttribute.THUMBNAIL_METAINFO || dataSize <= 4) return;

        byte[] fmtBytes = new byte[4];
        raf.readFully(fmtBytes);
        String fmt = new String(fmtBytes, StandardCharsets.US_ASCII).trim();
        result.setThumbnailFormat(fmt);

        byte[] imgData = new byte[dataSize - 4];
        raf.readFully(imgData);

        if (imgData.length > 0) {
            result.setThumbnailBytes(imgData);
        }
    }

    public static class OirParseException extends Exception {
        public OirParseException(String message) { super(message); }
    }
}
