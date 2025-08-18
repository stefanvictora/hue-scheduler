package at.sv.hue.api;

public interface ResourceModificationEventListener {
    /**
     * @param type    the type of the resource, e.g. "light", "zone"
     * @param id      the id of the resource
     * @param content the updated content. For HA this is the full new state.
     *                For Hue this is currently null (may be extended to carry changed properties in the future).
     *                Consumers must handle null.
     */
    void onModification(String type, String id, Object content);
}
