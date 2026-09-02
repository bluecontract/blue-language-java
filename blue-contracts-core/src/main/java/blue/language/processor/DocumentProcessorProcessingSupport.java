package blue.language.processor;

import blue.language.identity.NodeToBlueIdInput;
import blue.language.model.Node;
import blue.language.merge.ResolvedSnapshot;
import blue.language.runtime.LanguageRuntimeAccess;
import blue.language.snapshot.FrozenNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import static blue.language.processor.ProcessingInputAdmission.PROCESSING_EVENT_LABEL;
import static blue.language.processor.ProcessingInputAdmission.PROCESSING_ROOT_LABEL;

/** Shared evidence, admission, and result mechanics for PROCESS entry points. */
final class DocumentProcessorProcessingSupport {

    private static final String INVALID_EXTERNAL_DELIVERY_MESSAGE =
            "Invalid external delivery evidence";
    private static final String INCOMPLETE_DELIVERY_PLAN_MESSAGE =
            "External delivery plan is not certified complete";
    private static final String MISSING_SNAPSHOT_MANAGER_MESSAGE =
            "Snapshot-native processing requires a ProcessingSnapshotManager";

    private final DocumentProcessor processor;

    DocumentProcessorProcessingSupport(DocumentProcessor processor) {
        this.processor = processor;
    }

    ProcessingInputAdmission admission() {
        return new ProcessingInputAdmission(
                processor.snapshotManager());
    }

    ProcessingInputAdmission admission(
            ProcessorInvocationServices services) {
        return new ProcessingInputAdmission(
                Objects.requireNonNull(services, "services")
                        .snapshotManager());
    }

    ProcessingSnapshotManager requireSnapshotManager() {
        ProcessingSnapshotManager manager =
                processor.snapshotManager();
        if (manager == null) {
            throw new IllegalStateException(
                    MISSING_SNAPSHOT_MANAGER_MESSAGE);
        }
        return manager;
    }

    void requireProcessableEvent(Node event) {
        admission().requireProcessableTopLevel(
                event, PROCESSING_EVENT_LABEL);
    }

    Node requireProcessableSnapshotRoot(ResolvedSnapshot snapshot) {
        Node selectedRoot = Objects.requireNonNull(
                snapshot, "snapshot").sourceRoot();
        admission().requireProcessableTopLevel(
                selectedRoot, PROCESSING_ROOT_LABEL);
        return selectedRoot;
    }

    VerifiedExecutionEvidence deriveExternalDeliveryEvidence(
            Node document,
            Node event,
            SourceIdentityBinding sourceIdentities) {
        ExternalDeliveryPlan plan =
                deriveExternalDeliveryPlan(document, event);
        return bindAndVerifyDerived(
                document, event, sourceIdentities, plan);
    }

    VerifiedExecutionEvidence bindAndVerifyDerived(
            Node document,
            Node event,
            SourceIdentityBinding sourceIdentities,
            ExternalDeliveryPlan plan) {
        SourceIdentityBinding identities = Objects.requireNonNull(
                sourceIdentities, "sourceIdentities");
        VerifiedExecutionEvidence evidence = plan.bind(
                identities.rootBlueId(),
                identities.eventBlueId(),
                processor.runtimeRegistryIdentity());
        evidence.revalidateDerived(
                document,
                event,
                identities.rootBlueId(),
                identities.eventBlueId(),
                processor.runtimeRegistryIdentity(),
                processor.deliveryEvidenceVerifier(),
                plan);
        return evidence;
    }

