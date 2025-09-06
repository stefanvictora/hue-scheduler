package at.sv.hue.color;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static java.lang.Math.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * AI generated tests
 */
class OkLabUtilTest {

    /**
     * Practically achromatic → forces Cartesian branch when blended with chromatic colours.
     */
    private static final double X_WHITE = 0.3127;
    private static final double Y_WHITE = 0.3290;

    /**
     * Highly-chromatic primaries → polar branch.
     */
    private static final double X_RED = 0.640;
    private static final double Y_RED = 0.330;
    private static final double X_GREEN = 0.170;
    private static final double Y_GREEN = 0.700;
    private static final double X_MAGENTA = 0.378;   // hue ≈ 300°
    private static final double Y_MAGENTA = 0.172;

    /* ---------- numeric tolerances ---------- */

    private static final double XY_EPS = 1e-6;   // xy domain
    private static final double LAB_EPS = 2e-3;   // OKLab domain
    private static final double DEG_EPS = 1.0;    // degrees

    @Test
    @DisplayName("t = 0 and t = 1 must return the exact endpoints (no drift)")
    void endpointsArePreserved() {
        // RED  ->  GREEN
        double[] start = OkLabUtil.lerpOKLabXY(X_RED, Y_RED, X_GREEN, Y_GREEN, 0.0);
        double[] end = OkLabUtil.lerpOKLabXY(X_RED, Y_RED, X_GREEN, Y_GREEN, 1.0);

        assertThat(start[0]).isCloseTo(X_RED, within(XY_EPS));
        assertThat(start[1]).isCloseTo(Y_RED, within(XY_EPS));

        assertThat(end[0]).isCloseTo(X_GREEN, within(XY_EPS));
        assertThat(end[1]).isCloseTo(Y_GREEN, within(XY_EPS));
    }

    @Test
    @DisplayName("White ↔ chromatic colour uses the Cartesian interpolation path")
    void cartesianBranchMatchesLinearLab() {
        // WHITE -> RED at t = 0.25
        double t = 0.25;

        double[] xyActual = OkLabUtil.lerpOKLabXY(X_WHITE, Y_WHITE, X_RED, Y_RED, t);
        double[] labActual = toLab(xyActual);

        // Expected = linear interpolation in Lab
        double[] lab0 = toLab(X_WHITE, Y_WHITE);
        double[] lab1 = toLab(X_RED, Y_RED);
        double[] labExpected = lerpLab(lab0, lab1, t);

        assertThat(delta(labActual, labExpected)).isLessThan(LAB_EPS);
    }

    @Test
    @DisplayName("Chromatic ↔ chromatic takes the polar path (result ≠ Cartesian)")
    void polarBranchDiffersFromCartesian() {
        // RED -> GREEN at halfway point
        double t = 0.5;

        double[] xyActual = OkLabUtil.lerpOKLabXY(X_RED, Y_RED, X_GREEN, Y_GREEN, t);
        double[] labActual = toLab(xyActual);

        // Cartesian prediction (not taken by algorithm)
        double[] lab0 = toLab(X_RED, Y_RED);
        double[] lab1 = toLab(X_GREEN, Y_GREEN);
        double[] labCartesian = lerpLab(lab0, lab1, t);

        assertThat(delta(labActual, labCartesian))
                .as("Polar path should deviate from pure Cartesian blend")
                .isGreaterThan(0.01);   // comfortably above numeric noise
    }

    @Test
    @DisplayName("Hue wrapping: shortest-arc interpolation over the ±π seam")
    void hueShortestArcIsUsed() {
        // RED (h ~ 0°) -> MAGENTA (h ~ 300°, i.e. -60°) — seam not crossed yet.
        // To cross the seam choose around +10° and 350°, but red/magenta is enough:
        double t = 0.5;

        double[] lab0 = toLab(X_RED, Y_RED);
        double[] lab1 = toLab(X_MAGENTA, Y_MAGENTA);
        double h0 = atan2(lab0[2], lab0[1]);
        double h1 = atan2(lab1[2], lab1[1]);

        double expectedMidHue = h0 + t * shortestAngle(h1 - h0);

        double[] xyActual = OkLabUtil.lerpOKLabXY(X_RED, Y_RED, X_MAGENTA, Y_MAGENTA, t);
        double[] labActual = toLab(xyActual);
        double hActual = atan2(labActual[2], labActual[1]);

        double diffDeg = abs(toDegrees(shortestAngle(hActual - expectedMidHue)));
        assertThat(diffDeg).isLessThan(DEG_EPS);
    }

    private static double[] toLab(double[] xy) {
        double[] xyz = OkLabUtil.xyY_to_XYZ(xy[0], xy[1], 1.0);
        return OkLabUtil.XYZ_to_OKLab(xyz[0], xyz[1], xyz[2]);
    }

    private static double[] toLab(double x, double y) {
        return toLab(new double[]{x, y});
    }

    private static double[] lerpLab(double[] a, double[] b, double t) {
        return new double[]{
                a[0] + (b[0] - a[0]) * t,
                a[1] + (b[1] - a[1]) * t,
                a[2] + (b[2] - a[2]) * t
        };
    }

    private static double delta(double[] u, double[] v) {
        double d0 = u[0] - v[0], d1 = u[1] - v[1], d2 = u[2] - v[2];
        return sqrt(d0 * d0 + d1 * d1 + d2 * d2);
    }

    private static double shortestAngle(double d) {
        double twoPi = PI * 2.0;
        d = (d + PI) % twoPi;
        if (d < 0) {
            d += twoPi;
        }
        return d - PI;
    }
}
