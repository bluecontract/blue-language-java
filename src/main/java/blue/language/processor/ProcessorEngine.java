package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.Contract;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.PointerUtils;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.BlueIdReferenceValidator;
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
        DocumentProcessingResult invalid = validateProcessingDocument(document);
        if (invalid != null) {
            return invalid;
        }
        if (isInitialized(owner, document)) {
            throw new IllegalStateException("Document already initialized");
        }
        Execution execution = null;
        try {
            execution = new Execution(owner, document.clone());
            execution.initializeScope("/", true);
        } catch (RunTerminationException ignored) {
            // Initialization run terminated early (e.g., graceful root termination).
            if (execution == null) {
                return DocumentProcessingResult.runtimeFatal(
                        document.clone(),
                        "Initialization terminated before run state was available",
                        ProcessorErrorCategory.RuntimeExecutionFailure);
            }
        } catch (MustUnderstandFailureException ex) {
            return DocumentProcessingResult.capabilityFailure(document.clone(), ex.getMessage(), ex.errorCategory());
        } catch (IllegalArgumentException ex) {
            ProcessorErrorCategory category =
                    ScopeIdentityErrorMapper.from(ex);
            if (category
                    == ProcessorErrorCategory.ProviderUnavailable
                    || category
                    == ProcessorErrorCategory.ProviderBlueIdMismatch) {
                throw ex;
            }
            return DocumentProcessingResult.capabilityFailure(
                    document.clone(),
                    deterministicMessage(
                            ex, "Invalid initialization document"),
                    ProcessorErrorCategory.InvalidProcessingDocument);
        }
        return execution.result();
    }

    static DocumentProcessingResult initializeDocument(DocumentProcessor owner, ResolvedSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        DocumentProcessingResult invalid = validateProcessingDocument(snapshot.frozenResolvedRoot());
        if (invalid != null) {
            return invalid.withSnapshot(snapshot);
        }
        if (isInitialized(owner, snapshot)) {
            throw new IllegalStateException("Document already initialized");
        }
        Execution execution = null;
        try {
            execution = new Execution(owner, snapshot);
            execution.initializeScope("/", true);
        } catch (RunTerminationException ignored) {
            // Initialization run terminated early (e.g., graceful root termination).
            if (execution == null) {
                return DocumentProcessingResult.runtimeFatal(
                        snapshot.resolvedRoot(),
                        "Initialization terminated before run state was available",
                        ProcessorErrorCategory.RuntimeExecutionFailure)
                        .withSnapshot(snapshot);
            }
        } catch (MustUnderstandFailureException ex) {
            return DocumentProcessingResult.capabilityFailure(snapshot.resolvedRoot(), ex.getMessage(), ex.errorCategory());
        } catch (IllegalArgumentException ex) {
            ProcessorErrorCategory category =
                    ScopeIdentityErrorMapper.from(ex);
            if (category
                    == ProcessorErrorCategory.ProviderUnavailable
                    || category
                    == ProcessorErrorCategory.ProviderBlueIdMismatch) {
                throw ex;
            }
            return DocumentProcessingResult.capabilityFailure(
                    snapshot.resolvedRoot(),
                    deterministicMessage(
                            ex, "Invalid initialization document"),
                    ProcessorErrorCategory.InvalidProcessingDocument)
                    .withSnapshot(snapshot);
        }
        return execution.result();
    }

    static DocumentProcessingResult processDocument(DocumentProcessor owner, Node document, Node event) {
        return processDocument(owner, document, event, null);
    }

    static DocumentProcessingResult processDocument(DocumentProcessor owner,
                                                     Node document,
                                                     Node event,
                                                     VerifiedExecutionEvidence evidence) {
        return processDocumentWithTrace(owner, document, event, evidence).processResult();
    }

    static ProcessingDebugResult processDocumentWithTrace(DocumentProcessor owner,
                                                          Node document,
                                                          Node event,
                                                          VerifiedExecutionEvidence evidence) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(event, "event");
        ProcessingMetricsSink metrics = owner.metricsSink();
        long processStart = System.nanoTime();
        long preprocessStart = System.nanoTime();
        Execution execution = null;
        try {
            DocumentProcessingResult invalid = validateProcessingDocument(document);
            if (invalid != null) {
                return new ProcessingDebugResult(invalid, ProcessingConformanceTrace.empty());
            }
            Node cloned = document.clone();
            execution = new Execution(owner, cloned, event, evidence);
            execution.runtime().chargeProcessInvocation();
            if (execution.admitDirectRootState()) {
                metrics.addEventPreprocessNanos(
                        System.nanoTime() - preprocessStart);
                return execution.debugResult();
            }
            execution.admitEvidence();
            metrics.addEventPreprocessNanos(System.nanoTime() - preprocessStart);
            if (!execution.hasExecutionEvidence()) {
                throw new InvalidExecutionEvidenceException(
                        "PROCESS requires a complete external delivery plan");
            }
            execution.processEvidenceDeliveries(event);
            execution.finalizeSuccessfulRun();
            return execution.debugResult();
        } catch (RunTerminationException ignored) {
            // A graceful Root termination or deterministic run failure ends work.
        } catch (GasLimitExceededException ex) {
            if (execution == null) {
                DocumentProcessingResult result = DocumentProcessingResult.nonCommitting(
                        document.clone(),
                        ex.admittedGas(),
                        ProcessorStatus.GAS_LIMIT_EXCEEDED,
                        ex.diagnostic());
                return new ProcessingDebugResult(result, ProcessingConformanceTrace.empty());
            }
            execution.fail(ProcessorStatus.GAS_LIMIT_EXCEEDED, ex.diagnostic());
        } catch (PortableLimitExceededException ex) {
            if (execution == null) {
                DocumentProcessingResult result = DocumentProcessingResult.nonCommitting(
                        document.clone(),
                        0L,
                        ProcessorStatus.PORTABLE_LIMIT_EXCEEDED,
                        ex.diagnostic());
                return new ProcessingDebugResult(result, ProcessingConformanceTrace.empty());
            }
            execution.fail(ProcessorStatus.PORTABLE_LIMIT_EXCEEDED, ex.diagnostic());
        } catch (SubscriptionSurfaceInvalidException ex) {
            if (execution == null) {
                DocumentProcessingResult result = DocumentProcessingResult.nonCommitting(
                        document.clone(),
                        0L,
                        ProcessorStatus.SUBSCRIPTION_SURFACE_INVALID,
                        ex.diagnostic());
                return new ProcessingDebugResult(result, ProcessingConformanceTrace.empty());
            }
            execution.fail(
                    ProcessorStatus.SUBSCRIPTION_SURFACE_INVALID,
                    ex.diagnostic());
        } catch (InvalidExecutionEvidenceException ex) {
            if (execution == null) {
                DocumentProcessingResult result =
                        DocumentProcessingResult.nonCommitting(
                                document.clone(),
                                0L,
                                ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                                ProcessorDiagnostic.of(
                                        ProcessorErrorCategory
                                                .InvalidExternalChannelSnapshot,
                                        deterministicMessage(
                                                ex,
                                                "Invalid external delivery evidence")));
                return new ProcessingDebugResult(
                        result, ProcessingConformanceTrace.empty());
            }
            execution.fail(
                    ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                    ProcessorDiagnostic.of(
                            ProcessorErrorCategory
                                    .InvalidExternalChannelSnapshot,
                            deterministicMessage(
                                    ex,
                                    "Invalid external delivery evidence")));
        } catch (MustUnderstandFailureException ex) {
            metrics.addProcessDocumentNanos(System.nanoTime() - processStart);
            if (execution == null) {
                DocumentProcessingResult result = DocumentProcessingResult.capabilityFailure(
                        document.clone(), ex.getMessage(), ex.errorCategory());
                return new ProcessingDebugResult(result, ProcessingConformanceTrace.empty());
            }
            execution.fail(ProcessorStatus.CAPABILITY_FAILURE,
                    ProcessorDiagnostic.of(ex.errorCategory(), ex.getMessage()));
        } catch (RuntimeException ex) {
            ProcessorErrorCategory providerCategory =
                    ScopeIdentityErrorMapper.from(ex);
            if (providerCategory
                    == ProcessorErrorCategory.ProviderUnavailable
                    || providerCategory
                    == ProcessorErrorCategory.ProviderBlueIdMismatch) {
                throw ex;
            }
            if (execution == null) {
                DocumentProcessingResult result = DocumentProcessingResult.nonCommitting(
                        document.clone(),
                        0L,
                        ProcessorStatus.RUNTIME_FATAL,
                        ProcessorDiagnostic.of(
                                ProcessorErrorCategory.RuntimeExecutionFailure,
                                deterministicMessage(ex, "Runtime processing failed")));
                return new ProcessingDebugResult(result, ProcessingConformanceTrace.empty());
            }
            execution.fail(ProcessorStatus.RUNTIME_FATAL,
                    ProcessorDiagnostic.of(
                            execution.fatalCategory(
                                    ex, ProcessorErrorCategory.RuntimeExecutionFailure),
                            deterministicMessage(ex, "Runtime processing failed")));
        }
        long postStart = System.nanoTime();
        try {
            return execution.debugResult();
        } finally {
            metrics.addPostProcessingNanos(System.nanoTime() - postStart);
            metrics.addProcessDocumentNanos(System.nanoTime() - processStart);
        }
    }

    static String deterministicMessage(Throwable throwable, String fallback) {
        String message = throwable != null ? throwable.getMessage() : null;
        return message != null && !message.isEmpty() ? message : fallback;
    }

    static DocumentProcessingResult processDocument(DocumentProcessor owner,
                                                     ResolvedSnapshot snapshot,
                                                     Node event) {
        return processDocument(owner, snapshot, event, null);
    }

    static DocumentProcessingResult processDocument(DocumentProcessor owner,
                                                     ResolvedSnapshot snapshot,
                                                     Node event,
                                                     VerifiedExecutionEvidence evidence) {
        return processDocumentWithTrace(
                owner, snapshot, event, evidence).processResult();
    }

    static ProcessingDebugResult processDocumentWithTrace(
            DocumentProcessor owner,
            ResolvedSnapshot snapshot,
            Node event,
            VerifiedExecutionEvidence evidence) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(event, "event");
        ProcessingMetricsSink metrics = owner.metricsSink();
        long processStart = System.nanoTime();
        long preprocessStart = System.nanoTime();
        Execution execution = null;
        try {
            DocumentProcessingResult invalid = validateProcessingDocument(snapshot.frozenResolvedRoot());
            if (invalid != null) {
                return new ProcessingDebugResult(
                        nonCommittingSnapshotResult(
                                snapshot,
                                invalid.totalGas(),
                                invalid.status(),
                                invalid.diagnostic()),
                        ProcessingConformanceTrace.empty());
            }
            execution = new Execution(owner, snapshot, event, evidence);
            execution.runtime().chargeProcessInvocation();
            if (execution.admitDirectRootState()) {
                metrics.addEventPreprocessNanos(
                        System.nanoTime() - preprocessStart);
                return execution.debugResult();
            }
            execution.admitEvidence();
            metrics.addEventPreprocessNanos(System.nanoTime() - preprocessStart);
            if (!execution.hasExecutionEvidence()) {
                throw new InvalidExecutionEvidenceException(
                        "PROCESS requires a complete external delivery plan");
            }
            execution.processEvidenceDeliveries(event);
            execution.finalizeSuccessfulRun();
        } catch (RunTerminationException ignored) {
            // Processing terminated early; result still returned.
        } catch (GasLimitExceededException ex) {
            if (execution == null) {
                return new ProcessingDebugResult(
                        nonCommittingSnapshotResult(
                                snapshot,
                                ex.admittedGas(),
                                ProcessorStatus.GAS_LIMIT_EXCEEDED,
                                ex.diagnostic()),
                        ProcessingConformanceTrace.empty());
            }
            execution.fail(
                    ProcessorStatus.GAS_LIMIT_EXCEEDED,
                    ex.diagnostic());
        } catch (PortableLimitExceededException ex) {
            if (execution == null) {
                return new ProcessingDebugResult(
                        nonCommittingSnapshotResult(
                                snapshot,
                                0L,
                                ProcessorStatus.PORTABLE_LIMIT_EXCEEDED,
                                ex.diagnostic()),
                        ProcessingConformanceTrace.empty());
            }
            execution.fail(
                    ProcessorStatus.PORTABLE_LIMIT_EXCEEDED,
                    ex.diagnostic());
        } catch (SubscriptionSurfaceInvalidException ex) {
            if (execution == null) {
                return new ProcessingDebugResult(
                        nonCommittingSnapshotResult(
                                snapshot,
                                0L,
                                ProcessorStatus.SUBSCRIPTION_SURFACE_INVALID,
                                ex.diagnostic()),
                        ProcessingConformanceTrace.empty());
            }
            execution.fail(
                    ProcessorStatus.SUBSCRIPTION_SURFACE_INVALID,
                    ex.diagnostic());
        } catch (InvalidExecutionEvidenceException ex) {
            if (execution == null) {
                return new ProcessingDebugResult(
                        nonCommittingSnapshotResult(
                                snapshot,
                                0L,
                                ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                                ProcessorDiagnostic.of(
                                        ProcessorErrorCategory
                                                .InvalidExternalChannelSnapshot,
                                        deterministicMessage(
                                                ex,
                                                "Invalid external delivery evidence"))),
                        ProcessingConformanceTrace.empty());
            }
            execution.fail(
                    ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                    ProcessorDiagnostic.of(
                            ProcessorErrorCategory
                                    .InvalidExternalChannelSnapshot,
                            deterministicMessage(
                                    ex,
                                    "Invalid external delivery evidence")));
        } catch (MustUnderstandFailureException ex) {
            metrics.addProcessDocumentNanos(System.nanoTime() - processStart);
            if (execution == null) {
                return new ProcessingDebugResult(
                        nonCommittingSnapshotResult(
                                snapshot,
                                0L,
                                ProcessorStatus.CAPABILITY_FAILURE,
                                ProcessorDiagnostic.of(
                                        ex.errorCategory(),
                                        ex.getMessage())),
                        ProcessingConformanceTrace.empty());
            }
            execution.fail(
                    ProcessorStatus.CAPABILITY_FAILURE,
                    ProcessorDiagnostic.of(
                            ex.errorCategory(), ex.getMessage()));
        } catch (RuntimeException ex) {
            ProcessorErrorCategory providerCategory =
                    ScopeIdentityErrorMapper.from(ex);
            if (providerCategory
                    == ProcessorErrorCategory.ProviderUnavailable
                    || providerCategory
                    == ProcessorErrorCategory.ProviderBlueIdMismatch) {
                throw ex;
            }
            if (execution == null) {
                return new ProcessingDebugResult(
                        nonCommittingSnapshotResult(
                                snapshot,
                                0L,
                                ProcessorStatus.RUNTIME_FATAL,
                                ProcessorDiagnostic.of(
                                        ProcessorErrorCategory
                                                .RuntimeExecutionFailure,
                                        deterministicMessage(
                                                ex,
                                                "Runtime processing failed"))),
                        ProcessingConformanceTrace.empty());
            }
            execution.fail(
                    ProcessorStatus.RUNTIME_FATAL,
                    ProcessorDiagnostic.of(
                            execution.fatalCategory(
                                    ex,
                                    ProcessorErrorCategory
                                            .RuntimeExecutionFailure),
                            deterministicMessage(
                                    ex,
                                    "Runtime processing failed")));
        }
        long postStart = System.nanoTime();
        try {
            return execution.debugResult();
        } finally {
            metrics.addPostProcessingNanos(System.nanoTime() - postStart);
            metrics.addProcessDocumentNanos(System.nanoTime() - processStart);
        }
    }

    private static DocumentProcessingResult nonCommittingSnapshotResult(
            ResolvedSnapshot snapshot,
            long admittedGas,
            ProcessorStatus status,
            ProcessorDiagnostic diagnostic) {
        return DocumentProcessingResult.nonCommitting(
                snapshot.canonicalRoot(),
                admittedGas,
                status,
                diagnostic)
                .withSnapshot(snapshot);
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
        if (document.isReferenceOnly()) {
            return DocumentProcessingResult.invalidProcessingDocument(document.clone(),
                    "Invalid Processing Document: Root must be concrete");
        }
        try {
            BlueIdReferenceValidator.validate(document);
        } catch (IllegalArgumentException exception) {
            return DocumentProcessingResult.invalidProcessingDocument(
                    document.clone(),
                    deterministicMessage(
                            exception,
                            "Invalid Processing Document reference"));
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
        if (document.isReferenceOnly()) {
            return DocumentProcessingResult.invalidProcessingDocument(document.toNode(),
                    "Invalid Processing Document: Root must be concrete");
        }
        try {
            BlueIdReferenceValidator.validate(document.toNode());
        } catch (IllegalArgumentException exception) {
            return DocumentProcessingResult.invalidProcessingDocument(
                    document.toNode(),
                    deterministicMessage(
                            exception,
                            "Invalid Processing Document reference"));
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
                typed);
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
        String relativeSourceScopePath =
                relativizePointer(
                        scopePath, data.originScope());
        Node event = new Node().type(new Node().blueId(RuntimeBlueIds.DOCUMENT_UPDATE));
        event.properties("op", new Node().value(data.op().name().toLowerCase()));
        event.properties("path", new Node().value(relativePath));
        event.properties("beforePresent", new Node().value(data.beforePresent()));
        if (data.beforePresent()) {
            event.properties("before", data.before().clone());
        }
        event.properties("afterPresent", new Node().value(data.afterPresent()));
        if (data.afterPresent()) {
            event.properties("after", data.after().clone());
        }
        event.properties(
                "sourceScopePath",
                new Node().value(
                        relativeSourceScopePath));
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

    static boolean hasDirectRootTerminationEntry(Node root) {
        Node contracts = root != null
                ? root.getContracts()
                : null;
        return contracts != null
                && contracts.getProperties() != null
                && contracts.getProperties().containsKey(
                ProcessorContractConstants.KEY_TERMINATED);
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
        if (cause == null || cause.isEmpty()) {
            throw new IllegalStateException(
                    "Processing Terminated Marker cause must be non-empty Text at " + pointer);
        }
        return new TerminationMarker(
                cause,
                stringProperty(marker, "reason"));
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
        final String cause;
        final String reason;

        TerminationMarker(String cause,
                          String reason) {
            this.cause = cause;
            this.reason = reason;
        }
    }

    static final class Execution {
        private final DocumentProcessor owner;
        private final DocumentProcessingRuntime runtime;
        private final Node inputDocument;
        private final ResolvedSnapshot inputSnapshot;
        private Node classificationDocument;
        private ResolvedSnapshot classificationSnapshot;
        private final Node processEventSource;
        private final ProcessEventSnapshotFactory processEventSnapshotFactory;
        private final Object processEventSnapshotLock = new Object();
        private final Map<String, ContractBundle> bundles = new LinkedHashMap<>();
        private final Set<String> cutOffScopes = new LinkedHashSet<>();
        private final Set<String> consumedCheckpointDomainProofs =
                new LinkedHashSet<>();
        private final CheckpointManager checkpointManager;
        private final TerminationService terminationService;
        private final ChannelRunner channelRunner;
        private final ScopeExecutor scopeExecutor;
        private final ContractRecognitionMeter
                contractRecognitionMeter;
        private volatile ProcessEventSnapshotState processEventSnapshotState;
        private volatile FrozenNode frozenProcessEvent;
        private RuntimeException processEventSnapshotFailure;
        private VerifiedExecutionEvidence executionEvidence;
        private ProcessorStatus failureStatus;
        private ProcessorDiagnostic failureDiagnostic;
        private boolean directRootTerminated;
        private boolean acceptedDelivery;
        private boolean staleDelivery;
        private boolean completedDelivery;
        private SubscriptionDelta subscriptionDelta = SubscriptionDelta.empty();
        private final Map<String, List<String>> evidenceInitializationPaths =
                new LinkedHashMap<>();

        Execution(DocumentProcessor owner, Node document) {
            this(owner, document, null);
        }

        Execution(DocumentProcessor owner, Node document, Node processEventSource) {
            this(owner, document, processEventSource, FrozenNode::fromResolvedNode);
        }

        Execution(DocumentProcessor owner,
                  Node document,
                  Node processEventSource,
                  VerifiedExecutionEvidence executionEvidence) {
            this(owner, document, processEventSource, FrozenNode::fromResolvedNode);
            this.executionEvidence = executionEvidence;
        }

        Execution(DocumentProcessor owner,
                  Node document,
                  Node processEventSource,
                  ProcessEventSnapshotFactory processEventSnapshotFactory) {
            this.owner = owner;
            this.inputDocument = document.clone();
            this.inputSnapshot = null;
            this.runtime = new DocumentProcessingRuntime(document,
                    owner.conformanceEngine(),
                    owner.conformancePlannerOverride(),
                    owner.snapshotManager(),
                    owner.metricsSink(),
                    owner.newGasMeter(),
                    owner.registry()
                            .executableBodyFieldsByType());
            this.contractRecognitionMeter =
                    new ContractRecognitionMeter(
                            runtime.gasMeter());
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
            this.inputDocument = snapshot.canonicalRoot();
            this.inputSnapshot = snapshot;
            this.runtime = new DocumentProcessingRuntime(snapshot,
                    owner.conformanceEngine(),
                    owner.conformancePlannerOverride(),
                    owner.snapshotManager(),
                    owner.metricsSink(),
                    owner.newGasMeter(),
                    owner.registry()
                            .executableBodyFieldsByType());
            this.contractRecognitionMeter =
                    new ContractRecognitionMeter(
                            runtime.gasMeter());
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

        Execution(DocumentProcessor owner,
                  ResolvedSnapshot snapshot,
                  Node processEventSource,
                  VerifiedExecutionEvidence executionEvidence) {
            this(owner,
                    snapshot,
                    processEventSource,
                    FrozenNode::fromResolvedNode);
            this.executionEvidence = executionEvidence;
        }

        void initializeScope(String scopePath, boolean chargeScopeEntry) {
            scopeExecutor.initializeScope(scopePath, chargeScopeEntry);
        }

        void preflightScope(String scopePath) {
            scopeExecutor.preflightEvidenceScope(scopePath);
        }

        void finalizeSuccessfulRun() {
            if (failureStatus == null && completedDelivery) {
                scopeExecutor.cleanupCheckpointState();
                SubscriptionSurfaceValidationContext.Builder validation =
                        SubscriptionSurfaceValidationContext.builder(
                                inputDocument,
                                runtime.document(),
                                runtime.changedPaths(),
                                owner.gasSchedule())
                                .snapshots(
                                        inputSnapshot,
                                        runtime.snapshot());
                if (executionEvidence != null) {
                    long revision =
                            executionEvidence.managedRootRevision();
                    if (revision == Long.MAX_VALUE) {
                        throw new SubscriptionSurfaceInvalidException(
                                "Committing Root revision overflows",
                                "/",
                                null);
                    }
                    validation.committingInterval(
                            executionEvidence.eventOrderKey(),
                            revision + 1L);
                    if (executionEvidence
                            .hasActiveSubscriptionIntervals()) {
                        validation.activeSubscriptionIntervals(
                                executionEvidence
                                        .activeSubscriptionIntervals());
                    }
                }
                subscriptionDelta =
                        owner.subscriptionSurfaceValidator().validate(
                                validation.build());
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("added", subscriptionDelta.added().size());
                details.put("removed", subscriptionDelta.removed().size());
                runtime.recordTrace(
                        ProcessingTraceRecord.Kind.SUBSCRIPTION_DELTA,
                        "/",
                        null,
                        null,
                        details,
                        null);
            }
        }

        SubscriptionDelta subscriptionDelta() {
            return subscriptionDelta;
        }

        boolean admitDirectRootState() {
            try {
                /*
                 * Contracts 1.0 §4.5 and §12.8 require direct terminated
                 * state to win before application-contract recognition.
                 * Reading through DocumentProcessingRuntime would create a
                 * resolved snapshot and could therefore demand an unsupported
                 * application type before this reserved direct state.
                 */
                TerminationMarker marker =
                        ProcessorEngine.terminationMarker(inputDocument, "/");
                if (marker == null) {
                    return false;
                }
                runtime.scope("/").finalizeTermination(marker.reason);
                directRootTerminated = true;
                return true;
            } catch (RuntimeException exception) {
                fail(ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                        ProcessorDiagnostic.of(
                                ProcessorErrorCategory.InvalidReservedRuntimeState,
                                deterministicMessage(exception,
                                        "Invalid direct Root terminated state")));
                return true;
            }
        }

        void admitEvidence() {
            if (executionEvidence == null) {
                return;
            }
            for (ExternalDeliverySnapshot delivery : executionEvidence.deliveries()) {
                runtime.chargeDeliverySnapshotEntry(
                        delivery.scopePath(), delivery.channelKey());
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("order", delivery.order());
                details.put("effectiveTypeBlueId", delivery.effectiveTypeBlueId());
                details.put("checkpointDomainBlueId", delivery.checkpointDomainBlueId());
                details.put("checkpointSubjectBlueId", delivery.checkpointSubjectBlueId());
                runtime.recordTrace(ProcessingTraceRecord.Kind.EXTERNAL_DELIVERY,
                        delivery.scopePath(),
                        delivery.channelKey(),
                        null,
                        details,
                        null);
            }
        }

        boolean hasExecutionEvidence() {
            return executionEvidence != null;
        }

        FrozenNode classificationSelectedAt(String scopePath) {
            String normalized = normalizeScope(scopePath);
            if (inputSnapshot != null) {
                return inputSnapshot.canonicalAt(normalized);
            }
            ensureClassificationView();
            if (classificationSnapshot != null) {
                return classificationSnapshot.canonicalAt(normalized);
            }
            Node selected = nodeAt(classificationDocument, normalized);
            return selected != null
                    ? FrozenNode.fromResolvedNode(selected)
                    : null;
        }

        FrozenNode classificationResolvedAt(String scopePath) {
            String normalized = normalizeScope(scopePath);
            if (inputSnapshot != null) {
                return inputSnapshot.resolvedAt(normalized);
            }
            ensureClassificationView();
            if (classificationSnapshot != null) {
                return classificationSnapshot.resolvedAt(normalized);
            }
            Node selected = nodeAt(classificationDocument, normalized);
            return selected != null
                    ? FrozenNode.fromResolvedNode(selected)
                    : null;
        }

        private void ensureClassificationView() {
            if (classificationDocument != null
                    || classificationSnapshot != null) {
                return;
            }
            Node projected = inputDocument.clone();
            Map<String, Set<String>> selectedKeys =
                    new LinkedHashMap<>();
            if (executionEvidence != null) {
                for (ExternalDeliverySnapshot delivery
                        : executionEvidence.deliveries()) {
                    selectedKeys.computeIfAbsent(
                            normalizeScope(delivery.scopePath()),
                            ignored -> new LinkedHashSet<>())
                            .add(delivery.channelKey());
                }
            }
            pruneClassificationContracts(
                    projected, "/", selectedKeys);
            ProcessingSnapshotManager manager =
                    owner.snapshotManager();
            if (manager != null) {
                classificationSnapshot =
                        manager.fromDocumentTransient(projected);
            } else {
                classificationDocument = projected;
            }
        }

        private void pruneClassificationContracts(
                Node node,
                String scopePath,
                Map<String, Set<String>> selectedKeys) {
            if (node == null || node.isReferenceOnly()) {
                return;
            }
            Node contracts = node.getContracts();
            if (contracts != null
                    && contracts.getProperties() != null) {
                Set<String> selected =
                        selectedKeys.getOrDefault(
                                normalizeScope(scopePath),
                                Collections.emptySet());
                contracts.getProperties().entrySet()
                        .removeIf(entry ->
                                !selected.contains(entry.getKey())
                                        && !isDirectProcessorStateKey(
                                        entry.getKey())
                                        && !owner.contractLoader()
                                        .isProcessEmbeddedContract(
                                                entry.getValue()));
                if (contracts.getProperties().isEmpty()) {
                    node.contracts(null);
                }
            }
            if (node.getProperties() != null) {
                for (Map.Entry<String, Node> entry
                        : node.getProperties().entrySet()) {
                    pruneClassificationContracts(
                            entry.getValue(),
                            PointerUtils.appendPointer(
                                    scopePath, entry.getKey()),
                            selectedKeys);
                }
            }
            if (node.getItems() != null) {
                for (int index = 0;
                     index < node.getItems().size();
                     index++) {
                    pruneClassificationContracts(
                            node.getItems().get(index),
                            PointerUtils.appendPointer(
                                    scopePath,
                                    Integer.toString(index)),
                            selectedKeys);
                }
            }
        }

        private boolean isDirectProcessorStateKey(String key) {
            return ProcessorContractConstants.KEY_INITIALIZED
                    .equals(key)
                    || ProcessorContractConstants.KEY_TERMINATED
                    .equals(key)
                    || ProcessorContractConstants.KEY_CHECKPOINT
                    .equals(key);
        }

        /**
         * Executes exactly the feeder-admitted occurrences. Recognition of an
         * unrelated branch is neither required nor observable.
         */
        void processEvidenceDeliveries(Node event) {
            if (executionEvidence == null) {
                throw new IllegalStateException("No execution evidence admitted");
            }
            runtime.recordSemanticDemand("/");

            /*
             * Phase B is read-only.  Classify every feeder candidate from a
             * header-only view before recognizing the complete application
             * contract surface.  Rejected and stale-only targets therefore
             * never become participating scopes.
             */
            List<ChannelRunner.ExternalClassification> acceptedNew =
                    new ArrayList<>();
            Map<String, List<EvidenceRouteStep>> routes =
                    new LinkedHashMap<>();
            Map<String, ExternalDeliverySnapshot> acceptedEvidence =
                    new LinkedHashMap<>();
            Set<String> openedScopes = new LinkedHashSet<>();
            Map<String, List<EvidenceRouteStep>> plannedRoutes =
                    new LinkedHashMap<>();
            for (ExternalDeliverySnapshot delivery
                    : executionEvidence.deliveries()) {
                List<EvidenceRouteStep> route =
                        plannedRoutes.get(
                                normalizeScope(delivery.scopePath()));
                if (route == null) {
                    route = routeTo(
                            delivery.scopePath(),
                            openedScopes);
                    plannedRoutes.put(
                            normalizeScope(delivery.scopePath()),
                            route);
                }

                String normalizedTarget =
                        normalizeScope(delivery.scopePath());
                if (openedScopes.add(normalizedTarget)) {
                    runtime.chargeScopeEntry(normalizedTarget);
                }
                ContractBundle classificationBundle =
                        scopeExecutor.externalClassificationBundle(
                                delivery.scopePath(),
                                delivery.channelKey(),
                                false);
                validateDeliveryBinding(
                        delivery,
                        classificationBundle,
                        "classification");
                if ("/".equals(delivery.scopePath())
                        && route.isEmpty()) {
                    runtime.recordSemanticDemand("/contracts");
                }
                if (!"/".equals(delivery.scopePath())) {
                    runtime.recordSemanticDemand(
                            delivery.scopePath());
                }
                runtime.recordSemanticDemand(contractDemand(
                        delivery.scopePath(),
                        delivery.channelKey()));
                if (event != null
                        && event.getProperties() != null
                        && event.getProperties().containsKey(
                        "subscriptionKey")) {
                    runtime.recordSemanticDemand(
                            "/event/subscriptionKey");
                }

                ChannelRunner.ExternalClassification classification =
                        scopeExecutor.classifyEvidenceDelivery(
                                delivery.scopePath(),
                                delivery.channelKey(),
                                event,
                                classificationBundle);
                if (classification.acceptedNew()) {
                    String occurrence = occurrenceKey(
                            delivery.scopePath(),
                            delivery.channelKey());
                    acceptedNew.add(classification);
                    routes.put(
                            occurrence,
                            route);
                    acceptedEvidence.put(
                            occurrence,
                            delivery);
                }
            }

            if (acceptedNew.isEmpty()) {
                return;
            }

            Set<String> participatingScopes =
                    new LinkedHashSet<>();
            participatingScopes.add("/");
            for (ChannelRunner.ExternalClassification classification
                    : acceptedNew) {
                String occurrence = occurrenceKey(
                        classification.scopePath(),
                        classification.channelKey());
                List<EvidenceRouteStep> route =
                        routes.getOrDefault(
                                occurrence,
                                Collections.emptyList());
                List<String> initializationPath =
                        new ArrayList<>();
                initializationPath.add("/");
                for (EvidenceRouteStep step : route) {
                    participatingScopes.add(
                            step.targetScope);
                    initializationPath.add(
                            step.targetScope);
                }
                participatingScopes.add(
                        classification.scopePath());
                evidenceInitializationPaths.put(
                        classification.scopePath(),
                        Collections.unmodifiableList(
                                initializationPath));
            }

            /*
             * Phase C recognizes and validates only the accepted-new closure,
             * and still completes before the first mutation.
             */
            for (String scopePath : participatingScopes) {
                scopeExecutor.preflightSelectedHeaders(
                        scopePath);
            }
            for (String scopePath : participatingScopes) {
                scopeExecutor
                        .preflightEvidenceScopeAfterSelectedHeaders(
                        scopePath);
            }
            for (Map.Entry<String, ExternalDeliverySnapshot> entry
                    : acceptedEvidence.entrySet()) {
                ExternalDeliverySnapshot delivery =
                        entry.getValue();
                validateDeliveryBinding(
                        delivery,
                        bundles.get(
                                normalizeScope(
                                        delivery.scopePath())),
                        "accepted-new preflight");
            }

            for (ChannelRunner.ExternalClassification classification
                    : acceptedNew) {
                String occurrence = occurrenceKey(
                        classification.scopePath(),
                        classification.channelKey());
                registerEvidenceRoute(
                        routes.getOrDefault(
                                occurrence,
                                Collections.emptyList()));
                scopeExecutor.processClassifiedEvidenceDelivery(
                        classification);
                if (shouldStopScopeWork(
                        classification.scopePath())) {
                    return;
                }
            }
        }

        private void validateDeliveryBinding(
                ExternalDeliverySnapshot delivery,
                ContractBundle bundle,
                String phase) {
            ContractBundle.ChannelBinding binding =
                    bundle != null
                            ? bundle.channelBinding(
                            delivery.channelKey())
                            : null;
            EffectiveContractSnapshot snapshot =
                    bundle != null
                            ? bundle.effectiveContractSnapshot(
                            delivery.channelKey())
                            : null;
            if (binding == null
                    || ProcessorContractConstants
                    .isProcessorManagedChannel(
                            binding.contract())
                    || snapshot == null
                    || !delivery.effectiveTypeBlueId().equals(
                    snapshot.effectiveTypeBlueId())
                    || delivery.order() != snapshot.order()
                    || !delivery.sourceContributionNodeBlueIds()
                    .equals(
                            snapshot
                                    .sourceContributionNodeBlueIds())) {
                throw new InvalidExecutionEvidenceException(
                        "External delivery changed during "
                                + phase + " at "
                                + delivery.scopePath() + "/"
                                + delivery.channelKey());
            }
        }

        private String occurrenceKey(
                String scopePath,
                String channelKey) {
            return normalizeScope(scopePath)
                    + "\u0000" + channelKey;
        }

        private List<EvidenceRouteStep> routeTo(
                String targetScope,
                Set<String> openedScopes) {
            String target = normalizeScope(targetScope);
            if ("/".equals(target)) {
                return Collections.emptyList();
            }
            List<EvidenceRouteStep> result = new ArrayList<>();
            String currentScope = "/";
            Set<String> visited = new LinkedHashSet<>();
            while (!currentScope.equals(target)) {
                if (!visited.add(currentScope)) {
                    throw new InvalidExecutionEvidenceException(
                            "Cyclic Process Embedded route to " + target);
                }
                if (openedScopes.add(currentScope)) {
                    runtime.chargeScopeEntry(currentScope);
                }
                EvidenceRouteStep selected = null;
                ContractBundle bundle =
                        scopeExecutor.externalClassificationBundle(
                                currentScope,
                                null,
                                true);
                EffectiveContractSnapshot embeddedSnapshot = null;
                for (EffectiveContractSnapshot snapshot
                        : bundle.effectiveContractSnapshots()) {
                    if ("process-embedded".equals(snapshot.role())) {
                        embeddedSnapshot = snapshot;
                        break;
                    }
                }
                if (embeddedSnapshot != null) {
                    runtime.recordSemanticDemand(contractDemand(
                            currentScope,
                            embeddedSnapshot.key()));
                    for (String raw : bundle.embeddedPaths()) {
                            String candidate = resolvePointer(
                                    currentScope, raw);
                            if (candidate.equals(currentScope)
                                    || !PointerUtils.descendantOrEqual(
                                    target, candidate)) {
                                continue;
                            }
                            int segments = JsonPointer.split(
                                    relativizePointer(currentScope, candidate))
                                    .size();
                            EvidenceRouteStep next = new EvidenceRouteStep(
                                    currentScope,
                                    embeddedSnapshot.key(),
                                    candidate,
                                    segments,
                                    embeddedSnapshot
                                            .sourceContributionNodeBlueIds());
                            if (selected == null
                                    || JsonPointer.split(candidate).size()
                                    > JsonPointer.split(selected.targetScope)
                                    .size()) {
                                selected = next;
                            }
                    }
                }
                if (selected == null) {
                    throw new InvalidExecutionEvidenceException(
                            "No Process Embedded route to " + target);
                }
                result.add(selected);
                currentScope = selected.targetScope;
            }
            return Collections.unmodifiableList(result);
        }

        private void registerEvidenceRoute(
                List<EvidenceRouteStep> route) {
            for (EvidenceRouteStep step : route) {
                ScopeRuntimeContext declaringScope =
                        runtime.scope(step.declaringScope);
                runtime.attachScopeOccurrence(
                        step.declaringScope,
                        step.targetScope);
                if (!declaringScope.processedEmbeddedPaths()
                        .contains(step.targetScope)) {
                    declaringScope.recordProcessedEmbeddedPath(
                            step.targetScope);
                }
                runtime.setScopeEmbeddedDepth(
                        step.targetScope,
                        runtime.scopeEmbeddedDepth(
                                step.declaringScope) + 1);
            }
        }

        private String contractDemand(String scopePath, String key) {
            return resolvePointer(scopePath,
                    ProcessorPointerConstants.RELATIVE_CONTRACTS + "/"
                            + JsonPointer.escape(key));
        }

        private String directTypeBlueId(Node node) {
            Node type = node != null ? node.getType() : null;
            if (type == null) {
                return null;
            }
            return type.getBlueId() != null
                    ? type.getBlueId()
                    : BlueIdCalculator.calculateBlueId(type);
        }

        private Node directProperty(Node node, String key) {
            return node != null && node.getProperties() != null
                    ? node.getProperties().get(key)
                    : null;
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
            ProcessorStatus status = selectStatus();
            if (!status.commits()) {
                DocumentProcessingResult nonCommitting =
                        DocumentProcessingResult.nonCommitting(
                                inputDocument.clone(),
                                runtime.totalGas(),
                                status,
                                failureDiagnostic);
                return inputSnapshot != null
                        ? nonCommitting.withSnapshot(inputSnapshot)
                        : nonCommitting;
            }
            ResolvedSnapshot snapshot = runtime.snapshot();
            if (snapshot != null) {
                ResolvedSnapshot publishedSnapshot = publishableSnapshot(snapshot, owner.metricsSink());
                return DocumentProcessingResult.completed(runtime.selectedDocument(),
                        runtime.rootEmissions(),
                        runtime.totalGas(),
                        status,
                        null,
                        publishedSnapshot);
            }
            return DocumentProcessingResult.completed(runtime.document(),
                    runtime.rootEmissions(),
                    runtime.totalGas(),
                    status,
                    null,
                    null);
        }

        ProcessingDebugResult debugResult() {
            DocumentProcessingResult completed = result();
            PlatformCommitCompanion companion =
                    executionEvidence != null
                            ? PlatformCommitCompanion.of(
                            executionEvidence,
                            completed,
                            subscriptionDelta)
                            : null;
            return new ProcessingDebugResult(
                    completed,
                    runtime.conformanceTrace(),
                    companion);
        }

        private ProcessorStatus selectStatus() {
            if (failureStatus != null) {
                return failureStatus;
            }
            if (processEventSource == null) {
                return ProcessorStatus.SUCCESS;
            }
            if (directRootTerminated) {
                return ProcessorStatus.TERMINATED;
            }
            if (completedDelivery) {
                return ProcessorStatus.SUCCESS;
            }
            if (staleDelivery) {
                return ProcessorStatus.STALE;
            }
            return ProcessorStatus.NO_MATCH;
        }

        void fail(ProcessorStatus status, ProcessorDiagnostic diagnostic) {
            if (failureStatus != null) {
                return;
            }
            if (status == null || status.commits()
                    || status == ProcessorStatus.NO_MATCH
                    || status == ProcessorStatus.STALE
                    || status == ProcessorStatus.TERMINATED) {
                throw new IllegalArgumentException("Invalid deterministic failure status: " + status);
            }
            this.failureStatus = status;
            this.failureDiagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
            runtime.markRunTerminated();
        }

        void recordAcceptedDelivery(String scopePath, String channelKey) {
            acceptedDelivery = true;
            runtime.chargeChannelAccepted(scopePath, channelKey);
            ExternalDeliverySnapshot evidence =
                    deliveryEvidence(scopePath, channelKey);
            if (evidence != null) {
                useExternalContributionProof(
                        evidence, "external-channel-acceptance");
            }
        }

        void recordStaleDelivery() {
            acceptedDelivery = true;
            staleDelivery = true;
        }

        void recordCompletedDelivery() {
            acceptedDelivery = true;
            completedDelivery = true;
        }

        void recordRootTermination() {
            acceptedDelivery = true;
            completedDelivery = true;
        }

        ExternalDeliverySnapshot deliveryEvidence(String scopePath, String channelKey) {
            if (executionEvidence == null) {
                return null;
            }
            String normalized = normalizeScope(scopePath);
            for (ExternalDeliverySnapshot snapshot : executionEvidence.deliveries()) {
                if (snapshot.scopePath().equals(normalized)
                        && snapshot.channelKey().equals(channelKey)) {
                    return snapshot;
                }
            }
            return null;
        }

        String checkpointSubject(String scopePath,
                                 String channelKey,
                                 Node event) {
            ExternalDeliverySnapshot evidence =
                    deliveryEvidence(scopePath, channelKey);
            return evidence != null
                    ? evidence.checkpointSubjectBlueId()
                    : CheckpointIdentityCalculator.identity(
                            event, owner.matchingService().blue());
        }

        ContractBundle initializeAcceptedScope(String scopePath) {
            List<String> path = frozenEvidenceScopeChain(scopePath);
            ContractBundle current = null;
            for (String participatingScope : path) {
                current =
                        scopeExecutor.initializeEvidenceScope(participatingScope);
                if (current == null
                        || shouldStopScopeWork(participatingScope)) {
                    return null;
                }
            }
            return bundles.get(normalizeScope(scopePath));
        }

        List<String> frozenEvidenceScopeChain(String scopePath) {
            String normalized = normalizeScope(scopePath);
            List<String> path =
                    evidenceInitializationPaths.get(normalized);
            return path != null
                    ? path
                    : Collections.singletonList(normalized);
        }

        String checkpointDomain(ContractBundle.ChannelBinding channel,
                                String scopePath) {
            ExternalDeliverySnapshot evidence =
                    deliveryEvidence(scopePath, channel.key());
            if (evidence != null) {
                String occurrence = normalizeScope(scopePath)
                        + "\u0000" + channel.key();
                if (consumedCheckpointDomainProofs.add(occurrence)) {
                    useExternalContributionProof(
                            evidence, "checkpoint-domain");
                }
                /*
                 * channel.node() is the resolved/materialized effective
                 * contract and therefore does not identify any selected
                 * Source contribution. The complete ordered contribution
                 * sequence was already compared with the effective contract
                 * snapshot during evidence-closure preflight.
                 */
                return evidence.checkpointDomainBlueId();
            }
            List<String> contributions = channel.node() != null
                    ? sourceContributions(scopePath, channel.key())
                    : Collections.emptyList();
            return CheckpointDomain.derive(
                    channel.contract().getTypeBlueId(),
                    contributions,
                    null);
        }

        private void useExternalContributionProof(
                ExternalDeliverySnapshot delivery,
                String reason) {
            SemanticGasMeter semantic = runtime.semanticGas();
            /*
             * The effective type's exact BlueId is also the effective
             * constraint identity for this resolved contract validation. No
             * synthetic identity is assigned to the merged effective
             * contract.
             */
            String effectiveConstraintIdentity =
                    delivery.effectiveTypeBlueId();
            for (String contribution
                    : delivery.sourceContributionNodeBlueIds()) {
                GasChargeContext context = GasChargeContext.of(
                        delivery.scopePath(),
                        delivery.channelKey(),
                        contribution,
                        reason);
                semantic.openNodeManifest(contribution, context);
                semantic.useValidationProof(
                        contribution,
                        delivery.effectiveTypeBlueId(),
                        effectiveConstraintIdentity,
                        context);
            }
        }

        private List<String> sourceContributions(String scopePath,
                                                 String contractKey) {
            ContractBundle bundle = bundles.get(normalizeScope(scopePath));
            EffectiveContractSnapshot snapshot = bundle != null
                    ? bundle.effectiveContractSnapshot(contractKey)
                    : null;
            return snapshot != null
                    ? snapshot.sourceContributionNodeBlueIds()
                    : Collections.emptyList();
        }

        boolean hasFailure() {
            return failureStatus != null;
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
                return DocumentProcessingResult.nonCommitting(
                        inputDocument.clone(),
                        runtime.totalGas(),
                        ProcessorStatus.RUNTIME_FATAL,
                        ProcessorDiagnostic.of(
                                ProcessorErrorCategory.RuntimeExecutionFailure,
                                "Runtime processing failed"));
            }
        }

        DocumentProcessingRuntime runtime() {
            return runtime;
        }

        ContractRecognitionMeter contractRecognitionMeter() {
            return contractRecognitionMeter;
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
            return failureStatus != null
                    || isUnderCutOffScope(normalized)
                    || (context != null && context.isTerminated());
        }

        private boolean isUnderCutOffScope(String scopePath) {
            for (String cutOff : cutOffScopes) {
                if (PointerUtils.descendantOrEqual(scopePath, cutOff)) {
                    return true;
                }
            }
            return false;
        }

        boolean isScopeActive(String scopePath) {
            ScopeRuntimeContext context = runtime.existingScope(ProcessorEngine.normalizeScope(scopePath));
            return (context == null || context.isActive()) && !shouldStopScopeWork(scopePath);
        }

        boolean canDeliverOccurrenceLocally(
                ScopeRuntimeContext context) {
            return failureStatus == null
                    && context != null
                    && context.isActive()
                    && !context.isCutOff();
        }

        boolean rootIsTerminated() {
            ScopeRuntimeContext root = runtime.existingScope("/");
            return root != null && root.isTerminated();
        }

        boolean canCompleteTermination(String scopePath) {
            String normalized = ProcessorEngine.normalizeScope(scopePath);
            ScopeRuntimeContext context = runtime.existingScope(normalized);
            return failureStatus == null
                    && !isUnderCutOffScope(normalized)
                    && context != null
                    && context.isTerminating();
        }

        void enterGracefulTermination(String scopePath, ContractBundle bundle, String reason) {
            enterGracefulTermination(scopePath, bundle, "graceful", reason);
        }

        void enterGracefulTermination(String scopePath,
                                      ContractBundle bundle,
                                      String cause,
                                      String reason) {
            terminate(scopePath, bundle, cause, reason);
        }

        void abortRuntimeFailure(String scopePath,
                                 ContractBundle bundle,
                                 String reason) {
            abortRuntimeFailure(
                    scopePath,
                    bundle,
                    ProcessorErrorCategory.InternalProcessorError,
                    reason);
        }

        void abortRuntimeFailure(String scopePath,
                                 ContractBundle bundle,
                                 ProcessorErrorCategory errorCategory,
                                 String reason) {
            ProcessorErrorCategory category = errorCategory != null
                    ? errorCategory
                    : ProcessorErrorCategory.RuntimeExecutionFailure;
            fail(ProcessorStatus.RUNTIME_FATAL,
                    ProcessorDiagnostic.builder(category)
                            .message(reason)
                            .detail("scopePath", normalizeScope(scopePath))
                            .build());
            /*
             * Contracts 1.0 has no committed fatal termination mode. Abort the
             * atomic invocation immediately; do not write a terminated marker
             * and do not emit a lifecycle/fatal event.
             */
            throw new RunTerminationException();
        }

        private void terminate(String scopePath,
                               ContractBundle bundle,
                               String cause,
                               String reason) {
            String normalized = ProcessorEngine.normalizeScope(scopePath);
            ScopeRuntimeContext context = runtime.scope(normalized);
            if (!context.beginTermination()) {
                return;
            }
            runtime.chargeTerminationRequest();
            terminationService.terminateScope(
                    this, scopePath, bundle, cause, reason);
        }

        ContractBundle bundleForScope(String scopePath) {
            return bundles.get(scopePath);
        }

        void markCutOff(String scopePath) {
            String normalized = ProcessorEngine.normalizeScope(scopePath);
            if (cutOffScopes.add(normalized)) {
                runtime.recordTrace(ProcessingTraceRecord.Kind.SCOPE_CUT_OFF,
                        normalized,
                        null,
                        normalized);
                for (Map.Entry<String, ScopeRuntimeContext> entry
                        : runtime.scopes().entrySet()) {
                    if (PointerUtils.descendantOrEqual(
                            entry.getKey(), normalized)) {
                        entry.getValue().markCutOff();
                    }
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

        void enqueueApplicationEvent(String scopePath,
                                     String contractKey,
                                     Node event,
                                     String eventBlueId) {
            enqueueEventOccurrence(
                    scopePath,
                    contractKey,
                    event,
                    eventBlueId,
                    EventOccurrence.SourceMode.TRIGGERED);
        }

        private void enqueueEventOccurrence(
                String scopePath,
                String contractKey,
                Node event,
                EventOccurrence.SourceMode sourceMode) {
            String eventBlueId = CheckpointIdentityCalculator.identity(
                    event, owner.matchingService().blue());
            enqueueEventOccurrence(
                    scopePath,
                    contractKey,
                    event,
                    eventBlueId,
                    sourceMode);
        }

        private void enqueueEventOccurrence(
                String scopePath,
                String contractKey,
                Node event,
                String eventBlueId,
                EventOccurrence.SourceMode sourceMode) {
            String normalized = normalizeScope(scopePath);
            ScopeRuntimeContext source = runtime.scope(normalized);
            EventOccurrence occurrence = new EventOccurrence(
                    event,
                    eventBlueId,
                    source,
                    source.freezeAncestorChain(),
                    sourceMode,
                    contractKey);
            runtime.chargeEmitEvent(event);
            runtime.enqueueEventOccurrence(occurrence);
            runtime.recordTrace(
                    ProcessingTraceRecord.Kind.EVENT_ENQUEUED,
                    normalized,
                    contractKey,
                    null,
                    Collections.emptyMap(),
                    event);
            if ("/".equals(normalized)) {
                runtime.chargeRootEventRecorded();
                runtime.recordTrace(
                        ProcessingTraceRecord.Kind.ROOT_EVENT,
                        normalized,
                        contractKey,
                        null,
                        Collections.emptyMap(),
                        event);
                runtime.recordRootEmission(event.clone());
            }
        }

        void drainInternalEvents() {
            scopeExecutor.drainInternalEvents();
        }

        void requestInternalEventDrain() {
            scopeExecutor.requestInternalEventDrain();
        }

        void completePendingTerminations() {
            terminationService.completePendingTerminations(this);
        }

        private Node cloneEvent(Node event) {
            return event != null ? event.clone() : null;
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

    private static final class EvidenceRouteStep {
        private final String declaringScope;
        private final String contractKey;
        private final String targetScope;
        private final int relativeSegmentCount;
        private final List<String> orderedContributionBlueIds;

        private EvidenceRouteStep(String declaringScope,
                                  String contractKey,
                                  String targetScope,
                                  int relativeSegmentCount,
                                  List<String> orderedContributionBlueIds) {
            this.declaringScope = declaringScope;
            this.contractKey = contractKey;
            this.targetScope = targetScope;
            this.relativeSegmentCount = relativeSegmentCount;
            this.orderedContributionBlueIds =
                    Collections.unmodifiableList(
                            new ArrayList<>(
                                    orderedContributionBlueIds));
        }

        private String occurrenceKey() {
            return declaringScope + "\u0000" + contractKey + "\u0000"
                    + targetScope;
        }

        private String headerOccurrenceKey() {
            StringBuilder key = new StringBuilder(
                    declaringScope + "\u0000" + contractKey);
            for (String blueId : orderedContributionBlueIds) {
                key.append('\u0000').append(blueId);
            }
            return key.toString();
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

        ChannelMatch(boolean matches,
                     String eventId,
                     Node event,
                     ChannelProcessor<ChannelContract> processor) {
            this.matches = matches;
            this.eventId = eventId;
            this.event = event != null ? event.clone() : null;
            this.processor = processor;
        }

        Node eventNode() {
            return event != null ? event.clone() : null;
        }

        static ChannelMatch noMatch() {
            return new ChannelMatch(false, null, null, null);
        }
    }

    static final class BoundaryViolationException extends RuntimeException {
        BoundaryViolationException(String message) {
            super(message);
        }
    }
}
