package at.sv.hue;

import java.awt.*;

/**
 * See: https://developers.meethue.com/develop/application-design-guidance/color-conversion-formulas-rgb-to-xy-and-back/
 */
final class RBGToXYConverter {

    private RBGToXYConverter() {
    }

    public static XYColor convert(String hex) {
        Color color = Color.decode(hex);
        double red = gammaCorrect(color.getRed() / 255f);
        double green = gammaCorrect(color.getGreen() / 255f);
        double blue = gammaCorrect(color.getBlue() / 255f);
        double X = red * 0.664511f + green * 0.154324f + blue * 0.162028f;
        double Y = red * 0.283881f + green * 0.668433f + blue * 0.047685f;
        double Z = red * 0.000088f + green * 0.072310f + blue * 0.986039f;
        double x = X / (X + Y + Z);
        double y = Y / (X + Y + Z);
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
