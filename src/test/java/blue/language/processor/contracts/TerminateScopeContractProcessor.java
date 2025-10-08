package blue.language.processor.contracts;

import blue.language.model.Node;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.TerminateScope;

public class TerminateScopeContractProcessor implements HandlerProcessor<TerminateScope> {

    @Override
    public Class<TerminateScope> contractType() {
        return TerminateScope.class;
    }

    @Override
    public void execute(TerminateScope contract, ProcessorExecutionContext context) {
        String mode = contract.getMode() != null ? contract.getMode() : "graceful";
        String reason = contract.getReason();
        if ("fatal".equalsIgnoreCase(mode)) {
            context.terminateFatally(reason);
        } else {
            context.terminateGracefully(reason);
        }
        if (contract.isEmitAfter()) {
            Node event = new Node().properties("type", new Node().value("ShouldNotEmit"));
            context.emitEvent(event);
        }
        if (contract.isPatchAfter()) {
            String pointer = context.resolvePointer("/afterTermination");
            context.applyPatch(JsonPatch.add(pointer, new Node().value("should-not-exist")));
        }
    }
}
