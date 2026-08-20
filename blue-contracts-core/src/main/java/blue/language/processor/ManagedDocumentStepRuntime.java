package blue.language.processor;

import blue.language.model.Node;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.model.DocumentUpdateChannel;
import blue.language.processor.model.EmbeddedNodeChannel;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.LifecycleChannel;
import blue.language.processor.model.TriggeredEventChannel;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Executes already-selected managed-document work against independent Root
 * runtimes sharing one semantic gas/admission context.
 *
 * <p>This processor-side SPI performs no closure selection, graph mutation,
 * identity finalization, public-event projection, global FIFO admission, or
 * commit. Those boundaries remain with the supplied synchronous
 * continuation.</p>
 */
public final class ManagedDocumentStepRuntime implements AutoCloseable {

    private final ProcessorInvocationServices owner;
    private final ProcessingGasContext sharedGasContext;
    private final ManagedDocumentStepContinuation continuation;
    private boolean closed;

    /**
     * Creates a fail-closed isolated runtime over one processor generation.
     *
     * @param owner immutable processor generation retained by this session
     */
    public ManagedDocumentStepRuntime(DocumentProcessor owner) {
        this(owner, rejectingContinuation());
    }

    /**
     * Creates an isolated runtime with a closure-owned continuation.
     *
     * @param owner immutable processor generation
     * @param continuation synchronous closure effect boundary
     */
    public ManagedDocumentStepRuntime(
            DocumentProcessor owner,
            ManagedDocumentStepContinuation continuation) {
        DocumentProcessor checked = Objects.requireNonNull(owner, "owner");
        this.owner = ProcessorInvocationServices.configured(checked);
        this.sharedGasContext = this.owner.newGasContext();
        this.continuation = Objects.requireNonNull(
                continuation, "continuation");
    }

    /**
     * Creates one closure session under an exact shared and member-local
     * execution policy.
     *
     * @param owner immutable processor generation, retained but not owned
     * @param sharedGasLimit invocation-wide limit at or below the manifest max
     * @param localGasLimits optional caps keyed by stable document id text
     * @param continuation synchronous closure effect boundary
     */
    public ManagedDocumentStepRuntime(
            DocumentProcessor owner,
            long sharedGasLimit,
            Map<String, Long> localGasLimits,
            ManagedDocumentStepContinuation continuation) {
        DocumentProcessor checked = Objects.requireNonNull(owner, "owner");
        this.owner = ProcessorInvocationServices.configured(checked);
        GasMeter meter = new GasMeter(
                this.owner.gasSchedule(), sharedGasLimit);
        meter.configureLocalGasLimits(
                localGasLimits != null
                        ? localGasLimits
                        : Collections.<String, Long>emptyMap());
        this.sharedGasContext = new ProcessingGasContext(meter);
        this.continuation = Objects.requireNonNull(
                continuation, "continuation");
    }

