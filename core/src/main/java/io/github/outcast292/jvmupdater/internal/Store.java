package io.github.outcast292.jvmupdater.internal;

import io.github.outcast292.jvmupdater.Versions;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * The releases installed on this machine and which one to run.
 *
 * <pre>
 * root/state                 current, previous, pending and bad versions
 * root/versions/1.4.0/       a complete release (manifest, manifest.sig, files/)
 * root/versions/1.5.0.part/  a release being downloaded
 * </pre>
 *
 * The app (staging updates) and the launcher (starting and promoting them) both write here, so
 * every change goes through a lock held in this JVM and on {@code root/.lock}.
 */
public final class Store {

    private static final ConcurrentMap<Path, ReentrantLock> JVM_LOCKS = new ConcurrentHashMap<>();
    private static final String PART_SUFFIX = ".part";

    /**
     * @param current  last version that ran long enough to be trusted
     * @param previous the one before it, kept to roll back to
     * @param pending  downloaded and waiting for the next start
     * @param bad      versions that failed and must not be installed again
     */
    public record State(String current, String previous, String pending, Set<String> bad) {
        public static final State EMPTY = new State(null, null, null, Set.of());

        public State {
            bad = Set.copyOf(bad);
        }

        public State withPending(String version) {
            return new State(current, previous, version, bad);
        }

        /** Makes {@code version} current, keeping the old current as the version to roll back to. */
        public State promote(String version) {
            if (version.equals(current)) {
                return this;
            }
            String newPending = pending != null && Versions.isNewer(pending, version) ? pending : null;
            return new State(version, current, newPending, bad);
        }

        public State markBad(String version) {
            Set<String> newBad = new TreeSet<>(bad);
            newBad.add(version);
            return new State(
                    version.equals(current) ? null : current,
                    version.equals(previous) ? null : previous,
                    version.equals(pending) ? null : pending,
                    newBad);
        }

        public Set<String> installed() {
            Set<String> versions = new LinkedHashSet<>();
            for (String version : new String[]{pending, current, previous}) {
                if (version != null) {
                    versions.add(version);
                }
            }
            return versions;
        }
    }

    @FunctionalInterface
    public interface IoAction<T> {
        T run() throws IOException;
    }

    private final Path root;

    public Store(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path root() {
        return root;
    }

    public Path versionDir(String version) {
        return root.resolve("versions").resolve(Versions.requireValid(version));
    }

    public Path stagingDir(String version) {
        return root.resolve("versions").resolve(Versions.requireValid(version) + PART_SUFFIX);
    }

    public State read() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(root.resolve("state"))) {
            properties.load(in);
        } catch (NoSuchFileException e) {
            return State.EMPTY;
        }
        Set<String> bad = Arrays.stream(properties.getProperty("bad", "").split(","))
                .map(String::strip)
                .filter(Versions::isValid)
                .collect(Collectors.toSet());
        return new State(version(properties, "current"), version(properties, "previous"),
                version(properties, "pending"), bad);
    }

    public State update(UnaryOperator<State> change) throws IOException {
        return locked(() -> {
            State state = change.apply(read());
            write(state);
            return state;
        });
    }

    /** Runs {@code action} while no other thread or process changes the store. */
    public <T> T locked(IoAction<T> action) throws IOException {
        Files.createDirectories(root);
        ReentrantLock jvmLock = JVM_LOCKS.computeIfAbsent(root, path -> new ReentrantLock());
        if (jvmLock.isHeldByCurrentThread()) {
            // Already inside locked(): the file lock is ours, and taking it twice would throw.
            return action.run();
        }
        jvmLock.lock();
        try (FileChannel channel = FileChannel.open(root.resolve(".lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            return action.run();
        } finally {
            jvmLock.unlock();
        }
    }

    /** Deletes the complete releases that the state no longer refers to. Downloads in progress stay. */
    public void prune() throws IOException {
        locked(() -> {
            Set<String> keep = read().installed();
            Path versions = root.resolve("versions");
            if (!Files.isDirectory(versions)) {
                return null;
            }
            Set<Path> unused = new HashSet<>();
            try (DirectoryStream<Path> dirs = Files.newDirectoryStream(versions)) {
                for (Path dir : dirs) {
                    String name = dir.getFileName().toString();
                    if (!name.endsWith(PART_SUFFIX) && Versions.isValid(name) && !keep.contains(name)) {
                        unused.add(dir);
                    }
                }
            }
            for (Path dir : unused) {
                try {
                    deleteRecursively(dir);
                } catch (IOException e) {
                    // Windows keeps files of a running JVM locked; the next prune gets them.
                }
            }
            return null;
        });
    }

    public static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null) {
                    throw e;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void write(State state) throws IOException {
        Properties properties = new Properties();
        put(properties, "current", state.current());
        put(properties, "previous", state.previous());
        put(properties, "pending", state.pending());
        if (!state.bad().isEmpty()) {
            properties.setProperty("bad", String.join(",", new TreeSet<>(state.bad())));
        }
        Path temp = Files.createTempFile(root, "state", ".tmp");
        try (OutputStream out = Files.newOutputStream(temp)) {
            properties.store(out, null);
        }
        Files.move(temp, root.resolve("state"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void put(Properties properties, String key, String value) {
        if (value != null) {
            properties.setProperty(key, value);
        }
    }

    private static String version(Properties properties, String key) {
        String value = properties.getProperty(key);
        return Versions.isValid(value) ? value : null;
    }
}
