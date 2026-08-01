package blue.language.mapping;

import java.lang.reflect.Field;

/** Resolves effective Jackson property names across a class hierarchy. */
public class JacksonPropertyNames {

    /** Allows the legacy utility facade to inherit these operations. */
    protected JacksonPropertyNames() {
    }

    public static String propertyName(Field field) {
        return blue.language.utils.JacksonPropertyNames
                .propertyName(field);
    }

    public static String resolveTargetPropertyName(
            Class<?> valueClass, String fieldOrPropertyName) {
        return blue.language.utils.JacksonPropertyNames
                .resolveTargetPropertyName(
                        valueClass, fieldOrPropertyName);
    }

    public static Field findField(
            Class<?> valueClass, String fieldOrPropertyName) {
        return blue.language.utils.JacksonPropertyNames.findField(
                valueClass, fieldOrPropertyName);
    }
}
