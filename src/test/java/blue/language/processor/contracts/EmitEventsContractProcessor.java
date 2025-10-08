package blue.language.processor.contracts;

import blue.language.model.Node;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.model.EmitEvents;

public class EmitEventsContractProcessor implements HandlerProcessor<EmitEvents> {

    @Override
    public Class<EmitEvents> contractType() {
        return EmitEvents.class;
    }

    @Override
    public void execute(EmitEvents contract, ProcessorExecutionContext context) {
        if (contract.getEvents() == null) {
            return;
        }
        if (!shouldEmit(contract, context.event())) {
            return;
        }
        for (Node event : contract.getEvents()) {
            if (event != null) {
                context.emitEvent(event.clone());
            }
        }
    }

    private boolean shouldEmit(EmitEvents contract, Node currentEvent) {
        if (contract.getExpectedKind() == null || contract.getExpectedKind().isEmpty()) {
            return true;
        }
        if (currentEvent == null || currentEvent.getProperties() == null) {
            return false;
        }
        Node kindNode = currentEvent.getProperties().get("kind");
        if (kindNode == null) {
            return false;
        }
        Object value = kindNode.getValue();
        return value != null && contract.getExpectedKind().equals(value.toString());
    }
}
