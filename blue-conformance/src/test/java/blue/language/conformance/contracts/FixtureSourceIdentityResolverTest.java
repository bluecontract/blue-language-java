package blue.language.conformance.contracts;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.runtime.BlueLanguage;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Regression coverage for fixture-only exact Source identity discovery. */
final class FixtureSourceIdentityResolverTest {

    @Test
    void shouldAllowFiniteAuthoredExpansionOfMutuallyRecursiveTypeScope() {
        Node recursiveTypes = new Node().items(
                new Node()
                        .name("Recursive A")
                        .properties(
                                "child",
                                new Node().type(
                                        new Node().blueId("this#1"))),
                new Node()
                        .name("Recursive B")
                        .properties(
                                "child",
                                new Node().type(
                                        new Node().blueId("this#0"))));
        BasicNodeProvider provider = new BasicNodeProvider(recursiveTypes);
        Node source = new Node()
                .type(new Node().blueId(
                        provider.getBlueIdByName("Recursive A")))
                .properties(
                        "child",
                        new Node().properties(
                                "child",
                                new Node().properties(
                                        "marker",
                                        new Node().value("bounded"))));

        FixtureSourceIdentityResolver.Identity identity;
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build()) {
            identity = FixtureSourceIdentityResolver.resolve(
                    language,
                    source,
                    Collections.<String, java.util.List<String>>emptyMap(),
                    true);
        }

        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(
                        identity.canonicalInput()),
                identity.blueId());
    }

    @Test
    void shouldRejectCyclicMemberWithoutProviderProof() {
        Node recursiveTypes = new Node().items(
                new Node()
                        .name("Unproved Recursive A")
                        .type(new Node().blueId("this#1")),
                new Node()
                        .name("Unproved Recursive B")
                        .type(new Node().blueId("this#0")));
        BasicNodeProvider provedProvider =
                new BasicNodeProvider(recursiveTypes);
        String memberBlueId =
                provedProvider.getBlueIdByName("Unproved Recursive A");

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(blueId ->
                        memberBlueId.equals(blueId)
                                ? provedProvider.fetchByBlueId(blueId)
                                : null)
                .build()) {
            assertThrows(
                    IllegalStateException.class,
                    () -> FixtureSourceIdentityResolver.resolve(
                            language,
                            new Node().type(
                                    new Node().blueId(memberBlueId)),
                            Collections.<String, java.util.List<String>>emptyMap(),
                            true));
        }
    }

    @Test
    void shouldRejectExpandedProviderContentWithAnotherRootIdentity() {
        Node exactType = new Node()
                .properties("kind", new Node().value("verified-type"));
        String typeBlueId =
                DirectBlueIdCalculator.calculateBlueId(exactType);
        Node mislabeledType = exactType.clone().blueId(
                DirectBlueIdCalculator.calculateBlueId(
                        new Node().value("another")));

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(blueId ->
                        typeBlueId.equals(blueId)
                                ? Collections.singletonList(mislabeledType)
                                : null)
                .build()) {
            assertThrows(
                    IllegalStateException.class,
                    () -> FixtureSourceIdentityResolver.resolve(
                            language,
                            new Node().type(
                                    new Node().blueId(typeBlueId)),
                            Collections.<String, java.util.List<String>>emptyMap(),
                            true));
        }
    }

    @Test
    void shouldPreserveContractWithUnknownTypeAsOpaqueSource() {
        String unknownTypeBlueId =
                "6dUnbVwUFYbg4oBjfbANb3MeDzXvuahShSUppq3YLpNh";
        Node source = new Node().contracts(
                new Node().properties(
                        "unsupported",
                        new Node()
                                .type(new Node().blueId(unknownTypeBlueId))
                                .properties("authored", new Node().value(true))));

        FixtureSourceIdentityResolver.Identity identity;
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(blueId -> null)
                .build()) {
            identity = FixtureSourceIdentityResolver.resolve(
                    language,
                    source,
                    Collections.<String, java.util.List<String>>emptyMap(),
                    true);
        }

        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(source),
                identity.blueId());
        assertEquals(
                unknownTypeBlueId,
                identity.canonicalInput()
                        .getContracts()
                        .getProperties()
                        .get("unsupported")
                        .getType()
                        .getBlueId());
    }

    @Test
    void shouldResolveUntypedContractConstraintContributedByScopeType() {
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.addSingleNodes(new Node().name("Known zero-field contract"));
        String knownContract = provider.getBlueIdByName(
                "Known zero-field contract");
        provider.addSingleNodes(new Node()
                .name("Synthetic constrained scope")
                .contracts(new Node().properties(
                        "requiredWorkflow",
                        new Node().schema(new Schema().required(true)))));
        String scopeType = provider.getBlueIdByName(
                "Synthetic constrained scope");
        Node source = new Node()
                .type(new Node().blueId(scopeType))
                .contracts(new Node().properties(
                        "requiredWorkflow",
                        new Node()
                                .type(new Node().blueId(knownContract))
                                .properties("value", new Node().value("kept"))));
        Map<String, List<String>> exactFields = Collections.singletonMap(
                knownContract,
                Collections.<String>emptyList());

        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(provider)
                .build()) {
            FixtureSourceIdentityResolver.Identity identity =
                    FixtureSourceIdentityResolver.resolve(
                            language,
                            source,
                            exactFields,
                            true);

            assertEquals(
                    language.identity().sourceDocumentBlueId(source),
                    identity.blueId());
        }
    }

    @Test
    void shouldCatalogRegisteredZeroNodeFieldContractAsKnown() {
        Map<String, List<String>> exactFields =
                ContractsFixtureHarnessDataSupport.RegistryEnvironment
                        .load()
                        .exactSourceFieldsByType();

        assertTrue(exactFields.containsKey(
                RuntimeBlueIds.TYPE_GENERALIZATION_POLICY));
        assertTrue(exactFields.get(
                RuntimeBlueIds.TYPE_GENERALIZATION_POLICY).isEmpty());
    }
}
