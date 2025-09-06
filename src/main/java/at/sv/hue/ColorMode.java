package at.sv.hue;

import java.util.Locale;

public enum ColorMode {
    CT,
    XY,
    GRADIENT,
    NONE;

    public static ColorMode parse(String value) {
        if (value == null) {
            return NONE;
        }
        for (ColorMode colorMode : ColorMode.values()) {
            if (colorMode.name().equals(value.toUpperCase(Locale.getDefault()))) {
                return colorMode;
            }
        }
        return null;
    }
}
