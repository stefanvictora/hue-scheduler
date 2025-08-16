package at.sv.hue.api;

public interface ResourceModificationEventListener {
    /**
     * @param type the type of the resource, e.g. "light", "zone"
     * @param id   the id of the resource
     * @param content the updated content. For Hue only the changed properties, for HA the full new state.
     */
    void onModification(String type, String id, Object content);
}
