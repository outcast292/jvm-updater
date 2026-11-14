package io.github.outcast292.jvmupdater.launcher;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * The app the supervisor tests start. Its release's JVM arguments say what to do: where to note
 * that it ran, how long to run, which exit code to use, and when to ask for a restart.
 */
public final class FixtureApp {

    public static void main(String[] args) throws Exception {
        String line = System.getProperty("jvmupdater.version") + " " + String.join(" ", args);
        Files.writeString(Path.of(System.getProperty("fixture.marker")), line.strip() + "\n",
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        Thread.sleep(Long.getLong("fixture.sleep", 0));

        String restartOnce = System.getProperty("fixture.restartOnce");
        if (restartOnce != null && Files.deleteIfExists(Path.of(restartOnce))) {
            System.exit(75);
        }
        System.exit(Integer.getInteger("fixture.exit", 0));
    }
}
