package at.sv.hue.api;

public interface ResourceModificationEventListener {
    /**
     * @param type the type of the resource, e.g. "light", "zone"
     * @param id   the id of the resource
     */
    void onModification(String type, String id);
}
