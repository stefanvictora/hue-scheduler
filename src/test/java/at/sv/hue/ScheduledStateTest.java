package at.sv.hue;

import at.sv.hue.api.Capability;
import at.sv.hue.api.LightCapabilities;
import at.sv.hue.api.LightState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.EnumSet;

import static org.assertj.core.api.Assertions.assertThat;

class ScheduledStateTest {
    
    private static final EnumSet<Capability> COLOR_TEMPERATURE_LIGHT = EnumSet.of(
            Capability.COLOR_TEMPERATURE, Capability.BRIGHTNESS, Capability.ON_OFF);
    private static final EnumSet<Capability> COLOR_LIGHT_ONLY = EnumSet.of(
            Capability.COLOR, Capability.BRIGHTNESS, Capability.ON_OFF);
    private static final EnumSet<Capability> FULL_CAPABILITIES = EnumSet.allOf(Capability.class);
    
    private LightCapabilities defaultCapabilities;

    @BeforeEach
    void setUp() {
        defaultCapabilities = LightCapabilities.builder()
                .ctMin(0)
                .ctMax(100)
                .colorGamut(new Double[0][0])
                .capabilities(EnumSet.allOf(Capability.class))
                .build();
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
                                          .capabilities(COLOR_TEMPERATURE_LIGHT)
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
                                          .capabilities(COLOR_TEMPERATURE_LIGHT)
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
                                          .capabilities(COLOR_TEMPERATURE_LIGHT)
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
                                          .capabilities(FULL_CAPABILITIES)
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
                                          .capabilities(FULL_CAPABILITIES)
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
                                          .capabilities(FULL_CAPABILITIES)
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
                                          .capabilities(FULL_CAPABILITIES)
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
                                          .capabilities(FULL_CAPABILITIES)
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
                                          .capabilities(COLOR_LIGHT_ONLY)
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
                                          .capabilities(COLOR_LIGHT_ONLY)
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
                                          .capabilities(COLOR_LIGHT_ONLY)
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
                                          .capabilities(COLOR_LIGHT_ONLY)
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
                                          .capabilities(COLOR_LIGHT_ONLY)
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
                                          .capabilities(FULL_CAPABILITIES)
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
                                          .capabilities(FULL_CAPABILITIES)
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
                                          .capabilities(COLOR_LIGHT_ONLY)
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
                                          .capabilities(COLOR_LIGHT_ONLY)
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
                                          .capabilities(COLOR_LIGHT_ONLY)
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
                                          .capabilities(COLOR_LIGHT_ONLY)
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
                                          .capabilities(COLOR_LIGHT_ONLY)
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
                                          .capabilities(COLOR_LIGHT_ONLY)
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
                                          .capabilities(COLOR_LIGHT_ONLY)
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
                                          .capabilities(COLOR_LIGHT_ONLY)
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
                                          .capabilities(COLOR_LIGHT_ONLY)
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
                                          .capabilities(COLOR_LIGHT_ONLY)
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
                                          .capabilities(COLOR_LIGHT_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }

    @Test
    void lightStateDiffers_sameBrightness_sameColorMode_ignoresAnyEffectAndOtherProperties_false() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .brightness(10)
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .brightness(10)
                                          .x(0.123)
                                          .y(0.456)
                                          .hue(1000)
                                          .sat(100)
                                          .colorTemperature(30)
                                          .effect("IGNORED")
                                          .capabilities(FULL_CAPABILITIES)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_sameBrightness_differentColorMode_doesNotMatter_false() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .brightness(10)
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .brightness(10)
                                          .x(0.123)
                                          .y(0.456)
                                          .colormode("xy")
                                          .capabilities(COLOR_LIGHT_ONLY)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_defaultCapabilities_sameBrightness_differentColorMode_doesNotMatter_false() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .brightness(10)
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .brightness(10)
                                          .colormode("ct")
                                          .capabilities(COLOR_TEMPERATURE_LIGHT)
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, false);
    }

    @Test
    void lightStateDiffers_differentBrightness_true() {
        ScheduledState scheduledState = ScheduledState.builder()
                                                      .brightness(10)
                                                      .capabilities(defaultCapabilities)
                                                      .build();
        LightState lightState = LightState.builder()
                                          .brightness(200)
                                          .capabilities(EnumSet.of(Capability.BRIGHTNESS, Capability.ON_OFF))
                                          .build();

        assertLightStateDiffers(scheduledState, lightState, true);
    }
    
    @Test
    void lightStateDiffers_differentCapabilities_doesNotSupportBrightness_false() {
        ScheduledState scheduledState = ScheduledState.builder()
                .brightness(10)
                .capabilities(defaultCapabilities)
                .build();
        LightState lightState = LightState.builder()
                .on(true)
                .capabilities(EnumSet.of(Capability.ON_OFF))
                .build();
        
        assertLightStateDiffers(scheduledState, lightState, false);
    }
    
    @Test
    void lightStateDiffers_differentCapabilities_doesNotSupportColorTemperature_false() {
        ScheduledState scheduledState = ScheduledState.builder()
                .ct(100)
                .capabilities(defaultCapabilities)
                .build();
        LightState lightState = LightState.builder()
                .on(true)
                .brightness(100)
                .capabilities(EnumSet.of(Capability.BRIGHTNESS, Capability.ON_OFF))
                .build();
        
        assertLightStateDiffers(scheduledState, lightState, false);
    }
    
    @Test
    void lightStateDiffers_differentCapabilities_doesNotSupportColor_hs_false() {
        ScheduledState scheduledState = ScheduledState.builder()
                .hue(100)
                .sat(100)
                .capabilities(defaultCapabilities)
                .build();
        LightState lightState = LightState.builder()
                .on(true)
                .brightness(100)
                .colorTemperature(100)
                .capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE, Capability.BRIGHTNESS, Capability.ON_OFF))
                .build();
        
        assertLightStateDiffers(scheduledState, lightState, false);
    }
    
    @Test
    void lightStateDiffers_differentCapabilities_doesNotSupportColor_xy_false() {
        ScheduledState scheduledState = ScheduledState.builder()
                .x(1.0)
                .y(0.5)
                .capabilities(defaultCapabilities)
                .build();
        LightState lightState = LightState.builder()
                .on(true)
                .brightness(100)
                .colorTemperature(100)
                .capabilities(EnumSet.of(Capability.COLOR_TEMPERATURE, Capability.BRIGHTNESS, Capability.ON_OFF))
                .build();
        
        assertLightStateDiffers(scheduledState, lightState, false);
    }
    
    private static void assertLightStateDiffers(ScheduledState scheduledState, LightState lightState, boolean expected) {
        scheduledState.setLastPutCall(scheduledState.getPutCall(ZonedDateTime.now())); // fake last execution
        assertThat(scheduledState.lightStateDiffers(lightState)).isEqualTo(expected);
    }
}
