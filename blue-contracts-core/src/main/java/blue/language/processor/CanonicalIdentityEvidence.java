package blue.language.processor;

import blue.language.identity.BlueIdReferenceValidator;
import blue.language.identity.CanonicalIdentityInputBuilder;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.NodeToBlueIdInput;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.Schema;
import blue.language.model.wire.JsonPointer;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Contracts-side adapter for identities that only the Language resolver can
 * establish.
 *
 * <p>Direct BlueId calculation is valid for an already-proven Canonical
 * Identity Input. It is not a substitute for canonicalizing authored inline
 * content, and it is never valid for a completed effective type. This helper
 * keeps those two evidence-bearing operations explicit at Contracts
 * recognition boundaries.</p>
 */
final class CanonicalIdentityEvidence {

    private CanonicalIdentityEvidence() {
    }

    /**
     * Whether direct Source freezing would cross a materialized reserved type
     * position and therefore require resolver-issued identity evidence.
     */
    static boolean requiresEffectiveTypeIdentity(Node source) {
        Node root = Objects.requireNonNull(source, "source");
        Deque<Node> pending = new ArrayDeque<>();
        Set<Node> visited = Collections.newSetFromMap(
                new IdentityHashMap<Node, Boolean>());
        Set<Schema> visitedSchemas = Collections.newSetFromMap(
                new IdentityHashMap<Schema, Boolean>());
        pending.push(root);
        while (!pending.isEmpty()) {
            Node node = pending.pop();
            if (!visited.add(node)) {
                continue;
            }
            if (materialized(node.getType())
                    || materialized(node.getItemType())
                    || materialized(node.getKeyType())
                    || materialized(node.getValueType())) {
                return true;
            }
            push(pending, node.getBlue());
            push(pending, node.getContracts());
            if (node.getItems() != null) {
                for (Node item : node.getItems()) {
                    push(pending, item);
                }
            }
            if (node.getProperties() != null) {
                for (Node property : node.getProperties().values()) {
                    push(pending, property);
                }
            }
            Schema schema = node.getSchema();
            if (schema != null
                    && visitedSchemas.add(schema)
                    && !schema.isReferenceOnly()) {
                pushSchemaNodes(pending, schema);
            }
        }
        return false;
    }

    private static boolean materialized(Node type) {
        return type != null && !type.isReferenceOnly();
    }

    private static void pushSchemaNodes(
            Deque<Node> pending,
            Schema schema) {
        push(pending, schema.getRequired());
        push(pending, schema.getMinLength());
        push(pending, schema.getMaxLength());
        push(pending, schema.getMinimum());
        push(pending, schema.getMaximum());
        push(pending, schema.getExclusiveMinimum());
        push(pending, schema.getExclusiveMaximum());
        push(pending, schema.getMultipleOf());
        push(pending, schema.getMinItems());
        push(pending, schema.getMaxItems());
        push(pending, schema.getUniqueItems());
        push(pending, schema.getMinFields());
        push(pending, schema.getMaxFields());
        if (schema.getEnum() != null) {
            for (Node value : schema.getEnum()) {
                push(pending, value);
            }
        }
    }

    private static void push(Deque<Node> pending, Node node) {
        if (node != null) {
            pending.push(node);
        }
    }

    /** Resolves authored content and returns its Source-derived identity. */
    static String sourceBlueId(
            Node source,
            ProcessingSnapshotManager snapshotManager,
            String purpose) {
        return sourceBlueId(
                source,
                snapshotManager,
                purpose,
                Collections.<String>emptySet());
    }

    /** Resolves an executable body while preserving exact patch witnesses. */
    static String executableBodyBlueId(
            Node source,
            ProcessingSnapshotManager snapshotManager,
            String purpose) {
        Node checked = Objects.requireNonNull(source, "source");
        return sourceBlueId(
                checked,
                snapshotManager,
                purpose,
                ExecutableBodyPathCatalog.processorStatePatchEffectPaths(
                        checked));
    }