    /**
     * Executes one selected document using the shared session gas context.
     *
     * @param request exact Root-scoped work and target evidence
     * @return pre-finalization local outcome
     */
    public ManagedDocumentStepOutcome execute(
            ManagedDocumentStepRequest request) {
        ManagedDocumentStepRequest admitted = Objects.requireNonNull(
                request, "request");
        ensureOpen();
        Node document = admitted.exactDocument();
        validateTargetState(admitted, document);
        DocumentProcessingResult invalid =
                ProcessingInputAdmission.validateDocument(document);
        if (invalid != null) {
            ProcessorDiagnostic diagnostic = invalid.diagnostic();
            throw new ProcessorFailureException(
                    diagnostic != null
                            ? diagnostic.category()
                            : ProcessorErrorCategory.InvalidProcessingDocument,
                    diagnostic != null
                            ? diagnostic.message()
                            : "Invalid managed document-step Root");
        }

        ProcessorMarkerStore.collapseInitializationDocuments(document);
        Node exactPayload = admitted.exactPayload();
        Node processEvent = admitted.workKind()
                == ManagedDocumentWorkKind.EXTERNAL_DELIVERY
                ? exactPayload
                : null;
        List<FrozenJsonPatch> orderedPatches =
                new ArrayList<FrozenJsonPatch>();
        List<Node> orderedEmittedEvents =
                new ArrayList<Node>();
        ManagedDocumentStepContinuation stepContinuation =
                collectingContinuation(
                        orderedPatches, orderedEmittedEvents);
        long gasBefore = sharedGasContext.meter().totalGas();
        ProcessorInvocationState execution = new ProcessorInvocationState(
                owner,
                document,
                processEvent,
                FrozenNode::fromResolvedNode,
                sharedGasContext,
                stepContinuation,
                owner.snapshotManager() != null
                        ? new ManagedDocumentOverlaySnapshotManager(
                                owner.snapshotManager(),
                                admitted.resolutionOverlay())
                        : null);
        try (GasMeter.AttributionScope ignored =
                     sharedGasContext.withAttribution(
                             admitted.attribution())) {
            try {
                execution.executeIsolatedManagedRootWork(admitted);
            } catch (RunTerminationException terminated) {
                /* Ordinary Root termination is control flow; failure follows. */
            }
        }
        if (execution.hasFailure()) {
            DocumentProcessingResult failed = execution.partialResult();
            ProcessorDiagnostic diagnostic = failed.diagnostic();
            throw new ProcessorFailureException(
                    diagnostic != null
                            ? diagnostic.category()
                            : ProcessorErrorCategory.RuntimeExecutionFailure,
                    diagnostic != null && diagnostic.message() != null
                            ? diagnostic.message()
                            : "Managed document-step execution failed");
        }
        DocumentProcessingRuntime runtime = execution.runtime();
        if (runtime.hasPendingEventOccurrences()
                || !runtime.rootEmissions().isEmpty()) {
            throw new IllegalStateException(
                    "Isolated managed-document events must leave through "
                            + "the closure continuation only");
        }
        long gasAfter = sharedGasContext.meter().totalGas();
        return new ManagedDocumentStepOutcome(
                runtime.document(),
                orderedEmittedEvents,
                orderedPatches,
                gasBefore,
                gasAfter,
                !runtime.changedPaths().isEmpty());
    }

    /**
     * Applies one processor-owned state write to a detached exact Root while
     * retaining this session's shared gas meter and exact closure attribution.
     *
     * <p>This is the narrow mutation boundary used by closure-owned logical
     * marker batches. It performs the ordinary processor direct-write
     * preflight, semantic identity charging, and atomic detached publication.
     * It performs no closure planning, work admission, provider resolution, or
     * authoritative document publication.</p>
     *
     * @param exactRoot exact independently managed Root before the write
     * @param path absolute processor-owned state path
     * @param exactValue exact value to add or replace, or {@code null} to remove
     * @param attribution exact owning document/batch gas attribution
     * @return detached exact Root after the successful write
     */
    public Node writeDetachedProcessorState(
            Node exactRoot,
            String path,
            Node exactValue,
            GasChargeContext attribution) {
        ensureOpen();
        Node document = Objects.requireNonNull(
                exactRoot, "exactRoot").clone();
        DocumentProcessingRuntime detached = new DocumentProcessingRuntime(
                document,
                owner.conformanceEngine(),
                owner.conformancePlannerOverride(),
                null,
                owner.observer(),
                sharedGasContext,
                owner.registry().executableBodyFieldsByType(),
                owner.strictPlatformInvocation());
        try (GasMeter.AttributionScope ignored =
                     sharedGasContext.withAttribution(
                             Objects.requireNonNull(
                                     attribution, "attribution"))) {
            detached.directWrite(
                    Objects.requireNonNull(path, "path"),
                    exactValue != null ? exactValue.clone() : null);
        }
        return detached.document().clone();
    }

