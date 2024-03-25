package blue.lang.model;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.std.BeanSerializerBase;

public class BlueIdBeanSerializerModifier extends BeanSerializerModifier {
    @Override
    public JsonSerializer<?> modifySerializer(SerializationConfig config, BeanDescription beanDesc, JsonSerializer<?> serializer) {
        if (beanDesc.getBeanClass().isAnnotationPresent(BlueId.class) && serializer instanceof BeanSerializerBase)
            return new BlueIdSerializer((BeanSerializerBase) serializer);
        return serializer;
    }
}