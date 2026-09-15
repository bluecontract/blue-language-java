package blue.language.runtime;

import blue.language.codec.BlueFormat;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.preprocess.provider.BasicNodeProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static blue.language.model.wire.BlueLanguageConstants.LIST_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Source identity must not depend on whether a child is authored inline or
 * as a verified reference, including references to exact nodes that were
 * published raw rather than as canonical identity input.
 */
final class RawReferenceSourceIdentityTest {

    @Test
    void shouldDeriveOneSourceIdentityForEveryAcceptedCurrencyRepresentation() {
        // Given a Currency-typed field and two raw exact scalars accepted by it.
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            String currencyTypeId = store(language, provider,
                    source(language, "name: Currency\ntype: Text\n"));
            String holderTypeId = store(language, provider, source(language,
                    "name: Holder\ncurrency:\n  type:\n    blueId: " + currencyTypeId + "\n"));
            String textValueId = publishExact(language, provider,
                    source(language, "type: Text\nvalue: PLN\n"));
            String currencyValueId = publishExact(language, provider, source(language,
                    "type:\n  blueId: " + currencyTypeId + "\nvalue: PLN\n"));
            assertNotEquals(textValueId, currencyValueId,
                    "Text PLN and Currency PLN are different exact nodes");
            Node inlineText = holder(language, holderTypeId,
                    "currency:\n  type: Text\n  value: PLN\n");
            Node bare = holder(language, holderTypeId, "currency: PLN\n");
            Node inlineCurrency = holder(language, holderTypeId,
                    "currency:\n  type:\n    blueId: " + currencyTypeId + "\n  value: PLN\n");
            Node referencedText = holder(language, holderTypeId,
                    "currency:\n  blueId: " + textValueId + "\n");
            Node referencedCurrency = holder(language, holderTypeId,
                    "currency:\n  blueId: " + currencyValueId + "\n");

            // When deriving Source identity for each representation.
            Node expectedCanonical = new Node().type(new Node().blueId(holderTypeId))
                    .properties("currency", new Node()
                            .type(new Node().blueId(currencyTypeId)).value("PLN"));
            String expected = language.identity().directBlueId(expectedCanonical);

            // Then every accepted representation of the same value agrees.
            for (Node document : new Node[] {inlineText, bare, inlineCurrency, referencedText, referencedCurrency}) {
                assertEquals(expected, language.identity().sourceDocumentBlueId(document), () ->
                        "Canonical: " + NodeWireForm.get(
                                language.identity().canonicalIdentityInput(document)));
                Node resolvedCurrency = language.resolution().resolve(document)
                        .getProperties().get("currency");
                assertEquals(currencyTypeId, resolvedCurrency.getType().getBlueId());
                assertEquals("PLN", resolvedCurrency.getValue());
            }
            assertEquals(
                    language.identity().directBlueId(language.preprocessing().preprocess(inlineText)),
                    language.identity().directBlueId(language.preprocessing().preprocess(referencedText)),
                    "Exact identity of inline content and its verified reference stays equal");
        }
    }

    @Test
    void shouldKeepPureReferenceFormOnlyWhenItIsFaithfulToTheContext() {
        // Given references to a canonically typed and to a core-typed exact scalar.
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            String currencyTypeId = store(language, provider,
                    source(language, "name: Currency\ntype: Text\n"));
            String holderTypeId = store(language, provider, source(language,
                    "name: Holder\ncurrency:\n  type:\n    blueId: " + currencyTypeId + "\n"));
            String textValueId = publishExact(language, provider,
                    source(language, "type: Text\nvalue: PLN\n"));
            String currencyValueId = publishExact(language, provider, source(language,
                    "type:\n  blueId: " + currencyTypeId + "\nvalue: PLN\n"));

            // When canonicalizing both referenced representations.
            Node faithful = language.identity().canonicalIdentityInput(
                    holder(language, holderTypeId, "currency:\n  blueId: " + currencyValueId + "\n"));
            Node promoted = language.identity().canonicalIdentityInput(
                    holder(language, holderTypeId, "currency:\n  blueId: " + textValueId + "\n"));

            // Then the faithful reference is retained and the promoted one is materialized.
            assertTrue(faithful.getProperties().get("currency").isReferenceOnly());
            assertEquals(currencyValueId, faithful.getProperties().get("currency").getBlueId());
            assertNull(promoted.getProperties().get("currency").getBlueId());
            assertEquals(currencyTypeId, promoted.getProperties().get("currency").getType().getBlueId());
            assertEquals("PLN", promoted.getProperties().get("currency").getValue());
        }
    }

    @Test
    void shouldDeriveOneSourceIdentityForRawAmountInlineAndByReference() {
        // Given a raw Amount published exactly as authored under a typed price field.
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            String currencyTypeId = store(language, provider,
                    source(language, "name: Currency\ntype: Text\n"));
            String amountTypeId = store(language, provider, source(language,
                    "name: Amount\namountMinor:\n  type: Integer\ncurrency:\n  type:\n    blueId: "
                            + currencyTypeId + "\n"));
            String holderTypeId = store(language, provider, source(language,
                    "name: Price holder\nprice:\n  type:\n    blueId: " + amountTypeId + "\n"));
            Node rawAmount = source(language,
                    "type:\n  blueId: " + amountTypeId + "\namountMinor: 3990\ncurrency: PLN\n");
            String rawAmountId = publishExact(language, provider, rawAmount);
            Node canonicalAmount = language.identity().canonicalIdentityInput(rawAmount);
            provider.addSingleNodes(canonicalAmount);
            String canonicalAmountId = language.identity().directBlueId(canonicalAmount);
            Node inline = new Node().type(new Node().blueId(holderTypeId))
                    .properties("price", rawAmount.clone());
            Node rawReference = holder(language, holderTypeId, "price:\n  blueId: " + rawAmountId + "\n");
            Node canonicalReference = holder(language, holderTypeId,
                    "price:\n  blueId: " + canonicalAmountId + "\n");

            // When deriving Source identity for the inline and both referenced forms.
            String inlineId = language.identity().sourceDocumentBlueId(inline);

            // Then the raw and the canonical publication agree with the inline form,
            // and the canonical publication keeps exact and Source identity equal.
            assertEquals(inlineId, language.identity().sourceDocumentBlueId(rawReference));
            assertEquals(inlineId, language.identity().sourceDocumentBlueId(canonicalReference));
            assertEquals(inlineId, language.identity().directBlueId(
                    language.preprocessing().preprocess(canonicalReference)));
        }
    }

    @Test
    void shouldDeriveOneSourceIdentityThroughNestedReferencesColdAndWarm() {
        // Given a Holder whose Terms value is referenced and itself references its labeled title.
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            String termsTypeId = store(language, provider,
                    source(language, "name: Terms\ntitle:\n  type: Text\n"));
            String holderTypeId = store(language, provider, source(language,
                    "name: Holder\nterms:\n  type:\n    blueId: " + termsTypeId + "\n"));
            String titleId = publishExact(language, provider,
                    source(language, "name: Label\ntype: Text\nvalue: Coffee\n"));
            String termsValueId = publishExact(language, provider, source(language,
                    "type:\n  blueId: " + termsTypeId + "\ntitle:\n  blueId: " + titleId + "\n"));
            Node inline = holder(language, holderTypeId, "terms:\n  type:\n    blueId: " + termsTypeId
                    + "\n  title:\n    name: Label\n    type: Text\n    value: Coffee\n");
            Node nested = holder(language, holderTypeId, "terms:\n  blueId: " + termsValueId + "\n");

            // When deriving Source identity cold, then again after warming the reference caches.
            String inlineId = language.identity().sourceDocumentBlueId(inline);
            String coldNestedId = language.identity().sourceDocumentBlueId(nested);
            language.resolution().resolve(nested.clone());
            String warmNestedId = language.identity().sourceDocumentBlueId(nested);
            Node warmCanonical = language.identity().canonicalIdentityInput(inline);

            // Then the nested chain agrees with the inline form and keeps the authored label.
            assertEquals(inlineId, coldNestedId);
            assertEquals(inlineId, warmNestedId);
            assertEquals("Label", warmCanonical.getProperties().get("terms")
                    .getProperties().get("title").getName());
        }
    }

    @Test
    void shouldCompleteReferencesNestedInInlineTypedChildrenAndListItems() {
        // Given an inline Amount whose currency is a reference, under a typed field and a typed list.
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            String currencyTypeId = store(language, provider,
                    source(language, "name: Currency\ntype: Text\n"));
            String amountTypeId = store(language, provider, source(language,
                    "name: Amount\namountMinor:\n  type: Integer\ncurrency:\n  type:\n    blueId: "
                            + currencyTypeId + "\n  schema:\n    required: true\n"));
            String textValueId = publishExact(language, provider,
                    source(language, "type: Text\nvalue: PLN\n"));
            String holderTypeId = store(language, provider, source(language,
                    "name: Holder\nprice:\n  type:\n    blueId: " + amountTypeId + "\n"));
            String listTypeId = store(language, provider, source(language,
                    "name: Prices\nprices:\n  type: List\n  itemType:\n    blueId: " + amountTypeId + "\n"));
            Node amountByValue = new Node().type(new Node().blueId(amountTypeId))
                    .properties("amountMinor", new Node().value(3990))
                    .properties("currency", new Node().value("PLN"));
            Node amountByReference = new Node().type(new Node().blueId(amountTypeId))
                    .properties("amountMinor", new Node().value(3990))
                    .properties("currency", new Node().blueId(textValueId));
            Node field = new Node().type(new Node().blueId(holderTypeId))
                    .properties("price", amountByReference.clone());
            Node fieldByValue = new Node().type(new Node().blueId(holderTypeId))
                    .properties("price", amountByValue.clone());
            Node list = new Node().type(new Node().blueId(listTypeId))
                    .properties("prices", new Node().items(amountByReference.clone()));
            Node listByValue = new Node().type(new Node().blueId(listTypeId))
                    .properties("prices", new Node().items(amountByValue.clone()));

            // When deriving Source identity cold, after an instance resolution, and warm.
            String fieldId = language.identity().sourceDocumentBlueId(field);
            String listId = language.identity().sourceDocumentBlueId(list);
            language.snapshots().resolve(field.clone());
            language.snapshots().resolve(list.clone());

            // Then the nested reference completes everywhere and matches the value form.
            assertEquals(language.identity().sourceDocumentBlueId(fieldByValue), fieldId);
            assertEquals(language.identity().sourceDocumentBlueId(listByValue), listId);
            assertEquals(fieldId, language.identity().sourceDocumentBlueId(field));
            assertEquals(listId, language.identity().sourceDocumentBlueId(list));
            assertEquals("PLN", language.identity().canonicalIdentityInput(field)
                    .getProperties().get("price").getProperties().get("currency").getValue());
        }
    }

    @Test
    void shouldCompleteReferencesInsideAReferencedTypedList() {
        // Given a typed list published as one node whose items reference their currency.
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            String currencyTypeId = store(language, provider,
                    source(language, "name: Currency\ntype: Text\n"));
            String amountTypeId = store(language, provider, source(language,
                    "name: Amount\namountMinor:\n  type: Integer\ncurrency:\n  type:\n    blueId: "
                            + currencyTypeId + "\n"));
            String textValueId = publishExact(language, provider,
                    source(language, "name: Label\ntype: Text\nvalue: PLN\n"));
            String listTypeId = store(language, provider, source(language,
                    "name: Prices\nprices:\n  type: List\n  itemType:\n    blueId: " + amountTypeId + "\n"));
            Node amount = new Node().type(new Node().blueId(amountTypeId))
                    .properties("amountMinor", new Node().value(3990))
                    .properties("currency", new Node().blueId(textValueId));
            Node list = new Node().type(new Node().blueId(LIST_TYPE_BLUE_ID)).items(amount.clone());
            String listId = publishExact(language, provider, list.clone());
            Node inline = new Node().type(new Node().blueId(listTypeId))
                    .properties("prices", list.clone());
            Node referenced = new Node().type(new Node().blueId(listTypeId))
                    .properties("prices", new Node().blueId(listId));

            // When deriving Source identity cold, after an instance resolution, and warm.
            String inlineId = language.identity().sourceDocumentBlueId(inline);
            String referencedId = language.identity().sourceDocumentBlueId(referenced);
            language.snapshots().resolve(referenced.clone());
            String warmId = language.identity().sourceDocumentBlueId(referenced);
            Node currency = language.identity().canonicalIdentityInput(referenced)
                    .getProperties().get("prices").getItems().get(0).getProperties().get("currency");

            // Then the nested currency completes and the list forms agree, keeping the label.
            assertEquals(inlineId, referencedId);
            assertEquals(inlineId, warmId);
            assertEquals("PLN", currency.getValue());
            assertEquals("Label", currency.getName());
        }
    }

    @Test
    void shouldOmitReferencedContentThatOnlyRepeatsAnInheritedFixedValue() {
        // Given a field whose type fixes currency to PLN and a reference to an exact Text PLN.
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            String currencyTypeId = store(language, provider,
                    source(language, "name: Currency\ntype: Text\n"));
            String amountTypeId = store(language, provider, source(language,
                    "name: Amount\namountMinor:\n  type: Integer\ncurrency:\n  type:\n    blueId: "
                            + currencyTypeId + "\n  value: PLN\n"));
            String holderTypeId = store(language, provider, source(language,
                    "name: Holder\nprice:\n  type:\n    blueId: " + amountTypeId + "\n"));
            String textValueId = publishExact(language, provider,
                    source(language, "type: Text\nvalue: PLN\n"));
            String labeledValueId = publishExact(language, provider,
                    source(language, "name: Label\ntype: Text\nvalue: PLN\n"));
            Node[] forms = {
                    new Node().value("PLN"),
                    new Node().blueId(textValueId),
                    new Node().blueId(labeledValueId)};
            String[] ids = new String[forms.length];
            Node[] canonical = new Node[forms.length];

            // When deriving Source identity for the bare, referenced and labeled-reference forms.
            for (int index = 0; index < forms.length; index++) {
                Node document = new Node().type(new Node().blueId(holderTypeId))
                        .properties("price", new Node().type(new Node().blueId(amountTypeId))
                                .properties("amountMinor", new Node().value(3990))
                                .properties("currency", forms[index]));
                ids[index] = language.identity().sourceDocumentBlueId(document);
                canonical[index] = language.identity().canonicalIdentityInput(document)
                        .getProperties().get("price").getProperties().get("currency");
            }

            // Then the derivable value is omitted everywhere and only the label survives.
            assertEquals(ids[0], ids[1]);
            assertNull(canonical[0]);
            assertNull(canonical[1]);
            assertEquals("Label", canonical[2].getName());
            assertNull(canonical[2].getValue());
        }
    }

    @Test
    void shouldCompleteScalarReferencesTypedOnlyByTheEnclosingListItemType() {
        // Given a referenced list of labeled Currency scalars whose type comes from the holder's itemType.
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            String currencyTypeId = publishExact(language, provider,
                    source(language, "name: Currency\ntype: Text\n"));
            String holderTypeId = publishExact(language, provider, source(language,
                    "name: Holder list\nentries:\n  type: List\n  itemType:\n    blueId: " + currencyTypeId + "\n"));
            Node scalar = new Node().name("Authored label")
                    .type(new Node().blueId(currencyTypeId)).value("PLN");
            String scalarId = publishExact(language, provider, scalar.clone());
            String listId = publishExact(language, provider, new Node()
                    .type(new Node().blueId(LIST_TYPE_BLUE_ID))
                    .items(new Node().blueId(scalarId)));
            Node inline = new Node().type(new Node().blueId(holderTypeId)).properties("entries",
                    new Node().type(new Node().blueId(LIST_TYPE_BLUE_ID)).items(scalar.clone()));
            Node referenced = new Node().type(new Node().blueId(holderTypeId))
                    .properties("entries", new Node().blueId(listId));

            // When deriving Source identity cold, warm, and after a resolution.
            String expected = language.identity().sourceDocumentBlueId(inline);
            String cold = language.identity().sourceDocumentBlueId(referenced);
            String warm = language.identity().sourceDocumentBlueId(referenced);
            language.resolution().resolve(referenced.clone());
            String afterResolve = language.identity().sourceDocumentBlueId(referenced);
            Node entry = language.identity().canonicalIdentityInput(referenced)
                    .getProperties().get("entries").getItems().get(0);

            // Then every reading agrees with the inline list; the faithful scalar stays a pure reference.
            assertEquals(expected, cold);
            assertEquals(expected, warm);
            assertEquals(expected, afterResolve);
            assertEquals(scalarId, entry.getBlueId());
            assertTrue(entry.isReferenceOnly());
        }
    }

    @Test
    void shouldLeaveUnconstrainedReferencesOpaque() {
        // Given references in untyped positions to content the provider does not have.
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            String archiveTypeId = store(language, provider, source(language,
                    "name: Archive\ndocuments:\n  type: List\n"));
            String missingA = language.identity().directBlueId(new Node().name("unpublished A"));
            String missingB = language.identity().directBlueId(new Node().name("unpublished B"));
            Node archive = source(language, "type:\n  blueId: " + archiveTypeId
                    + "\ndocuments:\n  - blueId: " + missingA + "\n  - blueId: " + missingB
                    + "\nnote:\n  blueId: " + missingA + "\n");

            // When deriving Source identity without any provider content for them.
            Node canonical = language.identity().canonicalIdentityInput(archive);

            // Then the references stay exact leaves and identity needs no fetch.
            assertEquals(missingA, canonical.getProperties().get("documents").getItems().get(0).getBlueId());
            assertEquals(missingB, canonical.getProperties().get("documents").getItems().get(1).getBlueId());
            assertEquals(missingA, canonical.getProperties().get("note").getBlueId());
            assertEquals(language.identity().directBlueId(language.preprocessing().preprocess(archive)),
                    language.identity().sourceDocumentBlueId(archive));
        }
    }

    @Test
    void shouldCalculateListSourceIdentityWithoutFetchingReferencedDocuments() {
        // Given a List declaration without itemType and 100 stored documents.
        BasicNodeProvider provider = new BasicNodeProvider();
        Set<String> documentIds = new HashSet<>();
        List<Node> references = new ArrayList<>();
        String archiveTypeId;
        try (BlueLanguage writer = BlueLanguage.builder().nodeProvider(provider).build()) {
            archiveTypeId = store(writer, provider, source(writer,
                    "name: Archive\ndocuments:\n  type: List\n"));
            for (int index = 0; index < 100; index++) {
                String documentId = publishExact(writer, provider,
                        new Node().name("Document " + index)
                                .properties("body", new Node().value("Stored content " + index)));
                documentIds.add(documentId);
                references.add(new Node().blueId(documentId));
            }
        }
        Node archive = new Node().type(new Node().blueId(archiveTypeId))
                .properties("documents", new Node().items(references));
        try (BlueLanguage reader = BlueLanguage.builder().nodeProvider(blueId -> {
            if (documentIds.contains(blueId)) {
                throw new AssertionError("Source identity must not fetch document " + blueId);
            }
            return provider.fetchByBlueId(blueId);
        }).build()) {
            // When calculating Source identity with cold and then warm caches.
            String coldId = reader.identity().sourceDocumentBlueId(archive);
            String warmId = reader.identity().sourceDocumentBlueId(archive);
            Node canonical = reader.identity().canonicalIdentityInput(archive);

            // Then only child identities are needed; every reference remains a leaf.
            assertEquals(reader.identity().directBlueId(archive), coldId);
            assertEquals(coldId, warmId);
            assertEquals(NodeWireForm.get(new Node().items(references)),
                    NodeWireForm.get(canonical.getProperties().get("documents")));
        }
    }

    private static Node holder(BlueLanguage language, String holderTypeId, String body) {
        return source(language, "type:\n  blueId: " + holderTypeId + "\n" + body);
    }

    /** Publishes exact authored content, as a direct content API does. */
    private static String publishExact(
            BlueLanguage language, BasicNodeProvider provider, Node source) {
        Node exact = language.preprocessing().preprocess(source);
        provider.addSingleNodes(exact);
        return language.identity().directBlueId(exact);
    }

    private static String store(
            BlueLanguage language, BasicNodeProvider provider, Node source) {
        Node canonical = language.identity().canonicalIdentityInput(source);
        provider.addSingleNodes(canonical);
        return language.identity().directBlueId(canonical);
    }

    private static Node source(BlueLanguage language, String yaml) {
        return language.codec().parseSource(yaml, BlueFormat.YAML);
    }
}
