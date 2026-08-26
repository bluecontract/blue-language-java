package blue.language.processor.closure;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.ManagedProcessEmbeddedPath;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.SubscriptionSurfaceInvalidException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reconciles one complete effective Process Embedded surface with frozen
 * managed-occurrence evidence.
 *
 * <p>The service consumes concrete paths projected by the existing contract
 * loader and embedded-scope planner. It never parses contracts, discovers
 * lineages, or finalizes graph/component identity.</p>
 */
final class ProcessEmbeddedSurfaceReconciler {

    private final ProcessEmbeddedDemandDiscovery demandDiscovery =
            new ProcessEmbeddedDemandDiscovery();

    /**
     * Canonically aggregates demand discovery across every projected Root.
     * No source is reconciled unless this complete aggregate is empty.
     */
    List<ClosureResourceDemand> resourceDemands(
            Map<DocumentId, Node> resultingSources,
            Map<DocumentId, List<ManagedProcessEmbeddedPath>>
                    effectiveSurfaces,
            List<ManagedOccurrenceBinding> currentBindings,
            List<ManagedDocumentSnapshot> currentDocuments,
            DemandContext demandContext) {
        return demandDiscovery.discoverAll(
                resultingSources,
                effectiveSurfaces,
                currentBindings,
                currentDocuments,
                demandContext);
    }

    /**
     * Discovers every independently missing resource for one complete
     * resulting Process Embedded surface.
     *
     * <p>This boundary is read-only: it never changes occurrence rows,
     * retirement fences, graph state, or the supplied document. Callers that
     * own several Roots must aggregate this result for every Root before
     * invoking {@link #reconcileProjected} for any of them.</p>
     */
    List<ClosureResourceDemand> resourceDemands(
            DocumentId sourceDocumentId,
            Node resultingSource,
            List<ManagedProcessEmbeddedPath> effectiveSurface,
            List<ManagedOccurrenceBinding> currentBindings,
            List<ManagedDocumentSnapshot> currentDocuments,
            DemandContext demandContext) {
        return demandDiscovery.discover(
                sourceDocumentId,
                resultingSource,
                effectiveSurface,
                currentBindings,
                currentDocuments,
                demandContext);
    }

    /**
     * Typed single-source preflight followed by closed reconciliation.
     * Multi-Root callers must aggregate {@link #resourceDemands} for every
     * Root first and use the context-free overload only after that aggregate
     * is empty.
     */
    Reconciliation reconcileProjected(
            DocumentId sourceDocumentId,
            Node resultingSource,
            List<ManagedProcessEmbeddedPath> effectiveSurface,
            List<ManagedOccurrenceBinding> currentBindings,
            List<ManagedDocumentSnapshot> currentDocuments,
            Set<OccurrencePath> invocationRetirementFences,
            DemandContext demandContext) {
        List<ClosureResourceDemand> demands = resourceDemands(
                sourceDocumentId,
                resultingSource,
                effectiveSurface,
                currentBindings,
                currentDocuments,
                demandContext);
        if (!demands.isEmpty()) {
            throw new ClosureResourceDemandException(demands);
        }
        return reconcileProjected(
                sourceDocumentId,
                resultingSource,
                effectiveSurface,
                currentBindings,
                currentDocuments,
                invocationRetirementFences);
    }

    /**
     * Reconciles one independently managed source Root.
     *
     * <p>New paths must already have one inactive occurrence reservation.
     * Typed demand discovery and resolution occur before this boundary.</p>
     */
    Reconciliation reconcileProjected(
            DocumentId sourceDocumentId,
            Node resultingSource,
            List<ManagedProcessEmbeddedPath> effectiveSurface,
            List<ManagedOccurrenceBinding> currentBindings,
            List<ManagedDocumentSnapshot> currentDocuments,
            Set<OccurrencePath> invocationRetirementFences) {
        DocumentId source = Objects.requireNonNull(
                sourceDocumentId, "sourceDocumentId");
        Node document = Objects.requireNonNull(
                resultingSource, "resultingSource");
        Map<DocumentId, ManagedDocumentSnapshot> documents = documentsById(
                currentDocuments);
        if (!documents.containsKey(source)) {
            throw new IllegalArgumentException(
                    "Process Embedded source is outside closure membership");
        }
        Map<String, ManagedProcessEmbeddedPath> declared = projectedByPath(
                effectiveSurface);
        Map<String, ManagedOccurrenceBinding> sourceBindings =
                bindingsByPath(source, currentBindings);
        preflightDeclared(
                source, document, declared, sourceBindings, documents);

        Set<OccurrencePath> fences = Objects.requireNonNull(
                invocationRetirementFences,
                "invocationRetirementFences");
        List<ManagedOccurrenceBinding> reconciled =
                new ArrayList<ManagedOccurrenceBinding>();
        List<OccurrenceTransition> transitions =
                new ArrayList<OccurrenceTransition>();
        Set<String> activated = new LinkedHashSet<String>();
        Set<OccurrencePath> retired = new LinkedHashSet<OccurrencePath>();

        for (ManagedOccurrenceBinding binding : Objects.requireNonNull(
                currentBindings, "currentBindings")) {
            if (!source.equals(binding.sourceDocumentId())) {
                reconciled.add(binding);
                continue;
            }
            reconcileBinding(
                    source,
                    document,
                    binding,
                    declared,
                    documents,
                    fences,
                    reconciled,
                    transitions,
                    activated,
                    retired);
        }
        Collections.sort(reconciled);
        Collections.sort(transitions);
        return new Reconciliation(
                reconciled, transitions, activated, retired);
    }

