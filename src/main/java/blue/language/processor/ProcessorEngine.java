package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.Contract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.conformance.ScriptedContractsRuntime;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.JsonPointer;
import blue.language.utils.NodeToMapListOrValue;
import blue.language.utils.UncheckedObjectMapper;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.erdtman.jcs.JsonCanonicalizer;

final class ProcessorEngine {

    private ProcessorEngine() {
    }

    static DocumentProcessingResult initializeDocument(DocumentProcessor owner, Node document) {
        Objects.requireNonNull(document, "document");
        if (isInitialized(owner, document)) {
            throw new IllegalStateException("Document already initialized");
        }
        Execution execution = new Execution(owner, document.clone());
        try {
            execution.initializeScope("/", true);
        } catch (RunTerminationException ignored) {
            // Initialization run terminated early (e.g., graceful root termination).
        } catch (MustUnderstandFailureException ex) {
            return DocumentProcessingResult.capabilityFailure(document.clone(), ex.getMessage(), ex.errorCategory());
        }
        return execution.result();
    }

    static DocumentProcessingResult initializeDocument(DocumentProcessor owner, ResolvedSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (isInitialized(owner, snapshot)) {
            throw new IllegalStateException("Document already initialized");
        }
        Execution execution = new Execution(owner, snapshot);
        try {
            execution.initializeScope("/", true);
        } catch (RunTerminationException ignored) {
            // Initialization run terminated early (e.g., graceful root termination).
        } catch (MustUnderstandFailureException ex) {
            return DocumentProcessingResult.capabilityFailure(snapshot.resolvedRoot(), ex.getMessage(), ex.errorCategory());
        }
        return execution.result();
    }

