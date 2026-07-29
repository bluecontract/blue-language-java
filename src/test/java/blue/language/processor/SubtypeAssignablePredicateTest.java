package blue.language.processor;

import blue.language.Blue;
import blue.language.BlueLanguageErrorCategory;
import blue.language.BlueLanguageErrorClassifier;
import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.FrozenTypeMatcher;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SubtypeAssignablePredicateTest {

    @Test
    void shouldVerifyExactDirectAndDeepLineageUsesVerifiedBlueTypes() {
        // given
        Map<String, Node> definitions = new LinkedHashMap<>();
        Node base = new Node()
                .name("Assignable predicate base")
                .properties(
                        "family",
                        new Node().value("selected"));
        String baseId = add(definitions, base);
        Node direct = new Node()
                .name("Assignable predicate direct")
                .type(reference(baseId));
        String directId = add(definitions, direct);
        Node deep = new Node()
                .name("Assignable predicate deep")
                .type(reference(directId));
        String deepId = add(definitions, deep);
        Node unrelated = new Node()
                .name("Assignable predicate unrelated")
                .properties(
                        "family",
                        new Node().value("unrelated"));
        String unrelatedId = add(definitions, unrelated);
        FrozenTypeMatcher matcher = matcher(definitions);
        long limit = GasSchedule.contracts10()
                .portableLimit("typeChainEdges");

        // when
        boolean exact = matcher.isSubtypeOrSame(
                frozenReference(baseId),
                frozenReference(baseId),
                limit);
        boolean directSubtype = matcher.isSubtypeOrSame(
                frozenReference(directId),
                frozenReference(baseId),
                limit);
        boolean deepSubtype = matcher.isSubtypeOrSame(
                frozenReference(deepId),
                frozenReference(baseId),
                limit);
        boolean unrelatedSubtype = matcher.isSubtypeOrSame(
                frozenReference(unrelatedId),
                frozenReference(baseId),
                limit);

        // then
        assertTrue(exact);
        assertTrue(directSubtype);
        assertTrue(deepSubtype);
        assertFalse(unrelatedSubtype);
    }

    @Test
    void shouldVerifyPortableTypeChainLimitFailsClosed() {
        // given
        Map<String, Node> definitions = new LinkedHashMap<>();
        Node root = new Node()
                .name("Assignable bounded root")
                .properties(
                        "family",
                        new Node().value("bounded"));
        String rootId = add(definitions, root);
        long limit = GasSchedule.contracts10()
                .portableLimit("typeChainEdges");
        String parent = rootId;
        for (int index = 0; index <= limit; index++) {
            Node child = new Node()
                    .name("Assignable bounded child " + index)
                    .type(reference(parent));
            parent = add(definitions, child);
        }

        FrozenTypeMatcher matcher = matcher(definitions);
        String candidate = parent;

        // when
        Throwable failure = captureFailure(
                () -> matcher.isSubtypeOrSame(
                        frozenReference(candidate),
                        frozenReference(rootId),
                        limit));
        String failureMessage =
                failure == null ? null : failure.getMessage();

        // then
        assertInstanceOf(
                IllegalStateException.class,
                failure);
        assertTrue(failureMessage.contains(
                "Exact type hierarchy exceeds " + limit));
    }

    @Test
    void shouldVerifyVerifiedCyclicTypeEvidenceFailsClosed() {
        // given
        Node firstPlaceholder = new Node()
                .name("Assignable cyclic type")
                .type(reference("this#1"));
        Node secondPlaceholder = new Node()
                .name("Assignable cyclic companion")
                .type(reference("this#0"));
        BasicNodeProvider provider =
                new BasicNodeProvider(
                        Collections.singletonList(
                                new Node().items(
                                        firstPlaceholder,
                                        secondPlaceholder)));
        String cyclicTypeBlueId =
                provider.getBlueIdByName(
                        "Assignable cyclic type");
        Blue blue = ProcessorTestSupport.blue(provider);
        ExternalChannelFunctionEvaluation.MatcherSession session =
                ExternalChannelFunctionEvaluation
                        .verifiedMatcherSessions(
                                blue.getDocumentProcessor()
                                        .snapshotManager())
                        .open();

        Throwable failure = null;
        BlueLanguageErrorCategory category = null;
        try {
            // when
            failure = captureFailure(
                    () -> session.isAssignableToType(
                            cyclicTypeBlueId,
                            BlueIdCalculator.calculateBlueId(
                                    new Node().name(
                                            "Unrelated base"))));
            category = failure instanceof RuntimeException
                    ? BlueLanguageErrorClassifier.classify(
                    (RuntimeException) failure)
                    : null;
        } finally {
            session.close();
            blue.close();
        }

        // then
        assertInstanceOf(
                RuntimeException.class,
                failure);
        assertEquals(
                BlueLanguageErrorCategory.TypeCycle,
                category);
    }

    private static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static FrozenTypeMatcher matcher(
            Map<String, Node> definitions) {
        return FrozenTypeMatcher
                .withVerifiedReferenceMaterializer(reference -> {
                    Node definition = definitions.get(
                            reference.getReferenceBlueId());
                    if (definition == null) {
                        throw new IllegalStateException(
                                "Missing exact type definition: "
                                        + reference
                                        .getReferenceBlueId());
                    }
                    return FrozenNode.fromNode(
                            definition.clone());
                });
    }

    private static String add(
            Map<String, Node> definitions,
            Node definition) {
        String blueId =
                BlueIdCalculator.calculateBlueId(definition);
        definitions.put(blueId, definition);
        return blueId;
    }

    private static FrozenNode frozenReference(
            String blueId) {
        return FrozenNode.fromNode(reference(blueId));
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }
}
