package blue.language.conformance.contracts;

import blue.language.processor.HandlerMatchContext;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;

import java.util.Collections;
import java.util.List;

/**
 * Ordinary Handler processor for the published Scripted Handler fixture type.
 */
final class MockHandlerProcessor
        implements HandlerProcessor<MockHandler.Value> {

    private final ScriptedContractsRuntime runtime;

    /** Creates a processor backed by the empty scripted runtime. */
    public MockHandlerProcessor() {
        this(ScriptedContractsRuntime.empty());
    }

    /**
     * Creates a processor backed by fixture controls.
     *
     * @param runtime scripted runtime, or {@code null} to use the empty
     *        runtime
     */
    public MockHandlerProcessor(ScriptedContractsRuntime runtime) {
        this.runtime = runtime != null ? runtime : ScriptedContractsRuntime.empty();
    }

    @Override
    public Class<MockHandler.Value> contractType() {
        return MockHandler.Value.class;
    }

    @Override
    public List<String> executableBodyFields() {
        return Collections.singletonList(ContractsFixtureConstants.Field.RESULT);
    }

    @Override
    public boolean matches(
            MockHandler.Value contract,
            HandlerMatchContext context) {
        String path = ScriptedContractsRuntime.contractPath(
                context.scopePath(), context.handlerKey());
        if (runtime.hasHandlerScript(path)) {
            return runtime.matchesHandler(path, contract, context);
        }
        return context.matchesEventPattern(contract.getEvent());
    }

    @Override
    public void execute(
            MockHandler.Value contract,
            ProcessorExecutionContext context) {
        String path = ScriptedContractsRuntime.contractPath(
                context.scopePath(), context.contractKey());
        if (runtime.hasHandlerScript(path)) {
            runtime.executeHandler(path, contract, context);
        } else {
            runtime.executeDeclaredResult(contract.getResult(), context);
        }
    }
}
