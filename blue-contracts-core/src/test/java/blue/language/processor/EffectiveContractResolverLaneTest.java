package blue.language.processor;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.mapping.NodeToObjectConverter;
import blue.language.mapping.TypeClassResolver;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.processor.model.Contract;
import blue.language.runtime.BlueLanguage;
import blue.language.runtime.LanguageProcessing;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EffectiveContractResolverLaneTest {

    @Test
    void shouldFilterSelectedLaneWithoutChangingExactSourceRepresentation() {
        String retainedReferenceBlueId = blueId(
                "retained authored reference");
        Node retainedContract = new Node()
                .name("Exact authored contract representation")
                .type(new Node()
                        .name("Exact authored type representation")
                        .properties("sourceOnly", text("preserve me")))
                .properties(
                        "body", text("selected"),
                        "sourceReference",
                        new Node().blueId(retainedReferenceBlueId));
        Node selectedScope = new Node()
                .contracts(new Node()
                        .properties("retain", retainedContract)
                        .properties("drop", new Node()
                                .properties("body", text("not selected"))));
        EffectiveContractResolver resolver = resolver(
                new TypeClassResolver());

        Node filtered = resolver.filterSelectedScopeContracts(
                FrozenNode.fromSourceNode(selectedScope),
                Collections.singleton("retain"));

        Node retained = filtered.getContracts().getProperties().get("retain");
        assertEquals(
                "Exact authored contract representation",
                retained.getName());
        assertEquals(
                "Exact authored type representation",
                retained.getType().getName());
        assertNotNull(retained.getType().getProperties().get("sourceOnly"));
        Node retainedReference = retained.getProperties()
                .get("sourceReference");
        assertTrue(retainedReference.isReferenceOnly());
        assertEquals(retainedReferenceBlueId,
                retainedReference.getBlueId());
        assertFalse(filtered.getContracts().getProperties()
                .containsKey("drop"));

        assertEquals("Exact authored contract representation",
                retainedContract.getName(),
                "filtering must not mutate its selected input");
        assertEquals(
                "Exact authored type representation",
                retainedContract.getType().getName());
    }

    @Test
    void shouldKeepResolvedEffectiveTypeAndUseEvidenceAtIdentityBoundaries() {
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleDocs(
                "name: Effective Contract Base Type\n"
                        + "inheritedHeader: inherited\n");
        String baseTypeBlueId = provider.getBlueIdByName(
                "Effective Contract Base Type");
        Node authoredInlineType = new Node()
                .name("Authored Inline Contract Type")
                .type(new Node().blueId(baseTypeBlueId))
                .properties("authoredHeader", text("authored"));
        Node source = new Node().contracts(new Node()
                .properties("retain", new Node()
                        .type(authoredInlineType.clone())
                        .properties("payload", text("value")))
                .properties("drop", new Node()
                        .type(new Node().blueId(baseTypeBlueId))));

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            ResolvedSnapshot snapshot = scope.resolveTransient(source);
            FrozenNode completedContract = snapshot.frozenResolvedRoot()
                    .getContracts().getProperties().get("retain");
            String expectedTypeBlueId = snapshot.canonicalTypeIdentities()
                    .requireCanonicalTypeBlueId(
                            completedContract.getType().toNode());
            TypeClassResolver typeResolver = new TypeClassResolver()
                    .register(expectedTypeBlueId, InlineContract.class);
            NodeToObjectConverter converter = new NodeToObjectConverter(
                    typeResolver);
            EffectiveContractResolver resolver = resolver(
                    typeResolver, converter);

            Node filtered = resolver.filterEffectiveScopeContracts(
                    snapshot.frozenResolvedRoot(),
                    Collections.singleton("retain"),
                    snapshot.canonicalTypeIdentities());
            Node retained = filtered.getContracts()
                    .getProperties().get("retain");
            String projectedTypeBlueId = resolver.typeBlueId(
                    FrozenNode.fromResolvedNode(retained),
                    snapshot.canonicalTypeIdentities());
            Contract converted = converter.convertWithType(
                    retained,
                    Contract.class,
                    false,
                    snapshot.canonicalTypeIdentities());

            assertEquals(expectedTypeBlueId, projectedTypeBlueId,
                    "the resolver must index normalized authored inline "
                            + "Source as well as completion");
            assertInstanceOf(InlineContract.class, converted);
            assertEquals(
                    "Authored Inline Contract Type",
                    retained.getType().getName());
            assertNotNull(retained.getType().getProperties()
                    .get("authoredHeader"));
            assertNotNull(retained.getType().getProperties()
                            .get("inheritedHeader"),
                    "the effective lane must retain inherited runtime "
                            + "structure");
            assertFalse(filtered.getContracts().getProperties()
                    .containsKey("drop"));
            assertNotNull(completedContract.getType().getProperties()
                            .get("inheritedHeader"),
                    "filtering must not mutate the completed input");
            Node sourceType = snapshot.sourceRoot().getContracts()
                    .getProperties().get("retain").getType();
            assertTrue(snapshot.isSourceBacked());
            assertFalse(sourceType.isReferenceOnly(),
                    "the Source lane must retain the authored inline type");
            Node canonicalType = snapshot.canonicalRoot().getContracts()
                    .getProperties().get("retain").getType();
            assertTrue(canonicalType.isReferenceOnly(),
                    "the canonical lane must contain the exact type identity");
            assertEquals(expectedTypeBlueId, canonicalType.getBlueId());
        }
    }

    @Test
    void shouldFailClosedAtIdentityBoundaryWhenEffectiveTypeHasNoEvidence() {
        Node effectiveScope = new Node().contracts(new Node()
                .properties("retain", new Node()
                        .type(new Node().name(
                                "Unproved materialized type"))));
        EffectiveContractResolver resolver = resolver(
                new TypeClassResolver());

        Node filtered = resolver.filterEffectiveScopeContracts(
                FrozenNode.fromResolvedNode(effectiveScope),
                Collections.singleton("retain"),
                CanonicalTypeIdentityLookup.incomplete());
        FrozenNode retained = FrozenNode.fromResolvedNode(
                filtered.getContracts().getProperties().get("retain"));
        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> resolver.typeBlueId(
                        retained,
                        CanonicalTypeIdentityLookup.incomplete()));

        assertTrue(failure.getMessage().contains(
                "resolver-issued canonical type identity evidence"));
    }

    private static EffectiveContractResolver resolver(
            TypeClassResolver typeResolver) {
        return resolver(
                typeResolver,
                new NodeToObjectConverter(typeResolver));
    }

    private static EffectiveContractResolver resolver(
            TypeClassResolver typeResolver,
            NodeToObjectConverter converter) {
        return new EffectiveContractResolver(
                ContractProcessorRegistryBuilder.create().build(),
                converter,
                typeResolver,
                new ContractContributionCollector(null));
    }

    private static Node text(String value) {
        return new Node().value(value);
    }

    private static String blueId(String value) {
        return DirectBlueIdCalculator.calculateBlueId(text(value));
    }

    public static final class InlineContract extends Contract {
        public InlineContract() {
        }
    }
}
