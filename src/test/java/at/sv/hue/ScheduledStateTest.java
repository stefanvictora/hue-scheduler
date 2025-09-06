package at.sv.hue;

import at.sv.hue.api.Capability;
import at.sv.hue.api.Identifier;
import at.sv.hue.api.LightCapabilities;
import at.sv.hue.api.LightState;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledStateTest {

    private static final Double[][] GAMUT_A = new Double[][]{{0.704, 0.296}, {0.2151, 0.7106}, {0.138, 0.08}};
    private static final Double[][] GAMUT_C = new Double[][]{{0.6915, 0.3083}, {0.17, 0.7}, {0.1532, 0.0475}};
    private static final int BRIGHTNESS_THRESHOLD = 10;
    private static final int COLOR_TEMPERATURE_THRESHOLD_KELVIN = 350;
    private static final double COLOR_THRESHOLD = 0.08;

    private static final LightCapabilities COLOR_TEMPERATURE_LIGHT = LightCapabilities
            .builder()
            .capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE, Capability.BRIGHTNESS, Capability.ON_OFF))
            .build();
    private static final LightCapabilities COLOR_LIGHT_ONLY = LightCapabilities
            .builder()
            .capabilities(EnumSet.of(Capability.COLOR, Capability.BRIGHTNESS, Capability.ON_OFF))
            .colorGamut(GAMUT_C)
            .colorGamutType("C")
            .build();
    private static final LightCapabilities BRIGHTNESS_ONLY = LightCapabilities
            .builder()
            .capabilities(EnumSet.of(Capability.BRIGHTNESS, Capability.ON_OFF))
            .build();
    private static final LightCapabilities ON_OFF_ONLY = LightCapabilities
            .builder()
            .capabilities(EnumSet.of(Capability.ON_OFF))
            .build();
    private static final LightCapabilities FULL_CAPABILITIES = LightCapabilities
            .builder()
            .capabilities(EnumSet.allOf(Capability.class))
            .colorGamut(GAMUT_C)
            .colorGamutType("C")
            .build();

    @Test
    void lightStateDiffers_ct_sameColorTemperatureAndBrightness_false() {
        ScheduledState scheduledState = scheduledState(state().ct(153)
                                                              .bri(200));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .colorTemperature(153)
                                          .brightness(200)
                                          .colormode(ColorMode.CT)
                                          .lightCapabilities(COLOR_TEMPERATURE_LIGHT)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_ct_differentColorTemperature_sameBrightness_true() {
        ScheduledState scheduledState = scheduledState(state().ct(153)
                                                              .bri(200));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .colorTemperature(200)
                                          .brightness(200)
                                          .colormode(ColorMode.CT)
                                          .lightCapabilities(COLOR_TEMPERATURE_LIGHT)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_ct_differentColorTemperature_belowThreshold_false() {
        ScheduledState scheduledState = scheduledState(state().ct(200));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .colorTemperature(215)
                                          .colormode(ColorMode.CT)
                                          .lightCapabilities(COLOR_TEMPERATURE_LIGHT)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_ct_differentColorTemperature_aboveThreshold_true() {
        ScheduledState scheduledState = scheduledState(state().ct(200));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .colorTemperature(216)
                                          .colormode(ColorMode.CT)
                                          .lightCapabilities(COLOR_TEMPERATURE_LIGHT)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_ct_sameColorTemperature_differentBrightness_true() {
        ScheduledState scheduledState = scheduledState(state().ct(200)
                                                              .bri(1));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .colorTemperature(200)
                                          .brightness(200)
                                          .colormode(ColorMode.CT)
                                          .lightCapabilities(COLOR_TEMPERATURE_LIGHT)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_ct_sameColorTemperature_differentEffect_true() {
        ScheduledState scheduledState = scheduledState(state().ct(300)
                                                              .bri(200)
                                                              .effect("none"));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .colorTemperature(300)
                                          .brightness(200)
                                          .x(0.568)
                                          .y(0.889)
                                          .colormode(ColorMode.CT)
                                          .effect("colorloop") // even though this should not happen, if color mode is in "ct"
                                          .lightCapabilities(FULL_CAPABILITIES)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_ct_sameColorTemperatureAndBrightness_withAdditionalPropertiesOnLightState_sameColorMode_false() {
        ScheduledState scheduledState = scheduledState(state().ct(153)
                                                              .bri(200));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .colorTemperature(153)
                                          .brightness(200)
                                          .x(0.568)
                                          .y(0.889)
                                          .colormode(ColorMode.CT)
                                          .effect("none")
                                          .lightCapabilities(FULL_CAPABILITIES)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_ct_sameColorTemperatureAndBrightness_differentColorMode_xy_true() {
        ScheduledState scheduledState = scheduledState(state().ct(153)
                                                              .bri(200));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .colorTemperature(153)
                                          .brightness(200)
                                          .x(0.4869)
                                          .y(0.3619)
                                          .colormode(ColorMode.XY)
                                          .effect("none")
                                          .lightCapabilities(FULL_CAPABILITIES)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_ct_missingColorTemperatureValue_onCurrent_true() {
        ScheduledState scheduledState = scheduledState(state().ct(153));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .colormode(ColorMode.CT)
                                          .lightCapabilities(FULL_CAPABILITIES)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_xy_sameXAndY_usesXYForComparison_ignoresAdditionalProperties_false() {
        ScheduledState scheduledState = scheduledState(state().x(0.123)
                                                              .y(0.456));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .x(0.123)
                                          .y(0.456)
                                          .brightness(200)
                                          .colorTemperature(30)
                                          .colormode(ColorMode.XY)
                                          .effect("none")
                                          .lightCapabilities(FULL_CAPABILITIES)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_xy_sameXAndY_differentBrightness_true() {
        ScheduledState scheduledState = scheduledState(state().x(0.123)
                                                              .y(0.456)
                                                              .bri(100));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .x(0.123)
                                          .y(0.456)
                                          .brightness(200)
                                          .colormode(ColorMode.XY)
                                          .lightCapabilities(COLOR_LIGHT_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_xy_sameXAndY_noGamut_false() {
        ScheduledState scheduledState = scheduledState(state().x(0.123)
                                                              .y(0.456));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .x(0.123)
                                          .y(0.456)
                                          .brightness(200)
                                          .colormode(ColorMode.XY)
                                          .lightCapabilities(LightCapabilities
                                                  .builder()
                                                  .capabilities(EnumSet.of(Capability.COLOR, Capability.BRIGHTNESS, Capability.ON_OFF))
                                                  .build())
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_xy_similarXAndY_moreDigitsForScheduledState_similarValues_false() {
        ScheduledState scheduledState = scheduledState(state().x(0.5789)
                                                              .y(0.1234));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .x(0.579)
                                          .y(0.123)
                                          .colormode(ColorMode.XY)
                                          .lightCapabilities(COLOR_LIGHT_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_xy_similarXAndY_moreDigitsForLightState_similarValues_false() {
        ScheduledState scheduledState = scheduledState(state().x(0.985)
                                                              .y(0.233));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .x(0.9845)
                                          .y(0.2332)
                                          .colormode(ColorMode.XY)
                                          .lightCapabilities(COLOR_LIGHT_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_xy_differentX_notSimilar_true() {
        ScheduledState scheduledState = scheduledState(state().x(0.600)
                                                              .y(0.334));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .x(0.340)
                                          .y(0.334)
                                          .colormode(ColorMode.XY)
                                          .lightCapabilities(COLOR_LIGHT_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_xy_differentX_stillSimilar_false() {
        ScheduledState scheduledState = scheduledState(state().x(0.606)
                                                              .y(0.334));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .x(0.565)
                                          .y(0.334)
                                          .colormode(ColorMode.XY)
                                          .lightCapabilities(COLOR_LIGHT_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_xy_differentY_notSimilar_true() {
        ScheduledState scheduledState = scheduledState(state().x(0.123)
                                                              .y(0.777));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .x(0.123)
                                          .y(0.355)
                                          .colormode(ColorMode.XY)
                                          .lightCapabilities(COLOR_LIGHT_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_xy_differentY_stillSimilar_false() {
        ScheduledState scheduledState = scheduledState(state().x(0.123)
                                                              .y(0.777));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .x(0.123)
                                          .y(0.626)
                                          .colormode(ColorMode.XY)
                                          .lightCapabilities(COLOR_LIGHT_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_noneEffect_lightHasNoEffect_treatedAsEqual_false() {
        ScheduledState scheduledState = scheduledState(state().effect("none"));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .lightCapabilities(COLOR_LIGHT_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_effect_lightHasNoEffect_true() {
        ScheduledState scheduledState = scheduledState(state().effect("colorloop"));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .lightCapabilities(COLOR_LIGHT_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_colorloop_lightHasSameEffect_false() {
        ScheduledState scheduledState = scheduledState(state().effect("colorloop"));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .effect("colorloop")
                                          .lightCapabilities(COLOR_LIGHT_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_colorloop_lightHasDifferentEffect_true() {
        ScheduledState scheduledState = scheduledState(state().effect("colorloop"));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .effect("none")
                                          .lightCapabilities(COLOR_LIGHT_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_effectWithParameters_different_true() {
        ScheduledState scheduledState = scheduledState(state().effect(Effect.builder()
                                                                            .effect("colorloop")
                                                                            .speed(0.5)
                                                                            .build()));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .effect(Effect.builder()
                                                        .effect("colorloop")
                                                        .speed(0.7)
                                                        .build())
                                          .lightCapabilities(COLOR_LIGHT_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_effectWithParameters_same_false() {
        ScheduledState scheduledState = scheduledState(state().effect(Effect.builder()
                                                                            .effect("colorloop")
                                                                            .x(0.123456)
                                                                            .y(0.789)
                                                                            .build()));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .effect(Effect.builder()
                                                        .effect("colorloop")
                                                        .x(0.123456)
                                                        .y(0.789)
                                                        .build())
                                          .lightCapabilities(COLOR_LIGHT_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_sameBrightness_sameColorMode_ignoresAnyEffectAndOtherProperties_false() {
        ScheduledState scheduledState = scheduledState(state().bri(10));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .brightness(10)
                                          .x(0.123)
                                          .y(0.456)
                                          .colorTemperature(30)
                                          .effect("IGNORED")
                                          .lightCapabilities(FULL_CAPABILITIES)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_sameBrightness_differentColorMode_doesNotMatter_false() {
        ScheduledState scheduledState = scheduledState(state().bri(10));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .brightness(10)
                                          .x(0.123)
                                          .y(0.456)
                                          .colormode(ColorMode.XY)
                                          .lightCapabilities(COLOR_LIGHT_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_defaultCapabilities_sameBrightness_differentColorMode_doesNotMatter_false() {
        ScheduledState scheduledState = scheduledState(state().bri(10));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .brightness(10)
                                          .colormode(ColorMode.CT)
                                          .lightCapabilities(COLOR_TEMPERATURE_LIGHT)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_differentBrightness_aboveThreshold_true() {
        ScheduledState scheduledState = scheduledState(state().bri(10));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .brightness(10 + BRIGHTNESS_THRESHOLD)
                                          .lightCapabilities(BRIGHTNESS_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_differentBrightness_belowThreshold_false() {
        ScheduledState scheduledState = scheduledState(state().bri(10));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .brightness(10 + BRIGHTNESS_THRESHOLD - 1)
                                          .lightCapabilities(BRIGHTNESS_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_onState_stateIsOn_sameState_false() {
        ScheduledState scheduledState = scheduledState(state().on(true));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .lightCapabilities(ON_OFF_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_onState_stateIsOff_differentState_true() {
        ScheduledState scheduledState = scheduledState(state().on(true));
        LightState lightState = LightState.builder()
                                          .on(false)
                                          .lightCapabilities(ON_OFF_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_offState_stateIsOn_differentState_ignored_false() {
        ScheduledState scheduledState = scheduledState(state().on(false));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .lightCapabilities(ON_OFF_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_stateIsOff_noOtherProperties_false() {
        ScheduledState scheduledState = scheduledState(state().bri(10));
        LightState lightState = LightState.builder()
                                          .on(false)
                                          .lightCapabilities(BRIGHTNESS_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_stateIsOff_propertyDifferenceIsIgnoredInComparison_false() {
        ScheduledState scheduledState = scheduledState(state().bri(1));
        LightState lightState = LightState.builder()
                                          .on(false)
                                          .brightness(100)
                                          .lightCapabilities(BRIGHTNESS_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_stateIsOff_propertyDifferenceIsIgnoredInComparison2_false() {
        ScheduledState scheduledState = scheduledState(state().bri(10)
                                                              .ct(153));
        LightState lightState = LightState.builder()
                                          .on(false)
                                          .brightness(123)
                                          .colorTemperature(200)
                                          .colormode(ColorMode.CT)
                                          .lightCapabilities(FULL_CAPABILITIES)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_differentCapabilities_doesNotSupportOnOff_stateHasNoOnStateDefined_false() {
        ScheduledState scheduledState = scheduledState(state().on(true));
        LightState lightState = LightState.builder()
                                          .lightCapabilities(LightCapabilities.builder().build())
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_differentCapabilities_doesNotSupportOnOff_stateHasOnFalseAnyways_false() {
        ScheduledState scheduledState = scheduledState(state().on(true));
        LightState lightState = LightState.builder()
                                          .on(false)
                                          .lightCapabilities(LightCapabilities.builder().build())
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_differentCapabilities_doesNotSupportBrightness_false() {
        ScheduledState scheduledState = scheduledState(state().bri(10));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .lightCapabilities(ON_OFF_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_differentCapabilities_doesNotSupportColorTemperature_false() {
        ScheduledState scheduledState = scheduledState(state().ct(153));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .brightness(100)
                                          .lightCapabilities(BRIGHTNESS_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_differentCapabilities_doesNotSupportColor_xy_false() {
        ScheduledState scheduledState = scheduledState(state().x(1.0)
                                                              .y(0.5));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .brightness(100)
                                          .colorTemperature(100)
                                          .colormode(ColorMode.CT)
                                          .lightCapabilities(COLOR_TEMPERATURE_LIGHT)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_differentColorGamut_targetAlreadyApplied_sameValueAfterConversion_false() {
        ScheduledState scheduledState = scheduledState(state().x(1.0)
                                                              .y(0.5));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .x(0.704)
                                          .y(0.296)
                                          .colormode(ColorMode.XY)
                                          .lightCapabilities(LightCapabilities.builder()
                                                                              .colorGamutType("A")
                                                                              .colorGamut(GAMUT_A)
                                                                              .capabilities(COLOR_LIGHT_ONLY.getCapabilities())
                                                                              .build())
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_differentColorGamut_targetNotYetApplied_sameValueAfterConversion_false() {
        ScheduledState scheduledState = scheduledState(state().x(1.0)
                                                              .y(0.5));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .x(1.0)
                                          .y(0.5)
                                          .colormode(ColorMode.XY)
                                          .lightCapabilities(LightCapabilities.builder()
                                                                              .colorGamutType("A")
                                                                              .colorGamut(GAMUT_A)
                                                                              .capabilities(COLOR_LIGHT_ONLY.getCapabilities())
                                                                              .build())
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_differentColorGamut_targetAppliedAgain_consideredSame_false() {
        ScheduledState scheduledState = scheduledState(state().x(0.1)
                                                              .y(0.5));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .x(0.1828)
                                          .y(0.4892)
                                          .colormode(ColorMode.XY)
                                          .lightCapabilities(LightCapabilities.builder()
                                                                              .colorGamutType("A")
                                                                              .colorGamut(GAMUT_A)
                                                                              .capabilities(COLOR_LIGHT_ONLY.getCapabilities())
                                                                              .build())
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_differentColorGamut_valuesOnlySimilar_consideredSame_false() {
        ScheduledState scheduledState = scheduledState(state().x(0.6024)
                                                              .y(0.3433));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .x(0.6023)
                                          .y(0.3442)
                                          .colormode(ColorMode.XY)
                                          .lightCapabilities(LightCapabilities.builder()
                                                                              .colorGamutType("A")
                                                                              .colorGamut(GAMUT_A)
                                                                              .capabilities(COLOR_LIGHT_ONLY.getCapabilities())
                                                                              .build())
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_differentCtMin_belowTargetMin_consideredSame_false() {
        ScheduledState scheduledState = scheduledState(state().ct(153));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .colorTemperature(200)
                                          .colormode(ColorMode.CT)
                                          .lightCapabilities(LightCapabilities.builder()
                                                                              .ctMin(200)
                                                                              .ctMax(500)
                                                                              .capabilities(COLOR_TEMPERATURE_LIGHT.getCapabilities())
                                                                              .build())
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_differentCtMax_aboveTargetMax_consideredSame_false() {
        ScheduledState scheduledState = scheduledState(state().ct(500));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .colorTemperature(300)
                                          .colormode(ColorMode.CT)
                                          .lightCapabilities(LightCapabilities.builder()
                                                                              .ctMin(200)
                                                                              .ctMax(300)
                                                                              .capabilities(COLOR_TEMPERATURE_LIGHT.getCapabilities())
                                                                              .build())
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_onlyCtMinGiven_ignored_true() {
        ScheduledState scheduledState = scheduledState(state().ct(500));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .colorTemperature(300)
                                          .colormode(ColorMode.CT)
                                          .lightCapabilities(LightCapabilities.builder()
                                                                              .ctMin(200)
                                                                              .capabilities(COLOR_TEMPERATURE_LIGHT.getCapabilities())
                                                                              .build())
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_onlyCtMaxGiven_ignored_true() {
        ScheduledState scheduledState = scheduledState(state().ct(500));
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .colorTemperature(300)
                                          .colormode(ColorMode.CT)
                                          .lightCapabilities(LightCapabilities.builder()
                                                                              .ctMax(300)
                                                                              .capabilities(COLOR_TEMPERATURE_LIGHT.getCapabilities())
                                                                              .build())
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    private ScheduledState scheduledState(ScheduledLightState.ScheduledLightStateBuilder lightState) {
        return ScheduledState.builder()
                             .identifier(new Identifier("ID", "name"))
                             .capabilities(LightCapabilities.builder()
                                                            .ctMin(153)
                                                            .ctMax(500)
                                                            .colorGamutType("C")
                                                            .colorGamut(GAMUT_C)
                                                            .effects(List.of("colorloop"))
                                                            .capabilities(EnumSet.allOf(Capability.class))
                                                            .build())
                             .lightStates(List.of(lightState.build()))
                             .brightnessOverrideThreshold(BRIGHTNESS_THRESHOLD)
                             .colorTemperatureOverrideThresholdKelvin(COLOR_TEMPERATURE_THRESHOLD_KELVIN)
                             .colorOverrideThreshold(COLOR_THRESHOLD)
                             .build();
    }

    private static ScheduledLightState.ScheduledLightStateBuilder state() {
        return ScheduledLightState.builder();
    }

    private static void assertLightStateDiffers(ScheduledState scheduledState, LightState lightState, boolean expected) {
        ZonedDateTime now = ZonedDateTime.now();
        scheduledState.setLastPutCalls(scheduledState.getPutCalls(now, now)); // fake last execution
        assertThat(scheduledState.lightStateDiffers(lightState)).isEqualTo(expected);
    }
}
