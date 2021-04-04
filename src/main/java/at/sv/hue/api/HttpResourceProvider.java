package at.sv.hue.api;

import java.net.URL;

public interface HttpResourceProvider {
    /**
     * @return the requested resource as string, or null if an error occurred
     */
    String getResource(URL url);

    /**
     * @param body the json payload of the put request
     * @return the response of the server, or null if an error occurred
     */
    String putResource(URL url, String body);
}
