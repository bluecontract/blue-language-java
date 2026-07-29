package blue.language.model;

import blue.language.utils.Properties;

import blue.language.utils.BlueIdResolver;
import blue.language.utils.JacksonPropertyNames;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.BeanSerializerBase;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.*;

/**
 * Serializes annotated Java objects into Blue's type/reference and
 * name/description field shapes.
 *
 * <p>Classes without a resolvable type BlueId delegate to the original Jackson
 * bean serializer. Static constants and compiler-generated fields are omitted
 * because only per-instance state belongs in a Blue document.</p>
 */
public class BlueAnnotationsSerializer extends StdSerializer<Object> {
    /** Delegate used when a class has no resolvable Blue type identity. */
    private final BeanSerializerBase defaultSerializer;

    /**
     * Creates a serializer with the delegate used for non-Blue classes.
     *
     * @param defaultSerializer delegate bean serializer
     */
    public BlueAnnotationsSerializer(BeanSerializerBase defaultSerializer) {
        super(Object.class);
        this.defaultSerializer = defaultSerializer;
    }

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        Class<?> clazz = value.getClass();
        String typeBlueId = BlueIdResolver.resolveBlueId(clazz);

        if (typeBlueId != null) {
            gen.writeStartObject();

            gen.writeObjectFieldStart(Properties.OBJECT_TYPE);
            gen.writeStringField(Properties.OBJECT_BLUE_ID, typeBlueId);
            gen.writeEndObject();

            Map<String, Map<String, Object>> blueFields = new HashMap<>();
            Set<String> processedFields = new HashSet<>();

            for (Field field : getAllFields(clazz)) {
                field.setAccessible(true);
                String propertyName = JacksonPropertyNames.propertyName(field);
                Object fieldValue;
                try {
                    fieldValue = field.get(value);
                } catch (IllegalAccessException e) {
                    continue;
                }

                if (field.isAnnotationPresent(BlueId.class)) {
                    if (fieldValue != null) {
                        gen.writeObjectFieldStart(propertyName);
                        gen.writeStringField(Properties.OBJECT_BLUE_ID, fieldValue.toString());
                        gen.writeEndObject();
                    }
                    processedFields.add(propertyName);
                } else if (field.isAnnotationPresent(BlueName.class) || field.isAnnotationPresent(BlueDescription.class)) {
                    String targetFieldName = field.isAnnotationPresent(BlueName.class)
                            ? field.getAnnotation(BlueName.class).value()
                            : field.getAnnotation(BlueDescription.class).value();
                    String targetPropertyName = JacksonPropertyNames.resolveTargetPropertyName(clazz, targetFieldName);

                    blueFields.putIfAbsent(targetPropertyName, new HashMap<>());
                    Map<String, Object> blueFieldMap = blueFields.get(targetPropertyName);

                    if (field.isAnnotationPresent(BlueName.class)) {
                        blueFieldMap.put(Properties.OBJECT_NAME, fieldValue);
                    } else {
                        blueFieldMap.put(
                                Properties.OBJECT_DESCRIPTION, fieldValue);
                    }

                    Field targetFieldObj = JacksonPropertyNames.findField(clazz, targetFieldName);
                    if (targetFieldObj != null) {
                        targetFieldObj.setAccessible(true);
                        try {
                            Object targetFieldValue = targetFieldObj.get(value);
                            if (targetFieldValue instanceof Collection) {
                                blueFieldMap.put(Properties.OBJECT_ITEMS, targetFieldValue);
                            } else {
                                blueFieldMap.put(Properties.OBJECT_VALUE, targetFieldValue);
                            }
                        } catch (IllegalAccessException e) {
                            throw new RuntimeException(e);
                        }
                    }
                    processedFields.add(targetPropertyName);
                    processedFields.add(propertyName);
                }
            }

            for (Map.Entry<String, Map<String, Object>> entry : blueFields.entrySet()) {
                gen.writeObjectFieldStart(entry.getKey());
                for (Map.Entry<String, Object> fieldEntry : entry.getValue().entrySet()) {
                    gen.writeObjectField(fieldEntry.getKey(), fieldEntry.getValue());
                }
                gen.writeEndObject();
            }

            for (Field field : getAllFields(clazz)) {
                field.setAccessible(true);
                String propertyName = JacksonPropertyNames.propertyName(field);
                if (!processedFields.contains(propertyName)) {
                    try {
                        Object fieldValue = field.get(value);
                        gen.writeObjectField(propertyName, fieldValue);
                    } catch (IllegalAccessException e) {
                        throw new RuntimeException(e);
                    }
                }
            }

            gen.writeEndObject();
        } else {
            defaultSerializer.serialize(value, gen, provider);
        }
    }


    private List<Field> getAllFields(Class<?> clazz) {
        List<Field> fields = new ArrayList<>();
        while (clazz != null) {
            for (Field field : clazz.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        && !field.isSynthetic()) {
                    fields.add(field);
                }
            }
            clazz = clazz.getSuperclass();
        }
        return fields;
    }
}
