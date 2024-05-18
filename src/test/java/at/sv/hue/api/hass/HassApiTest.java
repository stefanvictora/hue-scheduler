package at.sv.hue.api.hass;

import at.sv.hue.api.Capability;
import at.sv.hue.api.EmptyGroupException;
import at.sv.hue.api.GroupNotFoundException;
import at.sv.hue.api.HttpResourceProvider;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class HassApiTest {

    private HassApiImpl api;
    private HttpResourceProvider http;
    private String baseUrl;

    @BeforeEach
    void setUp() {
        String ip = "localhost";
        String port = "8123";
        http = Mockito.mock(HttpResourceProvider.class);
        api = new HassApiImpl(http, ip, port, permits -> {
        });
        baseUrl = "http://" + ip + ":" + port + "/api";
    }

    @Test
    void getLightIdOrName_notFound_exception() {
        setGetResponse("/states", "[]");

        assertThatThrownBy(() -> api.getLightId("UNKNOWN NAME")).isInstanceOf(LightNotFoundException.class);
        assertThatThrownBy(() -> api.getLightName("light.unknown")).isInstanceOf(LightNotFoundException.class);
    }

    @Test
    void getLightOrGroupNameAndId_found() {
        setGetResponse("/states", """
                [
                  {
                    "entity_id": "person.stefans_home",
                    "state": "unknown",
                    "attributes": {
                      "editable": true,
                      "id": "stefans_home",
                      "user_id": "123456",
                      "device_trackers": [],
                      "friendly_name": "Stefans Home"
                    },
                    "last_changed": "2023-09-21T17:35:56.486268+00:00",
                    "last_updated": "2023-09-21T17:36:01.279407+00:00",
                    "context": {
                      "id": "123456789",
                      "parent_id": null,
                      "user_id": null
                    }
                  },
                  {
                    "entity_id": "zone.home",
                    "state": "0",
                    "attributes": {
                      "latitude": 1.0,
                      "longitude": 1.0,
                      "radius": 100,
                      "passive": false,
                      "persons": [],
                      "editable": true,
                      "icon": "mdi:home",
                      "friendly_name": "Home"
                    },
                    "last_changed": "2023-09-21T17:35:58.872380+00:00",
                    "last_updated": "2023-09-21T17:35:58.872380+00:00",
                    "context": {
                      "id": "123456789",
                      "parent_id": null,
                      "user_id": null
                    }
                  },
                  {
                    "entity_id": "light.schreibtisch_r",
                    "state": "unavailable",
                    "attributes": {
                      "min_color_temp_kelvin": 2000,
                      "max_color_temp_kelvin": 6535,
                      "min_mireds": 153,
                      "max_mireds": 500,
                      "effect_list": [
                        "None",
                        "candle",
                        "fire",
                        "unknown"
                      ],
                      "supported_color_modes": [
                        "color_temp",
                        "xy"
                      ],
                      "friendly_name": "Schreibtisch R",
                      "supported_features": 44
                    },
                    "last_changed": "2023-09-24T08:09:56.861254+00:00",
                    "last_updated": "2023-09-24T08:09:56.861254+00:00",
                    "context": {
                      "id": "123456789",
                      "parent_id": null,
                      "user_id": null
                    }
                  },
                  {
                    "entity_id": "light.flur",
                    "state": "on",
                    "attributes": {
                      "min_color_temp_kelvin": 2202,
                      "max_color_temp_kelvin": 6535,
                      "min_mireds": 153,
                      "max_mireds": 454,
                      "supported_color_modes": [
                        "color_temp",
                        "xy"
                      ],
                      "color_mode": "xy",
                      "brightness": 90,
                      "hs_color": [
                        15.638,
                        73.725
                      ],
                      "rgb_color": [
                        255,
                        116,
                        67
                      ],
                      "xy_color": [
                        0.6024,
                        0.3433
                      ],
                      "is_hue_group": true,
                      "hue_scenes": [],
                      "hue_type": "room",
                      "lights": [
                        "Eingang S Tür",
                        "Eingang S Klo",
                        "Eingang S Kleider",
                        "Flur S Mitte",
                        "Flur S Tür",
                        "Flur S Stiege",
                        "Eingang"
                      ],
                      "dynamics": false,
                      "icon": "mdi:lightbulb-group",
                      "friendly_name": "Flur",
                      "supported_features": 40
                    },
                    "last_changed": "2023-09-21T17:59:29.726678+00:00",
                    "last_updated": "2023-09-24T08:04:31.393954+00:00",
                    "context": {
                      "id": "01HB33FY115WJHS4DRSP1V83AV",
                      "parent_id": null,
                      "user_id": null
                    }
                  },
                  {
                    "entity_id": "light.on_off_1",
                    "state": "on",
                    "attributes": {
                      "supported_color_modes": [],
                      "color_mode": "onoff",
                      "mode": "normal",
                      "dynamics": "none",
                      "friendly_name": "On Off",
                      "supported_features": 0
                    },
                    "last_changed": "2023-09-24T07:55:01.698292+00:00",
                    "last_updated": "2023-09-24T07:55:01.698292+00:00",
                    "context": {
                      "id": "123456789",
                      "parent_id": null,
                      "user_id": null
                    }
                  },
                  {
                    "entity_id": "light.on_off_2",
                    "state": "on",
                    "attributes": {
                      "supported_color_modes": [],
                      "color_mode": "onoff",
                      "mode": "normal",
                      "dynamics": "none",
                      "friendly_name": "On Off",
                      "supported_features": 0
                    },
                    "last_changed": "2023-09-24T07:55:01.698292+00:00",
                    "last_updated": "2023-09-24T07:55:01.698292+00:00",
                    "context": {
                      "id": "123456789",
                      "parent_id": null,
                      "user_id": null
                    }
                  }
                ]
                """);

        assertThat(api.getLightName("light.schreibtisch_r")).isEqualTo("Schreibtisch R");
        assertThat(api.getLightName("light.flur")).isEqualTo("Flur");
        assertThat(api.getGroupName("light.flur")).isEqualTo("Flur");

        assertThat(api.getLightId("Schreibtisch R")).isEqualTo("light.schreibtisch_r");
        assertThat(api.getGroupId("Flur")).isEqualTo("light.flur");

        api.clearCaches();

        assertThat(api.getLightName("light.schreibtisch_r")).isEqualTo("Schreibtisch R");
        assertThat(api.getLightId("Schreibtisch R")).isEqualTo("light.schreibtisch_r");

        assertThat(api.getLightCapabilities("light.schreibtisch_r")).isEqualTo(LightCapabilities.builder()
                                                                                                .ctMin(153)
                                                                                                .ctMax(500)
                                                                                                .capabilities(EnumSet.of(Capability.COLOR,
                                                                                                        Capability.COLOR_TEMPERATURE, Capability.BRIGHTNESS,
                                                                                                        Capability.ON_OFF))
                                                                                                .build());

        // Unsupported state types
        assertThatThrownBy(() -> api.getLightName("zone.home")).isInstanceOf(UnsupportedStateException.class);
        assertThatThrownBy(() -> api.getLightName("person.stefans_home")).isInstanceOf(UnsupportedStateException.class);

        // Not found states
        assertThatThrownBy(() -> api.getLightName("light.unknown_light")).isInstanceOf(LightNotFoundException.class);
        assertThatThrownBy(() -> api.getGroupName("light.unknown_group")).isInstanceOf(LightNotFoundException.class);
        assertThatThrownBy(() -> api.getLightId("UNKNOWN LIGHT")).isInstanceOf(LightNotFoundException.class);
        assertThatThrownBy(() -> api.getGroupId("UNKNOWN GROUP")).isInstanceOf(LightNotFoundException.class);

        // Non unique names
        assertThatThrownBy(() -> api.getLightId("On Off")).isInstanceOf(NonUniqueNameException.class);

        // Not a group
        assertThatThrownBy(() -> api.getGroupId("Schreibtisch R")).isInstanceOf(GroupNotFoundException.class);
    }

    @Test
    void getLightState_colorAndCT_xyColorMode_returnsState() {
        setGetResponse("/states/light.schreibtisch_r", """
                {
                  "entity_id": "light.schreibtisch_r",
                  "state": "on",
                  "attributes": {
                    "min_color_temp_kelvin": 2000,
                    "max_color_temp_kelvin": 6535,
                    "min_mireds": 153,
                    "max_mireds": 500,
                    "effect_list": [
                      "None",
                      "candle",
                      "fire",
                      "unknown"
                    ],
                    "supported_color_modes": [
                      "color_temp",
                      "xy"
                    ],
                    "color_mode": "xy",
                    "brightness": 127,
                    "hs_color": [
                      15.638,
                      73.725
                    ],
                    "rgb_color": [
                      255,
                      116,
                      67
                    ],
                    "xy_color": [
                      0.6024,
                      0.3433
                    ],
                    "effect": "None",
                    "mode": "normal",
                    "dynamics": "none",
                    "friendly_name": "Schreibtisch R",
                    "supported_features": 44
                  },
                  "last_changed": "2023-09-21T17:59:28.462902+00:00",
                  "last_updated": "2023-09-23T20:05:44.786943+00:00",
                  "context": {
                    "id": "123456789",
                    "parent_id": null,
                    "user_id": null
                  }
                }""");

        LightState lightState = getLightState("light.schreibtisch_r");

        LightCapabilities lightCapabilities = LightCapabilities.builder()
                                                               .ctMin(153)
                                                               .ctMax(500)
                                                               .capabilities(EnumSet.of(Capability.COLOR,
                                                                       Capability.COLOR_TEMPERATURE, Capability.BRIGHTNESS,
                                                                       Capability.ON_OFF))
                                                               .build();
        assertThat(lightState).isEqualTo(LightState.builder()
                                                   .on(true)
                                                   .reachable(true)
                                                   .x(0.6024)
                                                   .y(0.3433)
                                                   .effect("none")
                                                   .colormode("xy")
                                                   .brightness(127)
                                                   .lightCapabilities(lightCapabilities)
                                                   .build());
    }

    @Test
    void getLightState_colorAndCT_colorTempMode_returnsState() {
        setGetResponse("/states/light.schreibtisch_r", """
                {
                    "entity_id": "light.schreibtisch_r",
                    "state": "on",
                    "attributes": {
                        "min_color_temp_kelvin": 2000,
                        "max_color_temp_kelvin": 6535,
                        "min_mireds": 153,
                        "max_mireds": 500,
                        "effect_list": [
                            "None",
                            "candle",
                            "fire",
                            "unknown"
                        ],
                        "supported_color_modes": [
                            "color_temp",
                            "xy"
                        ],
                        "color_mode": "color_temp",
                        "brightness": 37,
                        "color_temp_kelvin": 6211,
                        "color_temp": 161,
                        "hs_color": [
                            33.877,
                            4.876
                        ],
                        "rgb_color": [
                            255,
                            249,
                            242
                        ],
                        "xy_color": [
                            0.334,
                            0.336
                        ],
                        "effect": "None",
                        "mode": "normal",
                        "dynamics": "none",
                        "friendly_name": "Schreibtisch R",
                        "supported_features": 44
                    },
                    "last_changed": "2023-09-21T17:59:28.462902+00:00",
                    "last_updated": "2023-09-24T07:29:18.919176+00:00",
                    "context": {
                        "id": "123456789",
                        "parent_id": null,
                        "user_id": "abc"
                    }
                }""");

        LightState lightState = getLightState("light.schreibtisch_r");

        assertThat(lightState).isEqualTo(LightState.builder()
                                                   .on(true)
                                                   .reachable(true)
                                                   .colorTemperature(161)
                                                   .x(0.334)
                                                   .y(0.336)
                                                   .effect("none")
                                                   .colormode("ct")
                                                   .brightness(38) // converted to hue range
                                                   .lightCapabilities(LightCapabilities.builder()
                                                                                       .ctMin(153)
                                                                                       .ctMax(500)
                                                                                       .capabilities(EnumSet.of(Capability.COLOR,
                                                                                               Capability.COLOR_TEMPERATURE, Capability.BRIGHTNESS,
                                                                                               Capability.ON_OFF))
                                                                                       .build())
                                                   .build());
    }

    @Test
    void getLightsState_CTOnly() {
        setGetResponse("/states/light.ct_only", """
                {
                        "entity_id": "light.ct_only",
                        "state": "on",
                        "attributes": {
                            "min_color_temp_kelvin": 2202,
                            "max_color_temp_kelvin": 6535,
                            "min_mireds": 153,
                            "max_mireds": 454,
                            "effect_list": [
                                "None",
                                "candle"
                            ],
                            "supported_color_modes": [
                                "color_temp"
                            ],
                            "color_mode": "color_temp",
                            "brightness": 234,
                            "color_temp_kelvin": 2732,
                            "color_temp": 366,
                            "hs_color": [
                                28.327,
                                64.71
                            ],
                            "rgb_color": [
                                255,
                                167,
                                89
                            ],
                            "xy_color": [
                                0.524,
                                0.387
                            ],
                            "effect": "None",
                            "mode": "normal",
                            "dynamics": "none",
                            "friendly_name": "CT Only",
                            "supported_features": 44
                        },
                        "last_changed": "2023-09-24T08:02:02.025445+00:00",
                        "last_updated": "2023-09-24T08:02:03.037742+00:00",
                        "context": {
                            "id": "12345689",
                            "parent_id": null,
                            "user_id": "93aa9186c4944cb182e08a61f7d201ca"
                        }
                    }""");

        LightState lightState = getLightState("light.ct_only");

        assertThat(lightState).isEqualTo(LightState.builder()
                                                   .on(true)
                                                   .reachable(true)
                                                   .colorTemperature(366)
                                                   .x(0.524)
                                                   .y(0.387)
                                                   .effect("none")
                                                   .colormode("ct")
                                                   .brightness(233) // converted to hue range
                                                   .lightCapabilities(LightCapabilities.builder()
                                                                                       .ctMin(153)
                                                                                       .ctMax(454)
                                                                                       .capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE,
                                                                                               Capability.BRIGHTNESS,
                                                                                               Capability.ON_OFF))
                                                                                       .build())
                                                   .build());
    }

    @Test
    void getLightsState_XYOnly_doesNotSupportEffects() {
        setGetResponse("/states/light.xy_only", """
                {
                        "entity_id": "light.xy_only",
                        "state": "on",
                        "attributes": {
                            "supported_color_modes": [
                                "xy"
                            ],
                            "color_mode": "xy",
                            "brightness": 136,
                            "hs_color": [
                                13.366,
                                79.216
                            ],
                            "rgb_color": [
                                255,
                                98,
                                53
                            ],
                            "xy_color": [
                                0.6311,
                                0.3325
                            ],
                            "mode": "normal",
                            "dynamics": "none",
                            "friendly_name": "XY Only",
                            "supported_features": 40
                        },
                        "last_changed": "2023-09-23T15:32:38.271511+00:00",
                        "last_updated": "2023-09-23T17:10:00.578272+00:00",
                        "context": {
                            "id": "123456789",
                            "parent_id": null,
                            "user_id": null
                        }
                    }""");

        LightState lightState = getLightState("light.xy_only");

        assertThat(lightState).isEqualTo(LightState.builder()
                                                   .on(true)
                                                   .reachable(true)
                                                   .x(0.6311)
                                                   .y(0.3325)
                                                   .colormode("xy")
                                                   .brightness(136)
                                                   .lightCapabilities(LightCapabilities.builder()
                                                                                       .capabilities(EnumSet.of(Capability.COLOR,
                                                                                               Capability.BRIGHTNESS,
                                                                                               Capability.ON_OFF))
                                                                                       .build())
                                                   .build());
    }

    @Test
    void getLightState_unavailable_treatedAsOffAndUnreachable() {
        setGetResponse("/states/light.ceiling", """
                {
                    "entity_id": "light.ceiling",
                    "state": "unavailable",
                    "attributes": {
                        "min_color_temp_kelvin": 2000,
                        "max_color_temp_kelvin": 6535,
                        "min_mireds": 153,
                        "max_mireds": 500,
                        "effect_list": [
                            "None",
                            "candle",
                            "fire",
                            "unknown"
                        ],
                        "supported_color_modes": [
                            "color_temp",
                            "xy"
                        ],
                        "friendly_name": "Ceiling",
                        "supported_features": 44
                    },
                    "last_changed": "2023-09-21T18:10:21.423610+00:00",
                    "last_updated": "2023-09-21T18:10:21.423610+00:00",
                    "context": {
                        "id": "123456789",
                        "parent_id": null,
                        "user_id": null
                    }
                }""");

        LightState lightState = getLightState("light.ceiling");

        assertThat(lightState).isEqualTo(LightState.builder()
                                                   .on(false)
                                                   .reachable(false)
                                                   .lightCapabilities(LightCapabilities.builder()
                                                                                       .ctMin(153)
                                                                                       .ctMax(500)
                                                                                       .capabilities(EnumSet.of(Capability.COLOR,
                                                                                               Capability.COLOR_TEMPERATURE, Capability.BRIGHTNESS,
                                                                                               Capability.ON_OFF))
                                                                                       .build())
                                                   .build());
    }

    @Test
    void getLightState_isOff() {
        setGetResponse("/states/light.off", """
                {
                        "entity_id": "light.off",
                        "state": "off",
                        "attributes": {
                            "min_color_temp_kelvin": 2202,
                            "max_color_temp_kelvin": 6535,
                            "min_mireds": 153,
                            "max_mireds": 454,
                            "effect_list": [
                                "None",
                                "candle"
                            ],
                            "supported_color_modes": [
                                "color_temp"
                            ],
                            "mode": "normal",
                            "dynamics": "none",
                            "friendly_name": "Off Light",
                            "supported_features": 44
                        },
                        "last_changed": "2023-09-21T17:36:01.004020+00:00",
                        "last_updated": "2023-09-21T17:36:01.004020+00:00",
                        "context": {
                            "id": "123456789",
                            "parent_id": null,
                            "user_id": null
                        }
                    }""");

        LightState lightState = getLightState("light.off");

        assertThat(lightState).isEqualTo(LightState.builder()
                                                   .on(false)
                                                   .reachable(true)
                                                   .lightCapabilities(LightCapabilities.builder()
                                                                                       .ctMin(153)
                                                                                       .ctMax(454)
                                                                                       .capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE,
                                                                                               Capability.BRIGHTNESS,
                                                                                               Capability.ON_OFF))
                                                                                       .build())
                                                   .build());
    }

    @Test
    void getLightsState_onOffOnly_light() {
        setGetResponse("/states/light.on_off", """
                    {
                        "entity_id": "light.on_off",
                        "state": "on",
                        "attributes": {
                            "supported_color_modes": [],
                            "color_mode": "onoff",
                            "mode": "normal",
                            "dynamics": "none",
                            "friendly_name": "On Off",
                            "supported_features": 0
                        },
                        "last_changed": "2023-09-24T07:55:01.698292+00:00",
                        "last_updated": "2023-09-24T07:55:01.698292+00:00",
                        "context": {
                            "id": "123456789",
                            "parent_id": null,
                            "user_id": null
                        }
                    }
                """);

        LightState lightState = getLightState("light.on_off");

        assertThat(lightState).isEqualTo(LightState.builder()
                                                   .on(true)
                                                   .reachable(true)
                                                   .lightCapabilities(LightCapabilities.builder()
                                                                                       .capabilities(EnumSet.of(Capability.ON_OFF))
                                                                                       .build())
                                                   .build());
    }

    @Test
    void getLightsState_onOffOnly_switch() {
        setGetResponse("/states/switch.switch_demo", """
                    {
                        "entity_id": "switch.switch_demo",
                        "state": "on",
                        "attributes": {
                            "configuration": {},
                            "icon": "mdi:theme-light-dark",
                            "friendly_name": "Switch Demo"
                        },
                        "last_changed": "2023-09-24T10:36:25.264439+00:00",
                        "last_updated": "2023-09-24T10:36:25.264439+00:00",
                        "context": {
                            "id": "123456789",
                            "parent_id": null,
                            "user_id": null
                        }
                    }
                """);

        LightState lightState = getLightState("switch.switch_demo");

        assertThat(lightState).isEqualTo(LightState.builder()
                                                   .on(true)
                                                   .reachable(true)
                                                   .lightCapabilities(LightCapabilities.builder()
                                                                                       .capabilities(EnumSet.of(Capability.ON_OFF))
                                                                                       .build())
                                                   .build());
    }

    @Test
    void getLightsState_onOffOnly_inputBoolean() {
        setGetResponse("/states/input_boolean.test_toggle", """
                    {
                        "entity_id": "input_boolean.test_toggle",
                        "state": "on",
                        "attributes": {
                            "editable": true,
                            "icon": "mdi:alarm-panel",
                            "friendly_name": "Test Toggle"
                        },
                        "last_changed": "2023-09-24T14:06:32.744839+00:00",
                        "last_updated": "2023-09-24T14:06:32.744839+00:00",
                        "context": {
                            "id": "01HB3R6T9820DA0PE9VCFJRE14",
                            "parent_id": null,
                            "user_id": null
                        }
                    }
                """);

        LightState lightState = getLightState("input_boolean.test_toggle");

        assertThat(lightState).isEqualTo(LightState.builder()
                                                   .on(true)
                                                   .reachable(true)
                                                   .lightCapabilities(LightCapabilities.builder()
                                                                                       .capabilities(EnumSet.of(Capability.ON_OFF))
                                                                                       .build())
                                                   .build());
    }

    @Test
    void getLightsState_onOffOnly_unavailable() {
        setGetResponse("/states/light.on_off", """
                {
                        "entity_id": "light.on_off",
                        "state": "unavailable",
                        "attributes": {
                            "supported_color_modes": [],
                            "friendly_name": "On Off",
                            "supported_features": 0
                        },
                        "last_changed": "2023-09-21T17:36:01.003533+00:00",
                        "last_updated": "2023-09-21T17:36:01.003533+00:00",
                        "context": {
                            "id": "123456789",
                            "parent_id": null,
                            "user_id": null
                        }
                    }""");

        LightState lightState = getLightState("light.on_off");

        assertThat(lightState).isEqualTo(LightState.builder()
                                                   .on(false)
                                                   .reachable(false)
                                                   .lightCapabilities(LightCapabilities.builder()
                                                                                       .capabilities(EnumSet.of(Capability.ON_OFF))
                                                                                       .build())
                                                   .build());
    }

    @Test
    void getLightState_brightnessOnly_maxBrightness() {
        setGetResponse("/states/light.brightness", """
                {
                        "entity_id": "light.brightness",
                        "state": "on",
                        "attributes": {
                            "effect_list": [
                                "None",
                                "candle"
                            ],
                            "supported_color_modes": [
                                "brightness"
                            ],
                            "color_mode": "brightness",
                            "brightness": 255,
                            "effect": "None",
                            "mode": "normal",
                            "dynamics": "none",
                            "friendly_name": "Brightness only lamp",
                            "supported_features": 44
                        },
                        "last_changed": "2023-09-24T08:09:32.431546+00:00",
                        "last_updated": "2023-09-24T08:09:32.431546+00:00",
                        "context": {
                            "id": "123456789",
                            "parent_id": null,
                            "user_id": null
                        }
                    }""");

        LightState lightState = getLightState("light.brightness");

        assertThat(lightState).isEqualTo(LightState.builder()
                                                   .on(true)
                                                   .reachable(true)
                                                   .brightness(254) // adjusted to max value for hue
                                                   .effect("none")
                                                   .lightCapabilities(LightCapabilities.builder()
                                                                                       .capabilities(EnumSet.of(Capability.BRIGHTNESS,
                                                                                               Capability.ON_OFF))
                                                                                       .build())
                                                   .build());
    }

    @Test
    void getLightState_brightnessOnly_minBrightness() {
        setGetResponse("/states/light.brightness", """
                {
                        "entity_id": "light.brightness",
                        "state": "on",
                        "attributes": {
                            "effect_list": [
                                "None",
                                "candle"
                            ],
                            "supported_color_modes": [
                                "brightness"
                            ],
                            "color_mode": "brightness",
                            "brightness": 0,
                            "effect": "None",
                            "mode": "normal",
                            "dynamics": "none",
                            "friendly_name": "Brightness only lamp",
                            "supported_features": 44
                        },
                        "last_changed": "2023-09-24T08:09:32.431546+00:00",
                        "last_updated": "2023-09-24T08:09:32.431546+00:00",
                        "context": {
                            "id": "123456789",
                            "parent_id": null,
                            "user_id": null
                        }
                    }""");

        LightState lightState = getLightState("light.brightness");

        assertThat(lightState).isEqualTo(LightState.builder()
                                                   .on(true)
                                                   .reachable(true)
                                                   .brightness(1) // adjusted to min value for hue
                                                   .effect("none")
                                                   .lightCapabilities(LightCapabilities.builder()
                                                                                       .capabilities(EnumSet.of(Capability.BRIGHTNESS,
                                                                                               Capability.ON_OFF))
                                                                                       .build())
                                                   .build());
    }

    @Test
    void getLightState_brightnessOnly_brightnessInTheUpperRange() {
        setGetResponse("/states/light.brightness", """
                {
                        "entity_id": "light.brightness",
                        "state": "on",
                        "attributes": {
                            "effect_list": [
                                "None",
                                "candle"
                            ],
                            "supported_color_modes": [
                                "brightness"
                            ],
                            "color_mode": "brightness",
                            "brightness": 200,
                            "effect": "None",
                            "mode": "normal",
                            "dynamics": "none",
                            "friendly_name": "Brightness only lamp",
                            "supported_features": 44
                        },
                        "last_changed": "2023-09-24T08:09:32.431546+00:00",
                        "last_updated": "2023-09-24T08:09:32.431546+00:00",
                        "context": {
                            "id": "123456789",
                            "parent_id": null,
                            "user_id": null
                        }
                    }""");

        LightState lightState = getLightState("light.brightness");

        assertThat(lightState).isEqualTo(LightState.builder()
                                                   .on(true)
                                                   .reachable(true)
                                                   .brightness(199) // adjusted correclty
                                                   .effect("none")
                                                   .lightCapabilities(LightCapabilities.builder()
                                                                                       .capabilities(EnumSet.of(Capability.BRIGHTNESS,
                                                                                               Capability.ON_OFF))
                                                                                       .build())
                                                   .build());
    }

    @Test
    void getGroupLights_hueGroup_fetchesContainedLightsByName_ignoresGroupStates() {
        setGetResponse("/states", """
                [
                  {
                    "entity_id": "light.couch_group",
                    "state": "on",
                    "attributes": {
                      "supported_color_modes": [
                        "xy"
                      ],
                      "color_mode": "xy",
                      "brightness": 255,
                      "hs_color": [
                        12.464,
                        81.176
                      ],
                      "rgb_color": [
                        255,
                        91,
                        48
                      ],
                      "xy_color": [
                        0.6408,
                        0.3284
                      ],
                      "is_hue_group": true,
                      "hue_scenes": [
                        "Gedimmt",
                        "Nachtlicht",
                        "Hell",
                        "Frühlingsblüten",
                        "Sonnenuntergang Savanne",
                        "Tropendämmerung",
                        "Nordlichter"
                      ],
                      "hue_type": "zone",
                      "lights": [
                        "Couch",
                        "Couch unten"
                      ],
                      "dynamics": false,
                      "icon": "mdi:lightbulb-group",
                      "friendly_name": "Couch",
                      "supported_features": 40
                    },
                    "last_changed": "2023-09-24T14:06:34.783862+00:00",
                    "last_updated": "2023-09-24T14:06:34.783862+00:00",
                    "context": {
                      "id": "123456789",
                      "parent_id": null,
                      "user_id": null
                    }
                  },
                  {
                    "entity_id": "light.couch_unten",
                    "state": "on",
                    "attributes": {
                      "supported_color_modes": [
                        "xy"
                      ],
                      "color_mode": "xy",
                      "brightness": 255,
                      "hs_color": [
                        12.464,
                        81.176
                      ],
                      "rgb_color": [
                        255,
                        91,
                        48
                      ],
                      "xy_color": [
                        0.6408,
                        0.3284
                      ],
                      "mode": "normal",
                      "dynamics": "none",
                      "friendly_name": "Couch unten",
                      "supported_features": 40
                    },
                    "last_changed": "2023-09-24T14:06:34.772408+00:00",
                    "last_updated": "2023-09-24T14:06:34.772408+00:00",
                    "context": {
                      "id": "123456789",
                      "parent_id": null,
                      "user_id": null
                    }
                  },
                  {
                    "entity_id": "light.couch",
                    "state": "on",
                    "attributes": {
                      "supported_color_modes": [
                        "xy"
                      ],
                      "color_mode": "xy",
                      "brightness": 255,
                      "hs_color": [
                        12.464,
                        81.176
                      ],
                      "rgb_color": [
                        255,
                        91,
                        48
                      ],
                      "xy_color": [
                        0.6408,
                        0.3284
                      ],
                      "mode": "normal",
                      "dynamics": "none",
                      "friendly_name": "Couch",
                      "supported_features": 40
                    },
                    "last_changed": "2023-09-24T14:06:34.774658+00:00",
                    "last_updated": "2023-09-24T14:06:34.774658+00:00",
                    "context": {
                      "id": "123456789",
                      "parent_id": null,
                      "user_id": null
                    }
                  }
                ]
                """);

        assertThat(api.getGroupLights("light.couch_group")).containsExactlyInAnyOrder("light.couch",
                "light.couch_unten"
        );
    }

    @Test
    void getGroupLights_hueGroup_containsUnknownLight_exception() {
        setGetResponse("/states", """
                [
                  {
                    "entity_id": "light.couch_group",
                    "state": "on",
                    "attributes": {
                      "supported_color_modes": [
                        "xy"
                      ],
                      "color_mode": "xy",
                      "brightness": 255,
                      "hs_color": [
                        12.464,
                        81.176
                      ],
                      "rgb_color": [
                        255,
                        91,
                        48
                      ],
                      "xy_color": [
                        0.6408,
                        0.3284
                      ],
                      "is_hue_group": true,
                      "hue_scenes": [
                        "Gedimmt",
                        "Nachtlicht",
                        "Hell",
                        "Frühlingsblüten",
                        "Sonnenuntergang Savanne",
                        "Tropendämmerung",
                        "Nordlichter"
                      ],
                      "hue_type": "zone",
                      "lights": [
                        "Couch",
                        "Couch unten"
                      ],
                      "dynamics": false,
                      "icon": "mdi:lightbulb-group",
                      "friendly_name": "Couch",
                      "supported_features": 40
                    },
                    "last_changed": "2023-09-24T14:06:34.783862+00:00",
                    "last_updated": "2023-09-24T14:06:34.783862+00:00",
                    "context": {
                      "id": "123456789",
                      "parent_id": null,
                      "user_id": null
                    }
                  }
                ]
                """);

        assertThatThrownBy(() -> api.getGroupLights("light.couch_group")).isInstanceOf(LightNotFoundException.class);
    }

    @Test
    void getGroupLights_hueGroup_ignoresHassGroup_exception() {
        setGetResponse("/states", """
                [
                  {
                    "entity_id": "light.couch_group",
                    "state": "on",
                    "attributes": {
                      "supported_color_modes": [
                        "xy"
                      ],
                      "color_mode": "xy",
                      "brightness": 255,
                      "hs_color": [
                        12.464,
                        81.176
                      ],
                      "rgb_color": [
                        255,
                        91,
                        48
                      ],
                      "xy_color": [
                        0.6408,
                        0.3284
                      ],
                      "is_hue_group": true,
                      "hue_scenes": [
                        "Gedimmt",
                        "Nachtlicht",
                        "Hell",
                        "Frühlingsblüten",
                        "Sonnenuntergang Savanne",
                        "Tropendämmerung",
                        "Nordlichter"
                      ],
                      "hue_type": "zone",
                      "lights": [
                        "Couch"
                      ],
                      "dynamics": false,
                      "icon": "mdi:lightbulb-group",
                      "friendly_name": "Couch",
                      "supported_features": 40
                    },
                    "last_changed": "2023-09-24T14:06:34.783862+00:00",
                    "last_updated": "2023-09-24T14:06:34.783862+00:00",
                    "context": {
                      "id": "123456789",
                      "parent_id": null,
                      "user_id": null
                    }
                  },
                  {
                    "entity_id": "light.couch_group2",
                    "state": "on",
                    "attributes": {
                      "min_color_temp_kelvin": 2000,
                      "max_color_temp_kelvin": 6535,
                      "min_mireds": 153,
                      "max_mireds": 500,
                      "effect_list": [
                        "None",
                        "candle",
                        "fire",
                        "unknown"
                      ],
                      "supported_color_modes": [
                        "brightness",
                        "color_temp",
                        "xy"
                      ],
                      "color_mode": "brightness",
                      "brightness": 255,
                      "effect": "None",
                      "entity_id": [
                        "light.couch"
                      ],
                      "icon": "mdi:lightbulb-group",
                      "friendly_name": "Couch",
                      "supported_features": 44
                    },
                    "last_changed": "2023-09-24T14:06:35.260070+00:00",
                    "last_updated": "2023-09-24T14:20:00.208887+00:00",
                    "context": {
                      "id": "123456789",
                      "parent_id": null,
                      "user_id": null
                    }
                  }
                ]
                """);

        assertThatThrownBy(() -> api.getGroupLights("light.couch_group")).isInstanceOf(LightNotFoundException.class);
    }

    @Test
    void getGroupLights_hassGroup_returnsContainedLightIds() {
        setGetResponse("/states", """
                [
                  {
                    "entity_id": "light.test_group",
                    "state": "on",
                    "attributes": {
                      "min_color_temp_kelvin": 2000,
                      "max_color_temp_kelvin": 6535,
                      "min_mireds": 153,
                      "max_mireds": 500,
                      "effect_list": [
                        "None",
                        "candle",
                        "fire",
                        "unknown"
                      ],
                      "supported_color_modes": [
                        "brightness",
                        "color_temp",
                        "xy"
                      ],
                      "color_mode": "brightness",
                      "brightness": 255,
                      "effect": "None",
                      "entity_id": [
                        "light.schreibtisch_r",
                        "light.schreibtisch_l"
                      ],
                      "icon": "mdi:lightbulb-group",
                      "friendly_name": "Test Group",
                      "supported_features": 44
                    },
                    "last_changed": "2023-09-24T14:06:35.260070+00:00",
                    "last_updated": "2023-09-24T14:20:00.208887+00:00",
                    "context": {
                      "id": "123456789",
                      "parent_id": null,
                      "user_id": null
                    }
                  }
                ]
                """);

        assertThat(api.getGroupLights("light.test_group")).containsExactlyInAnyOrder("light.schreibtisch_r",
                "light.schreibtisch_l"
        );
    }

    @Test
    void getGroupLights_notAGroup_exception() {
        setGetResponse("/states", """
                [
                  {
                    "entity_id": "light.on_off",
                    "state": "on",
                    "attributes": {
                      "supported_color_modes": [],
                      "color_mode": "onoff",
                      "mode": "normal",
                      "dynamics": "none",
                      "friendly_name": "On Off",
                      "supported_features": 0
                    },
                    "last_changed": "2023-09-24T07:55:01.698292+00:00",
                    "last_updated": "2023-09-24T07:55:01.698292+00:00",
                    "context": {
                      "id": "123456789",
                      "parent_id": null,
                      "user_id": null
                    }
                  }
                ]
                """);

        assertThatThrownBy(() -> api.getGroupLights("light.on_off")).isInstanceOf(GroupNotFoundException.class);
    }

    @Test
    void getGroupLights_hueGroup_emptyGroup_exception() {
        setGetResponse("/states", """
                [
                  {
                    "entity_id": "light.couch_group",
                    "state": "on",
                    "attributes": {
                      "supported_color_modes": [
                        "xy"
                      ],
                      "color_mode": "xy",
                      "brightness": 255,
                      "hs_color": [
                        12.464,
                        81.176
                      ],
                      "rgb_color": [
                        255,
                        91,
                        48
                      ],
                      "xy_color": [
                        0.6408,
                        0.3284
                      ],
                      "is_hue_group": true,
                      "hue_scenes": [
                        "Gedimmt",
                        "Nachtlicht",
                        "Hell",
                        "Frühlingsblüten",
                        "Sonnenuntergang Savanne",
                        "Tropendämmerung",
                        "Nordlichter"
                      ],
                      "hue_type": "zone",
                      "lights": [
                      ],
                      "dynamics": false,
                      "icon": "mdi:lightbulb-group",
                      "friendly_name": "Couch",
                      "supported_features": 40
                    },
                    "last_changed": "2023-09-24T14:06:34.783862+00:00",
                    "last_updated": "2023-09-24T14:06:34.783862+00:00",
                    "context": {
                      "id": "123456789",
                      "parent_id": null,
                      "user_id": null
                    }
                  },
                  {
                    "entity_id": "light.couch_group2",
                    "state": "on",
                    "attributes": {
                      "min_color_temp_kelvin": 2000,
                      "max_color_temp_kelvin": 6535,
                      "min_mireds": 153,
                      "max_mireds": 500,
                      "effect_list": [
                        "None",
                        "candle",
                        "fire",
                        "unknown"
                      ],
                      "supported_color_modes": [
                        "brightness",
                        "color_temp",
                        "xy"
                      ],
                      "color_mode": "brightness",
                      "brightness": 255,
                      "effect": "None",
                      "entity_id": [
                      ],
                      "icon": "mdi:lightbulb-group",
                      "friendly_name": "Couch",
                      "supported_features": 44
                    },
                    "last_changed": "2023-09-24T14:06:35.260070+00:00",
                    "last_updated": "2023-09-24T14:20:00.208887+00:00",
                    "context": {
                      "id": "123456789",
                      "parent_id": null,
                      "user_id": null
                    }
                  }
                ]
                """);

        assertThatThrownBy(() -> api.getGroupLights("light.couch_group")).isInstanceOf(EmptyGroupException.class);
        assertThatThrownBy(() -> api.getGroupLights("light.couch_group2")).isInstanceOf(EmptyGroupException.class);
    }

    @Test
    void getGroupCapabilities_directlyReturnsCapabilitiesOfState() {
        setGetResponse("/states", """
                [
                  {
                    "entity_id": "light.couch_group",
                    "state": "on",
                    "attributes": {
                      "supported_color_modes": [
                        "xy"
                      ],
                      "color_mode": "xy",
                      "brightness": 255,
                      "hs_color": [
                        12.464,
                        81.176
                      ],
                      "rgb_color": [
                        255,
                        91,
                        48
                      ],
                      "xy_color": [
                        0.6408,
                        0.3284
                      ],
                      "is_hue_group": true,
                      "hue_scenes": [
                        "Gedimmt",
                        "Nachtlicht",
                        "Hell",
                        "Frühlingsblüten",
                        "Sonnenuntergang Savanne",
                        "Tropendämmerung",
                        "Nordlichter"
                      ],
                      "hue_type": "zone",
                      "lights": [
                      ],
                      "dynamics": false,
                      "icon": "mdi:lightbulb-group",
                      "friendly_name": "Couch",
                      "supported_features": 40
                    },
                    "last_changed": "2023-09-24T14:06:34.783862+00:00",
                    "last_updated": "2023-09-24T14:06:34.783862+00:00",
                    "context": {
                      "id": "123456789",
                      "parent_id": null,
                      "user_id": null
                    }
                  }
                ]""");

        assertThat(api.getGroupCapabilities("light.couch_group")).isEqualTo(LightCapabilities.builder()
                                                                                             .capabilities(EnumSet.of(Capability.COLOR,
                                                                                                     Capability.BRIGHTNESS, Capability.ON_OFF))
                                                                                             .build());

    }

    @Test
    void getGroupStates_returnsStatesForContainedLights_doesNotUseCache_ignoresUnknownStates() {
        setGetResponse("/states", """
                [
                  {
                    "entity_id": "light.test_group",
                    "state": "on",
                    "attributes": {
                      "min_color_temp_kelvin": 2000,
                      "max_color_temp_kelvin": 6535,
                      "min_mireds": 153,
                      "max_mireds": 500,
                      "effect_list": [
                        "None",
                        "candle",
                        "fire",
                        "unknown"
                      ],
                      "supported_color_modes": [
                        "color_temp",
                        "xy"
                      ],
                      "color_mode": "color_temp",
                      "brightness": 244,
                      "color_temp_kelvin": 2971,
                      "color_temp": 336,
                      "hs_color": [
                        27.874,
                        57.689
                      ],
                      "rgb_color": [
                        255,
                        176,
                        107
                      ],
                      "xy_color": [
                        0.499,
                        0.384
                      ],
                      "effect": "None",
                      "entity_id": [
                        "light.schreibtisch_l",
                        "light.schreibtisch_r",
                        "light.ignored",
                        "sensor.sun_next_setting"
                      ],
                      "icon": "mdi:lightbulb-group",
                      "friendly_name": "Test Group",
                      "supported_features": 44
                    },
                    "last_changed": "2023-09-24T16:13:59.022491+00:00",
                    "last_updated": "2023-09-24T16:13:59.194048+00:00",
                    "context": {
                      "id": "123456789",
                      "parent_id": null,
                      "user_id": null
                    }
                  },
                  {
                    "entity_id": "light.schreibtisch_l",
                    "state": "on",
                    "attributes": {
                      "min_color_temp_kelvin": 2000,
                      "max_color_temp_kelvin": 6535,
                      "min_mireds": 153,
                      "max_mireds": 500,
                      "effect_list": [
                        "None",
                        "candle",
                        "fire",
                        "unknown"
                      ],
                      "supported_color_modes": [
                        "color_temp",
                        "xy"
                      ],
                      "color_mode": "color_temp",
                      "brightness": 127,
                      "color_temp_kelvin": 2994,
                      "color_temp": 334,
                      "hs_color": [
                        27.835,
                        57.058
                      ],
                      "rgb_color": [
                        255,
                        177,
                        109
                      ],
                      "xy_color": [
                        0.497,
                        0.384
                      ],
                      "effect": "None",
                      "mode": "normal",
                      "dynamics": "none",
                      "friendly_name": "Schreibtisch L",
                      "supported_features": 44
                    },
                    "last_changed": "2023-09-24T14:50:55.062582+00:00",
                    "last_updated": "2023-09-24T16:10:21.374478+00:00",
                    "context": {
                      "id": "123456789",
                      "parent_id": null,
                      "user_id": null
                    }
                  },
                  {
                    "entity_id": "light.schreibtisch_r",
                    "state": "on",
                    "attributes": {
                      "min_color_temp_kelvin": 2000,
                      "max_color_temp_kelvin": 6535,
                      "min_mireds": 153,
                      "max_mireds": 500,
                      "effect_list": [
                        "None",
                        "candle",
                        "fire",
                        "unknown"
                      ],
                      "supported_color_modes": [
                        "color_temp",
                        "xy"
                      ],
                      "color_mode": "color_temp",
                      "brightness": 246,
                      "color_temp_kelvin": 2976,
                      "color_temp": 336,
                      "hs_color": [
                        27.865,
                        57.551
                      ],
                      "rgb_color": [
                        255,
                        176,
                        108
                      ],
                      "xy_color": [
                        0.498,
                        0.383
                      ],
                      "effect": "None",
                      "mode": "normal",
                      "dynamics": "none",
                      "friendly_name": "Schreibtisch R",
                      "supported_features": 44
                    },
                    "last_changed": "2023-09-24T16:04:27.160634+00:00",
                    "last_updated": "2023-09-24T16:12:22.081189+00:00",
                    "context": {
                      "id": "123456789",
                      "parent_id": null,
                      "user_id": null
                    }
                  },
                  {
                     "entity_id": "sensor.sun_next_setting",
                     "state": "2024-05-19T18:32:39+00:00",
                     "attributes": {
                         "device_class": "timestamp",
                         "friendly_name": "Sun Next setting"
                     },
                     "last_changed": "2024-05-18T19:27:33.729389+00:00",
                     "last_reported": "2024-05-18T19:27:33.729389+00:00",
                     "last_updated": "2024-05-18T19:27:33.729389+00:00",
                     "context": {
                         "id": "123456789",
                         "parent_id": null,
                         "user_id": null
                     }
                   }
                ]
                """);

        List<LightState> groupStates = api.getGroupStates("light.test_group");

        assertThat(groupStates).containsExactly(
                LightState.builder()
                          .on(true)
                          .reachable(true)
                          .colormode("ct")
                          .effect("none")
                          .brightness(127)
                          .colorTemperature(334)
                          .x(0.497)
                          .y(0.384)
                          .lightCapabilities(LightCapabilities.builder()
                                                              .ctMin(153)
                                                              .ctMax(500)
                                                              .capabilities(EnumSet.allOf(Capability.class))
                                                              .build())
                          .build(),
                LightState.builder()
                          .on(true)
                          .reachable(true)
                          .colormode("ct")
                          .effect("none")
                          .brightness(245)
                          .colorTemperature(336)
                          .x(0.498)
                          .y(0.383)
                          .lightCapabilities(LightCapabilities.builder()
                                                              .ctMin(153)
                                                              .ctMax(500)
                                                              .capabilities(EnumSet.allOf(Capability.class))
                                                              .build())
                          .build()
        );
    }

    @Test
    void getAssignedGroups_returnsGroupIdsTheGivenLightIdIsContained() {
        setGetResponse("/states", """
                [
                    {
                      "entity_id": "light.couch_group",
                      "state": "on",
                      "attributes": {
                        "supported_color_modes": [
                          "xy"
                        ],
                        "color_mode": "xy",
                        "brightness": 255,
                        "hs_color": [
                          12.464,
                          81.176
                        ],
                        "rgb_color": [
                          255,
                          91,
                          48
                        ],
                        "xy_color": [
                          0.6408,
                          0.3284
                        ],
                        "is_hue_group": true,
                        "hue_scenes": [
                          "Gedimmt",
                          "Nachtlicht",
                          "Hell",
                          "Frühlingsblüten",
                          "Sonnenuntergang Savanne",
                          "Tropendämmerung",
                          "Nordlichter"
                        ],
                        "hue_type": "zone",
                        "lights": [
                          "Couch Light"
                        ],
                        "dynamics": false,
                        "icon": "mdi:lightbulb-group",
                        "friendly_name": "Couch",
                        "supported_features": 40
                      },
                      "last_changed": "2023-09-24T14:06:34.783862+00:00",
                      "last_updated": "2023-09-24T14:06:34.783862+00:00",
                      "context": {
                        "id": "123456789",
                        "parent_id": null,
                        "user_id": null
                      }
                    },
                    {
                      "entity_id": "light.couch_group2",
                      "state": "on",
                      "attributes": {
                        "min_color_temp_kelvin": 2000,
                        "max_color_temp_kelvin": 6535,
                        "min_mireds": 153,
                        "max_mireds": 500,
                        "effect_list": [
                          "None",
                          "candle",
                          "fire",
                          "unknown"
                        ],
                        "supported_color_modes": [
                          "brightness",
                          "color_temp",
                          "xy"
                        ],
                        "color_mode": "brightness",
                        "brightness": 255,
                        "effect": "None",
                        "entity_id": [
                          "light.couch_light"
                        ],
                        "icon": "mdi:lightbulb-group",
                        "friendly_name": "Couch",
                        "supported_features": 44
                      },
                      "last_changed": "2023-09-24T14:06:35.260070+00:00",
                      "last_updated": "2023-09-24T14:20:00.208887+00:00",
                      "context": {
                        "id": "123456789",
                        "parent_id": null,
                        "user_id": null
                      }
                    },
                    {
                      "entity_id": "light.another_group",
                      "state": "on",
                      "attributes": {
                        "min_color_temp_kelvin": 2000,
                        "max_color_temp_kelvin": 6535,
                        "min_mireds": 153,
                        "max_mireds": 500,
                        "effect_list": [
                          "None",
                          "candle",
                          "fire",
                          "unknown"
                        ],
                        "supported_color_modes": [
                          "brightness",
                          "color_temp",
                          "xy"
                        ],
                        "color_mode": "brightness",
                        "brightness": 255,
                        "effect": "None",
                        "entity_id": [
                          "light.another_light"
                        ],
                        "icon": "mdi:lightbulb-group",
                        "friendly_name": "Another Group",
                        "supported_features": 44
                      },
                      "last_changed": "2023-09-24T14:06:35.260070+00:00",
                      "last_updated": "2023-09-24T14:20:00.208887+00:00",
                      "context": {
                        "id": "123456789",
                        "parent_id": null,
                        "user_id": null
                      }
                    },
                    {
                      "entity_id": "light.another_light",
                      "state": "on",
                      "attributes": {
                        "supported_color_modes": [
                          "xy"
                        ],
                        "color_mode": "xy",
                        "brightness": 245,
                        "hs_color": [
                          12.464,
                          81.176
                        ],
                        "rgb_color": [
                          255,
                          91,
                          48
                        ],
                        "xy_color": [
                          0.6408,
                          0.3284
                        ],
                        "mode": "normal",
                        "dynamics": "none",
                        "friendly_name": "Another Light",
                        "supported_features": 40
                      },
                      "last_changed": "2023-09-24T14:50:55.040229+00:00",
                      "last_updated": "2023-09-24T16:13:00.757488+00:00",
                      "context": {
                        "id": "01HB3ZECEN2DN594VKX5RFRQ5P",
                        "parent_id": null,
                        "user_id": null
                      }
                    },
                    {
                      "entity_id": "light.couch_light",
                      "state": "on",
                      "attributes": {
                        "supported_color_modes": [
                          "xy"
                        ],
                        "color_mode": "xy",
                        "brightness": 245,
                        "hs_color": [
                          12.464,
                          81.176
                        ],
                        "rgb_color": [
                          255,
                          91,
                          48
                        ],
                        "xy_color": [
                          0.6408,
                          0.3284
                        ],
                        "mode": "normal",
                        "dynamics": "none",
                        "friendly_name": "Couch Light",
                        "supported_features": 40
                      },
                      "last_changed": "2023-09-24T14:50:55.040229+00:00",
                      "last_updated": "2023-09-24T16:13:00.757488+00:00",
                      "context": {
                        "id": "01HB3ZECEN2DN594VKX5RFRQ5P",
                        "parent_id": null,
                        "user_id": null
                      }
                    }
                  ]
                """);

        assertThat(api.getAssignedGroups("light.couch_light")).containsExactlyInAnyOrder("light.couch_group",
                "light.couch_group2");
        assertThat(api.getAssignedGroups("light.another_light")).containsExactlyInAnyOrder("light.another_group");
    }

    @Test
    void putState_turnOn_brightness_ct_transition_areConvertedToHassFormat() {
        putState(PutCall.builder()
                        .on(true)
                        .id("light.id")
                        .bri(38)
                        .ct(153)
                        .transitionTime(5));

        verify(http).postResource(getUrl("/services/light/turn_on"),
                "{\"entity_id\":\"light.id\",\"brightness\":37,\"color_temp\":153,\"transition\":0.5}");
    }

    @Test
    void putState_turnOn_xy_color() {
        putState(PutCall.builder()
                        .id("light.id")
                        .bri(254)
                        .x(0.354)
                        .y(0.546));

        verify(http).postResource(getUrl("/services/light/turn_on"),
                "{\"entity_id\":\"light.id\",\"brightness\":255,\"xy_color\":[0.354,0.546]}");
    }

    @Test
    void putState_turnOn_xy_missingY_ignoresColor() {
        putState(PutCall.builder()
                        .id("light.id")
                        .bri(254)
                        .x(0.354)); // y missing

        verify(http).postResource(getUrl("/services/light/turn_on"),
                "{\"entity_id\":\"light.id\",\"brightness\":255}");
    }

    @Test
    void putState_turnOn_xy_missingX_ignoresColor() {
        putState(PutCall.builder()
                        .id("light.id")
                        .bri(254)
                        .y(0.546)); // x missing

        verify(http).postResource(getUrl("/services/light/turn_on"),
                "{\"entity_id\":\"light.id\",\"brightness\":255}");
    }

    @Test
    void putState_turnOn_effect() {
        putState(PutCall.builder()
                        .id("light.id")
                        .bri(1)
                        .effect("colorloop"));

        verify(http).postResource(getUrl("/services/light/turn_on"),
                "{\"entity_id\":\"light.id\",\"brightness\":0,\"effect\":\"colorloop\"}");
    }

