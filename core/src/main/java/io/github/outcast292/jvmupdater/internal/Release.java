package io.github.outcast292.jvmupdater.internal;

import io.github.outcast292.jvmupdater.Layout;
import io.github.outcast292.jvmupdater.Manifest;
import io.github.outcast292.jvmupdater.Signatures;
import io.github.outcast292.jvmupdater.UpdateException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.PublicKey;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/** A release directory on disk whose signature and files have been checked. */
public record Release(Path dir, Manifest manifest) {

    public String version() {
        return manifest.version();
    }

    public Path filesDir() {
        return dir.resolve(Layout.FILES);
    }

    public List<Path> jars() {
        return manifest.files().stream()
                .filter(Manifest.Entry::isJar)
                .map(entry -> filesDir().resolve(entry.path()))
                .toList();
    }

    /**
     * Checks that {@code dir} holds exactly the release its signed manifest describes.
     *
     * @param runtime the launcher's runtime, or null to accept any
     */
    public static Release verify(Path dir, Collection<PublicKey> trustedKeys, String runtime) throws IOException {
        byte[] manifestBytes = Files.readAllBytes(dir.resolve(Layout.MANIFEST));
        byte[] signature = Files.readAllBytes(dir.resolve(Layout.SIGNATURE));
        if (!Signatures.verify(manifestBytes, signature, trustedKeys)) {
            throw new UpdateException(dir + " is not signed by a trusted key");
        }
        Manifest manifest;
        try {
            manifest = Manifest.parse(manifestBytes);
        } catch (IllegalArgumentException e) {
            throw new UpdateException("Invalid manifest in " + dir + ": " + e.getMessage(), e);
        }
        if (!manifest.runsOn(runtime)) {
            throw new UpdateException(dir + " was built for runtime " + manifest.runtime().orElseThrow()
                    + ", this one is " + runtime);
        }

        Path files = dir.resolve(Layout.FILES);
        Set<String> expected = new HashSet<>();
        for (Manifest.Entry entry : manifest.files()) {
            expected.add(entry.path());
            Path file = files.resolve(entry.path());
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new UpdateException("Missing file in " + dir + ": " + entry.path());
            }
            if (Files.size(file) != entry.size() || !Hashing.sha256(file).equals(entry.sha256())) {
                throw new UpdateException("Changed file in " + dir + ": " + entry.path());
            }
        }
        if (Files.isDirectory(files)) {
            try (Stream<Path> walk = Files.walk(files)) {
                for (Path path : (Iterable<Path>) walk::iterator) {
                    if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        String relative = files.relativize(path).toString().replace(File.separatorChar, '/');
                        if (!expected.contains(relative)) {
                            throw new UpdateException("Unexpected file in " + dir + ": " + relative);
                        }
                    }
                }
            }
        }
        return new Release(dir, manifest);
    }

    /** The manifest of {@code dir} without any check, or empty when it can't be read. */
    public static Optional<Manifest> peek(Path dir) {
        try {
            return Optional.of(Manifest.parse(Files.readAllBytes(dir.resolve(Layout.MANIFEST))));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
