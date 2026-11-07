package mealplanner.llm;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.sun.net.httpserver.HttpServer;

/** A local stand-in for the Gemini REST endpoint that replays scripted responses. */
final class FakeGeminiServer implements AutoCloseable {

    record Reply(int status, String body, long delayMillis) {
    }

    record Received(String path, String query, String apiKeyHeader, String body) {
    }

    private final HttpServer server;
    private final Deque<Reply> replies = new ArrayDeque<>();
    final List<Received> received = new ArrayList<>();

    FakeGeminiServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            synchronized (received) {
                received.add(new Received(exchange.getRequestURI().getPath(), exchange.getRequestURI().getQuery(),
                        exchange.getRequestHeaders().getFirst("x-goog-api-key"), body));
            }
            Reply reply;
            synchronized (replies) {
                reply = replies.isEmpty() ? new Reply(500, "{}", 0) : replies.removeFirst();
            }
            if (reply.delayMillis() > 0) {
                try {
                    Thread.sleep(reply.delayMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            } catch (IOException ignored) {
                // client gave up (timeout test)
            }
        });
        server.start();
    }

    FakeGeminiServer reply(int status, String body) {
        return reply(status, body, 0);
    }

    FakeGeminiServer reply(int status, String body, long delayMillis) {
        synchronized (replies) {
            replies.addLast(new Reply(status, body, delayMillis));
        }
        return this;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1beta";
    }

    static String functionCall(String name, String argsJson, String signature) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"" + name
                + "\",\"args\":" + argsJson + "},\"thoughtSignature\":\"" + signature + "\"}]},\"finishReason\":\"STOP\"}],"
                + "\"usageMetadata\":{\"promptTokenCount\":100,\"candidatesTokenCount\":10,\"thoughtsTokenCount\":5}}";
    }

    static String text(String text) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":" + quote(text)
                + ",\"thoughtSignature\":\"sigText\"}]},\"finishReason\":\"STOP\"}],"
                + "\"usageMetadata\":{\"promptTokenCount\":120,\"candidatesTokenCount\":3}}";
    }

    static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