    private static void preflightDeclared(
            DocumentId source,
            Node document,
            Map<String, ManagedProcessEmbeddedPath> declared,
            Map<String, ManagedOccurrenceBinding> bindings,
            Map<DocumentId, ManagedDocumentSnapshot> documents) {
        for (String path : declared.keySet()) {
            ManagedOccurrenceBinding binding = bindings.get(path);
            if (binding == null) {
                throw new SubscriptionSurfaceInvalidException(
                        "Effective Process Embedded path lacks frozen "
                                + "occurrence evidence: "
                                + source.value() + ":" + path,
                        "/",
                        null);
            }
            Node value = NodePathEditor.getOrNull(document, path);
            if (value == null) {
                throw missingEffectiveValue(source, path);
            }
            if (!binding.active()
                    && binding.pendingHistoricalEpoch() == null) {
                ManagedDocumentSnapshot exact = documents.get(
                        binding.targetDocumentId());
                if (exact == null
                        || !binding.expectedTargetBlueId().equals(
                                exact.blueId())
                        || !ManagedOccurrenceTargetVerifier
                                .establishesExactTarget(value, exact)) {
                    throw invalidProspectiveOccurrence(path);
                }
            }
        }
    }

    private static void reconcileBinding(
            DocumentId source,
            Node document,
            ManagedOccurrenceBinding binding,
            Map<String, ManagedProcessEmbeddedPath> declared,
            Map<DocumentId, ManagedDocumentSnapshot> documents,
            Set<OccurrencePath> fences,
            List<ManagedOccurrenceBinding> reconciled,
            List<OccurrenceTransition> transitions,
            Set<String> activated,
            Set<OccurrencePath> retired) {
        String path = binding.sourcePath();
        OccurrencePath occurrencePath = new OccurrencePath(source, path);
        if (!declared.containsKey(path)) {
            if (!binding.active()) {
                reconciled.add(binding);
                return;
            }
            requireRetirementAvailable(occurrencePath, fences);
            ManagedOccurrenceBinding successor = retirementSuccessor(
                    binding, documents.get(binding.targetDocumentId()));
            reconciled.add(successor);
            transitions.add(OccurrenceTransition.remove(binding));
            retired.add(occurrencePath);
            return;
        }

        Node value = NodePathEditor.getOrNull(document, path);
        if (value == null) {
            throw missingEffectiveValue(source, path);
        }
        if (binding.pendingHistoricalEpoch() != null) {
            if (establishesPendingHistoricalValue(value, binding)) {
                reconciled.add(binding);
                return;
            }
            throw new ClosureCapabilityGapException(
                    "NEW_OCCURRENCE_ADMISSION_REQUIRED",
                    "A historical managed occurrence can change only "
                            + "through its exact managed-revision lane");
        }

        ManagedDocumentSnapshot exact = binding.active()
                ? exactTarget(value, binding, documents)
                : documents.get(binding.targetDocumentId());
        if (exact == null) {
            throw new ClosureCapabilityGapException(
                    "NEW_OCCURRENCE_ADMISSION_REQUIRED",
                    "Changed Process Embedded occurrence does not identify "
                            + "one frozen exact closure member: "
                            + source.value() + ":" + path);
        }
        if (!binding.active()
                && !ManagedOccurrenceTargetVerifier.establishesExactTarget(
                        value, exact)) {
            throw invalidProspectiveOccurrence(path);
        }
        if (!binding.active() && fences.contains(occurrencePath)) {
            throw sameInvocationReactivation(occurrencePath);
        }

        long generation = binding.activationGeneration();
        boolean retarget = binding.active()
                && !binding.targetDocumentId().equals(exact.documentId());
        if (retarget) {
            requireRetirementAvailable(occurrencePath, fences);
            generation = successorGeneration(generation);
            retired.add(occurrencePath);
        }
        ManagedOccurrenceBinding replacement =
                ManagedOccurrenceBinding.derived(
                        binding.bindingPolicyIdentity(),
                        source,
                        ScopeAddress.embedded(path, generation),
                        exact.documentId(),
                        exact.blueId(),
                        true,
                        null);
        reconciled.add(replacement);

        if (!binding.active()) {
            transitions.add(OccurrenceTransition.add(replacement));
            activated.add(replacement.occurrenceIdentity());
        } else if (!binding.bindingIdentity().equals(
                replacement.bindingIdentity())) {
            transitions.add(OccurrenceTransition.rebind(
                    binding, replacement));
            if (!binding.occurrenceIdentity().equals(
                    replacement.occurrenceIdentity())) {
                activated.add(replacement.occurrenceIdentity());
            }
        }
    }

