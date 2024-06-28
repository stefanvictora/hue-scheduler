package at.sv.hue.color;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RGBToHSVConverterTest {

    @Test
    void rgbToHsv() {
        assertRGBToHSV(255, 255, 255, 0, 0, 254);
        assertRGBToHSV(0, 0, 0, 0, 0, 0);
        assertRGBToHSV(0, 0, 255, 43690, 254, 254);
        assertRGBToHSV(0, 255, 0, 21845, 254, 254);
        assertRGBToHSV(255, 0, 0, 0, 254, 254);
    }

    @Test
    void convert_RGB_HS() {
        assertRGBToHSV(255, 0, 0, 0, 254, 254);   // red
        assertRGBToHSV(0, 255, 0, 21845, 254, 254); // green
        assertRGBToHSV(0, 0, 255, 43690, 254, 254); // blue
        assertRGBToHSV(255, 255, 255, 0, 0, 254); // white
    }

    @Test
    void convert_HS_RGB() {
        assertHsToRgb(0, 254, 254, 255, 0, 0);      // red
        assertHsToRgb(21845, 254, 254, 0, 255, 0);  // green
        assertHsToRgb(43690, 254, 254, 0, 0, 255);  // blue
        assertHsToRgb(0, 0, 254, 255, 255, 255);    // white
    }

    private static void assertRGBToHSV(int red, int green, int blue, int hue, int saturation, int brightness) {
        assertThat(RGBToHSVConverter.rgbToHsv(red, green, blue)).containsExactly(hue, saturation, brightness);
    }

    private static void assertHsToRgb(int hue, int saturation, int brightness, int red, int green, int blue) {
        assertThat(RGBToHSVConverter.hsvToRgb(hue, saturation, brightness)).containsExactly(red, green, blue);
    }
}
