package at.sv.hue.api.hue;

import at.sv.hue.api.LightEventListener;
import at.sv.hue.api.SceneEventListener;
import com.launchdarkly.eventsource.MessageEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HueEventHandlerTest {

    @Mock
    private LightEventListener lightEventListener;
    @Mock
    private SceneEventListener sceneEventListener;
    private HueEventHandler handler;

    @BeforeEach
    void setUp() {
        handler = new HueEventHandler(lightEventListener, sceneEventListener);
    }

    @Test
    void onMessage_missingData_noError() throws Exception {
        handler.onMessage("", new MessageEvent("""
                [
                  {
                    "creationtime": "2023-07-22T15:07:28Z",
                    "id": "49b5e23d-d712-48f7-a46c-db5ca9c838fd",
                    "type": "update"
                  }
                ]"""));

        verifyNoInteractions(lightEventListener);
    }

    @Test
    void onMessage_zigbeeConnectionLostEvent_triggersLightOff() throws Exception {
        handler.onMessage("", new MessageEvent("""
                [
                  {
                    "creationtime": "2023-07-22T15:07:28Z",
                    "data": [
                      {
                        "id": "69b2c6bd-48ff-4bab-9f06-37ccd9399f69",
                        "id_v1": "/lights/40",
                        "owner": {
                          "rid": "a3f0be40-e15f-40a2-a9a7-88bdd43b9c78",
                          "rtype": "device"
                        },
                        "status": "connectivity_issue",
                        "type": "zigbee_connectivity"
                      }
                    ],
                    "id": "49b5e23d-d712-48f7-a46c-db5ca9c838fd",
                    "type": "update"
                  }
                ]"""));

        verify(lightEventListener).onLightOff("/lights/40");
    }

    @Test
    void onMessage_zigbeeDisconnected_triggersLightOff() throws Exception {
        handler.onMessage("", new MessageEvent("""
                [
                  {
                    "creationtime": "2023-07-22T15:07:28Z",
                    "data": [
                      {
                        "id": "69b2c6bd-48ff-4bab-9f06-37ccd9399f69",
                        "id_v1": "/lights/40",
                        "owner": {
                          "rid": "a3f0be40-e15f-40a2-a9a7-88bdd43b9c78",
                          "rtype": "device"
                        },
                        "status": "disconnected",
                        "type": "zigbee_connectivity"
                      }
                    ],
                    "id": "49b5e23d-d712-48f7-a46c-db5ca9c838fd",
                    "type": "update"
                  }
                ]"""));

        verify(lightEventListener).onLightOff("/lights/40");
    }

    @Test
    void onMessage_zigbeeConnectionEstablishedEvent_triggersLightOn_setsPhysicalFlag() throws Exception {
        handler.onMessage("", new MessageEvent("""
                [
                  {
                    "creationtime": "2023-07-22T15:12:39Z",
                    "data": [
                      {
                        "id": "60b2bafa-53f4-4062-9f58-253be813b349",
                        "id_v1": "/lights/39",
                        "owner": {
                          "rid": "f36455ed-7b92-4e5a-97ba-73d804ab03da",
                          "rtype": "device"
                        },
                        "status": "connected",
                        "type": "zigbee_connectivity"
                      }
                    ],
                    "id": "6f772cec-7f86-4b99-8629-7f9a285129a5",
                    "type": "update"
                  }
                ]"""));

        verify(lightEventListener).onLightOn("/lights/39", true);
    }

    @Test
    void onMessage_sceneEvents_notifiesEventListener() throws Exception {
        handler.onMessage("", new MessageEvent("""
                [
                  {
                    "creationtime": "2023-07-22T18:57:03Z",
                    "data": [
                      {
                        "id": "b3ac88cb-4590-4b0a-a267-c8473250e955",
                        "id_v1": "/scenes/WKgxJj8-o8y3pH8",
                        "status": {
                          "active": "inactive"
                        },
                        "type": "scene"
                      },
                      {
                        "id": "f9b4085b-1409-4b12-ae0d-4ffe2be28b79",
                        "id_v1": "/scenes/IaVm23klVFrfDGQ",
                        "status": {
                          "active": "static"
                        },
                        "type": "scene"
                      },
                      {
                        "id": "f9b4085b-1409-4b12-ae0d-4ffe2be28b79",
                        "id_v1": "/scenes/eOapgitzaquU3c2",
                        "status": {
                          "active": "dynamic_palette"
                        },
                        "type": "scene"
                      },
                      {
                        "id": "f9b4085b-1409-4b12-ae0d-4ffe2be28b79",
                        "id_v1": "/scenes/v5Q0GsblLw4Ytxx4",
                        "type": "scene"
                      },
                      {
                        "id": "f9b4085b-1409-4b12-ae0d-4ffe2be28b79",
                        "id_v1": "/scenes/45648789711",
                        "status": "connected",
                        "type": "scene"
                      }
                    ],
                    "id": "5cf1f272-c33c-4c6c-9e74-9366cac5d969",
                    "type": "update"
                  }
                ]
                """));

        verify(sceneEventListener).onSceneActivated("/scenes/IaVm23klVFrfDGQ");
        verify(sceneEventListener).onSceneActivated("/scenes/eOapgitzaquU3c2");
        verifyNoInteractions(lightEventListener);
        verifyNoMoreInteractions(sceneEventListener);
    }

    @Test
    void onMessage_onEvent_triggersLightOn() throws Exception {
        handler.onMessage("", new MessageEvent("""
                [
                  {
                    "creationtime": "2023-07-22T15:12:39Z",
                    "data": [
                      {
                        "id": "d37eb9c4-d7eb-42ee-9a13-fa9148f8d403",
                        "id_v1": "/lights/36",
                        "on": {
                          "on": true
                        },
                        "owner": {
                          "rid": "a2cbe749-c227-4038-811d-f62881a9bd9f",
                          "rtype": "device"
                        },
                        "type": "light"
                      }
                    ],
                    "id": "6f772cec-7f86-4b99-8629-7f9a285129a5",
                    "type": "update"
                  }
                ]"""));

        verify(lightEventListener).onLightOn("/lights/36", false);
    }

    @Test
    void onMessage_offEvent_triggersLightOff() throws Exception {
        handler.onMessage("", new MessageEvent("""
                [
                  {
                    "creationtime": "2023-07-22T15:12:39Z",
                    "data": [
                      {
                        "id": "d37eb9c4-d7eb-42ee-9a13-fa9148f8d403",
                        "id_v1": "/lights/36",
                        "on": {
                          "on": false
                        },
                        "owner": {
                          "rid": "a2cbe749-c227-4038-811d-f62881a9bd9f",
                          "rtype": "device"
                        },
                        "type": "light"
                      }
                    ],
                    "id": "6f772cec-7f86-4b99-8629-7f9a285129a5",
                    "type": "update"
                  }
                ]"""));

        verify(lightEventListener).onLightOff("/lights/36");
    }

    @Test
    void onMessage_onEvent_forGroup_triggersGroupOn() throws Exception {
        handler.onMessage("", new MessageEvent("""
                [
                  {
                    "creationtime": "2023-08-15T19:30:41Z",
                    "data": [
                      {
                        "dimming": {
                          "brightness": 52.178
                        },
                        "id": "14871193-d3a9-4f09-9e7b-9ffacd616799",
                        "id_v1": "/groups/0",
                        "owner": {
                          "rid": "88d6c1ef-bed4-4b3f-aa36-8081a4367b6c",
                          "rtype": "bridge_home"
                        },
                        "type": "grouped_light"
                      },
                      {
                        "dimming": {
                          "brightness": 100.0
                        },
                        "id": "560cd8f7-c498-4358-8dba-19734b4173f7",
                        "id_v1": "/groups/13",
                        "on": {
                          "on": true
                        },
                        "owner": {
                          "rid": "233f8334-a727-4c5f-a10b-368a5d60f310",
                          "rtype": "room"
                        },
                        "type": "grouped_light"
                      }
                    ],
                    "id": "66e393a8-26e8-459b-9631-0e761db02396",
                    "type": "update"
                  }
                ]"""));

        verify(lightEventListener).onLightOn("/groups/13", false);
    }

    @Test
    void onMessage_offEvent_forGroup_triggersGroupOff() throws Exception {
        handler.onMessage("", new MessageEvent("""
                [
                  {
                    "creationtime": "2023-08-15T19:39:48Z",
                    "data": [
                      {
                        "dimming": {
                          "brightness": 48.762142857142855
                        },
                        "id": "14871193-d3a9-4f09-9e7b-9ffacd616799",
                        "id_v1": "/groups/0",
                        "owner": {
                          "rid": "88d6c1ef-bed4-4b3f-aa36-8081a4367b6c",
                          "rtype": "bridge_home"
                        },
                        "type": "grouped_light"
                      },
                      {
                        "dimming": {
                          "brightness": 0.0
                        },
                        "id": "560cd8f7-c498-4358-8dba-19734b4173f7",
                        "id_v1": "/groups/13",
                        "on": {
                          "on": false
                        },
                        "owner": {
                          "rid": "233f8334-a727-4c5f-a10b-368a5d60f310",
                          "rtype": "room"
                        },
                        "type": "grouped_light"
                      }
                    ],
                    "id": "99ea7091-a1ce-466a-871d-2d300113f34b",
                    "type": "update"
                  }
                ]"""));

        verify(lightEventListener).onLightOff("/groups/13");
    }

    @Test
    void onMessage_multipleEvents_correctlyParsed() throws Exception {
        handler.onMessage("", new MessageEvent("""
                [
                  {
                    "creationtime": "2023-07-22T15:01:50Z",
                    "data": [
                      {
                        "id": "d37eb9c4-d7eb-42ee-9a13-fa9148f8d403",
                        "id_v1": "/lights/36",
                        "on": {
                          "on": false
                        },
                        "owner": {
                          "rid": "a2cbe749-c227-4038-811d-f62881a9bd9f",
                          "rtype": "device"
                        },
                        "type": "light"
                      }
                    ],
                    "id": "1a8ad68d-ba3d-4676-b7a0-04d6b4611e68",
                    "type": "update"
                  },
                  {
                    "creationtime": "2023-07-22T15:01:50Z",
                    "data": [
                      {
                        "id": "db1d8ea4-d55d-47bd-b741-aa9d6ac0f0e7",
                        "id_v1": "/lights/38",
                        "on": {
                          "on": true
                        },
                        "owner": {
                          "rid": "51f88fb0-c02d-496c-990c-5c1cf16d999d",
                          "rtype": "device"
                        },
                        "type": "light"
                      },
                      {
                        "dimming": {
                          "brightness": 100.0
                        },
                        "id": "db1d8ea4-d55d-47bd-b741-aa9d6ac0f0e7",
                        "id_v1": "/lights/38",
                        "owner": {
                          "rid": "51f88fb0-c02d-496c-990c-5c1cf16d999d",
                          "rtype": "device"
                        },
                        "type": "light"
                      },
                      {
                        "color_temperature": {
                          "mirek": 199,
                          "mirek_valid": true
                        },
                        "id": "db1d8ea4-d55d-47bd-b741-aa9d6ac0f0e7",
                        "id_v1": "/lights/38",
                        "owner": {
                          "rid": "51f88fb0-c02d-496c-990c-5c1cf16d999d",
                          "rtype": "device"
                        },
                        "type": "light"
                      }
                    ],
                    "id": "7845f2fc-89bd-4a9e-807a-966f1b0a1f4c",
                    "type": "update"
                  }
                ]"""));

        verify(lightEventListener).onLightOff("/lights/36");
        verify(lightEventListener).onLightOn("/lights/38", false);
        verifyNoMoreInteractions(lightEventListener);
    }
}