    VerifiedExecutionEvidence verifySuppliedPlan(
            Node document,
            Node event,
            ExternalDeliveryPlan plan,
            VerifiedExecutionEvidence evidence,
            SourceIdentityBinding sourceIdentities,
            ProcessorInvocationServices services) {
        SourceIdentityBinding identities = Objects.requireNonNull(
                sourceIdentities, "sourceIdentities");
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(evidence, "evidence")
                .revalidateBinding(
                        identities.rootBlueId(),
                        identities.eventBlueId(),
                        services.runtimeRegistryIdentity());
        establishRequiredExactResources(plan, services);
        ExternalDeliveryEvidenceVerifier verifier =
                services.deliveryEvidenceVerifier();
        if (verifier instanceof RootExternalDeliveryEvidenceVerifier) {
            ((RootExternalDeliveryEvidenceVerifier) verifier)
                    .verifyDerived(
                            document,
                            event,
                            evidence,
                            plan,
                            services.externalPlanVerificationSessions(
                                    event,
                                    identities.eventBlueId()));
        } else {
            verifier.verifyDerived(
                    document, event, evidence, plan);
        }
        return evidence;
    }

    /**
     * Establishes the plan's declared exact-resource closure through this
     * invocation's isolated provider domain before semantic execution.
     */
    private void establishRequiredExactResources(
            ExternalDeliveryPlan plan,
            ProcessorInvocationServices services) {
        List<String> required = new ArrayList<>(
                plan.requiredExactNodeBlueIds());
        Collections.sort(required);
        ProcessingSnapshotManager manager =
                Objects.requireNonNull(
                        services.snapshotManager(),
                        "invocation snapshotManager");
        for (String blueId : required) {
            FrozenNode established = manager.materializeVerifiedExactReference(
                    FrozenNode.fromNode(new Node().blueId(blueId)));
            if (established == null) {
                throw ExternalEvidenceVerificationSupport.invalid(
                        "Required exact provider content is definitively "
                                + "absent for " + blueId);
            }
        }
    }

