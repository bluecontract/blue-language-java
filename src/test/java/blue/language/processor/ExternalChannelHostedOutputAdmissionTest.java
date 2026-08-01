package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExternalChannelHostedOutputAdmissionTest {

    private static final Node CHANNEL_TYPE =
            new Node().name(
                    "Generic Hosted Output Admission Channel");
    private static final String CHANNEL_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(
                    CHANNEL_TYPE);

    @Test
    void shouldVerifyPayloadAndCheckpointSubjectAreAutomaticallyAdmittedOnce() {
        // given
        Node output = output();

        // when
        try (EvaluationFixture fixture =
                     new EvaluationFixture(
                             output, false)) {
            EvaluationResult result =
                    fixture.evaluate();

            // then
            assertEquals(
                    result.evaluation.payload().blueId(),
                    result.evaluation
                            .checkpointSubjectBlueId());
            assertEquals(
                    2L,
                    hostedQuantity(
                            result.trace,
                            "nodeIdentityEstablished"),
                    "the two-node output shared by payload and checkpoint "
                            + "subject must be constructed exactly once");
            assertTrue(
                    hostedGas(result.trace) > 0L,
                    "generic hosted outputs must cross the semantic "
                            + "admission meter without runtime opt-in");
        }
    }

    @Test
    void shouldVerifyInlineAndVerifiedReferenceOutputsHaveIdenticalIdentityAndGas() {
        // given
        Node output = output();

        EvaluationResult inline;
        try (EvaluationFixture fixture =
                     new EvaluationFixture(
                             output, false)) {
            inline = fixture.evaluate();
        }

        // when
        EvaluationResult referenced;
        try (EvaluationFixture fixture =
                     new EvaluationFixture(
                             output, true)) {
            referenced = fixture.evaluate();
        }

        // then
        assertEquals(
                inline.evaluation.payload().blueId(),
                referenced.evaluation.payload().blueId());
        assertEquals(
                inline.evaluation
                        .checkpointSubjectBlueId(),
                referenced.evaluation
                        .checkpointSubjectBlueId());
        assertEquals(
                hostedProjection(inline.trace),
                hostedProjection(referenced.trace),
                "a verified reference and its inline exact content must "
                        + "produce the same semantic admission charges");
    }

    @Test
    void shouldVerifyVerifiedCyclicMemberOutputKeepsItsExactOpaqueIdentity() {
        // given
        Node cyclicSet =
                new Node().items(
                        new Node()
                                .name("Hosted Cyclic Output A")
                                .properties(
                                        "next",
                                        new Node().blueId(
                                                "this#1")),
                        new Node()
                                .name("Hosted Cyclic Output B")
                                .properties(
                                        "next",
                                        new Node().blueId(
                                                "this#0")));
        BasicNodeProvider provider =
                new BasicNodeProvider(cyclicSet);
        String memberBlueId =
                provider.getBlueIdByName(
                        "Hosted Cyclic Output A");

        // when
        try (EvaluationFixture fixture =
                     new EvaluationFixture(
                             provider,
                             new Node().blueId(
                                     memberBlueId))) {
            EvaluationResult result =
                    fixture.evaluate();

            // then
            assertTrue(
                    result.evaluation.payload()
                            .isReferenceOnly());
            assertEquals(
                    memberBlueId,
                    result.evaluation.payload()
                            .getReferenceBlueId());
            assertEquals(
                    memberBlueId,
                    result.evaluation
                            .checkpointSubjectBlueId());
            assertEquals(
                    0L,
                    hostedGas(result.trace),
                    "a processor-issued proven cyclic member edge has no "
                            + "standalone semantic construction to charge");
        }
    }

    private static long hostedQuantity(
            ProcessingConformanceTrace trace,
            String counter) {
        long quantity = 0L;
        for (GasTraceEntry entry : trace.gas()) {
            if ("hosted-runtime-output".equals(
                    entry.reason())
                    && counter.equals(entry.counter())) {
                quantity += entry.quantity();
            }
        }
        return quantity;
    }

    private static long hostedGas(
            ProcessingConformanceTrace trace) {
        long gas = 0L;
        for (GasTraceEntry entry : trace.gas()) {
            if ("hosted-runtime-output".equals(
                    entry.reason())) {
                gas += entry.subtotal();
            }
        }
        return gas;
    }

    private static List<String> hostedProjection(
            ProcessingConformanceTrace trace) {
        List<String> projection =
                new ArrayList<>();
        for (GasTraceEntry entry : trace.gas()) {
            if ("hosted-runtime-output".equals(
                    entry.reason())) {
                projection.add(
                        entry.namespace()
                                + "|" + entry.counter()
                                + "|" + entry.quantity()
                                + "|" + entry.weight()
                                + "|" + entry.subtotal());
            }
        }
        return projection;
    }

    private static Node output() {
        return new Node()
                .name("Hosted Output Value")
                .properties(
                        "message",
                        new Node().value(
                                "same semantic value"));
    }

    private static Node event() {
        return new Node().properties(
                "subscriptionKey",
                new Node().value("topic"));
    }

    private static Node document() {
        Node channel =
                new Node()
                        .type(new Node().blueId(
                                CHANNEL_TYPE_BLUE_ID))
                        .properties(
                                "order",
                                new Node().value(0));
        return new Node().contracts(
                new Node().properties(
                        "source", channel));
    }

    public static final class HostedOutputChannel
            extends ChannelContract {
    }

    private static final class HostedOutputProcessor
            implements ChannelProcessor<HostedOutputChannel> {
        private final Node suppliedOutput;

        private HostedOutputProcessor(
                Node suppliedOutput) {
            this.suppliedOutput =
                    suppliedOutput.clone();
        }

        @Override
        public Class<HostedOutputChannel>
        contractType() {
            return HostedOutputChannel.class;
        }

        @Override
        public ExternalChannelSubscriptionFunctions<
                HostedOutputChannel>
        externalSubscriptionFunctions() {
            return new ExternalChannelSubscriptionFunctions<
                    HostedOutputChannel>() {
                @Override
                public List<String> channelKeys(
                        HostedOutputChannel contract) {
                    return Collections.singletonList(
                            "topic");
                }

                @Override
                public String checkpointDomainDiscriminator(
                        HostedOutputChannel contract) {
                    return "hosted-output-admission-v1";
                }

                @Override
                public Node payload(
                        HostedOutputChannel contract,
                        Node exactEvent) {
                    return suppliedOutput();
                }

                @Override
                public Node checkpointSubject(
                        HostedOutputChannel contract,
                        Node exactEvent,
                        Node exactPayload) {
                    return suppliedOutput();
                }
            };
        }

        private Node suppliedOutput() {
            return suppliedOutput.clone();
        }
    }

    private static final class EvaluationFixture
            implements AutoCloseable {
        private final Blue blue;
        private final DocumentProcessor processor;

        private EvaluationFixture(
                Node output,
                boolean returnReference) {
            this(
                    new BasicNodeProvider(output),
                    returnReference
                            ? new Node().blueId(
                                    DirectBlueIdCalculator
                                            .calculateBlueId(
                                                    output))
                            : output);
        }

        private EvaluationFixture(
                BasicNodeProvider provider,
                Node suppliedOutput) {
            this.blue =
                    ProcessorTestSupport.blue(
                            provider);
            HostedOutputProcessor hosted =
                    new HostedOutputProcessor(
                            suppliedOutput);
            blue.registerExternalContractType(
                    CHANNEL_TYPE_BLUE_ID,
                    CHANNEL_TYPE,
                    hosted);
            this.processor =
                    blue.getDocumentProcessor();
        }

        private EvaluationResult evaluate() {
            ResolvedSnapshot snapshot =
                    processor.snapshotManager()
                            .fromDocumentTransient(
                                    document());
            ContractBundle bundle =
                    processor.contractLoader()
                            .load(snapshot, "/");
            ProcessorInvocationState execution =
                    new ProcessorInvocationState(
                            processor, snapshot);
            RuntimeWorkSession phase =
                    execution.runtime()
                            .newRuntimeWorkSession(
                                    blue);
            ExternalChannelFunctionEvaluation
                    evaluation =
                    ExternalChannelFunctionEvaluation
                            .evaluate(
                                    processor.registry(),
                                    processor
                                            .contractConverter(),
                                    ExternalChannelFunctionEvaluation
                                            .verifiedMatcherSessions(
                                                    processor
                                                            .snapshotManager()),
                                    bundle,
                                    bundle
                                            .effectiveContractSnapshot(
                                                    "source"),
                                    event(),
                                    null,
                                    phase);
            return new EvaluationResult(
                    evaluation,
                    execution.runtime()
                            .conformanceTrace());
        }

        @Override
        public void close() {
            blue.close();
        }
    }

    private static final class EvaluationResult {
        private final ExternalChannelFunctionEvaluation
                evaluation;
        private final ProcessingConformanceTrace trace;

        private EvaluationResult(
                ExternalChannelFunctionEvaluation
                        evaluation,
                ProcessingConformanceTrace trace) {
            this.evaluation = evaluation;
            this.trace = trace;
        }
    }
}
