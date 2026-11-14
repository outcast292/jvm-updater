module io.github.outcast292.jvmupdater {
    requires java.net.http;

    exports io.github.outcast292.jvmupdater;
    exports io.github.outcast292.jvmupdater.internal to io.github.outcast292.jvmupdater.launcher;
}
