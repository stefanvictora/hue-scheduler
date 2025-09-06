package at.sv.hue.color;

import at.sv.hue.ColorMode;
import at.sv.hue.Gradient;
import at.sv.hue.Pair;
import at.sv.hue.api.PutCall;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ColorModeConverterTest {

    private static final Double[][] GAMUT_A = new Double[][]{{0.704, 0.296}, {0.2151, 0.7106}, {0.138, 0.08}};
    private static final Double[][] GAMUT_B = new Double[][]{{0.675, 0.322}, {0.409, 0.518}, {0.167, 0.04}};
    private static final Double[][] GAMUT_C = new Double[][]{{0.6915, 0.3083}, {0.17, 0.7}, {0.1532, 0.0475}};
    private static final Double[][] GAMUT_DEFAULT = new Double[][]{{1.0, 0.0}, {0.0, 1.0}, {0.0, 0.0}};

    @Test
    void covert_CT_CT_noOperation() {
        PutCall call = PutCall.builder().ct(153).build();

        ColorModeConverter.convertIfNeeded(call, ColorMode.CT);

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
        assertCtToXY(153, 0.3157, 0.3329);
        assertCtToXY(200, 0.3473, 0.3523);
        assertCtToXY(300, 0.4202, 0.3881);
        assertCtToXY(400, 0.4839, 0.411);
        assertCtToXY(500, 0.5317, 0.4127);
    }

    @Test
    void convert_XY_CT() {
        assertXYToCt(0.3264, 0.333, 172);
        assertXYToCt(0.3722, 0.3504, 246);
        assertXYToCt(0.4701, 0.3772, 439);
        assertXYToCt(0.5465, 0.3893, 563); // todo: too big of a value
        assertXYToCt(0.5969, 0.3793, 570); // todo: too big of a value

        assertXYToCt(0.5119, 0.4147, 1_000_000 / 2137);
        assertXYToCt(0.368, 0.3686, 1_000_000 / 4302);
        assertXYToCt(0.4448, 0.4066, 1_000_000 / 2893);
        assertXYToCt(0.1, 0.8, 1_000_000 / 8645);
        assertXYToCt(0.5, 0.4, 1_000_000 / 2140);
    }

    @Test
    void convert_HS_XY() {
        assertHSToXY(65535, 254, GAMUT_A, 0.64, 0.33); // red
        assertHSToXY(0, 254, GAMUT_A, 0.64, 0.33); // red
        assertHSToXY(32767, 100, GAMUT_A, 0.2583, 0.3289); // light blue
    }

    @Test
    void convert_XY_HS() {
        assertXYToHS(1, 1, null, 7195, 254);
        assertXYToHS(0.35, 0.35, null, 4327, 52);
        assertXYToHS(1, 1, GAMUT_A, 6510, 254);
        assertXYToHS(0.1969, 0.6798, GAMUT_C, 22144, 254);
    }

    @Test
    void convert_Gradient_XY_takesFirstColor() {
        PutCall putCall = PutCall.builder()
                                 .gradient(Gradient.builder()
                                                   .points(List.of(Pair.of(0.1532, 0.0475),
                                                           Pair.of(0.6915, 0.3083),
                                                           Pair.of(0.17, 0.7)))
                                                   .build())
                                 .build();

        ColorModeConverter.convertIfNeeded(putCall, ColorMode.XY);

        assertThat(putCall).isEqualTo(PutCall.builder()
                                             .x(0.1532)
                                             .y(0.0475)
                                             .build());
    }

    @Test
    void convert_XY_Gradient_usesColorForTwoPoints() {
        PutCall putCall = PutCall.builder()
                                 .x(0.6915)
                                 .y(0.3083)
                                 .build();

        ColorModeConverter.convertIfNeeded(putCall, ColorMode.GRADIENT);

        assertThat(putCall).isEqualTo(PutCall.builder()
                                             .gradient(Gradient.builder()
                                                               .points(List.of(
                                                                       Pair.of(0.6915, 0.3083),
                                                                       Pair.of(0.6915, 0.3083)))
                                                               .build())
                                             .build());
    }

    @Test
    void convert_Gradient_CT_takesFirstPoint() {
        PutCall putCall = PutCall.builder()
                                 .gradient(Gradient.builder()
                                                   .points(List.of(Pair.of(0.1532, 0.0475),
                                                           Pair.of(0.6915, 0.3083),
                                                           Pair.of(0.17, 0.7)))
                                                   .build())
                                 .build();

        ColorModeConverter.convertIfNeeded(putCall, ColorMode.CT);

        assertThat(putCall).isEqualTo(PutCall.builder()
                                             .ct(580)
                                             .build());
    }

    @Test
    void convert_Gradient_HS_takesFirstPoint() {
        PutCall putCall = PutCall.builder()
                                 .gradient(Gradient.builder()
                                                   .points(List.of(Pair.of(0.1532, 0.0475),
                                                           Pair.of(0.6915, 0.3083),
                                                           Pair.of(0.17, 0.7)))
                                                   .build())
                                 .build();

        ColorModeConverter.convertIfNeeded(putCall, ColorMode.HS);

        assertThat(putCall).isEqualTo(PutCall.builder()
                                             .hue(45746)
                                             .sat(254)
                                             .build());
    }

    @Test
    void convert_Gradient_None_keepsInput() {
        PutCall putCall = PutCall.builder()
                                 .gradient(Gradient.builder()
                                                   .points(List.of(Pair.of(0.1532, 0.0475),
                                                           Pair.of(0.6915, 0.3083),
                                                           Pair.of(0.17, 0.7)))
                                                   .build())
                                 .build();
        PutCall copy = putCall.toBuilder().build();

        ColorModeConverter.convertIfNeeded(putCall, ColorMode.NONE);

        assertThat(putCall).isEqualTo(copy);
    }

    @Test
    void convert_Gradient_Gradient_keepsInput() {
        PutCall putCall = PutCall.builder()
                                 .gradient(Gradient.builder()
                                                   .points(List.of(Pair.of(0.1532, 0.0475),
                                                           Pair.of(0.6915, 0.3083),
                                                           Pair.of(0.17, 0.7)))
                                                   .build())
                                 .build();
        PutCall copy = putCall.toBuilder().build();

        ColorModeConverter.convertIfNeeded(putCall, ColorMode.GRADIENT);

        assertThat(putCall).isEqualTo(copy);
    }

    private static void assertCtToHS(int ct, int hue, int sat) {
        PutCall call = PutCall.builder()
                              .ct(ct)
                              .build();

        ColorModeConverter.convertIfNeeded(call, ColorMode.HS);

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

        ColorModeConverter.convertIfNeeded(call, ColorMode.CT);

        assertThat(call).isEqualTo(PutCall.builder()
                                          .ct(ct)
                                          .build());
    }

    private static void assertCtToXY(int ct, double x, double y) {
        PutCall call = PutCall.builder()
                              .ct(ct)
                              .gamut(GAMUT_C)
                              .build();

        ColorModeConverter.convertIfNeeded(call, ColorMode.XY);

        assertThat(call).isEqualTo(PutCall.builder()
                                          .x(x)
                                          .y(y)
                                          .build());
    }

    private static void assertXYToCt(double x, double y, int ct) {
        PutCall call = PutCall.builder()
                              .x(x)
                              .y(y)
                              .gamut(GAMUT_C)
                              .build();

        ColorModeConverter.convertIfNeeded(call, ColorMode.CT);

        assertThat(call).isEqualTo(PutCall.builder()
                                          .ct(ct)
                                          .build());
    }

    private static void assertHSToXY(int hue, int sat, Double[][] gamut, double x, double y) {
        PutCall call = PutCall.builder()
                              .hue(hue)
                              .sat(sat)
                              .gamut(gamut)
                              .build();

        ColorModeConverter.convertIfNeeded(call, ColorMode.XY);

        assertThat(call).isEqualTo(PutCall.builder()
                                          .x(x)
                                          .y(y)
                                          .build());
    }

    private static void assertXYToHS(double x, double y, Double[][] gamut, int hue, int sat) {
        PutCall call = PutCall.builder()
                              .x(x)
                              .y(y)
                              .gamut(gamut)
                              .build();

        ColorModeConverter.convertIfNeeded(call, ColorMode.HS);

        assertThat(call).isEqualTo(PutCall.builder()
                                          .hue(hue)
                                          .sat(sat)
                                          .build());
    }
}
