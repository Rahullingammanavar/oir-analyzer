package com.olympus.oir.extractor;

import com.olympus.oir.model.OirSection;
import com.olympus.oir.model.ParsedOirFile;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.*;
import java.util.logging.Logger;

/**
 * Extracts and pretty-prints XML metadata sections from a parsed OIR file.
 *
 * Targets the following sections from IMAGESET_METAINFO:
 *   - FILE_INFORMATION  (section 1)
 *   - IMAGE_PROPERTIES  (section 2)
 *   - IMAGE_ANNOTATION  (section 3)
 *   - EVENT_LIST        (section 14)
 *   - FRAME_PROPERTIES  (from RESOURCE_METAINFO)
 */
public class XmlMetadataExtractor {

    private static final Logger LOG = Logger.getLogger(XmlMetadataExtractor.class.getName());

    /**
     * Returns a map of section name → pretty-printed XML string for all available
     * XML-bearing sections in the parsed file.
     */
    public Map<String, String> extractAllXml(ParsedOirFile parsedFile) {
        Map<String, String> result = new LinkedHashMap<>();

        for (OirSection section : parsedFile.getSections()) {
            String xml = section.getXmlContent();
            if (xml != null && !xml.isBlank()) {
                String pretty = prettyPrint(xml);
                result.put(section.getSectionName(), pretty);
            }
        }

        return result;
    }

    /**
     * Returns the pretty-printed XML for a specific section, or an empty string.
     */
    public String extractSectionXml(ParsedOirFile parsedFile, int sectionId) {
        return parsedFile.findSection(sectionId)
                .map(OirSection::getXmlContent)
                .map(this::prettyPrint)
                .orElse("");
    }

    /**
     * Extract key metadata values from IMAGE_PROPERTIES XML.
     * Returns a map of property name → value string.
     */
    public Map<String, String> extractKeyMetadata(ParsedOirFile parsedFile) {
        Map<String, String> meta = new LinkedHashMap<>();

        // From header
        if (parsedFile.getHeader() != null) {
            meta.put("OIR Version",      parsedFile.getHeader().getVersionString());
            meta.put("File Size",        parsedFile.getHeader().getFileSize() + " bytes");
            meta.put("Total Blocks",     String.valueOf(parsedFile.getHeader().getTotalBlocks()));
            meta.put("Index Range",      "0x" + Long.toHexString(
                parsedFile.getHeader().getIndexRangeOffset()).toUpperCase());
            meta.put("Thumbnail Offset", "0x" + Long.toHexString(
                parsedFile.getHeader().getThumbnailMetainfoOffset()).toUpperCase());
            meta.put("Has Sections",     String.valueOf(parsedFile.getHeader().hasSections()));
            meta.put("Is V2.1+",         String.valueOf(parsedFile.getHeader().isV21OrLater()));
        }

        // Block summary
        long imagesetCount = parsedFile.getBlocks().stream()
            .filter(b -> b.getAttribute() != null &&
                b.getAttribute().getCode() == 0).count();
        long resourceCount = parsedFile.getBlocks().stream()
            .filter(b -> b.getAttribute() != null &&
                b.getAttribute().getCode() == 1).count();
        long bitmapCount = parsedFile.getBlocks().stream()
            .filter(b -> b.getAttribute() != null &&
                b.getAttribute().getCode() == 4).count();

        meta.put("IMAGESET_METAINFO blocks", String.valueOf(imagesetCount));
        meta.put("RESOURCE_METAINFO blocks", String.valueOf(resourceCount));
        meta.put("IMAGE_BITMAP blocks",      String.valueOf(bitmapCount));
        meta.put("Sections parsed",          String.valueOf(parsedFile.getSections().size()));
        meta.put("Thumbnail available",      String.valueOf(parsedFile.getThumbnailBytes() != null));

        // Warnings
        if (!parsedFile.getWarnings().isEmpty()) {
            meta.put("⚠ Warnings", String.valueOf(parsedFile.getWarnings().size()));
        }

        return meta;
    }

    /**
     * Pretty-print an XML string with 2-space indentation.
     * Returns the original string if parsing fails (e.g., it's not valid XML).
     */
    public String prettyPrint(String rawXml) {
        if (rawXml == null || rawXml.isBlank()) return "";

        String trimmed = rawXml.trim();

        // Quick sanity: must start with '<'
        if (!trimmed.startsWith("<")) {
            return trimmed;
        }

        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new InputSource(new StringReader(trimmed)));
            doc.normalize();

            TransformerFactory tf = TransformerFactory.newInstance();
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");

            StringWriter sw = new StringWriter();
            t.transform(new DOMSource(doc), new StreamResult(sw));
            return sw.toString().trim();

        } catch (Exception ex) {
            LOG.fine("XML pretty-print failed: " + ex.getMessage() + " — returning raw");
            return trimmed;
        }
    }
}
