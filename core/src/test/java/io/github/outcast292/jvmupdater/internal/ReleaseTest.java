package io.github.outcast292.jvmupdater.internal;

import io.github.outcast292.jvmupdater.Signatures;
import io.github.outcast292.jvmupdater.UpdateException;
import io.github.outcast292.jvmupdater.testing.TestReleases;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReleaseTest {

    @TempDir
    Path dir;

    private TestReleases releases;
    private Path release;
    private List<PublicKey> keys;

    @BeforeEach
    void setUp() {
        releases = new TestReleases(dir);
        release = releases.publish("1.0.0", Map.of("lib/a.jar", new byte[]{1}, "lib/b.jar", new byte[]{2}, "readme.txt", new byte[]{3}),
                builder -> builder.runtime("linux-x64-25"));
        keys = List.of(releases.keys().getPublic());
    }

    @Test
    void acceptsAnIntactRelease() throws IOException {
        Release verified = Release.verify(release, keys, "linux-x64-25");

        assertEquals("1.0.0", verified.version());
        assertEquals(List.of(release.resolve("files/lib/a.jar"), release.resolve("files/lib/b.jar")), verified.jars());
    }

    @Test
    void refusesAMissingFile() throws IOException {
        Files.delete(release.resolve("files/lib/a.jar"));
        assertThrows(UpdateException.class, () -> Release.verify(release, keys, null));
    }

    @Test
    void refusesAChangedFile() throws IOException {
        Files.write(release.resolve("files/lib/a.jar"), new byte[]{9});
        assertThrows(UpdateException.class, () -> Release.verify(release, keys, null));
    }

    @Test
    void refusesAnAddedFile() throws IOException {
        Files.write(release.resolve("files/lib/injected.jar"), new byte[]{9});
        assertThrows(UpdateException.class, () -> Release.verify(release, keys, null));
    }

    @Test
    void refusesAnotherRuntime() {
        assertThrows(UpdateException.class, () -> Release.verify(release, keys, "windows-x64-25"));
    }

    @Test
    void refusesUntrustedKeys() {
        List<PublicKey> others = List.of(Signatures.generateKeyPair().getPublic());
        assertThrows(UpdateException.class, () -> Release.verify(release, others, null));
    }
}