    /**
     * Resolves authored content while retaining exact, recognition-derived
     * deferred subtrees that may be contributed by a referenced type.
     */
    static String sourceBlueId(
            Node source,
            ProcessingSnapshotManager snapshotManager,
            String purpose,
            Set<String> additionalPreservedPaths) {
        Node checked = Objects.requireNonNull(source, "source");
        Set<String> checkedAdditionalPaths = Objects.requireNonNull(
                additionalPreservedPaths,
                "additionalPreservedPaths");
        if (checked.isReferenceOnly()) {
            BlueIdReferenceValidator.validate(checked);
            return checked.getBlueId();
        }
        Node sourceProjection = NodeToBlueIdInput
                .stripResolvedBlueIdMetadata(checked.clone());
        if (snapshotManager == null) {
            if (!requiresEffectiveTypeIdentity(sourceProjection)) {
                return blue.language.identity.DirectBlueIdCalculator
                        .calculateBlueId(sourceProjection);
            }
            throw new IllegalStateException(
                    purpose + " requires Language canonicalization evidence");
        }
        return resolveSourceSnapshot(
                sourceProjection,
                snapshotManager,
                purpose,
                checkedAdditionalPaths).blueId();
    }

    /**
     * Establishes one enclosing Source identity after independently
     * canonicalizing runtime-owned exact fields.
     *
     * <p>An exact {@link Node} field is not resolved as part of its contract
     * header, but its authored representation is still a Blue Language value.
     * Canonicalizing that value before retaining it keeps inline and referenced
     * type declarations identity-equivalent without executing the field or
     * turning its ordinary references into provider demands.</p>
     */
    static String sourceBlueIdWithCanonicalExactFields(
            Node source,
            ProcessingSnapshotManager snapshotManager,
            String purpose,
            Set<String> exactFieldPaths,
            Set<String> executableBodyPaths) {
        Node checked = Objects.requireNonNull(source, "source");
        ProcessingSnapshotManager manager = Objects.requireNonNull(
                snapshotManager, "snapshotManager");
        Set<String> checkedExactPaths = new LinkedHashSet<>(
                Objects.requireNonNull(exactFieldPaths, "exactFieldPaths"));
        Set<String> checkedExecutablePaths = new LinkedHashSet<>(
                Objects.requireNonNull(
                        executableBodyPaths, "executableBodyPaths"));
        if (!checkedExactPaths.containsAll(checkedExecutablePaths)) {
            throw new IllegalArgumentException(
                    "Executable body paths must be exact Source field paths");
        }
        if (checked.isReferenceOnly()) {
            BlueIdReferenceValidator.validate(checked);
            return checked.getBlueId();
        }

        Node sourceProjection = NodeToBlueIdInput
                .stripResolvedBlueIdMetadata(checked.clone());
        Set<String> presentExactPaths = canonicalizeExactFields(
                sourceProjection,
                manager,
                purpose,
                checkedExactPaths,
                checkedExecutablePaths);
        Set<String> preservedExactPaths = new LinkedHashSet<>(
                checkedExactPaths);
        preservedExactPaths.addAll(presentExactPaths);
        return resolveSourceSnapshot(
                sourceProjection,
                manager,
                purpose,
                preservedExactPaths).blueId();
    }

    private static Set<String> canonicalizeExactFields(
            Node sourceProjection,
            ProcessingSnapshotManager snapshotManager,
            String purpose,
            Set<String> exactFieldPaths,
            Set<String> executableBodyPaths) {
        Set<String> presentExactPaths = new LinkedHashSet<>();
        List<String> orderedPaths = new ArrayList<>(exactFieldPaths);
        Collections.sort(orderedPaths, (left, right) -> {
            int byDepth = Integer.compare(
                    JsonPointer.split(right).size(),
                    JsonPointer.split(left).size());
            return byDepth != 0 ? byDepth : left.compareTo(right);
        });
        for (String path : orderedPaths) {
            Node exactValue = NodePathEditor.getOrNull(
                    sourceProjection, path);
            if (exactValue == null) {
                continue;
            }
            presentExactPaths.add(path);
            Set<String> preservedWithinValue = relativeDescendants(
                    path, exactFieldPaths);
            if (executableBodyPaths.contains(path)) {
                preservedWithinValue.addAll(
                        ExecutableBodyPathCatalog
                                .processorStatePatchEffectPaths(exactValue));
            }
            Node canonicalValue = executableBodyPaths.contains(path)
                    ? canonicalSourceInput(
                            exactValue,
                            snapshotManager,
                            purpose + " executable field " + path,
                            preservedWithinValue)
                    : canonicalPartialSourceInput(
                            exactValue,
                            snapshotManager,
                            purpose + " exact header field " + path);
            NodePathEditor.put(sourceProjection, path, canonicalValue);
        }
        return presentExactPaths;
    }

