package blue.language.processor.contracts;

import blue.language.model.Node;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.SetPropertyOnEvent;

public class SetPropertyOnEventContractProcessor implements HandlerProcessor<SetPropertyOnEvent> {

    @Override
    public Class<SetPropertyOnEvent> contractType() {
        return SetPropertyOnEvent.class;
    }

    @Override
    public void execute(SetPropertyOnEvent contract, ProcessorExecutionContext context) {
        if (!matchesEvent(contract, context.event())) {
            return;
        }
        Node valueNode = new Node().value(contract.getPropertyValue());
        String pointer = context.resolvePointer(contract.getPropertyKey());
        context.applyPatch(JsonPatch.add(pointer, valueNode));
    }

    private boolean matchesEvent(SetPropertyOnEvent contract, Node event) {
        if (event == null || event.getProperties() == null) {
            return false;
        }
        if (contract.getExpectedKind() == null || contract.getExpectedKind().isEmpty()) {
            return true;
        }
        Node kindNode = event.getProperties().get("kind");
        if (kindNode == null) {
            return false;
        }
        Object value = kindNode.getValue();
        return value != null && contract.getExpectedKind().equals(value.toString());
    }
}
