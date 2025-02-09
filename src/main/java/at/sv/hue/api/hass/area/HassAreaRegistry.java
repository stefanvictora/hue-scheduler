package at.sv.hue.api.hass.area;

import at.sv.hue.api.GroupInfo;
import at.sv.hue.api.LightNotFoundException;

public interface HassAreaRegistry {
    /**
     * Looks up the area associated with the given entity ID and retrieves information
     * about the area and its contained entities. If the entity is not found or the entity
     * does not belong to an area, appropriate behavior is handled, such as throwing an exception
     * or returning {@code null}.
     *
     * @param entityId the ID of the entity for which the area information should be retrieved
     * @return a {@code GroupInfo} object containing the area's ID and a list of associated entities,
     * or {@code null} if the entity does not belong to an area
     * @throws LightNotFoundException if no entity with the specified ID is found
     */
    GroupInfo lookupAreaForEntity(String entityId);

    /**
     * Clears all internal registry caches, forcing fresh data to be fetched on next lookup.
     */
    void clearCaches();
}
