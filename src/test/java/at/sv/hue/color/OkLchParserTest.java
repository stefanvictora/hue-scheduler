package at.sv.hue.color;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * AI generated
 */
class OkLchParserTest {

    // High-precision reference for oklch(0.7 0.1 250)
    private static final double REF_X = 0.23230679573929788;
    private static final double REF_Y = 0.24985671375977590;
    private static final int REF_BRI = 178; // round(254 * 0.7)

    private static final double EPS_TIGHT = 1e-12;
    private static final double EPS_RELAX = 1e-9;

    @Test
    void reference_oklch_to_xy_and_brightness() {
        XYColor c = OkLchParser.parseOkLch("oklch(0.7 0.1 250)");

        assertThat(c.x()).isCloseTo(REF_X, within(EPS_TIGHT));
        assertThat(c.y()).isCloseTo(REF_Y, within(EPS_TIGHT));
        assertThat(c.bri()).isEqualTo(REF_BRI);
    }

    @Test
    void parses_lenient_angle_units_and_alpha_and_commas() {
        XYColor a = OkLchParser.parseOkLch("oklch(0.7 0.1 250)");              // unitless = degrees
        XYColor b = OkLchParser.parseOkLch("oklch(70% 0.1 250deg)");           // percent + deg
        XYColor c = OkLchParser.parseOkLch("oklch(0.7 0.1 4.36332313rad)");    // radians
        XYColor d = OkLchParser.parseOkLch("oklch(0.7 0.1 277.7777778grad)");  // grads (gon)
        XYColor e = OkLchParser.parseOkLch("oklch(0.7 0.1 0.6944444444turn)"); // turns
        XYColor f = OkLchParser.parseOkLch("oklch(0.7, 0.1, 250)");            // commas allowed
        XYColor g = OkLchParser.parseOkLch("oklch(.7 0.1 250 / 0.3)");         // alpha ignored

        for (XYColor cxy : new XYColor[]{a, b, c, d, e, f, g}) {
            assertThat(cxy.x()).isCloseTo(REF_X, within(EPS_RELAX));
            assertThat(cxy.y()).isCloseTo(REF_Y, within(EPS_RELAX));
            assertThat(cxy.bri()).isEqualTo(REF_BRI);
        }
    }

    @Test
    void hue_normalization_negative_equals_positive() {
        XYColor neg = OkLchParser.parseOkLch("oklch(0.7 0.1 -110)");
        XYColor pos = OkLchParser.parseOkLch("oklch(0.7 0.1 250)");

        assertThat(neg.x()).isCloseTo(pos.x(), within(EPS_TIGHT));
        assertThat(neg.y()).isCloseTo(pos.y(), within(EPS_TIGHT));
        assertThat(neg.bri()).isEqualTo(pos.bri());
    }

    @Test
    void chroma_negative_is_clamped_to_zero_and_yields_neutral_xy() {
        XYColor r = OkLchParser.parseOkLch("oklch(0.65 -0.50 123)");

        // Neutral greys should lie at D65 white chromaticity
        assertThat(r.x()).isCloseTo(0.3127, within(5e-4));
        assertThat(r.y()).isCloseTo(0.3290, within(5e-4));
        // Brightness still mapped from L
        assertThat(r.bri()).isEqualTo((int) Math.round(0.65 * 254.0));
    }

    @Test
    void brightness_mapping_edges_and_rounding() {
        assertThat(OkLchParser.parseOkLch("oklch(0 0 0)").bri()).isEqualTo(1);     // clamped min
        assertThat(OkLchParser.parseOkLch("oklch(1 0 0)").bri()).isEqualTo(254);   // max
        assertThat(OkLchParser.parseOkLch("oklch(0.5 0 0)").bri()).isEqualTo(127); // round(127.0)
        assertThat(OkLchParser.parseOkLch("oklch(50% 0 0)").bri()).isEqualTo(127);
    }

    @Test
    void invalid_inputs_throw() {
        assertThatThrownBy(() -> OkLchParser.parseOkLch("lab(0.7 0.1 250)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OkLchParser.parseOkLch("oklch(0.7 0.1)"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OkLchParser.parseOkLch("oklch( )"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
