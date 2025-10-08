package blue.language.processor.contracts;

import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.RemoveIfPresent;

public class RemoveIfPresentContractProcessor implements HandlerProcessor<RemoveIfPresent> {

    @Override
    public Class<RemoveIfPresent> contractType() {
        return RemoveIfPresent.class;
    }

    @Override
    public void execute(RemoveIfPresent contract, ProcessorExecutionContext context) {
        String key = contract.getPropertyKey();
        if (key == null) {
            return;
        }
        String trimmed = key.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        String normalized = trimmed.startsWith("/") ? trimmed : "/" + trimmed;
        String pointer = context.resolvePointer(normalized);
        if (!context.documentContains(pointer)) {
            return;
        }
        context.applyPatch(JsonPatch.remove(pointer));
    }
}
