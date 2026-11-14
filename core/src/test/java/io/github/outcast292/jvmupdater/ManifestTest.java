package io.github.outcast292.jvmupdater;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestTest {

    private static final String SHA_A = "a".repeat(64);
    private static final String SHA_B = "b".repeat(64);

    private static Manifest sample() {
        return Manifest.builder("1.4.0")
                .runtime("linux-x64-25")
                .mainModule("com.example.app/com.example.app.Main")
                .jvmArg("--enable-native-access=ALL-UNNAMED")
                .jvmArg("-Xmx512m")
                .file("lib/app.jar", SHA_A, 1200)
                .file("lib/javafx-graphics-25.0.5-linux.jar", SHA_B, 0)
                .build();
    }

    private static Manifest parse(String text) {
        return Manifest.parse(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void roundTripsThroughText() {
        Manifest manifest = sample();
        byte[] bytes = manifest.toBytes();
        Manifest parsed = Manifest.parse(bytes);

        assertArrayEquals(bytes, parsed.toBytes());
        assertEquals("1.4.0", parsed.version());
        assertEquals("linux-x64-25", parsed.runtime().orElseThrow());
        assertEquals("com.example.app/com.example.app.Main", parsed.mainModule().orElseThrow());
        assertEquals(List.of("--enable-native-access=ALL-UNNAMED", "-Xmx512m"), parsed.jvmArgs());
        assertEquals(2, parsed.files().size());
        assertEquals(new Manifest.Entry("lib/app.jar", SHA_A, 1200), parsed.files().getFirst());
    }

    @Test
    void readsWindowsLineEndingsAndComments() {
        Manifest manifest = parse("format=1\r\n# built by CI\r\nversion=2.0\r\nmain-class=a.Main\r\n");
        assertEquals("2.0", manifest.version());
        assertEquals("a.Main", manifest.mainClass().orElseThrow());
    }

    @Test
    void refusesNewerFormats() {
        var error = assertThrows(IllegalArgumentException.class,
                () -> parse("format=2\nversion=1.0\nmain-class=a.Main\n"));
        assertTrue(error.getMessage().contains("format 2"), error.getMessage());
    }

    @Test
    void refusesUnknownKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> parse("format=1\nversion=1.0\nmain-class=a.Main\npost-install=rm -rf /\n"));
    }

    @Test
    void refusesDuplicateSingleKeys() {
        assertThrows(IllegalArgumentException.class,
                () -> parse("format=1\nversion=1.0\nmain-class=a.Main\nmain-class=b.Main\n"));
    }

    @Test
    void needsExactlyOneMain() {
        assertThrows(IllegalArgumentException.class, () -> parse("format=1\nversion=1.0\n"));
        assertThrows(IllegalArgumentException.class,
                () -> parse("format=1\nversion=1.0\nmain-class=a.Main\nmain-module=m/a.Main\n"));
    }

    @Test
    void refusesFilesThatDifferOnlyByCase() {
        assertThrows(IllegalArgumentException.class, () -> Manifest.builder("1.0").mainClass("a.Main")
                .file("lib/App.jar", SHA_A, 1)
                .file("lib/app.jar", SHA_B, 1)
                .build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../evil.jar", "/etc/passwd", "lib//a.jar", "lib/./a.jar", "C:\\a.jar",
            "lib/a b.jar", "a.jar/", ""})
    void refusesPathsThatLeaveTheReleaseDirectory(String path) {
        assertThrows(IllegalArgumentException.class, () -> new Manifest.Entry(path, SHA_A, 1));
    }

    @Test
    void refusesBadHashes() {
        assertThrows(IllegalArgumentException.class, () -> new Manifest.Entry("a.jar", "ABC", 1));
        assertThrows(IllegalArgumentException.class, () -> new Manifest.Entry("a.jar", SHA_A.toUpperCase(), 1));
    }

    @Test
    void refusesInvalidUtf8() {
        byte[] bytes = {'f', 'o', 'r', 'm', 'a', 't', '=', '1', '\n', (byte) 0xC3, (byte) 0x28};
        assertThrows(IllegalArgumentException.class, () -> Manifest.parse(bytes));
    }

    @Test
    void runtimeMatching() {
        Manifest manifest = sample();
        assertTrue(manifest.runsOn("linux-x64-25"));
        assertTrue(manifest.runsOn(null));
        assertFalse(manifest.runsOn("windows-x64-25"));

        Manifest any = Manifest.builder("1.0").mainClass("a.Main").build();
        assertTrue(any.runsOn("windows-x64-25"));
    }
}
