package blue.language.merge.processor;

import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.merge.NodeResolver;
import blue.language.merge.TypeEvidenceResolution;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.provider.NodeProvider;
import blue.language.provider.Types;
import blue.language.resolve.ResolutionLimits;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static blue.language.model.wire.BlueLanguageConstants.BOOLEAN_TYPE_BLUE_ID;

/** Resolver-aware mutable type checks shared by merge processors. */
final class EffectiveTypeChecks {

    private EffectiveTypeChecks() {
    }

    static boolean isSubtype(
            Node subtype,
            Node supertype,
            NodeProvider nodeProvider,
            NodeResolver nodeResolver,
            CanonicalTypeIdentityLookup typeIdentities) {
        PreparedTypes prepared = prepare(
                nodeResolver, typeIdentities, subtype, supertype);
        return Types.isSubtype(
                prepared.type(0),
                prepared.type(1),
                nodeProvider,
                prepared.identities());
    }

    static boolean isExactSubtype(
            Node exactSubtype,
            Node supertype,
            NodeProvider nodeProvider,
            NodeResolver nodeResolver,
            CanonicalTypeIdentityLookup typeIdentities) {
        if (exactSubtype == null || supertype == null) {
            return false;
        }
        PreparedTypes prepared = prepare(
                nodeResolver, typeIdentities, exactSubtype, supertype);
        return Types.isSubtype(
                prepared.type(0),
                prepared.type(1),
                nodeProvider,
                prepared.identities());
    }

    static boolean isCollectionMemberCompatible(
            Node exactMemberType,
            Node declaredMemberType,
            NodeProvider nodeProvider,
            NodeResolver nodeResolver,
            CanonicalTypeIdentityLookup typeIdentities) {
        if (isExactSubtype(
                exactMemberType,
                declaredMemberType,
                nodeProvider,
                nodeResolver,
                typeIdentities)) {
            return true;
        }
        if (!isPresenceOnlyConstraintWrapper(declaredMemberType)) {
            return false;
        }
        PreparedTypes prepared = prepare(
                nodeResolver,
                typeIdentities,
                exactMemberType,
                declaredMemberType.getType());
        return Types.isSubtype(
                prepared.type(0),
                prepared.type(1),
                nodeProvider,
                prepared.identities());
    }

    private static boolean isPresenceOnlyConstraintWrapper(Node node) {
        if (node == null
                || node.getType() == null
                || node.getSchema() == null
                || node.getBlueId() != null
                || node.getName() != null
                || node.getDescription() != null
                || node.getItemType() != null
                || node.getKeyType() != null
                || node.getValueType() != null
                || node.getValue() != null
                || node.getItems() != null
                || node.getProperties() != null
                || node.getContracts() != null
                || node.getMergePolicy() != null
                || node.getPreviousBlueId() != null
                || node.getPosition() != null
                || node.getBlue() != null
                || node.isInlineValue()) {
            return false;
        }
        Schema schema = node.getSchema();
        return schema.getBlueId() == null
                && isPlainBoolean(schema.getRequired())
                && schema.getMinLength() == null
                && schema.getMaxLength() == null
                && schema.getMinimum() == null
                && schema.getMaximum() == null
                && schema.getExclusiveMinimum() == null
                && schema.getExclusiveMaximum() == null
                && schema.getMultipleOf() == null
                && schema.getMinItems() == null
                && schema.getMaxItems() == null
                && schema.getUniqueItems() == null
                && schema.getMinFields() == null
                && schema.getMaxFields() == null
                && schema.getEnum() == null;
    }

    private static boolean isPlainBoolean(Node node) {
        return node != null
                && node.getValue() instanceof Boolean
                && node.getName() == null
                && node.getDescription() == null
                && (node.getType() == null
                || node.getType().isReferenceOnly()
                && BOOLEAN_TYPE_BLUE_ID.equals(
                        node.getType().getBlueId()))
                && node.getItemType() == null
                && node.getKeyType() == null
                && node.getValueType() == null
                && node.getItems() == null
                && node.getProperties() == null
                && node.getContracts() == null
                && node.getBlueId() == null
                && node.getSchema() == null
                && node.getMergePolicy() == null
                && node.getPreviousBlueId() == null
                && node.getPosition() == null
                && node.getBlue() == null
                && !node.isInlineValue();
    }

