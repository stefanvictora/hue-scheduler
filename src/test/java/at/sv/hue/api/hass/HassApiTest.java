package at.sv.hue.api.hass;

import at.sv.hue.ColorMode;
import at.sv.hue.Effect;
import at.sv.hue.api.ApiFailure;
import at.sv.hue.api.BridgeAuthenticationFailure;
import at.sv.hue.api.BridgeConnectionFailure;
import at.sv.hue.api.Capability;
import at.sv.hue.api.EmptyGroupException;
import at.sv.hue.api.GroupInfo;
import at.sv.hue.api.GroupNotFoundException;
import at.sv.hue.api.HttpResourceProvider;
import at.sv.hue.api.Identifier;
import at.sv.hue.api.LightCapabilities;
import at.sv.hue.api.LightNotFoundException;
import at.sv.hue.api.LightState;
import at.sv.hue.api.PutCall;
import at.sv.hue.api.hass.area.HassAreaRegistry;
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

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class HassApiTest {

    private HassApiImpl api;
    private HttpResourceProvider http;
    private HassAreaRegistry areaRegistry;
    private String baseUrl;
    private URL sceneSyncUrl;

    @BeforeEach
    void setUp() {
        http = Mockito.mock(HttpResourceProvider.class);
        areaRegistry = Mockito.mock(HassAreaRegistry.class);
        setupApi("http://localhost:8123");
        sceneSyncUrl = getUrl("/services/scene/create");
    }

    private void setupApi(String origin) {
        HassAvailabilityListener availabilityListener = new HassAvailabilityListener(() -> {
        });
        api = new HassApiImpl(origin, http, areaRegistry, availabilityListener, permits -> {
        });
        baseUrl = origin + "/api";
    }

    @Test
    void assertConnection_stateLookupThrowsApiFailureException_exception() {
        when(http.getResource(any())).thenThrow(new ApiFailure("HTTP Failure"));

        assertThatThrownBy(() -> api.assertConnection()).isInstanceOf(BridgeConnectionFailure.class);
    }

    @Test
    void assertConnection_stateLookupThrowsBridgeAuthenticationException_reThrowsException() {
        when(http.getResource(any())).thenThrow(new BridgeAuthenticationFailure());

        assertThatThrownBy(() -> api.assertConnection()).isInstanceOf(BridgeAuthenticationFailure.class);
    }

    @Test
    void assertConnection_emptyStates_exception() {
        setGetResponse("/states", "[]");

        assertThatThrownBy(() -> api.assertConnection()).isInstanceOf(BridgeConnectionFailure.class);
    }

    @Test
    void assertConnection_hasStates_noException() {
        setGetResponse("/states", """
                [
                  {
                    "entity_id": "light.schreibtisch_r",
                    "state": "on",
                    "attributes": {
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
                  }
                ]
                """);

        assertThatNoException().isThrownBy(() -> api.assertConnection());
    }

    @Test
    void getLightIdOrName_notFound_exception() {
        setGetResponse("/states", "[]");

        assertThatThrownBy(() -> api.getLightIdentifierByName("UNKNOWN NAME")).isInstanceOf(LightNotFoundException.class);
        assertThatThrownBy(() -> api.getLightIdentifier("light.unknown")).isInstanceOf(LightNotFoundException.class);
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

        assertThat(api.getLightIdentifier("light.schreibtisch_r")).isEqualTo(new Identifier("light.schreibtisch_r", "Schreibtisch R"));
        assertThat(api.getLightIdentifier("light.flur")).isEqualTo(new Identifier("light.flur", "Flur"));
        assertThat(api.getGroupIdentifier("light.flur")).isEqualTo(new Identifier("light.flur", "Flur"));

        assertThat(api.getLightIdentifierByName("Schreibtisch R")).isEqualTo(new Identifier("light.schreibtisch_r", "Schreibtisch R"));
        assertThat(api.getGroupIdentifierByName("Flur")).isEqualTo(new Identifier("light.flur", "Flur"));

        api.clearCaches(); // todo: migrate to caffein caches

        assertThat(api.getLightCapabilities("light.schreibtisch_r")).isEqualTo(LightCapabilities.builder()
                                                                                                .ctMin(153)
                                                                                                .ctMax(500)
                                                                                                .effects(List.of("candle", "fire"))
                                                                                                .capabilities(EnumSet.of(Capability.COLOR,
                                                                                                        Capability.COLOR_TEMPERATURE, Capability.BRIGHTNESS,
                                                                                                        Capability.ON_OFF))
                                                                                                .build());

        // Not found states
        assertThatThrownBy(() -> api.getLightIdentifier("light.unknown_light")).isInstanceOf(LightNotFoundException.class);
        assertThatThrownBy(() -> api.getGroupIdentifier("light.unknown_group")).isInstanceOf(LightNotFoundException.class);
        assertThatThrownBy(() -> api.getLightIdentifierByName("UNKNOWN LIGHT")).isInstanceOf(LightNotFoundException.class);
        assertThatThrownBy(() -> api.getGroupIdentifierByName("UNKNOWN GROUP")).isInstanceOf(LightNotFoundException.class);

        // Non unique names
        assertThatThrownBy(() -> api.getLightIdentifierByName("On Off")).isInstanceOf(NonUniqueNameException.class);

        // Not a group
        assertThatThrownBy(() -> api.getGroupIdentifierByName("Schreibtisch R")).isInstanceOf(GroupNotFoundException.class);
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
                      "CANDLE",
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
                    "effect": "off",
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
                                                               .effects(List.of("candle", "fire"))
                                                               .capabilities(EnumSet.of(Capability.COLOR,
                                                                       Capability.COLOR_TEMPERATURE, Capability.BRIGHTNESS,
                                                                       Capability.ON_OFF))
                                                               .build();
        assertThat(lightState).isEqualTo(LightState.builder()
                                                   .id("light.schreibtisch_r")
                                                   .on(true)
                                                   .x(0.6024)
                                                   .y(0.3433)
                                                   .effect("none") // "off" treated as "none"
                                                   .colormode(ColorMode.XY)
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
                                                   .id("light.schreibtisch_r")
                                                   .on(true)
                                                   .colorTemperature(161)
                                                   .x(0.334)
                                                   .y(0.336)
                                                   .effect("none")
                                                   .colormode(ColorMode.CT)
                                                   .brightness(38) // converted to hue range
                                                   .lightCapabilities(LightCapabilities.builder()
                                                                                       .ctMin(153)
                                                                                       .ctMax(500)
                                                                                       .effects(List.of("candle", "fire"))
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
                            "effect": "Candle",
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
                                                   .id("light.ct_only")
                                                   .on(true)
                                                   .colorTemperature(366)
                                                   .x(0.524)
                                                   .y(0.387)
                                                   .effect("candle")
                                                   .colormode(ColorMode.CT)
                                                   .brightness(233) // converted to hue range
                                                   .lightCapabilities(LightCapabilities.builder()
                                                                                       .ctMin(153)
                                                                                       .ctMax(454)
                                                                                       .effects(List.of("candle"))
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
                                                   .id("light.xy_only")
                                                   .on(true)
                                                   .x(0.6311)
                                                   .y(0.3325)
                                                   .colormode(ColorMode.XY)
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
                                                   .id("light.ceiling")
                                                   .on(false)
                                                   .unavailable(true)
                                                   .lightCapabilities(LightCapabilities.builder()
                                                                                       .ctMin(153)
                                                                                       .ctMax(500)
                                                                                       .effects(List.of("candle", "fire"))
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
                                                   .id("light.off")
                                                   .on(false)
                                                   .lightCapabilities(LightCapabilities.builder()
                                                                                       .ctMin(153)
                                                                                       .ctMax(454)
                                                                                       .effects(List.of("candle"))
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
                                                   .id("light.on_off")
                                                   .on(true)
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
                                                   .id("switch.switch_demo")
                                                   .on(true)
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
                                                   .id("input_boolean.test_toggle")
                                                   .on(true)
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
                                                   .id("light.on_off")
                                                   .on(false)
                                                   .unavailable(true)
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
                                                   .id("light.brightness")
                                                   .on(true)
                                                   .brightness(254) // adjusted to max value for hue
                                                   .effect("none")
                                                   .lightCapabilities(LightCapabilities.builder()
                                                                                       .effects(List.of("candle"))
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
                                                   .id("light.brightness")
                                                   .on(true)
                                                   .brightness(1) // adjusted to min value for hue
                                                   .effect("none")
                                                   .lightCapabilities(LightCapabilities.builder()
                                                                                       .effects(List.of("candle"))
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
                                                   .id("light.brightness")
                                                   .on(true)
                                                   .brightness(199) // adjusted correctly
                                                   .effect("none")
                                                   .lightCapabilities(LightCapabilities.builder()
                                                                                       .effects(List.of("candle"))
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
    void getGroupLights_newHueGroupsWithEntityId() {
        setGetResponse("/states", """
                [
                    {
                        "entity_id": "light.bad",
                        "state": "off",
                        "attributes": {
                            "min_color_temp_kelvin": 2000,
                            "max_color_temp_kelvin": 6535,
                            "min_mireds": 153,
                            "max_mireds": 500,
                            "supported_color_modes": [
                                "color_temp",
                                "xy"
                            ],
                            "color_mode": null,
                            "brightness": null,
                            "color_temp_kelvin": null,
                            "color_temp": null,
                            "hs_color": null,
                            "rgb_color": null,
                            "xy_color": null,
                            "is_hue_group": true,
                            "hue_scenes": [
                                "Hell",
                                "HueScheduler",
                                "Gedimmt",
                                "Abend"
                            ],
                            "hue_type": "room",
                            "lights": [
                                "Bad Oben",
                                "Bad Tür",
                                "Bad Therme neu"
                            ],
                            "entity_id": [
                                "light.bad_therme_neu",
                                "light.bad_oben",
                                "light.bad_tur"
                            ],
                            "dynamics": false,
                            "icon": "mdi:lightbulb-group",
                            "friendly_name": "Bad",
                            "supported_features": 40
                        },
                        "last_changed": "2025-02-08T10:46:13.048207+00:00",
                        "last_reported": "2025-02-08T10:46:13.048207+00:00",
                        "last_updated": "2025-02-08T10:46:13.048207+00:00",
                        "context": {
                            "id": "01JKJJNH9RNH9HBDYGZMYGM7VY",
                            "parent_id": null,
                            "user_id": null
                        }
                    }
                ]
                """);

        assertThat(api.getGroupLights("light.bad")).containsExactlyInAnyOrder("light.bad_therme_neu",
                "light.bad_oben", "light.bad_tur"
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
                          .id("light.schreibtisch_l")
                          .on(false)
                          .unavailable(true)
                          .colormode(ColorMode.CT)
                          .effect("none")
                          .brightness(127)
                          .colorTemperature(334)
                          .x(0.497)
                          .y(0.384)
                          .lightCapabilities(LightCapabilities.builder()
                                                              .ctMin(153)
                                                              .ctMax(500)
                                                              .effects(List.of("candle", "fire"))
                                                              .capabilities(EnumSet.of(Capability.COLOR,
                                                                      Capability.BRIGHTNESS, Capability.ON_OFF,
                                                                      Capability.COLOR_TEMPERATURE))
                                                              .build())
                          .build(),
                LightState.builder()
                          .id("light.schreibtisch_r")
                          .on(true)
                          .colormode(ColorMode.CT)
                          .effect("none")
                          .brightness(245)
                          .colorTemperature(336)
                          .x(0.498)
                          .y(0.383)
                          .lightCapabilities(LightCapabilities.builder()
                                                              .ctMin(153)
                                                              .ctMax(500)
                                                              .effects(List.of("candle", "fire"))
                                                              .capabilities(EnumSet.of(Capability.COLOR,
                                                                      Capability.BRIGHTNESS, Capability.ON_OFF,
                                                                      Capability.COLOR_TEMPERATURE))
                                                              .build())
                          .build()
        );
    }

    @Test
    void getAssignedGroups_returnsGroupIdsTheGivenLightIdIsContained_ignoresScenes() {
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
                    "entity_id": "light.couch_group3",
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
                      "entity_id": [
                        "light.couch_light"
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
                    "entity_id": "light.couch_group_invalid",
                    "state": "on",
                    "attributes": {
                      "is_hue_group": true,
                      "hue_type": "zone",
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
                  },
                  {
                    "entity_id": "scene.test_scene",
                    "state": "2024-06-22T20:26:10.406785+00:00",
                    "attributes": {
                      "entity_id": [
                        "light.couch_light",
                        "light.another_light"
                      ],
                      "id": "1719087533616",
                      "friendly_name": "Test Scene"
                    },
                    "last_changed": "2024-06-22T20:26:48.309168+00:00",
                    "last_reported": "2024-06-22T20:26:48.309168+00:00",
                    "last_updated": "2024-06-22T20:26:48.309168+00:00",
                    "context": {
                      "id": "01J10T2K3NY0G61R0PE3K95TXZ",
                      "parent_id": null,
                      "user_id": null
                    }
                  }
                ]
                """);

        assertThat(api.getAssignedGroups("light.couch_light")).containsExactlyInAnyOrder("light.couch_group",
                "light.couch_group2", "light.couch_group3");
        assertThat(api.getAssignedGroups("light.another_light")).containsExactlyInAnyOrder("light.another_group");
        assertThat(api.getAssignedGroups("light.unknown_light")).isEmpty();
    }

    @Test
    void getAdditionalAreas_returnsDistinctAreasList_ignoresNull() {
        GroupInfo kitchenArea = new GroupInfo("kitchen", List.of("light.kitchen_1", "light.kitchen_2", "light.kitchen_3"));
        mockAreaForEntity("light.kitchen_1", kitchenArea);
        mockAreaForEntity("light.kitchen_2", kitchenArea);
        mockAreaForEntity("light.living_room", null);

        List<GroupInfo> areas = api.getAdditionalAreas(List.of("light.kitchen_1", "light.kitchen_2",
                "light.living_room", "light.unknown"));

        assertThat(areas).containsExactlyInAnyOrder(kitchenArea);
    }

    @Test
    void createOrUpdateScene_withMultipleLights_createsSceneWithAllLights_implicitlySetsOn() {
        String groupId = "kitchen";
        String sceneName = "HueScheduler";
        List<PutCall> putCalls = List.of(
                PutCall.builder()
                       .id("light.kitchen_main")
                       .bri(254)
                       .ct(100)
                       .build(),
                PutCall.builder()
                       .id("light.kitchen_table")
                       .on(true)
                       .bri(100)
                       .x(0.497)
                       .y(0.384)
                       .build(),
                PutCall.builder()
                       .id("light.kitchen_counter")
                       .on(false)
                       .transitionTime(400) // ignored
                       .bri(100) // ignored
                       .build()
        );

        createOrUpdateScene(groupId, sceneName, putCalls);

        verify(http).postResource(sceneSyncUrl, removeSpaces("""
                {
                  "scene_id" : "huescheduler_kitchen",
                  "entities" : {
                    "light.kitchen_main" : {
                      "state" : "on",
                      "brightness" : 255,
                      "color_temp" : 100
                    },
                    "light.kitchen_table" : {
                      "state" : "on",
                      "brightness" : 100,
                      "xy_color" : [ 0.497, 0.384 ]
                    },
                    "light.kitchen_counter" : {
                      "state" : "off"
                    }
                  }
                }""")
        );
    }

    @Test
    void createOrUpdateScene_withEmptyPutCalls_createsSceneWithNoStates() {
        String groupId = "bedroom";
        String sceneName = "night";
        List<PutCall> putCalls = List.of();

        createOrUpdateScene(groupId, sceneName, putCalls);

        verify(http).postResource(eq(sceneSyncUrl), argThat(body ->
                body.contains("\"scene_id\":\"night_bedroom\"") &&
                body.contains("\"entities\":{}}")
        ));
    }

    @Test
    void createOrUpdateScene_withSpecialCharactersInName_normalizesSceneId() {
        String groupId = "living-room";
        String sceneName = "Movie Time!";
        PutCall putCall = PutCall.builder()
                                 .id("light.living_room_main")
                                 .on(true)
                                 .build();

        createOrUpdateScene(groupId, sceneName, List.of(putCall));

        verify(http).postResource(eq(sceneSyncUrl), argThat(body ->
                body.contains("\"scene_id\":\"movie_time__living_room\"")
        ));
    }

    @Test
    void getAffectedIdsByDevice_returnsGivenId_andContainedGroups() {
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

        assertThat(api.getAffectedIdsByDevice("light.couch_light")).containsExactlyInAnyOrder("light.couch_light",
                "light.couch_group", "light.couch_group2");
    }

    @Test
    void getAffectedIdsByScene_getSceneName_returnsIds_andName() {
        setGetResponse("/states", """
                [
                  {
                    "entity_id": "scene.test_scene",
                    "state": "2024-06-22T20:26:10.406785+00:00",
                    "attributes": {
                      "entity_id": [
                        "light.1",
                        "light.2"
                      ],
                      "id": "1719087533616",
                      "friendly_name": "Test Scene"
                    },
                    "last_changed": "2024-06-22T20:26:48.309168+00:00",
                    "last_reported": "2024-06-22T20:26:48.309168+00:00",
                    "last_updated": "2024-06-22T20:26:48.309168+00:00",
                    "context": {
                      "id": "01J10T2K3NY0G61R0PE3K95TXZ",
                      "parent_id": null,
                      "user_id": null
                    }
                  },
                  {
                    "entity_id": "scene.hue_scene",
                    "state": "unknown",
                    "attributes": {
                      "group_name": "Wohnzimmer",
                      "group_type": "room",
                      "name": "Miami",
                      "speed": 0.6904761904761905,
                      "brightness": 80.0,
                      "is_dynamic": true,
                      "friendly_name": "Wohnzimmer Miami"
                    },
                    "last_changed": "2024-06-23T11:52:38.272542+00:00",
                    "last_reported": "2024-06-23T12:01:28.811621+00:00",
                    "last_updated": "2024-06-23T11:52:38.272542+00:00",
                    "context": {
                      "id": "01J12F1V40TBK47BJVWE3KPBJR",
                      "parent_id": null,
                      "user_id": null
                    }
                  },
                  {
                    "entity_id": "scene.hue_scene_broken1",
                    "state": "unknown",
                    "attributes": {
                      "group_name": "NOT FOUND",
                      "group_type": "room",
                      "name": "Miami",
                      "speed": 0.6904761904761905,
                      "brightness": 80.0,
                      "is_dynamic": true,
                      "friendly_name": "not known"
                    },
                    "last_changed": "2024-06-23T11:52:38.272542+00:00",
                    "last_reported": "2024-06-23T12:01:28.811621+00:00",
                    "last_updated": "2024-06-23T11:52:38.272542+00:00",
                    "context": {
                      "id": "01J12F1V40TBK47BJVWE3KPBJR",
                      "parent_id": null,
                      "user_id": null
                    }
                  },
                  {
                    "entity_id": "scene.hue_scene_broken2",
                    "state": "unknown",
                    "attributes": {
                      "group_name": "Light 1",
                      "group_type": "room",
                      "name": "Miami",
                      "speed": 0.6904761904761905,
                      "brightness": 80.0,
                      "is_dynamic": true,
                      "friendly_name": "not known"
                    },
                    "last_changed": "2024-06-23T11:52:38.272542+00:00",
                    "last_reported": "2024-06-23T12:01:28.811621+00:00",
                    "last_updated": "2024-06-23T11:52:38.272542+00:00",
                    "context": {
                      "id": "01J12F1V40TBK47BJVWE3KPBJR",
                      "parent_id": null,
                      "user_id": null
                    }
                  },
                  {
                    "entity_id": "light.wohnzimmer",
                    "state": "on",
                    "attributes": {
                      "is_hue_group": true,
                      "hue_scenes": [
                        "Miami"
                      ],
                      "hue_type": "room",
                      "lights": [
                        "Light 1",
                        "Light 3"
                      ],
                      "friendly_name": "Wohnzimmer",
                      "supported_features": 40
                    },
                    "last_changed": "2024-06-28T07:15:30.973498+00:00",
                    "last_reported": "2024-06-28T07:15:32.571302+00:00",
                    "last_updated": "2024-06-28T07:15:32.571302+00:00",
                    "context": {
                      "id": "01J1EV622V51EZPV8480BNYY5J",
                      "parent_id": null,
                      "user_id": null
                    }
                  },
                  {
                    "entity_id": "light.1",
                    "state": "on",
                    "attributes": {
                      "friendly_name": "Light 1",
                      "supported_features": 40
                    },
                    "last_changed": "2024-06-28T07:15:30.790775+00:00",
                    "last_reported": "2024-06-28T07:15:32.442080+00:00",
                    "last_updated": "2024-06-28T07:15:32.442080+00:00",
                    "context": {
                      "id": "01J1EV61YTBP7RKF3H66D2XD7W",
                      "parent_id": null,
                      "user_id": null
                    }
                  },
                  {
                    "entity_id": "light.3",
                    "state": "on",
                    "attributes": {
                      "friendly_name": "Light 3",
                      "supported_features": 40
                    },
                    "last_changed": "2024-06-28T07:15:30.774358+00:00",
                    "last_reported": "2024-06-28T07:15:32.424046+00:00",
                    "last_updated": "2024-06-28T07:15:32.424046+00:00",
                    "context": {
                      "id": "01J1EV61Y8B2Q7ENQYK8YTY9EP",
                      "parent_id": null,
                      "user_id": null
                    }
                  }
                ]
                """);

        assertThat(api.getAffectedIdsByScene("scene.unknown")).isEmpty();
        assertThat(api.getAffectedIdsByScene("scene.test_scene")).containsExactly("light.1", "light.2");
        assertThat(api.getAffectedIdsByScene("scene.hue_scene")).containsExactly("light.wohnzimmer", "light.1", "light.3");
        assertThat(api.getAffectedIdsByScene("scene.hue_scene_broken1")).isEmpty();
        assertThat(api.getAffectedIdsByScene("scene.hue_scene_broken2")).isEmpty();

        assertThat(api.getSceneName("scene.test_scene")).isEqualTo("Test Scene");
        assertThat(api.getSceneName("scene.hue_scene")).isEqualTo("Wohnzimmer Miami");
        assertThat(api.getSceneName("scene.unknown")).isNull();
    }

    @Test
    void onModification_updatesStates_getSceneName_initiallyNoResult_afterwardsSceneIsFound() {
        String sceneName = "Test Scene";
        String sceneId = "scene.test_scene";
        setGetResponse("/states", "[]");

        assertThat(api.getSceneName(sceneId)).isNull();
        assertThatThrownBy(() -> api.getLightIdentifierByName(sceneName)).isInstanceOf(LightNotFoundException.class);

        State updatedScene = createExampleState(sceneId, sceneName);
        api.onModification(null, sceneId, updatedScene);

        // Name is now found
        assertThat(api.getSceneName(sceneId)).isEqualTo(sceneName);
        assertThat(api.getLightIdentifierByName(sceneName)).isEqualTo(new Identifier(sceneId, sceneName));

        // Scene deleted again
        api.onModification(null, sceneId, null);

        // Reflected in lookup
        assertThat(api.getSceneName(sceneId)).isNull();
        assertThatThrownBy(() -> api.getLightIdentifierByName(sceneName)).isInstanceOf(LightNotFoundException.class);
    }

    @Test
    void onModification_noLookupBefore_ignored() {
        String sceneId = "scene.ignored";
        setGetResponse("/states", "[]");

        State ignoredScene = createExampleState(sceneId, "Ignored scene");
        api.onModification(null, "light.test", ignoredScene);

        // Performs initial API fetch and ignores any modifications before
        assertThat(api.getSceneName(sceneId)).isNull();
    }

    @Test
    void onModification_updatesStates_getSceneName_overwritesExistingEntry() {
        String sceneId = "scene.test_scene";
        setGetResponse("/states", """
                [
                  {
                    "entity_id": "scene.test_scene",
                    "state": "2024-06-22T20:26:10.406785+00:00",
                    "attributes": {
                      "entity_id": [
                        "light.1",
                        "light.2"
                      ],
                      "id": "1719087533616",
                      "friendly_name": "Test Scene"
                    },
                    "last_changed": "2024-06-22T20:26:48.309168+00:00",
                    "last_reported": "2024-06-22T20:26:48.309168+00:00",
                    "last_updated": "2024-06-22T20:26:48.309168+00:00",
                    "context": {
                      "id": "01J10T2K3NY0G61R0PE3K95TXZ",
                      "parent_id": null,
                      "user_id": null
                    }
                  }
                ]
                """);

        assertThat(api.getSceneName(sceneId)).isEqualTo("Test Scene");

        api.onModification(null, sceneId, createExampleState(sceneId, "Updated Name"));

        assertThat(api.getSceneName(sceneId)).isEqualTo("Updated Name");

        // Ignores non-State objects
        api.onModification(null, sceneId, new Object());

        assertThat(api.getSceneName(sceneId)).isEqualTo("Updated Name");
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
    void putGroupState_noDifference() {
        api.putGroupState(PutCall.builder()
                                 .on(true)
                                 .id("light.id")
                                 .bri(38)
                                 .ct(153)
                                 .transitionTime(5).build());

        verify(http).postResource(getUrl("/services/light/turn_on"),
                "{\"entity_id\":\"light.id\",\"brightness\":37,\"color_temp\":153,\"transition\":0.5}");
    }

    @Test
    void putSceneState_sendsEachRequestSeparately() {
        api.putSceneState("1",
                List.of(PutCall.builder()
                               .on(true)
                               .id("light.id1")
                               .bri(38)
                               .build(),
                        PutCall.builder()
                               .id("light.id2")
                               .bri(38)
                               .build()));

        verify(http).postResource(getUrl("/services/light/turn_on"),
                "{\"entity_id\":\"light.id1\",\"brightness\":37}");
        verify(http).postResource(getUrl("/services/light/turn_on"),
                "{\"entity_id\":\"light.id2\",\"brightness\":37}");
    }

    @Test
    void putState_supportsOtherOrigin() {
        setupApi("https://123456789.ui.nabu.casa");

        putState(PutCall.builder()
                        .id("light.id")
                        .bri(254)
                        .x(0.354)
                        .y(0.546));

        verify(http).postResource(getUrl("/services/light/turn_on"),
                "{\"entity_id\":\"light.id\",\"brightness\":255,\"xy_color\":[0.354,0.546]}");
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
    void putState_turnOn_xy_performsGamutCorrection() {
        putState(PutCall.builder()
                        .id("light.id")
                        .bri(254)
                        .x(0.8)
                        .y(0.2));

        verify(http).postResource(getUrl("/services/light/turn_on"),
                "{\"entity_id\":\"light.id\",\"brightness\":255,\"xy_color\":[0.6915,0.3083]}");
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
                        .effect(Effect.builder().effect("prism").build()));

        verify(http).postResource(getUrl("/services/light/turn_on"),
                "{\"entity_id\":\"light.id\",\"brightness\":1,\"effect\":\"prism\"}");
    }

    @Test
    void putState_turnOn_effect_none_treatedAsOff() {
        putState(PutCall.builder()
                        .id("light.id")
                        .bri(1)
                        .effect(Effect.builder().effect("none").build()));

        verify(http).postResource(getUrl("/services/light/turn_on"),
                "{\"entity_id\":\"light.id\",\"brightness\":1,\"effect\":\"off\"}");
    }

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
    void putState_turnOn_inputBoolean_usesCorrectService() {
        putState(PutCall.builder()
                        .id("input_boolean.test_switch"));

        verify(http).postResource(getUrl("/services/input_boolean/turn_on"),
                "{\"entity_id\":\"input_boolean.test_switch\"}");
    }

    @Test
    void putState_turnOff_inputBoolean_usesCorrectService() {
        putState(PutCall.builder()
                        .id("input_boolean.another_switch")
                        .on(false));

        verify(http).postResource(getUrl("/services/input_boolean/turn_off"),
                "{\"entity_id\":\"input_boolean.another_switch\"}");
    }

    @Test
    void putState_turnOn_switch_usesCorrectService() {
        putState(PutCall.builder()
                        .id("switch.tv_mute"));

        verify(http).postResource(getUrl("/services/switch/turn_on"),
                "{\"entity_id\":\"switch.tv_mute\"}");
    }

    @Test
    void putState_turnOn_fan_usesCorrectService() {
        putState(PutCall.builder()
                        .id("fan.test_fan"));

        verify(http).postResource(getUrl("/services/fan/turn_on"),
                "{\"entity_id\":\"fan.test_fan\"}");
    }

    @Test
    void unsupportedType_exception() {
        assertThatThrownBy(() -> getLightState("sensor.sun_next_setting")).isInstanceOf(UnsupportedStateException.class);
        assertThatThrownBy(() -> getLightState("zone.home")).isInstanceOf(UnsupportedStateException.class);
        assertThatThrownBy(() -> getLightState("person.stefans_home")).isInstanceOf(UnsupportedStateException.class);
        assertThatThrownBy(() -> api.getGroupStates("sensor.sun_next_setting")).isInstanceOf(UnsupportedStateException.class);
        assertThatThrownBy(() -> api.getGroupLights("sensor.sun_next_setting")).isInstanceOf(UnsupportedStateException.class);
        assertThatThrownBy(() -> api.getAssignedGroups("sensor.sun_next_setting")).isInstanceOf(UnsupportedStateException.class);
        assertThatThrownBy(() -> api.getLightIdentifier("sensor.sun_next_setting")).isInstanceOf(UnsupportedStateException.class);
        assertThatThrownBy(() -> api.getGroupIdentifier("sensor.sun_next_setting")).isInstanceOf(UnsupportedStateException.class);
        assertThatThrownBy(() -> api.getLightCapabilities("sensor.sun_next_setting")).isInstanceOf(UnsupportedStateException.class);
        assertThatThrownBy(() -> api.getGroupCapabilities("sensor.sun_next_setting")).isInstanceOf(UnsupportedStateException.class);
        assertThatThrownBy(() -> api.isLightOff("sensor.sun_next_setting")).isInstanceOf(UnsupportedStateException.class);
        assertThatThrownBy(() -> api.isGroupOff("sensor.sun_next_setting")).isInstanceOf(UnsupportedStateException.class);
        assertThatThrownBy(() -> api.putState(PutCall.builder().id("sensor.sun_next_setting").build())).isInstanceOf(UnsupportedStateException.class);

        assertThatThrownBy(() -> api.putState(PutCall.builder().id("scene.test_scene").build())).isInstanceOf(UnsupportedStateException.class);
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

    private void mockAreaForEntity(String entityId, GroupInfo area) {
        when(areaRegistry.lookupAreaForEntity(entityId)).thenReturn(area);
    }

    private void createOrUpdateScene(String groupId, String sceneName, List<PutCall> putCalls) {
        api.createOrUpdateScene(groupId, sceneName, putCalls);
    }

    private static String removeSpaces(@Language("JSON") String expectedBody) {
        return expectedBody.replaceAll("\\s+|\\n", "");
    }

    private static State createExampleState(String entityId, String friendlyName) {
        State state = new State();
        state.setEntity_id(entityId);
        StateAttributes attributes = new StateAttributes();
        attributes.setFriendly_name(friendlyName);
        state.setAttributes(attributes);
        return state;
    }
}
