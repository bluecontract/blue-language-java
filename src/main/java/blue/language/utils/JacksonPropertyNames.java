package blue.language.utils;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.lang.reflect.Field;

/**
 * Resolves Java fields and their effective Jackson property names across a
 * class hierarchy.
 */
public final class JacksonPropertyNames {

    private JacksonPropertyNames() {
    }

    /**
     * Returns an explicit {@link JsonProperty} name or the Java field name.
     *
     * @param field field whose serialized name is required
     * @return effective serialized property name
     */
    public static String propertyName(Field field) {
        JsonProperty jsonProperty = field.getAnnotation(JsonProperty.class);
        if (jsonProperty != null
                && jsonProperty.value() != null
                && !jsonProperty.value().isEmpty()
                && !JsonProperty.USE_DEFAULT_NAME.equals(jsonProperty.value())) {
            return jsonProperty.value();
        }
        return field.getName();
    }

    /**
     * Resolves either a Java field name or serialized property name to the
     * effective serialized property name.
     *
     * @param clazz class hierarchy to search
     * @param fieldOrPropertyName Java field name or serialized property name
     * @return effective serialized property name
     */
    public static String resolveTargetPropertyName(Class<?> clazz, String fieldOrPropertyName) {
        Field field = findField(clazz, fieldOrPropertyName);
        return field != null ? propertyName(field) : fieldOrPropertyName;
    }

    /**
     * Finds a declared field by Java or serialized name, including superclasses.
     *
     * @param clazz class hierarchy to search
     * @param fieldOrPropertyName Java field name or serialized property name
     * @return matching field, or {@code null} when no field matches
     */
    public static Field findField(Class<?> clazz, String fieldOrPropertyName) {
        Class<?> current = clazz;
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getName().equals(fieldOrPropertyName) || propertyName(field).equals(fieldOrPropertyName)) {
                    return field;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }
}
