package com.olympus.oir.ui;

import com.olympus.oir.extractor.ImageExtractor;
import com.olympus.oir.extractor.ThumbnailExtractor;
import com.olympus.oir.extractor.XmlMetadataExtractor;
import com.olympus.oir.model.*;
import com.olympus.oir.parser.OirParser;
import com.olympus.oir.util.MockOirGenerator;
import com.olympus.oir.validator.ValidationResult;
import com.olympus.oir.validator.XsdValidator;
import com.olympus.oir.writer.CustomMetadataWriter;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import org.w3c.dom.*;
import org.xml.sax.InputSource;
import javax.xml.parsers.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * KISS UI — 3 controls, 1 split view.
 *
 *  ┌──────────────────────────────────────────────────────┐
 *  │  [📂 Open File]   🔬 OIR Analyzer   [🏷 Add Tags]  │
 *  ├────────────────────────┬─────────────────────────────┤
 *  │  XML Tree (hierarchy)  │  Thumbnail Image            │
 *  │  ▶ FILE_INFORMATION   │                             │
 *  │    ▶ dataName: ...    │   [BMP displayed here]      │
 *  │  ▶ IMAGE_PROPERTIES   │                             │
 *  │    ▶ objective: ...   │                             │
 *  ├────────────────────────┴─────────────────────────────┤
 *  │  status bar                                          │
 *  └──────────────────────────────────────────────────────┘
 */
public class MainController {

    private static final Logger LOG = Logger.getLogger(MainController.class.getName());

    private final Stage stage;
    private ParsedOirFile currentFile;

    // ── UI refs ────────────────────────────────────────────────────────────
    private TreeView<String> xmlTree;
    private ImageView        thumbnailView;
    private Label            thumbInfoLabel;
    private Label            statusLabel;
    private ProgressBar      progressBar;
    private Button           exportXmlBtn;
    private Button           headerBtn;

    // ── Frame Extractor UI refs ──────────────────────────────────────────────
    private ComboBox<ImageExtractor.ChannelInfo> channelCombo;
    private Spinner<Integer>                     frameSpinner;
    private ImageView                            frameImageView;
    private Button                               exportFrameBtn;
    private Label                                frameInfoLabel;

    /** Validation status bar — shown below the tree header after file load. */
    private HBox             validationBar;

    /** Maps section-level TreeItems → OirSection for right-click tag injection. */
    private final Map<TreeItem<String>, OirSection> sectionItemMap = new HashMap<>();

    public MainController(Stage stage) { this.stage = stage; }

    // ── Build Scene ────────────────────────────────────────────────────────

    public Scene buildScene() {
        BorderPane root = new BorderPane();
        root.setTop(buildTopBar());
        root.setCenter(buildCenter());
        root.setBottom(buildStatusBar());

        Scene scene = new Scene(root, 1100, 700);
        var css = getClass().getResource("/css/dark-theme.css");
        if (css != null) scene.getStylesheets().add(css.toExternalForm());
        return scene;
    }

    // ── Top bar: [Open] [Export XML] [Header] | title  ─────────────────────
    // Tags are added via right-click on the XML tree — no toolbar button needed.

    private HBox buildTopBar() {
        HBox bar = new HBox();
        bar.getStyleClass().add("top-bar");
        bar.setAlignment(Pos.CENTER);
        bar.setPadding(new Insets(10, 20, 10, 20));
        bar.setSpacing(10);

        // Left group
        Button openBtn = new Button("📂  Open OIR File");
        openBtn.getStyleClass().addAll("toolbar-btn", "btn-accent");
        openBtn.setOnAction(e -> onOpenFile());

        exportXmlBtn = new Button("⬇  Download XML");
        exportXmlBtn.getStyleClass().addAll("toolbar-btn", "btn-secondary");
        exportXmlBtn.setDisable(true);
        exportXmlBtn.setOnAction(e -> onExportXml());

        headerBtn = new Button("🗒  File Header");
        headerBtn.getStyleClass().addAll("toolbar-btn", "btn-secondary");
        headerBtn.setDisable(true);
        headerBtn.setOnAction(e -> onShowHeader());

        // Centre title
        Label title = new Label("🔬  OIR File Analyzer");
        title.getStyleClass().add("title-label");

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        bar.getChildren().addAll(
            openBtn, exportXmlBtn, headerBtn,
            spacer, title);
        return bar;
    }


    // ── Center: XML Tree (left) | Thumbnail (right) ────────────────────────

