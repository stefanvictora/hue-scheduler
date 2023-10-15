package at.sv.hue.api.hass;

import lombok.Data;

import java.util.List;

@Data
final class StateAttributes {
    String friendly_name;
    String color_mode;
    Integer brightness;
    Integer color_temp;
    Double[] xy_color;
    String effect;
    List<String> supported_color_modes;
    Integer min_mireds;
    Integer max_mireds;
    Boolean is_hue_group;
    List<String> lights;
    List<String> entity_id;
}
