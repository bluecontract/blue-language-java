package blue.language.processor;

import blue.language.Blue;
import blue.language.conformance.ConformanceEngine;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.model.Node;
import blue.language.model.TypeBlueId;
import blue.language.processor.model.Contract;
import blue.language.processor.model.MarkerContract;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.TypeClassResolver;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Facade over the processor engine; retains public API for Document processing.
 */
public class DocumentProcessor implements AutoCloseable {

    private final ContractProcessorRegistry contractRegistry;
    private final TypeClassResolver contractTypeResolver;
    private final NodeToObjectConverter contractConverter;
    private final ContractLoader contractLoader;
    private ConformanceEngine conformanceEngine;
    private ConformancePlannerOverride conformancePlannerOverride;
    private ProcessingSnapshotManager snapshotManager;
    private ContractMatchingService matchingService;
    private volatile ProcessingMetricsSink metricsSink;
    private GasSchedule gasSchedule;
    private long gasLimit;
    private String runtimeRegistryIdentity;
    private ExternalDeliveryPlanDeriver externalDeliveryPlanDeriver;
    private ExternalDeliveryEvidenceVerifier deliveryEvidenceVerifier;
    private SubscriptionSurfaceValidator subscriptionSurfaceValidator;
    private final ReentrantReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
    private final Lock lifecycleRead = lifecycleLock.readLock();
    private final Lock lifecycleWrite = lifecycleLock.writeLock();
    private volatile boolean closed;
    private volatile boolean cachesCleared;
    private volatile boolean clearRequested;

    public DocumentProcessor() {
        this(ContractProcessorRegistryBuilder.create().registerDefaults().build());
    }

    public DocumentProcessor(ContractProcessorRegistry registry) {
        this(registry, defaultContractTypeResolver(), null, null);
    }

    public DocumentProcessor(ConformanceEngine conformanceEngine) {
        this(ContractProcessorRegistryBuilder.create().registerDefaults().build(), conformanceEngine, null);
    }

    public DocumentProcessor(ConformanceEngine conformanceEngine, ProcessingSnapshotManager snapshotManager) {
        this(ContractProcessorRegistryBuilder.create().registerDefaults().build(), conformanceEngine, snapshotManager);
    }

    public DocumentProcessor(ContractProcessorRegistry registry, ConformanceEngine conformanceEngine) {
        this(registry, conformanceEngine, null);
    }

    public DocumentProcessor(ContractProcessorRegistry registry,
                             ConformanceEngine conformanceEngine,
                             ProcessingSnapshotManager snapshotManager) {
        this(registry, defaultContractTypeResolver(), conformanceEngine, snapshotManager);
    }

    public DocumentProcessor(ContractProcessorRegistry registry,
                             TypeClassResolver contractTypeResolver,
                             ConformanceEngine conformanceEngine,
                             ProcessingSnapshotManager snapshotManager) {
        this(registry, contractTypeResolver, conformanceEngine, snapshotManager, new ContractMatchingService());
    }

    public DocumentProcessor(ContractProcessorRegistry registry,
                             TypeClassResolver contractTypeResolver,
                             ConformanceEngine conformanceEngine,
                             ProcessingSnapshotManager snapshotManager,
                             ContractMatchingService matchingService) {
        this(registry, contractTypeResolver, conformanceEngine, snapshotManager, matchingService, null);
    }

    public DocumentProcessor(ContractProcessorRegistry registry,
                             TypeClassResolver contractTypeResolver,
                             ConformanceEngine conformanceEngine,
                             ProcessingSnapshotManager snapshotManager,
                             ContractMatchingService matchingService,
                             ProcessingMetricsSink metricsSink) {
        this(registry,
                contractTypeResolver,
                conformanceEngine,
                null,
                snapshotManager,
                matchingService,
                metricsSink);
    }

    public DocumentProcessor(ContractProcessorRegistry registry,
                             TypeClassResolver contractTypeResolver,
                             ConformanceEngine conformanceEngine,
                             ConformancePlannerOverride conformancePlannerOverride,
                             ProcessingSnapshotManager snapshotManager,
                             ContractMatchingService matchingService,
                             ProcessingMetricsSink metricsSink) {
        this.contractRegistry = Objects.requireNonNull(registry, "registry");
        this.contractTypeResolver = Objects.requireNonNull(contractTypeResolver, "contractTypeResolver");
        registerRegistryContractTypes(this.contractRegistry, this.contractTypeResolver);
        this.contractConverter = new NodeToObjectConverter(this.contractTypeResolver);
        this.matchingService = Objects.requireNonNull(matchingService, "matchingService");
        this.contractLoader = new ContractLoader(
                contractRegistry,
                contractConverter,
                this.contractTypeResolver,
                this.matchingService.cachePolicy(),
                this.matchingService.blue() != null
                        ? this.matchingService.blue().getNodeProvider()
                        : null);
        this.conformanceEngine = conformanceEngine;
        this.conformancePlannerOverride = conformancePlannerOverride;
        this.snapshotManager = snapshotManager;
        this.metricsSink = metricsSink != null ? metricsSink : ProcessingMetricsSink.NOOP;
        this.gasSchedule = GasSchedule.contracts10();
        this.gasLimit = this.gasSchedule.maxProcessGas();
        this.runtimeRegistryIdentity = RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY;
        this.externalDeliveryPlanDeriver =
                ExternalDeliveryPlanDeriver.unavailable();
        this.deliveryEvidenceVerifier =
                RootExternalDeliveryEvidenceVerifier.configured(
                        contractLoader,
                        snapshotManager,
                        contractRegistry,
                        contractConverter,
                        externalDeliveryPlanDeriver);
        this.subscriptionSurfaceValidator =
                DirectSubscriptionSurfaceValidator.configured(
                        contractLoader,
                        snapshotManager,
                        contractRegistry,
                        contractConverter);
    }

    private DocumentProcessor(Builder builder) {
        this(builder.contractRegistry,
                builder.contractTypeResolver,
                builder.conformanceEngine,
                builder.conformancePlannerOverride,
                builder.snapshotManager,
                builder.matchingService,
                builder.metricsSink);
        this.gasSchedule = builder.gasSchedule;
        this.contractLoader.gasSchedule(builder.gasSchedule);
        this.gasLimit = builder.gasLimit != null
                ? builder.gasLimit
                : builder.gasSchedule.maxProcessGas();
        this.runtimeRegistryIdentity = builder.runtimeRegistryIdentity;
        this.externalDeliveryPlanDeriver =
                builder.externalDeliveryPlanDeriver;
        this.deliveryEvidenceVerifier =
                builder.deliveryEvidenceVerifier != null
                        ? builder.deliveryEvidenceVerifier
                        : RootExternalDeliveryEvidenceVerifier.configured(
                        contractLoader,
                        snapshotManager,
                        contractRegistry,
                        contractConverter,
                        externalDeliveryPlanDeriver);
        if (builder.subscriptionSurfaceValidator != null) {
            this.subscriptionSurfaceValidator =
                    builder.subscriptionSurfaceValidator;
        }
    }

