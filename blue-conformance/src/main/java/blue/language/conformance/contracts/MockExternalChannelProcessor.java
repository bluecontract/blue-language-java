package blue.language.conformance.contracts;

import blue.language.model.Node;
import blue.language.processor.ChannelEvaluation;
import blue.language.processor.ChannelEvaluationContext;
import blue.language.processor.ChannelLookupResult;
import blue.language.processor.ChannelProcessor;
import blue.language.processor.ExternalChannelFunctionContext;
import blue.language.processor.ExternalChannelSubscriptionFunctions;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.identity.DirectBlueIdCalculator;

import java.util.Collections;
import java.util.List;

/**
 * Closed fixture processor for {@link MockExternalChannel} contracts.
 */
final class MockExternalChannelProcessor
        implements ChannelProcessor<MockExternalChannel.Value> {

    private static final String OPTIONAL_PAYLOAD_DESCRIPTOR_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    new Node().description("Optional fixed payload."));

    private final ExternalChannelSubscriptionFunctions<MockExternalChannel.Value>
            subscriptionFunctions;

    /** Creates a fixture processor with no checkpoint-subject override. */
    public MockExternalChannelProcessor() {
        this(null);
    }

    /**
     * Applies the closed fixture-control transformation for
     * {@code checkpointSubject}. The override is returned by the immutable
     * channel function itself, so execution evidence and processing evaluate
     * the same exact subject.
     *
     * @param checkpointSubjectOverride optional subject copied into the
     *        fixture runtime
     */
    public MockExternalChannelProcessor(
            Node checkpointSubjectOverride) {
        this.subscriptionFunctions =
                new FixtureSubscriptionFunctions(
                        checkpointSubjectOverride);
    }

    @Override
    public Class<MockExternalChannel.Value> contractType() {
        return MockExternalChannel.Value.class;
    }

    @Override
    public ExternalChannelSubscriptionFunctions<MockExternalChannel.Value>
    externalSubscriptionFunctions() {
        return subscriptionFunctions;
    }

    @Override
    public ChannelEvaluation evaluate(
            MockExternalChannel.Value contract,
            ChannelEvaluationContext context) {
        String eventSubscriptionKey = eventText(
                context.event(),
                ProcessorContractConstants.KEY_SUBSCRIPTION_KEY);
        if (contract.getSubscriptionKey() != null
                && !contract.getSubscriptionKey().equals(eventSubscriptionKey)) {
            return ChannelEvaluation.noMatch();
        }
        if (Boolean.FALSE.equals(contract.getAccept())) {
            return ChannelEvaluation.noMatch();
        }
        Node declaredPayload = declaredPayload(contract);
        Node payload = declaredPayload != null
                ? declaredPayload.clone()
                : context.event();
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
            MockExternalChannel.Value> {

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
                MockExternalChannel.Value immutableContractSnapshot,
                ExternalChannelFunctionContext context) {
            String dependencyMode =
                    immutableContractSnapshot.getDependencyMode();
            if (ContractsFixtureConstants.DependencyMode.CATALOG.equals(
                    dependencyMode)) {
                context.dependOnSameScopeChannelCatalog();
            } else if (ContractsFixtureConstants.DependencyMode.EXACT.equals(
                    dependencyMode)) {
                String dependency =
                        immutableContractSnapshot
                                .getDependentChannelKey();
                if (dependency == null || dependency.isEmpty()) {
                    throw new IllegalArgumentException(
                            "dependencyMode exact requires "
                                    + "dependentChannelKey");
                }
                context.dependOnSameScopeChannel(dependency);
            } else if (dependencyMode != null
                    && !ContractsFixtureConstants.DependencyMode.NONE.equals(
                            dependencyMode)) {
                throw new IllegalArgumentException(
                        "Unsupported dependencyMode: "
                                + dependencyMode);
            }
            String key =
                    immutableContractSnapshot.getSubscriptionKey();
            return key != null && !key.isEmpty()
                    ? Collections.singletonList(key)
                    : Collections.<String>emptyList();
        }

        @Override
        public String checkpointDomainDiscriminator(
                MockExternalChannel.Value immutableContractSnapshot) {
            return immutableContractSnapshot.getCheckpointDomain();
        }

        @Override
        public boolean accepts(
                MockExternalChannel.Value immutableContractSnapshot,
                Node exactEvent,
                ExternalChannelFunctionContext context) {
            if (Boolean.FALSE.equals(
                    immutableContractSnapshot.getAccept())
                    || !immutableContractSnapshot
                    .getSubscriptionKey()
                    .equals(eventText(
                            exactEvent,
                            ProcessorContractConstants.KEY_SUBSCRIPTION_KEY))) {
                return false;
            }
            String requested =
                    immutableContractSnapshot
                            .getHandlerChannelKey();
            if (requested == null
                    || requested.isEmpty()
                    || Boolean.TRUE.equals(
                    immutableContractSnapshot
                            .getFallbackToSourceOnAbsentOrNonChannel())) {
                return true;
            }
            return context.lookupChannel(requested)
                    .isChannel();
        }

        @Override
        public Node payload(
                MockExternalChannel.Value immutableContractSnapshot,
                Node exactEvent) {
            Node declared =
                    declaredPayload(
                            immutableContractSnapshot);
            return declared != null
                    ? declared.clone()
                    : ExternalChannelSubscriptionFunctions.super
                    .payload(
                            immutableContractSnapshot,
                            exactEvent);
        }

        @Override
        public Node checkpointSubject(
                MockExternalChannel.Value immutableContractSnapshot,
                Node exactEvent,
                Node exactPayload,
                ExternalChannelFunctionContext context) {
            return checkpointSubjectOverride != null
                    ? checkpointSubjectOverride.clone()
                    : ExternalChannelSubscriptionFunctions.super
                    .checkpointSubject(
                            immutableContractSnapshot,
                            exactEvent,
                            exactPayload,
                            context);
        }

        @Override
        public String handlerChannelKey(
                MockExternalChannel.Value immutableContractSnapshot,
                Node exactEvent,
                Node exactPayload,
                ExternalChannelFunctionContext context) {
            String requested =
                    immutableContractSnapshot
                            .getHandlerChannelKey();
            if (requested == null || requested.isEmpty()) {
                return context.channelKey();
            }
            ChannelLookupResult lookup =
                    context.lookupChannel(requested);
            if (lookup.isChannel()) {
                return lookup.channel().get().channelKey();
            }
            if (Boolean.TRUE.equals(
                    immutableContractSnapshot
                            .getFallbackToSourceOnAbsentOrNonChannel())) {
                return context.channelKey();
            }
            throw new IllegalStateException(
                    "Rejected scripted handler target reached routing: "
                            + requested + ":" + lookup.kind());
        }

        @Override
        public String logicalDeliveryKey(
                MockExternalChannel.Value immutableContractSnapshot,
                Node exactEvent,
                Node exactPayload,
                ExternalChannelFunctionContext context) {
            String logicalKey =
                    immutableContractSnapshot
                            .getLogicalDeliveryKey();
            return logicalKey != null && !logicalKey.isEmpty()
                    ? logicalKey
                    : context.channelKey();
        }
    }

    /**
     * The resolved runtime type contributes its descriptive field declaration
     * when an optional arbitrary-Node payload is absent. That declaration is
     * schema metadata, not a fixed payload. Exact authored payloads remain
     * untouched, including every non-descriptor Node shape.
     */
    private static Node declaredPayload(
            MockExternalChannel.Value contract) {
        Node payload = contract != null
                ? contract.getPayload()
                : null;
        return payload != null
                && OPTIONAL_PAYLOAD_DESCRIPTOR_BLUE_ID.equals(
                DirectBlueIdCalculator.calculateBlueId(payload))
                ? null
                : payload;
    }
}
