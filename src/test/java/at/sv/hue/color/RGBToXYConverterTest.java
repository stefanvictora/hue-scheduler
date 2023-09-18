package at.sv.hue.color;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class RGBToXYConverterTest {

    private Double[][] gamutA;

    @BeforeEach
    void setUp() {
        gamutA = new Double[][]{{0.704, 0.296}, {0.2151, 0.7106}, {0.138, 0.08}};
    }

    /**
     * Adapted from <a href="https://github.com/home-assistant/core/blob/dev/tests/util/test_color.py">Home Assistant test_color.py</a>
     * License: Apache-2.0 License
     */
    @Test
    void convert_convertsRGBToXYAndBrightness_handlesGamut() {
        assertRGBToXY(0, 0, 0, null, 0.0, 0.0, 0);
        assertRGBToXY(255, 255, 255, null, 0.3227, 0.329, 254);
        assertRGBToXY(0, 0, 255, null, 0.1355, 0.0399, 12);
        assertRGBToXY(0, 255, 0, null, 0.1724, 0.7468, 170);
        assertRGBToXY(255, 0, 0, null, 0.7006, 0.2993, 72);
        assertRGBToXY(128, 0, 0, null, 0.7006, 0.2993, 15);
        assertRGBToXY(0, 0, 255, gamutA, 0.138, 0.08, 12);
        assertRGBToXY(0, 255, 0, gamutA, 0.2151, 0.7106, 170);
        assertRGBToXY(255, 0, 0, gamutA, 0.7004, 0.2991, 72);
    }

    /**
     * Adapted from <a href="https://github.com/home-assistant/core/blob/dev/tests/util/test_color.py">Home Assistant test_color.py</a>
     * License: Apache-2.0 License
     */
    @Test
    void convert_convertsXYToRGB_handlesGamut() {
        assertXYToRGB(1, 0, 255, null, 255, 0, 60);
        assertXYToRGB(0, 1, 255, null, 0, 255, 0);
        assertXYToRGB(0, 0, 255, null, 0, 63, 255);
        assertXYToRGB(1, 0, 255, gamutA, 255, 0, 3);
        assertXYToRGB(0, 1, 255, gamutA, 82, 255, 0);
        assertXYToRGB(0, 0, 255, gamutA, 9, 85, 255);
    }

    private void assertRGBToXY(int r, int g, int b, Double[][] gamut, double x, double y, int brightness) {
        RGBToXYConverter.XYColor color = RGBToXYConverter.convert(r, g, b, gamut);
        assertThat(color.getX()).isEqualByComparingTo(x);
        assertThat(color.getY()).isEqualByComparingTo(y);
        assertThat(color.getBrightness()).isEqualTo(brightness);
    }

    private void assertXYToRGB(double x, double y, int brightness, Double[][] gamut, int r, int g, int b) {
        int[] rgb = RGBToXYConverter.convert(x, y, brightness, gamut);
        assertThat(rgb[0]).isEqualTo(r);
        assertThat(rgb[1]).isEqualTo(g);
        assertThat(rgb[2]).isEqualTo(b);
    }
}
