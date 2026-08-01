package blue.language;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.api.LanguageRuntimeAccess;
import blue.language.provider.NodeProvider;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.provider.CyclicAwareNodeProvider;
import blue.language.provider.CyclicSetProof;
import blue.language.provider.CyclicSetProofResult;
import blue.language.provider.NodeContentHandler;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.CircularSetIdentityCalculator;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecursiveTypeResolutionTest {

    @Test
    void shouldResolveSelfRecursiveFieldTypeToFiniteReferenceBoundary() {
        // given
        CyclicFixture fixture = new CyclicFixture(
                "- name: Recursive Entry\n"
                        + "  previous:\n"
                        + "    type:\n"
                        + "      blueId: this#0\n");
        String entryId = fixture.id("Recursive Entry");

        // when
        Node resolved = fixture.blue.resolve(instanceOf(entryId));
        Node recursiveType = resolved.getAsNode("/previous/type");

        // then
        assertEquals(entryId, recursiveType.getBlueId());
        assertTrue(recursiveType.isReferenceOnly());
    }

    @Test
    void shouldResolveEachMutualFieldTypeOnceAndCloseWithReference() {
        // given
        CyclicFixture fixture = new CyclicFixture(
                "- name: Person\n"
                        + "  pet:\n"
                        + "    type:\n"
                        + "      blueId: this#1\n"
                        + "- name: Dog\n"
                        + "  owner:\n"
                        + "    type:\n"
                        + "      blueId: this#0\n");
        String personId = fixture.id("Person");
        String dogId = fixture.id("Dog");

        // when
        Node resolved = fixture.blue.resolve(instanceOf(personId));
        Node personBoundary = resolved.getAsNode("/pet/owner/type");

        // then
        assertEquals(dogId, resolved.getAsNode("/pet/type").getBlueId());
        assertEquals(personId, personBoundary.getBlueId());
        assertTrue(personBoundary.isReferenceOnly());
    }

    @Test
    void shouldKeepTypedReferenceToRecursiveInstanceFiniteAndCacheIndependent() {
        // given
        Node documents = YAML_MAPPER.readValue(
                "- name: Person\n"
                        + "  pet:\n"
                        + "    type:\n"
                        + "      blueId: this#1\n"
                        + "- name: Dog\n"
                        + "  owner:\n"
                        + "    type:\n"
                        + "      blueId: this#0\n",
                Node.class);
        BasicNodeProvider provider = new BasicNodeProvider(documents);
        String personId = provider.getBlueIdByName("Person");
        String dogId = provider.getBlueIdByName("Dog");
        Node dog = new Node().name("Fido").type(reference(dogId));
        provider.addSingleNodes(dog);
        String dogIdReference = provider.getBlueIdByName("Fido");
        Node source = instanceOf(personId).properties("pet", reference(dogIdReference));

        // when
        Blue coldBlue = new Blue(provider);
        Node cold = coldBlue.resolve(source.clone());
        Blue prewarmedBlue = new Blue(provider);
        prewarmedBlue.resolveToSnapshot(dog.clone());
        Node prewarmed = prewarmedBlue.resolve(source.clone());
        Node repeated = prewarmedBlue.resolve(source.clone());
        Node canonical = prewarmedBlue.canonicalize(source.clone());

        // then
        assertReference(cold.getAsNode("/pet/type/owner/type/pet/type"), dogId);
        assertEquals(JSON_MAPPER.valueToTree(cold), JSON_MAPPER.valueToTree(prewarmed));
        assertEquals(JSON_MAPPER.valueToTree(prewarmed), JSON_MAPPER.valueToTree(repeated));
        assertEquals(JSON_MAPPER.valueToTree(source),
                JSON_MAPPER.valueToTree(canonical));
    }

    @Test
    void shouldUseFiniteReferenceBoundariesForRecursiveCollectionMetadata() {
        // given
        CyclicFixture fixture = new CyclicFixture(
                "- name: Recursive Container\n"
                        + "  children:\n"
                        + "    type: List\n"
                        + "    itemType:\n"
                        + "      blueId: this#0\n"
                        + "  byName:\n"
                        + "    type: Dictionary\n"
                        + "    keyType: Text\n"
                        + "    valueType:\n"
                        + "      blueId: this#0\n");
        String containerId = fixture.id("Recursive Container");

        // when
        Node resolved = fixture.blue.resolve(instanceOf(containerId));
        Node itemType = resolved.getAsNode("/children/itemType");
        Node valueType = resolved.getAsNode("/byName/valueType");

        // then
        assertEquals(containerId, itemType.getBlueId());
        assertEquals(containerId, valueType.getBlueId());
        assertTrue(itemType.isReferenceOnly());
        assertTrue(valueType.isReferenceOnly());
    }

    @Test
    void shouldKeepRepeatedRecursiveFieldsAsIndependentReferenceBoundaries() {
        // given
        CyclicFixture fixture = new CyclicFixture(
                "- name: Binary Node\n"
                        + "  left:\n"
                        + "    type:\n"
                        + "      blueId: this#0\n"
                        + "  right:\n"
                        + "    type:\n"
                        + "      blueId: this#0\n");
        String nodeId = fixture.id("Binary Node");

        // when
        Node first = fixture.blue.resolve(instanceOf(nodeId));
        Node second = fixture.blue.resolve(instanceOf(nodeId));

        // then
        assertReference(first.getAsNode("/left/type"), nodeId);
        assertReference(first.getAsNode("/right/type"), nodeId);
        assertEquals(JSON_MAPPER.valueToTree(first), JSON_MAPPER.valueToTree(second));
    }

    @Test
    void shouldKeepFiniteBoundaryWhenCyclicTypedValueMergesIntoInheritedSlot() {
        // given
        CyclicFixture fixture = new CyclicFixture(
                "- name: Recursive Entry\n"
                        + "  previous:\n"
                        + "    type:\n"
                        + "      blueId: this#0\n"
                        + "- name: Entry Holder\n"
                        + "  entry:\n"
                        + "    description: Optional entry slot.\n");
        String entryId = fixture.id("Recursive Entry");
        String holderId = fixture.id("Entry Holder");
        Node source = instanceOf(holderId)
                .properties("entry", instanceOf(entryId));

        // when
        Node first = fixture.blue.resolve(source.clone());
        Node repeated = fixture.blue.resolve(source.clone());
        Node previous = first.getAsNode("/entry/previous");

        // then
        assertReference(previous.getType(), entryId);
        assertTrue(previous.getProperties() == null
                || !previous.getProperties().containsKey("previous"));
        assertEquals(JSON_MAPPER.valueToTree(first), JSON_MAPPER.valueToTree(repeated));
        assertFalse(first.getAsNode("/entry/type").isReferenceOnly());
    }

    @Test
    void shouldResolveMaterializedRecursiveValueAgainWithoutExpandingBoundary() {
        // given
        CyclicFixture fixture = new CyclicFixture(
                "- name: Recursive Entry\n"
                        + "  previous:\n"
                        + "    type:\n"
                        + "      blueId: this#0\n");
        String entryId = fixture.id("Recursive Entry");
        // when
        Node first = fixture.blue.resolve(instanceOf(entryId));
        Node repeated = fixture.blue.resolve(first.clone());
        Node previous = repeated.getProperties().get("previous");

        // then
        assertFalse(repeated.getType().isReferenceOnly());
        assertReference(previous.getType(), entryId);
        assertTrue(previous.getProperties() == null
                || !previous.getProperties().containsKey("previous"));
        assertEquals(JSON_MAPPER.valueToTree(first), JSON_MAPPER.valueToTree(repeated));
    }

    @Test
    void shouldApplyInheritedValidationToRecursiveInstanceOccurrencesOnDemand() {
        // given
        CyclicFixture fixture = new CyclicFixture(
                "- name: Recursive A\n"
                        + "  next:\n"
                        + "    type:\n"
                        + "      blueId: this#1\n"
                        + "  code:\n"
                        + "    type: Text\n"
                        + "    schema:\n"
                        + "      maxLength: 4\n"
                        + "- name: Recursive B\n"
                        + "  previous:\n"
                        + "    type:\n"
                        + "      blueId: this#0\n");
        String aId = fixture.id("Recursive A");
        Node valid = recursiveInstance(aId, "GOOD");
        Node invalid = recursiveInstance(aId, "TOO_LONG");

        // when
        Node resolved = fixture.blue.resolve(valid);
        IllegalArgumentException failure =
                captureFailure(() -> fixture.blue.resolve(invalid));
        Node nestedCode = resolved.getProperties().get("next")
                .getProperties().get("previous")
                .getProperties().get("code");
        Schema nestedSchema = nestedCode.getSchema();
        BlueLanguageErrorCategory errorCategory =
                BlueLanguageErrorClassifier.classify(failure);

        // then
        assertTrue(failure instanceof IllegalArgumentException);
        assertEquals("GOOD", resolved.getAsText("/next/previous/code"));
        assertNotNull(nestedSchema);
        assertEquals(4, ((Number) nestedSchema.getMaxLength().getValue()).intValue());
        assertEquals(BlueLanguageErrorCategory.SchemaViolation,
                errorCategory);
    }

    @Test
    void shouldKeepDirectSelfInheritanceAsTypeCycle() {
        // given
        CyclicFixture fixture = new CyclicFixture(
                "- name: Invalid Self Parent\n"
                        + "  type:\n"
                        + "    blueId: this#0\n");

        // when
        RuntimeException failure = captureFailure(
                () -> fixture.blue.resolve(instanceOf(fixture.id("Invalid Self Parent"))));
        BlueLanguageErrorCategory errorCategory =
                BlueLanguageErrorClassifier.classify(failure);

        // then
        assertTrue(failure instanceof RuntimeException);
        assertEquals(BlueLanguageErrorCategory.TypeCycle,
                errorCategory);
    }

    @Test
    void shouldKeepMutualInheritanceAsTypeCycle() {
        // given
        CyclicFixture fixture = new CyclicFixture(
                "- name: Invalid Parent A\n"
                        + "  type:\n"
                        + "    blueId: this#1\n"
                        + "- name: Invalid Parent B\n"
                        + "  type:\n"
                        + "    blueId: this#0\n");

        // when
        RuntimeException failure = captureFailure(
                () -> fixture.blue.resolve(instanceOf(fixture.id("Invalid Parent A"))));
        BlueLanguageErrorCategory errorCategory =
                BlueLanguageErrorClassifier.classify(failure);

        // then
        assertTrue(failure instanceof RuntimeException);
        assertEquals(BlueLanguageErrorCategory.TypeCycle,
                errorCategory);
    }

    @Test
    void shouldPreservePureReferenceOnDeclaredUntypedFieldDuringCanonicalization() {
        // given
        Node holder = new Node().name("Reference Holder")
                .properties("previous", new Node().description("Optional predecessor reference."));
        BasicNodeProvider provider = new BasicNodeProvider(holder);
        Blue blue = new Blue(provider);
        String holderId = provider.getBlueIdByName("Reference Holder");
        String previousId = DirectBlueIdCalculator.calculateBlueId(new Node().name("Previous Entry"));
        Node source = instanceOf(holderId).properties("previous", reference(previousId));
        Node expected = instanceOf(holderId)
                .properties("previous", reference(previousId));

        // when
        Node canonical = blue.canonicalize(source);
        String expectedBlueId =
                DirectBlueIdCalculator.calculateBlueId(expected);
        String canonicalBlueId =
                DirectBlueIdCalculator.calculateBlueId(canonical);

        // then
        assertEquals(holderId, canonical.getType().getBlueId());
        assertReference(canonical.getProperties().get("previous"), previousId);
        assertEquals(expectedBlueId, canonicalBlueId);
    }

    private static Node instanceOf(String typeBlueId) {
        return new Node().type(reference(typeBlueId));
    }

    private static Node recursiveInstance(String typeBlueId, String code) {
        return instanceOf(typeBlueId).properties("next",
                new Node().properties("previous",
                        new Node().properties("code", new Node().value(code))));
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static void assertReference(Node node, String expectedBlueId) {
        assertNotNull(node);
        assertEquals(expectedBlueId, node.getBlueId());
        assertTrue(node.isReferenceOnly());
    }

    private static final class CyclicFixture {
        private final NodeProvider provider;
        private final Blue blue;
        private final Map<String, String> idsByName;

        private CyclicFixture(String documents) {
            Node documentList = YAML_MAPPER.readValue(documents, Node.class);
            if (documentList.getItems().size() == 1) {
                SingletonCyclicProvider singleton = new SingletonCyclicProvider(documentList.getItems().get(0));
                provider = singleton;
                idsByName = singleton.idsByName;
            } else {
                BasicNodeProvider basic = new BasicNodeProvider(documentList);
                provider = basic;
                idsByName = new LinkedHashMap<>();
                for (Node document : documentList.getItems()) {
                    idsByName.put(document.getName(), basic.getBlueIdByName(document.getName()));
                }
            }
            blue = new Blue(provider);
        }

        private String id(String name) {
            return idsByName.get(name);
        }
    }

    private static final class SingletonCyclicProvider
            implements NodeProvider, CyclicAwareNodeProvider {
        private final String memberId;
        private final Node content;
        private final CyclicSetProof proof;
        private final Map<String, String> idsByName = new LinkedHashMap<>();

        private SingletonCyclicProvider(Node source) {
            Node preprocessed = new Blue().preprocess(source.clone());
            memberId = CircularSetIdentityCalculator
                    .calculateCircularSetBlueIds(Collections.singletonList(preprocessed)).get(0);
            String masterId = memberId.substring(0, memberId.indexOf('#'));
            content = JSON_MAPPER.treeToValue(NodeContentHandler.resolveThisReferences(
                    JSON_MAPPER.valueToTree(preprocessed), masterId, true), Node.class);
            proof = CyclicSetProof.fromDeclaredPlaceholderSet(
                    Collections.singletonList(preprocessed));
            idsByName.put(source.getName(), memberId);
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            return memberId.equals(blueId)
                    ? Collections.singletonList(content.clone())
                    : null;
        }

        @Override
        public CyclicSetProofResult cyclicSetProofFor(String blueId) {
            return memberId.equals(blueId)
                    ? CyclicSetProofResult.found(proof)
                    : CyclicSetProofResult.notFound();
        }
    }
}
