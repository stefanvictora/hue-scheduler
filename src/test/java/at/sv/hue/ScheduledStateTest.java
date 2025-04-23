package at.sv.hue;

import at.sv.hue.api.Capability;
import at.sv.hue.api.Identifier;
import at.sv.hue.api.LightCapabilities;
import at.sv.hue.api.LightState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledStateTest {

    private static final Double[][] GAMUT_A = new Double[][]{{0.704, 0.296}, {0.2151, 0.7106}, {0.138, 0.08}};
    private static final Double[][] GAMUT_C = new Double[][]{{0.6915, 0.3083}, {0.17, 0.7}, {0.1532, 0.0475}};

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

    private LightCapabilities defaultCapabilities;

    @BeforeEach
    void setUp() {
        defaultCapabilities = LightCapabilities.builder()
                                               .ctMin(153)
                                               .ctMax(500)
                                               .colorGamutType("C")
                                               .colorGamut(GAMUT_C)
                                               .effects(List.of("colorloop"))
                                               .capabilities(EnumSet.allOf(Capability.class))
                                               .build();
    }

    @Test
    void lightStateDiffers_ct_sameColorTemperatureAndBrightness_false() {
        ScheduledState scheduledState = scheduledState().ct(153)
                                                        .brightness(200)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
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
        ScheduledState scheduledState = scheduledState().ct(153)
                                                        .brightness(200)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
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
        ScheduledState scheduledState = scheduledState().ct(200)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .colorTemperature(204)
                                          .colormode(ColorMode.CT)
                                          .lightCapabilities(COLOR_TEMPERATURE_LIGHT)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_ct_differentColorTemperature_aboveThreshold_false() {
        ScheduledState scheduledState = scheduledState().ct(200)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .colorTemperature(200 + LightStateComparator.COLOR_TEMPERATURE_THRESHOLD)
                                          .colormode(ColorMode.CT)
                                          .lightCapabilities(COLOR_TEMPERATURE_LIGHT)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_ct_sameColorTemperature_differentBrightness_true() {
        ScheduledState scheduledState = scheduledState().ct(200)
                                                        .brightness(1)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
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
        ScheduledState scheduledState = scheduledState().ct(300)
                                                        .brightness(200)
                                                        .effect("none")
                                                        .capabilities(defaultCapabilities)
                                                        .build();
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
        ScheduledState scheduledState = scheduledState().ct(153)
                                                        .brightness(200)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
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
        ScheduledState scheduledState = scheduledState().ct(153)
                                                        .brightness(200)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
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
    void lightStateDiffers_ct_sameCT_differentColorMode_hs_true() {
        ScheduledState scheduledState = scheduledState().ct(153)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .brightness(200)
                                          .colorTemperature(153)
                                          .effect("none")
                                          .colormode(ColorMode.HS)
                                          .lightCapabilities(FULL_CAPABILITIES)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_ct_missingColorTemperatureValue_onCurrent_true() {
        ScheduledState scheduledState = scheduledState().ct(153)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .colormode(ColorMode.CT)
                                          .lightCapabilities(FULL_CAPABILITIES)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_xy_sameXAndY_usesXYForComparison_ignoresAdditionalProperties_false() {
        ScheduledState scheduledState = scheduledState().x(0.123)
                                                        .y(0.456)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
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
    void lightStateDiffers_xy_sameXAndY_hsColorMode_stillUsesXAndY_sameValue_false() {
        ScheduledState scheduledState = scheduledState().x(0.123)
                                                        .y(0.456)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .x(0.123)
                                          .y(0.456)
                                          .brightness(200)
                                          .colorTemperature(30)
                                          .colormode(ColorMode.HS)
                                          .lightCapabilities(FULL_CAPABILITIES)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_xy_sameXAndY_differentBrightness_true() {
        ScheduledState scheduledState = scheduledState().x(0.123)
                                                        .y(0.456)
                                                        .brightness(100)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
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
    void lightStateDiffers_xy_similarXAndY_moreDigitsForScheduledState_similarValues_false() {
        ScheduledState scheduledState = scheduledState().x(0.5789)
                                                        .y(0.1234)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
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
        ScheduledState scheduledState = scheduledState().x(0.985)
                                                        .y(0.233)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
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
        ScheduledState scheduledState = scheduledState().x(0.600)
                                                        .y(0.334)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .x(0.461)
                                          .y(0.273)
                                          .colormode(ColorMode.XY)
                                          .lightCapabilities(COLOR_LIGHT_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_xy_differentX_stillSimilar_false() {
        ScheduledState scheduledState = scheduledState().x(0.606)
                                                        .y(0.334)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
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
        ScheduledState scheduledState = scheduledState()
                .x(0.123)
                .y(0.777)
                .capabilities(defaultCapabilities)
                .build();
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .x(0.123)
                                          .y(0.555)
                                          .colormode(ColorMode.XY)
                                          .lightCapabilities(COLOR_LIGHT_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_xy_differentY_stillSimilar_false() {
        ScheduledState scheduledState = scheduledState().x(0.123)
                                                        .y(0.777)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
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
    void lightStateDiffers_hs_sameColorMode_usesXYForComparison_similarValues_ignoresOtherProperties_false() {
        ScheduledState scheduledState = scheduledState().hue(3936)
                                                        .sat(110)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .x(0.4376) // used
                                          .y(0.3663)
                                          .brightness(200)
                                          .colorTemperature(30)
                                          .effect("none")
                                          .colormode(ColorMode.HS)
                                          .lightCapabilities(FULL_CAPABILITIES)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_hs_differentColorMode_XY_noCurrentHsValues_similarXYValues_false() {
        ScheduledState scheduledState = scheduledState().hue(3936)
                                                        .sat(110)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .x(0.4376)
                                          .y(0.3663)
                                          .brightness(200)
                                          .colormode(ColorMode.XY)
                                          .lightCapabilities(FULL_CAPABILITIES)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_hs_sameHueAndSaturation_butDifferentColorMode_ct_true() {
        ScheduledState scheduledState = scheduledState().hue(1000)
                                                        .sat(50)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .brightness(200)
                                          .colorTemperature(153)
                                          .effect("none")
                                          .colormode(ColorMode.CT)
                                          .lightCapabilities(FULL_CAPABILITIES)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_hs_usesXYForComparison_noCurrentHsValues_true() {
        ScheduledState scheduledState = scheduledState().hue(1000)
                                                        .sat(50)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .x(0.4949)
                                          .y(0.3559)
                                          .colormode(ColorMode.HS)
                                          .lightCapabilities(COLOR_LIGHT_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_hs_usesXYForComparison_differentValue_true() {
        ScheduledState scheduledState = scheduledState().hue(1000)
                                                        .sat(50)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .x(0.9999)
                                          .y(0.9999)
                                          .colormode(ColorMode.HS)
                                          .lightCapabilities(COLOR_LIGHT_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_hs_lightRespondsWithXyColorMode_similarColorValue_false() {
        ScheduledState scheduledState = scheduledState().hue(19470)
                                                        .sat(160)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .x(0.333)
                                          .y(0.490)
                                          .colormode(ColorMode.XY)
                                          .lightCapabilities(FULL_CAPABILITIES)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_xy_lightRespondsWithHsColorMode_sameColorValue_false() {
        ScheduledState scheduledState = scheduledState().hue(1763)
                                                        .sat(205)
                                                        .x(0.6025)
                                                        .y(0.3433)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .x(0.6025)
                                          .y(0.3433)
                                          .colormode(ColorMode.HS)
                                          .lightCapabilities(FULL_CAPABILITIES)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_noneEffect_lightHasNoEffect_treatedAsEqual_false() {
        ScheduledState scheduledState = scheduledState().effect("none")
                                                        .capabilities(defaultCapabilities)
                                                        .build();
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .lightCapabilities(COLOR_LIGHT_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_effect_lightHasNoEffect_true() {
        ScheduledState scheduledState = scheduledState().effect("colorloop")
                                                        .capabilities(defaultCapabilities)
                                                        .build();
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .lightCapabilities(COLOR_LIGHT_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_colorloop_lightHasSameEffect_false() {
        ScheduledState scheduledState = scheduledState().effect("colorloop")
                                                        .capabilities(defaultCapabilities)
                                                        .build();
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .effect("colorloop")
                                          .lightCapabilities(COLOR_LIGHT_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_colorloop_lightHasDifferentEffect_true() {
        ScheduledState scheduledState = scheduledState().effect("colorloop")
                                                        .capabilities(defaultCapabilities)
                                                        .build();
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .effect("none")
                                          .lightCapabilities(COLOR_LIGHT_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_sameBrightness_sameColorMode_ignoresAnyEffectAndOtherProperties_false() {
        ScheduledState scheduledState = scheduledState().brightness(10)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
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
        ScheduledState scheduledState = scheduledState().brightness(10)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
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
        ScheduledState scheduledState = scheduledState().brightness(10)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
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
        ScheduledState scheduledState = scheduledState().brightness(10)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .brightness(10 + LightStateComparator.BRIGHTNESS_THRESHOLD)
                                          .lightCapabilities(BRIGHTNESS_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_differentBrightness_belowThreshold_false() {
        ScheduledState scheduledState = scheduledState().brightness(10)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .brightness(14)
                                          .lightCapabilities(BRIGHTNESS_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_onState_stateIsOn_sameState_false() {
        ScheduledState scheduledState = scheduledState().on(true)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .lightCapabilities(ON_OFF_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_onState_stateIsOff_differentState_true() {
        ScheduledState scheduledState = scheduledState().on(true)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
        LightState lightState = LightState.builder()
                                          .on(false)
                                          .lightCapabilities(ON_OFF_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_onState_stateIsOff_differentState_false() {
        ScheduledState scheduledState = scheduledState().on(false)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .lightCapabilities(ON_OFF_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_onState_false() {
        ScheduledState scheduledState = scheduledState().capabilities(defaultCapabilities)
                                                        .brightness(10)
                                                        .build();
        LightState lightState = LightState.builder()
                                          .on(false)
                                          .lightCapabilities(defaultCapabilities)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_stateHasNoOnStateDefined_false() {
        ScheduledState scheduledState = scheduledState().brightness(1)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
        LightState lightState = LightState.builder()
                                          .on(false)
                                          .brightness(100)
                                          .lightCapabilities(BRIGHTNESS_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_offState_currentHasNoOnState_propertiesDifferIsIgnoredInComparison_false() {
        ScheduledState scheduledState = scheduledState().brightness(10)
                                                        .hue(1000)
                                                        .sat(100)
                                                        .ct(153)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
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
        ScheduledState scheduledState = scheduledState().capabilities(defaultCapabilities)
                                                        .on(true)
                                                        .build();
        LightState lightState = LightState.builder()
                                          .lightCapabilities(LightCapabilities.builder().build())
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_differentCapabilities_doesNotSupportOnOff_stateHasOnFalseAnyways_false() {
        ScheduledState scheduledState = scheduledState().capabilities(defaultCapabilities)
                                                        .on(true)
                                                        .build();
        LightState lightState = LightState.builder()
                                          .on(false)
                                          .lightCapabilities(LightCapabilities.builder().build())
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_differentCapabilities_doesNotSupportBrightness_false() {
        ScheduledState scheduledState = scheduledState().brightness(10)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .lightCapabilities(ON_OFF_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_differentCapabilities_doesNotSupportColorTemperature_false() {
        ScheduledState scheduledState = scheduledState().ct(153)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
        LightState lightState = LightState.builder()
                                          .on(true)
                                          .brightness(100)
                                          .lightCapabilities(BRIGHTNESS_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_differentCapabilities_doesNotSupportColor_hs_false() {
        ScheduledState scheduledState = scheduledState().hue(100)
                                                        .sat(100)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
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
    void lightStateDiffers_differentCapabilities_doesNotSupportColor_xy_false() {
        ScheduledState scheduledState = scheduledState().x(1.0)
                                                        .y(0.5)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
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
        ScheduledState scheduledState = scheduledState().x(1.0)
                                                        .y(0.5)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
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
        ScheduledState scheduledState = scheduledState().x(1.0)
                                                        .y(0.5)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
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
        ScheduledState scheduledState = scheduledState().x(0.1)
                                                        .y(0.5)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
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
        ScheduledState scheduledState = scheduledState().x(0.6024)
                                                        .y(0.3433)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
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
        ScheduledState scheduledState = scheduledState().ct(153)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
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
        ScheduledState scheduledState = scheduledState().ct(500)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
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
        ScheduledState scheduledState = scheduledState().ct(500)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
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
        ScheduledState scheduledState = scheduledState().ct(500)
                                                        .capabilities(defaultCapabilities)
                                                        .build();
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

    private static ScheduledState.ScheduledStateBuilder scheduledState() {
        return ScheduledState.builder().identifier(new Identifier("ID", "name"));
    }

    private static void assertLightStateDiffers(ScheduledState scheduledState, LightState lightState, boolean expected) {
        ZonedDateTime now = ZonedDateTime.now();
        scheduledState.setLastPutCall(scheduledState.getPutCall(now, now)); // fake last execution
        assertThat(scheduledState.lightStateDiffers(lightState)).isEqualTo(expected);
    }
}
