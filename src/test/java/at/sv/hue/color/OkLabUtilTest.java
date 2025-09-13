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
        double[] start = lerpOKLabXY(X_RED, Y_RED, X_GREEN, Y_GREEN, 0.0);
        double[] end = lerpOKLabXY(X_RED, Y_RED, X_GREEN, Y_GREEN, 1.0);

        assertThat(start[0]).isCloseTo(X_RED, within(XY_EPS));
        assertThat(start[1]).isCloseTo(Y_RED, within(XY_EPS));

        assertThat(end[0]).isCloseTo(X_GREEN, within(XY_EPS));
        assertThat(end[1]).isCloseTo(Y_GREEN, within(XY_EPS));
    }

    @Test
    @DisplayName("Near-neutral ↔ chromatic: hue is taken from the chromatic endpoint; chroma is linear")
    void nearNeutralChromaticHueFromChromatic() {
        double t = 0.25;

        double[] lab0 = toLab(X_WHITE, Y_WHITE);
        double[] lab1 = toLab(X_RED, Y_RED);

        double h1 = atan2(lab1[2], lab1[1]);

        double[] xyActual = lerpOKLabXY(X_WHITE, Y_WHITE, X_RED, Y_RED, t);
        double[] labActual = toLab(xyActual);

        double hActual = atan2(labActual[2], labActual[1]);

        double hueDiffDeg = abs(toDegrees(shortestAngle(hActual - h1)));
        assertThat(hueDiffDeg).isLessThan(DEG_EPS);

        // monotonic instead of exactly linear (rescaling at Y=1 can skew magnitude)
        double[] ts = {0.1, 0.25, 0.5, 0.75, 0.9};
        double prev = hypot(lab0[1], lab0[2]); // start chroma
        for (double tau : ts) {
            double[] xy = lerpOKLabXY(X_WHITE, Y_WHITE, X_RED, Y_RED, tau);
            double[] l = toLab(xy);
            double c = hypot(l[1], l[2]);
            assertThat(c).isGreaterThanOrEqualTo(prev - 1e-6);
            prev = c;
        }
    }

    @Test
    @DisplayName("Both near-neutral: Cartesian interpolation ≈ linear Lab")
    void bothNearNeutralFallsBackToCartesian() {
        double t = 0.4;

        // Two whites very close to D65 to ensure tiny chroma on both ends
        double xA = 0.31270, yA = 0.32900; // D65
        double xB = 0.31300, yB = 0.32920; // slight shift near white

        double[] xyActual = lerpOKLabXY(xA, yA, xB, yB, t);
        double[] labActual = toLab(xyActual);

        double[] labA = toLab(xA, yA);
        double[] labB = toLab(xB, yB);
        double[] labCartesian = lerpLab(labA, labB, t);

        assertThat(delta(labActual, labCartesian)).isLessThan(LAB_EPS);
    }

    @Test
    @DisplayName("Chromatic ↔ chromatic takes the polar path (result ≠ Cartesian)")
    void polarBranchDiffersFromCartesian() {
        // RED -> GREEN at halfway point
        double t = 0.5;

        double[] xyActual = lerpOKLabXY(X_RED, Y_RED, X_GREEN, Y_GREEN, t);
        double[] labActual = toLab(xyActual);

        // Cartesian prediction (not taken by algorithm)
        double[] lab0 = toLab(X_RED, Y_RED);
        double[] lab1 = toLab(X_GREEN, Y_GREEN);
        double[] labCartesian = lerpLab(lab0, lab1, t);

        assertThat(delta(labActual, labCartesian))
                .as("Polar path should deviate from pure Cartesian blend")
                .isGreaterThan(0.01);
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

        double[] xyActual = lerpOKLabXY(X_RED, Y_RED, X_MAGENTA, Y_MAGENTA, t);
        double[] labActual = toLab(xyActual);
        double hActual = atan2(labActual[2], labActual[1]);

        double diffDeg = abs(toDegrees(shortestAngle(hActual - expectedMidHue)));
        assertThat(diffDeg).isLessThan(DEG_EPS);
    }

    @Test
    @DisplayName("Symmetry: f(a,b,t) == f(b,a,1-t) in xy")
    void symmetryProperty() {
        double t = 0.3;

        double[] p = lerpOKLabXY(X_RED, Y_RED, X_GREEN, Y_GREEN, t);
        double[] q = lerpOKLabXY(X_GREEN, Y_GREEN, X_RED, Y_RED, 1.0 - t);

        assertThat(p[0]).isCloseTo(q[0], within(XY_EPS));
        assertThat(p[1]).isCloseTo(q[1], within(XY_EPS));
    }

    @Test
    @DisplayName("Same endpoints: f(p,p,t) == p for all t (including extrapolation)")
    void sameEndpointsIdempotent() {
        double[][] pts = {
                {X_RED, Y_RED}, {X_GREEN, Y_GREEN},
                {X_WHITE, Y_WHITE}, {X_MAGENTA, Y_MAGENTA}
        };
        double[] ts = {-0.25, 0.0, 0.1, 0.5, 1.0, 1.5};
        for (double[] p : pts) {
            for (double t : ts) {
                double[] xy = lerpOKLabXY(p[0], p[1], p[0], p[1], t);
                assertThat(xy[0]).isCloseTo(p[0], within(XY_EPS));
                assertThat(xy[1]).isCloseTo(p[1], within(XY_EPS));
            }
        }
    }

    @Test
    @DisplayName("Chromatic ↔ chromatic: hue follows shortest arc for multiple t")
    void hueShortestArcMultiT() {
        double[] lab0 = toLab(X_RED, Y_RED);
        double[] lab1 = toLab(X_GREEN, Y_GREEN);
        double h0 = atan2(lab0[2], lab0[1]);
        double h1 = atan2(lab1[2], lab1[1]);
        double dh = shortestAngle(h1 - h0);

        for (double t : new double[]{0.1, 0.3, 0.5, 0.7, 0.9}) {
            double[] xy = lerpOKLabXY(X_RED, Y_RED, X_GREEN, Y_GREEN, t);
            double[] lab = toLab(xy);
            double h = atan2(lab[2], lab[1]);
            double diffDeg = abs(toDegrees(shortestAngle(h - (h0 + t * dh))));
            assertThat(diffDeg).isLessThan(DEG_EPS);
        }
    }

    @Test
    @DisplayName("Near-neutral ↔ chromatic: hue stays near chromatic endpoint across t")
    void nearNeutralHueConsistency() {
        double[] labR = toLab(X_RED, Y_RED);
        double hR = atan2(labR[2], labR[1]);
        for (double t : new double[]{0.1, 0.3, 0.5, 0.7, 0.9}) {
            double[] xy = lerpOKLabXY(X_WHITE, Y_WHITE, X_RED, Y_RED, t);
            double[] lab = toLab(xy);
            double h = atan2(lab[2], lab[1]);
            double diffDeg = abs(toDegrees(shortestAngle(h - hR)));
            assertThat(diffDeg).isLessThan(DEG_EPS);
        }
    }

    @Test
    @DisplayName("Near-neutral ↔ chromatic: chroma increases (W→R) and decreases (R→W)")
    void nearNeutralChromaMonotonicBothDirections() {
        // W -> R : non-decreasing
        double prevUp = hypot(toLab(X_WHITE, Y_WHITE)[1], toLab(X_WHITE, Y_WHITE)[2]);
        for (double t : new double[]{0.1, 0.3, 0.5, 0.7, 0.9}) {
            double[] l = toLab(lerpOKLabXY(X_WHITE, Y_WHITE, X_RED, Y_RED, t));
            double c = hypot(l[1], l[2]);
            assertThat(c).isGreaterThanOrEqualTo(prevUp - 1e-6);
            prevUp = c;
        }
        // R -> W : non-increasing
        double prevDown = hypot(toLab(X_RED, Y_RED)[1], toLab(X_RED, Y_RED)[2]);
        for (double t : new double[]{0.1, 0.3, 0.5, 0.7, 0.9}) {
            double[] l = toLab(lerpOKLabXY(X_RED, Y_RED, X_WHITE, Y_WHITE, t));
            double c = hypot(l[1], l[2]);
            assertThat(c).isLessThanOrEqualTo(prevDown + 1e-6);
            prevDown = c;
        }
    }

    @Test
    @DisplayName("Continuity: small t steps cause small OKLab changes (no jumps)")
    void continuityNoJumps() {
        double tStep = 0.01;
        double[] prevLab = toLab(lerpOKLabXY(X_RED, Y_RED, X_GREEN, Y_GREEN, 0.0));
        for (double t = tStep; t <= 1.0; t += tStep) {
            double[] lab = toLab(lerpOKLabXY(X_RED, Y_RED, X_GREEN, Y_GREEN, t));
            // generous bound; ensures no discontinuity across hue wrapping logic
            assertThat(delta(prevLab, lab)).isLessThan(0.15);
            prevLab = lab;
        }
    }

    @Test
    @DisplayName("Numerics: results are finite and within [0,1] in xy")
    void finiteAndInRange() {
        double[][] pairs = {
                {X_RED, Y_RED, X_GREEN, Y_GREEN},
                {X_WHITE, Y_WHITE, X_RED, Y_RED},
                {X_GREEN, Y_GREEN, X_MAGENTA, Y_MAGENTA}
        };
        double[] ts = {0.0, 0.1, 0.5, 0.9, 1.0};
        for (double[] p : pairs) {
            for (double t : ts) {
                double[] xy = lerpOKLabXY(p[0], p[1], p[2], p[3], t);
                assertThat(xy[0]).isFinite();
                assertThat(xy[1]).isFinite();
                assertThat(xy[0]).isBetween(0.0, 1.0);
                assertThat(xy[1]).isBetween(0.0, 1.0);
            }
        }
    }

    @Test
    @DisplayName("deltaE_OKLab: zero on identical, symmetric, positive otherwise")
    void deltaEBasics() {
        assertThat(OkLabUtil.deltaE_OKLab(X_RED, Y_RED, X_RED, Y_RED)).isCloseTo(0.0, within(1e-9));
        double dRG = OkLabUtil.deltaE_OKLab(X_RED, Y_RED, X_GREEN, Y_GREEN);
        double dGR = OkLabUtil.deltaE_OKLab(X_GREEN, Y_GREEN, X_RED, Y_RED);
        assertThat(dRG).isCloseTo(dGR, within(1e-12));
        assertThat(dRG).isGreaterThan(0.0);
    }

    @Test
    void oklch_to_xy_reference() {
        double[] xy = OkLabUtil.OKLchDeg_to_xy(0.7, 0.1, 250.0);
        
        assertThat(xy[0]).isCloseTo(0.23230679574, within(1e-9));
        assertThat(xy[1]).isCloseTo(0.24985671376, within(1e-9));
    }

    private static double[] lerpOKLabXY(double x0, double y0, double x1, double y1, double t) {
        return OkLabUtil.lerpOKLabXY(x0, y0, x1, y1, t);
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
