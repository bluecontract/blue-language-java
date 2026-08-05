package blue.language.processor;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueOperationResult;
import blue.language.identity.BlueIds;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.resolve.ResolutionLimits;
import blue.language.runtime.LanguageRuntimeAccess;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable borrowed view of one processor generation's Language runtime.
 *
 * <p>Every operation is admitted through the source processor's lifecycle and
 * therefore observes one exact registry and runtime generation. The view owns
 * neither the processor nor its snapshot manager. Closing the source processor
 * invalidates the view.</p>
 *
 * <p>Mutable {@link Node} inputs are cloned before they cross the snapshot
 * boundary. The underlying {@link ProcessingSnapshotManager} remains
 * package-private so callers cannot publish snapshots, apply patches, or
 * release processor-owned transient state.</p>
 */
public final class ProcessorRuntimeAccess {

    private static final String LANGUAGE_RUNTIME_REQUIRED =
            "Processor runtime access requires a configured LanguageRuntimeAccess";
    private static final String SNAPSHOT_MANAGER_REQUIRED =
            "Processor runtime access requires a configured ProcessingSnapshotManager";
    private static final String EXACT_REFERENCE_ABSENT =
            "No exact provider content is available for the requested reference";
    private static final String EXACT_REFERENCE_CONTENT_REQUIRED =
            "Exact provider materialization returned a reference instead of content for ";
    private static final String EXACT_REFERENCE_IDENTITY_MISMATCH =
            "Exact provider content BlueId mismatch: expected ";
    private static final String EXACT_REFERENCE_DECLARED_IDENTITY =
            " but content declared ";
    private static final String EXACT_REFERENCE_IDENTITY_INVALID =
            "Exact provider content identity could not be calculated";
    private static final String SNAPSHOT_GENERATION_EXPIRED =
            "Processor runtime snapshot generation is no longer current";
    private static final String RUNTIME_GENERATION_CHANGED =
            "Processor runtime generation changed after it was imported";

    private final DocumentProcessor processor;
    private final DocumentProcessorLifecycle lifecycle;
    private final LanguageRuntimeAccess guardedLanguageRuntime;

    ProcessorRuntimeAccess(
            DocumentProcessor processor,
            DocumentProcessorLifecycle lifecycle) {
        this.processor = Objects.requireNonNull(processor, "processor");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.guardedLanguageRuntime =
                new GuardedLanguageRuntimeAccess();
    }

    /**
     * Returns the verified Language runtime bound to this processor generation.
     *
     * @return borrowed immutable runtime capability
     * @throws IllegalStateException if the source processor is closed or has
     *         no configured Language runtime
     */
    public LanguageRuntimeAccess languageRuntime() {
        binding();
        return guardedLanguageRuntime;
    }

    /**
     * Resolves a detached copy of one document without publishing new state.
     *
     * @param document caller-owned document
     * @return immutable transient snapshot
     * @throws NullPointerException if {@code document} is {@code null}
     * @throws IllegalStateException if this borrowed generation is unavailable
     */
    public ResolvedSnapshot resolveTransient(Node document) {
        final Node detached = Objects.requireNonNull(
                document, "document").clone();
        return call(new SnapshotOperation<ResolvedSnapshot>() {
            @Override
            public ResolvedSnapshot apply(
                    ProcessingSnapshotManager snapshotManager) {
                return snapshotManager.fromDocumentTransient(detached);
            }
        });
    }

    /**
     * Resolves a detached document while preserving exact authored paths.
     *
     * @param document caller-owned document
     * @param preservedPaths absolute paths retained in authored form
     * @return immutable transient snapshot with the selected paths deferred
     * @throws NullPointerException if either argument is {@code null}
     * @throws IllegalStateException if this borrowed generation is unavailable
     */
    public ResolvedSnapshot resolveTransientPreservingPaths(
            Node document,
            Collection<String> preservedPaths) {
        final Node detached = Objects.requireNonNull(
                document, "document").clone();
        final Collection<String> detachedPaths =
                Collections.unmodifiableList(new ArrayList<>(
                        Objects.requireNonNull(
                                preservedPaths, "preservedPaths")));
        return call(new SnapshotOperation<ResolvedSnapshot>() {
            @Override
            public ResolvedSnapshot apply(
                    ProcessingSnapshotManager snapshotManager) {
                return snapshotManager
                        .fromDocumentTransientPreservingPaths(
                                detached, detachedPaths);
            }
        });
    }

