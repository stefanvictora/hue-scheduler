package at.sv.hue.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.launchdarkly.eventsource.MessageEvent;
import com.launchdarkly.eventsource.background.BackgroundEventHandler;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public final class HueRawEventHandler implements BackgroundEventHandler {
    private static final TypeReference<List<HueEventContainer>> typeRef = new TypeReference<List<HueEventContainer>>() {
    };
    private final HueEventListener hueEventListener;
    private final ObjectMapper objectMapper;

    public HueRawEventHandler(HueEventListener hueEventListener) {
        this.hueEventListener = hueEventListener;
        objectMapper = new ObjectMapper();
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Override
    public void onOpen() {
        MDC.put("context", "events");
        log.trace("Hue event stream handler opened");
    }

    @Override
    public void onClosed() {
        MDC.put("context", "events");
        log.trace("Hue event stream handler closed");
    }

    @Override
    public void onMessage(String event, MessageEvent messageEvent) throws Exception {
        List<HueEventContainer> hueEventContainers = objectMapper.readValue(messageEvent.getData(), typeRef);
        hueEventContainers.stream()
                          .flatMap(container -> container.getData().stream())
                          .forEach(hueEvent -> {
                              if (hueEvent.isLightOrGroup() && hueEvent.isOffEvent()) {
                                  hueEventListener.onLightOff(hueEvent.getId_v1(), hueEvent.getId());
                              } else if (hueEvent.isLightOrGroup() && hueEvent.isOnEvent()) {
                                  hueEventListener.onLightOn(hueEvent.getId_v1(), hueEvent.getId(), hueEvent.isPhysical());
                              }
                          });
    }

    @Override
    public void onComment(String comment) {
    }

    @Override
    public void onError(Throwable t) {
        MDC.put("context", "events");
        log.error("An error occurred during event stream processing: {}", t.getLocalizedMessage());
    }

    @Data
    private static final class HueEventContainer {
        private String creationtime;
        private List<HueEvent> data = new ArrayList<>();
        private String id;
        private String type;
    }

    @Data
    private static final class HueEvent {
        private String id;
        private String id_v1;
        private On on;
        private JsonNode status;
        private String type;

        private String getStatus() {
            if (status != null && status.isTextual()) {
                return status.asText();
            }
            return null;
        }

        public boolean isOffEvent() {
            return on != null && !on.isOn() || "zigbee_connectivity".equals(type) &&
                    ("connectivity_issue".equals(getStatus()) || "disconnected".equals(getStatus()));
        }

        public boolean isOnEvent() {
            return on != null && on.isOn() || "zigbee_connectivity".equals(type) && "connected".equals(getStatus());
        }

        public boolean isPhysical() {
            return "zigbee_connectivity".equals(type);
        }

        public boolean isLightOrGroup() {
            return isLight() || isGroup();
        }

        public boolean isLight() {
            return "light".equals(type) || id_v1 != null && id_v1.startsWith("/lights/");
        }

        public boolean isGroup() {
            return "grouped_light".equals(type) || id_v1 != null && id_v1.startsWith("/groups/");
        }

        @Data
        private static final class On {
            private boolean on;
        }
    }
}