    /**
     * Canonicalizes a retained contract-header value without asserting that
     * it is a complete runtime instance.
     *
     * <p>Node-valued header fields include event matchers. A matcher such as
     * {@code type: Document Processing Initiated} intentionally omits the
     * event type's required {@code document} field; resolving it as an event
     * instance would therefore reject valid contract Source. The Language
     * declaration boundary performs the same preprocessing, type-chain
     * resolution, and canonical identity reconstruction while correctly
     * treating the retained value as declaration-shaped evidence.</p>
     */
    private static Node canonicalPartialSourceInput(
            Node source,
            ProcessingSnapshotManager snapshotManager,
            String purpose) {
        Node checked = Objects.requireNonNull(source, "source");
        if (checked.isReferenceOnly()) {
            BlueIdReferenceValidator.validate(checked);
            return checked.clone();
        }
        CanonicalTypeIdentityEvidence evidence = Objects.requireNonNull(
                snapshotManager.resolveTypeDeclarationIdentity(
                        NodeToBlueIdInput.stripResolvedBlueIdMetadata(
                                checked.clone())),
                "canonicalPartialSourceEvidence");
        Node canonical = evidence.canonicalTypeIdentityInput();
        if (canonical == null) {
            throw new IllegalStateException(
                    purpose + " did not retain canonical inline Source proof");
        }
        return canonical;
    }

    private static Set<String> relativeDescendants(
            String parentPath,
            Set<String> candidatePaths) {
        List<String> parent = JsonPointer.split(parentPath);
        Set<String> result = new LinkedHashSet<>();
        for (String candidatePath : candidatePaths) {
            List<String> candidate = JsonPointer.split(candidatePath);
            if (candidate.size() <= parent.size()
                    || !candidate.subList(0, parent.size()).equals(parent)) {
                continue;
            }
            result.add(JsonPointer.toPointer(
                    candidate.subList(parent.size(), candidate.size())));
        }
        return result;
    }

    private static Node canonicalSourceInput(
            Node source,
            ProcessingSnapshotManager snapshotManager,
            String purpose,
            Set<String> additionalPreservedPaths) {
        Node checked = Objects.requireNonNull(source, "source");
        if (checked.isReferenceOnly()) {
            BlueIdReferenceValidator.validate(checked);
            return checked.clone();
        }
        Node sourceProjection = NodeToBlueIdInput
                .stripResolvedBlueIdMetadata(checked.clone());
        return resolveSourceSnapshot(
                sourceProjection,
                snapshotManager,
                purpose,
                additionalPreservedPaths).canonicalRoot();
    }

    private static ResolvedSnapshot resolveSourceSnapshot(
            Node sourceProjection,
            ProcessingSnapshotManager snapshotManager,
            String purpose,
            Set<String> additionalPreservedPaths) {
        Set<String> preservedPaths = new LinkedHashSet<>(
                ExecutableBodyPathCatalog.ordinaryReferencePaths(
                        sourceProjection));
        preservedPaths.addAll(
                ExecutableBodyPathCatalog.opaqueCyclicMemberPaths(
                        sourceProjection));
        preservedPaths.addAll(
                ExecutableBodyPathCatalog.processorStateReferencePaths(
                        sourceProjection,
                        ExecutableBodyPathCatalog.authoredNodePaths(
                                sourceProjection)));
        preservedPaths.addAll(additionalPreservedPaths);
        ResolvedSnapshot snapshot = Objects.requireNonNull(
                preservedPaths.isEmpty()
                        ? snapshotManager
                                .fromDocumentTransientForCanonicalIdentity(
                                        sourceProjection)
                        : snapshotManager
                                .fromDocumentTransientPreservingPaths(
                                        sourceProjection,
                                        preservedPaths),
                "canonicalIdentitySnapshot");
        snapshot.canonicalTypeIdentities().requireCompleteCoverage();
        if (!snapshot.hasCanonicalIdentity()) {
            throw new IllegalStateException(
                    purpose + " resolution did not establish a "
                            + "whole-document canonical identity");
        }
        return snapshot;
    }

