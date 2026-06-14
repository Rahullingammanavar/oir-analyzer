package com.olympus.oir.validator;

import com.olympus.oir.model.OirSection;
import com.olympus.oir.model.ParsedOirFile;

import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.InputStream;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Validates the 5 XML sections of an OIR file against their XSD schemas.
 *
 * Validation strategy (3 levels):
 *   Level 1 — Well-formedness: Catches corrupted / truncated XML (free, done by the
 *             parser internally; any exception here = ERROR).
 *   Level 2 — Schema validation: Verifies the root element matches the known Olympus
 *             namespace and element name. Lenient for child content (xs:any lax).
 *   Level 3 — Custom metadata check (programmatic): Verifies that if a
 *             &lt;customMetadata&gt; block exists its children have valid XML names.
 *
 * XSD files are loaded from the classpath under /xsd/.
 */
public class XsdValidator {

    private static final Logger LOG = Logger.getLogger(XsdValidator.class.getName());

    /**
     * Maps OirSection ID → classpath location of the XSD file.
     * Only the 5 pure XML sections are validated; binary sections are skipped.
     */
    private static final Map<Integer, String> SECTION_XSD = Map.of(
        OirSection.FILE_INFORMATION,   "/xsd/FILE_INFORMATION.xsd",
        OirSection.IMAGE_PROPERTIES,   "/xsd/IMAGE_PROPERTIES.xsd",
        OirSection.IMAGE_ANNOTATION,   "/xsd/IMAGE_ANNOTATION.xsd",
        OirSection.IMAGE_OVERLAY_ITEM, "/xsd/IMAGE_OVERLAY_ITEM.xsd",
        OirSection.EVENT_LIST,         "/xsd/EVENT_LIST.xsd"
    );

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Validates all XML sections in {@code parsedFile}.
     * Returns one {@link ValidationResult} per found XML section.
     */
    public List<ValidationResult> validateAll(ParsedOirFile parsedFile) {
        List<ValidationResult> results = new ArrayList<>();
        for (OirSection section : parsedFile.getSections()) {
            if (!SECTION_XSD.containsKey(section.getSectionId())) continue;
            String xml = section.getXmlContent();
            if (xml == null || xml.isBlank()) continue;
            results.add(validate(section.getSectionName(), section.getSectionId(), xml));
        }
        return results;
    }

    /**
     * Validates a single XML string against the XSD for the given section ID.
     *
     * @param sectionName display name (e.g. "IMAGE_ANNOTATION")
     * @param sectionId   OirSection constant (e.g. OirSection.IMAGE_ANNOTATION)
     * @param xml         raw XML string extracted from the OIR file
     * @return            a {@link ValidationResult} with VALID / WARNING / ERROR status
     */
    public ValidationResult validate(String sectionName, int sectionId, String xml) {
        String xsdPath = SECTION_XSD.get(sectionId);
        if (xsdPath == null) {
            return ValidationResult.warning(sectionName, sectionId,
                List.of("No XSD schema available for section ID " + sectionId));
        }

        List<String> issues = new ArrayList<>();

        // ── Level 2: XSD schema validation ───────────────────────────────────
        try (InputStream xsdStream = XsdValidator.class.getResourceAsStream(xsdPath)) {
            if (xsdStream == null) {
                issues.add("XSD file not found on classpath: " + xsdPath);
                return ValidationResult.warning(sectionName, sectionId, issues);
            }

            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            // Security: disable external entity/schema access
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD,    "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA,  "");

            Schema schema = factory.newSchema(new StreamSource(xsdStream));
            Validator validator = schema.newValidator();
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD,   "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");

            // Collect all schema errors without throwing on first error
            validator.setErrorHandler(new ErrorHandler() {
                @Override public void warning(SAXParseException e) {
                    issues.add("⚠  L" + e.getLineNumber() + ": " + simplify(e.getMessage()));
                }
                @Override public void error(SAXParseException e) {
                    issues.add("❌  L" + e.getLineNumber() + ": " + simplify(e.getMessage()));
                }
                @Override public void fatalError(SAXParseException e) {
                    issues.add("💥  L" + e.getLineNumber() + ": " + simplify(e.getMessage()));
                }
            });

            validator.validate(new StreamSource(new StringReader(xml)));

        } catch (Exception ex) {
            // Catches both XSD loading failures AND fatal XML parse errors (Level 1)
            String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            issues.add("💥  " + simplify(msg));
        }

        // ── Level 3: Custom metadata programmatic check ───────────────────────
        if (sectionId == OirSection.IMAGE_ANNOTATION) {
            issues.addAll(validateCustomMetadata(xml));
        }

        // ── Determine final status ────────────────────────────────────────────
        if (issues.isEmpty()) {
            return ValidationResult.valid(sectionName, sectionId);
        }
        boolean hasError = issues.stream().anyMatch(i -> i.startsWith("❌") || i.startsWith("💥"));
        return hasError
            ? ValidationResult.error(sectionName, sectionId, issues)
            : ValidationResult.warning(sectionName, sectionId, issues);
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Programmatically checks the &lt;customMetadata&gt; block if present.
     * Rules:
     *   - Each direct child of &lt;customMetadata&gt; must have a valid XML NCName.
     *   - No duplicate tag names within the same block.
     */
    private List<String> validateCustomMetadata(String xml) {
        List<String> issues = new ArrayList<>();
        if (!xml.contains("<customMetadata>")) return issues;  // block not present — skip

        try {
            javax.xml.parsers.DocumentBuilderFactory dbf =
                javax.xml.parsers.DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);

            org.w3c.dom.Document doc = dbf.newDocumentBuilder()
                .parse(new org.xml.sax.InputSource(new StringReader(xml)));
            doc.normalize();

            org.w3c.dom.NodeList cmNodes = doc.getElementsByTagName("customMetadata");
            if (cmNodes.getLength() == 0) return issues;

            org.w3c.dom.Node cm = cmNodes.item(0);
            List<String> tagNames = new ArrayList<>();

            org.w3c.dom.NodeList children = cm.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                org.w3c.dom.Node child = children.item(i);
                if (child.getNodeType() != org.w3c.dom.Node.ELEMENT_NODE) continue;

                String tagName = child.getLocalName() != null
                    ? child.getLocalName() : child.getNodeName();

                // Rule 1: valid XML NCName
                if (!tagName.matches("[a-zA-Z_][a-zA-Z0-9_\\-\\.]*")) {
                    issues.add("⚠  customMetadata: invalid tag name \"" + tagName + "\"");
                }
                // Rule 2: no duplicates
                if (tagNames.contains(tagName)) {
                    issues.add("⚠  customMetadata: duplicate tag \"" + tagName + "\"");
                } else {
                    tagNames.add(tagName);
                }
            }

        } catch (Exception ex) {
            issues.add("⚠  customMetadata check failed: " + ex.getMessage());
        }
        return issues;
    }

    /**
     * Strips verbose Saxon/Xerces namespace prefixes from error messages
     * so they are readable in the UI.
     */
    private String simplify(String msg) {
        if (msg == null) return "Unknown error";
        // Remove "cvc-..." schema violation codes — keep the human-readable part
        return msg.replaceAll("cvc-[^:]+: ", "").trim();
    }
}
