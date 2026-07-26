package blue.language.processor.contracts;

import blue.language.model.Node;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.model.AssertDocumentUpdate;

import java.math.BigInteger;
import java.util.Objects;

public class AssertDocumentUpdateContractProcessor implements HandlerProcessor<AssertDocumentUpdate> {

    @Override
    public Class<AssertDocumentUpdate> contractType() {
        return AssertDocumentUpdate.class;
    }

    @Override
    public void execute(AssertDocumentUpdate contract, ProcessorExecutionContext context) {
        Node event = context.event();
        Node pathNode = getRequiredProperty(event, "path");
        if (!Objects.equals(contract.getExpectedPath(), pathNode.getValue())) {
            throw new IllegalStateException("Expected path " + contract.getExpectedPath() + " but was " + pathNode.getValue());
        }

        Node opNode = getRequiredProperty(event, "op");
        if (!Objects.equals(contract.getExpectedOp(), opNode.getValue())) {
            throw new IllegalStateException("Expected op " + contract.getExpectedOp() + " but was " + opNode.getValue());
        }

        validateSnapshot(
                event,
                "before",
                contract.isExpectBeforeNull(),
                contract.getExpectedBeforeValue());
        validateSnapshot(
                event,
                "after",
                contract.isExpectAfterNull(),
                contract.getExpectedAfterValue());
    }

    private Node getRequiredProperty(Node event, String key) {
        Node value = event.getProperties() != null ? event.getProperties().get(key) : null;
        if (value == null) {
            throw new IllegalStateException("Document Update event missing property '" + key + "'");
        }
        return value;
    }

    private void validateSnapshot(Node event,
                                  String label,
                                  boolean expectAbsent,
                                  Integer expectedValue) {
        Node presentNode = getRequiredProperty(
                event, label + "Present");
        Object presentValue = presentNode.getValue();
        if (!(presentValue instanceof Boolean)) {
            throw new IllegalStateException(
                    "Document Update event property '"
                            + label + "Present' must be Boolean");
        }
        boolean present = (Boolean) presentValue;
        Node snapshot = event.getProperties() != null
                ? event.getProperties().get(label)
                : null;
        if (expectAbsent) {
            if (present || snapshot != null) {
                throw new IllegalStateException(
                        "Expected " + label
                                + " to be absent with "
                                + label + "Present=false");
            }
            return;
        }
        if (!present || snapshot == null) {
            throw new IllegalStateException(
                    "Expected " + label
                            + " to be present with "
                            + label + "Present=true");
        }
        if (expectedValue == null) {
            return;
        }
        Object value = snapshot.getValue();
        if (!(value instanceof BigInteger)) {
            throw new IllegalStateException("Expected " + label + " to be numeric but was " + value);
        }

        BigInteger numeric = (BigInteger) value;
        if (numeric.intValue() != expectedValue) {
            throw new IllegalStateException("Expected " + label + " value " + expectedValue + " but was " + numeric);
        }
    }
}