    /**
     * Resolves an authored Source type in an otherwise empty type position and
     * returns that type's canonical identity.
     *
     * <p>A type declaration is not a standalone document value: resolving and
     * hashing it as the wrapper root would apply instance semantics to the
     * declaration. The wrapper keeps the declaration in the reserved
     * {@code type} position, where Language records the exact bottom-up type
     * identity evidence used by ordinary document resolution.</p>
     */
    static String sourceTypeBlueId(
            Node sourceType,
            ProcessingSnapshotManager snapshotManager,
            String purpose) {
        Node checked = Objects.requireNonNull(sourceType, "sourceType");
        if (checked.isReferenceOnly()) {
            BlueIdReferenceValidator.validate(checked);
            return checked.getBlueId();
        }
        if (snapshotManager == null) {
            throw new IllegalStateException(
                    purpose + " requires Language canonicalization evidence");
        }
        return Objects.requireNonNull(
                snapshotManager.resolveTypeDeclarationIdentity(
                        NodeToBlueIdInput.stripResolvedBlueIdMetadata(
                                checked.clone())),
                "canonicalTypeIdentityEvidence").blueId();
    }

    /** Returns the canonical identity of a completed effective type. */
    static String resolvedTypeBlueId(
            Node completedType,
            CanonicalTypeIdentityLookup identities,
            String purpose) {
        Node checked = Objects.requireNonNull(
                completedType, "completedType");
        if (checked.isReferenceOnly()) {
            return checked.getBlueId();
        }
        if (identities == null) {
            throw new IllegalStateException(
                    purpose + " requires resolver-issued type identity evidence");
        }
        try {
            return identities.requireCanonicalTypeBlueId(checked);
        } catch (IllegalStateException missingEvidence) {
            throw new IllegalStateException(
                    purpose + ": " + missingEvidence.getMessage(),
                    missingEvidence);
        }
    }

    /** Frozen-node counterpart to {@link #resolvedTypeBlueId(Node,
     * CanonicalTypeIdentityLookup, String)}. */
    static String resolvedTypeBlueId(
            FrozenNode completedType,
            CanonicalTypeIdentityLookup identities,
            String purpose) {
        FrozenNode checked = Objects.requireNonNull(
                completedType, "completedType");
        if (checked.isReferenceOnly()) {
            return checked.getReferenceBlueId();
        }
        return resolvedTypeBlueId(checked.toNode(), identities, purpose);
    }

    /**
     * Builds an operation-local canonical projection for one exact resolved
     * graph without promoting its containing snapshot to complete.
     *
     * <p>Every materialized reserved type position must be covered by the
     * resolver evidence that produced this graph. Pure references need no
     * additional evidence. Content outside a demand-limited graph is neither
     * loaded nor claimed to have been canonicalized.</p>
     */
    static FrozenNode projectResolvedGraph(
            Node resolvedGraph,
            Node exactSource,
            CanonicalTypeIdentityLookup availableEvidence) {
        Node resolved = Objects.requireNonNull(
                resolvedGraph, "resolvedGraph");
        Node source = NodeToBlueIdInput.stripResolvedBlueIdMetadata(
                Objects.requireNonNull(exactSource, "exactSource").clone());
        CanonicalTypeIdentityLookup scopedEvidence =
                CanonicalTypeIdentityEvidenceUnion
                        .establishForResolvedGraph(
                                resolved,
                                Collections.singletonList(
                                        Objects.requireNonNull(
                                                availableEvidence,
                                                "availableEvidence")));
        Node canonical = new CanonicalIdentityInputBuilder().build(
                resolved, source, scopedEvidence);
        return FrozenNode.fromNode(canonical);
    }

