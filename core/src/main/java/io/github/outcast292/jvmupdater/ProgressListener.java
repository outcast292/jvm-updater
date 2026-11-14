package io.github.outcast292.jvmupdater;

/** Told how much of a release has been downloaded. Called on the downloading thread. */
@FunctionalInterface
public interface ProgressListener {

    ProgressListener NONE = (done, total) -> { };

    /**
     * @param done  bytes downloaded so far
     * @param total bytes to download in all; files reused from an installed release don't count
     */
    void progress(long done, long total);
}
