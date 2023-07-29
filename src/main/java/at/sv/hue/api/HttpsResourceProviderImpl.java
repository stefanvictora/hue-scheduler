package at.sv.hue.api;

import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.net.URL;

@Slf4j
public class HttpsResourceProviderImpl implements HttpResourceProvider {

    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;

    public HttpsResourceProviderImpl(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String getResource(URL url) {
        log.trace("Get: {}", url);
        try (Response response = callHttpClient(getRequest(url))) {
            assertSuccessful(response);
            return getBody(response);
        } catch (IOException e) {
            throw new BridgeConnectionFailure("Failed to GET '" + url + "'", e);
        }
    }

    @Override
    public String putResource(URL url, String body) {
        log.trace("Put: {}: {}", url, body);
        try (Response response = callHttpClient(putRequest(url, body))) {
            assertSuccessful(response);
            return getBody(response);
        } catch (IOException e) {
            throw new BridgeConnectionFailure("Failed to PUT '" + url + "'", e);
        }
    }

    private static Request getRequest(URL url) {
        return new Request.Builder()
                .url(url)
                .build();
    }

    private static Request putRequest(URL url, String json) {
        RequestBody requestBody = RequestBody.create(json, JSON);
        return new Request.Builder()
                .url(url)
                .put(requestBody)
                .build();
    }

    private Response callHttpClient(Request request) throws IOException {
        return httpClient.newCall(request).execute();
    }

    private static void assertSuccessful(Response response) throws IOException {
        if (!response.isSuccessful()) {
            throw new IOException("Unexpected return code " + response);
        }
    }

    private static String getBody(Response response) throws IOException {
        return response.body().string();
    }
}
