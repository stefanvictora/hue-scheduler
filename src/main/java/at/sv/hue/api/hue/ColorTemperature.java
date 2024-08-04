package at.sv.hue.api.hue;

import lombok.Data;
import lombok.NoArgsConstructor;

@NoArgsConstructor
@Data
final class ColorTemperature {
    Integer mirek;
    Boolean mirek_valid;
    MirekSchema mirek_schema;

    ColorTemperature(int mirek) {
        this.mirek = mirek;
    }

    Integer getCtMin() {
        if (mirek_schema == null) {
            return null;
        }
        return mirek_schema.getMirek_minimum();
    }

    Integer getCtMax() {
        if (mirek_schema == null) {
            return null;
        }
        return mirek_schema.getMirek_maximum();
    }

    @Data
    static final class MirekSchema {
        int mirek_minimum;
        int mirek_maximum;
    }
}
