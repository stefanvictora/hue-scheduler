package at.sv.hue.api;

import java.net.URL;

public interface HttpResourceProvider {
    String getResource(URL url);

    String putResource(URL url, String body);
}
