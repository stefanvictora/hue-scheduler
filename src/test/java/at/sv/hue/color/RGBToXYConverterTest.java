package at.sv.hue.color;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.text.DecimalFormat;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

public class RGBToXYConverterTest {

    private DecimalFormat df;
    private Double[][] gamutA;

    private void assertColor(int r, int g, int b, double x, double y, int brightness) {
        assertColor(r, g, b, x, y, brightness, null);
    }

    private void assertColor(int r, int g, int b, double x, double y, int brightness, Double[][] gamut) {
        RGBToXYConverter.XYColor color = RGBToXYConverter.convert(r, g, b, gamut);
        assertThat("X differs", df.format(color.getX()), is(df.format(x)));
        assertThat("Y differs", df.format(color.getY()), is(df.format(y)));
        assertThat("Brightness differs", color.getBrightness(), is(brightness));
    }

    @BeforeEach
    void setUp() {
        df = new DecimalFormat("#0.000");
        gamutA = new Double[][]{{0.704, 0.296}, {0.2151, 0.7106}, {0.138, 0.08}};
    }

    /**
     * Adapted from <a href="https://github.com/home-assistant/core/blob/dev/tests/util/test_color.py">Home Assistant test_color.py</a>
     * License: Apache-2.0 License
     */
    @Test
    void convert_convertsRGBToXYAndBrightness_handlesGamut() {
        assertColor(0, 0, 0, 0.0, 0.0, 0);
        assertColor(255, 255, 255, 0.323, 0.329, 254);
        assertColor(0, 0, 255, 0.136, 0.04, 12);
        assertColor(0, 0, 255, 0.138, 0.08, 12, gamutA);
        assertColor(0, 255, 0, 0.172, 0.747, 170);
        assertColor(0, 255, 0, 0.215, 0.711, 170, gamutA);
        assertColor(255, 0, 0, 0.701, 0.299, 72);
        assertColor(255, 0, 0, 0.7, 0.299, 72, gamutA);
        assertColor(128, 0, 0, 0.701, 0.299, 15);
    }
}
