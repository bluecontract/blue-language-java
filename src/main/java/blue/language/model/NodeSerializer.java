package blue.language.model;

import blue.language.model.wire.NodeWireForm;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/**
 * Jackson serializer that projects a mutable {@link Node} to Blue's external
 * map/list/scalar representation rather than its internal Java fields.
 */
public class NodeSerializer extends JsonSerializer<Node> {

    /**
     * Creates a Blue node serializer.
     */
    public NodeSerializer() {
    }

    @Override
    public void serialize(Node node, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        Object nodeObject = NodeWireForm.get(node);
        gen.writeObject(nodeObject);
    }
}
