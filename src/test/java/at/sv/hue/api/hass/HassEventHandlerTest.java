package at.sv.hue.api.hass;

import at.sv.hue.api.BridgeAuthenticationFailure;
import at.sv.hue.api.LightEventListener;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class HassEventHandlerTest {

    private LightEventListener eventListener;
    private HassEventHandler handler;

    @BeforeEach
    void setUp() {
        eventListener = Mockito.mock(LightEventListener.class);
        handler = new HassEventHandler(eventListener);
    }

    @Test
    void onMessage_authFailed_throwsException() {
        assertThatThrownBy(() -> handler.onMessage("""
                  {
                  "type": "auth_invalid",
                  "message": "Invalid password"
                }"""))
                .isInstanceOf(BridgeAuthenticationFailure.class);

        verifyNoInteractions(eventListener);
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

        verify(eventListener).onLightOn("light.schreibtisch_r", null, false);
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

        verify(eventListener).onLightOn("light.schreibtisch_r", null, true);
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

        verifyNoInteractions(eventListener);
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

        verifyNoInteractions(eventListener);
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

        verifyNoInteractions(eventListener);
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

        verify(eventListener).onLightOff("light.schreibtisch_l", null);
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

        verify(eventListener).onLightOff("light.schreibtisch_l", null);
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

        verifyNoInteractions(eventListener);
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

        verifyNoInteractions(eventListener);
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

        verifyNoInteractions(eventListener);
    }
}
