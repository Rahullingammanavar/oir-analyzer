package com.olympus.oir.parser;

import com.olympus.oir.model.OirHeader;
import com.olympus.oir.model.OirSection;
import com.olympus.oir.util.ByteUtils;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Parses Section data within IMAGESET_METAINFO and RESOURCE_METAINFO blocks.
 *
 * Section header layout (OIR >= 1.2, spec section 6.1.2.2):
 *
 *   [4B int] Section identifier
 *   [4B int] Version Major
 *   [4B int] Version Minor
 *   [4B int] Internal use (skip)
 *   [4B int] Internal use (skip)
 *   -- only present if sectionMajor >= 2 AND sectionMinor >= 1 (v2.1+) --
 *   [4B int] Product Version Major
 *   [4B int] Product Version Minor
 *   [4B int] Internal use (skip)
 *   [4B int] Internal use (skip)
 *   -- end conditional --
 *   ... section payload (XML strings, loop data, etc.) ...
 *
 * All data is little-endian.
 */
public class SectionParser {

    private static final Logger LOG = Logger.getLogger(SectionParser.class.getName());

    /**
     * Parse all sections from an IMAGESET_METAINFO block's data bytes.
     *
     * @param blockData raw bytes of the block payload
     * @param header    file header (to determine version rules)
     * @return list of parsed sections
     */
    public List<OirSection> parseSections(byte[] blockData, OirHeader header) {
        List<OirSection> sections = new ArrayList<>();

        if (!header.hasSections()) {
            // OIR 1.1 or earlier — no section structure, parse as flat data
            sections.add(parseLegacyImageset(blockData));
            return sections;
        }

        ByteBuffer buf = ByteBuffer.wrap(blockData).order(ByteOrder.LITTLE_ENDIAN);

        while (buf.remaining() >= 20) { // minimum section header = 20 bytes (5 × int)
            int startPos = buf.position();

            // Peek section ID
            int sectionId = buf.getInt();
            if (sectionId <= 0 || sectionId > 14) {
                // Not a valid section start — may be alignment padding
                LOG.fine("Unexpected section ID " + sectionId + " at pos " + startPos + ", stopping");
                break;
            }

            int vMajor = buf.getInt();
            int vMinor = buf.getInt();
            buf.getInt(); // internal
            buf.getInt(); // internal

            OirSection section = new OirSection(sectionId);
            section.setVersionMajor(vMajor);
            section.setVersionMinor(vMinor);

            // Check if this section has product version fields (v2.1+)
            boolean hasProductVer = (vMajor >= 2 && vMinor >= 1);
            section.setHasProductVersion(hasProductVer);

            if (hasProductVer && buf.remaining() >= 16) {
                section.setProductVersionMajor(buf.getInt());
                section.setProductVersionMinor(buf.getInt());
                buf.getInt(); // internal
                buf.getInt(); // internal
            }

            // Parse section payload based on section ID
            try {
                parseSectionPayload(buf, section, sectionId);
            } catch (Exception ex) {
                LOG.warning("Error parsing section " + sectionId + ": " + ex.getMessage());
                section.setXmlContent("<!-- Parse error: " + ex.getMessage() + " -->");
            }

            sections.add(section);
            LOG.fine("Parsed section: " + section);
        }

        return sections;
    }

    /**
     * Parse sections from a RESOURCE_METAINFO block's data bytes.
     * RESOURCE_METAINFO contains only one section: FRAME_PROPERTIES (ID=1 in this context).
     */
    public List<OirSection> parseResourceSections(byte[] blockData, OirHeader header) {
        List<OirSection> sections = new ArrayList<>();

        if (!header.hasSections()) {
            // v1.1: direct XML payload
            OirSection s = new OirSection(OirSection.FRAME_PROPERTIES);
            s.setVersionMajor(1);
            s.setVersionMinor(1);
            s.setXmlContent(new String(blockData, StandardCharsets.UTF_8).trim());
            sections.add(s);
            return sections;
        }

        ByteBuffer buf = ByteBuffer.wrap(blockData).order(ByteOrder.LITTLE_ENDIAN);

        if (buf.remaining() < 20) return sections;

        int sectionId = buf.getInt();
        int vMajor    = buf.getInt();
        int vMinor    = buf.getInt();
        buf.getInt(); // internal
        buf.getInt(); // internal

        OirSection section = new OirSection(sectionId);
        section.setVersionMajor(vMajor);
        section.setVersionMinor(vMinor);

        boolean hasProductVer = (vMajor >= 2 && vMinor >= 1);
        section.setHasProductVersion(hasProductVer);
        if (hasProductVer && buf.remaining() >= 16) {
            section.setProductVersionMajor(buf.getInt());
            section.setProductVersionMinor(buf.getInt());
            buf.getInt();
            buf.getInt();
        }

        // FrameProperties: single XML payload
        String xml = readXmlString(buf);
        section.setXmlContent(xml);

        // Override the sectionName for clarity
        sections.add(section);
        return sections;
    }

