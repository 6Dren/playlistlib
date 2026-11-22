package com.playlistlib.controller;

import com.playlistlib.model.DownloadRequest;
import com.playlistlib.service.LibraryService;
import com.playlistlib.service.YtdlpDownloadService;
import com.playlistlib.util.YtdlpLocator;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Stream;

public class DownloadController {

    private final YtdlpDownloadService downloadService = new YtdlpDownloadService();
    private final LibraryService libraryService = new LibraryService();

    private Path selectedOutputDirectory;

    public static final List<String> ALL_FORMATS = YtdlpDownloadService.ALL_FORMATS;

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

        directoryField.setEditable(false);
        directoryField.setOnMouseClicked(event -> chooseDirectory());
    }

    @FXML
    private void goBack(ActionEvent event) throws IOException {
        VBox root = FXMLLoader.load(getClass().getResource("/com/playlistlib/main.fxml"));
        Stage stage = (Stage) ((Node) event.getSource()).getScene().getWindow();
        stage.setScene(new Scene(root));
    }

    @FXML
    private void handleDownload(ActionEvent event) {
        String url = urlField.getText() == null ? "" : urlField.getText().trim();

        if (url.isEmpty()) {
            outputText.appendText("Please enter a valid URL.\n");
            statusLabel.setText("Please enter a valid URL.");
            statusLabel.setTextFill(Color.RED);
            return;
        }

        String format = formatBox.getValue();
        if (format == null || format.isBlank()) {
            format = DEFAULT_FORMAT;
        }

        Path outputDirectory;
        try {
            outputDirectory = resolveOutputDirectory();
        } catch (IOException | RuntimeException exception) {
            outputText.appendText("Invalid output directory: " + exception.getMessage() + "\n");
            statusLabel.setText("Invalid output directory.");
            statusLabel.setTextFill(Color.RED);
            return;
        }

        DownloadRequest request = new DownloadRequest(
                url,
                format,
                thumbnailCheckBox.isSelected(),
                outputDirectory);

        outputText.clear();
        outputText.appendText("Starting download...\n");
        statusLabel.setText("Downloading...");
        statusLabel.setTextFill(Color.web("#222222"));
        downloadButton.setDisable(true);

        Thread downloadThread = new Thread(() -> runDownload(request), "playlistlib-download");
        downloadThread.start();
    }

    private void runDownload(DownloadRequest request) {
        // Register the selected directory as a list before downloading.
        // This also creates an empty .playlistlib.json manifest when the
        // download produces no new files or metadata cannot be collected.
        try {
            libraryService.getOrCreateList(request.getOutputDirectory());
            Platform.runLater(() -> outputText.appendText(
                    "List registered. Manifest: "
                            + request.getOutputDirectory().resolve(".playlistlib.json")
                            + "\n"));
        } catch (SQLException | IOException | RuntimeException exception) {
            Platform.runLater(() -> finishWithError(
                    "Could not create or open this library list: " + exception.getMessage()));
            return;
        }

        YtdlpDownloadService.DownloadResult result;

        try {
            result = downloadService.downloadAndCollect(
                    request,
                    line -> Platform.runLater(() -> outputText.appendText(line + "\n")));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            Platform.runLater(() -> finishWithError("Download interrupted."));
            return;
        } catch (IOException | RuntimeException exception) {
            Platform.runLater(() -> finishWithError(
                    "Could not run yt-dlp: " + exception.getMessage()));
            return;
        }

        int registeredCount = 0;
        try {
            if (!result.downloadedItems().isEmpty()) {
                Platform.runLater(() -> outputText.appendText(
                        "Registering " + result.downloadedItems().size()
                                + " downloaded item(s) in the library...\n"));

                registeredCount = libraryService.registerDownloads(
                        request.getOutputDirectory(),
                        result.downloadedItems()).size();

                int countForMessage = registeredCount;
                Platform.runLater(() -> outputText.appendText(
                        countForMessage + " media item(s) added to the library.\n"));
            } else {
                Platform.runLater(() -> outputText.appendText(
                        "No new completed media files to register.\n"));
            }
        } catch (SQLException | IOException | RuntimeException exception) {
            String message = "Download finished, but the library or manifest could not be "
                    + "updated: " + exception.getMessage();
            Platform.runLater(() -> finishWithError(message));
            return;
        }

        int finalRegisteredCount = registeredCount;
        Platform.runLater(() -> {
            if (result.exitCode() == 0) {
                outputText.appendText("Download completed!\n");
                statusLabel.setText(finalRegisteredCount > 0
                        ? "Completed · " + finalRegisteredCount + " item(s) added to library"
                        : "Download completed; no new items to register.");
                statusLabel.setTextFill(Color.GREEN);
            } else {
                outputText.appendText("Download failed or partially completed. Exit code: "
                        + result.exitCode() + "\n");
                statusLabel.setText(finalRegisteredCount > 0
                        ? "Partial failure · " + finalRegisteredCount + " item(s) saved to library"
                        : "Download failed.");
                statusLabel.setTextFill(Color.RED);
            }
            downloadButton.setDisable(false);
        });
    }

    private void finishWithError(String message) {
        outputText.appendText(message + "\n");
        statusLabel.setText(message);
        statusLabel.setTextFill(Color.RED);
        downloadButton.setDisable(false);
    }

    private void chooseDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle("Choose a directory for this list");

        Path initialDirectory = selectedOutputDirectory != null
                ? selectedOutputDirectory
                : Path.of(System.getProperty("user.home"));

        if (Files.isDirectory(initialDirectory)) {
            chooser.setInitialDirectory(initialDirectory.toFile());
        }

        File selected = chooser.showDialog(directoryField.getScene().getWindow());

        if (selected != null) {
            selectedOutputDirectory = selected.toPath().toAbsolutePath().normalize();
            directoryField.setText(selectedOutputDirectory.toString());
        }
    }

    private Path resolveOutputDirectory() throws IOException {
        Path outputDirectory = selectedOutputDirectory != null
                ? selectedOutputDirectory
                : YtdlpLocator.getProjectRootDirectory().resolve("DEFAULT_LIST");

        outputDirectory = outputDirectory.toAbsolutePath().normalize();
        Files.createDirectories(outputDirectory);

        if (!Files.isDirectory(outputDirectory) || !Files.isWritable(outputDirectory)) {
            throw new IOException("Directory is not writable: " + outputDirectory);
        }

        return outputDirectory;
    }
}
