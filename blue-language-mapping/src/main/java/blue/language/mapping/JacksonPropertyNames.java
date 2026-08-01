package blue.language.mapping;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.lang.reflect.Field;

/** Resolves effective Jackson property names across a class hierarchy. */
final class JacksonPropertyNames {

    private JacksonPropertyNames() {
    }

    static String propertyName(Field field) {
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

    static String resolveTargetPropertyName(
            Class<?> valueClass, String fieldOrPropertyName) {
        Field field = findField(valueClass, fieldOrPropertyName);
        return field != null ? propertyName(field) : fieldOrPropertyName;
    }

    static Field findField(
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
