package blue.language.model;

import blue.language.utils.BlueIdResolver;
import blue.language.utils.JacksonPropertyNames;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.BeanSerializerBase;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.*;

public class BlueAnnotationsSerializer extends StdSerializer<Object> {
    private final BeanSerializerBase defaultSerializer;

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

            gen.writeObjectFieldStart("type");
            gen.writeStringField("blueId", typeBlueId);
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
                        gen.writeStringField("blueId", fieldValue.toString());
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
                        blueFieldMap.put("name", fieldValue);
                    } else {
                        blueFieldMap.put("description", fieldValue);
                    }

                    Field targetFieldObj = JacksonPropertyNames.findField(clazz, targetFieldName);
                    if (targetFieldObj != null) {
                        targetFieldObj.setAccessible(true);
                        try {
                            Object targetFieldValue = targetFieldObj.get(value);
                            if (targetFieldValue instanceof Collection) {
                                blueFieldMap.put("items", targetFieldValue);
                            } else {
                                blueFieldMap.put("value", targetFieldValue);
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
            fields.addAll(Arrays.asList(clazz.getDeclaredFields()));
            clazz = clazz.getSuperclass();
        }
        return fields;
    }
}
