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
        JsonProperty property = field.getAnnotation(JsonProperty.class);
        if (property != null
                && property.value() != null
                && !property.value().isEmpty()
                && !JsonProperty.USE_DEFAULT_NAME.equals(
                property.value())) {
            return property.value();
        }
        return field.getName();
    }

    /**
     * Resolves a Java field or serialized property name to its wire name.
     *
     * @param valueClass class hierarchy to search
     * @param fieldOrPropertyName Java field or serialized property name
     * @return effective serialized property name
     */
    public static String resolveTargetPropertyName(
            Class<?> valueClass, String fieldOrPropertyName) {
        Field field = findField(valueClass, fieldOrPropertyName);
        return field != null ? propertyName(field) : fieldOrPropertyName;
    }

    /**
     * Finds a declared field by Java or serialized name, including ancestors.
     *
     * @param valueClass class hierarchy to search
     * @param fieldOrPropertyName Java field or serialized property name
     * @return matching field, or {@code null}
     */
    public static Field findField(
            Class<?> valueClass, String fieldOrPropertyName) {
        Class<?> current = valueClass;
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (field.getName().equals(fieldOrPropertyName)
                        || propertyName(field).equals(
                        fieldOrPropertyName)) {
                    return field;
                }
            }
            current = current.getSuperclass();
        }
        return null;
    }
}
