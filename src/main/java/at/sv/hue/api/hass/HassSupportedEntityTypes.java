package at.sv.hue.api.hass;

import java.util.Locale;

public enum HassSupportedEntityTypes {
    LIGHT,
    INPUT_BOOLEAN,
    SWITCH;

    public static HassSupportedEntityTypes fromEntityId(String entityId) {
        String prefix = entityId.substring(0, entityId.indexOf('.'));
        for (HassSupportedEntityTypes type : HassSupportedEntityTypes.values()) {
            if (type.name().equals(prefix.toUpperCase(Locale.ROOT))) {
                return type;
            }
        }
        return null;
    }
}
