package blue.language.matching;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.api.BlueCachePolicy;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.snapshot.FrozenNode;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.merge.TypeEvidenceResolution;

import blue.language.matching.FrozenTypeMatcherCacheKeys.MatchKey;
import blue.language.matching.FrozenTypeMatcherCacheKeys.TypeKey;
import blue.language.matching.FrozenTypeMatcherCacheKeys.TypePairKey;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

import static blue.language.matching.MatchingPlanCache.Region.MATCH;
import static blue.language.matching.MatchingPlanCache.Region.RESOLVED_REFERENCE;
import static blue.language.matching.MatchingPlanCache.Region.SUBTYPE;
import static blue.language.matching.MatchingPlanCache.Region.TYPE_COMPATIBILITY;
import static blue.language.matching.MatchingPlanCache.Region.UNRESOLVED_REFERENCE;
import static blue.language.model.wire.BlueLanguageConstants.*;

/**
 * Fast matcher for already-resolved immutable Blue nodes.
 *
 * <p>The matcher treats the second node as a resolved type/shape pattern. It
 * performs no full document resolve during matching. Ordinary instances use
 * their bound {@link MatchingRuntime} for type-reference lookup. Event-scoped
 * callers can instead use {@link #withVerifiedReferenceMaterializer(Function)}
 * to confine every lookup to an explicitly captured verified materialization
 * boundary. Resolved references are cached only for the lifetime of this
 * matcher instance.</p>
 */
public final class FrozenTypeMatcher {

    private final MatchingRuntime runtime;
    private final MatchingPlanCache planCache;
    private final FrozenSchemaMatcher schemaMatcher;
    private final boolean resolveCandidateReferences;
    private final Function<FrozenNode, TypeEvidenceResolution>
            verifiedReferenceMaterializer;
    private final MatchingCanonicalTypeIdentityEvidence typeIdentityEvidence;

    /**
     * Creates a matcher backed by the runtime's verified type materialization
     * and cache policy.
     *
     * @param runtime runtime used for verified type materialization and cache policy
     */
    public FrozenTypeMatcher(MatchingRuntime runtime) {
        this(runtime, true);
    }

    FrozenTypeMatcher(MatchingRuntime runtime, boolean resolveCandidateReferences) {
        this(runtime,
                resolveCandidateReferences,
                runtime != null
                        ? runtime.matchingCachePolicy()
                        : BlueCachePolicy.boundedDefaults());
    }

    FrozenTypeMatcher(MatchingRuntime runtime,
                      boolean resolveCandidateReferences,
                      BlueCachePolicy cachePolicy) {
        this(
                runtime,
                resolveCandidateReferences,
                cachePolicy,
                null,
                null);
    }

    FrozenTypeMatcher(
            MatchingRuntime runtime,
            boolean resolveCandidateReferences,
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        this(
                Objects.requireNonNull(runtime, "runtime"),
                resolveCandidateReferences,
                runtime.matchingCachePolicy(),
                null,
                Objects.requireNonNull(
                        canonicalTypeIdentities,
                        "canonicalTypeIdentities"));
    }

    private FrozenTypeMatcher(
            MatchingRuntime runtime,
            boolean resolveCandidateReferences,
            BlueCachePolicy cachePolicy,
            Function<FrozenNode, TypeEvidenceResolution>
                    verifiedReferenceMaterializer,
            CanonicalTypeIdentityLookup canonicalTypeIdentities) {
        this.runtime = runtime;
        this.resolveCandidateReferences = resolveCandidateReferences;
        this.verifiedReferenceMaterializer =
                verifiedReferenceMaterializer;
        this.planCache = new MatchingPlanCache(
                Objects.requireNonNull(cachePolicy, "cachePolicy"));
        this.typeIdentityEvidence =
                new MatchingCanonicalTypeIdentityEvidence(
                        planCache,
                        canonicalTypeIdentities);
        this.schemaMatcher = new FrozenSchemaMatcher(
                typeIdentityEvidence.effectiveLookup());
    }

