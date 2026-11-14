package io.github.outcast292.jvmupdater;

import io.github.outcast292.jvmupdater.internal.Hashing;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.PrivateKey;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Turns a folder of files into a signed release inside a feed folder (see {@link Layout}). Upload
 * the feed folder with any tool afterwards: {@code aws s3 sync}, rclone, a git push to Pages...
 */
public final class Publisher {

    private Publisher() {
    }

    /**
     * Copies every file under {@code filesDir} into {@code feedDir/<version>/files}, then writes
     * the manifest and its signature. The {@code latest} pointer is not touched.
     *
     * @param manifest version, main class or module, runtime and JVM arguments; the files are added here
     * @return the release directory, which can also be shipped as an installer's baseline
     */
    public static Path publish(Path filesDir, Manifest.Builder manifest, PrivateKey key, Path feedDir) throws IOException {
        List<Path> sources = listFiles(filesDir);
        for (Path source : sources) {
            String path = filesDir.relativize(source).toString().replace(File.separatorChar, '/');
            manifest.file(path, Hashing.sha256(source), Files.size(source));
        }
        Manifest built = manifest.build();

        Path releaseDir = feedDir.resolve(built.version());
        if (Files.exists(releaseDir)) {
            throw new IOException(releaseDir + " already exists; a published version must never change");
        }
        Path files = releaseDir.resolve(Layout.FILES);
        for (int i = 0; i < sources.size(); i++) {
            Path target = files.resolve(built.files().get(i).path());
            Files.createDirectories(target.getParent());
            Files.copy(sources.get(i), target, StandardCopyOption.COPY_ATTRIBUTES);
        }
        Files.createDirectories(releaseDir);
        byte[] manifestBytes = built.toBytes();
        Files.write(releaseDir.resolve(Layout.MANIFEST), manifestBytes);
        Files.write(releaseDir.resolve(Layout.SIGNATURE), Signatures.sign(manifestBytes, key));
        return releaseDir;
    }

    /** Points the feed at {@code version}. Upload this file last, after the release itself. */
    public static void setLatest(Path feedDir, String version) throws IOException {
        Versions.requireValid(version);
        if (!Files.isDirectory(feedDir.resolve(version))) {
            throw new IOException("Version " + version + " is not published in " + feedDir);
        }
        Path temp = Files.createTempFile(feedDir, Layout.LATEST, ".tmp");
        Files.writeString(temp, version + "\n", StandardCharsets.UTF_8);
        Files.move(temp, feedDir.resolve(Layout.LATEST), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static List<Path> listFiles(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(dir)) {
            for (Path path : (Iterable<Path>) walk.sorted()::iterator) {
                if (Files.isSymbolicLink(path)) {
                    throw new IOException("Symbolic links are not supported in a release: " + path);
                }
                if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    files.add(path);
                }
            }
        }
        if (files.isEmpty()) {
            throw new IOException("No files to publish in " + dir);
        }
        return files;
    }
}
