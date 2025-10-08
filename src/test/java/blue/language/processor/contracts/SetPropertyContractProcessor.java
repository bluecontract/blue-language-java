package blue.language.processor.contracts;

import blue.language.model.Node;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.SetProperty;

public class SetPropertyContractProcessor implements HandlerProcessor<SetProperty> {

    @Override
    public Class<SetProperty> contractType() {
        return SetProperty.class;
    }

    @Override
    public void execute(SetProperty contract, ProcessorExecutionContext context) {
        String propertyKey = contract.getPropertyKey() != null ? contract.getPropertyKey() : "x";
        Node valueNode = new Node().value(contract.getPropertyValue());
        String relativePointer = buildPointer(contract.getPath(), propertyKey);
        String targetPath = context.resolvePointer(relativePointer);
        boolean exists = context.documentContains(targetPath);
        JsonPatch patch = exists ? JsonPatch.replace(targetPath, valueNode) : JsonPatch.add(targetPath, valueNode);
        context.applyPatch(patch);
    }

    private String buildPointer(String path, String propertyKey) {
        String base = strip(path);
        String key = strip(propertyKey);
        if (base.isEmpty() && key.isEmpty()) {
            return "/";
        }
        if (base.isEmpty()) {
            return "/" + key;
        }
        if (key.isEmpty()) {
            return "/" + base;
        }
        return "/" + base + "/" + key;
    }

    private String strip(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "";
        }
        String result = value.trim().replaceAll("/+", "/");
        while (result.startsWith("/")) {
            result = result.substring(1);
        }
        while (result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

}
