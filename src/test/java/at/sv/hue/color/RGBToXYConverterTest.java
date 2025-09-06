package at.sv.hue.color;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class RGBToXYConverterTest {

    private static final Double[][] GAMUT_A = new Double[][]{{0.704, 0.296}, {0.2151, 0.7106}, {0.138, 0.08}};
    private static final Double[][] GAMUT_B = new Double[][]{{0.675, 0.322}, {0.409, 0.518}, {0.167, 0.04}};
    private static final Double[][] GAMUT_C = new Double[][]{{0.6915, 0.3083}, {0.17, 0.7}, {0.1532, 0.0475}};

    /**
     * Adapted from <a href="https://github.com/home-assistant/core/blob/dev/tests/util/test_color.py">Home Assistant test_color.py</a>
     * License: Apache-2.0 License
     */
    @Test
    void convert_convertsRGBToXYAndBrightness_handlesGamut() {
        assertRGBToXY(0, 0, 0, null, 0.0, 0.0, 1);
        assertRGBToXY(255, 255, 255, null, 0.3127, 0.329, 254);
        assertRGBToXY(0, 0, 255, null, 0.15, 0.06, 254);
        assertRGBToXY(0, 255, 0, null, 0.3, 0.6, 254);
        assertRGBToXY(255, 0, 0, null, 0.64, 0.33, 254);
        assertRGBToXY(128, 0, 0, null, 0.64, 0.33, 55);
        assertRGBToXY(0, 0, 255, GAMUT_A, 0.1418, 0.0815, 184); // would be outside gamut
        assertRGBToXY(0, 255, 0, GAMUT_A, 0.3, 0.6, 254); // inside gamut
        assertRGBToXY(255, 0, 0, GAMUT_A, 0.64, 0.33, 254); // inside gamut
    }

    /**
     * Adapted from <a href="https://github.com/home-assistant/core/blob/dev/tests/util/test_color.py">Home Assistant test_color.py</a>
     * License: Apache-2.0 License
     */
    @Test
    void convert_convertsXYToRGB_handlesGamut() {
        assertXYToRGB(1, 0, 255, GAMUT_A, 255, 0, 0);
        assertXYToRGB(0, 1, 255, GAMUT_A, 0, 255, 0);
        assertXYToRGB(0, 0, 255, GAMUT_A, 0, 69, 255);

        assertXYToRGB(0.297, 0.213, 100, null, 152, 91, 168);
        assertXYToRGB(0.311, 0.541, 50, null, 47, 122, 36);
        assertXYToRGB(0.204, 0.142, 200, null, 103, 94, 229);
    }

    @Test
    void convert_RGB_XY() {
        assertRGBToXY(255, 0, 0, null, 0.64, 0.33, 254); // primary red
        assertRGBToXY(128, 0, 0, null, 0.64, 0.33, 55);
        assertRGBToXY(0, 255, 0, null, 0.3, 0.6, 254); // primary green
        assertRGBToXY(0, 0, 255, null, 0.15, 0.06, 254); // primary blue
        assertRGBToXY(0, 0, 0, null, 0.0, 0.0, 1);
        assertRGBToXY(255, 255, 255, null, 0.3127, 0.329, 254);

        // no effect of gamut:
        assertRGBToXY(255, 255, 255, GAMUT_C, 0.3127, 0.329, 254);
        assertRGBToXY(0, 255, 0, GAMUT_C, 0.3, 0.6, 254);
        assertRGBToXY(255, 0, 0, GAMUT_C, 0.64, 0.33, 254);

        // inside C, but outside B
        assertRGBToXY(115, 255, 201, null, 0.2662, 0.3959, 254);
        assertRGBToXY(115, 255, 201, GAMUT_C, 0.2662, 0.3959, 254);
        assertRGBToXY(115, 255, 201, GAMUT_B, 0.3307, 0.3633, 208);

        // outside A, B, and C
        assertRGBToXY(10, 4, 256, null, 0.151, 0.0611, 254);
        assertRGBToXY(10, 4, 256, GAMUT_A, 0.143, 0.0819, 188);
        assertRGBToXY(10, 4, 256, GAMUT_B, 0.1722, 0.0503, 254);
        assertRGBToXY(10, 4, 256, GAMUT_C, 0.1535, 0.061, 254);

        assertRGBToXY(255, 89, 37, null, 0.5746, 0.3633, 254); // red-orange
        assertRGBToXY(235, 255, 67, null, 0.3958, 0.4991, 254);  // green-yellow
        assertRGBToXY(76, 0, 255, null, 0.1683, 0.0701, 254);    // purple
        assertRGBToXY(254, 254, 255, null, 0.3122, 0.3281, 254); // white

        assertRGBToXY(122, 220, 255, GAMUT_C, 0.2368, 0.2867, 254);
        assertRGBToXY(255, 169, 99, GAMUT_C, 0.4551, 0.3988, 254);
        assertRGBToXY(120, 170, 255, GAMUT_C, 0.2228, 0.2216, 254);
        assertRGBToXY(10, 50, 10, GAMUT_C, 0.3027, 0.5427, 8);
    }

    @Test
    void convert_XY_RGB() {
        assertXYToRGB(0.64, 0.33, 255, null, 255, 0, 0); // primary red
        assertXYToRGB(0.3, 0.6, 255, null, 0, 255, 0); // primary green
        assertXYToRGB(0.15, 0.06, 255, null, 0, 0, 255); // primary blue

        assertXYToRGB(0.576, 0.364, 255, null, 255, 89, 35);
        assertXYToRGB(0.5746, 0.3633, 255, null, 255, 89, 37);
        assertXYToRGB(0.3958, 0.4991, 255, null, 235, 255, 67);
        assertXYToRGB(0.1683, 0.0701, 255, null, 76, 0, 255);
        assertXYToRGB(0.3122, 0.3281, 255, null, 254, 254, 255);

        assertXYToRGB(0.35, 0.35, 255, null, 255, 223, 202);
        assertXYToRGB(0.2368, 0.2867, 255, GAMUT_C, 122, 220, 255);
    }

    private void assertRGBToXY(int r, int g, int b, Double[][] gamut, double x, double y, int brightness) {
        RGBToXYConverter.XYColor color = RGBToXYConverter.rgbToXY(r, g, b, gamut);
        assertThat(color.x()).isEqualByComparingTo(x);
        assertThat(color.y()).isEqualByComparingTo(y);
        assertThat(color.brightness()).isEqualTo(brightness);
    }

    private void assertXYToRGB(double x, double y, int brightness, Double[][] gamut, int r, int g, int b) {
        int[] rgb = RGBToXYConverter.xyToRgb(x, y, brightness, gamut);
        assertThat(rgb[0]).isEqualTo(r);
        assertThat(rgb[1]).isEqualTo(g);
        assertThat(rgb[2]).isEqualTo(b);
    }
}