    ExternalDeliveryPlan deriveExternalDeliveryPlan(
            Node document,
            Node event) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(event, "event");
        ExternalDeliveryEvidenceVerifier verifier =
                processor.deliveryEvidenceVerifier();
        ExternalDeliveryPlan plan =
                verifier instanceof RootExternalDeliveryEvidenceVerifier
                        ? ((RootExternalDeliveryEvidenceVerifier) verifier)
                                .derivePlan(document, event)
                        : processor.externalDeliveryPlanDeriver().derive(
                                document.clone(), event.clone());
        if (plan == null || !plan.exactRuntimeState()) {
            throw new InvalidExecutionEvidenceException(
                    INCOMPLETE_DELIVERY_PLAN_MESSAGE);
        }
        return plan;
    }

    /**
     * Establishes canonical Source identities before admission changes either
     * semantic input's representation.
     */
    SourceIdentityBinding sourceIdentities(
            Node rootSource,
            Node eventSource,
            LanguageRuntimeAccess languageRuntime,
            ProcessingSnapshotManager snapshotManager) {
        Node checkedRoot = Objects.requireNonNull(
                rootSource, "rootSource");
        Node checkedEvent = Objects.requireNonNull(
                eventSource, "eventSource");
        SourceIdentityFieldPlan rootFields = sourceIdentityFieldPlan(
                checkedRoot,
                snapshotManager,
                true);
        SourceIdentityFieldPlan eventFields = sourceIdentityFieldPlan(
                checkedEvent,
                snapshotManager,
                false);
        return new SourceIdentityBinding(
                sourceBlueId(
                        checkedRoot,
                        languageRuntime,
                        snapshotManager,
                        "Processing Root identity",
                        rootFields),
                sourceBlueId(
                        checkedEvent,
                        languageRuntime,
                        snapshotManager,
                        "Processing Event identity",
                        eventFields));
    }

    /** Uses an already-resolved snapshot's authoritative Source identity. */
    SourceIdentityBinding sourceIdentities(
            ResolvedSnapshot rootSnapshot,
            Node eventSource,
            LanguageRuntimeAccess languageRuntime,
            ProcessingSnapshotManager snapshotManager) {
        ResolvedSnapshot checkedRoot = Objects.requireNonNull(
                rootSnapshot, "rootSnapshot");
        SourceIdentityFieldPlan rootFields = sourceIdentityFieldPlan(
                checkedRoot.sourceRoot(),
                snapshotManager,
                true);
        Node checkedEvent = Objects.requireNonNull(
                eventSource, "eventSource");
        SourceIdentityFieldPlan eventFields = sourceIdentityFieldPlan(
                checkedEvent,
                snapshotManager,
                false);
        return new SourceIdentityBinding(
                // A target-limited snapshot retains exact Source but has no
                // whole-document canonical identity to read directly. A full
                // generic Language snapshot is likewise not authoritative
                // for runtime-owned exact fields, which must first be
                // canonicalized as independent Source values.
                checkedRoot.hasCanonicalIdentity()
                        && rootFields.exactFieldPaths.isEmpty()
                        ? checkedRoot.blueId()
                        : sourceBlueId(
                                checkedRoot.sourceRoot(),
                                languageRuntime,
                                snapshotManager,
                                "Processing Root identity",
                                rootFields),
                sourceBlueId(
                        checkedEvent,
                        languageRuntime,
                        snapshotManager,
                        "Processing Event identity",
                        eventFields));
    }

    private String sourceBlueId(
            Node source,
            LanguageRuntimeAccess languageRuntime,
            ProcessingSnapshotManager snapshotManager,
            String purpose,
            SourceIdentityFieldPlan fieldPlan) {
        if (source.isReferenceOnly()) {
            return source.getBlueId();
        }
        /*
         * The processor snapshot manager owns the active registered-extension
         * provider graph. The generic Language runtime need not know those
         * contract types and therefore is not an equivalent identity oracle.
         */
        if (snapshotManager != null) {
            return CanonicalIdentityEvidence
                    .sourceBlueIdWithCanonicalExactFields(
                    source,
                    snapshotManager,
                    purpose,
                    fieldPlan.exactFieldPaths,
                    fieldPlan.executableBodyPaths);
        }
        if (languageRuntime != null) {
            return languageRuntime.calculateSourceDocumentBlueId(
                    NodeToBlueIdInput.stripResolvedBlueIdMetadata(
                            source.clone()));
        }
        return CanonicalIdentityEvidence.sourceBlueId(
                source, null, purpose);
    }

    private SourceIdentityFieldPlan sourceIdentityFieldPlan(
            Node source,
            ProcessingSnapshotManager snapshotManager,
            boolean includeEnclosingScopeTypeContracts) {
        if (snapshotManager == null || source.isReferenceOnly()) {
            return SourceIdentityFieldPlan.empty();
        }
        Set<String> authoredPaths =
                ExecutableBodyPathCatalog.authoredNodePaths(source);
        if (includeEnclosingScopeTypeContracts) {
            return new SourceIdentityFieldPlan(
                    ExecutableBodyPathCatalog
                    .fromNodeIncludingTypeContractsForSourceIdentity(
                            source,
                            authoredPaths,
                            processor.registry().exactSourceFieldsByType(),
                            snapshotManager),
                    ExecutableBodyPathCatalog
                    .fromNodeIncludingTypeContractsForSourceIdentity(
                            source,
                            authoredPaths,
                            processor.registry().executableBodyFieldsByType(),
                            snapshotManager));
        }
        return new SourceIdentityFieldPlan(
                ExecutableBodyPathCatalog
                .fromNodeDirectContractsForSourceIdentity(
                        source,
                        authoredPaths,
                        processor.registry().exactSourceFieldsByType(),
                        snapshotManager),
                ExecutableBodyPathCatalog
                .fromNodeDirectContractsForSourceIdentity(
                        source,
                        authoredPaths,
                        processor.registry().executableBodyFieldsByType(),
                        snapshotManager));
    }

    ProcessingInputAdmission.AdmittedNode admitDeliveryScopes(
            ProcessingInputAdmission admission,
            ProcessingInputAdmission.AdmittedNode admittedRoot,
            List<ExternalDeliverySnapshot> deliveries) {
        List<String> scopePaths = new ArrayList<>();
        for (ExternalDeliverySnapshot delivery : deliveries) {
            scopePaths.add(delivery.scopePath());
        }
        return admission.materializeScopePaths(
                admittedRoot, scopePaths);
    }

    DocumentProcessingResult processAdmitted(
            ProcessingInputAdmission admission,
            ProcessingInputAdmission.AdmittedNode admittedRoot,
            Node event,
            VerifiedExecutionEvidence evidence) {
        if (admittedRoot.wasMaterialized()) {
            return ProcessorEngine.processDocument(
                    processor,
                    admission.deferredSnapshot(admittedRoot),
                    event,
                    evidence);
        }
        return ProcessorEngine.processDocument(
                processor, admittedRoot.node(), event, evidence);
    }

    DocumentProcessingResult processAdmitted(
            ProcessingInputAdmission admission,
            ProcessingInputAdmission.AdmittedNode admittedRoot,
            Node event,
            VerifiedExecutionEvidence evidence,
            ProcessorInvocationServices services) {
        if (admittedRoot.wasMaterialized()) {
            return ProcessorEngine.processDocument(
                    services,
                    admission.deferredSnapshot(admittedRoot),
                    event,
                    evidence);
        }
        return ProcessorEngine.processDocument(
                services,
                admittedRoot.node(),
                event,
                evidence);
    }

    ProcessingDebugResult processAdmittedWithTrace(
            ProcessingInputAdmission admission,
            ProcessingInputAdmission.AdmittedNode admittedRoot,
            Node event,
            VerifiedExecutionEvidence evidence) {
        if (admittedRoot.wasMaterialized()) {
            return ProcessorEngine.processDocumentWithTrace(
                    processor,
                    admission.deferredSnapshot(admittedRoot),
                    event,
                    evidence);
        }
        return ProcessorEngine.processDocumentWithTrace(
                processor, admittedRoot.node(), event, evidence);
    }

    ProcessingDebugResult processAdmittedWithTrace(
            ProcessingInputAdmission admission,
            ProcessingInputAdmission.AdmittedNode admittedRoot,
            Node event,
            VerifiedExecutionEvidence evidence,
            ProcessorInvocationServices services) {
        if (admittedRoot.wasMaterialized()) {
            return ProcessorEngine.processDocumentWithTrace(
                    services,
                    admission.deferredSnapshot(admittedRoot),
                    event,
                    evidence);
        }
        return ProcessorEngine.processDocumentWithTrace(
                services,
                admittedRoot.node(),
                event,
                evidence);
    }

    ProcessAttemptResult completeAttempt(
            Node originalDocument,
            ProcessingInputAdmission admission,
            ProcessingInputAdmission.AdmittedNode admittedRoot,
            Node event,
            VerifiedExecutionEvidence evidence,
            SourceIdentityBinding sourceIdentities,
            ExternalDeliveryPlan derivedPlan) {
        SourceIdentityBinding identities = Objects.requireNonNull(
                sourceIdentities, "sourceIdentities");
        try {
            evidence.revalidateBinding(
                    identities.rootBlueId(),
                    identities.eventBlueId(),
                    processor.runtimeRegistryIdentity());
            List<String> missing =
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
                    identities.rootBlueId(),
                    identities.eventBlueId(),
                    processor.runtimeRegistryIdentity(),
                    processor.deliveryEvidenceVerifier(),
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
            return invalidAttempt(originalDocument, exception);
        }
    }

    ProcessAttemptResult needsResources(
            ExecutionEvidenceUnavailableException exception) {
        if (exception.requiredExactBlueIds().isEmpty()) {
            throw exception;
        }
        return ProcessAttemptResult.needsResources(
                exception.requiredExactBlueIds());
    }

    ProcessAttemptResult invalidAttempt(
            Node document,
            InvalidExecutionEvidenceException exception) {
        return ProcessAttemptResult.complete(
                DocumentProcessingResult.nonCommitting(
                        document,
                        0L,
                        ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                        ProcessorDiagnostic.of(
                                exception.errorCategory(),
                                exception.getMessage())));
    }

    DocumentProcessingResult invalidExternalDeliveryResult(
            Node document,
            InvalidExecutionEvidenceException exception) {
        return DocumentProcessingResult.nonCommitting(
                Objects.requireNonNull(document, "document"),
                0L,
                ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                ProcessorDiagnostic.of(
                        exception.errorCategory(),
                        ProcessorEngine.deterministicMessage(
                                exception,
                                INVALID_EXTERNAL_DELIVERY_MESSAGE)));
    }

    DocumentProcessingResult subscriptionSurfaceInvalidResult(
            Node document,
            SubscriptionSurfaceInvalidException exception) {
        return DocumentProcessingResult.nonCommitting(
                Objects.requireNonNull(document, "document"),
                0L,
                ProcessorStatus.SUBSCRIPTION_SURFACE_INVALID,
                exception.diagnostic());
    }

    DocumentProcessingResult portableLimitResult(
            Node document,
            PortableLimitExceededException exception) {
        return DocumentProcessingResult.nonCommitting(
                Objects.requireNonNull(document, "document"),
                0L,
                ProcessorStatus.PORTABLE_LIMIT_EXCEEDED,
                exception.diagnostic());
    }

    PlatformProcessingResult platformFailure(
            VerifiedExecutionEvidence evidence,
            DocumentProcessingResult result) {
        return new PlatformProcessingResult(
                result,
                PlatformCommitCompanion.of(
                        Objects.requireNonNull(evidence, "evidence"),
                        result,
                        SubscriptionDelta.empty()));
    }

    PlatformProcessingResult platformResult(ProcessingDebugResult debug) {
        PlatformCommitCompanion companion =
                debug.platformCommitCompanion();
        if (companion == null) {
            throw new IllegalStateException(
                    "Revision-bound execution produced no platform commit companion");
        }
        return new PlatformProcessingResult(
                debug.processResult(), companion);
    }

    /** Exact semantic-input identities established at the Source boundary. */
    static final class SourceIdentityBinding {
        private final String rootBlueId;
        private final String eventBlueId;

        private SourceIdentityBinding(
                String rootBlueId,
                String eventBlueId) {
            this.rootBlueId = Objects.requireNonNull(
                    rootBlueId, "rootBlueId");
            this.eventBlueId = Objects.requireNonNull(
                    eventBlueId, "eventBlueId");
        }

        String rootBlueId() {
            return rootBlueId;
        }

        String eventBlueId() {
            return eventBlueId;
        }
    }

    /** Runtime-owned exact fields and their executable-body subset. */
    private static final class SourceIdentityFieldPlan {
        private final Set<String> exactFieldPaths;
        private final Set<String> executableBodyPaths;

        private SourceIdentityFieldPlan(
                Set<String> exactFieldPaths,
                Set<String> executableBodyPaths) {
            this.exactFieldPaths = Collections.unmodifiableSet(
                    new LinkedHashSet<>(Objects.requireNonNull(
                            exactFieldPaths, "exactFieldPaths")));
            this.executableBodyPaths = Collections.unmodifiableSet(
                    new LinkedHashSet<>(Objects.requireNonNull(
                            executableBodyPaths, "executableBodyPaths")));
            if (!this.exactFieldPaths.containsAll(
                    this.executableBodyPaths)) {
                throw new IllegalStateException(
                        "Executable body paths are not exact Source fields");
            }
        }

        private static SourceIdentityFieldPlan empty() {
            return new SourceIdentityFieldPlan(
                    Collections.<String>emptySet(),
                    Collections.<String>emptySet());
        }
    }
}
