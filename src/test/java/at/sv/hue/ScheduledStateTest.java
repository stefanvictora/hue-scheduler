package at.sv.hue;

import at.sv.hue.api.LightCapabilities;
import at.sv.hue.api.LightState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledStateTest {

    private LightCapabilities defaultCapabilities;

    @BeforeEach
    void setUp() {
        defaultCapabilities = LightCapabilities.builder().ctMin(0).ctMax(100).colorGamut(new Double[0][0]).build();
    }

    @Test
    void lightStateDiffers_ct_sameColorTemperatureAndBrightness_false() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .ct(100)
                                                      .brightness(200)
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .colorTemperature(100)
                                          .brightness(200)
                                          .colormode("ct")
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_ct_differentColorTemperature_sameBrightness_true() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .ct(50)
                                                      .brightness(200)
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .colorTemperature(100)
                                          .brightness(200)
                                          .colormode("ct")
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_ct_sameColorTemperature_differentBrightness_true() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .ct(100)
                                                      .brightness(1)
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .colorTemperature(100)
                                          .brightness(200)
                                          .colormode("ct")
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_ct_sameColorTemperature_differentEffect_true() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .ct(100)
                                                      .brightness(200)
                                                      .effect("none")
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .colorTemperature(100)
                                          .brightness(200)
                                          .x(0.568)
                                          .y(0.889)
                                          .hue(2000)
                                          .sat(100)
                                          .colormode("ct")
                                          .effect("colorloop") // even though this should not happen, if color mode is in "ct"
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_ct_sameColorTemperatureAndBrightness_withAdditionalPropertiesOnLightState_sameColorMode_false() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .ct(100)
                                                      .brightness(200)
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .colorTemperature(100)
                                          .brightness(200)
                                          .x(0.568)
                                          .y(0.889)
                                          .hue(2000)
                                          .sat(100)
                                          .colormode("ct")
                                          .effect("none")
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_ct_sameColorTemperatureAndBrightness_differentColorMode_true() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .ct(100)
                                                      .brightness(200)
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .colorTemperature(100)
                                          .brightness(200)
                                          .x(0.568)
                                          .y(0.889)
                                          .hue(2000)
                                          .sat(100)
                                          .colormode("xy")
                                          .effect("none")
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_xy_sameXAndY_ignoresAdditionalProperties_false() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .x(0.123)
                                                      .y(0.456)
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .x(0.123)
                                          .y(0.456)
                                          .hue(1000)
                                          .sat(100)
                                          .brightness(200)
                                          .colorTemperature(30)
                                          .colormode("xy")
                                          .effect("none")
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_xy_sameXAndY_differentColorMode_true() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .x(0.123)
                                                      .y(0.456)
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .x(0.123)
                                          .y(0.456)
                                          .hue(1000)
                                          .sat(100)
                                          .brightness(200)
                                          .colorTemperature(30)
                                          .colormode("hs")
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_xy_sameXAndY_differentBrightness_true() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .x(0.123)
                                                      .y(0.456)
                                                      .brightness(100)
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .x(0.123)
                                          .y(0.456)
                                          .brightness(200)
                                          .colormode("xy")
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_xy_similarXAndY_moreDigitsForScheduledState_roundedToThreeDigits_sameValues_false() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .x(0.5789)
                                                      .y(0.1234)
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .x(0.579)
                                          .y(0.123)
                                          .colormode("xy")
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_xy_similarXAndY_moreDigitsForLightState_roundedToThreeDigits_sameValues_false() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .x(0.985)
                                                      .y(0.233)
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .x(0.9845)
                                          .y(0.2332)
                                          .colormode("xy")
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_xy_differentX_true() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .x(0.777)
                                                      .y(0.233)
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .x(0.999)
                                          .y(0.233)
                                          .colormode("xy")
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_xy_differentY_true() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .x(0.123)
                                                      .y(0.777)
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .x(0.123)
                                          .y(0.999)
                                          .colormode("xy")
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_hs_sameHueAndSaturation_sameColorMode_ignoresOtherProperties_false() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .hue(1000)
                                                      .sat(50)
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .hue(1000)
                                          .sat(50)
                                          .x(0.123)
                                          .y(0.456)
                                          .brightness(200)
                                          .colorTemperature(30)
                                          .effect("none")
                                          .colormode("hs")
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_hs_sameHueAndSaturation_differentColorMode_true() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .hue(1000)
                                                      .sat(50)
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .hue(1000)
                                          .sat(50)
                                          .brightness(200)
                                          .colorTemperature(30)
                                          .effect("none")
                                          .colormode("ct")
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_hs_sameHueAndSaturation_differentBrightness_true() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .hue(1000)
                                                      .sat(50)
                                                      .brightness(100)
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .hue(1000)
                                          .sat(50)
                                          .brightness(200)
                                          .colormode("hs")
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_hs_sameHueAndSaturation_lightIsInColorLoop_butNoEffectGiven_true() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .hue(1000)
                                                      .sat(50)
                                                      .effect("none")
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .hue(1000)
                                          .sat(50)
                                          .effect("colorloop")
                                          .colormode("hs")
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_hs_differentSaturation_true() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .hue(1000)
                                                      .sat(50)
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .hue(1000)
                                          .sat(100)
                                          .colormode("hs")
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_hs_differentHue_true() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .hue(1000)
                                                      .sat(50)
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .hue(2000)
                                          .sat(50)
                                          .colormode("hs")
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_hs_onlyHue_sameValue_false() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .hue(1000)
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .hue(1000)
                                          .sat(100)
                                          .colormode("hs")
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_hs_onlyHue_differentValue_true() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .hue(2000)
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .hue(1000)
                                          .sat(100)
                                          .colormode("hs")
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_hs_onlySaturation_sameValue_false() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .sat(100)
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .sat(100)
                                          .colormode("hs")
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_hs_onlySaturation_differentValue_true() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .sat(200)
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .sat(100)
                                          .colormode("hs")
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_noneEffect_lightHasNoEffect_treatedAsEqual_false() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .effect("none")
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_colorloop_lightHasSameEffect_false() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .effect("colorloop")
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .effect("colorloop")
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_colorloop_lightHasDifferentEffect_true() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .effect("colorloop")
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .effect("none")
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_noCapabilities_sameBrightness_sameColorMode_ignoresAnyEffectAndOtherProperties_false() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .brightness(10)
                                                      .capabilities(LightCapabilities.NO_CAPABILITIES)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .brightness(10)
                                          .x(0.123)
                                          .y(0.456)
                                          .hue(1000)
                                          .sat(100)
                                          .colorTemperature(30)
                                          .effect("IGNORED")
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_noCapabilities_sameBrightness_differentColorMode_true() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .brightness(10)
                                                      .capabilities(LightCapabilities.NO_CAPABILITIES)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .brightness(10)
                                          .x(0.123)
                                          .y(0.456)
                                          .colormode("xy")
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_noCapabilities_differentBrightness_true() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .brightness(10)
                                                      .capabilities(LightCapabilities.NO_CAPABILITIES)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .brightness(200)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    private static void assertLightStateDiffers(ScheduledState scheduledState, LightState lightState, boolean expected) {
        assertThat(scheduledState.lightStateDiffers(lightState)).isEqualTo(expected);
    }
}
