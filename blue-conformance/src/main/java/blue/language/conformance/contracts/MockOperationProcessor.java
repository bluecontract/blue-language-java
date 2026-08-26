package blue.language.conformance.contracts;

import blue.language.model.Node;
import blue.language.processor.HandlerMatchContext;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Ordinary fixture processor for the distinct Scripted Operation type. */
final class MockOperationProcessor
        implements HandlerProcessor<MockOperation.Value> {

    private final ScriptedContractsRuntime runtime;

    /** Creates a processor backed by the empty scripted runtime. */
    MockOperationProcessor() {
        this(ScriptedContractsRuntime.empty());
    }

    /**
     * Creates a processor backed by fixture controls.
     *
     * @param runtime scripted result executor, or {@code null}
     */
    MockOperationProcessor(ScriptedContractsRuntime runtime) {
        this.runtime = runtime != null
                ? runtime
                : ScriptedContractsRuntime.empty();
    }

    @Override
    public boolean isOperationRoute() {
        return true;
    }

    @Override
    public Class<MockOperation.Value> contractType() {
        return MockOperation.Value.class;
    }

    @Override
    public List<String> executableBodyFields() {
        return Collections.singletonList(
                ContractsFixtureConstants.Field.RESULT);
    }

    @Override
    public boolean matches(
            MockOperation.Value contract,
            HandlerMatchContext context) {
        if (!context.matchesEventPattern(contract.getEvent())) {
            return false;
        }
        String operationId = contract.getOperationId();
        return operationId == null
                || operationId.equals(eventId(context.occurrenceEvent()));
    }

    @Override
    public void execute(
            MockOperation.Value contract,
            ProcessorExecutionContext context) {
        runtime.executeDeclaredResult(contract.getResult(), context);
    }

    private static String eventId(Node event) {
        Map<String, Node> properties = event != null
                ? event.getProperties()
                : null;
        Node id = properties != null ? properties.get("id") : null;
        Object value = id != null ? id.getValue() : null;
        return value instanceof String ? (String) value : null;
    }
}
