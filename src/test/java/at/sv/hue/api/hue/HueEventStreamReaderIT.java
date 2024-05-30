package at.sv.hue.api.hue;

import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.junit5.WireMockRuntimeInfo;
import com.github.tomakehurst.wiremock.junit5.WireMockTest;
import com.launchdarkly.eventsource.MessageEvent;
import com.launchdarkly.eventsource.background.BackgroundEventHandler;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@WireMockTest(httpsEnabled = true)
class HueEventStreamReaderIT {

    @Test
    void testEventStreamWorks(WireMockRuntimeInfo info) throws Exception {
        String apiKey = "test-api-key";
        stubFor(get(WireMock.urlPathEqualTo("/eventstream/clip/v2"))
                .withHeader("hue-application-key", equalTo(apiKey))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "text/event-stream")
                        .withBody("id: 1716111309:0\ndata: Hello\n\n")));

        CountDownLatch latch = new CountDownLatch(1);
        final boolean[] messageReceived = {false};
        String testHost = "localhost:" + info.getHttpsPort();
        OkHttpClient httpsClient = TestHttpsClientFactory.createTrustAllHttpsClient();
        HueEventStreamReader reader = new HueEventStreamReader(testHost, apiKey, httpsClient, new BackgroundEventHandler() {
            @Override
            public void onOpen() {
            }

            @Override
            public void onClosed() {
            }

            @Override
            public void onMessage(String event, MessageEvent messageEvent) {
                if (messageEvent.getData().equals("Hello")) {
                    messageReceived[0] = true;
                    latch.countDown();
                }
            }

            @Override
            public void onComment(String comment) {
            }

            @Override
            public void onError(Throwable t) {
            }
        }, 1);

        reader.start();

        latch.await(3, TimeUnit.SECONDS);

        assertThat(messageReceived[0]).isTrue();
    }
}
