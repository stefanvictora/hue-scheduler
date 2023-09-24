package at.sv.hue.api.hass;

import at.sv.hue.api.Capability;
import at.sv.hue.api.EmptyGroupException;
import at.sv.hue.api.GroupNotFoundException;
import at.sv.hue.api.HttpResourceProvider;
import at.sv.hue.api.LightCapabilities;
import at.sv.hue.api.LightNotFoundException;
import at.sv.hue.api.LightState;
import at.sv.hue.api.PutCall;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.MalformedURLException;
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
        setGetResponse("/states", "[\n" +
                "  {\n" +
                "    \"entity_id\": \"person.stefans_home\",\n" +
                "    \"state\": \"unknown\",\n" +
                "    \"attributes\": {\n" +
                "      \"editable\": true,\n" +
                "      \"id\": \"stefans_home\",\n" +
                "      \"user_id\": \"123456\",\n" +
                "      \"device_trackers\": [],\n" +
                "      \"friendly_name\": \"Stefans Home\"\n" +
                "    },\n" +
                "    \"last_changed\": \"2023-09-21T17:35:56.486268+00:00\",\n" +
                "    \"last_updated\": \"2023-09-21T17:36:01.279407+00:00\",\n" +
                "    \"context\": {\n" +
                "      \"id\": \"123456789\",\n" +
                "      \"parent_id\": null,\n" +
                "      \"user_id\": null\n" +
                "    }\n" +
                "  },\n" +
                "  {\n" +
                "    \"entity_id\": \"zone.home\",\n" +
                "    \"state\": \"0\",\n" +
                "    \"attributes\": {\n" +
                "      \"latitude\": 1.0,\n" +
                "      \"longitude\": 1.0,\n" +
                "      \"radius\": 100,\n" +
                "      \"passive\": false,\n" +
                "      \"persons\": [],\n" +
                "      \"editable\": true,\n" +
                "      \"icon\": \"mdi:home\",\n" +
                "      \"friendly_name\": \"Home\"\n" +
                "    },\n" +
                "    \"last_changed\": \"2023-09-21T17:35:58.872380+00:00\",\n" +
                "    \"last_updated\": \"2023-09-21T17:35:58.872380+00:00\",\n" +
                "    \"context\": {\n" +
                "      \"id\": \"123456789\",\n" +
                "      \"parent_id\": null,\n" +
                "      \"user_id\": null\n" +
                "    }\n" +
                "  },\n" +
                "  {\n" +
                "    \"entity_id\": \"light.schreibtisch_r\",\n" +
                "    \"state\": \"unavailable\",\n" +
                "    \"attributes\": {\n" +
                "      \"min_color_temp_kelvin\": 2000,\n" +
                "      \"max_color_temp_kelvin\": 6535,\n" +
                "      \"min_mireds\": 153,\n" +
                "      \"max_mireds\": 500,\n" +
                "      \"effect_list\": [\n" +
                "        \"None\",\n" +
                "        \"candle\",\n" +
                "        \"fire\",\n" +
                "        \"unknown\"\n" +
                "      ],\n" +
                "      \"supported_color_modes\": [\n" +
                "        \"color_temp\",\n" +
                "        \"xy\"\n" +
                "      ],\n" +
                "      \"friendly_name\": \"Schreibtisch R\",\n" +
                "      \"supported_features\": 44\n" +
                "    },\n" +
                "    \"last_changed\": \"2023-09-24T08:09:56.861254+00:00\",\n" +
                "    \"last_updated\": \"2023-09-24T08:09:56.861254+00:00\",\n" +
                "    \"context\": {\n" +
                "      \"id\": \"123456789\",\n" +
                "      \"parent_id\": null,\n" +
                "      \"user_id\": null\n" +
                "    }\n" +
                "  },\n" +
                "  {\n" +
                "    \"entity_id\": \"light.flur\",\n" +
                "    \"state\": \"on\",\n" +
                "    \"attributes\": {\n" +
                "      \"min_color_temp_kelvin\": 2202,\n" +
                "      \"max_color_temp_kelvin\": 6535,\n" +
                "      \"min_mireds\": 153,\n" +
                "      \"max_mireds\": 454,\n" +
                "      \"supported_color_modes\": [\n" +
                "        \"color_temp\",\n" +
                "        \"xy\"\n" +
                "      ],\n" +
                "      \"color_mode\": \"xy\",\n" +
                "      \"brightness\": 90,\n" +
                "      \"hs_color\": [\n" +
                "        15.638,\n" +
                "        73.725\n" +
                "      ],\n" +
                "      \"rgb_color\": [\n" +
                "        255,\n" +
                "        116,\n" +
                "        67\n" +
                "      ],\n" +
                "      \"xy_color\": [\n" +
                "        0.6024,\n" +
                "        0.3433\n" +
                "      ],\n" +
                "      \"is_hue_group\": true,\n" +
                "      \"hue_scenes\": [],\n" +
                "      \"hue_type\": \"room\",\n" +
                "      \"lights\": [\n" +
                "        \"Eingang S Tür\",\n" +
                "        \"Eingang S Klo\",\n" +
                "        \"Eingang S Kleider\",\n" +
                "        \"Flur S Mitte\",\n" +
                "        \"Flur S Tür\",\n" +
                "        \"Flur S Stiege\",\n" +
                "        \"Eingang\"\n" +
                "      ],\n" +
                "      \"dynamics\": false,\n" +
                "      \"icon\": \"mdi:lightbulb-group\",\n" +
                "      \"friendly_name\": \"Flur\",\n" +
                "      \"supported_features\": 40\n" +
                "    },\n" +
                "    \"last_changed\": \"2023-09-21T17:59:29.726678+00:00\",\n" +
                "    \"last_updated\": \"2023-09-24T08:04:31.393954+00:00\",\n" +
                "    \"context\": {\n" +
                "      \"id\": \"01HB33FY115WJHS4DRSP1V83AV\",\n" +
                "      \"parent_id\": null,\n" +
                "      \"user_id\": null\n" +
                "    }\n" +
                "  },\n" +
                "  {\n" +
                "    \"entity_id\": \"light.on_off_1\",\n" +
                "    \"state\": \"on\",\n" +
                "    \"attributes\": {\n" +
                "      \"supported_color_modes\": [],\n" +
                "      \"color_mode\": \"onoff\",\n" +
                "      \"mode\": \"normal\",\n" +
                "      \"dynamics\": \"none\",\n" +
                "      \"friendly_name\": \"On Off\",\n" +
                "      \"supported_features\": 0\n" +
                "    },\n" +
                "    \"last_changed\": \"2023-09-24T07:55:01.698292+00:00\",\n" +
                "    \"last_updated\": \"2023-09-24T07:55:01.698292+00:00\",\n" +
                "    \"context\": {\n" +
                "      \"id\": \"123456789\",\n" +
                "      \"parent_id\": null,\n" +
                "      \"user_id\": null\n" +
                "    }\n" +
                "  },\n" +
                "  {\n" +
                "    \"entity_id\": \"light.on_off_2\",\n" +
                "    \"state\": \"on\",\n" +
                "    \"attributes\": {\n" +
                "      \"supported_color_modes\": [],\n" +
                "      \"color_mode\": \"onoff\",\n" +
                "      \"mode\": \"normal\",\n" +
                "      \"dynamics\": \"none\",\n" +
                "      \"friendly_name\": \"On Off\",\n" +
                "      \"supported_features\": 0\n" +
                "    },\n" +
                "    \"last_changed\": \"2023-09-24T07:55:01.698292+00:00\",\n" +
                "    \"last_updated\": \"2023-09-24T07:55:01.698292+00:00\",\n" +
                "    \"context\": {\n" +
                "      \"id\": \"123456789\",\n" +
                "      \"parent_id\": null,\n" +
                "      \"user_id\": null\n" +
                "    }\n" +
                "  }\n" +
                "]\n");

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
    }

    @Test
    void getLightState_colorAndCT_xyColorMode_returnsState() {
        setGetResponse("/states/light.schreibtisch_r", "{\n" +
                "  \"entity_id\": \"light.schreibtisch_r\",\n" +
                "  \"state\": \"on\",\n" +
                "  \"attributes\": {\n" +
                "    \"min_color_temp_kelvin\": 2000,\n" +
                "    \"max_color_temp_kelvin\": 6535,\n" +
                "    \"min_mireds\": 153,\n" +
                "    \"max_mireds\": 500,\n" +
                "    \"effect_list\": [\n" +
                "      \"None\",\n" +
                "      \"candle\",\n" +
                "      \"fire\",\n" +
                "      \"unknown\"\n" +
                "    ],\n" +
                "    \"supported_color_modes\": [\n" +
                "      \"color_temp\",\n" +
                "      \"xy\"\n" +
                "    ],\n" +
                "    \"color_mode\": \"xy\",\n" +
                "    \"brightness\": 127,\n" +
                "    \"hs_color\": [\n" +
                "      15.638,\n" +
                "      73.725\n" +
                "    ],\n" +
                "    \"rgb_color\": [\n" +
                "      255,\n" +
                "      116,\n" +
                "      67\n" +
                "    ],\n" +
                "    \"xy_color\": [\n" +
                "      0.6024,\n" +
                "      0.3433\n" +
                "    ],\n" +
                "    \"effect\": \"None\",\n" +
                "    \"mode\": \"normal\",\n" +
                "    \"dynamics\": \"none\",\n" +
                "    \"friendly_name\": \"Schreibtisch R\",\n" +
                "    \"supported_features\": 44\n" +
                "  },\n" +
                "  \"last_changed\": \"2023-09-21T17:59:28.462902+00:00\",\n" +
                "  \"last_updated\": \"2023-09-23T20:05:44.786943+00:00\",\n" +
                "  \"context\": {\n" +
                "    \"id\": \"123456789\",\n" +
                "    \"parent_id\": null,\n" +
                "    \"user_id\": null\n" +
                "  }\n" +
                "}");

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
        setGetResponse("/states/light.schreibtisch_r", "{\n" +
                "    \"entity_id\": \"light.schreibtisch_r\",\n" +
                "    \"state\": \"on\",\n" +
                "    \"attributes\": {\n" +
                "        \"min_color_temp_kelvin\": 2000,\n" +
                "        \"max_color_temp_kelvin\": 6535,\n" +
                "        \"min_mireds\": 153,\n" +
                "        \"max_mireds\": 500,\n" +
                "        \"effect_list\": [\n" +
                "            \"None\",\n" +
                "            \"candle\",\n" +
                "            \"fire\",\n" +
                "            \"unknown\"\n" +
                "        ],\n" +
                "        \"supported_color_modes\": [\n" +
                "            \"color_temp\",\n" +
                "            \"xy\"\n" +
                "        ],\n" +
                "        \"color_mode\": \"color_temp\",\n" +
                "        \"brightness\": 37,\n" +
                "        \"color_temp_kelvin\": 6211,\n" +
                "        \"color_temp\": 161,\n" +
                "        \"hs_color\": [\n" +
                "            33.877,\n" +
                "            4.876\n" +
                "        ],\n" +
                "        \"rgb_color\": [\n" +
                "            255,\n" +
                "            249,\n" +
                "            242\n" +
                "        ],\n" +
                "        \"xy_color\": [\n" +
                "            0.334,\n" +
                "            0.336\n" +
                "        ],\n" +
                "        \"effect\": \"None\",\n" +
                "        \"mode\": \"normal\",\n" +
                "        \"dynamics\": \"none\",\n" +
                "        \"friendly_name\": \"Schreibtisch R\",\n" +
                "        \"supported_features\": 44\n" +
                "    },\n" +
                "    \"last_changed\": \"2023-09-21T17:59:28.462902+00:00\",\n" +
                "    \"last_updated\": \"2023-09-24T07:29:18.919176+00:00\",\n" +
                "    \"context\": {\n" +
                "        \"id\": \"123456789\",\n" +
                "        \"parent_id\": null,\n" +
                "        \"user_id\": \"abc\"\n" +
                "    }\n" +
                "}");

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
        setGetResponse("/states/light.ct_only", "{\n" +
                "        \"entity_id\": \"light.ct_only\",\n" +
                "        \"state\": \"on\",\n" +
                "        \"attributes\": {\n" +
                "            \"min_color_temp_kelvin\": 2202,\n" +
                "            \"max_color_temp_kelvin\": 6535,\n" +
                "            \"min_mireds\": 153,\n" +
                "            \"max_mireds\": 454,\n" +
                "            \"effect_list\": [\n" +
                "                \"None\",\n" +
                "                \"candle\"\n" +
                "            ],\n" +
                "            \"supported_color_modes\": [\n" +
                "                \"color_temp\"\n" +
                "            ],\n" +
                "            \"color_mode\": \"color_temp\",\n" +
                "            \"brightness\": 234,\n" +
                "            \"color_temp_kelvin\": 2732,\n" +
                "            \"color_temp\": 366,\n" +
                "            \"hs_color\": [\n" +
                "                28.327,\n" +
                "                64.71\n" +
                "            ],\n" +
                "            \"rgb_color\": [\n" +
                "                255,\n" +
                "                167,\n" +
                "                89\n" +
                "            ],\n" +
                "            \"xy_color\": [\n" +
                "                0.524,\n" +
                "                0.387\n" +
                "            ],\n" +
                "            \"effect\": \"None\",\n" +
                "            \"mode\": \"normal\",\n" +
                "            \"dynamics\": \"none\",\n" +
                "            \"friendly_name\": \"CT Only\",\n" +
                "            \"supported_features\": 44\n" +
                "        },\n" +
                "        \"last_changed\": \"2023-09-24T08:02:02.025445+00:00\",\n" +
                "        \"last_updated\": \"2023-09-24T08:02:03.037742+00:00\",\n" +
                "        \"context\": {\n" +
                "            \"id\": \"12345689\",\n" +
                "            \"parent_id\": null,\n" +
                "            \"user_id\": \"93aa9186c4944cb182e08a61f7d201ca\"\n" +
                "        }\n" +
                "    }");

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
        setGetResponse("/states/light.xy_only", "{\n" +
                "        \"entity_id\": \"light.xy_only\",\n" +
                "        \"state\": \"on\",\n" +
                "        \"attributes\": {\n" +
                "            \"supported_color_modes\": [\n" +
                "                \"xy\"\n" +
                "            ],\n" +
                "            \"color_mode\": \"xy\",\n" +
                "            \"brightness\": 136,\n" +
                "            \"hs_color\": [\n" +
                "                13.366,\n" +
                "                79.216\n" +
                "            ],\n" +
                "            \"rgb_color\": [\n" +
                "                255,\n" +
                "                98,\n" +
                "                53\n" +
                "            ],\n" +
                "            \"xy_color\": [\n" +
                "                0.6311,\n" +
                "                0.3325\n" +
                "            ],\n" +
                "            \"mode\": \"normal\",\n" +
                "            \"dynamics\": \"none\",\n" +
                "            \"friendly_name\": \"XY Only\",\n" +
                "            \"supported_features\": 40\n" +
                "        },\n" +
                "        \"last_changed\": \"2023-09-23T15:32:38.271511+00:00\",\n" +
                "        \"last_updated\": \"2023-09-23T17:10:00.578272+00:00\",\n" +
                "        \"context\": {\n" +
                "            \"id\": \"123456789\",\n" +
                "            \"parent_id\": null,\n" +
                "            \"user_id\": null\n" +
                "        }\n" +
                "    },");

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
        setGetResponse("/states/light.ceiling", "{\n" +
                "    \"entity_id\": \"light.ceiling\",\n" +
                "    \"state\": \"unavailable\",\n" +
                "    \"attributes\": {\n" +
                "        \"min_color_temp_kelvin\": 2000,\n" +
                "        \"max_color_temp_kelvin\": 6535,\n" +
                "        \"min_mireds\": 153,\n" +
                "        \"max_mireds\": 500,\n" +
                "        \"effect_list\": [\n" +
                "            \"None\",\n" +
                "            \"candle\",\n" +
                "            \"fire\",\n" +
                "            \"unknown\"\n" +
                "        ],\n" +
                "        \"supported_color_modes\": [\n" +
                "            \"color_temp\",\n" +
                "            \"xy\"\n" +
                "        ],\n" +
                "        \"friendly_name\": \"Ceiling\",\n" +
                "        \"supported_features\": 44\n" +
                "    },\n" +
                "    \"last_changed\": \"2023-09-21T18:10:21.423610+00:00\",\n" +
                "    \"last_updated\": \"2023-09-21T18:10:21.423610+00:00\",\n" +
                "    \"context\": {\n" +
                "        \"id\": \"123456789\",\n" +
                "        \"parent_id\": null,\n" +
                "        \"user_id\": null\n" +
                "    }\n" +
                "}");

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
        setGetResponse("/states/light.off", "{\n" +
                "        \"entity_id\": \"light.off\",\n" +
                "        \"state\": \"off\",\n" +
                "        \"attributes\": {\n" +
                "            \"min_color_temp_kelvin\": 2202,\n" +
                "            \"max_color_temp_kelvin\": 6535,\n" +
                "            \"min_mireds\": 153,\n" +
                "            \"max_mireds\": 454,\n" +
                "            \"effect_list\": [\n" +
                "                \"None\",\n" +
                "                \"candle\"\n" +
                "            ],\n" +
                "            \"supported_color_modes\": [\n" +
                "                \"color_temp\"\n" +
                "            ],\n" +
                "            \"mode\": \"normal\",\n" +
                "            \"dynamics\": \"none\",\n" +
                "            \"friendly_name\": \"Off Light\",\n" +
                "            \"supported_features\": 44\n" +
                "        },\n" +
                "        \"last_changed\": \"2023-09-21T17:36:01.004020+00:00\",\n" +
                "        \"last_updated\": \"2023-09-21T17:36:01.004020+00:00\",\n" +
                "        \"context\": {\n" +
                "            \"id\": \"123456789\",\n" +
                "            \"parent_id\": null,\n" +
                "            \"user_id\": null\n" +
                "        }\n" +
                "    },");

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
        setGetResponse("/states/light.on_off", "    {\n" +
                "        \"entity_id\": \"light.on_off\",\n" +
                "        \"state\": \"on\",\n" +
                "        \"attributes\": {\n" +
                "            \"supported_color_modes\": [],\n" +
                "            \"color_mode\": \"onoff\",\n" +
                "            \"mode\": \"normal\",\n" +
                "            \"dynamics\": \"none\",\n" +
                "            \"friendly_name\": \"On Off\",\n" +
                "            \"supported_features\": 0\n" +
                "        },\n" +
                "        \"last_changed\": \"2023-09-24T07:55:01.698292+00:00\",\n" +
                "        \"last_updated\": \"2023-09-24T07:55:01.698292+00:00\",\n" +
                "        \"context\": {\n" +
                "            \"id\": \"123456789\",\n" +
                "            \"parent_id\": null,\n" +
                "            \"user_id\": null\n" +
                "        }\n" +
                "    }");

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
        setGetResponse("/states/switch.switch_demo", "    {\n" +
                "        \"entity_id\": \"switch.switch_demo\",\n" +
                "        \"state\": \"on\",\n" +
                "        \"attributes\": {\n" +
                "            \"configuration\": {},\n" +
                "            \"icon\": \"mdi:theme-light-dark\",\n" +
                "            \"friendly_name\": \"Switch Demo\"\n" +
                "        },\n" +
                "        \"last_changed\": \"2023-09-24T10:36:25.264439+00:00\",\n" +
                "        \"last_updated\": \"2023-09-24T10:36:25.264439+00:00\",\n" +
                "        \"context\": {\n" +
                "            \"id\": \"123456789\",\n" +
                "            \"parent_id\": null,\n" +
                "            \"user_id\": null\n" +
                "        }\n" +
                "    }");

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
        setGetResponse("/states/input_boolean.test_toggle", "    {\n" +
                "        \"entity_id\": \"input_boolean.test_toggle\",\n" +
                "        \"state\": \"on\",\n" +
                "        \"attributes\": {\n" +
                "            \"editable\": true,\n" +
                "            \"icon\": \"mdi:alarm-panel\",\n" +
                "            \"friendly_name\": \"Test Toggle\"\n" +
                "        },\n" +
                "        \"last_changed\": \"2023-09-24T14:06:32.744839+00:00\",\n" +
                "        \"last_updated\": \"2023-09-24T14:06:32.744839+00:00\",\n" +
                "        \"context\": {\n" +
                "            \"id\": \"01HB3R6T9820DA0PE9VCFJRE14\",\n" +
                "            \"parent_id\": null,\n" +
                "            \"user_id\": null\n" +
                "        }\n" +
                "    },");

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
        setGetResponse("/states/light.on_off", "{\n" +
                "        \"entity_id\": \"light.on_off\",\n" +
                "        \"state\": \"unavailable\",\n" +
                "        \"attributes\": {\n" +
                "            \"supported_color_modes\": [],\n" +
                "            \"friendly_name\": \"On Off\",\n" +
                "            \"supported_features\": 0\n" +
                "        },\n" +
                "        \"last_changed\": \"2023-09-21T17:36:01.003533+00:00\",\n" +
                "        \"last_updated\": \"2023-09-21T17:36:01.003533+00:00\",\n" +
                "        \"context\": {\n" +
                "            \"id\": \"123456789\",\n" +
                "            \"parent_id\": null,\n" +
                "            \"user_id\": null\n" +
                "        }\n" +
                "    }");

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
        setGetResponse("/states/light.brightness", "{\n" +
                "        \"entity_id\": \"light.brightness\",\n" +
                "        \"state\": \"on\",\n" +
                "        \"attributes\": {\n" +
                "            \"effect_list\": [\n" +
                "                \"None\",\n" +
                "                \"candle\"\n" +
                "            ],\n" +
                "            \"supported_color_modes\": [\n" +
                "                \"brightness\"\n" +
                "            ],\n" +
                "            \"color_mode\": \"brightness\",\n" +
                "            \"brightness\": 255,\n" +
                "            \"effect\": \"None\",\n" +
                "            \"mode\": \"normal\",\n" +
                "            \"dynamics\": \"none\",\n" +
                "            \"friendly_name\": \"Brightness only lamp\",\n" +
                "            \"supported_features\": 44\n" +
                "        },\n" +
                "        \"last_changed\": \"2023-09-24T08:09:32.431546+00:00\",\n" +
                "        \"last_updated\": \"2023-09-24T08:09:32.431546+00:00\",\n" +
                "        \"context\": {\n" +
                "            \"id\": \"123456789\",\n" +
                "            \"parent_id\": null,\n" +
                "            \"user_id\": null\n" +
                "        }\n" +
                "    },");

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
        setGetResponse("/states/light.brightness", "{\n" +
                "        \"entity_id\": \"light.brightness\",\n" +
                "        \"state\": \"on\",\n" +
                "        \"attributes\": {\n" +
                "            \"effect_list\": [\n" +
                "                \"None\",\n" +
                "                \"candle\"\n" +
                "            ],\n" +
                "            \"supported_color_modes\": [\n" +
                "                \"brightness\"\n" +
                "            ],\n" +
                "            \"color_mode\": \"brightness\",\n" +
                "            \"brightness\": 0,\n" +
                "            \"effect\": \"None\",\n" +
                "            \"mode\": \"normal\",\n" +
                "            \"dynamics\": \"none\",\n" +
                "            \"friendly_name\": \"Brightness only lamp\",\n" +
                "            \"supported_features\": 44\n" +
                "        },\n" +
                "        \"last_changed\": \"2023-09-24T08:09:32.431546+00:00\",\n" +
                "        \"last_updated\": \"2023-09-24T08:09:32.431546+00:00\",\n" +
                "        \"context\": {\n" +
                "            \"id\": \"123456789\",\n" +
                "            \"parent_id\": null,\n" +
                "            \"user_id\": null\n" +
                "        }\n" +
                "    },");

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
        setGetResponse("/states/light.brightness", "{\n" +
                "        \"entity_id\": \"light.brightness\",\n" +
                "        \"state\": \"on\",\n" +
                "        \"attributes\": {\n" +
                "            \"effect_list\": [\n" +
                "                \"None\",\n" +
                "                \"candle\"\n" +
                "            ],\n" +
                "            \"supported_color_modes\": [\n" +
                "                \"brightness\"\n" +
                "            ],\n" +
                "            \"color_mode\": \"brightness\",\n" +
                "            \"brightness\": 200,\n" +
                "            \"effect\": \"None\",\n" +
                "            \"mode\": \"normal\",\n" +
                "            \"dynamics\": \"none\",\n" +
                "            \"friendly_name\": \"Brightness only lamp\",\n" +
                "            \"supported_features\": 44\n" +
                "        },\n" +
                "        \"last_changed\": \"2023-09-24T08:09:32.431546+00:00\",\n" +
                "        \"last_updated\": \"2023-09-24T08:09:32.431546+00:00\",\n" +
                "        \"context\": {\n" +
                "            \"id\": \"123456789\",\n" +
                "            \"parent_id\": null,\n" +
                "            \"user_id\": null\n" +
                "        }\n" +
                "    },");

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
        setGetResponse("/states", "[\n" +
                "  {\n" +
                "    \"entity_id\": \"light.couch_group\",\n" +
                "    \"state\": \"on\",\n" +
                "    \"attributes\": {\n" +
                "      \"supported_color_modes\": [\n" +
                "        \"xy\"\n" +
                "      ],\n" +
                "      \"color_mode\": \"xy\",\n" +
                "      \"brightness\": 255,\n" +
                "      \"hs_color\": [\n" +
                "        12.464,\n" +
                "        81.176\n" +
                "      ],\n" +
                "      \"rgb_color\": [\n" +
                "        255,\n" +
                "        91,\n" +
                "        48\n" +
                "      ],\n" +
                "      \"xy_color\": [\n" +
                "        0.6408,\n" +
                "        0.3284\n" +
                "      ],\n" +
                "      \"is_hue_group\": true,\n" +
                "      \"hue_scenes\": [\n" +
                "        \"Gedimmt\",\n" +
                "        \"Nachtlicht\",\n" +
                "        \"Hell\",\n" +
                "        \"Frühlingsblüten\",\n" +
                "        \"Sonnenuntergang Savanne\",\n" +
                "        \"Tropendämmerung\",\n" +
                "        \"Nordlichter\"\n" +
                "      ],\n" +
                "      \"hue_type\": \"zone\",\n" +
                "      \"lights\": [\n" +
                "        \"Couch\",\n" +
                "        \"Couch unten\"\n" +
                "      ],\n" +
                "      \"dynamics\": false,\n" +
                "      \"icon\": \"mdi:lightbulb-group\",\n" +
                "      \"friendly_name\": \"Couch\",\n" +
                "      \"supported_features\": 40\n" +
                "    },\n" +
                "    \"last_changed\": \"2023-09-24T14:06:34.783862+00:00\",\n" +
                "    \"last_updated\": \"2023-09-24T14:06:34.783862+00:00\",\n" +
                "    \"context\": {\n" +
                "      \"id\": \"123456789\",\n" +
                "      \"parent_id\": null,\n" +
                "      \"user_id\": null\n" +
                "    }\n" +
                "  },\n" +
                "  {\n" +
                "    \"entity_id\": \"light.couch_unten\",\n" +
                "    \"state\": \"on\",\n" +
                "    \"attributes\": {\n" +
                "      \"supported_color_modes\": [\n" +
                "        \"xy\"\n" +
                "      ],\n" +
                "      \"color_mode\": \"xy\",\n" +
                "      \"brightness\": 255,\n" +
                "      \"hs_color\": [\n" +
                "        12.464,\n" +
                "        81.176\n" +
                "      ],\n" +
                "      \"rgb_color\": [\n" +
                "        255,\n" +
                "        91,\n" +
                "        48\n" +
                "      ],\n" +
                "      \"xy_color\": [\n" +
                "        0.6408,\n" +
                "        0.3284\n" +
                "      ],\n" +
                "      \"mode\": \"normal\",\n" +
                "      \"dynamics\": \"none\",\n" +
                "      \"friendly_name\": \"Couch unten\",\n" +
                "      \"supported_features\": 40\n" +
                "    },\n" +
                "    \"last_changed\": \"2023-09-24T14:06:34.772408+00:00\",\n" +
                "    \"last_updated\": \"2023-09-24T14:06:34.772408+00:00\",\n" +
                "    \"context\": {\n" +
                "      \"id\": \"123456789\",\n" +
                "      \"parent_id\": null,\n" +
                "      \"user_id\": null\n" +
                "    }\n" +
                "  },\n" +
                "  {\n" +
                "    \"entity_id\": \"light.couch\",\n" +
                "    \"state\": \"on\",\n" +
                "    \"attributes\": {\n" +
                "      \"supported_color_modes\": [\n" +
                "        \"xy\"\n" +
                "      ],\n" +
                "      \"color_mode\": \"xy\",\n" +
                "      \"brightness\": 255,\n" +
                "      \"hs_color\": [\n" +
                "        12.464,\n" +
                "        81.176\n" +
                "      ],\n" +
                "      \"rgb_color\": [\n" +
                "        255,\n" +
                "        91,\n" +
                "        48\n" +
                "      ],\n" +
                "      \"xy_color\": [\n" +
                "        0.6408,\n" +
                "        0.3284\n" +
                "      ],\n" +
                "      \"mode\": \"normal\",\n" +
                "      \"dynamics\": \"none\",\n" +
                "      \"friendly_name\": \"Couch\",\n" +
                "      \"supported_features\": 40\n" +
                "    },\n" +
                "    \"last_changed\": \"2023-09-24T14:06:34.774658+00:00\",\n" +
                "    \"last_updated\": \"2023-09-24T14:06:34.774658+00:00\",\n" +
                "    \"context\": {\n" +
                "      \"id\": \"123456789\",\n" +
                "      \"parent_id\": null,\n" +
                "      \"user_id\": null\n" +
                "    }\n" +
                "  }\n" +
                "]\n");

        assertThat(api.getGroupLights("light.couch_group")).containsExactlyInAnyOrder("light.couch",
                "light.couch_unten"
        );
    }

    @Test
    void getGroupLights_hueGroup_containsUnknownLight_exception() {
        setGetResponse("/states", "[\n" +
                "  {\n" +
                "    \"entity_id\": \"light.couch_group\",\n" +
                "    \"state\": \"on\",\n" +
                "    \"attributes\": {\n" +
                "      \"supported_color_modes\": [\n" +
                "        \"xy\"\n" +
                "      ],\n" +
                "      \"color_mode\": \"xy\",\n" +
                "      \"brightness\": 255,\n" +
                "      \"hs_color\": [\n" +
                "        12.464,\n" +
                "        81.176\n" +
                "      ],\n" +
                "      \"rgb_color\": [\n" +
                "        255,\n" +
                "        91,\n" +
                "        48\n" +
                "      ],\n" +
                "      \"xy_color\": [\n" +
                "        0.6408,\n" +
                "        0.3284\n" +
                "      ],\n" +
                "      \"is_hue_group\": true,\n" +
                "      \"hue_scenes\": [\n" +
                "        \"Gedimmt\",\n" +
                "        \"Nachtlicht\",\n" +
                "        \"Hell\",\n" +
                "        \"Frühlingsblüten\",\n" +
                "        \"Sonnenuntergang Savanne\",\n" +
                "        \"Tropendämmerung\",\n" +
                "        \"Nordlichter\"\n" +
                "      ],\n" +
                "      \"hue_type\": \"zone\",\n" +
                "      \"lights\": [\n" +
                "        \"Couch\",\n" +
                "        \"Couch unten\"\n" +
                "      ],\n" +
                "      \"dynamics\": false,\n" +
                "      \"icon\": \"mdi:lightbulb-group\",\n" +
                "      \"friendly_name\": \"Couch\",\n" +
                "      \"supported_features\": 40\n" +
                "    },\n" +
                "    \"last_changed\": \"2023-09-24T14:06:34.783862+00:00\",\n" +
                "    \"last_updated\": \"2023-09-24T14:06:34.783862+00:00\",\n" +
                "    \"context\": {\n" +
                "      \"id\": \"123456789\",\n" +
                "      \"parent_id\": null,\n" +
                "      \"user_id\": null\n" +
                "    }\n" +
                "  }\n" +
                "]\n");

        assertThatThrownBy(() -> api.getGroupLights("light.couch_group")).isInstanceOf(LightNotFoundException.class);
    }

    @Test
    void getGroupLights_hueGroup_ignoresHassGroup_exception() {
        setGetResponse("/states", "[\n" +
                "  {\n" +
                "    \"entity_id\": \"light.couch_group\",\n" +
                "    \"state\": \"on\",\n" +
                "    \"attributes\": {\n" +
                "      \"supported_color_modes\": [\n" +
                "        \"xy\"\n" +
                "      ],\n" +
                "      \"color_mode\": \"xy\",\n" +
                "      \"brightness\": 255,\n" +
                "      \"hs_color\": [\n" +
                "        12.464,\n" +
                "        81.176\n" +
                "      ],\n" +
                "      \"rgb_color\": [\n" +
                "        255,\n" +
                "        91,\n" +
                "        48\n" +
                "      ],\n" +
                "      \"xy_color\": [\n" +
                "        0.6408,\n" +
                "        0.3284\n" +
                "      ],\n" +
                "      \"is_hue_group\": true,\n" +
                "      \"hue_scenes\": [\n" +
                "        \"Gedimmt\",\n" +
                "        \"Nachtlicht\",\n" +
                "        \"Hell\",\n" +
                "        \"Frühlingsblüten\",\n" +
                "        \"Sonnenuntergang Savanne\",\n" +
                "        \"Tropendämmerung\",\n" +
                "        \"Nordlichter\"\n" +
                "      ],\n" +
                "      \"hue_type\": \"zone\",\n" +
                "      \"lights\": [\n" +
                "        \"Couch\"\n" +
                "      ],\n" +
                "      \"dynamics\": false,\n" +
                "      \"icon\": \"mdi:lightbulb-group\",\n" +
                "      \"friendly_name\": \"Couch\",\n" +
                "      \"supported_features\": 40\n" +
                "    },\n" +
                "    \"last_changed\": \"2023-09-24T14:06:34.783862+00:00\",\n" +
                "    \"last_updated\": \"2023-09-24T14:06:34.783862+00:00\",\n" +
                "    \"context\": {\n" +
                "      \"id\": \"123456789\",\n" +
                "      \"parent_id\": null,\n" +
                "      \"user_id\": null\n" +
                "    }\n" +
                "  },\n" +
                "  {\n" +
                "    \"entity_id\": \"light.couch_group2\",\n" +
                "    \"state\": \"on\",\n" +
                "    \"attributes\": {\n" +
                "      \"min_color_temp_kelvin\": 2000,\n" +
                "      \"max_color_temp_kelvin\": 6535,\n" +
                "      \"min_mireds\": 153,\n" +
                "      \"max_mireds\": 500,\n" +
                "      \"effect_list\": [\n" +
                "        \"None\",\n" +
                "        \"candle\",\n" +
                "        \"fire\",\n" +
                "        \"unknown\"\n" +
                "      ],\n" +
                "      \"supported_color_modes\": [\n" +
                "        \"brightness\",\n" +
                "        \"color_temp\",\n" +
                "        \"xy\"\n" +
                "      ],\n" +
                "      \"color_mode\": \"brightness\",\n" +
                "      \"brightness\": 255,\n" +
                "      \"effect\": \"None\",\n" +
                "      \"entity_id\": [\n" +
                "        \"light.couch\"\n" +
                "      ],\n" +
                "      \"icon\": \"mdi:lightbulb-group\",\n" +
                "      \"friendly_name\": \"Couch\",\n" +
                "      \"supported_features\": 44\n" +
                "    },\n" +
                "    \"last_changed\": \"2023-09-24T14:06:35.260070+00:00\",\n" +
                "    \"last_updated\": \"2023-09-24T14:20:00.208887+00:00\",\n" +
                "    \"context\": {\n" +
                "      \"id\": \"123456789\",\n" +
                "      \"parent_id\": null,\n" +
                "      \"user_id\": null\n" +
                "    }\n" +
                "  }\n" +
                "]\n");

        assertThatThrownBy(() -> api.getGroupLights("light.couch_group")).isInstanceOf(LightNotFoundException.class);
    }

    @Test
    void getGroupLights_hassGroup_returnsContainedLightIds() {
        setGetResponse("/states", "[\n" +
                "  {\n" +
                "    \"entity_id\": \"light.test_group\",\n" +
                "    \"state\": \"on\",\n" +
                "    \"attributes\": {\n" +
                "      \"min_color_temp_kelvin\": 2000,\n" +
                "      \"max_color_temp_kelvin\": 6535,\n" +
                "      \"min_mireds\": 153,\n" +
                "      \"max_mireds\": 500,\n" +
                "      \"effect_list\": [\n" +
                "        \"None\",\n" +
                "        \"candle\",\n" +
                "        \"fire\",\n" +
                "        \"unknown\"\n" +
                "      ],\n" +
                "      \"supported_color_modes\": [\n" +
                "        \"brightness\",\n" +
                "        \"color_temp\",\n" +
                "        \"xy\"\n" +
                "      ],\n" +
                "      \"color_mode\": \"brightness\",\n" +
                "      \"brightness\": 255,\n" +
                "      \"effect\": \"None\",\n" +
                "      \"entity_id\": [\n" +
                "        \"light.schreibtisch_r\",\n" +
                "        \"light.schreibtisch_l\"\n" +
                "      ],\n" +
                "      \"icon\": \"mdi:lightbulb-group\",\n" +
                "      \"friendly_name\": \"Test Group\",\n" +
                "      \"supported_features\": 44\n" +
                "    },\n" +
                "    \"last_changed\": \"2023-09-24T14:06:35.260070+00:00\",\n" +
                "    \"last_updated\": \"2023-09-24T14:20:00.208887+00:00\",\n" +
                "    \"context\": {\n" +
                "      \"id\": \"123456789\",\n" +
                "      \"parent_id\": null,\n" +
                "      \"user_id\": null\n" +
                "    }\n" +
                "  }\n" +
                "]\n");

        assertThat(api.getGroupLights("light.test_group")).containsExactlyInAnyOrder("light.schreibtisch_r",
                "light.schreibtisch_l"
        );
    }

    @Test
    void getGroupLights_notAGroup_exception() {
        setGetResponse("/states", "[\n" +
                "  {\n" +
                "    \"entity_id\": \"light.on_off\",\n" +
                "    \"state\": \"on\",\n" +
                "    \"attributes\": {\n" +
                "      \"supported_color_modes\": [],\n" +
                "      \"color_mode\": \"onoff\",\n" +
                "      \"mode\": \"normal\",\n" +
                "      \"dynamics\": \"none\",\n" +
                "      \"friendly_name\": \"On Off\",\n" +
                "      \"supported_features\": 0\n" +
                "    },\n" +
                "    \"last_changed\": \"2023-09-24T07:55:01.698292+00:00\",\n" +
                "    \"last_updated\": \"2023-09-24T07:55:01.698292+00:00\",\n" +
                "    \"context\": {\n" +
                "      \"id\": \"123456789\",\n" +
                "      \"parent_id\": null,\n" +
                "      \"user_id\": null\n" +
                "    }\n" +
                "  }\n" +
                "]\n");

        assertThatThrownBy(() -> api.getGroupLights("light.on_off")).isInstanceOf(GroupNotFoundException.class);
    }

    @Test
    void getGroupLights_emptyGroup_exception() {
        setGetResponse("/states", "[\n" +
                "  {\n" +
                "    \"entity_id\": \"light.couch_group\",\n" +
                "    \"state\": \"on\",\n" +
                "    \"attributes\": {\n" +
                "      \"supported_color_modes\": [\n" +
                "        \"xy\"\n" +
                "      ],\n" +
                "      \"color_mode\": \"xy\",\n" +
                "      \"brightness\": 255,\n" +
                "      \"hs_color\": [\n" +
                "        12.464,\n" +
                "        81.176\n" +
                "      ],\n" +
                "      \"rgb_color\": [\n" +
                "        255,\n" +
                "        91,\n" +
                "        48\n" +
                "      ],\n" +
                "      \"xy_color\": [\n" +
                "        0.6408,\n" +
                "        0.3284\n" +
                "      ],\n" +
                "      \"is_hue_group\": true,\n" +
                "      \"hue_scenes\": [\n" +
                "        \"Gedimmt\",\n" +
                "        \"Nachtlicht\",\n" +
                "        \"Hell\",\n" +
                "        \"Frühlingsblüten\",\n" +
                "        \"Sonnenuntergang Savanne\",\n" +
                "        \"Tropendämmerung\",\n" +
                "        \"Nordlichter\"\n" +
                "      ],\n" +
                "      \"hue_type\": \"zone\",\n" +
                "      \"lights\": [\n" +
                "      ],\n" +
                "      \"dynamics\": false,\n" +
                "      \"icon\": \"mdi:lightbulb-group\",\n" +
                "      \"friendly_name\": \"Couch\",\n" +
                "      \"supported_features\": 40\n" +
                "    },\n" +
                "    \"last_changed\": \"2023-09-24T14:06:34.783862+00:00\",\n" +
                "    \"last_updated\": \"2023-09-24T14:06:34.783862+00:00\",\n" +
                "    \"context\": {\n" +
                "      \"id\": \"123456789\",\n" +
                "      \"parent_id\": null,\n" +
                "      \"user_id\": null\n" +
                "    }\n" +
                "  },\n" +
                "  {\n" +
                "    \"entity_id\": \"light.couch_group2\",\n" +
                "    \"state\": \"on\",\n" +
                "    \"attributes\": {\n" +
                "      \"min_color_temp_kelvin\": 2000,\n" +
                "      \"max_color_temp_kelvin\": 6535,\n" +
                "      \"min_mireds\": 153,\n" +
                "      \"max_mireds\": 500,\n" +
                "      \"effect_list\": [\n" +
                "        \"None\",\n" +
                "        \"candle\",\n" +
                "        \"fire\",\n" +
                "        \"unknown\"\n" +
                "      ],\n" +
                "      \"supported_color_modes\": [\n" +
                "        \"brightness\",\n" +
                "        \"color_temp\",\n" +
                "        \"xy\"\n" +
                "      ],\n" +
                "      \"color_mode\": \"brightness\",\n" +
                "      \"brightness\": 255,\n" +
                "      \"effect\": \"None\",\n" +
                "      \"entity_id\": [\n" +
                "      ],\n" +
                "      \"icon\": \"mdi:lightbulb-group\",\n" +
                "      \"friendly_name\": \"Couch\",\n" +
                "      \"supported_features\": 44\n" +
                "    },\n" +
                "    \"last_changed\": \"2023-09-24T14:06:35.260070+00:00\",\n" +
                "    \"last_updated\": \"2023-09-24T14:20:00.208887+00:00\",\n" +
                "    \"context\": {\n" +
                "      \"id\": \"123456789\",\n" +
                "      \"parent_id\": null,\n" +
                "      \"user_id\": null\n" +
                "    }\n" +
                "  }\n" +
                "]\n");

        assertThatThrownBy(() -> api.getGroupLights("light.couch_group")).isInstanceOf(EmptyGroupException.class);
        assertThatThrownBy(() -> api.getGroupLights("light.couch_group2")).isInstanceOf(EmptyGroupException.class);
    }

    @Test
    void getGroupCapabilities_directlyReturnsCapabilitiesOfState() {
        setGetResponse("/states", "[\n" +
                "  {\n" +
                "    \"entity_id\": \"light.couch_group\",\n" +
                "    \"state\": \"on\",\n" +
                "    \"attributes\": {\n" +
                "      \"supported_color_modes\": [\n" +
                "        \"xy\"\n" +
                "      ],\n" +
                "      \"color_mode\": \"xy\",\n" +
                "      \"brightness\": 255,\n" +
                "      \"hs_color\": [\n" +
                "        12.464,\n" +
                "        81.176\n" +
                "      ],\n" +
                "      \"rgb_color\": [\n" +
                "        255,\n" +
                "        91,\n" +
                "        48\n" +
                "      ],\n" +
                "      \"xy_color\": [\n" +
                "        0.6408,\n" +
                "        0.3284\n" +
                "      ],\n" +
                "      \"is_hue_group\": true,\n" +
                "      \"hue_scenes\": [\n" +
                "        \"Gedimmt\",\n" +
                "        \"Nachtlicht\",\n" +
                "        \"Hell\",\n" +
                "        \"Frühlingsblüten\",\n" +
                "        \"Sonnenuntergang Savanne\",\n" +
                "        \"Tropendämmerung\",\n" +
                "        \"Nordlichter\"\n" +
                "      ],\n" +
                "      \"hue_type\": \"zone\",\n" +
                "      \"lights\": [\n" +
                "      ],\n" +
                "      \"dynamics\": false,\n" +
                "      \"icon\": \"mdi:lightbulb-group\",\n" +
                "      \"friendly_name\": \"Couch\",\n" +
                "      \"supported_features\": 40\n" +
                "    },\n" +
                "    \"last_changed\": \"2023-09-24T14:06:34.783862+00:00\",\n" +
                "    \"last_updated\": \"2023-09-24T14:06:34.783862+00:00\",\n" +
                "    \"context\": {\n" +
                "      \"id\": \"123456789\",\n" +
                "      \"parent_id\": null,\n" +
                "      \"user_id\": null\n" +
                "    }\n" +
                "  }\n" +
                "]");

        assertThat(api.getGroupCapabilities("light.couch_group")).isEqualTo(LightCapabilities.builder()
                                                                                             .capabilities(EnumSet.of(Capability.COLOR,
                                                                                                     Capability.BRIGHTNESS, Capability.ON_OFF))
                                                                                             .build());

    }

    @Test
    void getGroupStates_returnsStatesForContainedLights_doesNotUseCache() {
        setGetResponse("/states", "[\n" +
                "  {\n" +
                "    \"entity_id\": \"light.test_group\",\n" +
                "    \"state\": \"on\",\n" +
                "    \"attributes\": {\n" +
                "      \"min_color_temp_kelvin\": 2000,\n" +
                "      \"max_color_temp_kelvin\": 6535,\n" +
                "      \"min_mireds\": 153,\n" +
                "      \"max_mireds\": 500,\n" +
                "      \"effect_list\": [\n" +
                "        \"None\",\n" +
                "        \"candle\",\n" +
                "        \"fire\",\n" +
                "        \"unknown\"\n" +
                "      ],\n" +
                "      \"supported_color_modes\": [\n" +
                "        \"color_temp\",\n" +
                "        \"xy\"\n" +
                "      ],\n" +
                "      \"color_mode\": \"color_temp\",\n" +
                "      \"brightness\": 244,\n" +
                "      \"color_temp_kelvin\": 2971,\n" +
                "      \"color_temp\": 336,\n" +
                "      \"hs_color\": [\n" +
                "        27.874,\n" +
                "        57.689\n" +
                "      ],\n" +
                "      \"rgb_color\": [\n" +
                "        255,\n" +
                "        176,\n" +
                "        107\n" +
                "      ],\n" +
                "      \"xy_color\": [\n" +
                "        0.499,\n" +
                "        0.384\n" +
                "      ],\n" +
                "      \"effect\": \"None\",\n" +
                "      \"entity_id\": [\n" +
                "        \"light.schreibtisch_l\",\n" +
                "        \"light.schreibtisch_r\",\n" +
                "        \"light.ignored\"\n" +
                "      ],\n" +
                "      \"icon\": \"mdi:lightbulb-group\",\n" +
                "      \"friendly_name\": \"Test Group\",\n" +
                "      \"supported_features\": 44\n" +
                "    },\n" +
                "    \"last_changed\": \"2023-09-24T16:13:59.022491+00:00\",\n" +
                "    \"last_updated\": \"2023-09-24T16:13:59.194048+00:00\",\n" +
                "    \"context\": {\n" +
                "      \"id\": \"123456789\",\n" +
                "      \"parent_id\": null,\n" +
                "      \"user_id\": null\n" +
                "    }\n" +
                "  },\n" +
                "  {\n" +
                "    \"entity_id\": \"light.schreibtisch_l\",\n" +
                "    \"state\": \"on\",\n" +
                "    \"attributes\": {\n" +
                "      \"min_color_temp_kelvin\": 2000,\n" +
                "      \"max_color_temp_kelvin\": 6535,\n" +
                "      \"min_mireds\": 153,\n" +
                "      \"max_mireds\": 500,\n" +
                "      \"effect_list\": [\n" +
                "        \"None\",\n" +
                "        \"candle\",\n" +
                "        \"fire\",\n" +
                "        \"unknown\"\n" +
                "      ],\n" +
                "      \"supported_color_modes\": [\n" +
                "        \"color_temp\",\n" +
                "        \"xy\"\n" +
                "      ],\n" +
                "      \"color_mode\": \"color_temp\",\n" +
                "      \"brightness\": 127,\n" +
                "      \"color_temp_kelvin\": 2994,\n" +
                "      \"color_temp\": 334,\n" +
                "      \"hs_color\": [\n" +
                "        27.835,\n" +
                "        57.058\n" +
                "      ],\n" +
                "      \"rgb_color\": [\n" +
                "        255,\n" +
                "        177,\n" +
                "        109\n" +
                "      ],\n" +
                "      \"xy_color\": [\n" +
                "        0.497,\n" +
                "        0.384\n" +
                "      ],\n" +
                "      \"effect\": \"None\",\n" +
                "      \"mode\": \"normal\",\n" +
                "      \"dynamics\": \"none\",\n" +
                "      \"friendly_name\": \"Schreibtisch L\",\n" +
                "      \"supported_features\": 44\n" +
                "    },\n" +
                "    \"last_changed\": \"2023-09-24T14:50:55.062582+00:00\",\n" +
                "    \"last_updated\": \"2023-09-24T16:10:21.374478+00:00\",\n" +
                "    \"context\": {\n" +
                "      \"id\": \"123456789\",\n" +
                "      \"parent_id\": null,\n" +
                "      \"user_id\": null\n" +
                "    }\n" +
                "  },\n" +
                "  {\n" +
                "    \"entity_id\": \"light.schreibtisch_r\",\n" +
                "    \"state\": \"on\",\n" +
                "    \"attributes\": {\n" +
                "      \"min_color_temp_kelvin\": 2000,\n" +
                "      \"max_color_temp_kelvin\": 6535,\n" +
                "      \"min_mireds\": 153,\n" +
                "      \"max_mireds\": 500,\n" +
                "      \"effect_list\": [\n" +
                "        \"None\",\n" +
                "        \"candle\",\n" +
                "        \"fire\",\n" +
                "        \"unknown\"\n" +
                "      ],\n" +
                "      \"supported_color_modes\": [\n" +
                "        \"color_temp\",\n" +
                "        \"xy\"\n" +
                "      ],\n" +
                "      \"color_mode\": \"color_temp\",\n" +
                "      \"brightness\": 246,\n" +
                "      \"color_temp_kelvin\": 2976,\n" +
                "      \"color_temp\": 336,\n" +
                "      \"hs_color\": [\n" +
                "        27.865,\n" +
                "        57.551\n" +
                "      ],\n" +
                "      \"rgb_color\": [\n" +
                "        255,\n" +
                "        176,\n" +
                "        108\n" +
                "      ],\n" +
                "      \"xy_color\": [\n" +
                "        0.498,\n" +
                "        0.383\n" +
                "      ],\n" +
                "      \"effect\": \"None\",\n" +
                "      \"mode\": \"normal\",\n" +
                "      \"dynamics\": \"none\",\n" +
                "      \"friendly_name\": \"Schreibtisch R\",\n" +
                "      \"supported_features\": 44\n" +
                "    },\n" +
                "    \"last_changed\": \"2023-09-24T16:04:27.160634+00:00\",\n" +
                "    \"last_updated\": \"2023-09-24T16:12:22.081189+00:00\",\n" +
                "    \"context\": {\n" +
                "      \"id\": \"123456789\",\n" +
                "      \"parent_id\": null,\n" +
                "      \"user_id\": null\n" +
                "    }\n" +
                "  }\n" +
                "]\n");

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
        setGetResponse("/states", "[\n" +
                "  {\n" +
                "    \"entity_id\": \"light.couch_group\",\n" +
                "    \"state\": \"on\",\n" +
                "    \"attributes\": {\n" +
                "      \"supported_color_modes\": [\n" +
                "        \"xy\"\n" +
                "      ],\n" +
                "      \"color_mode\": \"xy\",\n" +
                "      \"brightness\": 255,\n" +
                "      \"hs_color\": [\n" +
                "        12.464,\n" +
                "        81.176\n" +
                "      ],\n" +
                "      \"rgb_color\": [\n" +
                "        255,\n" +
                "        91,\n" +
                "        48\n" +
                "      ],\n" +
                "      \"xy_color\": [\n" +
                "        0.6408,\n" +
                "        0.3284\n" +
                "      ],\n" +
                "      \"is_hue_group\": true,\n" +
                "      \"hue_scenes\": [\n" +
                "        \"Gedimmt\",\n" +
                "        \"Nachtlicht\",\n" +
                "        \"Hell\",\n" +
                "        \"Frühlingsblüten\",\n" +
                "        \"Sonnenuntergang Savanne\",\n" +
                "        \"Tropendämmerung\",\n" +
                "        \"Nordlichter\"\n" +
                "      ],\n" +
                "      \"hue_type\": \"zone\",\n" +
                "      \"lights\": [\n" +
                "        \"Couch Light\"\n" +
                "      ],\n" +
                "      \"dynamics\": false,\n" +
                "      \"icon\": \"mdi:lightbulb-group\",\n" +
                "      \"friendly_name\": \"Couch\",\n" +
                "      \"supported_features\": 40\n" +
                "    },\n" +
                "    \"last_changed\": \"2023-09-24T14:06:34.783862+00:00\",\n" +
                "    \"last_updated\": \"2023-09-24T14:06:34.783862+00:00\",\n" +
                "    \"context\": {\n" +
                "      \"id\": \"123456789\",\n" +
                "      \"parent_id\": null,\n" +
                "      \"user_id\": null\n" +
                "    }\n" +
                "  },\n" +
                "  {\n" +
                "    \"entity_id\": \"light.couch_group2\",\n" +
                "    \"state\": \"on\",\n" +
                "    \"attributes\": {\n" +
                "      \"min_color_temp_kelvin\": 2000,\n" +
                "      \"max_color_temp_kelvin\": 6535,\n" +
                "      \"min_mireds\": 153,\n" +
                "      \"max_mireds\": 500,\n" +
                "      \"effect_list\": [\n" +
                "        \"None\",\n" +
                "        \"candle\",\n" +
                "        \"fire\",\n" +
                "        \"unknown\"\n" +
                "      ],\n" +
                "      \"supported_color_modes\": [\n" +
                "        \"brightness\",\n" +
                "        \"color_temp\",\n" +
                "        \"xy\"\n" +
                "      ],\n" +
                "      \"color_mode\": \"brightness\",\n" +
                "      \"brightness\": 255,\n" +
                "      \"effect\": \"None\",\n" +
                "      \"entity_id\": [\n" +
                "        \"light.couch_light\"\n" +
                "      ],\n" +
                "      \"icon\": \"mdi:lightbulb-group\",\n" +
                "      \"friendly_name\": \"Couch\",\n" +
                "      \"supported_features\": 44\n" +
                "    },\n" +
                "    \"last_changed\": \"2023-09-24T14:06:35.260070+00:00\",\n" +
                "    \"last_updated\": \"2023-09-24T14:20:00.208887+00:00\",\n" +
                "    \"context\": {\n" +
                "      \"id\": \"123456789\",\n" +
                "      \"parent_id\": null,\n" +
                "      \"user_id\": null\n" +
                "    }\n" +
                "  },\n" +
                "  {\n" +
                "    \"entity_id\": \"light.couch_light\",\n" +
                "    \"state\": \"on\",\n" +
                "    \"attributes\": {\n" +
                "      \"supported_color_modes\": [\n" +
                "        \"xy\"\n" +
                "      ],\n" +
                "      \"color_mode\": \"xy\",\n" +
                "      \"brightness\": 245,\n" +
                "      \"hs_color\": [\n" +
                "        12.464,\n" +
                "        81.176\n" +
                "      ],\n" +
                "      \"rgb_color\": [\n" +
                "        255,\n" +
                "        91,\n" +
                "        48\n" +
                "      ],\n" +
                "      \"xy_color\": [\n" +
                "        0.6408,\n" +
                "        0.3284\n" +
                "      ],\n" +
                "      \"mode\": \"normal\",\n" +
                "      \"dynamics\": \"none\",\n" +
                "      \"friendly_name\": \"Couch Light\",\n" +
                "      \"supported_features\": 40\n" +
                "    },\n" +
                "    \"last_changed\": \"2023-09-24T14:50:55.040229+00:00\",\n" +
                "    \"last_updated\": \"2023-09-24T16:13:00.757488+00:00\",\n" +
                "    \"context\": {\n" +
                "      \"id\": \"01HB3ZECEN2DN594VKX5RFRQ5P\",\n" +
                "      \"parent_id\": null,\n" +
                "      \"user_id\": null\n" +
                "    }\n" +
                "  }\n" +
                "]\n");

        assertThat(api.getAssignedGroups("light.couch_light")).containsExactlyInAnyOrder("light.couch_group",
                "light.couch_group2");
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
        setGetResponse("/states/light.on_off", "{\n" +
                "  \"entity_id\": \"light.on_off\",\n" +
                "  \"state\": \"on\",\n" +
                "  \"attributes\": {\n" +
                "    \"supported_color_modes\": [],\n" +
                "    \"color_mode\": \"onoff\",\n" +
                "    \"mode\": \"normal\",\n" +
                "    \"dynamics\": \"none\",\n" +
                "    \"friendly_name\": \"On Off\",\n" +
                "    \"supported_features\": 0\n" +
                "  },\n" +
                "  \"last_changed\": \"2023-09-24T07:55:01.698292+00:00\",\n" +
                "  \"last_updated\": \"2023-09-24T07:55:01.698292+00:00\",\n" +
                "  \"context\": {\n" +
                "    \"id\": \"123456789\",\n" +
                "    \"parent_id\": null,\n" +
                "    \"user_id\": null\n" +
                "  }\n" +
                "}");


        assertThat(api.isLightOff("light.on_off")).isFalse();
        assertThat(api.isGroupOff("light.on_off")).isFalse();
    }

    @Test
    void isLightOff_off_true() {
        setGetResponse("/states/light.on_off", "{\n" +
                "  \"entity_id\": \"light.on_off\",\n" +
                "  \"state\": \"off\",\n" +
                "  \"attributes\": {\n" +
                "    \"supported_color_modes\": [],\n" +
                "    \"color_mode\": \"onoff\",\n" +
                "    \"mode\": \"normal\",\n" +
                "    \"dynamics\": \"none\",\n" +
                "    \"friendly_name\": \"On Off\",\n" +
                "    \"supported_features\": 0\n" +
                "  },\n" +
                "  \"last_changed\": \"2023-09-24T07:55:01.698292+00:00\",\n" +
                "  \"last_updated\": \"2023-09-24T07:55:01.698292+00:00\",\n" +
                "  \"context\": {\n" +
                "    \"id\": \"123456789\",\n" +
                "    \"parent_id\": null,\n" +
                "    \"user_id\": null\n" +
                "  }\n" +
                "}");


        assertThat(api.isLightOff("light.on_off")).isTrue();
        assertThat(api.isGroupOff("light.on_off")).isTrue();
    }

    @Test
    void isLightOff_unavailable_true() {
        setGetResponse("/states/light.on_off", "{\n" +
                "  \"entity_id\": \"light.on_off\",\n" +
                "  \"state\": \"unavailable\",\n" +
                "  \"attributes\": {\n" +
                "    \"supported_color_modes\": [],\n" +
                "    \"color_mode\": \"onoff\",\n" +
                "    \"mode\": \"normal\",\n" +
                "    \"dynamics\": \"none\",\n" +
                "    \"friendly_name\": \"On Off\",\n" +
                "    \"supported_features\": 0\n" +
                "  },\n" +
                "  \"last_changed\": \"2023-09-24T07:55:01.698292+00:00\",\n" +
                "  \"last_updated\": \"2023-09-24T07:55:01.698292+00:00\",\n" +
                "  \"context\": {\n" +
                "    \"id\": \"123456789\",\n" +
                "    \"parent_id\": null,\n" +
                "    \"user_id\": null\n" +
                "  }\n" +
                "}");


        assertThat(api.isLightOff("light.on_off")).isTrue();
        assertThat(api.isGroupOff("light.on_off")).isTrue();
    }

    private void setGetResponse(String expectedUrl, String response) {
        when(http.getResource(getUrl(expectedUrl))).thenReturn(response);
    }

    private URL getUrl(String expectedUrl) {
        try {
            return new URL(baseUrl + expectedUrl);
        } catch (MalformedURLException e) {
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
