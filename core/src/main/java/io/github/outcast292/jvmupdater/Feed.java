package io.github.outcast292.jvmupdater;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;

/**
 * Where releases are downloaded from.
 *
 * <p>{@link HttpFeed} and {@link DirectoryFeed} read the static {@link Layout}. Implement this
 * interface when releases come from somewhere else, for example a backend that hands out
 * short-lived links to a private bucket. Nothing returned here is trusted: the manifest must be
 * signed by a trusted key and every file must match its hash, or the release is refused.
 */
public interface Feed {

    /** The version the feed currently offers, or empty when it offers none. */
    Optional<String> latestVersion() throws IOException;

    byte[] manifest(String version) throws IOException;

    byte[] signature(String version) throws IOException;

    /** Opens one file of a release. The caller closes the stream. */
    InputStream open(String version, Manifest.Entry file) throws IOException;
}
