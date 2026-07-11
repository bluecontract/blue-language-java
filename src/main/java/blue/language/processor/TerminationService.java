package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.util.ProcessorPointerConstants;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.NodePathEditor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handles one scope termination transition: marker, lifecycle event, and root completion.
 */
final class TerminationService {

    private final DocumentProcessingRuntime runtime;

    TerminationService(DocumentProcessingRuntime runtime) {
        this.runtime = runtime;
    }

    void terminateScope(ProcessorEngine.Execution execution,
                        String scopePath,
                        ContractBundle bundle,
                        ScopeRuntimeContext.TerminationKind kind,
                        String reason) {
        String normalized = execution.normalizeScope(scopePath);
        Node marker = createTerminationMarker(kind, reason);
        if (!writeTerminationMarker(normalized, marker)) {
            execution.recordTerminationWriteFailure(normalized,
                    "Unable to write terminated marker at scope " + normalized);
            throw new RunTerminationException(true);
        }
        runtime.chargeTerminationMarker();

        ContractBundle bundleRef = bundle != null ? bundle : execution.bundleForScope(normalized);
        Node lifecycleEvent = createTerminationLifecycleEvent(kind, reason);
        execution.deliverTerminationLifecycle(normalized, bundleRef, lifecycleEvent);

        ScopeRuntimeContext scopeContext = runtime.scope(normalized);
        scopeContext.finalizeTermination(kind, reason);

        if (ScopeRuntimeContext.TerminationKind.FATAL.equals(kind)) {
            runtime.chargeFatalTerminationOverhead();
        }

        if ("/".equals(normalized)) {
            boolean fatal = ScopeRuntimeContext.TerminationKind.FATAL.equals(kind)
                    || execution.hasTerminationEscalation(normalized);
            if (fatal) {
                recordRootFatalEvidence(execution, execution.fatalTerminationReason(normalized, reason));
            }
            runtime.markRunTerminated();
            throw new RunTerminationException(fatal);
        }
    }

    private boolean writeTerminationMarker(String scopePath, Node marker) {
        String markerPointer = ProcessorEngine.resolvePointer(scopePath, ProcessorPointerConstants.RELATIVE_TERMINATED);
        try {
            runtime.directWrite(markerPointer, marker);
            return true;
        } catch (RuntimeException primaryFailure) {
            String contractsPointer = ProcessorEngine.resolvePointer(scopePath,
                    ProcessorPointerConstants.RELATIVE_CONTRACTS);
            if (!hasMalformedContractsContainer(contractsPointer)) {
                return false;
            }
            return replaceMalformedContractsOnce(contractsPointer, fallbackContracts(contractsPointer, marker));
        }
    }

    private boolean hasMalformedContractsContainer(String contractsPointer) {
        Node contracts = NodePathEditor.getOrNull(runtime.document(), contractsPointer);
        return contracts != null
                && (contracts.getValue() != null
                || contracts.getItems() != null
                || contracts.isReferenceOnly());
    }

    private boolean replaceMalformedContractsOnce(String contractsPointer, Node replacementContracts) {
        Node replacement = runtime.document().clone();
        try {
            NodePathEditor.put(replacement, contractsPointer, replacementContracts);
            FrozenNode.fromNode(replacement);
            runtime.replaceDocument(replacement);
            return true;
        } catch (RuntimeException fallbackFailure) {
            return false;
        }
    }

    private Node fallbackContracts(String contractsPointer, Node marker) {
        Node existingContracts = NodePathEditor.getOrNull(runtime.document(), contractsPointer);
        Map<String, Node> preserved = new LinkedHashMap<>();
        if (existingContracts != null && existingContracts.getProperties() != null) {
            for (String key : ProcessorContractConstants.RESERVED_CONTRACT_KEYS) {
                if (ProcessorContractConstants.KEY_TERMINATED.equals(key)) {
                    continue;
                }
                Node candidate = existingContracts.getProperties().get(key);
                if (isValidReservedRuntimeSubtree(candidate)) {
                    preserved.put(key, candidate.clone());
                }
            }
        }
        preserved.put(ProcessorContractConstants.KEY_TERMINATED, marker);
        return new Node().properties(preserved);
    }

    private boolean isValidReservedRuntimeSubtree(Node candidate) {
        if (candidate == null) {
            return false;
        }
        try {
            FrozenNode.fromNode(candidate);
            return true;
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private void recordRootFatalEvidence(ProcessorEngine.Execution execution, String reason) {
        if (execution.markRootFatalEvidenceAppended()) {
            runtime.recordRootEmission(createFatalOutboxEvent(reason));
        }
    }

    private Node createTerminationMarker(ScopeRuntimeContext.TerminationKind kind, String reason) {
        Node marker = new Node()
                .type(new Node().blueId(RuntimeBlueIds.PROCESSING_TERMINATED_MARKER))
                .properties("cause", new Node().value(kind == ScopeRuntimeContext.TerminationKind.GRACEFUL ? "graceful" : "fatal"));
        if (reason != null && !reason.isEmpty()) {
            marker.properties("reason", new Node().value(reason));
        }
        return marker;
    }

    private Node createTerminationLifecycleEvent(ScopeRuntimeContext.TerminationKind kind, String reason) {
        Node event = new Node().type(new Node().blueId(RuntimeBlueIds.DOCUMENT_PROCESSING_TERMINATED));
        event.properties("cause", new Node().value(kind == ScopeRuntimeContext.TerminationKind.GRACEFUL ? "graceful" : "fatal"));
        if (reason != null && !reason.isEmpty()) {
            event.properties("reason", new Node().value(reason));
        }
        return event;
    }

    private Node createFatalOutboxEvent(String reason) {
        Node event = new Node().type(new Node().blueId(RuntimeBlueIds.DOCUMENT_PROCESSING_FATAL_ERROR));
        if (reason != null && !reason.isEmpty()) {
            event.properties("reason", new Node().value(reason));
        }
        return event;
    }
}
