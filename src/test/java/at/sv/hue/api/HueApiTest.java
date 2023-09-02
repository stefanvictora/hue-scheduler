package at.sv.hue.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HueApiTest {
    private static final Double[][] GAMUT_A = new Double[][]{{0.704, 0.296}, {0.2151, 0.7106}, {0.138, 0.08}};
    private static final Double[][] GAMUT_B = new Double[][]{{0.675, 0.322}, {0.409, 0.518}, {0.167, 0.04}};
    private static final Double[][] GAMUT_C = new Double[][]{{0.6915, 0.3083}, {0.17, 0.7}, {0.1532, 0.0475}};
    private HueApi api;
    private String baseUrl;
    private HttpResourceProvider resourceProviderMock;
    
    @BeforeEach
    void setUp() {
        String ip = "localhost";
        String username = "username";
        resourceProviderMock = Mockito.mock(HttpResourceProvider.class);
        api = new HueApiImpl(resourceProviderMock, ip, username, permits -> {
        });
        baseUrl = "https://" + ip + "/api/" + username;
    }

    @Test
    void checkConnection_unauthorizedUser_exception() {
        setGetResponse("/lights", "[\n" +
                "{\n" +
                "\"error\": {\n" +
                "\"type\": 1,\n" +
                "\"address\": \"/lights\",\n" +
                "\"description\": \"unauthorized user\"\n" +
                "}\n" +
                "}\n" +
                "]");

        assertThrows(BridgeAuthenticationFailure.class, () -> api.assertConnection());
    }
    
    @Test
    void getState_networkFailure_exception() {
        when(resourceProviderMock.getResource(any())).thenThrow(new BridgeConnectionFailure("Failed"));
        
        assertThrows(BridgeConnectionFailure.class, () -> getLightState(1));
    }

    @Test
    void getState_unknownResourceError_exception() {
        setGetResponse("/lights/1", "[\n" +
                "{\n" +
                "\"error\": {\n" +
                "\"type\": 3,\n" +
                "\"address\": \"/lights/1/state\",\n" +
                "\"description\": \"resource, /lights/1/state, not available\"\n" +
                "}\n" +
                "}\n" +
                "]");

        assertThrows(HueApiFailure.class, () -> getLightState(1), "resource, /lights/1/state, not available");
    }

    @Test
    void getState_anyOtherError_exception() {
        setGetResponse("/lights/1", "[\n" +
                "{\n" +
                "\"error\": {\n" +
                "\"type\": 1000,\n" +
                "\"address\": \"/lights/1/state\",\n" +
                "\"description\": \"any other error\"\n" +
                "}\n" +
                "}\n" +
                "]");

        assertThrows(HueApiFailure.class, () -> getLightState(1), "any other error");
    }

    @Test
    void getState_emptyResponse_exception() {
        setGetResponse("/lights/1", "");

        assertThrows(HueApiFailure.class, () -> getLightState(1));
    }

    @Test
    void getState_emptyJSON_exception() {
        setGetResponse("/lights/1", "{}");

        assertThrows(HueApiFailure.class, () -> getLightState(1));
    }

    @Test
    void getState_returnsLightState_callsCorrectApiURL() {
        int lightId = 22;
        double x = 0.6715;
        double y = 0.3233;
        int ct = 153;
        int bri = 100;
        int hue = 979;
        int sat = 254;
        setGetResponse("/lights/" + lightId, "{\n"
                + "  \"state\": {\n"
                + "    \"on\": true,\n"
                + "    \"bri\": " + bri + ",\n"
                + "    \"hue\": " + hue + ",\n"
                + "    \"sat\": " + sat + ",\n"
                + "    \"effect\": \"colorloop\",\n"
                + "    \"xy\": [\n"
                + "      " + x + ",\n"
                + "      " + y + "\n"
                + "    ],\n"
                + "    \"ct\": " + ct + ",\n"
                + "    \"reachable\": true\n"
                + "  },\n"
                + "  \"name\": \"Name 2\",\n"
                + "  \"type\": \"Extended color light\"\n"
                + "}\n"
                + "\n");

        LightState lightState = getLightState(lightId);

        assertLightState(
                lightState, LightState
                        .builder()
                        .on(true)
                        .reachable(true)
                        .x(x)
                        .y(y)
                        .colorTemperature(ct)
                        .brightness(bri)
                        .hue(hue)
                        .sat(sat)
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
        double x = 0.5111;
        double y = 0.1132;
        int ct = 100;
        int bri = 41;
        int hue = 6000;
        int sat = 157;
        setGetResponse("/lights/" + lightId, "{\n"
                + "  \"state\": {\n"
                + "    \"on\": false,\n"
                + "    \"bri\": " + bri + ",\n"
                + "    \"hue\": " + hue + ",\n"
                + "    \"sat\": " + sat + ",\n"
                + "    \"effect\": \"none\",\n"
                + "    \"xy\": [\n"
                + "      " + x + ",\n"
                + "      " + y + "\n"
                + "    ],\n"
                + "    \"ct\": " + ct + ",\n"
                + "    \"reachable\": false\n"
                + "  },\n"
                + "  \"name\": \"Name 2\",\n"
                + "  \"type\": \"Extended color light\"\n"
                + "}\n"
                + "\n");

        LightState lightState = getLightState(lightId);

        assertLightState(lightState, LightState
                .builder()
                .on(false)
                .reachable(false)
                .x(x)
                .y(y)
                .colorTemperature(ct)
                .brightness(bri)
                .hue(hue)
                .sat(sat)
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
        int bri = 41;
        setGetResponse("/lights/" + lightId, "{\n"
                + "  \"state\": {\n"
                + "    \"on\": true,\n"
                + "    \"bri\": " + bri + ",\n"
                + "    \"reachable\": true\n"
                + "  },\n"
                + "  \"name\": \"Name 2\",\n"
                + "  \"type\": \"Dimmable light\"\n"
                + "}");

        LightState lightState = getLightState(lightId);

        assertLightState(lightState, LightState
                .builder()
                .on(true)
                .reachable(true)
                .brightness(bri)
                .lightCapabilities(LightCapabilities.builder()
                                                    .capabilities(EnumSet.of(Capability.BRIGHTNESS, Capability.ON_OFF))
                                                    .build())
                .build());
    }
    
    @Test
    void getState_onOffBulbOnly_noNullPointerException() {
        int lightId = 11;
        setGetResponse("/lights/" + lightId, "{\n"
                + "  \"state\": {\n"
                + "    \"on\": true,\n"
                + "    \"reachable\": true\n"
                + "  },\n"
                + "  \"name\": \"Name 2\",\n"
                + "  \"type\": \"On/Off plug-in unit\"\n"
                + "}");
        
        LightState lightState = getLightState(lightId);

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
        setGetResponse("/groups", "{\n"
                + "  \"17\": {\n"
                + "    \"name\": \"Group 17\",\n"
                + "    \"lights\": [\n"
                + "      \"8\",\n"
                + "      \"16\",\n"
                + "      \"21\",\n"
                + "      \"777\"\n"
                + "    ]\n"
                + "  },\n"
                + "  \"18\": {\n"
                + "    \"name\": \"Group 18\",\n"
                + "    \"lights\": [\n"
                + "      \"24\"\n"
                + "    ]\n"
                + "  }\n"
                + "}");
        setGetResponse("/lights", "{\n"
                + "  \"8\": {\n"
                + "    \"state\": {\n"
                + "      \"on\": true,\n"
                + "      \"bri\": 43,\n"
                + "      \"hue\": 692,\n"
                + "      \"sat\": 204,\n"
                + "      \"effect\": \"none\",\n"
                + "      \"xy\": [\n"
                + "        0.6189,\n"
                + "        0.3303\n"
                + "      ],\n"
                + "      \"ct\": 500,\n"
                + "      \"alert\": \"select\",\n"
                + "      \"colormode\": \"xy\",\n"
                + "      \"mode\": \"homeautomation\",\n"
                + "      \"reachable\": true\n"
                + "    },\n"
                + "    \"type\": \"Extended color light\",\n"
                + "    \"capabilities\": {\n"
                + "      \"certified\": true,\n"
                + "      \"control\": {\n"
                + "        \"mindimlevel\": 40,\n"
                + "        \"maxlumen\": 1600,\n"
                + "        \"colorgamuttype\": \"C\",\n"
                + "        \"colorgamut\": [\n"
                + "          [\n"
                + "            0.6915,\n"
                + "            0.3083\n"
                + "          ],\n"
                + "          [\n"
                + "            0.17,\n"
                + "            0.7\n"
                + "          ],\n"
                + "          [\n"
                + "            0.1532,\n"
                + "            0.0475\n"
                + "          ]\n"
                + "        ],\n"
                + "        \"ct\": {\n"
                + "          \"min\": 153,\n"
                + "          \"max\": 500\n"
                + "        }\n"
                + "      }\n"
                + "    }\n"
                + "  },\n"
                + "  \"16\": {\n"
                + "    \"state\": {\n"
                + "      \"on\": false,\n"
                + "      \"bri\": 127,\n"
                + "      \"alert\": \"select\",\n"
                + "      \"mode\": \"homeautomation\",\n"
                + "      \"reachable\": true\n"
                + "    },\n"
                + "    \"type\": \"Dimmable light\",\n"
                + "    \"capabilities\": {\n"
                + "      \"certified\": true,\n"
                + "      \"control\": {\n"
                + "        \"mindimlevel\": 5000,\n"
                + "        \"maxlumen\": 800\n"
                + "      }\n"
                + "    }\n"
                + "  },\n"
                + "  \"21\": {\n"
                + "    \"state\": {\n"
                + "      \"on\": false,\n"
                + "      \"bri\": 184,\n"
                + "      \"ct\": 366,\n"
                + "      \"alert\": \"select\",\n"
                + "      \"colormode\": \"ct\",\n"
                + "      \"mode\": \"homeautomation\",\n"
                + "      \"reachable\": true\n"
                + "    },\n"
                + "    \"type\": \"Color temperature light\",\n"
                + "    \"capabilities\": {\n"
                + "      \"certified\": true,\n"
                + "      \"control\": {\n"
                + "        \"mindimlevel\": 200,\n"
                + "        \"maxlumen\": 350,\n"
                + "        \"ct\": {\n"
                + "          \"min\": 153,\n"
                + "          \"max\": 454\n"
                + "        }\n"
                + "      }\n"
                + "    }\n"
                + "  },\n"
                + "  \"24\": {\n"
                + "    \"state\": {\n"
                + "      \"on\": false,\n"
                + "      \"alert\": \"select\",\n"
                + "      \"mode\": \"homeautomation\",\n"
                + "      \"reachable\": true\n"
                + "    },\n"
                + "    \"type\": \"On/Off plug-in unit\",\n"
                + "    \"capabilities\": {\n"
                + "      \"certified\": true,\n"
                + "      \"control\": {}\n"
                + "    }\n"
                + "  }\n"
                + "}");
        
        assertThat(api.getGroupStates(17)).containsExactly(
                LightState.builder()
                          .on(true)
                          .brightness(43)
                          .hue(692)
                          .sat(204)
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

        assertThat(api.getGroupStates(18)).containsExactly(
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
        setGetResponse("/groups", "{\n"
                + "  \"1\": {\n"
                + "    \"name\": \"Group 1\",\n"
                + "    \"lights\": [\n"
                + "      \"1\",\n"
                + "      \"2\",\n"
                + "      \"3\"\n"
                + "    ]\n"
                + "  },\n"
                + "  \"2\": {\n"
                + "    \"name\": \"Group 2\",\n"
                + "    \"lights\": [\n"
                + "      \"4\",\n"
                + "      \"5\"\n"
                + "    ]\n"
                + "  }}");
        
        assertThat(getGroupLights(2)).containsExactly(4, 5);
        assertThat(api.getGroupName(2)).isEqualTo("Group 2");
    }
    
    @Test
    void getAssignedGroups_givenLightId_returnsGroupIds() {
        setGetResponse("/groups", "{\n"
                + "  \"1\": {\n"
                + "    \"name\": \"Group 1\",\n"
                + "    \"lights\": [\n"
                + "      \"1\",\n"
                + "      \"2\",\n"
                + "      \"3\"\n"
                + "    ]\n"
                + "  },\n"
                + "  \"2\": {\n"
                + "    \"name\": \"Group 2\",\n"
                + "    \"lights\": [\n"
                + "      \"4\",\n"
                + "      \"5\",\n"
                + "      \"3\"\n"
                + "    ]\n"
                + "  }}");
        
        assertThat(api.getAssignedGroups(2)).containsExactly(1);
        assertThat(api.getAssignedGroups(3)).containsExactly(1, 2);
        assertThat(api.getAssignedGroups(777)).isEmpty();
        api.clearCaches();
        assertThat(api.getAssignedGroups(2)).containsExactly(1);
        assertThat(api.getAssignedGroups(2)).containsExactly(1);
        verify(resourceProviderMock, times(2)).getResource(any());
    }
    
    @Test
    void getGroupLights_emptyLights_exception() {
        setGetResponse("/groups", "{\n"
                + "  \"1\": {\n"
                + "    \"name\": \"Group 1\",\n"
                + "    \"lights\": [\n"
                + "    ]\n"
                + "  }\n"
                + "}}");

        assertThrows(EmptyGroupException.class, () -> getGroupLights(1));
    }

    @Test
    void getGroupLights_unknownId_exception() {
        setGetResponse("/groups", "{}");

        assertThrows(GroupNotFoundException.class, () -> api.getGroupLights(1234));
    }

    @Test
    void getGroupLights_emptyResponse_exception() {
        setGetResponse("/groups", "");

        assertThrows(HueApiFailure.class, () -> api.getGroupLights(1));
    }

    @Test
    void getGroupName_returnsNameForGroupId() {
        setGetResponse("/groups", "{\n"
                + "  \"1\": {\n"
                + "    \"name\": \"Group 1\",\n"
                + "    \"lights\": [\n"
                + "      \"1\",\n"
                + "      \"2\",\n"
                + "      \"3\"\n"
                + "    ]\n"
                + "  },\n"
                + "  \"2\": {\n"
                + "    \"name\": \"Group 2\",\n"
                + "    \"lights\": [\n"
                + "      \"4\",\n"
                + "      \"5\"\n"
                + "    ]\n"
                + "  }}");

        String name1 = api.getGroupName(1);
        String name2 = api.getGroupName(2);
        
        assertThat(name1).isEqualTo("Group 1");
        assertThat(name2).isEqualTo("Group 2");
    }

    @Test
    void getGroupName_unknownId_exception() {
        setGetResponse("/groups", "{}");

        assertThrows(GroupNotFoundException.class, () -> api.getGroupName(1234));
    }

    @Test
    void getGroupName_emptyResponse_exception() {
        setGetResponse("/groups", "");

        assertThrows(HueApiFailure.class, () -> api.getGroupName(1));
    }

    @Test
    void getLightId_returnsIdForLightName_reusesResponseForMultipleRequest() {
        setGetResponse("/lights", "{\n"
                + "  \"7\": {\n"
                + "    \"name\": \"Lamp 1\"\n"
                + "  },\n"
                + "  \"1234\": {\n"
                + "    \"name\": \"Lamp 2\"\n"
                + "  }\n"
                + "}");
        
        assertThat(api.getLightId("Lamp 1")).isEqualTo(7);
        assertThat(api.getLightId("Lamp 2")).isEqualTo(1234);
        verify(resourceProviderMock).getResource(any());
        api.clearCaches();
        assertThat(api.getLightId("Lamp 1")).isEqualTo(7);
        assertThat(api.getLightId("Lamp 1")).isEqualTo(7);
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

        assertThrows(HueApiFailure.class, () -> api.getLightId("Lamp"));
    }

    @Test
    void getGroupId_returnsIdForGroupName_reusesResponse() {
        setGetResponse("/groups", "{\n"
                + "  \"11\": {\n"
                + "    \"name\": \"Group 1\",\n"
                + "    \"lights\": [\n"
                + "      \"1\",\n"
                + "      \"2\"\n"
                + "    ]\n"
                + "  },\n"
                + "  \"789\": {\n"
                + "    \"name\": \"Group 2\",\n"
                + "    \"lights\": [\n"
                + "      \"3\",\n"
                + "      \"4\"\n"
                + "    ]\n"
                + "  }\n"
                + "}");
        
        assertThat(api.getGroupId("Group 1")).isEqualTo(11);
        assertThat(api.getGroupId("Group 2")).isEqualTo(789);
        verify(resourceProviderMock).getResource(any());
        api.clearCaches();
        assertThat(api.getGroupId("Group 1")).isEqualTo(11);
        assertThat(api.getGroupId("Group 1")).isEqualTo(11);
        verify(resourceProviderMock, times(2)).getResource(any());
    }
    
    @Test
    void getGroupId_unknownName_exception() {
        setGetResponse("/groups", "{}");

        assertThrows(GroupNotFoundException.class, () -> api.getGroupId("Unknown Group"));
    }

    @Test
    void getGroupId_emptyResponse_exception() {
        setGetResponse("/groups", "");

        assertThrows(HueApiFailure.class, () -> api.getGroupId("Group"));
    }

    @Test
    void getLightName_returnsNameForLightId() {
        setGetResponse("/lights", "{\n"
                + "  \"7\": {\n"
                + "    \"name\": \"Lamp 1\"\n"
                + "  },\n"
                + "  \"1234\": {\n"
                + "    \"name\": \"Lamp 2\"\n"
                + "  }\n"
                + "}");
        
        assertThat(api.getLightName(7)).isEqualTo("Lamp 1");
        assertThat(api.getLightName(1234)).isEqualTo("Lamp 2");
        verify(resourceProviderMock).getResource(any());
        api.clearCaches();
        assertThat(api.getLightName(7)).isEqualTo("Lamp 1");
        assertThat(api.getLightName(7)).isEqualTo("Lamp 1");
        verify(resourceProviderMock, times(2)).getResource(any());
    }

    @Test
    void getLightName_unknownId_exception() {
        setGetResponse("/lights", "{}");

        assertThrows(LightNotFoundException.class, () -> api.getLightName(1234));
    }

    @Test
    void getLightName_emptyResponse_exception() {
        setGetResponse("/lights", "");

        assertThrows(HueApiFailure.class, () -> api.getLightName(2));
    }

    @Test
    void getCapabilities_hasColorAndCtSupport() {
        setGetResponse("/lights", "{\n"
                + "  \"22\": {\n"
                + "    \"type\": \"Extended color light\",\n"
                + "    \"capabilities\": {\n"
                + "      \"control\": {\n"
                + "        \"mindimlevel\": 200,\n"
                + "        \"maxlumen\": 800,\n"
                + "        \"colorgamuttype\": \"C\",\n"
                + "        \"colorgamut\": [\n"
                + "          [\n"
                + "            0.6915,\n"
                + "            0.3083\n"
                + "          ],\n"
                + "          [\n"
                + "            0.17,\n"
                + "            0.7\n"
                + "          ],\n"
                + "          [\n"
                + "            0.1532,\n"
                + "            0.0475\n"
                + "          ]\n"
                + "        ],\n"
                + "        \"ct\": {\n"
                + "          \"min\": 153,\n"
                + "          \"max\": 500\n"
                + "        }\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "}");

        LightCapabilities capabilities = api.getLightCapabilities(22);

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
        setGetResponse("/lights", "{\n" +
                "\"22\": {\n" +
                "\"type\": \"Color light\",\n" +
                "\"capabilities\": {\n" +
                "\"control\": {\n" +
                "\"mindimlevel\": 200,\n" +
                "\"maxlumen\": 800,\n" +
                "\"colorgamuttype\": \"C\",\n" +
                "\"colorgamut\": [\n" +
                "[\n" +
                "0.6915,\n" +
                "0.3083\n" +
                "],\n" +
                "[\n" +
                "0.17,\n" +
                "0.7\n" +
                "],\n" +
                "[\n" +
                "0.1532,\n" +
                "0.0475\n" +
                "]\n" +
                "]\n" +
                "}\n" +
                "}\n" +
                "}\n" +
                "}");
        
        LightCapabilities capabilities = api.getLightCapabilities(22);

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
        setGetResponse("/lights", "{\n"
                + "  \"7\": {\n"
                + "    \"type\": \"Dimmable light\",\n"
                + "    \"capabilities\": {\n"
                + "      \"control\": {\n"
                + "        \"mindimlevel\": 5000,\n"
                + "        \"maxlumen\": 1600\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "}");

        LightCapabilities capabilities = api.getLightCapabilities(7);
        
        assertCapabilities(capabilities, LightCapabilities.builder().capabilities(EnumSet.of(Capability.BRIGHTNESS, Capability.ON_OFF)).build());
        assertThat(capabilities.isColorSupported()).isFalse();
        assertThat(capabilities.isCtSupported()).isFalse();
        assertThat(capabilities.isBrightnessSupported()).isTrue();
    }
    
    @Test
    void getCapabilities_colorTemperatureOnly() {
        setGetResponse("/lights", "{\n"
                + "  \"42\": {\n"
                + "    \"state\": {\n"
                + "      \"on\": true,\n"
                + "      \"bri\": 254,\n"
                + "      \"ct\": 408,\n"
                + "      \"alert\": \"select\",\n"
                + "      \"colormode\": \"ct\",\n"
                + "      \"mode\": \"homeautomation\",\n"
                + "      \"reachable\": false\n"
                + "    },\n"
                + "    \"type\": \"Color temperature light\",\n"
                + "    \"name\": \"Spot\",\n"
                + "    \"modelid\": \"LTA001\",\n"
                + "    \"manufacturername\": \"Signify Netherlands B.V.\",\n"
                + "    \"productname\": \"Hue ambiance lamp\",\n"
                + "    \"capabilities\": {\n"
                + "      \"certified\": true,\n"
                + "      \"control\": {\n"
                + "        \"mindimlevel\": 200,\n"
                + "        \"maxlumen\": 800,\n"
                + "        \"ct\": {\n"
                + "          \"min\": 153,\n"
                + "          \"max\": 454\n"
                + "        }\n"
                + "      },\n"
                + "      \"streaming\": {\n"
                + "        \"renderer\": false,\n"
                + "        \"proxy\": false\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "}");
        
        LightCapabilities capabilities = api.getLightCapabilities(42);
        
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
        setGetResponse("/lights", "{\n"
                + "  \"24\": {\n"
                + "    \"state\": {\n"
                + "      \"on\": false,\n"
                + "      \"alert\": \"select\",\n"
                + "      \"mode\": \"homeautomation\",\n"
                + "      \"reachable\": true\n"
                + "    },\n"
                + "    \"type\": \"On/Off plug-in unit\",\n"
                + "    \"name\": \"Smart Plug\",\n"
                + "    \"modelid\": \"LOM001\",\n"
                + "    \"manufacturername\": \"Signify Netherlands B.V.\",\n"
                + "    \"productname\": \"Hue Smart plug\",\n"
                + "    \"capabilities\": {\n"
                + "      \"certified\": true,\n"
                + "      \"control\": {},\n"
                + "      \"streaming\": {\n"
                + "        \"renderer\": false,\n"
                + "        \"proxy\": false\n"
                + "      }\n"
                + "    }\n"
                + "  }\n"
                + "}");
        
        LightCapabilities capabilities = api.getLightCapabilities(24);
        
        assertCapabilities(capabilities, LightCapabilities.builder().capabilities(EnumSet.of(Capability.ON_OFF)).build());
        assertThat(capabilities.isColorSupported()).isFalse();
        assertThat(capabilities.isCtSupported()).isFalse();
        assertThat(capabilities.isBrightnessSupported()).isFalse();
    }

    @Test
    void getCapabilities_unknownId_exception() {
        setGetResponse("/lights", "{}");

        assertThrows(LightNotFoundException.class, () -> api.getLightCapabilities(1234));
    }
    
    @Test
    void getGroupCapabilities_returnsMaxOfAllContainedLights() {
        setGetResponse("/lights", "{\n"
                + "  \"42\": {\n"
                + "    \"type\": \"Color temperature light\",\n"
                + "    \"capabilities\": {\n"
                + "      \"certified\": true,\n"
                + "      \"control\": {\n"
                + "        \"mindimlevel\": 200,\n"
                + "        \"maxlumen\": 800,\n"
                + "        \"ct\": {\n"
                + "          \"min\": 100,\n"
                + "          \"max\": 454\n"
                + "        }\n"
                + "      }\n"
                + "    }\n"
                + "  },\n"
                + "  \"22\": {\n"
                + "    \"type\": \"Color light\",\n"
                + "    \"capabilities\": {\n"
                + "      \"control\": {\n"
                + "        \"mindimlevel\": 200,\n"
                + "        \"maxlumen\": 800,\n"
                + "        \"colorgamuttype\": \"C\",\n"
                + "        \"colorgamut\": [\n"
                + "          [\n"
                + "            0.6915,\n"
                + "            0.3083\n"
                + "          ],\n"
                + "          [\n"
                + "            0.17,\n"
                + "            0.7\n"
                + "          ],\n"
                + "          [\n"
                + "            0.1532,\n"
                + "            0.0475\n"
                + "          ]\n"
                + "        ]\n"
                + "      }\n"
                + "    }\n"
                + "  },\n"
                + "  \"23\": {\n"
                + "    \"type\": \"Color light\",\n"
                + "    \"capabilities\": {\n"
                + "      \"control\": {\n"
                + "        \"mindimlevel\": 200,\n"
                + "        \"maxlumen\": 800,\n"
                + "        \"colorgamuttype\": \"A\",\n"
                + "        \"colorgamut\": [\n"
                + "          [\n"
                + "            0.704,\n"
                + "            0.296\n"
                + "          ],\n"
                + "          [\n"
                + "            0.2151,\n"
                + "            0.7106\n"
                + "          ],\n"
                + "          [\n"
                + "            0.138,\n"
                + "            0.08\n"
                + "          ]\n"
                + "        ]\n"
                + "      }\n"
                + "    }\n"
                + "  },\n"
                + "  \"24\": {\n"
                + "    \"type\": \"Color light\",\n"
                + "    \"capabilities\": {\n"
                + "      \"control\": {\n"
                + "        \"mindimlevel\": 200,\n"
                + "        \"maxlumen\": 800,\n"
                + "        \"colorgamuttype\": \"B\",\n"
                + "        \"colorgamut\": [\n"
                + "          [\n"
                + "            0.675,\n"
                + "            0.322\n"
                + "          ],\n"
                + "          [\n"
                + "            0.409,\n"
                + "            0.518\n"
                + "          ],\n"
                + "          [\n"
                + "            0.167,\n"
                + "            0.04\n"
                + "          ]\n"
                + "        ]\n"
                + "      }\n"
                + "    }\n"
                + "  },\n"
                + "  \"25\": {\n"
                + "    \"type\": \"Extended color light\",\n"
                + "    \"capabilities\": {\n"
                + "      \"certified\": true,\n"
                + "      \"control\": {\n"
                + "        \"mindimlevel\": 40,\n"
                + "        \"maxlumen\": 1600,\n"
                + "        \"colorgamuttype\": \"C\",\n"
                + "        \"colorgamut\": [\n"
                + "          [\n"
                + "            0.6915,\n"
                + "            0.3083\n"
                + "          ],\n"
                + "          [\n"
                + "            0.17,\n"
                + "            0.7\n"
                + "          ],\n"
                + "          [\n"
                + "            0.1532,\n"
                + "            0.0475\n"
                + "          ]\n"
                + "        ],\n"
                + "        \"ct\": {\n"
                + "          \"min\": 153,\n"
                + "          \"max\": 500\n"
                + "        }\n"
                + "      }\n"
                + "    }\n"
                + "  },\n"
                + "  \"30\": {\n"
                + "    \"type\": \"On/Off plug-in unit\",\n"
                + "    \"capabilities\": {\n"
                + "      \"certified\": true,\n"
                + "      \"control\": {}\n"
                + "    }\n"
                + "  }\n"
                + "}");
        setGetResponse("/groups", "{\n"
                + "  \"1\": {\n"
                + "    \"name\": \"Group 1\",\n"
                + "    \"lights\": [\n"
                + "      \"42\",\n"
                + "      \"22\",\n"
                + "      \"30\",\n"
                + "      \"25\"\n"
                + "    ]\n"
                + "  },\n"
                + "  \"2\": {\n"
                + "    \"name\": \"Group 2\",\n"
                + "    \"lights\": [\n"
                + "      \"22\"\n"
                + "    ]\n"
                + "  },\n"
                + "  \"3\": {\n"
                + "    \"name\": \"Group 3\",\n"
                + "    \"lights\": [\n"
                + "      \"42\"\n"
                + "    ]\n"
                + "  },\n"
                + "  \"4\": {\n"
                + "    \"name\": \"Group 4\",\n"
                + "    \"lights\": [\n"
                + "      \"30\"\n"
                + "    ]\n"
                + "  },\n"
                + "  \"5\": {\n"
                + "    \"name\": \"Group 5\",\n"
                + "    \"lights\": [\n"
                + "      \"23\"\n"
                + "    ]\n"
                + "  },\n"
                + "  \"6\": {\n"
                + "    \"name\": \"Group 6\",\n"
                + "    \"lights\": [\n"
                + "      \"22\",\n"
                + "      \"23\"\n"
                + "    ]\n"
                + "  },\n"
                + "  \"7\": {\n"
                + "    \"name\": \"Group 7\",\n"
                + "    \"lights\": [\n"
                + "      \"24\"\n"
                + "    ]\n"
                + "  }\n"
                + "}");

        assertCapabilities(
                api.getGroupCapabilities(1),
                LightCapabilities.builder()
                                 .colorGamut(GAMUT_C)
                        .ctMin(100)
                        .ctMax(500)
                        .capabilities(EnumSet.allOf(Capability.class))
                        .build()
        );
        assertCapabilities(
                api.getGroupCapabilities(2),
                LightCapabilities.builder()
                                 .colorGamut(GAMUT_C)
                        .capabilities(EnumSet.of(Capability.COLOR, Capability.BRIGHTNESS, Capability.ON_OFF))
                        .build());
        assertCapabilities(
                api.getGroupCapabilities(3),
                LightCapabilities.builder()
                        .ctMin(100)
                        .ctMax(454)
                        .capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE, Capability.BRIGHTNESS, Capability.ON_OFF))
                        .build()
        );
        assertCapabilities(
                api.getGroupCapabilities(4),
                LightCapabilities.builder()
                        .capabilities(EnumSet.of(Capability.ON_OFF))
                        .build()
        );
        assertCapabilities(
                api.getGroupCapabilities(5),
                LightCapabilities.builder()
                                 .colorGamut(GAMUT_A)
                        .capabilities(EnumSet.of(Capability.COLOR, Capability.BRIGHTNESS, Capability.ON_OFF))
                        .build());
        assertCapabilities(
                api.getGroupCapabilities(6),
                LightCapabilities.builder()
                                 .colorGamut(GAMUT_C)
                        .capabilities(EnumSet.of(Capability.COLOR, Capability.BRIGHTNESS, Capability.ON_OFF))
                        .build());
        assertCapabilities(
                api.getGroupCapabilities(7),
                LightCapabilities.builder()
                                 .colorGamut(GAMUT_B)
                        .capabilities(EnumSet.of(Capability.COLOR, Capability.BRIGHTNESS, Capability.ON_OFF))
                        .build());
    }
    
    @Test
    void putState_brightness_success_callsCorrectUrl() {
        setPutResponse("/lights/" + 15 + "/state", "{\"bri\":200}",
                "[\n" +
                        "{\n" +
                        "\"success\": {\n" +
                        "\"/lights/22/state/bri\": 200\n" +
                        "}\n" +
                        "}\n" +
                        "]");

        boolean success = performPutCall(PutCall.builder().id(15).bri(200).build());
        
        assertThat(success).isTrue();
    }

    @Test
    void putState_group_usesCorrectUrl() {
        setPutResponse("/groups/" + 9 + "/action", "{\"bri\":200}",
                "[\n" +
                        "{\n" +
                        "\"success\": {\n" +
                        "\"/groups/9/action/bri\": 200\n" +
                        "}\n" +
                        "}\n" +
                        "]");

        boolean success = performPutCall(PutCall.builder().id(9).bri(200).groupState(true).build());
        
        assertThat(success).isTrue();
    }

    @Test
    void putState_ct_correctBody() {
        setPutResponse("/lights/" + 16 + "/state", "{\"ct\":100}", "[success]");

        boolean success = performPutCall(PutCall.builder().id(16).ct(100).build());
        
        assertThat(success).isTrue();
    }

    @Test
    void putState_XAndY_correctBody() {
        double x = 0.6075;
        double y = 0.3525;
        setPutResponse("/lights/" + 16 + "/state", "{\"xy\":[" + x + "," + y + "]}", "[success]");

        boolean success = performPutCall(PutCall.builder().id(16).x(x).y(y).build());
        
        assertThat(success).isTrue();
    }

    @Test
    void putState_hueAndSaturation_correctBody() {
        int hue = 0;
        int sat = 254;
        setPutResponse("/lights/" + 16 + "/state", "{\"hue\":" + hue + ",\"sat\":" + sat + "}", "[success]");

        boolean success = performPutCall(PutCall.builder().id(16).hue(hue).sat(sat).build());
        
        assertThat(success).isTrue();
    }

    @Test
    void putState_on_setsFlagCorrectly() {
        setPutResponse("/lights/" + 16 + "/state", "{\"on\":true}", "[success]");

        boolean success = performPutCall(PutCall.builder().id(16).on(true).build());
        
        assertThat(success).isTrue();
    }

    @Test
    void putState_transitionTime_setsTimeCorrectly() {
        setPutResponse("/lights/" + 16 + "/state", "{\"transitiontime\":2}", "[success]");

        boolean success = performPutCall(PutCall.builder().id(16).transitionTime(2).build());
        
        assertThat(success).isTrue();
    }

    @Test
    void putState_transitionTime_defaultValueOfFour_isIgnored() {
        setPutResponse("/lights/" + 16 + "/state", "{}", "[success]");

        boolean success = performPutCall(PutCall.builder().id(16).transitionTime(4).build());
        
        assertThat(success).isTrue();
    }

    @Test
    void putState_effect_correctlySet() {
        setPutResponse("/lights/" + 1 + "/state", "{\"effect\":\"colorloop\"}", "[success]");

        boolean success = performPutCall(PutCall.builder().id(1).effect("colorloop").build());
        
        assertThat(success).isTrue();
    }

    @Test
    void putState_unauthorized_throwsException() {
        setPutResponse("/lights/" + 10 + "/state", "{\"bri\":100}", "[\n" +
                "{\n" +
                "\"error\": {\n" +
                "\"type\": 1,\n" +
                "\"address\": \"/lights\",\n" +
                "\"description\": \"unauthorized user\"\n" +
                "}\n" +
                "}\n" +
                "]");

        assertThrows(BridgeAuthenticationFailure.class, () -> performPutCall(PutCall.builder().id(10).bri(100).build()));
    }

    @Test
    void putState_lights_resourceNotAvailable_exception() {
        setPutResponse("/lights/" + 1 + "/state", "{\"bri\":100}", "[\n" +
                "{\n" +
                "\"error\": {\n" +
                "\"type\": 3,\n" +
                "\"address\": \"/lights/1/state\",\n" +
                "\"description\": \"resource, /lights/1/state, not available\"\n" +
                "}\n" +
                "}\n" +
                "]");

        HueApiFailure hueApiFailure = assertThrows(HueApiFailure.class, () -> performPutCall(PutCall.builder().id(1).bri(100).build()));
        assertThat(hueApiFailure.getMessage()).isEqualTo("resource, /lights/1/state, not available");
    }

    @Test
    void putState_groups_resourceNotAvailable_exception() {
        setPutResponse("/groups/" + 1 + "/action", "{\"bri\":100}", "[\n" +
                "{\n" +
                "\"error\": {\n" +
                "\"type\": 3,\n" +
                "\"address\": \"/groups/1/action\",\n" +
                "\"description\": \"resource, /groups/11/action, not available\"\n" +
                "}\n" +
                "}\n" +
                "]");

        HueApiFailure hueApiFailure = assertThrows(HueApiFailure.class, () -> performPutCall(PutCall.builder().id(1).bri(100).groupState(true).build()));
        assertThat(hueApiFailure.getMessage()).isEqualTo("resource, /groups/11/action, not available");
    }

    @Test
    void putState_connectionFailure_exception() {
        when(resourceProviderMock.putResource(any(), any())).thenThrow(new BridgeConnectionFailure("Failed"));
        
        assertThrows(BridgeConnectionFailure.class, () -> performPutCall(PutCall.builder().id(123).bri(100).build()));
    }

    @Test
    void putState_emptyResponse_treatedAsSuccess() {
        setPutResponse("/lights/" + 123 + "/state", "{\"bri\":100}",
                "[\n" +
                        "]");

        boolean success = performPutCall(PutCall.builder().id(123).bri(100).build());
        
        assertThat(success).isTrue();
    }

    @Test
    void putState_fakedInvalidParameterValueResponse_exception() {
        setPutResponse("/lights/" + 777 + "/state", "{\"bri\":300}",
                "[\n" +
                        "{\"success\":{\"/lights/22/state/transitiontime\":2}}," +
                        "{\n" +
                        "\"error\": {\n" +
                        "\"type\": 7,\n" +
                        "\"address\": \"/lights/22/state/bri\",\n" +
                        "\"description\": \"invalid value, 300}, for parameter, bri\"\n" +
                        "}\n" +
                        "}\n" +
                        "]");

        HueApiFailure hueApiFailure = assertThrows(HueApiFailure.class, () -> performPutCall(PutCall.builder().id(777).bri(300).build()));
        assertThat(hueApiFailure.getMessage()).isEqualTo("invalid value, 300}, for parameter, bri");
    }

    @Test
    void putState_parameterNotModifiable_becauseLightIsOff_returnsFalse() {
        setPutResponse("/lights/" + 777 + "/state", "{\"bri\":200}", "[\n" +
                "{\n" +
                "\"error\": {\n" +
                "\"type\": 201,\n" +
                "\"address\": \"/lights/777/state/bri\",\n" +
                "\"description\": \"parameter, bri, is not modifiable. Device is set to off.\"\n" +
                "}\n" +
                "}\n" +
                "]");

        boolean reachable = performPutCall(PutCall.builder().id(777).bri(200).build());
        
        assertThat(reachable).isFalse();
    }
    
    private void setGetResponse(String expectedUrl, String response) {
        when(resourceProviderMock.getResource(getUrl(expectedUrl))).thenReturn(response);
    }
    
    private URL getUrl(String expectedUrl) {
        try {
            return new URL(baseUrl + expectedUrl);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Could not create URL", e);
        }
    }
    
    private void setPutResponse(String expectedUrl, String expectedBody, String response) {
        when(resourceProviderMock.putResource(getUrl(expectedUrl), expectedBody)).thenReturn(response);
    }
    
    private LightState getLightState(int lightId) {
        return api.getLightState(lightId);
    }
    
    private void assertLightState(LightState lightState, LightState expected) {
        assertThat(lightState).isEqualTo(expected);
    }
    
    private boolean performPutCall(PutCall putCall) {
        return api.putState(putCall);
    }
    
    private List<Integer> getGroupLights(int groupId) {
        return api.getGroupLights(groupId);
    }
    
    private void assertCapabilities(LightCapabilities capabilities, LightCapabilities expected) {
        assertThat(capabilities).isEqualTo(expected);
    }
}
