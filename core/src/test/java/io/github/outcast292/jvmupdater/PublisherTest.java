package io.github.outcast292.jvmupdater;

import io.github.outcast292.jvmupdater.internal.Release;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PublisherTest {

    @TempDir
    Path dir;

    private final KeyPair keys = Signatures.generateKeyPair();

    private Path files() throws IOException {
        Path files = Files.createDirectories(dir.resolve("build/lib"));
        Files.write(files.resolve("app.jar"), new byte[]{1, 2});
        Files.write(files.resolve("dep.jar"), new byte[]{3});
        return dir.resolve("build");
    }

    @Test
    void publishesASignedRelease() throws IOException {
        Path feed = dir.resolve("feed");
        Path release = Publisher.publish(files(), Manifest.builder("1.0.0").mainModule("app/app.Main"), keys.getPrivate(), feed);
        Publisher.setLatest(feed, "1.0.0");

        Release verified = Release.verify(release, List.of(keys.getPublic()), null);
        assertEquals(List.of("lib/app.jar", "lib/dep.jar"), verified.manifest().files().stream().map(Manifest.Entry::path).toList());
        assertEquals("1.0.0", new DirectoryFeed(feed).latestVersion().orElseThrow());
    }

    @Test
    void neverOverwritesAPublishedVersion() throws IOException {
        Path feed = dir.resolve("feed");
        Path files = files();
        Publisher.publish(files, Manifest.builder("1.0.0").mainClass("app.Main"), keys.getPrivate(), feed);

        assertThrows(IOException.class,
                () -> Publisher.publish(files, Manifest.builder("1.0.0").mainClass("app.Main"), keys.getPrivate(), feed));
    }

    @Test
    void latestMustPointAtAPublishedVersion() throws IOException {
        Path feed = Files.createDirectories(dir.resolve("feed"));
        assertThrows(IOException.class, () -> Publisher.setLatest(feed, "1.0.0"));
    }

    @Test
    void refusesAnEmptyFolder() throws IOException {
        Path empty = Files.createDirectories(dir.resolve("empty"));
        assertThrows(IOException.class,
                () -> Publisher.publish(empty, Manifest.builder("1.0.0").mainClass("app.Main"), keys.getPrivate(), dir.resolve("feed")));
    }
}
