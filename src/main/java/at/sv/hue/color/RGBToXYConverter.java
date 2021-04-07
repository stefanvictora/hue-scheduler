package at.sv.hue.color;

import java.awt.*;

/**
 * See: https://developers.meethue.com/develop/application-design-guidance/color-conversion-formulas-rgb-to-xy-and-back/
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
        return new XYColor(x, y, (int) (Y * 255f));
    }

    private static double gammaCorrect(float color) {
        return (color > 0.04045f) ? Math.pow((color + 0.055f) / (1.0f + 0.055f), 2.4000000953674316f) : (color / 12.92f);
    }

    public static final class XYColor {
        private final double x;
        private final double y;
        private final int brightness;

        public XYColor(double x, double y, int brightness) {
            this.x = x;
            this.y = y;
            this.brightness = brightness;
        }

        public double getX() {
            return x;
        }

        public double getY() {
            return y;
        }

        public int getBrightness() {
            return brightness;
        }
    }
}
