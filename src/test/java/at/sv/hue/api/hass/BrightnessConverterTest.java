package at.sv.hue.api.hass;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BrightnessConverterTest {

    @Test
    void specialCases_minBrightness() {
        assertHueToHass(1, 1); // we can't send brightness 0 to hass
        assertHassToHue(0, 1); // brightness zero is still a valid value for HA
    }

    @Test
    void hueToHassAndBack() {
        assertHueToHassAndBack(2, 1);
        assertHueToHassAndBack(3, 2);
        assertHueToHassAndBack(50, 49);
        assertHueToHassAndBack(51, 50);
        assertHueToHassAndBack(60, 59);
        assertHueToHassAndBack(64, 63);
        assertHueToHassAndBack(65, 65);
        assertHueToHassAndBack(69, 69);
        assertHueToHassAndBack(100, 100);
        assertHueToHassAndBack(127, 127);
        assertHueToHassAndBack(128, 128);
        assertHueToHassAndBack(129, 129);
        assertHueToHassAndBack(191, 192);
        assertHueToHassAndBack(192, 193); // todo: the HA API returns actually 192
        assertHueToHassAndBack(199, 200);
        assertHueToHassAndBack(200, 201);
        assertHueToHassAndBack(253, 254);
        assertHueToHassAndBack(254, 255);
    }

    private static void assertHueToHassAndBack(int hue, int hass) {
        assertHueToHass(hue, hass);
        assertHassToHue(hass, hue);
    }

    private static void assertHassToHue(int hass, int hue) {
        assertThat(BrightnessConverter.hassToHueBrightness(hass)).isEqualTo(hue);
    }

    private static void assertHueToHass(int hue, int hass) {
        assertThat(BrightnessConverter.hueToHassBrightness(hue)).isEqualTo(hass);
    }
}
