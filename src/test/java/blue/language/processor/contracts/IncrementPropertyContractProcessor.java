package blue.language.processor.contracts;

import blue.language.model.Node;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.model.IncrementProperty;
import blue.language.processor.model.JsonPatch;

import java.math.BigInteger;

public class IncrementPropertyContractProcessor implements HandlerProcessor<IncrementProperty> {

    @Override
    public Class<IncrementProperty> contractType() {
        return IncrementProperty.class;
    }

    @Override
    public void execute(IncrementProperty contract, ProcessorExecutionContext context) {
        String pointer = buildPointer(contract.getPropertyKey());
        String absolute = context.resolvePointer(pointer);

        Node existing = context.documentAt(absolute);
        BigInteger current = currentValue(existing);
        BigInteger next = current.add(BigInteger.ONE);
        Node valueNode = new Node().value(next);
        boolean exists = existing != null;
        JsonPatch patch = exists ? JsonPatch.replace(absolute, valueNode) : JsonPatch.add(absolute, valueNode);
        context.applyPatch(patch);
    }

    private BigInteger currentValue(Node node) {
        if (node == null) {
            return BigInteger.ZERO;
        }
        Object value = node.getValue();
        if (value instanceof BigInteger) {
            return (BigInteger) value;
        }
        if (value instanceof Number) {
            return BigInteger.valueOf(((Number) value).longValue());
        }
        if (value instanceof String) {
            try {
                return new BigInteger((String) value);
            } catch (NumberFormatException ignored) {
            }
        }
        return BigInteger.ZERO;
    }

    private String buildPointer(String key) {
        if (key == null || key.trim().isEmpty()) {
            throw new IllegalArgumentException("propertyKey must not be empty");
        }
        String stripped = key.trim().replaceAll("/+", "/");
        while (stripped.startsWith("/")) {
            stripped = stripped.substring(1);
        }
        while (stripped.endsWith("/")) {
            stripped = stripped.substring(0, stripped.length() - 1);
        }
        return "/" + stripped;
    }
}
