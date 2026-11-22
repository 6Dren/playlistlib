package com.playlistlib.controller;

import com.playlistlib.service.LibraryService;
import com.playlistlib.service.LibraryService.DownloadedFile;
import com.playlistlib.service.LibraryService.DownloadedMedia;
import com.playlistlib.service.YtdlpDownloadService;
import com.playlistlib.database.DatabaseManager;
import com.playlistlib.model.Media;
import com.playlistlib.model.MediaList;
import com.playlistlib.repository.MediaListRepository;
import com.playlistlib.repository.MediaRepository;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;

import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collector;
import java.util.stream.Collectors;

public class LibraryController {
    @FXML
    private TextField searchMediaField;


    private static final List<String> AUDIO_FORMATS = YtdlpDownloadService.AUDIO_FORMATS;

    private static final List<String> VIDEO_FORMATS = YtdlpDownloadService.VIDEO_FORMATS;

    public static final List<String> ALL_FORMATS = YtdlpDownloadService.ALL_FORMATS;

    private final LibraryService libraryService = new LibraryService();
    private MediaList selectedList;

    @FXML
    private ListView<MediaList> listsView;

    @FXML
    private TableView<Media> mediaTable;

    @FXML
    private TableColumn<Media, String> titleColumn;

    @FXML
    private TableColumn<Media, String> durationColumn;

    @FXML
    private TableColumn<Media, String> sourceColumn;

    @FXML
    private Label selectedListLabel;

    @FXML
    private Label mediaCountLabel;

    @FXML
    private Label statusLabel;

    private final MediaRepository mediaRepository = new MediaRepository();

    @FXML
    private void initialize() {
        configureTable();

        listsView.getSelectionModel().selectedItemProperty().addListener(
                (observable, oldValue, newValue) -> {

                    selectedList = newValue;

                    loadSelectedList(newValue);
                });
        refreshLibrary();
    }

    private void searchMedia() {
        
    }

