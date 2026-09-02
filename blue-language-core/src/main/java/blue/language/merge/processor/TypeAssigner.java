package blue.language.merge.processor;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.merge.MergingProcessor;
import blue.language.merge.NodeResolver;
import blue.language.model.Node;
import blue.language.provider.NodeProvider;
import blue.language.model.NodeWireForm;

import static blue.language.model.wire.BlueLanguageConstants.CORE_TYPE_BLUE_IDS;

/**
 * Applies a source declared type only when it is equal to or more specific than
 * the type already required by the target.
 */
public class TypeAssigner implements MergingProcessor {

    /**
     * Creates a stateless type-assignment stage.
     */
    public TypeAssigner() {
    }

    @Override
    public void process(
            Node target,
            Node source,
            NodeProvider nodeProvider,
            NodeResolver nodeResolver,
            CanonicalTypeIdentityLookup typeIdentities) {
        Node targetType = target.getType();
        Node sourceType = source.getType();
        if (targetType == null)
            target.type(sourceType);
        else if (sourceType != null) {
            boolean isSubtype = EffectiveTypeChecks.isSubtype(
                    sourceType,
                    targetType,
                    nodeProvider,
                    nodeResolver,
                    typeIdentities);
            /*
             * Primitive inference contributes an exact released core type to
             * a value before an inherited, more-specific declaration is
             * applied. This is merge compatibility, not reversed subtype
             * semantics: require the source to be an exact core reference and
             * prove that the retained target derives from it.
             */
            if (!isSubtype
                    && isExactCoreReference(sourceType)) {
                isSubtype = EffectiveTypeChecks.isSubtype(
                        targetType,
                        sourceType,
                        nodeProvider,
                        nodeResolver,
                        typeIdentities);
            }
            if (!isSubtype) {
                String errorMessage = String.format("The source type '%s' is not a subtype of the target type '%s'.",
                        NodeWireForm.get(sourceType), NodeWireForm.get(targetType));
                throw new IllegalArgumentException(errorMessage);
            }
            target.type(sourceType);
        }
    }

    private static boolean isExactCoreReference(Node type) {
        return type != null
                && type.isReferenceOnly()
                && CORE_TYPE_BLUE_IDS.contains(type.getBlueId());
    }
}
