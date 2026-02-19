package at.sv.hue.api.hue;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Data
final class Recall {
    String action;
    Integer duration;
}
