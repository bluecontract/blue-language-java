package blue.language.mapping;

import blue.language.model.TypeBlueId;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.ser.std.BeanSerializerBase;

/** Installs Blue annotation serialization for {@link TypeBlueId} classes. */
public class BlueAnnotationsBeanSerializerModifier
        extends BeanSerializerModifier {

    /** Creates a stateless serializer modifier for Blue annotations. */
    public BlueAnnotationsBeanSerializerModifier() {
    }

    @Override
    public JsonSerializer<?> modifySerializer(
            SerializationConfig config,
            BeanDescription beanDescription,
            JsonSerializer<?> serializer) {
        if (beanDescription.getBeanClass().isAnnotationPresent(TypeBlueId.class)
                && serializer instanceof BeanSerializerBase) {
            return new BlueAnnotationsSerializer(
                    (BeanSerializerBase) serializer);
        }
        return serializer;
    }
}