    /**
     * Creates an independent matcher whose non-core reference lookups are
     * performed only through the supplied verified exact materializer.
     *
     * <p>The callback receives the original pure reference and returns both
     * its verified materialization and the canonical type identities issued
     * by that same resolution boundary. Its exceptions propagate unchanged,
     * and a null or still-reference-only result is rejected. This matcher
     * never attempts to re-verify the result by hashing its completed resolved
     * body. No ambient matching runtime, raw provider fallback, or
     * negative-result cache is consulted.</p>
     *
     * @param materializer callback that resolves one verified exact reference
     * @return independent matcher confined to the supplied materializer
     * @throws NullPointerException if {@code materializer} is null
     */
    public static FrozenTypeMatcher withVerifiedReferenceMaterializer(
            Function<FrozenNode, TypeEvidenceResolution> materializer) {
        return new FrozenTypeMatcher(
                null,
                true,
                BlueCachePolicy.boundedDefaults(),
                Objects.requireNonNull(
                        materializer,
                        "materializer"),
                null);
    }

    /**
     * Creates an exact matcher bound to one resolver invocation's type
     * identity evidence and verified reference materializer.
     *
     * <p>The lookup is required for completed inline types. It is never
     * replaced by hashing a resolved type body.</p>
     *
     * @param materializer verified exact reference materializer
     * @param typeIdentities resolver-issued canonical type identity evidence
     * @return matcher confined to the supplied evidence boundary
     */
    public static FrozenTypeMatcher withVerifiedTypeEvidence(
            Function<FrozenNode, TypeEvidenceResolution> materializer,
            CanonicalTypeIdentityLookup typeIdentities) {
        return new FrozenTypeMatcher(
                null,
                true,
                BlueCachePolicy.boundedDefaults(),
                Objects.requireNonNull(materializer, "materializer"),
                Objects.requireNonNull(typeIdentities, "typeIdentities"));
    }

    /**
     * Creates a structural matcher with no ambient provider lookup and with
     * explicitly bounded derived caches.
     *
     * @param cachePolicy bounds for matcher-owned derived caches
     * @return independent matcher without an ambient matching runtime
     */
    public static FrozenTypeMatcher withoutRuntime(
            BlueCachePolicy cachePolicy) {
        return new FrozenTypeMatcher(
                null,
                true,
                Objects.requireNonNull(cachePolicy, "cachePolicy"),
                null,
                null);
    }

    /**
     * Tests a resolved value against a resolved type/shape pattern.
     *
     * <p>A null pattern imposes no constraint. A null candidate matches only
     * when the pattern does not require presence.</p>
     *
     * @param resolvedNode resolved candidate value
     * @param resolvedTargetType resolved type or shape pattern
     * @return {@code true} when the candidate satisfies the pattern
     */
    public boolean matchesType(FrozenNode resolvedNode, FrozenNode resolvedTargetType) {
        typeIdentityEvidence.beginInvocation();
        try {
            if (resolvedTargetType == null) {
                return true;
            }
            if (resolvedNode == null) {
                return !requiresPresence(resolvedTargetType);
            }
            return matches(resolvedNode, resolvedTargetType);
        } finally {
            typeIdentityEvidence.endInvocation();
        }
    }

