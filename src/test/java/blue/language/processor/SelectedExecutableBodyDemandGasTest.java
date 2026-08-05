package blue.language.processor;

import blue.language.model.Node;
import blue.language.snapshot.FrozenNode;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SelectedExecutableBodyDemandGasTest {

    @Test
    void shouldGiveInlineAndPureReferenceFormsExactDemandAndGasParity() {
        // given
        Node authoredBody = executableBodyNode();
        String exactBodyBlueId =
                DirectBlueIdCalculator.calculateBlueId(authoredBody);
        FrozenNode inline =
                FrozenNode.fromResolvedNode(authoredBody);
        FrozenNode reference = FrozenNode.fromNode(
                new Node().blueId(exactBodyBlueId));
        DocumentProcessingRuntime inlineRuntime =
                new DocumentProcessingRuntime(new Node());
        DocumentProcessingRuntime referenceRuntime =
                new DocumentProcessingRuntime(new Node());

        // when
        inlineRuntime.recordSelectedExecutableBodyDemand(
                inline, "/child", "handler", "/contracts/handler/result");
        referenceRuntime.recordSelectedExecutableBodyDemand(
                reference, "/child", "handler", "/contracts/handler/result");
        ProcessingConformanceTrace inlineTrace =
                inlineRuntime.conformanceTrace();
        ProcessingConformanceTrace referenceTrace =
                referenceRuntime.conformanceTrace();

        // then
        assertEquals(
                Arrays.asList(exactBodyBlueId),
                inlineTrace.semanticDemands());
        assertEquals(
                inlineTrace.semanticDemands(),
                referenceTrace.semanticDemands());
        assertEquals(
                inlineRuntime.totalGas(),
                referenceRuntime.totalGas());
        assertEquals(0L, inlineRuntime.totalGas());
        assertEquals(
                gasProjection(inlineTrace.gas()),
                gasProjection(referenceTrace.gas()));
        assertEquals(
                java.util.Collections.emptyList(),
                gasProjection(inlineTrace.gas()));
    }

    @Test
    void shouldCarryPreAdmittedExactBodyAcrossRepeatedSelectionWithoutKernelGas() {
        // given
        FrozenNode body = executableBody();
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(new Node());

        // when
        runtime.recordSelectedExecutableBodyDemand(
                body, "/", "first", "/contracts/first/result");
        runtime.recordSelectedExecutableBodyDemand(
                body, "/", "second", "/contracts/second/result");
        ProcessingConformanceTrace trace = runtime.conformanceTrace();

        // then
        assertEquals(
                Arrays.asList(
                        DirectBlueIdCalculator.calculateBlueId(
                                body.toNode())),
                trace.semanticDemands());
        assertEquals(java.util.Collections.emptyList(), trace.gas());
    }

    @Test
    void shouldProduceNoDemandOrGasForAbsentExecutableField() {
        // given
        DocumentProcessingRuntime runtime =
                new DocumentProcessingRuntime(new Node());

        // when
        runtime.recordSelectedExecutableBodyDemand(
                null, "/", "handler", "/contracts/handler/result");

        // then
        assertEquals(
                java.util.Collections.emptyList(),
                runtime.conformanceTrace().semanticDemands());
        assertEquals(
                java.util.Collections.emptyList(),
                runtime.conformanceTrace().gas());
    }

    private static FrozenNode executableBody() {
        return FrozenNode.fromResolvedNode(executableBodyNode());
    }

    private static Node executableBodyNode() {
        return new Node()
                        .properties(
                                "patches",
                                new Node().items(
                                        new Node()
                                                .properties(
                                                        "op",
                                                        new Node().value(
                                                                "replace"))
                                                .properties(
                                                        "path",
                                                        new Node().value(
                                                                "/value"))
                                                .properties(
                                                        "val",
                                                        new Node().value(1))))
                        .properties(
                                "mode",
                                new Node().value("strict"));
    }

    private static List<String> gasProjection(
            List<GasTraceEntry> entries) {
        java.util.ArrayList<String> result =
                new java.util.ArrayList<>();
        for (GasTraceEntry entry : entries) {
            GasChargeContext context = entry.context();
            result.add(
                    entry.namespace() + ":"
                            + entry.counter() + ":"
                            + entry.quantity() + ":"
                            + context.scopePath() + ":"
                            + context.contractKey() + ":"
                            + context.logicalPath() + ":"
                            + context.reason());
        }
        return result;
    }

}
