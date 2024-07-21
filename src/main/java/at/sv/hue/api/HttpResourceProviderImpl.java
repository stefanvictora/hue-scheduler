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
        try (Response response = callHttpClient(getRequest(url))) {
            assertSuccessful(response);
            return getBody(response);
        } catch (IOException e) {
            log.error("Failed to GET '{}'", url, e);
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
            log.error("Failed to PUT '{}'", url, e);
            throw new BridgeConnectionFailure("Failed to PUT '" + url + "'", e);
        }
    }

    @Override
    public String postResource(URL url, String body) {
        log.trace("Post: {}: {}", url, body);
        try (Response response = callHttpClient(postRequest(url, body))) {
            assertSuccessful(response);
            return getBody(response);
        } catch (IOException e) {
            log.error("Failed to POST '{}'", url, e);
            throw new BridgeConnectionFailure("Failed to POST '" + url + "'", e);
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

    private static Request postRequest(URL url, String json) {
        RequestBody requestBody = RequestBody.create(json, JSON);
        return new Request.Builder()
                .url(url)
                .post(requestBody)
                .build();
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
        if (!response.isSuccessful()) {
            throw new IOException("Unexpected return code " + response + ". " + getBody(response));
        }
    }

    private static String getBody(Response response) throws IOException {
        return response.body().string();
    }
}
