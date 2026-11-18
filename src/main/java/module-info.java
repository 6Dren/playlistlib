module com.playlistlib {

    requires javafx.controls;
    requires javafx.fxml;

    requires java.sql;
    requires org.xerial.sqlitejdbc;


    exports com.playlistlib;

    opens com.playlistlib to javafx.fxml;
    opens com.playlistlib.controller to javafx.fxml;

}