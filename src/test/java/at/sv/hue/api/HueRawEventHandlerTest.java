package at.sv.hue.api;

import com.launchdarkly.eventsource.MessageEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@ExtendWith(MockitoExtension.class)
class HueRawEventHandlerTest {

    @Mock
    private HueEventListener hueEventListener;
    private HueRawEventHandler handler;

    @BeforeEach
    void setUp() {
        handler = new HueRawEventHandler(hueEventListener);
    }

    @Test
    void onMessage_missingData_noError() throws Exception {
        handler.onMessage("", new MessageEvent("[\n" +
                "  {\n" +
                "    \"creationtime\": \"2023-07-22T15:07:28Z\",\n" +
                "    \"id\": \"49b5e23d-d712-48f7-a46c-db5ca9c838fd\",\n" +
                "    \"type\": \"update\"\n" +
                "  }\n" +
                "]"));

        verifyNoInteractions(hueEventListener);
    }

    @Test
    void onMessage_zigbeeConnectionLostEvent_triggersLightOff() throws Exception {
        handler.onMessage("", new MessageEvent("[\n" +
                "  {\n" +
                "    \"creationtime\": \"2023-07-22T15:07:28Z\",\n" +
                "    \"data\": [\n" +
                "      {\n" +
                "        \"id\": \"69b2c6bd-48ff-4bab-9f06-37ccd9399f69\",\n" +
                "        \"id_v1\": \"/lights/40\",\n" +
                "        \"owner\": {\n" +
                "          \"rid\": \"a3f0be40-e15f-40a2-a9a7-88bdd43b9c78\",\n" +
                "          \"rtype\": \"device\"\n" +
                "        },\n" +
                "        \"status\": \"connectivity_issue\",\n" +
                "        \"type\": \"zigbee_connectivity\"\n" +
                "      }\n" +
                "    ],\n" +
                "    \"id\": \"49b5e23d-d712-48f7-a46c-db5ca9c838fd\",\n" +
                "    \"type\": \"update\"\n" +
                "  }\n" +
                "]"));

        verify(hueEventListener).onLightOff("/lights/40", "69b2c6bd-48ff-4bab-9f06-37ccd9399f69");
    }

    @Test
    void onMessage_zigbeeDisconnected_triggersLightOff() throws Exception {
        handler.onMessage("", new MessageEvent("[\n" +
                "  {\n" +
                "    \"creationtime\": \"2023-07-22T15:07:28Z\",\n" +
                "    \"data\": [\n" +
                "      {\n" +
                "        \"id\": \"69b2c6bd-48ff-4bab-9f06-37ccd9399f69\",\n" +
                "        \"id_v1\": \"/lights/40\",\n" +
                "        \"owner\": {\n" +
                "          \"rid\": \"a3f0be40-e15f-40a2-a9a7-88bdd43b9c78\",\n" +
                "          \"rtype\": \"device\"\n" +
                "        },\n" +
                "        \"status\": \"disconnected\",\n" +
                "        \"type\": \"zigbee_connectivity\"\n" +
                "      }\n" +
                "    ],\n" +
                "    \"id\": \"49b5e23d-d712-48f7-a46c-db5ca9c838fd\",\n" +
                "    \"type\": \"update\"\n" +
                "  }\n" +
                "]"));

        verify(hueEventListener).onLightOff("/lights/40", "69b2c6bd-48ff-4bab-9f06-37ccd9399f69");
    }

