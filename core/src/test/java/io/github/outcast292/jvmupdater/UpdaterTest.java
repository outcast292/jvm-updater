package io.github.outcast292.jvmupdater;

import io.github.outcast292.jvmupdater.internal.Release;
import io.github.outcast292.jvmupdater.internal.Store;
import io.github.outcast292.jvmupdater.testing.TestReleases;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdaterTest {

    @TempDir
    Path dir;

    private TestReleases releases;
    private Path root;

    @BeforeEach
    void setUp() {
        releases = new TestReleases(dir);
        root = dir.resolve("installed");
    }

    private Updater updater(String runningVersion) {
        return Updater.builder(root)
                .trust(releases.keys().getPublic())
                .runtime("linux-x64-25")
                .runningVersion(runningVersion)
                .build();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void stagesANewerRelease() throws IOException {
        releases.publishLatest("1.1.0", Map.of("lib/app.jar", bytes("app 1.1"), "conf/app.properties", bytes("x=1")));
        Updater updater = updater("1.0.0");

        Updater.Result result = updater.checkAndStage(new DirectoryFeed(releases.feed()));

        assertEquals(Updater.Status.STAGED, result.status());
        assertEquals("1.1.0", result.version());
        assertEquals(Optional.of("1.1.0"), updater.pendingVersion());
        Release staged = Release.verify(new Store(root).versionDir("1.1.0"), List.of(releases.keys().getPublic()), "linux-x64-25");
        assertArrayEquals(bytes("app 1.1"), Files.readAllBytes(staged.filesDir().resolve("lib/app.jar")));
        assertFalse(Files.exists(new Store(root).stagingDir("1.1.0")));
    }

    @Test
    void nothingToDoWhenTheFeedIsEmptyOrNotNewer() throws IOException {
        Updater updater = updater("1.0.0");
        assertEquals(Updater.Status.UP_TO_DATE, updater.checkAndStage(new DirectoryFeed(releases.feed())).status());

        releases.publishLatest("1.0.0", Map.of("a.jar", bytes("a")));
        assertEquals(Updater.Status.UP_TO_DATE, updater.checkAndStage(new DirectoryFeed(releases.feed())).status());
        assertEquals(Optional.empty(), updater.pendingVersion());
    }

    @Test
    void doesNotDownloadTwice() throws IOException {
        releases.publishLatest("1.1.0", Map.of("a.jar", bytes("a")));
        Updater updater = updater("1.0.0");
        updater.checkAndStage(new DirectoryFeed(releases.feed()));

        Updater.Result again = updater.checkAndStage(new DirectoryFeed(releases.feed()));
        assertEquals(Updater.Status.UP_TO_DATE, again.status());
    }

    @Test
    void refusesReleasesSignedByAnotherKey() {
        releases.publishLatest("1.1.0", Map.of("a.jar", bytes("a")));
        Updater stranger = Updater.builder(root)
                .trust(Signatures.generateKeyPair().getPublic())
                .runningVersion("1.0.0")
                .build();

        assertThrows(UpdateException.class, () -> stranger.checkAndStage(new DirectoryFeed(releases.feed())));
        assertFalse(Files.exists(new Store(root).versionDir("1.1.0")));
    }

    @Test
    void refusesFilesChangedAfterSigning() throws IOException {
        Path release = releases.publishLatest("1.1.0", Map.of("lib/app.jar", bytes("original")));
        Files.write(release.resolve("files/lib/app.jar"), bytes("tampered"));
        Updater updater = updater("1.0.0");

        assertThrows(UpdateException.class, () -> updater.checkAndStage(new DirectoryFeed(releases.feed())));
        assertEquals(Optional.empty(), updater.pendingVersion());
        assertFalse(Files.exists(new Store(root).versionDir("1.1.0")));
    }

    @Test
    void refusesAManifestPublishedUnderAnotherVersion() throws IOException {
        Path old = releases.publish("1.0.0", Map.of("a.jar", bytes("a")));
        Path replayed = Files.createDirectories(releases.feed().resolve("9.0.0"));
        Files.copy(old.resolve("manifest"), replayed.resolve("manifest"));
        Files.copy(old.resolve("manifest.sig"), replayed.resolve("manifest.sig"));
        Files.writeString(releases.feed().resolve("latest"), "9.0.0");

        assertThrows(UpdateException.class, () -> updater("0.9.0").checkAndStage(new DirectoryFeed(releases.feed())));
    }

    @Test
    void downloadsOnlyWhatChanged() throws IOException {
        releases.publishLatest("1.0.0", Map.of("lib/shared.jar", bytes("same in both"), "lib/app.jar", bytes("app 1.0")));
        Updater first = updater(null);
        first.checkAndStage(new DirectoryFeed(releases.feed()));
        new Store(root).update(state -> state.promote("1.0.0"));

        releases.publishLatest("1.1.0", Map.of("lib/shared.jar", bytes("same in both"), "lib/app.jar", bytes("app 1.1")));
        List<String> downloaded = new ArrayList<>();
        Feed counting = new CountingFeed(new DirectoryFeed(releases.feed()), downloaded);
        List<long[]> progress = new ArrayList<>();

        Updater.Result result = updater("1.0.0").checkAndStage(counting, (done, total) -> progress.add(new long[]{done, total}));

        assertEquals(Updater.Status.STAGED, result.status());
        assertEquals(List.of("lib/app.jar"), downloaded);
        long[] last = progress.getLast();
        assertEquals(bytes("app 1.1").length, last[0]);
        assertEquals(last[0], last[1]);
    }

    @Test
    void reusesFilesFromTheBaseline() throws IOException {
        Path baseline = releases.publish("1.0.0", Map.of("lib/shared.jar", bytes("shared")));
        releases.publishLatest("1.1.0", Map.of("lib/shared.jar", bytes("shared"), "lib/new.jar", bytes("new")));
        List<String> downloaded = new ArrayList<>();

        Updater updater = Updater.builder(root).trust(releases.keys().getPublic()).baseline(baseline).build();
        updater.checkAndStage(new CountingFeed(new DirectoryFeed(releases.feed()), downloaded));

        assertEquals(List.of("lib/new.jar"), downloaded);
    }

    @Test
    void doesNotInstallOlderThanTheBaseline() throws IOException {
        Path baseline = releases.publish("2.0.0", Map.of("a.jar", bytes("a")));
        releases.publishLatest("1.5.0", Map.of("a.jar", bytes("b")));

        Updater updater = Updater.builder(root).trust(releases.keys().getPublic()).baseline(baseline).build();
        assertEquals(Updater.Status.UP_TO_DATE, updater.checkAndStage(new DirectoryFeed(releases.feed())).status());
    }

    @Test
    void reportsReleasesForAnotherRuntime() throws IOException {
        releases.publish("1.1.0", Map.of("a.jar", bytes("a")), builder -> builder.runtime("windows-x64-25"));
        releases.setLatest("1.1.0");

        Updater.Result result = updater("1.0.0").checkAndStage(new DirectoryFeed(releases.feed()));

        assertEquals(Updater.Status.INCOMPATIBLE, result.status());
        assertTrue(result.detail().contains("windows-x64-25"), result.detail());
        assertNull(new Store(root).read().pending());
    }

    @Test
    void skipsVersionsThatFailedBefore() throws IOException {
        releases.publishLatest("1.1.0", Map.of("a.jar", bytes("a")));
        new Store(root).update(state -> state.markBad("1.1.0"));

        assertEquals(Updater.Status.UP_TO_DATE, updater("1.0.0").checkAndStage(new DirectoryFeed(releases.feed())).status());
    }

    @Test
    void startsOverAfterAnInterruptedDownload() throws IOException {
        releases.publishLatest("1.1.0", Map.of("lib/app.jar", bytes("app")));
        Path leftover = new Store(root).stagingDir("1.1.0").resolve("files/lib/app.jar");
        Files.createDirectories(leftover.getParent());
        Files.write(leftover, bytes("half"));

        assertEquals(Updater.Status.STAGED, updater("1.0.0").checkAndStage(new DirectoryFeed(releases.feed())).status());
    }

    @Test
    void pendingMustBeNewerThanTheRunningVersion() throws IOException {
        new Store(root).update(state -> state.withPending("1.0.0"));
        assertEquals(Optional.empty(), updater("1.0.0").pendingVersion());
        assertEquals(Optional.of("1.0.0"), updater("0.9.0").pendingVersion());
    }

    @Test
    void notManagedWithoutLauncherProperties() {
        assertEquals(Optional.empty(), Updater.fromSystemProperties());
    }

    @Test
    void needsATrustedKey() {
        assertThrows(IllegalArgumentException.class, () -> Updater.builder(root).build());
    }

    private record CountingFeed(Feed delegate, List<String> downloaded) implements Feed {
        @Override
        public Optional<String> latestVersion() throws IOException {
            return delegate.latestVersion();
        }

        @Override
        public byte[] manifest(String version) throws IOException {
            return delegate.manifest(version);
        }

        @Override
        public byte[] signature(String version) throws IOException {
            return delegate.signature(version);
        }

        @Override
        public InputStream open(String version, Manifest.Entry file) throws IOException {
            downloaded.add(file.path());
            return delegate.open(version, file);
        }
    }
}
