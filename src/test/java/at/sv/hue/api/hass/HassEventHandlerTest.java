package at.sv.hue.api.hass;

import at.sv.hue.api.BridgeAuthenticationFailure;
import at.sv.hue.api.LightEventListener;
import at.sv.hue.api.SceneEventListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class HassEventHandlerTest {

    private LightEventListener lightEventListener;
    private SceneEventListener sceneEventListener;
    private HassAvailabilityEventListener availabilityListener;
    private HassEventHandler handler;

    @BeforeEach
    void setUp() {
        lightEventListener = Mockito.mock(LightEventListener.class);
        sceneEventListener = Mockito.mock(SceneEventListener.class);
        availabilityListener = Mockito.mock(HassAvailabilityEventListener.class);
        handler = new HassEventHandler(lightEventListener, sceneEventListener, availabilityListener);
    }

    @Test
    void onMessage_authFailed_throwsException() {
        assertThatThrownBy(() -> handler.onMessage("""
                  {
                  "type": "auth_invalid",
                  "message": "Invalid password"
                }"""))
                .isInstanceOf(BridgeAuthenticationFailure.class);

        verifyNoEvents();
    }

    @Test
    void onMessage_homeAssistantStarted() {
        handler.onMessage("""
                {
                    "type": "event",
                    "event": {
                        "event_type": "homeassistant_started",
                        "data": {},
                        "origin": "LOCAL",
                        "time_fired": "2025-03-29T12:27:06.273030+00:00",
                        "context": {
                            "id": "01JQGXXFN10NRN93F72KV1QEAX",
                            "parent_id": null,
                            "user_id": null
                        }
                    },
                    "id": 1
                }
                """);

        verify(availabilityListener).onStarted();
    }

    @Test
    void onMessage_missingEvent_ignored() {
        handler.onMessage("""
                {
                    "type": "event",
                    "id": 1
                }
                """);

        verifyNoEvents();
    }

    @Test
    void onMessage_stateChanged_onEvent_noPreviousState_ignored() {
        handler.onMessage("""
                {
                  "id": 1,
                  "type": "event",
                  "event": {
                    "event_type": "state_changed",
                    "data": {
                      "entity_id": "light.schreibtisch_r",
                      "old_state": null,
                      "new_state": {
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
                          "brightness": 47,
                          "hs_color": [
                            1.667,
                            42.353
                          ],
                          "rgb_color": [
                            255,
                            150,
                            147
                          ],
                          "xy_color": [
                            0.4851,
                            0.3198
                          ],
                          "effect": "None",
                          "mode": "normal",
                          "dynamics": "none",
                          "friendly_name": "Schreibtisch R",
                          "supported_features": 44
                        },
                        "last_changed": "2023-10-14T13:44:10.209177+00:00",
                        "last_updated": "2023-10-14T13:44:10.209177+00:00",
                        "context": {
                          "id": "01HCQ6W7719HQPH8YP18XSJCAZ",
                          "parent_id": null,
                          "user_id": null
                        }
                      }
                    },
                    "origin": "LOCAL",
                    "time_fired": "2023-10-14T13:44:10.209177+00:00",
                    "context": {
                      "id": "01HCQ6W7719HQPH8YP18XSJCAZ",
                      "parent_id": null,
                      "user_id": null
                    }
                  }
                }
                """);

        verifyNoEvents();
    }

    @Test
    void onMessage_stateChanged_lightOn_previouslyOff_triggersOnEvent() {
        handler.onMessage("""
                {
                  "id": 1,
                  "type": "event",
                  "event": {
                    "event_type": "state_changed",
                    "data": {
                      "entity_id": "light.schreibtisch_r",
                      "old_state": {
                        "entity_id": "light.schreibtisch_r",
                        "state": "off",
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
                          "mode": "normal",
                          "dynamics": "none",
                          "friendly_name": "Schreibtisch R",
                          "supported_features": 44
                        },
                        "last_changed": "2023-10-14T13:44:05.470395+00:00",
                        "last_updated": "2023-10-14T13:44:05.470395+00:00",
                        "context": {
                          "id": "01HCQ6W2JY90P81WF6C9PW0T34",
                          "parent_id": null,
                          "user_id": null
                        }
                      },
                      "new_state": {
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
                          "brightness": 47,
                          "hs_color": [
                            1.667,
                            42.353
                          ],
                          "rgb_color": [
                            255,
                            150,
                            147
                          ],
                          "xy_color": [
                            0.4851,
                            0.3198
                          ],
                          "effect": "None",
                          "mode": "normal",
                          "dynamics": "none",
                          "friendly_name": "Schreibtisch R",
                          "supported_features": 44
                        },
                        "last_changed": "2023-10-14T13:44:10.209177+00:00",
                        "last_updated": "2023-10-14T13:44:10.209177+00:00",
                        "context": {
                          "id": "01HCQ6W7719HQPH8YP18XSJCAZ",
                          "parent_id": null,
                          "user_id": null
                        }
                      }
                    },
                    "origin": "LOCAL",
                    "time_fired": "2023-10-14T13:44:10.209177+00:00",
                    "context": {
                      "id": "01HCQ6W7719HQPH8YP18XSJCAZ",
                      "parent_id": null,
                      "user_id": null
                    }
                  }
                }
                """);

        verify(lightEventListener).onLightOn("light.schreibtisch_r");
    }

    @Test
    void onMessage_stateChanged_lightOn_previouslyUnavailable_triggersOnEventWithPhysicalFlag() {
        handler.onMessage("""
                {
                  "id": 1,
                  "type": "event",
                  "event": {
                    "event_type": "state_changed",
                    "data": {
                      "entity_id": "light.schreibtisch_r",
                      "old_state": {
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
                        "last_changed": "2023-10-14T13:59:26.369055+00:00",
                        "last_updated": "2023-10-14T13:59:26.369055+00:00",
                        "context": {
                          "id": "01HCQ7R5X1SWMGV0988WY4RWZJ",
                          "parent_id": null,
                          "user_id": null
                        }
                      },
                      "new_state": {
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
                          "brightness": 255,
                          "hs_color": [
                            1.667,
                            42.353
                          ],
                          "rgb_color": [
                            255,
                            150,
                            147
                          ],
                          "xy_color": [
                            0.4853,
                            0.3197
                          ],
                          "effect": "None",
                          "mode": "normal",
                          "dynamics": "none",
                          "friendly_name": "Schreibtisch R",
                          "supported_features": 44
                        },
                        "last_changed": "2023-10-14T14:04:26.781526+00:00",
                        "last_updated": "2023-10-14T14:04:26.781526+00:00",
                        "context": {
                          "id": "01HCQ81B8XA35WZJJVWCH991ZF",
                          "parent_id": null,
                          "user_id": null
                        }
                      }
                    },
                    "origin": "LOCAL",
                    "time_fired": "2023-10-14T14:04:26.781526+00:00",
                    "context": {
                      "id": "01HCQ81B8XA35WZJJVWCH991ZF",
                      "parent_id": null,
                      "user_id": null
                    }
                  }
                }
                """);

        verify(lightEventListener).onPhysicalOn("light.schreibtisch_r");
    }

    @Test
    void onMessage_stateChanged_lightOn_previouslyAlreadyOn_noEventTriggered() {
        handler.onMessage("""
                {
                  "id": 1,
                  "type": "event",
                  "event": {
                    "event_type": "state_changed",
                    "data": {
                      "entity_id": "light.schreibtisch_r",
                      "old_state": {
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
                          "brightness": 49,
                          "hs_color": [
                            1.667,
                            42.353
                          ],
                          "rgb_color": [
                            255,
                            150,
                            147
                          ],
                          "xy_color": [
                            0.4851,
                            0.3198
                          ],
                          "effect": "None",
                          "mode": "normal",
                          "dynamics": "none",
                          "friendly_name": "Schreibtisch R",
                          "supported_features": 44
                        },
                        "last_changed": "2023-10-14T13:44:10.209177+00:00",
                        "last_updated": "2023-10-14T13:52:59.405483+00:00",
                        "context": {
                          "id": "01HCQ7CC0D5XAYKQ41ED4QT84F",
                          "parent_id": null,
                          "user_id": null
                        }
                      },
                      "new_state": {
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
                          "brightness": 255,
                          "hs_color": [
                            1.667,
                            42.353
                          ],
                          "rgb_color": [
                            255,
                            150,
                            147
                          ],
                          "xy_color": [
                            0.4851,
                            0.3198
                          ],
                          "effect": "None",
                          "mode": "normal",
                          "dynamics": "none",
                          "friendly_name": "Schreibtisch R",
                          "supported_features": 44
                        },
                        "last_changed": "2023-10-14T13:44:10.209177+00:00",
                        "last_updated": "2023-10-14T13:53:00.409390+00:00",
                        "context": {
                          "id": "01HCQ7CCZSVS7V3YCNBA86H3AJ",
                          "parent_id": null,
                          "user_id": null
                        }
                      }
                    },
                    "origin": "LOCAL",
                    "time_fired": "2023-10-14T13:53:00.409390+00:00",
                    "context": {
                      "id": "01HCQ7CCZSVS7V3YCNBA86H3AJ",
                      "parent_id": null,
                      "user_id": null
                    }
                  }
                }
                """);

        verifyNoEvents();
    }

    @Test
    void onMessage_stateChanged_lightOff_noPreviousState_ignored() {
        handler.onMessage("""
                {
                  "id": 1,
                  "type": "event",
                  "event": {
                    "event_type": "state_changed",
                    "data": {
                      "entity_id": "light.schreibtisch_r",
                      "old_state": null,
                      "new_state": {
                        "entity_id": "light.schreibtisch_r",
                        "state": "off",
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
                          "brightness": 47,
                          "hs_color": [
                            1.667,
                            42.353
                          ],
                          "rgb_color": [
                            255,
                            150,
                            147
                          ],
                          "xy_color": [
                            0.4851,
                            0.3198
                          ],
                          "effect": "None",
                          "mode": "normal",
                          "dynamics": "none",
                          "friendly_name": "Schreibtisch R",
                          "supported_features": 44
                        },
                        "last_changed": "2023-10-14T13:44:10.209177+00:00",
                        "last_updated": "2023-10-14T13:44:10.209177+00:00",
                        "context": {
                          "id": "01HCQ6W7719HQPH8YP18XSJCAZ",
                          "parent_id": null,
                          "user_id": null
                        }
                      }
                    },
                    "origin": "LOCAL",
                    "time_fired": "2023-10-14T13:44:10.209177+00:00",
                    "context": {
                      "id": "01HCQ6W7719HQPH8YP18XSJCAZ",
                      "parent_id": null,
                      "user_id": null
                    }
                  }
                }
                """);

        verifyNoEvents();
    }

    @Test
    void onMessage_stateChanged_lightOff_previouslyUnknownStatus_noEventTriggered() {
        handler.onMessage("""
                {
                  "id": 1,
                  "type": "event",
                  "event": {
                    "event_type": "state_changed",
                    "data": {
                      "entity_id": "light.schreibtisch_r",
                      "old_state": {
                        "entity_id": "light.schreibtisch_r",
                        "state": "INVALID",
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
                          "brightness": 49,
                          "hs_color": [
                            1.667,
                            42.353
                          ],
                          "rgb_color": [
                            255,
                            150,
                            147
                          ],
                          "xy_color": [
                            0.4851,
                            0.3198
                          ],
                          "effect": "None",
                          "mode": "normal",
                          "dynamics": "none",
                          "friendly_name": "Schreibtisch R",
                          "supported_features": 44
                        },
                        "last_changed": "2023-10-14T13:44:10.209177+00:00",
                        "last_updated": "2023-10-14T13:52:59.405483+00:00",
                        "context": {
                          "id": "01HCQ7CC0D5XAYKQ41ED4QT84F",
                          "parent_id": null,
                          "user_id": null
                        }
                      },
                      "new_state": {
                        "entity_id": "light.schreibtisch_r",
                        "state": "off",
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
                          "brightness": 255,
                          "hs_color": [
                            1.667,
                            42.353
                          ],
                          "rgb_color": [
                            255,
                            150,
                            147
                          ],
                          "xy_color": [
                            0.4851,
                            0.3198
                          ],
                          "effect": "None",
                          "mode": "normal",
                          "dynamics": "none",
                          "friendly_name": "Schreibtisch R",
                          "supported_features": 44
                        },
                        "last_changed": "2023-10-14T13:44:10.209177+00:00",
                        "last_updated": "2023-10-14T13:53:00.409390+00:00",
                        "context": {
                          "id": "01HCQ7CCZSVS7V3YCNBA86H3AJ",
                          "parent_id": null,
                          "user_id": null
                        }
                      }
                    },
                    "origin": "LOCAL",
                    "time_fired": "2023-10-14T13:53:00.409390+00:00",
                    "context": {
                      "id": "01HCQ7CCZSVS7V3YCNBA86H3AJ",
                      "parent_id": null,
                      "user_id": null
                    }
                  }
                }
                """);

        verifyNoEvents();
    }

    @Test
    void onMessage_stateChanged_lightOff_previouslyAlreadyOff_noEventTriggered() {
        handler.onMessage("""
                {
                  "id": 1,
                  "type": "event",
                  "event": {
                    "event_type": "state_changed",
                    "data": {
                      "entity_id": "light.schreibtisch_r",
                      "old_state": {
                        "entity_id": "light.schreibtisch_r",
                        "state": "off",
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
                          "brightness": 49,
                          "hs_color": [
                            1.667,
                            42.353
                          ],
                          "rgb_color": [
                            255,
                            150,
                            147
                          ],
                          "xy_color": [
                            0.4851,
                            0.3198
                          ],
                          "effect": "None",
                          "mode": "normal",
                          "dynamics": "none",
                          "friendly_name": "Schreibtisch R",
                          "supported_features": 44
                        },
                        "last_changed": "2023-10-14T13:44:10.209177+00:00",
                        "last_updated": "2023-10-14T13:52:59.405483+00:00",
                        "context": {
                          "id": "01HCQ7CC0D5XAYKQ41ED4QT84F",
                          "parent_id": null,
                          "user_id": null
                        }
                      },
                      "new_state": {
                        "entity_id": "light.schreibtisch_r",
                        "state": "off",
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
                          "brightness": 255,
                          "hs_color": [
                            1.667,
                            42.353
                          ],
                          "rgb_color": [
                            255,
                            150,
                            147
                          ],
                          "xy_color": [
                            0.4851,
                            0.3198
                          ],
                          "effect": "None",
                          "mode": "normal",
                          "dynamics": "none",
                          "friendly_name": "Schreibtisch R",
                          "supported_features": 44
                        },
                        "last_changed": "2023-10-14T13:44:10.209177+00:00",
                        "last_updated": "2023-10-14T13:53:00.409390+00:00",
                        "context": {
                          "id": "01HCQ7CCZSVS7V3YCNBA86H3AJ",
                          "parent_id": null,
                          "user_id": null
                        }
                      }
                    },
                    "origin": "LOCAL",
                    "time_fired": "2023-10-14T13:53:00.409390+00:00",
                    "context": {
                      "id": "01HCQ7CCZSVS7V3YCNBA86H3AJ",
                      "parent_id": null,
                      "user_id": null
                    }
                  }
                }
                """);

        verifyNoEvents();
    }

    @Test
    void onMessage_stateChanged_lightOff_previouslyOn_triggersOffEvent() {
        handler.onMessage("""
                {
                  "id": 1,
                  "type": "event",
                  "event": {
                    "event_type": "state_changed",
                    "data": {
                      "entity_id": "light.schreibtisch_l",
                      "old_state": {
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
                          "color_mode": "xy",
                          "brightness": 255,
                          "hs_color": [
                            1.667,
                            42.353
                          ],
                          "rgb_color": [
                            255,
                            150,
                            147
                          ],
                          "xy_color": [
                            0.4851,
                            0.3198
                          ],
                          "effect": "None",
                          "mode": "normal",
                          "dynamics": "none",
                          "friendly_name": "Schreibtisch L",
                          "supported_features": 44
                        },
                        "last_changed": "2023-10-14T13:44:10.209177+00:00",
                        "last_updated": "2023-10-14T13:53:00.409390+00:00",
                        "context": {
                          "id": "01HCQ7CCZSVS7V3YCNBA86H3AJ",
                          "parent_id": null,
                          "user_id": null
                        }
                      },
                      "new_state": {
                        "entity_id": "light.schreibtisch_l",
                        "state": "off",
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
                          "mode": "normal",
                          "dynamics": "none",
                          "friendly_name": "Schreibtisch L",
                          "supported_features": 44
                        },
                        "last_changed": "2023-10-14T13:55:56.920375+00:00",
                        "last_updated": "2023-10-14T13:55:56.920375+00:00",
                        "context": {
                          "id": "01HCQ7HSBR36FE1FZSPZGKG6RZ",
                          "parent_id": null,
                          "user_id": null
                        }
                      }
                    },
                    "origin": "LOCAL",
                    "time_fired": "2023-10-14T13:55:56.920375+00:00",
                    "context": {
                      "id": "01HCQ7HSBR36FE1FZSPZGKG6RZ",
                      "parent_id": null,
                      "user_id": null
                    }
                  }
                }
                """);

        verify(lightEventListener).onLightOff("light.schreibtisch_l");
    }

    @Test
    void onMessage_stateChanged_lightUnavailable_previouslyOn_triggersOffEvent() {
        handler.onMessage("""
                {
                  "id": 1,
                  "type": "event",
                  "event": {
                    "event_type": "state_changed",
                    "data": {
                      "entity_id": "light.schreibtisch_l",
                      "old_state": {
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
                          "color_mode": "xy",
                          "brightness": 255,
                          "hs_color": [
                            1.667,
                            42.353
                          ],
                          "rgb_color": [
                            255,
                            150,
                            147
                          ],
                          "xy_color": [
                            0.4853,
                            0.3197
                          ],
                          "effect": "None",
                          "mode": "normal",
                          "dynamics": "none",
                          "friendly_name": "Schreibtisch L",
                          "supported_features": 44
                        },
                        "last_changed": "2023-10-14T13:57:52.942740+00:00",
                        "last_updated": "2023-10-14T13:57:52.944123+00:00",
                        "context": {
                          "id": "01HCQ7NANGHR4XWBGSMY7HWRDS",
                          "parent_id": null,
                          "user_id": null
                        }
                      },
                      "new_state": {
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
                          "friendly_name": "Schreibtisch L",
                          "supported_features": 44
                        },
                        "last_changed": "2023-10-14T13:59:26.369055+00:00",
                        "last_updated": "2023-10-14T13:59:26.369055+00:00",
                        "context": {
                          "id": "01HCQ7R5X1SWMGV0988WY4RWZJ",
                          "parent_id": null,
                          "user_id": null
                        }
                      }
                    },
                    "origin": "LOCAL",
                    "time_fired": "2023-10-14T13:59:26.369055+00:00",
                    "context": {
                      "id": "01HCQ7R5X1SWMGV0988WY4RWZJ",
                      "parent_id": null,
                      "user_id": null
                    }
                  }
                }
                """);

        verify(lightEventListener).onLightOff("light.schreibtisch_l");
    }

    @Test
    void onMessage_stateChanged_lightOff_previouslyUnavailable_noEventTriggered() {
        handler.onMessage("""
                {
                  "id": 1,
                  "type": "event",
                  "event": {
                    "event_type": "state_changed",
                    "data": {
                      "entity_id": "light.schreibtisch_r",
                      "old_state": {
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
                          "color_mode": "xy",
                          "brightness": 49,
                          "hs_color": [
                            1.667,
                            42.353
                          ],
                          "rgb_color": [
                            255,
                            150,
                            147
                          ],
                          "xy_color": [
                            0.4851,
                            0.3198
                          ],
                          "effect": "None",
                          "mode": "normal",
                          "dynamics": "none",
                          "friendly_name": "Schreibtisch R",
                          "supported_features": 44
                        },
                        "last_changed": "2023-10-14T13:44:10.209177+00:00",
                        "last_updated": "2023-10-14T13:52:59.405483+00:00",
                        "context": {
                          "id": "01HCQ7CC0D5XAYKQ41ED4QT84F",
                          "parent_id": null,
                          "user_id": null
                        }
                      },
                      "new_state": {
                        "entity_id": "light.schreibtisch_r",
                        "state": "off",
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
                          "brightness": 255,
                          "hs_color": [
                            1.667,
                            42.353
                          ],
                          "rgb_color": [
                            255,
                            150,
                            147
                          ],
                          "xy_color": [
                            0.4851,
                            0.3198
                          ],
                          "effect": "None",
                          "mode": "normal",
                          "dynamics": "none",
                          "friendly_name": "Schreibtisch R",
                          "supported_features": 44
                        },
                        "last_changed": "2023-10-14T13:44:10.209177+00:00",
                        "last_updated": "2023-10-14T13:53:00.409390+00:00",
                        "context": {
                          "id": "01HCQ7CCZSVS7V3YCNBA86H3AJ",
                          "parent_id": null,
                          "user_id": null
                        }
                      }
                    },
                    "origin": "LOCAL",
                    "time_fired": "2023-10-14T13:53:00.409390+00:00",
                    "context": {
                      "id": "01HCQ7CCZSVS7V3YCNBA86H3AJ",
                      "parent_id": null,
                      "user_id": null
                    }
                  }
                }
                """);

        verifyNoEvents();
    }

    @Test
    void onMessage_callService_ignored() {
        handler.onMessage("""
                {
                    "type": "event",
                    "event": {
                        "event_type": "call_service",
                        "data": {
                            "domain": "light",
                            "service": "turn_on",
                            "service_data": {
                                "entity_id": "light.bad_tur"
                            }
                        },
                        "origin": "LOCAL",
                        "time_fired": "2024-05-18T15:19:01.132779+00:00",
                        "context": {
                            "id": "01HY64HVRBHMN4YME0WRH6E3TP",
                            "parent_id": null,
                            "user_id": "93aa9186c4944cb182e08a61f7d201ca"
                        }
                    },
                    "id": 60
                }
                """);

        verifyNoEvents();
    }

    @Test
    void onMessage_subscribe_ignored() {
          handler.onMessage("""
                  {
                      "id": 50,
                      "type": "result",
                      "success": true,
                      "result": null
                  }
                  """);

        verifyNoEvents();
    }

    @Test
    void onMessage_hueSceneStateChanged_triggersEvent() {
        handler.onMessage("""
                {
                   "type": "event",
                   "event": {
                     "event_type": "state_changed",
                     "data": {
                       "entity_id": "scene.living_room_night",
                       "old_state": {
                         "entity_id": "scene.living_room_night",
                         "state": "unknown",
                         "attributes": {
                           "group_name": "Zuhause",
                           "group_type": "zone",
                           "name": "At Night",
                           "speed": 0.5,
                           "brightness": 8.3,
                           "is_dynamic": false,
                           "friendly_name": "Home At Night"
                         },
                         "last_changed": "2024-06-15T20:11:11.149695+00:00",
                         "last_reported": "2024-06-15T20:11:11.149695+00:00",
                         "last_updated": "2024-06-15T20:11:11.149695+00:00",
                         "context": {
                           "id": "01J0ERCYXDH6KCE5D3B0VG1M2W",
                           "parent_id": null,
                           "user_id": null
                         }
                       },
                       "new_state": {
                         "entity_id": "scene.living_room_night",
                         "state": "2024-06-19T18:45:44.425350+00:00",
                         "attributes": {
                           "group_name": "Zuhause",
                           "group_type": "zone",
                           "name": "At Night",
                           "speed": 0.5,
                           "brightness": 8.3,
                           "is_dynamic": false,
                           "friendly_name": "Home At Night"
                         },
                         "last_changed": "2024-06-19T18:45:44.425536+00:00",
                         "last_reported": "2024-06-19T18:45:44.425536+00:00",
                         "last_updated": "2024-06-19T18:45:44.425536+00:00",
                         "context": {
                           "id": "01J0RX3CB83RBV370M80HPNDV6",
                           "parent_id": null,
                           "user_id": null
                         }
                       }
                     },
                     "origin": "LOCAL",
                     "time_fired": "2024-06-19T18:45:44.425536+00:00",
                     "context": {
                       "id": "01J0RX3CB83RBV370M80HPNDV6",
                       "parent_id": null,
                       "user_id": null
                     }
                   },
                   "id": 30
                 }
                """);

        verify(sceneEventListener).onSceneActivated("scene.living_room_night");
    }

    @Test
    void onMessage_haScene_stateChanged_eventTriggered() {
        handler.onMessage("""
                {
                  "type" : "event",
                  "event" : {
                    "event_type" : "state_changed",
                    "data" : {
                      "entity_id" : "scene.huescheduler_bad",
                      "old_state" : {
                        "entity_id" : "scene.huescheduler_bad",
                        "state" : "2025-02-08T18:58:41.810800+00:00",
                        "attributes" : {
                          "entity_id" : [ "light.bad_tur", "light.bad_therme_neu", "light.bad_oben" ],
                          "friendly_name" : "huescheduler_bad"
                        },
                        "last_changed" : "2025-02-08T19:13:36.495651+00:00",
                        "last_reported" : "2025-02-08T19:13:36.495651+00:00",
                        "last_updated" : "2025-02-08T19:13:36.495651+00:00",
                        "context" : {
                          "id" : "01JKKFPK7FX2QYV1M8XVZC9BQD",
                          "parent_id" : null,
                          "user_id" : null
                        }
                      },
                      "new_state" : {
                        "entity_id" : "scene.huescheduler_bad",
                        "state" : "2025-02-08T19:14:25.879647+00:00",
                        "attributes" : {
                          "entity_id" : [ "light.bad_tur", "light.bad_therme_neu", "light.bad_oben" ],
                          "friendly_name" : "huescheduler_bad"
                        },
                        "last_changed" : "2025-02-08T19:14:25.879907+00:00",
                        "last_reported" : "2025-02-08T19:14:25.879907+00:00",
                        "last_updated" : "2025-02-08T19:14:25.879907+00:00",
                        "context" : {
                          "id" : "01JKKFR3EJS8CS87BVTPCDJMWX",
                          "parent_id" : "01JKKFR3DBQDFT2GPA330J85XF",
                          "user_id" : null
                        }
                      }
                    },
                    "origin" : "LOCAL",
                    "time_fired" : "2025-02-08T19:14:25.879907+00:00",
                    "context" : {
                      "id" : "01JKKFR3EJS8CS87BVTPCDJMWX",
                      "parent_id" : "01JKKFR3DBQDFT2GPA330J85XF",
                      "user_id" : null
                    }
                  },
                  "id" : 2
                }
                """);

        verify(sceneEventListener).onSceneActivated("scene.huescheduler_bad");
    }

    @Test
    void onMessage_hueScene_unknown_noEventTriggered() {
        handler.onMessage("""
                {
                  "type" : "event",
                  "event" : {
                    "event_type" : "state_changed",
                    "data" : {
                      "entity_id" : "scene.zuhause_huescheduler_2",
                      "old_state" : {
                        "entity_id" : "scene.zuhause_huescheduler_2",
                        "state" : "2025-02-08T18:58:41.810800+00:00",
                        "attributes" : {
                          "group_name" : "Zuhause",
                          "group_type" : "zone",
                          "name" : "HueScheduler",
                          "speed" : 0.5,
                          "brightness" : 72,
                          "is_dynamic" : false,
                          "friendly_name" : "Zuhause HueScheduler"
                        },
                        "last_changed" : "2025-02-08T18:13:18.249177+00:00",
                        "last_reported" : "2025-02-08T19:02:45.106506+00:00",
                        "last_updated" : "2025-02-08T19:02:45.106506+00:00",
                        "context" : {
                          "id" : "01JKKF2Q3JXRE6CKTEE67MGS59",
                          "parent_id" : null,
                          "user_id" : null
                        }
                      },
                      "new_state" : {
                        "entity_id" : "scene.zuhause_huescheduler_2",
                        "state" : "unknown",
                        "attributes" : {
                          "group_name" : "Zuhause",
                          "group_type" : "zone",
                          "name" : "HueScheduler",
                          "speed" : 0.5,
                          "brightness" : 71,
                          "is_dynamic" : false,
                          "friendly_name" : "Zuhause HueScheduler"
                        },
                        "last_changed" : "2025-02-08T18:13:18.249177+00:00",
                        "last_reported" : "2025-02-08T19:04:52.501765+00:00",
                        "last_updated" : "2025-02-08T19:04:52.501765+00:00",
                        "context" : {
                          "id" : "01JKKF6KGN0VR41G3A08DW44RQ",
                          "parent_id" : null,
                          "user_id" : null
                        }
                      }
                    },
                    "origin" : "LOCAL",
                    "time_fired" : "2025-02-08T19:04:52.501765+00:00",
                    "context" : {
                      "id" : "01JKKF6KGN0VR41G3A08DW44RQ",
                      "parent_id" : null,
                      "user_id" : null
                    }
                  },
                  "id" : 1
                }
                """);

        verifyNoEvents();
    }

    @Test
    void onMessage_haScene_update_noEventTriggered() {
        handler.onMessage("""
                {
                  "type" : "event",
                  "event" : {
                    "event_type" : "state_changed",
                    "data" : {
                      "entity_id" : "scene.huescheduler_bad",
                      "old_state" : null,
                      "new_state" : {
                        "entity_id" : "scene.huescheduler_bad",
                        "state" : "2025-02-08T18:58:41.810800+00:00",
                        "attributes" : {
                          "entity_id" : [ "light.bad_tur", "light.bad_therme_neu", "light.bad_oben" ],
                          "friendly_name" : "huescheduler_bad"
                        },
                        "last_changed" : "2025-02-08T19:11:28.950183+00:00",
                        "last_reported" : "2025-02-08T19:11:28.950183+00:00",
                        "last_updated" : "2025-02-08T19:11:28.950183+00:00",
                        "context" : {
                          "id" : "01JKKFJPNP013F9BTVBGP4WW02",
                          "parent_id" : null,
                          "user_id" : null
                        }
                      }
                    },
                    "origin" : "LOCAL",
                    "time_fired" : "2025-02-08T19:11:28.950183+00:00",
                    "context" : {
                      "id" : "01JKKFJPNP013F9BTVBGP4WW02",
                      "parent_id" : null,
                      "user_id" : null
                    }
                  },
                  "id" : 1
                }
                """);

        verifyNoEvents();
    }

    @Test
    void onMessage_hueScene_onlyAttributesChanged_noEventTriggered() {
        handler.onMessage("""
                {
                  "type" : "event",
                  "event" : {
                    "event_type" : "state_changed",
                    "data" : {
                      "entity_id" : "scene.bad_huescheduler",
                      "old_state" : {
                        "entity_id" : "scene.bad_huescheduler",
                        "state" : "2025-02-09T13:20:57.257967+00:00",
                        "attributes" : {
                          "group_name" : "Bad",
                          "group_type" : "room",
                          "name" : "HueScheduler",
                          "speed" : 0.5,
                          "brightness" : 242,
                          "is_dynamic" : false,
                          "friendly_name" : "Bad HueScheduler"
                        },
                        "last_changed" : "2025-03-29T16:38:35.508777+00:00",
                        "last_reported" : "2025-03-29T16:45:52.068893+00:00",
                        "last_updated" : "2025-03-29T16:45:52.068893+00:00",
                        "context" : {
                          "id" : "01JQHCQ9J4Z21229HGC6G96WET",
                          "parent_id" : null,
                          "user_id" : null
                        }
                      },
                      "new_state" : {
                        "entity_id" : "scene.bad_huescheduler",
                        "state" : "2025-02-09T13:20:57.257967+00:00",
                        "attributes" : {
                          "group_name" : "Bad",
                          "group_type" : "room",
                          "name" : "HueScheduler",
                          "speed" : 0.5,
                          "brightness" : 236,
                          "is_dynamic" : false,
                          "friendly_name" : "Bad HueScheduler"
                        },
                        "last_changed" : "2025-03-29T16:38:35.508777+00:00",
                        "last_reported" : "2025-03-29T16:48:52.936191+00:00",
                        "last_updated" : "2025-03-29T16:48:52.936191+00:00",
                        "context" : {
                          "id" : "01JQHCWT68X3HBTQWHJ00HH0N0",
                          "parent_id" : null,
                          "user_id" : null
                        }
                      }
                    },
                    "origin" : "LOCAL",
                    "time_fired" : "2025-03-29T16:48:52.936191+00:00",
                    "context" : {
                      "id" : "01JQHCWT68X3HBTQWHJ00HH0N0",
                      "parent_id" : null,
                      "user_id" : null
                    }
                  },
                  "id" : 1
                }
                """);

        verifyNoEvents();
    }

    @Test
    void onMesssage_haScene_unavailable_noEventTriggered() {
        handler.onMessage("""
                {
                    "type": "event",
                    "event": {
                        "event_type": "state_changed",
                        "data": {
                            "entity_id": "scene.test_scene",
                            "old_state": {
                                "entity_id": "scene.test_scene",
                                "state": "2024-06-22T20:26:10.406785+00:00",
                                "attributes": {
                                    "entity_id": [
                                        "light.schreibtisch_r",
                                        "light.schreibtisch_l"
                                    ],
                                    "id": "1719087533616",
                                    "icon": "mdi:cake-layered",
                                    "friendly_name": "Test Scene"
                                },
                                "last_changed": "2024-06-22T20:26:10.407069+00:00",
                                "last_reported": "2024-06-22T20:26:10.407069+00:00",
                                "last_updated": "2024-06-22T20:26:10.407069+00:00",
                                "context": {
                                    "id": "01J10T1E35VD9HE07PNHTNRH8C",
                                    "parent_id": null,
                                    "user_id": null
                                }
                            },
                            "new_state": {
                                "entity_id": "scene.test_scene",
                                "state": "unavailable",
                                "attributes": {
                                    "restored": true,
                                    "icon": "mdi:cake-layered",
                                    "friendly_name": "Test Scene",
                                    "supported_features": 0
                                },
                                "last_changed": "2024-06-22T20:26:48.307492+00:00",
                                "last_reported": "2024-06-22T20:26:48.307492+00:00",
                                "last_updated": "2024-06-22T20:26:48.307492+00:00",
                                "context": {
                                    "id": "01J10T2K3KT0HPWM86VZ1EZYKK",
                                    "parent_id": null,
                                    "user_id": null
                                }
                            }
                        },
                        "origin": "LOCAL",
                        "time_fired": "2024-06-22T20:26:48.307492+00:00",
                        "context": {
                            "id": "01J10T2K3KT0HPWM86VZ1EZYKK",
                            "parent_id": null,
                            "user_id": null
                        }
                    },
                    "id": 30
                }
                """);

        verifyNoEvents();
    }

    @Test
    void onMessage_automation_oldUnavailable_noEvent() {
        handler.onMessage("""
                {
                  "type" : "event",
                  "event" : {
                    "event_type" : "state_changed",
                    "data" : {
                      "entity_id" : "automation.test_automation",
                      "old_state" : {
                        "entity_id" : "automation.test_automation",
                        "state" : "unavailable",
                        "attributes" : {
                          "restored" : true,
                          "id" : "1739038096466",
                          "friendly_name" : "Test Automation",
                          "supported_features" : 0
                        },
                        "last_changed" : "2025-02-08T18:49:03.181666+00:00",
                        "last_reported" : "2025-02-08T18:49:03.181666+00:00",
                        "last_updated" : "2025-02-08T18:49:03.181666+00:00",
                        "context" : {
                          "id" : "01JKKE9MEDSH5FKH47MARXJSCB",
                          "parent_id" : null,
                          "user_id" : null
                        }
                      },
                      "new_state" : {
                        "entity_id" : "automation.test_automation",
                        "state" : "on",
                        "attributes" : {
                          "id" : "1739038096466",
                          "last_triggered" : "2025-02-08T18:48:51.669559+00:00",
                          "mode" : "single",
                          "current" : 0,
                          "friendly_name" : "Test Automation"
                        },
                        "last_changed" : "2025-02-08T18:49:03.186185+00:00",
                        "last_reported" : "2025-02-08T18:49:03.186185+00:00",
                        "last_updated" : "2025-02-08T18:49:03.186185+00:00",
                        "context" : {
                          "id" : "01JKKE9MEJP5JYKS8MADHZ9DZC",
                          "parent_id" : null,
                          "user_id" : null
                        }
                      }
                    },
                    "origin" : "LOCAL",
                    "time_fired" : "2025-02-08T18:49:03.186185+00:00",
                    "context" : {
                      "id" : "01JKKE9MEJP5JYKS8MADHZ9DZC",
                      "parent_id" : null,
                      "user_id" : null
                    }
                  },
                  "id" : 2
                }
                """);

        verifyNoEvents();
    }

    @Test
    void onMessage_automation_oldOff_noEvent() {
        handler.onMessage("""
                {
                  "type" : "event",
                  "event" : {
                    "event_type" : "state_changed",
                    "data" : {
                      "entity_id" : "automation.test_automation",
                      "old_state" : {
                        "entity_id" : "automation.test_automation",
                        "state" : "off",
                        "attributes" : {
                          "restored" : true,
                          "id" : "1739038096466",
                          "friendly_name" : "Test Automation",
                          "supported_features" : 0
                        },
                        "last_changed" : "2025-02-08T18:49:03.181666+00:00",
                        "last_reported" : "2025-02-08T18:49:03.181666+00:00",
                        "last_updated" : "2025-02-08T18:49:03.181666+00:00",
                        "context" : {
                          "id" : "01JKKE9MEDSH5FKH47MARXJSCB",
                          "parent_id" : null,
                          "user_id" : null
                        }
                      },
                      "new_state" : {
                        "entity_id" : "automation.test_automation",
                        "state" : "on",
                        "attributes" : {
                          "id" : "1739038096466",
                          "last_triggered" : "2025-02-08T18:48:51.669559+00:00",
                          "mode" : "single",
                          "current" : 0,
                          "friendly_name" : "Test Automation"
                        },
                        "last_changed" : "2025-02-08T18:49:03.186185+00:00",
                        "last_reported" : "2025-02-08T18:49:03.186185+00:00",
                        "last_updated" : "2025-02-08T18:49:03.186185+00:00",
                        "context" : {
                          "id" : "01JKKE9MEJP5JYKS8MADHZ9DZC",
                          "parent_id" : null,
                          "user_id" : null
                        }
                      }
                    },
                    "origin" : "LOCAL",
                    "time_fired" : "2025-02-08T18:49:03.186185+00:00",
                    "context" : {
                      "id" : "01JKKE9MEJP5JYKS8MADHZ9DZC",
                      "parent_id" : null,
                      "user_id" : null
                    }
                  },
                  "id" : 2
                }
                """);

        verifyNoEvents();
    }

    @Test
    void onMessage_noNewState_noEvent() {
        handler.onMessage("""
                {
                  "type" : "event",
                  "event" : {
                    "event_type" : "state_changed",
                    "data" : {
                      "entity_id" : "scene.huescheduler_bad",
                      "old_state" : {
                        "entity_id" : "scene.huescheduler_bad",
                        "state" : "2025-02-08T18:48:51.672912+00:00",
                        "attributes" : {
                          "entity_id" : [ "light.bad_tur", "light.bad_therme_neu", "light.bad_oben" ],
                          "friendly_name" : "huescheduler_bad"
                        },
                        "last_changed" : "2025-02-08T18:48:51.673151+00:00",
                        "last_reported" : "2025-02-08T18:48:51.673151+00:00",
                        "last_updated" : "2025-02-08T18:48:51.673151+00:00",
                        "context" : {
                          "id" : "01JKKE996MSAD4V9NWHRVTQ88P",
                          "parent_id" : "01JKKE996KCV043A4N7SZTY34E",
                          "user_id" : null
                        }
                      },
                      "new_state" : null
                    },
                    "origin" : "LOCAL",
                    "time_fired" : "2025-02-08T18:54:03.576897+00:00",
                    "context" : {
                      "id" : "01JKKE996MSAD4V9NWHRVTQ88P",
                      "parent_id" : "01JKKE996KCV043A4N7SZTY34E",
                      "user_id" : null
                    }
                  },
                  "id" : 1
                }
                """);

        verifyNoEvents();
    }

    @Test
    void onMessage_incorrectType_noEvent() {
        handler.onMessage("""
                {
                  "type" : "INCORRECT_TYPE",
                  "event" : {
                    "event_type" : "state_changed",
                    "data" : {
                      "entity_id" : "scene.huescheduler_bad",
                      "old_state" : {
                        "entity_id" : "scene.huescheduler_bad",
                        "state" : "2025-02-08T18:58:41.810800+00:00",
                        "attributes" : {
                          "entity_id" : [ "light.bad_tur", "light.bad_therme_neu", "light.bad_oben" ],
                          "friendly_name" : "huescheduler_bad"
                        },
                        "last_changed" : "2025-02-08T19:13:36.495651+00:00",
                        "last_reported" : "2025-02-08T19:13:36.495651+00:00",
                        "last_updated" : "2025-02-08T19:13:36.495651+00:00",
                        "context" : {
                          "id" : "01JKKFPK7FX2QYV1M8XVZC9BQD",
                          "parent_id" : null,
                          "user_id" : null
                        }
                      },
                      "new_state" : {
                        "entity_id" : "scene.huescheduler_bad",
                        "state" : "2025-02-08T19:14:25.879647+00:00",
                        "attributes" : {
                          "entity_id" : [ "light.bad_tur", "light.bad_therme_neu", "light.bad_oben" ],
                          "friendly_name" : "huescheduler_bad"
                        },
                        "last_changed" : "2025-02-08T19:14:25.879907+00:00",
                        "last_reported" : "2025-02-08T19:14:25.879907+00:00",
                        "last_updated" : "2025-02-08T19:14:25.879907+00:00",
                        "context" : {
                          "id" : "01JKKFR3EJS8CS87BVTPCDJMWX",
                          "parent_id" : "01JKKFR3DBQDFT2GPA330J85XF",
                          "user_id" : null
                        }
                      }
                    },
                    "origin" : "LOCAL",
                    "time_fired" : "2025-02-08T19:14:25.879907+00:00",
                    "context" : {
                      "id" : "01JKKFR3EJS8CS87BVTPCDJMWX",
                      "parent_id" : "01JKKFR3DBQDFT2GPA330J85XF",
                      "user_id" : null
                    }
                  },
                  "id" : 2
                }
                """);

        verifyNoEvents();
    }

    private void verifyNoEvents() {
        verifyNoInteractions(lightEventListener);
        verifyNoInteractions(sceneEventListener);
        verifyNoInteractions(availabilityListener);
    }
}
