package blue.language;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.merge.Merger;
import blue.language.merge.MergingProcessor;
import blue.language.merge.processor.BasicTypesVerifier;
import blue.language.merge.processor.DictionaryProcessor;
import blue.language.merge.processor.ListProcessor;
import blue.language.merge.processor.SchemaPropagator;
import blue.language.merge.processor.SchemaVerifier;
import blue.language.merge.processor.SequentialMergingProcessor;
import blue.language.merge.processor.TypeAssigner;
import blue.language.merge.processor.ValuePropagator;
import blue.language.provider.BasicNodeProvider;
import blue.language.snapshot.FrozenNode;
import blue.language.snapshot.ResolvedReferenceCache;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;

class ResolvedInstanceSchemaValidationTest {

    @Test
    void materializedSubtypeSatisfiesRequiredTypedField() {
        Fixture fixture = new Fixture();

        Node instance = fixture.holderInstance(new Node()
                .type(reference(fixture.concreteSubjectId))
                .properties("identifier", new Node().value("subject-1")));

        Node resolved = assertDoesNotThrow(() -> fixture.blue.resolve(instance));

        assertEquals("subject-1", resolved.getProperties().get("subject")
                .getProperties().get("identifier").getValue());
    }

    @Test
    void missingRequiredTypedFieldFailsAfterCompletedMerge() {
        Fixture fixture = new Fixture();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> fixture.blue.resolve(fixture.holderInstance(null)));