    private SplitPane buildCenter() {
        // ── Left: XML tree ────────────────────────────────────────────────
        xmlTree = new TreeView<>();
        xmlTree.getStyleClass().add("xml-tree");
        xmlTree.setShowRoot(true);

        TreeItem<String> placeholder = new TreeItem<>("📄 No file loaded");
        xmlTree.setRoot(placeholder);

        // Cell factory: style root, section, and leaf nodes differently
        xmlTree.setCellFactory(tv -> new TreeCell<>() {
            // One shared context menu per cell (re-used across recycles)
            private final ContextMenu ctxMenu = buildCellContextMenu();

            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null); setGraphic(null);
                    getStyleClass().removeAll("tree-root","tree-section","tree-leaf");
                    setContextMenu(null);
                    return;
                }
                setText(item);
                TreeItem<String> ti = getTreeItem();
                getStyleClass().removeAll("tree-root", "tree-section", "tree-leaf");
                if (ti != null && ti.getParent() == null)                                              getStyleClass().add("tree-root");
                else if (ti != null && ti.getParent() != null && ti.getParent().getParent() == null)   getStyleClass().add("tree-section");
                else                                                                                   getStyleClass().add("tree-leaf");

                // Show "Add Tag Here" context menu for any node inside a writable XML section
                OirSection sec = findSectionFor(ti);
                boolean writable = sec != null
                    && CustomMetadataWriter.XML_SECTION_NAMES.containsKey(sec.getSectionId())
                    && sec.getXmlContent() != null && !sec.getXmlContent().isBlank();
                setContextMenu(writable ? ctxMenu : null);
            }
        });

        VBox leftPane = new VBox(0);

        // Tree header bar: title + expand/collapse buttons
        Label treeHeader = new Label("  📋  XML Metadata");
        treeHeader.getStyleClass().add("pane-header");
        HBox.setHgrow(treeHeader, Priority.ALWAYS);
        treeHeader.setMaxWidth(Double.MAX_VALUE);

        Button expandAllBtn  = new Button("⊞ Expand All");
        Button collapseAllBtn = new Button("⊟ Collapse All");
        expandAllBtn.getStyleClass().addAll("toolbar-btn", "btn-secondary");
        collapseAllBtn.getStyleClass().addAll("toolbar-btn", "btn-secondary");
        expandAllBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8 3 8;");
        collapseAllBtn.setStyle("-fx-font-size: 11px; -fx-padding: 3 8 3 8;");

        expandAllBtn.setOnAction(e  -> setAllExpanded(xmlTree.getRoot(), true));
        collapseAllBtn.setOnAction(e -> {
            setAllExpanded(xmlTree.getRoot(), false);
            // Always keep root expanded so sections are visible
            if (xmlTree.getRoot() != null) xmlTree.getRoot().setExpanded(true);
        });

        HBox treeTopBar = new HBox(6, treeHeader, expandAllBtn, collapseAllBtn);
        treeTopBar.setAlignment(Pos.CENTER_LEFT);
        treeTopBar.setStyle("-fx-background-color: #1a1a2e; -fx-padding: 0 6 0 0;");

        // Validation bar — hidden until a file is loaded
        validationBar = new HBox(6);
        validationBar.setAlignment(Pos.CENTER_LEFT);
        validationBar.setPadding(new Insets(4, 10, 4, 10));
        validationBar.setStyle("-fx-background-color: #0d0d1a; -fx-border-color: #1e3a5f; -fx-border-width: 0 0 1 0;");
        validationBar.setVisible(false);
        validationBar.setManaged(false);

        VBox.setVgrow(xmlTree, Priority.ALWAYS);
        leftPane.getChildren().addAll(treeTopBar, validationBar, xmlTree);


        // ── Right: Thumbnail ───────────────────────────────────────────────
        thumbnailView = new ImageView();
        thumbnailView.setPreserveRatio(true);
        thumbnailView.setSmooth(true);

        Label noImgLabel = new Label("🖼\n\nNo thumbnail\nOpen an OIR file\nto view the image");
        noImgLabel.getStyleClass().add("placeholder-text");
        noImgLabel.setAlignment(Pos.CENTER);

        thumbInfoLabel = new Label("Format: —  |  Size: —");
        thumbInfoLabel.getStyleClass().add("thumb-info");

        StackPane imgStack = new StackPane(noImgLabel, thumbnailView);
        imgStack.getStyleClass().add("image-pane");
        imgStack.setAlignment(Pos.CENTER);

        thumbnailView.imageProperty().addListener((obs, o, img) -> {
            noImgLabel.setVisible(img == null);
            thumbnailView.setVisible(img != null);
        });
        thumbnailView.setVisible(false);

        // Bind thumbnail fitWidth/Height to imgStack size minus padding
        imgStack.widthProperty().addListener((obs, o, w) ->
            thumbnailView.setFitWidth(w.doubleValue() - 20));
        imgStack.heightProperty().addListener((obs, o, h) ->
            thumbnailView.setFitHeight(h.doubleValue() - 50));

        VBox rightPane = new VBox(0);
        Label imgHeader = new Label("  🖼  Thumbnail Preview");
        imgHeader.getStyleClass().add("pane-header");
        imgHeader.setMaxWidth(Double.MAX_VALUE);
        VBox.setVgrow(imgStack, Priority.ALWAYS);
        rightPane.getChildren().addAll(imgHeader, imgStack, thumbInfoLabel);


        // ── Right: Frame Extractor ──────────────────────────────────────────
        VBox frameExtPane = new VBox(0);
        Label frameHeader = new Label("  🔬  Image Frame Extraction");
        frameHeader.getStyleClass().add("pane-header");
        frameHeader.setMaxWidth(Double.MAX_VALUE);

        channelCombo = new ComboBox<>();
        channelCombo.setPromptText("Select Channel");
        channelCombo.getStyleClass().add("combo-box");
        channelCombo.setDisable(true);

        frameSpinner = new Spinner<>(1, 1, 1);
        frameSpinner.getStyleClass().add("spinner");
        frameSpinner.setDisable(true);
        frameSpinner.setEditable(true);

        exportFrameBtn = new Button("⬇  Export Frame");
        exportFrameBtn.getStyleClass().addAll("toolbar-btn", "btn-secondary");
        exportFrameBtn.setDisable(true);
        exportFrameBtn.setOnAction(e -> onExportFrame());

        HBox controlsBox = new HBox(10,
            new Label("Channel:"), channelCombo,
            new Label("Frame:"), frameSpinner,
            exportFrameBtn
        );
        controlsBox.setAlignment(Pos.CENTER_LEFT);
        controlsBox.setPadding(new Insets(10, 16, 10, 16));
        controlsBox.setStyle("-fx-background-color: #1a1a2e;");

        frameImageView = new ImageView();
        frameImageView.setPreserveRatio(true);
        frameImageView.setSmooth(true);

        Label noFrameLabel = new Label("🖼\n\nNo frame loaded\nOpen an OIR file and\nselect a channel/frame");
        noFrameLabel.getStyleClass().add("placeholder-text");
        noFrameLabel.setAlignment(Pos.CENTER);

        StackPane frameImgStack = new StackPane(noFrameLabel, frameImageView);
        frameImgStack.getStyleClass().add("image-pane");
        frameImgStack.setAlignment(Pos.CENTER);

        frameImageView.imageProperty().addListener((obs, o, img) -> {
            noFrameLabel.setVisible(img == null);
            frameImageView.setVisible(img != null);
        });
        frameImageView.setVisible(false);

        frameInfoLabel = new Label("Format: —  |  Size: —");
        frameInfoLabel.getStyleClass().add("thumb-info");

        // Bind frame image dimensions to stack size
        frameImgStack.widthProperty().addListener((obs, o, w) ->
            frameImageView.setFitWidth(w.doubleValue() - 20));
        frameImgStack.heightProperty().addListener((obs, o, h) ->
            frameImageView.setFitHeight(h.doubleValue() - 50));

        VBox.setVgrow(frameImgStack, Priority.ALWAYS);
        frameExtPane.getChildren().addAll(frameHeader, controlsBox, frameImgStack, frameInfoLabel);


        // ── TabPane wrapping both views ──────────────────────────────────────
        TabPane rightTabPane = new TabPane();
        rightTabPane.getStyleClass().add("right-tab-pane");

        Tab thumbTab = new Tab(" Thumbnail ", rightPane);
        thumbTab.setClosable(false);
        Tab extractTab = new Tab(" Frame Extractor ", frameExtPane);
        extractTab.setClosable(false);

        rightTabPane.getTabs().addAll(thumbTab, extractTab);

        SplitPane split = new SplitPane(leftPane, rightTabPane);
        split.getStyleClass().add("main-split");
        split.setDividerPositions(0.55);
        return split;
    }

    // ── Status bar ─────────────────────────────────────────────────────────

    private HBox buildStatusBar() {
        HBox bar = new HBox(10);
        bar.getStyleClass().add("status-bar");
        bar.setPadding(new Insets(5, 16, 5, 16));
        bar.setAlignment(Pos.CENTER_LEFT);

        statusLabel = new Label("Ready — open an OIR file to begin.");
        statusLabel.getStyleClass().add("status-label");

        progressBar = new ProgressBar();
        progressBar.getStyleClass().add("progress-bar");
        progressBar.setPrefWidth(120);
        progressBar.setVisible(false);

        Region sp = new Region();
        HBox.setHgrow(sp, Priority.ALWAYS);

        Label spec = new Label("OIR Spec v6 | Java 17 + JavaFX 21");
        spec.getStyleClass().add("spec-label");

        bar.getChildren().addAll(statusLabel, progressBar, sp, spec);
        return bar;
    }

    // ── Event: Open File ───────────────────────────────────────────────────

    private void onOpenFile() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Open OIR File");
        fc.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("OIR Files", "*.oir"),
            new FileChooser.ExtensionFilter("All Files", "*.*")
        );
        File chosen = fc.showOpenDialog(stage);
        if (chosen != null) loadOirFile(chosen);
    }

    // ── Event: Download XML ────────────────────────────────────────────────
    // Saves only the 5 pure XML sections: FILE_INFORMATION, IMAGE_PROPERTIES,
    // IMAGE_ANNOTATION, IMAGE_OVERLAY_ITEM, EVENT_LIST.
    // Binary loop sections (LUT, FRAME_LOCATION, etc.) are excluded automatically
    // by XmlMetadataExtractor.extractAllXml().

    private void onExportXml() {
        if (currentFile == null) return;

        FileChooser fc = new FileChooser();
        fc.setTitle("Save XML Metadata");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("XML File", "*.xml"));
        fc.setInitialFileName(
            currentFile.getSourceFile().getName().replace(".oir", "_metadata.xml"));
        File out = fc.showSaveDialog(stage);
        if (out == null) return;

        try (PrintWriter pw = new PrintWriter(new FileWriter(out))) {
            pw.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            pw.println("<oirMetadata>");
            pw.println("  <source>" + currentFile.getSourceFile().getName() + "</source>");
            pw.println("  <oirVersion>" + currentFile.getHeader().getVersionString() + "</oirVersion>");

            // extractAllXml returns ONLY the 5 XML sections — no binary data
            XmlMetadataExtractor extractor = new XmlMetadataExtractor();
            extractor.extractAllXml(currentFile).forEach((sectionName, xml) -> {
                pw.println("\n  <!-- " + sectionName + " -->");
                pw.println("  <section name=\"" + sectionName + "\">");
                for (String line : xml.split("\n"))
                    pw.println("    " + line);
                pw.println("  </section>");
            });

            pw.println("</oirMetadata>");
            setStatus("✅  XML saved → " + out.getName());

        } catch (Exception ex) {
            showError("Failed to save XML", ex);
        }
    }


    // ── Event: Show File Header ────────────────────────────────────────────

    private void onShowHeader() {
        if (currentFile == null) return;
        OirHeader h = currentFile.getHeader();

        // Build a clean table-style text
        String[][] rows = {
            { "Magic Word",          h.getMagicWord() },
            { "OIR Version",         h.getVersionString() },
            { "Header Size",         h.getHeaderSize() + " bytes  (" + (h.isV21OrLater() ? "v2.1+ / 96B" : "v1.x / 80B") + ")" },
            { "File Size",           String.format("%,d bytes  (%.2f MB)", h.getFileSize(), h.getFileSize() / 1_048_576.0) },
            { "Total Blocks",        String.valueOf(h.getTotalBlocks()) },
            { "Block Attr Types",    String.valueOf(h.getBlockAttributeCount()) + "  (fixed = 6)" },
            { "Index Range Offset",  String.format("0x%08X  (%d)", h.getIndexRangeOffset(), h.getIndexRangeOffset()) },
            { "Thumbnail Offset",    String.format("0x%08X  (%d)", h.getThumbnailMetainfoOffset(), h.getThumbnailMetainfoOffset()) },
            { "Magic Valid",         h.isMagicValid() ? "✅  YES" : "❌  NO" },
            { "Has Sections (≥1.2)", h.hasSections() ? "Yes" : "No" },
            { "Is v2.1+",            h.isV21OrLater() ? "Yes  →  96-byte header" : "No  →  80-byte header" },
        };

        // Optionally add v2.1 product version
        String extra = "";
        if (h.isV21OrLater()) {
            extra = "\n" + String.format("  %-22s  %s", "Product Version",
                h.getProductVersionMajor() + "." + h.getProductVersionMinor());
        }

        StringBuilder sb = new StringBuilder();
        for (String[] row : rows) {
            sb.append(String.format("  %-22s  %s%n", row[0], row[1]));
        }
        sb.append(extra);

        Alert dlg = new Alert(Alert.AlertType.INFORMATION);
        dlg.setTitle("OIR File Header");
        dlg.setHeaderText("📋  Header Range — " + currentFile.getSourceFile().getName());
        dlg.setContentText(sb.toString());
        dlg.getDialogPane().setPrefWidth(520);
        dlg.getDialogPane().setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 13px;");
        var css = getClass().getResource("/css/dark-theme.css");
        if (css != null) dlg.getDialogPane().getStylesheets().add(css.toExternalForm());
        dlg.show();
    }

    private void loadOirFile(File file) {
        setStatus("Parsing " + file.getName() + " …");
        progressBar.setVisible(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);

        Task<ParsedOirFile> task = new Task<>() {
            @Override protected ParsedOirFile call() throws Exception {
                return new OirParser(file).parse();
            }
        };

        task.setOnSucceeded(e -> {
            currentFile = task.getValue();
            progressBar.setVisible(false);
            exportXmlBtn.setDisable(false);
            headerBtn.setDisable(false);
            populateXmlTree(currentFile);
            populateThumbnail(currentFile);
            initFrameExtractor(currentFile); // Initialize frame extractor controls
            runValidation(currentFile);   // XSD validation runs after tree is built
            OirHeader h = currentFile.getHeader();
            setStatus("✅  " + file.getName() + "  |  OIR v" + h.getVersionString()
                + "  |  " + currentFile.getBlocks().size() + " blocks"
                + "  |  " + currentFile.getSections().size() + " sections");
        });

        task.setOnFailed(e -> {
            progressBar.setVisible(false);
            showError("Cannot parse file", task.getException());
        });

        new Thread(task, "oir-parser").start();
    }

    // ── XSD Validation ──────────────────────────────────────────────────

    /**
     * Runs XSD validation on a background thread, then updates the validation bar
     * on the JavaFX Application Thread.
     * Each section gets a coloured pill label. Click any pill to see detail.
     */
    private void runValidation(ParsedOirFile pf) {
        Task<List<ValidationResult>> vTask = new Task<>() {
            @Override protected List<ValidationResult> call() {
                return new XsdValidator().validateAll(pf);
            }
        };

        vTask.setOnSucceeded(ev -> {
            List<ValidationResult> results = vTask.getValue();
            updateValidationBar(results);
        });

        vTask.setOnFailed(ev -> {
            // Validation failure doesn’t block anything — just hide the bar
            validationBar.setVisible(false);
            validationBar.setManaged(false);
        });

        new Thread(vTask, "xsd-validator").start();
    }

    /**
     * Populates the validation bar with one coloured pill per validated section.
     * Click a pill to see the full validation detail in an alert.
     */
    private void updateValidationBar(List<ValidationResult> results) {
        validationBar.getChildren().clear();

        if (results.isEmpty()) {
            validationBar.setVisible(false);
            validationBar.setManaged(false);
            return;
        }

        // Summary label on the left
        long validCount = results.stream().filter(ValidationResult::isValid).count();
        boolean allValid = validCount == results.size();
        Label summaryLbl = new Label(
            allValid ? "🛡  Validation" : "🛡  Validation — " + validCount + "/" + results.size() + " valid");
        summaryLbl.setStyle("-fx-text-fill: " + (allValid ? "#4ecdc4" : "#e0a050")
            + "; -fx-font-size: 11px; -fx-font-weight: bold;");
        validationBar.getChildren().add(summaryLbl);

        // One pill per section
        for (ValidationResult r : results) {
            Label pill = new Label(r.getStatusEmoji() + "  " + r.getSectionName());
            pill.setPadding(new Insets(2, 8, 2, 8));
            pill.setStyle(
                "-fx-background-radius: 10; -fx-font-size: 10px; -fx-cursor: hand; "
                + "-fx-text-fill: #e0e0e0; -fx-background-color: "
                + switch (r.getStatus()) {
                    case VALID   -> "#1a3a2a";
                    case WARNING -> "#3a2a10";
                    case ERROR   -> "#3a1010";
                } + ";");

            // Click pill → show detail alert
            pill.setOnMouseClicked(e -> showValidationDetail(r));
            validationBar.getChildren().add(pill);
        }

        validationBar.setVisible(true);
        validationBar.setManaged(true);
    }

    /** Shows a detail dialog for a single section’s validation result. */
    private void showValidationDetail(ValidationResult r) {
        Alert alert = new Alert(
            r.isValid() ? Alert.AlertType.INFORMATION : Alert.AlertType.WARNING);
        alert.setTitle("XSD Validation — " + r.getSectionName());
        alert.setHeaderText(r.getStatusEmoji() + "  " + r.getSectionName()
            + "  [" + r.getStatus() + "]");

        if (r.getMessages().isEmpty()) {
            alert.setContentText("✅  XML is well-formed and passes schema validation.");
        } else {
            String detail = String.join("\n", r.getMessages());
            alert.setContentText(detail);
        }

        alert.getDialogPane().setPrefWidth(560);
        alert.getDialogPane().setStyle("-fx-font-family: 'Consolas', monospace; -fx-font-size: 12px;");
        var css = getClass().getResource("/css/dark-theme.css");
        if (css != null) alert.getDialogPane().getStylesheets().add(css.toExternalForm());
        alert.show();
    }

    // ── Populate XML Tree ──────────────────────────────────────────────────
    // Shows: file root → XML sections only (Header via toolbar button).
    // No blocks list, no warnings node — KISS.

    private void populateXmlTree(ParsedOirFile pf) {
        sectionItemMap.clear();  // fresh map for every file load

        String fname = pf.getSourceFile().getName();
        OirHeader h  = pf.getHeader();

        TreeItem<String> root = new TreeItem<>(
            "📄 " + fname + "  [OIR v" + h.getVersionString() + "]");
        root.setExpanded(true);

        // ── Sort sections by their canonical spec ID (1→14) so order never changes ──
        List<OirSection> sections = pf.getSections().stream()
            .sorted(Comparator.comparingInt(OirSection::getSectionId))
            .toList();

        if (sections.isEmpty()) {
            root.getChildren().add(new TreeItem<>("  (no XML sections found)"));
        }

        for (OirSection sec : sections) {
            TreeItem<String> secNode = section(sec.getSectionName());
            sectionItemMap.put(secNode, sec);  // register so right-click can find it
            secNode.setExpanded(false);

            // XML content → DOM tree
            String xml = sec.getXmlContent();
            if (xml != null && !xml.isBlank()) {
                try {
                    Document doc = parseXml(xml);
                    buildDomTree(doc.getDocumentElement(), secNode);
                } catch (Exception ignore) {
                    addLeaf(secNode, "raw", xml.trim().substring(0,
                        Math.min(xml.trim().length(), 200)));
                }
            }

            // Loop sections (FRAME_LOCATION, FRAME_FRAGMENT_LOCATION, LUT etc.)
            List<Map.Entry<String, Object>> loops = sec.getLoopEntries();
            if (!loops.isEmpty()) {
                TreeItem<String> loopNode = new TreeItem<>("  ↳ entries (" + loops.size() + ")");
                loopNode.setExpanded(false);
                for (Map.Entry<String, Object> entry : loops) {
                    loopNode.getChildren().add(
                        new TreeItem<>(entry.getKey() + ":  " + entry.getValue()));
                }
                secNode.getChildren().add(loopNode);
            }

            // Always add section to tree — even if empty (don't silently hide IMAGE_OVERLAY_ITEM etc.)
            if (secNode.getChildren().isEmpty()) {
                secNode.getChildren().add(new TreeItem<>("  (empty section — no content)"));
            }
            root.getChildren().add(secNode);
        }

        xmlTree.setRoot(root);
    }


    // Recursively build TreeItems from a DOM element
    private void buildDomTree(Element el, TreeItem<String> parent) {
        NodeList children = el.getChildNodes();
        boolean hasElementChildren = false;

        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) {
                hasElementChildren = true;
                Element child = (Element) n;
                String text = child.getTextContent().trim();

                // Leaf element (no child elements)
                if (!hasChildElements(child)) {
                    String label = child.getTagName();
                    // Include attributes if any
                    if (child.hasAttributes()) {
                        StringBuilder attrs = new StringBuilder();
                        NamedNodeMap nm = child.getAttributes();
                        for (int a = 0; a < nm.getLength(); a++) {
                            Attr attr = (Attr) nm.item(a);
                            attrs.append(" ").append(attr.getName()).append("=").append(attr.getValue());
                        }
                        label += " [" + attrs.toString().trim() + "]";
                    }
                    addLeaf(parent, label, text.isEmpty() ? "—" : truncate(text, 80));
                } else {
                    // Container element
                    TreeItem<String> childNode = section("▶ " + child.getTagName());
                    buildDomTree(child, childNode);
                    parent.getChildren().add(childNode);
                }
            }
        }

        // If element has no children but has text + attributes, show inline
        if (!hasElementChildren) {
            String text = el.getTextContent().trim();
            if (!text.isEmpty()) {
                addLeaf(parent, el.getTagName(), truncate(text, 80));
            }
        }
    }

    private boolean hasChildElements(Element el) {
        NodeList nl = el.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++)
            if (nl.item(i).getNodeType() == Node.ELEMENT_NODE) return true;
        return false;
    }

    // ── Populate Thumbnail ─────────────────────────────────────────────────

    private void populateThumbnail(ParsedOirFile pf) {
        ThumbnailExtractor te = new ThumbnailExtractor();
        Image img = te.extractToFxImage(pf);
        thumbnailView.setImage(img);

        if (img != null) {
            thumbInfoLabel.setText(te.getSummary(pf)
                + String.format("  |  %d × %d px", (int) img.getWidth(), (int) img.getHeight()));
        } else {
            thumbInfoLabel.setText("No thumbnail in this file");
        }
    }


    /** Update info text below the section ComboBox. */
    private void updateSectionInfo(Label label, OirSection sec) {
        if (sec == null) { label.setText(""); return; }
        String desc = switch (sec.getSectionId()) {
            case OirSection.FILE_INFORMATION  -> "Experiment name, creation date, researcher info";
            case OirSection.IMAGE_PROPERTIES  -> "Microscope settings — objective, laser, pixel size";
            case OirSection.IMAGE_ANNOTATION  -> "ROI annotations and measurements";
            case OirSection.IMAGE_OVERLAY_ITEM-> "Display overlays (line type, font, color)";
            case OirSection.EVENT_LIST        -> "Experiment event log";
            default -> "";
        };
        label.setText("→ " + desc);
    }

    // ── Context-menu helpers ───────────────────────────────────────────────

    /**
     * Build the ContextMenu shown on every tree cell that belongs to a writable
     * XML section. The menu item triggers onAddTagToSection() using whichever
     * item is currently selected in the tree.
     */
    private ContextMenu buildCellContextMenu() {
        MenuItem addHere = new MenuItem("🏷  Add Tag Here");
        addHere.setOnAction(e -> {
            TreeItem<String> selected = xmlTree.getSelectionModel().getSelectedItem();
            OirSection sec = findSectionFor(selected);
            if (sec != null) onAddTagToSection(sec);
        });
        ContextMenu menu = new ContextMenu(addHere);
        return menu;
    }

    /**
     * Walk up the tree from {@code item} until we find a node registered in
     * sectionItemMap. Returns null if no section ancestor is found (e.g. root).
     */
    private OirSection findSectionFor(TreeItem<String> item) {
        while (item != null) {
            OirSection sec = sectionItemMap.get(item);
            if (sec != null) return sec;
            item = item.getParent();
        }
        return null;
    }

    /**
     * Simplified "Add Tag" dialog — the target section is already known from the
     * tree click, so there is no section ComboBox. Everything else is identical
     * to onAddTags().
     */
    private void onAddTagToSection(OirSection sec) {
        if (currentFile == null) { setStatus("⚠ Open a file first."); return; }

        Dialog<String[]> dialog = new Dialog<>();
        dialog.setTitle("Add Tag → " + sec.getSectionName());
        dialog.setHeaderText("Inject a custom XML tag into:  " + sec.getSectionName());

        ButtonType saveType = new ButtonType("💾  Save to File", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        GridPane grid = new GridPane();
        grid.setHgap(12); grid.setVgap(14);
        grid.setPadding(new Insets(24, 24, 16, 24));

        // Section info row
        Label secInfoLabel = new Label();
        secInfoLabel.setStyle("-fx-text-fill: #7ecbe0; -fx-font-size: 11px;");
        secInfoLabel.setWrapText(true);
        secInfoLabel.setMaxWidth(280);
        updateSectionInfo(secInfoLabel, sec);

        // Tag name
        Label nameLabel = new Label("Tag Name:");
        nameLabel.getStyleClass().add("field-label");
        TextField tagName = new TextField();
        tagName.setPromptText("e.g.  sampleName   (no spaces, XML-safe)");
        tagName.getStyleClass().add("text-field");
        tagName.setPrefWidth(280);

        Label nameHint = new Label("⚠ Must start with a letter. Only letters, digits, _ - . allowed.");
        nameHint.setStyle("-fx-text-fill: #e0a050; -fx-font-size: 10px;");
        nameHint.setWrapText(true);
        nameHint.setMaxWidth(280);

        // Tag value
        Label valLabel = new Label("Tag Value:");
        valLabel.getStyleClass().add("field-label");
        TextField tagValue = new TextField();
        tagValue.setPromptText("e.g.  Mouse brain slice #3");
        tagValue.getStyleClass().add("text-field");
        tagValue.setPrefWidth(280);

        // Save mode
        ToggleGroup saveGroup = new ToggleGroup();
        RadioButton overwriteRb = new RadioButton("♻️  Overwrite original file");
        overwriteRb.setToggleGroup(saveGroup);
        overwriteRb.setSelected(true);
        overwriteRb.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 12px;");
        RadioButton newFileRb = new RadioButton("📄  Save as new file…");
        newFileRb.setToggleGroup(saveGroup);
        newFileRb.setStyle("-fx-text-fill: #e0e0e0; -fx-font-size: 12px;");
        VBox saveModeBox = new VBox(6, overwriteRb, newFileRb);

        javafx.scene.control.Separator sep2 = new javafx.scene.control.Separator();
        sep2.setMaxWidth(Double.MAX_VALUE);

        grid.add(new Label("Section:"),  0, 0); grid.add(secInfoLabel, 1, 0);
        grid.add(nameLabel,              0, 1); grid.add(tagName,      1, 1);
        grid.add(new Label(),            0, 2); grid.add(nameHint,     1, 2);
        grid.add(valLabel,               0, 3); grid.add(tagValue,     1, 3);
        grid.add(sep2,                   0, 4, 2, 1);
        grid.add(new Label("Save Mode:"),0, 5); grid.add(saveModeBox,  1, 5);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().setPrefWidth(500);
        dialog.getDialogPane().setPrefHeight(380);

        var css = getClass().getResource("/css/dark-theme.css");
        if (css != null) dialog.getDialogPane().getStylesheets().add(css.toExternalForm());

        // Disable Save until tag name is valid XML identifier
        var saveButton = dialog.getDialogPane().lookupButton(saveType);
        saveButton.setDisable(true);
        tagName.textProperty().addListener((obs, o, n) -> {
            boolean valid = n != null && n.matches("[a-zA-Z_][a-zA-Z0-9_\\-\\.]*");
            saveButton.setDisable(!valid);
            nameHint.setStyle("-fx-font-size: 10px; -fx-text-fill: "
                + (valid || n.isBlank() ? "#7ecbe0" : "#e05050") + ";");
        });

        dialog.setResultConverter(btn -> {
            if (btn == saveType) {
                String mode = overwriteRb.isSelected() ? "overwrite" : "new";
                return new String[]{ String.valueOf(sec.getSectionId()),
                    tagName.getText().trim(), tagValue.getText().trim(), mode };
            }
            return null;
        });

        dialog.showAndWait().ifPresent(result ->
            injectTag(Integer.parseInt(result[0]), result[1], result[2], result[3]));
    }



    // ── Helpers ────────────────────────────────────────────────────────────

    private void injectTag(int sectionId, String tagName, String tagValue, String mode) {
        File original = currentFile.getSourceFile();
        String secName = CustomMetadataWriter.XML_SECTION_NAMES
            .getOrDefault(sectionId, "Section " + sectionId);

        // If "new file" mode, ask user where to save it first
        final File destFile;
        if ("new".equals(mode)) {
            FileChooser fc = new FileChooser();
            fc.setTitle("Save Tagged OIR File As");
            fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("OIR File", "*.oir"));
            String baseName = original.getName().replace(".oir", "");
            fc.setInitialFileName(baseName + "_tagged.oir");
            fc.setInitialDirectory(original.getParentFile());
            File chosen = fc.showSaveDialog(stage);
            if (chosen == null) return;  // user cancelled
            destFile = chosen;
        } else {
            destFile = original;  // overwrite mode
        }

        setStatus("Writing <" + tagName + "> → " + secName + " …");
        progressBar.setVisible(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);

        final ParsedOirFile pf = currentFile;
        final boolean overwrite = "overwrite".equals(mode);

        Task<File> task = new Task<>() {
            @Override protected File call() throws Exception {
                Map<String, String> tags = new LinkedHashMap<>();
                tags.put(tagName, tagValue);

                if (overwrite) {
                    // Write to temp → atomic replace of original
                    File tmp = File.createTempFile("oir_tmp_", ".oir", original.getParentFile());
                    try {
                        new CustomMetadataWriter().injectCustomTags(pf, sectionId, tags, tmp);
                        if (!original.delete())
                            throw new IOException("Cannot overwrite original file (is it open elsewhere?)");
                        if (!tmp.renameTo(original))
                            throw new IOException("Could not rename temp file to original path.");
                        return original;
                    } catch (Exception ex) {
                        tmp.delete();
                        throw ex;
                    }
                } else {
                    // Write directly to chosen new file
                    new CustomMetadataWriter().injectCustomTags(pf, sectionId, tags, destFile);
                    return destFile;
                }
            }
        };

        task.setOnSucceeded(e -> {
            progressBar.setVisible(false);
            File saved = task.getValue();
            if (overwrite) {
                setStatus("✅  <" + tagName + "> saved → " + saved.getName() + "  — reloading…");
                loadOirFile(saved);
            } else {
                setStatus("✅  New file saved → " + saved.getName() + "  (original unchanged)");
                // Ask if user wants to open the new file
                Alert prompt = new Alert(Alert.AlertType.CONFIRMATION);
                prompt.setTitle("Open New File?");
                prompt.setHeaderText("New file saved: " + saved.getName());
                prompt.setContentText("Open the new tagged file now? (Original file remains unchanged)");
                var css2 = getClass().getResource("/css/dark-theme.css");
                if (css2 != null) prompt.getDialogPane().getStylesheets().add(css2.toExternalForm());
                prompt.showAndWait().ifPresent(btn -> {
                    if (btn == ButtonType.OK) loadOirFile(saved);
                });
            }
        });

        task.setOnFailed(e -> {
            progressBar.setVisible(false);
            showError("Tag injection failed", task.getException());
        });

        new Thread(task, "oir-writer").start();
    }

    private TreeItem<String> section(String label) {
        TreeItem<String> item = new TreeItem<>(label);
        item.setExpanded(true);
        return item;
    }

    /** Recursively expand or collapse all nodes in the tree. */
    private void setAllExpanded(TreeItem<String> node, boolean expanded) {
        if (node == null) return;
        node.setExpanded(expanded);
        for (TreeItem<String> child : node.getChildren()) {
            setAllExpanded(child, expanded);
        }
    }

    private void addLeaf(TreeItem<String> parent, String key, String value) {
        parent.getChildren().add(new TreeItem<>(key + ":  " + value));
    }

    private String hex(long v) { return String.format("0x%08X", v); }

    private String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private Document parseXml(String xml) throws Exception {
        // Strip UTF-8 BOM (0xFEFF) that Java's XML Transformer can prepend
        if (xml != null && xml.startsWith("\uFEFF")) xml = xml.substring(1);
        // Trim leading whitespace (Transformer can add newlines before root element)
        if (xml != null) xml = xml.stripLeading();
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
        dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
        DocumentBuilder db = dbf.newDocumentBuilder();
        // Suppress SAX parser stderr output for invalid XML
        db.setErrorHandler(new org.xml.sax.helpers.DefaultHandler());
        return db.parse(new InputSource(new StringReader(xml)));
    }

    private void setStatus(String msg) {
        Platform.runLater(() -> statusLabel.setText(msg));
    }

    private void showError(String title, Throwable ex) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Error");
            alert.setHeaderText(title);
            alert.setContentText(ex != null ? ex.getMessage() : "Unknown error");
            var css = getClass().getResource("/css/dark-theme.css");
            if (css != null) alert.getDialogPane().getStylesheets().add(css.toExternalForm());
            alert.showAndWait();
            setStatus("❌  " + title);
        });
    }
    private void initFrameExtractor(ParsedOirFile pf) {
        List<ImageExtractor.ChannelInfo> channels = ImageExtractor.getChannels(pf);
        List<String> frames = ImageExtractor.getFrames(pf);

        if (channels.isEmpty() || frames.isEmpty()) {
            channelCombo.setDisable(true);
            frameSpinner.setDisable(true);
            exportFrameBtn.setDisable(true);
            frameImageView.setImage(null);
            frameInfoLabel.setText("No channels or frames found in this OIR file.");
            return;
        }

        // Setup channels combo
        channelCombo.getItems().clear();
        channelCombo.getItems().addAll(channels);
        channelCombo.getSelectionModel().selectFirst();
        channelCombo.setDisable(false);

        // Setup frames spinner
        int frameCount = frames.size();
        SpinnerValueFactory<Integer> valueFactory = new SpinnerValueFactory.IntegerSpinnerValueFactory(1, frameCount, 1);
        frameSpinner.setValueFactory(valueFactory);
        frameSpinner.setDisable(false);

        // Clear previous event handler references by setting new action triggers
        channelCombo.setOnAction(e -> loadFrameAsync());
        frameSpinner.valueProperty().addListener((obs, o, n) -> {
            if (n != null) loadFrameAsync();
        });

        exportFrameBtn.setDisable(false);

        // Load initial frame
        loadFrameAsync();
    }

    private void loadFrameAsync() {
        if (currentFile == null) return;

        ImageExtractor.ChannelInfo selectedCh = channelCombo.getSelectionModel().getSelectedItem();
        Integer frameNum = frameSpinner.getValue();

        if (selectedCh == null || frameNum == null) return;

        List<String> frames = ImageExtractor.getFrames(currentFile);
        if (frameNum < 1 || frameNum > frames.size()) return;
        String frameIndex = frames.get(frameNum - 1);

        setStatus("Extracting frame " + frameNum + " / channel " + selectedCh.order + " …");
        progressBar.setVisible(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);

        Task<BufferedImage> task = new Task<>() {
            @Override
            protected BufferedImage call() throws Exception {
                ImageExtractor extractor = new ImageExtractor();
                short[] raw = extractor.extractRawPixels(currentFile, frameIndex, selectedCh.id);
                int[] dims = ImageExtractor.getDimensions(currentFile);
                return extractor.convertTo8BitGrayscale(raw, dims[0], dims[1]);
            }
        };

        task.setOnSucceeded(e -> {
            BufferedImage bimg = task.getValue();
            progressBar.setVisible(false);
            if (bimg != null) {
                Image fxImg = SwingFXUtils.toFXImage(bimg, null);
                frameImageView.setImage(fxImg);
                int[] dims = ImageExtractor.getDimensions(currentFile);
                frameInfoLabel.setText(String.format("Format: 8-bit Greyscale Preview (Auto-Scaled)  |  Size: %d × %d px", dims[0], dims[1]));
                setStatus("✅  Frame extracted successfully.");
            } else {
                frameImageView.setImage(null);
                frameInfoLabel.setText("Failed to reconstruct frame.");
                setStatus("❌  Failed to extract frame.");
            }
        });

        task.setOnFailed(e -> {
            progressBar.setVisible(false);
            frameImageView.setImage(null);
            frameInfoLabel.setText("Extraction failed: " + task.getException().getMessage());
            setStatus("❌  Extraction failed: " + task.getException().getMessage());
            showError("Failed to extract frame", task.getException());
        });

        new Thread(task, "frame-extractor").start();
    }

    private void onExportFrame() {
        if (currentFile == null) return;

        ImageExtractor.ChannelInfo selectedCh = channelCombo.getSelectionModel().getSelectedItem();
        Integer frameNum = frameSpinner.getValue();
        if (selectedCh == null || frameNum == null) return;

        List<String> frames = ImageExtractor.getFrames(currentFile);
        if (frameNum < 1 || frameNum > frames.size()) return;
        String frameIndex = frames.get(frameNum - 1);

        FileChooser fc = new FileChooser();
        fc.setTitle("Export Reconstructed Frame");
        fc.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("PNG Image (8-bit)", "*.png"),
            new FileChooser.ExtensionFilter("TIFF Image (16-bit)", "*.tif", "*.tiff")
        );
        String baseName = currentFile.getSourceFile().getName().replace(".oir", "");
        fc.setInitialFileName(String.format("%s_ch%d_frame%d.png", baseName, selectedCh.order, frameNum));
        fc.setInitialDirectory(currentFile.getSourceFile().getParentFile());
        File outFile = fc.showSaveDialog(stage);
        if (outFile == null) return;

        setStatus("Exporting frame to " + outFile.getName() + " …");
        progressBar.setVisible(true);
        progressBar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);

        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                ImageExtractor extractor = new ImageExtractor();
                short[] raw = extractor.extractRawPixels(currentFile, frameIndex, selectedCh.id);
                int[] dims = ImageExtractor.getDimensions(currentFile);

                String ext = outFile.getName().substring(outFile.getName().lastIndexOf(".") + 1).toLowerCase();
                BufferedImage bimg;
                if ("tif".equals(ext) || "tiff".equals(ext)) {
                    bimg = extractor.convertTo16BitGrayscale(raw, dims[0], dims[1]);
                } else {
                    bimg = extractor.convertTo8BitGrayscale(raw, dims[0], dims[1]);
                }

                boolean ok = ImageIO.write(bimg, ext, outFile);
                if (!ok) {
                    throw new IOException("No writer found for format: " + ext);
                }
                return null;
            }
        };

        task.setOnSucceeded(e -> {
            progressBar.setVisible(false);
            setStatus("✅  Frame exported successfully to " + outFile.getName());
        });

        task.setOnFailed(e -> {
            progressBar.setVisible(false);
            setStatus("❌  Export failed: " + task.getException().getMessage());
            showError("Failed to export frame", task.getException());
        });

        new Thread(task, "frame-exporter").start();
    }
}

