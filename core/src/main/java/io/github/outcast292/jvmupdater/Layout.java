package io.github.outcast292.jvmupdater;

/**
 * Where things live in a published feed and in a release directory. Plain static files, so any
 * web server, bucket or folder can host a feed:
 *
 * <pre>
 * latest                        one line: the version to install
 * 1.4.0/manifest                signed description of the release
 * 1.4.0/manifest.sig            Ed25519 signature of that manifest
 * 1.4.0/files/lib/app.jar       the files listed in the manifest
 * </pre>
 *
 * A release directory on disk ({@code 1.4.0/} above) is also what a launcher runs from.
 */
public final class Layout {

    public static final String LATEST = "latest";
    public static final String MANIFEST = "manifest";
    public static final String SIGNATURE = "manifest.sig";
    public static final String FILES = "files";

    private Layout() {
    }

    public static String manifest(String version) {
        return Versions.requireValid(version) + "/" + MANIFEST;
    }

    public static String signature(String version) {
        return Versions.requireValid(version) + "/" + SIGNATURE;
    }

    public static String file(String version, Manifest.Entry entry) {
        return Versions.requireValid(version) + "/" + FILES + "/" + entry.path();
    }
}
