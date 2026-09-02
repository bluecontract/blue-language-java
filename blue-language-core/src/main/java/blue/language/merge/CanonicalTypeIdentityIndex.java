package blue.language.merge;

import blue.language.identity.BlueIdReferenceValidator;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.NodeToBlueIdInput;
import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Invocation-local evidence that binds a completed effective type to its
 * canonical identity.
 *
 * <p>The identity map is the hot path while one mutable resolution graph is
 * active. A normalized frozen structural key makes the same evidence usable
 * after defensive cloning and across verified-reference cache boundaries.
 * Verified requested-reference BlueIds remain part of that key. They are
 * provenance, not disposable annotations: two exact type references may
 * complete to the same semantic body while retaining distinct canonical
 * identities. Inline completed types have no requested-reference BlueId and
 * therefore occupy their own structural key.</p>
 */
final class CanonicalTypeIdentityIndex
        implements CanonicalTypeIdentityLookup {

    private static final long SNAPSHOT_ENTRY_OVERHEAD_BYTES = 96L;
    private static final int CHARACTER_BYTES = 2;

    private final Map<Node, SemanticTypeEvidenceKey> keyByIdentity =
            new IdentityHashMap<>();
    private final Map<Node, Evidence> evidenceByIdentity =
            new IdentityHashMap<>();
    private final Map<SemanticTypeEvidenceKey, Map<String, Evidence>>
            evidenceByStructure = new HashMap<>();
    private boolean completeCoverage;
    private boolean coverageGap;
    private Node completeCoverageRoot;

    /** Distinguishes independently derived and provider-verified evidence. */
    enum EvidenceKind {
        AUTHORED_INLINE,
        VERIFIED_REFERENCE,
        AUTHORED_INLINE_AND_VERIFIED_REFERENCE;

        EvidenceKind combine(EvidenceKind other) {
            if (this == other) {
                return this;
            }
            return AUTHORED_INLINE_AND_VERIFIED_REFERENCE;
        }
    }

    /** Immutable identity evidence for one normalized effective-type shape. */
    static final class Evidence {
        private final String blueId;
        private final EvidenceKind kind;
        private final FrozenNode canonicalTypeIdentityInput;
        private final FrozenNode authoredTypeSource;

        private Evidence(
                String blueId,
                EvidenceKind kind,
                FrozenNode canonicalTypeIdentityInput,
                FrozenNode authoredTypeSource) {
            this.blueId = requireBlueId(blueId);
            this.kind = Objects.requireNonNull(kind, "kind");
            this.canonicalTypeIdentityInput =
                    canonicalTypeIdentityInput;
            this.authoredTypeSource = authoredTypeSource;
            boolean hasInlineEvidence = canonicalTypeIdentityInput != null
                    && authoredTypeSource != null;
            if ((canonicalTypeIdentityInput == null)
                    != (authoredTypeSource == null)
                    || (kind == EvidenceKind.VERIFIED_REFERENCE)
                    == hasInlineEvidence) {
                throw new IllegalArgumentException(
                        "Canonical type evidence kind, identity input, and "
                                + "authored Source must agree");
            }
        }

        String blueId() {
            return blueId;
        }

        EvidenceKind kind() {
            return kind;
        }

        Evidence combine(Evidence other) {
            if (!blueId.equals(other.blueId)) {
                throw new IllegalStateException(
                        "Conflicting canonical type identities for one "
                                + "resolved structure: " + blueId + " and "
                                + other.blueId + " (" + kind + " versus "
                                + other.kind + ")");
            }
            EvidenceKind combined = kind.combine(other.kind);
            Evidence inlineEvidence = selectRetainedInlineEvidence(
                    this, other);
            if (combined == kind && inlineEvidence == this) {
                return this;
            }
            if (combined == other.kind
                    && inlineEvidence == other) {
                return other;
            }
            return new Evidence(
                    blueId,
                    combined,
                    inlineEvidence != null
                            ? inlineEvidence.canonicalTypeIdentityInput
                            : null,
                    inlineEvidence != null
                            ? inlineEvidence.authoredTypeSource
                            : null);
        }

        CanonicalTypeIdentityEvidence descriptor() {
            CanonicalTypeIdentityEvidence descriptor =
                    authoredTypeSource != null
                            ? CanonicalTypeIdentityEvidence.authoredInline(
                                    blueId,
                                    canonicalTypeIdentityInput.toNode(),
                                    authoredTypeSource.toNode())
                            : CanonicalTypeIdentityEvidence.identityOnly(
                                    blueId);
            if (kind == EvidenceKind.VERIFIED_REFERENCE
                    || kind == EvidenceKind
                    .AUTHORED_INLINE_AND_VERIFIED_REFERENCE) {
                descriptor = descriptor.combine(
                        CanonicalTypeIdentityEvidence.referenceSource(
                                blueId));
            }
            return descriptor;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Evidence)) {
                return false;
            }
            Evidence that = (Evidence) other;
            return blueId.equals(that.blueId)
                    && kind == that.kind;
        }

        @Override
        public int hashCode() {
            return 31 * blueId.hashCode() + kind.hashCode();
        }

        private static Evidence selectRetainedInlineEvidence(
                Evidence left,
                Evidence right) {
            if (left.authoredTypeSource == null) {
                return right.authoredTypeSource != null ? right : null;
            }
            return left;
        }
    }

    /**
     * Immutable cache-transport form. Only complete snapshots may justify a
     * warm resolved-reference hit.
     */
    static final class EvidenceSnapshot
            implements CanonicalTypeIdentityLookup {
        private static final EvidenceSnapshot INCOMPLETE_EMPTY =
                new EvidenceSnapshot(
                        Collections.<SemanticTypeEvidenceKey,
                                Map<String, Evidence>>
                                emptyMap(),
                        false);

        private final Map<SemanticTypeEvidenceKey, Map<String, Evidence>>
                evidenceByStructure;
        private final boolean completeCoverage;
        private final long retainedWeightBytes;

        private EvidenceSnapshot(
                Map<SemanticTypeEvidenceKey, Map<String, Evidence>> evidence,
                boolean completeCoverage) {
            this.evidenceByStructure = immutableEvidenceBuckets(evidence);
            this.completeCoverage = completeCoverage;
            this.retainedWeightBytes = calculateRetainedWeightBytes();
        }

        static EvidenceSnapshot incompleteEmpty() {
            return INCOMPLETE_EMPTY;
        }

        @Override
        public boolean hasCompleteCoverage() {
            return completeCoverage;
        }

        @Override
        public Optional<String> findCanonicalTypeBlueId(
                Node completedType) {
            Objects.requireNonNull(completedType, "completedType");
            if (completedType.isReferenceOnly()) {
                return Optional.of(requireBlueId(
                        completedType.getBlueId()));
            }
            Evidence evidence = uniqueEvidence(
                    evidenceByStructure.get(structuralKey(completedType)));
            return evidence != null
                    ? Optional.of(evidence.blueId)
                    : Optional.empty();
        }

        @Override
        public Optional<CanonicalTypeIdentityEvidence>
        findCanonicalTypeIdentityEvidence(Node completedType) {
            Objects.requireNonNull(completedType, "completedType");
            if (completedType.isReferenceOnly()) {
                return Optional.of(
                        CanonicalTypeIdentityEvidence.referenceSource(
                                requireBlueId(completedType.getBlueId())));
            }
            Evidence evidence = uniqueEvidence(
                    evidenceByStructure.get(structuralKey(completedType)));
            return evidence != null
                    ? Optional.of(evidence.descriptor())
                    : Optional.<CanonicalTypeIdentityEvidence>empty();
        }

        @Override
        public Optional<CanonicalTypeIdentityEvidence>
        findCanonicalTypeIdentityEvidence(
                Node completedType,
                Node authoredTypeSource) {
            return findEvidence(
                    evidenceByStructure,
                    completedType,
                    authoredTypeSource)
                    .map(Evidence::descriptor);
        }

        @Override
        public String requireCanonicalTypeBlueId(Node completedType) {
            return findCanonicalTypeBlueId(completedType)
                    .orElseThrow(() -> new IllegalStateException(
                            "No canonical identity evidence for completed "
                                    + "effective type"));
        }

        int size() {
            Map<Evidence, Boolean> unique = new IdentityHashMap<>();
            for (Map<String, Evidence> bucket
                    : evidenceByStructure.values()) {
                for (Evidence evidence : bucket.values()) {
                    unique.put(evidence, Boolean.TRUE);
                }
            }
            return unique.size();
        }

        @Override
        public long approximateRetainedWeightBytes() {
            return retainedWeightBytes;
        }

        private long calculateRetainedWeightBytes() {
            long weight = 32L;
            for (Map.Entry<SemanticTypeEvidenceKey, Map<String, Evidence>> entry
                    : evidenceByStructure.entrySet()) {
                long bucketWeight = saturatedAdd(
                        SNAPSHOT_ENTRY_OVERHEAD_BYTES,
                        entry.getKey().approximateRetainedWeightBytes());
                for (Evidence evidence : entry.getValue().values()) {
                    long evidenceWeight = saturatedAdd(
                            SNAPSHOT_ENTRY_OVERHEAD_BYTES,
                            CHARACTER_BYTES
                                    * (long) evidence.blueId.length());
                    if (evidence.authoredTypeSource != null) {
                        evidenceWeight = saturatedAdd(
                                evidenceWeight,
                                evidence.canonicalTypeIdentityInput
                                        .approximateRetainedWeightBytes());
                        evidenceWeight = saturatedAdd(
                                evidenceWeight,
                                evidence.authoredTypeSource
                                        .approximateRetainedWeightBytes());
                    }
                    bucketWeight = saturatedAdd(
                            bucketWeight, evidenceWeight);
                }
                weight = saturatedAdd(weight, bucketWeight);
            }
            return weight;
        }

        EvidenceSnapshot combine(EvidenceSnapshot other) {
            Objects.requireNonNull(other, "other");
            requireCompleteCoverage();
            other.requireCompleteCoverage();
            Map<SemanticTypeEvidenceKey, Map<String, Evidence>> combined =
                    mutableEvidenceBuckets(evidenceByStructure);
            for (Map.Entry<SemanticTypeEvidenceKey, Map<String, Evidence>> entry
                    : other.evidenceByStructure.entrySet()) {
                mergeEvidenceBucket(combined, entry.getKey(), entry.getValue());
            }
            return new EvidenceSnapshot(
                    combined,
                    true);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof EvidenceSnapshot)) {
                return false;
            }
            EvidenceSnapshot that = (EvidenceSnapshot) other;
            return completeCoverage == that.completeCoverage
                    && evidenceByStructure.equals(that.evidenceByStructure);
        }

        @Override
        public int hashCode() {
            return 31 * evidenceByStructure.hashCode()
                    + (completeCoverage ? 1 : 0);
        }
    }

    void record(
            Node completedType,
            String canonicalBlueId,
            EvidenceKind kind,
            FrozenNode canonicalTypeIdentityInput,
            FrozenNode authoredTypeSource) {
        Objects.requireNonNull(completedType, "completedType");
        if (completeCoverage) {
            throw new IllegalStateException(
                    "Canonical type identity coverage is already complete");
        }
        Evidence candidate = new Evidence(
                canonicalBlueId,
                kind,
                canonicalTypeIdentityInput,
                authoredTypeSource);
        SemanticTypeEvidenceKey key = structuralKey(completedType);
        SemanticTypeEvidenceKey previousKey =
                keyByIdentity.get(completedType);
        if (previousKey != null && !previousKey.equals(key)) {
            throw new IllegalStateException(
                    "Completed effective type changed after identity evidence "
                            + "was recorded");
        }
        Evidence retained = mergeEvidence(
                evidenceByIdentity.get(completedType), candidate);
        mergeEvidence(evidenceByStructure, key, retained);
        retained = evidenceByStructure.get(key).get(retained.blueId);
        keyByIdentity.put(completedType, key);
        evidenceByIdentity.put(completedType, retained);
    }

    Evidence require(Node completedType) {
        Objects.requireNonNull(completedType, "completedType");
        SemanticTypeEvidenceKey key = structuralKey(completedType);
        SemanticTypeEvidenceKey previousKey = keyByIdentity.get(
                completedType);
        if (previousKey != null && !previousKey.equals(key)) {
            throw new IllegalStateException(
                    "Completed effective type changed after identity evidence "
                            + "was recorded");
        }
        Evidence evidence = exactEvidence(completedType, key);
        if (evidence == null) {
            evidence = uniqueEvidence(evidenceByStructure.get(key));
        }
        if (evidence == null) {
            throw new IllegalStateException(
                    "No canonical identity evidence for completed effective type");
        }
        keyByIdentity.put(completedType, key);
        evidenceByIdentity.put(completedType, evidence);
        return evidence;
    }

    @Override
    public boolean hasCompleteCoverage() {
        return completeCoverage;
    }

    @Override
    public Optional<String> findCanonicalTypeBlueId(Node completedType) {
        Objects.requireNonNull(completedType, "completedType");
        if (completedType.isReferenceOnly()) {
            return Optional.of(requireBlueId(completedType.getBlueId()));
        }
        SemanticTypeEvidenceKey key = structuralKey(completedType);
        SemanticTypeEvidenceKey previousKey =
                keyByIdentity.get(completedType);
        if (previousKey != null && !previousKey.equals(key)) {
            throw new IllegalStateException(
                    "Completed effective type changed after identity evidence "
                            + "was recorded");
        }
        Evidence evidence = exactEvidence(completedType, key);
        if (evidence == null) {
            evidence = uniqueEvidence(evidenceByStructure.get(key));
        }
        if (evidence != null) {
            keyByIdentity.put(completedType, key);
            evidenceByIdentity.put(completedType, evidence);
            return Optional.of(evidence.blueId());
        }
        return Optional.empty();
    }

    @Override
    public Optional<CanonicalTypeIdentityEvidence>
    findCanonicalTypeIdentityEvidence(Node completedType) {
        Objects.requireNonNull(completedType, "completedType");
        if (completedType.isReferenceOnly()) {
            return Optional.of(
                    CanonicalTypeIdentityEvidence.referenceSource(
                            requireBlueId(completedType.getBlueId())));
        }
        SemanticTypeEvidenceKey key = structuralKey(completedType);
        SemanticTypeEvidenceKey previousKey =
                keyByIdentity.get(completedType);
        if (previousKey != null && !previousKey.equals(key)) {
            throw new IllegalStateException(
                    "Completed effective type changed after identity evidence "
                            + "was recorded");
        }
        Evidence evidence = exactEvidence(completedType, key);
        if (evidence == null) {
            evidence = uniqueEvidence(evidenceByStructure.get(key));
        }
        if (evidence == null) {
            return Optional.empty();
        }
        keyByIdentity.put(completedType, key);
        evidenceByIdentity.put(completedType, evidence);
        return Optional.of(evidence.descriptor());
    }

    @Override
    public Optional<CanonicalTypeIdentityEvidence>
    findCanonicalTypeIdentityEvidence(
            Node completedType,
            Node authoredTypeSource) {
        Objects.requireNonNull(completedType, "completedType");
        if (completedType.isReferenceOnly()) {
            return findEvidence(
                    evidenceByStructure,
                    completedType,
                    authoredTypeSource)
                    .map(Evidence::descriptor);
        }
        SemanticTypeEvidenceKey key = structuralKey(completedType);
        Evidence exact = exactEvidence(completedType, key);
        if (authoredTypeSource == null && exact != null) {
            return Optional.of(exact.descriptor());
        }
        Optional<Evidence> selected = findEvidence(
                evidenceByStructure,
                completedType,
                authoredTypeSource);
        if (exact != null && selected.isPresent()
                && !exact.blueId.equals(selected.get().blueId)) {
            throw new IllegalStateException(
                    "Authored and completed canonical type provenance conflict");
        }
        Evidence evidence = authoredTypeSource != null
                ? selected.orElse(null)
                : selected.orElse(exact);
        return evidence != null
                ? Optional.of(evidence.descriptor())
                : Optional.<CanonicalTypeIdentityEvidence>empty();
    }

    @Override
    public String requireCanonicalTypeBlueId(Node completedType) {
        return findCanonicalTypeBlueId(completedType)
                .orElseThrow(() -> new IllegalStateException(
                        "No canonical identity evidence for completed "
                                + "effective type"));
    }

    /** Retains exact invocation provenance across one defensive graph clone. */
    void bindEquivalentGraph(Node original, Node copy) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(copy, "copy");
        CanonicalTypeIdentityGraphTraversal.forEachEquivalentNodePair(
                original,
                copy,
                this::bindEquivalentNode);
    }

    void bindCanonicalIdentity(Node completedType, String canonicalBlueId) {
        Objects.requireNonNull(completedType, "completedType");
        SemanticTypeEvidenceKey key = structuralKey(completedType);
        Map<String, Evidence> bucket = evidenceByStructure.get(key);
        Evidence evidence = bucket != null
                ? bucket.get(requireBlueId(canonicalBlueId))
                : null;
        if (evidence == null) {
            throw new IllegalStateException(
                    "No canonical identity evidence for completed effective type");
        }
        SemanticTypeEvidenceKey previousKey = keyByIdentity.get(completedType);
        if (previousKey != null && !previousKey.equals(key)) {
            throw new IllegalStateException(
                    "Completed effective type changed after identity evidence "
                            + "was recorded");
        }
        Evidence previousEvidence = evidenceByIdentity.get(completedType);
        evidenceByIdentity.put(
                completedType,
                mergeEvidence(previousEvidence, evidence));
        keyByIdentity.put(completedType, key);
    }


    void importComplete(EvidenceSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!snapshot.completeCoverage) {
            throw new IllegalStateException(
                    "Incomplete canonical type identity evidence cannot be imported");
        }
        if (completeCoverage) {
            throw new IllegalStateException(
                    "Canonical type identity coverage is already complete");
        }
        for (Map.Entry<SemanticTypeEvidenceKey, Map<String, Evidence>> entry
                : snapshot.evidenceByStructure.entrySet()) {
            mergeEvidenceBucket(
                    evidenceByStructure, entry.getKey(), entry.getValue());
        }
    }

    EvidenceSnapshot completeSnapshotForResolvedReference(
            Node resolvedRoot) {
        Objects.requireNonNull(resolvedRoot, "resolvedRoot");
        if (coverageGap) {
            return new EvidenceSnapshot(
                    collectEvidenceThroughout(resolvedRoot, false),
                    false);
        }
        return new EvidenceSnapshot(
                collectEvidenceThroughout(resolvedRoot, true),
                true);
    }

    void noteCoverageGap() {
        coverageGap = true;
    }

    /**
     * Marks a source-preserved subtree incomplete when it contains an inline
     * effective type that this invocation did not resolve. Pure references
     * remain self-identifying terminals and therefore need no sidecar entry.
     */
    void noteCoverageGapIfTypeEvidenceMissing(Node preservedSubtree) {
        if (hasMissingTypeEvidence(preservedSubtree)) {
            noteCoverageGap();
        }
    }

    void markCompleteCoverage(Node resolvedRoot) {
        Objects.requireNonNull(resolvedRoot, "resolvedRoot");
        completeCoverage = false;
        completeCoverageRoot = null;
        if (coverageGap) {
            return;
        }
        collectEvidenceThroughout(resolvedRoot, true);
        completeCoverageRoot = resolvedRoot;
        completeCoverage = true;
    }

    EvidenceSnapshot snapshot() {
        if (!completeCoverage || completeCoverageRoot == null) {
            return new EvidenceSnapshot(evidenceByStructure, false);
        }
        return new EvidenceSnapshot(
                collectEvidenceThroughout(completeCoverageRoot, true),
                true);
    }

    private Map<SemanticTypeEvidenceKey, Map<String, Evidence>>
    collectEvidenceThroughout(Node root, boolean requireComplete) {
        Map<SemanticTypeEvidenceKey, Map<String, Evidence>> collected =
                new HashMap<>();
        CanonicalTypeIdentityGraphTraversal.forEachInlineTypePosition(
                root,
                completedType -> collectTypePosition(
                        completedType, collected, requireComplete));
        return collected;
    }

    private boolean hasMissingTypeEvidence(Node root) {
        return CanonicalTypeIdentityGraphTraversal
                .hasInlineTypeWithoutEvidence(root, this::hasEvidence);
    }

    private void collectTypePosition(
            Node completedType,
            Map<SemanticTypeEvidenceKey, Map<String, Evidence>> collected,
            boolean requireComplete) {
        SemanticTypeEvidenceKey key = structuralKey(completedType);
        Evidence exact = exactEvidence(completedType, key);
        Map<String, Evidence> bucket = evidenceByStructure.get(key);
        if (exact != null) {
            collectEvidence(collected, key, exact);
        } else if (bucket != null && !bucket.isEmpty()) {
            for (Evidence evidence : bucket.values()) {
                collectEvidence(collected, key, evidence);
            }
        } else if (requireComplete) {
            throw new IllegalStateException(
                    "No canonical identity evidence for completed "
                            + "effective type");
        }
    }

    private static void collectEvidence(
            Map<SemanticTypeEvidenceKey, Map<String, Evidence>> collected,
            SemanticTypeEvidenceKey completedKey,
            Evidence evidence) {
        mergeEvidence(collected, completedKey, evidence);
        if (evidence.authoredTypeSource == null) {
            return;
        }
        Node authoredTypeSource = NodeToBlueIdInput
                .stripResolvedBlueIdMetadata(
                        evidence.authoredTypeSource.toNode());
        mergeEvidence(
                collected,
                structuralKey(authoredTypeSource),
                evidence);
    }

    private void bindEquivalentNode(Node original, Node copy) {
        Evidence exact = evidenceByIdentity.get(original);
        if (exact != null) {
            bindCanonicalIdentity(copy, exact.blueId);
        }
    }

    private static Evidence mergeEvidence(
            Evidence existing, Evidence candidate) {
        return existing == null ? candidate : existing.combine(candidate);
    }

    private boolean hasEvidence(Node completedType) {
        if (evidenceByIdentity.containsKey(completedType)) {
            return true;
        }
        Map<String, Evidence> bucket = evidenceByStructure.get(
                structuralKey(completedType));
        return bucket != null && !bucket.isEmpty();
    }

    private Evidence exactEvidence(
            Node completedType,
            SemanticTypeEvidenceKey key) {
        Evidence exact = evidenceByIdentity.get(completedType);
        if (exact == null) {
            return null;
        }
        Map<String, Evidence> bucket = evidenceByStructure.get(key);
        Evidence retained = bucket != null
                ? bucket.get(exact.blueId)
                : null;
        if (retained != null && retained != exact) {
            exact = mergeEvidence(exact, retained);
            evidenceByIdentity.put(completedType, exact);
        }
        return exact;
    }

    private static void mergeEvidence(
            Map<SemanticTypeEvidenceKey, Map<String, Evidence>> target,
            SemanticTypeEvidenceKey key,
            Evidence candidate) {
        Map<String, Evidence> bucket = target.get(key);
        if (bucket == null) {
            bucket = new HashMap<>();
            target.put(key, bucket);
        }
        bucket.put(
                candidate.blueId,
                mergeEvidence(bucket.get(candidate.blueId), candidate));
    }

    private static void mergeEvidenceBucket(
            Map<SemanticTypeEvidenceKey, Map<String, Evidence>> target,
            SemanticTypeEvidenceKey key,
            Map<String, Evidence> candidates) {
        for (Evidence candidate : candidates.values()) {
            mergeEvidence(target, key, candidate);
        }
    }

    private static Map<SemanticTypeEvidenceKey, Map<String, Evidence>>
    mutableEvidenceBuckets(
            Map<SemanticTypeEvidenceKey, Map<String, Evidence>> source) {
        Map<SemanticTypeEvidenceKey, Map<String, Evidence>> copy =
                new HashMap<>();
        for (Map.Entry<SemanticTypeEvidenceKey, Map<String, Evidence>> entry
                : source.entrySet()) {
            copy.put(entry.getKey(), new HashMap<>(entry.getValue()));
        }
        return copy;
    }

    private static Map<SemanticTypeEvidenceKey, Map<String, Evidence>>
    immutableEvidenceBuckets(
            Map<SemanticTypeEvidenceKey, Map<String, Evidence>> source) {
        Map<SemanticTypeEvidenceKey, Map<String, Evidence>> copy =
                new HashMap<>();
        for (Map.Entry<SemanticTypeEvidenceKey, Map<String, Evidence>> entry
                : source.entrySet()) {
            copy.put(
                    entry.getKey(),
                    Collections.unmodifiableMap(
                            new HashMap<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(copy);
    }

    private static Evidence uniqueEvidence(Map<String, Evidence> bucket) {
        if (bucket == null || bucket.isEmpty()) {
            return null;
        }
        if (bucket.size() != 1) {
            throw new IllegalStateException(
                    "Completed effective type has multiple canonical identities; "
                            + "exact authored provenance is required");
        }
        return bucket.values().iterator().next();
    }

    private static Optional<Evidence> findEvidence(
            Map<SemanticTypeEvidenceKey, Map<String, Evidence>> evidence,
            Node completedType,
            Node authoredTypeSource) {
        Objects.requireNonNull(completedType, "completedType");
        if (completedType.isReferenceOnly()) {
            String completedBlueId = requireBlueId(
                    completedType.getBlueId());
            if (authoredTypeSource != null
                    && authoredTypeSource.isReferenceOnly()
                    && !completedBlueId.equals(requireBlueId(
                    authoredTypeSource.getBlueId()))) {
                throw new IllegalStateException(
                        "Authored and completed type references conflict");
            }
            return Optional.of(new Evidence(
                    completedBlueId,
                    EvidenceKind.VERIFIED_REFERENCE,
                    null,
                    null));
        }
        Map<String, Evidence> completedBucket = evidence.get(
                structuralKey(completedType));
        if (authoredTypeSource == null) {
            return Optional.ofNullable(uniqueEvidence(completedBucket));
        }
        if (authoredTypeSource.isReferenceOnly()) {
            String authoredBlueId = requireBlueId(
                    authoredTypeSource.getBlueId());
            Evidence selected = completedBucket != null
                    ? completedBucket.get(authoredBlueId)
                    : null;
            return Optional.ofNullable(selected);
        }
        SemanticTypeEvidenceKey authoredKey = structuralKey(
                NodeToBlueIdInput.stripResolvedBlueIdMetadata(
                        authoredTypeSource.clone()));
        Evidence selected = null;
        if (completedBucket == null) {
            return Optional.empty();
        }
        for (Evidence candidate : completedBucket.values()) {
            if (candidate.authoredTypeSource == null
                    || !authoredKey.equals(structuralKey(
                    candidate.authoredTypeSource.toNode()))) {
                continue;
            }
            selected = mergeEvidence(selected, candidate);
        }
        return Optional.ofNullable(selected);
    }

    private static SemanticTypeEvidenceKey structuralKey(Node node) {
        return SemanticTypeEvidenceKey.of(node);
    }

    private static String requireBlueId(String blueId) {
        Objects.requireNonNull(blueId, "canonicalBlueId");
        BlueIdReferenceValidator.validate(new Node().blueId(blueId));
        return blueId;
    }

    private static long saturatedAdd(long left, long right) {
        return Long.MAX_VALUE - left < right
                ? Long.MAX_VALUE
                : left + right;
    }
}
