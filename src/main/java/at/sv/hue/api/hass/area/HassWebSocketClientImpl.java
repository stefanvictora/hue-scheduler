package at.sv.hue.api.hass.area;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public class HassWebSocketClientImpl implements HassWebSocketClient {

    private static final String API_WEBSOCKET_PATH = "/api/websocket";

    private final String origin;
    private final String accessToken;
    private final OkHttpClient client;
    private final int requestTimeoutSeconds;
    private final ObjectMapper mapper;
    private final AtomicInteger messageIdCounter = new AtomicInteger(1);
    private final Object connectionLock = new Object();
    private final Map<Integer, CompletableFuture<String>> pendingRequests = new ConcurrentHashMap<>();

    private volatile WebSocket webSocket;
    private CompletableFuture<Void> authFuture;

    public HassWebSocketClientImpl(String origin, String accessToken, OkHttpClient client,
                                   int requestTimeoutSeconds) {
        this.origin = origin;
        this.accessToken = accessToken;
        this.client = client;
        this.requestTimeoutSeconds = requestTimeoutSeconds;
        this.mapper = new ObjectMapper();
        this.mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
    }

    @Override
    public String sendCommand(String commandType) {
        int id = messageIdCounter.getAndIncrement();
        return sendMessageAndWait(id, createCommandMessage(id, commandType));
    }

    private String createCommandMessage(int id, String commandType) {
        ObjectNode command = mapper.createObjectNode();
        command.put("id", id);
        command.put("type", commandType);
        return serialize(command);
    }

    private String serialize(ObjectNode command) {
        try {
            return mapper.writeValueAsString(command);
        } catch (JsonProcessingException e) {
            throw new HassWebSocketException("Failed to serialized command", e);
        }
    }

    private String sendMessageAndWait(int id, String message) {
        ensureConnected();
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingRequests.put(id, future);
        if (!webSocket.send(message)) {
            pendingRequests.remove(id);
            throw new HassWebSocketException("Failed to send message over WebSocket.");
        }
        try {
            return future.get(requestTimeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new HassWebSocketException("Timeout or error waiting for response", e);
        }
    }

    private void ensureConnected() {
        synchronized (connectionLock) {
            if (webSocket == null) {
                authFuture = new CompletableFuture<>();
                webSocket = createNewWebSocket();
            }
        }
        try {
            authFuture.get(requestTimeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new HassWebSocketException("Authentication timed out or failed.", e);
        }
    }

    private WebSocket createNewWebSocket() {
        Request request = new Request.Builder().url(origin + API_WEBSOCKET_PATH).build();
        return client.newWebSocket(request, new HassWebSocketListener());
    }

    private class HassWebSocketListener extends WebSocketListener {
        @Override
        public void onOpen(@NotNull WebSocket ws, @NotNull Response response) {
            String authMessage = String.format("{\"type\": \"auth\", \"access_token\": \"%s\"}", accessToken);
            ws.send(authMessage);
        }

        @Override
        public void onMessage(@NotNull WebSocket ws, @NotNull String text) {
            try {
                JsonNode node = mapper.readTree(text);
                if (node.has("type")) {
                    String type = node.get("type").asText();
                    if ("auth_ok".equals(type)) {
                        authFuture.complete(null);
                    } else if ("auth_invalid".equals(type)) {
                        authFuture.completeExceptionally(new HassWebSocketException("Authentication failed: " + text));
                    }
                }
                if (node.has("id")) {
                    int id = node.get("id").asInt();
                    CompletableFuture<String> future = pendingRequests.remove(id);
                    if (future != null) {
                        future.complete(text);
                    }
                }
            } catch (Exception e) {
                log.error("Failed handle WebSocket message: '{}'", text, e);
            }
        }

        @Override
        public void onFailure(@NotNull WebSocket ws, @NotNull Throwable t, @Nullable Response response) {
            synchronized (connectionLock) {
                webSocket = null;
            }
            pendingRequests.values().forEach(future -> future.completeExceptionally(t));
            pendingRequests.clear();
        }

        @Override
        public void onClosing(@NotNull WebSocket ws, int code, @NotNull String reason) {
            ws.close(code, reason);
            synchronized (connectionLock) {
                webSocket = null;
            }
        }
    }
}
