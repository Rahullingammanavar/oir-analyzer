package com.oirextractor.validator;

import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.StringReader;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Validates extracted OIR XML sections against their respective XSD schemas.
 * XSD paths are loaded from a configuration file so official schemas can be
 * plugged in later without code changes.
 */
public class XMLValidator {

    private static final Logger LOG = Logger.getLogger(XMLValidator.class.getName());

    private final Properties config;
    private final ValidationReport report;

    /**
     * Creates a new XMLValidator.
     *
     * @param sourceFileName Name of the OIR file being validated (for the report)
     * @param configFilePath Path to the properties file containing XSD paths
     */
    public XMLValidator(String sourceFileName, String configFilePath) {
        this.report = new ValidationReport(sourceFileName);
        this.config = new Properties();
        loadConfig(configFilePath);
    }

    private void loadConfig(String configFilePath) {
        try (InputStream is = new FileInputStream(configFilePath)) {
            config.load(is);
            LOG.info("Loaded XSD configuration from " + configFilePath);
        } catch (Exception e) {
            LOG.warning("Could not load validation config from " + configFilePath + ": " + e.getMessage());
            // We continue. Validation will fail gracefully if keys are missing.
        }
    }

    /**
     * Validates a single extracted XML section. If validation fails, it logs the error
     * but does NOT crash, adding the result to the ongoing ValidationReport.
     *
     * @param sectionName The display name of the section (e.g., "FileInformation", "FrameProperties[1]")
     * @param xmlContent  The raw extracted XML string
     */
    public void validateSection(String sectionName, String xmlContent) {
        if (xmlContent == null || xmlContent.isBlank()) {
            report.addResult(new ValidationReport.ValidationResult(sectionName, false, "XML content is empty or null"));
            return;
        }

        // Strip index like "[1]" from "FrameProperties[1]" to match config key
        String configKey = sectionName.replaceAll("\\[\\d+\\]$", "");
        String xsdPath = config.getProperty("xsd." + configKey);

        if (xsdPath == null || xsdPath.isBlank()) {
            report.addResult(new ValidationReport.ValidationResult(
                sectionName, false, "No XSD path configured for section type: " + configKey));
            return;
        }

        File xsdFile = new File(xsdPath);
        if (!xsdFile.exists()) {
            report.addResult(new ValidationReport.ValidationResult(
                sectionName, false, "Configured XSD file not found: " + xsdPath));
            return;
        }

        try {
            // Setup SchemaFactory and Validator
            SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            Schema schema = factory.newSchema(xsdFile);
            Validator validator = schema.newValidator();

            // Validate
            validator.validate(new StreamSource(new StringReader(xmlContent)));

            // If we get here, it passed!
            report.addResult(new ValidationReport.ValidationResult(sectionName, true, null));
            LOG.info("Validation PASSED for section: " + sectionName);

        } catch (SAXException e) {
            // Validation failed
            String errMsg = extractLineNumberAndMessage(e);
            report.addResult(new ValidationReport.ValidationResult(sectionName, false, errMsg));
            LOG.warning("Validation FAILED for section: " + sectionName + " - " + errMsg);
        } catch (Exception e) {
            // Other fatal errors (e.g., IO reading string)
            report.addResult(new ValidationReport.ValidationResult(sectionName, false, "System Error: " + e.getMessage()));
            LOG.severe("System error validating section: " + sectionName + " - " + e.getMessage());
        }
    }

    /**
     * Returns the complete ValidationReport object containing all results.
     */
    public ValidationReport getReport() {
        return report;
    }

    /**
     * Helper to format SAXException details elegantly.
     */
    private String extractLineNumberAndMessage(SAXException e) {
        if (e instanceof org.xml.sax.SAXParseException) {
            org.xml.sax.SAXParseException spe = (org.xml.sax.SAXParseException) e;
            return "Line " + spe.getLineNumber() + ": " + spe.getMessage();
        }
        return e.getMessage();
    }
}
