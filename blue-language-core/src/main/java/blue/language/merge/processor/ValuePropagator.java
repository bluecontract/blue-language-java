package blue.language.merge.processor;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.merge.MergingProcessor;
import blue.language.merge.NodeResolver;

import java.math.BigInteger;
import java.util.LinkedHashMap;

/**
 * Propagates scalar values and rejects conflicting fixed values.
 *
 * <p>Canonical decimal text is normalized to an Integer only when inherited
 * type context requires Integer semantics.</p>
 */
public class ValuePropagator implements MergingProcessor {

    /**
     * Creates a stateless scalar-value propagation stage.
     */
    public ValuePropagator() {
    }

    @Override
    public void process(
            Node target,
            Node source,
            NodeProvider nodeProvider,
            NodeResolver nodeResolver,
            CanonicalTypeIdentityLookup typeIdentities) {
        validatePayloadCompatibility(target, source);
        normalizeQuotedIntegerInInheritedContext(
                target,
                source,
                nodeProvider,
                nodeResolver,
                typeIdentities);
        if (source.getValue() != null) {
            if (target.getValue() == null)
                target.value(source.getValue());
            else if (!source.getValue().equals(target.getValue()))
                throw new IllegalArgumentException("Node values conflict. Source node value: " + source.getValue() +
                        ", target node value: " + target.getValue());
        }
        
    }

    private void validatePayloadCompatibility(Node target, Node source) {
        boolean targetObject = Nodes.hasObjectPayload(target);
        boolean sourceObject = Nodes.hasObjectPayload(source);
        boolean targetScalar = target.getValue() != null;
        boolean sourceScalar = source.getValue() != null;
        boolean targetList = target.getItems() != null;
        boolean sourceList = source.getItems() != null;
        if (sourceObject && (targetScalar || targetList)
                || targetObject && (sourceScalar || sourceList)) {
            throw new IllegalArgumentException(
                    "Node payload kinds conflict: object, list, and scalar values cannot override one another.");
        }
        if (sourceObject
                && source.getProperties().isEmpty()
                && target.getProperties() == null) {
            target.properties(new LinkedHashMap<>());
        }
    }

    private void normalizeQuotedIntegerInInheritedContext(
            Node target,
            Node source,
            NodeProvider nodeProvider,
            NodeResolver nodeResolver,
            CanonicalTypeIdentityLookup typeIdentities) {
        if (!EffectiveTypeChecks.isIntegerType(
                target.getType(), nodeProvider, nodeResolver, typeIdentities)
                || !EffectiveTypeChecks.isTextType(
                source.getType(), nodeProvider, nodeResolver, typeIdentities)
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
