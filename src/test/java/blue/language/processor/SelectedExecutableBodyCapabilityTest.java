package blue.language.processor;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.snapshot.FrozenNode;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SelectedExecutableBodyCapabilityTest {

    @Test
    void shouldVerifyEveryNestedSchemaNodeAndSchemaReferenceIsReachable() {
        // given
        List<String> blueIds =
                new ArrayList<>();
        for (int index = 0; index < 15; index++) {
            blueIds.add(
                    DirectBlueIdCalculator.calculateBlueId(
                            new Node().value(
                                    "schema-reference-"
                                            + index)));
        }
        Schema nested =
                new Schema()
                        .required(ref(blueIds.get(0)))
                        .minLength(ref(blueIds.get(1)))
                        .maxLength(ref(blueIds.get(2)))
                        .minimum(ref(blueIds.get(3)))
                        .maximum(ref(blueIds.get(4)))
                        .exclusiveMinimum(ref(blueIds.get(5)))
                        .exclusiveMaximum(ref(blueIds.get(6)))
                        .multipleOf(ref(blueIds.get(7)))
                        .minItems(ref(blueIds.get(8)))
                        .maxItems(ref(blueIds.get(9)))
                        .uniqueItems(ref(blueIds.get(10)))
                        .minFields(ref(blueIds.get(11)))
                        .maxFields(ref(blueIds.get(12)))
                        .enumValues(
                                Collections.singletonList(
                                        ref(blueIds.get(13))));
        Node body =
                new Node()
                        .schema(nested)
                        .properties(
                                "schemaReference",
                                new Node().schema(
                                        new Schema().blueId(
                                                blueIds.get(14))));
        SelectedExecutableBody selected =
                new SelectedExecutableBody(
                        "script",
                        "schema-body",
                        FrozenNode.fromResolvedNode(body),
                        false,
                        reference ->
                                FrozenNode.fromResolvedNode(
                                        new Node().name(
                                                reference
                                                        .getReferenceBlueId())),
                        (origin, patches) -> Collections.emptyList(),
                        (origin, event) -> event,
                        () -> true,
                        GasSchedule.contracts10());
        Set<String> expected =
                new LinkedHashSet<>(blueIds);

        // when
        Set<String> available =
                selected.availableReferenceBlueIds();
        FrozenNode opened =
                selected.materializeExactReference(
                        blueIds.get(7));

        // then
        assertEquals(expected, available);
        assertEquals(
                blueIds.get(7),
                opened.getName());
    }

    @Test
    void shouldNotGrantMixedSchemaBlueIdAsAReferenceCapability() {
        // given
        String metadataBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        new Node().value(
                                "mixed schema metadata"));
        Schema authoredMixedSchema =
                new Schema()
                        .blueId(metadataBlueId)
                        .required(new Node().value(true));
        SelectedExecutableBody selected =
                new SelectedExecutableBody(
                        "script",
                        "mixed-schema-body",
                        FrozenNode.fromResolvedNode(
                                new Node().schema(
                                        authoredMixedSchema)),
                        false,
                        reference -> FrozenNode.empty(),
                        (origin, patches) -> Collections.emptyList(),
                        (origin, event) -> event,
                        () -> true,
                        GasSchedule.contracts10());

        // when
        Set<String> available =
                selected.availableReferenceBlueIds();
        Throwable failure = captureFailure(
                () -> selected.materializeExactReference(
                        metadataBlueId));

        // then
        assertFalse(available.contains(metadataBlueId));
        assertInstanceOf(
                IllegalArgumentException.class,
                failure);
    }

    @Test
    void shouldRejectAnOversizedInitialReferenceCatalogAtomically() {
        // given
        GasSchedule schedule =
                GasSchedule.contracts10();
        int limit =
                (int) schedule.portableLimit(
                        "runtimeChildLedgerCounterKinds");
        List<Node> oversized =
                references("initial", limit + 1);

        // when
        Throwable failure = captureFailure(
                () -> new SelectedExecutableBody(
                        "script",
                        "initial-body",
                        FrozenNode.fromResolvedNode(
                                new Node().items(
                                        oversized)),
                        false,
                        reference ->
                                FrozenNode.empty(),
                        (origin, patches) -> Collections.emptyList(),
                        (origin, event) -> event,
                        () -> true,
                        schedule));
        String limitName = failure
                instanceof PortableLimitExceededException
                ? ((PortableLimitExceededException) failure)
                .limitName() : null;
        long observed = failure
                instanceof PortableLimitExceededException
                ? ((PortableLimitExceededException) failure)
                .observed() : -1L;
        long actualLimit = failure
                instanceof PortableLimitExceededException
                ? ((PortableLimitExceededException) failure)
                .limit() : -1L;

        // then
        assertInstanceOf(
                PortableLimitExceededException.class,
                failure);
        assertEquals(
                "runtimeChildLedgerCounterKinds",
                limitName);
        assertEquals(limit + 1L, observed);
        assertEquals(limit, actualLimit);
    }

    @Test
    void shouldRejectATransitiveReferenceExpansionWithoutMutatingTheCatalog() {
        // given
        GasSchedule schedule =
                GasSchedule.contracts10();
        int limit =
                (int) schedule.portableLimit(
                        "runtimeChildLedgerCounterKinds");
        String entry =
                DirectBlueIdCalculator.calculateBlueId(
                        new Node().value(
                                "transitive-entry"));
        SelectedExecutableBody selected =
                new SelectedExecutableBody(
                        "script",
                        "transitive-body",
                        FrozenNode.fromResolvedNode(
                                ref(entry)),
                        false,
                        reference ->
                                FrozenNode.fromResolvedNode(
                                        new Node().items(
                                                references(
                                                        "expanded",
                                                        limit))),
                        (origin, patches) -> Collections.emptyList(),
                        (origin, event) -> event,
                        () -> true,
                        schedule);

        // when
        Throwable failure = captureFailure(
                () -> selected.materializeExactReference(
                        entry));
        Set<String> availableAfterRejection =
                selected.availableReferenceBlueIds();
        long observed = failure
                instanceof PortableLimitExceededException
                ? ((PortableLimitExceededException) failure)
                .observed() : -1L;

        // then
        assertInstanceOf(
                PortableLimitExceededException.class,
                failure);
        assertEquals(limit + 1L, observed);
        assertEquals(
                Collections.singleton(entry),
                availableAfterRejection,
                "a rejected expansion must not mutate the capability catalog");
    }

    @Test
    void shouldOpenOnlyReferencesReachableFromSelectedBodyAndExpireWithContext() {
        // given
        Node leaf =
                new Node()
                        .name("Selected Body Leaf")
                        .description("leaf");
        BasicNodeProvider preliminary =
                new BasicNodeProvider(leaf);
        String leafBlueId =
                preliminary.getBlueIdByName(
                        "Selected Body Leaf");
        Node nested =
                new Node()
                        .name("Selected Body Nested")
                        .properties(
                                "leaf",
                                new Node().blueId(
                                        leafBlueId));
        BasicNodeProvider provider =
                new BasicNodeProvider(leaf, nested);
        String nestedBlueId =
                provider.getBlueIdByName(
                        "Selected Body Nested");
        Node body =
                new Node().properties(
                        "entry",
                        new Node().blueId(
                                nestedBlueId));
        String bodyBlueId =
                DirectBlueIdCalculator.calculateBlueId(body);

        try (Blue blue = new Blue(provider)) {
            ProcessorInvocationState execution =
                    new ProcessorInvocationState(
                            blue.getDocumentProcessor(),
                            new Node());
            execution.preflightScope("/");
            ProcessorExecutionContext context =
                    execution.createContext(
                            "/",
                            execution.bundleForScope("/"),
                            new Node(),
                            "handler",
                            FrozenNode.fromResolvedNode(
                                    new Node().properties(
                                            "script", body)),
                            false);
            context.bindSelectedExecutableBodies(
                    Collections.singletonList("script"),
                    Collections.singletonMap(
                            "script", bodyBlueId),
                    context.frozenContractNode());
            SelectedExecutableBody selected =
                    context.selectedExecutableBody(
                            "script");
            String unrelated =
                    DirectBlueIdCalculator.calculateBlueId(
                            new Node().value(
                                    "unrelated"));

            // when
            String selectedBodyBlueId =
                    selected.bodyBlueId();
            boolean nestedAvailable =
                    selected.availableReferenceBlueIds()
                            .contains(nestedBlueId);
            FrozenNode openedNested =
                    selected.materializeExactReference(
                            nestedBlueId);
            boolean leafAvailable =
                    selected.availableReferenceBlueIds()
                            .contains(leafBlueId);
            FrozenNode openedLeaf =
                    selected.materializeExactReference(
                            leafBlueId);
            FrozenNode repeatedLeaf =
                    selected.materializeExactReference(
                            leafBlueId);
            Throwable unrelatedFailure = captureFailure(
                    () -> selected
                            .materializeExactReference(
                                    unrelated));
            context.close();
            Throwable closedContextFailure = captureFailure(
                    selected::exactBody);

            // then
            assertEquals(bodyBlueId, selectedBodyBlueId);
            assertTrue(nestedAvailable);
            assertEquals(
                    "Selected Body Nested",
                    openedNested.getName());
            assertTrue(leafAvailable);
            assertEquals(
                    "Selected Body Leaf",
                    openedLeaf.getName());
            assertSame(openedLeaf, repeatedLeaf);
            assertInstanceOf(
                    IllegalArgumentException.class,
                    unrelatedFailure);
            assertInstanceOf(
                    IllegalStateException.class,
                    closedContextFailure);
        }
    }

    @Test
    void shouldVerifyCyclicMemberCanBeOpenedOnlyWithCompleteProviderProof() {
        // given
        Node cyclicSet =
                new Node().items(
                        new Node()
                                .name("Selected Cyclic A")
                                .properties(
                                        "next",
                                        new Node().blueId(
                                                "this#1")),
                        new Node()
                                .name("Selected Cyclic B")
                                .properties(
                                        "next",
                                        new Node().blueId(
                                                "this#0")));
        BasicNodeProvider provider =
                new BasicNodeProvider(
                        Collections.singletonList(
                                cyclicSet));
        String memberBlueId =
                provider.getBlueIdByName(
                        "Selected Cyclic A");

        try (Blue blue = new Blue(provider)) {
            ProcessorInvocationState execution =
                    new ProcessorInvocationState(
                            blue.getDocumentProcessor(),
                            new Node());
            execution.preflightScope("/");
            ProcessorExecutionContext context =
                    execution.createContext(
                            "/",
                            execution.bundleForScope("/"),
                            new Node(),
                            "handler",
                            FrozenNode.fromResolvedNode(
                                    new Node().properties(
                                            "script",
                                            new Node().blueId(
                                                    memberBlueId))),
                            false);
            context.bindSelectedExecutableBodies(
                    Collections.singletonList("script"),
                    Collections.singletonMap(
                            "script", memberBlueId),
                    context.frozenContractNode());

            // when
            FrozenNode member =
                    context.selectedExecutableBody(
                                    "script")
                            .materializeExactReference(
                                    memberBlueId);
            String memberName = member.getName();
            context.close();

            // then
            assertEquals(
                    "Selected Cyclic A",
                    memberName);
        }
    }

    private static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static Node ref(String blueId) {
        return new Node().blueId(blueId);
    }

    private static List<Node> references(
            String prefix,
            int count) {
        List<Node> references =
                new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            references.add(
                    ref(DirectBlueIdCalculator.calculateBlueId(
                            new Node().value(
                                    prefix + "-" + index))));
        }
        return references;
    }
}
