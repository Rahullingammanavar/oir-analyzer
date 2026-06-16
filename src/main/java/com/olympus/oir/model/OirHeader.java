package com.olympus.oir.model;

/**
 * Represents the parsed Header Range of an OIR File Unit.
 *
 * ── v1.x layout (80 bytes = 10 attributes × 8 bytes) ──────────────────────
 *
 *   0x0000 [16B]  ①② Magic Word — "OLYMPUSRAWFORMAT"
 *                     (counts as 2 attributes of 8 bytes each)
 *   0x0010 [ 8B]  ③  Header attribute count — always 10 for v1.x.
 *                     NOTE: This is NOT the version field. Common mistake.
 *   0x0018 [ 8B]  ④  OIR Version (little-endian 64-bit split):
 *                       lower 32 bits @ 0x0018 = minor version  (e.g. 5)
 *                       upper 32 bits @ 0x001C = major version  (e.g. 1)
 *                     → read as two sequential int32 LE reads: minor then major
 *   0x0020 [ 8B]  ⑤  File size of this File Unit (bytes)
 *   0x0028 [ 8B]  ⑥  Index Range byte offset from file start
 *   0x0030 [ 8B]  ⑦  Total number of Blocks in Data Range
 *   0x0038 [ 8B]  ⑧  Number of Block attribute types (= 6, fixed)
 *   0x0040 [ 8B]  ⑨  THUMBNAIL_METAINFO byte offset from file start
 *   0x0048 [ 8B]  ⑩  Reserved
 *   ── header ends at 0x0050 = 80 bytes ──
 *
 * ── v2.1+ layout (96 bytes = 12 attributes × 8 bytes) ────────────────────
 *   Same as above, plus 2 additional attributes starting at 0x0050:
 *
 *   0x0050 [ 8B]  ⑪  Product ID (8-byte integer)
 *   0x0058 [ 4B]  ⑫a Product Version Major (int32 LE)
 *   0x005C [ 4B]  ⑫b Product Version Minor (int32 LE)
 *   0x0060 [ 8B]      Reserved2
 *   ── parser reads to 0x0067 inclusive ──
 *
 *   NOTE: The spec states 96 bytes (12 × 8). The parser reads product-version
 *   as two int32 fields (4+4 = 8 bytes, one logical attribute) plus reserved2
 *   (8 bytes). Total extra = 8 + 8 = 16 bytes → 80 + 16 = 96 bytes. ✓
 *
 * All fields are little-endian (OIR spec section 8.1).
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
    private long reserved1;          // reserved (v1.x field ⑩)

    // ── v2.1+ additional fields ─────────────────────────────
    private long productId;          // Product ID (8-byte integer)
    private int  productVersionMajor;
    private int  productVersionMinor;
    private long reserved2;          // reserved (v2.1+ trailing field)

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
        return versionMajor > 2 || (versionMajor == 2 && versionMinor >= 1);
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
        return String.format(
            "OirHeader{magic='%s', version=%s, fileSize=%d, totalBlocks=%d, thumbnailOffset=0x%X, headerSize=%dB}",
            magicWord, getVersionString(), fileSize, totalBlocks, thumbnailMetainfoOffset, headerSize);
    }
}
