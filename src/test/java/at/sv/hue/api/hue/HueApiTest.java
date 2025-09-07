package at.sv.hue.api.hue;

import at.sv.hue.ColorMode;
import at.sv.hue.Effect;
import at.sv.hue.Gradient;
import at.sv.hue.Pair;
import at.sv.hue.ScheduledLightState;
import at.sv.hue.api.ApiFailure;
import at.sv.hue.api.BridgeAuthenticationFailure;
import at.sv.hue.api.BridgeConnectionFailure;
import at.sv.hue.api.Capability;
import at.sv.hue.api.EmptyGroupException;
import at.sv.hue.api.GroupNotFoundException;
import at.sv.hue.api.HttpResourceProvider;
import at.sv.hue.api.HueApi;
import at.sv.hue.api.Identifier;
import at.sv.hue.api.LightCapabilities;
import at.sv.hue.api.LightNotFoundException;
import at.sv.hue.api.LightState;
import at.sv.hue.api.PutCall;
import at.sv.hue.api.ResourceNotFoundException;
import org.intellij.lang.annotations.Language;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class HueApiTest {
    private static final Double[][] GAMUT_A = new Double[][]{{0.704, 0.296}, {0.2151, 0.7106}, {0.138, 0.08}};
    private static final Double[][] GAMUT_B = new Double[][]{{0.675, 0.322}, {0.409, 0.518}, {0.167, 0.04}};
    private static final Double[][] GAMUT_C = new Double[][]{{0.6915, 0.3083}, {0.17, 0.7}, {0.1532, 0.0475}};
    @Language("JSON")
    static final String EMPTY_RESPONSE = """
            {
              "errors": [],
              "data": []
            }
            """;
    private HueApi api;
    private String baseUrl;
    private HttpResourceProvider resourceProviderMock;

    @BeforeEach
    void setUp() {
        String host = "localhost";
        resourceProviderMock = Mockito.mock(HttpResourceProvider.class);
        api = new HueApiImpl(resourceProviderMock, host, permits -> {
        }, 5);
        baseUrl = "https://" + host + "/clip/v2/resource";
    }

    @Test
    void invalidHost_cantUseScheme_exception() {
        assertThrows(InvalidConnectionException.class, () -> new HueApiImpl(resourceProviderMock, "hTtps://localhost", permits -> {
        }, 5));
    }

    @Test
    void checkConnection_unauthorizedUser_exception() {
        when(resourceProviderMock.getResource(getUrl("/light"))).thenThrow(new BridgeAuthenticationFailure());

        assertThrows(BridgeAuthenticationFailure.class, () -> api.assertConnection());
    }

    @Test
    void getState_networkFailure_exception() {
        when(resourceProviderMock.getResource(any())).thenThrow(new BridgeConnectionFailure("Failed"));

        assertThrows(BridgeConnectionFailure.class, () -> getLightState("9d8378df-fa1b-4988-b9d1-2ba568143116"));
    }

    @Test
    void getState_emptyResponse_exception() {
        setGetResponse("/light/ABCD-1234", "");

        assertThrows(ApiFailure.class, () -> getLightState("ABCD-1234"));
    }

    @Test
    void getState_emptyJSON_exception() {
        setGetResponse("/light/ABCD-1234", "{}");

        assertThrows(ApiFailure.class, () -> getLightState("ABCD-1234"));
    }

    @Test
    void getState_lightNotFound_exception() {
        when(resourceProviderMock.getResource(getUrl("/light"))).thenThrow(new ResourceNotFoundException(""));

        assertThrows(ResourceNotFoundException.class, () -> getLightState("ABCD-1234"));
    }

    @Test
    void getState_hasGradientSupport() {
        setGetResponse("/light/cb6f54e8-5071-478e-9c0a-949a51b117a2", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "cb6f54e8-5071-478e-9c0a-949a51b117a2",
                      "id_v1": "/lights/55",
                      "owner": {
                        "rid": "2d019cd6-7de4-46dc-b8af-963add43a000",
                        "rtype": "device"
                      },
                      "metadata": {
                        "name": "Fernseher Play",
                        "archetype": "hue_lightstrip_tv",
                        "function": "decorative"
                      },
                      "product_data": {
                        "function": "decorative"
                      },
                      "identify": {},
                      "service_id": 0,
                      "on": {
                        "on": true
                      },
                      "dimming": {
                        "brightness": 7.91,
                        "min_dim_level": 0.01
                      },
                      "dimming_delta": {},
                      "color_temperature": {
                        "mirek": null,
                        "mirek_valid": false,
                        "mirek_schema": {
                          "mirek_minimum": 153,
                          "mirek_maximum": 500
                        }
                      },
                      "color_temperature_delta": {},
                      "color": {
                        "xy": {
                          "x": 0.1969,
                          "y": 0.0688
                        },
                        "gamut": {
                          "red": {
                            "x": 0.6915,
                            "y": 0.3083
                          },
                          "green": {
                            "x": 0.17,
                            "y": 0.7
                          },
                          "blue": {
                            "x": 0.1532,
                            "y": 0.0475
                          }
                        },
                        "gamut_type": "C"
                      },
                      "dynamics": {
                        "status": "none",
                        "status_values": [
                          "none",
                          "dynamic_palette"
                        ],
                        "speed": 0.0,
                        "speed_valid": false
                      },
                      "mode": "normal",
                      "gradient": {
                        "points": [
                          {
                            "color": {
                              "xy": {
                                "x": 0.1969,
                                "y": 0.0688
                              }
                            }
                          },
                          {
                            "color": {
                              "xy": {
                                "x": 0.3352,
                                "y": 0.1357
                              }
                            }
                          },
                          {
                            "color": {
                              "xy": {
                                "x": 0.5501,
                                "y": 0.2398
                              }
                            }
                          },
                          {
                            "color": {
                              "xy": {
                                "x": 0.6079,
                                "y": 0.3554
                              }
                            }
                          },
                          {
                            "color": {
                              "xy": {
                                "x": 0.4813,
                                "y": 0.45
                              }
                            }
                          }
                        ],
                        "mode": "interpolated_palette",
                        "points_capable": 5,
                        "mode_values": [
                          "interpolated_palette",
                          "interpolated_palette_mirrored",
                          "random_pixelated"
                        ],
                        "pixel_count": 24
                      },
                      "effects_v2": {
                        "action": {
                          "effect_values": [
                            "no_effect",
                            "candle"
                          ]
                        },
                        "status": {
                          "effect": "no_effect",
                          "effect_values": [
                            "no_effect",
                            "candle"
                          ]
                        }
                      },
                      "type": "light"
                    }
                  ]
                }
                """);
        setGetResponse("/device", EMPTY_RESPONSE);

        LightState lightState = getLightState("cb6f54e8-5071-478e-9c0a-949a51b117a2");

        assertLightState(
                lightState,
                LightState.builder()
                          .id("cb6f54e8-5071-478e-9c0a-949a51b117a2")
                          .on(true)
                          .brightness(20)
                          .colorTemperature(null)
                          .x(0.1969)
                          .y(0.0688)
                          .colormode(ColorMode.GRADIENT)
                          .effect("none")
                          .gradient(Gradient.builder()
                                            .points(List.of(
                                                    Pair.of(0.1969, 0.0688),
                                                    Pair.of(0.3352, 0.1357),
                                                    Pair.of(0.5501, 0.2398),
                                                    Pair.of(0.6079, 0.3554),
                                                    Pair.of(0.4813, 0.45)
                                            ))
                                            .mode("interpolated_palette")
                                            .build())
                          .lightCapabilities(LightCapabilities.builder()
                                                              .colorGamutType("C")
                                                              .colorGamut(GAMUT_C)
                                                              .ctMin(153)
                                                              .ctMax(500)
                                                              .gradientModes(List.of("interpolated_palette", "interpolated_palette_mirrored",
                                                                      "random_pixelated"))
                                                              .maxGradientPoints(5)
                                                              .effects(List.of("candle"))
                                                              .capabilities(EnumSet.of(Capability.GRADIENT, Capability.COLOR, Capability.COLOR_TEMPERATURE,
                                                                      Capability.BRIGHTNESS, Capability.ON_OFF))
                                                              .build()
                          )
                          .build()
        );
    }

    @Test
    void getState_returnsLightState_effectActive_xyAsParameter_callsCorrectApiURL() {
        setGetResponse("/light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "9a40e007-8d76-4954-ba88-0f52444b7df6",
                      "id_v1": "/lights/29",
                      "owner": {
                        "rid": "6f10b317-e118-4259-a6b8-d4a2ac960756",
                        "rtype": "device"
                      },
                      "metadata": {
                        "name": "Schreibtisch L",
                        "archetype": "sultan_bulb",
                        "function": "mixed"
                      },
                      "product_data": {
                        "function": "mixed"
                      },
                      "identify": {},
                      "service_id": 0,
                      "on": {
                        "on": true
                      },
                      "dimming": {
                        "brightness": 100.0,
                        "min_dim_level": 0.20000000298023225
                      },
                      "dimming_delta": {},
                      "color_temperature": {
                        "mirek": 398,
                        "mirek_valid": false
                      },
                      "color_temperature_delta": {},
                      "color": {
                        "xy": {
                          "x": 0.3082,
                          "y": 0.2482
                        }
                      },
                      "dynamics": {
                        "status": "none",
                        "status_values": [
                          "none",
                          "dynamic_palette"
                        ],
                        "speed": 0.0,
                        "speed_valid": false
                      },
                      "alert": {
                        "action_values": [
                          "breathe"
                        ]
                      },
                      "signaling": {
                        "signal_values": [
                          "no_signal",
                          "on_off",
                          "on_off_color",
                          "alternating"
                        ]
                      },
                      "mode": "normal",
                      "effects_v2": {
                        "action": {
                          "effect_values": [
                            "no_effect",
                            "opal"
                          ]
                        },
                        "status": {
                          "effect": "opal",
                          "effect_values": [
                            "no_effect",
                            "opal"
                          ],
                          "parameters": {
                            "color": {
                              "xy": {
                                "x": 0.2116,
                                "y": 0.0759
                              }
                            },
                            "color_temperature": {
                              "mirek": 153,
                              "mirek_valid": false
                            },
                            "speed": 0.5
                          }
                        }
                      },
                      "timed_effects": {
                        "status_values": [
                          "no_effect",
                          "sunrise",
                          "sunset"
                        ],
                        "status": "no_effect",
                        "effect_values": [
                          "no_effect",
                          "sunrise",
                          "sunset"
                        ]
                      },
                      "type": "light"
                    }
                  ]
                }
                """);
        setGetResponse("/device", EMPTY_RESPONSE);

        LightState lightState = getLightState("9a40e007-8d76-4954-ba88-0f52444b7df6");

        assertLightState(
                lightState, LightState
                        .builder()
                        .id("9a40e007-8d76-4954-ba88-0f52444b7df6")
                        .on(true)
                        .x(0.3082)
                        .y(0.2482)
                        .brightness(254) // todo: we need to switch to APIv2 brightness probably in the whole application
                        .effect(Effect.builder()
                                      .effect("opal")
                                      .x(0.2116)
                                      .y(0.0759)
                                      .speed(0.5)
                                      .build())
                        .colormode(ColorMode.XY)
                        .lightCapabilities(LightCapabilities.builder()
                                                            .effects(List.of("opal"))
                                                            .capabilities(EnumSet.of(
                                                                    Capability.COLOR,
                                                                    Capability.BRIGHTNESS,
                                                                    Capability.COLOR_TEMPERATURE,
                                                                    Capability.ON_OFF)
                                                            )
                                                            .build())
                        .build()
        );
    }

    @Test
    void getState_returnsLightState_effectActive_ctAsParameter_callsCorrectApiURL() {
        setGetResponse("/light/cb6f54e8-5071-478e-9c0a-949a51b117a2", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "cb6f54e8-5071-478e-9c0a-949a51b117a2",
                      "id_v1": "/lights/55",
                      "owner": {
                        "rid": "2d019cd6-7de4-46dc-b8af-963add43a000",
                        "rtype": "device"
                      },
                      "metadata": {
                        "name": "TV Play",
                        "archetype": "hue_lightstrip_tv",
                        "function": "decorative"
                      },
                      "product_data": {
                        "function": "decorative"
                      },
                      "on": {
                        "on": true
                      },
                      "dimming": {
                        "brightness": 26.09,
                        "min_dim_level": 0.01
                      },
                      "color_temperature": {
                        "mirek": 493,
                        "mirek_valid": true,
                        "mirek_schema": {
                          "mirek_minimum": 153,
                          "mirek_maximum": 500
                        }
                      },
                      "color": {
                        "xy": {
                          "x": 0.5236,
                          "y": 0.4137
                        }
                      },
                      "mode": "normal",
                      "effects_v2": {
                        "action": {
                          "effect_values": [
                            "no_effect",
                            "candle"
                          ]
                        },
                        "status": {
                          "effect": "candle",
                          "effect_values": [
                            "no_effect",
                            "candle"
                          ],
                          "parameters": {
                            "color": {
                              "xy": {
                                "x": 0.5236,
                                "y": 0.4137
                              }
                            },
                            "color_temperature": {
                              "mirek": 493,
                              "mirek_valid": true
                            },
                            "speed": 0.1825
                          }
                        }
                      },
                      "type": "light"
                    }
                  ]
                }
                """);
        setGetResponse("/device", EMPTY_RESPONSE);

        LightState lightState = getLightState("cb6f54e8-5071-478e-9c0a-949a51b117a2");

        assertLightState(
                lightState, LightState
                        .builder()
                        .id("cb6f54e8-5071-478e-9c0a-949a51b117a2")
                        .on(true)
                        .x(0.5236)
                        .y(0.4137)
                        .colorTemperature(493)
                        .brightness(66)
                        .effect(Effect.builder()
                                      .effect("candle")
                                      .ct(493)
                                      .speed(0.1825)
                                      .build())
                        .colormode(ColorMode.CT)
                        .lightCapabilities(LightCapabilities.builder()
                                                            .effects(List.of("candle"))
                                                            .capabilities(EnumSet.of(
                                                                    Capability.COLOR,
                                                                    Capability.BRIGHTNESS,
                                                                    Capability.COLOR_TEMPERATURE,
                                                                    Capability.ON_OFF)
                                                            )
                                                            .ctMin(153)
                                                            .ctMax(500)
                                                            .build())
                        .build()
        );
    }

    @Test
    void getState_differentResult_colorTemperatureActive_correctState() {
        setGetResponse("/light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "9d8378df-fa1b-4988-b9d1-2ba568143116",
                      "id_v1": "/lights/29",
                      "owner": {
                        "rid": "6f10b317-e118-4259-a6b8-d4a2ac960756",
                        "rtype": "device"
                      },
                      "metadata": {
                        "name": "Schreibtisch L",
                        "archetype": "sultan_bulb",
                        "function": "mixed"
                      },
                      "product_data": {
                        "function": "mixed"
                      },
                      "identify": {},
                      "service_id": 0,
                      "on": {
                        "on": false
                      },
                      "dimming": {
                        "brightness": 49.8,
                        "min_dim_level": 0.20000000298023225
                      },
                      "dimming_delta": {},
                      "color_temperature": {
                        "mirek": 398,
                        "mirek_valid": true,
                        "mirek_schema": {
                          "mirek_minimum": 153,
                          "mirek_maximum": 500
                        }
                      },
                      "color_temperature_delta": {},
                      "color": {
                        "xy": {
                          "x": 0.4759,
                          "y": 0.4135
                        }
                      },
                      "dynamics": {
                        "status": "none",
                        "status_values": [
                          "none",
                          "dynamic_palette"
                        ],
                        "speed": 0.0,
                        "speed_valid": false
                      },
                      "alert": {
                        "action_values": [
                          "breathe"
                        ]
                      },
                      "signaling": {
                        "signal_values": [
                          "no_signal",
                          "on_off",
                          "on_off_color",
                          "alternating"
                        ]
                      },
                      "mode": "normal",
                      "effects_v2": {
                        "action": {
                          "effect_values": [
                            "no_effect",
                            "fire"
                          ]
                        },
                        "status": {
                          "effect": "no_effect",
                          "effect_values": [
                            "no_effect",
                            "fire"
                          ]
                        }
                      },
                      "timed_effects": {
                        "status_values": [
                          "no_effect",
                          "sunrise",
                          "sunset"
                        ],
                        "status": "no_effect",
                        "effect_values": [
                          "no_effect",
                          "sunrise",
                          "sunset"
                        ]
                      },
                      "type": "light"
                    }
                  ]
                }
                """);
        setGetResponse("/device", EMPTY_RESPONSE);

        LightState lightState = getLightState("9d8378df-fa1b-4988-b9d1-2ba568143116");

        assertLightState(lightState, LightState
                .builder()
                .id("9d8378df-fa1b-4988-b9d1-2ba568143116")
                .on(false)
                .x(0.4759)
                .y(0.4135)
                .colorTemperature(398)
                .brightness(127)
                .effect("none")
                .colormode(ColorMode.CT)
                .lightCapabilities(LightCapabilities.builder()
                                                    .ctMin(153)
                                                    .ctMax(500)
                                                    .effects(List.of("fire"))
                                                    .capabilities(EnumSet.of(
                                                            Capability.COLOR,
                                                            Capability.BRIGHTNESS,
                                                            Capability.COLOR_TEMPERATURE,
                                                            Capability.ON_OFF)
                                                    )
                                                    .build())
                .build()
        );
    }

    @Test
    void getState_whiteBulbOnly_noOnProperty_treatedAsOff_noNullPointerException() {
        setGetResponse("/light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "94eb9b65-f310-4c31-907a-89da8ef7ccdd",
                      "id_v1": "/lights/50",
                      "owner": {
                        "rid": "645a6898-1a16-47af-aa17-7cc4c348f337",
                        "rtype": "device"
                      },
                      "metadata": {
                        "name": "Hue white lamp 1",
                        "archetype": "sultan_bulb",
                        "fixed_mired": 366,
                        "function": "mixed"
                      },
                      "product_data": {
                        "function": "mixed"
                      },
                      "identify": {},
                      "service_id": 0,
                      "dimming": {
                        "brightness": 30.04,
                        "min_dim_level": 5.0
                      },
                      "dimming_delta": {},
                      "dynamics": {
                        "status": "none",
                        "status_values": [
                          "none"
                        ],
                        "speed": 0.0,
                        "speed_valid": false
                      },
                      "alert": {
                        "action_values": [
                          "breathe"
                        ]
                      },
                      "signaling": {
                        "signal_values": [
                          "no_signal",
                          "on_off"
                        ]
                      },
                      "type": "light"
                    }
                  ]
                }""");
        setGetResponse("/device", EMPTY_RESPONSE);

        LightState lightState = getLightState("94eb9b65-f310-4c31-907a-89da8ef7ccdd");

        assertLightState(lightState, LightState
                .builder()
                .id("94eb9b65-f310-4c31-907a-89da8ef7ccdd")
                .on(false)
                .brightness(76) // todo: the APIv1 itself reports 77
                .colormode(ColorMode.NONE)
                .lightCapabilities(LightCapabilities.builder()
                                                    .capabilities(EnumSet.of(Capability.BRIGHTNESS, Capability.ON_OFF))
                                                    .build())
                .build());
    }

    @Test
    void getState_onOffBulbOnly_noNullPointerException() {
        setGetResponse("/light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "426ab1f6-c27f-42ba-b18d-0783665b4e21",
                      "id_v1": "/lights/32",
                      "owner": {
                        "rid": "23e0ec8f-f096-4e9c-a6d5-4d7efe98ace6",
                        "rtype": "device"
                      },
                      "metadata": {
                        "name": "Plug",
                        "archetype": "plug",
                        "function": "functional"
                      },
                      "product_data": {
                        "function": "functional"
                      },
                      "identify": {},
                      "service_id": 0,
                      "on": {
                        "on": true
                      },
                      "dynamics": {
                        "status": "none",
                        "status_values": [
                          "none"
                        ],
                        "speed": 0.0,
                        "speed_valid": false
                      },
                      "alert": {
                        "action_values": []
                      },
                      "mode": "normal",
                      "type": "light"
                    }
                  ]
                }""");
        setGetResponse("/device", EMPTY_RESPONSE);

        LightState lightState = getLightState("426ab1f6-c27f-42ba-b18d-0783665b4e21");

        assertLightState(lightState, LightState
                .builder()
                .id("426ab1f6-c27f-42ba-b18d-0783665b4e21")
                .on(true)
                .colormode(ColorMode.NONE)
                .lightCapabilities(LightCapabilities.builder()
                                                    .capabilities(EnumSet.of(Capability.ON_OFF))
                                                    .build())
                .build());
    }

    @Test
    void getState_onOffBulbOnly_isUnavailable_correctlySet() {
        setGetResponse("/light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "unavailable_light",
                      "owner": {
                        "rid": "device_id",
                        "rtype": "device"
                      },
                      "metadata": {
                        "name": "Plug",
                        "archetype": "plug",
                        "function": "functional"
                      },
                      "on": {
                        "on": true
                      },
                      "type": "light"
                    }
                  ]
                }""");
        setGetResponse("/device", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "device_id",
                      "services": [
                        {
                          "rid": "zigbee_id",
                          "rtype": "zigbee_connectivity"
                        },
                        {
                          "rid": "unavailable_light",
                          "rtype": "light"
                        }
                      ],
                      "type": "device"
                    }
                  ]
                }""");
        setGetResponse("/zigbee_connectivity", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "zigbee_id",
                      "owner": {
                        "rid": "device_id",
                        "rtype": "device"
                      },
                      "status": "disconnected",
                      "type": "zigbee_connectivity"
                    }
                  ]
                }""");

        LightState lightState = getLightState("unavailable_light");

        assertLightState(lightState, LightState
                .builder()
                .id("unavailable_light")
                .on(true)
                .unavailable(true)
                .colormode(ColorMode.NONE)
                .lightCapabilities(LightCapabilities.builder()
                                                    .capabilities(EnumSet.of(Capability.ON_OFF))
                                                    .build())
                .build());
    }

    @Test
    void getGroupState_returnsListOfLightStates_andTheirAvailability_ignoresUnknownLights() {
        setGetResponse("/grouped_light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "GroupedLight_ID_1",
                      "owner": {
                        "rid": "Group_ID_1",
                        "rtype": "zone"
                      },
                      "type": "grouped_light"
                    },
                    {
                      "id": "GroupedLight_ID_2",
                      "owner": {
                        "rid": "Group_ID_2",
                        "rtype": "zone"
                      },
                      "type": "grouped_light"
                    }
                  ]
                }""");
        setGetResponse("/zone", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "Group_ID_1",
                      "id_v1": "/groups/82",
                      "children": [
                        {
                          "rid": "af9a2b88-6fb0-4699-9300-356d3f306b0d",
                          "rtype": "light"
                        },
                        {
                          "rid": "79016b3c-6258-4f7f-a847-e5c360112b07",
                          "rtype": "light"
                        },
                        {
                          "rid": "UNKNOWN",
                          "rtype": "light"
                        }
                      ],
                      "services": [
                        {
                          "rid": "cecb9d02-acd5-4aff-b46d-330f614dd1fb",
                          "rtype": "grouped_light"
                        }
                      ],
                      "metadata": {
                        "name": "Bad indirekt",
                        "archetype": "attic"
                      },
                      "type": "zone"
                    },
                    {
                      "id": "Group_ID_2",
                      "id_v1": "/groups/82",
                      "children": [
                        {
                          "rid": "af9a2b88-6fb0-4699-9300-356d3f306b0d",
                          "rtype": "light"
                        },
                        {
                          "rid": "9d8378df-fa1b-4988-b9d1-2ba568143116",
                          "rtype": "light"
                        }
                      ],
                      "services": [
                        {
                          "rid": "cecb9d02-acd5-4aff-b46d-330f614dd1fb",
                          "rtype": "grouped_light"
                        }
                      ],
                      "metadata": {
                        "name": "Bad indirekt",
                        "archetype": "attic"
                      },
                      "type": "zone"
                    }
                  ]
                }""");
        setGetResponse("/light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "af9a2b88-6fb0-4699-9300-356d3f306b0d",
                      "id_v1": "/lights/11",
                      "owner": {
                        "rid": "Device_ID_1",
                        "rtype": "device"
                      },
                      "metadata": {
                        "name": "Bad Tür"
                      },
                      "on": {
                        "on": true
                      },
                      "type": "light"
                    },
                    {
                      "id": "79016b3c-6258-4f7f-a847-e5c360112b07",
                      "id_v1": "/lights/49",
                      "owner": {
                        "rid": "Device_ID_2",
                        "rtype": "device"
                      },
                      "metadata": {
                        "name": "Bad Therme neu"
                      },
                      "on": {
                        "on": false
                      },
                      "dimming": {
                        "brightness": 50.2,
                        "min_dim_level": 0.20000000298023225
                      },
                      "color_temperature": {
                        "mirek": 170,
                        "mirek_valid": true
                      },
                      "color_temperature_delta": {},
                      "type": "light"
                    },
                    {
                      "id": "9d8378df-fa1b-4988-b9d1-2ba568143116",
                      "id_v1": "/lights/50",
                      "owner": {
                        "rid": "Device_ID_3",
                        "rtype": "device"
                      },
                      "metadata": {
                        "name": "Bad Decke"
                      },
                      "on": {
                        "on": false
                      },
                      "type": "light"
                    }
                  ]
                }""");
        setGetResponse("/device", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "Device_ID_1",
                      "id_v1": "/lights/11",
                      "metadata": {
                        "name": "Bad Tür",
                        "archetype": "hue_lightstrip"
                      },
                      "identify": {},
                      "services": [
                        {
                          "rid": "Zigbee_ID_1",
                          "rtype": "zigbee_connectivity"
                        },
                        {
                          "rid": "af9a2b88-6fb0-4699-9300-356d3f306b0d",
                          "rtype": "light"
                        }
                      ],
                      "type": "device"
                    },
                    {
                      "id": "Device_ID_2",
                      "id_v1": "/lights/49",
                      "metadata": {
                        "name": "Bad Tür",
                        "archetype": "hue_lightstrip"
                      },
                      "identify": {},
                      "services": [
                        {
                          "rid": "Zigbee_ID_2",
                          "rtype": "zigbee_connectivity"
                        },
                        {
                          "rid": "79016b3c-6258-4f7f-a847-e5c360112b07",
                          "rtype": "light"
                        }
                      ],
                      "type": "device"
                    },
                    {
                      "id": "Device_ID_3",
                      "id_v1": "/lights/50",
                      "metadata": {
                        "name": "Bad Decke",
                        "archetype": "hue_lightstrip"
                      },
                      "identify": {},
                      "services": [
                        {
                          "rid": "9d8378df-fa1b-4988-b9d1-2ba568143116",
                          "rtype": "light"
                        }
                      ],
                      "type": "device"
                    }
                  ]
                }""");
        setGetResponse("/zigbee_connectivity", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "Zigbee_ID_1",
                      "id_v1": "/lights/11",
                      "owner": {
                        "rid": "Device_ID_1",
                        "rtype": "device"
                      },
                      "status": "connectivity_issue",
                      "type": "zigbee_connectivity"
                    },
                    {
                      "id": "Zigbee_ID_2",
                      "id_v1": "/lights/49",
                      "owner": {
                        "rid": "Device_ID_2",
                        "rtype": "device"
                      },
                      "status": "connected",
                      "type": "zigbee_connectivity"
                    }
                  ]
                }""");

        assertThat(getGroupStates("GroupedLight_ID_1")).containsExactly(
                LightState.builder()
                          .id("af9a2b88-6fb0-4699-9300-356d3f306b0d")
                          .on(true)
                          .unavailable(true)
                          .colormode(ColorMode.NONE)
                          .lightCapabilities(LightCapabilities.builder()
                                                              .capabilities(EnumSet.of(Capability.ON_OFF))
                                                              .build())
                          .build(),
                LightState.builder()
                          .id("79016b3c-6258-4f7f-a847-e5c360112b07")
                          .on(false)
                          .unavailable(false)
                          .brightness(127)
                          .colorTemperature(170)
                          .colormode(ColorMode.CT)
                          .lightCapabilities(LightCapabilities.builder()
                                                              .capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE,
                                                                      Capability.BRIGHTNESS, Capability.ON_OFF))
                                                              .build())
                          .build()
        );

        assertThat(getGroupStates("GroupedLight_ID_2")).containsExactly(
                LightState.builder()
                          .id("af9a2b88-6fb0-4699-9300-356d3f306b0d")
                          .on(true)
                          .unavailable(true)
                          .colormode(ColorMode.NONE)
                          .lightCapabilities(LightCapabilities.builder()
                                                              .capabilities(EnumSet.of(Capability.ON_OFF))
                                                              .build())
                          .build(),
                LightState.builder()
                          .id("9d8378df-fa1b-4988-b9d1-2ba568143116")
                          .on(false)
                          .unavailable(false) // default to false, if we can't determine it
                          .colormode(ColorMode.NONE)
                          .lightCapabilities(LightCapabilities.builder()
                                                              .capabilities(EnumSet.of(Capability.ON_OFF))
                                                              .build())
                          .build()
        );
    }

    @Test
    void getGroupLights_returnsLightIdsForRoomAndZones_reusesSameForName() {
        setGetResponse("/grouped_light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "eeb336d9-243b-4756-8455-1c69f50efd31",
                      "id_v1": "/groups/3",
                      "owner": {
                        "rid": "75ee4e3b-cacb-4b87-923b-d11d2480e8ff",
                        "rtype": "zone"
                      },
                      "type": "grouped_light"
                    },
                    {
                      "id": "8061bb6b-bb7f-4872-a2ad-fb2d81f88a2d",
                      "id_v1": "/groups/83",
                      "owner": {
                        "rid": "78a2948e-b9cc-4a92-8965-09e17819d8d7",
                        "rtype": "zone"
                      },
                      "type": "grouped_light"
                    },{
                      "id": "4c69c282-3e45-4e87-adb0-fa52e03b4620",
                      "id_v1": "/groups/1",
                      "owner": {
                        "rid": "3cfd5fad-2811-430a-a099-ae692b2185f8",
                        "rtype": "room"
                      },
                      "type": "grouped_light"
                    }
                  ]
                }""");
        setGetResponse("/zone", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "75ee4e3b-cacb-4b87-923b-d11d2480e8ff",
                      "id_v1": "/groups/3",
                      "children": [
                        {
                          "rid": "1271bf6f-be63-42fc-b18c-3ad462914d8e",
                          "rtype": "light"
                        },
                        {
                          "rid": "1aa6083d-3692-49e5-92f7-b926b302dd49",
                          "rtype": "light"
                        }
                      ],
                      "services": [
                        {
                          "rid": "eeb336d9-243b-4756-8455-1c69f50efd31",
                          "rtype": "grouped_light"
                        }
                      ],
                      "metadata": {
                        "name": "Couch",
                        "archetype": "lounge"
                      },
                      "type": "zone"
                    },
                    {
                      "id": "78a2948e-b9cc-4a92-8965-09e17819d8d7",
                      "id_v1": "/groups/83",
                      "children": [
                        {
                          "rid": "1062e2b5-959e-474c-8149-9108e928f332",
                          "rtype": "light"
                        },
                        {
                          "rid": "23941817-3ebe-4012-b105-04fe4fca5f17",
                          "rtype": "light"
                        }
                      ],
                      "services": [
                        {
                          "rid": "8061bb6b-bb7f-4872-a2ad-fb2d81f88a2d",
                          "rtype": "grouped_light"
                        }
                      ],
                      "metadata": {
                        "name": "Non Color Lights",
                        "archetype": "dining"
                      },
                      "type": "zone"
                    }
                  ]
                }
                """);
        setGetResponse("/room", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "3cfd5fad-2811-430a-a099-ae692b2185f8",
                      "id_v1": "/groups/1",
                      "children": [
                        {
                          "rid": "cf704269-6f65-47f1-8423-677b65d3f874",
                          "rtype": "device"
                        },
                        {
                          "rid": "2f279281-3e45-462c-9e89-6b2d3363d883",
                          "rtype": "device"
                        },
                        {
                          "rid": "SOMETHING_ELSE",
                          "rtype": "another_resource"
                        }
                      ],
                      "services": [
                        {
                          "rid": "4c69c282-3e45-4e87-adb0-fa52e03b4620",
                          "rtype": "grouped_light"
                        }
                      ],
                      "metadata": {
                        "name": "Living Room",
                        "archetype": "living_room"
                      },
                      "type": "room"
                    }
                  ]
                }
                """);
        setGetResponse("/device", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "cf704269-6f65-47f1-8423-677b65d3f874",
                      "id_v1": "/lights/1",
                      "product_data": {
                        "model_id": "LST001",
                        "manufacturer_name": "Signify Netherlands B.V.",
                        "product_name": "Hue lightstrip",
                        "product_archetype": "hue_lightstrip",
                        "certified": true,
                        "software_version": "67.116.3",
                        "hardware_platform_type": "100b-103"
                      },
                      "metadata": {
                        "name": "Couch",
                        "archetype": "hue_lightstrip"
                      },
                      "identify": {},
                      "services": [
                        {
                          "rid": "467288fc-29b1-483b-98b0-f6faf8396dc1",
                          "rtype": "zigbee_connectivity"
                        },
                        {
                          "rid": "1aa6083d-3692-49e5-92f7-b926b302dd49",
                          "rtype": "light"
                        },
                        {
                          "rid": "dcefe837-140a-4842-b5d0-23f15088e50d",
                          "rtype": "entertainment"
                        },
                        {
                          "rid": "3288bac5-097b-4775-93a2-089efba4c4cb",
                          "rtype": "device_software_update"
                        }
                      ],
                      "type": "device"
                    },
                    {
                      "id": "2f279281-3e45-462c-9e89-6b2d3363d883",
                      "id_v1": "/lights/27",
                      "product_data": {
                        "model_id": "LCA001",
                        "manufacturer_name": "Signify Netherlands B.V.",
                        "product_name": "Hue color lamp",
                        "product_archetype": "sultan_bulb",
                        "certified": true,
                        "software_version": "1.116.3",
                        "hardware_platform_type": "100b-112"
                      },
                      "metadata": {
                        "name": "Schreibtisch R",
                        "archetype": "sultan_bulb"
                      },
                      "identify": {},
                      "services": [
                        {
                          "rid": "d64efab2-f44a-45de-85c4-26f8857482f1",
                          "rtype": "zigbee_connectivity"
                        },
                        {
                          "rid": "2697622a-f39a-4f0e-b42e-651f94b4b983",
                          "rtype": "light"
                        },
                        {
                           "rid": "2697622a-f39a-4f0e-b42e-651f94b4b983",
                           "rtype": "light"
                        },
                        {
                          "rid": "eaa3a531-6aee-48c0-8c0f-aaf629811e6f",
                          "rtype": "entertainment"
                        },
                        {
                          "rid": "72a25201-bb6a-48ee-9289-33bda9f8ef6e",
                          "rtype": "taurus_7455"
                        },
                        {
                          "rid": "686e224b-ec99-4b1c-9131-4e770aaa729e",
                          "rtype": "device_software_update"
                        }
                      ],
                      "type": "device"
                    }
                  ]
                }""");

        // zone -> returns contained lights immediately
        assertThat(getGroupLights("eeb336d9-243b-4756-8455-1c69f50efd31"))
                .containsExactly("1271bf6f-be63-42fc-b18c-3ad462914d8e", "1aa6083d-3692-49e5-92f7-b926b302dd49");
        assertThat(getGroupLights("8061bb6b-bb7f-4872-a2ad-fb2d81f88a2d"))
                .containsExactly("1062e2b5-959e-474c-8149-9108e928f332", "23941817-3ebe-4012-b105-04fe4fca5f17");

        // room -> looks up devices and returns contained lights
        assertThat(getGroupLights("4c69c282-3e45-4e87-adb0-fa52e03b4620"))
                .containsExactly("1aa6083d-3692-49e5-92f7-b926b302dd49", "2697622a-f39a-4f0e-b42e-651f94b4b983",
                        "2697622a-f39a-4f0e-b42e-651f94b4b983");

        assertGroupIdentifier("/groups/3", "eeb336d9-243b-4756-8455-1c69f50efd31", "Couch");
        assertGroupIdentifier("/groups/83", "8061bb6b-bb7f-4872-a2ad-fb2d81f88a2d", "Non Color Lights");
        assertGroupIdentifier("/groups/1", "4c69c282-3e45-4e87-adb0-fa52e03b4620", "Living Room");
    }

    @Test
    void getAffectedIdsByScene_returnsLightIds_empty_ignoredAffectedByScene() {
        setGetResponse("/scene", EMPTY_RESPONSE);

        List<String> sceneLights = api.getAffectedIdsByScene("0314f5ad-b424-4f63-aa2e-f55cac83e306");

        assertThat(sceneLights).isEmpty();
    }

    @Test
    void getSceneName_sceneNotFound_returnsNull() {
        setGetResponse("/scene", EMPTY_RESPONSE);

        assertThat(api.getSceneName("123456789")).isNull();
    }

    @Test
    void getAffectedIdsByScene_getSceneName_returnsLightIdsAndGroupLightId_correctIdsAffectedByScene() {
        setGetResponse("/grouped_light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "3cfd5fad-2811-430a-a099-ae692b2185f8",
                      "id_v1": "/groups/3",
                      "owner": {
                        "rid": "75ee4e3b-cacb-4b87-923b-d11d2480e8ff",
                        "rtype": "zone"
                      },
                      "type": "grouped_light"
                    }
                  ]
                }""");
        setGetResponse("/room", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "3cfd5fad-2811-430a-a099-ae692b2185f8",
                      "id_v1": "/groups/1",
                      "children": [
                        {
                          "rid": "6f10b317-e118-4259-a6b8-d4a2ac960756",
                          "rtype": "device"
                        },
                        {
                          "rid": "2f279281-3e45-462c-9e89-6b2d3363d883",
                          "rtype": "device"
                        }
                      ],
                      "services": [
                        {
                          "rid": "4c69c282-3e45-4e87-adb0-fa52e03b4620",
                          "rtype": "grouped_light"
                        }
                      ],
                      "metadata": {
                        "name": "Wohnzimmer",
                        "archetype": "living_room"
                      },
                      "type": "room"
                    }
                  ]
                }
                """);
        setGetResponse("/zone", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "75ee4e3b-cacb-4b87-923b-d11d2480e8ff",
                      "id_v1": "/groups/3",
                      "children": [
                        {
                          "rid": "1271bf6f-be63-42fc-b18c-3ad462914d8e",
                          "rtype": "light"
                        },
                        {
                          "rid": "1aa6083d-3692-49e5-92f7-b926b302dd49",
                          "rtype": "light"
                        }
                      ],
                      "services": [
                        {
                          "rid": "eeb336d9-243b-4756-8455-1c69f50efd31",
                          "rtype": "grouped_light"
                        }
                      ],
                      "metadata": {
                        "name": "Couch",
                        "archetype": "lounge"
                      },
                      "type": "zone"
                    }
                  ]
                }
                """);
        setGetResponse("/scene", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "4b5a905c-cc5e-48be-bc15-84da7deb5da7",
                      "id_v1": "/scenes/CYpbjqIplpzquoRn",
                      "actions": [
                        {
                          "target": {
                            "rid": "a24f1683-3cd9-466f-8072-3eed721af248",
                            "rtype": "light"
                          },
                          "action": {
                            "on": {
                              "on": true
                            },
                            "dimming": {
                              "brightness": 100.0
                            },
                            "color_temperature": {
                              "mirek": 153
                            }
                          }
                        },
                        {
                          "target": {
                            "rid": "57eaaeee-cd72-4b3b-b2dc-7cccbce0bbd7",
                            "rtype": "light"
                          },
                          "action": {
                            "on": {
                              "on": false
                            },
                            "dimming": {
                              "brightness": 47.43
                            },
                            "color": {
                              "xy": {
                                "x": 0.26250000000000009,
                                "y": 0.1357000000000001
                              }
                            }
                          }
                        }
                      ],
                      "metadata": {
                        "name": "Scene_1"
                      },
                      "group": {
                        "rid": "3cfd5fad-2811-430a-a099-ae692b2185f8",
                        "rtype": "room"
                      },
                      "status": {
                        "active": "inactive"
                      },
                      "type": "scene"
                    },
                    {
                      "id": "f96f02db-9765-401c-9aa5-86d59fbdde8e",
                      "id_v1": "/scenes/dWmQtCtTLkoJZPtD",
                      "actions": [
                        {
                          "target": {
                            "rid": "1aa6083d-3692-49e5-92f7-b926b302dd49",
                            "rtype": "light"
                          },
                          "action": {
                            "on": {
                              "on": true
                            },
                            "color": {
                              "xy": {
                                "x": 0.5452,
                                "y": 0.2408
                              }
                            }
                          }
                        },
                        {
                          "target": {
                            "rid": "1271bf6f-be63-42fc-b18c-3ad462914d8e",
                            "rtype": "light"
                          },
                          "action": {
                            "on": {
                              "on": true
                            },
                            "color_temperature": {
                                "mirek": 199,
                                "mirek_valid": true,
                                "mirek_schema": {
                                  "mirek_minimum": 153,
                                  "mirek_maximum": 454
                                }
                              },
                            "dimming": {
                              "brightness": 44.51
                            }
                          }
                        }
                      ],
                      "metadata": {
                        "name": "Scene_2"
                      },
                      "group": {
                        "rid": "75ee4e3b-cacb-4b87-923b-d11d2480e8ff",
                        "rtype": "zone"
                      },
                      "status": {
                        "active": "inactive"
                      },
                      "type": "scene"
                    }
                  ]
                }
                """);

        assertThat(api.getAffectedIdsByScene("4b5a905c-cc5e-48be-bc15-84da7deb5da7"))
                .containsExactly("a24f1683-3cd9-466f-8072-3eed721af248", "4c69c282-3e45-4e87-adb0-fa52e03b4620"); // ignores off light
        assertThat(api.getAffectedIdsByScene("f96f02db-9765-401c-9aa5-86d59fbdde8e"))
                .containsExactly("1aa6083d-3692-49e5-92f7-b926b302dd49", "1271bf6f-be63-42fc-b18c-3ad462914d8e",
                        "eeb336d9-243b-4756-8455-1c69f50efd31");

        assertThat(api.getSceneName("4b5a905c-cc5e-48be-bc15-84da7deb5da7")).isEqualTo("Scene_1");
        assertThat(api.getSceneName("f96f02db-9765-401c-9aa5-86d59fbdde8e")).isEqualTo("Scene_2");
    }

    @Test
    void getSceneLightStates() {
        setGetResponse("/grouped_light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "GROUPED_LIGHT_1",
                      "owner": {
                        "rid": "ZONE_ID",
                        "rtype": "zone"
                      },
                      "type": "grouped_light"
                    }
                  ]
                }""");
        setGetResponse("/zone", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "ZONE_ID",
                      "children": [
                        {
                          "rid": "LIGHT_1",
                          "rtype": "light"
                        },
                        {
                          "rid": "LIGHT_2",
                          "rtype": "light"
                        }
                      ],
                      "services": [
                        {
                          "rid": "GROUPED_LIGHT_1",
                          "rtype": "grouped_light"
                        }
                      ],
                      "metadata": {
                        "name": "Couch",
                        "archetype": "lounge"
                      },
                      "type": "zone"
                    }
                  ]
                }
                """);
        setGetResponse("/scene", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "SCENE_1",
                      "actions": [
                        {
                          "target": {
                            "rid": "LIGHT_1",
                            "rtype": "light"
                          },
                          "action": {
                            "on": {
                              "on": true
                            },
                            "dimming": {
                              "brightness": 100.0
                            },
                            "color_temperature": {
                              "mirek": 153
                            }
                          }
                        },
                        {
                          "target": {
                            "rid": "LIGHT_2",
                            "rtype": "light"
                          },
                          "action": {
                            "on": {
                              "on": false
                            },
                            "dimming": {
                              "brightness": 47.43
                            },
                            "color": {
                              "xy": {
                                "x": 0.2,
                                "y": 0.1
                              }
                            }
                          }
                        }
                      ],
                      "metadata": {
                        "name": "Scene_1"
                      },
                      "group": {
                        "rid": "ZONE_ID",
                        "rtype": "zone"
                      },
                      "status": {
                        "active": "inactive"
                      },
                      "type": "scene"
                    },
                    {
                      "id": "SCENE_2",
                      "actions": [
                        {
                          "target": {
                            "rid": "LIGHT_1",
                            "rtype": "light"
                          },
                          "action": {
                            "on": {
                              "on": true
                            },
                            "color": {
                              "xy": {
                                "x": 0.543,
                                "y": 0.321
                              }
                            }
                          }
                        },
                        {
                          "target": {
                            "rid": "LIGHT_2",
                            "rtype": "light"
                          },
                          "action": {
                            "color_temperature": {
                              "mirek": 199,
                              "mirek_valid": true,
                              "mirek_schema": {
                                "mirek_minimum": 153,
                                "mirek_maximum": 454
                              }
                            },
                            "dimming": {
                              "brightness": 44.51
                            }
                          }
                        }
                      ],
                      "metadata": {
                        "name": "Scene_2"
                      },
                      "group": {
                        "rid": "ZONE_ID",
                        "rtype": "zone"
                      },
                      "status": {
                        "active": "inactive"
                      },
                      "type": "scene"
                    },
                    {
                      "id": "SCENE_3",
                      "actions": [
                        {
                          "target": {
                            "rid": "LIGHT_1",
                            "rtype": "light"
                          },
                          "action": {
                            "on": {
                              "on": true
                            },
                            "effects_v2": {
                              "action": {
                                "effect": "candle",
                                "parameters": {
                                  "color_temperature": {
                                    "mirek": 497
                                  },
                                  "speed": 0.1825
                                }
                              }
                            }
                          }
                        },
                        {
                          "target": {
                            "rid": "LIGHT_2",
                            "rtype": "light"
                          },
                          "action": {
                            "on": {
                              "on": true
                            },
                            "dimming": {
                              "brightness": 100.0
                            },
                            "effects_v2": {
                              "action": {
                                "effect": "prism",
                                "parameters": {
                                  "color": {
                                    "xy": {
                                      "x": 0.211,
                                      "y": 0.075
                                    }
                                  },
                                  "speed": 0.5
                                }
                              }
                            }
                          }
                        }
                      ],
                      "metadata": {
                        "name": "Scene_3"
                      },
                      "group": {
                        "rid": "ZONE_ID",
                        "rtype": "zone"
                      },
                      "status": {
                        "active": "inactive"
                      },
                      "type": "scene"
                    },
                    {
                      "id": "SCENE_4",
                      "actions": [
                        {
                          "target": {
                            "rid": "LIGHT_1",
                            "rtype": "light"
                          },
                          "action": {
                            "on": {
                              "on": true
                            },
                            "effects_v2": {
                              "action": {
                                "effect": "candle",
                                "parameters": {
                                  "speed": 0.1825
                                }
                              }
                            }
                          }
                        },
                        {
                          "target": {
                            "rid": "LIGHT_2",
                            "rtype": "light"
                          },
                          "action": {
                            "on": {
                              "on": true
                            },
                            "effects_v2": {
                              "action": {
                                "effect": "prism"
                              }
                            }
                          }
                        }
                      ],
                      "metadata": {
                        "name": "Scene_4"
                      },
                      "group": {
                        "rid": "ZONE_ID",
                        "rtype": "zone"
                      },
                      "status": {
                        "active": "inactive"
                      },
                      "type": "scene"
                    },
                    {
                      "id": "SCENE_5",
                      "actions": [
                        {
                          "target": {
                            "rid": "LIGHT_1",
                            "rtype": "light"
                          },
                          "action": {
                            "on": {
                              "on": true
                            },
                            "gradient": {
                              "points": [
                                {
                                  "color": {
                                    "xy": {
                                      "x": 0.15352,
                                      "y": 0.06006
                                    }
                                  }
                                },
                                {
                                  "color": {
                                    "xy": {
                                      "x": 0.18649,
                                      "y": 0.09751
                                    }
                                  }
                                },
                                {
                                  "color": {
                                    "xy": {
                                      "x": 0.2256,
                                      "y": 0.09818
                                    }
                                  }
                                }
                              ],
                              "mode": "interpolated_palette"
                            }
                          }
                        },
                        {
                          "target": {
                            "rid": "LIGHT_2",
                            "rtype": "light"
                          },
                          "action": {
                            "on": {
                              "on": true
                            }
                          }
                        }
                      ],
                      "metadata": {
                        "name": "Scene_5"
                      },
                      "group": {
                        "rid": "ZONE_ID",
                        "rtype": "zone"
                      },
                      "status": {
                        "active": "inactive"
                      },
                      "type": "scene"
                    }
                  ]
                }
                """);

        assertSceneLightStates("GROUPED_LIGHT_1", "Scene_1",
                ScheduledLightState.builder()
                                   .id("LIGHT_1")
                                   // no "on"
                                   .bri(254)
                                   .ct(153),
                ScheduledLightState.builder()
                                   .id("LIGHT_2")
                                   .on(false)
                                   .bri(120)
                                   .x(0.2)
                                   .y(0.1)
        );

        assertSceneLightStates("GROUPED_LIGHT_1", "Scene_2",
                ScheduledLightState.builder()
                                   .id("LIGHT_1")
                                   // no "on"
                                   .x(0.543)
                                   .y(0.321),
                ScheduledLightState.builder()
                                   .id("LIGHT_2")
                                   // no "on"
                                   .bri(113)
                                   .ct(199)
        );

        assertSceneLightStates("GROUPED_LIGHT_1", "Scene_3",
                ScheduledLightState.builder()
                                   .id("LIGHT_1")
                                   .effect(Effect.builder()
                                                 .effect("candle")
                                                 .ct(497)
                                                 .speed(0.1825)
                                                 .build()),
                ScheduledLightState.builder()
                                   .id("LIGHT_2")
                                   .bri(254)
                                   .effect(Effect.builder()
                                                 .effect("prism")
                                                 .x(0.211)
                                                 .y(0.075)
                                                 .speed(0.5)
                                                 .build())
        );

        assertSceneLightStates("GROUPED_LIGHT_1", "Scene_4",
                ScheduledLightState.builder()
                                   .id("LIGHT_1")
                                   .effect(Effect.builder()
                                                 .effect("candle")
                                                 .speed(0.1825)
                                                 .build()),
                ScheduledLightState.builder()
                                   .id("LIGHT_2")
                                   .effect(Effect.builder()
                                                 .effect("prism")
                                                 .build())
        );

        assertSceneLightStates("GROUPED_LIGHT_1", "Scene_5",
                ScheduledLightState.builder()
                                   .id("LIGHT_1")
                                   .gradient(Gradient.builder()
                                                     .points(List.of(
                                                             Pair.of(0.15352, 0.06006),
                                                             Pair.of(0.18649, 0.09751),
                                                             Pair.of(0.2256, 0.09818)
                                                     ))
                                                     .mode("interpolated_palette")
                                                     .build()),
                ScheduledLightState.builder()
                                   .id("LIGHT_2")
        );

        assertSceneLightStates("GROUPED_LIGHT_1", "UNKNOWN_SCENE"); // empty list for unknown scene

        assertThrows(GroupNotFoundException.class, () -> api.getSceneLightState("UNKNOWN_GROUP", "Scene_1"));

    }

    @Test
    void getAffectedIdsByDevice_returnsLightIdsOfDevice_andTheirContainedGroups() {
        setGetResponse("/device", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "DEVICE",
                      "metadata": {
                        "name": "Schreibtisch R",
                        "archetype": "sultan_bulb"
                      },
                      "services": [
                        {
                          "rid": "d64efab2-f44a-45de-85c4-26f8857482f1",
                          "rtype": "zigbee_connectivity"
                        },
                        {
                          "rid": "LIGHT_1_1",
                          "rtype": "light"
                        },
                        {
                          "rid": "LIGHT_1_2",
                          "rtype": "light"
                        }
                      ],
                      "type": "device"
                    }
                  ]
                }
                """);
        setGetResponse("/room", EMPTY_RESPONSE);
        setGetResponse("/zone", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "ZONE_1",
                      "children": [
                        {
                          "rid": "LIGHT_1_1",
                          "rtype": "light"
                        },
                        {
                          "rid": "IGNORED_LIGHT",
                          "rtype": "light"
                        }
                      ],
                      "services": [
                        {
                          "rid": "GROUPED_LIGHT_1",
                          "rtype": "grouped_light"
                        }
                      ],
                      "metadata": {
                        "name": "Couch",
                        "archetype": "lounge"
                      },
                      "type": "zone"
                    },
                    {
                      "id": "ZONE_2",
                      "children": [
                        {
                          "rid": "LIGHT_1_2",
                          "rtype": "light"
                        }
                      ],
                      "services": [
                        {
                          "rid": "GROUPED_LIGHT_2",
                          "rtype": "grouped_light"
                        }
                      ],
                      "metadata": {
                        "name": "Couch",
                        "archetype": "lounge"
                      },
                      "type": "zone"
                    }
                  ]
                }
                """);

        assertThat(api.getAffectedIdsByDevice("UNKNOWN")).isEmpty();
        assertThat(api.getAffectedIdsByDevice("DEVICE")).containsExactly("LIGHT_1_1", "LIGHT_1_2", "GROUPED_LIGHT_1",
                "GROUPED_LIGHT_2");
    }

    @Test
    void getAssignedGroups_givenLightId_returnsGroupedLightIds() {
        setGetResponse("/room", EMPTY_RESPONSE);
        setGetResponse("/zone", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "GROUP_ID_1",
                      "id_v1": "/groups/9",
                      "children": [
                        {
                          "rid": "1",
                          "rtype": "light"
                        },
                        {
                          "rid": "2",
                          "rtype": "light"
                        },
                        {
                          "rid": "3",
                          "rtype": "light"
                        }
                      ],
                      "services": [
                        {
                          "rid": "GROUPED_LIGHT_ID_1",
                          "rtype": "grouped_light"
                        }
                      ],
                      "metadata": {
                        "name": "Oben",
                        "archetype": "upstairs"
                      },
                      "type": "zone"
                    },
                    {
                      "id": "GROUP_ID_2",
                      "id_v1": "/groups/82",
                      "children": [
                        {
                          "rid": "4",
                          "rtype": "light"
                        },
                        {
                          "rid": "5",
                          "rtype": "light"
                        },
                        {
                          "rid": "3",
                          "rtype": "light"
                        }
                      ],
                      "services": [
                        {
                          "rid": "IGNORED_ID",
                          "rtype": "another_service"
                        },
                        {
                          "rid": "GROUPED_LIGHT_ID_2",
                          "rtype": "grouped_light"
                        }
                      ],
                      "metadata": {
                        "name": "Bad indirekt",
                        "archetype": "attic"
                      },
                      "type": "zone"
                    }
                  ]
                }""");

        assertThat(getAssignedGroups("2")).containsExactly("GROUPED_LIGHT_ID_1");
        assertThat(getAssignedGroups("3")).containsExactlyInAnyOrder("GROUPED_LIGHT_ID_1", "GROUPED_LIGHT_ID_2");
        assertThat(getAssignedGroups("777")).isEmpty();
    }

    @Test
    void getAssignedGroups_givenLightId_returnsGroupedLightIds_noIdPresent_exception() {
        setGetResponse("/room", EMPTY_RESPONSE);
        setGetResponse("/zone", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "GROUP_ID_1",
                      "id_v1": "/groups/9",
                      "children": [
                        {
                          "rid": "1",
                          "rtype": "light"
                        },
                        {
                          "rid": "2",
                          "rtype": "light"
                        },
                        {
                          "rid": "3",
                          "rtype": "light"
                        }
                      ],
                      "services": [],
                      "metadata": {
                        "name": "Oben",
                        "archetype": "upstairs"
                      },
                      "type": "zone"
                    }
                  ]
                }""");

        assertThrows(GroupNotFoundException.class, () -> getAssignedGroups("2"));
    }

    @Test
    void getGroupLights_zone_emptyLights_exception() {
        setGetResponse("/grouped_light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "b3bd0551-312f-4b6d-bf87-432e5ef56b69",
                      "id_v1": "/groups/81",
                      "owner": {
                        "rid": "3346a743-5c88-4198-b21f-4632dfa3549f",
                        "rtype": "zone"
                      },
                      "type": "grouped_light"
                    }
                  ]
                }""");
        setGetResponse("/zone", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "3346a743-5c88-4198-b21f-4632dfa3549f",
                      "id_v1": "/groups/81",
                      "children": [],
                      "services": [
                        {
                          "rid": "b3bd0551-312f-4b6d-bf87-432e5ef56b69",
                          "rtype": "grouped_light"
                        }
                      ],
                      "metadata": {
                        "name": "Zone 1",
                        "archetype": "top_floor"
                      },
                      "type": "zone"
                    }
                  ]
                }
                """);

        assertThrows(EmptyGroupException.class, () -> getGroupLights("b3bd0551-312f-4b6d-bf87-432e5ef56b69"));
    }

    @Test
    void getGroupLights_room_emptyLights_exception() {
        setGetResponse("/grouped_light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "e6bafd35-2246-4c5b-b0ac-5957d9344dc4",
                      "id_v1": "/groups/8",
                      "owner": {
                        "rid": "ba9c4460-0ea4-407c-93bf-3837e6b9888f",
                        "rtype": "room"
                      },
                      "type": "grouped_light"
                    }
                  ]
                }""");
        setGetResponse("/room", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "ba9c4460-0ea4-407c-93bf-3837e6b9888f",
                      "id_v1": "/groups/8",
                      "children": [
                        {
                          "rid": "7d3b3371-50dd-4b89-a1fc-c8ddd761a6b0",
                          "rtype": "device"
                        }
                      ],
                      "services": [
                        {
                          "rid": "e6bafd35-2246-4c5b-b0ac-5957d9344dc4",
                          "rtype": "grouped_light"
                        }
                      ],
                      "metadata": {
                        "name": "Room"
                      },
                      "type": "room"
                    }
                  ]
                }
                """);
        setGetResponse("/device", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "7d3b3371-50dd-4b89-a1fc-c8ddd761a6b0",
                      "id_v1": "/lights/30",
                      "metadata": {
                        "name": "Ceiling",
                        "archetype": "sultan_bulb"
                      },
                      "services": [
                        {
                          "rid": "0fd6308b-778c-483d-a12b-f7cbbaaefefc",
                          "rtype": "zigbee_connectivity"
                        },
                        {
                          "rid": "266ea722-fd3e-4563-a7a4-f4fcb7941534",
                          "rtype": "entertainment"
                        },
                        {
                          "rid": "9e8117d2-b233-4f6a-ae42-855a7f8e76a8",
                          "rtype": "taurus_7455"
                        },
                        {
                          "rid": "4987f41c-7d32-4288-aa82-d58bd1c7fcd9",
                          "rtype": "device_software_update"
                        }
                      ],
                      "type": "device"
                    }
                  ]
                }
                """);

        assertThrows(EmptyGroupException.class, () -> getGroupLights("e6bafd35-2246-4c5b-b0ac-5957d9344dc4"));
    }

    @Test
    void getGroupLights_unknownId_exception() {
        setGetResponse("/grouped_light", EMPTY_RESPONSE);

        assertThrows(GroupNotFoundException.class, () -> getGroupLights("75ee4e3b-cacb-4b87-923b-d11d2480e8ff"));
    }

    @Test
    void getGroupLights_emptyResponse_exception() {
        setGetResponse("/grouped_light", "");

        assertThrows(ApiFailure.class, () -> getGroupLights("75ee4e3b-cacb-4b87-923b-d11d2480e8ff"));
    }

    @Test
    void getLightMetadata_lightNotFound_exception() {
        setGetResponse("/light", EMPTY_RESPONSE);

        assertThrows(ResourceNotFoundException.class, () -> api.getLightIdentifier("/lights/UNKNOWN"));
    }

    @Test
    void getGroupMetadata_groupNotFound_exception() {
        setGetResponse("/room", EMPTY_RESPONSE);
        setGetResponse("/zone", EMPTY_RESPONSE);

        assertThrows(ResourceNotFoundException.class, () -> api.getGroupIdentifier("/groups/UNKNOWN"));
    }

    @Test
    void getLightId_returnsIdForLightName() {
        setGetResponse("/light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "426ab1f6-c27f-42ba-b18d-0783665b4e21",
                      "id_v1": "/lights/32",
                      "owner": {
                        "rid": "23e0ec8f-f096-4e9c-a6d5-4d7efe98ace6",
                        "rtype": "device"
                      },
                      "metadata": {
                        "name": "Fan",
                        "archetype": "plug",
                        "function": "functional"
                      },
                      "product_data": {
                        "function": "functional"
                      },
                      "identify": {},
                      "service_id": 0,
                      "on": {
                        "on": false
                      },
                      "type": "light"
                    },
                    {
                      "id": "94eb9b65-f310-4c31-907a-89da8ef7ccdd",
                      "id_v1": "/lights/50",
                      "owner": {
                        "rid": "645a6898-1a16-47af-aa17-7cc4c348f337",
                        "rtype": "device"
                      },
                      "metadata": {
                        "name": "Another lamp",
                        "archetype": "sultan_bulb",
                        "fixed_mired": 366,
                        "function": "mixed"
                      },
                      "product_data": {
                        "function": "mixed"
                      },
                      "identify": {},
                      "service_id": 0,
                      "on": {
                        "on": true
                      },
                      "dimming": {
                        "brightness": 100.0,
                        "min_dim_level": 5.0
                      },
                      "type": "light"
                    }
                  ]
                }""");

        assertLightIdentifier("/lights/32", "426ab1f6-c27f-42ba-b18d-0783665b4e21", "Fan");
        assertLightIdentifier("/lights/50", "94eb9b65-f310-4c31-907a-89da8ef7ccdd", "Another lamp");
    }

    @Test
    void getLightIdentifierByName_unknownName_exception() {
        setGetResponse("/light", EMPTY_RESPONSE);

        assertThrows(LightNotFoundException.class, () -> api.getLightIdentifierByName("Lamp"));
    }

    @Test
    void getLightIdentifierByName_emptyResponse_exception() {
        setGetResponse("/light", "");

        assertThrows(ApiFailure.class, () -> api.getLightIdentifierByName("Lamp"));
    }

    @Test
    void getGroupIdentifierByName_unknownName_exception() {
        setGetResponse("/room", EMPTY_RESPONSE);
        setGetResponse("/zone", EMPTY_RESPONSE);

        assertThrows(GroupNotFoundException.class, () -> api.getGroupIdentifierByName("Unknown Group"));
    }

    @Test
    void getGroupIdentifierByName_emptyResponse_exception() {
        setGetResponse("/room", "");
        setGetResponse("/zone", "");

        assertThrows(ApiFailure.class, () -> api.getGroupIdentifierByName("Group"));
    }

    @Test
    void getCapabilities_hasColorAndCtSupport() {
        setGetResponse("/light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "2697622a-f39a-4f0e-b42e-651f94b4b983",
                      "id_v1": "/lights/27",
                      "owner": {
                        "rid": "2f279281-3e45-462c-9e89-6b2d3363d883",
                        "rtype": "device"
                      },
                      "metadata": {
                        "name": "Schreibtisch R",
                        "archetype": "sultan_bulb",
                        "function": "mixed"
                      },
                      "product_data": {
                        "function": "mixed"
                      },
                      "identify": {},
                      "service_id": 0,
                      "on": {
                        "on": true
                      },
                      "dimming": {
                        "brightness": 7.51,
                        "min_dim_level": 0.20000000298023225
                      },
                      "dimming_delta": {},
                      "color_temperature": {
                        "mirek": null,
                        "mirek_valid": false,
                        "mirek_schema": {
                          "mirek_minimum": 153,
                          "mirek_maximum": 500
                        }
                      },
                      "color_temperature_delta": {},
                      "color": {
                        "xy": {
                          "x": 0.6025,
                          "y": 0.3433
                        },
                        "gamut": {
                          "red": {
                            "x": 0.6915,
                            "y": 0.3083
                          },
                          "green": {
                            "x": 0.17,
                            "y": 0.7
                          },
                          "blue": {
                            "x": 0.1532,
                            "y": 0.0475
                          }
                        },
                        "gamut_type": "C"
                      },
                      "dynamics": {
                        "status": "none",
                        "status_values": [
                          "none",
                          "dynamic_palette"
                        ],
                        "speed": 0.0,
                        "speed_valid": false
                      },
                      "alert": {
                        "action_values": [
                          "breathe"
                        ]
                      },
                      "signaling": {
                        "signal_values": [
                          "no_signal",
                          "on_off",
                          "on_off_color",
                          "alternating"
                        ]
                      },
                      "mode": "normal",
                      "effects_v2": {
                        "action": {
                          "effect_values": [
                            "no_effect",
                            "candle",
                            "fire",
                            "prism",
                            "sparkle",
                            "opal",
                            "glisten",
                            "underwater",
                            "cosmos",
                            "sunbeam",
                            "enchant"
                          ]
                        },
                        "status": {
                          "effect": "no_effect",
                          "effect_values": [
                            "no_effect",
                            "candle",
                            "fire",
                            "prism",
                            "sparkle",
                            "opal",
                            "glisten",
                            "underwater",
                            "cosmos",
                            "sunbeam",
                            "enchant"
                          ]
                        }
                      },
                      "timed_effects": {
                        "status_values": [
                          "no_effect",
                          "sunrise",
                          "sunset"
                        ],
                        "status": "no_effect",
                        "effect_values": [
                          "no_effect",
                          "sunrise",
                          "sunset"
                        ]
                      }
                    }
                  ]
                }
                """);

        LightCapabilities capabilities = getLightCapabilities("2697622a-f39a-4f0e-b42e-651f94b4b983");

        assertCapabilities(
                capabilities,
                LightCapabilities.builder()
                                 .colorGamutType("C")
                                 .colorGamut(GAMUT_C)
                                 .ctMin(153)
                                 .ctMax(500)
                                 .effects(List.of("candle", "fire", "prism", "sparkle", "opal", "glisten", "underwater",
                                         "cosmos", "sunbeam", "enchant"))
                                 .capabilities(EnumSet.of(Capability.COLOR, Capability.COLOR_TEMPERATURE,
                                         Capability.BRIGHTNESS, Capability.ON_OFF))
                                 .build()
        );
        assertThat(capabilities.isColorSupported()).isTrue();
        assertThat(capabilities.isCtSupported()).isTrue();
        assertThat(capabilities.isBrightnessSupported()).isTrue();
    }

    @Test
    void getCapabilities_colorOnly() {
        setGetResponse("/light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "2697622a-f39a-4f0e-b42e-651f94b4b983",
                      "id_v1": "/lights/27",
                      "owner": {
                        "rid": "2f279281-3e45-462c-9e89-6b2d3363d883",
                        "rtype": "device"
                      },
                      "metadata": {
                        "name": "Color only",
                        "archetype": "sultan_bulb",
                        "function": "mixed"
                      },
                      "product_data": {
                        "function": "mixed"
                      },
                      "identify": {},
                      "service_id": 0,
                      "on": {
                        "on": true
                      },
                      "dimming": {
                        "brightness": 7.51,
                        "min_dim_level": 0.20000000298023225
                      },
                      "dimming_delta": {},
                      "color_temperature_delta": {},
                      "color": {
                        "xy": {
                          "x": 0.6025,
                          "y": 0.3433
                        },
                        "gamut": {
                          "red": {
                            "x": 0.704,
                            "y": 0.296
                          },
                          "green": {
                            "x": 0.2151,
                            "y": 0.7106
                          },
                          "blue": {
                            "x": 0.138,
                            "y": 0.08
                          }
                        },
                        "gamut_type": "A"
                      },
                      "mode": "normal",
                      "type": "light"
                    }
                  ]
                }""");

        LightCapabilities capabilities = getLightCapabilities("2697622a-f39a-4f0e-b42e-651f94b4b983");

        assertCapabilities(
                capabilities,
                LightCapabilities.builder()
                                 .colorGamutType("A")
                                 .colorGamut(GAMUT_A)
                                 .capabilities(EnumSet.of(Capability.COLOR, Capability.BRIGHTNESS, Capability.ON_OFF))
                                 .build()
        );
        assertThat(capabilities.isColorSupported()).isTrue();
        assertThat(capabilities.isCtSupported()).isFalse();
        assertThat(capabilities.isBrightnessSupported()).isTrue();
    }

    @Test
    void getCapabilities_brightnessOnly() {
        setGetResponse("/light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "94eb9b65-f310-4c31-907a-89da8ef7ccdd",
                      "id_v1": "/lights/50",
                      "owner": {
                        "rid": "645a6898-1a16-47af-aa17-7cc4c348f337",
                        "rtype": "device"
                      },
                      "metadata": {
                        "name": "Hue white lamp 1",
                        "archetype": "sultan_bulb",
                        "fixed_mired": 366,
                        "function": "mixed"
                      },
                      "product_data": {
                        "function": "mixed"
                      },
                      "identify": {},
                      "service_id": 0,
                      "on": {
                        "on": true
                      },
                      "dimming": {
                        "brightness": 100.0,
                        "min_dim_level": 5.0
                      },
                      "dimming_delta": {},
                      "dynamics": {
                        "status": "none",
                        "status_values": [
                          "none"
                        ],
                        "speed": 0.0,
                        "speed_valid": false
                      },
                      "alert": {
                        "action_values": [
                          "breathe"
                        ]
                      },
                      "signaling": {
                        "signal_values": [
                          "no_signal",
                          "on_off"
                        ]
                      },
                      "mode": "normal",
                      "effects_v2": {
                        "action": {
                          "effect_values": [
                            "no_effect",
                            "candle"
                          ]
                        },
                        "status": {
                          "effect": "no_effect",
                          "effect_values": [
                            "no_effect",
                            "candle"
                          ]
                        }
                      },
                      "type": "light"
                    }
                  ]
                }""");

        LightCapabilities capabilities = getLightCapabilities("94eb9b65-f310-4c31-907a-89da8ef7ccdd");

        assertCapabilities(capabilities, LightCapabilities.builder()
                                                          .effects(List.of("candle"))
                                                          .capabilities(EnumSet.of(Capability.BRIGHTNESS, Capability.ON_OFF))
                                                          .build());
        assertThat(capabilities.isColorSupported()).isFalse();
        assertThat(capabilities.isCtSupported()).isFalse();
        assertThat(capabilities.isBrightnessSupported()).isTrue();
    }

    @Test
    void getCapabilities_colorTemperatureOnly() {
        setGetResponse("/light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "c1f7a58f-4267-4ead-8957-9587301a3ba6",
                      "id_v1": "/lights/39",
                      "owner": {
                        "rid": "f36455ed-7b92-4e5a-97ba-73d804ab03da",
                        "rtype": "device"
                      },
                      "metadata": {
                        "name": "Flur S Mitte",
                        "archetype": "spot_bulb",
                        "function": "mixed"
                      },
                      "product_data": {
                        "function": "mixed"
                      },
                      "identify": {},
                      "service_id": 0,
                      "on": {
                        "on": true
                      },
                      "dimming": {
                        "brightness": 100.0,
                        "min_dim_level": 0.20000000298023225
                      },
                      "dimming_delta": {},
                      "color_temperature": {
                        "mirek": 199,
                        "mirek_valid": true,
                        "mirek_schema": {
                          "mirek_minimum": 153,
                          "mirek_maximum": 454
                        }
                      },
                      "color_temperature_delta": {},
                      "dynamics": {
                        "status": "none",
                        "status_values": [
                          "none"
                        ],
                        "speed": 0.0,
                        "speed_valid": false
                      },
                      "alert": {
                        "action_values": [
                          "breathe"
                        ]
                      },
                      "signaling": {
                        "signal_values": [
                          "no_signal",
                          "on_off"
                        ]
                      },
                      "mode": "normal",
                      "effects_v2": {
                        "action": {
                          "effect_values": [
                            "no_effect",
                            "candle",
                            "sparkle",
                            "glisten"
                          ]
                        },
                        "status": {
                          "effect": "no_effect",
                          "effect_values": [
                            "no_effect",
                            "candle",
                            "sparkle",
                            "glisten"
                          ]
                        }
                      },
                      "timed_effects": {
                        "status_values": [
                          "no_effect",
                          "sunrise",
                          "sunset"
                        ],
                        "status": "no_effect",
                        "effect_values": [
                          "no_effect",
                          "sunrise",
                          "sunset"
                        ]
                      },
                      "type": "light"
                    }
                  ]
                }""");

        LightCapabilities capabilities = getLightCapabilities("c1f7a58f-4267-4ead-8957-9587301a3ba6");

        assertCapabilities(
                capabilities,
                LightCapabilities.builder()
                                 .ctMin(153)
                                 .ctMax(454)
                                 .effects(List.of("candle", "sparkle", "glisten"))
                                 .capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE, Capability.BRIGHTNESS, Capability.ON_OFF)).build()
        );
        assertThat(capabilities.isColorSupported()).isFalse();
        assertThat(capabilities.isCtSupported()).isTrue();
        assertThat(capabilities.isBrightnessSupported()).isTrue();
    }

    @Test
    void getCapabilities_onOffOnly() {
        setGetResponse("/light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "426ab1f6-c27f-42ba-b18d-0783665b4e21",
                      "id_v1": "/lights/32",
                      "owner": {
                        "rid": "23e0ec8f-f096-4e9c-a6d5-4d7efe98ace6",
                        "rtype": "device"
                      },
                      "metadata": {
                        "name": "Fan",
                        "archetype": "plug",
                        "function": "functional"
                      },
                      "product_data": {
                        "function": "functional"
                      },
                      "identify": {},
                      "service_id": 0,
                      "on": {
                        "on": false
                      },
                      "dynamics": {
                        "status": "none",
                        "status_values": [
                          "none"
                        ],
                        "speed": 0.0,
                        "speed_valid": false
                      },
                      "alert": {
                        "action_values": []
                      },
                      "mode": "normal",
                      "type": "light"
                    }
                  ]
                }
                """);

        LightCapabilities capabilities = getLightCapabilities("426ab1f6-c27f-42ba-b18d-0783665b4e21");

        assertCapabilities(capabilities, LightCapabilities.builder().capabilities(EnumSet.of(Capability.ON_OFF)).build());
        assertThat(capabilities.isColorSupported()).isFalse();
        assertThat(capabilities.isCtSupported()).isFalse();
        assertThat(capabilities.isBrightnessSupported()).isFalse();
    }

    @Test
    void getCapabilities_unknownId_exception() {
        setGetResponse("/light", EMPTY_RESPONSE);

        assertThrows(LightNotFoundException.class, () -> getLightCapabilities("426ab1f6-c27f-42ba-b18d-0783665b4e21"));
    }

    @Test
    void getGroupCapabilities_returnsMaxOfAllContainedLights() {
        setGetResponse("/grouped_light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "GroupedLight_Zone_1",
                      "owner": {
                        "rid": "Zone_1",
                        "rtype": "zone"
                      },
                      "type": "grouped_light"
                    },
                    {
                      "id": "GroupedLight_Zone_2",
                      "owner": {
                        "rid": "Zone_2",
                        "rtype": "zone"
                      },
                      "type": "grouped_light"
                    },
                    {
                      "id": "GroupedLight_Zone_3",
                      "owner": {
                        "rid": "Zone_3",
                        "rtype": "zone"
                      },
                      "type": "grouped_light"
                    },
                    {
                      "id": "GroupedLight_Zone_4",
                      "owner": {
                        "rid": "Zone_4",
                        "rtype": "zone"
                      },
                      "type": "grouped_light"
                    },
                    {
                      "id": "GroupedLight_Zone_5",
                      "owner": {
                        "rid": "Zone_5",
                        "rtype": "zone"
                      },
                      "type": "grouped_light"
                    },
                    {
                      "id": "GroupedLight_Zone_6",
                      "owner": {
                        "rid": "Zone_6",
                        "rtype": "zone"
                      },
                      "type": "grouped_light"
                    },
                    {
                      "id": "GroupedLight_Zone_7",
                      "owner": {
                        "rid": "Zone_7",
                        "rtype": "zone"
                      },
                      "type": "grouped_light"
                    }
                  ]
                }""");
        setGetResponse("/zone", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "Zone_1",
                      "children": [
                        {
                          "rid": "42",
                          "rtype": "light"
                        },
                        {
                          "rid": "22",
                          "rtype": "light"
                        },
                        {
                          "rid": "30",
                          "rtype": "light"
                        },
                        {
                          "rid": "25",
                          "rtype": "light"
                        }
                      ],
                      "type": "zone"
                    },
                    {
                      "id": "Zone_2",
                      "children": [
                        {
                          "rid": "22",
                          "rtype": "light"
                        }
                      ],
                      "type": "zone"
                    },
                    {
                      "id": "Zone_3",
                      "children": [
                        {
                          "rid": "42",
                          "rtype": "light"
                        }
                      ],
                      "type": "zone"
                    },
                    {
                      "id": "Zone_4",
                      "children": [
                        {
                          "rid": "30",
                          "rtype": "light"
                        }
                      ],
                      "type": "zone"
                    },
                    {
                      "id": "Zone_5",
                      "children": [
                        {
                          "rid": "23",
                          "rtype": "light"
                        }
                      ],
                      "type": "zone"
                    },
                    {
                      "id": "Zone_6",
                      "children": [
                        {
                          "rid": "22",
                          "rtype": "light"
                        },
                        {
                          "rid": "23",
                          "rtype": "light"
                        }
                      ],
                      "type": "zone"
                    },
                    {
                      "id": "Zone_7",
                      "children": [
                        {
                          "rid": "24",
                          "rtype": "light"
                        }
                      ],
                      "type": "zone"
                    }
                  ]
                }
                """);
        setGetResponse("/light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "42",
                      "metadata": {
                        "name": "Color temperature light"
                      },
                      "dimming": {
                        "brightness": 100.0,
                        "min_dim_level": 2.0
                      },
                      "color_temperature": {
                        "mirek": 199,
                        "mirek_valid": true,
                        "mirek_schema": {
                          "mirek_minimum": 100,
                          "mirek_maximum": 454
                        }
                      },
                      "type": "light"
                    },
                    {
                      "id": "22",
                      "metadata": {
                        "name": "Color light"
                      },
                      "dimming": {
                        "brightness": 100.0,
                        "min_dim_level": 0.009999999776482582
                      },
                      "color": {
                        "xy": {
                          "x": 0.3448,
                          "y": 0.3553
                        },
                        "gamut": {
                          "red": {
                            "x": 0.6915,
                            "y": 0.3083
                          },
                          "green": {
                            "x": 0.17,
                            "y": 0.7
                          },
                          "blue": {
                            "x": 0.1532,
                            "y": 0.0475
                          }
                        },
                        "gamut_type": "C"
                      },
                      "type": "light"
                    },
                    {
                      "id": "23",
                      "metadata": {
                        "name": "Color light"
                      },
                      "dimming": {
                        "brightness": 100.0,
                        "min_dim_level": 0.009999999776482582
                      },
                      "color": {
                        "xy": {
                          "x": 0.6404,
                          "y": 0.3277
                        },
                        "gamut": {
                          "red": {
                            "x": 0.704,
                            "y": 0.296
                          },
                          "green": {
                            "x": 0.2151,
                            "y": 0.7106
                          },
                          "blue": {
                            "x": 0.138,
                            "y": 0.08
                          }
                        },
                        "gamut_type": "A"
                      },
                      "type": "light"
                    },
                    {
                      "id": "24",
                      "metadata": {
                        "name": "Color light"
                      },
                      "dimming": {
                        "brightness": 100.0,
                        "min_dim_level": 0.009999999776482582
                      },
                      "color": {
                        "xy": {
                          "x": 0.6404,
                          "y": 0.3277
                        },
                        "gamut": {
                          "red": {
                            "x": 0.675,
                            "y": 0.322
                          },
                          "green": {
                            "x": 0.409,
                            "y": 0.518
                          },
                          "blue": {
                            "x": 0.167,
                            "y": 0.04
                          }
                        },
                        "gamut_type": "B"
                      },
                      "type": "light"
                    },
                    {
                      "id": "25",
                      "metadata": {
                        "name": "Extended color light"
                      },
                      "dimming": {
                        "brightness": 100.0,
                        "min_dim_level": 0.009999999776482582
                      },
                      "color_temperature": {
                        "mirek": null,
                        "mirek_valid": false,
                        "mirek_schema": {
                          "mirek_minimum": 153,
                          "mirek_maximum": 500
                        }
                      },
                      "color": {
                        "xy": {
                          "x": 0.6024,
                          "y": 0.3433
                        },
                        "gamut": {
                          "red": {
                            "x": 0.6915,
                            "y": 0.3083
                          },
                          "green": {
                            "x": 0.17,
                            "y": 0.7
                          },
                          "blue": {
                            "x": 0.1532,
                            "y": 0.0475
                          }
                        },
                        "gamut_type": "C"
                      },
                      "type": "light"
                    },
                    {
                      "id": "30",
                      "metadata": {
                        "name": "On/off plug-in unit"
                      },
                      "type": "light"
                    }
                  ]
                }""");

        assertCapabilities(
                getGroupCapabilities("GroupedLight_Zone_1"),
                LightCapabilities.builder()
                                 .colorGamut(GAMUT_C)
                                 .ctMin(100)
                                 .ctMax(500)
                                 .capabilities(EnumSet.of(Capability.COLOR, Capability.COLOR_TEMPERATURE, Capability.BRIGHTNESS,
                                         Capability.ON_OFF))
                                 .build()
        );
        assertCapabilities(
                getGroupCapabilities("GroupedLight_Zone_2"),
                LightCapabilities.builder()
                                 .colorGamut(GAMUT_C)
                                 .capabilities(EnumSet.of(Capability.COLOR, Capability.BRIGHTNESS, Capability.ON_OFF))
                                 .build());
        assertCapabilities(
                getGroupCapabilities("GroupedLight_Zone_3"),
                LightCapabilities.builder()
                                 .ctMin(100)
                                 .ctMax(454)
                                 .capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE, Capability.BRIGHTNESS, Capability.ON_OFF))
                                 .build()
        );
        assertCapabilities(
                getGroupCapabilities("GroupedLight_Zone_4"),
                LightCapabilities.builder()
                                 .capabilities(EnumSet.of(Capability.ON_OFF))
                                 .build()
        );
        assertCapabilities(
                getGroupCapabilities("GroupedLight_Zone_5"),
                LightCapabilities.builder()
                                 .colorGamut(GAMUT_A)
                                 .capabilities(EnumSet.of(Capability.COLOR, Capability.BRIGHTNESS, Capability.ON_OFF))
                                 .build());
        assertCapabilities(
                getGroupCapabilities("GroupedLight_Zone_6"),
                LightCapabilities.builder()
                                 .colorGamut(GAMUT_C)
                                 .capabilities(EnumSet.of(Capability.COLOR, Capability.BRIGHTNESS, Capability.ON_OFF))
                                 .build());
        assertCapabilities(
                getGroupCapabilities("GroupedLight_Zone_7"),
                LightCapabilities.builder()
                                 .colorGamut(GAMUT_B)
                                 .capabilities(EnumSet.of(Capability.COLOR, Capability.BRIGHTNESS, Capability.ON_OFF))
                                 .build());
    }

    @Test
    void putState_brightness_success_callsCorrectUrl() {
        performPutCall(PutCall.builder().id("2697622a-f39a-4f0e-b42e-651f94b4b983").bri(127).build());

        verifyPut("/light/2697622a-f39a-4f0e-b42e-651f94b4b983", """
                {
                  "dimming": {
                    "brightness": 50.0
                  }
                }""");
    }

    @Test
    void putState_group_usesCorrectUrl() {
        String groupedLightId = "eeb336d9-243b-4756-8455-1c69f50efd31";
        performGroupPutCall(PutCall.builder().id(groupedLightId).bri(127).build());

        verifyPut("/grouped_light/" + groupedLightId, """
                {
                  "dimming": {
                    "brightness": 50.0
                  }
                }""");
    }

    @Test
    void putState_ct_correctBody() {
        performPutCall(PutCall.builder().id("ID").ct(100).build());

        verifyPut("/light/ID", """
                {
                  "color_temperature": {
                    "mirek": 100
                  }
                }""");
    }

    @Test
    void putState_XAndY_correctBody() {
        performPutCall(PutCall.builder().id("ID").x(0.6075).y(0.3525).build());

        verifyPut("/light/ID", """
                {
                  "color": {
                    "xy": {
                      "x": 0.6075,
                      "y": 0.3525
                    }
                  }
                }""");
    }

    @Test
    void putState_XAndY_performsGamutCorrection() {
        performPutCall(PutCall.builder().id("ID").x(0.15).y(0.3).build());

        verifyPut("/light/ID", """
                {
                  "color": {
                    "xy": {
                      "x": 0.15969472265287582,
                      "y": 0.29975038875008686
                    }
                  }
                }""");
    }

    @Test
    void putState_on_setsFlagCorrectly() {
        performPutCall(PutCall.builder().id("ID").on(true).build());

        verifyPut("/light/ID", """
                {
                  "on": {
                    "on": true
                  }
                }""");
    }

    @Test
    void putState_off_setsFlagCorrectly() {
        performPutCall(PutCall.builder().id("ID").on(false).build());

        verifyPut("/light/ID", """
                {
                  "on": {
                    "on": false
                  }
                }""");
    }

    @Test
    void putState_transitionTime_setsTimeCorrectly() {
        performPutCall(PutCall.builder().id("ID").transitionTime(2).build());

        verifyPut("/light/ID", """
                {
                  "dynamics": {
                    "duration": 200
                  }
                }""");
    }

    @Test
    void putState_transitionTime_defaultValueOfFour_isIgnored() {
        performPutCall(PutCall.builder().id("ID").transitionTime(4).build());

        verifyPut("/light/ID", "{}");
    }

    @Test
    void putState_effect_correctlySet() {
        performPutCall(PutCall.builder().id("ID").effect(Effect.builder().effect("opal").build()).build());

        verifyPut("/light/ID", """
                {
                  "effects_v2": {
                    "action": {
                      "effect": "opal"
                    }
                  }
                }""");
    }

    @Test
    void putState_effect_none_usesCorrectValue() {
        performPutCall(PutCall.builder().id("ID").effect(Effect.builder().effect("none").build()).build());

        verifyPut("/light/ID", """
                {
                  "effects_v2": {
                    "action": {
                      "effect": "no_effect"
                    }
                  }
                }""");
    }

    @Test
    void putState_effect_removesOtherCTAndColorProperties_keepsDimming() {
        performPutCall(PutCall.builder().id("ID").ct(100).x(0.854).y(0.489).bri(127)
                              .effect(Effect.builder().effect("prism").build()).build());

        verifyPut("/light/ID", """
                {
                  "dimming": {
                    "brightness": 50.0
                  },
                  "effects_v2": {
                    "action": {
                      "effect": "prism"
                    }
                  }
                }""");
    }

    @Test
    void putState_effect_withParameters_xy_speed() {
        performPutCall(PutCall.builder().id("ID").effect(Effect.builder()
                                                               .effect("prism")
                                                               .x(0.123)
                                                               .y(0.456)
                                                               .speed(0.6)
                                                               .build()).build());

        verifyPut("/light/ID", """
                {
                  "effects_v2": {
                    "action": {
                      "effect": "prism",
                      "parameters": {
                        "color": {
                          "xy": {
                            "x": 0.123,
                            "y": 0.456
                          }
                        },
                        "speed": 0.6
                      }
                    }
                  }
                }""");
    }

    @Test
    void putState_effect_withParameters_ct() {
        performPutCall(PutCall.builder().id("ID").effect(Effect.builder()
                                                               .effect("opal")
                                                               .ct(350)
                                                               .build()).build());

        verifyPut("/light/ID", """
                {
                  "effects_v2": {
                    "action": {
                      "effect": "opal",
                      "parameters": {
                        "color_temperature": {
                          "mirek": 350
                        }
                      }
                    }
                  }
                }""");
    }

    @Test
    void putState_effect_withParameters_speedOnly() {
        performPutCall(PutCall.builder().id("ID").effect(Effect.builder()
                                                               .effect("opal")
                                                               .speed(0.7)
                                                               .build()).build());

        verifyPut("/light/ID", """
                {
                  "effects_v2": {
                    "action": {
                      "effect": "opal",
                      "parameters": {
                        "speed": 0.7
                      }
                    }
                  }
                }""");
    }

    @Test
    void putState_gradient_noModeGiven() {
        performPutCall(PutCall.builder().id("ID").gradient(Gradient.builder()
                                                                   .points(List.of(
                                                                           Pair.of(0.123, 0.456),
                                                                           Pair.of(0.234, 0.567)
                                                                   ))
                                                                   .build()).build());

        verifyPut("/light/ID", """
                {
                  "gradient": {
                    "points": [
                      {
                        "color": {
                          "xy": {
                            "x": 0.123,
                            "y": 0.456
                          }
                        }
                      },
                      {
                        "color": {
                          "xy": {
                            "x": 0.234,
                            "y": 0.567
                          }
                        }
                      }
                    ]
                  }
                }""");
    }

    @Test
    void putState_gradient_withMode() {
        performPutCall(PutCall.builder().id("ID").gradient(Gradient.builder()
                                                                   .points(List.of(
                                                                           Pair.of(0.123, 0.456),
                                                                           Pair.of(0.234, 0.567),
                                                                           Pair.of(0.345, 0.678)
                                                                   ))
                                                                   .mode("random_pixelated")
                                                                   .build()).build());

        verifyPut("/light/ID", """
                {
                  "gradient": {
                    "points": [
                      {
                        "color": {
                          "xy": {
                            "x": 0.123,
                            "y": 0.456
                          }
                        }
                      },
                      {
                        "color": {
                          "xy": {
                            "x": 0.234,
                            "y": 0.567
                          }
                        }
                      },
                      {
                        "color": {
                          "xy": {
                            "x": 0.345,
                            "y": 0.678
                          }
                        }
                      }
                    ],
                    "mode": "random_pixelated"
                  }
                }""");
    }

    @Test
    void isLightOff_stateIsOn_false() {
        setGetResponse("/light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "426ab1f6-c27f-42ba-b18d-0783665b4e21",
                      "id_v1": "/lights/32",
                      "owner": {
                        "rid": "23e0ec8f-f096-4e9c-a6d5-4d7efe98ace6",
                        "rtype": "device"
                      },
                      "metadata": {
                        "name": "Ventilator",
                        "archetype": "plug",
                        "function": "functional"
                      },
                      "on": {
                        "on": true
                      },
                      "type": "light"
                    }
                  ]
                }""");
        setGetResponse("/device", EMPTY_RESPONSE);

        assertThat(isLightOff("426ab1f6-c27f-42ba-b18d-0783665b4e21")).isFalse();
    }

    @Test
    void isLightOff_stateIsOff_true() {
        setGetResponse("/light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "631f8419-1b41-495a-9708-81d679644b07",
                      "id_v1": "/lights/32",
                      "owner": {
                        "rid": "23e0ec8f-f096-4e9c-a6d5-4d7efe98ace6",
                        "rtype": "device"
                      },
                      "metadata": {
                        "name": "Ventilator",
                        "archetype": "plug",
                        "function": "functional"
                      },
                      "on": {
                        "on": false
                      },
                      "type": "light"
                    }
                  ]
                }""");
        setGetResponse("/device", EMPTY_RESPONSE);

        assertThat(isLightOff("631f8419-1b41-495a-9708-81d679644b07")).isTrue();
    }

//    @Test
//    void isGroupOff_noGroupedLightServiceFound_groupNotFoundException() {
//        setGetResponse("/room", EMPTY_RESPONSE);
//        setGetResponse("/zone", """
//                {
//                  "errors": [],
//                  "data": [
//                    {
//                      "id": "3346a743-5c88-4198-b21f-4632dfa3549f",
//                      "id_v1": "/groups/81",
//                      "children": [
//                        {
//                          "rid": "414f0d82-d00c-41da-b56a-b728b8cf2445",
//                          "rtype": "light"
//                        }
//                      ],
//                      "services": [
//                      ],
//                      "metadata": {
//                        "name": "Bad Decke",
//                        "archetype": "top_floor"
//                      },
//                      "type": "zone"
//                    }
//                  ]
//                }""");
//
//        assertThrows(GroupNotFoundException.class, () -> api.isGroupOff("b3bd0551-312f-4b6d-bf87-432e5ef56b69"));
//    }

    @Test
    void isGroupOff_isOn_returnsFalse() {
//        setGetResponse("/room", EMPTY_RESPONSE);
//        setGetResponse("/zone", """
//                {
//                  "errors": [],
//                  "data": [
//                    {
//                      "id": "75ee4e3b-cacb-4b87-923b-d11d2480e8ff",
//                      "id_v1": "/groups/3",
//                      "children": [
//                        {
//                          "rid": "1271bf6f-be63-42fc-b18c-3ad462914d8e",
//                          "rtype": "light"
//                        },
//                        {
//                          "rid": "1aa6083d-3692-49e5-92f7-b926b302dd49",
//                          "rtype": "light"
//                        }
//                      ],
//                      "services": [
//                        {
//                          "rid": "ANOTHER_ID_IGNORED",
//                          "rtype": "device"
//                        },
//                        {
//                          "rid": "eeb336d9-243b-4756-8455-1c69f50efd31",
//                          "rtype": "grouped_light"
//                        }
//                      ],
//                      "metadata": {
//                        "name": "Couch",
//                        "archetype": "lounge"
//                      },
//                      "type": "zone"
//                    }
//                  ]
//                }""");
        setGetResponse("/grouped_light", """
                {
                    "errors": [],
                    "data": [
                        {
                            "id": "eeb336d9-243b-4756-8455-1c69f50efd31",
                            "id_v1": "/groups/3",
                            "owner": {
                                "rid": "75ee4e3b-cacb-4b87-923b-d11d2480e8ff",
                                "rtype": "zone"
                            },
                            "on": {
                                "on": true
                            },
                            "dimming": {
                                "brightness": 0.0
                            },
                            "type": "grouped_light"
                        }
                    ]
                }""");

        assertThat(api.isGroupOff("eeb336d9-243b-4756-8455-1c69f50efd31")).isFalse();
    }

    @Test
    void isGroupOff_isFalse_returnsTrue() {
//        setGetResponse("/room", EMPTY_RESPONSE);
//        setGetResponse("/zone", """
//                {
//                  "errors": [],
//                  "data": [
//                    {
//                      "id": "3346a743-5c88-4198-b21f-4632dfa3549f",
//                      "id_v1": "/groups/81",
//                      "children": [
//                        {
//                          "rid": "414f0d82-d00c-41da-b56a-b728b8cf2445",
//                          "rtype": "light"
//                        }
//                      ],
//                      "services": [
//                        {
//                          "rid": "b3bd0551-312f-4b6d-bf87-432e5ef56b69",
//                          "rtype": "grouped_light"
//                        }
//                      ],
//                      "metadata": {
//                        "name": "Bad Decke",
//                        "archetype": "top_floor"
//                      },
//                      "type": "zone"
//                    }
//                  ]
//                }""");
        setGetResponse("/grouped_light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "b3bd0551-312f-4b6d-bf87-432e5ef56b69",
                      "id_v1": "/groups/81",
                      "owner": {
                        "rid": "3346a743-5c88-4198-b21f-4632dfa3549f",
                        "rtype": "zone"
                      },
                      "on": {
                        "on": false
                      },
                      "dimming": {
                        "brightness": 0.0
                      },
                      "type": "grouped_light"
                    }
                  ]
                }""");

        assertThat(api.isGroupOff("b3bd0551-312f-4b6d-bf87-432e5ef56b69")).isTrue();
    }

    @Test
    void getOrCreateScene_groupNotFound_exception() {
        setGetResponse("/grouped_light", EMPTY_RESPONSE);

        assertThrows(GroupNotFoundException.class,
                () -> api.createOrUpdateScene("UNKNOWN_GROUPED_LIGHT_ID", "SCENE NAME", List.of()));
    }

    @Test
    void getOrCreateScene_ifSceneNotFound_createsNewScene_otherwiseUpdatesExistingOne() {
        setGetResponse("/grouped_light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "GROUPED_LIGHT_1",
                      "owner": {
                        "rid": "ROOM_1",
                        "rtype": "room"
                      },
                      "type": "grouped_light"
                    },
                    {
                      "id": "GROUPED_LIGHT_2",
                      "owner": {
                        "rid": "ZONE_1",
                        "rtype": "zone"
                      },
                      "type": "grouped_light"
                    }
                  ]
                }""");
        setGetResponse("/room", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "ROOM_1",
                      "id_v1": "/groups/1",
                      "children": [
                        {
                          "rid": "DEVICE_1",
                          "rtype": "device"
                        },
                        {
                          "rid": "DEVICE_2",
                          "rtype": "device"
                        }
                      ],
                      "services": [
                        {
                          "rid": "GROUPED_LIGHT_1",
                          "rtype": "grouped_light"
                        }
                      ],
                      "metadata": {
                        "name": "Living Room",
                        "archetype": "living_room"
                      },
                      "type": "room"
                    }
                  ]
                }
                """);
        setGetResponse("/zone", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "ZONE_1",
                      "id_v1": "/groups/3",
                      "children": [
                        {
                          "rid": "LIGHT_1",
                          "rtype": "light"
                        },
                        {
                          "rid": "LIGHT_2_1",
                          "rtype": "light"
                        }
                      ],
                      "services": [
                        {
                          "rid": "GROUPED_LIGHT_2",
                          "rtype": "grouped_light"
                        }
                      ],
                      "metadata": {
                        "name": "Couch",
                        "archetype": "lounge"
                      },
                      "type": "zone"
                    }
                  ]
                }
                """);
        setGetResponse("/device", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "DEVICE_1",
                      "product_data": {
                        "model_id": "LST001",
                        "manufacturer_name": "Signify Netherlands B.V.",
                        "product_name": "Hue lightstrip",
                        "product_archetype": "hue_lightstrip",
                        "certified": true,
                        "software_version": "67.116.3",
                        "hardware_platform_type": "100b-103"
                      },
                      "metadata": {
                        "name": "Couch",
                        "archetype": "hue_lightstrip"
                      },
                      "services": [
                        {
                          "rid": "467288fc-29b1-483b-98b0-f6faf8396dc1",
                          "rtype": "zigbee_connectivity"
                        },
                        {
                          "rid": "LIGHT_1",
                          "rtype": "light"
                        }
                      ],
                      "type": "device"
                    },
                    {
                      "id": "DEVICE_2",
                      "metadata": {
                        "name": "Schreibtisch R",
                        "archetype": "sultan_bulb"
                      },
                      "services": [
                        {
                          "rid": "d64efab2-f44a-45de-85c4-26f8857482f1",
                          "rtype": "zigbee_connectivity"
                        },
                        {
                          "rid": "LIGHT_2_1",
                          "rtype": "light"
                        },
                        {
                          "rid": "LIGHT_2_2",
                          "rtype": "light"
                        }
                      ],
                      "type": "device"
                    }
                  ]
                }
                """);
        setGetResponse("/scene", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "SCENE_1",
                      "actions": [
                        {
                          "target": {
                            "rid": "a24f1683-3cd9-466f-8072-3eed721af248",
                            "rtype": "light"
                          },
                          "action": {
                            "on": {
                              "on": true
                            },
                            "dimming": {
                              "brightness": 100.0
                            },
                            "color_temperature": {
                              "mirek": 153
                            }
                          }
                        },
                        {
                          "target": {
                            "rid": "57eaaeee-cd72-4b3b-b2dc-7cccbce0bbd7",
                            "rtype": "light"
                          },
                          "action": {
                            "on": {
                              "on": false
                            },
                            "dimming": {
                              "brightness": 47.43
                            },
                            "color": {
                              "xy": {
                                "x": 0.262,
                                "y": 0.135
                              }
                            }
                          }
                        }
                      ],
                      "palette": {
                        "color": [],
                        "dimming": [],
                        "color_temperature": [],
                        "effects": []
                      },
                      "recall": {},
                      "metadata": {
                        "name": "Scene_1"
                      },
                      "group": {
                        "rid": "ROOM_1",
                        "rtype": "room"
                      },
                      "speed": 0.6031746031746031,
                      "auto_dynamic": false,
                      "status": {
                        "active": "inactive"
                      },
                      "type": "scene"
                    },
                    {
                      "id": "SCENE_3",
                      "actions": [
                        {
                          "target": {
                            "rid": "LIGHT_2_1",
                            "rtype": "light"
                          },
                          "action": {
                            "on": {
                              "on": true
                            },
                            "dimming": {
                              "brightness": 7.0
                            },
                            "color": {
                              "xy": {
                                "x": 0.5451,
                                "y": 0.2408
                              }
                            }
                          }
                        },
                        {
                          "target": {
                            "rid": "LIGHT_1",
                            "rtype": "light"
                          },
                          "action": {
                            "on": {
                              "on": true
                            },
                            "dimming": {
                              "brightness": 20.0
                            }
                          }
                        }
                      ],
                      "palette": {
                        "color": [
                          {
                            "color": {
                              "xy": {
                                "x": 0.5451,
                                "y": 0.2408
                              }
                            },
                            "dimming": {
                              "brightness": 7.51
                            }
                          },
                          {
                            "color": {
                              "xy": {
                                "x": 0.207,
                                "y": 0.083
                              }
                            },
                            "dimming": {
                              "brightness": 44.51
                            }
                          }
                        ],
                        "dimming": [],
                        "color_temperature": [],
                        "effects": []
                      },
                      "recall": {},
                      "metadata": {
                        "name": "Scene_3"
                      },
                      "group": {
                        "rid": "ZONE_1",
                        "rtype": "zone"
                      },
                      "speed": 0.6031746031746031,
                      "auto_dynamic": false,
                      "status": {
                        "active": "inactive"
                      },
                      "type": "scene"
                    }
                  ]
                }
                """);
        setGetResponse("/light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "LIGHT_1",
                      "metadata": {
                        "name": "Color light"
                      },
                      "on": {
                        "on": true
                      },
                      "dimming": {
                        "brightness": 100.0,
                        "min_dim_level": 2.0
                      },
                      "color_temperature": {
                        "mirek": 199,
                        "mirek_valid": true,
                        "mirek_schema": {
                          "mirek_minimum": 153,
                          "mirek_maximum": 500
                        }
                      },
                      "color": {
                        "xy": {
                          "x": 0.3448,
                          "y": 0.3553
                        }
                      },
                      "type": "light"
                    },
                    {
                      "id": "LIGHT_2_1",
                      "metadata": {
                        "name": "Color light"
                      },
                      "on": {
                        "on": true
                      },
                      "dimming": {
                        "brightness": 100.0,
                        "min_dim_level": 0.2
                      },
                      "color_temperature": {
                        "mirek": 199,
                        "mirek_valid": true,
                        "mirek_schema": {
                          "mirek_minimum": 153,
                          "mirek_maximum": 500
                        }
                      },
                      "color": {
                        "xy": {
                          "x": 0.3448,
                          "y": 0.3553
                        }
                      },
                      "type": "light"
                    },
                    {
                      "id": "LIGHT_2_2",
                      "metadata": {
                        "name": "Color light"
                      },
                      "on": {
                        "on": true
                      },
                      "dimming": {
                        "brightness": 100.0,
                        "min_dim_level": 0.2
                      },
                      "color_temperature": {
                        "mirek": 199,
                        "mirek_valid": true,
                        "mirek_schema": {
                          "mirek_minimum": 153,
                          "mirek_maximum": 500
                        }
                      },
                      "color": {
                        "xy": {
                          "x": 0.3448,
                          "y": 0.3553
                        }
                      },
                      "type": "light"
                    }
                  ]
                }""");
        mockSceneCreationResult("SCENE_ID");

        // "Scene_3" does not yet exist for Room -> create it
        createOrUpdateScene("GROUPED_LIGHT_1",
                "Scene_3",
                PutCall.builder().id("LIGHT_1").ct(250).bri(20).transitionTime(5),
                PutCall.builder().id("LIGHT_2_1").bri(20).transitionTime(2),
                PutCall.builder().id("LIGHT_2_2").ct(250)
        );

        verifyPost("/scene", """
                {
                  "metadata": {
                    "name": "Scene_3",
                    "appdata": "huescheduler:app"
                  },
                  "group": {
                    "rid": "ROOM_1",
                    "rtype": "room"
                  },
                  "actions": [
                    {
                      "target": {
                        "rid": "LIGHT_1",
                        "rtype": "light"
                      },
                      "action": {
                        "on": {
                          "on": true
                        },
                        "dimming": {
                          "brightness": 7.87
                        },
                        "color_temperature": {
                          "mirek": 250
                        },
                        "dynamics": {
                          "duration": 500
                        }
                      }
                    },
                    {
                      "target": {
                        "rid": "LIGHT_2_1",
                        "rtype": "light"
                      },
                      "action": {
                        "on": {
                          "on": true
                        },
                        "dimming": {
                          "brightness": 7.87
                        },
                        "dynamics": {
                          "duration": 200
                        }
                      }
                    },
                    {
                      "target": {
                        "rid": "LIGHT_2_2",
                        "rtype": "light"
                      },
                      "action": {
                        "on": {
                          "on": true
                        },
                        "color_temperature": {
                          "mirek": 250
                        }
                      }
                    }
                  ]
                }
                """);

        // "Scene_1" already exists for Room -> update it; missing light treated as off
        createOrUpdateScene("GROUPED_LIGHT_1", "Scene_1",
                PutCall.builder().id("LIGHT_1").x(0.623).y(0.3435),
                PutCall.builder().id("LIGHT_2_1").x(0.623).y(0.3435)
        );

        verifyPut("/scene/SCENE_1", """
                {
                  "actions": [
                    {
                      "target": {
                        "rid": "LIGHT_1",
                        "rtype": "light"
                      },
                      "action": {
                        "on": {
                          "on": true
                        },
                        "color": {
                          "xy": {
                            "x": 0.623,
                            "y": 0.3435
                          }
                        }
                      }
                    },
                    {
                      "target": {
                        "rid": "LIGHT_2_1",
                        "rtype": "light"
                      },
                      "action": {
                        "on": {
                          "on": true
                        },
                        "color": {
                          "xy": {
                            "x": 0.623,
                            "y": 0.3435
                          }
                        }
                      }
                    },
                    {
                      "target": {
                        "rid": "LIGHT_2_2",
                        "rtype": "light"
                      },
                      "action": {
                        "on": {
                          "on": false
                        }
                      }
                    }
                  ]
                }""");

        // "Scene_3" already exists for Zone -> update it

        createOrUpdateScene("GROUPED_LIGHT_2", "Scene_3",
                PutCall.builder().id("LIGHT_1"),
                PutCall.builder().id("LIGHT_2_1")
        );

        verifyPut("/scene/SCENE_3", """
                {
                  "actions": [
                    {
                      "target": {
                        "rid": "LIGHT_1",
                        "rtype": "light"
                      },
                      "action": {
                        "on": {
                          "on": true
                        }
                      }
                    },
                    {
                      "target": {
                        "rid": "LIGHT_2_1",
                        "rtype": "light"
                      },
                      "action": {
                        "on": {
                          "on": true
                        }
                      }
                    }
                  ]
                }""");

        // special case for providing effect -> remove any color attribute
        createOrUpdateScene("GROUPED_LIGHT_2", "Scene_3",
                PutCall.builder().id("LIGHT_1").effect(Effect.builder().effect("opal").build()).x(0.123).y(0.456),
                PutCall.builder().id("LIGHT_2_1")
        );

        verifyPut("/scene/SCENE_3", """
                {
                  "actions": [
                    {
                      "target": {
                        "rid": "LIGHT_1",
                        "rtype": "light"
                      },
                      "action": {
                        "on": {
                          "on": true
                        },
                        "effects_v2": {
                          "action": {
                            "effect": "opal"
                          }
                        }
                      }
                    },
                    {
                      "target": {
                        "rid": "LIGHT_2_1",
                        "rtype": "light"
                      },
                      "action": {
                        "on": {
                          "on": true
                        }
                      }
                    }
                  ]
                }""");
    }

    @Test
    void getOrCreateScene_adjustsSceneActionBasedOnLightCapability() {
        setGetResponse("/grouped_light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "GROUPED_LIGHT",
                      "owner": {
                        "rid": "ZONE",
                        "rtype": "zone"
                      },
                      "type": "grouped_light"
                    }
                  ]
                }""");
        setGetResponse("/zone", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "ZONE",
                      "children": [
                        {
                          "rid": "COLOR",
                          "rtype": "light"
                        },
                        {
                          "rid": "CT_ONLY",
                          "rtype": "light"
                        },
                        {
                          "rid": "BRI_ONLY",
                          "rtype": "light"
                        },
                        {
                          "rid": "ON_OFF_ONLY",
                          "rtype": "light"
                        }
                      ],
                      "services": [
                        {
                          "rid": "GROUPED_LIGHT",
                          "rtype": "grouped_light"
                        }
                      ],
                      "metadata": {
                        "name": "Couch",
                        "archetype": "lounge"
                      },
                      "type": "zone"
                    }
                  ]
                }
                """);
        setGetResponse("/scene", EMPTY_RESPONSE);
        setGetResponse("/light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "COLOR",
                      "owner": {
                        "rid": "2f279281-3e45-462c-9e89-6b2d3363d883",
                        "rtype": "device"
                      },
                      "metadata": {
                        "name": "Color",
                        "archetype": "sultan_bulb",
                        "function": "mixed"
                      },
                      "on": {
                        "on": true
                      },
                      "dimming": {
                        "brightness": 100.0,
                        "min_dim_level": 0.2
                      },
                      "color_temperature": {
                        "mirek": 199,
                        "mirek_valid": true,
                        "mirek_schema": {
                          "mirek_minimum": 153,
                          "mirek_maximum": 500
                        }
                      },
                      "color": {
                        "xy": {
                          "x": 0.3448,
                          "y": 0.3553
                        },
                        "gamut": {
                          "red": {
                            "x": 0.6915,
                            "y": 0.3083
                          },
                          "green": {
                            "x": 0.17,
                            "y": 0.7
                          },
                          "blue": {
                            "x": 0.1532,
                            "y": 0.0475
                          }
                        },
                        "gamut_type": "C"
                      },
                      "type": "light"
                    },
                    {
                      "id": "CT_ONLY",
                      "owner": {
                        "rid": "2184f321-73fb-432d-afb3-33f7d2c23557",
                        "rtype": "device"
                      },
                      "metadata": {
                        "name": "CT Only",
                        "archetype": "candle_bulb",
                        "function": "mixed"
                      },
                      "on": {
                        "on": true
                      },
                      "dimming": {
                        "brightness": 100.0,
                        "min_dim_level": 2.0
                      },
                      "color_temperature": {
                        "mirek": 199,
                        "mirek_valid": true,
                        "mirek_schema": {
                          "mirek_minimum": 153,
                          "mirek_maximum": 454
                        }
                      },
                      "type": "light"
                    },
                    {
                      "id": "BRI_ONLY",
                      "owner": {
                        "rid": "645a6898-1a16-47af-aa17-7cc4c348f337",
                        "rtype": "device"
                      },
                      "metadata": {
                        "name": "Bri Only",
                        "archetype": "sultan_bulb",
                        "fixed_mired": 366,
                        "function": "mixed"
                      },
                      "on": {
                        "on": false
                      },
                      "dimming": {
                        "brightness": 100.0,
                        "min_dim_level": 5.0
                      },
                      "type": "light"
                    },
                    {
                      "id": "ON_OFF_ONLY",
                      "owner": {
                        "rid": "0cd93bd2-8719-4fcb-83b3-f149549bd863",
                        "rtype": "device"
                      },
                      "metadata": {
                        "name": "On Off Only",
                        "archetype": "plug",
                        "function": "functional"
                      },
                      "on": {
                        "on": false
                      },
                      "type": "light"
                    }
                  ]
                }
                """);

        // Color and brightness (uses min mirek):

        mockSceneCreationResult("SCENE_ID");

        createOrUpdateScene("GROUPED_LIGHT", "SCENE",
                PutCall.builder().id("COLOR").x(0.280).y(0.280).bri(20),
                PutCall.builder().id("CT_ONLY").x(0.280).y(0.280).bri(20),
                PutCall.builder().id("BRI_ONLY").x(0.280).y(0.280).bri(20),
                PutCall.builder().id("ON_OFF_ONLY").x(0.280).y(0.280).bri(20)
        );

        verifyPost("/scene", """
                {
                  "metadata": {
                    "name": "SCENE",
                    "appdata": "huescheduler:app"
                  },
                  "group": {
                    "rid": "ZONE",
                    "rtype": "zone"
                  },
                  "actions": [
                    {
                      "target": {
                        "rid": "COLOR",
                        "rtype": "light"
                      },
                      "action": {
                        "on": {
                          "on": true
                        },
                        "dimming": {
                          "brightness": 7.87
                        },
                        "color": {
                          "xy": {
                            "x": 0.28,
                            "y": 0.28
                          }
                        }
                      }
                    },
                    {
                      "target": {
                        "rid": "CT_ONLY",
                        "rtype": "light"
                      },
                      "action": {
                        "on": {
                          "on": true
                        },
                        "dimming": {
                          "brightness": 7.87
                        },
                        "color_temperature": {
                          "mirek": 153
                        }
                      }
                    },
                    {
                      "target": {
                        "rid": "BRI_ONLY",
                        "rtype": "light"
                      },
                      "action": {
                        "on": {
                          "on": true
                        },
                        "dimming": {
                          "brightness": 7.87
                        }
                      }
                    },
                    {
                      "target": {
                        "rid": "ON_OFF_ONLY",
                        "rtype": "light"
                      },
                      "action": {
                        "on": {
                          "on": true
                        }
                      }
                    }
                  ]
                }
                """);

        // CT and brightness (uses max mirek):

        createOrUpdateScene("GROUPED_LIGHT", "SCENE",
                PutCall.builder().id("COLOR").bri(254).ct(499),
                PutCall.builder().id("CT_ONLY").bri(254).ct(499),
                PutCall.builder().id("BRI_ONLY").bri(254).ct(499),
                PutCall.builder().id("ON_OFF_ONLY").bri(254).ct(499)
        );

        verifyPut("/scene/SCENE_ID", """
                {
                  "metadata": {
                    "name": "SCENE",
                    "appdata": "huescheduler:app"
                  },
                  "group": {
                    "rid": "ZONE",
                    "rtype": "zone"
                  },
                  "actions": [
                    {
                      "target": {
                        "rid": "COLOR",
                        "rtype": "light"
                      },
                      "action": {
                        "on": {
                          "on": true
                        },
                        "dimming": {
                          "brightness": 100.0
                        },
                        "color_temperature": {
                          "mirek": 499
                        }
                      }
                    },
                    {
                      "target": {
                        "rid": "CT_ONLY",
                        "rtype": "light"
                      },
                      "action": {
                        "on": {
                          "on": true
                        },
                        "dimming": {
                          "brightness": 100.0
                        },
                        "color_temperature": {
                          "mirek": 454
                        }
                      }
                    },
                    {
                      "target": {
                        "rid": "BRI_ONLY",
                        "rtype": "light"
                      },
                      "action": {
                        "on": {
                          "on": true
                        },
                        "dimming": {
                          "brightness": 100.0
                        }
                      }
                    },
                    {
                      "target": {
                        "rid": "ON_OFF_ONLY",
                        "rtype": "light"
                      },
                      "action": {
                        "on": {
                          "on": true
                        }
                      }
                    }
                  ]
                }
                """);
    }

    @Test
    void getOrCreateScene_updateExistingOne_sameActions_noSceneCreationOrUpdateCallMade() {
        setGetResponse("/grouped_light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "GROUPED_LIGHT",
                      "owner": {
                        "rid": "ZONE",
                        "rtype": "zone"
                      },
                      "type": "grouped_light"
                    }
                  ]
                }""");
        setGetResponse("/zone", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "ZONE",
                      "children": [
                        {
                          "rid": "CT_ONLY",
                          "rtype": "light"
                        },
                        {
                          "rid": "COLOR",
                          "rtype": "light"
                        }
                      ],
                      "services": [
                        {
                          "rid": "GROUPED_LIGHT",
                          "rtype": "grouped_light"
                        }
                      ],
                      "metadata": {
                        "name": "Couch",
                        "archetype": "lounge"
                      },
                      "type": "zone"
                    }
                  ]
                }
                """);
        setGetResponse("/scene", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "SCENE",
                      "actions": [
                        {
                          "target": {
                            "rid": "COLOR",
                            "rtype": "light"
                          },
                          "action": {
                            "on": {
                              "on": true
                            },
                            "dimming": {
                              "brightness": 100.0
                            },
                            "color_temperature": {
                              "mirek": 199
                            }
                          }
                        },
                        {
                          "target": {
                            "rid": "CT_ONLY",
                            "rtype": "light"
                          },
                          "action": {
                            "on": {
                              "on": true
                            },
                            "dimming": {
                              "brightness": 100.0
                            },
                            "color_temperature": {
                              "mirek": 199
                            }
                          }
                        }
                      ],
                      "metadata": {
                        "name": "SCENE"
                      },
                      "group": {
                        "rid": "ZONE",
                        "rtype": "zone"
                      },
                      "speed": 0.6031746031746031,
                      "auto_dynamic": false,
                      "status": {
                        "active": "inactive"
                      },
                      "type": "scene"
                    }
                  ]
                }
                """);
        setGetResponse("/light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "COLOR",
                      "owner": {
                        "rid": "2f279281-3e45-462c-9e89-6b2d3363d883",
                        "rtype": "device"
                      },
                      "metadata": {
                        "name": "Color",
                        "archetype": "sultan_bulb",
                        "function": "mixed"
                      },
                      "on": {
                        "on": true
                      },
                      "dimming": {
                        "brightness": 100.0,
                        "min_dim_level": 0.2
                      },
                      "color_temperature": {
                        "mirek": 199,
                        "mirek_valid": true,
                        "mirek_schema": {
                          "mirek_minimum": 153,
                          "mirek_maximum": 500
                        }
                      },
                      "color": {
                        "xy": {
                          "x": 0.3448,
                          "y": 0.3553
                        },
                        "gamut": {
                          "red": {
                            "x": 0.6915,
                            "y": 0.3083
                          },
                          "green": {
                            "x": 0.17,
                            "y": 0.7
                          },
                          "blue": {
                            "x": 0.1532,
                            "y": 0.0475
                          }
                        },
                        "gamut_type": "C"
                      },
                      "type": "light"
                    },
                    {
                      "id": "CT_ONLY",
                      "owner": {
                        "rid": "2184f321-73fb-432d-afb3-33f7d2c23557",
                        "rtype": "device"
                      },
                      "metadata": {
                        "name": "CT Only",
                        "archetype": "candle_bulb",
                        "function": "mixed"
                      },
                      "on": {
                        "on": true
                      },
                      "dimming": {
                        "brightness": 100.0,
                        "min_dim_level": 2.0
                      },
                      "color_temperature": {
                        "mirek": 199,
                        "mirek_valid": true,
                        "mirek_schema": {
                          "mirek_minimum": 153,
                          "mirek_maximum": 454
                        }
                      },
                      "type": "light"
                    }
                  ]
                }
                """);

        // same state -> no update

        createOrUpdateScene("GROUPED_LIGHT", "SCENE",
                PutCall.builder().id("CT_ONLY").ct(199).bri(254),
                PutCall.builder().id("COLOR").ct(199).bri(254)
        );

        verify(resourceProviderMock, never()).postResource(any(), any());
        verify(resourceProviderMock, never()).putResource(any(), any());

        // change ct -> performs update

        createOrUpdateScene("GROUPED_LIGHT", "SCENE",
                PutCall.builder().id("CT_ONLY").ct(300).bri(254),
                PutCall.builder().id("COLOR").ct(300).bri(254)
        );

        verifyPut("/scene/SCENE", """
                {
                  "actions": [
                    {
                      "target": {
                        "rid": "CT_ONLY",
                        "rtype": "light"
                      },
                      "action": {
                        "on": {
                          "on": true
                        },
                        "dimming": {
                          "brightness": 100.0
                        },
                        "color_temperature": {
                          "mirek": 300
                        }
                      }
                    },
                    {
                      "target": {
                        "rid": "COLOR",
                        "rtype": "light"
                      },
                      "action": {
                        "on": {
                          "on": true
                        },
                        "dimming": {
                          "brightness": 100.0
                        },
                        "color_temperature": {
                          "mirek": 300
                        }
                      }
                    }
                  ]
                }""");

        // overrides single light -> performs update

        createOrUpdateScene("GROUPED_LIGHT", "SCENE",
                PutCall.builder().id("CT_ONLY").on(false).transitionTime(10).ct(200), // ignores other properties for off states
                PutCall.builder().id("COLOR").ct(199).bri(254)
        );

        verifyPut("/scene/SCENE", """
                {
                  "actions": [
                    {
                      "target": {
                        "rid": "CT_ONLY",
                        "rtype": "light"
                      },
                      "action": {
                        "on": {
                          "on": false
                        },
                        "dynamics": {
                          "duration": 1000
                        }
                      }
                    },
                    {
                      "target": {
                        "rid": "COLOR",
                        "rtype": "light"
                      },
                      "action": {
                        "on": {
                          "on": true
                        },
                        "dimming": {
                          "brightness": 100.0
                        },
                        "color_temperature": {
                          "mirek": 199
                        }
                      }
                    }
                  ]
                }""");
    }

    @Test
    void putSceneState_noExistingTempScene_createAndRecall() {
        setGetResponse("/grouped_light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "GL_ZONE_1",
                      "owner": {
                        "rid": "ZONE_1",
                        "rtype": "zone"
                      },
                      "type": "grouped_light"
                    }
                  ]
                }
                """);
        setGetResponse("/zone", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "ZONE_1",
                      "id_v1": "/groups/3",
                      "children": [
                        {
                          "rid": "LIGHT_A",
                          "rtype": "light"
                        },
                        {
                          "rid": "LIGHT_B",
                          "rtype": "light"
                        }
                      ],
                      "services": [
                        {
                          "rid": "GL_ZONE_1",
                          "rtype": "grouped_light"
                        }
                      ],
                      "metadata": {
                        "name": "Zone One",
                        "archetype": "lounge"
                      },
                      "type": "zone"
                    }
                  ]
                }
                """);
        setGetResponse("/light", """
                {
                  "errors": [],
                  "data": [
                    {
                      "id": "LIGHT_A",
                      "metadata": {
                        "name": "LA"
                      },
                      "owner": {
                        "rid": "DEV_A",
                        "rtype": "device"
                      },
                      "on": {
                        "on": true
                      },
                      "dimming": {
                        "brightness": 100.0
                      },
                      "color_temperature": {
                        "mirek": 250,
                        "mirek_valid": true,
                        "mirek_schema": {
                          "mirek_minimum": 153,
                          "mirek_maximum": 500
                        }
                      },
                      "color": {
                        "xy": {
                          "x": 0.3,
                          "y": 0.3
                        }
                      },
                      "type": "light"
                    },
                    {
                      "id": "LIGHT_B",
                      "metadata": {
                        "name": "LB"
                      },
                      "owner": {
                        "rid": "DEV_B",
                        "rtype": "device"
                      },
                      "on": {
                        "on": true
                      },
                      "dimming": {
                        "brightness": 100.0
                      },
                      "color_temperature": {
                        "mirek": 250,
                        "mirek_valid": true,
                        "mirek_schema": {
                          "mirek_minimum": 153,
                          "mirek_maximum": 500
                        }
                      },
                      "color": {
                        "xy": {
                          "x": 0.3,
                          "y": 0.3
                        }
                      },
                      "type": "light"
                    }
                  ]
                }
                """);

        setGetResponse("/scene", EMPTY_RESPONSE);
        mockSceneCreationResult("SCENE_NEW");

        api.putSceneState("GL_ZONE_1", List.of(
                PutCall.builder().id("LIGHT_A").ct(300).bri(100).transitionTime(5).build(),
                PutCall.builder().id("LIGHT_B").on(true).gradient(Gradient.builder()
                                                                          .points(List.of(Pair.of(0.123, 0.456),
                                                                                  Pair.of(0.234, 0.567)))
                                                                          .mode("interpolated_palette_mirrored")
                                                                          .build()).transitionTime(2).build()
        ));

        verifyPost("/scene", """
                {
                  "metadata": {
                    "name": "•",
                    "appdata": "huescheduler:app"
                  },
                  "group": {
                    "rid": "ZONE_1",
                    "rtype": "zone"
                  },
                  "actions": [
                    {
                      "target": {
                        "rid": "LIGHT_A",
                        "rtype": "light"
                      },
                      "action": {
                        "on": {
                          "on": true
                        },
                        "dimming": {
                          "brightness": 39.37
                        },
                        "color_temperature": {
                          "mirek": 300
                        },
                        "dynamics": {
                          "duration": 500
                        }
                      }
                    },
                    {
                      "target": {
                        "rid": "LIGHT_B",
                        "rtype": "light"
                      },
                      "action": {
                        "on": {
                          "on": true
                        },
                        "gradient": {
                          "points": [
                            {
                              "color": {
                                "xy": {
                                  "x": 0.123,
                                  "y": 0.456
                                }
                              }
                            },
                            {
                              "color": {
                                "xy": {
                                  "x": 0.234,
                                  "y": 0.567
                                }
                              }
                            }
                          ],
                          "mode": "interpolated_palette_mirrored"
                        },
                        "dynamics": {
                          "duration": 200
                        }
                      }
                    }
                  ]
                }
                """);

        verifyPut("/scene/SCENE_NEW", """
                {
                  "recall": {
                    "action": "active"
                  }
                }
                """);
    }

    private void mockSceneCreationResult(String sceneId) {
        when(resourceProviderMock.postResource(eq(getUrl("/scene")), any()))
                .thenReturn("{\n" +
                            "  \"data\": [\n" +
                            "    {\n" +
                            "      \"rid\": \"" + sceneId + "\",\n" +
                            "      \"rtype\": \"scene\"\n" +
                            "    }\n" +
                            "  ],\n" +
                            "  \"errors\": []\n" +
                            "}\n");
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

    private void verifyPut(String expectedUrl, @Language("JSON") String expectedBody) {
        verify(resourceProviderMock).putResource(getUrl(expectedUrl), removeSpaces(expectedBody));
    }

    private void verifyPost(String expectedUrl, @Language("JSON") String expectedBody) {
        verify(resourceProviderMock).postResource(getUrl(expectedUrl), removeSpaces(expectedBody));
    }

    private static String removeSpaces(@Language("JSON") String expectedBody) {
        return expectedBody.replaceAll("\\s+|\\n", "");
    }

    private LightCapabilities getLightCapabilities(String id) {
        return api.getLightCapabilities(id);
    }

    private LightCapabilities getGroupCapabilities(String groupedLightId) {
        return api.getGroupCapabilities(groupedLightId);
    }

    private LightState getLightState(String lightId) {
        return api.getLightState(lightId);
    }

    private List<LightState> getGroupStates(String groupedLightId) {
        return api.getGroupStates(groupedLightId);
    }

    private void assertLightState(LightState lightState, LightState expected) {
        assertThat(lightState).usingRecursiveComparison().isEqualTo(expected);
    }

    private void performPutCall(PutCall putCall) {
        api.putState(putCall);
    }

    private void performGroupPutCall(PutCall putCall) {
        api.putGroupState(putCall);
    }

    private List<String> getGroupLights(String groupedLightId) {
        return api.getGroupLights(groupedLightId);
    }

    private List<String> getAssignedGroups(String lightId) {
        return api.getAssignedGroups(lightId);
    }

    private void assertCapabilities(LightCapabilities capabilities, LightCapabilities expected) {
        assertThat(capabilities).usingRecursiveComparison().isEqualTo(expected);
    }

    private void assertGroupIdentifier(String idv1, String groupedLightId, String name) {
        assertIdentifier(api.getGroupIdentifier(idv1), groupedLightId, name);
        assertIdentifier(api.getGroupIdentifierByName(name), groupedLightId, name);
    }

    private void assertLightIdentifier(String idv1, String id, String name) {
        assertIdentifier(api.getLightIdentifier(idv1), id, name);
        assertIdentifier(api.getLightIdentifierByName(name), id, name);
    }

    private void assertIdentifier(Identifier identifier, String id, String name) {
        assertThat(identifier).isEqualTo(new Identifier(id, name));
    }

    private void createOrUpdateScene(String groupedLightId, String sceneSyncName,
                                     PutCall.PutCallBuilder... overriddenPutCallBuilders) {
        api.createOrUpdateScene(groupedLightId, sceneSyncName,
                Stream.of(overriddenPutCallBuilders).map(PutCall.PutCallBuilder::build).toList());
    }

    private void assertSceneLightStates(String groupId, String sceneName, ScheduledLightState.ScheduledLightStateBuilder... states) {
        assertThat(api.getSceneLightState(groupId, sceneName))
                .usingRecursiveComparison()
                .isEqualTo(Arrays.stream(states).map(ScheduledLightState.ScheduledLightStateBuilder::build).toList());
    }
}
