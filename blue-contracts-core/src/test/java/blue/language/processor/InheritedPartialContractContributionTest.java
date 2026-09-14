package blue.language.processor;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.processor.model.ChannelContract;
import blue.language.processor.registry.BlueRuntimeTypeRegistry;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.provider.NodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.runtime.BlueLanguage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class InheritedPartialContractContributionTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldInspectCompletedChannelWithOrderedSourceContributions(
            boolean inheritDeclaration) {
        // Given a channel whose required timeline is supplied by the descendant.
        try (Fixture fixture = new Fixture(false)) {
            Node declaration = fixture.declaration();
            Node completedContribution = declaration.clone()
                    .properties("timeline", new Node().value("coffee-shop"));
            Node parent = new Node().name("Product");
            if (inheritDeclaration) {
                parent.contracts(contracts(declaration));
            }
            Node descendant = new Node().type(reference(fixture.store(parent)))
                    .contracts(contracts(completedContribution));
            String completedId = fixture.identity(completedContribution);
            List<String> expectedContributions = inheritDeclaration
                    ? Arrays.asList(fixture.identity(declaration), completedId)
                    : Collections.singletonList(completedId);
            assertEquals("coffee-shop", fixture.language.resolution()
                    .resolve(descendant)
                    .getAsText("/contracts/providerChannel/timeline"));

            // When inspecting the complete contract through the public API.
            EffectiveContractSnapshot channel = fixture.inspect(descendant);

            // Then its exact contributions retain ancestor-to-descendant order.
            assertEquals(fixture.channelTypeId, channel.effectiveTypeBlueId());
            assertEquals("coffee-shop", channel.headerFields()
                    .get("timeline").getValue());
            assertEquals(expectedContributions, channel.sourceContributionNodeBlueIds());
        }
    }

    @Test
    void shouldCompletePartialAncestorThroughTwoTypesWithoutRepeatingChannelType() {
        // Given Product's incomplete labeled channel inherited by Fixed Product.
        try (Fixture fixture = new Fixture(false)) {
            Node ancestorContribution = fixture.declaration()
                    .properties("label", new Node().value("provider"));
            String productId = fixture.store(new Node().name("Product")
                    .contracts(contracts(ancestorContribution)));
            String fixedProductId = fixture.store(new Node().name("Fixed Product")
                    .type(reference(productId)));
            Node directContribution = new Node()
                    .properties("timeline", new Node().value("coffee-shop"));
            Node document = new Node().type(reference(fixedProductId))
                    .contracts(contracts(directContribution));

            // When the instance supplies the payload without repeating its type.
            EffectiveContractSnapshot channel = fixture.inspect(document);

            // Then the complete header retains both exact contributions in order.
            assertEquals(fixture.channelTypeId, channel.effectiveTypeBlueId());
            assertEquals("coffee-shop", channel.headerFields().get("timeline").getValue());
            assertEquals("provider", channel.headerFields().get("label").getValue());
            assertEquals(Arrays.asList(fixture.identity(ancestorContribution),
                            fixture.identity(directContribution)),
                    channel.sourceContributionNodeBlueIds());
        }
    }

    @Test
    void shouldCombineIndividuallyPartialAncestorAndDirectContributions() {
        // Given two required fields supplied by separate typed contributions.
        try (Fixture fixture = new Fixture(true)) {
            Node ancestorContribution = fixture.declaration()
                    .properties("timeline", new Node().value("coffee-shop"));
            String parentId = fixture.store(new Node().name("Product")
                    .contracts(contracts(ancestorContribution)));
            Node directContribution = fixture.declaration()
                    .properties("actor", new Node().value("provider"));
            Node document = new Node().type(reference(parentId))
                    .contracts(contracts(directContribution));

            // When Contracts inspects the complete merged channel.
            EffectiveContractSnapshot channel = fixture.inspect(document);

            // Then neither individual contribution must supply the other's field.
            assertEquals("coffee-shop", channel.headerFields().get("timeline").getValue());
            assertEquals("provider", channel.headerFields().get("actor").getValue());
            assertEquals(Arrays.asList(fixture.identity(ancestorContribution),
                            fixture.identity(directContribution)),
                    channel.sourceContributionNodeBlueIds());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldRejectMissingOrMalformedRequiredFieldOnEffectiveContract(boolean malformed) {
        // Given an inherited required timeline that the instance leaves invalid.
        try (Fixture fixture = new Fixture(false)) {
            String parentId = fixture.store(new Node().name("Product")
                    .contracts(contracts(fixture.declaration())));
            Node contribution = fixture.declaration();
            if (malformed) {
                contribution.properties("timeline", new Node().value(42));
            }
            Node document = new Node().type(reference(parentId))
                    .contracts(contracts(contribution));

            // When inspecting the effective channel, then validation still rejects it.
            assertThrows(IllegalArgumentException.class, () -> fixture.inspect(document));
        }
    }

    @Test
    void shouldKeepUnavailableOptionalDefinitionOutsideCatalogDemands() {
        // Given a partial ancestor with an exact Node field absent from the provider.
        try (Fixture fixture = new Fixture(false)) {
            String unavailableId = fixture.language.identity().directBlueId(
                    new Node().name("Unavailable definition"));
            Node ancestorContribution = fixture.declaration()
                    .properties("definition", reference(unavailableId));
            String parentId = fixture.store(new Node().name("Product")
                    .contracts(contracts(ancestorContribution)));
            Node directContribution = fixture.declaration()
                    .properties("timeline", new Node().value("coffee-shop"));
            Node document = new Node().type(reference(parentId))
                    .contracts(contracts(directContribution));
            List<String> expectedContributions = Arrays.asList(
                    fixture.identity(ancestorContribution), fixture.identity(directContribution));
            fixture.requests.clear();

            // When cataloging headers without demanding the opaque definition.
            EffectiveContractSnapshot channel = fixture.inspect(document);

            // Then exact contribution identity preserves the reference without fetching it.
            assertEquals("coffee-shop", channel.headerFields().get("timeline").getValue());
            assertEquals(unavailableId,
                    channel.headerFields().get("definition").getReferenceBlueId());
            assertEquals(expectedContributions, channel.sourceContributionNodeBlueIds());
            assertFalse(fixture.requests.contains(unavailableId));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldCanonicalizeInheritedDefinitionListOnlyOnce(boolean referencedDefinition) {
        // Given a cold definition whose own type contributes the first list item.
        try (Fixture fixture = new Fixture(false)) {
            String bodyTypeId = fixture.store(new Node().name("Body")
                    .properties("steps", new Node().items(new Node().value("one"))));
            Node authoredBody = new Node().type(reference(bodyTypeId))
                    .properties("steps", new Node().items(new Node().value("two")));
            Node canonicalBody = fixture.language.identity().canonicalIdentityInput(authoredBody);
            String bodyId = fixture.language.identity().directBlueId(canonicalBody);
            fixture.provider.addSingleNodes(canonicalBody);
            Node ancestorContribution = fixture.declaration();
            String parentId = fixture.store(new Node().name("Product")
                    .contracts(contracts(ancestorContribution)));
            Node directContribution = fixture.declaration()
                    .properties("timeline", new Node().value("coffee-shop"))
                    .properties("definition", referencedDefinition
                            ? reference(bodyId) : authoredBody);
            Node document = new Node().type(reference(parentId))
                    .contracts(contracts(directContribution));
            // The independent expected exact input contains the body canonicalized once.
            Node expectedCanonicalContribution = fixture.language.preprocessing()
                    .preprocess(directContribution.clone())
                    .properties("definition", canonicalBody.clone());
            String expectedDirectId = fixture.language.identity()
                    .directBlueId(expectedCanonicalContribution);

            // When identifying the contributions while preserving the cold definition.
            EffectiveContractSnapshot channel = fixture.inspect(document);

            // Then enclosing identity must not append the inherited item a second time.
            assertEquals(Arrays.asList(fixture.identity(ancestorContribution), expectedDirectId),
                    channel.sourceContributionNodeBlueIds());
            assertEquals("coffee-shop", channel.headerFields().get("timeline").getValue());
            if (referencedDefinition) {
                assertEquals(bodyId, channel.headerFields().get("definition").getReferenceBlueId());
            } else {
                // The header retains authored Source; only contribution identity
                // uses the independently canonicalized [one, two] body above.
                Node retainedBody = channel.headerFields().get("definition").toNode();
                assertEquals(1, retainedBody.getNode("/steps").getItems().size());
                assertEquals("two", retainedBody.getAsText("/steps/0"));
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldPreserveInlineAndReferencedContributionIdentities(boolean useReferences) {
        // Given the same exact partial and complete contributions in either representation.
        try (Fixture fixture = new Fixture(false)) {
            Node ancestorContribution = fixture.declaration();
            Node directContribution = fixture.declaration()
                    .properties("timeline", new Node().value("coffee-shop"));
            String ancestorId = fixture.store(ancestorContribution);
            String directId = fixture.store(directContribution);
            String parentId = fixture.store(new Node().name("Product")
                    .contracts(contracts(useReferences
                            ? reference(ancestorId) : ancestorContribution)));
            Node document = new Node().type(reference(parentId))
                    .contracts(contracts(useReferences
                            ? reference(directId) : directContribution));

            // When cataloging the completed inherited channel.
            EffectiveContractSnapshot channel = fixture.inspect(document);

            // Then materialization preserves the exact contribution sequence.
            assertEquals("coffee-shop", channel.headerFields().get("timeline").getValue());
            assertEquals(Arrays.asList(ancestorId, directId),
                    channel.sourceContributionNodeBlueIds());
        }
    }

    private static Node contracts(Node contribution) {
        return new Node().properties("providerChannel", contribution.clone());
    }

    private static String store(
            BlueLanguage language, BasicNodeProvider provider, Node source) {
        Node canonical = language.identity().canonicalIdentityInput(source);
        provider.addSingleNodes(canonical);
        return language.identity().directBlueId(canonical);
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static final class Fixture implements AutoCloseable {
        private final BasicNodeProvider provider = new BasicNodeProvider();
        private final List<String> requests = new ArrayList<>();
        private final BlueLanguage language;
        private final BlueContracts contracts;
        private final String channelTypeId;

        private Fixture(boolean requireActor) {
            NodeProvider recordingProvider = blueId -> {
                requests.add(blueId);
                return provider.fetchByBlueId(blueId);
            };
            language = BlueLanguage.builder().nodeProvider(new SequentialNodeProvider(
                    Arrays.asList(recordingProvider, BlueRuntimeTypeRegistry.getDefault()
                            .asProcessorSnapshotProvider()))).build();
            Node channelType = new Node().name("Timeline Channel")
                    .type(reference(RuntimeBlueIds.CHANNEL))
                    .properties("timeline", requiredText());
            if (requireActor) {
                channelType.properties("actor", requiredText());
            }
            Node canonical = language.identity().canonicalIdentityInput(channelType);
            channelTypeId = store(canonical);
            contracts = BlueContracts.builder(language.processing())
                    .runtimeRegistry(ContractProcessorRegistryBuilder.create()
                            .register(channelTypeId, canonical, new TimelineProcessor()).build())
                    .build();
        }

        private Node declaration() {
            return new Node().type(reference(channelTypeId));
        }

        private String identity(Node source) {
            return language.identity().sourceDocumentBlueId(source);
        }

        private String store(Node source) {
            return InheritedPartialContractContributionTest.store(language, provider, source);
        }

        private EffectiveContractSnapshot inspect(Node document) {
            List<EffectiveContractSnapshot> effective = contracts.effectiveFragmentationCatalog(
                    language.preprocessing().preprocess(document))
                    .effectiveContractsByScope().get("/");
            assertEquals(1, effective.size());
            assertEquals("providerChannel", effective.get(0).key());
            return effective.get(0);
        }

        private static Node requiredText() {
            return new Node().type(reference(TEXT_TYPE_BLUE_ID))
                    .schema(new Schema().required(true));
        }

        @Override
        public void close() {
            contracts.close();
            language.close();
        }
    }

    public static final class TimelineChannel extends ChannelContract {
        private String timeline;
        private String actor;

        public String getTimeline() {
            return timeline;
        }

        public void setTimeline(String timeline) {
            this.timeline = timeline;
        }

        public String getActor() {
            return actor;
        }

        public void setActor(String actor) {
            this.actor = actor;
        }
    }

    private static final class TimelineProcessor
            implements ChannelProcessor<TimelineChannel> {
        @Override
        public Class<TimelineChannel> contractType() {
            return TimelineChannel.class;
        }
    }
}