    private void configureTable() {
        titleColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().getTitle()));

        durationColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(formatDuration(
                cell.getValue().getDurationSeconds())));

        sourceColumn.setCellValueFactory(cell -> {
            Media media = cell.getValue();
            String source = media.getSourceId();

            if (source == null || source.isBlank()) {
                source = media.getSourceUrl();
            }
            if (source == null || source.isBlank()) {
                source = media.getExtractor();
            }

            return new ReadOnlyStringWrapper(
                    source == null || source.isBlank() ? "—" : source);
        });

        mediaTable.setItems(FXCollections.observableArrayList());
        mediaTable.setPlaceholder(new Label("This list has no media yet."));
    }

    @FXML
    private void refreshLibrary() {
        String previouslySelectedId = Optional.ofNullable(
                listsView.getSelectionModel().getSelectedItem())
                .map(MediaList::getId)
                .orElse(null);

        try {
            int removedLists = libraryService.reconcileLists();

            List<MediaList> lists = findAllLists();
            listsView.getItems().setAll(lists);

            if (previouslySelectedId != null) {
                for (int i = 0; i < lists.size(); i++) {
                    if (lists.get(i).getId().equals(previouslySelectedId)) {
                        listsView.getSelectionModel().select(i);

                        statusLabel.setText(
                                removedLists > 0
                                        ? removedLists + " missing list(s) removed."
                                        : "Library refreshed.");

                        return;
                    }
                }
            }

            if (!lists.isEmpty()) {
                listsView.getSelectionModel().selectFirst();
            } else {
                mediaTable.getItems().clear();
                selectedListLabel.setText("No list selected");
                mediaCountLabel.setText("0 media items");
                statusLabel.setText("No lists found in the database.");
            }
        } catch (SQLException | IOException | RuntimeException e) {
            statusLabel.setText("Could not load library: " + e.getMessage());
        }
    }

    private List<MediaList> findAllLists() throws SQLException, IOException {
        String sql = """
                SELECT id, name, directory_path, manifest_version,
                       created_at, updated_at
                FROM media_lists
                ORDER BY name COLLATE NOCASE, id
                """;

        List<MediaList> lists = new ArrayList<>();

        try (Connection connection = DatabaseManager.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet result = statement.executeQuery()) {

            while (result.next()) {
                lists.add(new MediaList(
                        result.getString("id"),
                        result.getString("name"),
                        result.getString("directory_path"),
                        result.getInt("manifest_version"),
                        parseInstant(result.getString("created_at")),
                        parseInstant(result.getString("updated_at"))));
            }
        }

        return lists;
    }

    private void loadSelectedList(MediaList selectedList) {
        if (selectedList == null) {
            mediaTable.getItems().clear();
            selectedListLabel.setText("No list selected");
            mediaCountLabel.setText("0 media items");
            return;
        }

        try {
            List<Media> media = mediaRepository.findByListId(selectedList.getId());
            mediaTable.getItems().setAll(media);
            selectedListLabel.setText(selectedList.getName());
            mediaCountLabel.setText(media.size() +
                    (media.size() == 1 ? " media item" : " media items"));
            statusLabel.setText(selectedList.getDirectoryPath());
        } catch (SQLException | IOException | RuntimeException e) {
            mediaTable.getItems().clear();
            selectedListLabel.setText(selectedList.getName());
            mediaCountLabel.setText("0 media items");
            statusLabel.setText("Could not load this list: " + e.getMessage());
        }
    }

    private Instant parseInstant(String value) throws SQLException {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException | NullPointerException e) {
            throw new SQLException("Invalid timestamp stored in database: " + value, e);
        }
    }

    private String formatDuration(Double durationSeconds) {
        if (durationSeconds == null || !Double.isFinite(durationSeconds)
                || durationSeconds < 0) {
            return "—";
        }

        long totalSeconds = Math.round(durationSeconds);
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format("%d:%02d", minutes, seconds);
    }

    @FXML
    void importList() {
        // Create the directory chooser.
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle("Import Existing List");

        // Open the chooser.
        java.io.File selectedDirectory = directoryChooser.showDialog(
                listsView.getScene().getWindow());

        // User cancelled the operation.
        if (selectedDirectory == null) {
            return;
        }

        try {
            // Convert the selected directory into a Path.
            Path directory = selectedDirectory.toPath();

            // Import the list.
            MediaList importedList = libraryService.importList(directory);

            // Refresh the library.
            refreshLibrary();

            // Select the newly imported list.
            for (int i = 0; i < listsView.getItems().size(); i++) {

                MediaList list = listsView.getItems().get(i);

                if (list.getId().equals(importedList.getId())) {
                    listsView.getSelectionModel().select(i);
                    break;
                }
            }

            statusLabel.setText(
                    "Successfully imported: " + importedList.getName());

        } catch (IOException | SQLException | RuntimeException e) {

            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Import Error");
            alert.setHeaderText("Could not import the selected list.");
            alert.setContentText(e.getMessage());
            alert.showAndWait();
        }

    }

    @FXML
    private void removeList() {

        // Check whether a list is selected.
        if (selectedList == null) {

            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("No List Selected");
            alert.setHeaderText(null);
            alert.setContentText("Please select a list first.");
            alert.showAndWait();

            return;
        }

        // Create the two deletion options.
        ButtonType deleteDatabase = new ButtonType(
                "Delete list from database");

        ButtonType deletePhysical = new ButtonType(
                "Delete list from database and physically",
                ButtonBar.ButtonData.OTHER);

        // Create the dialogue.
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);

        alert.setTitle("Remove List");
        alert.setHeaderText("Remove \"" + selectedList.getName() + "\"?");
        alert.setContentText("Choose how you want to remove this list.");

        alert.getButtonTypes().setAll(
                deleteDatabase,
                deletePhysical,
                ButtonType.CANCEL);

        Optional<ButtonType> result = alert.showAndWait();

        // User cancelled or closed the dialogue.
        if (result.isEmpty() || result.get() == ButtonType.CANCEL) {
            return;
        }

        try {

            if (result.get() == deleteDatabase) {

                libraryService.removeList(selectedList);

                statusLabel.setText(
                        "List removed from database.");

            } else if (result.get() == deletePhysical) {

                MediaList listToRemove = selectedList;

                // Ask for one final confirmation.
                Alert confirmation = new Alert(Alert.AlertType.WARNING);

                confirmation.setTitle("Permanent Deletion");

                confirmation.setHeaderText(
                        "Permanently delete \"" + listToRemove.getName() + "\"?");

                confirmation.setContentText(
                        "This will permanently delete the entire directory:\n\n"
                                + listToRemove.getDirectoryPath()
                                + "\n\nAll files and nested folders inside it will be deleted.");

                ButtonType permanentlyDelete = new ButtonType(
                        "Delete permanently",
                        ButtonBar.ButtonData.OTHER);

                confirmation.getButtonTypes().setAll(
                        permanentlyDelete,
                        ButtonType.CANCEL);

                Optional<ButtonType> confirmationResult = confirmation.showAndWait();

                if (confirmationResult.isEmpty()
                        || confirmationResult.get() != permanentlyDelete) {
                    return;
                }

                // Perform physical and database deletion.
                libraryService.removeListPhysically(listToRemove);

                selectedList = null;

                refreshLibrary();

                statusLabel.setText(
                        "Permanently deleted: " + listToRemove.getName());

            }

            // Refresh the ListView and clear the selection.
            selectedList = null;
            refreshLibrary();

        } catch (SQLException | IOException exception) {

            Alert error = new Alert(Alert.AlertType.ERROR);

            error.setTitle("Deletion Error");
            error.setHeaderText("Could not remove the selected list.");
            error.setContentText(exception.getMessage());

            error.showAndWait();
        }
    }

    @FXML
    private void addMedia() {
        if (selectedList == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("No List Selected");
            alert.setHeaderText(null);
            alert.setContentText("Please select a List first.");
            alert.showAndWait();
            return;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Select a media");

        List<String> audioExtensions = AUDIO_FORMATS.stream()
                .map(f -> "*." + f.toLowerCase())
                .toList();

        List<String> videoExtensions = VIDEO_FORMATS.stream()
                .map(f -> "*." + f.toLowerCase())
                .toList();

        List<String> allExtensions = ALL_FORMATS.stream()
                .map(f -> "*." + f.toLowerCase())
                .toList();

        FileChooser.ExtensionFilter audioFilter = new FileChooser.ExtensionFilter(
                String.format("Audio Files (%s)", String.join(",", audioExtensions)), audioExtensions);

        FileChooser.ExtensionFilter videoFilter = new FileChooser.ExtensionFilter(
                String.format("video Files (%s)", String.join(",", videoExtensions)), videoExtensions);

        FileChooser.ExtensionFilter allFilter = new FileChooser.ExtensionFilter(
                String.format("All formats (%s)", String.join(",", allExtensions)), allExtensions);

        fileChooser.getExtensionFilters().addAll(audioFilter, videoFilter, allFilter);

        // Open the chooser.
        java.io.File selectedFile = fileChooser.showOpenDialog(
                listsView.getScene().getWindow());

        // User cancelled the operation.
        if (selectedFile == null) {
            return;
        }

        Path sourcePath = selectedFile.toPath();
        if (!Files.isRegularFile(sourcePath)) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("Invalid Media File");
            alert.setHeaderText(null);
            alert.setContentText("The selected file is not a valid regular file: " + sourcePath.toString());
            alert.showAndWait();
            return;
        }

        String originalFileName = selectedFile.getName();
        String fileName = originalFileName.replaceFirst("\\.[^.]+$", "");

        String format = "";

        int lastDotIndex = originalFileName.lastIndexOf('.');
        if (lastDotIndex > 0 && lastDotIndex < originalFileName.length() - 1) {
            format = originalFileName.substring(lastDotIndex + 1).toUpperCase();
        }

        TextInputDialog dialog = new TextInputDialog(fileName); // Optional default value
        dialog.setTitle("Adding Media");
        dialog.setHeaderText("Enter your details");
        dialog.setContentText("Media title:");

        Optional<String> result = dialog.showAndWait();

        if (result.isEmpty() || result.get().isBlank()) {
            return;
        }

        String title = result.get().trim();

        Path listDirectory = Path.of(selectedList.getDirectoryPath());
        if (!Files.exists(listDirectory)) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("List could not be found");
            alert.setHeaderText(null);
            alert.setContentText("List's directory couldn't be found:" + listDirectory.toString());
            alert.showAndWait();
            return;
        }

        Path targetPath = listDirectory.resolve(sourcePath.getFileName());
        if (Files.exists(targetPath)) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("File Already Exists");
            alert.setHeaderText(null);
            alert.setContentText("A file with this name already exists in the selected list.");
            alert.showAndWait();
            return;
        }

        List<LibraryService.DownloadedFile> downloadedFiles = List.of(
                new LibraryService.DownloadedFile(targetPath, "MEDIA", format, null));

        List<LibraryService.DownloadedMedia> downloadedMedias = List.of(
                new LibraryService.DownloadedMedia(title, null, null, null, null, null, downloadedFiles));

        // Copy the file
        try {
            Files.copy(sourcePath, targetPath);
        } catch (IOException e) {
            e.printStackTrace();
            Alert alert = new Alert(AlertType.ERROR);
            alert.setTitle("Invalid directory");
            alert.setHeaderText(null); // Optional: removes the large header banner
            alert.setContentText("Directory couldn't be found" + e.getMessage());

            alert.showAndWait();
            return;
        }

        try {
            libraryService.registerDownloads(listDirectory, downloadedMedias);
            loadSelectedList(selectedList);
            statusLabel.setText("Media added successfully.");

        } catch (IOException | SQLException e) {
            e.printStackTrace();

            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Add Media Error");
            alert.setHeaderText("Could not add media.");
            alert.setContentText(e.getMessage());
            alert.showAndWait();
        }
    }

    @FXML
    private void removeMedia() {

        Media selectedMedia = mediaTable.getSelectionModel().getSelectedItem();

        if (selectedMedia == null) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle("No Media Selected");
            alert.setHeaderText(null);
            alert.setContentText("Please select a media item first.");
            alert.showAndWait();
            return;
        }

        ButtonType deleteDatabase = new ButtonType(
                "Remove from database");

        ButtonType deletePhysical = new ButtonType(
                "Remove from database and physically",
                ButtonBar.ButtonData.OTHER);

        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Remove Media");
        alert.setHeaderText("Remove \"" + selectedMedia.getTitle() + "\"?");
        alert.setContentText("Choose how you want to remove this media item.");

        alert.getButtonTypes().setAll(
                deleteDatabase,
                deletePhysical,
                ButtonType.CANCEL);

        Optional<ButtonType> result = alert.showAndWait();

        if (result.isEmpty() || result.get() == ButtonType.CANCEL) {
            return;
        }

        boolean deletePhysicalFiles = result.get() == deletePhysical;

        // Ask for another confirmation before physically deleting files.
        if (deletePhysicalFiles) {

            Alert confirmation = new Alert(Alert.AlertType.WARNING);

            confirmation.setTitle("Permanent Media Deletion");

            confirmation.setHeaderText(
                    "Permanently delete \"" + selectedMedia.getTitle() + "\"?");

            confirmation.setContentText(
                    "All physical files registered for this media item will be deleted.\n\n"
                            + "This action cannot be undone.");

            ButtonType permanentlyDelete = new ButtonType(
                    "Delete permanently",
                    ButtonBar.ButtonData.OTHER);

            confirmation.getButtonTypes().setAll(
                    permanentlyDelete,
                    ButtonType.CANCEL);

            Optional<ButtonType> confirmationResult = confirmation.showAndWait();

            if (confirmationResult.isEmpty()
                    || confirmationResult.get() != permanentlyDelete) {
                return;
            }
        }

        try {

            libraryService.removeMedia(selectedMedia, deletePhysicalFiles);

            loadSelectedList(selectedList);

            statusLabel.setText(deletePhysicalFiles
                    ? "Media removed from the database and its files deleted."
                    : "Media removed from the database. Physical files were kept.");

        } catch (SQLException | IOException | RuntimeException exception) {

            // Reload the current list so the table reflects the actual state.
            loadSelectedList(selectedList);

            Alert error = new Alert(Alert.AlertType.ERROR);
            error.setTitle("Media Removal Error");
            error.setHeaderText("Could not complete the media removal.");
            error.setContentText(exception.getMessage());
            error.showAndWait();
        }
    }

    @FXML
    private void goBack() throws IOException {
        Parent root = FXMLLoader.load(
                getClass().getResource("/com/playlistlib/main.fxml"));
        Stage stage = (Stage) listsView.getScene().getWindow();
        stage.setScene(new Scene(root));
        stage.sizeToScene();
    }
}
