package at.sv.hue.api.hue;

import com.fasterxml.jackson.annotation.JsonMerge;
import com.fasterxml.jackson.annotation.OptBoolean;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

@Data
final class Device implements Resource {
    String id;
    String id_v1;
    Metadata metadata;
    @JsonMerge(OptBoolean.FALSE)
    List<ResourceReference> services = new ArrayList<>();
    String type;

    List<ResourceReference> getLightResources() {
        return services.stream()
                       .filter(ResourceReference::isLight)
                       .toList();
    }

    Optional<String> getZigbeeConnectivityResource() {
        return services.stream()
                       .filter(ResourceReference::isZigbeeConnectivity)
                       .findFirst()
                       .map(ResourceReference::getRid);
    }

    Stream<String> getLightIds() {
        return services.stream()
                       .filter(ResourceReference::isLight)
                       .map(ResourceReference::getRid);
    }
}
