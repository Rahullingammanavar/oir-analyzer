package com.olympus.oir.writer;

import com.olympus.oir.model.*;
import com.olympus.oir.util.ByteUtils;

import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.parsers.*;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.logging.Logger;

/**
 * Safely injects custom metadata tags into an OIR file.
 *
 * Strategy (OIR spec section 6.1.2.5):
 *   "If there are multiple IMAGESET_METAINFOs in one File Unit,
 *    those in the latter Block are enabled."
 *
 *   1. Find the XML of the chosen section (e.g. IMAGE_PROPERTIES).
 *   2. Inject the custom tag into that XML under a <customMetadata> container.
 *   3. Rebuild a new IMAGESET_METAINFO block with the updated section.
 *   4. Append the new block after the existing data — original bytes untouched.
 *   5. Write an updated Index Range + patch header fields at VERIFIED offsets:
 *        0x0020 = File Size
 *        0x0028 = Index Range Offset
 *        0x0030 = Total Blocks
 *
 * Sections that support XML tag injection (must have XML payload):
 *   FILE_INFORMATION (1), IMAGE_PROPERTIES (2), IMAGE_ANNOTATION (3),
 *   IMAGE_OVERLAY_ITEM (4), EVENT_LIST (14)
 */
public class CustomMetadataWriter {

    private static final Logger LOG = Logger.getLogger(CustomMetadataWriter.class.getName());

    /** Section IDs that have an XML payload (safe for tag injection). */
    public static final int[] XML_SECTION_IDS = {
        OirSection.FILE_INFORMATION,
        OirSection.IMAGE_PROPERTIES,
        OirSection.IMAGE_ANNOTATION,
        OirSection.IMAGE_OVERLAY_ITEM,
        OirSection.EVENT_LIST
    };

    /** Human-readable names for XML sections, matching OirSection constants. */
    public static final Map<Integer, String> XML_SECTION_NAMES = new LinkedHashMap<>();
    static {
        XML_SECTION_NAMES.put(OirSection.FILE_INFORMATION,  "FILE_INFORMATION");
        XML_SECTION_NAMES.put(OirSection.IMAGE_PROPERTIES,  "IMAGE_PROPERTIES");
        XML_SECTION_NAMES.put(OirSection.IMAGE_ANNOTATION,  "IMAGE_ANNOTATION");
        XML_SECTION_NAMES.put(OirSection.IMAGE_OVERLAY_ITEM,"IMAGE_OVERLAY_ITEM");
        XML_SECTION_NAMES.put(OirSection.EVENT_LIST,        "EVENT_LIST");
    }

    // ── Public API ──────────────────────────────────────────────────────────

    /**
     * Inject a custom tag into a chosen XML section.
     *
     * @param parsedFile  the parsed source OIR file
     * @param sectionId   which section to modify (use OirSection.IMAGE_PROPERTIES etc.)
     * @param tagName     XML element name (valid XML identifier: letters/digits/_)
     * @param tagValue    text content for the new element
     * @param outputFile  destination file (write to temp; caller replaces original)
     */
    public void injectCustomTag(ParsedOirFile parsedFile,
                                int sectionId,
                                String tagName,
                                String tagValue,
                                File outputFile) throws IOException {
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put(tagName, tagValue);
        injectCustomTags(parsedFile, sectionId, tags, outputFile);
    }

    /**
     * Inject multiple custom tags into a chosen XML section.
     */
    public void injectCustomTags(ParsedOirFile parsedFile,
                                  int sectionId,
                                  Map<String, String> tags,
                                  File outputFile) throws IOException {
        validateTagNames(tags.keySet());

        // Verify the chosen section exists and has XML
        String sectionName = XML_SECTION_NAMES.getOrDefault(sectionId, "Section " + sectionId);
        OirSection targetSection = parsedFile.findSection(sectionId)
            .orElseThrow(() -> new IOException(
                "Section '" + sectionName + "' not found in this OIR file."));

        String existingXml = targetSection.getXmlContent();
        if (existingXml == null || existingXml.isBlank()) {
            throw new IOException(
                "Section '" + sectionName + "' has no XML content. " +
                "Only XML sections can receive custom tags.");
        }

        // Inject tags into the chosen section's XML
        String updatedXml = injectTagsIntoXml(existingXml, tags);
        LOG.info("Updated " + sectionName + " XML length: " + updatedXml.length());

        // Build a new IMAGESET_METAINFO block with the updated section
        byte[] newBlockData = buildUpdatedImagesetBlock(parsedFile, sectionId, updatedXml);

        // Write output file (original + new block + updated index + patched header)
        writeOutputFile(parsedFile, newBlockData, outputFile);

        LOG.info("Custom metadata written to: " + outputFile.getAbsolutePath());
    }

