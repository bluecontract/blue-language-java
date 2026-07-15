package blue.language;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.merge.Merger;
import blue.language.provider.BasicNodeProvider;
import blue.language.snapshot.ResolvedReferenceCache;
import org.junit.jupiter.api.Test;

import static blue.language.utils.Properties.LIST_TYPE_BLUE_ID;
import static blue.language.utils.limits.Limits.NO_LIMITS;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VerifiedReferenceMaterializationTest {

    @Test
    void coldTypedFieldMaterializesConcreteReferenceWithoutReapplyingItsDeclaredType() {
        Fixture fixture = new Fixture();

        Node resolved = assertDoesNotThrow(() -> fixture.blue.resolve(fixture.holderInstance()));

        assertNotEquals(fixture.documentTypeId, fixture.concreteDocumentId,
                "the referenced document must not be its own type definition");
        assertEquals(fixture.concreteDocumentId, resolved.getAsNode("/subject").getBlueId());
        assertEquals("present", resolved.getAsText("/subject/instanceValue"));
        assertEquals(fixture.computeTypeId,
                resolved.getAsNode("/subject/steps/0/type").getBlueId());
    }

    @Test
    void warmTypedFieldResolutionMatchesColdResolution() {
        Fixture fixture = new Fixture();
        Node cold = fixture.blue.resolve(fixture.holderInstance());

        Node warm = assertDoesNotThrow(() -> fixture.blue.resolve(fixture.holderInstance()));

        assertEquals(fixture.blue.nodeToJson(cold), fixture.blue.nodeToJson(warm));
    }

    @Test
    void pureReferenceAndEquivalentInlineDocumentHaveTheSameSemanticIdentity() {
        Fixture fixture = new Fixture();

        Node referenced = fixture.blue.resolve(fixture.holderInstance());
        Node inline = assertDoesNotThrow(() -> fixture.blue.resolve(fixture.holderWithInlineSubject()));

        assertEquals(fixture.concreteDocumentId,
                fixture.blue.calculateBlueId(fixture.inlineSubject()));
        assertEquals(fixture.blue.calculateSemanticBlueId(referenced),
                fixture.blue.calculateSemanticBlueId(inline));
        assertEquals(fixture.computeTypeId,
                inline.getAsNode("/subject/steps/0/type").getBlueId());
    }

    @Test
    void unresolvedTargetWithTheSameDeclaredTypeStillReceivesItsTypeContribution() {
        Fixture fixture = new Fixture();
        Node target = new Node().type(reference(fixture.documentTypeId));
        Node source = new Node().type(reference(fixture.documentTypeId));
        Merger merger = new Merger(fixture.blue.getMergingProcessor(), fixture.provider,
                new ResolvedReferenceCache());

        merger.merge(target, source, NO_LIMITS);

        assertNotNull(target.getAsNode("/steps/0"));
        assertEquals(fixture.computeTypeId, target.getAsNode("/steps/0/type").getBlueId());
    }

    @Test
    void expandedTypeMetadataAloneDoesNotProveItsContributionWasApplied() {
        Fixture fixture = new Fixture();
        Node expandedType = fixture.provider.fetchFirstByBlueId(fixture.documentTypeId)
                .clone()
                .blueId(fixture.documentTypeId);
        Node target = new Node().type(expandedType);
        Node source = new Node().type(reference(fixture.documentTypeId));
        Merger merger = new Merger(fixture.blue.getMergingProcessor(), fixture.provider,
                new ResolvedReferenceCache());

        merger.merge(target, source, NO_LIMITS);

        assertNotNull(target.getAsNode("/steps/0"));
        assertEquals(fixture.computeTypeId, target.getAsNode("/steps/0/type").getBlueId());
    }

    @Test
    void materializedTargetWithADifferentDeclaredTypeStillChecksCompatibility() {
        Fixture fixture = new Fixture();
        Node materializedTargetType = fixture.provider.fetchFirstByBlueId(fixture.documentTypeId)
                .clone()
                .blueId(fixture.documentTypeId);
        Node target = new Node().type(materializedTargetType);
        Node source = new Node().type(reference(fixture.otherDocumentTypeId));
        Merger merger = new Merger(fixture.blue.getMergingProcessor(), fixture.provider,
                new ResolvedReferenceCache());

        assertThrows(IllegalArgumentException.class,
                () -> merger.merge(target, source, NO_LIMITS));
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static final class Fixture {
        private final BasicNodeProvider provider = new BasicNodeProvider();
        private final String computeTypeId;
        private final String documentTypeId;
        private final String concreteDocumentId;
        private final String otherDocumentTypeId;
        private final String holderTypeId;
        private final Blue blue;

        private Fixture() {
            Node stepType = new Node().name("Materialization Step");
            provider.addSingleNodes(stepType);
            String stepTypeId = provider.getBlueIdByName("Materialization Step");

            Node computeType = new Node()
                    .name("Materialization Compute")
                    .type(reference(stepTypeId));
            provider.addSingleNodes(computeType);
            computeTypeId = provider.getBlueIdByName("Materialization Compute");

            Node documentType = new Node()
                    .name("Materialization Document Type")
                    .properties("steps", new Node()
                            .type(reference(LIST_TYPE_BLUE_ID))
                            .itemType(reference(stepTypeId))
                            .items(new Node().type(reference(computeTypeId))));
            provider.addSingleNodes(documentType);
            documentTypeId = provider.getBlueIdByName("Materialization Document Type");

            Node concreteDocument = new Node()
                    .name("Concrete Materialization Document")
                    .type(reference(documentTypeId))
                    .properties("instanceValue", new Node().value("present"));
            provider.addSingleNodes(concreteDocument);
            concreteDocumentId = provider.getBlueIdByName("Concrete Materialization Document");

            provider.addSingleNodes(new Node()
                    .name("Other Materialization Document Type")
                    .properties("otherValue", new Node().value("other")));
            otherDocumentTypeId = provider.getBlueIdByName("Other Materialization Document Type");

            Node holderType = new Node()
                    .name("Materialization Holder")
                    .properties("subject", new Node()
                            .type(reference(documentTypeId))
                            .schema(new Schema().required(true)));
            provider.addSingleNodes(holderType);
            holderTypeId = provider.getBlueIdByName("Materialization Holder");
            blue = new Blue(provider);
        }

        private Node holderInstance() {
            return new Node()
                    .type(reference(holderTypeId))
                    .properties("subject", reference(concreteDocumentId));
        }

        private Node holderWithInlineSubject() {
            return new Node()
                    .type(reference(holderTypeId))
                    .properties("subject", inlineSubject());
        }

        private Node inlineSubject() {
            return provider.fetchFirstByBlueId(concreteDocumentId).clone().blueId(null);
        }
    }
}
