package at.sv.hue.api.hass.area;

public interface HassWebSocketClient {
    /**
     * Sends a command to the Home Assistant WebSocket API and returns its response synchronously.
     *
     * @param commandType the type of command to send
     * @return the response from the Home Assistant server as a JSON string
     * @throws HassWebSocketException if authentication fails, the connection fails, or a timeout occurs
     */
    String sendCommand(String commandType);
}
