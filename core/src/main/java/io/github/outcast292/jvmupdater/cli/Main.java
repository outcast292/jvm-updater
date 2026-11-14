package io.github.outcast292.jvmupdater.cli;

import io.github.outcast292.jvmupdater.Manifest;
import io.github.outcast292.jvmupdater.Publisher;
import io.github.outcast292.jvmupdater.Signatures;
import io.github.outcast292.jvmupdater.internal.Release;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyPair;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Command line for build pipelines: make keys, publish releases, check a release directory. */
public final class Main {

    private static final String USAGE = """
            Usage:
              jvm-updater keygen <name>
                  Writes <name>.key (private, keep it secret) and <name>.pub.

              jvm-updater publish --files <dir> --version <version> --feed <dir> --key <file|env:VAR>
                                  (--main-module <module/main.Class> | --main-class <main.Class>)
                                  [--runtime <id>] [--jvm-arg <arg>]... [--no-latest]
                  Signs the files as a release in the feed folder and points 'latest' at it.
                  Upload the feed folder afterwards (aws s3 sync, rclone, ...).

              jvm-updater verify <release-dir> --public-key <file|base64> [--runtime <id>]
                  Checks the signature and every file of a release directory.
            """;

    private static final Set<String> FLAGS = Set.of("--no-latest");

    private final PrintStream out;
    private final PrintStream err;

    Main(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        System.exit(new Main(System.out, System.err).run(args));
    }

    int run(String[] args) {
        if (args.length == 0 || args[0].equals("--help") || args[0].equals("-h")) {
            out.print(USAGE);
            return args.length == 0 ? 2 : 0;
        }
        try {
            Options options = Options.parse(args);
            return switch (args[0]) {
                case "keygen" -> keygen(options);
                case "publish" -> publish(options);
                case "verify" -> verify(options);
                default -> throw new UsageException("Unknown command: " + args[0]);
            };
        } catch (UsageException e) {
            err.println(e.getMessage());
            err.print(USAGE);
            return 2;
        } catch (IOException | IllegalArgumentException e) {
            err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private int keygen(Options options) throws IOException {
        String name = options.single();
        Path privateFile = Path.of(name + ".key");
        Path publicFile = Path.of(name + ".pub");
        if (Files.exists(privateFile) || Files.exists(publicFile)) {
            throw new IOException(privateFile + " or " + publicFile + " already exists");
        }
        KeyPair keys = Signatures.generateKeyPair();
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(privateFile, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        }
        Files.writeString(privateFile, Signatures.toPem(keys.getPrivate()), StandardCharsets.US_ASCII);
        Files.writeString(publicFile, Signatures.toPem(keys.getPublic()), StandardCharsets.US_ASCII);
        out.println("Private key: " + privateFile + " (keep it out of version control)");
        out.println("Public key:  " + publicFile);
        out.println("Public key for --public-key: " + Signatures.toBase64(keys.getPublic()));
        return 0;
    }

    private int publish(Options options) throws IOException {
        Manifest.Builder manifest = Manifest.builder(options.required("--version"));
        String mainModule = options.optional("--main-module");
        String mainClass = options.optional("--main-class");
        if ((mainModule == null) == (mainClass == null)) {
            throw new UsageException("Give exactly one of --main-module and --main-class");
        }
        if (mainModule != null) {
            manifest.mainModule(mainModule);
        } else {
            manifest.mainClass(mainClass);
        }
        manifest.runtime(options.optional("--runtime"));
        manifest.jvmArgs(options.all("--jvm-arg"));

        Path feed = Path.of(options.required("--feed"));
        Files.createDirectories(feed);
        Path release = Publisher.publish(Path.of(options.required("--files")), manifest,
                Signatures.readPrivateKey(readKey(options.required("--key"))), feed);
        String version = release.getFileName().toString();
        if (!options.flag("--no-latest")) {
            Publisher.setLatest(feed, version);
        }
        out.println("Published " + version + " in " + release);
        return 0;
    }

    private int verify(Options options) throws IOException {
        Path dir = Path.of(options.single());
        String keyText = options.required("--public-key");
        PublicKey key = Signatures.readPublicKey(Files.isRegularFile(Path.of(keyText)) ? Files.readString(Path.of(keyText)) : keyText);
        Release release = Release.verify(dir, List.of(key), options.optional("--runtime"));
        out.println("OK: " + release.version() + ", " + release.manifest().files().size() + " files");
        return 0;
    }

    private static String readKey(String source) throws IOException {
        if (source.startsWith("env:")) {
            String value = System.getenv(source.substring(4));
            if (value == null || value.isBlank()) {
                throw new IOException("Environment variable " + source.substring(4) + " is empty");
            }
            return value;
        }
        return Files.readString(Path.of(source), StandardCharsets.US_ASCII);
    }

    static final class UsageException extends IllegalArgumentException {
        UsageException(String message) {
            super(message);
        }
    }

    /** {@code --name value}, {@code --name=value}, flags and positional arguments after the command. */
    record Options(Map<String, List<String>> named, List<String> positional) {

        static Options parse(String[] args) {
            Map<String, List<String>> named = new HashMap<>();
            List<String> positional = new ArrayList<>();
            for (int i = 1; i < args.length; i++) {
                String arg = args[i];
                if (!arg.startsWith("--")) {
                    positional.add(arg);
                    continue;
                }
                String name = arg;
                String value;
                int eq = arg.indexOf('=');
                if (eq > 0) {
                    name = arg.substring(0, eq);
                    value = arg.substring(eq + 1);
                } else if (FLAGS.contains(arg)) {
                    value = "true";
                } else if (i + 1 < args.length) {
                    // Taken as is, so JVM arguments like --enable-native-access=... work as values.
                    value = args[++i];
                } else {
                    throw new UsageException(arg + " needs a value");
                }
                named.computeIfAbsent(name, key -> new ArrayList<>()).add(value);
            }
            return new Options(named, positional);
        }

        String required(String name) {
            String value = optional(name);
            if (value == null) {
                throw new UsageException("Missing " + name);
            }
            return value;
        }

        String optional(String name) {
            List<String> values = named.get(name);
            if (values == null) {
                return null;
            }
            if (values.size() > 1) {
                throw new UsageException(name + " can only be given once");
            }
            return values.getFirst();
        }

        List<String> all(String name) {
            return named.getOrDefault(name, List.of());
        }

        boolean flag(String name) {
            return named.containsKey(name);
        }

        String single() {
            if (positional.size() != 1) {
                throw new UsageException("Expected one argument, got " + positional.size());
            }
            return positional.getFirst();
        }
    }
}