    /**
     * Selects matching Root Triggered Event channels without executing,
     * enqueueing, draining, or charging them.
     *
     * @param exactDocument exact emitter document state
     * @param exactEvent exact admitted semantic event
     * @return immutable routes in channel order/key order
     */
    public List<ManagedDocumentStepRoute> classifyTriggeredEventRoutes(
            Node exactDocument,
            Node exactEvent) {
        ensureOpen();
        Node event = Objects.requireNonNull(exactEvent, "exactEvent").clone();
        ContractBundle bundle = classifyRootBundle(exactDocument);
        List<ManagedDocumentStepRoute> routes =
                new ArrayList<ManagedDocumentStepRoute>();
        for (ContractBundle.ChannelBinding binding
                : bundle.channelsOfType(TriggeredEventChannel.class)) {
            TriggeredEventChannel channel =
                    (TriggeredEventChannel) binding.contract();
            if (channel.getEvent() == null
                    || owner.matchingService().matches(
                            event, channel.getEvent())) {
                routes.add(new ManagedDocumentStepRoute(
                        ManagedDocumentWorkKind.TRIGGERED_EVENT,
                        binding.key(),
                        event,
                        null));
            }
        }
        return Collections.unmodifiableList(routes);
    }

    /**
     * Freezes the Root lifecycle-channel routes for one initialization event.
     * This is a read-only Phase-B operation: it neither charges nor executes
     * application code.
     *
     * @param exactDocument exact independently managed Root
     * @param exactEvent exact Document Processing Initiated value
     * @return immutable routes in canonical contract-key order
     */
    public List<ManagedDocumentStepRoute> classifyLifecycleRoutes(
            Node exactDocument,
            Node exactEvent) {
        ensureOpen();
        Node event = Objects.requireNonNull(exactEvent, "exactEvent").clone();
        ContractBundle bundle = classifyRootBundle(exactDocument);
        List<ManagedDocumentStepRoute> routes =
                new ArrayList<ManagedDocumentStepRoute>();
        for (ContractBundle.ChannelBinding binding
                : bundle.channelsOfType(LifecycleChannel.class)) {
            routes.add(new ManagedDocumentStepRoute(
                    ManagedDocumentWorkKind.LIFECYCLE,
                    binding.key(),
                    event,
                    null));
        }
        return Collections.unmodifiableList(routes);
    }

    /**
     * Validates one exact Root's complete declaration-covered managed path
     * surface without executing, enqueueing, or charging closure gas.
     *
     * @param exactDocument exact independently managed Root
     * @param expectedBlueIdsByPath complete current path-to-target evidence
     */
    public void validateManagedEmbeddedPaths(
            Node exactDocument,
            Map<String, String> expectedBlueIdsByPath) {
        ensureOpen();
        long gasBefore = sharedGasContext.meter().totalGas();
        Node document = Objects.requireNonNull(
                exactDocument, "exactDocument").clone();
        ProcessorMarkerStore.collapseInitializationDocuments(document);
        DocumentProcessingRuntime view = new DocumentProcessingRuntime(
                document,
                owner.conformanceEngine(),
                owner.conformancePlannerOverride(),
                owner.snapshotManager(),
                owner.observer(),
                sharedGasContext,
                owner.registry().executableBodyFieldsByType(),
                owner.strictPlatformInvocation());
        FrozenNode selected = view.selectedFrozenAt(JsonPointer.ROOT);
        owner.contractLoader().preflightSelectedContractHeaders(selected);
        FrozenNode resolved = view.resolvedFrozenAt(JsonPointer.ROOT);
        FrozenNode recognition = view.contractRecognitionScope(
                selected, resolved);
        ContractBundle bundle = owner.contractLoader().load(
                selected,
                recognition,
                JsonPointer.ROOT,
                owner.observer(),
                null,
                null);
        Map<String, String> expected = Objects.requireNonNull(
                expectedBlueIdsByPath, "expectedBlueIdsByPath");
        if (!bundle.hasProcessEmbedded()) {
            if (!expected.isEmpty()) {
                throw new InvalidExecutionEvidenceException(
                        "Managed occurrences are not covered by Process Embedded",
                        ProcessorErrorCategory.SubscriptionSurfaceInvalid);
            }
        } else {
            EmbeddedScopeDeclaration declaration =
                    bundle.embeddedScopeDeclaration();
            new EmbeddedScopePlanner().planForOpaqueManagedRoot(
                    resolved,
                    JsonPointer.ROOT,
                    declaration.explicitPaths(),
                    declaration.collectionPaths(),
                    expected,
                    owner.gasSchedule());
        }
        if (sharedGasContext.meter().totalGas() != gasBefore) {
            throw new IllegalStateException(
                    "Managed declaration validation must not charge shared gas");
        }
    }

