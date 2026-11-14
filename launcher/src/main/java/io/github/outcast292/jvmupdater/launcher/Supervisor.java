package io.github.outcast292.jvmupdater.launcher;

import io.github.outcast292.jvmupdater.HttpFeed;
import io.github.outcast292.jvmupdater.Manifest;
import io.github.outcast292.jvmupdater.Signatures;
import io.github.outcast292.jvmupdater.Updater;
import io.github.outcast292.jvmupdater.Versions;
import io.github.outcast292.jvmupdater.internal.Release;
import io.github.outcast292.jvmupdater.internal.Store;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;
import java.util.stream.Collectors;

/**
 * Starts the newest valid release in a child JVM and watches it.
 *
 * <ul>
 *   <li>Exit code 0: the launcher stops too.</li>
 *   <li>Exit code {@link Updater#RESTART_EXIT_CODE}: the launcher starts again, on the pending
 *       release if the app downloaded one.</li>
 *   <li>Any other exit before {@code --prove-after} on a release that never ran that long: the
 *       release is marked bad and the previous one is started instead.</li>
 * </ul>
 *
 * Every release is checked (signature, runtime, each file's hash) before each start.
 */
public final class Supervisor {

    private static final Logger LOG = Logger.getLogger("jvmupdater");
    private static final int MAX_RESTARTS_PER_MINUTE = 5;

    private final Options options;
    private final Store store;
    private final AtomicReference<Process> child = new AtomicReference<>();
    private final Deque<Long> restarts = new ArrayDeque<>();

    public Supervisor(Options options) {
        this.options = options;
        this.store = new Store(options.root());
    }

    public static void main(String[] args) {
        Options options;
        try {
            options = Options.parse(args);
        } catch (RuntimeException e) {
            System.err.println(e.getMessage());
            System.err.print(Options.USAGE);
            System.exit(2);
            return;
        }
        logToFile(options.root().resolve("logs"));
        Supervisor supervisor = new Supervisor(options);
        Runtime.getRuntime().addShutdownHook(new Thread(supervisor::stopChild, "jvmupdater-shutdown"));
        System.exit(supervisor.run());
    }

    /** Runs releases until one exits for good; returns the exit code to use. */
    public int run() {
        checkFeed();
        while (true) {
            Optional<Candidate> candidate = chooseRelease();
            if (candidate.isEmpty()) {
                LOG.severe("No valid release to start, not even the baseline in " + options.baseline());
                return 1;
            }
            Outcome outcome;
            try {
                outcome = runAndWatch(candidate.get());
            } catch (IOException e) {
                LOG.log(Level.SEVERE, "Could not start " + candidate.get().release().version(), e);
                return 1;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return 1;
            }
            if (outcome.rolledBack()) {
                continue;
            }
            if (outcome.code() != Updater.RESTART_EXIT_CODE) {
                return outcome.code();
            }
            if (tooManyRestarts()) {
                LOG.severe("The app asked to restart more than " + MAX_RESTARTS_PER_MINUTE + " times in a minute; stopping");
                return 1;
            }
        }
    }

    private record Candidate(Release release, boolean baseline) {
    }

    private record Outcome(int code, boolean rolledBack) {
        static Outcome exited(int code) {
            return new Outcome(code, false);
        }

        static Outcome rolledBack(int code) {
            return new Outcome(code, true);
        }
    }

    private Optional<Candidate> chooseRelease() {
        Store.State state;
        try {
            state = store.read();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Cannot read " + store.root() + ", using the baseline", e);
            state = Store.State.EMPTY;
        }

        record Choice(String version, Path dir, boolean baseline) {
        }
        List<Choice> choices = new ArrayList<>();
        for (String version : state.installed()) {
            if (!state.bad().contains(version)) {
                choices.add(new Choice(version, store.versionDir(version), false));
            }
        }
        Path baseline = options.baseline();
        Release.peek(baseline).ifPresent(manifest -> choices.add(new Choice(manifest.version(), baseline, true)));
        // Newest first; the installed copy before the baseline when both have the same version.
        Comparator<Choice> newestFirst = Comparator.comparing(Choice::version, Versions::compare).reversed();
        choices.sort(newestFirst.thenComparing(Choice::baseline));

        for (Choice choice : choices) {
            try {
                Release release = Release.verify(choice.dir(), options.trustedKeys(), options.runtime());
                if (!release.version().equals(choice.version())) {
                    throw new IOException(choice.dir() + " holds " + release.version() + ", not " + choice.version());
                }
                return Optional.of(new Candidate(release, choice.baseline()));
            } catch (IOException e) {
                LOG.warning("Skipping " + choice.version() + ": " + e.getMessage());
                if (!choice.baseline()) {
                    markBad(choice.version());
                }
            }
        }
        if (choices.stream().noneMatch(Choice::baseline)) {
            LOG.severe("Cannot read the baseline manifest in " + baseline);
        }
        return Optional.empty();
    }