    /**
     * Tests one exact type against another using Blue's nominal subtype
     * rules, with a strict bound on the number of parent-type edges.
     *
     * <p>Unlike the structural matching entry point, this method performs
     * only type-lineage comparison. Missing exact definitions, a cyclic
     * lineage, and a lineage beyond {@code maximumTypeChainEdges} fail
     * closed.</p>
     *
     * @param candidateType exact candidate type definition or pure reference
     * @param targetType exact requested base type definition or pure reference
     * @param maximumTypeChainEdges maximum parent edges that may be traversed
     * @return whether the candidate is the target type or one of its subtypes
     */
    public boolean isSubtypeOrSame(
            FrozenNode candidateType,
            FrozenNode targetType,
            long maximumTypeChainEdges) {
        Objects.requireNonNull(candidateType, "candidateType");
        Objects.requireNonNull(targetType, "targetType");
        if (maximumTypeChainEdges < 0L) {
            throw new IllegalArgumentException(
                    "maximumTypeChainEdges must be non-negative");
        }

        typeIdentityEvidence.beginInvocation();
        try {
            FrozenNode current = candidateType;
            Set<String> visited = new HashSet<>();
            long traversedEdges = 0L;
            boolean matched = false;
            while (current != null) {
                String identity = requireExactTypeIdentity(current);
                if (!visited.add(identity)) {
                    throw new IllegalStateException(
                            "Type cycle in exact type hierarchy at "
                                    + identity);
                }
                if (identity.equals(
                        requireExactTypeIdentity(targetType))) {
                    matched = true;
                }

                FrozenNode resolved = resolveTypeReference(current);
                if (resolved == null) {
                    throw new IllegalStateException(
                            "Exact type definition is unavailable for "
                                    + identity);
                }
                FrozenNode parent = resolved.getType();
                if (parent == null) {
                    return matched;
                }
                if (traversedEdges >= maximumTypeChainEdges) {
                    throw new IllegalStateException(
                            "Exact type hierarchy exceeds "
                                    + maximumTypeChainEdges
                                    + " parent edges");
                }
                traversedEdges++;
                current = parent;
            }
            return matched;
        } finally {
            typeIdentityEvidence.endInvocation();
        }
    }

    /** Releases every reloadable matching and type-resolution cache entry. */
    public void clearCaches() {
        planCache.clear();
        typeIdentityEvidence.endInvocation();
    }

    /**
     * Returns the number of entries retained across all matcher cache regions.
     *
     * @return current retained cache-entry count
     */
    public int cacheEntryCount() {
        return planCache.size();
    }

    /**
     * Returns the approximate retained weight across all matcher cache regions.
     *
     * @return approximate retained cache weight in bytes
     */
    public long cacheWeightBytes() {
        return planCache.currentWeightBytes();
    }

    private boolean matches(FrozenNode node, FrozenNode target) {
        MatchKey key = new MatchKey(
                node.resolvedStructuralKey(),
                target.resolvedStructuralKey(),
                0L);
        Boolean cached = (Boolean) planCache.get(MATCH, key);
        if (cached != null) {
            return cached;
        }

        boolean result = computeMatch(node, target);
        MatchKey retainedKey = key.withRetainedWeightBytes(
                FrozenNode.approximateRetainedWeightBytesOf(node, target));
        planCache.put(MATCH, retainedKey, result);
        return result;
    }

    private boolean computeMatch(FrozenNode node, FrozenNode target) {
        if (target.isReferenceOnly()) {
            return referenceMatches(node, target.getReferenceBlueId());
        }
        if (resolveCandidateReferences && node.isReferenceOnly()) {
            FrozenNode resolvedNode = resolveTypeReference(node);
            if (resolvedNode != null && !resolvedNode.isReferenceOnly()) {
                return computeMatch(resolvedNode, target);
            }
        }

        if (!matchesDeclaredType(node, target.getType())) {
            return false;
        }
        if (!valuesEqualWhenSpecified(node.getValue(), target.getValue())) {
            return false;
        }
        if (!schemaMatcher.matches(node, target.getSchema())) {
            return false;
        }
        if (!matchesItemType(node, target.getItemType())) {
            return false;
        }
        if (!matchesKeyType(node, target.getKeyType())) {
            return false;
        }
        if (!matchesValueType(node, target.getValueType())) {
            return false;
        }
        if (!matchesItems(node, target.getItems())) {
            return false;
        }
        return matchesProperties(node, target.getProperties());
    }

