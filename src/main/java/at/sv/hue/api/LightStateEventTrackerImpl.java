package at.sv.hue.api;

import com.launchdarkly.eventsource.ConnectStrategy;
import com.launchdarkly.eventsource.ErrorStrategy;
import com.launchdarkly.eventsource.EventSource;
import com.launchdarkly.eventsource.background.BackgroundEventSource;
import okhttp3.OkHttpClient;

import java.net.MalformedURLException;
import java.net.URL;
import java.time.Duration;

public class LightStateEventTrackerImpl {

    private final String apiKey;
    private final URL eventUrl;
    private final HueRawEventHandler hueRawEventHandler;
    private final OkHttpClient httpsClient;

    public LightStateEventTrackerImpl(String ip, String apiKey, OkHttpClient httpsClient, HueRawEventHandler eventHandler) {
        this.apiKey = apiKey;
        this.httpsClient = httpsClient.newBuilder()
                                      .connectTimeout(Duration.ofSeconds(15))
                                      .readTimeout(Duration.ofHours(2))
                                      .build();
        this.hueRawEventHandler = eventHandler;
        eventUrl = createUrl("https://" + ip + "/eventstream/clip/v2");
    }

    private static URL createUrl(String url) {
        try {
            return new URL(url);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Failed to construct API url", e);
        }
    }

    public void start() {
        try {
            createBackgroundEventSource(hueRawEventHandler).start();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private BackgroundEventSource createBackgroundEventSource(HueRawEventHandler hueRawEventHandler) {
        return new BackgroundEventSource.Builder(hueRawEventHandler,
                new EventSource.Builder(ConnectStrategy.http(eventUrl)
                                                       .httpClient(httpsClient)
                                                       .header("hue-application-key", apiKey))
                        .errorStrategy(ErrorStrategy.alwaysContinue())
        ).build();
    }
}
