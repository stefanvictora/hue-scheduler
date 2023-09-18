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

    private static void assertRGBFromAndToKelvin(int kelvin, int red, int green, int blue) {
        int mired = 1_000_000 / kelvin;
        assertThat(CTToRGBConverter.approximateRGBFromMired(mired)).containsExactly(red, green, blue);
        assertThat(CTToRGBConverter.approximateMiredFromRGB(red, green, blue)).isEqualTo(mired);
    }
}
