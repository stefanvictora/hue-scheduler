package at.sv.hue.api.hue;

import at.sv.hue.api.ApiFailure;
import at.sv.hue.api.BridgeAuthenticationFailure;
import at.sv.hue.api.BridgeConnectionFailure;
import at.sv.hue.api.Capability;
import at.sv.hue.api.EmptyGroupException;
import at.sv.hue.api.GroupNotFoundException;
import at.sv.hue.api.HttpResourceProvider;
import at.sv.hue.api.HueApi;
import at.sv.hue.api.InvalidConnectionException;
import at.sv.hue.api.LightCapabilities;
import at.sv.hue.api.LightNotFoundException;
import at.sv.hue.api.LightState;
import at.sv.hue.api.PutCall;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HueApiTest {
    private static final Double[][] GAMUT_A = new Double[][]{{0.704, 0.296}, {0.2151, 0.7106}, {0.138, 0.08}};
    private static final Double[][] GAMUT_B = new Double[][]{{0.675, 0.322}, {0.409, 0.518}, {0.167, 0.04}};
    private static final Double[][] GAMUT_C = new Double[][]{{0.6915, 0.3083}, {0.17, 0.7}, {0.1532, 0.0475}};
    private HueApi api;
    private String baseUrl;
    private HttpResourceProvider resourceProviderMock;

    @BeforeEach
    void setUp() {
        String host = "localhost";
        String accessToken = "token";
        resourceProviderMock = Mockito.mock(HttpResourceProvider.class);
        api = new HueApiImpl(resourceProviderMock, host, accessToken, permits -> {
        });
        baseUrl = "https://" + host + "/api/" + accessToken;
    }

    @Test
    void invalidHost_cantUseScheme_exception() {
        assertThrows(InvalidConnectionException.class, () -> new HueApiImpl(resourceProviderMock, "hTtps://localhost", "TOKEN", permits -> {
        }));
    }

    @Test
    void checkConnection_unauthorizedUser_exception() {
        setGetResponse("/lights", """
                [
                  {
                    "error": {
                      "type": 1,
                      "address": "/lights",
                      "description": "unauthorized user"
                    }
                  }
                ]""");

        assertThrows(BridgeAuthenticationFailure.class, () -> api.assertConnection());
    }

    @Test
    void getState_networkFailure_exception() {
        when(resourceProviderMock.getResource(any())).thenThrow(new BridgeConnectionFailure("Failed"));

        assertThrows(BridgeConnectionFailure.class, () -> getLightState("/lights/1"));
    }

    @Test
    void getState_unknownResourceError_exception() {
        setGetResponse("/lights/1", """
                [
                  {
                    "error": {
                      "type": 3,
                      "address": "/lights/1/state",
                      "description": "resource, /lights/1/state, not available"
                    }
                  }
                ]""");

        assertThrows(ApiFailure.class, () -> getLightState("/lights/1"), "resource, /lights/1/state, not available");
    }

    @Test
    void getState_anyOtherError_exception() {
        setGetResponse("/lights/1", """
                [
                  {
                    "error": {
                      "type": 1000,
                      "address": "/lights/1/state",
                      "description": "any other error"
                    }
                  }
                ]""");

        assertThrows(ApiFailure.class, () -> getLightState("/lights/1"), "any other error");
    }

    @Test
    void getState_emptyResponse_exception() {
        setGetResponse("/lights/1", "");

        assertThrows(ApiFailure.class, () -> getLightState("/lights/1"));
    }

    @Test
    void getState_emptyJSON_exception() {
        setGetResponse("/lights/1", "{}");

        assertThrows(ApiFailure.class, () -> getLightState("/lights/1"));
    }

    @Test
    void getState_returnsLightState_callsCorrectApiURL() {
        int lightId = 22;
        setGetResponse("/lights/" + lightId, """
                {
                  "state": {
                    "on": true,
                    "bri": 100,
                    "hue": 979,
                    "sat": 254,
                    "effect": "colorloop",
                    "xy": [
                      0.6715,
                      0.3233
                    ],
                    "ct": 153,
                    "reachable": true
                  },
                  "name": "Name 2",
                  "type": "Extended color light"
                }
                """);

        LightState lightState = getLightState("/lights/" + lightId);

        assertLightState(
                lightState, LightState
                        .builder()
                        .on(true)
                        .reachable(true)
                        .x(0.6715)
                        .y(0.3233)
                        .colorTemperature(153)
                        .brightness(100)
                        .effect("colorloop")
                        .lightCapabilities(LightCapabilities.builder()
                                                            .capabilities(EnumSet.allOf(Capability.class))
                                                            .build())
                        .build()
        );
    }

    @Test
    void getState_differentResult_correctState() {
        int lightId = 11;
        setGetResponse("/lights/" + lightId, """
                {
                  "state": {
                    "on": false,
                    "bri": 41,
                    "hue": 6000,
                    "sat": 157,
                    "effect": "none",
                    "xy": [
                      0.5111,
                      0.1132
                    ],
                    "ct": 100,
                    "reachable": false
                  },
                  "name": "Name 2",
                  "type": "Extended color light"
                }
                """);

        LightState lightState = getLightState("/lights/" + lightId);

        assertLightState(lightState, LightState
                .builder()
                .on(false)
                .reachable(false)
                .x(0.5111)
                .y(0.1132)
                .colorTemperature(100)
                .brightness(41)
                .effect("none")
                .lightCapabilities(LightCapabilities.builder()
                                                    .capabilities(EnumSet.allOf(Capability.class))
                                                    .build())
                .build()
        );
    }

    @Test
    void getState_whiteBulbOnly_noNullPointerException() {
        int lightId = 11;
        setGetResponse("/lights/" + lightId, """
                {
                  "state": {
                    "on": true,
                    "bri": 41,
                    "reachable": true
                  },
                  "name": "Name 2",
                  "type": "Dimmable light"
                }""");

        LightState lightState = getLightState("/lights/" + lightId);

        assertLightState(lightState, LightState
                .builder()
                .on(true)
                .reachable(true)
                .brightness(41)
                .lightCapabilities(LightCapabilities.builder()
                                                    .capabilities(EnumSet.of(Capability.BRIGHTNESS, Capability.ON_OFF))
                                                    .build())
                .build());
    }

    @Test
    void getState_onOffBulbOnly_noNullPointerException() {
        int lightId = 11;
        setGetResponse("/lights/" + lightId, """
                {
                  "state": {
                    "on": true,
                    "reachable": true
                  },
                  "name": "Name 2",
                  "type": "On/Off plug-in unit"
                }""");

        LightState lightState = getLightState("/lights/" + lightId);

        assertLightState(lightState, LightState
                .builder()
                .on(true)
                .reachable(true)
                .lightCapabilities(LightCapabilities.builder()
                                                    .capabilities(EnumSet.of(Capability.ON_OFF))
                                                    .build())
                .build());
    }

    @Test
    void getGroupState_returnsListOfLightStates_ignoresUnknownLights() {
        setGetResponse("/groups", """
                {
                  "17": {
                    "name": "Group 17",
                    "lights": [
                      "8",
                      "16",
                      "21",
                      "777"
                    ]
                  },
                  "18": {
                    "name": "Group 18",
                    "lights": [
                      "24"
                    ]
                  }
                }""");
        setGetResponse("/lights", """
                {
                  "8": {
                    "state": {
                      "on": true,
                      "bri": 43,
                      "hue": 692,
                      "sat": 204,
                      "effect": "none",
                      "xy": [
                        0.6189,
                        0.3303
                      ],
                      "ct": 500,
                      "alert": "select",
                      "colormode": "xy",
                      "mode": "homeautomation",
                      "reachable": true
                    },
                    "type": "Extended color light",
                    "capabilities": {
                      "certified": true,
                      "control": {
                        "mindimlevel": 40,
                        "maxlumen": 1600,
                        "colorgamuttype": "C",
                        "colorgamut": [
                          [
                            0.6915,
                            0.3083
                          ],
                          [
                            0.17,
                            0.7
                          ],
                          [
                            0.1532,
                            0.0475
                          ]
                        ],
                        "ct": {
                          "min": 153,
                          "max": 500
                        }
                      }
                    }
                  },
                  "16": {
                    "state": {
                      "on": false,
                      "bri": 127,
                      "alert": "select",
                      "mode": "homeautomation",
                      "reachable": true
                    },
                    "type": "Dimmable light",
                    "capabilities": {
                      "certified": true,
                      "control": {
                        "mindimlevel": 5000,
                        "maxlumen": 800
                      }
                    }
                  },
                  "21": {
                    "state": {
                      "on": false,
                      "bri": 184,
                      "ct": 366,
                      "alert": "select",
                      "colormode": "ct",
                      "mode": "homeautomation",
                      "reachable": true
                    },
                    "type": "Color temperature light",
                    "capabilities": {
                      "certified": true,
                      "control": {
                        "mindimlevel": 200,
                        "maxlumen": 350,
                        "ct": {
                          "min": 153,
                          "max": 454
                        }
                      }
                    }
                  },
                  "24": {
                    "state": {
                      "on": false,
                      "alert": "select",
                      "mode": "homeautomation",
                      "reachable": true
                    },
                    "type": "On/Off plug-in unit",
                    "capabilities": {
                      "certified": true,
                      "control": {}
                    }
                  }
                }""");

        assertThat(getGroupStates("/groups/17")).containsExactly(
                LightState.builder()
                          .on(true)
                          .brightness(43)
                          .effect("none")
                          .x(0.6189)
                          .y(0.3303)
                          .colorTemperature(500)
                          .colormode("xy")
                          .reachable(true)
                          .lightCapabilities(LightCapabilities.builder()
                                                              .colorGamutType("C")
                                                              .colorGamut(GAMUT_C)
                                                              .ctMin(153)
                                                              .ctMax(500)
                                                              .capabilities(EnumSet.allOf(Capability.class))
                                                              .build())
                          .build(),
                LightState.builder()
                          .on(false)
                          .brightness(127)
                          .reachable(true)
                          .lightCapabilities(LightCapabilities.builder()
                                                              .capabilities(EnumSet.of(Capability.BRIGHTNESS, Capability.ON_OFF))
                                                              .build())
                          .build(),
                LightState.builder()
                          .on(false)
                          .brightness(184)
                          .colorTemperature(366)
                          .colormode("ct")
                          .reachable(true)
                          .lightCapabilities(LightCapabilities.builder()
                                                              .ctMin(153)
                                                              .ctMax(454)
                                                              .capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE,
                                                                      Capability.BRIGHTNESS, Capability.ON_OFF))
                                                              .build())
                          .build()
        );

        assertThat(getGroupStates("/groups/18")).containsExactly(
                LightState.builder()
                          .on(false)
                          .reachable(true)
                          .lightCapabilities(LightCapabilities.builder()
                                                              .capabilities(EnumSet.of(Capability.ON_OFF))
                                                              .build())
                          .build()
        );
    }

    @Test
    void getGroupLights_returnsLightIdsForGroup_reusesSameForName() {
        setGetResponse("/groups", """
                {
                  "1": {
                    "name": "Group 1",
                    "lights": [
                      "1",
                      "2",
                      "3"
                    ]
                  },
                  "2": {
                    "name": "Group 2",
                    "lights": [
                      "4",
                      "5"
                    ]
                  }}""");

        assertThat(getGroupLights("/groups/2")).containsExactly("/lights/4", "/lights/5");
        assertThat(getGroupName("/groups/2")).isEqualTo("Group 2");
    }

    @Test
    void getAffectedIdsByScene_returnsLightIdsFor_empty_ignoredAffectedByScene() {
        setGetResponse("/scenes", """
                {
                }
                """);

        List<String> sceneLights = api.getAffectedIdsByScene("/scene/12345ABC");

        assertThat(sceneLights).isEmpty();
    }

    @Test
    void getAffectedIdsByScene_returnsLightIdsAndGroupIdFor_correctIdsAffectedByScene() {
        setGetResponse("/scenes", """
                {
                  "8V1DI3muI6bSns1": {
                    "name": "Video Chat",
                    "type": "LightScene",
                    "lights": [
                      "6",
                      "19",
                      "47"
                    ],
                    "owner": "9e3ebcc4-2ead-4b29-8382-eb27f459fb53",
                    "recycle": false,
                    "locked": false,
                    "appdata": {
                      "version": 1,
                      "data": "E5FD4543:AA01000"
                    },
                    "picture": "",
                    "lastupdated": "2023-04-27T20:17:21",
                    "version": 2
                  },
                  "bqd6wEsNlIHQ2-b": {
                    "name": "Soft Chill",
                    "type": "LightScene",
                    "lights": [
                      "1",
                      "2",
                      "3"
                    ],
                    "owner": "9e3ebcc4-2ead-4b29-8382-eb27f459fb53",
                    "recycle": false,
                    "locked": false,
                    "appdata": {
                      "version": 1,
                      "data": "A32B0391:AA01000"
                    },
                    "picture": "",
                    "lastupdated": "2023-04-27T20:17:21",
                    "version": 2
                  },
                  "4yoLHdxJsSHArWN": {
                    "name": "Tag",
                    "type": "GroupScene",
                    "group": "13",
                    "lights": [
                      "46",
                      "47"
                    ],
                    "owner": "9e3ebcc4-2ead-4b29-8382-eb27f459fb53",
                    "recycle": false,
                    "locked": true,
                    "appdata": {
                      "version": 0,
                      "data": "AF5B49B3:AB0D000"
                    },
                    "picture": "",
                    "lastupdated": "2024-01-18T06:33:46",
                    "version": 2
                  }
                }
                """);

        assertThat(api.getAffectedIdsByScene("/scenes/8V1DI3muI6bSns1")).containsExactly("/lights/6", "/lights/19", "/lights/47");
        assertThat(api.getAffectedIdsByScene("/scenes/bqd6wEsNlIHQ2-b")).containsExactly("/lights/1", "/lights/2", "/lights/3");
        assertThat(api.getAffectedIdsByScene("/scenes/4yoLHdxJsSHArWN")).containsExactly("/lights/46", "/lights/47", "/groups/13");
    }

    @Test
    void getAssignedGroups_givenLightId_returnsGroupIds() {
        setGetResponse("/groups", """
                {
                  "1": {
                    "name": "Group 1",
                    "lights": [
                      "1",
                      "2",
                      "3"
                    ]
                  },
                  "2": {
                    "name": "Group 2",
                    "lights": [
                      "4",
                      "5",
                      "3"
                    ]
                  }}""");

        assertThat(getAssignedGroups("/lights/2")).containsExactly("/groups/1");
        assertThat(getAssignedGroups("/lights/3")).containsExactlyInAnyOrder("/groups/1", "/groups/2");
        assertThat(getAssignedGroups("/lights/777")).isEmpty();
        api.clearCaches();
        assertThat(getAssignedGroups("/lights/2")).containsExactly("/groups/1");
        assertThat(getAssignedGroups("/lights/2")).containsExactly("/groups/1");
        verify(resourceProviderMock, times(2)).getResource(any());
    }

    @Test
    void getGroupLights_emptyLights_exception() {
        setGetResponse("/groups", """
                {
                  "1": {
                    "name": "Group 1",
                    "lights": [
                    ]
                  }
                }""");

        assertThrows(EmptyGroupException.class, () -> getGroupLights("/groups/1"));
    }

    @Test
    void getGroupLights_unknownId_exception() {
        setGetResponse("/groups", "{}");

        assertThrows(GroupNotFoundException.class, () -> getGroupLights("/groups/1234"));
    }

    @Test
    void getGroupLights_emptyResponse_exception() {
        setGetResponse("/groups", "");

        assertThrows(ApiFailure.class, () -> getGroupLights("/groups/1"));
    }

    @Test
    void getGroupName_returnsNameForGroupId() {
        setGetResponse("/groups", """
                {
                  "1": {
                    "name": "Group 1",
                    "lights": [
                      "1",
                      "2",
                      "3"
                    ]
                  },
                  "2": {
                    "name": "Group 2",
                    "lights": [
                      "4",
                      "5"
                    ]
                  }}""");

        String name1 = getGroupName("/groups/1");
        String name2 = getGroupName("/groups/2");

        assertThat(name1).isEqualTo("Group 1");
        assertThat(name2).isEqualTo("Group 2");
    }

    @Test
    void getGroupName_unknownId_exception() {
        setGetResponse("/groups", "{}");

        assertThrows(GroupNotFoundException.class, () -> getGroupName("/groups/1234"));
    }

    @Test
    void getGroupName_emptyResponse_exception() {
        setGetResponse("/groups", "");

        assertThrows(ApiFailure.class, () -> getGroupName("/groups/1"));
    }

    @Test
    void getLightId_returnsIdForLightName_reusesResponseForMultipleRequest() {
        setGetResponse("/lights", """
                {
                  "7": {
                    "name": "Lamp 1"
                  },
                  "1234": {
                    "name": "Lamp 2"
                  }
                }""");

        assertThat(api.getLightId("Lamp 1")).isEqualTo("/lights/7");
        assertThat(api.getLightId("Lamp 2")).isEqualTo("/lights/1234");
        verify(resourceProviderMock).getResource(any());
        api.clearCaches();
        assertThat(api.getLightId("Lamp 1")).isEqualTo("/lights/7");
        assertThat(api.getLightId("Lamp 1")).isEqualTo("/lights/7");
        verify(resourceProviderMock, times(2)).getResource(any());
    }

    @Test
    void getLightId_unknownName_exception() {
        setGetResponse("/lights", "{}");

        assertThrows(LightNotFoundException.class, () -> api.getLightId("Lamp"));
    }

    @Test
    void getLightId_emptyResponse_exception() {
        setGetResponse("/lights", "");

        assertThrows(ApiFailure.class, () -> api.getLightId("Lamp"));
    }

    @Test
    void getGroupId_returnsIdForGroupName_reusesResponse() {
        setGetResponse("/groups", """
                {
                  "11": {
                    "name": "Group 1",
                    "lights": [
                      "1",
                      "2"
                    ]
                  },
                  "789": {
                    "name": "Group 2",
                    "lights": [
                      "3",
                      "4"
                    ]
                  }
                }""");

        assertThat(getGroupId("Group 1")).isEqualTo("/groups/11");
        assertThat(getGroupId("Group 2")).isEqualTo("/groups/789");
        verify(resourceProviderMock).getResource(any());
        api.clearCaches();
        assertThat(getGroupId("Group 1")).isEqualTo("/groups/11");
        assertThat(getGroupId("Group 1")).isEqualTo("/groups/11");
        verify(resourceProviderMock, times(2)).getResource(any());
    }

    @Test
    void getGroupId_unknownName_exception() {
        setGetResponse("/groups", "{}");

        assertThrows(GroupNotFoundException.class, () -> getGroupId("Unknown Group"));
    }

    @Test
    void getGroupId_emptyResponse_exception() {
        setGetResponse("/groups", "");

        assertThrows(ApiFailure.class, () -> getGroupId("Group"));
    }

    @Test
    void getLightName_returnsNameForLightId() {
        setGetResponse("/lights", """
                {
                  "7": {
                    "name": "Lamp 1"
                  },
                  "1234": {
                    "name": "Lamp 2"
                  }
                }""");

        assertThat(getLightName("/lights/7")).isEqualTo("Lamp 1");
        assertThat(getLightName("/lights/1234")).isEqualTo("Lamp 2");
        verify(resourceProviderMock).getResource(any());
        api.clearCaches();
        assertThat(getLightName("/lights/7")).isEqualTo("Lamp 1");
        assertThat(getLightName("/lights/7")).isEqualTo("Lamp 1");
        verify(resourceProviderMock, times(2)).getResource(any());
    }

    @Test
    void getLightName_unknownId_exception() {
        setGetResponse("/lights", "{}");

        assertThrows(LightNotFoundException.class, () -> getLightName("/lights/1234"));
    }

    @Test
    void getLightName_emptyResponse_exception() {
        setGetResponse("/lights", "");

        assertThrows(ApiFailure.class, () -> getLightName("/lights/2"));
    }

    @Test
    void getCapabilities_hasColorAndCtSupport() {
        setGetResponse("/lights", """
                {
                  "22": {
                    "type": "Extended color light",
                    "capabilities": {
                      "control": {
                        "mindimlevel": 200,
                        "maxlumen": 800,
                        "colorgamuttype": "C",
                        "colorgamut": [
                          [
                            0.6915,
                            0.3083
                          ],
                          [
                            0.17,
                            0.7
                          ],
                          [
                            0.1532,
                            0.0475
                          ]
                        ],
                        "ct": {
                          "min": 153,
                          "max": 500
                        }
                      }
                    }
                  }
                }""");

        LightCapabilities capabilities = getLightCapabilities("/lights/22");

        assertCapabilities(
                capabilities,
                LightCapabilities.builder()
                                 .colorGamutType("C")
                                 .colorGamut(GAMUT_C)
                                 .ctMin(153)
                                 .ctMax(500)
                                 .capabilities(EnumSet.allOf(Capability.class))
                                 .build()
        );
        assertThat(capabilities.isColorSupported()).isTrue();
        assertThat(capabilities.isCtSupported()).isTrue();
        assertThat(capabilities.isBrightnessSupported()).isTrue();
    }

    @Test
    void getCapabilities_colorOnly() {
        setGetResponse("/lights", """
                {
                  "22": {
                    "type": "Color light",
                    "capabilities": {
                      "control": {
                        "mindimlevel": 200,
                        "maxlumen": 800,
                        "colorgamuttype": "C",
                        "colorgamut": [
                          [
                            0.6915,
                            0.3083
                          ],
                          [
                            0.17,
                            0.7
                          ],
                          [
                            0.1532,
                            0.0475
                          ]
                        ]
                      }
                    }
                  }
                }""");

        LightCapabilities capabilities = getLightCapabilities("/lights/22");

        assertCapabilities(
                capabilities,
                LightCapabilities.builder()
                                 .colorGamutType("C")
                                 .colorGamut(GAMUT_C)
                                 .capabilities(EnumSet.of(Capability.COLOR, Capability.BRIGHTNESS, Capability.ON_OFF))
                                 .build()
        );
        assertThat(capabilities.isColorSupported()).isTrue();
        assertThat(capabilities.isCtSupported()).isFalse();
        assertThat(capabilities.isBrightnessSupported()).isTrue();
    }

    @Test
    void getCapabilities_brightnessOnly() {
        setGetResponse("/lights", """
                {
                  "7": {
                    "type": "Dimmable light",
                    "capabilities": {
                      "control": {
                        "mindimlevel": 5000,
                        "maxlumen": 1600
                      }
                    }
                  }
                }""");

        LightCapabilities capabilities = getLightCapabilities("/lights/7");

        assertCapabilities(capabilities, LightCapabilities.builder().capabilities(EnumSet.of(Capability.BRIGHTNESS, Capability.ON_OFF)).build());
        assertThat(capabilities.isColorSupported()).isFalse();
        assertThat(capabilities.isCtSupported()).isFalse();
        assertThat(capabilities.isBrightnessSupported()).isTrue();
    }

    @Test
    void getCapabilities_colorTemperatureOnly() {
        setGetResponse("/lights", """
                {
                  "42": {
                    "state": {
                      "on": true,
                      "bri": 254,
                      "ct": 408,
                      "alert": "select",
                      "colormode": "ct",
                      "mode": "homeautomation",
                      "reachable": false
                    },
                    "type": "Color temperature light",
                    "name": "Spot",
                    "modelid": "LTA001",
                    "manufacturername": "Signify Netherlands B.V.",
                    "productname": "Hue ambiance lamp",
                    "capabilities": {
                      "certified": true,
                      "control": {
                        "mindimlevel": 200,
                        "maxlumen": 800,
                        "ct": {
                          "min": 153,
                          "max": 454
                        }
                      },
                      "streaming": {
                        "renderer": false,
                        "proxy": false
                      }
                    }
                  }
                }""");

        LightCapabilities capabilities = getLightCapabilities("/lights/42");

        assertCapabilities(
                capabilities,
                LightCapabilities.builder()
                                 .ctMin(153)
                                 .ctMax(454)
                                 .capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE, Capability.BRIGHTNESS, Capability.ON_OFF)).build()
        );
        assertThat(capabilities.isColorSupported()).isFalse();
        assertThat(capabilities.isCtSupported()).isTrue();
        assertThat(capabilities.isBrightnessSupported()).isTrue();
    }

    @Test
    void getCapabilities_onOffOnly() {
        setGetResponse("/lights", """
                {
                  "24": {
                    "state": {
                      "on": false,
                      "alert": "select",
                      "mode": "homeautomation",
                      "reachable": true
                    },
                    "type": "On/Off plug-in unit",
                    "name": "Smart Plug",
                    "modelid": "LOM001",
                    "manufacturername": "Signify Netherlands B.V.",
                    "productname": "Hue Smart plug",
                    "capabilities": {
                      "certified": true,
                      "control": {},
                      "streaming": {
                        "renderer": false,
                        "proxy": false
                      }
                    }
                  }
                }""");

        LightCapabilities capabilities = getLightCapabilities("/lights/24");

        assertCapabilities(capabilities, LightCapabilities.builder().capabilities(EnumSet.of(Capability.ON_OFF)).build());
        assertThat(capabilities.isColorSupported()).isFalse();
        assertThat(capabilities.isCtSupported()).isFalse();
        assertThat(capabilities.isBrightnessSupported()).isFalse();
    }

    @Test
    void getCapabilities_unknownId_exception() {
        setGetResponse("/lights", "{}");

        assertThrows(LightNotFoundException.class, () -> getLightCapabilities("/lights/1234"));
    }

    @Test
    void getGroupCapabilities_returnsMaxOfAllContainedLights() {
        setGetResponse("/lights", """
                {
                  "42": {
                    "type": "Color temperature light",
                    "capabilities": {
                      "certified": true,
                      "control": {
                        "mindimlevel": 200,
                        "maxlumen": 800,
                        "ct": {
                          "min": 100,
                          "max": 454
                        }
                      }
                    }
                  },
                  "22": {
                    "type": "Color light",
                    "capabilities": {
                      "control": {
                        "mindimlevel": 200,
                        "maxlumen": 800,
                        "colorgamuttype": "C",
                        "colorgamut": [
                          [
                            0.6915,
                            0.3083
                          ],
                          [
                            0.17,
                            0.7
                          ],
                          [
                            0.1532,
                            0.0475
                          ]
                        ]
                      }
                    }
                  },
                  "23": {
                    "type": "Color light",
                    "capabilities": {
                      "control": {
                        "mindimlevel": 200,
                        "maxlumen": 800,
                        "colorgamuttype": "A",
                        "colorgamut": [
                          [
                            0.704,
                            0.296
                          ],
                          [
                            0.2151,
                            0.7106
                          ],
                          [
                            0.138,
                            0.08
                          ]
                        ]
                      }
                    }
                  },
                  "24": {
                    "type": "Color light",
                    "capabilities": {
                      "control": {
                        "mindimlevel": 200,
                        "maxlumen": 800,
                        "colorgamuttype": "B",
                        "colorgamut": [
                          [
                            0.675,
                            0.322
                          ],
                          [
                            0.409,
                            0.518
                          ],
                          [
                            0.167,
                            0.04
                          ]
                        ]
                      }
                    }
                  },
                  "25": {
                    "type": "Extended color light",
                    "capabilities": {
                      "certified": true,
                      "control": {
                        "mindimlevel": 40,
                        "maxlumen": 1600,
                        "colorgamuttype": "C",
                        "colorgamut": [
                          [
                            0.6915,
                            0.3083
                          ],
                          [
                            0.17,
                            0.7
                          ],
                          [
                            0.1532,
                            0.0475
                          ]
                        ],
                        "ct": {
                          "min": 153,
                          "max": 500
                        }
                      }
                    }
                  },
                  "30": {
                    "type": "On/Off plug-in unit",
                    "capabilities": {
                      "certified": true,
                      "control": {}
                    }
                  }
                }""");
        setGetResponse("/groups", """
                {
                  "1": {
                    "name": "Group 1",
                    "lights": [
                      "42",
                      "22",
                      "30",
                      "25"
                    ]
                  },
                  "2": {
                    "name": "Group 2",
                    "lights": [
                      "22"
                    ]
                  },
                  "3": {
                    "name": "Group 3",
                    "lights": [
                      "42"
                    ]
                  },
                  "4": {
                    "name": "Group 4",
                    "lights": [
                      "30"
                    ]
                  },
                  "5": {
                    "name": "Group 5",
                    "lights": [
                      "23"
                    ]
                  },
                  "6": {
                    "name": "Group 6",
                    "lights": [
                      "22",
                      "23"
                    ]
                  },
                  "7": {
                    "name": "Group 7",
                    "lights": [
                      "24"
                    ]
                  }
                }""");

        assertCapabilities(
                getGroupCapabilities("/groups/1"),
                LightCapabilities.builder()
                                 .colorGamut(GAMUT_C)
                                 .ctMin(100)
                                 .ctMax(500)
                                 .capabilities(EnumSet.allOf(Capability.class))
                                 .build()
        );
        assertCapabilities(
                getGroupCapabilities("/groups/2"),
                LightCapabilities.builder()
                                 .colorGamut(GAMUT_C)
                                 .capabilities(EnumSet.of(Capability.COLOR, Capability.BRIGHTNESS, Capability.ON_OFF))
                                 .build());
        assertCapabilities(
                getGroupCapabilities("/groups/3"),
                LightCapabilities.builder()
                                 .ctMin(100)
                                 .ctMax(454)
                                 .capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE, Capability.BRIGHTNESS, Capability.ON_OFF))
                                 .build()
        );
        assertCapabilities(
                getGroupCapabilities("/groups/4"),
                LightCapabilities.builder()
                                 .capabilities(EnumSet.of(Capability.ON_OFF))
                                 .build()
        );
        assertCapabilities(
                getGroupCapabilities("/groups/5"),
                LightCapabilities.builder()
                                 .colorGamut(GAMUT_A)
                                 .capabilities(EnumSet.of(Capability.COLOR, Capability.BRIGHTNESS, Capability.ON_OFF))
                                 .build());
        assertCapabilities(
                getGroupCapabilities("/groups/6"),
                LightCapabilities.builder()
                                 .colorGamut(GAMUT_C)
                                 .capabilities(EnumSet.of(Capability.COLOR, Capability.BRIGHTNESS, Capability.ON_OFF))
                                 .build());
        assertCapabilities(
                getGroupCapabilities("/groups/7"),
                LightCapabilities.builder()
                                 .colorGamut(GAMUT_B)
                                 .capabilities(EnumSet.of(Capability.COLOR, Capability.BRIGHTNESS, Capability.ON_OFF))
                                 .build());
    }

    @Test
    void putState_brightness_success_callsCorrectUrl() {
        setPutResponse("/lights/" + 15 + "/state", "{\"bri\":200}",
                """
                        [
                          {
                            "success": {
                              "/lights/22/state/bri": 200
                            }
                          }
                        ]""");

        performPutCall(PutCall.builder().id("/lights/15").bri(200).build());
    }

    @Test
    void putState_group_usesCorrectUrl() {
        setPutResponse("/groups/" + 9 + "/action", "{\"bri\":200}",
                """
                        [
                          {
                            "success": {
                              "/groups/9/action/bri": 200
                            }
                          }
                        ]""");

        performPutCall(PutCall.builder().id("/groups/9").bri(200).groupState(true).build());
    }

    @Test
    void putState_ct_correctBody() {
        setPutResponse("/lights/" + 16 + "/state", "{\"ct\":100}", "[success]");

        performPutCall(PutCall.builder().id("/lights/16").ct(100).build());
    }

    @Test
    void putState_XAndY_correctBody() {
        double x = 0.6075;
        double y = 0.3525;
        setPutResponse("/lights/" + 16 + "/state", "{\"xy\":[" + x + "," + y + "]}", "[success]");

        performPutCall(PutCall.builder().id("/lights/16").x(x).y(y).build());
    }

    @Test
    void putState_hueAndSaturation_correctBody() {
        int hue = 0;
        int sat = 254;
        setPutResponse("/lights/" + 16 + "/state", "{\"hue\":" + hue + ",\"sat\":" + sat + "}", "[success]");

        performPutCall(PutCall.builder().id("/lights/16").hue(hue).sat(sat).build());
    }

    @Test
    void putState_on_setsFlagCorrectly() {
        setPutResponse("/lights/" + 16 + "/state", "{\"on\":true}", "[success]");

        performPutCall(PutCall.builder().id("/lights/16").on(true).build());
    }

    @Test
    void putState_transitionTime_setsTimeCorrectly() {
        setPutResponse("/lights/" + 16 + "/state", "{\"transitiontime\":2}", "[success]");

        performPutCall(PutCall.builder().id("/lights/16").transitionTime(2).build());
    }

    @Test
    void putState_transitionTime_defaultValueOfFour_isIgnored() {
        setPutResponse("/lights/" + 16 + "/state", "{}", "[success]");

        performPutCall(PutCall.builder().id("/lights/16").transitionTime(4).build());
    }

    @Test
    void putState_effect_correctlySet() {
        setPutResponse("/lights/" + 1 + "/state", "{\"effect\":\"colorloop\"}", "[success]");

        performPutCall(PutCall.builder().id("/lights/1").effect("colorloop").build());

    }

    @Test
    void putState_unauthorized_throwsException() {
        setPutResponse("/lights/" + 10 + "/state", "{\"bri\":100}", """
                [
                  {
                    "error": {
                      "type": 1,
                      "address": "/lights",
                      "description": "unauthorized user"
                    }
                  }
                ]""");

        assertThrows(BridgeAuthenticationFailure.class, () -> performPutCall(PutCall.builder().id("/lights/10").bri(100).build()));
    }

    @Test
    void putState_lights_resourceNotAvailable_exception() {
        setPutResponse("/lights/" + 1 + "/state", "{\"bri\":100}", """
                [
                  {
                    "error": {
                      "type": 3,
                      "address": "/lights/1/state",
                      "description": "resource, /lights/1/state, not available"
                    }
                  }
                ]""");

        ApiFailure apiFailure = assertThrows(ApiFailure.class, () -> performPutCall(PutCall.builder().id("/lights/1").bri(100).build()));
        assertThat(apiFailure.getMessage()).isEqualTo("resource, /lights/1/state, not available");
    }

    @Test
    void putState_groups_resourceNotAvailable_exception() {
        setPutResponse("/groups/" + 1 + "/action", "{\"bri\":100}", """
                [
                  {
                    "error": {
                      "type": 3,
                      "address": "/groups/1/action",
                      "description": "resource, /groups/11/action, not available"
                    }
                  }
                ]""");

        ApiFailure apiFailure = assertThrows(ApiFailure.class, () -> performPutCall(PutCall.builder().id("/groups/1").bri(100).groupState(true).build()));
        assertThat(apiFailure.getMessage()).isEqualTo("resource, /groups/11/action, not available");
    }

    @Test
    void putState_connectionFailure_exception() {
        when(resourceProviderMock.putResource(any(), any())).thenThrow(new BridgeConnectionFailure("Failed"));

        assertThrows(BridgeConnectionFailure.class, () -> performPutCall(PutCall.builder().id("/lights/123").bri(100).build()));
    }

    @Test
    void putState_emptyResponse_treatedAsSuccess() {
        setPutResponse("/lights/" + 123 + "/state", "{\"bri\":100}",
                "[\n" +
                "]");

        performPutCall(PutCall.builder().id("/lights/123").bri(100).build());
    }

    @Test
    void putState_fakedInvalidParameterValueResponse_exception() {
        setPutResponse("/lights/" + 777 + "/state", "{\"bri\":300}",
                """
                        [
                          {
                            "success": {
                              "/lights/22/state/transitiontime": 2
                            }
                          },
                          {
                            "error": {
                              "type": 7,
                              "address": "/lights/22/state/bri",
                              "description": "invalid value, 300}, for parameter, bri"
                            }
                          }
                        ]""");

        ApiFailure apiFailure = assertThrows(ApiFailure.class, () -> performPutCall(PutCall.builder().id("/lights/777").bri(300).build()));
        assertThat(apiFailure.getMessage()).isEqualTo("invalid value, 300}, for parameter, bri");
    }

    @Test
    void putState_parameterNotModifiable_becauseLightIsOff_noExceptionThrown_ignored() {
        setPutResponse("/lights/" + 777 + "/state", "{\"bri\":200}", """
                [
                  {
                    "error": {
                      "type": 201,
                      "address": "/lights/777/state/bri",
                      "description": "parameter, bri, is not modifiable. Device is set to off."
                    }
                  }
                ]""");

        performPutCall(PutCall.builder().id("/lights/777").bri(200).build());
    }

    @Test
    void isLightOff_stateIsOn_false() {
        String lightId = "2";
        setGetResponse("/lights/" + lightId, """
                {
                  "state": {
                    "on": true,
                    "reachable": true
                  },
                  "name": "Name 2",
                  "type": "On/Off plug-in unit"
                }""");

        assertThat(isLightOff("/lights/" + lightId)).isFalse();
    }

    @Test
    void isLightOff_stateIsOn_andUnreachable_stillFalse() {
        String lightId = "1";
        setGetResponse("/lights/" + lightId, """
                {
                  "state": {
                    "on": true,
                    "reachable": false
                  },
                  "name": "Name 2",
                  "type": "On/Off plug-in unit"
                }""");

        assertThat(isLightOff("/lights/" + lightId)).isFalse();
    }

    @Test
    void isLightOff_stateIsOff_true() {
        String lightId = "4";
        setGetResponse("/lights/" + lightId, """
                {
                  "state": {
                    "on": false,
                    "reachable": true
                  },
                  "name": "Name 2",
                  "type": "On/Off plug-in unit"
                }""");

        assertThat(isLightOff("/lights/" + lightId)).isTrue();
    }

    @Test
    void isGroupOff_anyOn_isTrue_returnsFalse() {
        String groupId = "5";
        setGetResponse("/groups/" + groupId, """
                {
                  "name": "Group",
                  "lights": [
                    "1",
                    "2"
                  ],
                  "sensors": [],
                  "type": "Room",
                  "state": {
                    "all_on": true,
                    "any_on": true
                  },
                  "recycle": false,
                  "class": "Living room"
                }""");

        assertThat(api.isGroupOff("/groups/" + groupId)).isFalse();
    }

    @Test
    void isGroupOff_allOn_isFalse_returnsFalse() {
        String groupId = "4";
        setGetResponse("/groups/" + groupId, """
                {
                  "name": "Group",
                  "lights": [
                    "1",
                    "2"
                  ],
                  "sensors": [],
                  "type": "Room",
                  "state": {
                    "all_on": false,
                    "any_on": true
                  },
                  "recycle": false,
                  "class": "Living room"
                }""");

        assertThat(api.isGroupOff("/groups/" + groupId)).isFalse();
    }

    @Test
    void isGroupOff_anyOn_isFalse_returnsTrue() {
        String groupId = "4";
        setGetResponse("/groups/" + groupId, """
                {
                  "name": "Group",
                  "lights": [
                    "1",
                    "2"
                  ],
                  "sensors": [],
                  "type": "Room",
                  "state": {
                    "all_on": false,
                    "any_on": false
                  },
                  "recycle": false,
                  "class": "Living room"
                }""");

        assertThat(api.isGroupOff("/groups/" + groupId)).isTrue();
    }

    private boolean isLightOff(String lightId) {
        return api.isLightOff(lightId);
    }

    private void setGetResponse(String expectedUrl, @Language("JSON") String response) {
        when(resourceProviderMock.getResource(getUrl(expectedUrl))).thenReturn(response);
    }

    private URL getUrl(String expectedUrl) {
        try {
            return new URI(baseUrl + expectedUrl).toURL();
        } catch (MalformedURLException | URISyntaxException e) {
            throw new IllegalArgumentException("Could not create URL", e);
        }
    }

    private void setPutResponse(String expectedUrl, String expectedBody, @Language("JSON") String response) {
        when(resourceProviderMock.putResource(getUrl(expectedUrl), expectedBody)).thenReturn(response);
    }

    private LightCapabilities getLightCapabilities(String id) {
        return api.getLightCapabilities(id);
    }

    private LightCapabilities getGroupCapabilities(String id) {
        return api.getGroupCapabilities(id);
    }

    private LightState getLightState(String lightId) {
        return api.getLightState(lightId);
    }

    private List<LightState> getGroupStates(String id) {
        return api.getGroupStates(id);
    }

    private String getLightName(String id) {
        return api.getLightName(id);
    }

    private String getGroupName(String groupId) {
        return api.getGroupName(groupId);
    }

    private String getGroupId(String name) {
        return api.getGroupId(name);
    }

    private void assertLightState(LightState lightState, LightState expected) {
        assertThat(lightState).isEqualTo(expected);
    }

    private void performPutCall(PutCall putCall) {
        api.putState(putCall);
    }

    private List<String> getGroupLights(String groupId) {
        return api.getGroupLights(groupId);
    }

    private List<String> getAssignedGroups(String lightId) {
        return api.getAssignedGroups(lightId);
    }

    private void assertCapabilities(LightCapabilities capabilities, LightCapabilities expected) {
        assertThat(capabilities).isEqualTo(expected);
    }
}