    /**
     * Materializes exact provider content with exhaustive typed outcomes.
     *
     * @param reference immutable value or pure reference to materialize
     * @return established, absent, incomplete, or invalid materialization
     *         outcome
     * @throws NullPointerException if {@code reference} is {@code null}
     * @throws IllegalStateException if this borrowed generation is unavailable
     */
    public BlueOperationResult<FrozenNode>
    materializeVerifiedExactReference(FrozenNode reference) {
        final FrozenNode exactReference = Objects.requireNonNull(
                reference, "reference");
        return call(new SnapshotOperation<BlueOperationResult<FrozenNode>>() {
            @Override
            public BlueOperationResult<FrozenNode> apply(
                    ProcessingSnapshotManager snapshotManager) {
                try {
                    FrozenNode materialized = snapshotManager
                            .materializeVerifiedExactReference(
                                    exactReference);
                    return verifiedMaterialization(
                            exactReference, materialized);
                } catch (ExecutionEvidenceUnavailableException unavailable) {
                    return BlueOperationResult.incomplete(
                            null,
                            new LinkedHashSet<>(
                                    unavailable.requiredExactBlueIds()),
                            null,
                            unavailable.getMessage());
                } catch (InvalidExecutionEvidenceException invalid) {
                    return BlueOperationResult.invalid(
                            invalid.getMessage(), null);
                } catch (IllegalArgumentException invalid) {
                    return BlueOperationResult.invalid(
                            invalid.getMessage(), null);
                }
            }
        });
    }

    /**
     * Reports whether the source processor and snapshot generation remain live.
     *
     * @return {@code true} while this borrowed access can admit operations
     */
    public boolean isCurrent() {
        if (lifecycle.isClosed()) {
            return false;
        }
        try (DocumentProcessorLifecycle.ReadScope ignored =
                     lifecycle.openRead(processor.registry())) {
            ProcessingSnapshotManager snapshotManager =
                    processor.snapshotManager();
            return processor.languageRuntimeAccess() != null
                    && snapshotManager != null
                    && snapshotManager.isTransientStateCurrent();
        } catch (IllegalStateException unavailable) {
            return false;
        }
    }

    /** Captures both borrowed collaborators under one lifecycle read. */
    Binding binding() {
        try (DocumentProcessorLifecycle.ReadScope ignored =
                     lifecycle.openRead(processor.registry())) {
            LanguageRuntimeAccess languageRuntime =
                    processor.languageRuntimeAccess();
            if (languageRuntime == null) {
                throw new IllegalStateException(
                        LANGUAGE_RUNTIME_REQUIRED);
            }
            ProcessingSnapshotManager snapshotManager =
                    processor.snapshotManager();
            if (snapshotManager == null) {
                throw new IllegalStateException(
                        SNAPSHOT_MANAGER_REQUIRED);
            }
            if (!snapshotManager.isTransientStateCurrent()) {
                throw new IllegalStateException(
                        SNAPSHOT_GENERATION_EXPIRED);
            }
            return new Binding(
                    languageRuntime,
                    snapshotManager,
                    new GenerationGuard(
                            processor,
                            lifecycle,
                            languageRuntime,
                            snapshotManager));
        }
    }

    private <T> T call(SnapshotOperation<T> operation) {
        Objects.requireNonNull(operation, "operation");
        try (DocumentProcessorLifecycle.ReadScope ignored =
                     lifecycle.openRead(processor.registry())) {
            if (processor.languageRuntimeAccess() == null) {
                throw new IllegalStateException(
                        LANGUAGE_RUNTIME_REQUIRED);
            }
            ProcessingSnapshotManager snapshotManager =
                    processor.snapshotManager();
            if (snapshotManager == null) {
                throw new IllegalStateException(
                        SNAPSHOT_MANAGER_REQUIRED);
            }
            if (!snapshotManager.isTransientStateCurrent()) {
                throw new IllegalStateException(
                        SNAPSHOT_GENERATION_EXPIRED);
            }
            return operation.apply(snapshotManager);
        }
    }