    public DocumentProcessingResult initializeDocument(Node document) {
        Lock configurationRead = contractRegistry.configurationReadLock();
        configurationRead.lock();
        lifecycleRead.lock();
        try {
            ensureOpen();
            return ProcessorEngine.initializeDocument(this, document);
        } finally {
            releaseLifecycleReadAndConfiguration(configurationRead);
        }
    }

    /**
     * Initializes the snapshot's resolved root as the selected Processing Document.
     * The canonical root remains the immutable identity companion.
     *
     * @param snapshot verified canonical and resolved document views
     * @return the initialization result and its authoritative snapshot
     */
    public DocumentProcessingResult initializeDocument(ResolvedSnapshot snapshot) {
        Lock configurationRead = contractRegistry.configurationReadLock();
        configurationRead.lock();
        lifecycleRead.lock();
        try {
            ensureOpen();
            requireSnapshotManager();
            return ProcessorEngine.initializeDocument(this, snapshot);
        } finally {
            releaseLifecycleReadAndConfiguration(configurationRead);
        }
    }

    public DocumentProcessingResult processDocument(Node document, Node event) {
        Lock configurationRead = contractRegistry.configurationReadLock();
        configurationRead.lock();
        lifecycleRead.lock();
        try {
            ensureOpen();
            ProcessingInputAdmission admission =
                    new ProcessingInputAdmission(snapshotManager);
            ProcessingInputAdmission.AdmittedNode admittedRoot =
                    admission.materializeTopLevel(
                            document, "Processing Root");
            if (ProcessorEngine.hasDirectRootTerminationEntry(
                    admittedRoot.node())) {
                return processAdmitted(
                        admission, admittedRoot, event, null);
            }
            Node admittedEvent = admission.materializeTopLevel(
                    event, "Processing Event").node();
            ExternalDeliveryPlan plan =
                    deriveExternalDeliveryPlan(
                            admittedRoot.node(), admittedEvent);
            admittedRoot = admitDeliveryScopes(
                    admission, admittedRoot, plan.deliveries());
            VerifiedExecutionEvidence evidence =
                    bindAndVerifyDerived(
                            admittedRoot.node(),
                            admittedEvent,
                            plan);
            return processAdmitted(
                    admission,
                    admittedRoot,
                    admittedEvent,
                    evidence);
        } catch (InvalidExecutionEvidenceException exception) {
            return invalidExternalDeliveryResult(
                    document, exception);
        } finally {
            releaseLifecycleReadAndConfiguration(configurationRead);
        }
    }

    /**
     * Processes with revision-bound verified feeder evidence. The evidence is
     * revalidated against the exact Root, event, and runtime registry before
     * semantic execution and is never inserted into either semantic input.
     */
    public DocumentProcessingResult processDocument(Node document,
                                                    Node event,
                                                    VerifiedExecutionEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        Lock configurationRead = contractRegistry.configurationReadLock();
        configurationRead.lock();
        lifecycleRead.lock();
        try {
            ensureOpen();
            ProcessingInputAdmission admission =
                    new ProcessingInputAdmission(snapshotManager);
            ProcessingInputAdmission.AdmittedNode admittedRoot =
                    admission.materializeTopLevel(
                            document, "Processing Root");
            if (ProcessorEngine.hasDirectRootTerminationEntry(
                    admittedRoot.node())) {
                return processAdmitted(
                        admission, admittedRoot, event, null);
            }
            Node admittedEvent = admission.materializeTopLevel(
                    event, "Processing Event").node();
            admittedRoot = admitDeliveryScopes(
                    admission,
                    admittedRoot,
                    evidence.deliveries());
            evidence.revalidate(
                    admittedRoot.node(),
                    admittedEvent,
                    runtimeRegistryIdentity,
                    deliveryEvidenceVerifier);
            return processAdmitted(
                    admission,
                    admittedRoot,
                    admittedEvent,
                    evidence);
        } catch (InvalidExecutionEvidenceException exception) {
            return DocumentProcessingResult.nonCommitting(document,
                    0L,
                    ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                    ProcessorDiagnostic.of(
                            ProcessorErrorCategory.InvalidExternalChannelSnapshot,
                            exception.getMessage()));
        } finally {
            releaseLifecycleReadAndConfiguration(configurationRead);
        }
    }

    /**
     * Executes PROCESS and returns the separate revision-bound host companion
     * required to commit Root/outbox, the exact validated subscription delta,
     * and delivery progress atomically.
     *
     * <p>Unlike {@link #processDocument(Node, Node,
     * VerifiedExecutionEvidence)}, invalid feeder evidence is rejected at this
     * platform boundary instead of being converted to a semantic result: no
     * trustworthy compare-and-swap companion can be constructed for it.</p>
     */
    public PlatformProcessingResult processDocumentForPlatformCommit(
            Node document,
            Node event,
            VerifiedExecutionEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        Lock configurationRead =
                contractRegistry.configurationReadLock();
        configurationRead.lock();
        lifecycleRead.lock();
        try {
            ensureOpen();
            ProcessingInputAdmission admission =
                    new ProcessingInputAdmission(snapshotManager);
            ProcessingInputAdmission.AdmittedNode admittedRoot =
                    admission.materializeTopLevel(
                            document, "Processing Root");
            if (ProcessorEngine.hasDirectRootTerminationEntry(
                    admittedRoot.node())) {
                evidence.revalidateBinding(
                        admittedRoot.node(),
                        event,
                        runtimeRegistryIdentity);
            } else {
                Node admittedEvent = admission.materializeTopLevel(
                        event, "Processing Event").node();
                admittedRoot = admitDeliveryScopes(
                        admission,
                        admittedRoot,
                        evidence.deliveries());
                evidence.revalidate(
                        admittedRoot.node(),
                        admittedEvent,
                        runtimeRegistryIdentity,
                        deliveryEvidenceVerifier);
                event = admittedEvent;
            }
            ProcessingDebugResult debug =
                    processAdmittedWithTrace(
                            admission,
                            admittedRoot,
                            event,
                            evidence);
            PlatformCommitCompanion companion =
                    debug.platformCommitCompanion();
            if (companion == null) {
                throw new IllegalStateException(
                        "Revision-bound execution produced no platform "
                                + "commit companion");
            }
            return new PlatformProcessingResult(
                    debug.processResult(), companion);
        } finally {
            releaseLifecycleReadAndConfiguration(
                    configurationRead);
        }
    }

