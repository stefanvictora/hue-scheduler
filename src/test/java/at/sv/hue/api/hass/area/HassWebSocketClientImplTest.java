package at.sv.hue.api.hass.area;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HassWebSocketClientImplTest {

    private static final String ORIGIN = "http://example.com";
    private static final String ACCESS_TOKEN = "test_token";
    private static final Duration AUTHENTICATION_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration RESPONSE_TIMEOUT = Duration.ofMillis(100);
    private static final Duration TEST_WAIT_TIMEOUT = Duration.ofSeconds(5);

    private OkHttpClient mockClient;
    private WebSocket mockWebSocket;
    private HassWebSocketClientImpl client;
    private ArgumentCaptor<String> messageCaptor;

    @BeforeEach
    void setup() {
        mockClient = mock(OkHttpClient.class);
        mockWebSocket = mock(WebSocket.class);
        when(mockClient.newWebSocket(any(Request.class), any(WebSocketListener.class)))
                .thenReturn(mockWebSocket);
        messageCaptor = ArgumentCaptor.forClass(String.class);
        when(mockWebSocket.send(messageCaptor.capture())).thenReturn(true);
        client = new HassWebSocketClientImpl(
                ORIGIN, ACCESS_TOKEN, mockClient, AUTHENTICATION_TIMEOUT, RESPONSE_TIMEOUT);
    }

    @Test
    void sendCommand_timeoutOnAuthentication_exception() throws Exception {
        client = new HassWebSocketClientImpl(
                ORIGIN, ACCESS_TOKEN, mockClient, Duration.ofMillis(100), RESPONSE_TIMEOUT);
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
        // No response is simulated.

        assertThatThrownBy(() -> getResult(futureResult))
                .hasMessageContaining("Timeout or error waiting for response");
    }

    @Test
    void sendCommand_shouldSendMessageAndReturnResponse_multipleMessages_incrementsId() throws Exception {
        CompletableFuture<String> futureResult1 = asyncSendExampleCommand();
        simulateWebSocketAuthSuccess();
        simulateSuccessfulWebSocketResponse(1);

        String result1 = getResult(futureResult1);
        assertThat(messageCaptor.getValue()).isEqualTo("{\"id\":1,\"type\":\"test_command\"}");
        assertThat(result1).isEqualTo("{\"id\":1,\"type\":\"result\",\"success\":true}");

        CompletableFuture<String> futureResult2 = asyncSendExampleCommand();
        simulateSuccessfulWebSocketResponse(2);

        String result2 = getResult(futureResult2);
        assertThat(messageCaptor.getValue()).isEqualTo("{\"id\":2,\"type\":\"test_command\"}");
        assertThat(result2).isEqualTo("{\"id\":2,\"type\":\"result\",\"success\":true}");
    }

    @Test
    void sendCommand_unknownMessage_unknownId_ignored() throws Exception {
        CompletableFuture<String> futureResult = asyncSendExampleCommand();
        simulateWebSocketAuthSuccess();
        waitForCommandToBeSent(1);
        simulateWebSocketResponse("{\"unknown\":1}"); // ignored
        simulateWebSocketResponse("{\"id\":7777}"); // ignored
        simulateSuccessfulWebSocketResponse(1);

        String result = getResult(futureResult);
        assertThat(result).isEqualTo("{\"id\":1,\"type\":\"result\",\"success\":true}");
    }

    @Test
    void sendCommand_onFailure_reconnectsOnNextCommand() throws Exception {
        CompletableFuture<String> futureResult1 = asyncSendExampleCommand();
        simulateWebSocketAuthSuccess();
        waitForCommandToBeSent(1);
        simulateGeneralWebSocketFailure();

        assertThatThrownBy(() -> getResult(futureResult1))
                .hasMessageContaining("Timeout or error waiting for response");

        // For a new command, the connection should be re-established.
        CompletableFuture<String> futureResult2 = asyncSendExampleCommand();
        simulateWebSocketAuthSuccess();
        simulateSuccessfulWebSocketResponse(2);

        String result2 = getResult(futureResult2);
        assertThat(messageCaptor.getValue()).isEqualTo("{\"id\":2,\"type\":\"test_command\"}");
        assertThat(result2).isEqualTo("{\"id\":2,\"type\":\"result\",\"success\":true}");
    }

    @Test
    void sendCommand_invalidJsonResponse_ignoredUntilTimeout() throws Exception {
        CompletableFuture<String> futureResult = asyncSendExampleCommand();
        simulateWebSocketAuthSuccess();
        waitForCommandToBeSent(1);
        simulateWebSocketResponse("{");

        assertThatThrownBy(() -> getResult(futureResult))
                .hasMessageContaining("Timeout or error waiting for response");
    }

    @Test
    void sendCommand_webSocketFailure_exception() throws Exception {
        CompletableFuture<String> futureResult = asyncSendExampleCommand();
        simulateWebSocketAuthSuccess();
        waitForCommandToBeSent(1);
        simulateGeneralWebSocketFailure();

        assertThatThrownBy(() -> getResult(futureResult))
                .hasMessageContaining("Timeout or error waiting for response");
    }

    @Test
    void sendCommand_webSocketClosed_exception() throws Exception {
        CompletableFuture<String> futureResult = asyncSendExampleCommand();
        simulateWebSocketAuthSuccess();
        waitForCommandToBeSent(1);
        simulateWebSocketClosed();

        assertThatThrownBy(() -> getResult(futureResult))
                .hasMessageContaining("Timeout or error waiting for response");
    }

    @Test
    void sendCommand_multipleSimultaneousCommands() throws Exception {
        // Establish ID order while keeping all three commands pending before replying.
        CompletableFuture<String> future1 = asyncSendExampleCommand();
        simulateWebSocketAuthSuccess();
        waitForCommandToBeSent(1);
        CompletableFuture<String> future2 = asyncSendExampleCommand();
        waitForCommandToBeSent(2);
        CompletableFuture<String> future3 = asyncSendExampleCommand();
        waitForCommandToBeSent(3);

        simulateSuccessfulWebSocketResponse(2);
        simulateSuccessfulWebSocketResponse(1);
        simulateSuccessfulWebSocketResponse(3);

        assertThat(getResult(future1)).isEqualTo("{\"id\":1,\"type\":\"result\",\"success\":true}");
        assertThat(getResult(future2)).isEqualTo("{\"id\":2,\"type\":\"result\",\"success\":true}");
        assertThat(getResult(future3)).isEqualTo("{\"id\":3,\"type\":\"result\",\"success\":true}");
    }

    private CompletableFuture<String> asyncSendExampleCommand() throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return client.sendCommand("test_command");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void waitForWebSocket() {
        await().atMost(TEST_WAIT_TIMEOUT)
               .until(() -> client.getWebSocket() != null);
    }

    private void simulateWebSocketAuthSuccess() {
        simulateAuthResponse("{\"type\": \"auth_ok\"}");
    }

    private void simulateWebSocketAuthFailure() {
        simulateAuthResponse("{\"type\": \"auth_invalid\"}");
    }

    private void simulateAuthResponse(String authResponse) {
        waitForWebSocket();
        WebSocketListener listener = getWebSocketListener();

        listener.onOpen(mockWebSocket, mock(Response.class));
        listener.onMessage(mockWebSocket, authResponse);
    }

    private void simulateWebSocketResponse(String text) {
        getWebSocketListener().onMessage(mockWebSocket, text);
    }

    private void simulateSuccessfulWebSocketResponse(int id) {
        waitForCommandToBeSent(id);
        simulateWebSocketResponse(String.format(
                "{\"id\":%d,\"type\":\"result\",\"success\":true}", id));
    }

    private void waitForCommandToBeSent(int id) {
        verify(mockWebSocket, timeout(TEST_WAIT_TIMEOUT.toMillis())).send(
                String.format("{\"id\":%d,\"type\":\"test_command\"}", id));
    }

    private void simulateGeneralWebSocketFailure() {
        getWebSocketListener().onFailure(mockWebSocket, new RuntimeException("Simulated Error"), mock(Response.class));
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
        return futureResult.get(TEST_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
    }
}
