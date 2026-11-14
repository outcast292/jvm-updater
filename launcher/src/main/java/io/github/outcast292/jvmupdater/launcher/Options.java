package io.github.outcast292.jvmupdater.launcher;

import io.github.outcast292.jvmupdater.Signatures;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Launcher settings, from the command line (for jpackage, {@code --arguments}).
 *
 * @param root         where releases are installed; {@code ~} is the user's home
 * @param baseline     release shipped with the installer, used when nothing better is installed
 * @param trustedKeys  keys allowed to sign releases
 * @param runtime      this runtime's id; releases built for another one are refused
 * @param proveAfter   a new release that runs this long without crashing is kept
 * @param feed         when set, checked before each start (zero-code mode)
 * @param feedHeaders  extra HTTP headers for the feed, such as a token
 * @param checkTimeout how long a start may wait for the feed
 * @param appArgs      passed to the app, after {@code --}
 */
public record Options(Path root, Path baseline, List<PublicKey> trustedKeys, String runtime, Duration proveAfter,
                      URI feed, Map<String, String> feedHeaders, Duration checkTimeout, List<String> appArgs) {

    static final String USAGE = """
            Usage: jvm-updater-launcher --root <dir> --baseline <release-dir> --public-key <key>...
                                        [--runtime <id>] [--prove-after <120s>]
                                        [--feed <url> [--feed-header 'Name: value']... [--check-timeout <10s>]]
                                        [-- <app arguments>]
            A key is the base64 printed by 'jvm-updater keygen' or the path of a .pub file.
            Durations accept ms, s and m: 500ms, 30s, 2m.
            """;

    public Options {
        trustedKeys = List.copyOf(trustedKeys);
        feedHeaders = Map.copyOf(feedHeaders);
        appArgs = List.copyOf(appArgs);
    }

    public static Options parse(String[] args) {
        Path root = null;
        Path baseline = null;
        List<PublicKey> keys = new ArrayList<>();
        String runtime = null;
        Duration proveAfter = Duration.ofSeconds(120);
        URI feed = null;
        Map<String, String> headers = new LinkedHashMap<>();
        Duration checkTimeout = Duration.ofSeconds(10);
        List<String> appArgs = new ArrayList<>();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.equals("--")) {
                appArgs.addAll(List.of(args).subList(i + 1, args.length));
                break;
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException(arg + " needs a value");
            }
            String value = args[++i];
            switch (arg) {
                case "--root" -> root = expandHome(value);
                case "--baseline" -> baseline = expandHome(value);
                case "--public-key" -> keys.add(readKey(value));
                case "--runtime" -> runtime = value;
                case "--prove-after" -> proveAfter = duration(value);
                case "--feed" -> feed = URI.create(value);
                case "--feed-header" -> {
                    int colon = value.indexOf(':');
                    if (colon <= 0) {
                        throw new IllegalArgumentException("--feed-header must look like 'Name: value'");
                    }
                    headers.put(value.substring(0, colon).strip(), value.substring(colon + 1).strip());
                }
                case "--check-timeout" -> checkTimeout = duration(value);
                default -> throw new IllegalArgumentException("Unknown option " + arg);
            }
        }
        if (root == null || baseline == null || keys.isEmpty()) {
            throw new IllegalArgumentException("--root, --baseline and at least one --public-key are required");
        }
        return new Options(root, baseline, keys, runtime, proveAfter, feed, headers, checkTimeout, appArgs);
    }

    static Duration duration(String text) {
        try {
            if (text.endsWith("ms")) {
                return Duration.ofMillis(Long.parseLong(text.substring(0, text.length() - 2)));
            }
            if (text.endsWith("s")) {
                return Duration.ofSeconds(Long.parseLong(text.substring(0, text.length() - 1)));
            }
            if (text.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(text.substring(0, text.length() - 1)));
            }
        } catch (NumberFormatException e) {
            // falls through to the error below
        }
        throw new IllegalArgumentException("Invalid duration: " + text + " (use 500ms, 30s or 2m)");
    }

    private static Path expandHome(String path) {
        if (path.equals("~") || path.startsWith("~/") || path.startsWith("~\\")) {
            return Path.of(System.getProperty("user.home") + path.substring(1));
        }
        return Path.of(path);
    }

    private static PublicKey readKey(String value) {
        Path file = expandHome(value);
        if (value.endsWith(".pub") || value.endsWith(".pem")) {
            try {
                return Signatures.readPublicKey(Files.readString(file));
            } catch (IOException e) {
                throw new UncheckedIOException("Cannot read public key " + file, e);
            }
        }
        return Signatures.readPublicKey(value);
    }
}
