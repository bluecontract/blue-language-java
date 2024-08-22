package blue.language.preprocess.processor;

import blue.language.model.Node;
import blue.language.preprocess.TransformationProcessor;
import blue.language.utils.NodeTransformer;

import java.math.BigDecimal;
import java.math.BigInteger;

import static blue.language.utils.Properties.*;

public class InferBasicTypesForUntypedValues implements TransformationProcessor {
    @Override
    public Node process(Node document) {
        return NodeTransformer.transform(document, this::inferType);
    }

    private Node inferType(Node node) {
        if (node.getType() == null && node.getValue() != null) {
            Object value = node.getValue();
            if (value instanceof String) {
                node.type(new Node().blueId(TEXT_TYPE_BLUE_ID));
            } else if (value instanceof BigInteger) {
                node.type(new Node().blueId(INTEGER_TYPE_BLUE_ID));
            } else if (value instanceof BigDecimal) {
                node.type(new Node().blueId(DOUBLE_TYPE_BLUE_ID));
            } else if (value instanceof Boolean) {
                node.type(new Node().blueId(BOOLEAN_TYPE_BLUE_ID));
            }
        }
        return node;
    }
}