package io.github.outcast292.jvmupdater.launcher;

import io.github.outcast292.jvmupdater.Signatures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OptionsTest {

    @TempDir
    Path dir;

    private final KeyPair keys = Signatures.generateKeyPair();

    @Test
    void readsEveryOption() throws IOException {
        Path pubFile = dir.resolve("next.pub");
        Files.writeString(pubFile, Signatures.toPem(Signatures.generateKeyPair().getPublic()));

        Options options = Options.parse(new String[]{
                "--root", "~/app", "--baseline", "/opt/app/bundle",
                "--public-key", Signatures.toBase64(keys.getPublic()), "--public-key", pubFile.toString(),
                "--runtime", "linux-x64-25", "--prove-after", "90s",
                "--feed", "https://example.com/app/stable/", "--feed-header", "Authorization: Bearer abc",
                "--check-timeout", "500ms", "--", "--open", "file.txt"});

        assertEquals(Path.of(System.getProperty("user.home"), "app"), options.root());
        assertEquals(2, options.trustedKeys().size());
        assertEquals("linux-x64-25", options.runtime());
        assertEquals(Duration.ofSeconds(90), options.proveAfter());
        assertEquals(URI.create("https://example.com/app/stable/"), options.feed());
        assertEquals(Map.of("Authorization", "Bearer abc"), options.feedHeaders());
        assertEquals(Duration.ofMillis(500), options.checkTimeout());
        assertEquals(List.of("--open", "file.txt"), options.appArgs());
    }

    @Test
    void hasDefaults() {
        Options options = Options.parse(new String[]{"--root", "r", "--baseline", "b",
                "--public-key", Signatures.toBase64(keys.getPublic())});

        assertEquals(Duration.ofSeconds(120), options.proveAfter());
        assertEquals(Duration.ofSeconds(10), options.checkTimeout());
        assertNull(options.feed());
        assertNull(options.runtime());
    }

    @Test
    void requiresRootBaselineAndKey() {
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[]{"--root", "r", "--baseline", "b"}));
    }

    @Test
    void refusesUnknownOptionsAndBadDurations() {
        assertThrows(IllegalArgumentException.class, () -> Options.parse(new String[]{"--rot", "r"}));
        assertThrows(IllegalArgumentException.class, () -> Options.duration("ten"));
        assertEquals(Duration.ofMinutes(2), Options.duration("2m"));
    }
}
