package at.sv.hue.color;

public final class ColorComparator {

    private ColorComparator() {
    }

    /**
     * Compare two xy chromaticities in OKLab.
     * Both sides are evaluated at Y=1 (chromaticity-only comparison).
     */
    public static boolean colorDiffers(double x1, double y1, double x2, double y2,
                                       Double[][] gamut, double threshold) {
        XYColorGamutCorrection c1 = new XYColorGamutCorrection(x1, y1, gamut);
        XYColorGamutCorrection c2 = new XYColorGamutCorrection(x2, y2, gamut);
        return OkLabUtil.deltaE_OKLab(c1.getX(), c1.getY(), c2.getX(), c2.getY()) >= threshold;
    }
}
