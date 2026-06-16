OIR Extractor & Validator
═══════════════════════════════════════════════

This application processes Olympus microscopy OIR files. It performs XML metadata extraction, XSD validation, and individual/merged TIFF image extraction, all wrapped in a KISS JavaFX interface.

1. HOW TO BUILD
───────────────────────────────────────────────
Ensure you have Maven (mvn) installed. From the root directory containing pom.xml, run:

  mvn clean package

This will compile the code, resolve all dependencies (including JDOM2 and ImageJ), and package the application into an executable JAR file with dependencies included.

2. HOW TO RUN
───────────────────────────────────────────────
After building successfully, navigate to the target/ directory and run:

  java -jar oir-extractor.jar
  
Alternatively, if using the JavaFX maven plugin:
  
  mvn javafx:run

3. WHAT THE OUTPUT FOLDER CONTAINS
───────────────────────────────────────────────
When you select an OIR file and an output directory, the tool generates:

  [Output Folder]/
  ├── experiment_metadata.xml      (Unified XML containing all extracted metadata)
  ├── validation_report.txt        (Results of comparing the XML against XSDs)
  ├── extraction_report.txt        (Summary of images/frames successfully extracted)
  ├── images/                      (Image extractions)
  │   ├── thumbnail.bmp            (Extracted preview image)
  │   ├── channels/                (16-bit TIFFs for individual channels, e.g. 0000001_CH1.tif)
  │   ├── merged/                  (8-bit RGB TIFFs overlaying the LUT colors for all channels)
  │   └── reference/               (16-bit TIFFs for reference channel images)

4. HOW TO PLUG IN OFFICIAL XSD FILES LATER
───────────────────────────────────────────────
The XML validator is completely decoupled from the codebase using `validation.properties`. 
If Olympus releases official XSD files in the future:
  1. Place the official XSD files into the `src/main/resources/xsd/` directory (or anywhere on disk).
  2. Open `validation.properties` and update the paths to point to the new files.
  3. Example `validation.properties`:
       xsd.FileInformation=xsd/OFFICIAL_FILE_INFORMATION.xsd
       xsd.ImageProperties=xsd/OFFICIAL_IMAGE_PROPERTIES.xsd
  4. The application will automatically use the new schemas on the next run without needing a recompile.
