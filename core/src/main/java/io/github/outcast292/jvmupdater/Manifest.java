package io.github.outcast292.jvmupdater;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Describes one release: its version, how to start it and every file it contains.
 *
 * <p>The text form is what gets signed, so it is strict: UTF-8, one {@code key=value} per line,
 * {@code format=1} first, unknown keys refused. A launcher that doesn't know a newer format
 * refuses the release instead of starting it the wrong way.
 *
 * <pre>
 * format=1
 * version=1.4.0
 * runtime=linux-x64-25
 * main-module=com.example.app/com.example.app.Main
 * jvm-arg=-Xmx512m
 * file=&lt;sha256&gt; &lt;size&gt; lib/app.jar
 * </pre>
 */
public final class Manifest {

    public static final int FORMAT = 1;

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern PATH_SEGMENT = Pattern.compile("[A-Za-z0-9._+-]+");
    private static final Pattern JAVA_NAME = Pattern.compile("[\\p{L}_$][\\p{L}\\p{N}_$]*(\\.[\\p{L}_$][\\p{L}\\p{N}_$]*)*");

    /** A file of the release, relative to its {@code files/} directory, always with {@code /}. */
    public record Entry(String path, String sha256, long size) {
        public Entry {
            checkPath(path);
            if (!SHA256.matcher(sha256).matches()) {
                throw new IllegalArgumentException("Invalid sha256 for " + path + ": " + sha256);
            }
            if (size < 0) {
                throw new IllegalArgumentException("Negative size for " + path);
            }
        }

        public boolean isJar() {
            return path.endsWith(".jar");
        }
    }

    private final String version;
    private final String runtime;
    private final String mainModule;
    private final String mainClass;
    private final List<String> jvmArgs;
    private final List<Entry> files;

