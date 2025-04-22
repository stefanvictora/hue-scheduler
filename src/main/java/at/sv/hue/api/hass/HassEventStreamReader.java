package at.sv.hue.api.hass;

import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.MDC;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
public final class HassEventStreamReader {

    private final String origin;
    private final String accessToken;
    private final OkHttpClient client;
    private final HassEventHandler hassEventHandler;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(factory -> {
        Thread thread = new Thread(factory, "hass-reconnect");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger messageIdCounter = new AtomicInteger(1);

    public HassEventStreamReader(String origin, String accessToken, OkHttpClient client, HassEventHandler hassEventHandler) {
        this.origin = origin;
        this.accessToken = accessToken;
        this.client = client.newBuilder()
                            .retryOnConnectionFailure(true)
                            .pingInterval(30, TimeUnit.SECONDS)
                            .build();
        this.hassEventHandler = hassEventHandler;
    }

    public void start() {
        Request request = new Request.Builder().url(origin + "/api/websocket").build();
        MDC.put("context", "events");
        log.trace("Connecting to HA event stream...");
        client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
                MDC.put("context", "events");
                log.info("HA event stream connected.");
                authenticate(webSocket);
                subscribeToEvents(webSocket, "state_changed");
                subscribeToEvents(webSocket, "homeassistant_started");
                MDC.clear();
            }

            @Override
            public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
                MDC.put("context", "events");
                try {
                    hassEventHandler.onMessage(text);
                } catch (Exception e) {
                    log.error("Exception while handling message: {}", text, e);
                }
                MDC.clear();
            }

            @Override
            public void onClosing(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                MDC.put("context", "events");
                log.warn("HA event stream closing: [{}] {}. Reconnecting in 3s.", code, reason);
                webSocket.close(code, reason);
                reconnectWithDelay();
                MDC.clear();
            }

            @Override
            public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, @Nullable Response response) {
                MDC.put("context", "events");
                log.error("HA event stream failure: '{}'. Reconnecting in 3s.", t.getMessage());
                webSocket.cancel();
                reconnectWithDelay();
                MDC.clear();
            }
        });
    }

    private void reconnectWithDelay() {
        scheduler.schedule(this::start, 3, TimeUnit.SECONDS);
    }

    private void authenticate(WebSocket webSocket) {
        String authMessage = String.format("{\"type\": \"auth\", \"access_token\": \"%s\"}", accessToken);
        webSocket.send(authMessage);
    }

    private void subscribeToEvents(WebSocket webSocket, String type) {
        int messageId = messageIdCounter.getAndIncrement();
        String subscribeMessage = String.format(
                "{\"id\": %d, \"type\": \"subscribe_events\", \"event_type\": \"%s\"}", messageId, type);
        webSocket.send(subscribeMessage);
    }
}
