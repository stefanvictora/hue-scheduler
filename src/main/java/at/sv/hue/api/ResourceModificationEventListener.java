package at.sv.hue.api;

public interface ResourceModificationEventListener {
    /**
     * @param type    the type of the resource, e.g. "light", "zone"
     * @param id      the id of the resource
     * @param content the updated content. For HA this is the full new state.
     *                For Hue this contains the delta that was reported by the event stream. Consumers must handle
     *                {@code null}, as delete events do not provide any content.
     */
    void onModification(String type, String id, Object content);
}
