package blue.language.runtime;

import blue.language.codec.BlueFormat;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.merge.ResolvedSnapshot;
import blue.language.merge.Merger;
import blue.language.merge.processor.DictionaryProcessor;
import blue.language.merge.processor.SequentialMergingProcessor;
import blue.language.merge.processor.TypeAssigner;
import blue.language.merge.processor.ListProcessor;
import blue.language.merge.processor.ValuePropagator;
import blue.language.merge.processor.SchemaPropagator;
import blue.language.merge.processor.SchemaVerifier;
import blue.language.merge.processor.BasicTypesVerifier;
import blue.language.resolve.ResolutionLimits;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

final class ContextualReferenceCanonicalizationTest {

    @Test
    void shouldCanonicalizeSchemaOnlyReferenceThroughItsInterpretingContext() {
        // Given a definition whose child combines inherited and authored schema.
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            String holderId = publishExact(language, provider, source(language,
                    "name: Schema holder\nfield:\n  schema:\n    minLength: 1\n"));
            Node contribution = source(language, "schema:\n  required: true\n");
            String contributionId = publishExact(language, provider, contribution);
            Node inline = new Node().type(reference(holderId))
                    .properties("field", contribution.clone());
            Node referenced = new Node().type(reference(holderId))
                    .properties("field", reference(contributionId));
            Node expected = new Node().type(reference(holderId)).properties("field",
                    source(language, "schema:\n  minLength: 1\n  required: true\n"));
            String expectedId = language.identity().directBlueId(expected);

            // When preparing each definition without inventing a sample payload.
            String inlineId = language.identity().sourceDocumentBlueId(inline);
            String referenceId = language.identity().sourceDocumentBlueId(referenced);

            // Then the effective schema, including both constraints, determines identity.
            assertEquals(expectedId, inlineId);
            assertEquals(expectedId, referenceId);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"Dictionary", "List"})
    void shouldRejectIncompatibleCollectionValuesInlineAndByReference(String collectionType) {
        // Given Text is not subtype-compatible with the Dictionary's custom Label valueType.
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            String labelId = publishExact(language, provider,
                    source(language, "name: Label\ntype: Text\n"));
            Node text = source(language, "type: Text\nvalue: PLN\n");
            String textId = publishExact(language, provider, text);
            boolean dictionary = collectionType.equals("Dictionary");
            Node collection = source(language,
                    "type: " + collectionType + "\n" + (dictionary ? "valueType" : "itemType")
                            + ":\n  blueId: " + labelId + "\n");
            Node inline = dictionary ? collection.clone().properties("entry", text)
                    : collection.clone().items(text);
            Node referenced = dictionary ? collection.clone().properties("entry", reference(textId))
                    : collection.clone().items(reference(textId));

            // When preparing Source identity or certifying the complete value.
            // Then both representations preserve the specified rejection.
            for (Node input : new Node[] {inline, referenced}) {
                assertThrows(IllegalArgumentException.class,
                        () -> language.identity().sourceDocumentBlueId(input));
                assertThrows(IllegalArgumentException.class,
                        () -> language.resolution().resolve(input));
            }
        }
    }

    @Test
    void shouldPreserveInvalidSourceListControlFailure() {
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node input = source(language, "type: List\nitems:\n  - $pos: 0\n    value: A\n  - value: B\n");
            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> language.resolution().resolve(input));
            assertEquals(BlueLanguageErrorCategory.ListControlViolation,
                    BlueLanguageErrorClassifier.classify(failure), failure.toString());
        }
    }

    @Test
    void shouldValidateExactScalarTypeIndependentlyOfProviderNodeRepresentation() {
        // Given two host Nodes encode the same exact Text value.
        BasicNodeProvider definitions = new BasicNodeProvider();
        try (BlueLanguage author = BlueLanguage.builder().nodeProvider(definitions).build()) {
            Node implicit = new Node().value("PLN");
            Node explicit = source(author, "type: Text\nvalue: PLN\n");
            explicit = author.preprocessing().preprocess(explicit);
            String textId = author.identity().directBlueId(implicit);
            assertEquals(textId, author.identity().directBlueId(explicit));
            String labelId = publishExact(author, definitions,
                    source(author, "name: Label\ntype: Text\n"));
            for (Node supplied : new Node[] {implicit, explicit}) {
                try (BlueLanguage language = BlueLanguage.builder().nodeProvider(id -> textId.equals(id)
                        ? Collections.singletonList(supplied.clone()) : definitions.fetchByBlueId(id)).build()) {
                    for (String collection : new String[] {"Dictionary", "List"}) {
                        boolean dictionary = collection.equals("Dictionary");
                        Node input = source(language, "type: " + collection + "\n"
                                + (dictionary ? "valueType" : "itemType") + ":\n  blueId: " + labelId + "\n");
                        if (dictionary) input.properties("entry", reference(textId));
                        else input.items(reference(textId));
                        // When interpreting the verified exact entry in either collection.
                        // Then the provider's omitted host type does not bypass the same rejection.
                        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                                () -> language.identity().sourceDocumentBlueId(input), collection);
                        assertEquals(BlueLanguageErrorCategory.TypeCompatibilityViolation,
                                BlueLanguageErrorClassifier.classify(failure));
                    }
                }
            }
        }
    }

    @Test
    void shouldValidateExplicitTypesInsideListReplacementControls() {
        // Given an inherited typed position and an explicitly incompatible replacement.
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            String labelId = publishExact(language, provider,
                    source(language, "name: Label\ntype: Text\n"));
            String listId = publishExact(language, provider, source(language,
                    "name: Labels\ntype: List\nitemType:\n  blueId: " + labelId
                            + "\nitems:\n  - type:\n      blueId: " + labelId + "\n"));
            String textId = publishExact(language, provider,
                    source(language, "type: Text\nvalue: PLN\n"));
            for (String replacement : new String[] {
                    "type: Text\n      value: PLN", "blueId: " + textId}) {
                Node input = source(language, "type:\n  blueId: " + listId
                        + "\nitems:\n  - $pos: 0\n    $replace:\n      " + replacement + "\n");
                // When Source controls expose the actual replacement contribution.
                // Then both representations reject before contextual primitive promotion.
                IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                        () -> language.identity().sourceDocumentBlueId(input));
                assertTrue(failure.getMessage().contains("not a subtype"), failure.getMessage());
            }
        }
    }

    @Test
    void shouldRetainTermsPayloadAndDistinguishTeaAndAbsence() {
        // Given independently authored canonical Terms/Coffee under a typed field.
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            String termsId = publishExact(language, provider,
                    source(language, "name: Terms\ntitle:\n  type: Text\n"));
            String holderId = publishExact(language, provider, new Node().name("Holder")
                    .properties("terms", new Node().type(reference(termsId))));
            Node coffee = language.preprocessing().preprocess(new Node().type(reference(termsId))
                    .properties("title", new Node().value("Coffee")));
            String coffeeId = publishExact(language, provider, coffee);
            Node expected = new Node().type(reference(holderId)).properties("terms", coffee);
            String expectedId = language.identity().directBlueId(expected);
            // When preparing inline and exact-reference contributions, cold and warm.
            for (Node child : new Node[] {reference(coffeeId), coffee, reference(coffeeId)}) {
                Node input = new Node().type(reference(holderId)).properties("terms", child);
                assertEquals(expectedId, language.identity().sourceDocumentBlueId(input));
                Node resolved = language.resolution().resolve(input).getProperties().get("terms");
                assertEquals(termsId, resolved.getType().getBlueId());
                assertEquals("Coffee", resolved.getProperties().get("title").getValue());
            }
            // Then neither another payload nor a missing field can become Coffee.
            assertNotEquals(expectedId, language.identity().sourceDocumentBlueId(
                    new Node().type(reference(holderId)).properties("terms",
                            new Node().type(reference(termsId))
                                    .properties("title", new Node().value("Tea")))));
            assertNotEquals(expectedId, language.identity().sourceDocumentBlueId(
                    new Node().type(reference(holderId))));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "[]", "\"\"", "0", "false"})
    void shouldRetainEmptyAndFalseValuedContributions(String yaml) {
        // Given a present value in an interpreting field, including zero-length payloads.
        BasicNodeProvider provider = new BasicNodeProvider();
        Map<String, Node> exactValues = new HashMap<>();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(id ->
                exactValues.containsKey(id)
                        ? Collections.singletonList(exactValues.get(id).clone())
                        : provider.fetchByBlueId(id)).build()) {
            Node exact = language.preprocessing().preprocess(source(language, yaml));
            Node field = exact.getType() != null
                    ? new Node().type(exact.getType().clone())
                    : exact.getItems() != null
                            ? source(language, "type: List\n")
                            : source(language, "schema:\n  maxFields: 3\n");
            String holderId = publishExact(language, provider,
                    new Node().name("Empty holder").properties("field", field));
            String exactId = language.identity().directBlueId(exact);
            exactValues.put(exactId, exact);
            Node inline = new Node().type(reference(holderId)).properties("field", exact);
            Node byReference = new Node().type(reference(holderId)).properties("field", reference(exactId));
            // When interpreting both forms.
            Node resolvedInline = language.resolution().resolve(inline).getProperties().get("field");
            Node resolvedReference = language.resolution().resolve(byReference).getProperties().get("field");
            resolvedInline.blueId(null).materializedReferenceBlueId(null);
            resolvedReference.blueId(null).materializedReferenceBlueId(null);
            // Then the payload survives, including distinctions between empty and absent.
            assertEquals(NodeWireForm.get(resolvedInline), NodeWireForm.get(resolvedReference));
            assertEquals(language.identity().sourceDocumentBlueId(inline),
                    language.identity().sourceDocumentBlueId(byReference));
            assertNotNull(language.identity().canonicalIdentityInput(byReference)
                    .getProperties().get("field"));
        }
    }

    @Test
    void shouldValidateDictionarySchemasAndRetainCompatibleReferences() {
        // Given a Dictionary of Terms whose fixed entries must satisfy title constraints.
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            String termsId = publishExact(language, provider, source(language,
                    "name: Terms\ntitle:\n  type: Text\n  schema:\n    minLength: 2\n"));
            Node dictionary = source(language,
                    "type: Dictionary\nvalueType:\n  blueId: " + termsId + "\n");
            Node coffee = new Node().type(reference(termsId))
                    .properties("title", new Node().value("Coffee"));
            String coffeeId = publishExact(language, provider, coffee);
            Node bad = new Node().type(reference(termsId))
                    .properties("title", new Node().value("x"));
            String badId = publishExact(language, provider, bad);
            // When preparing compatible entries and invalid fixed content.
            Node inline = dictionary.clone().properties("entry", coffee);
            Node referenced = dictionary.clone().properties("entry", reference(coffeeId));
            assertEquals(language.identity().sourceDocumentBlueId(inline),
                    language.identity().sourceDocumentBlueId(referenced));
            assertEquals("Coffee", language.resolution().resolve(referenced)
                    .getProperties().get("entry").getProperties().get("title").getValue());
            // Public merge can reuse a resolved mutable target without the old
            // invocation's sidecar. Existing entries are not fresh references.
            Merger merger = new Merger(new SequentialMergingProcessor(Arrays.asList(
                    new ValuePropagator(), new TypeAssigner(), new ListProcessor(),
                    new DictionaryProcessor(), new SchemaPropagator(),
                    new SchemaVerifier(), new BasicTypesVerifier())), provider);
            for (Node input : new Node[] {inline, referenced}) {
                Node previous = language.resolution().resolve(input);
                merger.merge(previous, source(language, "{}"), ResolutionLimits.NO_LIMITS);
                assertEquals("Coffee", previous.getProperties().get("entry")
                        .getProperties().get("title").getValue());
            }
            // Then schema failure remains a failure, including through a warm provider.
            for (Node value : new Node[] {bad, reference(badId), reference(badId)}) {
                Node input = dictionary.clone().properties("entry", value);
                assertThrows(IllegalArgumentException.class,
                        () -> language.identity().sourceDocumentBlueId(input));
                assertThrows(IllegalArgumentException.class,
                        () -> language.resolution().resolve(input));
            }
        }
    }

    @Test
    void shouldKeepOpaqueAndInterpretedOccurrencesSeparateInEitherOrder() {
        // Given the same raw reference at an opaque position and a schema-interpreted one.
        BasicNodeProvider provider = new BasicNodeProvider();
        List<String> reads = new ArrayList<>();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(id -> {
            reads.add(id);
            return provider.fetchByBlueId(id);
        }).build()) {
            Node content = source(language, "schema:\n  required: true\n");
            String contentId = publishExact(language, provider, content);
            String holderId = publishExact(language, provider, source(language,
                    "name: Schema holder\nfield:\n  schema:\n    minLength: 1\n"));
            Node interpreted = new Node().type(reference(holderId))
                    .properties("field", reference(contentId));
            String expected = language.identity().directBlueId(new Node().type(reference(holderId))
                    .properties("field", source(language,
                            "schema:\n  minLength: 1\n  required: true\n")));
            // When an opaque call precedes, then follows a required materialization.
            reads.clear();
            assertEquals(contentId, language.identity().sourceDocumentBlueId(reference(contentId)));
            assertFalse(reads.contains(contentId));
            assertEquals(expected, language.identity().sourceDocumentBlueId(interpreted));
            assertTrue(reads.contains(contentId));
            reads.clear();
            assertEquals(contentId, language.identity().sourceDocumentBlueId(reference(contentId)));
            assertTrue(reads.isEmpty());
            assertEquals(expected, language.identity().sourceDocumentBlueId(interpreted));
            // The same exact bytes may occur in both contexts in one graph.
            Node mixed = interpreted.clone().properties("opaque", reference(contentId));
            Node expectedMixed = new Node().type(reference(holderId))
                    .properties("opaque", reference(contentId)).properties("field",
                            source(language, "schema:\n  minLength: 1\n  required: true\n"));
            assertEquals(language.identity().directBlueId(expectedMixed),
                    language.identity().sourceDocumentBlueId(mixed));
            assertTrue(language.identity().canonicalIdentityInput(mixed)
                    .getProperties().get("opaque").isReferenceOnly());
        }
    }

    @Test
    void shouldTransportOccurrenceEvidenceOnlyInResolvedCopies() {
        // Given byte-identical references with different resolver-established occurrences.
        Node opaque = reference("reference");
        Node interpreted = opaque.clone().materializedReferenceBlueId("reference");
        // When copying resolved views, freezing Source, and starting fresh preprocessing.
        assertEquals("reference", interpreted.clone().getMaterializedReferenceBlueId());
        assertEquals("reference", FrozenNode.fromResolvedNode(interpreted).toNode()
                .getMaterializedReferenceBlueId());
        assertNull(FrozenNode.fromSourceNode(interpreted).toNode().getMaterializedReferenceBlueId());
        assertNull(interpreted.cloneWithoutResolutionEvidence().getMaterializedReferenceBlueId());
        // Then representation interning distinguishes proof, while wire identity does not.
        assertNotEquals(FrozenNode.fromResolvedNode(opaque).resolvedStructuralKey(),
                FrozenNode.fromResolvedNode(interpreted).resolvedStructuralKey());
        assertEquals(NodeWireForm.get(opaque), NodeWireForm.get(interpreted));
        assertNull(interpreted.blueId("different").getMaterializedReferenceBlueId());
    }

    @Test
    void shouldPreserveCanonicalListsThroughExactStorageAndColdLoading() {
        // Given an authored List overlay with an inherited prefix and repeated items.
        BasicNodeProvider provider = new BasicNodeProvider();
        Node canonical;
        String expectedId;
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            String holderId = publishExact(language, provider, source(language,
                    "name: Sequence holder\nlabels:\n  type: List\n  itemType: Text\n  items: [A]\n"));
            Node input = new Node().type(reference(holderId))
                    .properties("labels", source(language, "[B, B]"));
            Node expected = new Node().type(reference(holderId))
                    .properties("labels", language.preprocessing().preprocess(
                            source(language, "[A, B, B]\n")));
            expectedId = language.identity().directBlueId(expected);
            // When preparing and storing the complete canonical payload as exact content.
            canonical = language.identity().canonicalIdentityInput(input);
            assertEquals(expectedId, language.identity().directBlueId(canonical));
            provider.addSingleNodes(canonical);
            assertEquals(expectedId, language.graph().collapse(language.graph().expand(canonical)).getBlueId());
        }
        // Then a new runtime uses the canonical loading boundary, never a fresh List overlay.
        try (BlueLanguage reader = BlueLanguage.builder().nodeProvider(provider).build()) {
            String json = reader.codec().write(canonical, BlueFormat.JSON);
            Node loadedBytes = reader.codec().parseBlueIdInput(json, BlueFormat.JSON);
            for (ResolvedSnapshot loaded : new ResolvedSnapshot[] {
                    reader.snapshots().load(expectedId), reader.snapshots().load(loadedBytes)}) {
                assertEquals(expectedId, loaded.blueId());
                List<Node> items = loaded.resolvedRoot().getProperties().get("labels").getItems();
                assertEquals(3, items.size());
                assertEquals("A", items.get(0).getValue());
                assertEquals("B", items.get(1).getValue());
                assertEquals("B", items.get(2).getValue());
            }
        }
    }

    @Test
    void shouldDistinguishMissingAndForgedRequiredContent() {
        // Given a constrained field whose exact value is absent or forged by the provider.
        BasicNodeProvider provider = new BasicNodeProvider();
        String holderId;
        String valueId;
        try (BlueLanguage writer = BlueLanguage.builder().nodeProvider(provider).build()) {
            holderId = publishExact(writer, provider,
                    source(writer, "name: Text holder\nfield:\n  type: Text\n"));
            valueId = writer.identity().directBlueId(writer.preprocessing()
                    .preprocess(new Node().value("expected")));
        }
        for (boolean forged : new boolean[] {false, true}) {
            try (BlueLanguage language = BlueLanguage.builder().nodeProvider(id -> {
                if (id.equals(valueId)) {
                    return forged ? Collections.singletonList(new Node().value("forged"))
                            : Collections.emptyList();
                }
                return provider.fetchByBlueId(id);
            }).build()) {
                Node input = new Node().type(reference(holderId)).properties("field", reference(valueId));
                // When each preparation is repeated, the outcome must remain a failure.
                for (int run = 0; run < 2; run++) {
                    RuntimeException failure = assertThrows(RuntimeException.class,
                            () -> language.identity().sourceDocumentBlueId(input));
                    assertEquals(forged ? BlueLanguageErrorCategory.ProviderBlueIdMismatch
                                    : BlueLanguageErrorCategory.ProviderUnavailable,
                            BlueLanguageErrorClassifier.classify(failure));
                }
            }
        }
    }

    @Test
    void shouldRejectConflictingValuesAndUnrelatedTypesThroughBothForms() {
        // Given a fixed Currency/PLN field and a distinct custom subtype of Text.
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            String currencyId = publishExact(language, provider,
                    source(language, "name: Currency\ntype: Text\n"));
            String labelId = publishExact(language, provider,
                    source(language, "name: Label\ntype: Text\n"));
            String holderId = publishExact(language, provider, new Node().name("Fixed holder")
                    .properties("currency", new Node().type(reference(currencyId)).value("PLN")));
            for (Node bad : new Node[] {new Node().value("EUR"),
                    new Node().type(reference(labelId)).value("PLN")}) {
                String badId = publishExact(language, provider, bad);
                // When the same incompatible contribution arrives inline or through a reference.
                for (Node child : new Node[] {bad, reference(badId)}) {
                    Node input = new Node().type(reference(holderId)).properties("currency", child);
                    assertThrows(IllegalArgumentException.class,
                            () -> language.identity().sourceDocumentBlueId(input));
                    assertThrows(IllegalArgumentException.class,
                            () -> language.resolution().resolve(input));
                }
            }
            // A valid definition still need not invent an instance payload.
            Node gender = source(language,
                    "name: Gender\ntype: Text\nschema:\n  enum: [Female, Male]\n");
            assertNull(language.resolution().resolveDefinition(gender).getValue());
            assertNull(language.identity().canonicalIdentityInput(gender).getValue());
        }
    }

    private static String publishExact(
            BlueLanguage language, BasicNodeProvider provider, Node input) {
        Node exact = language.preprocessing().preprocess(input);
        provider.addSingleNodes(exact);
        return language.identity().directBlueId(exact);
    }

    private static Node source(BlueLanguage language, String yaml) {
        return language.codec().parseSource(yaml, BlueFormat.YAML);
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }
}
