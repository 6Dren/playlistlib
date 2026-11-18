package com.playlistlib.controller;

import com.playlistlib.database.DatabaseManager;
import com.playlistlib.model.Media;
import com.playlistlib.model.MediaList;
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
import javafx.stage.Stage;

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

public class LibraryController {

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

        listsView.getSelectionModel()
                .selectedItemProperty()
                .addListener((observable, previousList, selectedList) -> {
                    loadSelectedList(selectedList);
                });

        refreshLibrary();
    }

    private void configureTable() {
        titleColumn.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(cell.getValue().getTitle()));

        durationColumn.setCellValueFactory(cell ->
                new ReadOnlyStringWrapper(formatDuration(
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
            List<MediaList> lists = findAllLists();
            listsView.getItems().setAll(lists);

            if (previouslySelectedId != null) {
                for (int i = 0; i < lists.size(); i++) {
                    if (lists.get(i).getId().equals(previouslySelectedId)) {
                        listsView.getSelectionModel().select(i);
                        statusLabel.setText("Library refreshed.");
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
                        parseInstant(result.getString("updated_at"))
                ));
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
    private void goBack() throws IOException {
        Parent root = FXMLLoader.load(
                getClass().getResource("/com/playlistlib/main.fxml"));
        Stage stage = (Stage) listsView.getScene().getWindow();
        stage.setScene(new Scene(root));
        stage.sizeToScene();
    }
}