    /**
     * Selects matching Root Embedded Event channels and constructs their exact
     * adapter wrappers without executing, enqueueing, draining, or charging.
     *
     * @param exactContainingDocument exact independently managed receiver
     * @param exactSourcePath exact Root-relative contained occurrence path
     * @param exactEvent exact admitted originating event
     * @param exactEventBlueId already-admitted identity of {@code exactEvent}
     * @return immutable embedded routes in channel order/key order
     */
    public List<ManagedDocumentStepRoute> classifyEmbeddedEventRoutes(
            Node exactContainingDocument,
            String exactSourcePath,
            Node exactEvent,
            String exactEventBlueId) {
        ensureOpen();
        String sourcePath = ProcessorEngine.normalizeScope(
                Objects.requireNonNull(exactSourcePath, "exactSourcePath"));
        if (JsonPointer.ROOT.equals(sourcePath)) {
            throw new IllegalArgumentException(
                    "An embedded-event source must be below Root");
        }
        Node event = Objects.requireNonNull(exactEvent, "exactEvent").clone();
        String eventBlueId = Objects.requireNonNull(
                exactEventBlueId, "exactEventBlueId");
        String computed = CheckpointIdentityCalculator.identity(
                event, owner.languageRuntimeAccess());
        if (!eventBlueId.equals(computed)) {
            throw new InvalidExecutionEvidenceException(
                    "Embedded event identity does not identify exactEvent",
                    ProcessorErrorCategory.InvalidProcessingEvent);
        }
        String adapterSourcePath = ProcessorEngine.relativizePointer(
                JsonPointer.ROOT, sourcePath);
        Node wrapper = new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.EMBEDDED_EVENT_DELIVERY))
                .properties(
                        ProcessorContractConstants.KEY_SOURCE_PATH,
                        new Node().value(adapterSourcePath))
                .properties(
                        ProcessorContractConstants.KEY_EVENT,
                        new Node().blueId(eventBlueId));
        ContractBundle bundle = classifyRootBundle(
                exactContainingDocument);
        List<ManagedDocumentStepRoute> routes =
                new ArrayList<ManagedDocumentStepRoute>();
        for (ContractBundle.ChannelBinding binding
                : bundle.channelsOfType(EmbeddedNodeChannel.class)) {
            EmbeddedNodeChannel channel =
                    (EmbeddedNodeChannel) binding.contract();
            String configured = channel.getSourcePath();
            boolean pathMatches = configured == null
                    || ProcessorEngine.resolvePointer(
                            JsonPointer.ROOT, configured).equals(sourcePath);
            boolean eventMatches = channel.getEvent() == null
                    || owner.matchingService().matches(
                            event, channel.getEvent());
            if (pathMatches && eventMatches) {
                routes.add(new ManagedDocumentStepRoute(
                        ManagedDocumentWorkKind.EMBEDDED_EVENT,
                        binding.key(),
                        wrapper,
                        event));
            }
        }
        return Collections.unmodifiableList(routes);
    }

    /**
     * Selects matching Root Document Update channels and renders their exact
     * local payloads without executing, enqueueing, draining, or charging.
     *
     * @param exactDocument exact independently managed receiver
     * @param occurrence immutable patch occurrence from a continuation barrier
     * @return immutable update routes in channel order/key order
     */
    public List<ManagedDocumentStepRoute> classifyDocumentUpdateRoutes(
            Node exactDocument,
            DocumentUpdateOccurrence occurrence) {
        ensureOpen();
        DocumentUpdateOccurrence update = Objects.requireNonNull(
                occurrence, "occurrence");
        ContractBundle bundle = classifyRootBundle(exactDocument);
        DocumentUpdateData data = new DocumentUpdateData(
                update.path(),
                update.before(),
                update.after(),
                update.op(),
                update.originScope(),
                update.recipientChain());
        Node payload = ProcessorEngine.createDocumentUpdateEvent(
                data, JsonPointer.ROOT);
        List<ManagedDocumentStepRoute> routes =
                new ArrayList<ManagedDocumentStepRoute>();
        for (ContractBundle.ChannelBinding binding
                : bundle.channelsOfType(DocumentUpdateChannel.class)) {
            DocumentUpdateChannel channel =
                    (DocumentUpdateChannel) binding.contract();
            if (ProcessorEngine.matchesDocumentUpdate(
                    JsonPointer.ROOT,
                    channel.getPath(),
                    update.path())) {
                routes.add(new ManagedDocumentStepRoute(
                        ManagedDocumentWorkKind.DOCUMENT_UPDATE,
                        binding.key(),
                        payload,
                        null));
            }
        }
        return Collections.unmodifiableList(routes);
    }

    /**
     * Constructs and classifies one processor-managed reference-rewrite update
     * after exact tentative finalization.
     *
     * @param exactDocument exact finalized receiving Root
     * @param absolutePath exact changed occurrence path
     * @param before exact pre-finalization value
     * @param after exact finalized value
     * @return immutable matching Root Document Update routes
     */
    public List<ManagedDocumentStepRoute>
    classifyFinalizationDocumentUpdateRoutes(
            Node exactDocument,
            String absolutePath,
            Node before,
            Node after) {
        DocumentUpdateOccurrence occurrence =
                new DocumentUpdateOccurrence(
                        Objects.requireNonNull(
                                absolutePath, "absolutePath"),
                        before,
                        after,
                        before == null
                                ? JsonPatch.Op.ADD
                                : after == null
                                        ? JsonPatch.Op.REMOVE
                                        : JsonPatch.Op.REPLACE,
                        JsonPointer.ROOT,
                        Collections.singletonList(JsonPointer.ROOT));
        return classifyDocumentUpdateRoutes(
                exactDocument, occurrence);
    }

    /**
     * Projects every effective Channel of one independently managed Root
     * without executing a Handler, admitting an event, or charging gas.
     *
     * @param exactRoot exact independently managed Root
     * @return immutable canonical Channel occurrence surface
     */
    public List<ManagedRootChannelOccurrence> projectRootChannelSurface(
            Node exactRoot) {
        ensureOpen();
        return settlementService().projectRootChannelSurface(exactRoot);
    }

    /**
     * Projects the complete non-recursive Root Channel and external-route
     * surface without executing a Handler or admitting an event.
     *
     * @param exactRoot exact independently managed Root
     * @return immutable Root-only subscription surface
     */
    public ManagedRootSubscriptionSurface projectRootSubscriptionSurface(
            Node exactRoot) {
        ensureOpen();
        return settlementService().projectRootSubscriptionSurface(exactRoot);
    }

    /**
     * Performs read-only Phase-B acceptance and checkpoint comparison for one
     * exact raw Root source occurrence on this session's shared meter.
     *
     * @param exactRoot exact independently managed Root before local work
     * @param rawChannelKey exact raw External Channel key
     * @param exactEvent exact externally admitted event
     * @param attribution exact owning document/work gas attribution
     * @return immutable classification and optional frozen candidate
     */
    public ManagedExternalDeliveryClassification classifyExternalDelivery(
            Node exactRoot,
            String rawChannelKey,
            Node exactEvent,
            GasChargeContext attribution) {
        ensureOpen();
        try (GasMeter.AttributionScope ignored =
                     sharedGasContext.withAttribution(
                             Objects.requireNonNull(
                                     attribution, "attribution"))) {
            return settlementService().classifyExternalDelivery(
                    exactRoot,
                    rawChannelKey,
                    exactEvent,
                    attribution);
        }
    }

    /**
     * Applies one post-quiescence Root-local checkpoint batch atomically on a
     * detached exact Root using this session's shared meter.
     *
     * @param exactRoot exact final Root before checkpoint settlement
     * @param completedEntries accepted-new sources paired with their frozen
     *        raw-source ordinals and exact write attribution
     * @param cleanupContextFactory exact attribution factory invoked for each
     *        actual lexical inactive-entry removal
     * @param batchAttribution exact target document/barrier attribution for
     *        deterministic settlement revalidation work
     * @return exact resulting Root and actual add/replace/remove evidence
     */
    public ManagedCheckpointSettlement settleCheckpoints(
            Node exactRoot,
            List<ManagedCheckpointSettlementEntry> completedEntries,
            ManagedCheckpointCleanupContextFactory cleanupContextFactory,
            GasChargeContext batchAttribution) {
        ensureOpen();
        try (GasMeter.AttributionScope ignored =
                     sharedGasContext.withAttribution(
                             Objects.requireNonNull(
                                     batchAttribution,
                                     "batchAttribution"))) {
            return settlementService().settleCheckpoints(
                    exactRoot,
                    completedEntries,
                    Objects.requireNonNull(
                            cleanupContextFactory,
                            "cleanupContextFactory"));
        }
    }

    /**
     * Applies one closure-wide post-quiescence checkpoint batch across all
     * participating managed Roots on this session's shared meter.
     *
     * <p>Every target is fully planned and the complete batch is preflighted
     * before the first direct marker mutation. Actual accepted-source writes
     * are applied in frozen raw-occurrence order across targets, followed by
     * cleanup removals in lexical target-identity/raw-key order.</p>
     *
     * @param requests exact per-Root settlement requests
     * @param cleanupContextFactory exact attribution factory for actual global
     *        cleanup removals
     * @return exact resulting Roots and globally ordered mutation evidence
     */
    public ManagedCheckpointSettlementBatch settleCheckpointBatch(
            List<ManagedCheckpointSettlementRequest> requests,
            ManagedCheckpointBatchCleanupContextFactory
                    cleanupContextFactory) {
        ensureOpen();
        return settlementService().settleCheckpointBatch(
                requests,
                Objects.requireNonNull(
                        cleanupContextFactory, "cleanupContextFactory"));
    }

    /**
     * Returns the exact identity of the captured immutable runtime registry.
     *
     * @return captured runtime-registry identity
     */
    public String runtimeRegistryIdentity() {
        ensureOpen();
        return owner.runtimeRegistryIdentity();
    }

    /**
     * Returns the exact digest identity of the released gas-manifest bytes.
     *
     * @return {@code sha256:}-prefixed exact manifest-byte digest
     * @throws IllegalStateException for a custom schedule without a separately
     *         configured byte identity
     */
    public String gasManifestIdentity() {
        ensureOpen();
        return owner.gasManifestIdentity();
    }

    /**
     * Returns a narrow finalization bridge bound to this session's exact
     * semantic memo, shared meter, manifest formulas, and supplied attribution.
     *
     * @param attribution exact document/component/finalization charge owner
     * @return immutable attribution-bound semantic charging bridge
     */
    public ManagedSemanticGasBridge semanticGas(
            GasChargeContext attribution) {
        ensureOpen();
        return new ManagedSemanticGasBridge(
                sharedGasContext.meter().semantic(),
                owner.gasSchedule(),
                Objects.requireNonNull(attribution, "attribution"));
    }

    ProcessingGasContext sharedGasContext() {
        return sharedGasContext;
    }

    private ManagedRootSettlementService settlementService() {
        return new ManagedRootSettlementService(owner, sharedGasContext);
    }

    /**
     * Charges closure orchestration work on this session's exact ledger.
     *
     * @param namespace exact gas-schedule namespace
     * @param counter exact named counter
     * @param quantity non-negative logical quantity
     * @param context exact closure orchestration attribution
     */
    public void charge(
            String namespace,
            String counter,
            long quantity,
            GasChargeContext context) {
        ensureOpen();
        sharedGasContext.meter().charge(
                namespace, counter, quantity, context);
    }

    /**
     * Returns the gas admitted by document steps and orchestration together.
     *
     * @return exact admitted shared gas total
     */
    public long totalGas() {
        ensureOpen();
        return sharedGasContext.meter().totalGas();
    }

    /**
     * Returns the remaining invocation-wide allowance.
     *
     * @return exact remaining shared allowance
     */
    public long remainingGas() {
        ensureOpen();
        return sharedGasContext.meter().remainingGas();
    }

    /**
     * Returns the configured invocation-wide ceiling.
     *
     * @return configured invocation-wide shared ceiling
     */
    public long gasLimit() {
        ensureOpen();
        return sharedGasContext.meter().gasLimit();
    }

    /**
     * Returns the combined admitted trace in ledger sequence order.
     *
     * @return immutable point-in-time admitted trace
     */
    public List<GasTraceEntry> gasTrace() {
        ensureOpen();
        return sharedGasContext.meter().trace();
    }

    /**
     * Ends this composition session. The captured configured service view is
     * released; the caller still owns and closes the DocumentProcessor.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        owner.close();
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "Managed document-step runtime is closed");
        }
    }

    private ContractBundle classifyRootBundle(Node exactDocument) {
        Node document = Objects.requireNonNull(
                exactDocument, "exactDocument").clone();
        DocumentProcessingResult invalid =
                ProcessingInputAdmission.validateDocument(document);
        if (invalid != null) {
            ProcessorDiagnostic diagnostic = invalid.diagnostic();
            throw new ProcessorFailureException(
                    diagnostic != null
                            ? diagnostic.category()
                            : ProcessorErrorCategory.InvalidProcessingDocument,
                    diagnostic != null && diagnostic.message() != null
                            ? diagnostic.message()
                            : "Invalid managed route-classification document");
        }
        ProcessorMarkerStore.collapseInitializationDocuments(document);
        long gasBefore = sharedGasContext.meter().totalGas();
        DocumentProcessingRuntime view = new DocumentProcessingRuntime(
                document,
                owner.conformanceEngine(),
                owner.conformancePlannerOverride(),
                owner.snapshotManager(),
                owner.observer(),
                sharedGasContext,
                owner.registry().executableBodyFieldsByType(),
                owner.strictPlatformInvocation());
        FrozenNode selected = view.selectedFrozenAt(JsonPointer.ROOT);
        owner.contractLoader().preflightSelectedContractHeaders(selected);
        FrozenNode resolved = view.resolvedFrozenAt(JsonPointer.ROOT);
        FrozenNode recognition = view.contractRecognitionScope(
                selected, resolved);
        ContractBundle bundle = owner.contractLoader().load(
                selected,
                recognition,
                JsonPointer.ROOT,
                owner.observer(),
                null,
                null);
        if (sharedGasContext.meter().totalGas() != gasBefore) {
            throw new IllegalStateException(
                    "Route classification must not charge shared gas");
        }
        return bundle;
    }

    private ManagedDocumentStepContinuation collectingContinuation(
            final List<FrozenJsonPatch> orderedPatches,
            final List<Node> orderedEmittedEvents) {
        return new ManagedDocumentStepContinuation() {
            @Override
            public void afterPatch(
                    String scopePath,
                    Node currentDocument,
                    FrozenJsonPatch patch,
                    List<DocumentUpdateOccurrence> updates) {
                orderedPatches.add(Objects.requireNonNull(patch, "patch"));
                continuation.afterPatch(
                        scopePath,
                        currentDocument,
                        patch,
                        updates);
            }

            @Override
            public void onApplicationEvent(
                    String scopePath,
                    String originContractKey,
                    Node event,
                    String eventBlueId) {
                orderedEmittedEvents.add(
                        Objects.requireNonNull(event, "event").clone());
                continuation.onApplicationEvent(
                        scopePath,
                        originContractKey,
                        event,
                        eventBlueId);
            }

            @Override
            public void onTerminationRequested(
                    String scopePath,
                    String cause,
                    String reason) {
                continuation.onTerminationRequested(
                        scopePath, cause, reason);
            }
        };
    }

    private static void validateTargetState(
            ManagedDocumentStepRequest request,
            Node document) {
        final boolean initialized;
        final boolean terminated;
        try {
            initialized = ProcessorMarkerStore.isInitialized(document);
            terminated = ProcessorMarkerStore.terminationMarker(
                    document, blue.language.model.wire.JsonPointer.ROOT)
                    != null;
        } catch (RuntimeException invalidMarker) {
            throw new InvalidExecutionEvidenceException(
                    "Managed document-step lifecycle marker is invalid: "
                            + ProcessorEngine.deterministicMessage(
                                    invalidMarker,
                                    "invalid lifecycle marker"),
                    ProcessorErrorCategory.InvalidReservedRuntimeState);
        }
        if (initialized != request.initialized()
                || terminated != request.terminated()) {
            throw new InvalidExecutionEvidenceException(
                    "Managed document-step lifecycle flags disagree with "
                            + "the exact target document",
                    ProcessorErrorCategory.InvalidReservedRuntimeState);
        }
        if (terminated) {
            throw new InvalidExecutionEvidenceException(
                    "Terminated managed document was selected for local work",
                    ProcessorErrorCategory.ActiveScopeCutOff);
        }
        if (request.workKind() == ManagedDocumentWorkKind.INITIALIZATION
                && initialized) {
            throw new InvalidExecutionEvidenceException(
                    "Initialized managed document was selected for "
                            + "initialization work",
                    ProcessorErrorCategory.InvalidReservedRuntimeState);
        }
    }

    private static ManagedDocumentStepContinuation rejectingContinuation() {
        return new ManagedDocumentStepContinuation() {
            @Override
            public void afterPatch(
                    String scopePath,
                    Node currentDocument,
                    FrozenJsonPatch patch,
                    List<DocumentUpdateOccurrence> updates) {
                throw new DocumentStepRuntimeGapException(
                        DocumentStepRuntimeGapException.Reason
                                .PATCH_CONTINUATION_HOOK_REQUIRED,
                        "Managed document-step patch requires immediate "
                                + "closure identity and caused-work continuation");
            }

            @Override
            public void onApplicationEvent(
                    String scopePath,
                    String originContractKey,
                    Node event,
                    String eventBlueId) {
                throw new DocumentStepRuntimeGapException(
                        DocumentStepRuntimeGapException.Reason
                                .APPLICATION_EVENT_CONTINUATION_HOOK_REQUIRED,
                        "Managed document-step emission requires frozen "
                                + "closure occurrence continuation");
            }

            @Override
            public void onTerminationRequested(
                    String scopePath,
                    String cause,
                    String reason) {
                throw new DocumentStepRuntimeGapException(
                        DocumentStepRuntimeGapException.Reason
                                .TERMINATION_CONTINUATION_HOOK_REQUIRED,
                        "Managed document-step termination requires a "
                                + "closure lifecycle continuation");
            }
        };
    }
}
