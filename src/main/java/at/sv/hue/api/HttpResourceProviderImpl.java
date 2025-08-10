package at.sv.hue.api;

import lombok.extern.slf4j.Slf4j;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import java.io.IOException;
import java.net.URL;

@Slf4j
public class HttpResourceProviderImpl implements HttpResourceProvider {

    public static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    private final OkHttpClient httpClient;

    public HttpResourceProviderImpl(OkHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public String getResource(URL url) {
        log.trace("Get: {}", url);
        return performCall(getRequest(url));
    }

    @Override
    public String putResource(URL url, String body) {
        log.trace("Put: {}: {}", url, getTruncatedBody(body));
        return performCall(putRequest(url, body));
    }

    private static String getTruncatedBody(String body) {
        return body.length() > 150 ? body.substring(0, 150) + "..." : body;
    }

    @Override
    public String postResource(URL url, String body) {
        log.trace("Post: {}: {}", url, getTruncatedBody(body));
        return performCall(postRequest(url, body));
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

    private static Request postRequest(URL url, String json) {
        RequestBody requestBody = RequestBody.create(json, JSON);
        return new Request.Builder()
                .url(url)
                .post(requestBody)
                .build();
    }

    private String performCall(Request request) {
        try (Response response = callHttpClient(request)) {
            assertSuccessful(response);
            return getBody(response);
        } catch (IOException e) {
            log.error("Failed '{}'", request);
            throw new BridgeConnectionFailure("Failed '" + request + "'", e);
        }
    }

    private Response callHttpClient(Request request) throws IOException {
        return httpClient.newCall(request).execute();
    }

    private static void assertSuccessful(Response response) throws IOException {
        if (response.code() == 401 || response.code() == 403) {
            throw new BridgeAuthenticationFailure();
        }
        if (response.code() == 404) {
            throw new ResourceNotFoundException("Resource not found: " + getBody(response));
        }
        if (response.code() == 429) {
            throw new ApiFailure("Rate limit exceeded");
        }
        if (response.code() >= 500) {
            throw new ApiFailure("Server error: " + getBody(response));
        }
        if (!response.isSuccessful()) {
            throw new IOException("Unexpected return code " + response + ". " + getBody(response));
        }
    }

    private static String getBody(Response response) throws IOException {
        return response.body().string();
    }
}