    /**
     * Explicit debug/conformance API. The returned trace is out-of-band and is
     * not part of the five-field ProcessResult.
     */
    public ProcessingDebugResult processDocumentWithTrace(Node document, Node event) {
        Lock configurationRead = contractRegistry.configurationReadLock();
        configurationRead.lock();
        lifecycleRead.lock();
        try {
            ensureOpen();
            ProcessingInputAdmission admission =
                    new ProcessingInputAdmission(snapshotManager);
            ProcessingInputAdmission.AdmittedNode admittedRoot =
                    admission.materializeTopLevel(
                            document, "Processing Root");
            if (ProcessorEngine.hasDirectRootTerminationEntry(
                    admittedRoot.node())) {
                return processAdmittedWithTrace(
                        admission, admittedRoot, event, null);
            }
            Node admittedEvent = admission.materializeTopLevel(
                    event, "Processing Event").node();
            ExternalDeliveryPlan plan =
                    deriveExternalDeliveryPlan(
                            admittedRoot.node(), admittedEvent);
            admittedRoot = admitDeliveryScopes(
                    admission, admittedRoot, plan.deliveries());
            VerifiedExecutionEvidence evidence =
                    bindAndVerifyDerived(
                            admittedRoot.node(),
                            admittedEvent,
                            plan);
            return processAdmittedWithTrace(
                    admission,
                    admittedRoot,
                    admittedEvent,
                    evidence);
        } catch (InvalidExecutionEvidenceException exception) {
            return new ProcessingDebugResult(
                    invalidExternalDeliveryResult(document, exception),
                    ProcessingConformanceTrace.empty());
        } finally {
            releaseLifecycleReadAndConfiguration(configurationRead);
        }
    }

    public ProcessingDebugResult processDocumentWithTrace(Node document,
                                                          Node event,
                                                          VerifiedExecutionEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        Lock configurationRead = contractRegistry.configurationReadLock();
        configurationRead.lock();
        lifecycleRead.lock();
        try {
            ensureOpen();
            ProcessingInputAdmission admission =
                    new ProcessingInputAdmission(snapshotManager);
            ProcessingInputAdmission.AdmittedNode admittedRoot =
                    admission.materializeTopLevel(
                            document, "Processing Root");
            if (ProcessorEngine.hasDirectRootTerminationEntry(
                    admittedRoot.node())) {
                return processAdmittedWithTrace(
                        admission, admittedRoot, event, null);
            }
            Node admittedEvent = admission.materializeTopLevel(
                    event, "Processing Event").node();
            admittedRoot = admitDeliveryScopes(
                    admission,
                    admittedRoot,
                    evidence.deliveries());
            evidence.revalidate(
                    admittedRoot.node(),
                    admittedEvent,
                    runtimeRegistryIdentity,
                    deliveryEvidenceVerifier);
            return processAdmittedWithTrace(
                    admission,
                    admittedRoot,
                    admittedEvent,
                    evidence);
        } catch (InvalidExecutionEvidenceException exception) {
            DocumentProcessingResult result = DocumentProcessingResult.nonCommitting(
                    document,
                    0L,
                    ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                    ProcessorDiagnostic.of(
                            ProcessorErrorCategory.InvalidExternalChannelSnapshot,
                            exception.getMessage()));
            return new ProcessingDebugResult(result, ProcessingConformanceTrace.empty());
        } finally {
            releaseLifecycleReadAndConfiguration(configurationRead);
        }
    }

    /**
     * Resource-acquisition boundary for Contracts 1.0.
     */
    public ProcessAttemptResult processAttempt(
            Node document,
            Node event) {
        Lock configurationRead =
                contractRegistry.configurationReadLock();
        configurationRead.lock();
        lifecycleRead.lock();
        try {
            ensureOpen();
            ProcessingInputAdmission admission =
                    new ProcessingInputAdmission(snapshotManager);
            ProcessingInputAdmission.AdmittedNode admittedRoot =
                    admission.materializeTopLevel(
                            document, "Processing Root");
            if (ProcessorEngine.hasDirectRootTerminationEntry(
                    admittedRoot.node())) {
                return ProcessAttemptResult.complete(
                        processAdmitted(
                                admission,
                                admittedRoot,
                                event,
                                null));
            }
            Node admittedEvent = admission.materializeTopLevel(
                    event, "Processing Event").node();
            ExternalDeliveryPlan plan =
                    deriveExternalDeliveryPlan(
                            admittedRoot.node(), admittedEvent);
            VerifiedExecutionEvidence evidence =
                    plan.bind(
                            admittedRoot.node(),
                            admittedEvent,
                            runtimeRegistryIdentity);
            return completeAttempt(
                    document,
                    admission,
                    admittedRoot,
                    admittedEvent,
                    evidence,
                    plan);
        } catch (ExecutionEvidenceUnavailableException exception) {
            return needsResources(exception);
        } catch (InvalidExecutionEvidenceException exception) {
            return invalidAttempt(document, exception);
        } finally {
            releaseLifecycleReadAndConfiguration(
                    configurationRead);
        }
    }

