package blue.language.model;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.std.BeanSerializerBase;

/**
 * Jackson hook that installs {@link BlueAnnotationsSerializer} for classes
 * carrying {@link TypeBlueId}; other bean serializers are left unchanged.
 */
public class BlueAnnotationsBeanSerializerModifier extends BeanSerializerModifier {

    /** Creates the stateless Blue annotation serializer hook. */
    public BlueAnnotationsBeanSerializerModifier() {
    }

    @Override
    public JsonSerializer<?> modifySerializer(SerializationConfig config, BeanDescription beanDesc, JsonSerializer<?> serializer) {
        if (beanDesc.getBeanClass().isAnnotationPresent(TypeBlueId.class) && serializer instanceof BeanSerializerBase)
            return new BlueAnnotationsSerializer((BeanSerializerBase) serializer);
        return serializer;
    }
}