    @Test
    void onMessage_zigbeeConnectionEstablishedEvent_triggersLightOn() throws Exception {
        handler.onMessage("", new MessageEvent("[\n" +
                "  {\n" +
                "    \"creationtime\": \"2023-07-22T15:12:39Z\",\n" +
                "    \"data\": [\n" +
                "      {\n" +
                "        \"id\": \"60b2bafa-53f4-4062-9f58-253be813b349\",\n" +
                "        \"id_v1\": \"/lights/39\",\n" +
                "        \"owner\": {\n" +
                "          \"rid\": \"f36455ed-7b92-4e5a-97ba-73d804ab03da\",\n" +
                "          \"rtype\": \"device\"\n" +
                "        },\n" +
                "        \"status\": \"connected\",\n" +
                "        \"type\": \"zigbee_connectivity\"\n" +
                "      }\n" +
                "    ],\n" +
                "    \"id\": \"6f772cec-7f86-4b99-8629-7f9a285129a5\",\n" +
                "    \"type\": \"update\"\n" +
                "  }\n" +
                "]"));

        verify(hueEventListener).onLightOn("/lights/39", "60b2bafa-53f4-4062-9f58-253be813b349");
    }

    @Test
    void onMessage_sceneEvents_noErrorIsThrown() throws Exception {
        handler.onMessage("", new MessageEvent("[\n" +
                "  {\n" +
                "    \"creationtime\": \"2023-07-22T18:57:03Z\",\n" +
                "    \"data\": [\n" +
                "      {\n" +
                "        \"id\": \"b3ac88cb-4590-4b0a-a267-c8473250e955\",\n" +
                "        \"id_v1\": \"/scenes/WKgxJj8-o8y3pH8\",\n" +
                "        \"status\": {\n" +
                "          \"active\": \"inactive\"\n" +
                "        },\n" +
                "        \"type\": \"scene\"\n" +
                "      },\n" +
                "      {\n" +
                "        \"id\": \"f9b4085b-1409-4b12-ae0d-4ffe2be28b79\",\n" +
                "        \"id_v1\": \"/scenes/IaVm23klVFrfDGQ\",\n" +
                "        \"status\": {\n" +
                "          \"active\": \"static\"\n" +
                "        },\n" +
                "        \"type\": \"scene\"\n" +
                "      }\n" +
                "    ],\n" +
                "    \"id\": \"5cf1f272-c33c-4c6c-9e74-9366cac5d969\",\n" +
                "    \"type\": \"update\"\n" +
                "  }\n" +
                "]\n"));

        verifyNoInteractions(hueEventListener);
    }

    @Test
    void onMessage_onEvent_triggersLightOn() throws Exception {
        handler.onMessage("", new MessageEvent("[\n" +
                "  {\n" +
                "    \"creationtime\": \"2023-07-22T15:12:39Z\",\n" +
                "    \"data\": [\n" +
                "      {\n" +
                "        \"id\": \"d37eb9c4-d7eb-42ee-9a13-fa9148f8d403\",\n" +
                "        \"id_v1\": \"/lights/36\",\n" +
                "        \"on\": {\n" +
                "          \"on\": true\n" +
                "        },\n" +
                "        \"owner\": {\n" +
                "          \"rid\": \"a2cbe749-c227-4038-811d-f62881a9bd9f\",\n" +
                "          \"rtype\": \"device\"\n" +
                "        },\n" +
                "        \"type\": \"light\"\n" +
                "      }\n" +
                "    ],\n" +
                "    \"id\": \"6f772cec-7f86-4b99-8629-7f9a285129a5\",\n" +
                "    \"type\": \"update\"\n" +
                "  }\n" +
                "]"));

        verify(hueEventListener).onLightOn("/lights/36", "d37eb9c4-d7eb-42ee-9a13-fa9148f8d403");
    }

    @Test
    void onMessage_offEvent_triggersLightOff() throws Exception {
        handler.onMessage("", new MessageEvent("[\n" +
                "  {\n" +
                "    \"creationtime\": \"2023-07-22T15:12:39Z\",\n" +
                "    \"data\": [\n" +
                "      {\n" +
                "        \"id\": \"d37eb9c4-d7eb-42ee-9a13-fa9148f8d403\",\n" +
                "        \"id_v1\": \"/lights/36\",\n" +
                "        \"on\": {\n" +
                "          \"on\": false\n" +
                "        },\n" +
                "        \"owner\": {\n" +
                "          \"rid\": \"a2cbe749-c227-4038-811d-f62881a9bd9f\",\n" +
                "          \"rtype\": \"device\"\n" +
                "        },\n" +
                "        \"type\": \"light\"\n" +
                "      }\n" +
                "    ],\n" +
                "    \"id\": \"6f772cec-7f86-4b99-8629-7f9a285129a5\",\n" +
                "    \"type\": \"update\"\n" +
                "  }\n" +
                "]"));

        verify(hueEventListener).onLightOff("/lights/36", "d37eb9c4-d7eb-42ee-9a13-fa9148f8d403");
    }
    