    static boolean isSubtypeOfBasicType(
            Node type,
            NodeProvider nodeProvider,
            NodeResolver nodeResolver,
            CanonicalTypeIdentityLookup typeIdentities) {
        PreparedTypes prepared = prepare(
                nodeResolver, typeIdentities, type);
        return Types.isSubtypeOfBasicType(
                prepared.type(0),
                nodeProvider,
                prepared.identities());
    }

    static String findBasicTypeName(
            Node type,
            NodeProvider nodeProvider,
            NodeResolver nodeResolver,
            CanonicalTypeIdentityLookup typeIdentities) {
        PreparedTypes prepared = prepare(
                nodeResolver, typeIdentities, type);
        return Types.findBasicTypeName(
                prepared.type(0),
                nodeProvider,
                prepared.identities());
    }

    static boolean isBasicType(
            Node type,
            NodeProvider nodeProvider,
            NodeResolver nodeResolver,
            CanonicalTypeIdentityLookup typeIdentities) {
        PreparedTypes prepared = prepare(
                nodeResolver, typeIdentities, type);
        return Types.isBasicType(
                prepared.type(0),
                nodeProvider,
                prepared.identities());
    }

    static boolean isTextType(
            Node type,
            NodeProvider nodeProvider,
            NodeResolver nodeResolver,
            CanonicalTypeIdentityLookup typeIdentities) {
        PreparedTypes prepared = prepare(
                nodeResolver, typeIdentities, type);
        return Types.isTextType(
                prepared.type(0),
                nodeProvider,
                prepared.identities());
    }

    static boolean isNumberType(
            Node type,
            NodeProvider nodeProvider,
            NodeResolver nodeResolver,
            CanonicalTypeIdentityLookup typeIdentities) {
        PreparedTypes prepared = prepare(
                nodeResolver, typeIdentities, type);
        return Types.isNumberType(
                prepared.type(0),
                nodeProvider,
                prepared.identities());
    }

    static boolean isIntegerType(
            Node type,
            NodeProvider nodeProvider,
            NodeResolver nodeResolver,
            CanonicalTypeIdentityLookup typeIdentities) {
        PreparedTypes prepared = prepare(
                nodeResolver, typeIdentities, type);
        return Types.isIntegerType(
                prepared.type(0),
                nodeProvider,
                prepared.identities());
    }

    static boolean isBooleanType(
            Node type,
            NodeProvider nodeProvider,
            NodeResolver nodeResolver,
            CanonicalTypeIdentityLookup typeIdentities) {
        PreparedTypes prepared = prepare(
                nodeResolver, typeIdentities, type);
        return Types.isBooleanType(
                prepared.type(0),
                nodeProvider,
                prepared.identities());
    }

    static boolean isListType(
            Node type,
            NodeProvider nodeProvider,
            NodeResolver nodeResolver,
            CanonicalTypeIdentityLookup typeIdentities) {
        PreparedTypes prepared = prepare(
                nodeResolver, typeIdentities, type);
        return Types.isListType(
                prepared.type(0),
                nodeProvider,
                prepared.identities());
    }

    static boolean isDictionaryType(
            Node type,
            NodeProvider nodeProvider,
            NodeResolver nodeResolver,
            CanonicalTypeIdentityLookup typeIdentities) {
        PreparedTypes prepared = prepare(
                nodeResolver, typeIdentities, type);
        return Types.isDictionaryType(
                prepared.type(0),
                nodeProvider,
                prepared.identities());
    }

