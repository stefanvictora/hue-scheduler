package at.sv.hue.api.hue;

interface Resource {
    String getId();
    String getId_v1();
    Metadata getMetadata();
    String getType();

    default String getName() {
        return getMetadata().getName();
    }
}
