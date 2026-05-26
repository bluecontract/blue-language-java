package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.JsonPatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ContractEffectBuffer {

    private long gas;
    private String invalidGasReason;
    private final List<JsonPatch> patches = new ArrayList<>();
    private final List<Node> emittedEvents = new ArrayList<>();
    private TerminationRequest terminationRequest;

    void addGas(long units) {
        if (units < 0) {
            invalidGasReason = "Gas amount must be non-negative";
            return;
        }
        gas += units;
    }

    long gas() {
        return gas;
    }

    String invalidGasReason() {
        return invalidGasReason;
    }

    void addPatch(JsonPatch patch) {
        if (patch != null) {
            patches.add(copyPatch(patch));
        }
    }

    void addPatches(List<JsonPatch> input) {
        if (input == null || input.isEmpty()) {
            return;
        }
        for (JsonPatch patch : input) {
            addPatch(patch);
        }
    }

    List<JsonPatch> patches() {
        return Collections.unmodifiableList(patches);
    }

    void emit(Node event) {
        emittedEvents.add(event != null ? event.clone() : null);
    }

    List<Node> emittedEvents() {
        return Collections.unmodifiableList(emittedEvents);
    }

    void terminate(ScopeRuntimeContext.TerminationKind kind, String reason) {
        if (terminationRequest == null) {
            terminationRequest = new TerminationRequest(kind, reason);
        }
    }

    TerminationRequest terminationRequest() {
        return terminationRequest;
    }

    private JsonPatch copyPatch(JsonPatch patch) {
        switch (patch.getOp()) {
            case ADD:
                return JsonPatch.add(patch.getPath(), patch.getVal().clone());
            case REPLACE:
                return JsonPatch.replace(patch.getPath(), patch.getVal().clone());
            case REMOVE:
                return JsonPatch.remove(patch.getPath());
            default:
                throw new IllegalStateException("Unsupported patch op: " + patch.getOp());
        }
    }

    static final class TerminationRequest {
        private final ScopeRuntimeContext.TerminationKind kind;
        private final String reason;

        private TerminationRequest(ScopeRuntimeContext.TerminationKind kind, String reason) {
            this.kind = kind;
            this.reason = reason;
        }

        ScopeRuntimeContext.TerminationKind kind() {
            return kind;
        }

        String reason() {
            return reason;
        }
    }
}
