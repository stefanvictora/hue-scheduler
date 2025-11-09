package at.sv.hue.api.hue;

import com.fasterxml.jackson.annotation.JsonMerge;
import com.fasterxml.jackson.annotation.OptBoolean;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@AllArgsConstructor
@NoArgsConstructor
@Data
final class Scene implements Resource {
    String id;
    String id_v1;
    Metadata metadata;
    ResourceReference group;
    @JsonMerge(OptBoolean.FALSE)
    List<SceneAction> actions;
    String type;

    Scene(String name, String appdata, List<SceneAction> actions) {
        this.metadata = new Metadata(name, appdata);
        this.actions = actions;
    }

    Scene(String name, String appdata, ResourceReference group, List<SceneAction> actions) {
        this.metadata = new Metadata(name, appdata);
        this.group = group;
        this.actions = actions;
    }

    boolean isPartOf(Group otherGroup) {
        return otherGroup.toResourceReference().equals(group);
    }
}
