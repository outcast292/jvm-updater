package io.github.outcast292.jvmupdater;

import io.github.outcast292.jvmupdater.internal.Hashing;
import io.github.outcast292.jvmupdater.internal.Release;
import io.github.outcast292.jvmupdater.internal.Store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Downloads new releases next to the installed ones. The launcher starts them on its next start.
 *
 * <p>In an app started by the jvm-updater launcher, {@link #fromSystemProperties()} returns an
 * updater set up for the running release. Check for updates whenever it suits the app, then exit
 * with {@link #RESTART_EXIT_CODE} when it is a good moment to switch:
 *
 * <pre>{@code
 * Optional<Updater> updater = Updater.fromSystemProperties();
 * if (updater.isPresent()) {
 *     Feed feed = new HttpFeed(URI.create("https://example.com/app/stable/"));
 *     Updater.Result result = updater.get().checkAndStage(feed);
 *     if (result.status() == Updater.Status.STAGED) {
 *         // later, when nobody is using the app:
 *         System.exit(Updater.RESTART_EXIT_CODE);
 *     }
 * }
 * }</pre>
 */
public final class Updater {

    /** Exit code that tells the launcher to start again, on the pending release if there is one. */
    public static final int RESTART_EXIT_CODE = 75;

    public static final String PROPERTY_ROOT = "jvmupdater.root";
    public static final String PROPERTY_VERSION = "jvmupdater.version";
    public static final String PROPERTY_KEYS = "jvmupdater.keys";
    public static final String PROPERTY_RUNTIME = "jvmupdater.runtime";
    public static final String PROPERTY_BASELINE = "jvmupdater.baseline";
    public static final String PROPERTY_APP_DIR = "jvmupdater.app-dir";

    public enum Status {
        /** Nothing newer to install, or the newer version is already pending or known to fail. */
        UP_TO_DATE,
        /** A newer release was downloaded and checked; it runs after the next restart. */
        STAGED,
        /** The newer release needs another runtime: only a new installer can bring it. */
        INCOMPATIBLE
    }

    /**
     * @param version the version the feed offers, when it offers one
     * @param detail  why nothing was staged, for logs
     */
    public record Result(Status status, String version, String detail) {
    }

    private final Store store;
    private final List<PublicKey> trustedKeys;
    private final String runtime;
    private final Path baseline;
    private final String runningVersion;

    private Updater(Builder builder) {
        if (builder.trustedKeys.isEmpty()) {
            throw new IllegalArgumentException("At least one trusted public key is needed");
        }
        this.store = new Store(builder.root);
        this.trustedKeys = List.copyOf(builder.trustedKeys);
        this.runtime = builder.runtime;
        this.baseline = builder.baseline;
        this.runningVersion = builder.runningVersion;
    }

    public static Builder builder(Path root) {
        return new Builder(root);
    }

    /** The updater of an app started by the jvm-updater launcher, or empty when started otherwise. */
    public static Optional<Updater> fromSystemProperties() {
        String root = System.getProperty(PROPERTY_ROOT);
        String keys = System.getProperty(PROPERTY_KEYS);
        if (root == null || keys == null) {
            return Optional.empty();
        }
        Builder builder = builder(Path.of(root))
                .runtime(System.getProperty(PROPERTY_RUNTIME))
                .runningVersion(System.getProperty(PROPERTY_VERSION));
        for (String key : keys.split(",")) {
            if (!key.isBlank()) {
                builder.trust(Signatures.readPublicKey(key));
            }
        }
        String baseline = System.getProperty(PROPERTY_BASELINE);
        if (baseline != null) {
            builder.baseline(Path.of(baseline));
        }
        return Optional.of(builder.build());
    }

    public Optional<String> runningVersion() {
        return Optional.ofNullable(runningVersion);
    }

    /** The release waiting for a restart, if it is newer than the running one. */
    public Optional<String> pendingVersion() throws IOException {
        Store.State state = store.read();
        String pending = state.pending();
        if (pending == null || state.bad().contains(pending) || !Versions.isNewer(pending, runningVersion)) {
            return Optional.empty();
        }
        return Optional.of(pending);
    }

    public Result checkAndStage(Feed feed) throws IOException {
        return checkAndStage(feed, ProgressListener.NONE);
    }

    /**
     * Asks the feed for its latest version and, when it is newer than everything installed,
     * downloads it next to the installed releases. Files already present in an installed release
     * are copied instead of downloaded.
     *
     * @throws UpdateException when the release is refused (signature, hashes, manifest)
     * @throws IOException     when the feed or the disk fails; try again later
     */
    public synchronized Result checkAndStage(Feed feed, ProgressListener progress) throws IOException {
        Optional<String> offered = feed.latestVersion();
        if (offered.isEmpty()) {
            return new Result(Status.UP_TO_DATE, null, "The feed offers no release");
        }
        String version = offered.get();
        Store.State state = store.read();
        if (state.bad().contains(version)) {
            return new Result(Status.UP_TO_DATE, version, version + " failed before and is skipped");
        }
        String newest = newestInstalled(state);
        if (!Versions.isNewer(version, newest)) {
            return new Result(Status.UP_TO_DATE, version, "Already have " + newest);
        }

        byte[] manifestBytes = feed.manifest(version);
        byte[] signature = feed.signature(version);
        if (!Signatures.verify(manifestBytes, signature, trustedKeys)) {
            throw new UpdateException("Release " + version + " is not signed by a trusted key");
        }
        Manifest manifest;
        try {
            manifest = Manifest.parse(manifestBytes);
        } catch (IllegalArgumentException e) {
            throw new UpdateException("Release " + version + " has an invalid manifest: " + e.getMessage(), e);
        }
        if (!manifest.version().equals(version)) {
            throw new UpdateException("The feed offers " + version + " but its manifest is for " + manifest.version());
        }
        if (!manifest.runsOn(runtime)) {
            return new Result(Status.INCOMPATIBLE, version, version + " needs runtime "
                    + manifest.runtime().orElseThrow() + ", this one is " + runtime);
        }

        stage(feed, manifest, manifestBytes, signature, state, progress);
        return new Result(Status.STAGED, version, "Downloaded " + version);
    }

    private void stage(Feed feed, Manifest manifest, byte[] manifestBytes, byte[] signature,
                       Store.State state, ProgressListener progress) throws IOException {
        String version = manifest.version();
        Path part = store.stagingDir(version);
        Store.deleteRecursively(part);
        Path files = part.resolve(Layout.FILES);
        Files.createDirectories(files);

        Map<String, Path> local = localFiles(state);
        Progress counter = new Progress(progress);
        List<Manifest.Entry> toDownload = new ArrayList<>();
        for (Manifest.Entry entry : manifest.files()) {
            Path target = files.resolve(entry.path());
            Files.createDirectories(target.getParent());
            Path copy = local.get(entry.sha256());
            if (copy == null || !copyIfIntact(copy, target, entry)) {
                toDownload.add(entry);
                counter.total += entry.size();
            }
        }
        for (Manifest.Entry entry : toDownload) {
            try (InputStream in = feed.open(version, entry)) {
                write(in, files.resolve(entry.path()), entry, counter);
            }
        }
        Files.write(part.resolve(Layout.MANIFEST), manifestBytes);
        Files.write(part.resolve(Layout.SIGNATURE), signature);

        store.locked(() -> {
            Path dir = store.versionDir(version);
            Store.deleteRecursively(dir);
            Files.move(part, dir, StandardCopyOption.ATOMIC_MOVE);
            return store.update(current -> current.withPending(version));
        });
    }

    /** Files of the installed releases by hash, to avoid downloading what is already here. */
    private Map<String, Path> localFiles(Store.State state) {
        List<Path> dirs = new ArrayList<>();
        for (String version : state.installed()) {
            dirs.add(store.versionDir(version));
        }
        if (baseline != null) {
            dirs.add(baseline);
        }
        Map<String, Path> files = new HashMap<>();
        for (Path dir : dirs) {
            Release.peek(dir).ifPresent(manifest -> {
                for (Manifest.Entry entry : manifest.files()) {
                    Path file = dir.resolve(Layout.FILES).resolve(entry.path());
                    if (Files.isRegularFile(file)) {
                        files.putIfAbsent(entry.sha256(), file);
                    }
                }
            });
        }
        return files;
    }

    // The installed copy is not trusted either: it is hashed again while copying.
    private static boolean copyIfIntact(Path source, Path target, Manifest.Entry entry) throws IOException {
        try (InputStream in = Files.newInputStream(source)) {
            write(in, target, entry, null);
            return true;
        } catch (UpdateException e) {
            Files.deleteIfExists(target);
            return false;
        }
    }

    private static void write(InputStream in, Path target, Manifest.Entry entry, Progress progress) throws IOException {
        MessageDigest digest = Hashing.sha256();
        long written = 0;
        try (FileChannel out = FileChannel.open(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                written += read;
                if (written > entry.size()) {
                    throw new UpdateException(entry.path() + " is larger than its manifest entry");
                }
                digest.update(buffer, 0, read);
                ByteBuffer chunk = ByteBuffer.wrap(buffer, 0, read);
                while (chunk.hasRemaining()) {
                    out.write(chunk);
                }
                if (progress != null) {
                    progress.add(read);
                }
            }
            out.force(true);
        }
        if (written != entry.size()) {
            throw new UpdateException(entry.path() + " is smaller than its manifest entry");
        }
        if (!Hashing.hex(digest).equals(entry.sha256())) {
            throw new UpdateException(entry.path() + " does not match its sha256");
        }
    }

    private String newestInstalled(Store.State state) {
        String newest = runningVersion;
        List<String> candidates = new ArrayList<>(state.installed());
        if (baseline != null) {
            Release.peek(baseline).map(Manifest::version).ifPresent(candidates::add);
        }
        for (String version : candidates) {
            if (Versions.isNewer(version, newest)) {
                newest = version;
            }
        }
        return newest;
    }

    private static final class Progress {
        private final ProgressListener listener;
        private long done;
        private long total;

        Progress(ProgressListener listener) {
            this.listener = Objects.requireNonNull(listener);
        }

        void add(long bytes) {
            done += bytes;
            listener.progress(done, total);
        }
    }

    public static final class Builder {
        private final Path root;
        private final List<PublicKey> trustedKeys = new ArrayList<>();
        private String runtime;
        private Path baseline;
        private String runningVersion;

        private Builder(Path root) {
            this.root = Objects.requireNonNull(root);
        }

        /** A key allowed to sign releases. Give several while rotating keys. */
        public Builder trust(PublicKey key) {
            trustedKeys.add(Objects.requireNonNull(key));
            return this;
        }

        /** Releases built for another runtime are not downloaded. Null accepts any. */
        public Builder runtime(String runtime) {
            this.runtime = runtime;
            return this;
        }

        /** Release shipped with the installer; its files are reused and its version counts as installed. */
        public Builder baseline(Path baseline) {
            this.baseline = baseline;
            return this;
        }

        public Builder runningVersion(String version) {
            this.runningVersion = version == null ? null : Versions.requireValid(version);
            return this;
        }

        public Updater build() {
            return new Updater(this);
        }
    }
}
