package at.sv.hue.color;

/**
 * The approximation from RGB to Kelvin was ported from <a href="https://tannerhelland.com/2012/09/18/convert-temperature-rgb-algorithm-code.html">
 * How to Convert Temperature (K) to RGB: Algorithm and Sample Code</a> from Tanner Helland (c) 2012
 */
public final class CTToRGBConverter {

    private static final int MIN_KELVIN = 1000;
    private static final int MAX_KELVIN = 6700;
    private static final int STEP = 50;
    private static final double[][] RGB_VALUES_BY_KELVIN = buildRBGByKelvinLookupTable();

    private static double[][] buildRBGByKelvinLookupTable() {
        int size = (MAX_KELVIN - MIN_KELVIN) / STEP + 1;
        double[][] table = new double[size][];
        for (int i = 0; i < size; i++) {
            table[i] = approximateRGBFromKelvin(MIN_KELVIN + i * STEP);
        }
        return table;
    }

    private static int approximateKelvinFromRGB(int r, int g, int b) {
        int closestKelvin = MIN_KELVIN;
        double closestDistance = Double.MAX_VALUE;
        for (int k = MIN_KELVIN, i = 0; k <= MAX_KELVIN; k += STEP, i++) {
            double distance = colorDistance(r, g, b, RGB_VALUES_BY_KELVIN[i]);
            if (distance < closestDistance) {
                closestDistance = distance;
                closestKelvin = k;
            }
        }
        return closestKelvin;
    }

    private static double colorDistance(int r1, int g1, int b1, double[] rgb2) {
        double wr = 0.3;
        double wg = 0.59;
        double wb = 0.11;

        double rDiff = r1 - rgb2[0];
        double gDiff = g1 - rgb2[1];
        double bDiff = b1 - rgb2[2];

        return Math.sqrt(wr * rDiff * rDiff + wg * gDiff * gDiff + wb * bDiff * bDiff);
    }

    /**
     * Returns the approximated Mired value from the given RGB
     */
    public static int approximateMiredFromRGB(int r, int g, int b) {
        return 1_000_000 / approximateKelvinFromRGB(r, g, b);
    }

    /**
     * Approximates the given Mired to rgb values
     *
     * @param mired the mired to approximate
     * @return [r, g, b] array of colors
     */
    public static int[] approximateRGBFromMired(int mired) {
        double[] rgb = approximateRGBFromKelvin((int) (1_000_000.0 / mired));
        return new int[]{rgbClamp(rgb[0]), rgbClamp(rgb[1]), rgbClamp(rgb[2])};
    }

    /**
     * Approximates the given Kelvin to rgb values
     *
     * @param kelvin the kelvin to approximate. Min: 1000. Max: 40000
     * @return [r, g, b] array of colors
     */
    private static double[] approximateRGBFromKelvin(int kelvin) {
        kelvin = clamp(kelvin, 1000, 40000);
        kelvin = kelvin / 100;
        double r = approximateRed(kelvin);
        double g = approximateGreen(kelvin);
        double b = approximateBlue(kelvin);
        return new double[]{r, g, b};
    }

    private static double approximateRed(int kelvin) {
        if (kelvin <= 66) {
            return 255;
        } else {
            return 329.698727446 * Math.pow(kelvin - 60, -0.1332047592);
        }
    }

    private static double approximateGreen(int kelvin) {
        if (kelvin <= 66) {
            return 99.4708025861 * Math.log(kelvin) - 161.1195681661;
        } else {
            return 288.1221695283 * Math.pow(kelvin - 60, -0.0755148492);
        }
    }

    private static double approximateBlue(int kelvin) {
        if (kelvin >= 66) {
            return 255;
        } else if (kelvin <= 19) {
            return 0;
        } else {
            return 138.5177312231 * Math.log(kelvin - 10) - 305.0447927307;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    private static int rgbClamp(double color) {
        return clamp((int) color, 0, 255);
    }
}
