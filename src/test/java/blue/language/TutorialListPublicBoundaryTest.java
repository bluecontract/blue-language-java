package blue.language;

import blue.language.codec.BlueFormat;
import blue.language.model.Node;
import blue.language.merge.ResolvedSnapshot;
import blue.language.resolve.MinimizedOverlayBuilder;
import blue.language.runtime.BlueLanguage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Public Language reproductions of list tutorial failures observed through MyOS. */
final class TutorialListPublicBoundaryTest {
    @Test
    void shouldPrepareConstrainedListWithoutInventingItems() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            // when
            Node declaration = source(language, "type: List\nschema: {minItems: 1, maxItems: 2}\n");
            Node prepared = language.resolution().resolveDefinition(declaration);
            // then
            assertNull(prepared.getItems());
            assertNotNull(language.identity().sourceDocumentBlueId(declaration));
            assertThrows(IllegalArgumentException.class, () -> language.resolution()
                    .resolveDefinition(source(language, "type: List\nschema: {minItems: 1}\nitems: []\n")));
        }
    }

    @Test
    void shouldRetainEntireInheritedListInCanonicalInput() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            // when
            Node canonical = language.identity().canonicalIdentityInput(source(language,
                    "type:\n  entries:\n    type: List\n    items: [A, B]\n"));
            // then
            assertNotNull(canonical.getProperties());
            assertNotNull(canonical.getProperties().get("entries"));
            assertEquals(2, canonical.getProperties().get("entries").getItems().size());
        }
    }

    @Test
    void shouldAppendPlainPositionalItemsAfterInheritedPrefix() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            // when
            Node resolved = language.resolution().resolve(source(language,
                    "type:\n  type: List\n  items: [A, B]\nitems: [C]\n"));
            // then
            assertEquals(3, resolved.getItems().size());
            assertEquals("A", resolved.getItems().get(0).getValue());
            assertEquals("B", resolved.getItems().get(1).getValue());
            assertEquals("C", resolved.getItems().get(2).getValue());
        }
    }

    @Test
    void shouldAppendNullAsRetainedPlaceholder() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            // when
            Node canonical = language.identity().canonicalIdentityInput(source(language,
                    "type:\n  type: List\n  items: [A, B]\nitems: [null]\n"));
            // then
            assertEquals(3, canonical.getItems().size());
            assertTrue(blue.language.model.Nodes.isEmptyPlaceholder(canonical.getItems().get(2)));
        }
    }

    @Test
    void shouldRoundTripWholeObjectReplacementWithoutChangingFixedFieldRules() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            // when
            String prefix = "type:\n  type: List\n  items:\n    - code: old\n      score: 1\n";
            // then
            assertRoundTrip(language, prefix + "items:\n  - $pos: 0\n    $replace: {code: replacement, score: 20}\n");
            assertThrows(IllegalArgumentException.class, () -> language.resolution().resolve(source(language,
                    prefix + "items:\n  - $pos: 0\n    score: 20\n")));
        }
    }

    @Test
    void shouldRoundTripNestedListReplacement() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            // when
            // then
            assertRoundTrip(language, "type:\n  type: List\n  items: [[A]]\nitems:\n  - $pos: 0\n    $replace: {items: [B, C]}\n");
        }
    }

    @Test
    void shouldKeepListControlNamesOrdinaryOutsideListElements() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            // when
            Node value = source(language,
                    "$pos: 0\n$previous: ordinary\n$replace: field\n$empty: false\n");
            Node canonical = language.identity().canonicalIdentityInput(value);
            // then
            assertEquals(4, canonical.getProperties().size());
            assertEquals("ordinary", canonical.getProperties().get("$previous").getValue());
        }
    }

    @Test
    void shouldRejectPositionTargetingAnItemAppendedInSameOverlay() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            // when
            // then
            assertThrows(IllegalArgumentException.class, () -> language.resolution().resolve(source(language,
                    "type:\n  type: List\n  items: [A]\nitems:\n  - B\n  - $pos: 1\n    value: C\n")));
        }
    }

    @Test
    void shouldRejectPositionWithoutAnInheritedPrefix() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            // when
            Node noPrefix = source(language, "type: List\nitems: [{$pos: 0, value: A}]\n");
            // then
            assertThrows(IllegalArgumentException.class,
                    () -> language.resolution().resolve(noPrefix));
        }
    }

    @Test
    void shouldDistinguishFullCanonicalPayloadFromSameBytesAuthoredAsAppend() {
        // given
        for (String policy : new String[] {"positional", "append-only"}) {
            blue.language.preprocess.provider.BasicNodeProvider provider =
                    new blue.language.preprocess.provider.BasicNodeProvider();
            provider.addSingleNodes(new Node().name("Tutorial prefix")
                    .type(new Node().blueId(blue.language.model.wire.BlueLanguageConstants.LIST_TYPE_BLUE_ID))
                    .mergePolicy(policy).items(new Node().value("A"), new Node().value("B")));
            String base = provider.getBlueIdByName("Tutorial prefix");
            provider.addSingleNodes(new Node().name("Tutorial exact payload")
                    .type(new Node().blueId(base))
                    .items(new Node().value("A"), new Node().value("B"), new Node().value("C")));
            String exact = provider.getBlueIdByName("Tutorial exact payload");
            try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
                // when
                Node materialized = language.snapshots().load(exact).resolvedRoot();
                Node authored = language.resolution().resolve(source(language,
                        "type: {blueId: " + base + "}\nitems: [A, B, C]\n"));
                // then
                assertEquals(3, materialized.getItems().size());
                assertEquals(5, authored.getItems().size());
                assertEquals("A", authored.getItems().get(2).getValue());
                assertEquals("C", authored.getItems().get(4).getValue());
            }
        }
    }

    @Test
    void shouldMaterializeCanonicalPositionalReplacementFromExactProvider() {
        // given
        blue.language.preprocess.provider.BasicNodeProvider provider =
                new blue.language.preprocess.provider.BasicNodeProvider();
        provider.addSingleNodes(new Node().name("Tutorial replacement prefix")
                .type(new Node().blueId(blue.language.model.wire.BlueLanguageConstants.LIST_TYPE_BLUE_ID))
                .items(new Node().value("A"), new Node().value("B")));
        String base = provider.getBlueIdByName("Tutorial replacement prefix");
        Node canonical;
        try (BlueLanguage writer = BlueLanguage.builder().nodeProvider(provider).build()) {
            canonical = writer.identity().canonicalIdentityInput(source(writer,
                    "type: {blueId: " + base + "}\nitems: [{$pos: 1, value: C}]\n"));
        }
        String exact = blue.language.identity.DirectBlueIdCalculator.calculateBlueId(canonical);
        provider.addSingleNodes(canonical);
        // when
        try (BlueLanguage reader = BlueLanguage.builder().nodeProvider(provider).build()) {
            Node materialized = reader.snapshots().load(exact).resolvedRoot();
            // then
            assertEquals(2, materialized.getItems().size());
            assertEquals("C", materialized.getItems().get(1).getValue());
        }
    }

    @Test
    void shouldRejectIncompatibleReplacementRegardlessOfInlineOrReferenceEncoding() {
        // given
        blue.language.preprocess.provider.BasicNodeProvider provider =
                new blue.language.preprocess.provider.BasicNodeProvider();
        provider.addSingleNodes(new Node().name("Tutorial incompatible object")
                .properties("amount", new Node().value(3)));
        String exact = provider.getBlueIdByName("Tutorial incompatible object");
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            String prefix = "type: {type: List, items: [A, B]}\nitems: [{$pos: 1, $replace: ";
            // when
            Node inline = source(language, prefix + "{amount: 3}}]\n");
            Node reference = source(language, prefix + "{blueId: " + exact + "}}]\n");
            // then
            assertThrows(IllegalArgumentException.class, () -> language.resolution().resolve(inline));
            assertThrows(IllegalArgumentException.class, () -> language.resolution().resolve(reference));
        }
    }

    @Test
    void shouldRequireUnavailableReplacementContentWhenTheSlotHasAType() {
        // given
        String missing = "4bcw6sKGddkWPs28vrgwXvT8Qi9Qy4TPWPYnvheTZ5Yb";
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            // when
            Node replacement = source(language,
                    "type: {type: List, items: [A, B]}\nitems: [{$pos: 1, $replace: {blueId: "
                            + missing + "}}]\n");
            // then
            assertThrows(IllegalArgumentException.class,
                    () -> language.resolution().resolve(replacement));
        }
    }

    @Test
    void shouldPreserveCompatibleExactTextReplacementIdentity() {
        // given
        blue.language.preprocess.provider.BasicNodeProvider provider =
                new blue.language.preprocess.provider.BasicNodeProvider();
        provider.addSingleNodes(new Node().name("Tutorial replacement Text").value("C"));
        String exact = provider.getBlueIdByName("Tutorial replacement Text");
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            // when
            Node canonical = language.identity().canonicalIdentityInput(source(language,
                    "type: {type: List, items: [A, B]}\nitems: [{$pos: 1, $replace: {blueId: "
                            + exact + "}}]\n"));
            // then
            assertEquals(2, canonical.getItems().size());
            assertTrue(canonical.getItems().get(1).isReferenceOnly());
            assertEquals(exact, canonical.getItems().get(1).getBlueId());
        }
    }

    @Test
    void shouldRejectScalarReplacementWithIncompatibleExplicitOrInferredType() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            String prefix = "type: {type: List, items: [A]}\nitems: [{$pos: 0, $replace: ";
            // when
            Node inferred = source(language, prefix + "7}]\n");
            Node explicit = source(language, prefix + "{type: Integer, value: 7}}]\n");
            // then
            assertThrows(IllegalArgumentException.class, () -> language.resolution().resolve(inferred));
            assertThrows(IllegalArgumentException.class, () -> language.resolution().resolve(explicit));
        }
    }

    @Test
    void shouldRetainInheritedSlotSchemaAcrossWholeValueReplacement() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            String prefix = "type: {type: List, items: [{type: Integer, value: 1, schema: {minimum: 0}}]}\n"
                    + "items: [{$pos: 0, $replace: ";
            // when
            Node valid = source(language, prefix + "2}]\n");
            Node invalid = source(language, prefix + "-1}]\n");
            // then
            assertEquals(new java.math.BigInteger("2"),
                    language.resolution().resolve(valid).getItems().get(0).getValue());
            assertThrows(IllegalArgumentException.class, () -> language.resolution().resolve(invalid));
        }
    }

    private static Node source(BlueLanguage language, String yaml) {
        return language.codec().parseSource(yaml, BlueFormat.YAML);
    }

    private static void assertRoundTrip(BlueLanguage language, String yaml) {
        Node authored = source(language, yaml);
        ResolvedSnapshot original = language.processing().runtimeAccess().canonicalizeWithEvidence(authored);
        Node minimized = new MinimizedOverlayBuilder().build(
                original.frozenResolvedRoot(), original.canonicalTypeIdentities());
        ResolvedSnapshot reloaded = language.processing().runtimeAccess().canonicalizeWithEvidence(minimized);
        assertEquals(original.blueId(), reloaded.blueId());
        assertEquals(language.codec().write(original.resolvedRoot(), BlueFormat.JSON),
                language.codec().write(reloaded.resolvedRoot(), BlueFormat.JSON));
    }
}
