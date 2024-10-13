package at.sv.hue.api.hue;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

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

    Stream<String> getLightIds() {
        return services.stream()
                       .filter(ResourceReference::isLight)
                       .map(ResourceReference::getRid);
    }
}
