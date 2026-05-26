package blue.language.processor.conformance;

import blue.language.model.Node;
import blue.language.processor.ChannelEvaluation;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;

public final class MockExternalChannelProcessor implements ChannelProcessor<MockExternalChannel> {

    private final ScriptedContractsRuntime scriptedRuntime;

    public MockExternalChannelProcessor() {
        this(ScriptedContractsRuntime.empty());
    }

    public MockExternalChannelProcessor(ScriptedContractsRuntime scriptedRuntime) {
        this.scriptedRuntime = scriptedRuntime != null ? scriptedRuntime : ScriptedContractsRuntime.empty();
    }

    @Override
    public Class<MockExternalChannel> contractType() {
        return MockExternalChannel.class;
    }

    @Override
    public ChannelEvaluation evaluate(MockExternalChannel contract, ChannelEvaluationContext context) {
        String contractPath = ScriptedContractsRuntime.contractPath(context.scopePath(), context.bindingKey());
        if (scriptedRuntime.hasChannelScript(contractPath)) {
            return scriptedRuntime.evaluateChannel(contractPath, context);
        }
        if (Boolean.FALSE.equals(contract.getAccept())) {
            return ChannelEvaluation.noMatch();
        }
        Node payload = contract.getPayload() != null ? contract.getPayload().clone() : context.event();
        return ChannelEvaluation.match(payload, null);
    }
}