    // ── Section payload parsers ────────────────────────────────────────────

    private void parseSectionPayload(ByteBuffer buf, OirSection section, int sectionId) {
        switch (sectionId) {
            case OirSection.FILE_INFORMATION   -> section.setXmlContent(readXmlString(buf));
            case OirSection.IMAGE_PROPERTIES   -> section.setXmlContent(readXmlString(buf));
            case OirSection.IMAGE_ANNOTATION   -> section.setXmlContent(readXmlString(buf));
            case OirSection.IMAGE_OVERLAY_ITEM -> section.setXmlContent(readXmlString(buf));
            case OirSection.EVENT_LIST         -> section.setXmlContent(readXmlString(buf));

            case OirSection.IMAGE_LUT          -> parseLutSection(buf, section);
            case OirSection.FRAME_LOCATION     -> parseFrameLocationSection(buf, section);
            case OirSection.FRAME_FRAGMENT_LOCATION       -> parseFragmentLocationSection(buf, section);
            case OirSection.FRAME_FRAGMENTS_PER_CHANNEL   -> parseFragmentsPerChannelSection(buf, section);
            case OirSection.FRAME_CONTAINED_FILE_UNIT     -> parseFrameContainedFileUnit(buf, section);
            case OirSection.REFERENCE_IMAGE_INFORMATION   -> parseReferenceImageInfo(buf, section);
            case OirSection.REFERENCE_LUT                 -> parseReferenceLut(buf, section);
            case OirSection.REFERENCE_IMAGE_LOCATION      -> parseReferenceImageLocation(buf, section);
            case OirSection.REFERENCE_FRAGMENTS_PER_CHANNEL -> parseReferenceFragsPerChannel(buf, section);

            default -> LOG.fine("Unknown section ID " + sectionId + " — skipping");
        }
    }

    // ── Loop section parsers ───────────────────────────────────────────────

    /** Section 5: Loop(Channel ID String, LUT XML) */
    private void parseLutSection(ByteBuffer buf, OirSection section) {
        if (buf.remaining() < 4) return;
        int count = buf.getInt();
        for (int i = 0; i < count && buf.remaining() >= 8; i++) {
            String channelId = readString(buf);
            String lutXml    = readXmlString(buf);
            section.addLoopEntry("LUT[" + channelId + "]", lutXml);
            section.addXmlContent(lutXml);
        }
    }

    /** Section 6: Loop(Frame Index String, Block No int) */
    private void parseFrameLocationSection(ByteBuffer buf, OirSection section) {
        if (buf.remaining() < 4) return;
        int count = buf.getInt();
        for (int i = 0; i < count && buf.remaining() >= 8; i++) {
            String frameIndex = readString(buf);
            int    blockNo    = buf.remaining() >= 4 ? buf.getInt() : -1;
            section.addLoopEntry(frameIndex, blockNo);
        }
    }

    /** Section 7: Loop(Fragment Index String, Block No int) */
    private void parseFragmentLocationSection(ByteBuffer buf, OirSection section) {
        if (buf.remaining() < 4) return;
        int count = buf.getInt();
        for (int i = 0; i < count && buf.remaining() >= 8; i++) {
            String fragmentIndex = readString(buf);
            int    blockNo       = buf.remaining() >= 4 ? buf.getInt() : -1;
            section.addLoopEntry(fragmentIndex, blockNo);
        }
    }

    /** Section 8: Loop(Channel ID String, Number of Fragments int) */
    private void parseFragmentsPerChannelSection(ByteBuffer buf, OirSection section) {
        if (buf.remaining() < 4) return;
        int count = buf.getInt();
        for (int i = 0; i < count && buf.remaining() >= 8; i++) {
            String channelId  = readString(buf);
            int    fragCount  = buf.remaining() >= 4 ? buf.getInt() : -1;
            section.addLoopEntry(channelId, fragCount);
        }
    }

