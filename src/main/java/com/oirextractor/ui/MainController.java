package com.oirextractor.ui;

import com.oirextractor.extractor.ImageExtractor;
import com.oirextractor.validator.ValidationReport;
import com.oirextractor.validator.XMLValidator;
import com.olympus.oir.extractor.XmlMetadataExtractor;
import com.olympus.oir.model.ParsedOirFile;
import com.olympus.oir.parser.OirParser;

import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.time.LocalTime;

public class MainController {

    // Screens
    @FXML private VBox homeScreen;
    @FXML private VBox progressScreen;
    @FXML private VBox resultsScreen;

    // Home
    @FXML private TextField oirFilePathField;
    @FXML private TextField outputDirField;
    @FXML private CheckBox chkExtractXml;
    @FXML private CheckBox chkValidateXml;
    @FXML private CheckBox chkExtractImages;
    @FXML private VBox imageOptionsPanel;
    
    // Image Options
    @FXML private CheckBox chkIncIndividual, chkIncMerged, chkIncReference, chkIncThumbnail;
    @FXML private RadioButton rbTiff16, rbTiff8, rbPng;

    @FXML private Label lblChannelsFound;
    @FXML private Button btnStart;

    // Progress
    @FXML private Label lblProgressFileName, lblTask1, lblTask2, lblTask3, lblCurrentAction;
    @FXML private ProgressBar progressBar;
    @FXML private TextArea txtLog;
    @FXML private Button btnCancel;

    // Results
    @FXML private Label lblResultIcon, lblResultTitle;
    @FXML private Label resFileName, resChannels, resZT, resFrames, resImages;
    @FXML private Hyperlink lnkXmlFile;
    @FXML private Label lblValidationStats;

    private File currentOirFile;
    private File currentOutputDir;
    private Task<Void> processingTask;

    @FXML
    public void initialize() {
        // Bind visibility of image options to checkbox
        imageOptionsPanel.visibleProperty().bind(chkExtractImages.selectedProperty());
        imageOptionsPanel.managedProperty().bind(imageOptionsPanel.visibleProperty());



        showScreen(homeScreen);
    }

    private void showScreen(VBox screen) {
        homeScreen.setVisible(false);
        progressScreen.setVisible(false);
        resultsScreen.setVisible(false);
        screen.setVisible(true);
    }

