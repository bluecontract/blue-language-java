package blue.language.processor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EmbeddedScopePlanTest {

    @Test
    void shouldDefensivelyCopyEmbeddedScopeDeclarationPaths() {
        // given
        List<String> explicitPaths = new ArrayList<>(
                Collections.singletonList("/payment"));
        List<String> collectionPaths = new ArrayList<>(
                Collections.singletonList("/lessons"));

        // when
        EmbeddedScopeDeclaration declaration =
                EmbeddedScopeDeclaration.of(
                        explicitPaths, collectionPaths);
        explicitPaths.add("/delivery");
        collectionPaths.clear();

        // then
        assertEquals(
                Collections.singletonList("/payment"),
                declaration.explicitPaths());
        assertEquals(
                Collections.singletonList("/lessons"),
                declaration.collectionPaths());
    }

    @Test
    void shouldExposeUnmodifiableEmbeddedScopeDeclarationPaths() {
        // given
        EmbeddedScopeDeclaration declaration =
                EmbeddedScopeDeclaration.of(
                        Collections.singletonList("/payment"),
                        Collections.singletonList("/lessons"));

        // when
        List<String> explicitPaths = declaration.explicitPaths();
        List<String> collectionPaths = declaration.collectionPaths();

        // then
        assertThrows(
                UnsupportedOperationException.class,
                () -> explicitPaths.add("/other"));
        assertThrows(
                UnsupportedOperationException.class,
                collectionPaths::clear);
    }

    @Test
    void shouldReuseEmptyEmbeddedScopeDeclaration() {
        // given
        List<String> noPaths = Collections.emptyList();

        // when
        EmbeddedScopeDeclaration fromEmptyLists =
                EmbeddedScopeDeclaration.of(noPaths, noPaths);
        EmbeddedScopeDeclaration fromNullLists =
                EmbeddedScopeDeclaration.of(null, null);

        // then
        assertSame(EmbeddedScopeDeclaration.empty(), fromEmptyLists);
        assertSame(EmbeddedScopeDeclaration.empty(), fromNullLists);
        assertTrue(fromEmptyLists.isEmpty());
    }

    @Test
    void shouldCompareEmbeddedScopeDeclarationsByOrderedContent() {
        // given
        EmbeddedScopeDeclaration first = EmbeddedScopeDeclaration.of(
                Arrays.asList("/payment", "/delivery"),
                Collections.singletonList("/lessons"));
        EmbeddedScopeDeclaration same = EmbeddedScopeDeclaration.of(
                Arrays.asList("/payment", "/delivery"),
                Collections.singletonList("/lessons"));
        EmbeddedScopeDeclaration reordered = EmbeddedScopeDeclaration.of(
                Arrays.asList("/delivery", "/payment"),
                Collections.singletonList("/lessons"));

        // when
        boolean equal = first.equals(same);
        boolean reorderedEqual = first.equals(reordered);

        // then
        assertTrue(equal);
        assertEquals(first.hashCode(), same.hashCode());
        assertFalse(reorderedEqual);
    }

    @Test
    void shouldDefensivelyCopyAllPlanCollections() {
        // given
        List<String> explicitDeclarations = new ArrayList<>(
                Collections.singletonList("/payment"));
        List<String> collectionDeclarations = new ArrayList<>(
                Collections.singletonList("/lessons"));
        List<String> lessonKeys = new ArrayList<>(
                Arrays.asList("lesson-a", "lesson-b"));
        Map<String, List<String>> memberKeys = new LinkedHashMap<>();
        memberKeys.put("/lessons", lessonKeys);
        Map<String, EmbeddedCollectionState> collectionStates =
                new LinkedHashMap<>();
        collectionStates.put(
                "/lessons",
                EmbeddedCollectionState.PRESENT_COLLECTION);
        List<EmbeddedConcretePath> concretePaths = new ArrayList<>(
                Arrays.asList(
                        explicit("/payment", "/payment"),
                        collection(
                                "/lessons/lesson-a",
                                "/lessons",
                                "lesson-a")));

        // when
        EmbeddedScopePlan plan = new EmbeddedScopePlan(
                "",
                explicitDeclarations,
                collectionDeclarations,
                memberKeys,
                collectionStates,
                concretePaths);
        explicitDeclarations.add("/delivery");
        collectionDeclarations.clear();
        lessonKeys.add("lesson-c");
        memberKeys.clear();
        collectionStates.clear();
        concretePaths.clear();

        // then
        assertEquals(
                Collections.singletonList("/payment"),
                plan.explicitDeclarationPaths());
        assertEquals(
                Collections.singletonList("/lessons"),
                plan.collectionDeclarationPaths());
        assertEquals(
                Arrays.asList("lesson-a", "lesson-b"),
                plan.collectionMemberKeysByDeclaration().get("/lessons"));
        assertEquals(
                EmbeddedCollectionState.PRESENT_COLLECTION,
                plan.collectionStatesByDeclaration().get("/lessons"));
        assertEquals(
                Arrays.asList("/payment", "/lessons/lesson-a"),
                plan.concreteChildPaths());
    }

    @Test
    void shouldExposeOnlyDeeplyUnmodifiablePlanCollections() {
        // given
        EmbeddedScopePlan plan = planWithTwoCollections();

        // when
        List<String> explicitDeclarations =
                plan.explicitDeclarationPaths();
        List<String> collectionDeclarations =
                plan.collectionDeclarationPaths();
        Map<String, List<String>> memberKeys =
                plan.collectionMemberKeysByDeclaration();
        Map<String, EmbeddedCollectionState> collectionStates =
                plan.collectionStatesByDeclaration();
        List<EmbeddedConcretePath> concretePaths = plan.concretePaths();
        List<String> concreteChildPaths = plan.concreteChildPaths();
        Map<String, EmbeddedPathOrigin> origins =
                plan.concretePathOrigins();

        // then
        assertThrows(
                UnsupportedOperationException.class,
                () -> explicitDeclarations.add("/other"));
        assertThrows(
                UnsupportedOperationException.class,
                collectionDeclarations::clear);
        assertThrows(
                UnsupportedOperationException.class,
                () -> memberKeys.put("/other", Collections.emptyList()));
        assertThrows(
                UnsupportedOperationException.class,
                () -> memberKeys.get("/lessons").add("lesson-c"));
        assertThrows(
                UnsupportedOperationException.class,
                () -> collectionStates.put(
                        "/other",
                        EmbeddedCollectionState.PRESENT_COLLECTION));
        assertThrows(
                UnsupportedOperationException.class,
                concretePaths::clear);
        assertThrows(
                UnsupportedOperationException.class,
                concreteChildPaths::clear);
        assertThrows(
                UnsupportedOperationException.class,
                () -> origins.put("/other", EmbeddedPathOrigin.EXPLICIT));
    }

    @Test
    void shouldRetainDeterministicDeclarationAndConcreteOrder() {
        // given
        List<String> collectionDeclarations = Arrays.asList(
                "/lessons", "/refunds");
        Map<String, List<String>> reverseInputMap = new LinkedHashMap<>();
        reverseInputMap.put(
                "/refunds", Collections.singletonList("refund-a"));
        reverseInputMap.put(
                "/lessons", Arrays.asList("lesson-a", "lesson-b"));
        List<EmbeddedConcretePath> concretePaths = Arrays.asList(
                explicit("/payment", "/payment"),
                collection(
                        "/lessons/lesson-a", "/lessons", "lesson-a"),
                collection(
                        "/lessons/lesson-b", "/lessons", "lesson-b"),
                collection(
                        "/refunds/refund-a", "/refunds", "refund-a"));

        // when
        EmbeddedScopePlan plan = new EmbeddedScopePlan(
                "",
                Collections.singletonList("/payment"),
                collectionDeclarations,
                reverseInputMap,
                presentStates(collectionDeclarations),
                concretePaths);

        // then
        assertEquals(
                collectionDeclarations,
                new ArrayList<>(
                        plan.collectionMemberKeysByDeclaration().keySet()));
        assertEquals(
                Arrays.asList(
                        "/payment",
                        "/lessons/lesson-a",
                        "/lessons/lesson-b",
                        "/refunds/refund-a"),
                plan.concreteChildPaths());
        assertEquals(
                plan.concreteChildPaths(),
                new ArrayList<>(plan.concretePathOrigins().keySet()));
    }

    @Test
    void shouldRetainConcretePathProvenance() {
        // given
        EmbeddedConcretePath explicit = explicit(
                "/payment", "/payment");
        EmbeddedConcretePath collectionMember = collection(
                "/lessons/lesson~1a",
                "/lessons",
                "lesson/a");

        // when
        EmbeddedScopePlan plan = new EmbeddedScopePlan(
                "/agreement",
                Collections.singletonList("/payment"),
                Collections.singletonList("/lessons"),
                Collections.singletonMap(
                        "/lessons",
                        Collections.singletonList("lesson/a")),
                presentStates(Collections.singletonList("/lessons")),
                Arrays.asList(explicit, collectionMember));

        // then
        assertEquals("/agreement", plan.scopePath());
        assertEquals(
                EmbeddedPathOrigin.EXPLICIT,
                plan.concretePathOrigins().get("/payment"));
        assertEquals(
                EmbeddedPathOrigin.COLLECTION_MEMBER,
                plan.concretePathOrigins().get("/lessons/lesson~1a"));
        assertEquals("/lessons", collectionMember.declarationPath());
        assertEquals("lesson/a", collectionMember.memberKey());
        assertNull(explicit.memberKey());
    }

    private static EmbeddedScopePlan planWithTwoCollections() {
        Map<String, List<String>> memberKeys = new LinkedHashMap<>();
        memberKeys.put(
                "/lessons", Collections.singletonList("lesson-a"));
        memberKeys.put(
                "/refunds", Collections.singletonList("refund-a"));
        return new EmbeddedScopePlan(
                "",
                Collections.singletonList("/payment"),
                Arrays.asList("/lessons", "/refunds"),
                memberKeys,
                presentStates(Arrays.asList("/lessons", "/refunds")),
                Arrays.asList(
                        explicit("/payment", "/payment"),
                        collection(
                                "/lessons/lesson-a",
                                "/lessons",
                                "lesson-a"),
                        collection(
                                "/refunds/refund-a",
                                "/refunds",
                                "refund-a")));
    }

    private static Map<String, EmbeddedCollectionState> presentStates(
            List<String> declarations) {
        Map<String, EmbeddedCollectionState> states = new LinkedHashMap<>();
        for (String declaration : declarations) {
            states.put(
                    declaration,
                    EmbeddedCollectionState.PRESENT_COLLECTION);
        }
        return states;
    }

    private static EmbeddedConcretePath explicit(
            String absolutePath,
            String declarationPath) {
        return new EmbeddedConcretePath(
                absolutePath,
                EmbeddedPathOrigin.EXPLICIT,
                declarationPath,
                null);
    }

    private static EmbeddedConcretePath collection(
            String absolutePath,
            String declarationPath,
            String memberKey) {
        return new EmbeddedConcretePath(
                absolutePath,
                EmbeddedPathOrigin.COLLECTION_MEMBER,
                declarationPath,
                memberKey);
    }
}