    @Test
    void onMessage_onEvent_forGroup_triggersGroupOn() throws Exception {
        handler.onMessage("", new MessageEvent("[\n"
                + "  {\n"
                + "    \"creationtime\": \"2023-08-15T19:30:41Z\",\n"
                + "    \"data\": [\n"
                + "      {\n"
                + "        \"dimming\": {\n"
                + "          \"brightness\": 52.178\n"
                + "        },\n"
                + "        \"id\": \"14871193-d3a9-4f09-9e7b-9ffacd616799\",\n"
                + "        \"id_v1\": \"/groups/0\",\n"
                + "        \"owner\": {\n"
                + "          \"rid\": \"88d6c1ef-bed4-4b3f-aa36-8081a4367b6c\",\n"
                + "          \"rtype\": \"bridge_home\"\n"
                + "        },\n"
                + "        \"type\": \"grouped_light\"\n"
                + "      },\n"
                + "      {\n"
                + "        \"dimming\": {\n"
                + "          \"brightness\": 100.0\n"
                + "        },\n"
                + "        \"id\": \"560cd8f7-c498-4358-8dba-19734b4173f7\",\n"
                + "        \"id_v1\": \"/groups/13\",\n"
                + "        \"on\": {\n"
                + "          \"on\": true\n"
                + "        },\n"
                + "        \"owner\": {\n"
                + "          \"rid\": \"233f8334-a727-4c5f-a10b-368a5d60f310\",\n"
                + "          \"rtype\": \"room\"\n"
                + "        },\n"
                + "        \"type\": \"grouped_light\"\n"
                + "      }\n"
                + "    ],\n"
                + "    \"id\": \"66e393a8-26e8-459b-9631-0e761db02396\",\n"
                + "    \"type\": \"update\"\n"
                + "  }\n"
                + "]"));
        
        verify(hueEventListener).onLightOn("/groups/13", "560cd8f7-c498-4358-8dba-19734b4173f7");
    }
    
    @Test
    void onMessage_offEvent_forGroup_triggersGroupOff() throws Exception {
        handler.onMessage("", new MessageEvent("[\n"
                + "  {\n"
                + "    \"creationtime\": \"2023-08-15T19:39:48Z\",\n"
                + "    \"data\": [\n"
                + "      {\n"
                + "        \"dimming\": {\n"
                + "          \"brightness\": 48.762142857142855\n"
                + "        },\n"
                + "        \"id\": \"14871193-d3a9-4f09-9e7b-9ffacd616799\",\n"
                + "        \"id_v1\": \"/groups/0\",\n"
                + "        \"owner\": {\n"
                + "          \"rid\": \"88d6c1ef-bed4-4b3f-aa36-8081a4367b6c\",\n"
                + "          \"rtype\": \"bridge_home\"\n"
                + "        },\n"
                + "        \"type\": \"grouped_light\"\n"
                + "      },\n"
                + "      {\n"
                + "        \"dimming\": {\n"
                + "          \"brightness\": 0.0\n"
                + "        },\n"
                + "        \"id\": \"560cd8f7-c498-4358-8dba-19734b4173f7\",\n"
                + "        \"id_v1\": \"/groups/13\",\n"
                + "        \"on\": {\n"
                + "          \"on\": false\n"
                + "        },\n"
                + "        \"owner\": {\n"
                + "          \"rid\": \"233f8334-a727-4c5f-a10b-368a5d60f310\",\n"
                + "          \"rtype\": \"room\"\n"
                + "        },\n"
                + "        \"type\": \"grouped_light\"\n"
                + "      }\n"
                + "    ],\n"
                + "    \"id\": \"99ea7091-a1ce-466a-871d-2d300113f34b\",\n"
                + "    \"type\": \"update\"\n"
                + "  }\n"
                + "]"));
        
        verify(hueEventListener).onLightOff("/groups/13", "560cd8f7-c498-4358-8dba-19734b4173f7");
    }
    
