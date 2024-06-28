package at.sv.hue.color;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * See: <a href="https://developers.meethue.com/develop/application-design-guidance/color-conversion-formulas-rgb-to-xy-and-back/">developers.meethue.com</a>
 * And: <a href="https://viereck.ch/hue-xy-rgb/">RGB/xy Color Conversion -- Thomas Lochmatter</a>
 */
public final class RGBToXYConverter {

    private RGBToXYConverter() {
    }

    public static XYColor rgbToXY(int r, int g, int b, Double[][] gamut) {
        double[] XYZ = rgbToXYZ(r, g, b);
        double X = XYZ[0];
        double Y = XYZ[1];
        double Z = XYZ[2];

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
            XYColorGamutCorrection correction = new XYColorGamutCorrection(x, y, gamut);
            x = correction.getX();
            y = correction.getY();
        }
        double maxY = findMaximumY(x, y, gamut);
        int brightness = (int) Math.round(Y / maxY * 255);
        return new XYColor(getDoubleValueWithFixedPrecision(x), getDoubleValueWithFixedPrecision(y),
                clampBrightness(brightness));
    }

    private static int clampBrightness(int brightness) {
        return Math.max(1, Math.min(254, brightness)); // 1..254
    }

    public static double[] rgbToXYZ(int r, int g, int b) {
        double red = GammaCorrection.sRGBToLinear(r / 255f);
        double green = GammaCorrection.sRGBToLinear(g / 255f);
        double blue = GammaCorrection.sRGBToLinear(b / 255f);
        double X = red * 0.412453 + green * 0.357580 + blue * 0.180423;
        double Y = red * 0.212671 + green * 0.715160 + blue * 0.072169;
        double Z = red * 0.019334 + green * 0.119193 + blue * 0.950227;
        return new double[]{X, Y, Z};
    }

    /**
     * Adapted from <a href="https://github.com/home-assistant/core">Home Assistant Core</a>
     * Apache License
     * Version 2.0, January 2004
     * http://www.apache.org/licenses/
     * <br>
     * And: <a href="https://viereck.ch/hue-xy-rgb/">RGB/xy Color Conversion</a>
     * Author: Thomas Lochmatter, https://viereck.ch/thomas
     * License: MIT
     */
    public static int[] xyToRgb(double x, double y, int brightness, Double[][] gamut) { // todo: check if it makes sense to use customizable brightness
        double maxY = findMaximumY(x, y, gamut);
        double[] rgb = xyYToRgb(x, y, maxY * brightness / 255, gamut);
        int r = Math.min((int) (rgb[0] * 255), 255);
        int g = Math.min((int) (rgb[1] * 255), 255);
        int b = Math.min((int) (rgb[2] * 255), 255);
        return new int[]{r, g, b};
    }

    public static double findMaximumY(double x, double y, Double[][] gamut) {
        double bri = 1.0;
        for (int i = 0; i < 10; i++) {
            double[] rgb = xyYToRgb(x, y, bri, gamut);
            double maxComponent = Math.max(rgb[0], Math.max(rgb[1], rgb[2]));
            if (maxComponent > 1) {
                bri /= maxComponent;
            }
        }
        return bri;
    }

    private static double[] xyYToRgb(double x, double y, double Y, Double[][] gamut) {
        if (gamut != null) {
            XYColorGamutCorrection correction = new XYColorGamutCorrection(x, y, gamut);
            x = correction.getX();
            y = correction.getY();
        }
        if (Y == 0.0) {
            return new double[]{0.0, 0.0, 0.0};
        }
        if (y == 0.0) {
            y += 0.00000000001;
        }
        double z = 1 - x - y;
        double X = (Y / y) * x;
        double Z = (Y / y) * z;
        double red = X * 3.240479 - Y * 1.53715 - Z * 0.498535;
        double green = -X * 0.969256 + Y * 1.875991 + Z * 0.041556;
        double blue = X * 0.055648 - Y * 0.204043 + Z * 1.057311;
        double r = GammaCorrection.linearToSRGB(red);
        double g = GammaCorrection.linearToSRGB(green);
        double b = GammaCorrection.linearToSRGB(blue);
        return new double[]{Math.max(0, r), Math.max(0, g), Math.max(0, b)};
    }

    private static double getDoubleValueWithFixedPrecision(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    public record XYColor(double x, double y, int brightness) {
    }

    private static final class GammaCorrection {
        private static final double gamma = 2.4;
        private static final double transition = 0.0031308;
        private static final double slope = 12.92;
        private static final double offset = 0.055;

        public static double linearToSRGB(double value) {
            if (value <= transition) {
                return slope * value;
            } else {
                return (1 + offset) * Math.pow(value, 1 / gamma) - offset;
            }
        }

        public static double sRGBToLinear(double value) {
            double transitionInv = linearToSRGB(transition);
            if (value <= transitionInv) {
                return value / slope;
            } else {
                return Math.pow((value + offset) / (1 + offset), gamma);
            }
        }
    }
}
