package at.sv.hue.api.hass.area;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class HassWebSocketClientImplTest {

    private static final String ORIGIN = "http://example.com";
    private static final String ACCESS_TOKEN = "test_token";

    private OkHttpClient mockClient;
    private WebSocket mockWebSocket;
    private HassWebSocketClientImpl client;
    private int requestTimeoutSeconds;
    private ArgumentCaptor<String> messageCaptor;

    @BeforeEach
    void setup() {
        mockClient = mock(OkHttpClient.class);
        mockWebSocket = mock(WebSocket.class);
        when(mockClient.newWebSocket(any(Request.class), any())).thenReturn(mockWebSocket);
        messageCaptor = ArgumentCaptor.forClass(String.class);
        when(mockWebSocket.send(messageCaptor.capture())).thenReturn(true);
        requestTimeoutSeconds = 3;
        client = new HassWebSocketClientImpl(ORIGIN, ACCESS_TOKEN, mockClient, requestTimeoutSeconds);
    }

    @Test
    void sendCommand_timeoutOnAuthentication_exception() throws Exception {
        CompletableFuture<String> futureResult = asyncSendExampleCommand();

        assertThatThrownBy(() -> getResult(futureResult))
                .hasMessageContaining("Authentication timed out or failed.");
    }

    @Test
    void sendCommand_authenticationFailed_exception() throws Exception {
        CompletableFuture<String> futureResult = asyncSendExampleCommand();

        simulateWebSocketAuthFailure();

        assertThatThrownBy(() -> getResult(futureResult))
                .hasMessageContaining("Authentication timed out or failed.");
    }

    @Test
    void sendCommand_shouldThrowExceptionWhenMessageFailsToSend() throws Exception {
        when(mockWebSocket.send(anyString())).thenReturn(false);

        CompletableFuture<String> futureResult = asyncSendExampleCommand();
        simulateWebSocketAuthSuccess();

        assertThatThrownBy(() -> getResult(futureResult))
                .hasMessageContaining("Failed to send message over WebSocket.");
    }

    @Test
    void sendCommand_timeoutOnMessage_exception() throws Exception {
        CompletableFuture<String> futureResult = asyncSendExampleCommand();
        simulateWebSocketAuthSuccess();
        // no message response

        assertThatThrownBy(() -> getResult(futureResult))
                .hasMessageContaining("Timeout or error waiting for response");
    }

    @Test
    void sendCommand_shouldSendMessageAndReturnResponse_multipleMessages_incrementsId() throws Exception {
        CompletableFuture<String> futureResult1 = asyncSendExampleCommand();
        simulateWebSocketAuthSuccess();
        simulateWebSocketResponse("{\"id\":1,\"type\":\"result\",\"success\":true}");

        String result1 = getResult(futureResult1);
        assertThat(messageCaptor.getValue()).isEqualTo("{\"id\":1,\"type\":\"test_command\"}");
        assertThat(result1).isEqualTo("{\"id\":1,\"type\":\"result\",\"success\":true}");

        CompletableFuture<String> futureResult2 = asyncSendExampleCommand();
        simulateWebSocketResponse("{\"id\":2,\"type\":\"result\",\"success\":true}");

        String result2 = getResult(futureResult2);
        assertThat(messageCaptor.getValue()).isEqualTo("{\"id\":2,\"type\":\"test_command\"}");
        assertThat(result2).isEqualTo("{\"id\":2,\"type\":\"result\",\"success\":true}");
    }

    @Test
    void sendCommand_unknownMessage_unknownId_ignored() throws Exception {
        CompletableFuture<String> futureResult = asyncSendExampleCommand();
        simulateWebSocketAuthSuccess();
        simulateWebSocketResponse("{\"unknown\":1}"); // ignored
        simulateWebSocketResponse("{\"id\":7777}"); // ignored
        simulateWebSocketResponse("{\"id\":1,\"type\":\"result\",\"success\":true}");

        String result = getResult(futureResult);
        assertThat(result).isEqualTo("{\"id\":1,\"type\":\"result\",\"success\":true}");
    }

    @Test
    void sendCommand_onFailure_reconnectsOnNextCommand() throws Exception {
        CompletableFuture<String> futureResult1 = asyncSendExampleCommand();
        simulateWebSocketAuthSuccess();
        simulateGeneralWebSocketFailure();

        assertThatThrownBy(() -> getResult(futureResult1))
                .hasMessageContaining("Timeout or error waiting for response");

        CompletableFuture<String> futureResult2 = asyncSendExampleCommand();
        simulateWebSocketAuthSuccess();
        simulateWebSocketResponse("{\"id\":2,\"type\":\"result\",\"success\":true}");

        String result2 = getResult(futureResult2);
        assertThat(messageCaptor.getValue()).isEqualTo("{\"id\":2,\"type\":\"test_command\"}");
        assertThat(result2).isEqualTo("{\"id\":2,\"type\":\"result\",\"success\":true}");
    }

    @Test
    void sendCommand_invalidJSONResponse_exception() throws Exception {
        CompletableFuture<String> futureResult = asyncSendExampleCommand();
        simulateWebSocketAuthSuccess();
        simulateWebSocketResponse("{");

        assertThatThrownBy(() -> getResult(futureResult))
                .hasMessageContaining("Timeout or error waiting for response");
    }

    @Test
    void sendCommand_webSocketFailure_exception() throws Exception {
        CompletableFuture<String> futureResult = asyncSendExampleCommand();
        simulateWebSocketAuthSuccess();
        simulateGeneralWebSocketFailure();

        assertThatThrownBy(() -> getResult(futureResult))
                .hasMessageContaining("Timeout or error waiting for response");
    }

    @Test
    void sendCommand_webSocketClosed_exception() throws Exception {
        CompletableFuture<String> futureResult = asyncSendExampleCommand();
        simulateWebSocketAuthSuccess();
        simulateWebSocketClosed();

        assertThatThrownBy(() -> getResult(futureResult))
                .hasMessageContaining("Timeout or error waiting for response");
    }

    private CompletableFuture<String> asyncSendExampleCommand() throws Exception {
        CompletableFuture<String> future = CompletableFuture.supplyAsync(() -> {
            try {
                return client.sendCommand("test_command");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        Thread.sleep(200);
        return future;
    }

    private void simulateWebSocketAuthSuccess() {
        simulateAuthResponse("{\"type\": \"auth_ok\"}");
    }

    private void simulateWebSocketAuthFailure() {
        simulateAuthResponse("{\"type\": \"auth_invalid\"}");
    }

    private void simulateAuthResponse(String authResponse) {
        WebSocketListener listener = getWebSocketListener();

        listener.onOpen(mockWebSocket, mock(Response.class));
        listener.onMessage(mockWebSocket, authResponse);
    }

    private void simulateWebSocketResponse(String text) {
        getWebSocketListener().onMessage(mockWebSocket, text);
    }

    private void simulateGeneralWebSocketFailure() {
        getWebSocketListener().onFailure(mockWebSocket, new Exception(), mock(Response.class));
    }

    private void simulateWebSocketClosed() {
        getWebSocketListener().onClosing(mockWebSocket, 1002, "Reason");
    }

    private WebSocketListener getWebSocketListener() {
        ArgumentCaptor<WebSocketListener> listenerCaptor = ArgumentCaptor.forClass(WebSocketListener.class);
        verify(mockClient, atLeastOnce()).newWebSocket(any(Request.class), listenerCaptor.capture());
        return listenerCaptor.getValue();
    }

    private String getResult(CompletableFuture<String> futureResult) throws Exception {
        return futureResult.get(requestTimeoutSeconds + 1, TimeUnit.SECONDS);
    }
}
