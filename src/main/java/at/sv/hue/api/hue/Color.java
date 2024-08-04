package at.sv.hue.api.hue;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Data
final class Color {
    XY xy;
    Gamut gamut;
    String gamut_type;

    Color(XY xy) {
        this.xy = xy;
    }

    Double[][] getGamut() {
        if (gamut == null) {
            return null;
        }
        return gamut.toArray();
    }

    @Data
    private static final class Gamut {
        XY red;
        XY green;
        XY blue;

        private Double[][] toArray() {
            return new Double[][]{{red.x, red.y}, {green.x, green.y}, {blue.x, blue.y}};
        }
    }
}