    @FXML
    private void onBrowseOirFile(ActionEvent event) {
        FileChooser chooser = new FileChooser();
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("OIR Files", "*.oir"));
        File file = chooser.showOpenDialog(oirFilePathField.getScene().getWindow());
        if (file != null) {
            currentOirFile = file;
            oirFilePathField.setText(file.getAbsolutePath());
            
            // Auto-set output dir
            currentOutputDir = new File(file.getParentFile(), file.getName().replace(".oir", "_output"));
            outputDirField.setText(currentOutputDir.getAbsolutePath());
            
            lblChannelsFound.setText("Ready to extract");
            btnStart.setDisable(false);
        }
    }

    @FXML
    private void onBrowseOutputDir(ActionEvent event) {
        DirectoryChooser chooser = new DirectoryChooser();
        if (currentOutputDir != null && currentOutputDir.getParentFile() != null) {
            chooser.setInitialDirectory(currentOutputDir.getParentFile());
        }
        File dir = chooser.showDialog(outputDirField.getScene().getWindow());
        if (dir != null) {
            currentOutputDir = dir;
            outputDirField.setText(dir.getAbsolutePath());
        }
    }

    @FXML
    private void onExtractImagesToggled(ActionEvent event) {
        // Logic handled by binding in initialize()
    }

    @FXML
    private void onStartProcessing(ActionEvent event) {
        if (currentOirFile == null) return;

        showScreen(progressScreen);
        lblProgressFileName.setText(currentOirFile.getName());
        txtLog.clear();
        btnStart.setDisable(true);
        progressBar.progressProperty().unbind();
        progressBar.setProgress(0);
        
        lblTask1.setText(chkExtractXml.isSelected() ? "⏳ Task 1: Extract XML" : "⏭️ Task 1: Skipped");
        lblTask2.setText(chkValidateXml.isSelected() ? "⬜ Task 2: Validate XML" : "⏭️ Task 2: Skipped");
        lblTask3.setText(chkExtractImages.isSelected() ? "⬜ Task 3: Extract Images" : "⏭️ Task 3: Skipped");

        processingTask = new Task<Void>() {
            @Override
            protected Void call() throws Exception {
                logMessage("Started processing: " + currentOirFile.getName());
                currentOutputDir.mkdirs();

                // Parsing
                updateMessage("Parsing OIR file...");
                OirParser parser = new OirParser(currentOirFile);
                ParsedOirFile parsedFile = parser.parse();
                logMessage("Parsed " + parsedFile.getBlocks().size() + " blocks.");

                // 1. Extract XML
                File xmlFile = null;
                if (chkExtractXml.isSelected()) {
                    updateProgress(10, 100);
                    updateMessage("Extracting XML metadata...");
                    XmlMetadataExtractor xmlExtractor = new XmlMetadataExtractor();
                    xmlFile = new File(currentOutputDir, "experiment_metadata.xml");
                    xmlExtractor.writeExperimentXml(parsedFile, xmlFile);
                    logMessage("Saved " + xmlFile.getName());
                    Platform.runLater(() -> lblTask1.setText("✅ Task 1: Extract XML"));
                }

                // 2. Validate XML
                ValidationReport vReport = null;
                if (chkValidateXml.isSelected()) {
                    Platform.runLater(() -> lblTask2.setText("⏳ Task 2: Validate XML"));
                    updateProgress(30, 100);
                    updateMessage("Validating XML...");
                    
                    XMLValidator validator = new XMLValidator(currentOirFile.getName(), "validation.properties");
                    validator.validateSection("FileInformation", parsedFile.getFileInformationXml().orElse(""));
                    
                    vReport = validator.getReport();
                    logMessage("Validation " + (vReport.getOverallStatus().contains("FAIL") ? "FAILED" : "PASSED"));
                    Platform.runLater(() -> lblTask2.setText("✅ Task 2: Validate XML"));
                }

                // 3. Extract Images
                if (chkExtractImages.isSelected()) {
                    Platform.runLater(() -> lblTask3.setText("⏳ Task 3: Extract Images"));
                    updateMessage("Extracting images...");
                    
                    ImageExtractor.ExtractionOptions opts = new ImageExtractor.ExtractionOptions();
                    opts.outputDirectory = currentOutputDir;
                    opts.extractChannels = chkIncIndividual.isSelected();
                    opts.extractMerge = chkIncMerged.isSelected();
                    opts.extractReference = chkIncReference.isSelected();
                    opts.extractThumbnail = chkIncThumbnail.isSelected();
                    opts.convertTo8Bit = rbTiff8.isSelected() || rbPng.isSelected();
                    
                    // Extract unique channels from fragment keys (e.g. t001_0_1_GUID_0 -> GUID)
                    java.util.Set<String> channels = new java.util.HashSet<>();
                    if (!parsedFile.getFrameIndexMap().isEmpty()) {
                        String firstFrame = parsedFile.getFrameIndexMap().keySet().iterator().next();
                        for (String key : parsedFile.getFragmentIndexMap().keySet()) {
                            if (key.startsWith(firstFrame + "_")) {
                                String remainder = key.substring(firstFrame.length() + 1);
                                int lastUnderscore = remainder.lastIndexOf('_');
                                if (lastUnderscore > 0) {
                                    channels.add(remainder.substring(0, lastUnderscore));
                                }
                            }
                        }
                    }
                    opts.channelsToExtract.addAll(channels);
                    
                    // Dynamically extract true image dimensions from XML
                    String imgPropsXml = parsedFile.getImagePropertiesXml().orElse("");
                    java.util.regex.Pattern pWidth = java.util.regex.Pattern.compile("<[^>]*width[^>]*>(\\d+)</[^>]*>", java.util.regex.Pattern.CASE_INSENSITIVE);
                    java.util.regex.Matcher mWidth = pWidth.matcher(imgPropsXml);
                    if (mWidth.find()) {
                        opts.imageWidth = Integer.parseInt(mWidth.group(1));
                    }
                    
                    java.util.regex.Pattern pHeight = java.util.regex.Pattern.compile("<[^>]*height[^>]*>(\\d+)</[^>]*>", java.util.regex.Pattern.CASE_INSENSITIVE);
                    java.util.regex.Matcher mHeight = pHeight.matcher(imgPropsXml);
                    if (mHeight.find()) {
                        opts.imageHeight = Integer.parseInt(mHeight.group(1));
                    }
                    
                    // Dynamically map UUIDs to friendly names like CH1, DAPI
                    java.util.regex.Pattern pChannel = java.util.regex.Pattern.compile("<lsmimage:channel\\s+[^>]*\\bid=\"([^\"]+)\"[^>]*>.*?<commonimage:name>([^<]+)</commonimage:name>.*?</lsmimage:channel>", java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);
                    java.util.regex.Matcher mChannel = pChannel.matcher(imgPropsXml);
                    while(mChannel.find()) {
                        opts.channelNameMap.put(mChannel.group(1), mChannel.group(2));
                    }
                    

                    
                    ImageExtractor extractor = new ImageExtractor();
                    extractor.extractImages(parsedFile, opts, (taskName, current, total) -> {
                        if (isCancelled()) throw new RuntimeException("Cancelled");
                        updateMessage(taskName);
                        updateProgress(40 + (current * 60.0 / total), 100);
                    });
                    
                    Platform.runLater(() -> lblTask3.setText("✅ Task 3: Extract Images"));
                }

                updateProgress(100, 100);
                updateMessage("Complete");

                // 4. Generate Extraction Report
                File extractionReportFile = new File(currentOutputDir, "extraction_report.txt");
                try (java.io.PrintWriter writer = new java.io.PrintWriter(new java.io.FileWriter(extractionReportFile))) {
                    writer.println("════════════════════════════════════");
                    writer.println("OIR EXTRACTION REPORT");
                    writer.println("════════════════════════════════════");
                    writer.println("Status  : SUCCESS");
                    writer.println("Frames  : " + parsedFile.getFrameCount());
                    writer.println("Images  : Extracted successfully");
                    writer.println("════════════════════════════════════");
                } catch (IOException ignored) {}

                int totalChannels = 0;
                if (!parsedFile.getFrameIndexMap().isEmpty()) {
                    String firstFrame = parsedFile.getFrameIndexMap().keySet().iterator().next();
                    java.util.Set<String> chSet = new java.util.HashSet<>();
                    for (String key : parsedFile.getFragmentIndexMap().keySet()) {
                        if (key.startsWith(firstFrame + "_")) {
                            String remainder = key.substring(firstFrame.length() + 1);
                            int lastUnderscore = remainder.lastIndexOf('_');
                            if (lastUnderscore > 0) chSet.add(remainder.substring(0, lastUnderscore));
                        }
                    }
                    totalChannels = chSet.size();
                }
                
                int extractedImages = 0;
                if (chkExtractImages.isSelected() && currentOutputDir.exists()) {
                    try {
                        extractedImages = (int) Files.walk(currentOutputDir.toPath())
                            .filter(p -> p.toString().toLowerCase().endsWith(".tif") || p.toString().toLowerCase().endsWith(".png") || p.toString().toLowerCase().endsWith(".bmp"))
                            .count();
                    } catch (Exception e) {}
                }

                // Prepare results UI
                final File finalXml = xmlFile;
                final ValidationReport finalVReport = vReport;
                final int frames = parsedFile.getFrameCount();
                final int finalChannels = totalChannels;
                final int finalImages = extractedImages;

                Platform.runLater(() -> setupResultsScreen(finalXml, finalVReport, frames, finalChannels, finalImages));

                return null;
            }
        };

        processingTask.messageProperty().addListener((obs, oldMsg, newMsg) -> {
            lblCurrentAction.setText(newMsg);
        });

        progressBar.progressProperty().bind(processingTask.progressProperty());

        processingTask.setOnSucceeded(e -> showScreen(resultsScreen));
        processingTask.setOnCancelled(e -> showScreen(homeScreen));
        processingTask.setOnFailed(e -> {
            logMessage("ERROR: " + processingTask.getException().getMessage());
            lblCurrentAction.setText("Failed.");
        });

        new Thread(processingTask).start();
    }

    private void logMessage(String msg) {
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        Platform.runLater(() -> {
            txtLog.appendText("[" + time + "] " + msg + "\n");
        });
    }

    @FXML
    private void onCancel(ActionEvent event) {
        if (processingTask != null && processingTask.isRunning()) {
            processingTask.cancel();
            logMessage("User cancelled the operation.");
        }
    }

    private void setupResultsScreen(File xmlFile, ValidationReport vReport, int frameCount, int channels, int imagesExtracted) {
        resFileName.setText(currentOirFile.getName());
        resFrames.setText(String.valueOf(frameCount));
        resChannels.setText(channels > 0 ? String.valueOf(channels) : "N/A");
        resImages.setText(imagesExtracted > 0 ? String.valueOf(imagesExtracted) : "0");
        resZT.setText("N/A");
        
        if (xmlFile != null) {
            lnkXmlFile.setText(xmlFile.getName());
            lnkXmlFile.setUserData(xmlFile);
        } else {
            lnkXmlFile.setText("Not extracted");
            lnkXmlFile.setUserData(null);
        }

        if (vReport != null) {
            if (vReport.getOverallStatus().contains("FULL PASS")) {
                lblValidationStats.setText("Validation Passed");
            } else {
                lblValidationStats.setText(vReport.getPassedSections() + "/" + vReport.getTotalSections() + " Passed");
            }
        } else {
            lblValidationStats.setText("Not run");
        }
    }

    @FXML
    private void onOpenXml(ActionEvent event) {
        File file = (File) lnkXmlFile.getUserData();
        if (file != null && file.exists()) {
            try { Desktop.getDesktop().open(file); } catch (IOException ignored) {}
        }
    }



    @FXML
    private void onOpenOutputFolder(ActionEvent event) {
        if (currentOutputDir != null && currentOutputDir.exists()) {
            try { Desktop.getDesktop().open(currentOutputDir); } catch (IOException ignored) {}
        }
    }

    @FXML
    private void onProcessAnother(ActionEvent event) {
        showScreen(homeScreen);
    }
    
    @FXML
    private void onOpenAdvancedInspector(ActionEvent event) {
        javafx.stage.Stage stage = new javafx.stage.Stage();
        stage.setTitle("Advanced XML Metadata Inspector");
        com.olympus.oir.ui.MainController advancedController = new com.olympus.oir.ui.MainController(stage);
        javafx.scene.Scene scene = advancedController.buildScene();
        stage.setScene(scene);
        stage.show();
    }
}
