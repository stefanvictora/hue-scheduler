package at.sv.hue.color;

public final class ColorComparator {

    private ColorComparator() {
    }

    // CIE standard constants for XYZ->LAB conversion
    private static final double EPSILON = 216.0 / 24389.0; // ~ 0.008856
    private static final double KAPPA   = 24389.0 / 27.0;  // ~ 903.3

    // D65 reference white point (sRGB)
    private static final double REF_X = 95.047;
    private static final double REF_Y = 100.0;
    private static final double REF_Z = 108.883;

    public static boolean colorDiffers(double x, double y, int hue, int sat, double threshold) {
        XYZ xyz1 = xyToXYZ(x, y);
        XYZ xyz2 = hueSatToXYZ(hue, sat);
        return deltaE76(xyz1, xyz2) >= threshold;
    }

    private static XYZ hueSatToXYZ(int hue, int sat) {
        int[] rgb = RGBToHSVConverter.hsvToRgb(hue, sat, 254);
        double[] xyz = RGBToXYConverter.rgbToXYZ(rgb[0], rgb[1], rgb[2]);
        return new XYZ(xyz[0], xyz[1], xyz[2]);
    }

    public static boolean colorDiffers(double x1, double y1, double x2, double y2, Double[][] gamut, double threshold) {
        XY xy1 = getAdjustedXY(x1, y1, gamut);
        XY xy2 = getAdjustedXY(x2, y2, gamut);
        return deltaE76(xy1, xy2) >= threshold;
    }

    private static XY getAdjustedXY(double x, double y, Double[][] gamut) {
        if (gamut == null) {
            return new XY(x, y);
        }
        XYColorGamutCorrection correction = new XYColorGamutCorrection(x, y, gamut);
        return new XY(correction.getX(), correction.getY());
    }

    private static double deltaE76(XY xy1, XY xy2) {
        return deltaE76(xyToXYZ(xy1), xyToXYZ(xy2));
    }

    private static double deltaE76(XYZ xyz1, XYZ xyz2) {
        LAB lab1 = xyzToLab(xyz1);
        LAB lab2 = xyzToLab(xyz2);
        return calculateDistance(lab1, lab2);
    }

    private static XYZ xyToXYZ(XY xy) {
        return xyToXYZ(xy.x, xy.y);
    }

    private static XYZ xyToXYZ(double x, double y) {
        double Y = RGBToXYConverter.findMaximumY(x, y, null);
        double X = (x * Y) / y;
        double Z = ((1 - x - y) * Y) / y;
        return new XYZ(X, Y, Z);
    }

    private static LAB xyzToLab(XYZ colorXYZ) {
        // Normalize by reference white (assumes D65 and same XYZ scaling as REF_*)
        double xr = colorXYZ.X() / REF_X;
        double yr = colorXYZ.Y() / REF_Y;
        double zr = colorXYZ.Z() / REF_Z;

        double fx = fXYZ(xr);
        double fy = fXYZ(yr);
        double fz = fXYZ(zr);

        double L = 116.0 * fy - 16.0;
        double a = 500.0 * (fx - fy);
        double b = 200.0 * (fy - fz);

        return new LAB(L, a, b);
    }

    private static double fXYZ(double t) {
        return (t > EPSILON) ? Math.cbrt(t) : (KAPPA * t + 16.0) / 116.0;
    }

    private static double calculateDistance(LAB lab1, LAB lab2) {
        double deltaL = lab1.L() - lab2.L();
        double deltaA = lab1.a() - lab2.a();
        double deltaB = lab1.b() - lab2.b();
        return Math.sqrt(deltaL * deltaL + deltaA * deltaA + deltaB * deltaB);
    }

    private record XY(double x, double y) {
    }

    private record XYZ(double X, double Y, double Z) {
    }

    private record LAB(double L, double a, double b) {
    }
}
