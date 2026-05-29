package blue.language.processor.conformance;

import blue.language.model.Node;
import blue.language.processor.HandlerMatchContext;
import blue.language.processor.HandlerProcessor;
import blue.language.processor.ProcessorExecutionContext;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.registry.RuntimeBlueIds;

import java.math.BigInteger;
import java.util.Locale;

public final class MockHandlerProcessor implements HandlerProcessor<MockHandler> {

    private final ScriptedContractsRuntime scriptedRuntime;

    public MockHandlerProcessor() {
        this(ScriptedContractsRuntime.empty());
    }

    public MockHandlerProcessor(ScriptedContractsRuntime scriptedRuntime) {
        this.scriptedRuntime = scriptedRuntime != null ? scriptedRuntime : ScriptedContractsRuntime.empty();
    }

    @Override
    public Class<MockHandler> contractType() {
        return MockHandler.class;
    }

    @Override
    public boolean matches(MockHandler contract, HandlerMatchContext context) {
        String contractPath = ScriptedContractsRuntime.contractPath(context.scopePath(), context.handlerKey());
        if (scriptedRuntime.hasHandlerScript(contractPath)) {
            return scriptedRuntime.matchesHandler(contractPath, contract, context);
        }
        return context.matchesEventPattern(contract.getEvent());
    }

    @Override
    public void execute(MockHandler contract, ProcessorExecutionContext context) {
        String contractPath = ScriptedContractsRuntime.contractPath(context.scopePath(), context.contractKey());
        if (scriptedRuntime.hasHandlerScript(contractPath)) {
            scriptedRuntime.executeHandler(contractPath, contract, context);
            return;
        }
        if ("beforeEffects".equals(contract.getFailure())) {
            throw new IllegalStateException("Mock handler failure before effects");
        }
        if (contract.getGasConsumed() != null) {
            context.consumeGas(contract.getGasConsumed());
        }
        applyPatches(contract.getPatches(), context);
        addDocumentUpdateChannel(contract, context);
        emitEvents(contract.getTriggeredEvents(), context);
        if (Boolean.TRUE.equals(contract.getEmitInvalidEvent())) {
            context.terminateFatally("Invalid emitted event: fixture invalid event");
            return;
        }
        terminate(contract, context);
        if ("afterBuffering".equals(contract.getFailure())) {
            throw new IllegalStateException("Mock handler failure after buffering");
        }
    }

    private void applyPatches(Node patches, ProcessorExecutionContext context) {
        if (patches == null || patches.getItems() == null) {
            return;
        }
        for (Node patchNode : patches.getItems()) {
            context.applyPatch(toPatch(patchNode));
        }
    }

    private JsonPatch toPatch(Node patchNode) {
        String op = stringField(patchNode, "op");
        String path = stringField(patchNode, "path");
        Node value = field(patchNode, "val");
        if ("remove".equals(op)) {
            return JsonPatch.remove(path);
        }
        if ("replace".equals(op)) {
            return JsonPatch.replace(path, value);
        }
        if ("add".equals(op)) {
            return JsonPatch.add(path, value);
        }
        throw new IllegalArgumentException("Unsupported mock patch op: " + op);
    }

    private void addDocumentUpdateChannel(MockHandler contract, ProcessorExecutionContext context) {
        String target = contract.getAddDocumentUpdateChannelAt();
        if (target == null || target.trim().isEmpty()) {
            return;
        }
        String watchPath = contract.getDocumentUpdatePath();
        Node channel = new Node()
                .type(new Node().blueId(RuntimeBlueIds.DOCUMENT_UPDATE_CHANNEL))
                .properties("path", new Node().value(watchPath != null ? watchPath : target));
        context.applyPatch(JsonPatch.add(context.resolvePointer(target), channel));
    }

    private void emitEvents(Node events, ProcessorExecutionContext context) {
        if (events == null || events.getItems() == null) {
            return;
        }
        for (Node event : events.getItems()) {
            context.emitEvent(event.clone());
        }
    }

    private void terminate(MockHandler contract, ProcessorExecutionContext context) {
        String termination = contract.getTermination();
        if (termination == null || termination.trim().isEmpty()) {
            return;
        }
        String mode = termination.trim().toLowerCase(Locale.ROOT);
        if ("fatal".equals(mode)) {
            context.terminateFatally(contract.getTerminationReason());
        } else if ("graceful".equals(mode)) {
            context.terminateGracefully(contract.getTerminationReason());
        } else {
            throw new IllegalArgumentException("Unsupported mock termination mode: " + termination);
        }
    }

    private String stringField(Node node, String key) {
        Node field = field(node, key);
        Object value = field != null ? field.getValue() : null;
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof BigInteger) {
            return value.toString();
        }
        return value != null ? String.valueOf(value) : null;
    }

    private Node field(Node node, String key) {
        if (node == null || node.getProperties() == null) {
            return null;
        }
        return node.getProperties().get(key);
    }
}
