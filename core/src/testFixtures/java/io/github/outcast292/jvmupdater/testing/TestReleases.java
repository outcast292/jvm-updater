package io.github.outcast292.jvmupdater.testing;

import io.github.outcast292.jvmupdater.Manifest;
import io.github.outcast292.jvmupdater.Publisher;
import io.github.outcast292.jvmupdater.Signatures;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.Map;
import java.util.function.Consumer;

/** Publishes small signed releases into a feed folder for tests. */
public final class TestReleases {

    private final Path feed;
    private final Path work;
    private final KeyPair keys;

    public TestReleases(Path dir) {
        this(dir, Signatures.generateKeyPair());
    }

    public TestReleases(Path dir, KeyPair keys) {
        this.feed = dir.resolve("feed");
        this.work = dir.resolve("work");
        this.keys = keys;
    }

    public Path feed() {
        return feed;
    }

    public KeyPair keys() {
        return keys;
    }

    public Path publish(String version, Map<String, byte[]> files) {
        return publish(version, files, builder -> { });
    }

    public Path publish(String version, Map<String, byte[]> files, Consumer<Manifest.Builder> customize) {
        try {
            Path source = Files.createDirectories(work.resolve(version));
            for (Map.Entry<String, byte[]> file : files.entrySet()) {
                Path target = source.resolve(file.getKey());
                Files.createDirectories(target.getParent());
                Files.write(target, file.getValue());
            }
            Manifest.Builder builder = Manifest.builder(version).mainClass("test.Main");
            customize.accept(builder);
            Files.createDirectories(feed);
            return Publisher.publish(source, builder, keys.getPrivate(), feed);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public Path publishLatest(String version, Map<String, byte[]> files) {
        Path release = publish(version, files);
        setLatest(version);
        return release;
    }

    public void setLatest(String version) {
        try {
            Publisher.setLatest(feed, version);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
