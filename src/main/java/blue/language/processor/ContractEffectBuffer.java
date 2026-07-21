package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.model.FrozenJsonPatch;
import blue.language.processor.model.JsonPatch;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class ContractEffectBuffer implements AutoCloseable {

    private long gas;
    private String invalidGasReason;
    private final List<PatchInput> patches = new ArrayList<>();
    private final List<PatchBatch> patchBatches = new ArrayList<>();
    private final List<Node> emittedEvents = new ArrayList<>();
    private TerminationRequest terminationRequest;
    private boolean closed;

    void addGas(long units) {
        ensureOpen();
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
            addPatches(Collections.singletonList(patch));
        }
    }

    void addPatches(List<JsonPatch> input) {
        addPatchInputs(PatchInput.mutableList(input, PatchSource.CUSTOM_PROCESSOR), null);
    }

    void addPreviewedPatches(List<JsonPatch> input, WorkingDocument.Preview preview) {
        addPatchInputs(PatchInput.mutableList(input, PatchSource.CUSTOM_PROCESSOR), preview);
    }

    void addFrozenPatches(List<FrozenJsonPatch> input) {
        addPatchInputs(PatchInput.frozenList(input), null);
    }

    void addPreviewedFrozenPatches(List<FrozenJsonPatch> input, WorkingDocument.Preview preview) {
        addPatchInputs(PatchInput.frozenList(input), preview);
    }

    private void addPatchInputs(List<PatchInput> input, WorkingDocument.Preview preview) {
        ensureOpen();
        if (input == null || input.isEmpty()) {
            return;
        }
        List<PatchInput> batch = new ArrayList<>(input);
        patches.addAll(batch);
        patchBatches.add(new PatchBatch(batch, preview));
    }

    List<PatchInput> patches() {
        return Collections.unmodifiableList(patches);
    }

    List<PatchBatch> patchBatches() {
        return Collections.unmodifiableList(patchBatches);
    }

    void emit(Node event) {
        ensureOpen();
        emittedEvents.add(event != null ? event.clone() : null);
    }

    List<Node> emittedEvents() {
        return Collections.unmodifiableList(emittedEvents);
    }

    void terminate(ScopeRuntimeContext.TerminationKind kind, String reason) {
        ensureOpen();
        if (terminationRequest == null) {
            terminationRequest = new TerminationRequest(kind, reason);
        }
    }

    TerminationRequest terminationRequest() {
        return terminationRequest;
    }

    /** Releases every preview whose ownership was transferred into this buffer. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        Throwable failure = null;
        for (PatchBatch patchBatch : patchBatches) {
            try {
                patchBatch.closePreview();
            } catch (RuntimeException | Error ex) {
                if (failure == null) {
                    failure = ex;
                } else if (failure != ex) {
                    failure.addSuppressed(ex);
                }
            }
        }
        patches.clear();
        patchBatches.clear();
        emittedEvents.clear();
        terminationRequest = null;
        gas = 0L;
        invalidGasReason = null;
        if (failure instanceof RuntimeException) {
            throw (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Contract effect buffer is closed");
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

    static final class PatchBatch {
        private final List<PatchInput> patches;
        private WorkingDocument.Preview preview;

        private PatchBatch(List<PatchInput> patches, WorkingDocument.Preview preview) {
            this.patches = Collections.unmodifiableList(new ArrayList<>(patches));
            this.preview = preview;
        }

        List<PatchInput> patches() {
            return patches;
        }

        WorkingDocument.Preview preview() {
            return preview;
        }

        private void closePreview() {
            WorkingDocument.Preview retained = preview;
            preview = null;
            if (retained != null) {
                retained.close();
            }
        }
    }
}
