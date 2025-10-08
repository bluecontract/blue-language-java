package blue.language.processor.contracts;

import blue.language.model.Node;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.model.MutateEvent;

public class MutateEventContractProcessor implements HandlerProcessor<MutateEvent> {

    @Override
    public Class<MutateEvent> contractType() {
        return MutateEvent.class;
    }

    @Override
    public void execute(MutateEvent contract, ProcessorExecutionContext context) {
        Node event = context.event();
        if (event == null) {
            return;
        }
        event.properties("kind", new Node().value("mutated"));
    }
}
