/**
 * OIR File Analyzer — Java Module Descriptor
 *
 * Module: com.olympus.oir
 * Requires JavaFX modules for GUI, java.xml for JAXP DOM parsing,
 * and java.desktop for javax.imageio (BMP image loading).
 */
module com.olympus.oir {
    // ── JavaFX ──────────────────────────────────────────────
    requires javafx.controls;
    requires javafx.fxml;
    requires javafx.swing;       // SwingFXUtils.toFXImage()

    // ── JDK ─────────────────────────────────────────────────
    requires java.xml;           // javax.xml.parsers, org.w3c.dom
    requires java.desktop;       // javax.imageio (BMP decode)
    requires java.logging;

    // ── JDOM2 (for building structured experiment_metadata.xml) ──
    requires org.jdom2;

    // ── ImageJ (for 16-bit TIFF extraction and overlay) ──
    requires ij;

    // ── Opens / Exports ─────────────────────────────────────
    // Allow JavaFX reflection into our UI packages (for property binding etc.)
    opens com.olympus.oir.ui to javafx.fxml, javafx.graphics;
    opens com.oirextractor.ui to javafx.fxml, javafx.graphics;
    opens com.olympus.oir.model to javafx.base;

    exports com.olympus.oir;
    exports com.olympus.oir.model;
    exports com.olympus.oir.parser;
    exports com.olympus.oir.extractor;
    exports com.olympus.oir.writer;
    exports com.olympus.oir.util;
    exports com.olympus.oir.ui;
    
    // New Extractor architecture exports
    exports com.oirextractor.ui;
    exports com.oirextractor.validator;
    exports com.oirextractor.extractor;
}
