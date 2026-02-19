package at.sv.hue.api;

import lombok.extern.slf4j.Slf4j;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Slf4j
class HttpResourceProviderTest {
    private HttpResourceProviderImpl provider;
    private MockWebServer mockServer;
    private URL url;

    @BeforeEach
    void setUp() throws IOException {
        OkHttpClient client = new OkHttpClient.Builder().build();
        provider = new HttpResourceProviderImpl(client, 2);
        mockServer = new MockWebServer();
        mockServer.start();
        url = mockServer.url("/test").url();
    }

    @AfterEach
    void tearDown() {
        shutdownIgnoringException();
    }

    private void shutdownIgnoringException() {
        mockServer.close();
    }

    @Test
    void get_success() {
        mockServer.enqueue(new MockResponse.Builder().body("test").build());

        String resource = provider.getResource(url);

        assertThat(resource).isEqualTo("test");
    }

    @Test
    void put_success() {
        mockServer.enqueue(new MockResponse.Builder().body("test").build());

        String resource = provider.putResource(url, "{}");

        assertThat(resource).isEqualTo("test");
    }

    @Test
    void post_success() {
        mockServer.enqueue(new MockResponse.Builder().body("test").build());

        String resource = provider.postResource(url, "{}");

        assertThat(resource).isEqualTo("test");
    }

    @Test
    void get_exception() {
        shutdownIgnoringException();

        assertThatThrownBy(() -> provider.getResource(url)).isInstanceOf(BridgeConnectionFailure.class);
    }

    @Test
    void code_404_throwsResourceNotFoundException() {
        mockStatusCode(404, "Not found");

        assertThrowsError(ResourceNotFoundException.class, "Resource not found: Not found");
    }

    @Test
    void code_401_throwsAuthenticationFailureException() {
        mockServer.enqueue(new MockResponse.Builder().code(401).build());

        assertThatThrownBy(() -> provider.getResource(url)).isInstanceOf(BridgeAuthenticationFailure.class);
    }

    @Test
    void code_403_throwsAuthenticationFailureException() {
        mockServer.enqueue(new MockResponse.Builder().code(403).build());

        assertThatThrownBy(() -> provider.getResource(url)).isInstanceOf(BridgeAuthenticationFailure.class);
    }

    @Test
    void code_429_rateLimit_throwsApiFailure_ignoresDescription() {
        mockStatusCode(429, "Description");

        assertThrowsError(ApiFailure.class, "Rate limit exceeded");
    }

    @Test
    void code_500_serverError_throwsApiFailure() {
        mockStatusCode(500, "Description");

        assertThrowsError(ApiFailure.class, "Server error: Description");
    }

    @Test
    void code_unexpected_410_connectionException() {
        mockStatusCode(410, "Description");

        assertThrowsError(BridgeConnectionFailure.class, "Failed");
    }

    private void mockStatusCode(int code, String body) {
        mockServer.enqueue(new MockResponse.Builder().code(code).body(body).build());
        mockServer.enqueue(new MockResponse.Builder().code(code).body(body).build());
        mockServer.enqueue(new MockResponse.Builder().code(code).body(body).build());
    }

    private void assertThrowsError(Class<?> type, String messageContaining) {
        assertThatThrownBy(() -> provider.getResource(url)).isInstanceOf(type).hasMessageContaining(messageContaining);
        assertThatThrownBy(() -> provider.putResource(url, "{}")).isInstanceOf(type).hasMessageContaining(messageContaining);
        assertThatThrownBy(() -> provider.postResource(url, "{}")).isInstanceOf(type).hasMessageContaining(messageContaining);
    }
}
