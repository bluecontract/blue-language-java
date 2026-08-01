package blue.language;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueLanguageRuntime;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.api.LanguageRuntimeAccess;
import blue.language.api.WeightedLruCache;
import blue.language.provider.NodeProvider;

import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.utils.Properties.LIST_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifiedReferenceMaterializationTest {

    @Test
    void shouldPreserveNodeBlueIdWhenExpandingExactRootReference() {
        // given
        Fixture fixture = new Fixture();
        Node reference = reference(fixture.concreteDocumentId);

        // when
        Node expanded = fixture.blue.expand(reference);
        String referenceBlueId = fixture.blue.calculateBlueId(reference);
        String expandedBlueId = fixture.blue.calculateBlueId(expanded);

        // then
        assertEquals(fixture.concreteDocumentId, referenceBlueId);
        assertEquals(fixture.concreteDocumentId, expandedBlueId);
        assertEquals("present", expanded.getAsText("/instanceValue"));
    }

    @Test
    void shouldPreserveParentIdentityWhenRecursivelyExpandingDocument() {
        // given
        Fixture fixture = new Fixture();
        Node collapsed = fixture.holderInstance();
        String collapsedBlueId = fixture.blue.calculateBlueId(collapsed);

        // when
        Node expanded = fixture.blue.expand(collapsed);
        String expandedBlueId = fixture.blue.calculateBlueId(expanded);

        // then
        assertEquals(collapsedBlueId, expandedBlueId);
        assertEquals("present", expanded.getAsText("/subject/instanceValue"));
        assertEquals("Materialization Compute",
                expanded.getAsNode("/subject/type/steps/0/type").getName());
    }

    @Test
    void shouldGivePureReferenceAndEquivalentInlineNodeTheSameIdentity() {
        // given
        Fixture fixture = new Fixture();
        Node referenced = fixture.holderInstance();
        Node inline = fixture.holderWithInlineSubject();
        Node inlineSubject = fixture.inlineSubject();

        // when
        String inlineSubjectBlueId =
                fixture.blue.calculateBlueId(inlineSubject);
        String referencedBlueId = fixture.blue.calculateBlueId(referenced);
        String inlineBlueId = fixture.blue.calculateBlueId(inline);

        // then
        assertEquals(fixture.concreteDocumentId, inlineSubjectBlueId);
        assertEquals(referencedBlueId, inlineBlueId);
    }

    @Test
    void shouldKeepCacheStateUnobservableAcrossRepeatedExpansions() {
        // given
        Fixture fixture = new Fixture();
        Blue freshBlue = new Blue(fixture.provider);

        // when
        Node first = fixture.blue.expand(fixture.holderInstance());
        Node second = fixture.blue.expand(fixture.holderInstance());
        Node fresh = freshBlue.expand(fixture.holderInstance());

        // then
        assertEquals(fixture.blue.nodeToJson(first),
                fixture.blue.nodeToJson(second));
        assertEquals(fixture.blue.nodeToJson(first),
                fixture.blue.nodeToJson(fresh));
        assertEquals(fixture.blue.calculateBlueId(first),
                fixture.blue.calculateBlueId(fresh));
    }

    @Test
    void shouldRejectMixedBlueIdMaterializationAsBlueContent() {
        // given
        Fixture fixture = new Fixture();
        Node mixed = fixture.inlineSubject()
                .blueId(fixture.concreteDocumentId);

        // when
        Throwable failure = captureFailure(
                () -> fixture.blue.calculateBlueId(mixed));

        // then
        assertInstanceOf(IllegalArgumentException.class, failure);
        assertTrue(messageChain(failure).contains("reference-only"));
    }

    @Test
    void shouldRejectProviderContentThatDoesNotVerifyRequestedIdentityDuringExpansion() {
        // given
        Fixture fixture = new Fixture();
        Blue mismatched = new Blue(blueId -> Collections.singletonList(
                new Node().name("Different provider content")));

        // when
        Throwable failure = captureFailure(
                () -> mismatched.expand(reference(fixture.concreteDocumentId)));

        // then
        assertInstanceOf(RuntimeException.class, failure);
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
