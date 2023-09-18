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

    private static void assertRGBToHSV(int red, int green, int blue, int hue, int saturation, int brightness) {
        assertThat(RGBToHSVConverter.rgbToHsv(red, green, blue)).containsExactly(hue, saturation, brightness);
    }
}
