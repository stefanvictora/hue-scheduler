package at.sv.hue.color;

import lombok.Data;

import java.awt.*;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * See: <a href="https://developers.meethue.com/develop/application-design-guidance/color-conversion-formulas-rgb-to-xy-and-back/">developers.meethue.com</a>
 */
public final class RGBToXYConverter {

    private RGBToXYConverter() {
    }

    public static XYColor convert(String hex, Double[][] gamut) {
        Color color = Color.decode(hex);
        return convert(color.getRed(), color.getGreen(), color.getBlue(), gamut);
    }

    public static XYColor convert(String r, String g, String b, Double[][] gamut) {
        return convert(parseInt(r), parseInt(g), parseInt(b), gamut);
    }

    private static int parseInt(String s) {
        return Integer.parseInt(s.trim());
    }

    public static XYColor convert(int r, int g, int b, Double[][] gamut) {
        double red = gammaCorrect(r / 255f);
        double green = gammaCorrect(g / 255f);
        double blue = gammaCorrect(b / 255f);
        double X = red * 0.664511f + green * 0.154324f + blue * 0.162028f;
        double Y = red * 0.283881f + green * 0.668433f + blue * 0.047685f;
        double Z = red * 0.000088f + green * 0.072310f + blue * 0.986039f;
        double sum = X + Y + Z;
        double x;
        double y;
        if (sum == 0.0) {
            x = 0.0;
            y = 0.0;
        } else {
            x = X / sum;
            y = Y / sum;
        }
        if (gamut != null) {
            Point point = new XYColorGamutCorrection(x, y, gamut).adjustIfNeeded();
            x = point.x;
            y = point.y;
        }
        return new XYColor(getDoubleValueWithFixedPrecision(x), getDoubleValueWithFixedPrecision(y), (int) (Y * 255f));
    }

    private static double gammaCorrect(float color) {
        return (color > 0.04045f) ? Math.pow((color + 0.055f) / (1.0f + 0.055f), 2.4000000953674316f) : (color / 12.92f);
    }

    /**
     * Adapted from <a href="https://github.com/home-assistant/core">Home Assistant Core</a>
     * Apache License
     * Version 2.0, January 2004
     * http://www.apache.org/licenses/
     */
    public static int[] convert(double x, double y, int brightness, Double[][] gamut) {
        if (gamut != null) {
            Point point = new XYColorGamutCorrection(x, y, gamut).adjustIfNeeded();
            x = point.x;
            y = point.y;
        }
        double Y = brightness / 255.0;
        if (Y == 0.0) {
            return new int[]{0, 0, 0};
        }
        if (y == 0.0) {
            y += 0.00000000001;
        }
        double z = 1 - x - y;
        double X = (Y / y) * x;
        double Z = (Y / y) * z;
        double red = X * 1.656492 - Y * 0.354851 - Z * 0.255038;
        double green = -X * 0.707196 + Y * 1.655397 + Z * 0.036152;
        double blue = X * 0.051713 - Y * 0.121364 + Z * 1.011530;
        red = inverseGammaCorrect(red);
        green = inverseGammaCorrect(green);
        blue = inverseGammaCorrect(blue);
        red = Math.max(0, red);
        green = Math.max(0, green);
        blue = Math.max(0, blue);
        double maxComponent = Math.max(red, Math.max(green, blue));
        if (maxComponent > 1) {
            red /= maxComponent;
            green /= maxComponent;
            blue /= maxComponent;
        }
        return new int[]{(int) (red * 255), (int) (green * 255), (int) (blue * 255)};
    }

    private static double inverseGammaCorrect(double color) {
        return (color <= 0.0031308) ? 12.92 * color : (1.0 + 0.055) * Math.pow(color, 1.0 / 2.4) - 0.055;
    }

    private static double getDoubleValueWithFixedPrecision(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    @Data
    public static final class XYColor {
        private final double x;
        private final double y;
        private final int brightness;
    }
}
