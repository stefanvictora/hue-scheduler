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
    private HassEventHandler handler;

    @BeforeEach
    void setUp() {
        lightEventListener = Mockito.mock(LightEventListener.class);
        sceneEventListener = Mockito.mock(SceneEventListener.class);
        handler = new HassEventHandler(lightEventListener, sceneEventListener);
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
    void onMessage_stateChanged_onEvent_noPreviousState_treatedAsOff() {
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

        verify(lightEventListener).onLightOn("light.schreibtisch_r");
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
    void onMessage_stateChanged_lightOff_noPreviousState_treatedAsOff_noEvent() {
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

    private void verifyNoEvents() {
        verifyNoInteractions(lightEventListener);
        verifyNoInteractions(sceneEventListener);
    }
}