    /**
     * Resource-acquisition boundary for Contracts 1.0 with an already
     * captured feeder evidence envelope.
     */
    public ProcessAttemptResult processAttempt(Node document,
                                               Node event,
                                               VerifiedExecutionEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        Lock configurationRead = contractRegistry.configurationReadLock();
        configurationRead.lock();
        lifecycleRead.lock();
        try {
            ensureOpen();
            if (ProcessorEngine.hasDirectRootTerminationEntry(
                    document)) {
                return ProcessAttemptResult.complete(
                        ProcessorEngine.processDocument(
                                this, document, event, null));
            }
            /*
             * Validate only immutable input/revision/registry bindings before
             * acquisition. Full subscription/provider verification must not
             * run until every explicitly required exact node is available.
             */
            try {
                evidence.revalidateBinding(
                        document, event, runtimeRegistryIdentity);
            } catch (InvalidExecutionEvidenceException exception) {
                return invalidAttempt(document, exception);
            }
            java.util.List<String> missing =
                    evidence.missingRequiredExactNodeBlueIds();
            if (!missing.isEmpty()) {
                return ProcessAttemptResult.needsResources(missing);
            }
            try {
                ProcessingInputAdmission admission =
                        new ProcessingInputAdmission(snapshotManager);
                ProcessingInputAdmission.AdmittedNode admittedRoot =
                        admission.materializeTopLevel(
                                document, "Processing Root");
                if (ProcessorEngine.hasDirectRootTerminationEntry(
                        admittedRoot.node())) {
                    return ProcessAttemptResult.complete(
                            processAdmitted(
                                    admission,
                                    admittedRoot,
                                    event,
                                    null));
                }
                Node admittedEvent = admission.materializeTopLevel(
                        event, "Processing Event").node();
                admittedRoot = admitDeliveryScopes(
                        admission,
                        admittedRoot,
                        evidence.deliveries());
                evidence.revalidate(
                        admittedRoot.node(),
                        admittedEvent,
                        runtimeRegistryIdentity,
                        deliveryEvidenceVerifier);
                return ProcessAttemptResult.complete(
                        processAdmitted(
                                admission,
                                admittedRoot,
                                admittedEvent,
                                evidence));
            } catch (ExecutionEvidenceUnavailableException exception) {
                return needsResources(exception);
            } catch (InvalidExecutionEvidenceException exception) {
                return invalidAttempt(document, exception);
            }
        } finally {
            releaseLifecycleReadAndConfiguration(configurationRead);
        }
    }

    /**
     * Processes the snapshot's resolved root as the selected Processing Document.
     * The canonical root remains the immutable identity companion.
     *
     * @param snapshot verified canonical and resolved document views
     * @param event read-only Processing Event
     * @return the processing result and its authoritative snapshot
     */
    public DocumentProcessingResult processDocument(ResolvedSnapshot snapshot, Node event) {
        Lock configurationRead = contractRegistry.configurationReadLock();
        configurationRead.lock();
        lifecycleRead.lock();
        try {
            ensureOpen();
            requireSnapshotManager();
            Node canonicalRoot = snapshot.canonicalRoot();
            if (ProcessorEngine.hasDirectRootTerminationEntry(
                    canonicalRoot)) {
                return ProcessorEngine.processDocument(
                        this, snapshot, event, null);
            }
            Node admittedEvent =
                    new ProcessingInputAdmission(snapshotManager)
                            .materializeTopLevel(
                                    event, "Processing Event")
                            .node();
            VerifiedExecutionEvidence evidence =
                    deriveExternalDeliveryEvidence(
                            canonicalRoot, admittedEvent);
            return ProcessorEngine.processDocument(
                    this, snapshot, admittedEvent, evidence);
        } catch (InvalidExecutionEvidenceException exception) {
            return invalidExternalDeliveryResult(
                    snapshot.canonicalRoot(), exception);
        } finally {
            releaseLifecycleReadAndConfiguration(configurationRead);
        }
    }

