package blue.language.processor.contracts;

import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.RemoveProperty;

public class RemovePropertyContractProcessor implements HandlerProcessor<RemoveProperty> {

    @Override
    public Class<RemoveProperty> contractType() {
        return RemoveProperty.class;
    }

    @Override
    public void execute(RemoveProperty contract, ProcessorExecutionContext context) {
        String propertyKey = contract.getPropertyKey();
        if (propertyKey == null || propertyKey.trim().isEmpty()) {
            throw new IllegalArgumentException("propertyKey must not be empty for RemoveProperty");
        }
        String normalized = normalize(propertyKey);
        String pointer = context.resolvePointer(normalized);
        context.applyPatch(JsonPatch.remove(pointer));
    }

    private String normalize(String key) {
        String stripped = key.trim();
        if (!stripped.startsWith("/")) {
            stripped = "/" + stripped;
        }
        return stripped.replaceAll("/+", "/");
    }
}
