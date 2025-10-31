package at.sv.hue.api.hue;

import at.sv.hue.api.LightEventListener;
import at.sv.hue.api.ResourceModificationEventListener;
import at.sv.hue.api.SceneEventListener;
import com.launchdarkly.eventsource.MessageEvent;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HueEventHandlerTest {

    @Mock
    private LightEventListener lightEventListener;
    @Mock
    private SceneEventListener sceneEventListener;
    @Mock
    private ResourceModificationEventListener resourceModificationEventListener;
    private HueEventHandler handler;

    @BeforeEach
    void setUp() {
        handler = new HueEventHandler(lightEventListener, sceneEventListener, resourceModificationEventListener);
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

        verify(lightEventListener).onLightOff("69b2c6bd-48ff-4bab-9f06-37ccd9399f69");
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

        verify(lightEventListener).onLightOff("69b2c6bd-48ff-4bab-9f06-37ccd9399f69");
    }

    @Test
    void onMessage_zigbeeConnectionEstablishedEvent_triggersPhysicalOn_usesDeviceId() throws Exception {
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

        verify(lightEventListener).onPhysicalOn("f36455ed-7b92-4e5a-97ba-73d804ab03da");
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
                        "id": "0314f5ad-b424-4f63-aa2e-f55cac83e306",
                        "id_v1": "/scenes/eOapgitzaquU3c2",
                        "status": {
                          "active": "dynamic_palette"
                        },
                        "type": "scene"
                      },
                      {
                        "id": "3df1b8e5-0ab5-4454-94da-c55bf3748713",
                        "id_v1": "/scenes/cul4KMCB2nlJSNPa",
                        "status": {
                          "last_recall": "2025-02-22T08:43:24.151Z"
                        },
                        "type": "scene"
                      },
                      {
                        "id": "618208cb-4c4a-46d5-91bb-e578344013ee",
                        "id_v1": "/scenes/VV3txu0MX7ImDNZ4",
                        "status": {
                          "active": "static",
                          "last_recall": "2025-02-22T09:08:09.193Z"
                        },
                        "type": "scene"
                      },
                      {
                        "id": "098b2c13-6402-447f-ba63-8aab9b1df55b",
                        "id_v1": "/scenes/v5Q0GsblLw4Ytxx4",
                        "type": "scene"
                      },
                      {
                        "id": "0f8adad3-a3e4-48a7-ba31-9082014d9180",
                        "id_v1": "/scenes/45648789711",
                        "status": "connected",
                        "type": "scene"
                      },
                      {
                        "id": "ANOTHER_ID",
                        "status": {
                          "active": "dynamic_palette"
                        },
                        "type": "ANOTHER_TYPE"
                      }
                    ],
                    "id": "5cf1f272-c33c-4c6c-9e74-9366cac5d969",
                    "type": "update"
                  }
                ]
                """));

        verify(sceneEventListener).onSceneActivated("f9b4085b-1409-4b12-ae0d-4ffe2be28b79");
        verify(sceneEventListener).onSceneActivated("0314f5ad-b424-4f63-aa2e-f55cac83e306");
        verify(sceneEventListener).onSceneActivated("618208cb-4c4a-46d5-91bb-e578344013ee");
        verify(sceneEventListener).onSceneActivated("3df1b8e5-0ab5-4454-94da-c55bf3748713");
        verifyNoInteractions(lightEventListener);
        verifyNoMoreInteractions(sceneEventListener);
        verifyResourceModification("ANOTHER_TYPE", "ANOTHER_ID");
        verifyNoMoreInteractions(resourceModificationEventListener); // scene activation alone is not a resource modification
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

        verify(lightEventListener).onLightOn("d37eb9c4-d7eb-42ee-9a13-fa9148f8d403");
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

        verify(lightEventListener).onLightOff("d37eb9c4-d7eb-42ee-9a13-fa9148f8d403");
    }

    @Test
    void onMessage_onEvent_forGroup_triggersGroupOn_usingGroupedLightId() throws Exception {
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

        verify(lightEventListener).onLightOn("560cd8f7-c498-4358-8dba-19734b4173f7");
    }

    @Test
    void onMessage_offEvent_forGroup_triggersGroupOff_usingGroupedLightId() throws Exception {
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
                        "id": "cecb9d02-acd5-4aff-b46d-330f614dd1fb",
                        "id_v1": "/groups/82",
                        "on": {
                          "on": false
                        },
                        "owner": {
                          "rid": "516ed694-6f41-471d-8f4f-57e36bc19d22",
                          "rtype": "zone"
                        },
                        "type": "grouped_light"
                      }
                    ],
                    "id": "99ea7091-a1ce-466a-871d-2d300113f34b",
                    "type": "update"
                  }
                ]"""));

        verify(lightEventListener).onLightOff("cecb9d02-acd5-4aff-b46d-330f614dd1fb");
        verifyResourceModification("grouped_light", "14871193-d3a9-4f09-9e7b-9ffacd616799");
        verifyResourceModification("grouped_light", "cecb9d02-acd5-4aff-b46d-330f614dd1fb");

        verifyNoMoreInteractions(resourceModificationEventListener);
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

        verify(lightEventListener).onLightOff("d37eb9c4-d7eb-42ee-9a13-fa9148f8d403");
        verify(lightEventListener).onLightOn("db1d8ea4-d55d-47bd-b741-aa9d6ac0f0e7");
        verifyResourceModification("light", "d37eb9c4-d7eb-42ee-9a13-fa9148f8d403");
        verify(resourceModificationEventListener, times(3)).onModification(
                eq("light"),
                eq("db1d8ea4-d55d-47bd-b741-aa9d6ac0f0e7"),
                isNotNull()
        );

        verifyNoMoreInteractions(lightEventListener);
        verifyNoMoreInteractions(resourceModificationEventListener);
    }

    @Test
    void onMessage_addZone_detectsResourceModifications_groupedLightAndZone() throws Exception {
        handler.onMessage("", new MessageEvent("""
                [
                  {
                    "creationtime": "2024-07-27T11:22:52Z",
                    "data": [
                      {
                        "alert": {
                          "action_values": []
                        },
                        "dynamics": {},
                        "id": "e4cfe0a4-4630-48fa-a357-750dfb7debe1",
                        "id_v1": "/groups/84",
                        "on": {
                          "on": true
                        },
                        "owner": {
                          "rid": "a2f6a14c-9dd6-4d01-a31d-5a5f2e1d1b11",
                          "rtype": "zone"
                        },
                        "type": "grouped_light"
                      },
                      {
                        "children": [
                          {
                            "rid": "426ab1f6-c27f-42ba-b18d-0783665b4e21",
                            "rtype": "light"
                          }
                        ],
                        "id": "a2f6a14c-9dd6-4d01-a31d-5a5f2e1d1b11",
                        "id_v1": "/groups/84",
                        "metadata": {
                          "archetype": "gym",
                          "name": "Fitnessraum"
                        },
                        "services": [
                          {
                            "rid": "e4cfe0a4-4630-48fa-a357-750dfb7debe1",
                            "rtype": "grouped_light"
                          }
                        ],
                        "type": "zone"
                      }
                    ],
                    "id": "b3c09e32-0e8e-4400-9c60-3d872d60dd07",
                    "type": "add"
                  }
                ]
                """));

        verifyResourceModification("grouped_light", "e4cfe0a4-4630-48fa-a357-750dfb7debe1");
        verifyResourceModification("zone", "a2f6a14c-9dd6-4d01-a31d-5a5f2e1d1b11");

        verify(lightEventListener).onLightOn("e4cfe0a4-4630-48fa-a357-750dfb7debe1");

        verifyNoMoreInteractions(resourceModificationEventListener);
        verifyNoMoreInteractions(lightEventListener);
    }

    @Test
    void onMessage_zoneDeleted_detectsResourceModification() throws Exception {
        handler.onMessage("", new MessageEvent("""
                [
                  {
                    "creationtime": "2024-07-27T11:53:39Z",
                    "data": [
                      {
                        "id": "d74f17b1-bc07-4a13-9986-1e5c95860e17",
                        "id_v1": "/groups/84",
                        "owner": {
                          "rid": "5cee5b29-9feb-4682-8641-7426066e5ba7",
                          "rtype": "zone"
                        },
                        "type": "grouped_light"
                      },
                      {
                        "id": "db1d8ea4-d55d-47bd-b741-aa9d6ac0f0e7",
                        "id_v1": "/lights/38",
                        "owner": {
                          "rid": "51f88fb0-c02d-496c-990c-5c1cf16d999d",
                          "rtype": "device"
                        },
                        "type": "light"
                      },
                      {
                        "id": "5cee5b29-9feb-4682-8641-7426066e5ba7",
                        "id_v1": "/groups/84",
                        "type": "zone"
                      }
                    ],
                    "id": "36b56123-bbe4-4af5-ba4b-d47f8ee3e9f3",
                    "type": "delete"
                  }
                ]
                """));

        verifyResourceRemoval("grouped_light", "d74f17b1-bc07-4a13-9986-1e5c95860e17");
        verifyResourceRemoval("light", "db1d8ea4-d55d-47bd-b741-aa9d6ac0f0e7");
        verifyResourceRemoval("zone", "5cee5b29-9feb-4682-8641-7426066e5ba7");

        verifyNoMoreInteractions(resourceModificationEventListener);
        verifyNoInteractions(lightEventListener);
        verifyNoInteractions(sceneEventListener);
    }

    @Test
    void onMessage_addScene_detectsResourceModification() throws Exception {
        handler.onMessage("", new MessageEvent("""
                [
                  {
                    "creationtime": "2024-07-27T11:26:46Z",
                    "data": [
                      {
                        "actions": [
                          {
                            "action": {
                              "on": {
                                "on": true
                              }
                            },
                            "target": {
                              "rid": "426ab1f6-c27f-42ba-b18d-0783665b4e21",
                              "rtype": "light"
                            }
                          }
                        ],
                        "auto_dynamic": false,
                        "group": {
                          "rid": "a2f6a14c-9dd6-4d01-a31d-5a5f2e1d1b11",
                          "rtype": "zone"
                        },
                        "id": "c1ad153a-b1ca-4250-a352-9dbb6f985586",
                        "id_v1": "/scenes/dUSuLezndk7G9-V7",
                        "metadata": {
                          "image": {
                            "rid": "07591cf1-2594-4d6e-ae07-a032bc101ccc",
                            "rtype": "public_image"
                          },
                          "name": "Krokus"
                        },
                        "palette": {
                          "color": [
                            {
                              "color": {
                                "xy": {
                                  "x": 0.38180000000000014,
                                  "y": 0.4849999999999998
                                }
                              },
                              "dimming": {
                                "brightness": 80.0
                              }
                            },
                            {
                              "color": {
                                "xy": {
                                  "x": 0.41949999999999976,
                                  "y": 0.4215999999999999
                                }
                              },
                              "dimming": {
                                "brightness": 80.0
                              }
                            },
                            {
                              "color": {
                                "xy": {
                                  "x": 0.4211999999999999,
                                  "y": 0.38000000000000006
                                }
                              },
                              "dimming": {
                                "brightness": 80.0
                              }
                            },
                            {
                              "color": {
                                "xy": {
                                  "x": 0.28770000000000007,
                                  "y": 0.2519000000000002
                                }
                              },
                              "dimming": {
                                "brightness": 80.0
                              }
                            },
                            {
                              "color": {
                                "xy": {
                                  "x": 0.2194,
                                  "y": 0.1332
                                }
                              },
                              "dimming": {
                                "brightness": 80.0
                              }
                            }
                          ],
                          "color_temperature": [
                            {
                              "color_temperature": {
                                "mirek": 310
                              },
                              "dimming": {
                                "brightness": 80.0
                              }
                            }
                          ],
                          "dimming": [],
                          "effects": []
                        },
                        "recall": {},
                        "speed": 0.626984126984127,
                        "status": {
                          "active": "inactive"
                        },
                        "type": "scene"
                      }
                    ],
                    "id": "7460698b-9ea8-41fc-bf03-0ad12cfef9ec",
                    "type": "add"
                  }
                ]"""));

        verifyResourceModification("scene", "c1ad153a-b1ca-4250-a352-9dbb6f985586");

        verifyNoInteractions(sceneEventListener);
    }

    @Test
    void onMessage_sceneDeleted_detectsResourceModification() throws Exception {
        handler.onMessage("", new MessageEvent("""
                [
                  {
                    "creationtime": "2024-07-27T13:13:20Z",
                    "data": [
                      {
                        "id": "46e3fb34-1f88-4b0f-b138-e8466b76928d",
                        "id_v1": "/scenes/-cHP3IuHS3Nh8lbA",
                        "type": "scene"
                      }
                    ],
                    "id": "1a7808f8-ac50-4728-8153-f6e7ec7d0517",
                    "type": "delete"
                  }
                ]"""));

        verifyResourceRemoval("scene", "46e3fb34-1f88-4b0f-b138-e8466b76928d");

        verifyNoMoreInteractions(resourceModificationEventListener);
        verifyNoInteractions(lightEventListener);
        verifyNoInteractions(sceneEventListener);
    }

    @Test
    void onMessage_sceneRenamed_detectsResourceModification() throws Exception {
        handler.onMessage("", new MessageEvent("""
                [
                  {
                    "creationtime": "2024-07-27T13:35:43Z",
                    "data": [
                      {
                        "id": "70c6eb7f-7966-4a84-b62e-dd04e0c71ad0",
                        "id_v1": "/scenes/OriVDa3ciDdRKI6T",
                        "metadata": {
                          "name": "Entspannen"
                        },
                        "status": {
                          "active": "inactive"
                        },
                        "type": "scene"
                      }
                    ],
                    "id": "bc3be5e4-f677-452c-9d3b-bb71e36a1358",
                    "type": "update"
                  }
                ]"""));

        verifyResourceModification("scene", "70c6eb7f-7966-4a84-b62e-dd04e0c71ad0");

        verifyNoMoreInteractions(resourceModificationEventListener);
        verifyNoInteractions(lightEventListener);
        verifyNoInteractions(sceneEventListener);
    }

    @Test
    void onMessage_sceneActivated_noSceneResourceModification_justLights() throws Exception {
        handler.onMessage("", new MessageEvent("""
                [
                  {
                    "creationtime": "2024-07-27T13:16:23Z",
                    "data": [
                      {
                        "color": {
                          "xy": {
                            "x": 0.5446,
                            "y": 0.2372
                          }
                        },
                        "color_temperature": {
                          "mirek": null,
                          "mirek_valid": false
                        },
                        "id": "1aa6083d-3692-49e5-92f7-b926b302dd49",
                        "id_v1": "/lights/1",
                        "owner": {
                          "rid": "cf704269-6f65-47f1-8423-677b65d3f874",
                          "rtype": "device"
                        },
                        "service_id": 0,
                        "type": "light"
                      },
                      {
                        "color": {
                          "xy": {
                            "x": 0.5463,
                            "y": 0.2394
                          }
                        },
                        "color_temperature": {
                          "mirek": null,
                          "mirek_valid": false
                        },
                        "id": "5b09ad56-a4bf-4f8a-b978-ad152c6e7751",
                        "id_v1": "/lights/3",
                        "owner": {
                          "rid": "cb3d791f-5a9a-4159-ad4c-b98e74c9cc12",
                          "rtype": "device"
                        },
                        "service_id": 0,
                        "type": "light"
                      }
                    ],
                    "id": "45ef0540-c80f-4cb0-a995-538d70fe0435",
                    "type": "update"
                  },
                  {
                    "creationtime": "2024-07-27T13:16:23Z",
                    "data": [
                      {
                        "id": "3010f4a4-e1f8-42e7-825b-035f36d2ad81",
                        "id_v1": "/scenes/hqhkuieACLDcI0Fg",
                        "status": {
                          "active": "static"
                        },
                        "type": "scene"
                      }
                    ],
                    "id": "136bc929-094b-4db3-a4e3-6edab6f09909",
                    "type": "update"
                  },
                  {
                    "creationtime": "2024-07-27T13:16:23Z",
                    "data": [
                      {
                        "dimming": {
                          "brightness": 91.10653846153846
                        },
                        "id": "1b03060d-1c90-4bb5-92ea-529586aa285b",
                        "id_v1": "/groups/13",
                        "owner": {
                          "rid": "bb82cff1-0ced-4407-b592-aae808ee462b",
                          "rtype": "zone"
                        },
                        "type": "grouped_light"
                      }
                    ],
                    "id": "41ec55a9-23f0-4c25-86d3-fbe57d0f340f",
                    "type": "update"
                  }
                ]"""));

        verify(sceneEventListener).onSceneActivated("3010f4a4-e1f8-42e7-825b-035f36d2ad81");
        verifyResourceModification("light", "1aa6083d-3692-49e5-92f7-b926b302dd49");
        verifyResourceModification("light", "5b09ad56-a4bf-4f8a-b978-ad152c6e7751");
        verifyResourceModification("grouped_light", "1b03060d-1c90-4bb5-92ea-529586aa285b");
        
        verifyNoMoreInteractions(sceneEventListener);
        verifyNoInteractions(lightEventListener);
        verifyNoMoreInteractions(resourceModificationEventListener);
    }

    @Test
    void onMessage_addLightToZone_detectsResourceModification() throws Exception {
        handler.onMessage("", new MessageEvent("""
                [
                  {
                    "creationtime": "2024-07-27T12:46:51Z",
                    "data": [
                      {
                        "dimming": {
                          "brightness": 26.084999999999997
                        },
                        "id": "eeb336d9-243b-4756-8455-1c69f50efd31",
                        "id_v1": "/groups/3",
                        "owner": {
                          "rid": "75ee4e3b-cacb-4b87-923b-d11d2480e8ff",
                          "rtype": "zone"
                        },
                        "signaling": {
                          "signal_values": [
                            "alternating",
                            "no_signal",
                            "on_off",
                            "on_off_color"
                          ]
                        },
                        "type": "grouped_light"
                      },
                      {
                        "children": [
                          {
                            "rid": "1271bf6f-be63-42fc-b18c-3ad462914d8e",
                            "rtype": "light"
                          },
                          {
                            "rid": "1aa6083d-3692-49e5-92f7-b926b302dd49",
                            "rtype": "light"
                          },
                          {
                            "rid": "5b09ad56-a4bf-4f8a-b978-ad152c6e7751",
                            "rtype": "light"
                          }
                        ],
                        "id": "75ee4e3b-cacb-4b87-923b-d11d2480e8ff",
                        "id_v1": "/groups/3",
                        "type": "zone"
                      },
                      {
                        "actions": [
                          {
                            "action": {
                              "color": {
                                "xy": {
                                  "x": 0.5451999999999999,
                                  "y": 0.2408
                                }
                              },
                              "dimming": {
                                "brightness": 7.51
                              },
                              "on": {
                                "on": true
                              }
                            },
                            "target": {
                              "rid": "1aa6083d-3692-49e5-92f7-b926b302dd49",
                              "rtype": "light"
                            }
                          },
                          {
                            "action": {
                              "color": {
                                "xy": {
                                  "x": 0.2573,
                                  "y": 0.10289999999999998
                                }
                              },
                              "dimming": {
                                "brightness": 7.51
                              },
                              "on": {
                                "on": true
                              }
                            },
                            "target": {
                              "rid": "1271bf6f-be63-42fc-b18c-3ad462914d8e",
                              "rtype": "light"
                            }
                          },
                          {
                            "action": {
                              "color": {
                                "xy": {
                                  "x": 0.2573,
                                  "y": 0.10289999999999998
                                }
                              },
                              "dimming": {
                                "brightness": 7.51
                              },
                              "on": {
                                "on": true
                              }
                            },
                            "target": {
                              "rid": "5b09ad56-a4bf-4f8a-b978-ad152c6e7751",
                              "rtype": "light"
                            }
                          }
                        ],
                        "id": "f6b7b1ee-31e1-4a24-b848-376a5dd6e2d4",
                        "id_v1": "/scenes/Otl-sz5X5cmce0IX",
                        "type": "scene"
                      }
                    ],
                    "id": "e5302581-1b12-404b-a8b1-e69c25b3dd48",
                    "type": "update"
                  }
                ]"""));

        verifyResourceModification("zone", "75ee4e3b-cacb-4b87-923b-d11d2480e8ff");
        verifyResourceModification("scene", "f6b7b1ee-31e1-4a24-b848-376a5dd6e2d4");
        verifyResourceModification("grouped_light", "eeb336d9-243b-4756-8455-1c69f50efd31");

        verifyNoMoreInteractions(resourceModificationEventListener);
        verifyNoInteractions(sceneEventListener);
    }

    @Test
    void onMessage_deviceRenamed_detectsModification() throws Exception {
        handler.onMessage("", new MessageEvent("""
                [
                  {
                    "creationtime": "2024-07-27T11:32:28Z",
                    "data": [
                      {
                        "id": "23e0ec8f-f096-4e9c-a6d5-4d7efe98ace6",
                        "id_v1": "/lights/32",
                        "metadata": {
                          "name": "Ventilator (Neu)"
                        },
                        "type": "device"
                      }
                    ],
                    "id": "4ca2db02-8edd-4b96-b153-d52e4dae4698",
                    "type": "update"
                  }
                ]"""));

        verifyResourceModification("device", "23e0ec8f-f096-4e9c-a6d5-4d7efe98ace6");

        verifyNoMoreInteractions(resourceModificationEventListener);
    }

    private void verifyResourceModification(String type, String id) {
        verifyResourceModification(type, id, notNullValue());
    }

    private void verifyResourceRemoval(String type, String id) {
        verifyResourceModification(type, id, nullValue());
    }

    private <T> void verifyResourceModification(String type, String id, Matcher<T> contentMatcher) {
        verify(resourceModificationEventListener).onModification(
                eq(type),
                eq(id),
                argThat(contentMatcher::matches)
        );
    }
}
