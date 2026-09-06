package blue.language.runtime;

import blue.language.codec.BlueFormat;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.snapshot.FrozenNode;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static blue.language.model.wire.BlueLanguageConstants.LIST_TYPE_BLUE_ID;

/** Canonical processing input must never replay Source list appends. */
final class CanonicalProcessingInputTest {
    @Test
    void shouldKeepCanonicalAndAuthoredListInputRolesDistinct() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleNodes(new Node().name("Processing list prefix")
                .type(new Node().blueId(LIST_TYPE_BLUE_ID))
                .items(new Node().value("A"), new Node().value("B")));
        String prefix = provider.getBlueIdByName("Processing list prefix");
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build();
             LanguageProcessing.Scope scope = language.processing().openScope()) {
            Node source = language.codec().parseSource(
                    "type: {blueId: " + prefix + "}\nitems: [C]\n", BlueFormat.YAML);
            FrozenNode canonical = FrozenNode.fromNode(language.identity().canonicalIdentityInput(source));
            // when
            ResolvedSnapshot fromCanonical = scope.resolveCanonicalTransient(canonical, Collections.emptyList());
            ResolvedSnapshot fromAuthored = scope.resolveTransient(canonical.toNode());
            // then
            assertEquals(3, fromCanonical.resolvedRoot().getItems().size());
            assertEquals(5, fromAuthored.resolvedRoot().getItems().size());
            assertEquals(canonical.blueId(), fromCanonical.blueId());
            assertTrue(fromCanonical.isResolutionComplete());
            assertNotEquals(fromCanonical.blueId(), fromAuthored.blueId());
        }
    }

    @Test
    void shouldRetainColdCanonicalPathWithoutClaimingCompletedResolution() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build();
             LanguageProcessing.Scope scope = language.processing().openScope()) {
            FrozenNode canonical = FrozenNode.fromNode(new Node()
                    .properties("values", new Node().items(new Node().value("A")))
                    .properties("cold", new Node().type(new Node().blueId(
                            "4bcw6sKGddkWPs28vrgwXvT8Qi9Qy4TPWPYnvheTZ5Yb"))));
            // when
            ResolvedSnapshot result = scope.resolveCanonicalTransient(canonical, Collections.singleton("/cold"));
            // then
            assertEquals(canonical.blueId(), result.blueId());
            assertFalse(result.isResolutionComplete());
            assertTrue(result.frozenResolvedRoot().at("/cold")
                    .sameResolvedStructure(canonical.at("/cold")));
            assertThrows(IllegalArgumentException.class,
                    () -> scope.resolveCanonicalTransient(canonical, Collections.emptyList()));
        }
    }
    @Test
    void shouldRestoreCompactScalarTypesInsideColdCanonicalBodiesWithoutFetchingReferences() {
        // given
        String missing = "4bcw6sKGddkWPs28vrgwXvT8Qi9Qy4TPWPYnvheTZ5Yb";
        Node body = new Node().properties("op", new Node().value("replace"))
                .properties("count", new Node().value(java.math.BigInteger.ONE))
                .properties("cold", new Node().blueId(missing));
        FrozenNode canonical = FrozenNode.fromNode(new Node().properties("body", body));
        try (BlueLanguage language = BlueLanguage.builder().build();
             LanguageProcessing.Scope scope = language.processing().openScope()) {
            // when
            ResolvedSnapshot result = scope.resolveCanonicalTransient(canonical, Collections.singleton("/body"));
            // then
            assertEquals(canonical.blueId(), result.blueId());
            assertFalse(result.isResolutionComplete());
            assertEquals(blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID,
                    result.frozenResolvedRoot().at("/body/op").getType().getReferenceBlueId());
            assertEquals(blue.language.model.wire.BlueLanguageConstants.INTEGER_TYPE_BLUE_ID,
                    result.frozenResolvedRoot().at("/body/count").getType().getReferenceBlueId());
            assertTrue(result.frozenResolvedRoot().at("/body/cold").isReferenceOnly());
            assertEquals(missing, result.frozenResolvedRoot().at("/body/cold").getReferenceBlueId());
        }
    }

}