    /** Section 9: Loop(File Unit No int, First Frame Index String, Last Frame Index String) */
    private void parseFrameContainedFileUnit(ByteBuffer buf, OirSection section) {
        if (buf.remaining() < 4) return;
        int count = buf.getInt();
        for (int i = 0; i < count && buf.remaining() >= 4; i++) {
            int    fileUnitNo = buf.getInt();
            String firstFrame = readString(buf);
            String lastFrame  = readString(buf);
            section.addLoopEntry("FileUnit[" + fileUnitNo + "]",
                firstFrame + " → " + lastFrame);
        }
    }

    /** Section 10: Loop(Device Name String, Loop(Channel XML, ImageDefinition XML)) */
    private void parseReferenceImageInfo(ByteBuffer buf, OirSection section) {
        if (buf.remaining() < 4) return;
        int deviceCount = buf.getInt();
        for (int d = 0; d < deviceCount && buf.remaining() >= 4; d++) {
            String deviceName = readString(buf);
            int channelCount  = buf.remaining() >= 4 ? buf.getInt() : 0;
            for (int c = 0; c < channelCount && buf.remaining() >= 4; c++) {
                String channelXml    = readXmlString(buf);
                String imgDefXml     = readXmlString(buf);
                section.addLoopEntry(deviceName + ".channel[" + c + "]", channelXml);
                section.addLoopEntry(deviceName + ".imgDef[" + c + "]", imgDefXml);
                section.addXmlContent(channelXml);
                section.addXmlContent(imgDefXml);
            }
        }
    }

    /** Section 11: Loop(Channel ID String, LUT XML) — for References */
    private void parseReferenceLut(ByteBuffer buf, OirSection section) {
        if (buf.remaining() < 4) return;
        int count = buf.getInt();
        for (int i = 0; i < count && buf.remaining() >= 4; i++) {
            String channelId = readString(buf);
            String lutXml    = readXmlString(buf);
            section.addLoopEntry("REF_LUT[" + channelId + "]", lutXml);
            section.addXmlContent(lutXml);
        }
    }

    /** Section 12: Loop(Reference Frame Index String, File Unit No int) */
    private void parseReferenceImageLocation(ByteBuffer buf, OirSection section) {
        if (buf.remaining() < 4) return;
        int count = buf.getInt();
        for (int i = 0; i < count && buf.remaining() >= 4; i++) {
            String refFrameIndex = readString(buf);
            int    fileUnitNo    = buf.remaining() >= 4 ? buf.getInt() : -1;
            section.addLoopEntry(refFrameIndex, fileUnitNo);
        }
    }

    /** Section 13: Loop(Channel ID String, Number of Fragments int) — for References */
    private void parseReferenceFragsPerChannel(ByteBuffer buf, OirSection section) {
        if (buf.remaining() < 4) return;
        int count = buf.getInt();
        for (int i = 0; i < count && buf.remaining() >= 4; i++) {
            String channelId = readString(buf);
            int    fragCount = buf.remaining() >= 4 ? buf.getInt() : -1;
            section.addLoopEntry("REF[" + channelId + "].frags", fragCount);
        }
    }

    // ── Legacy parser (OIR v1.1 or earlier) ───────────────────────────────

    /**
     * Parse a flat (no-sections) IMAGESET_METAINFO block for OIR version 1.1.
     * Data is read positionally.
     */
    private OirSection parseLegacyImageset(byte[] blockData) {
        OirSection section = new OirSection(OirSection.IMAGE_PROPERTIES);
        section.setVersionMajor(1);
        section.setVersionMinor(1);

        ByteBuffer buf = ByteBuffer.wrap(blockData).order(ByteOrder.LITTLE_ENDIAN);

        try {
            // Item 1: int tag (fixed=1) + FileInformation XML
            if (buf.remaining() >= 4) {
                int tag = buf.getInt(); // should be 1
                String fileInfoXml = readXmlString(buf);
                section.addXmlContent(fileInfoXml);
            }
            // Item 2: ImageProperties XML
            if (buf.remaining() >= 4) {
                String imgPropsXml = readXmlString(buf);
                section.setXmlContent(imgPropsXml);
            }
        } catch (Exception ex) {
            LOG.warning("Legacy IMAGESET parse error: " + ex.getMessage());
        }

        return section;
    }

    // ── String / XML read helpers ──────────────────────────────────────────

    /**
     * Read an OIR String from the buffer.
     * Format: [4B int length] [length bytes UTF-8]
     */
    private String readString(ByteBuffer buf) {
        if (buf.remaining() < 4) return "";
        int len = buf.getInt();
        if (len <= 0 || len > buf.remaining()) return "";
        byte[] bytes = new byte[len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Read an OIR XML payload from the buffer.
     * Same format as String but semantically an XML document.
     */
    private String readXmlString(ByteBuffer buf) {
        return readString(buf);
    }
}
