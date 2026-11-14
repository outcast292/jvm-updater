package io.github.outcast292.jvmupdater.launcher;

import com.sun.net.httpserver.HttpServer;
import io.github.outcast292.jvmupdater.DirectoryFeed;
import io.github.outcast292.jvmupdater.Signatures;
import io.github.outcast292.jvmupdater.Updater;
import io.github.outcast292.jvmupdater.internal.Store;
import io.github.outcast292.jvmupdater.testing.TestReleases;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupervisorTest {

    @TempDir
    Path dir;

    private TestReleases releases;
    private Path root;
    private Path marker;
    private Path baseline;
    private byte[] appJar;

    @BeforeEach
    void setUp() throws IOException {
        releases = new TestReleases(dir);
        root = dir.resolve("installed");
        marker = dir.resolve("ran.txt");
        appJar = fixtureJar();
        baseline = release("1.0.0");
    }

    private static byte[] fixtureJar() throws IOException {
        String name = FixtureApp.class.getName().replace('.', '/') + ".class";
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes);
             InputStream in = FixtureApp.class.getClassLoader().getResourceAsStream(name)) {
            jar.putNextEntry(new JarEntry(name));
            in.transferTo(jar);
        }
        return bytes.toByteArray();
    }

    private Path release(String version, String... jvmArgs) {
        List<String> args = new ArrayList<>(List.of(jvmArgs));
        args.add("-Dfixture.marker=" + marker);
        return releases.publish(version, Map.of("lib/app.jar", appJar),
                builder -> builder.mainClass(FixtureApp.class.getName()).jvmArgs(args));
    }

    private void stage(String version) throws IOException {
        releases.setLatest(version);
        Updater updater = Updater.builder(root).trust(releases.keys().getPublic()).baseline(baseline).build();
        assertEquals(Updater.Status.STAGED, updater.checkAndStage(new DirectoryFeed(releases.feed())).status());
    }

    private Options options(Duration proveAfter, URI feed, Duration checkTimeout, String... appArgs) {
        return new Options(root, baseline, List.of(releases.keys().getPublic()), null, proveAfter,
                feed, Map.of(), checkTimeout, List.of(appArgs));
    }

    private int supervise() {
        return new Supervisor(options(Duration.ofSeconds(30), null, Duration.ofSeconds(5))).run();
    }

    private List<String> ran() throws IOException {
        return Files.exists(marker) ? Files.readAllLines(marker) : List.of();
    }

    private Store.State state() throws IOException {
        return new Store(root).read();
    }

    @Test
    void startsTheBaselineWhenNothingIsInstalled() throws IOException {
        assertEquals(0, new Supervisor(options(Duration.ofSeconds(30), null, Duration.ofSeconds(5), "--open", "file.txt")).run());
        assertEquals(List.of("1.0.0 --open file.txt"), ran());
    }

    @Test
    void startsThePendingReleaseAndKeepsIt() throws IOException {
        release("1.1.0");
        stage("1.1.0");

        assertEquals(0, supervise());

        assertEquals(List.of("1.1.0"), ran());
        assertEquals("1.1.0", state().current());
        assertNull(state().pending());
    }

    @Test
    void rollsBackWhenANewReleaseCrashesAtStart() throws IOException {
        release("1.1.0", "-Dfixture.exit=3");
        stage("1.1.0");

        assertEquals(0, supervise());

        assertEquals(List.of("1.1.0", "1.0.0"), ran());
        assertTrue(state().bad().contains("1.1.0"));
        assertFalse(Files.exists(new Store(root).versionDir("1.1.0")));
    }

    @Test
    void aReleaseThatRanLongEnoughIsNotRolledBack() throws IOException {
        release("1.1.0", "-Dfixture.exit=3");
        stage("1.1.0");
        new Store(root).update(state -> state.promote("1.1.0"));

        assertEquals(3, supervise());

        assertEquals(List.of("1.1.0"), ran());
        assertFalse(state().bad().contains("1.1.0"));
    }

    @Test
    void keepsAReleaseOnceItOutlivesProveAfter() throws IOException {
        release("1.1.0", "-Dfixture.sleep=1500");
        stage("1.1.0");

        assertEquals(0, new Supervisor(options(Duration.ofMillis(300), null, Duration.ofSeconds(5))).run());

        assertEquals("1.1.0", state().current());
    }

    @Test
    void restartsWhenTheAppAsksForIt() throws IOException {
        Path flag = Files.createFile(dir.resolve("restart-once"));
        release("1.1.0", "-Dfixture.restartOnce=" + flag);
        stage("1.1.0");

        assertEquals(0, supervise());

        assertEquals(List.of("1.1.0", "1.1.0"), ran());
    }

    @Test
    void skipsAnInstalledReleaseThatWasTamperedWith() throws IOException {
        release("1.1.0");
        stage("1.1.0");
        Files.write(new Store(root).versionDir("1.1.0").resolve("files/lib/app.jar"), new byte[]{0});

        assertEquals(0, supervise());

        assertEquals(List.of("1.0.0"), ran());
        assertTrue(state().bad().contains("1.1.0"));
    }

    @Test
    void refusesToStartWithoutAValidRelease() {
        Options strangers = new Options(root, baseline, List.of(Signatures.generateKeyPair().getPublic()), null,
                Duration.ofSeconds(30), null, Map.of(), Duration.ofSeconds(5), List.of());

        assertEquals(1, new Supervisor(strangers).run());
    }

    @Test
    void zeroCodeModeInstallsFromTheFeedBeforeStarting() throws IOException {
        release("1.1.0");
        releases.setLatest("1.1.0");
        HttpServer server = serve(releases.feed(), 0);
        try {
            URI feed = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            assertEquals(0, new Supervisor(options(Duration.ofSeconds(30), feed, Duration.ofSeconds(10))).run());
        } finally {
            server.stop(0);
        }

        assertEquals(List.of("1.1.0"), ran());
    }

    @Test
    void aSlowFeedDoesNotDelayTheStart() throws IOException {
        release("1.1.0");
        releases.setLatest("1.1.0");
        HttpServer server = serve(releases.feed(), 5000);
        try {
            URI feed = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            long started = System.nanoTime();
            assertEquals(0, new Supervisor(options(Duration.ofSeconds(30), feed, Duration.ofMillis(300))).run());
            assertTrue(Duration.ofNanos(System.nanoTime() - started).toMillis() < 4000);
        } finally {
            server.stop(0);
        }

        assertEquals(List.of("1.0.0"), ran());
    }

    @Test
    void anUnreachableFeedStartsTheInstalledRelease() throws IOException {
        URI nowhere = URI.create("http://127.0.0.1:9/");
        assertEquals(0, new Supervisor(options(Duration.ofSeconds(30), nowhere, Duration.ofSeconds(2))).run());
        assertEquals(List.of("1.0.0"), ran());
    }

    private static HttpServer serve(Path feed, long delayMs) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                Thread.sleep(delayMs);
                Path file = feed.resolve(exchange.getRequestURI().getPath().substring(1));
                if (!Files.isRegularFile(file)) {
                    exchange.sendResponseHeaders(404, -1);
                    return;
                }
                byte[] body = Files.readAllBytes(file);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        return server;
    }
}
