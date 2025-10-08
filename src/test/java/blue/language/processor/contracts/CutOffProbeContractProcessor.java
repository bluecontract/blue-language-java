package blue.language.processor.contracts;

import blue.language.model.Node;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.model.CutOffProbe;
import blue.language.processor.model.JsonPatch;

public class CutOffProbeContractProcessor implements HandlerProcessor<CutOffProbe> {

    @Override
    public Class<CutOffProbe> contractType() {
        return CutOffProbe.class;
    }

    @Override
    public void execute(CutOffProbe contract, ProcessorExecutionContext context) {
        if (contract.isEmitBefore()) {
            emit(context, contract.getPreEmitKind());
        }
        if (contract.getPatchPointer() != null) {
            applyPatch(context, contract.getPatchPointer(), contract.getPatchValue());
        }
        if (contract.isEmitAfter()) {
            emit(context, contract.getPostEmitKind());
        }
        if (contract.getPostPatchPointer() != null) {
            applyPatch(context, contract.getPostPatchPointer(), contract.getPostPatchValue());
        }
    }

    private void emit(ProcessorExecutionContext context, String kind) {
        if (kind == null) {
            return;
        }
        Node event = new Node().properties("kind", new Node().value(kind));
        context.emitEvent(event);
    }

    private void applyPatch(ProcessorExecutionContext context, String pointer, Integer value) {
        if (pointer == null) {
            return;
        }
        String resolved = context.resolvePointer(pointer);
        Node nodeValue = new Node().value(value != null ? value : 0);
        context.applyPatch(JsonPatch.add(resolved, nodeValue));
    }
}
