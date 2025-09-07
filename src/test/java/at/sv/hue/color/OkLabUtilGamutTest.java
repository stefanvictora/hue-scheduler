package at.sv.hue.color;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static java.lang.Math.abs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * AI generated
 */
class OkLabUtilGamutTest {

    // Philips Hue published gamuts
    private static final Double[][] GAMUT_A = new Double[][]{
            {0.704, 0.296}, {0.2151, 0.7106}, {0.138, 0.08}};
    private static final Double[][] GAMUT_B = new Double[][]{
            {0.675, 0.322}, {0.409, 0.518}, {0.167, 0.04}};
    private static final Double[][] GAMUT_C = new Double[][]{
            {0.6915, 0.3083}, {0.17, 0.7}, {0.1532, 0.0475}};

    private static final double XY_EPS = 1e-6;

    // A couple of in-gamut points for GAMUT_C (also safe for A/B in practice)
    private static final double[] D65 = {0.3127, 0.3290};
    private static final double[] IN1 = {0.4000, 0.5000};

    // OOG points for stress
    private static final double[] OOG1 = {0.8000, 0.2000};
    private static final double[] OOG2 = {0.1000, 0.9000};
    private static final double[] OOG3 = {0.7000, 0.4000};

    @Test
    @DisplayName("t=0/1 return pre-clamped endpoints (A/B/C)")
    void endpointsArePreclamped() {
        for (Double[][] gamut : new Double[][][]{GAMUT_A, GAMUT_B, GAMUT_C}) {
            // pick two out-of-gamut endpoints to ensure clamping happens
            double[] sClamp = clampXY(OOG1[0], OOG1[1], gamut);
            double[] eClamp = clampXY(OOG2[0], OOG2[1], gamut);

            double[] at0 = OkLabUtil.lerpOKLabXY(OOG1[0], OOG1[1], OOG2[0], OOG2[1], 0.0, gamut);
            double[] at1 = OkLabUtil.lerpOKLabXY(OOG1[0], OOG1[1], OOG2[0], OOG2[1], 1.0, gamut);

            assertThat(at0[0]).isCloseTo(sClamp[0], within(XY_EPS));
            assertThat(at0[1]).isCloseTo(sClamp[1], within(XY_EPS));
            assertThat(at1[0]).isCloseTo(eClamp[0], within(XY_EPS));
            assertThat(at1[1]).isCloseTo(eClamp[1], within(XY_EPS));
        }
    }

    @Test
    @DisplayName("All interpolated steps are inside gamut (A/B/C)")
    void allStepsInsideGamut() {
        double[] ts = {0.0, 0.1, 0.25, 0.5, 0.75, 0.9, 1.0};
        for (Double[][] gamut : new Double[][][]{GAMUT_A, GAMUT_B, GAMUT_C}) {
            // mix in/out-of-gamut endpoints to exercise both pre- and post-clamp
            double[][] pairs = {
                    {D65[0], D65[1], IN1[0], IN1[1]},
                    {OOG1[0], OOG1[1], IN1[0], IN1[1]},
                    {IN1[0], IN1[1], OOG2[0], OOG2[1]},
                    {OOG3[0], OOG3[1], OOG2[0], OOG2[1]}
            };
            for (double[] p : pairs) {
                for (double t : ts) {
                    double[] xy = OkLabUtil.lerpOKLabXY(p[0], p[1], p[2], p[3], t, gamut);
                    assertThat(isInsideGamut(xy[0], xy[1], gamut))
                            .as("xy must be inside gamut at t=" + t)
                            .isTrue();
                }
            }
        }
    }

    @Test
    @DisplayName("Clamped pipeline equals: clamp endpoints → interpolate → clamp result")
    void matchesManualPipeline() {
        Double[][] gamut = GAMUT_C; // any will do
        double t = 0.37;

        double[] xyAuto = OkLabUtil.lerpOKLabXY(OOG1[0], OOG1[1], OOG2[0], OOG2[1], t, gamut);

        double[] s = clampXY(OOG1[0], OOG1[1], gamut);
        double[] e = clampXY(OOG2[0], OOG2[1], gamut);
        double[] mid = OkLabUtil.lerpOKLabXY(s[0], s[1], e[0], e[1], t, null);
        double[] xyManual = clampXY(mid[0], mid[1], gamut);

        assertThat(xyAuto[0]).isCloseTo(xyManual[0], within(XY_EPS));
        assertThat(xyAuto[1]).isCloseTo(xyManual[1], within(XY_EPS));
    }

    @Test
    @DisplayName("Already in gamut: clamped interpolation equals unclamped interpolation")
    void noEffectWhenInside() {
        Double[][] gamut = GAMUT_C;
        double[] s = D65;
        double[] e = IN1;

        // sanity: both inside
        assertThat(isInsideGamut(s[0], s[1], gamut)).isTrue();
        assertThat(isInsideGamut(e[0], e[1], gamut)).isTrue();

        for (double t : new double[]{0.0, 0.2, 0.5, 0.8, 1.0}) {
            double[] unclamped = OkLabUtil.lerpOKLabXY(s[0], s[1], e[0], e[1], t, null);
            double[] clamped = OkLabUtil.lerpOKLabXY(s[0], s[1], e[0], e[1], t, gamut);
            assertThat(clamped[0]).isCloseTo(unclamped[0], within(XY_EPS));
            assertThat(clamped[1]).isCloseTo(unclamped[1], within(XY_EPS));
        }
    }

    @Test
    @DisplayName("Symmetry with clamp: f(a,b,t,g) == f(b,a,1-t,g)")
    void symmetryWithClamp() {
        Double[][] gamut = GAMUT_B;
        double t = 0.33;
        double[] p = OkLabUtil.lerpOKLabXY(OOG1[0], OOG1[1], IN1[0], IN1[1], t, gamut);
        double[] q = OkLabUtil.lerpOKLabXY(IN1[0], IN1[1], OOG1[0], OOG1[1], 1.0 - t, gamut);
        assertThat(p[0]).isCloseTo(q[0], within(XY_EPS));
        assertThat(p[1]).isCloseTo(q[1], within(XY_EPS));
    }

    @Test
    @DisplayName("Idempotence: clamping the clamped result changes nothing")
    void idempotentOutputs() {
        Double[][] gamut = GAMUT_A;
        double[] xy = OkLabUtil.lerpOKLabXY(OOG1[0], OOG1[1], OOG2[0], OOG2[1], 0.6, gamut);
        double[] reclamped = clampXY(xy[0], xy[1], gamut);
        assertThat(reclamped[0]).isCloseTo(xy[0], within(XY_EPS));
        assertThat(reclamped[1]).isCloseTo(xy[1], within(XY_EPS));
    }

    private static boolean isInsideGamut(double x, double y, Double[][] gamut) {
        XYColorGamutCorrection c = new XYColorGamutCorrection(x, y, gamut);
        return abs(c.getX() - x) < 1e-12 && abs(c.getY() - y) < 1e-12;
    }

    private static double[] clampXY(double x, double y, Double[][] gamut) {
        XYColorGamutCorrection c = new XYColorGamutCorrection(x, y, gamut);
        return new double[]{c.getX(), c.getY()};
    }
}
