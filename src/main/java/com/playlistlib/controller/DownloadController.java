package com.playlistlib.controller;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

import com.playlistlib.model.DownloadRequest;
import com.playlistlib.service.YtdlpDownloadService;

import javafx.application.Platform;
import java.nio.file.Path;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;

import javafx.stage.Stage;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.scene.Node;
import javafx.scene.paint.Color;

import javafx.scene.control.TextField;
import javafx.scene.control.ComboBox;
import javafx.scene.control.CheckBox;
import javafx.scene.control.TitledPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Label;
import javafx.scene.control.Button;

public class DownloadController {

    private final YtdlpDownloadService downloadService = new YtdlpDownloadService();

    private static final List<String> AUDIO_FORMATS = List.of(
            "MP3", "WAV", "FLAC", "M4A",
            "OPUS", "AAC", "ALAC", "VORBIS");

    private static final List<String> VIDEO_FORMATS = List.of(
            "MP4", "WEBM");

    public static final List<String> ALL_FORMATS = Stream.concat(
            AUDIO_FORMATS.stream(),
            VIDEO_FORMATS.stream()).toList();

    private static final String DEFAULT_FORMAT = "MP3";

    @FXML
    private Button downloadButton;

    @FXML
    private Label statusLabel;

    @FXML
    private TitledPane outputPane;

    @FXML
    private TextArea outputText;

    @FXML
    private ComboBox<String> formatBox;

    @FXML
    private CheckBox thumbnailCheckBox;

    @FXML
    private TextField directoryField;

    @FXML
    private TextField urlField;

    @FXML
    private void initialize() {
        formatBox.getItems().setAll(ALL_FORMATS);
        formatBox.setValue(DEFAULT_FORMAT);

        thumbnailCheckBox.setSelected(true);

    }

    @FXML
    private void goBack(javafx.event.ActionEvent event) throws IOException {
        VBox root = FXMLLoader.load(getClass().getResource("/com/playlistlib/main.fxml"));

        Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();

        stage.setScene(new Scene(root));

    }

    @FXML
    private void handleDownload(ActionEvent event) {
        // Get the URL and remove whitespace
        String url = urlField.getText().trim();

        String format = formatBox.getValue();

        String directory = directoryField.getText().trim();

        Path outputDirectory = directory.isEmpty()
                ? null
                : Path.of(directory);

        DownloadRequest request = new DownloadRequest(
                url,
                format,
                thumbnailCheckBox.isSelected(),
                outputDirectory);

        // Check if url is empty
        if (url.isEmpty()) {
            outputText.appendText("Please enter a valid URL.");

            statusLabel.setTextFill(Color.RED);
            statusLabel.setText("Please enter a valid URL.");
            return;
        }

        // Clean the field for the next input | I may change it to keep the same url
        // even after pressing download
        outputText.clear();

        outputText.appendText("Starting download...");
        statusLabel.setText("Downloading...");
        statusLabel.setTextFill(Color.web("#222222"));
        downloadButton.setDisable(true);

        // Run the download on a background thread so the UI doesn't freeze
        Thread downloadThread = new Thread(() -> {
            try {
                int exitCode = downloadService.download(
                        request,
                        line -> Platform.runLater(
                                () -> outputText.appendText(line + "\n")));

                Platform.runLater(() -> {

                    if (exitCode == 0) {
                        outputText.appendText("Download completed!\n");
                        statusLabel.setText("Download completed");
                        statusLabel.setTextFill(Color.GREEN);

                    } else {
                        outputText.appendText(
                                "Download failed. Exit code: " + exitCode + "\n");

                        statusLabel.setText("Download failed.");
                        statusLabel.setTextFill(Color.RED);
                    }

                    downloadButton.setDisable(false);
                });

            } catch (InterruptedException e) {

                Thread.currentThread().interrupt();

                Platform.runLater(() -> {
                    outputText.appendText("\nDownload interrupted.\n");
                    statusLabel.setText("Download interrupted.");
                    statusLabel.setTextFill(Color.RED);
                    downloadButton.setDisable(false);
                });

            } catch (IOException e) {

                Platform.runLater(() -> {
                    outputText.appendText(
                            "\nCould not start yt-dlp: " + e.getMessage() + "\n");

                    statusLabel.setText("Could not start yt-dlp.");
                    statusLabel.setTextFill(Color.RED);
                    downloadButton.setDisable(false);
                });
            }
        });

        downloadThread.start();
    }

}
