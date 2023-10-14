package at.sv.hue.api.hue;

import com.launchdarkly.eventsource.ConnectStrategy;
import com.launchdarkly.eventsource.ErrorStrategy;
import com.launchdarkly.eventsource.EventSource;
import com.launchdarkly.eventsource.background.BackgroundEventSource;
import okhttp3.OkHttpClient;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;

public class HueEventStreamReader {

    private final String apiKey;
    private final URI eventUrl;
    private final HueEventHandler hueEventHandler;
    private final OkHttpClient httpsClient;

    public HueEventStreamReader(String ip, String apiKey, OkHttpClient httpsClient, HueEventHandler eventHandler,
                                int eventStreamReadTimeoutInMinutes) {
        this.apiKey = apiKey;
        this.httpsClient = httpsClient.newBuilder()
                                      .connectTimeout(Duration.ofSeconds(15))
                                      .readTimeout(Duration.ofMinutes(eventStreamReadTimeoutInMinutes))
                                      .build();
        this.hueEventHandler = eventHandler;
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
            createBackgroundEventSource(hueEventHandler).start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private BackgroundEventSource createBackgroundEventSource(HueEventHandler hueEventHandler) {
        return new BackgroundEventSource.Builder(hueEventHandler,
                new EventSource.Builder(ConnectStrategy.http(eventUrl)
                                                       .httpClient(httpsClient)
                                                       .header("hue-application-key", apiKey))
                        .errorStrategy(ErrorStrategy.alwaysContinue())
        ).build();
    }
}
