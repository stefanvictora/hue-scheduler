package at.sv.hue.api.hue;

import java.beans.Transient;

interface Resource {
    String getId();
    String getId_v1();
    Metadata getMetadata();
    String getType();

    @Transient
    default String getName() {
        return getMetadata().getName();
    }
}
