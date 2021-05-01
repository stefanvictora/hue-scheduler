package at.sv.hue.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class HttpResourceProviderImpl implements HttpResourceProvider {

    private static final Logger LOG = LoggerFactory.getLogger(HttpResourceProviderImpl.class);

    @Override
    public String getResource(URL url) {
        LOG.trace("Get: {}", url);
        try (BufferedReader reader = getResourceReader(url)) {
            return read(reader);
        } catch (IOException e) {
            throw new BridgeConnectionFailure("Failed to GET '" + url + "'", e);
        }
    }

    private BufferedReader getResourceReader(URL url) throws IOException {
        return warpInBufferedReader(url.openStream());
    }

    private BufferedReader warpInBufferedReader(InputStream inputStream) {
        return new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
    }

    private String read(BufferedReader reader) throws IOException {
        StringBuilder str = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            str.append(line).append("\n");
        }
        return str.toString();
    }

    @Override
    public String putResource(URL url, String body) {
        LOG.trace("Put: {}: {}", url, body);
        try {
            HttpURLConnection http = (HttpURLConnection) url.openConnection();
            http.setRequestMethod("PUT");
            http.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            http.setDoOutput(true);
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            http.setFixedLengthStreamingMode(out.length);
            http.connect();
            try (OutputStream os = http.getOutputStream()) {
                os.write(out);
            }
            return read(warpInBufferedReader(http.getInputStream()));
        } catch (IOException e) {
            throw new BridgeConnectionFailure("Failed to PUT '" + url + "'", e);
        }
    }
}
