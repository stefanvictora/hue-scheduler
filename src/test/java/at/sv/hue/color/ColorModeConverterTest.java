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
        assertXYToCt(0.1, 0.8, 124); // applies gamut correction
        assertXYToCt(0.8, 0.2, 186); // applies gamut correction
        assertXYToCt(0.5, 0.4, 1_000_000 / 2140);
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
    void convert_CT_Gradient_convertsToXYAndUsesAsTwoPoints() {
        PutCall putCall = PutCall.builder()
                                 .ct(500)
                                 .build();

        ColorModeConverter.convertIfNeeded(putCall, ColorMode.GRADIENT);

        assertThat(putCall).isEqualTo(PutCall.builder()
                                             .gradient(Gradient.builder()
                                                               .points(List.of(
                                                                       Pair.of(0.5317, 0.4127),
                                                                       Pair.of(0.5317, 0.4127)))
                                                               .build())
                                             .build());
    }

    @Test
    void convert_Gradient_CT_takesFirstPointAndConvertsToCT() {
        PutCall putCall = PutCall.builder()
                                 .gradient(Gradient.builder()
                                                   .points(List.of(Pair.of(0.5317, 0.4127),
                                                           Pair.of(0.6915, 0.3083),
                                                           Pair.of(0.17, 0.7)))
                                                   .build())
                                 .build();

        ColorModeConverter.convertIfNeeded(putCall, ColorMode.CT);

        assertThat(putCall).isEqualTo(PutCall.builder()
                                             .ct(507)
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
}
