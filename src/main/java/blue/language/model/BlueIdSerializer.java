package blue.language.model;

import blue.language.utils.Properties;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.BeanSerializerBase;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;

public class BlueIdSerializer extends StdSerializer<Object> {
    private final BeanSerializerBase defaultSerializer;

    public BlueIdSerializer(BeanSerializerBase defaultSerializer) {
        super(Object.class);
        this.defaultSerializer = defaultSerializer;
    }

    @Override
    public void serialize(Object value, JsonGenerator gen, SerializerProvider provider) throws IOException {
        BlueId blueIdAnnotation = value.getClass().getAnnotation(BlueId.class);
        if (blueIdAnnotation != null) {
            gen.writeStartObject();

            String typeBlueId = blueIdAnnotation.defaultValue();
            if (typeBlueId.isEmpty()) {
                typeBlueId = blueIdAnnotation.value()[0];
            }
            gen.writeObjectFieldStart(Properties.OBJECT_TYPE);
            gen.writeStringField(Properties.OBJECT_BLUE_ID, typeBlueId);
            gen.writeEndObject();

            defaultSerializer.unwrappingSerializer(null).serialize(value, gen, provider);
            gen.writeEndObject();
        } else {
            defaultSerializer.serialize(value, gen, provider);
        }
    }
}