    private boolean matchesDeclaredType(FrozenNode node, FrozenNode targetType) {
        if (targetType == null) {
            return true;
        }
        if (targetType.isReferenceOnly() && referenceMatches(node, targetType.getReferenceBlueId())) {
            return true;
        }
        if (matchesImplicitStructure(node, targetType)) {
            return true;
        }
        FrozenNode definition = resolveTypeReference(targetType);
        FrozenNode nodeType = node.getType();
        boolean declaredSubtype = nodeType != null && isSubtype(nodeType, targetType);
        boolean definitionConformance = hasTypeDefinitionConstraints(definition) && matches(node, definition);
        if (!declaredSubtype && !definitionConformance) {
            return false;
        }
        if (!matchesCorePayloadKind(node, targetType)) {
            return false;
        }
        return true;
    }

    private boolean matchesImplicitStructure(FrozenNode node, FrozenNode targetType) {
        if (node.getType() != null) {
            return false;
        }
        if (isTextType(targetType)
                || isIntegerType(targetType)
                || isDoubleType(targetType)
                || isBooleanType(targetType)) {
            return node.getValue() != null
                    && node.getItems() == null
                    && node.getProperties() == null
                    && matchesCorePayloadKind(node, targetType);
        }
        if (isListType(targetType)) {
            return node.getItems() != null && node.getValue() == null && node.getProperties() == null;
        }
        if (isDictionaryType(targetType)) {
            return node.getProperties() != null && node.getValue() == null && node.getItems() == null;
        }
        return false;
    }

    private boolean matchesCorePayloadKind(FrozenNode node, FrozenNode targetType) {
        if (isTextType(targetType)) {
            return node.getValue() == null || node.getValue() instanceof String;
        }
        if (isIntegerType(targetType)) {
            return node.getValue() == null || node.getValue() instanceof BigInteger;
        }
        if (isDoubleType(targetType)) {
            return node.getValue() == null
                    || node.getValue() instanceof BigDecimal
                    || node.getValue() instanceof BigInteger;
        }
        if (isBooleanType(targetType)) {
            return node.getValue() == null || node.getValue() instanceof Boolean;
        }
        if (isListType(targetType)) {
            return node.getValue() == null && node.getProperties() == null;
        }
        if (isDictionaryType(targetType)) {
            return node.getValue() == null && node.getItems() == null;
        }
        return true;
    }

    private boolean hasTypeDefinitionConstraints(FrozenNode definition) {
        if (definition == null || isCoreType(definition)) {
            return false;
        }
        return definition.getType() != null
                || definition.getItemType() != null
                || definition.getKeyType() != null
                || definition.getValueType() != null
                || definition.getValue() != null
                || definition.getItems() != null
                || definition.getProperties() != null
                || definition.getSchema() != null;
    }

    private boolean referenceMatches(FrozenNode node, String targetBlueId) {
        if (targetBlueId == null) {
            return true;
        }
        if (targetBlueId.equals(node.getReferenceBlueId())) {
            return true;
        }
        if (node.isStrictCanonical()
                && targetBlueId.equals(node.blueId())) {
            return true;
        }
        FrozenNode nodeType = node.getType();
        return nodeType != null
                && targetBlueId.equals(canonicalTypeIdentity(nodeType));
    }

    private boolean valuesEqualWhenSpecified(Object nodeValue, Object targetValue) {
        if (targetValue == null) {
            return true;
        }
        if (nodeValue == null) {
            return false;
        }
        if (nodeValue instanceof Number && targetValue instanceof Number) {
            return numberValue(nodeValue).compareTo(numberValue(targetValue)) == 0;
        }
        return nodeValue.equals(targetValue);
    }

