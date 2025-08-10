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
import org.slf4j.MDC;

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
    private volatile CompletableFuture<Void> authFuture;

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
        return sendAndAwaitResponse(id, createCommandMessage(id, commandType));
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
            throw new HassWebSocketException("Failed to serialize command", e);
        }
    }

    private String sendAndAwaitResponse(int id, String message) {
        CompletableFuture<String> future = new CompletableFuture<>();
        pendingRequests.put(id, future);
        WebSocket ws = getOrConnect();
        log.trace("Send: {}", message);
        if (!ws.send(message)) {
            HassWebSocketException exception = new HassWebSocketException("Failed to send message over WebSocket.");
            invalidateConnection(ws, exception);
            pendingRequests.remove(id);
            throw exception;
        }
        return awaitResponse(id, future);
    }

    private String awaitResponse(int id, CompletableFuture<String> future) {
        try {
            return future.get(requestTimeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            pendingRequests.remove(id);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt(); // restore interrupt status
            }
            throw new HassWebSocketException("Timeout or error waiting for response.", e);
        }
    }

    private WebSocket getOrConnect() {
        synchronized (connectionLock) {
            if (webSocket == null) {
                log.trace("Connecting to HA WebSocket...");
                authFuture = new CompletableFuture<>();
                webSocket = createNewWebSocket();
            }
        }
        return awaitAuthentication();
    }

    private WebSocket createNewWebSocket() {
        Request request = new Request.Builder().url(origin + API_WEBSOCKET_PATH).build();
        return client.newWebSocket(request, new HassWebSocketListener());
    }

    private WebSocket awaitAuthentication() {
        try {
            authFuture.get(requestTimeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            invalidateConnection(webSocket, e);
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt(); // restore interrupt status
            }
            throw new HassWebSocketException("Authentication timed out or failed.", e);
        }
        synchronized (connectionLock) {
            if (webSocket == null) {
                throw new HassWebSocketException("WebSocket connection lost after authentication.");
            }
            return webSocket;
        }
    }

    private void invalidateConnection(WebSocket ws, Throwable t) {
        synchronized (connectionLock) {
            if (webSocket == ws) {
                webSocket = null;
                if (authFuture != null && !authFuture.isDone()) {
                    authFuture.completeExceptionally(t);
                }
            }
        }
        pendingRequests.forEach((id, future) -> future.completeExceptionally(t));
        pendingRequests.clear();
    }

    private class HassWebSocketListener extends WebSocketListener {
        @Override
        public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
            MDC.put("context", "websocket");
            log.info("HA WebSocket connected.");
            authenticate(webSocket);
            MDC.remove("context");
        }

        @Override
        public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
            MDC.put("context", "websocket");
            try {
                JsonNode node = mapper.readTree(text);
                handleMessage(webSocket, node, text);
            } catch (Exception e) {
                log.error("Failed to handle WebSocket message: '{}'", text, e);
            }
            MDC.remove("context");
        }

        @Override
        public void onClosing(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
            MDC.put("context", "websocket");
            log.trace("WebSocket is closing: [{}] {}", code, reason);
            invalidateConnection(webSocket, new HassWebSocketException("WebSocket closing: " + reason));
            MDC.remove("context");
        }

        @Override
        public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, @Nullable Response response) {
            MDC.put("context", "websocket");
            log.error("WebSocket failure: '{}'", t.getMessage());
            webSocket.cancel();
            invalidateConnection(webSocket, t);
            MDC.remove("context");
        }
    }

    private void authenticate(WebSocket webSocket) {
        String authMessage = String.format("{\"type\": \"auth\", \"access_token\": \"%s\"}", accessToken);
        webSocket.send(authMessage);
    }

    private void handleMessage(WebSocket webSocket, JsonNode node, String text) {
        if (node.has("type")) {
            String type = node.get("type").asText();
            if ("auth_ok".equals(type)) {
                authFuture.complete(null);
            } else if ("auth_invalid".equals(type)) {
                String errorMsg = "Authentication failed: '" + text + "'";
                log.error(errorMsg);
                invalidateConnection(webSocket, new HassWebSocketException(errorMsg));
            }
        }
        if (node.has("id")) {
            int id = node.get("id").asInt();
            CompletableFuture<String> future = pendingRequests.remove(id);
            if (future != null) {
                future.complete(text);
            }
        }
    }

    CompletableFuture<Void> getAuthFuture() {
        return authFuture;
    }

    WebSocket getWebSocket() {
        return webSocket;
    }
}
