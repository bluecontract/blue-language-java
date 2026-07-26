package blue.language.merge.processor;

import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.merge.MergingProcessor;
import blue.language.merge.NodeResolver;
import blue.language.utils.Types;

import java.math.BigInteger;

public class ValuePropagator implements MergingProcessor {
    @Override
    public void process(Node target, Node source, NodeProvider nodeProvider, NodeResolver nodeResolver) {
        normalizeQuotedIntegerInInheritedContext(
                target, source, nodeProvider);
        if (source.getValue() != null) {
            if (target.getValue() == null)
                target.value(source.getValue());
            else if (!source.getValue().equals(target.getValue()))
                throw new IllegalArgumentException("Node values conflict. Source node value: " + source.getValue() +
                        ", target node value: " + target.getValue());
        }
        
    }

    private void normalizeQuotedIntegerInInheritedContext(
            Node target,
            Node source,
            NodeProvider nodeProvider) {
        if (!Types.isIntegerType(target.getType(), nodeProvider)
                || !Types.isTextType(source.getType(), nodeProvider)
                || !(source.getRawValue() instanceof String)) {
            return;
        }
        String decimal = (String) source.getRawValue();
        if (!decimal.matches("0|-?[1-9][0-9]*")) {
            throw new IllegalArgumentException(
                    "Integer type is incompatible with noncanonical decimal text: " + decimal);
        }
        BigInteger integer = new BigInteger(decimal);
        source.value(integer);
        source.type(target.getType().clone());
    }
}
