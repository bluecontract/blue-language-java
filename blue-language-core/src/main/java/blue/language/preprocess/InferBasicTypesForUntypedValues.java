package blue.language.preprocess;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.Node;

import java.math.BigDecimal;
import java.math.BigInteger;

import static blue.language.model.wire.BlueLanguageConstants.*;

/**
 * Assigns canonical core type references to untyped scalar values according to
 * their parsed Java value class.
 */
public class InferBasicTypesForUntypedValues implements TransformationProcessor {

    /**
     * Creates a stateless basic-type inference transformation.
     */
    public InferBasicTypesForUntypedValues() {
    }

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
