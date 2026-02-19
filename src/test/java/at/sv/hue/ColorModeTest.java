package at.sv.hue;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ColorModeTest {

    @Test
    void parse_null_returnsNONE() {
        assertColorMode(null, ColorMode.NONE);
    }

    @Test
    void parse_unknownValue_returnsNull() {
        assertColorMode("UNKNOWN", null);
    }

    @Test
    void parse_returnsCorrectValues_caseInsensitive() {
        assertColorMode("CT", ColorMode.CT);
        assertColorMode("ct", ColorMode.CT);
        assertColorMode("XY", ColorMode.XY);
        assertColorMode("xy", ColorMode.XY);
        assertColorMode("gradient", ColorMode.GRADIENT);
        assertColorMode("GRADIENT", ColorMode.GRADIENT);
    }

    private static void assertColorMode(String name, ColorMode colorMode) {
        assertThat(ColorMode.parse(name)).isEqualTo(colorMode);
    }
}