//    @Test
//    void putState_turnOn_hs_color() { // todo: add Hue/Sat support
//        api.putState(PutCall.builder()
//                            .id("light.id")
//                            .bri(254)
//                   .hue()
//                            .x(0.354)
//                            .y(0.546)
//                            .build());
//
//        verify(http).postResource(getUrl("/services/light/turn_on"),
//                "{\"entity_id\":\"light.id\",\"brightness\":255,\"xy_color\":[0.354,0.546]}");
//    }

    @Test
    void putState_turnOff_transition_callsCorrectEndpoint() {
        putState(PutCall.builder()
                        .on(false)
                        .id("light.id2")
                        .transitionTime(10));

        verify(http).postResource(getUrl("/services/light/turn_off"),
                "{\"entity_id\":\"light.id2\",\"transition\":1.0}");
    }

    @Test
    void unsupportedType_exception() {
        assertThatThrownBy(() -> getLightState("sensor.sun_next_setting")).isInstanceOf(UnsupportedStateException.class);
        assertThatThrownBy(() -> api.getGroupStates("sensor.sun_next_setting")).isInstanceOf(UnsupportedStateException.class);
        assertThatThrownBy(() -> api.getGroupLights("sensor.sun_next_setting")).isInstanceOf(UnsupportedStateException.class);
        assertThatThrownBy(() -> api.getAssignedGroups("sensor.sun_next_setting")).isInstanceOf(UnsupportedStateException.class);
        assertThatThrownBy(() -> api.getLightName("sensor.sun_next_setting")).isInstanceOf(UnsupportedStateException.class);
        assertThatThrownBy(() -> api.getGroupName("sensor.sun_next_setting")).isInstanceOf(UnsupportedStateException.class);
        assertThatThrownBy(() -> api.getLightCapabilities("sensor.sun_next_setting")).isInstanceOf(UnsupportedStateException.class);
        assertThatThrownBy(() -> api.getGroupCapabilities("sensor.sun_next_setting")).isInstanceOf(UnsupportedStateException.class);
        assertThatThrownBy(() -> api.isLightOff("sensor.sun_next_setting")).isInstanceOf(UnsupportedStateException.class);
        assertThatThrownBy(() -> api.isGroupOff("sensor.sun_next_setting")).isInstanceOf(UnsupportedStateException.class);
        assertThatThrownBy(() -> api.putState(PutCall.builder().id("sensor.sun_next_setting").build())).isInstanceOf(UnsupportedStateException.class);
    }

    @Test
    void isLightOff_isOn_false() {
        setGetResponse("/states/light.on_off", """
                {
                  "entity_id": "light.on_off",
                  "state": "on",
                  "attributes": {
                    "supported_color_modes": [],
                    "color_mode": "onoff",
                    "mode": "normal",
                    "dynamics": "none",
                    "friendly_name": "On Off",
                    "supported_features": 0
                  },
                  "last_changed": "2023-09-24T07:55:01.698292+00:00",
                  "last_updated": "2023-09-24T07:55:01.698292+00:00",
                  "context": {
                    "id": "123456789",
                    "parent_id": null,
                    "user_id": null
                  }
                }""");


        assertThat(api.isLightOff("light.on_off")).isFalse();
        assertThat(api.isGroupOff("light.on_off")).isFalse();
    }

    @Test
    void isLightOff_off_true() {
        setGetResponse("/states/light.on_off", """
                {
                  "entity_id": "light.on_off",
                  "state": "off",
                  "attributes": {
                    "supported_color_modes": [],
                    "color_mode": "onoff",
                    "mode": "normal",
                    "dynamics": "none",
                    "friendly_name": "On Off",
                    "supported_features": 0
                  },
                  "last_changed": "2023-09-24T07:55:01.698292+00:00",
                  "last_updated": "2023-09-24T07:55:01.698292+00:00",
                  "context": {
                    "id": "123456789",
                    "parent_id": null,
                    "user_id": null
                  }
                }""");


        assertThat(api.isLightOff("light.on_off")).isTrue();
        assertThat(api.isGroupOff("light.on_off")).isTrue();
    }

    @Test
    void isLightOff_unavailable_true() {
        setGetResponse("/states/light.on_off", """
                {
                  "entity_id": "light.on_off",
                  "state": "unavailable",
                  "attributes": {
                    "supported_color_modes": [],
                    "color_mode": "onoff",
                    "mode": "normal",
                    "dynamics": "none",
                    "friendly_name": "On Off",
                    "supported_features": 0
                  },
                  "last_changed": "2023-09-24T07:55:01.698292+00:00",
                  "last_updated": "2023-09-24T07:55:01.698292+00:00",
                  "context": {
                    "id": "123456789",
                    "parent_id": null,
                    "user_id": null
                  }
                }""");


        assertThat(api.isLightOff("light.on_off")).isTrue();
        assertThat(api.isGroupOff("light.on_off")).isTrue();
    }

    private void setGetResponse(String expectedUrl, @Language("JSON") String response) {
        when(http.getResource(getUrl(expectedUrl))).thenReturn(response);
    }

    private URL getUrl(String expectedUrl) {
        try {
            return new URI(baseUrl + expectedUrl).toURL();
        } catch (MalformedURLException | URISyntaxException e) {
            throw new IllegalArgumentException("Could not create URL", e);
        }
    }

    private void putState(PutCall.PutCallBuilder putCall) {
        api.putState(putCall.build());
    }

    private LightState getLightState(String id) {
        return api.getLightState(id);
    }
}