    /** Attaches a canonical lane proven only for the snapshot's exact graph. */
    static ResolvedSnapshot projectSnapshot(ResolvedSnapshot snapshot) {
        ResolvedSnapshot exact = Objects.requireNonNull(
                snapshot, "snapshot");
        if (exact.hasCanonicalIdentity()) {
            return exact;
        }
        CanonicalTypeIdentityLookup scopedEvidence =
                CanonicalTypeIdentityEvidenceUnion
                        .establishForResolvedGraph(
                                exact.resolvedRoot(),
                                Collections.singletonList(
                                        exact.canonicalTypeIdentities()));
        return exact.withCanonicalIdentityEvidence(scopedEvidence);
    }

    /**
     * Defers a whole-graph evidence rebuild until a materialized type is
     * actually inspected or a consumer explicitly requires complete coverage.
     * Pure references remain self-identifying and therefore do not trigger the
     * supplier.
     */
    static CanonicalTypeIdentityLookup onDemand(
            CanonicalTypeIdentityLookup available,
            Supplier<CanonicalTypeIdentityLookup> completeLookup) {
        CanonicalTypeIdentityLookup checked = Objects.requireNonNull(
                available, "available");
        if (checked.hasCompleteCoverage()
                || checked instanceof OnDemandCanonicalTypeIdentityLookup) {
            return checked;
        }
        return new OnDemandCanonicalTypeIdentityLookup(
                checked,
                Objects.requireNonNull(completeLookup, "completeLookup"));
    }

    private static final class OnDemandCanonicalTypeIdentityLookup
            implements CanonicalTypeIdentityLookup {

        private volatile CanonicalTypeIdentityLookup delegate;
        private volatile Supplier<CanonicalTypeIdentityLookup> completeLookup;

        private OnDemandCanonicalTypeIdentityLookup(
                CanonicalTypeIdentityLookup available,
                Supplier<CanonicalTypeIdentityLookup> completeLookup) {
            this.delegate = available;
            this.completeLookup = completeLookup;
        }

        @Override
        public boolean hasCompleteCoverage() {
            return delegate.hasCompleteCoverage();
        }

        @Override
        public void requireCompleteCoverage() {
            complete().requireCompleteCoverage();
        }

        @Override
        public Optional<String> findCanonicalTypeBlueId(
                Node completedType) {
            Optional<String> available = delegate.findCanonicalTypeBlueId(
                    completedType);
            return available.isPresent()
                    ? available
                    : complete().findCanonicalTypeBlueId(completedType);
        }

        @Override
        public Optional<CanonicalTypeIdentityEvidence>
        findCanonicalTypeIdentityEvidence(Node completedType) {
            CanonicalTypeIdentityLookup current = delegate;
            Optional<CanonicalTypeIdentityEvidence> available = current
                    .findCanonicalTypeIdentityEvidence(completedType);
            if (available.isPresent()) {
                CanonicalTypeIdentityEvidence evidence = available.get();
                if (evidence.hasReferenceSource()
                        || evidence.authoredTypeSource() != null) {
                    return available;
                }
            }
            return complete().findCanonicalTypeIdentityEvidence(
                    completedType);
        }

        @Override
        public Optional<CanonicalTypeIdentityEvidence>
        findCanonicalTypeIdentityEvidence(
                Node completedType,
                Node authoredTypeSource) {
            /*
             * An authored Source hint is a disambiguating semantic demand.
             * The complete lookup must see it; consulting an older one-argument
             * partial wrapper first could either ignore the hint or fail before
             * the authoritative lookup is materialized.
             */
            return complete().findCanonicalTypeIdentityEvidence(
                    completedType, authoredTypeSource);
        }

        @Override
        public String requireCanonicalTypeBlueId(Node completedType) {
            return findCanonicalTypeBlueId(completedType)
                    .orElseThrow(() -> new IllegalStateException(
                            "No resolver-issued canonical type identity "
                                    + "evidence is available"));
        }

        @Override
        public long approximateRetainedWeightBytes() {
            return completeLookup != null
                    ? Long.MAX_VALUE
                    : delegate.approximateRetainedWeightBytes();
        }

        private CanonicalTypeIdentityLookup complete() {
            CanonicalTypeIdentityLookup current = delegate;
            if (current.hasCompleteCoverage()) {
                return current;
            }
            synchronized (this) {
                current = delegate;
                if (current.hasCompleteCoverage()) {
                    return current;
                }
                Supplier<CanonicalTypeIdentityLookup> supplier =
                        completeLookup;
                if (supplier == null) {
                    return delegate;
                }
                CanonicalTypeIdentityLookup materialized =
                        Objects.requireNonNull(
                                supplier.get(),
                                "completedCanonicalTypeIdentityLookup");
                materialized.requireCompleteCoverage();
                boolean hasNoRetainedEvidence =
                        current == CanonicalTypeIdentityLookup.incomplete();
                CanonicalTypeIdentityLookup combined = current == materialized
                        || hasNoRetainedEvidence
                        ? materialized
                        : new AugmentedCompleteCanonicalTypeIdentityLookup(
                                current, materialized);
                delegate = combined;
                completeLookup = null;
                return combined;
            }
        }
    }

