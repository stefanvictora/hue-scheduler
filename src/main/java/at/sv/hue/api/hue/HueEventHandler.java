package at.sv.hue.api.hue;

import at.sv.hue.api.LightEventListener;
import at.sv.hue.api.ResourceModificationEventListener;
import at.sv.hue.api.SceneEventListener;
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
public final class HueEventHandler implements BackgroundEventHandler {
    private static final TypeReference<List<HueEventContainer>> typeRef = new TypeReference<>() {
    };
    private final LightEventListener lightEventListener;
    private final SceneEventListener sceneEventListener;
    private final ResourceModificationEventListener resourceModificationEventListener;
    private final ObjectMapper objectMapper;

    public HueEventHandler(LightEventListener lightEventListener, SceneEventListener sceneEventListener,
                           ResourceModificationEventListener resourceModificationEventListener) {
        this.lightEventListener = lightEventListener;
        this.sceneEventListener = sceneEventListener;
        this.resourceModificationEventListener = resourceModificationEventListener;
        objectMapper = new ObjectMapper();
        objectMapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Override
    public void onOpen() {
        MDC.put("context", "events");
        log.trace("Hue event stream handler opened.");
    }

    @Override
    public void onClosed() {
        MDC.put("context", "events");
        log.trace("Hue event stream handler closed.");
    }

    @Override
    public void onMessage(String event, MessageEvent messageEvent) throws Exception {
        List<HueEventContainer> hueEventContainers = objectMapper.readValue(messageEvent.getData(), typeRef);
        for (HueEventContainer container : hueEventContainers) {
            for (HueEvent hueEvent : container.getData()) {
                if (hueEvent.isLightOrGroup() && hueEvent.isOffEvent()) {
                    lightEventListener.onLightOff(hueEvent.getId());
                } else if (hueEvent.isLightOrGroup() && hueEvent.isOnEvent()) {
                    MDC.put("context", "events");
                    if (hueEvent.isPhysical()) {
                        lightEventListener.onPhysicalOn(hueEvent.getOwner().getRid());
                    } else {
                        lightEventListener.onLightOn(hueEvent.getId());
                    }
                } else if (hueEvent.isScene() && hueEvent.isSceneActivated()) {
                    sceneEventListener.onSceneActivated(hueEvent.getId());
                }

                if ("update".equals(container.type)) {
                    if (isNotRelevantForUpdate(hueEvent.type) || hueEvent.notRelevantSceneModification()) {
                        continue;
                    }
                }
                resourceModificationEventListener.onModification(hueEvent.getType(), hueEvent.getId());
            }
        }
    }

    private static boolean isNotRelevantForUpdate(String type) {
        return "light".equals(type) || "grouped_light".equals(type) || "device".equals(type);
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
        private JsonNode actions;
        private JsonNode metadata;
        private Resource owner;
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
            return "grouped_light".equals(type);
        }

        public boolean isScene() {
            return "scene".equals(type);
        }

        public boolean isSceneActivated() {
            String sceneStatus = getSceneActiveStatus();
            return "static".equals(sceneStatus) || "dynamic_palette".equals(sceneStatus) || isSceneRecall();
        }

        private String getSceneActiveStatus() {
            if (status != null && status.get("active") != null) {
                return status.get("active").asText();
            }
            return null;
        }

        private boolean isSceneRecall() {
            return status != null && status.get("last_recall") != null;
        }

        public boolean notRelevantSceneModification() {
            return isScene() && actions==null && metadata==null;
        }

        @Data
        private static final class On {
            private boolean on;
        }

        @Data
        private static final class Resource {
            String rid;
            String rtype;
        }
    }
}


