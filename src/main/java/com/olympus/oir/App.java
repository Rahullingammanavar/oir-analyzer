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
            // Build main scene via controller
            MainController controller = new MainController(primaryStage);
            Scene scene = controller.buildScene();

            primaryStage.setTitle("🔬 OIR File Analyzer — Olympus Imaging Raw");
            primaryStage.setScene(scene);
            primaryStage.setMinWidth(1100);
            primaryStage.setMinHeight(720);
            primaryStage.setWidth(1280);
            primaryStage.setHeight(800);
            primaryStage.centerOnScreen();
            primaryStage.show();

            LOG.info("OIR File Analyzer started.");

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
