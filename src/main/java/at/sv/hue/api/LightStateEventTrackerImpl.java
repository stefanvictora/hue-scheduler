package at.sv.hue.api;

import com.launchdarkly.eventsource.ConnectStrategy;
import com.launchdarkly.eventsource.ErrorStrategy;
import com.launchdarkly.eventsource.EventSource;
import com.launchdarkly.eventsource.background.BackgroundEventSource;

import java.net.MalformedURLException;
import java.net.URL;

public class LightStateEventTrackerImpl {

    private final String ip;
    private final String apiKey;
    private final URL eventUrl;
    private final HueRawEventHandler hueRawEventHandler;

    public LightStateEventTrackerImpl(String ip, String apiKey, HueRawEventHandler eventHandler) {
        this.ip = ip;
        this.apiKey = apiKey;
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
        } catch (final Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private BackgroundEventSource createBackgroundEventSource(HueRawEventHandler hueRawEventHandler) throws Exception {
        return new BackgroundEventSource.Builder(hueRawEventHandler,
                new EventSource.Builder(ConnectStrategy.http(eventUrl)
                                                       .httpClient(HueApiHttpsClientFactory.createHttpsClient(ip))
                                                       .header("hue-application-key", apiKey))
                        .errorStrategy(ErrorStrategy.alwaysContinue())
        ).build();
    }
}
