package at.sv.hue.api.hue;

import at.sv.hue.api.GroupNotFoundException;
import com.fasterxml.jackson.annotation.JsonMerge;
import com.fasterxml.jackson.annotation.OptBoolean;
import lombok.Data;

import java.util.List;

@Data
final class Group implements Resource {
    String id;
    String id_v1;
    Metadata metadata;
    @JsonMerge(OptBoolean.FALSE)
    List<ResourceReference> children;
    @JsonMerge(OptBoolean.FALSE)
    List<ResourceReference> services;
    String type;

    ResourceReference toResourceReference() {
        return new ResourceReference(id, type);
    }

    String getGroupedLightId() {
        return services.stream()
                       .filter(resource -> "grouped_light".equals(resource.getRtype()))
                       .findFirst()
                       .map(ResourceReference::getRid)
                       .orElseThrow(() -> new GroupNotFoundException("No grouped_light found for '" + id + "'"));
    }
}
