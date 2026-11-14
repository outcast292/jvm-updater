package io.github.outcast292.jvmupdater;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DirectoryFeedTest {

    @TempDir
    Path dir;

    @Test
    void emptyFolderOffersNothing() throws IOException {
        assertEquals(Optional.empty(), new DirectoryFeed(dir).latestVersion());
    }

    @Test
    void readsTheLayout() throws IOException {
        Files.writeString(dir.resolve("latest"), "2.0.0\n");
        Files.createDirectories(dir.resolve("2.0.0/files/lib"));
        Files.write(dir.resolve("2.0.0/manifest"), new byte[]{1});
        Files.write(dir.resolve("2.0.0/manifest.sig"), new byte[]{2});
        Files.write(dir.resolve("2.0.0/files/lib/app.jar"), new byte[]{3});
        DirectoryFeed feed = new DirectoryFeed(dir);

        assertEquals("2.0.0", feed.latestVersion().orElseThrow());
        assertArrayEquals(new byte[]{1}, feed.manifest("2.0.0"));
        assertArrayEquals(new byte[]{2}, feed.signature("2.0.0"));
        try (InputStream in = feed.open("2.0.0", new Manifest.Entry("lib/app.jar", "a".repeat(64), 1))) {
            assertArrayEquals(new byte[]{3}, in.readAllBytes());
        }
    }

    @Test
    void refusesVersionsThatWouldEscapeTheFolder() throws IOException {
        Files.writeString(dir.resolve("latest"), "../outside");
        assertThrows(UpdateException.class, () -> new DirectoryFeed(dir).latestVersion());
        assertThrows(IllegalArgumentException.class, () -> new DirectoryFeed(dir).manifest("../outside"));
    }
}