        assertTrue(failure.getMessage().contains("/subject"));
        assertTrue(failure.getMessage().contains("Required"));
    }

    @Test
    void metadataOnlyAndTypeDerivedBlueIdDoNotSatisfyRequired() {
        Fixture fixture = new Fixture();

        assertThrows(IllegalArgumentException.class, () -> fixture.blue.resolve(
                fixture.holderInstance(new Node().description("declaration metadata"))));
        assertThrows(IllegalArgumentException.class, () -> fixture.blue.resolve(
                fixture.holderInstance(new Node().type(reference(fixture.concreteSubjectId)))));
    }

    @Test
    void requiredPresenceAcceptsEverySemanticPayloadForm() {
        Schema required = new Schema().required(true);
        Blue blue = new Blue(new BasicNodeProvider());

        assertDoesNotThrow(() -> blue.resolve(new Node().schema(required.clone()).value("value")));
        assertDoesNotThrow(() -> blue.resolve(new Node().schema(required.clone())
                .properties("field", new Node().value("value"))));
        assertDoesNotThrow(() -> blue.resolve(new Node().schema(required.clone())
                .items(new ArrayList<>())));
        assertDoesNotThrow(() -> blue.resolve(new Node().schema(required.clone())
                .items(new Node().value("value"))));
        assertDoesNotThrow(() -> blue.resolve(new Node().schema(required.clone())));
    }

    @Test
    void emptyObjectDoesNotSatisfyNestedRequiredField() {
        Node type = new Node().name("Required Holder")
                .properties("field", new Node().schema(new Schema().required(true)));
        BasicNodeProvider provider = new BasicNodeProvider(type);
        String typeId = provider.getBlueIdByName("Required Holder");
        Blue blue = new Blue(provider);
        Node instance = new Node().type(reference(typeId)).properties("field", new Node());

        assertThrows(IllegalArgumentException.class, () -> blue.resolve(instance));
    }

    @Test
    void requiredOnlyReferenceDoesNotFetchProvider() {
        Node payload = new Node().name("Payload").value("content");
        BasicNodeProvider delegate = new BasicNodeProvider(payload);
        String payloadId = delegate.getBlueIdByName("Payload");
        CountingProvider provider = new CountingProvider(delegate);
        Blue blue = new Blue(provider);
        Node type = new Node().name("Untyped Holder")
                .properties("payload", new Node().schema(new Schema().required(true)));
        delegate.addSingleNodes(type);
        String typeId = delegate.getBlueIdByName("Untyped Holder");

        assertDoesNotThrow(() -> blue.resolve(new Node().type(reference(typeId))
                .properties("payload", reference(payloadId))));

        assertEquals(0, provider.fetches(payloadId));
    }

    @Test
    void typedReferenceFetchesOnceAndWarmCacheAvoidsProvider() {
        Fixture fixture = new Fixture();
        Node referenced = new Node().name("Referenced Subject")
                .type(reference(fixture.concreteSubjectId))
                .properties("identifier", new Node().value("subject-1"));
        fixture.delegate.addSingleNodes(referenced);
        String referenceId = fixture.delegate.getBlueIdByName("Referenced Subject");
        Node instance = fixture.holderInstance(reference(referenceId));

        assertDoesNotThrow(() -> fixture.blue.resolve(instance));
        assertEquals(1, fixture.provider.fetches(referenceId));

        assertDoesNotThrow(() -> fixture.blue.resolve(instance));
        assertEquals(1, fixture.provider.fetches(referenceId));
    }

    @Test
    void typedReferenceWithoutRepeatedTypeUsesNormalInheritanceRules() {
        Fixture fixture = new Fixture();
        Node untypedContent = new Node().name("Untyped Subject Content")
                .properties("identifier", new Node().value("subject-1"));
        fixture.delegate.addSingleNodes(untypedContent);
        String referenceId = fixture.delegate.getBlueIdByName("Untyped Subject Content");

        Node resolved = assertDoesNotThrow(() -> fixture.blue.resolve(
                fixture.holderInstance(reference(referenceId))));

        Node subject = resolved.getProperties().get("subject");
        assertEquals(fixture.baseSubjectId, subject.getType().getBlueId());
        assertEquals("subject-1", subject.getProperties().get("identifier").getValue());
    }

    @Test
    void materializedTypedValueDoesNotFetchItsContentIdentity() {
        Fixture fixture = new Fixture();
        Node materialized = new Node().name("Inline Subject")
                .type(reference(fixture.concreteSubjectId))
                .properties("identifier", new Node().value("subject-1"));
        String materializedId = blueIdOf(materialized);

        assertDoesNotThrow(() -> fixture.blue.resolve(fixture.holderInstance(materialized)));

        assertEquals(0, fixture.provider.fetches(materializedId));
    }

    @Test
    void repeatedTypedReferencesFetchSameBlueIdOncePerResolution() {
        Fixture fixture = new Fixture(true);
        Node referenced = new Node().name("Shared Subject")
                .type(reference(fixture.concreteSubjectId))
                .properties("identifier", new Node().value("subject-1"));
        fixture.delegate.addSingleNodes(referenced);
        String referenceId = fixture.delegate.getBlueIdByName("Shared Subject");

        Node instance = new Node().type(reference(fixture.holderId))
                .properties("subject", reference(referenceId))
                .properties("secondSubject", reference(referenceId));

        assertDoesNotThrow(() -> fixture.blue.resolve(instance));
        assertEquals(1, fixture.provider.fetches(referenceId));
    }

    @Test
    void incompatibleTypedReferenceFailsWithAffectedPath() {
        Fixture fixture = new Fixture();
        Node otherType = new Node().name("Other Type");
        fixture.delegate.addSingleNodes(otherType);
        String otherTypeId = fixture.delegate.getBlueIdByName("Other Type");
        Node incompatible = new Node().name("Incompatible Subject")
                .type(reference(otherTypeId))
                .value("wrong");
        fixture.delegate.addSingleNodes(incompatible);
        String incompatibleId = fixture.delegate.getBlueIdByName("Incompatible Subject");

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> fixture.blue.resolve(fixture.holderInstance(reference(incompatibleId))));

        assertTrue(messageChain(failure).contains("subject"), messageChain(failure));
    }

    @Test
    void payloadConstrainedReferenceMaterializesBeforeValidation() {
        Node payload = new Node().name("List Payload")
                .items(new Node().value("one"), new Node().value("two"));
        BasicNodeProvider delegate = new BasicNodeProvider(payload);
        String payloadId = delegate.getBlueIdByName("List Payload");
        Node holder = new Node().name("List Holder").properties("payload",
                new Node().schema(new Schema().minItems(2)));
        delegate.addSingleNodes(holder);
        String holderId = delegate.getBlueIdByName("List Holder");
        CountingProvider provider = new CountingProvider(delegate);
        Blue blue = new Blue(provider);

        assertDoesNotThrow(() -> blue.resolve(new Node().type(reference(holderId))
                .properties("payload", reference(payloadId))));

        assertEquals(1, provider.fetches(payloadId));
    }

    @Test
    void missingRequiredReferenceContentFailsDeterministically() {
        Fixture fixture = new Fixture();
        String unavailable = blueIdOf(new Node().name("Unavailable Subject"));

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> fixture.blue.resolve(fixture.holderInstance(reference(unavailable))));

        assertTrue(failure.getMessage().contains(unavailable));
        assertEquals(1, fixture.provider.fetches(unavailable));
    }

    @Test
    void contextualSnapshotEntryCannotSatisfyLaterTypedReference() {
        Fixture fixture = new Fixture();
        String unavailableId = blueIdOf(new Node().name("Unavailable Subject")
                .properties("identifier", new Node().value("not available")));
        Node untypedHolder = new Node().name("Untyped Required Holder")
                .properties("subject", new Node().schema(new Schema().required(true)));
        fixture.delegate.addSingleNodes(untypedHolder);
        String untypedHolderId = fixture.delegate.getBlueIdByName("Untyped Required Holder");

        assertDoesNotThrow(() -> fixture.blue.resolveToSnapshot(new Node()
                .type(reference(untypedHolderId))
                .properties("subject", reference(unavailableId))));

        assertThrows(IllegalArgumentException.class, () -> fixture.blue.resolve(
                fixture.holderInstance(reference(unavailableId))));
        assertEquals(1, fixture.provider.fetches(unavailableId));
    }

    @Test
    void contextualResolvedGraphCannotSatisfyPayloadConstrainedReference() {
        String unavailableId = blueIdOf(new Node().name("Unavailable Object")
                .properties("field", new Node().value("not available")));
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        FrozenNode contextual = cache.freezeResolved(new Node().blueId(unavailableId)
                .schema(new Schema().minFields(1))
                .properties("inherited", new Node().value("context only")));
        FrozenNode referenceView = cache.freezeResolved(reference(unavailableId));
        CountingProvider provider = new CountingProvider(blueId -> null);
        Merger merger = new Merger(defaultProcessor(), provider, cache);
        Node target = new Node().schema(new Schema().minFields(1));

        assertThrows(IllegalArgumentException.class,
                () -> merger.merge(target, reference(unavailableId),
                        blue.language.utils.limits.Limits.NO_LIMITS));

        assertFalse(cache.getVerifiedCanonical(unavailableId).isPresent());
        assertNotSame(contextual, referenceView);
        assertTrue(referenceView.isReferenceOnly());
        assertEquals(1, provider.fetches(unavailableId));
    }

    @Test
    void typedReferenceUsesExactVerifiedCacheEntry() {
        Fixture fixture = new Fixture();
        Node referenced = new Node().type(reference(fixture.concreteSubjectId))
                .properties("identifier", new Node().value("subject-1"));
        fixture.delegate.addSingleNodes(referenced);
        String referenceId = fixture.blue.calculateBlueId(fixture.blue.preprocess(referenced.clone()));
        ResolvedSnapshot verifiedSnapshot = fixture.blue.resolveToSnapshot(referenced);
        ResolvedReferenceCache cache = new ResolvedReferenceCache();
        cache.putVerifiedResolved(verifiedSnapshot.verifiedReferenceResolution());
        CountingProvider coldCounter = new CountingProvider(fixture.delegate);
        Merger merger = new Merger(defaultProcessor(), coldCounter, cache);

        Node resolved = assertDoesNotThrow(() -> merger.resolve(
                fixture.holderInstance(reference(referenceId))));

        assertEquals(0, coldCounter.fetches(referenceId));
        assertEquals("subject-1", resolved.getProperties().get("subject")
                .getProperties().get("identifier").getValue());
    }

    @Test
    void providerContentWithWrongBlueIdFailsBeforeValidation() {
        Fixture fixture = new Fixture();
        Node expected = new Node().type(reference(fixture.concreteSubjectId))
                .properties("identifier", new Node().value("expected"));
        String expectedId = fixture.blue.calculateBlueId(fixture.blue.preprocess(expected.clone()));
        Node wrong = new Node().type(reference(fixture.concreteSubjectId))
                .properties("identifier", new Node().value("wrong"));
        NodeProvider wrongContentProvider = blueId -> expectedId.equals(blueId)
                ? Collections.singletonList(wrong.clone())
                : fixture.delegate.fetchByBlueId(blueId);
        Blue blue = new Blue(wrongContentProvider);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> blue.resolve(fixture.holderInstance(reference(expectedId))));

        assertTrue(messageChain(failure).contains(expectedId));
        assertTrue(messageChain(failure).contains("Provider"), messageChain(failure));
    }

    @Test
    void missingTypedReferenceContentIsProviderUnavailable() {
        Fixture fixture = new Fixture();
        String missingId = fixture.blue.calculateBlueId(new Node().name("Missing Required Subject"));

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> fixture.blue.resolve(fixture.holderInstance(reference(missingId))));

        assertEquals(BlueLanguageErrorCategory.ProviderUnavailable,
                BlueLanguageErrorClassifier.classify(failure), messageChain(failure));
        assertEquals(1, fixture.provider.fetches(missingId));
    }

    @Test
    void multiDocumentProviderResultUsesExistingListSemantics() {
        List<Node> documents = Arrays.asList(new Node().value("one"), new Node().value("two"));
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.processNodeList(documents);
        Blue blue = new Blue(provider);
        List<Node> canonicalDocuments = Arrays.asList(
                blue.preprocess(documents.get(0).clone()),
                blue.preprocess(documents.get(1).clone()));
        String referenceId = BlueIdCalculator.calculateBlueId(canonicalDocuments);
        Node holder = new Node().name("Multi-document Holder")
                .properties("payload", new Node().schema(new Schema().minItems(2)));
        provider.addSingleNodes(holder);
        String holderId = provider.getBlueIdByName("Multi-document Holder");

        Node resolved = assertDoesNotThrow(() -> blue.resolve(new Node().type(reference(holderId))
                .properties("payload", reference(referenceId))));

        assertEquals(2, resolved.getProperties().get("payload").getItems().size());
    }

    @Test
    void cyclicRequiredMaterializationFailsWithoutStackOverflow() {
        Node cyclicTypes = YAML_MAPPER.readValue("- name: Cyclic A\n"
                + "  type:\n"
                + "    blueId: this#1\n"
                + "- name: Cyclic B\n"
                + "  type:\n"
                + "    blueId: this#0\n", Node.class);
        BasicNodeProvider provider = new BasicNodeProvider(cyclicTypes);
        String recursiveContentId = provider.getBlueIdByName("Cyclic A");
        Node holder = new Node().name("Recursive Holder")
                .properties("payload", new Node().schema(new Schema().minFields(1)));
        provider.addSingleNodes(holder);
        String holderId = provider.getBlueIdByName("Recursive Holder");
        Blue blue = new Blue(provider);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> blue.resolve(new Node().type(reference(holderId))
                        .properties("payload", reference(recursiveContentId))));

        assertTrue(messageChain(failure).contains("Cyclic"), messageChain(failure));
    }

    @Test
    void schemaFailureEscapesRfc6901PathSegments() {
        String key = "subject/with~markers";
        Node type = new Node().name("Escaped Holder")
                .properties(key, new Node().schema(required()));
        BasicNodeProvider provider = new BasicNodeProvider(type);
        String typeId = provider.getBlueIdByName("Escaped Holder");

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> new Blue(provider).resolve(new Node().type(reference(typeId))));

        assertTrue(failure.getMessage().contains("/subject~1with~0markers"), failure.getMessage());
    }

    @Test
    void minItemsAndMinFieldsUseCompletedPayload() {
        BasicNodeProvider provider = new BasicNodeProvider();
        Node type = new Node().name("Constrained Holder")
                .properties("list", new Node().schema(new Schema().minItems(2)))
                .properties("object", new Node().schema(new Schema().minFields(2)));
        provider.addSingleNodes(type);
        String typeId = provider.getBlueIdByName("Constrained Holder");
        Blue blue = new Blue(provider);

        Node valid = new Node().type(reference(typeId))
                .properties("list", new Node().items(new Node().value("a"), new Node().value("b")))
                .properties("object", new Node()
                        .properties("a", new Node().value("a"))
                        .properties("b", new Node().value("b")));
        assertDoesNotThrow(() -> blue.resolve(valid));

        Node invalidList = valid.clone().properties("list", new Node().items(new Node().value("a")));
        IllegalArgumentException listFailure = assertThrows(IllegalArgumentException.class,
                () -> blue.resolve(invalidList));
        assertTrue(listFailure.getMessage().contains("/list"));
        assertTrue(listFailure.getMessage().contains("minimum required items"), listFailure.getMessage());

        Node invalidObject = valid.clone().properties("object", new Node()
                .properties("a", new Node().value("a")));
        IllegalArgumentException objectFailure = assertThrows(IllegalArgumentException.class,
                () -> blue.resolve(invalidObject));
        assertTrue(objectFailure.getMessage().contains("/object"));
        assertTrue(objectFailure.getMessage().contains("minimum required fields"));
    }

    @Test
    void inheritedFixedPayloadsSatisfyRequired() {
        Node referenced = new Node().name("Fixed Reference").value("fixed");
        BasicNodeProvider provider = new BasicNodeProvider(referenced);
        String referenceId = provider.getBlueIdByName("Fixed Reference");
        Node fixedType = new Node().name("Fixed Values")
                .properties("scalar", new Node().schema(required()).value("fixed"))
                .properties("object", new Node().schema(required())
                        .properties("nested", new Node().value("fixed")))
                .properties("list", new Node().schema(required()).items(new ArrayList<>()))
                .properties("reference", reference(referenceId));
        provider.addSingleNodes(fixedType);
        String fixedTypeId = provider.getBlueIdByName("Fixed Values");
        Node type = new Node().name("Fixed Holder").type(reference(fixedTypeId))
                .properties("reference", new Node().schema(required()));
        provider.addSingleNodes(type);
        String typeId = provider.getBlueIdByName("Fixed Holder");

        assertDoesNotThrow(() -> new Blue(provider).resolve(new Node().type(reference(typeId))));
    }

    @Test
    void retainedOrdinaryChildMakesInheritedObjectSemanticallyPresent() {
        Node type = new Node().name("Declaration Holder")
                .properties("field", new Node().schema(required())
                        .properties("nested", new Node().description("metadata only")));
        BasicNodeProvider provider = new BasicNodeProvider(type);
        String typeId = provider.getBlueIdByName("Declaration Holder");

        Node resolved = assertDoesNotThrow(
                () -> new Blue(provider).resolve(new Node().type(reference(typeId))));

        assertEquals("metadata only", resolved.getProperties().get("field")
                .getProperties().get("nested").getDescription());
    }

    @Test
    void referenceAndEquivalentMaterializedValueHaveSameCanonicalIdentity() {
        Fixture fixture = new Fixture();
        Node materialized = new Node().type(reference(fixture.concreteSubjectId))
                .properties("identifier", new Node().value("subject-1"));
        fixture.delegate.addSingleNodes(materialized);
        String referenceId = fixture.blue.calculateBlueId(fixture.blue.preprocess(materialized.clone()));
        Node referencedInstance = fixture.holderInstance(reference(referenceId));
        Node materializedInstance = fixture.holderInstance(materialized.clone());

        assertEquals(fixture.blue.calculateSemanticBlueId(materializedInstance),
                fixture.blue.calculateSemanticBlueId(referencedInstance));

        Node canonical = fixture.blue.canonicalize(referencedInstance);
        Node canonicalSubject = canonical.getProperties().get("subject");
        assertTrue(canonicalSubject.isReferenceOnly(), canonicalSubject.toString());
        assertEquals(referenceId, canonicalSubject.getBlueId());
    }

    @Test
    void resolveAndSnapshotAgreeForValidationMaterialization() {
        Fixture fixture = new Fixture();
        Node referenced = new Node().name("Snapshot Subject")
                .type(reference(fixture.concreteSubjectId))
                .properties("identifier", new Node().value("subject-1"));
        fixture.delegate.addSingleNodes(referenced);
        String referenceId = fixture.delegate.getBlueIdByName("Snapshot Subject");
        Node instance = fixture.holderInstance(reference(referenceId));

        Node resolved = fixture.blue.resolve(instance);
        ResolvedSnapshot snapshot = fixture.blue.resolveToSnapshot(instance);

        assertEquals(resolved.getProperties().get("subject").getProperties().get("identifier").getValue(),
                snapshot.resolvedAt("/subject/identifier").getValue());
        assertTrue(snapshot.canonicalAt("/subject").isReferenceOnly(),
                snapshot.canonicalAt("/subject").toString());
        assertEquals(referenceId, snapshot.canonicalAt("/subject").getReferenceBlueId());
    }

    @Test
    void loadSnapshotAppliesCompletedSchemaValidation() {
        Fixture fixture = new Fixture();

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> fixture.blue.loadSnapshot(fixture.holderInstance(null)));

        assertTrue(failure.getMessage().contains("/subject"), failure.getMessage());
    }

    @Test
    void canonicalizationIsStableAcrossColdAndWarmReferenceCache() {
        Fixture fixture = new Fixture();
        Node referenced = new Node().type(reference(fixture.concreteSubjectId))
                .properties("identifier", new Node().value("subject-1"));
        fixture.delegate.addSingleNodes(referenced);
        String referenceId = fixture.blue.calculateBlueId(fixture.blue.preprocess(referenced.clone()));
        Node instance = fixture.holderInstance(reference(referenceId));

        Node cold = fixture.blue.canonicalize(instance);
        fixture.blue.resolve(instance);
        Node warm = fixture.blue.canonicalize(instance);

        assertEquals(BlueIdCalculator.calculateBlueId(cold), BlueIdCalculator.calculateBlueId(warm));
        assertTrue(cold.getProperties().get("subject").isReferenceOnly());
        assertTrue(warm.getProperties().get("subject").isReferenceOnly());
    }

    @Test
    void publicAndProcessingSnapshotsPreserveNestedListReferenceIdentityColdAndWarm() {
        Fixture fixture = new Fixture();
        Node referenced = new Node().name("Nested Snapshot Subject")
                .type(reference(fixture.concreteSubjectId))
                .properties("identifier", new Node().value("subject-1"));
        fixture.delegate.addSingleNodes(referenced);
        String referenceId = fixture.delegate.getBlueIdByName("Nested Snapshot Subject");
        Node source = new Node().items(
                fixture.holderInstance(reference(referenceId)),
                fixture.holderInstance(reference(referenceId)));

        ResolvedSnapshot publicCold = fixture.blue.resolveToSnapshot(source);
        fixture.blue.clearResolvedSnapshotCache();
        ResolvedSnapshot processingCold = fixture.blue.initializeDocument(source).snapshot();
        ResolvedSnapshot publicWarm = fixture.blue.resolveToSnapshot(source);
        ResolvedSnapshot processingWarm = fixture.blue.initializeDocument(source).snapshot();

        assertEquals(publicCold.blueId(), processingCold.blueId());
        assertEquals(publicCold.blueId(), publicWarm.blueId());
        assertEquals(publicCold.blueId(), processingWarm.blueId());
        assertEquals(referenceId, publicCold.canonicalAt("/0/subject").getReferenceBlueId());
        assertEquals(referenceId, processingCold.canonicalAt("/1/subject").getReferenceBlueId());
        assertEquals(publicCold.canonicalAt("/0/subject").resolvedStructuralKey(),
                processingCold.canonicalAt("/0/subject").resolvedStructuralKey());
        assertEquals(publicCold.resolvedAt("/1/subject").resolvedStructuralKey(),
                processingWarm.resolvedAt("/1/subject").resolvedStructuralKey());
    }

    private static Schema required() {
        return new Schema().required(true);
    }

    private static MergingProcessor defaultProcessor() {
        return new SequentialMergingProcessor(Arrays.asList(
                new ValuePropagator(),
                new TypeAssigner(),
                new ListProcessor(),
                new DictionaryProcessor(),
                new SchemaPropagator(),
                new SchemaVerifier(),
                new BasicTypesVerifier()));
    }

    private static String messageChain(Throwable failure) {
        StringBuilder message = new StringBuilder();
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current.getMessage() != null) {
                message.append(current.getMessage()).append('\n');
            }
        }
        return message.toString();
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static String blueIdOf(Node node) {
        BasicNodeProvider provider = new BasicNodeProvider(node);
        List<Node> fetched = provider.fetchByBlueId(blue.language.utils.BlueIdCalculator.calculateBlueId(node));
        if (fetched != null) {
            return blue.language.utils.BlueIdCalculator.calculateBlueId(node);
        }
        throw new AssertionError("Unable to calculate fixture BlueId");
    }

    private static final class Fixture {
        private final BasicNodeProvider delegate = new BasicNodeProvider();
        private final CountingProvider provider;
        private final Blue blue;
        private final String concreteSubjectId;
        private final String baseSubjectId;
        private final String holderId;

        private Fixture() {
            this(false);
        }

        private Fixture(boolean secondSubject) {
            Node baseSubject = new Node().name("Base Subject");
            delegate.addSingleNodes(baseSubject);
            baseSubjectId = delegate.getBlueIdByName("Base Subject");

            Node concreteSubject = new Node().name("Concrete Subject")
                    .type(reference(baseSubjectId));
            delegate.addSingleNodes(concreteSubject);
            concreteSubjectId = delegate.getBlueIdByName("Concrete Subject");

            Node holder = new Node().name("Holder").properties("subject",
                    new Node().type(reference(baseSubjectId))
                            .schema(new Schema().required(true)));
            if (secondSubject) {
                holder.properties("secondSubject", new Node().type(reference(baseSubjectId))
                        .schema(new Schema().required(true)));
            }
            delegate.addSingleNodes(holder);
            holderId = delegate.getBlueIdByName("Holder");
            provider = new CountingProvider(delegate);
            blue = new Blue(provider);
        }

        private Node holderInstance(Node subject) {
            Node instance = new Node().type(reference(holderId));
            if (subject != null) {
                instance.properties("subject", subject);
            }
            return instance;
        }
    }

    private static final class CountingProvider implements NodeProvider {
        private final NodeProvider delegate;
        private final ConcurrentHashMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();

        private CountingProvider(NodeProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            counts.computeIfAbsent(blueId, ignored -> new AtomicInteger()).incrementAndGet();
            return delegate.fetchByBlueId(blueId);
        }

        private int fetches(String blueId) {
            AtomicInteger count = counts.get(blueId);
            return count == null ? 0 : count.get();
        }
    }
}
