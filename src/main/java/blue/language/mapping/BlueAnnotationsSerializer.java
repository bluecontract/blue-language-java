package blue.language.mapping;

import blue.language.model.BlueDescription;
import blue.language.model.BlueId;
import blue.language.model.BlueName;
import blue.language.model.wire.BlueLanguageConstants;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.BeanSerializerBase;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Serializes Blue-annotated Java objects into their Language wire shape. */
public class BlueAnnotationsSerializer extends StdSerializer<Object> {

    private final BeanSerializerBase defaultSerializer;

    public BlueAnnotationsSerializer(BeanSerializerBase defaultSerializer) {
        super(Object.class);
        this.defaultSerializer = defaultSerializer;
    }

    @Override
    public void serialize(
            Object value,
            JsonGenerator generator,
            SerializerProvider provider) throws IOException {
        Class<?> valueClass = value.getClass();
        String typeBlueId = BlueIdResolver.resolveBlueId(valueClass);
        if (typeBlueId == null) {
            defaultSerializer.serialize(value, generator, provider);
            return;
        }

        generator.writeStartObject();
        generator.writeObjectFieldStart(BlueLanguageConstants.OBJECT_TYPE);
        generator.writeStringField(
                BlueLanguageConstants.OBJECT_BLUE_ID, typeBlueId);
        generator.writeEndObject();

        Map<String, Map<String, Object>> blueFields = new HashMap<>();
        Set<String> processedFields = new HashSet<>();
        for (Field field : getAllFields(valueClass)) {
            field.setAccessible(true);
            String propertyName = JacksonPropertyNames.propertyName(field);
            Object fieldValue;
            try {
                fieldValue = field.get(value);
            } catch (IllegalAccessException ignored) {
                continue;
            }

            if (field.isAnnotationPresent(BlueId.class)) {
                if (fieldValue != null) {
                    generator.writeObjectFieldStart(propertyName);
                    generator.writeStringField(
                            BlueLanguageConstants.OBJECT_BLUE_ID,
                            fieldValue.toString());
                    generator.writeEndObject();
                }
                processedFields.add(propertyName);
                continue;
            }
            if (field.isAnnotationPresent(BlueName.class)
                    || field.isAnnotationPresent(BlueDescription.class)) {
                collectLabeledField(value, valueClass, field, fieldValue,
                        blueFields, processedFields, propertyName);
            }
        }

        for (Map.Entry<String, Map<String, Object>> entry
                : blueFields.entrySet()) {
            generator.writeObjectFieldStart(entry.getKey());
            for (Map.Entry<String, Object> fieldEntry
                    : entry.getValue().entrySet()) {
                generator.writeObjectField(
                        fieldEntry.getKey(), fieldEntry.getValue());
            }
            generator.writeEndObject();
        }
        for (Field field : getAllFields(valueClass)) {
            field.setAccessible(true);
            String propertyName = JacksonPropertyNames.propertyName(field);
            if (!processedFields.contains(propertyName)) {
                try {
                    generator.writeObjectField(
                            propertyName, field.get(value));
                } catch (IllegalAccessException exception) {
                    throw new IllegalStateException(exception);
                }
            }
        }
        generator.writeEndObject();
    }

    private void collectLabeledField(
            Object value,
            Class<?> valueClass,
            Field field,
            Object fieldValue,
            Map<String, Map<String, Object>> blueFields,
            Set<String> processedFields,
            String propertyName) {
        boolean name = field.isAnnotationPresent(BlueName.class);
        String targetFieldName = name
                ? field.getAnnotation(BlueName.class).value()
                : field.getAnnotation(BlueDescription.class).value();
        String targetPropertyName = JacksonPropertyNames
                .resolveTargetPropertyName(valueClass, targetFieldName);
        Map<String, Object> blueField = blueFields.computeIfAbsent(
                targetPropertyName, ignored -> new HashMap<>());
        blueField.put(name
                ? BlueLanguageConstants.OBJECT_NAME
                : BlueLanguageConstants.OBJECT_DESCRIPTION, fieldValue);

        Field targetField = JacksonPropertyNames.findField(
                valueClass, targetFieldName);
        if (targetField != null) {
            targetField.setAccessible(true);
            try {
                Object targetValue = targetField.get(value);
                blueField.put(targetValue instanceof Collection
                                ? BlueLanguageConstants.OBJECT_ITEMS
                                : BlueLanguageConstants.OBJECT_VALUE,
                        targetValue);
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException(exception);
            }
        }
        processedFields.add(targetPropertyName);
        processedFields.add(propertyName);
    }

    private List<Field> getAllFields(Class<?> valueClass) {
        List<Field> fields = new ArrayList<>();
        Class<?> current = valueClass;
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        && !field.isSynthetic()) {
                    fields.add(field);
                }
            }
            current = current.getSuperclass();
        }
        return fields;
    }
}
