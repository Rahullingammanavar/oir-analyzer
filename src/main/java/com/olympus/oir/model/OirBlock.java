package com.olympus.oir.model;

import java.io.File;

/**
 * Represents a single Block in the OIR Data Range.
 *
 * Block layout:
 *   [4 bytes] dataSize  — byte count of the data area (int32 LE)
 *   [4 bytes] blockAttr — attribute code identifying block type (int32 LE)
 *   [dataSize bytes] data — payload
 *
 * The byte offset of this block is sourced from the Index Range.
 * In multi-file OIR datasets (e.g. _0001.oir), the sourceFile indicates
 * which physical file contains this block.
 */
public class OirBlock {

    /** The physical file containing this block (essential for multi-file datasets). */
    private File sourceFile;

    /** Block index (0-based) as listed in the Index Range. */
    private int blockIndex;

    /** Absolute byte offset of this block from the start of its sourceFile. */
    private long byteOffset;

    /** Data size in bytes (excludes the 8-byte block header). */
    private int dataSize;

    /** Block attribute type. */
    private BlockAttribute attribute;

    /** Raw integer code (in case attribute is unknown). */
    private int rawAttributeCode;

    /** Block payload data (may be null for large IMAGE_BITMAP blocks to save memory). */
    private byte[] data;

    // ── Constructors ─────────────────────────────────────────

    public OirBlock() {}

    public OirBlock(File sourceFile, int blockIndex, long byteOffset, int dataSize, int rawAttributeCode) {
        this.sourceFile = sourceFile;
        this.blockIndex = blockIndex;
        this.byteOffset = byteOffset;
        this.dataSize = dataSize;
        this.rawAttributeCode = rawAttributeCode;
        this.attribute = BlockAttribute.fromCode(rawAttributeCode);
    }

    // ── Convenience ──────────────────────────────────────────

    public boolean isImagesetMetainfo() {
        return attribute == BlockAttribute.IMAGESET_METAINFO;
    }
    public boolean isThumbnailMetainfo() {
        return attribute == BlockAttribute.THUMBNAIL_METAINFO;
    }
    public boolean isResourceMetainfo() {
        return attribute == BlockAttribute.RESOURCE_METAINFO;
    }
    public boolean isImageMetainfo() {
        return attribute == BlockAttribute.IMAGE_METAINFO;
    }
    public boolean isImageBitmap() {
        return attribute == BlockAttribute.IMAGE_BITMAP;
    }
    public boolean isCommitLine() {
        return attribute == BlockAttribute.COMMIT_LINE;
    }

    /** Returns the absolute offset of the block data area (skips 8-byte block header). */
    public long getDataOffset() {
        return byteOffset + 8;
    }

    // ── Getters / Setters ────────────────────────────────────

    public File getSourceFile() { return sourceFile; }
    public void setSourceFile(File sourceFile) { this.sourceFile = sourceFile; }

    public int getBlockIndex() { return blockIndex; }
    public void setBlockIndex(int blockIndex) { this.blockIndex = blockIndex; }

    public long getByteOffset() { return byteOffset; }
    public void setByteOffset(long byteOffset) { this.byteOffset = byteOffset; }

    public int getDataSize() { return dataSize; }
    public void setDataSize(int dataSize) { this.dataSize = dataSize; }

    public BlockAttribute getAttribute() { return attribute; }
    public void setAttribute(BlockAttribute attribute) { this.attribute = attribute; }

    public int getRawAttributeCode() { return rawAttributeCode; }
    public void setRawAttributeCode(int rawAttributeCode) {
        this.rawAttributeCode = rawAttributeCode;
        this.attribute = BlockAttribute.fromCode(rawAttributeCode);
    }

    public byte[] getData() { return data; }
    public void setData(byte[] data) { this.data = data; }

    @Override
    public String toString() {
        String attrName = (attribute != null) ? attribute.getName() : ("UNKNOWN(" + rawAttributeCode + ")");
        String fileName = (sourceFile != null) ? sourceFile.getName() : "unknown";
        return String.format("Block#%d [%s] file=%s offset=0x%X size=%d bytes",
                blockIndex, attrName, fileName, byteOffset, dataSize);
    }
}
