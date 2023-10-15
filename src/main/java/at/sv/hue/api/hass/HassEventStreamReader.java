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

import java.util.concurrent.TimeUnit;

@Slf4j
public final class HassEventStreamReader {

    private final String hostname;
    private final String port;
    private final String token;
    private final OkHttpClient client;
    private final HassEventHandler hassEventHandler;
    private int messageIdCounter = 1;

    public HassEventStreamReader(String hostname, String port, String token, OkHttpClient client, HassEventHandler hassEventHandler) {
        this.hostname = hostname;
        this.port = port;
        this.token = token;
        this.client = client.newBuilder()
                .retryOnConnectionFailure(true)
                .pingInterval(30, TimeUnit.SECONDS)
                .build();
        this.hassEventHandler = hassEventHandler;

    }

    public void start() {
        Request request = new Request.Builder().url("ws://" + hostname + ":" + port + "/api/websocket").build();
        client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
                MDC.put("context", "events");
                authenticate(webSocket);
                subscribeToEvents(webSocket);
                log.trace("HASS event stream handler opened.");
            }

            @Override
            public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
                MDC.put("context", "events");
                hassEventHandler.onMessage(text);
            }

            @Override
            public void onClosing(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                start();
            }

            @Override
            public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, @Nullable Response response) {
                MDC.put("context", "events");
                log.error("HASS event stream failed", t);
                start();
            }
        });
    }

    private void authenticate(WebSocket webSocket) {
        String authMessage = String.format("{\"type\": \"auth\", \"access_token\": \"%s\"}", token);
        webSocket.send(authMessage);
    }

    private void subscribeToEvents(WebSocket webSocket) {
        int messageId = messageIdCounter++;
        String subscribeMessage = String.format("{\"id\": %d, \"type\": \"subscribe_events\", \"event_type\": \"state_changed\"}", messageId);
        webSocket.send(subscribeMessage);
    }
}
