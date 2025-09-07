package at.sv.hue.color;

/**
 * AI generated
 */
public final class OkLabUtil {
    private OkLabUtil() {
    }

    private static final double CHROMA_MIN = 0.05;
    private static final double WHITE_Y = 1.0;

    public static double[] lerpOKLabXY(double x0, double y0, double x1, double y1, double t, Double[][] gamut) {
        if (gamut == null) {
            return lerpOKLabXY(x0, y0, x1, y1, t);
        }

        double[] s = clampXY(x0, y0, gamut);
        double[] e = clampXY(x1, y1, gamut);
        double[] xy = lerpOKLabXY(s[0], s[1], e[0], e[1], t);
        return clampXY(xy[0], xy[1], gamut);
    }

    private static double[] clampXY(double x, double y, Double[][] gamut) {
        XYColorGamutCorrection c = new XYColorGamutCorrection(x, y, gamut);
        return new double[]{c.getX(), c.getY()};
    }

    /**
     * OKLab default; switches to OKLCH shortest-arc hue only when both are sufficiently chromatic.
     */
    private static double[] lerpOKLabXY(double x0, double y0, double x1, double y1, double t) {
        double[] XYZ0 = xyY_to_XYZ(x0, y0, WHITE_Y);
        double[] XYZ1 = xyY_to_XYZ(x1, y1, WHITE_Y);

        double[] okLab0 = XYZ_to_OKLab(XYZ0[0], XYZ0[1], XYZ0[2]);
        double[] okLab1 = XYZ_to_OKLab(XYZ1[0], XYZ1[1], XYZ1[2]);

        double c0 = Math.hypot(okLab0[1], okLab0[2]);
        double c1 = Math.hypot(okLab1[1], okLab1[2]);

        double[] ok;
        if (c0 < CHROMA_MIN && c1 < CHROMA_MIN) {
            ok = lerpOkLab(okLab0, okLab1, t);
        } else {
            ok = lerpOklchWithHueRules(okLab0, okLab1, c0, c1, t); // hue from chromatic endpoint if only one chromatic; else shortest arc
        }

        double[] XYZ = OKLab_to_XYZ(ok[0], ok[1], ok[2]);
        return XYZ_to_xy(XYZ[0], XYZ[1], XYZ[2]);
    }

    /**
     * xyY -> XYZ with Yref in [0..1], recommended Yref=1.0 for chromaticity-only work
     */
    public static double[] xyY_to_XYZ(double x, double y, double Yref) {
        if (y <= 0) {
            return new double[]{0, 0, 0};
        }
        double X = (x * Yref) / y;
        double Z = ((1 - x - y) * Yref) / y;
        return new double[]{X, Yref, Z};
    }

    /**
     * OKLab <-> XYZ (D65), expects XYZ normalized so that white has Y=1.0
     */
    public static double[] XYZ_to_OKLab(double X, double Y, double Z) {
        double l = 0.8189330101 * X + 0.3618667424 * Y - 0.1288597137 * Z;
        double m = 0.0329845436 * X + 0.9293118715 * Y + 0.0361456387 * Z;
        double s = 0.0482003018 * X + 0.2643662691 * Y + 0.6338517070 * Z;

        double l_ = Math.cbrt(l), m_ = Math.cbrt(m), s_ = Math.cbrt(s);

        double L = 0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_;
        double a = 1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_;
        double b = 0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_;
        return new double[]{L, a, b};
    }

    private static double[] OKLab_to_XYZ(double L, double a, double b) {
        double l_ = L + 0.3963377774 * a + 0.2158037573 * b;
        double m_ = L - 0.1055613458 * a - 0.0638541728 * b;
        double s_ = L - 0.0894841775 * a - 1.2914855480 * b;

        double l = l_ * l_ * l_, m = m_ * m_ * m_, s = s_ * s_ * s_;

        double X = 1.2270138511 * l - 0.5577999807 * m + 0.2812561490 * s;
        double Y = -0.0405801784 * l + 1.1122568696 * m - 0.0716766787 * s;
        double Z = -0.0763812845 * l - 0.4214819784 * m + 1.5861632204 * s;
        return new double[]{X, Y, Z};
    }

    private static double[] XYZ_to_xy(double X, double Y, double Z) {
        double sum = X + Y + Z;
        if (sum <= 1e-12) {
            return new double[]{0.0, 0.0};
        }
        return new double[]{X / sum, Y / sum};
    }

    /**
     * Linear interpolation in Cartesian OKLab space.
     */
    private static double[] lerpOkLab(double[] a, double[] b, double t) {
        return new double[]{
                lerp(a[0], b[0], t),
                lerp(a[1], b[1], t),
                lerp(a[2], b[2], t)};
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    /**
     * Interpolation in polar OKLCH space using shortest-arc hue and near-neutral fallback.
     * Returns [L, a, b].
     */
    private static double[] lerpOklchWithHueRules(double[] ok0, double[] ok1, double c0, double c1, double t) {
        double L = lerp(ok0[0], ok1[0], t);
        double h0 = Math.atan2(ok0[2], ok0[1]);
        double h1 = Math.atan2(ok1[2], ok1[1]);

        double C = lerp(c0, c1, t);
        double h;
        if (c0 < CHROMA_MIN ^ c1 < CHROMA_MIN) { // exactly one chromatic
            // use hue of the chromatic endpoint
            if (c0 >= CHROMA_MIN) {
                h = h0;
            } else {
                h = h1;
            }
        } else {
            // both chromatic: shortest-arc interpolation
            double dh = shortestAngle(h1 - h0);
            if (Math.abs(Math.abs(dh) - Math.PI) < 1e-9) {
                // tie: pick direction that keeps hue closer to the higher-chroma endpoint
                dh = (c1 >= c0) ? Math.abs(dh) : -Math.abs(dh);
            }
            h = h0 + t * dh;
        }

        double a = C * Math.cos(h);
        double b = C * Math.sin(h);
        return new double[]{L, a, b};
    }

    private static double shortestAngle(double d) {
        double twoPi = Math.PI * 2.0;
        d = (d + Math.PI) % twoPi;
        if (d < 0) {
            d += twoPi;
        }
        return d - Math.PI;
    }

    /**
     * ΔE in OKLab for two xy chromaticities (Y fixed to 1).
     */
    public static double deltaE_OKLab(double x0, double y0, double x1, double y1) {
        double[] XYZ0 = xyY_to_XYZ(x0, y0, WHITE_Y);
        double[] XYZ1 = xyY_to_XYZ(x1, y1, WHITE_Y);
        double[] okLab0 = XYZ_to_OKLab(XYZ0[0], XYZ0[1], XYZ0[2]);
        double[] okLab1 = XYZ_to_OKLab(XYZ1[0], XYZ1[1], XYZ1[2]);
        return euclidean(okLab0, okLab1);
    }

    private static double euclidean(double[] a, double[] b) {
        double d0 = a[0] - b[0], d1 = a[1] - b[1], d2 = a[2] - b[2];
        return Math.sqrt(d0 * d0 + d1 * d1 + d2 * d2);
    }
}
