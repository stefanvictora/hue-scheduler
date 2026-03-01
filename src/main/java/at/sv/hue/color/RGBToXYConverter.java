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

    // D65 linear sRGB <-> XYZ matrices
    // sRGB (linear) -> XYZ
    private static final double M11 = 0.412453, M12 = 0.357580, M13 = 0.180423;
    private static final double M21 = 0.212671, M22 = 0.715160, M23 = 0.072169;
    private static final double M31 = 0.019334, M32 = 0.119193, M33 = 0.950227;

    // XYZ -> sRGB (linear)
    private static final double IM11 = 3.240479, IM12 = -1.537150, IM13 = -0.498535;
    private static final double IM21 = -0.969256, IM22 = 1.875991, IM23 = 0.041556;
    private static final double IM31 = 0.055648, IM32 = -0.204043, IM33 = 1.057311;

    private static final double EPS_Y = 1e-6;

    public static XYColor rgbToXY(int r, int g, int b, Double[][] gamut) {
        double[] XYZ = rgbToXYZ(r, g, b);
        double X = XYZ[0], Y = XYZ[1], Z = XYZ[2];

        double sum = X + Y + Z;
        double x = (sum == 0.0) ? 0.0 : X / sum;
        double y = (sum == 0.0) ? 0.0 : Y / sum;

        XYColorGamutCorrection corr = new XYColorGamutCorrection(x, y, gamut);
        x = corr.getX();
        y = corr.getY();

        double maxY = findMaximumY(x, y); // Y in [0..1]
        int brightness = (int) Math.round((maxY > 0 ? Y / maxY : 0.0) * 255.0);

        return new XYColor(round4(x), round4(y), clampBrightness(brightness));
    }

    private static int clampBrightness(int brightness) {
        return Math.max(1, Math.min(254, brightness)); // 1..254
    }

    private static double[] rgbToXYZ(int r, int g, int b) {
        double red = GammaCorrection.sRGBToLinear(r / 255.0);
        double green = GammaCorrection.sRGBToLinear(g / 255.0);
        double blue = GammaCorrection.sRGBToLinear(b / 255.0);

        double X = M11 * red + M12 * green + M13 * blue;
        double Y = M21 * red + M22 * green + M23 * blue;
        double Z = M31 * red + M32 * green + M33 * blue;
        return new double[]{X, Y, Z};
    }

    /**
     * Largest Y in [0..1] such that xyY -> linear sRGB stays within [0..1].
     * Assumes xy is already gamut-corrected.
     */
    private static double findMaximumY(double x, double y) {
        if (y <= EPS_Y) return 0.0;

        // XYZ at Y=1.0
        double z = 1.0 - x - y;
        double X1 = x / y;
        double Y1 = 1.0;
        double Z1 = z / y;

        // Linear sRGB
        double r = IM11 * X1 + IM12 * Y1 + IM13 * Z1;
        double g = IM21 * X1 + IM22 * Y1 + IM23 * Z1;
        double b = IM31 * X1 + IM32 * Y1 + IM33 * Z1;

        // Small negatives can occur right on triangle edges; clamp to 0 for scaling.
        r = Math.max(0.0, r);
        g = Math.max(0.0, g);
        b = Math.max(0.0, b);

        double maxLin = Math.max(r, Math.max(g, b));
        if (maxLin <= 0.0) return 0.0;   // degenerate/black
        if (maxLin <= 1.0) return 1.0;   // already within gamut at Y=1
        return 1.0 / maxLin;             // scale to bring the peak to 1
    }

    private static double round4(double v) {
        return BigDecimal.valueOf(v).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private static final class GammaCorrection {
        private static final double gamma = 2.4;
        private static final double transition = 0.0031308; // linear domain
        private static final double slope = 12.92;
        private static final double offset = 0.055;

        static double sRGBToLinear(double value) {
            double transitionInv = slope * GammaCorrection.transition; // ~ 0.04045
            if (value <= transitionInv) {
                return value / slope;
            }
            return Math.pow((value + offset) / (1 + offset), gamma);
        }
    }
}
