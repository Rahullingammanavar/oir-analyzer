package com.olympus.oir;

import com.olympus.oir.ui.MainController;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.stage.Stage;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * JavaFX Application entry point for OIR File Analyzer.
 *
 * Run with Maven:  mvn javafx:run
 */
public class App extends Application {

    private static final Logger LOG = Logger.getLogger(App.class.getName());

    @Override
    public void start(Stage primaryStage) {
        try {
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(getClass().getResource("/fxml/main.fxml"));
            javafx.scene.Parent root = loader.load();
            Scene scene = new Scene(root);

            primaryStage.setTitle("🔬 OIR Extractor & Validator");
            primaryStage.setScene(scene);
            primaryStage.setMinWidth(800);
            primaryStage.setMinHeight(650);
            primaryStage.setWidth(850);
            primaryStage.setHeight(700);
            primaryStage.centerOnScreen();
            primaryStage.show();

            LOG.info("OIR Extractor & Validator started.");

        } catch (Exception ex) {
            LOG.log(Level.SEVERE, "Failed to start application", ex);
        }
    }

    public static void main(String[] args) {
        // Configure logging format
        System.setProperty("java.util.logging.SimpleFormatter.format",
            "[%1$tH:%1$tM:%1$tS] [%4$s] %5$s%6$s%n");
        launch(args);
    }
}
