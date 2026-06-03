package com.olympus.oir.model;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Aggregates all parsed data from an OIR File Unit:
 *   - Header fields
 *   - All blocks (with their data where loaded)
 *   - All sections extracted from IMAGESET_METAINFO blocks
 *   - Thumbnail raw bytes
 */
public class ParsedOirFile {

    private File   sourceFile;
    private OirHeader header;
    private List<OirBlock>   blocks   = new ArrayList<>();
    private List<OirSection> sections = new ArrayList<>();

    /** Raw BMP bytes of the thumbnail image (null if not present). */
    private byte[] thumbnailBytes;

    /** Format string from THUMBNAIL_METAINFO (usually "BMP"). */
    private String thumbnailFormat;

    /** Any parse warnings or anomalies found during parsing. */
    private List<String> warnings = new ArrayList<>();

    // ── Convenience accessors ────────────────────────────────

    /** Returns all IMAGESET_METAINFO blocks (last one takes precedence per spec). */
    public List<OirBlock> getImagesetBlocks() {
        return blocks.stream()
                .filter(OirBlock::isImagesetMetainfo)
                .toList();
    }

    /** Returns the last (active) IMAGESET_METAINFO block. */
    public Optional<OirBlock> getLastImagesetBlock() {
        List<OirBlock> list = getImagesetBlocks();
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(list.size() - 1));
    }

    /** Returns the first THUMBNAIL_METAINFO block. */
    public Optional<OirBlock> getThumbnailBlock() {
        return blocks.stream().filter(OirBlock::isThumbnailMetainfo).findFirst();
    }

    /** Find a section by its ID from the parsed sections list. */
    public Optional<OirSection> findSection(int sectionId) {
        return sections.stream()
                .filter(s -> s.getSectionId() == sectionId)
                .findFirst();
    }

    /** Returns IMAGE_PROPERTIES XML if available. */
    public Optional<String> getImagePropertiesXml() {
        return findSection(OirSection.IMAGE_PROPERTIES)
                .map(OirSection::getXmlContent);
    }

    /** Returns FILE_INFORMATION XML if available. */
    public Optional<String> getFileInformationXml() {
        return findSection(OirSection.FILE_INFORMATION)
                .map(OirSection::getXmlContent);
    }

    public void addWarning(String warning) {
        warnings.add(warning);
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

    /**
     * Replace an existing section IN-PLACE (same list index) so the original
     * file order is preserved across multiple tag injections.
     * If no section with that ID exists yet, appends it.
     */
    public void replaceSection(OirSection section) {
        for (int i = 0; i < sections.size(); i++) {
            if (sections.get(i).getSectionId() == section.getSectionId()) {
                sections.set(i, section);  // swap in-place — order unchanged
                return;
            }
        }
        sections.add(section);  // first time seen — append
    }

    /** @deprecated Use replaceSection() to preserve order. */
    @Deprecated
    public void removeSection(int sectionId) {
        this.sections.removeIf(s -> s.getSectionId() == sectionId);
    }


    public byte[] getThumbnailBytes() { return thumbnailBytes; }
    public void setThumbnailBytes(byte[] thumbnailBytes) { this.thumbnailBytes = thumbnailBytes; }

    public String getThumbnailFormat() { return thumbnailFormat; }
    public void setThumbnailFormat(String thumbnailFormat) { this.thumbnailFormat = thumbnailFormat; }

    public List<String> getWarnings() { return warnings; }

    @Override
    public String toString() {
        return String.format("ParsedOirFile{file='%s', version=%s, blocks=%d, sections=%d, hasThumbnail=%b}",
                sourceFile != null ? sourceFile.getName() : "<none>",
                header != null ? header.getVersionString() : "?",
                blocks.size(), sections.size(), thumbnailBytes != null);
    }
}