    static DocumentProcessingResult processDocument(DocumentProcessor owner, Node document, Node event) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(event, "event");
        ProcessingMetricsSink metrics = owner.metricsSink();
        long processStart = System.nanoTime();
        long preprocessStart = System.nanoTime();
        Execution execution = null;
        try {
            DocumentProcessingResult invalid = validateProcessingDocument(document);
            if (invalid != null) {
                return invalid;
            }
            Node cloned = document.clone();
            execution = new Execution(owner, cloned, event);
            metrics.addEventPreprocessNanos(System.nanoTime() - preprocessStart);
            if (execution.applyScriptedForcedFatalIfPresent()) {
                return execution.result();
            }
            long bundleStart = System.nanoTime();
            execution.loadBundles("/");
            metrics.addBundleLoadNanos(System.nanoTime() - bundleStart);
            execution.processExternalEvent("/", event);
        } catch (RunTerminationException ignored) {
            // Processing terminated early; result still returned.
        } catch (MustUnderstandFailureException ex) {
            metrics.addProcessDocumentNanos(System.nanoTime() - processStart);
            return DocumentProcessingResult.capabilityFailure(document.clone(), ex.getMessage(), ex.errorCategory());
        }
        long postStart = System.nanoTime();
        try {
            return execution.result();
        } finally {
            metrics.addPostProcessingNanos(System.nanoTime() - postStart);
            metrics.addProcessDocumentNanos(System.nanoTime() - processStart);
        }
    }

    static DocumentProcessingResult processDocument(DocumentProcessor owner, ResolvedSnapshot snapshot, Node event) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(event, "event");
        ProcessingMetricsSink metrics = owner.metricsSink();
        long processStart = System.nanoTime();
        long preprocessStart = System.nanoTime();
        Execution execution = null;
        try {
            DocumentProcessingResult invalid = validateProcessingDocument(snapshot.frozenResolvedRoot());
            if (invalid != null) {
                return invalid.withSnapshot(snapshot);
            }
            execution = new Execution(owner, snapshot, event);
            metrics.addEventPreprocessNanos(System.nanoTime() - preprocessStart);
            if (execution.applyScriptedForcedFatalIfPresent()) {
                return execution.result();
            }
            long bundleStart = System.nanoTime();
            execution.loadBundles("/");
            metrics.addBundleLoadNanos(System.nanoTime() - bundleStart);
            execution.processExternalEvent("/", event);
        } catch (RunTerminationException ignored) {
            // Processing terminated early; result still returned.
        } catch (MustUnderstandFailureException ex) {
            metrics.addProcessDocumentNanos(System.nanoTime() - processStart);
            return DocumentProcessingResult.capabilityFailure(snapshot.resolvedRoot(), ex.getMessage(), ex.errorCategory());
        }
        long postStart = System.nanoTime();
        try {
            return execution.result();
        } finally {
            metrics.addPostProcessingNanos(System.nanoTime() - postStart);
            metrics.addProcessDocumentNanos(System.nanoTime() - processStart);
        }
    }

    static boolean isInitialized(DocumentProcessor owner, Node document) {
        Objects.requireNonNull(document, "document");
        String pointer = resolvePointer("/", ProcessorPointerConstants.RELATIVE_INITIALIZED);
        Node marker = null;
        try {
            marker = nodeAt(document, pointer);
        } catch (Exception ignored) {
        }
        if (marker == null) {
            return false;
        }
        validateInitializationMarker(marker, pointer);
        return true;
    }

    private static DocumentProcessingResult validateProcessingDocument(Node document) {
        if (document == null) {
            throw new NullPointerException("document");
        }
        if (document.getBlue() != null) {
            return DocumentProcessingResult.invalidProcessingDocument(document.clone(),
                    "Invalid Processing Document: root blue directive is not allowed");
        }
        if (document.getValue() != null || document.getItems() != null || document.isReferenceOnly()) {
            return DocumentProcessingResult.invalidProcessingDocument(document.clone(),
                    "Invalid Processing Document: root scope must be an object");
        }
        return null;
    }

    private static DocumentProcessingResult validateProcessingDocument(FrozenNode document) {
        if (document == null) {
            throw new NullPointerException("document");
        }
        if (document.getBlue() != null) {
            return DocumentProcessingResult.invalidProcessingDocument(document.toNode(),
                    "Invalid Processing Document: root blue directive is not allowed");
        }
        if (document.getValue() != null || document.hasItems() || document.isReferenceOnly()) {
            return DocumentProcessingResult.invalidProcessingDocument(document.toNode(),
                    "Invalid Processing Document: root scope must be an object");
        }
        return null;
    }

    static boolean isInitialized(DocumentProcessor owner, ResolvedSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        String pointer = resolvePointer("/", ProcessorPointerConstants.RELATIVE_INITIALIZED);
        Node marker = snapshot.canonicalNodeAt(pointer);
        if (marker == null) {
            return false;
        }
        validateInitializationMarker(marker, pointer);
        return true;
    }

    static String resolvePointer(String scopePath, String relativePointer) {
        return PointerUtils.resolvePointer(scopePath, relativePointer);
    }

    static String normalizeScope(String scopePath) {
        return PointerUtils.normalizeScope(scopePath);
    }

    static String normalizePointer(String pointer) {
        return PointerUtils.normalizePointer(pointer);
    }

    static String joinRelativePointers(String base, String tail) {
        return PointerUtils.joinRelativePointers(base, tail);
    }

    static String relativizePointer(String scopePath, String absolutePath) {
        return PointerUtils.relativizePointer(scopePath, absolutePath);
    }

    static String stripSlashes(String value) {
        return PointerUtils.stripSlashes(value);
    }

    @SuppressWarnings("unchecked")
    static ChannelMatch evaluateChannel(DocumentProcessor owner,
                                            ContractBundle.ChannelBinding channel,
                                            ContractBundle bundle,
                                            String scopePath,
                                            Node event) {
        ChannelContract contract = channel.contract();
        ChannelProcessor<? extends ChannelContract> processor =
                owner.registry().lookupChannel(contract).orElse(null);
        if (processor == null) {
            return ChannelMatch.noMatch();
        }
        Node clonedEvent = event != null ? event.clone() : null;
        Object eventObject = null;
        try {
            eventObject = owner.contractConverter().convertWithType(clonedEvent, Object.class, false);
        } catch (Exception ignored) {
        }
        @SuppressWarnings("unchecked")
        ChannelProcessor<ChannelContract> typed = (ChannelProcessor<ChannelContract>) processor;
        ChannelEvaluationContext context = new ChannelEvaluationContext(scopePath,
                channel.key(),
                clonedEvent,
                eventObject,
                bundle.channels(),
                bundle.markers(),
                owner.registry());
        ChannelEvaluation evaluation = typed.evaluate(contract, context);
        if (evaluation == null || !evaluation.matches()) {
            return ChannelMatch.noMatch();
        }
        return new ChannelMatch(true,
                evaluation.eventId(),
                evaluation.eventForDelivery(),
                typed,
                evaluation.deliveries());
    }

    static Node createLifecycleInitiatedEvent(String documentId) {
        Node event = new Node().type(new Node().blueId(RuntimeBlueIds.DOCUMENT_PROCESSING_INITIATED));
        event.properties("documentId", new Node().value(documentId));
        return event;
    }

    static String canonicalSignature(Node node) {
        if (node == null) {
            return null;
        }
        Object canonical = NodeToMapListOrValue.get(normalizeSignatureNode(node.clone()));
        try {
            String json = UncheckedObjectMapper.JSON_MAPPER.writeValueAsString(canonical);
            return new JsonCanonicalizer(json).getEncodedString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to canonicalize node for checkpoint comparison", ex);
        }
    }

    private static Node normalizeSignatureNode(Node node) {
        if (node == null) {
            return null;
        }
        node.type(normalizeSignatureReference(node.getType()));
        node.itemType(normalizeSignatureReference(node.getItemType()));
        node.keyType(normalizeSignatureReference(node.getKeyType()));
        node.valueType(normalizeSignatureReference(node.getValueType()));
        if (node.getItems() != null) {
            node.getItems().replaceAll(ProcessorEngine::normalizeSignatureNode);
        }
        if (node.getProperties() != null) {
            node.getProperties().replaceAll((key, value) -> {
                if (isTypeReferenceKey(key)) {
                    return normalizeSignatureReference(value);
                }
                return normalizeSignatureNode(value);
            });
        }
        if (node.getContracts() != null) {
            node.contracts(normalizeSignatureNode(node.getContracts()));
        }
        if (node.getBlue() != null) {
            node.blue(normalizeSignatureNode(node.getBlue()));
        }
        return node;
    }

    private static boolean isTypeReferenceKey(String key) {
        return "type".equals(key)
                || "itemType".equals(key)
                || "keyType".equals(key)
                || "valueType".equals(key);
    }

    private static Node normalizeSignatureReference(Node reference) {
        if (reference == null) {
            return null;
        }
        normalizeSignatureNode(reference);
        if (reference.getBlueId() != null) {
            return new Node().blueId(reference.getBlueId());
        }
        if (reference.getBlueId() == null && reference.getName() != null) {
            return new Node().blueId(BlueIdCalculator.calculateBlueId(reference));
        }
        return reference;
    }

    static Node createDocumentUpdateEvent(DocumentProcessingRuntime.DocumentUpdateData data, String scopePath) {
        String relativePath = relativizePointer(scopePath, data.path());
        Node event = new Node().type(new Node().blueId(RuntimeBlueIds.DOCUMENT_UPDATE));
        event.properties("op", new Node().value(data.op().name().toLowerCase()));
        Node beforeNode = data.before() != null ? data.before().clone() : new Node().value(null);
        Node afterNode = data.after() != null ? data.after().clone() : new Node().value(null);
        event.properties("path", new Node().value(relativePath));
        event.properties("before", beforeNode);
        event.properties("after", afterNode);
        return event;
    }

    static boolean matchesDocumentUpdate(String scopePath, String watchPath, String changedPath) {
        if (watchPath == null || watchPath.isEmpty()) {
            return false;
        }
        String watch = PointerUtils.normalizePointer(PointerUtils.resolvePointer(scopePath, watchPath));
        String changed = PointerUtils.normalizePointer(changedPath);
        return PointerUtils.descendantOrEqual(changed, watch);
    }

    static Node nodeAt(Node root, String pointer) {
        if (pointer.equals("/")) {
            return root;
        }
        Node current = root;
        for (String segment : JsonPointer.split(pointer)) {
            if (segment.isEmpty()) {
                continue;
            }
            if ("contracts".equals(segment)) {
                current = current.getContracts();
                if (current == null) {
                    return null;
                }
                continue;
            }
            Map<String, Node> props = current.getProperties();
            if (props == null) {
                return null;
            }
            current = props.get(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    static boolean hasInitializationMarker(Node root, String scopePath) {
        String pointer = resolvePointer(scopePath, ProcessorPointerConstants.RELATIVE_INITIALIZED);
        Node marker;
        try {
            marker = nodeAt(root, pointer);
        } catch (Exception ignored) {
            return false;
        }
        if (marker == null) {
            return false;
        }
        validateInitializationMarker(marker, pointer);
        return true;
    }

    static TerminationMarker terminationMarker(Node root, String scopePath) {
        String pointer = resolvePointer(scopePath, ProcessorPointerConstants.RELATIVE_TERMINATED);
        Node marker;
        try {
            marker = nodeAt(root, pointer);
        } catch (Exception ignored) {
            return null;
        }
        if (marker == null) {
            return null;
        }
        return validateTerminationMarker(marker, pointer);
    }

    static void validateInitializationMarker(Node marker, String pointer) {
        if (marker == null) {
            return;
        }
        Node type = marker.getType();
        if (type == null || !RuntimeBlueIds.PROCESSING_INITIALIZED_MARKER.equals(runtimeTypeBlueId(type))) {
            throw new IllegalStateException(
                    "Reserved key 'initialized' must contain a Processing Initialized Marker at " + pointer);
        }
    }

    static TerminationMarker validateTerminationMarker(Node marker, String pointer) {
        if (marker == null) {
            return null;
        }
        Node type = marker.getType();
        if (type == null || !RuntimeBlueIds.PROCESSING_TERMINATED_MARKER.equals(runtimeTypeBlueId(type))) {
            throw new IllegalStateException(
                    "Reserved key 'terminated' must contain a Processing Terminated Marker at " + pointer);
        }
        String cause = stringProperty(marker, "cause");
        ScopeRuntimeContext.TerminationKind kind = "fatal".equals(cause)
                ? ScopeRuntimeContext.TerminationKind.FATAL
                : ScopeRuntimeContext.TerminationKind.GRACEFUL;
        return new TerminationMarker(kind, stringProperty(marker, "reason"));
    }

    private static String runtimeTypeBlueId(Node type) {
        if (type == null) {
            return null;
        }
        if (type.getBlueId() != null) {
            return type.getBlueId();
        }
        try {
            return BlueIdCalculator.calculateBlueId(type);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static String stringProperty(Node node, String key) {
        if (node == null || node.getProperties() == null) {
            return null;
        }
        Node value = node.getProperties().get(key);
        Object raw = value != null ? value.getValue() : null;
        return raw instanceof String ? (String) raw : null;
    }

    static final class TerminationMarker {
        final ScopeRuntimeContext.TerminationKind kind;
        final String reason;

        TerminationMarker(ScopeRuntimeContext.TerminationKind kind, String reason) {
            this.kind = kind;
            this.reason = reason;
        }
    }

    static final class Execution {
        private final DocumentProcessor owner;
        private final DocumentProcessingRuntime runtime;
        private final Node processEventSource;
        private final ProcessEventSnapshotFactory processEventSnapshotFactory;
        private final Object processEventSnapshotLock = new Object();
        private final Map<String, ContractBundle> bundles = new LinkedHashMap<>();
        private final Map<String, TerminationRecord> firstTerminations = new LinkedHashMap<>();
        private final Map<String, TerminationRecord> terminationEscalations = new LinkedHashMap<>();
        private final Set<String> cutOffScopes = new LinkedHashSet<>();
        private final Set<LogicalDelivery> successfulLogicalDeliveries = new LinkedHashSet<>();
        private boolean rootFatalEvidenceAppended;
        private final CheckpointManager checkpointManager;
        private final TerminationService terminationService;
        private final ChannelRunner channelRunner;
        private final ScopeExecutor scopeExecutor;
        private volatile ProcessEventSnapshotState processEventSnapshotState;
        private volatile FrozenNode frozenProcessEvent;
        private RuntimeException processEventSnapshotFailure;

        Execution(DocumentProcessor owner, Node document) {
            this(owner, document, null);
        }

        Execution(DocumentProcessor owner, Node document, Node processEventSource) {
            this(owner, document, processEventSource, FrozenNode::fromResolvedNode);
        }

        Execution(DocumentProcessor owner,
                  Node document,
                  Node processEventSource,
                  ProcessEventSnapshotFactory processEventSnapshotFactory) {
            this.owner = owner;
            this.runtime = new DocumentProcessingRuntime(document,
                    owner.conformanceEngine(),
                    owner.conformancePlannerOverride(),
                    owner.snapshotManager(),
                    owner.metricsSink());
            this.processEventSource = processEventSource;
            this.processEventSnapshotFactory = Objects.requireNonNull(processEventSnapshotFactory,
                    "processEventSnapshotFactory");
            this.processEventSnapshotState = processEventSource != null
                    ? ProcessEventSnapshotState.UNINITIALIZED
                    : ProcessEventSnapshotState.ABSENT;
            this.checkpointManager = new CheckpointManager(runtime, owner.matchingService().blue(), owner.metricsSink());
            this.terminationService = new TerminationService(runtime);
            this.channelRunner = new ChannelRunner(owner, this, runtime, checkpointManager);
            this.scopeExecutor = new ScopeExecutor(owner, this, runtime, bundles, channelRunner);
        }

        Execution(DocumentProcessor owner, ResolvedSnapshot snapshot) {
            this(owner, snapshot, null);
        }

        Execution(DocumentProcessor owner, ResolvedSnapshot snapshot, Node processEventSource) {
            this(owner, snapshot, processEventSource, FrozenNode::fromResolvedNode);
        }

        Execution(DocumentProcessor owner,
                  ResolvedSnapshot snapshot,
                  Node processEventSource,
                  ProcessEventSnapshotFactory processEventSnapshotFactory) {
            this.owner = owner;
            this.runtime = new DocumentProcessingRuntime(snapshot,
                    owner.conformanceEngine(),
                    owner.conformancePlannerOverride(),
                    owner.snapshotManager(),
                    owner.metricsSink());
            this.processEventSource = processEventSource;
            this.processEventSnapshotFactory = Objects.requireNonNull(processEventSnapshotFactory,
                    "processEventSnapshotFactory");
            this.processEventSnapshotState = processEventSource != null
                    ? ProcessEventSnapshotState.UNINITIALIZED
                    : ProcessEventSnapshotState.ABSENT;
            this.checkpointManager = new CheckpointManager(runtime, owner.matchingService().blue(), owner.metricsSink());
            this.terminationService = new TerminationService(runtime);
            this.channelRunner = new ChannelRunner(owner, this, runtime, checkpointManager);
            this.scopeExecutor = new ScopeExecutor(owner, this, runtime, bundles, channelRunner);
        }

        void initializeScope(String scopePath, boolean chargeScopeEntry) {
            scopeExecutor.initializeScope(scopePath, chargeScopeEntry);
        }

        void loadBundles(String scopePath) {
            scopeExecutor.loadBundles(scopePath);
        }

        void processExternalEvent(String scopePath, Node event) {
            scopeExecutor.processExternalEvent(scopePath, event);
        }

        boolean applyScriptedForcedFatalIfPresent() {
            ScriptedContractsRuntime scriptedRuntime = ScriptedContractsRuntime.active();
            if (scriptedRuntime == null || !scriptedRuntime.hasForcedFatal()) {
                return false;
            }
            ScriptedContractsRuntime.ForcedFatal forcedFatal = scriptedRuntime.consumeForcedFatal();
            String scope = forcedFatal.scope() != null ? forcedFatal.scope() : "/";
            ensureContractsContainerForForcedFatal(scope);
            enterFatalTermination(scope,
                    bundleForScope(ProcessorEngine.normalizeScope(scope)),
                    ProcessorErrorCategory.TerminationError,
                    forcedFatal.reason());
            return true;
        }

        private void ensureContractsContainerForForcedFatal(String scopePath) {
            String contractsPointer = ProcessorEngine.resolvePointer(scopePath, ProcessorPointerConstants.RELATIVE_CONTRACTS);
            Node contracts = null;
            try {
                contracts = runtime.nodeAt(contractsPointer);
            } catch (RuntimeException ignored) {
            }
            if (contracts != null && contracts.getProperties() != null) {
                return;
            }
            Node replacement = runtime.document().clone();
            if ("/".equals(ProcessorEngine.normalizeScope(scopePath))) {
                replacement.contracts(new Node());
                runtime.replaceDocument(replacement);
            }
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
            scopeExecutor.handlePatches(scopePath, bundle, patches, allowReservedMutation);
        }

        void handlePatches(String scopePath,
                           ContractBundle bundle,
                           List<JsonPatch> patches,
                           boolean allowReservedMutation,
                           WorkingDocument.Preview preview) {
            scopeExecutor.handlePatches(scopePath, bundle, patches, allowReservedMutation, preview);
        }

        void handlePatchInputs(String scopePath,
                               ContractBundle bundle,
                               List<PatchInput> patches,
                               boolean allowReservedMutation,
                               WorkingDocument.Preview preview) {
            scopeExecutor.handlePatchInputs(scopePath, bundle, patches, allowReservedMutation, preview);
        }

        ProcessorExecutionContext createContext(String scopePath,
                                                ContractBundle bundle,
                                                Node event) {
            return createContext(scopePath, bundle, event, false);
        }

        ProcessorExecutionContext createContext(String scopePath,
                                                ContractBundle bundle,
                                                Node event,
                                                boolean allowReservedMutation) {
            return createContext(scopePath, bundle, event, null, null, allowReservedMutation);
        }

        ProcessorExecutionContext createContext(String scopePath,
                                                ContractBundle bundle,
                                                Node event,
                                                String contractKey,
                                                FrozenNode contractNode,
                                                boolean allowReservedMutation) {
            return new ProcessorExecutionContext(this, bundle, scopePath,
                    contractKey, contractNode,
                    cloneEvent(event), allowReservedMutation);
        }

        DocumentProcessingResult result() {
            FatalDiagnostic fatal = selectFatalDiagnostic();
            ProcessorStatus status = fatal == null ? ProcessorStatus.SUCCESS : ProcessorStatus.RUNTIME_FATAL;
            ProcessorErrorCategory category = fatal != null ? fatal.category : null;
            String reason = fatal != null ? fatal.reason : null;
            ResolvedSnapshot snapshot = runtime.snapshot();
            if (snapshot != null) {
                ResolvedSnapshot publishedSnapshot = publishableSnapshot(snapshot, owner.metricsSink());
                return DocumentProcessingResult.ofSelected(runtime.selectedDocument(),
                        publishedSnapshot,
                        runtime.rootEmissions(),
                        runtime.totalGas(),
                        status,
                        category,
                        reason);
            }
            return DocumentProcessingResult.of(runtime.document(),
                    runtime.rootEmissions(),
                    runtime.totalGas(),
                    status,
                    category,
                    reason);
        }

        private ResolvedSnapshot publishableSnapshot(ResolvedSnapshot snapshot,
                                                     ProcessingMetricsSink metrics) {
            ProcessingMetricsSink sink = metrics != null ? metrics : ProcessingMetricsSink.NOOP;
            sink.incrementProcessorPublicationInvariantChecks();
            ResolvedSnapshot published = snapshot;
            if (!isStrictPublishable(published)) {
                sink.incrementProcessorPublicationCanonicalizations();
                sink.incrementProcessorPublicationCanonicalMaterializations();
                sink.incrementProcessorPublicationStrictBlueIdCalculations();
                long canonicalizationStart = System.nanoTime();
                try {
                    published = published.toStrictBlueIdValidatedCanonical();
                } catch (RuntimeException exception) {
                    sink.incrementProcessorPublishedUncheckedCanonical();
                    sink.incrementProcessorPublicationIdentityMismatches();
                    throw exception;
                } finally {
                    sink.addProcessorPublicationCanonicalizationNanos(
                            Math.max(1L, System.nanoTime() - canonicalizationStart));
                }
            }

            if (!isStrictPublishable(published)) {
                sink.incrementProcessorPublishedUncheckedCanonical();
                sink.incrementProcessorPublicationIdentityMismatches();
                throw new IllegalStateException(
                        "Processor result snapshot must be strict canonical with strict BlueId validation.");
            }
            String snapshotBlueId = published.blueId();
            String canonicalBlueId = published.frozenCanonicalRoot().blueId();
            if (!Objects.equals(snapshotBlueId, canonicalBlueId)) {
                sink.incrementProcessorPublicationIdentityMismatches();
                throw new IllegalStateException(
                        "Processor result snapshot BlueId must match canonical root BlueId.");
            }
            sink.incrementProcessorPublishedStrictCanonical();
            return published;
        }

        private boolean isStrictPublishable(ResolvedSnapshot snapshot) {
            FrozenNode canonicalRoot = snapshot.frozenCanonicalRoot();
            return canonicalRoot.isStrictCanonical()
                    && canonicalRoot.isStrictBlueIdValidation();
        }

        DocumentProcessingResult partialResult() {
            try {
                return result();
            } catch (RuntimeException ignored) {
                return DocumentProcessingResult.of(runtime.document(),
                        runtime.rootEmissions(),
                        runtime.totalGas());
            }
        }

        DocumentProcessingRuntime runtime() {
            return runtime;
        }

        Blue blue() {
            return owner.matchingService().blue();
        }

        boolean hasProcessEvent() {
            return processEventSource != null;
        }

        FrozenNode frozenProcessEvent() {
            ProcessEventSnapshotState state = processEventSnapshotState;
            if (state == ProcessEventSnapshotState.ABSENT) {
                return null;
            }
            if (state == ProcessEventSnapshotState.READY) {
                return frozenProcessEvent;
            }
            if (state == ProcessEventSnapshotState.FAILED) {
                throw processEventSnapshotFailure;
            }

            synchronized (processEventSnapshotLock) {
                state = processEventSnapshotState;
                if (state == ProcessEventSnapshotState.READY) {
                    return frozenProcessEvent;
                }
                if (state == ProcessEventSnapshotState.FAILED) {
                    throw processEventSnapshotFailure;
                }
                return buildFrozenProcessEvent();
            }
        }

        private FrozenNode buildFrozenProcessEvent() {
            ProcessingMetricsSink metrics = owner.metricsSink();
            metrics.incrementProcessEventSnapshotAttempts();
            long startedAt = System.nanoTime();
            try {
                FrozenNode snapshot = processEventSnapshotFactory.freeze(processEventSource);
                if (snapshot == null) {
                    throw new IllegalStateException("Processing Event snapshot construction returned null");
                }
                frozenProcessEvent = snapshot;
                processEventSnapshotState = ProcessEventSnapshotState.READY;
                metrics.incrementProcessEventSnapshotBuilds();
                return snapshot;
            } catch (RuntimeException ex) {
                processEventSnapshotFailure = ex;
                processEventSnapshotState = ProcessEventSnapshotState.FAILED;
                metrics.incrementProcessEventSnapshotFailures();
                throw ex;
            } finally {
                metrics.addProcessEventSnapshotConstructionNanos(System.nanoTime() - startedAt);
            }
        }

        boolean shouldStopScopeWork(String scopePath) {
            String normalized = ProcessorEngine.normalizeScope(scopePath);
            ScopeRuntimeContext context = runtime.existingScope(normalized);
            return cutOffScopes.contains(normalized)
                    || terminationEscalations.containsKey(normalized)
                    || (context != null && context.isTerminated());
        }

        boolean isScopeActive(String scopePath) {
            ScopeRuntimeContext context = runtime.existingScope(ProcessorEngine.normalizeScope(scopePath));
            return (context == null || context.isActive()) && !shouldStopScopeWork(scopePath);
        }

        boolean hasSuccessfulLogicalDelivery(String scopePath,
                                             String eventIdentity,
                                             String handlerChannelKey,
                                             String logicalDeliveryKey) {
            return successfulLogicalDeliveries.contains(new LogicalDelivery(
                    normalizeScope(scopePath),
                    eventIdentity,
                    handlerChannelKey,
                    logicalDeliveryKey));
        }

        void recordSuccessfulLogicalDelivery(String scopePath,
                                             String eventIdentity,
                                             String handlerChannelKey,
                                             String logicalDeliveryKey) {
            successfulLogicalDeliveries.add(new LogicalDelivery(
                    normalizeScope(scopePath),
                    eventIdentity,
                    handlerChannelKey,
                    logicalDeliveryKey));
        }

        void enterGracefulTermination(String scopePath, ContractBundle bundle, String reason) {
            terminate(scopePath, bundle, ScopeRuntimeContext.TerminationKind.GRACEFUL, reason);
        }

        void enterRequestedFatalTermination(String scopePath, ContractBundle bundle, String reason) {
            String normalized = ProcessorEngine.normalizeScope(scopePath);
            ScopeRuntimeContext context = runtime.scope(normalized);
            if (!context.isActive()) {
                return;
            }
            terminate(scopePath,
                    bundle,
                    ScopeRuntimeContext.TerminationKind.FATAL,
                    ProcessorErrorCategory.InternalProcessorError,
                    reason);
        }

        void enterFatalTermination(String scopePath, ContractBundle bundle, String reason) {
            enterFatalTermination(scopePath, bundle, ProcessorErrorCategory.InternalProcessorError, reason);
        }

        void enterFatalTermination(String scopePath,
                                   ContractBundle bundle,
                                   ProcessorErrorCategory errorCategory,
                                   String reason) {
            String normalized = ProcessorEngine.normalizeScope(scopePath);
            ScopeRuntimeContext context = runtime.scope(normalized);
            if (context.isTerminated()) {
                return;
            }
            ProcessorErrorCategory category = errorCategory != null
                    ? errorCategory
                    : ProcessorErrorCategory.InternalProcessorError;
            if (context.isTerminating()) {
                terminationEscalations.putIfAbsent(normalized, new TerminationRecord(
                        ScopeRuntimeContext.TerminationKind.FATAL,
                        category,
                        reason));
                return;
            }
            terminate(scopePath, bundle, ScopeRuntimeContext.TerminationKind.FATAL, category, reason);
        }

        private void terminate(String scopePath,
                               ContractBundle bundle,
                               ScopeRuntimeContext.TerminationKind kind,
                               String reason) {
            terminate(scopePath, bundle, kind, null, reason);
        }

        private void terminate(String scopePath,
                               ContractBundle bundle,
                               ScopeRuntimeContext.TerminationKind kind,
                               ProcessorErrorCategory category,
                               String reason) {
            String normalized = ProcessorEngine.normalizeScope(scopePath);
            ScopeRuntimeContext context = runtime.scope(normalized);
            if (!context.beginTermination()) {
                return;
            }
            firstTerminations.putIfAbsent(normalized, new TerminationRecord(kind, category, reason));
            terminationService.terminateScope(this, scopePath, bundle, kind, reason);
        }

        ContractBundle bundleForScope(String scopePath) {
            return bundles.get(scopePath);
        }

        boolean hasTerminationEscalation(String scopePath) {
            return terminationEscalations.containsKey(ProcessorEngine.normalizeScope(scopePath));
        }

        String fatalTerminationReason(String scopePath, String initialReason) {
            TerminationRecord escalation = terminationEscalations.get(ProcessorEngine.normalizeScope(scopePath));
            return escalation != null && escalation.reason != null ? escalation.reason : initialReason;
        }

        void recordTerminationWriteFailure(String scopePath, String reason) {
            String normalized = ProcessorEngine.normalizeScope(scopePath);
            terminationEscalations.putIfAbsent(normalized, new TerminationRecord(
                    ScopeRuntimeContext.TerminationKind.FATAL,
                    ProcessorErrorCategory.TerminationError,
                    reason));
            runtime.markRunTerminated();
        }

        boolean markRootFatalEvidenceAppended() {
            if (rootFatalEvidenceAppended) {
                return false;
            }
            rootFatalEvidenceAppended = true;
            return true;
        }

        void markCutOff(String scopePath) {
            String normalized = ProcessorEngine.normalizeScope(scopePath);
            if (cutOffScopes.add(normalized)) {
                ScopeRuntimeContext context = runtime.existingScope(normalized);
                if (context != null) {
                    context.markCutOff();
                }
            }
        }

        String normalizeScope(String scopePath) {
            return ProcessorEngine.normalizeScope(scopePath);
        }

        String resolvePointer(String scopePath, String relativePointer) {
            return ProcessorEngine.resolvePointer(scopePath, relativePointer);
        }

        String fatalReason(Throwable throwable, String defaultReason) {
            String message = throwable != null ? throwable.getMessage() : null;
            return message != null ? message : defaultReason;
        }

        ProcessorErrorCategory fatalCategory(Throwable throwable, ProcessorErrorCategory defaultCategory) {
            if (throwable instanceof ProcessorFailureException) {
                return ((ProcessorFailureException) throwable).errorCategory();
            }
            if (throwable instanceof ProcessorFatalException) {
                return ((ProcessorFatalException) throwable).errorCategory();
            }
            if (throwable instanceof MustUnderstandFailureException) {
                return ((MustUnderstandFailureException) throwable).errorCategory();
            }
            return defaultCategory != null ? defaultCategory : ProcessorErrorCategory.InternalProcessorError;
        }

        private FatalDiagnostic selectFatalDiagnostic() {
            TerminationRecord rootEscalation = terminationEscalations.get("/");
            if (isFatal(rootEscalation)) {
                return FatalDiagnostic.from(rootEscalation);
            }
            TerminationRecord rootInitial = firstTerminations.get("/");
            if (isFatal(rootInitial)) {
                return FatalDiagnostic.from(rootInitial);
            }
            ProcessorEngine.TerminationMarker rootMarker = runtime.terminationMarker("/");
            if (rootMarker != null && rootMarker.kind == ScopeRuntimeContext.TerminationKind.FATAL) {
                return new FatalDiagnostic(ProcessorErrorCategory.InternalProcessorError, rootMarker.reason);
            }
            for (TerminationRecord escalation : terminationEscalations.values()) {
                if (isFatal(escalation)) {
                    return FatalDiagnostic.from(escalation);
                }
            }
            for (TerminationRecord initial : firstTerminations.values()) {
                if (isFatal(initial)) {
                    return FatalDiagnostic.from(initial);
                }
            }
            return null;
        }

        private boolean isFatal(TerminationRecord record) {
            return record != null && record.kind == ScopeRuntimeContext.TerminationKind.FATAL;
        }

        void deliverLifecycle(String scopePath,
                              ContractBundle bundle,
                              Node event,
                              boolean finalizeAfter) {
            scopeExecutor.deliverLifecycle(scopePath, bundle, event, finalizeAfter);
        }

        void deliverTerminationLifecycle(String scopePath,
                                         ContractBundle bundle,
                                         Node event) {
            scopeExecutor.deliverTerminationLifecycle(scopePath, bundle, event);
        }

        void recordLifecycleForBridging(String scopePath, Node event) {
            ScopeRuntimeContext scopeContext = runtime.scope(scopePath);
            scopeContext.recordBridgeable(event.clone());
            if ("/".equals(scopePath)) {
                runtime.recordRootEmission(event.clone());
            }
        }

        private Node cloneEvent(Node event) {
            return event != null ? event.clone() : null;
        }

        private static final class LogicalDelivery {
            private final String scopePath;
            private final String eventIdentity;
            private final String handlerChannelKey;
            private final String logicalDeliveryKey;

            private LogicalDelivery(String scopePath,
                                    String eventIdentity,
                                    String handlerChannelKey,
                                    String logicalDeliveryKey) {
                this.scopePath = Objects.requireNonNull(scopePath, "scopePath");
                this.eventIdentity = Objects.requireNonNull(eventIdentity, "eventIdentity");
                this.handlerChannelKey = Objects.requireNonNull(handlerChannelKey, "handlerChannelKey");
                this.logicalDeliveryKey = Objects.requireNonNull(logicalDeliveryKey, "logicalDeliveryKey");
            }

            @Override
            public boolean equals(Object other) {
                if (this == other) {
                    return true;
                }
                if (!(other instanceof LogicalDelivery)) {
                    return false;
                }
                LogicalDelivery that = (LogicalDelivery) other;
                return scopePath.equals(that.scopePath)
                        && eventIdentity.equals(that.eventIdentity)
                        && handlerChannelKey.equals(that.handlerChannelKey)
                        && logicalDeliveryKey.equals(that.logicalDeliveryKey);
            }

            @Override
            public int hashCode() {
                return Objects.hash(scopePath, eventIdentity, handlerChannelKey, logicalDeliveryKey);
            }
        }
    }

    @FunctionalInterface
    interface ProcessEventSnapshotFactory {
        FrozenNode freeze(Node processEventSource);
    }

    private enum ProcessEventSnapshotState {
        UNINITIALIZED,
        ABSENT,
        READY,
        FAILED
    }

    private static final class TerminationRecord {
        final ScopeRuntimeContext.TerminationKind kind;
        final ProcessorErrorCategory category;
        final String reason;

        TerminationRecord(ScopeRuntimeContext.TerminationKind kind,
                          ProcessorErrorCategory category,
                          String reason) {
            this.kind = kind;
            this.category = category;
            this.reason = reason;
        }
    }

    private static final class FatalDiagnostic {
        final ProcessorErrorCategory category;
        final String reason;

        FatalDiagnostic(ProcessorErrorCategory category, String reason) {
            this.category = category != null ? category : ProcessorErrorCategory.InternalProcessorError;
            this.reason = reason;
        }

        static FatalDiagnostic from(TerminationRecord record) {
            return new FatalDiagnostic(record.category, record.reason);
        }
    }


    @SuppressWarnings("unchecked")
    static void executeHandler(DocumentProcessor owner, HandlerContract contract, ProcessorExecutionContext context) {
        HandlerProcessor<? extends HandlerContract> processor = owner.registry()
                .lookupHandler(contract)
                .orElseThrow(() -> new IllegalStateException(
                        "No processor registered for contract type " + contract.getTypeBlueId()));
        HandlerProcessor<HandlerContract> typed = (HandlerProcessor<HandlerContract>) processor;
        typed.execute(contract, context);
    }

    @SuppressWarnings("unchecked")
    static boolean matchesHandler(DocumentProcessor owner,
                                  HandlerContract contract,
                                  HandlerMatchContext context) {
        HandlerProcessor<? extends HandlerContract> processor = owner.registry()
                .lookupHandler(contract)
                .orElseThrow(() -> new IllegalStateException(
                        "No processor registered for contract type " + contract.getTypeBlueId()));
        HandlerProcessor<HandlerContract> typed = (HandlerProcessor<HandlerContract>) processor;
        return typed.matches(contract, context);
    }

    static final class ChannelMatch {
        final boolean matches;
        final String eventId;
        final Node event;
        final ChannelProcessor<ChannelContract> processor;
        final List<ChannelDelivery> deliveries;

        ChannelMatch(boolean matches,
                     String eventId,
                     Node event,
                     ChannelProcessor<ChannelContract> processor,
                     List<ChannelDelivery> deliveries) {
            this.matches = matches;
            this.eventId = eventId;
            this.event = event != null ? event.clone() : null;
            this.processor = processor;
            this.deliveries = copyDeliveries(deliveries);
        }

        Node eventNode() {
            return event != null ? event.clone() : null;
        }

        List<ChannelDelivery> deliveries() {
            return deliveries;
        }

        static ChannelMatch noMatch() {
            return new ChannelMatch(false, null, null, null, Collections.emptyList());
        }

        private static List<ChannelDelivery> copyDeliveries(List<ChannelDelivery> deliveries) {
            if (deliveries == null || deliveries.isEmpty()) {
                return Collections.emptyList();
            }
            List<ChannelDelivery> copy = new ArrayList<>();
            for (ChannelDelivery delivery : deliveries) {
                if (delivery != null) {
                    copy.add(ChannelDelivery.of(delivery.event(),
                            delivery.eventId(),
                            delivery.checkpointKey(),
                            delivery.shouldProcess(),
                            delivery.handlerChannelKey(),
                            delivery.logicalDeliveryKey()));
                }
            }
            return Collections.unmodifiableList(copy);
        }
    }

    static final class BoundaryViolationException extends RuntimeException {
        BoundaryViolationException(String message) {
            super(message);
        }
    }
}