    /**
     * Prepares types that merge processors must classify before normal child
     * traversal reaches their metadata.
     *
     * <p>The active invocation remains the first authority. If it has not yet
     * reached a materialized type, an independent resolver invocation proves
     * that exact type contribution and supplies the completed form together
     * with its sidecar identity. The comparison never hashes the materialized
     * body itself and never imports detached mutable state into the active
     * resolution.</p>
     */
    private static PreparedTypes prepare(
            NodeResolver nodeResolver,
            CanonicalTypeIdentityLookup typeIdentities,
            Node... types) {
        boolean requiresEvidence = false;
        for (Node type : types) {
            if (type != null && !type.isReferenceOnly()) {
                requiresEvidence = true;
                break;
            }
        }
        if (!requiresEvidence) {
            return new PreparedTypes(
                    types,
                    CanonicalTypeIdentityLookup.incomplete());
        }
        if (nodeResolver == null) {
            throw new IllegalStateException(
                    "Resolver-issued canonical type identity evidence "
                            + "is required for an expanded type");
        }

        CanonicalTypeIdentityLookup active = Objects.requireNonNull(
                typeIdentities,
                "typeIdentities");
        Node[] prepared = types.clone();
        List<CanonicalTypeIdentityLookup> evidence = new ArrayList<>();
        evidence.add(active);
        for (int i = 0; i < prepared.length; i++) {
            Node type = prepared[i];
            if (type == null || type.isReferenceOnly()
                    || active.findCanonicalTypeBlueId(type).isPresent()) {
                continue;
            }
            TypeEvidenceResolution proof = nodeResolver.resolveTypeEvidence(
                    new Node().type(type.clone()),
                    ResolutionLimits.NO_LIMITS);
            Node provenType = proof.resolvedRoot().getType().toNode();
            CanonicalTypeIdentityLookup provenIdentities = Objects.requireNonNull(
                    proof.canonicalTypeIdentities(),
                    "canonicalTypeIdentities");
            if (!provenIdentities.findCanonicalTypeBlueId(provenType)
                    .isPresent()) {
                /* Bare core names are classified intrinsically by Types. */
                if (provenType.getName() == null) {
                    throw new IllegalStateException(
                            "Detached type resolution produced no canonical "
                                    + "identity evidence");
                }
            }
            prepared[i] = provenType;
            evidence.add(provenIdentities);
        }
        return new PreparedTypes(
                prepared,
                new ComparisonIdentityLookup(evidence));
    }

    private static final class PreparedTypes {
        private final Node[] types;
        private final CanonicalTypeIdentityLookup identities;

        private PreparedTypes(
                Node[] types,
                CanonicalTypeIdentityLookup identities) {
            this.types = types;
            this.identities = identities;
        }

        private Node type(int index) {
            return types[index];
        }

        private CanonicalTypeIdentityLookup identities() {
            return identities;
        }
    }

    /**
     * Comparison-scoped evidence union. It intentionally never claims
     * complete whole-graph coverage.
     */
    private static final class ComparisonIdentityLookup
            implements CanonicalTypeIdentityLookup {
        private final List<CanonicalTypeIdentityLookup> constituents;

        private ComparisonIdentityLookup(
                List<CanonicalTypeIdentityLookup> constituents) {
            this.constituents = new ArrayList<>(constituents);
        }

        @Override
        public boolean hasCompleteCoverage() {
            return false;
        }

        @Override
        public Optional<String> findCanonicalTypeBlueId(Node completedType) {
            return findCanonicalTypeIdentityEvidence(completedType)
                    .map(CanonicalTypeIdentityEvidence::blueId);
        }

        @Override
        public Optional<CanonicalTypeIdentityEvidence>
        findCanonicalTypeIdentityEvidence(Node completedType) {
            return findCanonicalTypeIdentityEvidence(completedType, null);
        }

        @Override
        public Optional<CanonicalTypeIdentityEvidence>
        findCanonicalTypeIdentityEvidence(
                Node completedType,
                Node authoredTypeSource) {
            CanonicalTypeIdentityEvidence established = null;
            for (CanonicalTypeIdentityLookup constituent : constituents) {
                Optional<CanonicalTypeIdentityEvidence> candidate = constituent
                        .findCanonicalTypeIdentityEvidence(
                                completedType, authoredTypeSource);
                if (!candidate.isPresent()) {
                    continue;
                }
                established = established == null
                        ? candidate.get()
                        : established.combine(candidate.get());
            }
            return Optional.ofNullable(established);
        }

        @Override
        public String requireCanonicalTypeBlueId(Node completedType) {
            return findCanonicalTypeBlueId(completedType)
                    .orElseThrow(() -> new IllegalStateException(
                            "No resolver-issued canonical type identity "
                                    + "evidence covers this type comparison"));
        }
    }
}