    private Outcome runAndWatch(Candidate candidate) throws IOException, InterruptedException {
        Release release = candidate.release();
        boolean trusted = candidate.baseline() || release.version().equals(store.read().current());
        LOG.info("Starting " + release.version() + (candidate.baseline() ? " (baseline)" : ""));

        long started = System.nanoTime();
        Process process = new ProcessBuilder(command(release, candidate.baseline())).inheritIO().start();
        child.set(process);
        try {
            if (!process.waitFor(options.proveAfter().toMillis(), TimeUnit.MILLISECONDS)) {
                if (!trusted) {
                    keep(release.version());
                }
                return Outcome.exited(process.waitFor());
            }
            int code = process.exitValue();
            if (code == 0 || code == Updater.RESTART_EXIT_CODE) {
                if (!trusted) {
                    keep(release.version());
                }
                return Outcome.exited(code);
            }
            if (trusted) {
                LOG.warning(release.version() + " exited with code " + code);
                return Outcome.exited(code);
            }
            Duration ran = Duration.ofNanos(System.nanoTime() - started);
            LOG.warning(release.version() + " exited with code " + code + " after " + ran.toMillis()
                    + " ms; marking it bad and starting the previous release");
            markBad(release.version());
            return Outcome.rolledBack(code);
        } finally {
            child.set(null);
        }
    }

    private List<String> command(Release release, boolean baseline) {
        Manifest manifest = release.manifest();
        List<String> command = new ArrayList<>();
        command.add(javaExecutable());
        command.addAll(manifest.jvmArgs());
        command.add("-D" + Updater.PROPERTY_ROOT + "=" + store.root());
        command.add("-D" + Updater.PROPERTY_VERSION + "=" + release.version());
        command.add("-D" + Updater.PROPERTY_APP_DIR + "=" + release.filesDir());
        command.add("-D" + Updater.PROPERTY_BASELINE + "=" + options.baseline().toAbsolutePath());
        command.add("-D" + Updater.PROPERTY_KEYS + "=" + options.trustedKeys().stream()
                .map(Signatures::toBase64).collect(Collectors.joining(",")));
        if (options.runtime() != null) {
            command.add("-D" + Updater.PROPERTY_RUNTIME + "=" + options.runtime());
        }
        String path = release.jars().stream().map(Path::toString).collect(Collectors.joining(File.pathSeparator));
        if (manifest.mainModule().isPresent()) {
            command.add("--module-path");
            command.add(path);
            command.add("--module");
            command.add(manifest.mainModule().get());
        } else {
            command.add("--class-path");
            command.add(path);
            command.add(manifest.mainClass().orElseThrow());
        }
        command.addAll(options.appArgs());
        return command;
    }

    // javaw on Windows: a GUI app started from a launcher without console must not open one.
    private static String javaExecutable() {
        Path bin = Path.of(System.getProperty("java.home"), "bin");
        if (File.separatorChar == '\\') {
            Path javaw = bin.resolve("javaw.exe");
            return (Files.exists(javaw) ? javaw : bin.resolve("java.exe")).toString();
        }
        return bin.resolve("java").toString();
    }

    private void checkFeed() {
        if (options.feed() == null) {
            return;
        }
        Updater.Builder builder = Updater.builder(options.root())
                .runtime(options.runtime())
                .baseline(options.baseline());
        options.trustedKeys().forEach(builder::trust);
        Updater updater = builder.build();
        HttpFeed feed = new HttpFeed(options.feed(), options.feedHeaders(), options.checkTimeout());

        // A daemon thread: past the timeout the app starts and the download goes on next to it,
        // to be used at the following start.
        Thread check = Thread.ofPlatform().daemon().name("jvmupdater-check").start(() -> {
            try {
                Updater.Result result = updater.checkAndStage(feed);
                LOG.info("Update check: " + result.status() + " (" + result.detail() + ")");
            } catch (IOException | RuntimeException e) {
                LOG.warning("Update check failed: " + e);
            }
        });
        try {
            if (!check.join(options.checkTimeout())) {
                LOG.info("Update check takes longer than " + options.checkTimeout().toMillis()
                        + " ms; starting the installed release meanwhile");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void keep(String version) {
        try {
            store.update(state -> state.promote(version));
            store.prune();
            LOG.info(version + " is now the current release");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not record " + version + " as current", e);
        }
    }

    private void markBad(String version) {
        try {
            store.update(state -> state.markBad(version));
            store.prune();
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Could not mark " + version + " as bad", e);
        }
    }

    private boolean tooManyRestarts() {
        long now = System.nanoTime();
        restarts.addLast(now);
        while (now - restarts.getFirst() > TimeUnit.MINUTES.toNanos(1)) {
            restarts.removeFirst();
        }
        return restarts.size() > MAX_RESTARTS_PER_MINUTE;
    }

    private void stopChild() {
        Process process = child.get();
        if (process == null || !process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
        }
    }

    private static void logToFile(Path dir) {
        System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT %4$s %5$s%6$s%n");
        try {
            Files.createDirectories(dir);
            FileHandler handler = new FileHandler(dir.resolve("launcher.%g.log").toString(), 1 << 20, 2, true);
            handler.setFormatter(new SimpleFormatter());
            LOG.addHandler(handler);
        } catch (IOException e) {
            LOG.warning("Cannot write logs to " + dir + ": " + e.getMessage());
        }
    }
}
