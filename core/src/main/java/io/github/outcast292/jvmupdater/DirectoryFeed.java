package io.github.outcast292.jvmupdater;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Reads the {@link Layout} from a folder: a network share, a USB stick, a test fixture. */
public final class DirectoryFeed implements Feed {

    private final Path base;

    public DirectoryFeed(Path base) {
        this.base = base;
    }

    @Override
    public Optional<String> latestVersion() throws IOException {
        Path latest = base.resolve(Layout.LATEST);
        if (!Files.exists(latest)) {
            return Optional.empty();
        }
        String version = Files.readString(latest, StandardCharsets.UTF_8).strip();
        if (!Versions.isValid(version)) {
            throw new UpdateException(latest + " holds an invalid version: " + version);
        }
        return Optional.of(version);
    }

    @Override
    public byte[] manifest(String version) throws IOException {
        return Files.readAllBytes(base.resolve(Layout.manifest(version)));
    }

    @Override
    public byte[] signature(String version) throws IOException {
        return Files.readAllBytes(base.resolve(Layout.signature(version)));
    }

    @Override
    public InputStream open(String version, Manifest.Entry file) throws IOException {
        return Files.newInputStream(base.resolve(Layout.file(version, file)));
    }
}