    private BlueOperationResult<FrozenNode> verifiedMaterialization(
            FrozenNode reference,
            FrozenNode materialized) {
        if (materialized == null) {
            return BlueOperationResult.absent(
                    EXACT_REFERENCE_ABSENT);
        }
        if (!reference.isReferenceOnly()) {
            return BlueOperationResult.established(materialized);
        }
        String requestedBlueId = reference.getReferenceBlueId();
        if (materialized.isReferenceOnly()) {
            return BlueOperationResult.invalid(
                    EXACT_REFERENCE_CONTENT_REQUIRED
                            + requestedBlueId,
                    null);
        }
        String declaredBlueId =
                materialized.getReferenceBlueId();
        if (declaredBlueId != null
                && !requestedBlueId.equals(declaredBlueId)) {
            return BlueOperationResult.invalid(
                    EXACT_REFERENCE_IDENTITY_MISMATCH
                            + requestedBlueId
                            + EXACT_REFERENCE_DECLARED_IDENTITY
                            + declaredBlueId,
                    null);
        }
        if (BlueIds.hasCyclicMemberSeparator(
                requestedBlueId)) {
            /*
             * The manager has already required the complete cyclic-set proof.
             * One member has no ordinary standalone identity input, so hashing
             * it independently here would reject valid exact evidence.
             */
            return BlueOperationResult.established(materialized);
        }
        final String calculatedBlueId;
        try {
            Node canonicalContent = materialized.toNode();
            if (canonicalContent.getBlueId() != null) {
                /* Root BlueId is provider provenance, not canonical content. */
                canonicalContent.blueId(null);
            }
            calculatedBlueId =
                    DirectBlueIdCalculator.calculateBlueId(
                            canonicalContent);
        } catch (RuntimeException invalidIdentity) {
            String detail = invalidIdentity.getMessage();
            return BlueOperationResult.invalid(
                    detail == null || detail.isEmpty()
                            ? EXACT_REFERENCE_IDENTITY_INVALID
                            : EXACT_REFERENCE_IDENTITY_INVALID
                                    + ": " + detail,
                    null);
        }
        if (!requestedBlueId.equals(calculatedBlueId)) {
            return BlueOperationResult.invalid(
                    EXACT_REFERENCE_IDENTITY_MISMATCH
                            + requestedBlueId
                            + " but calculated "
                            + calculatedBlueId,
                    null);
        }
        return BlueOperationResult.established(materialized);
    }

    private <T> T callRuntime(
            RuntimeOperation<T> operation) {
        Objects.requireNonNull(operation, "operation");
        try (DocumentProcessorLifecycle.ReadScope ignored =
                     lifecycle.openRead(processor.registry())) {
            LanguageRuntimeAccess runtime =
                    processor.languageRuntimeAccess();
            if (runtime == null) {
                throw new IllegalStateException(
                        LANGUAGE_RUNTIME_REQUIRED);
            }
            ProcessingSnapshotManager snapshotManager =
                    processor.snapshotManager();
            if (snapshotManager == null) {
                throw new IllegalStateException(
                        SNAPSHOT_MANAGER_REQUIRED);
            }
            if (!snapshotManager.isTransientStateCurrent()) {
                throw new IllegalStateException(
                        SNAPSHOT_GENERATION_EXPIRED);
            }
            return operation.apply(runtime);
        }
    }

    /** Public runtime view that re-enters the source generation per call. */
    private final class GuardedLanguageRuntimeAccess
            implements LanguageRuntimeAccess {

        private final NodeProvider guardedProvider =
                new GuardedNodeProvider();

        @Override
        public NodeProvider getNodeProvider() {
            callRuntime(LanguageRuntimeAccess::getNodeProvider);
            return guardedProvider;
        }

        @Override
        public BlueCachePolicy matchingCachePolicy() {
            return callRuntime(
                    LanguageRuntimeAccess::matchingCachePolicy);
        }

        @Override
        public BlueCachePolicy cachePolicy() {
            return callRuntime(LanguageRuntimeAccess::cachePolicy);
        }

        @Override
        public String languageVersion() {
            return callRuntime(LanguageRuntimeAccess::languageVersion);
        }

        @Override
        public Map<String, String> preprocessingAliases() {
            return callRuntime(
                    LanguageRuntimeAccess::preprocessingAliases);
        }

        @Override
        public Map<String, String> environmentImports() {
            return callRuntime(
                    LanguageRuntimeAccess::environmentImports);
        }

        @Override
        public Node canonicalizeSourceContent(Node source) {
            return callRuntime(runtime ->
                    runtime.canonicalizeSourceContent(source));
        }

        @Override
        public String canonicalRegistryIdentity() {
            return callRuntime(
                    LanguageRuntimeAccess::canonicalRegistryIdentity);
        }

        @Override
        public Node preprocessForMatching(Node source) {
            return callRuntime(runtime ->
                    runtime.preprocessForMatching(source));
        }

        @Override
        public void expandForMatching(
                Node source,
                ResolutionLimits limits) {
            callRuntime(runtime -> {
                runtime.expandForMatching(source, limits);
                return null;
            });
        }

        @Override
        public Node resolveForMatching(
                Node source,
                ResolutionLimits limits) {
            return callRuntime(runtime ->
                    runtime.resolveForMatching(source, limits));
        }

        @Override
        public FrozenNode materializeTypeReferenceForMatching(
                FrozenNode reference) {
            return callRuntime(runtime ->
                    runtime.materializeTypeReferenceForMatching(
                            reference));
        }

        @Override
        public Node canonicalize(Node source) {
            return callRuntime(runtime ->
                    runtime.canonicalize(source));
        }

        @Override
        public String calculateSourceDocumentBlueId(
                Node source) {
            return callRuntime(runtime ->
                    runtime.calculateSourceDocumentBlueId(
                            source));
        }

        /** Provider view that never leaks the raw runtime provider. */
        private final class GuardedNodeProvider
                implements NodeProvider {

            @Override
            public List<Node> fetchByBlueId(String blueId) {
                return callRuntime(runtime ->
                        runtime.getNodeProvider()
                                .fetchByBlueId(blueId));
            }

            @Override
            public NodeProviderResult fetchResultByBlueId(
                    String blueId) {
                return callRuntime(runtime ->
                        runtime.getNodeProvider()
                                .fetchResultByBlueId(blueId));
            }

            @Override
            public Node fetchFirstByBlueId(String blueId) {
                return callRuntime(runtime ->
                        runtime.getNodeProvider()
                                .fetchFirstByBlueId(blueId));
            }
        }
    }

