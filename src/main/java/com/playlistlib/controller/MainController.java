package com.playlistlib.controller;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

// import java.nio.file.PathMatcher;

import com.playlistlib.util.YtdlpLocator;

import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.stage.Stage;
import javafx.scene.Node;
import javafx.scene.layout.VBox;;

public class MainController {
    @FXML
    private Button downloadButton;

    @FXML
    private Button libraryButton;

    @FXML
    private Button settingsButton;

    @FXML
    private Button exitButton;

    @FXML
    private void initialize() {
        System.out.println(YtdlpLocator.findExecutableOnPath("yt-dlp"));
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

}
