package com.playlistlib;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class MainApp extends Application {

    @Override
    public void start(Stage stage) throws Exception {
        // Load the FXML file relative to this class location
        FXMLLoader loader = new FXMLLoader(getClass().getResource("main.fxml"));
        Parent root = loader.load();

        // Put the loaded FXML root onto your scene
        Scene scene = new Scene(root);

        stage.setTitle("Playlistlib");
        stage.setScene(scene);
        stage.setMinWidth(310);
        stage.setMinHeight(420);
        stage.setResizable(false);
        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
