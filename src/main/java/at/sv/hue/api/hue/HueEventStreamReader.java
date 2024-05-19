package at.sv.hue.api.hue;

import com.launchdarkly.eventsource.ConnectStrategy;
import com.launchdarkly.eventsource.ErrorStrategy;
import com.launchdarkly.eventsource.EventSource;
import com.launchdarkly.eventsource.background.BackgroundEventHandler;
import com.launchdarkly.eventsource.background.BackgroundEventSource;
import okhttp3.OkHttpClient;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;

public class HueEventStreamReader {

    private final String apiKey;
    private final URI eventUrl;
    private final BackgroundEventHandler eventHandler;
    private final OkHttpClient httpsClient;

    public HueEventStreamReader(String ip, String apiKey, OkHttpClient httpsClient, BackgroundEventHandler eventHandler,
                                int eventStreamReadTimeoutInMinutes) {
        this.apiKey = apiKey;
        this.httpsClient = httpsClient.newBuilder()
                                      .connectTimeout(Duration.ofSeconds(15))
                                      .readTimeout(Duration.ofMinutes(eventStreamReadTimeoutInMinutes))
                                      .build();
        this.eventHandler = eventHandler;
        eventUrl = createUrl("https://" + ip + "/eventstream/clip/v2");
    }

    private static URI createUrl(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Failed to construct API url", e);
        }
    }

    public void start() {
        try {
            createBackgroundEventSource(eventHandler).start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private BackgroundEventSource createBackgroundEventSource(BackgroundEventHandler eventHandler) {
        return new BackgroundEventSource.Builder(eventHandler,
                new EventSource.Builder(ConnectStrategy.http(eventUrl)
                                                       .httpClient(httpsClient)
                                                       .header("hue-application-key", apiKey))
                        .errorStrategy(ErrorStrategy.alwaysContinue())
        ).build();
    }
}
