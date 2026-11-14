package io.github.outcast292.jvmupdater;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the {@link Layout} over HTTP(S): a public bucket (S3, B2, R2...), GitHub Pages or any
 * static web server. Extra headers can carry a token for feeds behind simple authentication.
 *
 * <p>Redirects are followed. A request that fails on the network or with a 5xx is tried once
 * more. The timeout covers connecting and waiting for the response headers.
 */
public final class HttpFeed implements Feed {

    private static final int MAX_METADATA_BYTES = 1 << 20;

    private final URI base;
    private final Map<String, String> headers;
    private final Duration timeout;
    private final HttpClient client;

    public HttpFeed(URI base) {
        this(base, Map.of(), Duration.ofSeconds(30));
    }

    public HttpFeed(URI base, Map<String, String> headers, Duration timeout) {
        String scheme = base.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Feed URL must be http or https: " + base);
        }
        String text = base.toString();
        this.base = URI.create(text.endsWith("/") ? text : text + "/");
        this.headers = Map.copyOf(headers);
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public URI base() {
        return base;
    }

    @Override
    public Optional<String> latestVersion() throws IOException {
        HttpResponse<InputStream> response = send(Layout.LATEST);
        if (response.statusCode() == 404) {
            response.body().close();
            return Optional.empty();
        }
        String version = new String(readSmall(response, Layout.LATEST), StandardCharsets.UTF_8).strip();
        if (!Versions.isValid(version)) {
            throw new UpdateException("Feed " + base + " offers an invalid version: " + version);
        }
        return Optional.of(version);
    }

    @Override
    public byte[] manifest(String version) throws IOException {
        String path = Layout.manifest(version);
        return readSmall(send(path), path);
    }

    @Override
    public byte[] signature(String version) throws IOException {
        String path = Layout.signature(version);
        return readSmall(send(path), path);
    }

    @Override
    public InputStream open(String version, Manifest.Entry file) throws IOException {
        String path = Layout.file(version, file);
        HttpResponse<InputStream> response = send(path);
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("GET " + base.resolve(path) + " answered " + response.statusCode());
        }
        return response.body();
    }

    private HttpResponse<InputStream> send(String path) throws IOException {
        // Paths only contain [A-Za-z0-9._+-/] (see Manifest and Versions), so no escaping is needed.
        HttpRequest.Builder builder = HttpRequest.newBuilder(base.resolve(path)).timeout(timeout).GET();
        headers.forEach(builder::header);
        HttpRequest request = builder.build();

        IOException failure = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() >= 500 && attempt == 0) {
                    response.body().close();
                    continue;
                }
                return response;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while downloading " + request.uri());
            } catch (IOException e) {
                failure = e;
            }
        }
        throw failure != null ? failure : new IOException("GET " + request.uri() + " keeps failing with a server error");
    }

    private byte[] readSmall(HttpResponse<InputStream> response, String path) throws IOException {
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("GET " + base.resolve(path) + " answered " + response.statusCode());
            }
            byte[] bytes = body.readNBytes(MAX_METADATA_BYTES + 1);
            if (bytes.length > MAX_METADATA_BYTES) {
                throw new UpdateException(base.resolve(path) + " is larger than " + MAX_METADATA_BYTES + " bytes");
            }
            return bytes;
        }
    }
}