    private static boolean establishesPendingHistoricalValue(
            Node value,
            ManagedOccurrenceBinding binding) {
        String expected = binding.expectedTargetBlueId();
        if (value.isReferenceOnly()) {
            return expected.equals(value.getBlueId());
        }
        return expected.equals(
                DirectBlueIdCalculator.calculateBlueId(value));
    }

    private static Map<DocumentId, ManagedDocumentSnapshot> documentsById(
            List<ManagedDocumentSnapshot> currentDocuments) {
        Map<DocumentId, ManagedDocumentSnapshot> result =
                new LinkedHashMap<DocumentId, ManagedDocumentSnapshot>();
        for (ManagedDocumentSnapshot document : Objects.requireNonNull(
                currentDocuments, "currentDocuments")) {
            ManagedDocumentSnapshot checked = Objects.requireNonNull(
                    document, "managed document");
            if (result.put(checked.documentId(), checked) != null) {
                throw new IllegalArgumentException(
                        "Duplicate managed DocumentId");
            }
        }
        return result;
    }

    private static Map<String, ManagedProcessEmbeddedPath> projectedByPath(
            List<ManagedProcessEmbeddedPath> effectiveSurface) {
        List<ManagedProcessEmbeddedPath> ordered =
                new ArrayList<ManagedProcessEmbeddedPath>(
                        Objects.requireNonNull(
                                effectiveSurface, "effectiveSurface"));
        Collections.sort(ordered);
        Map<String, ManagedProcessEmbeddedPath> result =
                new LinkedHashMap<String, ManagedProcessEmbeddedPath>();
        for (ManagedProcessEmbeddedPath projection : ordered) {
            ManagedProcessEmbeddedPath checked = Objects.requireNonNull(
                    projection, "Process Embedded projection");
            if (result.put(checked.absolutePath(), checked) != null) {
                throw new IllegalArgumentException(
                        "Duplicate concrete Process Embedded path: "
                                + checked.absolutePath());
            }
        }
        return result;
    }

    private static Map<String, ManagedOccurrenceBinding> bindingsByPath(
            DocumentId source,
            List<ManagedOccurrenceBinding> currentBindings) {
        Map<String, ManagedOccurrenceBinding> result =
                new LinkedHashMap<String, ManagedOccurrenceBinding>();
        for (ManagedOccurrenceBinding binding : Objects.requireNonNull(
                currentBindings, "currentBindings")) {
            ManagedOccurrenceBinding checked = Objects.requireNonNull(
                    binding, "managed occurrence binding");
            if (source.equals(checked.sourceDocumentId())
                    && result.put(checked.sourcePath(), checked) != null) {
                throw new IllegalArgumentException(
                        "Managed Root has duplicate current occurrence paths");
            }
        }
        return result;
    }

    private static ManagedDocumentSnapshot exactTarget(
            Node value,
            ManagedOccurrenceBinding current,
            Map<DocumentId, ManagedDocumentSnapshot> documents) {
        ManagedDocumentSnapshot prior = documents.get(
                current.targetDocumentId());
        if (ManagedOccurrenceTargetVerifier.establishesExactTarget(
                value, prior)) {
            return prior;
        }
        ManagedDocumentSnapshot result = null;
        for (ManagedDocumentSnapshot candidate : documents.values()) {
            if (!ManagedOccurrenceTargetVerifier.establishesExactTarget(
                    value, candidate)) {
                continue;
            }
            if (result != null) {
                return null;
            }
            result = candidate;
        }
        return result;
    }

