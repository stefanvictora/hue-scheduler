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

    public Scene(List<SceneAction> actions) {
        this.actions = actions;
    }

    Scene(String name, ResourceReference group, List<SceneAction> actions) {
        this.metadata = new Metadata(name, null);
        this.group = group;
        this.actions = actions;
    }

    boolean isPartOf(Group otherGroup) {
        return otherGroup.toResourceReference().equals(group);
    }
}
