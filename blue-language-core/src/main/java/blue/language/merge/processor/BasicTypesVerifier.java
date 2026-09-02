package blue.language.merge.processor;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.merge.MergingProcessor;
import blue.language.provider.NodeProvider;
import blue.language.merge.NodeResolver;
import blue.language.model.Node;

/**
 * Rejects resolved instances of scalar core types that also carry list or
 * object payloads.
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
            if (target.getItems() != null
                    || blue.language.model.Nodes.hasObjectPayload(target)) {
                String basicTypeName = EffectiveTypeChecks.findBasicTypeName(
                        target.getType(), nodeProvider, nodeResolver, typeIdentities);
                throw new IllegalArgumentException("Node of type \"" + target.getType().getName() +
                                                   "\" (which extends basic type \"" + basicTypeName +
                                                   "\") must not have items or properties.");
            }
        }
    }
}