    private static ManagedOccurrenceBinding retirementSuccessor(
            ManagedOccurrenceBinding removed,
            ManagedDocumentSnapshot target) {
        if (target == null) {
            throw new IllegalArgumentException(
                    "Occurrence target is outside closure membership");
        }
        return ManagedOccurrenceBinding.derived(
                removed.bindingPolicyIdentity(),
                removed.sourceDocumentId(),
                ScopeAddress.embedded(
                        removed.sourcePath(),
                        successorGeneration(
                                removed.activationGeneration())),
                removed.targetDocumentId(),
                target.blueId(),
                false,
                null);
    }

    private static void requireRetirementAvailable(
            OccurrencePath path,
            Set<OccurrencePath> fences) {
        if (fences.contains(path)) {
            throw sameInvocationReactivation(path);
        }
    }

    private static ClosureCapabilityGapException sameInvocationReactivation(
            OccurrencePath path) {
        return new ClosureCapabilityGapException(
                "PROCESS_EMBEDDED_REACTIVATION_REQUIRES_LATER_INVOCATION",
                "A Process Embedded occurrence path can retire only one "
                        + "active lineage per invocation: " + path);
    }

    private static ClosureCapabilityGapException missingEffectiveValue(
            DocumentId source,
            String path) {
        return new ClosureCapabilityGapException(
                "PROCESS_EMBEDDED_EFFECTIVE_VALUE_PROJECTION_REQUIRED",
                "An effective Process Embedded path has no exact value: "
                        + source.value() + ":" + path);
    }

    private static InvalidExecutionEvidenceException
    invalidProspectiveOccurrence(String path) {
        return new InvalidExecutionEvidenceException(
                "Prospective managed occurrence did not install its exact "
                        + "expected target at " + path,
                ProcessorErrorCategory.ManagedOccurrenceBindingMissing);
    }

    private static long successorGeneration(long generation) {
        if (generation == ClosureValueSupport.MAX_SAFE_INTEGER) {
            throw new IllegalArgumentException(
                    "Occurrence activation generation cannot overflow");
        }
        return generation + 1L;
    }

    /** Read-only exact-reference availability owned by the processor host. */
    interface ExactReferenceAvailability {
        boolean isAvailable(String blueId);
    }

    /** Frozen identity and lookup context for one demand-discovery boundary. */
    static final class DemandContext {
        private final String logicalCauseIdentity;
        private final String inputClosureIdentity;
        private final long inputGraphGeneration;
        private final ExactReferenceAvailability exactReferenceAvailability;
        private final boolean verifyHistoricalExactReferences;

        DemandContext(
                String logicalCauseIdentity,
                String inputClosureIdentity,
                long inputGraphGeneration,
                ExactReferenceAvailability exactReferenceAvailability) {
            this(logicalCauseIdentity,
                    inputClosureIdentity,
                    inputGraphGeneration,
                    exactReferenceAvailability,
                    false);
        }

        DemandContext(
                String logicalCauseIdentity,
                String inputClosureIdentity,
                long inputGraphGeneration,
                ExactReferenceAvailability exactReferenceAvailability,
                boolean verifyHistoricalExactReferences) {
            this.logicalCauseIdentity =
                    ClosureValueSupport.requireSha256Identity(
                            logicalCauseIdentity,
                            "logicalCauseIdentity");
            this.inputClosureIdentity =
                    ClosureValueSupport.requireSha256Identity(
                            inputClosureIdentity,
                            "inputClosureIdentity");
            this.inputGraphGeneration =
                    ClosureValueSupport.requireSafeInteger(
                            inputGraphGeneration,
                            "inputGraphGeneration");
            this.exactReferenceAvailability = Objects.requireNonNull(
                    exactReferenceAvailability,
                    "exactReferenceAvailability");
            this.verifyHistoricalExactReferences =
                    verifyHistoricalExactReferences;
        }

        String logicalCauseIdentity() {
            return logicalCauseIdentity;
        }

        String inputClosureIdentity() {
            return inputClosureIdentity;
        }

        long inputGraphGeneration() {
            return inputGraphGeneration;
        }

        ExactReferenceAvailability exactReferenceAvailability() {
            return exactReferenceAvailability;
        }

        boolean verifyHistoricalExactReferences() {
            return verifyHistoricalExactReferences;
        }
    }

    /** Stable source-lineage/path fence for one invocation. */
    static final class OccurrencePath {
        private final DocumentId sourceDocumentId;
        private final String path;

        OccurrencePath(DocumentId sourceDocumentId, String path) {
            this.sourceDocumentId = Objects.requireNonNull(
                    sourceDocumentId, "sourceDocumentId");
            this.path = ClosureValueSupport.requireAbsolutePointer(
                    path, "path");
            if ("/".equals(this.path)) {
                throw new IllegalArgumentException(
                        "A managed occurrence path cannot be Root");
            }
        }

