package blue.language.runtime;

import blue.language.codec.BlueFormat;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import blue.language.preprocess.provider.BasicNodeProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class NestedValueReferenceIdentityTest {

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldPreserveSourceIdentityWhenReplacingTypedInlineChildWithExactReference(
            boolean declareChildType) {
        // Given the same Terms value under a Holder with or without a field type.
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            String termsTypeId = store(language, provider,
                    source(language, "name: Terms\ntitle:\n  type: Text\n"));
            Node holderType = new Node().name("Holder");
            if (declareChildType) {
                holderType.properties("terms", new Node().type(reference(termsTypeId)));
            }
            String holderTypeId = store(language, provider, holderType);
            Node terms = source(language,
                    "type:\n  blueId: " + termsTypeId
                            + "\ntitle:\n  type: Text\n  value: Coffee\n");
            String termsId = store(language, provider, terms);
            Node inline = new Node().type(reference(holderTypeId))
                    .properties("terms", terms.clone());
            Node referenced = new Node().type(reference(holderTypeId))
                    .properties("terms", reference(termsId));
            Node inlinePreprocessed = language.preprocessing().preprocess(inline);
            Node referencedPreprocessed = language.preprocessing().preprocess(referenced);

            // When calculating Source identity for the two representations.
            String inlineId = language.identity().sourceDocumentBlueId(inline);
            String referencedId = language.identity().sourceDocumentBlueId(referenced);

            Node inlineExpanded = language.graph().expand(inlinePreprocessed);
            Node referenceExpanded = language.graph().expand(referencedPreprocessed);
            String minimizedJson = language.codec().write(
                    language.resolution().minimize(inline), BlueFormat.JSON);
            String reloadedId;
            try (BlueLanguage reader = BlueLanguage.builder().nodeProvider(provider).build()) {
                reloadedId = reader.identity().sourceDocumentBlueId(
                        reader.codec().parseSource(minimizedJson, BlueFormat.JSON));
            }

            // Then exact content, full expansion and Source identity all agree.
            assertEquals(NodeWireForm.get(inlineExpanded), NodeWireForm.get(referenceExpanded));
            assertEquals(language.identity().directBlueId(inlinePreprocessed),
                    language.identity().directBlueId(referencedPreprocessed));
            assertEquals(inlineId, referencedId, () ->
                    "Inline canonical: " + NodeWireForm.get(
                            language.identity().canonicalIdentityInput(inline))
                            + "; reference canonical: " + NodeWireForm.get(
                            language.identity().canonicalIdentityInput(referenced)));
            assertEquals(inlineId, reloadedId,
                    "Minimized Source must preserve identity after a cold reload");
        }
    }

    @Test
    void shouldPreserveInheritedCustomScalarIdentityForPrimitiveSyntax() {
        // Given a text value whose custom type is declared by its Holder field.
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            String titleTypeId = store(language, provider,
                    source(language, "name: Title\ntype: Text\nschema:\n  minLength: 1\n"));
            String holderTypeId = store(language, provider,
                    new Node().name("Holder").properties("title",
                            new Node().type(reference(titleTypeId))));
            Node title = new Node().type(reference(titleTypeId)).value("Coffee");
            String titleId = store(language, provider, title);
            Node typed = new Node().type(reference(holderTypeId))
                    .properties("title", title.clone());
            Node primitive = new Node().type(reference(holderTypeId))
                    .properties("title", new Node().value("Coffee"));
            Node referenced = new Node().type(reference(holderTypeId))
                    .properties("title", reference(titleId));

            // When calculating the identity of each equivalent authoring form.
            String typedId = language.identity().sourceDocumentBlueId(typed);
            String primitiveId = language.identity().sourceDocumentBlueId(primitive);
            String referencedId = language.identity().sourceDocumentBlueId(referenced);

            // Then primitive syntax retains the same proven custom type identity.
            assertEquals(referencedId, typedId);
            assertEquals(referencedId, primitiveId);
        }
    }

    @Test
    void shouldPreserveUntypedChildIdentityWhenTheFieldDeclaresDictionary() {
        // Given an exact untyped object accepted by a Dictionary field.
        BasicNodeProvider provider = new BasicNodeProvider();
        try (BlueLanguage language = BlueLanguage.builder().nodeProvider(provider).build()) {
            String holderTypeId = store(language, provider,
                    source(language, "name: Holder\nterms:\n  type: Dictionary\n"));
            Node terms = new Node().properties("title", new Node().value("Coffee"));
            String termsId = store(language, provider, terms);
            Node inline = new Node().type(reference(holderTypeId))
                    .properties("terms", terms.clone());
            Node referenced = new Node().type(reference(holderTypeId))
                    .properties("terms", reference(termsId));

            // When calculating identity after applying the field's type constraint.
            String inlineId = language.identity().sourceDocumentBlueId(inline);
            String referencedId = language.identity().sourceDocumentBlueId(referenced);

            // Then the field constraint does not become part of the exact child's identity.
            assertEquals(referencedId, inlineId);
        }
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

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }
}
