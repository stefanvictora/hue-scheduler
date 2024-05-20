package at.sv.hue.api.hass;

import java.util.Locale;

public enum HassSupportedEntityType {
    LIGHT,
    INPUT_BOOLEAN,
    SWITCH,
    FAN;

    public static HassSupportedEntityType fromEntityId(String entityId) {
        int separatorIndex = entityId.indexOf('.');
        if (separatorIndex == -1) {
            return null;
        }
        String prefix = entityId.substring(0, separatorIndex);
        for (HassSupportedEntityType type : HassSupportedEntityType.values()) {
            if (type.name().equals(prefix.toUpperCase(Locale.ROOT))) {
                return type;
            }
        }
        return null;
    }

    public static boolean isSupportedEntityType(String entityId) {
        return fromEntityId(entityId) != null;
    }
}
