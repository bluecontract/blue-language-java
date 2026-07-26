package blue.language;

import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static blue.language.utils.Properties.LIST_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedReferenceMaterializationTest {

    @Test
    void expandingExactRootReferencePreservesNodeBlueId() {
        Fixture fixture = new Fixture();
        Node reference = reference(fixture.concreteDocumentId);

        Node expanded = fixture.blue.expand(reference);

        assertEquals(fixture.concreteDocumentId,
                fixture.blue.calculateBlueId(reference));
        assertEquals(fixture.concreteDocumentId,
                fixture.blue.calculateBlueId(expanded));
        assertEquals("present", expanded.getAsText("/instanceValue"));
    }

    @Test
    void recursivelyExpandedDocumentPreservesParentIdentity() {
        Fixture fixture = new Fixture();
        Node collapsed = fixture.holderInstance();

        Node expanded = fixture.blue.expand(collapsed);

        assertEquals(fixture.blue.calculateBlueId(collapsed),
                fixture.blue.calculateBlueId(expanded));
        assertEquals("present", expanded.getAsText("/subject/instanceValue"));
        assertEquals("Materialization Compute",
                expanded.getAsNode("/subject/type/steps/0/type").getName());
    }

    @Test
    void pureReferenceAndEquivalentInlineNodeHaveTheSameIdentity() {
        Fixture fixture = new Fixture();
        Node referenced = fixture.holderInstance();
        Node inline = fixture.holderWithInlineSubject();

        assertEquals(fixture.concreteDocumentId,
                fixture.blue.calculateBlueId(fixture.inlineSubject()));
        assertEquals(fixture.blue.calculateBlueId(referenced),
                fixture.blue.calculateBlueId(inline));
    }

    @Test
    void repeatedExpansionDoesNotMakeCacheStateObservable() {
        Fixture fixture = new Fixture();

        Node first = fixture.blue.expand(fixture.holderInstance());
        Node second = fixture.blue.expand(fixture.holderInstance());
        Node fresh = new Blue(fixture.provider)
                .expand(fixture.holderInstance());

        assertEquals(fixture.blue.nodeToJson(first),
                fixture.blue.nodeToJson(second));
        assertEquals(fixture.blue.nodeToJson(first),
                fixture.blue.nodeToJson(fresh));
        assertEquals(fixture.blue.calculateBlueId(first),
                fixture.blue.calculateBlueId(fresh));
    }

    @Test
    void mixedBlueIdMaterializationIsNeverAcceptedAsBlueContent() {
        Fixture fixture = new Fixture();
        Node mixed = fixture.inlineSubject()
                .blueId(fixture.concreteDocumentId);

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> fixture.blue.calculateBlueId(mixed));

        assertTrue(messageChain(failure).contains("reference-only"));
    }

    @Test
    void expansionRejectsProviderContentThatDoesNotVerifyRequestedIdentity() {
        Fixture fixture = new Fixture();
        Blue mismatched = new Blue(blueId -> Collections.singletonList(
                new Node().name("Different provider content")));

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> mismatched.expand(reference(fixture.concreteDocumentId)));

        assertEquals(BlueLanguageErrorCategory.ProviderBlueIdMismatch,
                BlueLanguageErrorClassifier.classify(failure));
    }

    private static String messageChain(Throwable failure) {
        StringBuilder messages = new StringBuilder();
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return messages.toString();
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static final class Fixture {
        private final BasicNodeProvider provider = new BasicNodeProvider();
        private final String concreteDocumentId;
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
            String computeTypeId =
                    provider.getBlueIdByName("Materialization Compute");

            Node documentType = new Node()
                    .name("Materialization Document Type")
                    .properties("steps", new Node()
                            .type(reference(LIST_TYPE_BLUE_ID))
                            .itemType(reference(stepTypeId))
                            .items(new Node().type(reference(computeTypeId))));
            provider.addSingleNodes(documentType);
            String documentTypeId =
                    provider.getBlueIdByName("Materialization Document Type");

            Node concreteDocument = new Node()
                    .name("Concrete Materialization Document")
                    .type(reference(documentTypeId))
                    .properties("instanceValue", new Node().value("present"));
            provider.addSingleNodes(concreteDocument);
            concreteDocumentId =
                    provider.getBlueIdByName("Concrete Materialization Document");

            Node holderType = new Node()
                    .name("Materialization Holder")
                    .properties("subject", new Node());
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
            Node subject = provider.fetchFirstByBlueId(concreteDocumentId).clone();
            if (subject.getBlueId() != null) {
                subject.blueId(null);
            }
            return subject;
        }
    }
}
