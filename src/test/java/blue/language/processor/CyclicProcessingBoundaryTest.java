package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import blue.language.provider.VerifyingNodeProvider;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class CyclicProcessingBoundaryTest {

    @Test
    void exactMaterializationRetainsCyclicSetProofWithoutStandaloneHash() {
        CyclicFixture fixture = new CyclicFixture();
        try (Blue blue = new Blue(fixture.provider)) {
            FrozenNode materialized =
                    blue.getDocumentProcessor()
                            .snapshotManager()
                            .materializeVerifiedExactReference(
                                    FrozenNode.fromNode(
                                            new Node().blueId(
                                                    fixture.memberBlueId)));

            assertFalse(materialized.isReferenceOnly());
            assertEquals(
                    "member-a",
                    materialized.toNode().getAsText("/label"));
            assertFalse(
                    fixture.memberBlueId.equals(
                            materialized.blueId()),
                    "a cyclic member must not claim an independently "
                            + "calculated ordinary BlueId");
        }
    }

    @Test
    void ordinaryContentCannotCounterfeitCyclicMemberProof() {
        Node ordinary = new Node().value("ordinary");
        String ordinaryBlueId =
                BlueIdCalculator.calculateBlueId(ordinary);
        BasicNodeProvider provider =
                new BasicNodeProvider(ordinary);
        VerifyingNodeProvider verifying =
                new VerifyingNodeProvider(provider);

        assertEquals(
                blue.language.provider.NodeProviderOutcome
                        .INVALID_EVIDENCE,
                verifying.fetchResultByBlueId(
                        ordinaryBlueId + "#0")
                        .outcome());
    }

    @Test
    void snapshotEntryRejectsTopLevelCyclicMemberBeforeExecution() {
        CyclicFixture fixture = new CyclicFixture();
        try (Blue blue = new Blue(fixture.provider)) {
            Node member =
                    fixture.provider
                            .fetchByBlueId(
                                    fixture.memberBlueId)
                            .get(0)
                            .clone()
                            .blueId(null);
            ResolvedSnapshot snapshot =
                    new ResolvedSnapshot(
                            FrozenNode.fromNode(
                                    new Node().blueId(
                                            fixture.memberBlueId)),
                            FrozenNode.fromResolvedNode(member),
                            fixture.memberBlueId);

            ProcessingDebugResult result =
                    blue.getDocumentProcessor()
                            .processDocumentWithTrace(
                                    snapshot,
                                    new Node().value("event"));

            assertEquals(
                    ProcessorStatus.INVALID_PROCESSING_DOCUMENT,
                    result.processResult().status());
            assertEquals(
                    fixture.memberBlueId,
                    result.resultingSnapshot().blueId());
        }
    }

    private static final class CyclicFixture {
        private final BasicNodeProvider provider;
        private final String memberBlueId;

        private CyclicFixture() {
            Node cyclicSet = new Node().items(
                    new Node()
                            .name("Processing Cyclic A")
                            .properties(
                                    "label",
                                    new Node().value(
                                            "member-a"))
                            .properties(
                                    "next",
                                    new Node().blueId(
                                            "this#1")),
                    new Node()
                            .name("Processing Cyclic B")
                            .properties(
                                    "label",
                                    new Node().value(
                                            "member-b"))
                            .properties(
                                    "next",
                                    new Node().blueId(
                                            "this#0")));
            provider = new BasicNodeProvider(
                    Collections.singletonList(
                            cyclicSet));
            memberBlueId =
                    provider.getBlueIdByName(
                            "Processing Cyclic A");
        }
    }
}
