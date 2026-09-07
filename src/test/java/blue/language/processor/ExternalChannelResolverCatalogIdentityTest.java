package blue.language.processor;

import blue.language.Blue;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.Node;
import blue.language.processor.model.ChannelContract;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExternalChannelResolverCatalogIdentityTest {

    private static final Node CHANNEL_TYPE =
            new Node().name("Catalog identity evidence channel");
    private static final String CHANNEL_TYPE_BLUE_ID =
            DirectBlueIdCalculator.calculateBlueId(CHANNEL_TYPE);

    @Test
    void shouldConvertCanonicalizedChannelWithProducingSnapshotEvidence() {
        // given
        try (Blue blue = blue()) {
            DocumentProcessor processor = blue.getDocumentProcessor();
            ResolvedSnapshot snapshot = processor.snapshotManager()
                    .fromDocumentTransient(documentWithInlineChannelType());
            ContractBundle bundle = processor.contractLoader()
                    .load(snapshot, "/");
            EffectiveContractSnapshot channelSnapshot =
                    bundle.effectiveContractSnapshot("source");

            FrozenNode effectiveChannel = bundle.contractNode("source");

            // when
            ChannelContract converted = new ExternalChannelResolverCatalog(
                    processor.registry(),
                    processor.contractConverter(),
                    bundle,
                    null)
                    .freshChannel(channelSnapshot);

            // then
            assertTrue(effectiveChannel.getType().isReferenceOnly());
            assertTrue(bundle.canonicalTypeIdentities()
                    .hasCompleteCoverage());
            EvidenceChannel channel = assertInstanceOf(
                    EvidenceChannel.class, converted);
            assertEquals("source", channel.getKey());
            assertEquals(CHANNEL_TYPE_BLUE_ID, channel.getTypeBlueId());
            assertEquals("topic-a", channel.getTopic());
        }
    }

    @Test
    void shouldRejectMaterializedChannelWithoutProducingSnapshotEvidence() {
        // given
        try (Blue blue = blue()) {
            DocumentProcessor processor = blue.getDocumentProcessor();
            ResolvedSnapshot snapshot = processor.snapshotManager()
                    .fromDocumentTransient(documentWithInlineChannelType());
            ContractBundle resolved = processor.contractLoader()
                    .load(snapshot, "/");
            EffectiveContractSnapshot channelSnapshot =
                    resolved.effectiveContractSnapshot("source");
            Node materializedChannel = documentWithInlineChannelType()
                    .getContracts()
                    .getProperties()
                    .get("source");
            ContractBundle withoutEvidence = ContractBundle.builder()
                    .addChannel(
                            "source",
                            resolved.channel("source"),
                            FrozenNode.fromResolvedNode(materializedChannel))
                    .addEffectiveContractSnapshot(channelSnapshot)
                    .build();

            // when
            ExternalChannelResolverCatalog catalog =
                    new ExternalChannelResolverCatalog(
                            processor.registry(),
                            processor.contractConverter(),
                            withoutEvidence,
                            null);

            // then
            IllegalStateException failure = assertThrows(
                    IllegalStateException.class,
                    () -> catalog.freshChannel(channelSnapshot));

            assertTrue(failure.getMessage().contains(
                    "resolver-issued canonical type identity evidence"),
                    failure.getMessage());
        }
    }

    private static Blue blue() {
        Blue blue = ProcessorTestSupport.blue();
        blue.registerExternalContractType(
                CHANNEL_TYPE_BLUE_ID,
                CHANNEL_TYPE,
                new EvidenceChannelProcessor());
        return blue;
    }

    private static Node documentWithInlineChannelType() {
        return new Node().contracts(
                new Node().properties(
                        "source",
                        new Node()
                                .type(CHANNEL_TYPE.clone())
                                .properties(
                                        "topic",
                                        new Node().value("topic-a"))));
    }

    public static final class EvidenceChannel extends ChannelContract {
        private String topic;

        public String getTopic() {
            return topic;
        }

        public void setTopic(String topic) {
            this.topic = topic;
        }
    }

    private static final class EvidenceChannelProcessor
            implements ChannelProcessor<EvidenceChannel> {

        @Override
        public Class<EvidenceChannel> contractType() {
            return EvidenceChannel.class;
        }
    }
}
