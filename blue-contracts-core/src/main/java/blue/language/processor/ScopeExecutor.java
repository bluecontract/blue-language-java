package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.model.wire.JsonPointer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Handles scope traversal, embedded processing, cascades, and lifecycle delivery.
 *
 * <p>Each {@link ProcessorInvocationState} owns a single instance which
 * orchestrates the five-phase algorithm for a scope. Consolidating the logic
 * here keeps {@code ProcessorEngine} primarily focused on composition.</p>
 */
final class ScopeExecutor {

    private final ProcessorInvocationServices owner;
    private final ProcessorInvocationState execution;
    private final DocumentProcessingRuntime runtime;
    private final ChannelRunner channelRunner;
    private final ScopeParticipationRegistry participation;
    private final ScopeFrameFactory frameFactory;
    private final ScopePropagationChain propagationChain;
    private final ScopeLifecycleExecutor lifecycleExecutor;
    private final ExternalCandidateProjector candidateProjector;
    private final ScopeMutationExecutor mutationExecutor;

    ScopeExecutor(ProcessorInvocationServices owner,
                  ProcessorInvocationState execution,
                  DocumentProcessingRuntime runtime,
                  Map<String, ContractBundle> bundles,
                  ChannelRunner channelRunner) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.execution = Objects.requireNonNull(execution, "execution");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.channelRunner = Objects.requireNonNull(channelRunner, "channelRunner");
        this.participation = new ScopeParticipationRegistry(
                Objects.requireNonNull(bundles, "bundles"));
        this.frameFactory = new ScopeFrameFactory(
                owner, execution, runtime, participation);
        ScopeHandlerDispatcher handlerDispatcher =
                new ScopeHandlerDispatcher(owner, execution, runtime);
        this.propagationChain = new ScopePropagationChain(
                owner,
                execution,
                runtime,
                participation,
                frameFactory,
                handlerDispatcher);
        this.lifecycleExecutor = new ScopeLifecycleExecutor(
                execution,
                runtime,
                handlerDispatcher,
                propagationChain);
        this.candidateProjector = new ExternalCandidateProjector(
                owner, execution, runtime);
        DocumentUpdateRouter updateRouter = new DocumentUpdateRouter(
                owner,
                execution,
                runtime,
                participation,
                frameFactory,
                propagationChain,
                channelRunner);
        this.mutationExecutor = new ScopeMutationExecutor(
                owner,
                execution,
                runtime,
                new PatchPreflight(owner, runtime),
                updateRouter);
    }

    void initializeScope(String scopePath, boolean chargeScopeEntry) {
        initializeScope(scopePath, chargeScopeEntry, true);
    }

    private void initializeScope(String scopePath, boolean chargeScopeEntry, boolean finalizeAfterInitialization) {
        String normalizedScope = ProcessorEngine.normalizeScope(scopePath);
        Set<String> processedEmbedded = new LinkedHashSet<>();
        ContractBundle bundle = null;
        ScopeRuntimeContext scopeContext = runtime.scope(normalizedScope);
        if (JsonPointer.ROOT.equals(normalizedScope)) {
            runtime.setScopeEmbeddedDepth(normalizedScope, 0);
        }
        scopeContext.clearProcessedEmbeddedPaths();

        if (chargeScopeEntry) {
            runtime.chargeScopeEntry(normalizedScope);
        }

        try {
            if (runtime.hasTerminationMarker(normalizedScope)) {
                runtime.markScopeTerminatedFromMarker(normalizedScope);
                return;
            }
        } catch (IllegalStateException ex) {
            execution.abortRuntimeFailure(normalizedScope,
                    null,
                    ProcessorErrorCategory.InvalidReservedRuntimeState,
                    execution.fatalReason(ex, "Invalid terminated marker"));
            return;
        }

        while (true) {
            ProcessingObserver metrics = owner.observer();
            long resolvedStart = System.nanoTime();
            FrozenNode scopeNode;
            try {
                scopeNode = runtime.resolvedFrozenAt(normalizedScope);
            } finally {
                ProcessingObservations.record(
                        metrics,
                        ProcessingMetricId.BUNDLE_SCOPE_RESOLVED_LOOKUP_NANOS,
                        System.nanoTime() - resolvedStart);
            }
            if (scopeNode == null) {
                return;
            }

            bundle = frameFactory.load(
                    scopeNode,
                    normalizedScope,
                    metrics);
            participation.participate(normalizedScope, bundle);

            String childScope;
            try {
                childScope = frameFactory.nextEmbeddedChild(
                        normalizedScope, bundle, processedEmbedded);
            } catch (ProcessorEngine.BoundaryViolationException | IllegalArgumentException ex) {
                execution.abortRuntimeFailure(normalizedScope,
                        bundle,
                        ProcessorErrorCategory.PatchBoundaryViolation,
                        execution.fatalReason(ex, "Invalid embedded path"));
                return;
            }
            if (childScope == null) {
                break;
            }

            processedEmbedded.add(childScope);
            scopeContext.recordProcessedEmbeddedPath(childScope);
            runtime.attachScopeOccurrence(
                    normalizedScope,
                    childScope);
            runtime.setScopeEmbeddedDepth(childScope, runtime.scopeEmbeddedDepth(normalizedScope) + 1);
            try {
                runtime.validateProcessEmbeddedTraversalWithoutResolution(
                        childScope);
            } catch (ProcessorFailureException ex) {
                execution.abortRuntimeFailure(
                        normalizedScope,
                        bundle,
                        ex.errorCategory(),
                        execution.fatalReason(
                                ex,
                                "Invalid opaque embedded boundary"));
                return;
            }
            FrozenNode selectedChildNode = runtime.selectedFrozenAt(childScope);
            FrozenNode childNode = runtime.resolvedFrozenAt(childScope);
            if (childNode != null) {
                if (!frameFactory.isObjectScope(selectedChildNode)
                        || !frameFactory.isObjectScope(childNode)) {
                    execution.abortRuntimeFailure(normalizedScope,
                            bundle,
                            ProcessorErrorCategory.PatchBoundaryViolation,
                            "Embedded path " + childScope + " does not select an object scope");
                    return;
                }
                initializeScope(childScope, true, finalizeAfterInitialization);
            }
        }

        if (bundle == null) {
            return;
        }

        boolean initialized = runtime.hasInitializationMarker(normalizedScope);
        if (!initialized && finalizeAfterInitialization && bundle.hasCheckpoint()) {
            throw new IllegalStateException("Reserved key 'checkpoint' must not appear before initialization at scope " + normalizedScope);
        }

        if (initialized) {
            return;
        }

        runtime.chargeInitialization(normalizedScope);
        FrozenNode initialDocument;
        try {
            initialDocument =
                    runtime.capturePreInitializationScopeDocument(
                            normalizedScope);
        } catch (RuntimeException ex) {
            execution.abortRuntimeFailure(normalizedScope,
                    bundle,
                    ScopeIdentityErrorMapper.from(ex),
                    execution.fatalReason(
                            ex,
                            "Exact scope identity calculation failed"));
            return;
        }
        Node lifecycleEvent =
                ProcessorEngine.createLifecycleInitiatedEvent(
                        initialDocument);
        deliverLifecycle(normalizedScope, bundle, lifecycleEvent, false);
        if (finalizeAfterInitialization && !execution.shouldStopScopeWork(normalizedScope)) {
            propagationChain.drain();
        }
        if (!execution.shouldStopScopeWork(normalizedScope)) {
            lifecycleExecutor.publishInitializationMarker(
                    normalizedScope, initialDocument);
        }
    }

    /**
     * Executes one externally preselected occurrence without recursively
     * discovering unrelated channels or implicitly initializing the scope.
     */
    void processEvidenceDelivery(String scopePath,
                                 String channelKey,
                                 Node event) {
        String normalizedScope = ProcessorEngine.normalizeScope(scopePath);
        if (execution.shouldStopScopeWork(normalizedScope)) {
            return;
        }
        try {
            if (runtime.hasTerminationMarker(normalizedScope)) {
                runtime.markScopeTerminatedFromMarker(normalizedScope);
                return;
            }
        } catch (IllegalStateException ex) {
            execution.abortRuntimeFailure(
                    normalizedScope,
                    participation.bundle(normalizedScope),
                    ProcessorErrorCategory.InvalidReservedRuntimeState,
                    execution.fatalReason(ex, "Invalid terminated marker"));
            return;
        }
        ContractBundle bundle = participation.bundle(normalizedScope);
        if (bundle == null) {
            throw new InvalidExecutionEvidenceException(
                    "External delivery scope was not preflighted: "
                            + normalizedScope);
        }
        if (bundle == null) {
            throw new InvalidExecutionEvidenceException(
                    "External delivery scope disappeared: " + normalizedScope);
        }
        ContractBundle.ChannelBinding channel =
                bundle.channelBinding(channelKey);
        if (channel == null
                || ProcessorManagedChannelTypes.contains(
                channel.contract())) {
            throw new InvalidExecutionEvidenceException(
                    "External delivery occurrence is not executable at "
                            + normalizedScope + "/" + channelKey);
        }
        channelRunner.runExternalChannel(
                normalizedScope, bundle, channel, event);
        propagationChain.drain();
        channelRunner.persistPendingCheckpoints(normalizedScope);
    }

    ContractBundle externalClassificationBundle(
            String scopePath,
            String channelKey,
            boolean includeProcessEmbedded) {
        return externalClassificationBundle(
                scopePath,
                channelKey,
                includeProcessEmbedded,
                ExternalChannelDependencySnapshot.none());
    }

    ContractBundle externalClassificationBundle(
            String scopePath,
            String channelKey,
            boolean includeProcessEmbedded,
            ExternalChannelDependencySnapshot
                    declaredDependencies) {
        return candidateProjector.project(
                scopePath,
                channelKey,
                includeProcessEmbedded,
                declaredDependencies);
    }

    ChannelRunner.ExternalClassification classifyEvidenceDelivery(
            String scopePath,
            String channelKey,
            Node event,
            ContractBundle classificationBundle) {
        ContractBundle.ChannelBinding channel =
                candidateProjector.requireExternalSource(
                        scopePath, channelKey, classificationBundle);
        return channelRunner.classifyExternalChannel(
                ProcessorEngine.normalizeScope(scopePath),
                classificationBundle,
                channel,
                event);
    }

    void processClassifiedEvidenceDelivery(
            ChannelRunner.ExternalClassification classification) {
        if (classification == null) {
            return;
        }
        processClassifiedEvidenceDeliveryGroup(
                Collections.singletonList(classification));
    }

    void processClassifiedEvidenceDeliveryGroup(
            List<ChannelRunner.ExternalClassification>
                    classifications) {
        if (classifications == null
                || classifications.isEmpty()) {
            return;
        }
        ChannelRunner.ExternalClassification first =
                classifications.get(0);
        if (first == null || !first.acceptedNew()) {
            return;
        }
        String normalizedScope =
                ProcessorEngine.normalizeScope(
                        first.scopePath());
        if (execution.shouldStopScopeWork(normalizedScope)) {
            return;
        }
        ContractBundle bundle = participation.bundle(normalizedScope);
        if (bundle == null) {
            throw new InvalidExecutionEvidenceException(
                    "External delivery scope was not preflighted: "
                            + normalizedScope);
        }
        for (ChannelRunner.ExternalClassification classification
                : classifications) {
            if (classification == null
                    || !classification.acceptedNew()
                    || !normalizedScope.equals(
                    ProcessorEngine.normalizeScope(
                            classification.scopePath()))) {
                throw new InvalidExecutionEvidenceException(
                        "Logical delivery group changed before execution at "
                                + normalizedScope);
            }
            ContractBundle.ChannelBinding channel =
                    bundle.channelBinding(
                            classification.sourceChannelKey());
            if (channel == null
                    || ProcessorManagedChannelTypes
                    .contains(
                            channel.contract())) {
                throw new InvalidExecutionEvidenceException(
                        "External delivery occurrence changed before "
                                + "execution at "
                                + normalizedScope + "/"
                                + classification
                                .sourceChannelKey());
            }
        }
        ContractBundle checkpointBundle =
                channelRunner.runClassifiedExternalGroup(
                        classifications);
        propagationChain.drain();
        if (checkpointBundle != null
                && !execution.hasFailure()
                && execution.isScopeActive(
                normalizedScope)) {
            channelRunner.queueClassifiedCheckpoints(
                    classifications,
                    checkpointBundle);
        }
    }

    ContractBundle preflightEvidenceScope(String scopePath) {
        return preflightEvidenceScope(scopePath, true);
    }

    ContractBundle preflightEvidenceScopeAfterSelectedHeaders(
            String scopePath) {
        return preflightEvidenceScope(scopePath, false);
    }

    private ContractBundle preflightEvidenceScope(
            String scopePath,
            boolean preflightSelectedHeaders) {
        String normalizedScope = ProcessorEngine.normalizeScope(scopePath);
        runtime.validateProcessEmbeddedTraversalWithoutResolution(
                normalizedScope);
        FrozenNode selected = runtime.selectedFrozenAt(normalizedScope);
        try {
            /*
             * Classify directly present headers before effective resolution.
             * Otherwise an unknown direct type with no provider body is
             * misreported as malformed evidence rather than must-understand.
             */
            if (preflightSelectedHeaders) {
                owner.contractLoader().preflightSelectedContractHeaders(
                        selected);
            }
            FrozenNode resolved =
                    runtime.resolvedFrozenAt(normalizedScope);
            if (!frameFactory.isParticipatingScope(
                    normalizedScope, selected)
                    || !frameFactory.isParticipatingScope(
                    normalizedScope, resolved)) {
                throw new InvalidExecutionEvidenceException(
                        "Participating scope is absent or not an object: "
                                + normalizedScope);
            }
            if (runtime.hasTerminationMarker(normalizedScope)) {
                throw new InvalidExecutionEvidenceException(
                        "Participating scope is directly terminated: "
                                + normalizedScope);
            }
            return frameFactory.refresh(normalizedScope, false);
        } catch (InvalidExecutionEvidenceException exception) {
            throw exception;
        } catch (MustUnderstandFailureException exception) {
            /*
             * The feeder identifies the participating closure; support for
             * every effective contract in that closure is a processor
             * capability question, not malformed feeder evidence.
             */
            throw exception;
        } catch (RuntimeException exception) {
            if (ScopeIdentityErrorMapper.isProviderIdentityFailure(
                    exception)) {
                throw exception;
            }
            throw new InvalidExecutionEvidenceException(
                    "Participating scope preflight failed at "
                            + normalizedScope + ": "
                            + ProcessorEngine.deterministicMessage(
                            exception, "unsupported contract"));
        }
    }

    void preflightSelectedHeaders(String scopePath) {
        String normalizedScope =
                ProcessorEngine.normalizeScope(scopePath);
        owner.contractLoader().preflightSelectedContractHeaders(
                runtime.selectedFrozenAt(normalizedScope));
    }

    ContractBundle initializeEvidenceScope(String scopePath) {
        String normalizedScope = ProcessorEngine.normalizeScope(scopePath);
        if (execution.shouldStopScopeWork(normalizedScope)) {
            return null;
        }
        if (runtime.hasTerminationMarker(normalizedScope)) {
            runtime.markScopeTerminatedFromMarker(normalizedScope);
            return null;
        }
        ContractBundle bundle = participation.bundle(normalizedScope);
        if (bundle == null) {
            bundle = preflightEvidenceScope(normalizedScope);
        }
        if (runtime.hasInitializationMarker(normalizedScope)) {
            return bundle;
        }
        runtime.chargeInitialization(normalizedScope);
        FrozenNode initialDocument =
                runtime.capturePreInitializationScopeDocument(
                        normalizedScope);
        Node lifecycleEvent =
                ProcessorEngine.createLifecycleInitiatedEvent(
                        initialDocument);
        deliverLifecycle(normalizedScope, bundle, lifecycleEvent, false);
        if (execution.shouldStopScopeWork(normalizedScope)) {
            return null;
        }
        propagationChain.drain();
        if (execution.shouldStopScopeWork(normalizedScope)) {
            return null;
        }
        lifecycleExecutor.publishInitializationMarker(
                normalizedScope, initialDocument);
        return frameFactory.refresh(normalizedScope);
    }

    void handlePatch(String scopePath,
                     ContractBundle bundle,
                     JsonPatch patch,
                     boolean allowReservedMutation) {
        if (patch == null) {
            return;
        }
        handlePatches(scopePath,
                bundle,
                Collections.singletonList(patch),
                allowReservedMutation);
    }

    void handlePatches(String scopePath,
                       ContractBundle bundle,
                       List<JsonPatch> patches,
                       boolean allowReservedMutation) {
        handlePatches(scopePath, bundle, patches, allowReservedMutation, null);
    }

    void handlePatches(String scopePath,
                       ContractBundle bundle,
                       List<JsonPatch> patches,
                       boolean allowReservedMutation,
                       WorkingDocument.Preview preview) {
        handlePatchInputs(scopePath,
                bundle,
                PatchInput.mutableList(patches),
                allowReservedMutation,
                preview);
    }

    void handlePatchInputs(String scopePath,
                           ContractBundle bundle,
                           List<PatchInput> patches,
                           boolean allowReservedMutation,
                           WorkingDocument.Preview preview) {
        mutationExecutor.execute(
                scopePath,
                bundle,
                patches,
                allowReservedMutation,
                preview);
    }

    void deliverLifecycle(String scopePath,
                          ContractBundle bundle,
                          Node event,
                          boolean finalizeAfter) {
        lifecycleExecutor.deliver(scopePath, bundle, event);
    }

    void deliverTerminationLifecycle(String scopePath,
                                     ContractBundle bundle,
                                     Node event) {
        deliverLifecycle(scopePath, bundle, event, false);
    }

    void cleanupCheckpointState() {
        List<String> scopes = new ArrayList<>(
                participation.scopePaths());
        Collections.sort(scopes,
                (left, right) -> {
                    int depth = Integer.compare(
                            JsonPointer.split(right).size(),
                            JsonPointer.split(left).size());
                    return depth != 0
                            ? depth
                            : ExternalOrderKey.compareTextCodePoints(left, right);
                });
        for (String scopePath : scopes) {
            if (execution.shouldStopScopeWork(scopePath)) {
                continue;
            }
            ContractBundle bundle = frameFactory.refresh(scopePath);
            if (bundle != null) {
                channelRunner.cleanupInactiveCheckpoints(scopePath, bundle);
            }
        }
        channelRunner.persistAllPendingCheckpoints();
    }

    void requestInternalEventDrain() {
        propagationChain.requestDrain();
    }

    void drainInternalEvents() {
        propagationChain.drain();
    }
}
