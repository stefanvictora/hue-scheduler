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
        if (gamut != null) {
            XYColorGamutCorrection c1 = new XYColorGamutCorrection(x1, y1, gamut);
            XYColorGamutCorrection c2 = new XYColorGamutCorrection(x2, y2, gamut);
            x1 = c1.getX();
            y1 = c1.getY();
            x2 = c2.getX();
            y2 = c2.getY();
        }
        return OkLabUtil.deltaE_OKLab(x1, y1, x2, y2) >= threshold;
    }
}
