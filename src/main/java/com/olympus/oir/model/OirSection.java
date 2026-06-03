package com.olympus.oir.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Represents a parsed Section within an IMAGESET_METAINFO or RESOURCE_METAINFO block.
 *
 * Section header layout (OIR >= 1.2):
 *   [4B int] Section Identifier  — 1-14 for IMAGESET, 1 for RESOURCE
 *   [4B int] Version Major
 *   [4B int] Version Minor
 *   [4B int] Internal use
 *   [4B int] Internal use
 *   --- only if sectionMajor >= 2 AND sectionMinor >= 1 (file version >= 2.1) ---
 *   [4B int] Product Version Major
 *   [4B int] Product Version Minor
 *   [4B int] Internal use
 *   [4B int] Internal use
 *   ----- end conditional -----
 *   [data...] Section payload (XML, loops, etc.)
 */
public class OirSection {

    // ── Section identifier constants (IMAGESET_METAINFO) ────
    public static final int FILE_INFORMATION              = 1;
    public static final int IMAGE_PROPERTIES              = 2;
    public static final int IMAGE_ANNOTATION              = 3;
    public static final int IMAGE_OVERLAY_ITEM            = 4;
    public static final int IMAGE_LUT                     = 5;
    public static final int FRAME_LOCATION                = 6;
    public static final int FRAME_FRAGMENT_LOCATION       = 7;
    public static final int FRAME_FRAGMENTS_PER_CHANNEL   = 8;
    public static final int FRAME_CONTAINED_FILE_UNIT     = 9;
    public static final int REFERENCE_IMAGE_INFORMATION   = 10;
    public static final int REFERENCE_LUT                 = 11;
    public static final int REFERENCE_IMAGE_LOCATION      = 12;
    public static final int REFERENCE_FRAGMENTS_PER_CHANNEL = 13;
    public static final int EVENT_LIST                    = 14;

    // ── Section identifier constants (RESOURCE_METAINFO) ────
    public static final int FRAME_PROPERTIES              = 1;  // (within RESOURCE_METAINFO)

    // ── Fields ───────────────────────────────────────────────
    private int    sectionId;
    private String sectionName;
    private int    versionMajor;
    private int    versionMinor;
    private int    productVersionMajor;
    private int    productVersionMinor;
    private boolean hasProductVersion;

    /** Primary XML payload (if this section contains a single XML). */
    private String xmlContent;

    /** All XML strings found in this section (for sections with multiple XMLs or loops). */
    private List<String> allXmlContents = new ArrayList<>();

    /** Key-value entries extracted from loop sections (e.g. Frame Index → Block No). */
    private List<Map.Entry<String, Object>> loopEntries = new ArrayList<>();

    // ── Constructor ──────────────────────────────────────────

    public OirSection(int sectionId) {
        this.sectionId = sectionId;
        this.sectionName = resolveNameById(sectionId);
    }

    // ── Static helper ────────────────────────────────────────

    public static String resolveNameById(int id) {
        return switch (id) {
            case FILE_INFORMATION              -> "FILE_INFORMATION";
            case IMAGE_PROPERTIES              -> "IMAGE_PROPERTIES";
            case IMAGE_ANNOTATION              -> "IMAGE_ANNOTATION";
            case IMAGE_OVERLAY_ITEM            -> "IMAGE_OVERLAY_ITEM";
            case IMAGE_LUT                     -> "IMAGE_LUT";
            case FRAME_LOCATION                -> "FRAME_LOCATION";
            case FRAME_FRAGMENT_LOCATION       -> "FRAME_FRAGMENT_LOCATION";
            case FRAME_FRAGMENTS_PER_CHANNEL   -> "FRAME_FRAGMENTS_PER_CHANNEL";
            case FRAME_CONTAINED_FILE_UNIT     -> "FRAME_CONTAINED_FILE_UNIT";
            case REFERENCE_IMAGE_INFORMATION   -> "REFERENCE_IMAGE_INFORMATION";
            case REFERENCE_LUT                 -> "REFERENCE_LUT";
            case REFERENCE_IMAGE_LOCATION      -> "REFERENCE_IMAGE_LOCATION";
            case REFERENCE_FRAGMENTS_PER_CHANNEL -> "REFERENCE_FRAGMENTS_PER_CHANNEL";
            case EVENT_LIST                    -> "EVENT_LIST";
            default                            -> "UNKNOWN_SECTION_" + id;
        };
    }

    // ── Getters / Setters ────────────────────────────────────

    public int getSectionId() { return sectionId; }
    public void setSectionId(int sectionId) {
        this.sectionId = sectionId;
        this.sectionName = resolveNameById(sectionId);
    }

    public String getSectionName() { return sectionName; }

    public int getVersionMajor() { return versionMajor; }
    public void setVersionMajor(int versionMajor) { this.versionMajor = versionMajor; }

    public int getVersionMinor() { return versionMinor; }
    public void setVersionMinor(int versionMinor) { this.versionMinor = versionMinor; }

    public String getVersionString() { return versionMajor + "." + versionMinor; }

    public int getProductVersionMajor() { return productVersionMajor; }
    public void setProductVersionMajor(int v) { this.productVersionMajor = v; }

    public int getProductVersionMinor() { return productVersionMinor; }
    public void setProductVersionMinor(int v) { this.productVersionMinor = v; }

    public boolean isHasProductVersion() { return hasProductVersion; }
    public void setHasProductVersion(boolean hasProductVersion) { this.hasProductVersion = hasProductVersion; }

    public String getXmlContent() { return xmlContent; }
    public void setXmlContent(String xmlContent) {
        this.xmlContent = xmlContent;
        if (xmlContent != null && !xmlContent.isBlank()) {
            this.allXmlContents.add(xmlContent);
        }
    }

    public List<String> getAllXmlContents() { return allXmlContents; }
    public void addXmlContent(String xml) {
        if (xml != null && !xml.isBlank()) {
            this.allXmlContents.add(xml);
            if (this.xmlContent == null) this.xmlContent = xml;
        }
    }

    public List<Map.Entry<String, Object>> getLoopEntries() { return loopEntries; }
    public void addLoopEntry(String key, Object value) {
        loopEntries.add(Map.entry(key, value));
    }

    @Override
    public String toString() {
        return String.format("Section{id=%d, name='%s', version=%s, xmlLen=%d}",
                sectionId, sectionName, getVersionString(),
                xmlContent != null ? xmlContent.length() : 0);
    }
}