    private Manifest(Builder builder) {
        this.version = Versions.requireValid(builder.version);
        this.runtime = builder.runtime;
        this.mainModule = builder.mainModule;
        this.mainClass = builder.mainClass;
        this.jvmArgs = List.copyOf(builder.jvmArgs);
        this.files = List.copyOf(builder.files);

        if ((mainModule == null) == (mainClass == null)) {
            throw new IllegalArgumentException("Exactly one of main-module and main-class is required");
        }
        if (mainModule != null) {
            String[] parts = mainModule.split("/", -1);
            if (parts.length != 2 || !JAVA_NAME.matcher(parts[0]).matches() || !JAVA_NAME.matcher(parts[1]).matches()) {
                throw new IllegalArgumentException("main-module must look like module/main.Class: " + mainModule);
            }
        }
        if (mainClass != null && !JAVA_NAME.matcher(mainClass).matches()) {
            throw new IllegalArgumentException("Invalid main-class: " + mainClass);
        }
        if (runtime != null && !PATH_SEGMENT.matcher(runtime).matches()) {
            throw new IllegalArgumentException("Invalid runtime: " + runtime);
        }
        for (String arg : jvmArgs) {
            if (arg.isEmpty() || arg.indexOf('\n') >= 0 || arg.indexOf('\r') >= 0) {
                throw new IllegalArgumentException("Invalid jvm-arg: " + arg);
            }
        }
        Set<String> seen = new HashSet<>();
        for (Entry entry : files) {
            // Lower case so two files can't collide on a case-insensitive file system.
            if (!seen.add(entry.path().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate file: " + entry.path());
            }
        }
    }

    public static Builder builder(String version) {
        return new Builder(version);
    }

    public static Manifest parse(byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Manifest is not valid UTF-8", e);
        }

        Builder builder = null;
        boolean formatSeen = false;
        Set<String> singleKeys = new HashSet<>();
        int lineNumber = 0;
        for (String rawLine : text.split("\n", -1)) {
            lineNumber++;
            String line = rawLine.endsWith("\r") ? rawLine.substring(0, rawLine.length() - 1) : rawLine;
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("Line " + lineNumber + " is not key=value");
            }
            String key = line.substring(0, eq);
            String value = line.substring(eq + 1);

            if (!formatSeen) {
                if (!key.equals("format")) {
                    throw new IllegalArgumentException("Manifest must start with format=" + FORMAT);
                }
                if (!value.equals(String.valueOf(FORMAT))) {
                    throw new IllegalArgumentException("Unsupported manifest format " + value
                            + "; this version of jvm-updater reads format " + FORMAT);
                }
                formatSeen = true;
                continue;
            }
            if (!key.equals("jvm-arg") && !key.equals("file") && !singleKeys.add(key)) {
                throw new IllegalArgumentException("Duplicate key on line " + lineNumber + ": " + key);
            }
            if (key.equals("version")) {
                if (builder != null) {
                    throw new IllegalArgumentException("version must come right after format");
                }
                builder = new Builder(value);
                continue;
            }
            if (builder == null) {
                throw new IllegalArgumentException("version must come right after format");
            }
            switch (key) {
                case "runtime" -> builder.runtime(value);
                case "main-module" -> builder.mainModule(value);
                case "main-class" -> builder.mainClass(value);
                case "jvm-arg" -> builder.jvmArg(value);
                case "file" -> {
                    String[] parts = value.split(" ", 3);
                    if (parts.length != 3) {
                        throw new IllegalArgumentException("Line " + lineNumber + ": file=<sha256> <size> <path>");
                    }
                    long size;
                    try {
                        size = Long.parseLong(parts[1]);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("Line " + lineNumber + ": invalid size " + parts[1]);
                    }
                    builder.file(parts[2], parts[0], size);
                }
                default -> throw new IllegalArgumentException("Unknown key on line " + lineNumber + ": " + key);
            }
        }
        if (builder == null) {
            throw new IllegalArgumentException("Manifest has no version");
        }
        return builder.build();
    }

    public byte[] toBytes() {
        StringBuilder out = new StringBuilder();
        out.append("format=").append(FORMAT).append('\n');
        out.append("version=").append(version).append('\n');
        if (runtime != null) {
            out.append("runtime=").append(runtime).append('\n');
        }
        if (mainModule != null) {
            out.append("main-module=").append(mainModule).append('\n');
        } else {
            out.append("main-class=").append(mainClass).append('\n');
        }
        for (String arg : jvmArgs) {
            out.append("jvm-arg=").append(arg).append('\n');
        }
        for (Entry entry : files) {
            out.append("file=").append(entry.sha256()).append(' ').append(entry.size())
                    .append(' ').append(entry.path()).append('\n');
        }
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    public String version() {
        return version;
    }

    /** Runtime the release was built for, such as {@code linux-x64-25}. Empty means any. */
    public Optional<String> runtime() {
        return Optional.ofNullable(runtime);
    }

    /** {@code module/main.Class} when the release runs on the module path. */
    public Optional<String> mainModule() {
        return Optional.ofNullable(mainModule);
    }

    /** Main class when the release runs on the class path. */
    public Optional<String> mainClass() {
        return Optional.ofNullable(mainClass);
    }

    public List<String> jvmArgs() {
        return jvmArgs;
    }

    public List<Entry> files() {
        return files;
    }

    /** True when a launcher started for {@code launcherRuntime} may run this release. */
    public boolean runsOn(String launcherRuntime) {
        return runtime == null || launcherRuntime == null || runtime.equals(launcherRuntime);
    }

    static void checkPath(String path) {
        if (path == null || path.isEmpty() || path.length() > 512) {
            throw new IllegalArgumentException("Invalid file path: " + path);
        }
        for (String segment : path.split("/", -1)) {
            if (!PATH_SEGMENT.matcher(segment).matches() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("Invalid file path: " + path);
            }
        }
    }

    public static final class Builder {
        private final String version;
        private String runtime;
        private String mainModule;
        private String mainClass;
        private final List<String> jvmArgs = new ArrayList<>();
        private final List<Entry> files = new ArrayList<>();

        private Builder(String version) {
            this.version = version;
        }

        public Builder runtime(String runtime) {
            this.runtime = runtime;
            return this;
        }

        /** Runs the release on the module path, with {@code java -m module/main.Class}. */
        public Builder mainModule(String moduleAndClass) {
            this.mainModule = moduleAndClass;
            return this;
        }

        /** Runs the release on the class path, with its jars in manifest order. */
        public Builder mainClass(String mainClass) {
            this.mainClass = mainClass;
            return this;
        }

        public Builder jvmArg(String arg) {
            jvmArgs.add(arg);
            return this;
        }

        public Builder jvmArgs(List<String> args) {
            jvmArgs.addAll(args);
            return this;
        }

        public Builder file(String path, String sha256, long size) {
            files.add(new Entry(path, sha256, size));
            return this;
        }

        public Manifest build() {
            return new Manifest(this);
        }
    }
}
