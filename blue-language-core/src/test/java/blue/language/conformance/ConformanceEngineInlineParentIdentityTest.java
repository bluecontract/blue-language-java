package blue.language.conformance;

import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.merge.processor.BasicTypesVerifier;
import blue.language.merge.processor.DictionaryProcessor;
import blue.language.merge.processor.ListProcessor;
import blue.language.merge.processor.SchemaPropagator;
import blue.language.merge.processor.SchemaVerifier;
import blue.language.merge.processor.SequentialMergingProcessor;
import blue.language.merge.processor.TypeAssigner;
import blue.language.merge.processor.ValuePropagator;
import blue.language.model.Node;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.runtime.BlueLanguage;
import blue.language.runtime.LanguageProcessing;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static blue.language.model.wire.BlueLanguageConstants.DICTIONARY_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConformanceEngineInlineParentIdentityTest {

    @Test
    void followsInlineParentByResolverIssuedCanonicalIdentity() {
        Node parent = new Node()
                .name("Inline ancestry parent")
                .type(reference(DICTIONARY_TYPE_BLUE_ID))
                .properties("inherited", new Node()
                        .type(reference(TEXT_TYPE_BLUE_ID)));
        BasicNodeProvider provider = new BasicNodeProvider(parent);
        Node child = new Node()
                .name("Inline ancestry child")
                .type(parent.clone())
                .properties("local", new Node()
                        .type(reference(TEXT_TYPE_BLUE_ID)));
        String parentBlueId = provider.getBlueIdByName(
                "Inline ancestry parent");
        CanonicalTypeIdentityEvidence childIdentity;
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            childIdentity = scope.resolveTypeDeclarationIdentity(child);
        }
        provider.addSingleNodes(
                childIdentity.canonicalTypeIdentityInput());
        String childBlueId = childIdentity.blueId();

        try (ConformanceEngine engine = new ConformanceEngine(
                provider,
                new SequentialMergingProcessor(Arrays.asList(
                        new ValuePropagator(),
                        new TypeAssigner(),
                        new ListProcessor(),
                        new DictionaryProcessor(),
                        new SchemaPropagator(),
                        new SchemaVerifier(),
                        new BasicTypesVerifier())))) {
            assertTrue(engine.isSubtypeOf(childBlueId, parentBlueId));
        }
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }
}
