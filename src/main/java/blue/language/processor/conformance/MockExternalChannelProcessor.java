package blue.language.processor.conformance;

import blue.language.model.Node;
import blue.language.processor.ChannelEvaluation;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.ExternalChannelSubscriptionFunctions;

import java.util.Collections;
import java.util.List;

public final class MockExternalChannelProcessor implements ChannelProcessor<MockExternalChannel> {

    private final ExternalChannelSubscriptionFunctions<MockExternalChannel>
            subscriptionFunctions;

    public MockExternalChannelProcessor() {
        this(null, null);
    }

    /**
     * The runtime parameter is retained only as constructor-level source
     * compatibility. Channel behavior is entirely declared by the selected
     * Scripted External Channel itself.
     */
    public MockExternalChannelProcessor(ScriptedContractsRuntime scriptedRuntime) {
        this(scriptedRuntime, null);
    }

    /**
     * Applies the closed fixture-control transformation for
     * {@code checkpointSubject}. The override is returned by the immutable
     * channel function itself, so execution evidence and processing evaluate
     * the same exact subject.
     */
    public MockExternalChannelProcessor(
            ScriptedContractsRuntime scriptedRuntime,
            Node checkpointSubjectOverride) {
        this.subscriptionFunctions =
                new FixtureSubscriptionFunctions(
                        checkpointSubjectOverride);
    }

    @Override
    public Class<MockExternalChannel> contractType() {
        return MockExternalChannel.class;
    }

    @Override
    public ExternalChannelSubscriptionFunctions<MockExternalChannel>
    externalSubscriptionFunctions() {
        return subscriptionFunctions;
    }

    @Override
    public ChannelEvaluation evaluate(MockExternalChannel contract, ChannelEvaluationContext context) {
        String eventSubscriptionKey = eventText(context.event(), "subscriptionKey");
        if (contract.getSubscriptionKey() != null
                && !contract.getSubscriptionKey().equals(eventSubscriptionKey)) {
            return ChannelEvaluation.noMatch();
        }
        if (Boolean.FALSE.equals(contract.getAccept())) {
            return ChannelEvaluation.noMatch();
        }
        Node payload = contract.getPayload() != null ? contract.getPayload().clone() : context.event();
        return ChannelEvaluation.match(payload, null);
    }

    private static String eventText(Node event, String field) {
        Node value = event != null && event.getProperties() != null
                ? event.getProperties().get(field)
                : null;
        return value != null && value.getValue() != null
                ? String.valueOf(value.getValue())
                : null;
    }

    private static final class FixtureSubscriptionFunctions
            implements ExternalChannelSubscriptionFunctions<
            MockExternalChannel> {

        private final Node checkpointSubjectOverride;

        private FixtureSubscriptionFunctions(
                Node checkpointSubjectOverride) {
            this.checkpointSubjectOverride =
                    checkpointSubjectOverride != null
                            ? checkpointSubjectOverride.clone()
                            : null;
        }

        @Override
        public List<String> channelKeys(
                MockExternalChannel immutableContractSnapshot) {
            String key =
                    immutableContractSnapshot.getSubscriptionKey();
            return key != null && !key.isEmpty()
                    ? Collections.singletonList(key)
                    : Collections.<String>emptyList();
        }

        @Override
        public String checkpointDomainDiscriminator(
                MockExternalChannel immutableContractSnapshot) {
            return immutableContractSnapshot.getCheckpointDomain();
        }

        @Override
        public boolean accepts(
                MockExternalChannel immutableContractSnapshot,
                Node exactEvent) {
            return !Boolean.FALSE.equals(
                    immutableContractSnapshot.getAccept())
                    && preselects(
                    immutableContractSnapshot, exactEvent);
        }

        @Override
        public Node payload(
                MockExternalChannel immutableContractSnapshot,
                Node exactEvent) {
            Node declared =
                    immutableContractSnapshot.getPayload();
            return declared != null
                    ? declared.clone()
                    : ExternalChannelSubscriptionFunctions.super
                    .payload(
                            immutableContractSnapshot,
                            exactEvent);
        }

        @Override
        public Node checkpointSubject(
                MockExternalChannel immutableContractSnapshot,
                Node exactEvent,
                Node exactPayload) {
            return checkpointSubjectOverride != null
                    ? checkpointSubjectOverride.clone()
                    : ExternalChannelSubscriptionFunctions.super
                    .checkpointSubject(
                            immutableContractSnapshot,
                            exactEvent,
                            exactPayload);
        }
    }
}
