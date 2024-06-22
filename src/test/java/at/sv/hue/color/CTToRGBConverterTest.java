package at.sv.hue.color;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CTToRGBConverterTest {

    @Test
    void testApproximationFromAndToKelvin() {
        assertRGBFromAndToKelvin(6700, 254, 248, 255); // more blue
        assertRGBFromAndToKelvin(6600, 255, 255, 255); // "perfect" white
        assertRGBFromAndToKelvin(6500, 255, 254, 250); // more red
        assertRGBFromAndToKelvin(5000, 255, 228, 205);
        assertRGBFromAndToKelvin(4000, 255, 205, 166);
        assertRGBFromAndToKelvin(3000, 255, 177, 109);
        assertRGBFromAndToKelvin(2500, 255, 159, 70);
        assertRGBFromAndToKelvin(2000, 255, 136, 13); // least blue
    }

    @Test
    void convert_RGB_CT() {
        assertRgbToCt(255, 147, 41, 454); // approx warm white
        assertRgbToCt(255, 255, 255, 151); // white
        assertRgbToCt(255, 56, 0, 1000);  // red todo: too big of a value
    }

    @Test
    void convert_CT_RGB() {
        assertMiredToRgb(454, 255, 146, 39); // warm white
        assertMiredToRgb(151, 255, 255, 255); // white
        assertMiredToRgb(1000, 255, 67, 0);  // red todo: too big of a value
    }

    private static void assertRGBFromAndToKelvin(int kelvin, int red, int green, int blue) {
        int mired = 1_000_000 / kelvin;
        assertMiredToRgb(mired, red, green, blue);
        assertRgbToCt(red, green, blue, mired);
    }

    private static void assertMiredToRgb(int mired, int r, int g, int b) {
        assertThat(CTToRGBConverter.approximateRGBFromMired(mired)).containsExactly(r, g, b);
    }

    private static void assertRgbToCt(int r, int g, int b, int mired) {
        assertThat(CTToRGBConverter.approximateMiredFromRGB(r, g, b)).isEqualTo(mired);
    }
}
