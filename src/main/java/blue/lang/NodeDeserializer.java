package blue.lang;

import blue.lang.utils.UncheckedObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static blue.lang.utils.Properties.OBJECT_NAME;
import static blue.lang.utils.Properties.OBJECT_TYPE;
import static blue.lang.utils.UncheckedObjectMapper.YAML_MAPPER;

public class NodeDeserializer extends StdDeserializer<Node> {

    protected NodeDeserializer() {
        super(Node.class);
    }

    @Override
    public Node deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        TreeNode treeNode = p.readValueAsTree();
        Node obj = new Node();
        Map<String, Node> properties = new LinkedHashMap<>();

        if (treeNode.isObject()) {
            for (Iterator<Map.Entry<String, JsonNode>> it = ((JsonNode) treeNode).fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> entry = it.next();
                String key = entry.getKey();
                JsonNode value = entry.getValue();
                if (OBJECT_NAME.equals(key))
                    obj.name(value.asText());
                else if (OBJECT_TYPE.equals(key))
                    obj.type(value.asText());
                else
                    properties.put(key, handleNode(value));
            }
        }

        obj.properties(properties);
        return obj;
    }

    private Node handleNode(JsonNode node) {
        if (node.isTextual())
            return new Node().value(node.asText());
        else if (node.isBigInteger() || node.isInt() || node.isLong())
            return new Node().value(node.bigIntegerValue());
        else if (node.isFloatingPointNumber())
            return new Node().value(node.decimalValue());
        else if (node.isBoolean())
            return new Node().value(node.asBoolean());
        else if (node.isObject())
            return UncheckedObjectMapper.YAML_MAPPER.treeToValue(node, Node.class);
        else if (node.isArray())
            return handleArray(node);
        else if (node.isNull())
            return null;
        throw new IllegalArgumentException("Can't handle node: " + node);
    }

    private Node handleArray(JsonNode value) {
        ArrayNode arrayNode = (ArrayNode) value;
        List<Node> result = StreamSupport.stream(arrayNode.spliterator(), false)
                .map(this::handleNode)
                .collect(Collectors.toList());
        return new Node().items(result);
    }

    public static void main2(String[] args) throws IOException {
        Node object = YAML_MAPPER.readValue(new File("src/test/resources/sample.blue"), Node.class);
        System.out.println(YAML_MAPPER.writeValueAsString(object));
    }

}