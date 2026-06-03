package com.olympus.oir.model;

/**
 * Represents the parsed Header Range of an OIR File Unit.
 *
 * v1.x layout (80 bytes, 10 attributes × 8 bytes):
 *   0x0000 [16B] Magic Word — "OLYMPUSRAWFORMAT"
 *   0x0010 [ 8B] OIR Version (upper32=major, lower32=minor)
 *   0x0018 [ 8B] File Size of this File Unit
 *   0x0020 [ 8B] Index Range byte offset
 *   0x0028 [ 8B] Total number of Blocks
 *   0x0030 [ 8B] Number of Block attribute types (=6)
 *   0x0038 [ 8B] THUMBNAIL_METAINFO byte offset
 *   0x0040 [ 8B] Reserved
 *
 * v2.1+ layout (96 bytes, 12 attributes × 8 bytes), adds:
 *   0x0048 [ 8B] Product ID string
 *   0x0050 [ 8B] Product OIR version
 *   0x0058 [ 8B] Reserved
 *
 * All fields are little-endian.
 */
public class OirHeader {

    // ── Magic Word ───────────────────────────────────────────
    public static final String EXPECTED_MAGIC = "OLYMPUSRAWFORMAT";

    private String  magicWord;       // Should equal EXPECTED_MAGIC
    private boolean magicValid;

    // ── Version ─────────────────────────────────────────────
    private int versionMajor;        // e.g. 1, 2
    private int versionMinor;        // e.g. 1, 2, 3, 4, 5

    // ── Core header fields ──────────────────────────────────
    private long fileSize;           // byte size of this File Unit
    private long indexRangeOffset;   // byte offset to Index Range
    private long totalBlocks;        // number of Blocks in Data Range
    private long blockAttributeCount;// fixed = 6
    private long thumbnailMetainfoOffset; // byte offset to THUMBNAIL_METAINFO block
    private long reserved1;          // reserved (v1.x)

    // ── v2.1+ additional fields ─────────────────────────────
    private long productId;          // Product ID string (8 bytes)
    private int  productVersionMajor;
    private int  productVersionMinor;
    private long reserved2;          // reserved (v2.1+)

    // ── Header size (80 for v1.x, 96 for v2.1+) ─────────────
    private int headerSize;

    // ── Getters / Setters ────────────────────────────────────

    public String getMagicWord() { return magicWord; }
    public void setMagicWord(String magicWord) {
        this.magicWord = magicWord;
        this.magicValid = EXPECTED_MAGIC.equals(magicWord);
    }
    public boolean isMagicValid() { return magicValid; }

    public int getVersionMajor() { return versionMajor; }
    public void setVersionMajor(int versionMajor) { this.versionMajor = versionMajor; }

    public int getVersionMinor() { return versionMinor; }
    public void setVersionMinor(int versionMinor) { this.versionMinor = versionMinor; }

    public String getVersionString() { return versionMajor + "." + versionMinor; }

    /** Returns true if OIR file version is 1.2 or later (sections introduced). */
    public boolean hasSections() {
        return versionMajor > 1 || (versionMajor == 1 && versionMinor >= 2);
    }

    /** Returns true if OIR file version is 2.1 or later (extended header + product version in sections). */
    public boolean isV21OrLater() {
        return versionMajor >= 2 && versionMinor >= 1;
    }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public long getIndexRangeOffset() { return indexRangeOffset; }
    public void setIndexRangeOffset(long indexRangeOffset) { this.indexRangeOffset = indexRangeOffset; }

    public long getTotalBlocks() { return totalBlocks; }
    public void setTotalBlocks(long totalBlocks) { this.totalBlocks = totalBlocks; }

    public long getBlockAttributeCount() { return blockAttributeCount; }
    public void setBlockAttributeCount(long blockAttributeCount) { this.blockAttributeCount = blockAttributeCount; }

    public long getThumbnailMetainfoOffset() { return thumbnailMetainfoOffset; }
    public void setThumbnailMetainfoOffset(long thumbnailMetainfoOffset) { this.thumbnailMetainfoOffset = thumbnailMetainfoOffset; }

    public long getReserved1() { return reserved1; }
    public void setReserved1(long reserved1) { this.reserved1 = reserved1; }

    public long getProductId() { return productId; }
    public void setProductId(long productId) { this.productId = productId; }

    public int getProductVersionMajor() { return productVersionMajor; }
    public void setProductVersionMajor(int productVersionMajor) { this.productVersionMajor = productVersionMajor; }

    public int getProductVersionMinor() { return productVersionMinor; }
    public void setProductVersionMinor(int productVersionMinor) { this.productVersionMinor = productVersionMinor; }

    public long getReserved2() { return reserved2; }
    public void setReserved2(long reserved2) { this.reserved2 = reserved2; }

    public int getHeaderSize() { return headerSize; }
    public void setHeaderSize(int headerSize) { this.headerSize = headerSize; }

    @Override
    public String toString() {
        return String.format("OirHeader{magic='%s', version=%s, fileSize=%d, totalBlocks=%d, thumbnailOffset=0x%X}",
                magicWord, getVersionString(), fileSize, totalBlocks, thumbnailMetainfoOffset);
    }
}
