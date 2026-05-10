package blue.language.utils;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.lang.reflect.Field;

public final class JacksonPropertyNames {

    private JacksonPropertyNames() {
    }

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

    public static String resolveTargetPropertyName(Class<?> clazz, String fieldOrPropertyName) {
        Field field = findField(clazz, fieldOrPropertyName);
        return field != null ? propertyName(field) : fieldOrPropertyName;
    }

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