    /**
     * Processes a snapshot with revision-bound feeder evidence. Evidence is
     * bound to the snapshot's exact canonical Root, while execution reads the
     * verified resolved companion.
     */
    public DocumentProcessingResult processDocument(
            ResolvedSnapshot snapshot,
            Node event,
            VerifiedExecutionEvidence evidence) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(evidence, "evidence");
        Lock configurationRead = contractRegistry.configurationReadLock();
        configurationRead.lock();
        lifecycleRead.lock();
        try {
            ensureOpen();
            requireSnapshotManager();
            Node canonicalRoot = snapshot.canonicalRoot();
            if (ProcessorEngine.hasDirectRootTerminationEntry(
                    canonicalRoot)) {
                return ProcessorEngine.processDocument(
                        this, snapshot, event, null);
            }
            Node admittedEvent =
                    new ProcessingInputAdmission(snapshotManager)
                            .materializeTopLevel(
                                    event, "Processing Event")
                            .node();
            evidence.revalidate(
                    canonicalRoot,
                    admittedEvent,
                    runtimeRegistryIdentity,
                    deliveryEvidenceVerifier);
            return ProcessorEngine.processDocument(
                    this, snapshot, admittedEvent, evidence);
        } catch (InvalidExecutionEvidenceException exception) {
            return invalidExternalDeliveryResult(
                    snapshot.canonicalRoot(), exception);
        } finally {
            releaseLifecycleReadAndConfiguration(configurationRead);
        }
    }

    /**
     * Snapshot-native atomic platform hand-off. The compare-and-swap binding
     * remains the exact canonical Root carried by the supplied snapshot.
     */
    public PlatformProcessingResult processDocumentForPlatformCommit(
            ResolvedSnapshot snapshot,
            Node event,
            VerifiedExecutionEvidence evidence) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(evidence, "evidence");
        Lock configurationRead =
                contractRegistry.configurationReadLock();
        configurationRead.lock();
        lifecycleRead.lock();
        try {
            ensureOpen();
            requireSnapshotManager();
            Node canonicalRoot = snapshot.canonicalRoot();
            if (ProcessorEngine.hasDirectRootTerminationEntry(
                    canonicalRoot)) {
                evidence.revalidateBinding(
                        canonicalRoot,
                        event,
                        runtimeRegistryIdentity);
            } else {
                event = new ProcessingInputAdmission(
                        snapshotManager)
                        .materializeTopLevel(
                                event, "Processing Event")
                        .node();
                evidence.revalidate(
                        canonicalRoot,
                        event,
                        runtimeRegistryIdentity,
                        deliveryEvidenceVerifier);
            }
            ProcessingDebugResult debug =
                    ProcessorEngine.processDocumentWithTrace(
                            this, snapshot, event, evidence);
            PlatformCommitCompanion companion =
                    debug.platformCommitCompanion();
            if (companion == null) {
                throw new IllegalStateException(
                        "Revision-bound execution produced no platform "
                                + "commit companion");
            }
            return new PlatformProcessingResult(
                    debug.processResult(), companion);
        } finally {
            releaseLifecycleReadAndConfiguration(
                    configurationRead);
        }
    }

    /**
     * Snapshot-native debug/conformance entry point. The trace remains
     * out-of-band and the semantic result retains the authoritative snapshot.
     */
    public ProcessingDebugResult processDocumentWithTrace(
            ResolvedSnapshot snapshot,
            Node event) {
        Objects.requireNonNull(snapshot, "snapshot");
        Lock configurationRead = contractRegistry.configurationReadLock();
        configurationRead.lock();
        lifecycleRead.lock();
        try {
            ensureOpen();
            requireSnapshotManager();
            if (ProcessorEngine.hasDirectRootTerminationEntry(
                    snapshot.canonicalRoot())) {
                return ProcessorEngine.processDocumentWithTrace(
                        this, snapshot, event, null);
            }
            Node admittedEvent =
                    new ProcessingInputAdmission(snapshotManager)
                            .materializeTopLevel(
                                    event, "Processing Event")
                            .node();
            VerifiedExecutionEvidence evidence =
                    deriveExternalDeliveryEvidence(
                            snapshot.canonicalRoot(),
                            admittedEvent);
            return ProcessorEngine.processDocumentWithTrace(
                    this, snapshot, admittedEvent, evidence);
        } catch (InvalidExecutionEvidenceException exception) {
            return new ProcessingDebugResult(
                    invalidExternalDeliveryResult(
                            snapshot.canonicalRoot(), exception),
                    ProcessingConformanceTrace.empty(),
                    null,
                    snapshot);
        } finally {
            releaseLifecycleReadAndConfiguration(configurationRead);
        }
    }

    /**
     * Snapshot-native debug/conformance entry point with explicit verified
     * feeder evidence.
     */
    public ProcessingDebugResult processDocumentWithTrace(
            ResolvedSnapshot snapshot,
            Node event,
            VerifiedExecutionEvidence evidence) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(evidence, "evidence");
        Lock configurationRead = contractRegistry.configurationReadLock();
        configurationRead.lock();
        lifecycleRead.lock();
        try {
            ensureOpen();
            requireSnapshotManager();
            Node canonicalRoot = snapshot.canonicalRoot();
            if (ProcessorEngine.hasDirectRootTerminationEntry(
                    canonicalRoot)) {
                return ProcessorEngine.processDocumentWithTrace(
                        this, snapshot, event, null);
            }
            Node admittedEvent =
                    new ProcessingInputAdmission(snapshotManager)
                            .materializeTopLevel(
                                    event, "Processing Event")
                            .node();
            evidence.revalidate(
                    canonicalRoot,
                    admittedEvent,
                    runtimeRegistryIdentity,
                    deliveryEvidenceVerifier);
            return ProcessorEngine.processDocumentWithTrace(
                    this, snapshot, admittedEvent, evidence);
        } catch (InvalidExecutionEvidenceException exception) {
            return new ProcessingDebugResult(
                    invalidExternalDeliveryResult(
                            snapshot.canonicalRoot(), exception),
                    ProcessingConformanceTrace.empty(),
                    null,
                    snapshot);
        } finally {
            releaseLifecycleReadAndConfiguration(configurationRead);
        }
    }

    private VerifiedExecutionEvidence deriveExternalDeliveryEvidence(
            Node document,
            Node event) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(event, "event");
        ExternalDeliveryPlan plan =
                deriveExternalDeliveryPlan(document, event);
        return bindAndVerifyDerived(
                document, event, plan);
    }

    private VerifiedExecutionEvidence bindAndVerifyDerived(
            Node document,
            Node event,
            ExternalDeliveryPlan plan) {
        VerifiedExecutionEvidence evidence =
                plan.bind(document, event, runtimeRegistryIdentity);
        evidence.revalidateDerived(
                document,
                event,
                runtimeRegistryIdentity,
                deliveryEvidenceVerifier,
                plan);
        return evidence;
    }

    private ExternalDeliveryPlan deriveExternalDeliveryPlan(
            Node document,
            Node event) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(event, "event");
        ExternalDeliveryPlan plan =
                deliveryEvidenceVerifier
                        instanceof RootExternalDeliveryEvidenceVerifier
                        ? ((RootExternalDeliveryEvidenceVerifier)
                        deliveryEvidenceVerifier).derivePlan(
                        document, event)
                        : externalDeliveryPlanDeriver.derive(
                        document.clone(), event.clone());
        if (plan == null || !plan.exactRuntimeState()) {
            throw new InvalidExecutionEvidenceException(
                    "External delivery plan is not certified complete");
        }
        return plan;
    }

    private ProcessingInputAdmission.AdmittedNode admitDeliveryScopes(
            ProcessingInputAdmission admission,
            ProcessingInputAdmission.AdmittedNode admittedRoot,
            java.util.List<ExternalDeliverySnapshot> deliveries) {
        java.util.List<String> scopePaths =
                new java.util.ArrayList<>();
        for (ExternalDeliverySnapshot delivery : deliveries) {
            scopePaths.add(delivery.scopePath());
        }
        return admission.materializeScopePaths(
                admittedRoot, scopePaths);
    }

    private DocumentProcessingResult processAdmitted(
            ProcessingInputAdmission admission,
            ProcessingInputAdmission.AdmittedNode admittedRoot,
            Node event,
            VerifiedExecutionEvidence evidence) {
        if (admittedRoot.wasMaterialized()) {
            return ProcessorEngine.processDocument(
                    this,
                    admission.deferredSnapshot(admittedRoot),
                    event,
                    evidence);
        }
        return ProcessorEngine.processDocument(
                this, admittedRoot.node(), event, evidence);
    }

    private ProcessingDebugResult processAdmittedWithTrace(
            ProcessingInputAdmission admission,
            ProcessingInputAdmission.AdmittedNode admittedRoot,
            Node event,
            VerifiedExecutionEvidence evidence) {
        if (admittedRoot.wasMaterialized()) {
            return ProcessorEngine.processDocumentWithTrace(
                    this,
                    admission.deferredSnapshot(admittedRoot),
                    event,
                    evidence);
        }
        return ProcessorEngine.processDocumentWithTrace(
                this, admittedRoot.node(), event, evidence);
    }

    private ProcessAttemptResult completeAttempt(
            Node originalDocument,
            ProcessingInputAdmission admission,
            ProcessingInputAdmission.AdmittedNode admittedRoot,
            Node event,
            VerifiedExecutionEvidence evidence,
            ExternalDeliveryPlan derivedPlan) {
        try {
            evidence.revalidateBinding(
                    admittedRoot.node(),
                    event,
                    runtimeRegistryIdentity);
            java.util.List<String> missing =
                    evidence.missingRequiredExactNodeBlueIds();
            if (!missing.isEmpty()) {
                return ProcessAttemptResult.needsResources(missing);
            }
            admittedRoot = admitDeliveryScopes(
                    admission,
                    admittedRoot,
                    derivedPlan.deliveries());
            evidence.revalidateDerived(
                    admittedRoot.node(),
                    event,
                    runtimeRegistryIdentity,
                    deliveryEvidenceVerifier,
                    derivedPlan);
            return ProcessAttemptResult.complete(
                    processAdmitted(
                            admission,
                            admittedRoot,
                            event,
                            evidence));
        } catch (ExecutionEvidenceUnavailableException exception) {
            return needsResources(exception);
        } catch (InvalidExecutionEvidenceException exception) {
            return invalidAttempt(
                    originalDocument, exception);
        }
    }

    private ProcessAttemptResult needsResources(
            ExecutionEvidenceUnavailableException exception) {
        if (exception.requiredExactBlueIds().isEmpty()) {
            /*
             * Feeder/activation state without a content-addressed demand
             * cannot be represented by NeedsResources(sortedExactBlueIds).
             * Keep it as a host suspension rather than fabricating an ID.
             */
            throw exception;
        }
        return ProcessAttemptResult.needsResources(
                exception.requiredExactBlueIds());
    }

    private ProcessAttemptResult invalidAttempt(
            Node document,
            InvalidExecutionEvidenceException exception) {
        return ProcessAttemptResult.complete(
                DocumentProcessingResult.nonCommitting(
                        document,
                        0L,
                        ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                        ProcessorDiagnostic.of(
                                ProcessorErrorCategory
                                        .InvalidExternalChannelSnapshot,
                                exception.getMessage())));
    }

    private DocumentProcessingResult invalidExternalDeliveryResult(
            Node document,
            InvalidExecutionEvidenceException exception) {
        return DocumentProcessingResult.nonCommitting(
                Objects.requireNonNull(document, "document"),
                0L,
                ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                ProcessorDiagnostic.of(
                        ProcessorErrorCategory
                                .InvalidExternalChannelSnapshot,
                        ProcessorEngine.deterministicMessage(
                                exception,
                                "Invalid external delivery evidence")));
    }

    public boolean isInitialized(Node document) {
        Lock configurationRead = contractRegistry.configurationReadLock();
        configurationRead.lock();
        lifecycleRead.lock();
        try {
            ensureOpen();
            return ProcessorEngine.isInitialized(this, document);
        } finally {
            releaseLifecycleReadAndConfiguration(configurationRead);
        }
    }

    public boolean isInitialized(ResolvedSnapshot snapshot) {
        Lock configurationRead = contractRegistry.configurationReadLock();
        configurationRead.lock();
        lifecycleRead.lock();
        try {
            ensureOpen();
            return ProcessorEngine.isInitialized(this, snapshot);
        } finally {
            releaseLifecycleReadAndConfiguration(configurationRead);
        }
    }

    public DocumentProcessor registerContractProcessor(ContractProcessor<? extends Contract> processor) {
        rejectWriteUpgrade();
        Lock configurationWrite = contractRegistry.configurationWriteLock();
        configurationWrite.lock();
        lifecycleWrite.lock();
        try {
            ensureOpen();
            Objects.requireNonNull(processor, "processor");
            contractRegistry.register(processor);
            registerAnnotatedContractType(processor.contractType());
            clearCachesInternal();
            return this;
        } finally {
            lifecycleWrite.unlock();
            configurationWrite.unlock();
        }
    }

    /**
     * Registers a processor for an explicit BlueId without supplying provider
     * content for that BlueId.
     *
     * <p>For standalone initialization, configure a verified provider-backed
     * snapshot manager/Blue runtime or use the exact-canonical-content overload.
     * Otherwise a scope that requires the registered type fails before
     * initiation with {@link ProcessorErrorCategory#RuntimeExecutionFailure}.</p>
     */
    public DocumentProcessor registerContractProcessor(String blueId, ContractProcessor<? extends Contract> processor) {
        rejectWriteUpgrade();
        Lock configurationWrite = contractRegistry.configurationWriteLock();
        configurationWrite.lock();
        lifecycleWrite.lock();
        try {
            ensureOpen();
            Objects.requireNonNull(processor, "processor");
            contractRegistry.register(blueId, processor);
            contractTypeResolver.register(blueId, processor.contractType());
            clearCachesInternal();
            return this;
        } finally {
            lifecycleWrite.unlock();
            configurationWrite.unlock();
        }
    }

    /**
     * Registers an external contract processor together with its exact
     * canonical Blue type content. The content is cloned and verified against
     * {@code blueId} before the registry is mutated.
     */
    public DocumentProcessor registerContractProcessor(
            String blueId,
            Node canonicalTypeNode,
            ContractProcessor<? extends Contract> processor) {
        rejectWriteUpgrade();
        Lock configurationWrite = contractRegistry.configurationWriteLock();
        configurationWrite.lock();
        lifecycleWrite.lock();
        try {
            ensureOpen();
            Objects.requireNonNull(processor, "processor");
            registerExactContractProcessor(
                    contractRegistry,
                    contractTypeResolver,
                    blueId,
                    canonicalTypeNode,
                    processor);
            clearCachesInternal();
            return this;
        } finally {
            lifecycleWrite.unlock();
            configurationWrite.unlock();
        }
    }

    public ContractProcessorRegistry getContractRegistry() {
        return contractRegistry;
    }

    public TypeClassResolver getContractTypeResolver() {
        return contractTypeResolver;
    }

    ContractProcessorRegistry registry() {
        return contractRegistry;
    }

    NodeToObjectConverter contractConverter() {
        return contractConverter;
    }

    ContractLoader contractLoader() {
        return contractLoader;
    }

    ConformanceEngine conformanceEngine() {
        return conformanceEngine;
    }

    ConformancePlannerOverride conformancePlannerOverride() {
        return conformancePlannerOverride;
    }

    ProcessingSnapshotManager snapshotManager() {
        return snapshotManager;
    }

    ProcessingSnapshotManager scopeIdentitySnapshotManager() {
        if (snapshotManager != null) {
            return snapshotManager;
        }
        ContractMatchingService currentMatchingService = matchingService;
        Blue languageRuntime = currentMatchingService != null
                ? currentMatchingService.blue()
                : null;
        if (languageRuntime == null) {
            return new RegisteredContractScopeIdentitySnapshotManager(contractRegistry);
        }
        DocumentProcessor languageProcessor = languageRuntime.getDocumentProcessor();
        ProcessingSnapshotManager languageManager = languageProcessor != this
                ? languageProcessor.snapshotManager()
                : null;
        return languageManager != null ? languageManager.transientSequence() : null;
    }

    ContractMatchingService matchingService() {
        return matchingService;
    }

    ProcessingMetricsSink metricsSink() {
        return metricsSink != null ? metricsSink : ProcessingMetricsSink.NOOP;
    }

    GasMeter newGasMeter() {
        return new GasMeter(gasSchedule, gasLimit);
    }

    String runtimeRegistryIdentity() {
        return runtimeRegistryIdentity;
    }

    SubscriptionSurfaceValidator subscriptionSurfaceValidator() {
        return subscriptionSurfaceValidator;
    }

    GasSchedule gasSchedule() {
        return gasSchedule;
    }

    public ProcessingMetricsSink processingMetricsSink() {
        return metricsSink();
    }

    public boolean supportsSnapshotProcessing() {
        return snapshotManager != null;
    }

    public DocumentProcessor processingMetricsSink(ProcessingMetricsSink metricsSink) {
        rejectWriteUpgrade();
        lifecycleWrite.lock();
        try {
            ensureOpen();
            this.metricsSink = metricsSink != null ? metricsSink : ProcessingMetricsSink.NOOP;
            return this;
        } finally {
            lifecycleWrite.unlock();
        }
    }

    /** Releases every reloadable contract-plan and matching cache owned by this processor. */
    public void clearCaches() {
        if (lifecycleLock.getReadHoldCount() > 0) {
            clearRequested = true;
            return;
        }
        lifecycleWrite.lock();
        try {
            clearCachesInternal();
            clearRequested = false;
        } finally {
            lifecycleWrite.unlock();
        }
    }

    /** Returns the number of reloadable processor-plan cache entries. */
    public int cacheEntryCount() {
        int loaderEntries = contractLoader.cacheSize();
        ContractMatchingService currentMatchingService = matchingService;
        int matchingEntries = currentMatchingService != null
                ? currentMatchingService.cacheEntryCount() : 0;
        return Integer.MAX_VALUE - loaderEntries < matchingEntries
                ? Integer.MAX_VALUE
                : loaderEntries + matchingEntries;
    }

    /** Returns the approximate retained weight of reloadable processor-plan caches. */
    public long cacheWeightBytes() {
        long loaderWeight = contractLoader.cacheWeightBytes();
        ContractMatchingService currentMatchingService = matchingService;
        long matchingWeight = currentMatchingService != null
                ? currentMatchingService.cacheWeightBytes() : 0L;
        return Long.MAX_VALUE - loaderWeight < matchingWeight
                ? Long.MAX_VALUE
                : loaderWeight + matchingWeight;
    }

    public Map<String, MarkerContract> markersFor(Node scopeNode, String scopePath) {
        Lock configurationRead = contractRegistry.configurationReadLock();
        configurationRead.lock();
        lifecycleRead.lock();
        try {
            ensureOpen();
            ContractBundle bundle = contractLoader.load(
                    FrozenNode.fromResolvedNode(scopeNode), scopePath);
            return bundle.markers();
        } finally {
            releaseLifecycleReadAndConfiguration(configurationRead);
        }
    }

    /** Returns whether this processor has released its reloadable caches. */
    public boolean isClosed() {
        return closed;
    }

    /** Invalidates processor work and releases every reloadable plan/matching cache. */
    @Override
    public void close() {
        closed = true;
        if (lifecycleLock.getReadHoldCount() > 0) {
            clearRequested = true;
            return;
        }
        lifecycleWrite.lock();
        try {
            clearCachesIfNeeded();
        } finally {
            lifecycleWrite.unlock();
        }
    }

    private void clearCachesInternal() {
        contractLoader.clearCaches();
        ContractMatchingService currentMatchingService = matchingService;
        if (currentMatchingService != null) {
            currentMatchingService.clearCaches();
        }
    }

    private void releaseLifecycleRead() {
        lifecycleRead.unlock();
        if ((closed || clearRequested) && lifecycleLock.getReadHoldCount() == 0) {
            lifecycleWrite.lock();
            try {
                clearCachesIfNeeded();
            } finally {
                lifecycleWrite.unlock();
            }
        }
    }

    private void releaseLifecycleReadAndConfiguration(Lock configurationRead) {
        try {
            releaseLifecycleRead();
        } finally {
            configurationRead.unlock();
        }
    }

    private void clearCachesIfNeeded() {
        if (closed) {
            if (!cachesCleared) {
                clearCachesInternal();
                cachesCleared = true;
            }
            detachRuntimeCollaborators();
            clearRequested = false;
        } else if (clearRequested) {
            clearCachesInternal();
            clearRequested = false;
        }
    }

    private void rejectWriteUpgrade() {
        if (lifecycleLock.getReadHoldCount() > 0
                || contractRegistry.isConfigurationReadHeldByCurrentThread()) {
            throw new IllegalStateException(
                    "Document processor configuration cannot change during active processing");
        }
    }

    private void detachRuntimeCollaborators() {
        conformanceEngine = null;
        conformancePlannerOverride = null;
        snapshotManager = null;
        matchingService = null;
        metricsSink = ProcessingMetricsSink.NOOP;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Document processor is closed");
        }
    }

    private void requireSnapshotManager() {
        if (snapshotManager == null) {
            throw new IllegalStateException("Snapshot-native processing requires a ProcessingSnapshotManager");
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    private static TypeClassResolver defaultContractTypeResolver() {
        return new TypeClassResolver("blue.language.processor.model");
    }

    private static void registerRegistryContractTypes(
            ContractProcessorRegistry registry,
            TypeClassResolver resolver) {
        synchronized (resolver) {
            for (Map.Entry<String, Class<? extends Contract>> entry
                    : registry.registeredContractTypes().entrySet()) {
                resolver.register(entry.getKey(), entry.getValue());
            }
        }
    }

    private static void registerExactContractProcessor(
            ContractProcessorRegistry registry,
            TypeClassResolver resolver,
            String blueId,
            Node canonicalTypeNode,
            ContractProcessor<? extends Contract> processor) {
        Objects.requireNonNull(processor, "processor");
        Class<? extends Contract> contractType = processor.contractType();
        Lock configurationWrite = registry.configurationWriteLock();
        configurationWrite.lock();
        try {
            synchronized (resolver) {
                requireCompatibleTypeRegistration(resolver, blueId, contractType);
                // Registry validation (canonical BlueId and processor shape) is
                // mutation-free on failure. With both configuration locks held,
                // the following resolver registration cannot conflict.
                registry.register(blueId, canonicalTypeNode, processor);
                resolver.register(blueId, contractType);
            }
        } finally {
            configurationWrite.unlock();
        }
    }

    private static void requireCompatibleTypeRegistration(
            TypeClassResolver resolver,
            String blueId,
            Class<? extends Contract> contractType) {
        if (blueId == null || blueId.isEmpty()) {
            throw new IllegalArgumentException("blueId must not be empty");
        }
        if (contractType == null) {
            throw new IllegalArgumentException("clazz must not be null");
        }
        Class<?> existing = resolver.resolveClass(blueId);
        if (existing != null && !existing.equals(contractType)) {
            throw new IllegalStateException("Duplicate BlueId value: " + blueId);
        }
    }

    private void registerAnnotatedContractType(Class<? extends Contract> contractType) {
        if (contractType != null && contractType.isAnnotationPresent(TypeBlueId.class)) {
            contractTypeResolver.registerAnnotatedClass(contractType);
        }
    }

    public static final class Builder {
        private ContractProcessorRegistry contractRegistry = ContractProcessorRegistryBuilder.create().registerDefaults().build();
        private TypeClassResolver contractTypeResolver = defaultContractTypeResolver();
        private ConformanceEngine conformanceEngine;
        private ConformancePlannerOverride conformancePlannerOverride;
        private ProcessingSnapshotManager snapshotManager;
        private ContractMatchingService matchingService = new ContractMatchingService();
        private ProcessingMetricsSink metricsSink = ProcessingMetricsSink.NOOP;
        private GasSchedule gasSchedule = GasSchedule.contracts10();
        private Long gasLimit;
        private String runtimeRegistryIdentity = RuntimeBlueIds.REGISTRY_PACKAGE_IDENTITY;
        private ExternalDeliveryPlanDeriver externalDeliveryPlanDeriver =
                ExternalDeliveryPlanDeriver.unavailable();
        private ExternalDeliveryEvidenceVerifier deliveryEvidenceVerifier;
        private SubscriptionSurfaceValidator subscriptionSurfaceValidator;

        public Builder withRegistry(ContractProcessorRegistry registry) {
            this.contractRegistry = Objects.requireNonNull(registry, "registry");
            return this;
        }

        public Builder withContractTypeResolver(TypeClassResolver resolver) {
            this.contractTypeResolver = Objects.requireNonNull(resolver, "resolver");
            return this;
        }

        public Builder scanContractTypes(String packageName) {
            this.contractTypeResolver.scanPackage(packageName);
            return this;
        }

        public Builder registerContractType(String blueId, Class<? extends Contract> contractType) {
            this.contractTypeResolver.register(blueId, contractType);
            return this;
        }

        public Builder registerContractProcessor(ContractProcessor<? extends Contract> processor) {
            Objects.requireNonNull(processor, "processor");
            this.contractRegistry.register(processor);
            Class<? extends Contract> contractType = processor.contractType();
            if (contractType != null && contractType.isAnnotationPresent(TypeBlueId.class)) {
                this.contractTypeResolver.registerAnnotatedClass(contractType);
            }
            return this;
        }

        /**
         * Registers a processor mapping without supplying provider content.
         * Standalone initialization that needs this type fails with
         * {@link ProcessorErrorCategory#RuntimeExecutionFailure} unless a verified
         * provider-backed manager/Blue runtime is configured.
         */
        public Builder registerContractProcessor(String blueId, ContractProcessor<? extends Contract> processor) {
            Objects.requireNonNull(processor, "processor");
            this.contractRegistry.register(blueId, processor);
            this.contractTypeResolver.register(blueId, processor.contractType());
            return this;
        }

        public Builder registerContractProcessor(
                String blueId,
                Node canonicalTypeNode,
                ContractProcessor<? extends Contract> processor) {
            Objects.requireNonNull(processor, "processor");
            registerExactContractProcessor(
                    this.contractRegistry,
                    this.contractTypeResolver,
                    blueId,
                    canonicalTypeNode,
                    processor);
            return this;
        }

        public Builder withConformanceEngine(ConformanceEngine conformanceEngine) {
            this.conformanceEngine = conformanceEngine;
            return this;
        }

        public Builder withConformancePlannerOverride(ConformancePlannerOverride conformancePlannerOverride) {
            this.conformancePlannerOverride = conformancePlannerOverride;
            return this;
        }

        public Builder withSnapshotManager(ProcessingSnapshotManager snapshotManager) {
            this.snapshotManager = snapshotManager;
            return this;
        }

        public Builder withMatchingService(ContractMatchingService matchingService) {
            this.matchingService = Objects.requireNonNull(matchingService, "matchingService");
            return this;
        }

        public Builder withProcessingMetricsSink(ProcessingMetricsSink metricsSink) {
            this.metricsSink = metricsSink != null ? metricsSink : ProcessingMetricsSink.NOOP;
            return this;
        }

        public Builder withGasSchedule(GasSchedule gasSchedule) {
            this.gasSchedule = Objects.requireNonNull(gasSchedule, "gasSchedule");
            if (gasLimit != null && gasLimit > gasSchedule.maxProcessGas()) {
                throw new IllegalArgumentException(
                        "Configured gas limit exceeds manifest maxProcessGas");
            }
            return this;
        }

        public Builder withGasLimit(long gasLimit) {
            if (gasLimit < 0L || gasLimit > gasSchedule.maxProcessGas()) {
                throw new IllegalArgumentException(
                        "Gas limit must be between 0 and manifest maxProcessGas "
                                + gasSchedule.maxProcessGas());
            }
            this.gasLimit = gasLimit;
            return this;
        }

        public Builder withRuntimeRegistryIdentity(String identity) {
            if (identity == null || identity.isEmpty()) {
                throw new IllegalArgumentException(
                        "Runtime registry identity must not be empty");
            }
            this.runtimeRegistryIdentity = identity;
            return this;
        }

        public Builder withExternalDeliveryEvidenceVerifier(
                ExternalDeliveryEvidenceVerifier verifier) {
            this.deliveryEvidenceVerifier =
                    Objects.requireNonNull(verifier, "verifier");
            return this;
        }

        /**
         * Supplies the revision-complete environmental occurrence-plan
         * derivation used by both the two-input PROCESS API and explicit
         * evidence verification.
         */
        public Builder withExternalDeliveryPlanDeriver(
                ExternalDeliveryPlanDeriver deriver) {
            this.externalDeliveryPlanDeriver =
                    Objects.requireNonNull(deriver, "deriver");
            return this;
        }

        public Builder withSubscriptionSurfaceValidator(
                SubscriptionSurfaceValidator validator) {
            this.subscriptionSurfaceValidator =
                    Objects.requireNonNull(validator, "validator");
            return this;
        }

        public DocumentProcessor build() {
            return new DocumentProcessor(this);
        }
    }
}
