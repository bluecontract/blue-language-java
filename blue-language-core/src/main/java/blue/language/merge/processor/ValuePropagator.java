package blue.language.merge.processor;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.merge.MergingProcessor;
import blue.language.merge.NodeResolver;

import java.math.BigInteger;

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