        DocumentId sourceDocumentId() {
            return sourceDocumentId;
        }

        String path() {
            return path;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof OccurrencePath)) {
                return false;
            }
            OccurrencePath that = (OccurrencePath) other;
            return sourceDocumentId.equals(that.sourceDocumentId)
                    && path.equals(that.path);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sourceDocumentId, path);
        }

        @Override
        public String toString() {
            return sourceDocumentId.value() + ":" + path;
        }
    }

    /** One closed active-edge lifecycle transition. */
    static final class OccurrenceTransition
            implements Comparable<OccurrenceTransition> {
        enum Kind {
            ADD,
            REMOVE,
            REBIND
        }

        private final Kind kind;
        private final ManagedOccurrenceBinding before;
        private final ManagedOccurrenceBinding after;

        private OccurrenceTransition(
                Kind kind,
                ManagedOccurrenceBinding before,
                ManagedOccurrenceBinding after) {
            this.kind = Objects.requireNonNull(kind, "kind");
            boolean valid = kind == Kind.ADD
                    ? before == null && after != null && after.active()
                    : kind == Kind.REMOVE
                    ? before != null && before.active() && after == null
                    : before != null && after != null
                            && before.active() && after.active();
            if (!valid) {
                throw new IllegalArgumentException(
                        "Invalid " + kind + " occurrence transition");
            }
            ManagedOccurrenceBinding selected =
                    after != null ? after : before;
            if (before != null && after != null
                    && (!before.sourceDocumentId().equals(
                            after.sourceDocumentId())
                    || !before.sourcePath().equals(after.sourcePath()))) {
                throw new IllegalArgumentException(
                        "Occurrence transition changes source location");
            }
            this.before = before;
            this.after = after;
            Objects.requireNonNull(selected, "selected occurrence");
        }

        static OccurrenceTransition add(ManagedOccurrenceBinding after) {
            return new OccurrenceTransition(Kind.ADD, null, after);
        }

        static OccurrenceTransition remove(ManagedOccurrenceBinding before) {
            return new OccurrenceTransition(Kind.REMOVE, before, null);
        }

        static OccurrenceTransition rebind(
                ManagedOccurrenceBinding before,
                ManagedOccurrenceBinding after) {
            return new OccurrenceTransition(Kind.REBIND, before, after);
        }

        Kind kind() {
            return kind;
        }

        ManagedOccurrenceBinding before() {
            return before;
        }

        ManagedOccurrenceBinding after() {
            return after;
        }

        DocumentId sourceDocumentId() {
            return selected().sourceDocumentId();
        }

        String sourcePath() {
            return selected().sourcePath();
        }

        @Override
        public int compareTo(OccurrenceTransition other) {
            int order = sourceDocumentId().compareTo(
                    other.sourceDocumentId());
            return order != 0
                    ? order
                    : ClosureValueSupport.comparePortableText(
                            sourcePath(), other.sourcePath());
        }

        private ManagedOccurrenceBinding selected() {
            return after != null ? after : before;
        }
    }

    /** Immutable complete replacement and its exact lifecycle transitions. */
    static final class Reconciliation {
        private final List<ManagedOccurrenceBinding> bindings;
        private final List<OccurrenceTransition> transitions;
        private final Set<String> activatedOccurrenceIdentities;
        private final Set<OccurrencePath> retiredOccurrencePaths;

        private Reconciliation(
                List<ManagedOccurrenceBinding> bindings,
                List<OccurrenceTransition> transitions,
                Set<String> activatedOccurrenceIdentities,
                Set<OccurrencePath> retiredOccurrencePaths) {
            this.bindings = Collections.unmodifiableList(
                    new ArrayList<ManagedOccurrenceBinding>(bindings));
            this.transitions = Collections.unmodifiableList(
                    new ArrayList<OccurrenceTransition>(transitions));
            this.activatedOccurrenceIdentities =
                    Collections.unmodifiableSet(new LinkedHashSet<String>(
                            activatedOccurrenceIdentities));
            this.retiredOccurrencePaths = Collections.unmodifiableSet(
                    new LinkedHashSet<OccurrencePath>(
                            retiredOccurrencePaths));
        }

        List<ManagedOccurrenceBinding> bindings() {
            return bindings;
        }

        List<OccurrenceTransition> transitions() {
            return transitions;
        }

        Set<String> activatedOccurrenceIdentities() {
            return activatedOccurrenceIdentities;
        }

        Set<OccurrencePath> retiredOccurrencePaths() {
            return retiredOccurrencePaths;
        }
    }
}
