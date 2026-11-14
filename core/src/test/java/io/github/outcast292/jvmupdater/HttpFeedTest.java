package io.github.outcast292.jvmupdater;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HttpFeedTest {

    private HttpServer server;
    private final Map<String, byte[]> files = new ConcurrentHashMap<>();
    private final Map<String, String> lastHeaders = new ConcurrentHashMap<>();
    private final AtomicInteger failuresLeft = new AtomicInteger();
    private volatile long delayMs;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/feed/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.getRequestHeaders().forEach((name, values) -> lastHeaders.put(name.toLowerCase(), values.getFirst()));
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            if (failuresLeft.getAndDecrement() > 0) {
                exchange.sendResponseHeaders(503, -1);
                return;
            }
            byte[] body = files.get(exchange.getRequestURI().getPath().substring("/feed/".length()));
            if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private HttpFeed feed(Duration timeout) {
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/feed");
        return new HttpFeed(base, Map.of("Authorization", "Bearer secret"), timeout);
    }

    @Test
    void noLatestFileMeansNoRelease() throws IOException {
        assertEquals(Optional.empty(), feed(Duration.ofSeconds(5)).latestVersion());
    }

    @Test
    void readsTheWholeLayout() throws IOException {
        files.put("latest", "1.2.0\n".getBytes(StandardCharsets.UTF_8));
        files.put("1.2.0/manifest", "manifest bytes".getBytes(StandardCharsets.UTF_8));
        files.put("1.2.0/manifest.sig", "sig".getBytes(StandardCharsets.UTF_8));
        files.put("1.2.0/files/lib/app.jar", new byte[]{1, 2, 3});
        HttpFeed feed = feed(Duration.ofSeconds(5));

        assertEquals("1.2.0", feed.latestVersion().orElseThrow());
        assertArrayEquals("manifest bytes".getBytes(StandardCharsets.UTF_8), feed.manifest("1.2.0"));
        assertArrayEquals("sig".getBytes(StandardCharsets.UTF_8), feed.signature("1.2.0"));
        try (InputStream in = feed.open("1.2.0", new Manifest.Entry("lib/app.jar", "a".repeat(64), 3))) {
            assertArrayEquals(new byte[]{1, 2, 3}, in.readAllBytes());
        }
        assertEquals("Bearer secret", lastHeaders.get("authorization"));
    }

    @Test
    void refusesAnInvalidLatestVersion() {
        files.put("latest", "../../etc".getBytes(StandardCharsets.UTF_8));
        assertThrows(UpdateException.class, () -> feed(Duration.ofSeconds(5)).latestVersion());
    }

    @Test
    void retriesOnceAfterAServerError() throws IOException {
        files.put("latest", "1.0.0".getBytes(StandardCharsets.UTF_8));
        failuresLeft.set(1);
        assertEquals("1.0.0", feed(Duration.ofSeconds(5)).latestVersion().orElseThrow());
    }

    @Test
    void givesUpAfterTwoServerErrors() {
        files.put("1.0.0/manifest", new byte[1]);
        failuresLeft.set(2);
        assertThrows(IOException.class, () -> feed(Duration.ofSeconds(5)).manifest("1.0.0"));
    }

    @Test
    void refusesHugeManifests() {
        files.put("1.0.0/manifest", new byte[(1 << 20) + 1]);
        assertThrows(UpdateException.class, () -> feed(Duration.ofSeconds(5)).manifest("1.0.0"));
    }

    @Test
    void slowServersTimeOut() {
        files.put("latest", "1.0.0".getBytes(StandardCharsets.UTF_8));
        delayMs = 2000;
        assertThrows(IOException.class, () -> feed(Duration.ofMillis(200)).latestVersion());
    }

    @Test
    void onlyHttpAndHttps() {
        assertThrows(IllegalArgumentException.class, () -> new HttpFeed(URI.create("file:///tmp/feed")));
    }
}
