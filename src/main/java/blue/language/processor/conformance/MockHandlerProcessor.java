package blue.language.processor.conformance;

import blue.language.processor.HandlerMatchContext;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;

import java.util.Collections;
import java.util.List;

/**
 * Ordinary Handler processor for the published Scripted Handler fixture type.
 */
public final class MockHandlerProcessor implements HandlerProcessor<MockHandler> {

    private final ScriptedContractsRuntime runtime;

    public MockHandlerProcessor() {
        this(ScriptedContractsRuntime.empty());
    }

    public MockHandlerProcessor(ScriptedContractsRuntime runtime) {
        this.runtime = runtime != null ? runtime : ScriptedContractsRuntime.empty();
    }

    @Override
    public Class<MockHandler> contractType() {
        return MockHandler.class;
    }

    @Override
    public List<String> executableBodyFields() {
        return Collections.singletonList("result");
    }

    @Override
    public boolean matches(MockHandler contract, HandlerMatchContext context) {
        String path = ScriptedContractsRuntime.contractPath(
                context.scopePath(), context.handlerKey());
        if (runtime.hasHandlerScript(path)) {
            return runtime.matchesHandler(path, contract, context);
        }
        return context.matchesEventPattern(contract.getEvent());
    }

    @Override
    public void execute(MockHandler contract, ProcessorExecutionContext context) {
        String path = ScriptedContractsRuntime.contractPath(
                context.scopePath(), context.contractKey());
        if (runtime.hasHandlerScript(path)) {
            runtime.executeHandler(path, contract, context);
        } else {
            runtime.executeDeclaredResult(contract.getResult(), context);
        }
    }
}
