package com.olympus.oir.model;

import java.io.File;
import java.util.*;

/**
 * Aggregates all parsed data from an OIR File Unit:
 *   - Header fields
 *   - All blocks (with their data where loaded)
 *   - All sections extracted from IMAGESET_METAINFO blocks
 *   - Per-frame FrameProperties XML from RESOURCE_METAINFO blocks
 *   - Thumbnail raw bytes
 */
public class ParsedOirFile {

    private File   sourceFile;
    private OirHeader header;
    private List<OirBlock>   blocks   = new ArrayList<>();
    private List<OirSection> sections = new ArrayList<>();

    /**
     * Per-frame FrameProperties XML extracted from RESOURCE_METAINFO blocks.
     */
    private Map<String, String> framePropertiesMap = new LinkedHashMap<>();

    /** Raw BMP bytes of the thumbnail image (null if not present). */
    private byte[] thumbnailBytes;

    /** Format string from THUMBNAIL_METAINFO (usually "BMP"). */
    private String thumbnailFormat;

    /** Any parse warnings or anomalies found during parsing. */
    private List<String> warnings = new ArrayList<>();

    // ── Convenience accessors ────────────────────────────────

    public List<OirBlock> getImagesetBlocks() {
        return blocks.stream()
                .filter(OirBlock::isImagesetMetainfo)
                .toList();
    }

    public Optional<OirBlock> getLastImagesetBlock() {
        List<OirBlock> list = getImagesetBlocks();
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(list.size() - 1));
    }

    public Optional<OirBlock> getThumbnailBlock() {
        return blocks.stream().filter(OirBlock::isThumbnailMetainfo).findFirst();
    }

    public Optional<OirSection> findSection(int sectionId) {
        return sections.stream()
                .filter(s -> s.getSectionId() == sectionId)
                .findFirst();
    }

    public Optional<String> getImagePropertiesXml() {
        return findSection(OirSection.IMAGE_PROPERTIES)
                .map(OirSection::getXmlContent);
    }

    public Optional<String> getFileInformationXml() {
        return findSection(OirSection.FILE_INFORMATION)
                .map(OirSection::getXmlContent);
    }

    public void addWarning(String warning) {
        warnings.add(warning);
    }

    // ── Index Maps ───────────────────────────────────────────

    /**
     * Returns a Map of Frame Index (String) to Block Number (Integer).
     * Extracted from FRAME_LOCATION (section 6).
     * Essential for mapping a frame to its pixel data.
     */
    public Map<String, Integer> getFrameIndexMap() {
        Map<String, Integer> map = new LinkedHashMap<>();
        findSection(OirSection.FRAME_LOCATION).ifPresent(section -> {
            for (Map.Entry<String, Object> entry : section.getLoopEntries()) {
                if (entry.getValue() instanceof Integer) {
                    map.put(entry.getKey(), (Integer) entry.getValue());
                }
            }
        });
        return map;
    }

    /**
     * Returns a Map of Fragment Index (String) to Block Number (Integer).
     * Extracted from FRAME_FRAGMENT_LOCATION (section 7).
     * Essential for extracting channel images.
     */
    public Map<String, Integer> getFragmentIndexMap() {
        Map<String, Integer> map = new LinkedHashMap<>();
        findSection(OirSection.FRAME_FRAGMENT_LOCATION).ifPresent(section -> {
            for (Map.Entry<String, Object> entry : section.getLoopEntries()) {
                if (entry.getValue() instanceof Integer) {
                    map.put(entry.getKey(), (Integer) entry.getValue());
                }
            }
        });
        return map;
    }

    // ── Per-frame FrameProperties ─────────────────────────────

    public void addFrameProperties(String frameIndex, String xml) {
        if (frameIndex != null && xml != null && !xml.isBlank()) {
            framePropertiesMap.put(frameIndex, xml);
        }
    }

    public Map<String, String> getFramePropertiesMap() {
        return framePropertiesMap;
    }

    public int getFrameCount() {
        return framePropertiesMap.size();
    }

    // ── Section replacement ──────────────────────────────────

    public void replaceSection(OirSection section) {
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).getSectionId() == section.getSectionId()) {
                sections.set(i, section);  // swap in-place — order unchanged
                return;
            }
        }
        sections.add(section);  // first time seen — append
    }

    @Deprecated
    public void removeSection(int sectionId) {
        this.sections.removeIf(s -> s.getSectionId() == sectionId);
    }

    // ── Getters / Setters ────────────────────────────────────

    public File getSourceFile() { return sourceFile; }
    public void setSourceFile(File sourceFile) { this.sourceFile = sourceFile; }

    public OirHeader getHeader() { return header; }
    public void setHeader(OirHeader header) { this.header = header; }

    public List<OirBlock> getBlocks() { return blocks; }
    public void setBlocks(List<OirBlock> blocks) { this.blocks = blocks; }
    public void addBlock(OirBlock block) { this.blocks.add(block); }

    public List<OirSection> getSections() { return sections; }
    public void setSections(List<OirSection> sections) { this.sections = sections; }
    public void addSection(OirSection section) { this.sections.add(section); }

    public byte[] getThumbnailBytes() { return thumbnailBytes; }
    public void setThumbnailBytes(byte[] thumbnailBytes) { this.thumbnailBytes = thumbnailBytes; }

    public String getThumbnailFormat() { return thumbnailFormat; }
    public void setThumbnailFormat(String thumbnailFormat) { this.thumbnailFormat = thumbnailFormat; }

    public List<String> getWarnings() { return warnings; }

    @Override
    public String toString() {
        return String.format(
            "ParsedOirFile{file='%s', version=%s, blocks=%d, sections=%d, frames=%d, hasThumbnail=%b}",
            sourceFile != null ? sourceFile.getName() : "<none>",
            header != null ? header.getVersionString() : "?",
            blocks.size(), sections.size(), framePropertiesMap.size(),
            thumbnailBytes != null);
    }
}
