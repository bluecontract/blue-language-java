package blue.language.snapshot;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.provider.BasicNodeProvider;
import blue.language.utils.FrozenTypeMatcher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrozenNodeStructuralInternerTest {

    @Test
    void directThenReferencedNodesDoNotLoseReferenceProvenance() {
        assertReferenceProvenanceIsIndependentOfInsertionOrder(false);
    }

    @Test
    void referencedThenDirectNodesDoNotGainReferenceProvenance() {
        assertReferenceProvenanceIsIndependentOfInsertionOrder(true);
    }

    @Test
    void snapshotResolvedViewsAreIndependentOfInsertionOrder() {
        SnapshotPair referenceFirst = snapshots(true);
        SnapshotPair materializedFirst = snapshots(false);

        assertEquals(referenceFirst.reference.blueId(), materializedFirst.reference.blueId());
        assertEquals(referenceFirst.reference.frozenCanonicalRoot().resolvedStructuralKey(),
                materializedFirst.reference.frozenCanonicalRoot().resolvedStructuralKey());
        assertEquals(referenceFirst.materialized.frozenCanonicalRoot().resolvedStructuralKey(),
                materializedFirst.materialized.frozenCanonicalRoot().resolvedStructuralKey());
        assertEquals(referenceFirst.reference.frozenResolvedRoot().resolvedStructuralKey(),
                materializedFirst.reference.frozenResolvedRoot().resolvedStructuralKey());
        assertEquals(referenceFirst.materialized.frozenResolvedRoot().resolvedStructuralKey(),
                materializedFirst.materialized.frozenResolvedRoot().resolvedStructuralKey());
        assertFalse(referenceFirst.reference.frozenResolvedRoot() == referenceFirst.materialized.frozenResolvedRoot());
    }

    @Test
    void structuralSharingOccursOnlyForExactlyEquivalentFrozenNodes() {
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        Node source = new Node().name("Equivalent").properties("field", new Node().value("value"));

        FrozenNode first = cache.freezeResolved(source);
        FrozenNode second = cache.freezeResolved(source.clone());
        FrozenNode different = cache.freezeResolved(source.clone().description("different"));

        assertSame(first, second);
        assertNotSame(first, different);
    }

    @Test
    void repeatedEquivalentSnapshotsRetainOnlyBoundedStructuralEntries() {
        BasicNodeProvider provider = new BasicNodeProvider();
        Node direct = new Node().name("Bounded Subject")
                .properties("identifier", new Node().value("subject-1"));
        provider.addSingleNodes(direct);
        String blueId = provider.getBlueIdByName("Bounded Subject");
        Blue blue = new Blue(provider);

        blue.resolveToSnapshot(direct);
        blue.resolveToSnapshot(reference(blueId));
        int retained = blue.resolvedStructuralCacheSize();
        for (int index = 0; index < 100; index++) {
            blue.resolveToSnapshot(index % 2 == 0 ? direct : reference(blueId));
        }

        assertEquals(retained, blue.resolvedStructuralCacheSize());
    }

    @Test
    void concurrentInterningCannotChooseSemanticallyDifferentFirstWriter() throws Exception {
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        ExecutorService executor = Executors.newFixedThreadPool(12);
        try {
            List<Callable<FrozenNode>> work = new ArrayList<>();
            for (int index = 0; index < 200; index++) {
                final boolean inline = index % 2 == 0;
                work.add(() -> cache.freezeResolved(new Node().name("Concurrent").inlineValue(inline)));
            }
            List<Future<FrozenNode>> futures = executor.invokeAll(work);
            for (int index = 0; index < futures.size(); index++) {
                assertEquals(index % 2 == 0, futures.get(index).get(10, TimeUnit.SECONDS).isInlineValue());
            }
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void matcherCacheDistinguishesExactRepresentationsWithSameSemanticBlueId() {
        Node directNode = new Node().name("Matcher Candidate");
        String targetId = new Blue().calculateBlueId(new Node().name("Target Identity"));
        FrozenNode direct = FrozenNode.fromResolvedNode(directNode);
        FrozenNode withReferenceProvenance = FrozenNode.fromResolvedNode(
                directNode.clone().blueId(targetId));
        FrozenNode target = FrozenNode.fromResolvedNode(reference(targetId));
        FrozenTypeMatcher matcher = new FrozenTypeMatcher(null);

        assertFalse(matcher.matchesType(direct, target));
        assertEquals(direct.blueId(), withReferenceProvenance.blueId());
        assertFalse(direct.resolvedStructuralKey().equals(
                withReferenceProvenance.resolvedStructuralKey()));
        assertFalse(direct.sameResolvedStructure(withReferenceProvenance));
        assertTrue(matcher.matchesType(withReferenceProvenance, target));
    }

    @Test
    void directResolvedStructureComparisonMatchesRefreezeNormalization() {
        Node source = new Node().name("Subject")
                .description("description")
                .schema(new Schema().required(true))
                .properties("field", new Node().value("value"));
        FrozenNode canonical = FrozenNode.fromNode(source);
        FrozenNode resolved = FrozenNode.fromResolvedNode(source.clone());

        assertFalse(canonical.resolvedStructuralKey().equals(resolved.resolvedStructuralKey()));
        assertTrue(canonical.sameResolvedStructure(resolved));
        assertLegacyNormalizationParity(canonical, resolved);

        FrozenNode listElement = FrozenNode.fromNode(
                new Node().items(new Node().value("item"))).item(0);
        FrozenNode rootValue = FrozenNode.fromNode(new Node().value("item"));
        assertTrue(listElement.sameResolvedStructure(rootValue),
                "list-element construction context is normalized away by refreezing");
        assertLegacyNormalizationParity(listElement, rootValue);
    }

    @Test
    void directResolvedStructureComparisonPreservesPropertyOrder() {
        Map<String, Node> firstOrder = new LinkedHashMap<>();
        firstOrder.put("a", new Node().value(1));
        firstOrder.put("b", new Node().value(2));
        Map<String, Node> secondOrder = new LinkedHashMap<>();
        secondOrder.put("b", new Node().value(2));
        secondOrder.put("a", new Node().value(1));
        FrozenNode first = FrozenNode.fromResolvedNode(new Node().properties(firstOrder));
        FrozenNode second = FrozenNode.fromResolvedNode(new Node().properties(secondOrder));

        assertEquals(first.blueId(), second.blueId());
        assertFalse(first.sameResolvedStructure(second));
        assertLegacyNormalizationParity(first, second);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("observableFieldVariants")
    void structuralKeyIncludesEveryObservableField(String field, UnaryOperator<Node> variant) {
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        Node base = new Node().name("Base");

        FrozenNode first = cache.freezeResolved(base);
        FrozenNode second = cache.freezeResolved(variant.apply(base.clone()));

        assertNotSame(first, second, field + " must participate in exact structural identity");
        assertFalse(first.sameResolvedStructure(second),
                field + " must participate in direct resolved structure comparison");
        assertLegacyNormalizationParity(first, second);
    }

    private static Stream<Arguments> observableFieldVariants() {
        return Stream.of(
                Arguments.of("referenceBlueId", (UnaryOperator<Node>) node -> node.blueId("reference-id")),
                Arguments.of("name", (UnaryOperator<Node>) node -> node.name("Different")),
                Arguments.of("description", (UnaryOperator<Node>) node -> node.description("description")),
                Arguments.of("type", (UnaryOperator<Node>) node -> node.type(reference("type-id"))),
                Arguments.of("itemType", (UnaryOperator<Node>) node -> node.itemType(reference("item-type-id"))),
                Arguments.of("keyType", (UnaryOperator<Node>) node -> node.keyType(reference("key-type-id"))),
                Arguments.of("valueType", (UnaryOperator<Node>) node -> node.valueType(reference("value-type-id"))),
                Arguments.of("value", (UnaryOperator<Node>) node -> node.value("value")),
                Arguments.of("items", (UnaryOperator<Node>) node -> node.items(new Node().value("item"))),
                Arguments.of("properties", (UnaryOperator<Node>) node -> node.properties("field", new Node().value("value"))),
                Arguments.of("contracts", (UnaryOperator<Node>) node -> node.contracts(new Node().properties("contract", new Node().value(true)))),
                Arguments.of("schema", (UnaryOperator<Node>) node -> node.schema(new Schema().required(true))),
                Arguments.of("mergePolicy", (UnaryOperator<Node>) node -> node.mergePolicy("replace")),
                Arguments.of("previousBlueId", (UnaryOperator<Node>) node -> node.previousBlueId("previous-id")),
                Arguments.of("position", (UnaryOperator<Node>) node -> node.position(3)),
                Arguments.of("blue", (UnaryOperator<Node>) node -> node.blue(new Node().value("directive"))),
                Arguments.of("inlineValue", (UnaryOperator<Node>) node -> node.inlineValue(true))
        );
    }

    private static void assertLegacyNormalizationParity(FrozenNode left, FrozenNode right) {
        boolean expected = FrozenNode.fromResolvedNode(left.toNode()).resolvedStructuralKey().equals(
                FrozenNode.fromResolvedNode(right.toNode()).resolvedStructuralKey());
        assertEquals(expected, left.sameResolvedStructure(right));
        assertEquals(expected, right.sameResolvedStructure(left));
    }

    private void assertReferenceProvenanceIsIndependentOfInsertionOrder(boolean referenceFirst) {
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        Node direct = new Node().name("Subject");
        String blueId = new Blue().calculateBlueId(direct);
        Node referencedView = direct.clone().blueId(blueId);

        FrozenNode first = cache.freezeResolved(referenceFirst ? referencedView : direct);
        FrozenNode second = cache.freezeResolved(referenceFirst ? direct : referencedView);
        FrozenNode referenced = referenceFirst ? first : second;
        FrozenNode materialized = referenceFirst ? second : first;

        assertEquals(blueId, referenced.getReferenceBlueId());
        assertNull(materialized.getReferenceBlueId());
        assertNotSame(referenced, materialized);
    }

    private SnapshotPair snapshots(boolean referenceFirst) {
        BasicNodeProvider provider = new BasicNodeProvider();
        Node direct = new Node().name("Snapshot Subject").properties("identifier", new Node().value("subject-1"));
        provider.addSingleNodes(direct);
        String blueId = provider.getBlueIdByName("Snapshot Subject");
        Blue blue = new Blue(provider);
        ResolvedSnapshot reference;
        ResolvedSnapshot materialized;
        if (referenceFirst) {
            reference = blue.resolveToSnapshot(reference(blueId));
            materialized = blue.resolveToSnapshot(direct);
        } else {
            materialized = blue.resolveToSnapshot(direct);
            reference = blue.resolveToSnapshot(reference(blueId));
        }
        return new SnapshotPair(reference, materialized);
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static final class SnapshotPair {
        private final ResolvedSnapshot reference;
        private final ResolvedSnapshot materialized;

        private SnapshotPair(ResolvedSnapshot reference, ResolvedSnapshot materialized) {
            this.reference = reference;
            this.materialized = materialized;
        }
    }
}
