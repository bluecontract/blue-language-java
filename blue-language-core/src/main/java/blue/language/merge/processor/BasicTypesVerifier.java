package blue.language.merge.processor;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.merge.MergingProcessor;
import blue.language.provider.NodeProvider;
import blue.language.merge.NodeResolver;
import blue.language.model.Node;

import java.math.BigDecimal;
import java.math.BigInteger;

import static blue.language.model.wire.BlueLanguageConstants.BOOLEAN_TYPE;
import static blue.language.model.wire.BlueLanguageConstants.CORE_TYPE_NAME_TO_BLUE_ID_MAP;
import static blue.language.model.wire.BlueLanguageConstants.DOUBLE_TYPE;
import static blue.language.model.wire.BlueLanguageConstants.INTEGER_TYPE;
import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE;

/**
 * Checks that a supplied payload agrees with its effective scalar core type.
 */
public class BasicTypesVerifier implements MergingProcessor {

    /** Creates a stateless scalar payload verifier. */
    public BasicTypesVerifier() {
    }

    @Override
    public void process(
            Node target,
            Node source,
            NodeProvider nodeProvider,
            NodeResolver nodeResolver,
            CanonicalTypeIdentityLookup typeIdentities) {
        // do nothing
    }

    @Override
    public void postProcess(
            Node target,
            Node source,
            NodeProvider nodeProvider,
            NodeResolver nodeResolver,
            CanonicalTypeIdentityLookup typeIdentities) {
        if (target.getType() != null
                && EffectiveTypeChecks.isSubtypeOfBasicType(
                target.getType(), nodeProvider, nodeResolver, typeIdentities)) {
            String basicTypeName = EffectiveTypeChecks.findBasicTypeName(
                    target.getType(), nodeProvider, nodeResolver, typeIdentities);
            if (target.getItems() != null
                    || blue.language.model.Nodes.hasObjectPayload(target)) {
                throw new IllegalArgumentException("payload kinds conflict: Node of type \"" + target.getType().getName() +
                                                   "\" (which extends basic type \"" + basicTypeName +
                                                   "\") must not have items or properties.");
            }
            if (target.getRawValue() != null) {
                verifyScalarPayload(target, basicTypeName);
            }
        }
    }

    private void verifyScalarPayload(Node target, String basicTypeName) {
        /*
         * Core spelling normalization depends on the proven primitive domain,
         * not on whether the declared type is expanded, inline, or referenced.
         * This temporary view only checks compatibility; it never changes the
         * declared custom type or the scalar's exact representation.
         */
        Object value = new Node()
                .type(new Node().blueId(
                        CORE_TYPE_NAME_TO_BLUE_ID_MAP.get(basicTypeName)))
                .value(target.getRawValue())
                .getValue();
        boolean compatible = TEXT_TYPE.equals(basicTypeName)
                && value instanceof String
                || INTEGER_TYPE.equals(basicTypeName)
                && value instanceof BigInteger
                || DOUBLE_TYPE.equals(basicTypeName)
                && value instanceof BigDecimal
                || BOOLEAN_TYPE.equals(basicTypeName)
                && value instanceof Boolean;
        if (!compatible) {
            throw new IllegalArgumentException(
                    "Scalar payload is incompatible with effective "
                            + basicTypeName + " type.");
        }
    }
}
