package at.sv.hue.color;

import at.sv.hue.ColorMode;
import at.sv.hue.api.PutCall;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static org.assertj.core.api.Assertions.assertThat;

class ColorModeConverterTest {

    private static final Double[][] GAMUT_A = new Double[][]{{0.704, 0.296}, {0.2151, 0.7106}, {0.138, 0.08}};
    private static final Double[][] GAMUT_C = new Double[][]{{0.6915, 0.3083}, {0.17, 0.7}, {0.1532, 0.0475}};

    @Test
    void covert_CT_CT_noOperation() {
        PutCall call = PutCall.builder().ct(153).build();

        ColorModeConverter.convertIfNeeded(call, null, ColorMode.CT, ColorMode.CT);

        assertThat(call).isEqualTo(PutCall.builder().ct(153).build());
    }

    @Test
    void covert_CT_HS() {
        assertCtToHS(153, 8738, 4); // cold white
        assertCtToHS(200, 5024, 49);
        assertCtToHS(300, 4941, 125);
        assertCtToHS(400, 5254, 184);
        assertCtToHS(500, 5551, 241); // warm white
    }

    @Test
    void convert_HS_CT() {
        assertHsToCt(8738, 4, 153); // cold white
        assertHsToCt(5024, 49, 200);
        assertHsToCt(4941, 125, 303);
        assertHsToCt(5254, 184, 400);
        assertHsToCt(5551, 241, 500); // warm white
    }

    @Test
    void covert_CT_XY() {
        assertCtToXY(153, 0.3264, 0.333);
        assertCtToXY(200, 0.3722, 0.3504);
        assertCtToXY(300, 0.4701, 0.3772);
        assertCtToXY(400, 0.5465, 0.3893);
        assertCtToXY(500, 0.5969, 0.3793);
    }

    @Test
    void convert_XY_CT() {
        assertXYToCt(0.3264, 0.333, 153);
        assertXYToCt(0.3722, 0.3504, 200);
        assertXYToCt(0.4701, 0.3772, 303);
        assertXYToCt(0.5465, 0.3893, 400);
        assertXYToCt(0.5969, 0.3793, 476);
    }

    @Test
    void convert_HS_XY() {
        assertHSToXY(65535, 254, GAMUT_A, 0.7004, 0.2991); // red
        assertHSToXY(0, 254, GAMUT_A, 0.7004, 0.2991); // red
        assertHSToXY(32767, 100, GAMUT_A, 0.2224, 0.3369);

        assertHSToXYClassic(180, 100, null, 0.1513, 0.3425);
        assertHSToXYClassic(350, 12.5, null, 0.3551, 0.321);
        assertHSToXYClassic(140, 50, null, 0.2296, 0.4738);
        assertHSToXYClassic(0, 40, null, 0.4721, 0.3173);
        assertHSToXYClassic(360, 0, null, 0.3227, 0.329);
        assertHSToXYClassic(0, 100, GAMUT_A, 0.7004, 0.2991);
        assertHSToXYClassic(120, 100, GAMUT_A, 0.2151, 0.7106);
        assertHSToXYClassic(180, 100, GAMUT_A, 0.1698, 0.3402);
        assertHSToXYClassic(240, 100, GAMUT_A, 0.138, 0.08);
        assertHSToXYClassic(360, 100, GAMUT_A, 0.7004, 0.2991);
    }

    @Test
    void convert_XY_HS() {
        assertXYToHSClassic(1, 1, null, 47.294, 100);
        assertXYToHSClassic(0.35, 0.35, null, 38.182, 12.941);
        assertXYToHSClassic(1, 0, null, 345.883, 100);
        assertXYToHSClassic(0, 1, null, 120.001, 100);
        assertXYToHSClassic(0, 0, null, 225.176, 100);

        assertXYToHSClassic(1, 0, GAMUT_A, 359.294, 100);
        assertXYToHSClassic(0, 1, GAMUT_A, 100.706, 100);
        assertXYToHSClassic(0, 0, GAMUT_A, 221.463, 96.471);
    }

