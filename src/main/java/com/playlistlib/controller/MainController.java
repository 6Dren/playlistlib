package com.playlistlib.controller;

import java.io.IOException;

// import java.nio.file.PathMatcher;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Separator;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.stage.Stage;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.layout.VBox;;

public class MainController {
    @FXML
    private Button downloadButton;

    @FXML
    private Button libraryButton;

    @FXML
    private Button exitButton;

    @FXML
    private void initialize() {
    }

    @FXML
    private void exitApp(javafx.event.ActionEvent event) {
        System.out.print("Exiting PlaylistLib");
        Platform.exit();
    }

    @FXML
    private void openDownload(javafx.event.ActionEvent event) throws IOException { // whoever call this method must
                                                                                   // handle this exception
        VBox root = FXMLLoader.load(getClass().getResource("/com/playlistlib/views/download.fxml"));

        Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();

        stage.setScene(new Scene(root));

    }

    @FXML
    private void openLibrary(ActionEvent event) throws IOException {
        Parent root = FXMLLoader.load(
                getClass().getResource("/com/playlistlib/views/library.fxml"));

        Stage stage = (Stage) ((Node) event.getSource())
                .getScene()
                .getWindow();

        stage.setScene(new Scene(root));
        stage.sizeToScene();
    }

    @FXML
    void about() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);

        alert.setTitle("About Playlistlib");
        alert.setHeaderText(null);
        alert.setGraphic(null);

        Label title = new Label("Playlistlib");
        title.getStyleClass().add("about-title");

        Label version = new Label("Version 1.0-SNAPSHOT");
        version.getStyleClass().add("about-version");

        Label description = new Label(
                "A simple desktop application for \ndownloading and organizing audio and video.");
        description.setWrapText(true);
        description.getStyleClass().add("about-description");

        Label repository = new Label("GitHub: github.com/6Dren/playlistlib");
        repository.getStyleClass().add("about-repository");

        Label technologies = new Label("Java 21 · JavaFX · SQLite · yt-dlp");
        technologies.getStyleClass().add("about-technologies");

        VBox content = new VBox(10,
                title,
                version,
                description,
                new Separator(),
                repository,
                technologies);

        content.setPadding(new Insets(10, 15, 10, 15));

        var dialogPane = alert.getDialogPane();
        dialogPane.setContent(content);
        dialogPane.setMinWidth(380);
        dialogPane.getStyleClass().add("about-dialog");

        var stylesheet = getClass().getResource(
                "/com/playlistlib/styles/about.css");

        if (stylesheet != null) {
            dialogPane.getStylesheets().add(stylesheet.toExternalForm());
        }

        alert.showAndWait();

    }

}
