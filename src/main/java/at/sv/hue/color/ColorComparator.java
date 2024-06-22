package at.sv.hue.color;

public final class ColorComparator {

    private static final double NOTABLE_DIFFERENCE = 2.3;

    private ColorComparator() {
    }

    // D65 reference white point
    private static final double REF_X = 95.047;
    private static final double REF_Y = 100.0;
    private static final double REF_Z = 108.883;

    public static boolean colorDiffers(double x, double y, int hue, int sat) {
        XYZ xyz1 = xyToXYZ(x, y);
        XYZ xyz2 = hueSatToXYZ(hue, sat);

        return colorDiffers(xyz1, xyz2);
    }

    private static XYZ hueSatToXYZ(int hue, int sat) {
        int[] rgb = RGBToHSVConverter.hsvToRgb(hue, sat, 254);
        double[] xyz = RGBToXYConverter.rgbToXYZ(rgb[0], rgb[1], rgb[2]);
        return new XYZ(xyz[0], xyz[1], xyz[2]);
    }

    public static boolean colorDiffers(double x1, double y1, double x2, double y2, Double[][] gamut) {
        XY xy1 = getAdjustedXY(x1, y1, gamut);
        XY xy2 = getAdjustedXY(x2, y2, gamut);

        return colorDiffers(xy1, xy2);
    }

    private static XY getAdjustedXY(double x, double y, Double[][] gamut) {
        if (gamut == null) {
            return new XY(x, y);
        }
        XYColorGamutCorrection correction = new XYColorGamutCorrection(x, y, gamut);
        return new XY(correction.getX(), correction.getY());
    }

    private static boolean colorDiffers(XY xy1, XY xy2) {
        XYZ xyz1 = xyToXYZ(xy1);
        XYZ xyz2 = xyToXYZ(xy2);
        return colorDiffers(xyz1, xyz2);
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

    private static boolean colorDiffers(XYZ xyz1, XYZ xyz2) {
        LAB lab1 = xyzToLab(xyz1);
        LAB lab2 = xyzToLab(xyz2);

        double distance = calculateDistance(lab1, lab2);
        return distance >= NOTABLE_DIFFERENCE;
    }

    private static LAB xyzToLab(XYZ colorXYZ) {
        double x = colorXYZ.X() / REF_X;
        double y = colorXYZ.Y() / REF_Y;
        double z = colorXYZ.Z() / REF_Z;

        x = (x > 0.008856) ? Math.cbrt(x) : (7.787 * x + 16.0 / 116.0);
        y = (y > 0.008856) ? Math.cbrt(y) : (7.787 * y + 16.0 / 116.0);
        z = (z > 0.008856) ? Math.cbrt(z) : (7.787 * z + 16.0 / 116.0);

        double L = (116.0 * y) - 16.0;
        double a = 500.0 * (x - y);
        double b = 200.0 * (y - z);

        return new LAB(L, a, b);
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