    private static void assertCtToHS(int ct, int hue, int sat) {
        PutCall call = PutCall.builder()
                              .ct(ct)
                              .build();

        ColorModeConverter.convertIfNeeded(call, null, ColorMode.CT, ColorMode.HS);

        assertThat(call).isEqualTo(PutCall.builder()
                                          .hue(hue)
                                          .sat(sat)
                                          .build());
    }

    private static void assertHsToCt(int hue, int sat, int ct) {
        PutCall call = PutCall.builder()
                              .hue(hue)
                              .sat(sat)
                              .build();

        ColorModeConverter.convertIfNeeded(call, null, ColorMode.HS, ColorMode.CT);

        assertThat(call).isEqualTo(PutCall.builder()
                                          .ct(ct)
                                          .build());
    }

    private static void assertCtToXY(int ct, double x, double y) {
        PutCall call = PutCall.builder()
                              .ct(ct)
                              .build();

        ColorModeConverter.convertIfNeeded(call, GAMUT_C, ColorMode.CT, ColorMode.XY);

        assertThat(call).isEqualTo(PutCall.builder()
                                          .x(x)
                                          .y(y)
                                          .build());
    }

    private static void assertXYToCt(double x, double y, int ct) {
        PutCall call = PutCall.builder()
                              .x(x)
                              .y(y)
                              .build();

        ColorModeConverter.convertIfNeeded(call, GAMUT_C, ColorMode.XY, ColorMode.CT);

        assertThat(call).isEqualTo(PutCall.builder()
                                          .ct(ct)
                                          .build());
    }

    private static void assertHSToXY(int hue, int sat, Double[][] gamut, double x, double y) {
        PutCall call = PutCall.builder()
                              .hue(hue)
                              .sat(sat)
                              .build();

        ColorModeConverter.convertIfNeeded(call, gamut, ColorMode.HS, ColorMode.XY);

        assertThat(call).isEqualTo(PutCall.builder()
                                          .x(x)
                                          .y(y)
                                          .build());
    }

    private static void assertHSToXYClassic(double hueInDegrees, double saturationInPercent, Double[][] gamut, double x, double y) {
        assertHSToXY(convertHueInDegreesForHue(hueInDegrees), convertSaturationInPercentForHue(saturationInPercent),
                gamut, x, y);
    }

    private static void assertXYToHS(double x, double y, Double[][] gamutA, int hue, int sat) {
        PutCall call = PutCall.builder()
                              .x(x)
                              .y(y)
                              .build();

        ColorModeConverter.convertIfNeeded(call, gamutA, ColorMode.XY, ColorMode.HS);

        assertThat(call).isEqualTo(PutCall.builder()
                                          .hue(hue)
                                          .sat(sat)
                                          .build());
    }

    private static void assertXYToHSClassic(double x, double y, Double[][] gamut, double hueInDegrees, double saturationInPercent) {
        int hueForHue = convertHueInDegreesForHue(hueInDegrees);
        int saturationForHue = convertSaturationInPercentForHue(saturationInPercent);
        assertXYToHS(x, y, gamut, hueForHue, saturationForHue);
    }

    private static int convertSaturationInPercentForHue(double saturationInPercent) {
        return BigDecimal.valueOf(saturationInPercent)
                         .divide(BigDecimal.valueOf(100), 5, RoundingMode.HALF_UP)
                         .multiply(BigDecimal.valueOf(254))
                         .intValue();
    }

    private static int convertHueInDegreesForHue(double hueInDegrees) {
        return BigDecimal.valueOf(hueInDegrees)
                         .divide(BigDecimal.valueOf(360), 5, RoundingMode.HALF_UP)
                         .multiply(BigDecimal.valueOf(65535))
                         .intValue();
    }
}
