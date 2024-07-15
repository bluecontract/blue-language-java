package blue.language.model;

import blue.language.utils.NodeToObject;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

public class NodeSerializer extends JsonSerializer<Node> {
    @Override
    public void serialize(Node node, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        Object nodeObject = NodeToObject.get(node);
        gen.writeObject(nodeObject);
    }
}