    /** Atomic borrowed collaborator capture for builder state. */
    static final class Binding {
        final LanguageRuntimeAccess languageRuntime;
        final ProcessingSnapshotManager snapshotManager;
        final GenerationGuard generationGuard;

        private Binding(
                LanguageRuntimeAccess languageRuntime,
                ProcessingSnapshotManager snapshotManager,
                GenerationGuard generationGuard) {
            this.languageRuntime = languageRuntime;
            this.snapshotManager = snapshotManager;
            this.generationGuard = generationGuard;
        }
    }

    /** Retained source-generation admission guard for imported processors. */
    static final class GenerationGuard {
        private final DocumentProcessor processor;
        private final DocumentProcessorLifecycle lifecycle;
        private final LanguageRuntimeAccess expectedLanguageRuntime;
        private final ProcessingSnapshotManager expectedSnapshotManager;

        private GenerationGuard(
                DocumentProcessor processor,
                DocumentProcessorLifecycle lifecycle,
                LanguageRuntimeAccess expectedLanguageRuntime,
                ProcessingSnapshotManager expectedSnapshotManager) {
            this.processor = processor;
            this.lifecycle = lifecycle;
            this.expectedLanguageRuntime = expectedLanguageRuntime;
            this.expectedSnapshotManager = expectedSnapshotManager;
        }

        /** Acquires and validates the exact source generation atomically. */
        GenerationLease open() {
            DocumentProcessorLifecycle.ReadScope sourceRead =
                    lifecycle.openRead(processor.registry());
            try {
                requireCurrentGeneration();
                return new GenerationLease(sourceRead);
            } catch (RuntimeException | Error failure) {
                sourceRead.close();
                throw failure;
            }
        }

        private void requireCurrentGeneration() {
            if (processor.languageRuntimeAccess()
                    != expectedLanguageRuntime
                    || processor.snapshotManager()
                    != expectedSnapshotManager) {
                throw new IllegalStateException(
                        RUNTIME_GENERATION_CHANGED);
            }
            if (!expectedSnapshotManager
                    .isTransientStateCurrent()) {
                throw new IllegalStateException(
                        SNAPSHOT_GENERATION_EXPIRED);
            }
        }
    }

    /** One held source-generation admission lease. */
    static final class GenerationLease implements AutoCloseable {
        private DocumentProcessorLifecycle.ReadScope sourceRead;

        private GenerationLease(
                DocumentProcessorLifecycle.ReadScope sourceRead) {
            this.sourceRead = sourceRead;
        }

        @Override
        public void close() {
            if (sourceRead != null) {
                sourceRead.close();
                sourceRead = null;
            }
        }
    }

    private interface SnapshotOperation<T> {
        T apply(ProcessingSnapshotManager snapshotManager);
    }

    private interface RuntimeOperation<T> {
        T apply(LanguageRuntimeAccess runtime);
    }
}