    @Test
    void onMessage_multipleEvents_correctlyParsed() throws Exception {
        handler.onMessage("", new MessageEvent("[\n" +
                "  {\n" +
                "    \"creationtime\": \"2023-07-22T15:01:50Z\",\n" +
                "    \"data\": [\n" +
                "      {\n" +
                "        \"id\": \"d37eb9c4-d7eb-42ee-9a13-fa9148f8d403\",\n" +
                "        \"id_v1\": \"/lights/36\",\n" +
                "        \"on\": {\n" +
                "          \"on\": false\n" +
                "        },\n" +
                "        \"owner\": {\n" +
                "          \"rid\": \"a2cbe749-c227-4038-811d-f62881a9bd9f\",\n" +
                "          \"rtype\": \"device\"\n" +
                "        },\n" +
                "        \"type\": \"light\"\n" +
                "      }\n" +
                "    ],\n" +
                "    \"id\": \"1a8ad68d-ba3d-4676-b7a0-04d6b4611e68\",\n" +
                "    \"type\": \"update\"\n" +
                "  },\n" +
                "  {\n" +
                "    \"creationtime\": \"2023-07-22T15:01:50Z\",\n" +
                "    \"data\": [\n" +
                "      {\n" +
                "        \"id\": \"db1d8ea4-d55d-47bd-b741-aa9d6ac0f0e7\",\n" +
                "        \"id_v1\": \"/lights/38\",\n" +
                "        \"on\": {\n" +
                "          \"on\": true\n" +
                "        },\n" +
                "        \"owner\": {\n" +
                "          \"rid\": \"51f88fb0-c02d-496c-990c-5c1cf16d999d\",\n" +
                "          \"rtype\": \"device\"\n" +
                "        },\n" +
                "        \"type\": \"light\"\n" +
                "      },\n" +
                "      {\n" +
                "        \"dimming\": {\n" +
                "          \"brightness\": 100.0\n" +
                "        },\n" +
                "        \"id\": \"db1d8ea4-d55d-47bd-b741-aa9d6ac0f0e7\",\n" +
                "        \"id_v1\": \"/lights/38\",\n" +
                "        \"owner\": {\n" +
                "          \"rid\": \"51f88fb0-c02d-496c-990c-5c1cf16d999d\",\n" +
                "          \"rtype\": \"device\"\n" +
                "        },\n" +
                "        \"type\": \"light\"\n" +
                "      },\n" +
                "      {\n" +
                "        \"color_temperature\": {\n" +
                "          \"mirek\": 199,\n" +
                "          \"mirek_valid\": true\n" +
                "        },\n" +
                "        \"id\": \"db1d8ea4-d55d-47bd-b741-aa9d6ac0f0e7\",\n" +
                "        \"id_v1\": \"/lights/38\",\n" +
                "        \"owner\": {\n" +
                "          \"rid\": \"51f88fb0-c02d-496c-990c-5c1cf16d999d\",\n" +
                "          \"rtype\": \"device\"\n" +
                "        },\n" +
                "        \"type\": \"light\"\n" +
                "      }\n" +
                "    ],\n" +
                "    \"id\": \"7845f2fc-89bd-4a9e-807a-966f1b0a1f4c\",\n" +
                "    \"type\": \"update\"\n" +
                "  }\n" +
                "]"));

        verify(hueEventListener).onLightOff("/lights/36", "d37eb9c4-d7eb-42ee-9a13-fa9148f8d403");
        verify(hueEventListener).onLightOn("/lights/38", "db1d8ea4-d55d-47bd-b741-aa9d6ac0f0e7");
        verifyNoMoreInteractions(hueEventListener);
    }
}