    private BigDecimal numberValue(Object value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof BigInteger) {
            return new BigDecimal((BigInteger) value);
        }
        return new BigDecimal(value.toString());
    }

    private boolean matchesItemType(FrozenNode node, FrozenNode targetItemType) {
        if (targetItemType == null) {
            return true;
        }
        if (!isListShaped(node)) {
            return false;
        }
        FrozenNode nodeItemType = node.getItemType();
        boolean declaredCompatible = nodeItemType == null || isSubtype(nodeItemType, targetItemType);
        List<FrozenNode> items = node.getItems();
        if (items == null) {
            return declaredCompatible;
        }
        for (FrozenNode item : items) {
            if (!matchesDeclaredType(item, targetItemType)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesKeyType(FrozenNode node, FrozenNode targetKeyType) {
        if (targetKeyType == null) {
            return true;
        }
        if (!isDictionaryShaped(node)) {
            return false;
        }
        FrozenNode nodeKeyType = node.getKeyType();
        boolean declaredCompatible = nodeKeyType == null || isSubtype(nodeKeyType, targetKeyType);
        Map<String, FrozenNode> properties = node.getProperties();
        if (properties == null) {
            return declaredCompatible;
        }
        for (String key : properties.keySet()) {
            if (!keyMatchesType(key, targetKeyType)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesValueType(FrozenNode node, FrozenNode targetValueType) {
        if (targetValueType == null) {
            return true;
        }
        if (!isDictionaryShaped(node)) {
            return false;
        }
        FrozenNode nodeValueType = node.getValueType();
        boolean declaredCompatible = nodeValueType == null || isSubtype(nodeValueType, targetValueType);
        Map<String, FrozenNode> properties = node.getProperties();
        if (properties == null) {
            return declaredCompatible;
        }
        for (FrozenNode value : properties.values()) {
            if (!matchesDeclaredType(value, targetValueType)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesItems(FrozenNode node, List<FrozenNode> targetItems) {
        if (targetItems == null) {
            return true;
        }
        if (!isListShaped(node)) {
            return false;
        }
        List<FrozenNode> nodeItems = node.getItems() != null ? node.getItems() : Collections.<FrozenNode>emptyList();
        for (int i = 0; i < targetItems.size(); i++) {
            FrozenNode targetItem = targetItems.get(i);
            if (i < nodeItems.size()) {
                if (!matches(nodeItems.get(i), targetItem)) {
                    return false;
                }
            } else if (requiresPresence(targetItem)) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesProperties(FrozenNode node, Map<String, FrozenNode> targetProperties) {
        if (targetProperties == null) {
            return true;
        }
        if (!isDictionaryShaped(node)) {
            return false;
        }
        Map<String, FrozenNode> nodeProperties = node.getProperties() != null
                ? node.getProperties()
                : Collections.<String, FrozenNode>emptyMap();
        for (Map.Entry<String, FrozenNode> entry : targetProperties.entrySet()) {
            FrozenNode nodeProperty = nodeProperties.get(entry.getKey());
            FrozenNode targetProperty = entry.getValue();
            if (nodeProperty != null) {
                if (!matches(nodeProperty, targetProperty)) {
                    return false;
                }
            } else if (requiresPresence(targetProperty)) {
                return false;
            }
        }
        return true;
    }

    private boolean requiresPresence(FrozenNode target) {
        Schema schema = target.getSchema();
        if (schema != null && Boolean.TRUE.equals(schema.getRequiredValue())) {
            return true;
        }
        return hasValueInNestedStructure(target);
    }

    private boolean hasValueInNestedStructure(FrozenNode node) {
        if (node.isReferenceOnly()) {
            return true;
        }
        if (node.getValue() != null) {
            return true;
        }
        if (node.getItems() != null) {
            for (FrozenNode item : node.getItems()) {
                if (hasValueInNestedStructure(item)) {
                    return true;
                }
            }
        }
        if (node.getProperties() != null) {
            for (FrozenNode property : node.getProperties().values()) {
                if (hasValueInNestedStructure(property)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isListShaped(FrozenNode node) {
        return node.getItems() != null
                || node.getItemType() != null
                || (node.getType() != null && isListType(node.getType()));
    }

    private boolean isDictionaryShaped(FrozenNode node) {
        return node.getProperties() != null
                || node.getKeyType() != null
                || node.getValueType() != null
                || (node.getType() != null && isDictionaryType(node.getType()));
    }

    private boolean keyMatchesType(String key, FrozenNode targetKeyType) {
        if (isTextType(targetKeyType)) {
            return true;
        }
        if (isIntegerType(targetKeyType)) {
            try {
                new BigInteger(key);
                return true;
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        if (isDoubleType(targetKeyType)) {
            try {
                double value = Double.parseDouble(key);
                return Double.isFinite(value);
            } catch (NumberFormatException ex) {
                return false;
            }
        }
        if (isBooleanType(targetKeyType)) {
            return BlueLanguageConstants.BOOLEAN_TEXT_TRUE.equals(key)
                    || BlueLanguageConstants.BOOLEAN_TEXT_FALSE.equals(key);
        }
        return false;
    }

    private boolean isSubtype(FrozenNode candidateType, FrozenNode targetType) {
        if (candidateType == null || targetType == null) {
            return false;
        }
        TypePairKey key = new TypePairKey(
                typeKey(candidateType),
                typeKey(targetType));
        Boolean cached = (Boolean) planCache.get(SUBTYPE, key);
        if (cached != null) {
            return cached;
        }

        boolean result = computeSubtype(candidateType, targetType);
        planCache.put(SUBTYPE, key, result);
        return result;
    }

    private boolean computeSubtype(FrozenNode candidateType, FrozenNode targetType) {
        FrozenNode current = resolveTypeReference(candidateType);
        Set<TypeKey> visited = new HashSet<>();
        while (current != null) {
            TypeKey identity = typeKey(current);
            if (!visited.add(identity)) {
                return false;
            }
            if (sameType(current, targetType)) {
                return true;
            }
            current = parentType(current);
        }
        return false;
    }

    private FrozenNode parentType(FrozenNode type) {
        FrozenNode resolved = resolveTypeReference(type);
        if (resolved == null) {
            return null;
        }
        return resolved.getType();
    }

    private FrozenNode resolveTypeReference(FrozenNode type) {
        if (type == null) {
            return null;
        }
        if (!type.isReferenceOnly()) {
            return type;
        }
        String blueId = type.getReferenceBlueId();
        if (CORE_TYPE_BLUE_IDS.contains(blueId)) {
            return coreType(blueId);
        }
        FrozenNode cached = (FrozenNode) planCache.get(RESOLVED_REFERENCE, blueId);
        if (cached != null
                && typeIdentityEvidence
                        .hasRetainedMaterializationEvidence(blueId)) {
            return cached;
        }
        if (verifiedReferenceMaterializer != null) {
            TypeEvidenceResolution materialization =
                    verifiedReferenceMaterializer.apply(type);
            if (materialization == null) {
                throw new IllegalArgumentException(
                        "Verified reference materializer returned no content for "
                                + blueId);
            }
            FrozenNode materialized = materialization.resolvedRoot();
            if (materialized.isReferenceOnly()) {
                throw new IllegalArgumentException(
                        "Verified reference materializer retained a pure reference for "
                                + blueId);
            }
            typeIdentityEvidence.retainMaterializedTypeEvidence(
                    blueId,
                    materialized,
                    materialization.canonicalTypeIdentities());
            planCache.put(
                    RESOLVED_REFERENCE,
                    blueId,
                    materialized);
            return materialized;
        }
        if (planCache.get(UNRESOLVED_REFERENCE, blueId) != null) {
            return null;
        }
        TypeEvidenceResolution materialization = null;
        if (runtime != null) {
            materialization = runtime
                    .materializeTypeReferenceForMatching(type);
        }
        FrozenNode resolved = materialization != null
                ? materialization.resolvedRoot()
                : null;
        if (resolved == null) {
            planCache.put(UNRESOLVED_REFERENCE, blueId, Boolean.TRUE);
            return null;
        }
        if (resolved.isReferenceOnly()) {
            planCache.put(UNRESOLVED_REFERENCE, blueId, Boolean.TRUE);
            return null;
        }
        typeIdentityEvidence.retainMaterializedTypeEvidence(
                blueId,
                resolved,
                materialization.canonicalTypeIdentities());
        planCache.put(RESOLVED_REFERENCE, blueId, resolved);
        return resolved;
    }

    private FrozenNode coreType(String blueId) {
        FrozenNode cached = (FrozenNode) planCache.get(RESOLVED_REFERENCE, blueId);
        if (cached != null) {
            return cached;
        }
        FrozenNode core = FrozenNode.fromResolvedNode(new Node().blueId(blueId));
        planCache.put(RESOLVED_REFERENCE, blueId, core);
        return core;
    }

    private boolean sameType(FrozenNode left, FrozenNode right) {
        String leftIdentity = canonicalTypeIdentity(left);
        String rightIdentity = canonicalTypeIdentity(right);
        if (leftIdentity != null && leftIdentity.equals(rightIdentity)) {
            return true;
        }
        String leftCompatibility = typeCompatibilityIdentity(left);
        String rightCompatibility = typeCompatibilityIdentity(right);
        if (leftIdentity != null && CORE_TYPE_BLUE_IDS.contains(leftIdentity)
                || rightIdentity != null
                && CORE_TYPE_BLUE_IDS.contains(rightIdentity)) {
            return false;
        }
        return leftCompatibility.equals(rightCompatibility);
    }

    private String requireExactTypeIdentity(FrozenNode type) {
        return typeIdentityEvidence.requireCanonicalTypeBlueId(type);
    }

    private String canonicalTypeIdentity(FrozenNode type) {
        return typeIdentityEvidence.canonicalTypeBlueId(type);
    }

    private boolean isCoreType(FrozenNode type) {
        String identity = canonicalTypeIdentity(type);
        return identity != null && CORE_TYPE_BLUE_IDS.contains(identity);
    }

    private TypeKey typeKey(FrozenNode type) {
        String identity = canonicalTypeIdentity(type);
        return identity != null
                ? TypeKey.canonical(identity)
                : TypeKey.structural(type.resolvedStructuralKey());
    }

    private String typeCompatibilityIdentity(FrozenNode type) {
        FrozenNode resolved = type.isReferenceOnly() ? resolveTypeReference(type) : type;
        if (resolved == null) {
            String identity = canonicalTypeIdentity(type);
            if (identity == null) {
                throw new IllegalStateException(
                        "Verified type materialization is unavailable");
            }
            return "exact-type:" + identity;
        }
        String identityBlueId = canonicalTypeIdentity(resolved);
        if (CORE_TYPE_BLUE_IDS.contains(identityBlueId)) {
            return "exact-type:" + identityBlueId;
        }
        TypeKey cacheKey = typeKey(resolved);
        String cached = (String) planCache.get(TYPE_COMPATIBILITY, cacheKey);
        if (cached != null) {
            return cached;
        }
        String identity = LabelNeutralTypeIdentity
                .calculateCompatibilityFingerprint(
                        resolved,
                        this::resolveTypeReference);
        planCache.put(TYPE_COMPATIBILITY, cacheKey, identity);
        return identity;
    }

    private boolean isTextType(FrozenNode type) {
        return isSubtype(type, coreType(TEXT_TYPE_BLUE_ID));
    }

    private boolean isIntegerType(FrozenNode type) {
        return isSubtype(type, coreType(INTEGER_TYPE_BLUE_ID));
    }

    private boolean isDoubleType(FrozenNode type) {
        return isSubtype(type, coreType(DOUBLE_TYPE_BLUE_ID));
    }

    private boolean isBooleanType(FrozenNode type) {
        return isSubtype(type, coreType(BOOLEAN_TYPE_BLUE_ID));
    }

    private boolean isListType(FrozenNode type) {
        return isSubtype(type, coreType(LIST_TYPE_BLUE_ID));
    }

    private boolean isDictionaryType(FrozenNode type) {
        return isSubtype(type, coreType(DICTIONARY_TYPE_BLUE_ID));
    }

}
