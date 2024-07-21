package at.sv.hue.api.hue;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
final class Device implements Resource {
    String id;
    String id_v1;
    Metadata metadata;
    List<ResourceReference> services = new ArrayList<>();
    String type;

    List<ResourceReference> getLightResources() {
        return services.stream()
                       .filter(ResourceReference::isLight)
                       .toList();
    }
}
