package hello;

import io.github.outcast292.jvmupdater.HttpFeed;
import io.github.outcast292.jvmupdater.Updater;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.net.URI;
import java.util.Optional;

public class HelloApp extends Application {

    private final Label status = new Label();
    private final Button check = new Button("Check for updates");
    private final Button restart = new Button("Restart on the new version");

    @Override
    public void start(Stage stage) {
        String version = System.getProperty(Updater.PROPERTY_VERSION, "dev");
        Label hello = new Label("Hello from version " + version);
        hello.setStyle("-fx-font-size: 22px");

        restart.setVisible(false);
        restart.setOnAction(event -> System.exit(Updater.RESTART_EXIT_CODE));
        check.setOnAction(event -> checkForUpdates());

        VBox root = new VBox(12, hello, check, status, restart);
        root.setPadding(new Insets(24));
        stage.setTitle("hello-fx " + version);
        stage.setScene(new Scene(root, 380, 200));
        stage.show();
    }

    private void checkForUpdates() {
        Optional<Updater> updater = Updater.fromSystemProperties();
        if (updater.isEmpty()) {
            status.setText("Not started by the jvm-updater launcher");
            return;
        }
        check.setDisable(true);
        status.setText("Checking...");
        Thread.ofVirtual().start(() -> {
            String text;
            boolean staged = false;
            try {
                HttpFeed feed = new HttpFeed(URI.create(System.getProperty("hello.feed")));
                Updater.Result result = updater.get().checkAndStage(feed);
                staged = result.status() == Updater.Status.STAGED;
                text = result.detail();
            } catch (Exception e) {
                text = "Update failed: " + e.getMessage();
            }
            String message = text;
            boolean ready = staged;
            Platform.runLater(() -> {
                status.setText(message);
                restart.setVisible(ready);
                check.setDisable(false);
            });
        });
    }
}