    // ── XML manipulation ───────────────────────────────────────────────────

    private String injectTagsIntoXml(String xmlContent, Map<String, String> tags)
            throws IOException {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new InputSource(new StringReader(xmlContent)));
            Element root = doc.getDocumentElement();

            // Reuse or create <customMetadata> container
            NodeList existing = root.getElementsByTagName("customMetadata");
            Element container;
            if (existing.getLength() > 0) {
                container = (Element) existing.item(0);
            } else {
                container = doc.createElement("customMetadata");
                root.appendChild(container);
            }

            // Timestamp of injection
            Element ts = doc.createElement("injectedAt");
            ts.setTextContent(java.time.LocalDateTime.now().toString());
            container.appendChild(ts);

            // Add each tag
            for (Map.Entry<String, String> entry : tags.entrySet()) {
                Element el = doc.createElement(entry.getKey());
                el.setTextContent(entry.getValue());
                container.appendChild(el);
            }

            // Serialize back
            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            StringWriter sw = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(sw));
            return sw.toString();

        } catch (Exception ex) {
            throw new IOException("Failed to modify XML: " + ex.getMessage(), ex);
        }
    }

    // ── Block builder ──────────────────────────────────────────────────────

    /**
     * Build a complete IMAGESET_METAINFO block, reproducing all XML-bearing sections
     * but replacing targetSectionId's XML with updatedXml.
     */
    private byte[] buildUpdatedImagesetBlock(ParsedOirFile parsedFile,
                                              int targetSectionId,
                                              String updatedXml) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        boolean v21 = parsedFile.getHeader().isV21OrLater();

        for (OirSection section : parsedFile.getSections()) {
            String xml = (section.getSectionId() == targetSectionId)
                ? updatedXml
                : section.getXmlContent();

            // Only write XML-bearing sections (loop-only sections need raw bytes — skip safely)
            if (xml == null || xml.isBlank()) continue;

            // Strip BOM and leading whitespace before writing
            if (xml.startsWith("\uFEFF")) xml = xml.substring(1);
            xml = xml.stripLeading();

            // Validate it's actually parseable XML — skip if not (e.g. binary masquerading as XML)
            if (!isValidXml(xml)) {
                LOG.warning("Skipping section " + section.getSectionName()
                    + " (id=" + section.getSectionId() + ") — invalid XML, won't write to block.");
                continue;
            }

            writeSectionHeader(dos, section, v21);

            byte[] xmlBytes = xml.getBytes(StandardCharsets.UTF_8);
            writeInt32LE(dos, xmlBytes.length);
            dos.write(xmlBytes);
        }

        byte[] data = baos.toByteArray();
        if (data.length == 0) {
            throw new IOException(
                "Could not build updated IMAGESET_METAINFO — no writable XML sections found.");
        }

        byte[] block = new byte[8 + data.length];
        ByteUtils.writeInt32(block, 0, data.length);
        ByteUtils.writeInt32(block, 4, BlockAttribute.IMAGESET_METAINFO.getCode());
        System.arraycopy(data, 0, block, 8, data.length);
        return block;
    }

    /** Returns true if the string can be parsed as valid XML. */
    private boolean isValidXml(String xml) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            db.setErrorHandler(new org.xml.sax.helpers.DefaultHandler()); // suppress stderr
            db.parse(new InputSource(new StringReader(xml)));
            return true;
        } catch (Exception e) {
            return false;
        }
    }


    private void writeSectionHeader(DataOutputStream dos, OirSection section,
                                     boolean v21) throws IOException {
        writeInt32LE(dos, section.getSectionId());
        writeInt32LE(dos, section.getVersionMajor());
        writeInt32LE(dos, section.getVersionMinor());
        writeInt32LE(dos, 0); // internal
        writeInt32LE(dos, 0); // internal

        if (section.isHasProductVersion() && v21) {
            writeInt32LE(dos, section.getProductVersionMajor());
            writeInt32LE(dos, section.getProductVersionMinor());
            writeInt32LE(dos, 0); // internal
            writeInt32LE(dos, 0); // internal
        }
    }

    // ── File writer ────────────────────────────────────────────────────────

    /**
     * Write the output OIR file:
     *   1. Copy original bytes up to (but not including) Index Range
     *   2. Append new IMAGESET_METAINFO block
     *   3. Append updated Index Range (sentinel + all original offsets + new block offset)
     *   4. Patch header at VERIFIED offsets (confirmed from spec + hex dump of real file):
     *        0x0020 = File Size       (8 bytes LE)
     *        0x0028 = IndexRange offset (8 bytes LE)
     *        0x0030 = Total Blocks    (8 bytes LE)
     */
    private void writeOutputFile(ParsedOirFile parsedFile, byte[] newBlock,
                                  File outputFile) throws IOException {
        File sourceFile = parsedFile.getSourceFile();
        OirHeader header = parsedFile.getHeader();
        long indexRangeOrig = header.getIndexRangeOffset();

        if (indexRangeOrig <= 0 || indexRangeOrig > sourceFile.length()) {
            throw new IOException("Invalid Index Range offset in header.");
        }

        // New layout positions
        long newBlockOffset      = indexRangeOrig;                         // appended right at old IR
        long newIndexRangeOffset = newBlockOffset + newBlock.length;
        long newTotalBlocks      = header.getTotalBlocks() + 1;
        long newFileSize         = newIndexRangeOffset + 4 + (newTotalBlocks * 8); // sentinel+offsets

        try (FileOutputStream fos = new FileOutputStream(outputFile);
             RandomAccessFile src = new RandomAccessFile(sourceFile, "r")) {

            // 1. Copy original header + data blocks (everything before old Index Range)
            byte[] buf = new byte[65536];
            long remaining = indexRangeOrig;
            src.seek(0);
            while (remaining > 0) {
                int toRead = (int) Math.min(buf.length, remaining);
                int read = src.read(buf, 0, toRead);
                if (read < 0) break;
                fos.write(buf, 0, read);
                remaining -= read;
            }

            // 2. Write the new IMAGESET_METAINFO block
            fos.write(newBlock);

            // 3. Write updated Index Range
            //    Sentinel: 0xFFFFFFFF (4 bytes)
            byte[] sentinel = new byte[4];
            ByteUtils.writeInt32(sentinel, 0, 0xFFFFFFFF);
            fos.write(sentinel);

            //    Original block offsets (read directly from source file's index range)
            src.seek(indexRangeOrig + 4); // skip old sentinel
            for (long i = 0; i < header.getTotalBlocks(); i++) {
                byte[] offBytes = new byte[8];
                src.readFully(offBytes);
                fos.write(offBytes);
            }

            //    New block offset (the block we just appended)
            byte[] newOffBytes = new byte[8];
            ByteUtils.writeInt64(newOffBytes, 0, newBlockOffset);
            fos.write(newOffBytes);
        }

        // 4. Patch header fields at VERIFIED byte offsets
        //    (confirmed from spec section 6.1.1.1 + hex dump: header attributes are at
        //     0x10=attrCount, 0x18=version, 0x20=fileSize, 0x28=indexRange, 0x30=totalBlocks)
        try (RandomAccessFile raf = new RandomAccessFile(outputFile, "rw")) {

            // ⑤ File Size at 0x0020
            raf.seek(0x0020);
            byte[] fs = new byte[8];
            ByteUtils.writeInt64(fs, 0, newFileSize);
            raf.write(fs);

            // ⑥ Index Range Offset at 0x0028
            raf.seek(0x0028);
            byte[] ir = new byte[8];
            ByteUtils.writeInt64(ir, 0, newIndexRangeOffset);
            raf.write(ir);

            // ⑦ Total Blocks at 0x0030
            raf.seek(0x0030);
            byte[] tb = new byte[8];
            ByteUtils.writeInt64(tb, 0, newTotalBlocks);
            raf.write(tb);
        }

        LOG.info(String.format(
            "Written: %s | size=%d | blocks=%d | indexRange=0x%X",
            outputFile.getName(), newFileSize, newTotalBlocks, newIndexRangeOffset));
    }

    // ── Validation ─────────────────────────────────────────────────────────

    private void validateTagNames(Set<String> names) throws IOException {
        for (String name : names) {
            if (name == null || name.isBlank())
                throw new IOException("Tag name cannot be blank.");
            if (!name.matches("[a-zA-Z_][a-zA-Z0-9_\\-\\.]*"))
                throw new IOException(
                    "Invalid XML tag name: \"" + name + "\". " +
                    "Must start with letter/underscore, then letters/digits/_ - .");
        }
    }

    // ── Helper ─────────────────────────────────────────────────────────────

    private void writeInt32LE(DataOutputStream dos, int value) throws IOException {
        byte[] buf = new byte[4];
        ByteUtils.writeInt32(buf, 0, value);
        dos.write(buf);
    }
}
