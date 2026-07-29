package app.revanced.extension.edge.devtools;

import android.content.res.AssetManager;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.net.Uri;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

final class DevToolsServer implements Closeable {
    private static final String LOG_TAG = "EdgeDevTools";
    private static final String FRONTEND_PATH = "frontend/";
    private static final String FRONTEND_ASSET_PATH = "edge_devtools/";
    private static final int BUFFER_SIZE = 32 * 1024;
    private static final int MAX_HTTP_HEADERS = 64 * 1024;
    private static final int MAX_FRONTEND_ASSET_BYTES = 32 * 1024 * 1024;

    private final AssetManager assets;
    private final ServerSocket listener;
    private final Set<Connection> connections = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final int browserPid;

    DevToolsServer(AssetManager assets, int browserPid) throws IOException {
        if (browserPid <= 0) {
            throw new IOException("Edge browser process is unavailable");
        }
        this.assets = assets;
        this.browserPid = browserPid;

        listener = new ServerSocket(
            0,
            16,
            InetAddress.getByName("127.0.0.1")
        );

        Thread acceptThread = new Thread(this::acceptConnections, "EdgeDevToolsAccept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    int getPort() {
        return listener.getLocalPort();
    }

    boolean isClosed() {
        return closed.get();
    }

    String getTargets() throws IOException {
        try (LocalSocket backend = connectBackend()) {
            backend.setSoTimeout(3000);

            OutputStream output = backend.getOutputStream();
            output.write(
                ("GET /json/list HTTP/1.1\r\n" +
                    "Host: 127.0.0.1:" + getPort() + "\r\n" +
                    "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)
            );
            output.flush();

            return readHttpBody(backend.getInputStream());
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }

        closeQuietly(listener);
        for (Connection connection : connections) {
            connection.close();
        }
        connections.clear();
    }

    private void acceptConnections() {
        while (!closed.get()) {
            try {
                Socket client = listener.accept();
                client.setTcpNoDelay(true);
                Thread routeThread = new Thread(
                    () -> route(client),
                    "EdgeDevToolsRoute"
                );
                routeThread.setDaemon(true);
                routeThread.start();
            } catch (SocketException exception) {
                if (!closed.get()) {
                    close();
                }
            } catch (IOException ignored) {
                if (!closed.get()) {
                    // Keep accepting: a single failed CDP connection is recoverable.
                }
            }
        }
    }

    private void route(Socket client) {
        try {
            byte[] requestHeaders = readHttpHeaders(client.getInputStream());
            String requestLine = new String(
                requestHeaders,
                StandardCharsets.ISO_8859_1
            ).split("\r\n", 2)[0];
            String[] requestParts = requestLine.split(" ");
            if (
                requestParts.length != 3 ||
                (!"GET".equals(requestParts[0]) && !"HEAD".equals(requestParts[0]))
            ) {
                sendError(client, 405, "Method Not Allowed");
                return;
            }

            String requestPath = normalizeRequestPath(requestParts[1]);

            if (isBackendPath(requestPath)) {
                LocalSocket backend = connectBackend();
                backend.getOutputStream().write(
                    stripFrontendOrigin(requestHeaders)
                );
                backend.getOutputStream().flush();
                Connection connection = new Connection(client, backend);
                connections.add(connection);
                connection.start();
                return;
            }

            if (requestPath.startsWith(FRONTEND_PATH)) {
                requestPath = requestPath.substring(FRONTEND_PATH.length());
            }
            serveFrontend(
                client,
                requestPath,
                "HEAD".equals(requestParts[0])
            );
        } catch (IOException exception) {
            sendError(client, 502, "Bad Gateway");
        }
    }

    private static boolean isBackendPath(String path) {
        return path.equals("json") ||
            path.startsWith("json/") ||
            path.equals("devtools") ||
            path.startsWith("devtools/");
    }

    private static String normalizeRequestPath(String requestTarget) {
        int queryIndex = requestTarget.indexOf('?');
        if (queryIndex >= 0) {
            requestTarget = requestTarget.substring(0, queryIndex);
        }

        int schemeSeparator = requestTarget.indexOf("://");
        int firstSlash = requestTarget.indexOf('/');
        int authoritySeparator = requestTarget.indexOf(':');
        if (schemeSeparator >= 0) {
            firstSlash = requestTarget.indexOf('/', schemeSeparator + 3);
            requestTarget = firstSlash >= 0
                ? requestTarget.substring(firstSlash + 1)
                : "";
        } else if (
            !requestTarget.startsWith("/") &&
            authoritySeparator >= 0 &&
            (firstSlash < 0 || authoritySeparator < firstSlash)
        ) {
            requestTarget = firstSlash >= 0
                ? requestTarget.substring(firstSlash + 1)
                : "";
        }

        while (requestTarget.startsWith("/")) {
            requestTarget = requestTarget.substring(1);
        }
        return Uri.decode(requestTarget);
    }

    private byte[] stripFrontendOrigin(byte[] requestHeaders) {
        String request = new String(
            requestHeaders,
            StandardCharsets.ISO_8859_1
        );
        String expectedOrigin = "http://127.0.0.1:" + getPort();
        String[] lines = request.split("\r\n", -1);
        StringBuilder sanitized = new StringBuilder(request.length());
        boolean removed = false;

        for (String line : lines) {
            int separator = line.indexOf(':');
            if (
                separator > 0 &&
                line.substring(0, separator).equalsIgnoreCase("Origin") &&
                line.substring(separator + 1).trim().equals(expectedOrigin)
            ) {
                removed = true;
                continue;
            }
            if (sanitized.length() > 0) {
                sanitized.append("\r\n");
            }
            sanitized.append(line);
        }

        return removed
            ? sanitized.toString().getBytes(StandardCharsets.ISO_8859_1)
            : requestHeaders;
    }

    private void serveFrontend(Socket client, String path, boolean head) {
        if (
            path.isEmpty() ||
            path.startsWith("/") ||
            path.indexOf('\\') >= 0 ||
            path.indexOf('\0') >= 0 ||
            containsParentSegment(path)
        ) {
            sendError(client, 404, "Not Found");
            return;
        }

        try (InputStream input = assets.open(
            FRONTEND_ASSET_PATH + path,
            AssetManager.ACCESS_STREAMING
        )) {
            byte[] body = readFrontendAsset(input);
            OutputStream output = client.getOutputStream();
            output.write(
                ("HTTP/1.1 200 OK\r\n" +
                    "Content-Type: " + contentType(path) + "\r\n" +
                    "Content-Length: " + body.length + "\r\n" +
                    "Cache-Control: no-store\r\n" +
                    "X-Content-Type-Options: nosniff\r\n" +
                    "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)
            );
            if (!head) {
                output.write(body);
            }
            output.flush();
        } catch (IOException exception) {
            Log.e(LOG_TAG, "Could not serve DevTools asset " + path, exception);
            sendError(client, 404, "Not Found");
        } finally {
            closeQuietly(client);
        }
    }

    private static byte[] readFrontendAsset(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[BUFFER_SIZE];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (output.size() + count > MAX_FRONTEND_ASSET_BYTES) {
                throw new IOException("Oversized DevTools frontend asset");
            }
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }

    private static void sendError(Socket client, int status, String reason) {
        try {
            client.getOutputStream().write(
                ("HTTP/1.1 " + status + " " + reason + "\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)
            );
        } catch (IOException ignored) {
            // The requesting tab may already have gone away.
        } finally {
            closeQuietly(client);
        }
    }

    private static boolean containsParentSegment(String path) {
        for (String segment : path.split("/")) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static String contentType(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=UTF-8";
        }
        if (path.endsWith(".js") || path.endsWith(".mjs")) {
            return "text/javascript; charset=UTF-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=UTF-8";
        }
        if (path.endsWith(".json") || path.endsWith(".map")) {
            return "application/json; charset=UTF-8";
        }
        if (path.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (path.endsWith(".png")) {
            return "image/png";
        }
        if (path.endsWith(".avif")) {
            return "image/avif";
        }
        if (path.endsWith(".webp")) {
            return "image/webp";
        }
        if (path.endsWith(".gif")) {
            return "image/gif";
        }
        if (path.endsWith(".ico")) {
            return "image/x-icon";
        }
        if (path.endsWith(".wasm")) {
            return "application/wasm";
        }
        if (path.endsWith(".woff2")) {
            return "font/woff2";
        }
        if (path.endsWith(".woff")) {
            return "font/woff";
        }
        if (path.endsWith(".ttf")) {
            return "font/ttf";
        }
        return "application/octet-stream";
    }

    private LocalSocket connectBackend() throws IOException {
        IOException lastFailure = null;
        String[] socketNames = {
            "chrome_devtools_remote_" + browserPid,
            "chrome_devtools_remote"
        };

        for (String socketName : socketNames) {
            LocalSocket socket = new LocalSocket();
            try {
                socket.connect(
                    new LocalSocketAddress(
                        socketName,
                        LocalSocketAddress.Namespace.ABSTRACT
                    )
                );
                return socket;
            } catch (IOException exception) {
                closeQuietly(socket);
                lastFailure = exception;
            }
        }

        throw lastFailure;
    }

    private static String readHttpBody(InputStream input) throws IOException {
        byte[] headers = readHttpHeaders(input);
        String[] headerLines = new String(
            headers,
            StandardCharsets.ISO_8859_1
        ).split("\r\n");
        if (headerLines.length == 0 || !headerLines[0].contains(" 200 ")) {
            throw new IOException(
                "CDP returned an unexpected HTTP status: " +
                    (headerLines.length == 0 ? "" : headerLines[0])
            );
        }

        int contentLength = -1;
        for (String headerLine : headerLines) {
            int separator = headerLine.indexOf(':');
            if (
                separator > 0 &&
                headerLine.substring(0, separator).equalsIgnoreCase("Content-Length")
            ) {
                try {
                    contentLength = Integer.parseInt(
                        headerLine.substring(separator + 1).trim()
                    );
                } catch (NumberFormatException exception) {
                    throw new IOException("CDP returned an invalid Content-Length", exception);
                }
                break;
            }
        }
        if (contentLength < 0) {
            throw new IOException("CDP response has no Content-Length");
        }

        byte[] body = new byte[contentLength];
        int offset = 0;
        while (offset < body.length) {
            int count = input.read(body, offset, body.length - offset);
            if (count == -1) {
                throw new IOException("CDP closed before sending the complete response");
            }
            offset += count;
        }
        return new String(body, StandardCharsets.UTF_8);
    }

    private static byte[] readHttpHeaders(InputStream input) throws IOException {
        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        int terminatorProgress = 0;

        while (headerBytes.size() < MAX_HTTP_HEADERS) {
            int value = input.read();
            if (value == -1) {
                throw new IOException("Connection closed before sending HTTP headers");
            }

            headerBytes.write(value);
            if (
                (terminatorProgress == 0 || terminatorProgress == 2) &&
                value == '\r'
            ) {
                terminatorProgress++;
            } else if (
                (terminatorProgress == 1 || terminatorProgress == 3) &&
                value == '\n'
            ) {
                terminatorProgress++;
                if (terminatorProgress == 4) {
                    break;
                }
            } else {
                terminatorProgress = value == '\r' ? 1 : 0;
            }
        }

        if (terminatorProgress != 4) {
            throw new IOException("Oversized HTTP headers");
        }
        return headerBytes.toByteArray();
    }

    private final class Connection implements Closeable {
        private final Socket client;
        private final LocalSocket backend;
        private final AtomicBoolean connectionClosed = new AtomicBoolean();

        private Connection(Socket client, LocalSocket backend) {
            this.client = client;
            this.backend = backend;
        }

        private void start() throws IOException {
            try {
                startPipe(client.getInputStream(), backend.getOutputStream());
                startPipe(backend.getInputStream(), client.getOutputStream());
            } catch (IOException exception) {
                close();
                throw exception;
            }
        }

        private void startPipe(InputStream input, OutputStream output) {
            Thread thread = new Thread(
                () -> copy(input, output),
                "EdgeDevToolsPipe"
            );
            thread.setDaemon(true);
            thread.start();
        }

        private void copy(InputStream input, OutputStream output) {
            byte[] buffer = new byte[BUFFER_SIZE];
            try {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                    output.flush();
                }
            } catch (IOException ignored) {
                // Closing either endpoint is the normal end of a CDP connection.
            } finally {
                close();
            }
        }

        @Override
        public void close() {
            if (!connectionClosed.compareAndSet(false, true)) {
                return;
            }

            connections.remove(this);
            closeQuietly(client);
            closeQuietly(backend);
        }
    }

    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }
}
