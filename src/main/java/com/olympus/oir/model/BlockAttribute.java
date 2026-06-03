package com.olympus.oir.model;

/**
 * Enum representing the six Block attribute types defined in the OIR spec
 * (section 6.1.2 — Data Range).
 *
 * Each block in the Data Range carries a 32-bit integer blockAttr field.
 * Value 0 = IMAGESET_METAINFO, 1 = RESOURCE_METAINFO, ... 5 = COMMIT_LINE
 */
public enum BlockAttribute {

    /** Contains whole-experiment info: XML sections, Frame/Fragment maps, LUT, etc. */
    IMAGESET_METAINFO(0, "IMAGESET_METAINFO", "Whole-experiment metadata (XML sections, indexes)"),

    /** Per-Frame FrameProperties XML. One block per Frame. */
    RESOURCE_METAINFO(1, "RESOURCE_METAINFO", "Per-Frame acquisition properties (XML)"),

    /** 24-bit BMP thumbnail. Always in first File Unit only. */
    THUMBNAIL_METAINFO(2, "THUMBNAIL_METAINFO", "24-bit BMP thumbnail image"),

    /** Fragment metadata: start offset, data size, channel ID. Always paired with IMAGE_BITMAP next. */
    IMAGE_METAINFO(3, "IMAGE_METAINFO", "Fragment offset, size, and channel ID"),

    /** Raw fragment pixel data. Always immediately after IMAGE_METAINFO. */
    IMAGE_BITMAP(4, "IMAGE_BITMAP", "Raw fragment pixel data"),

    /** Delimiter block — indicates all data before this point is complete. dataSize = 0. */
    COMMIT_LINE(5, "COMMIT_LINE", "Commit delimiter (data size = 0)");

    private final int code;
    private final String name;
    private final String description;

    BlockAttribute(int code, String name, String description) {
        this.code = code;
        this.name = name;
        this.description = description;
    }

    public int getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }

    /**
     * Resolve a BlockAttribute from its integer code.
     * Returns null if unrecognised (e.g. future version extension).
     */
    public static BlockAttribute fromCode(int code) {
        for (BlockAttribute attr : values()) {
            if (attr.code == code) return attr;
        }
        return null;
    }

    @Override
    public String toString() {
        return name;
    }
}
