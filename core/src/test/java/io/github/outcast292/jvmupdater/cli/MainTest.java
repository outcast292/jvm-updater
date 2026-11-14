package io.github.outcast292.jvmupdater.cli;

import io.github.outcast292.jvmupdater.DirectoryFeed;
import io.github.outcast292.jvmupdater.Manifest;
import io.github.outcast292.jvmupdater.Signatures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    @TempDir
    Path dir;

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(String... args) {
        return new Main(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8)).run(args);
    }

    private String key(String name) throws IOException {
        var keys = Signatures.generateKeyPair();
        Path file = dir.resolve(name + ".key");
        Files.writeString(file, Signatures.toPem(keys.getPrivate()));
        Files.writeString(dir.resolve(name + ".pub"), Signatures.toPem(keys.getPublic()));
        return file.toString();
    }

    @Test
    void publishesAndVerifiesARelease() throws IOException {
        Path files = Files.createDirectories(dir.resolve("build/lib"));
        Files.write(files.resolve("app.jar"), new byte[]{1, 2, 3});
        Path feed = dir.resolve("feed");

        int code = run("publish", "--files", dir.resolve("build").toString(), "--version", "1.2.0",
                "--main-module", "app/app.Main", "--runtime", "linux-x64-25",
                "--jvm-arg", "--enable-native-access=ALL-UNNAMED", "--jvm-arg=-Xmx256m",
                "--key", key("release"), "--feed", feed.toString());

        assertEquals(0, code, err.toString(StandardCharsets.UTF_8));
        assertEquals("1.2.0", new DirectoryFeed(feed).latestVersion().orElseThrow());
        Manifest manifest = Manifest.parse(Files.readAllBytes(feed.resolve("1.2.0/manifest")));
        assertEquals(List.of("--enable-native-access=ALL-UNNAMED", "-Xmx256m"), manifest.jvmArgs());

        assertEquals(0, run("verify", feed.resolve("1.2.0").toString(), "--public-key", dir.resolve("release.pub").toString()));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("OK: 1.2.0, 1 files"));
    }

    @Test
    void publishWithoutMovingLatest() throws IOException {
        Files.write(Files.createDirectories(dir.resolve("build")).resolve("app.jar"), new byte[]{1});
        Path feed = dir.resolve("feed");

        assertEquals(0, run("publish", "--files", dir.resolve("build").toString(), "--version", "2.0.0-beta.1",
                "--main-class", "app.Main", "--key", key("release"), "--feed", feed.toString(), "--no-latest"));
        assertTrue(Files.isDirectory(feed.resolve("2.0.0-beta.1")));
        assertTrue(Files.notExists(feed.resolve("latest")));
    }

    @Test
    void verifyFailsWithAnotherKey() throws IOException {
        Files.write(Files.createDirectories(dir.resolve("build")).resolve("app.jar"), new byte[]{1});
        Path feed = dir.resolve("feed");
        run("publish", "--files", dir.resolve("build").toString(), "--version", "1.0.0",
                "--main-class", "app.Main", "--key", key("release"), "--feed", feed.toString());
        key("other");

        assertEquals(1, run("verify", feed.resolve("1.0.0").toString(), "--public-key", dir.resolve("other.pub").toString()));
    }

    @Test
    void keygenWritesAKeyPairOnce() throws IOException {
        String name = dir.resolve("release").toString();

        assertEquals(0, run("keygen", name));
        var privateKey = Signatures.readPrivateKey(Files.readString(dir.resolve("release.key")));
        var publicKey = Signatures.readPublicKey(Files.readString(dir.resolve("release.pub")));
        byte[] data = {1, 2, 3};
        assertTrue(Signatures.verify(data, Signatures.sign(data, privateKey), List.of(publicKey)));

        assertEquals(1, run("keygen", name));
    }

    @Test
    void usageErrorsExitWithTwo() {
        assertEquals(2, run());
        assertEquals(2, run("frobnicate"));
        assertEquals(2, run("publish", "--version", "1.0.0"));
        assertEquals(2, run("publish", "--files", "x", "--version", "1.0.0", "--feed", "f", "--key", "k"));
    }
}