    /** Retains partial rich evidence while adding a complete coverage proof. */
    private static final class AugmentedCompleteCanonicalTypeIdentityLookup
            implements CanonicalTypeIdentityLookup {

        private final CanonicalTypeIdentityLookup retained;
        private final CanonicalTypeIdentityLookup complete;

        private AugmentedCompleteCanonicalTypeIdentityLookup(
                CanonicalTypeIdentityLookup retained,
                CanonicalTypeIdentityLookup complete) {
            this.retained = Objects.requireNonNull(retained, "retained");
            this.complete = Objects.requireNonNull(complete, "complete");
            this.complete.requireCompleteCoverage();
        }

        @Override
        public boolean hasCompleteCoverage() {
            return true;
        }

        @Override
        public Optional<String> findCanonicalTypeBlueId(Node completedType) {
            return findCanonicalTypeIdentityEvidence(completedType)
                    .map(CanonicalTypeIdentityEvidence::blueId);
        }

        @Override
        public Optional<CanonicalTypeIdentityEvidence>
        findCanonicalTypeIdentityEvidence(Node completedType) {
            Optional<CanonicalTypeIdentityEvidence> left = retained
                    .findCanonicalTypeIdentityEvidence(completedType);
            Optional<CanonicalTypeIdentityEvidence> right = complete
                    .findCanonicalTypeIdentityEvidence(completedType);
            if (!left.isPresent()) {
                return right;
            }
            if (!right.isPresent()) {
                return left;
            }
            return Optional.of(left.get().combine(right.get()));
        }

        @Override
        public Optional<CanonicalTypeIdentityEvidence>
        findCanonicalTypeIdentityEvidence(
                Node completedType,
                Node authoredTypeSource) {
            Optional<CanonicalTypeIdentityEvidence> left = retained
                    .findCanonicalTypeIdentityEvidence(
                            completedType, authoredTypeSource);
            Optional<CanonicalTypeIdentityEvidence> right = complete
                    .findCanonicalTypeIdentityEvidence(
                            completedType, authoredTypeSource);
            if (!left.isPresent()) {
                return right;
            }
            if (!right.isPresent()) {
                return left;
            }
            return Optional.of(left.get().combine(right.get()));
        }

        @Override
        public String requireCanonicalTypeBlueId(Node completedType) {
            return findCanonicalTypeBlueId(completedType)
                    .orElseThrow(() -> new IllegalStateException(
                            "No resolver-issued canonical type identity "
                                    + "evidence is available"));
        }

        @Override
        public long approximateRetainedWeightBytes() {
            return saturatedAdd(
                    retained.approximateRetainedWeightBytes(),
                    complete.approximateRetainedWeightBytes());
        }
    }

    private static long saturatedAdd(long left, long right) {
        if (left < 0L
                || right < 0L
                || left == Long.MAX_VALUE
                || right == Long.MAX_VALUE
                || left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
