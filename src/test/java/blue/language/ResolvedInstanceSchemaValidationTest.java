package blue.language;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.runtime.LanguageRuntimeAccess;
import blue.language.provider.NodeProvider;

import static blue.language.processor.DocumentProcessingResultTestSupport.snapshot;

import blue.language.model.Node;
import blue.language.model.Nodes;
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
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedReferenceCache;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static blue.language.codec.jackson.UncheckedObjectMapper.YAML_MAPPER;

class ResolvedInstanceSchemaValidationTest {

    @Test
    void shouldSatisfyRequiredTypedFieldWithMaterializedSubtype() {
        // given
        Fixture fixture = new Fixture();

        Node instance = fixture.holderInstance(new Node()
                .type(reference(fixture.concreteSubjectId))
                .properties("identifier", new Node().value("subject-1")));

        // when
        Node resolved = fixture.blue.resolve(instance);

        // then
        assertEquals("subject-1", resolved.getProperties().get("subject")
                .getProperties().get("identifier").getValue());
    }

    @Test
    void shouldFailMissingRequiredTypedFieldAfterCompletedMerge() {
        // given
        Fixture fixture = new Fixture();

        // when
        IllegalArgumentException failure = captureFailure(
                () -> fixture.blue.resolve(fixture.holderInstance(null)));

        // then
        assertTrue(failure instanceof IllegalArgumentException,
                String.valueOf(failure));
        assertTrue(failure.getMessage().contains("/subject"));
        assertTrue(failure.getMessage().contains("Required"));
    }

    @Test
    void shouldNotSatisfyRequiredWithMetadataOnlyOrTypeDerivedBlueId() {
        // given
        Fixture fixture = new Fixture();

        // when
        IllegalArgumentException metadataFailure = captureFailure(() -> fixture.blue.resolve(
                fixture.holderInstance(new Node().description("declaration metadata"))));
        IllegalArgumentException typeOnlyFailure = captureFailure(() -> fixture.blue.resolve(
                fixture.holderInstance(new Node().type(reference(fixture.concreteSubjectId)))));

        // then
        assertTrue(metadataFailure instanceof IllegalArgumentException,
                String.valueOf(metadataFailure));
        assertTrue(typeOnlyFailure instanceof IllegalArgumentException,
                String.valueOf(typeOnlyFailure));
    }

    @Test
    void shouldAcceptEverySemanticPayloadFormForRequiredPresence() {
        // given
        Schema required = new Schema().required(true);
        Blue blue = new Blue(new BasicNodeProvider());

        // when
        List<Node> resolved = Arrays.asList(
                blue.resolve(new Node().schema(required.clone()).value("value")),
                blue.resolve(new Node().schema(required.clone())
                        .properties("field", new Node().value("value"))),
                blue.resolve(new Node().schema(required.clone()).items(new ArrayList<>())),
                blue.resolve(new Node().schema(required.clone())
                        .items(new Node().value("value"))),
                blue.resolve(new Node().schema(required.clone())));

        // then
        assertEquals(5, resolved.size());
    }

    @Test
    void shouldSatisfyNestedRequiredFieldWithExactEmptyObject() {
        // given
        Node type = new Node().name("Required Holder")
                .properties("field", new Node().schema(new Schema().required(true)));
        BasicNodeProvider provider = new BasicNodeProvider(type);
        String typeId = provider.getBlueIdByName("Required Holder");
        Blue blue = new Blue(provider);
        Node instance = new Node().type(reference(typeId))
                .properties("field", Nodes.emptyObject());

        // when
        Node resolved = blue.resolve(instance);

        // then
        assertNotNull(resolved.getProperties().get("field").getProperties());
        assertTrue(resolved.getProperties().get("field")
                .getProperties().isEmpty());
    }

    @Test
    void shouldNotFetchProviderForRequiredOnlyReference() {
        // given
        Node payload = new Node().name("Payload").value("content");
        BasicNodeProvider delegate = new BasicNodeProvider(payload);
        String payloadId = delegate.getBlueIdByName("Payload");
        CountingProvider provider = new CountingProvider(delegate);
        Blue blue = new Blue(provider);
        Node type = new Node().name("Untyped Holder")
                .properties("payload", new Node().schema(new Schema().required(true)));
        delegate.addSingleNodes(type);
        String typeId = delegate.getBlueIdByName("Untyped Holder");

        // when
        Node resolved = blue.resolve(new Node().type(reference(typeId))
                .properties("payload", reference(payloadId)));
        int fetchCount = provider.fetches(payloadId);

        // then
        assertTrue(resolved != null);
        assertEquals(0, fetchCount);
    }

    @Test
    void shouldFetchTypedReferenceOnceAndAvoidProviderWithWarmCache() {
        // given
        Fixture fixture = new Fixture();
        Node referenced = new Node().name("Referenced Subject")
                .type(reference(fixture.concreteSubjectId))
                .properties("identifier", new Node().value("subject-1"));
        fixture.delegate.addSingleNodes(referenced);
        String referenceId = fixture.delegate.getBlueIdByName("Referenced Subject");
        Node instance = fixture.holderInstance(reference(referenceId));

        // when
        fixture.blue.resolve(instance);
        int coldFetchCount = fixture.provider.fetches(referenceId);
        fixture.blue.resolve(instance);
        int warmFetchCount = fixture.provider.fetches(referenceId);

        // then
        assertEquals(1, coldFetchCount);
        assertEquals(1, warmFetchCount);
    }

    @Test
    void shouldUseNormalInheritanceRulesForTypedReferenceWithoutRepeatedType() {
        // given
        Fixture fixture = new Fixture();
        Node untypedContent = new Node().name("Untyped Subject Content")
                .properties("identifier", new Node().value("subject-1"));
        fixture.delegate.addSingleNodes(untypedContent);
        String referenceId = fixture.delegate.getBlueIdByName("Untyped Subject Content");

        // when
        Node resolved = fixture.blue.resolve(fixture.holderInstance(reference(referenceId)));
        Node subject = resolved.getProperties().get("subject");

        // then
        assertEquals(fixture.baseSubjectId, subject.getType().getBlueId());
        assertEquals("subject-1", subject.getProperties().get("identifier").getValue());
    }

    @Test
    void shouldNotFetchContentIdentityForMaterializedTypedValue() {
        // given
        Fixture fixture = new Fixture();
        Node materialized = new Node().name("Inline Subject")
                .type(reference(fixture.concreteSubjectId))
                .properties("identifier", new Node().value("subject-1"));
        String materializedId = blueIdOf(materialized);

        // when
        fixture.blue.resolve(fixture.holderInstance(materialized));
        int fetchCount = fixture.provider.fetches(materializedId);

        // then
        assertEquals(0, fetchCount);
    }

    @Test
    void shouldFetchRepeatedTypedReferenceBlueIdOncePerResolution() {
        // given
        Fixture fixture = new Fixture(true);
        Node referenced = new Node().name("Shared Subject")
                .type(reference(fixture.concreteSubjectId))
                .properties("identifier", new Node().value("subject-1"));
        fixture.delegate.addSingleNodes(referenced);
        String referenceId = fixture.delegate.getBlueIdByName("Shared Subject");

        Node instance = new Node().type(reference(fixture.holderId))
                .properties("subject", reference(referenceId))
                .properties("secondSubject", reference(referenceId));

        // when
        fixture.blue.resolve(instance);
        int fetchCount = fixture.provider.fetches(referenceId);

        // then
        assertEquals(1, fetchCount);
    }

    @Test
    void shouldFailIncompatibleTypedReferenceWithAffectedPath() {
        // given
        Fixture fixture = new Fixture();
        Node otherType = new Node().name("Other Type");
        fixture.delegate.addSingleNodes(otherType);
        String otherTypeId = fixture.delegate.getBlueIdByName("Other Type");
        Node incompatible = new Node().name("Incompatible Subject")
                .type(reference(otherTypeId))
                .value("wrong");
        fixture.delegate.addSingleNodes(incompatible);
        String incompatibleId = fixture.delegate.getBlueIdByName("Incompatible Subject");

        // when
        RuntimeException failure = captureFailure(
                () -> fixture.blue.resolve(fixture.holderInstance(reference(incompatibleId))));

        // then
        assertTrue(failure instanceof RuntimeException);
        assertTrue(messageChain(failure).contains("subject"), messageChain(failure));
    }

    @Test
    void shouldMaterializePayloadConstrainedReferenceBeforeValidation() {
        // given
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

        // when
        blue.resolve(new Node().type(reference(holderId))
                .properties("payload", reference(payloadId)));
        int fetchCount = provider.fetches(payloadId);

        // then
        assertEquals(1, fetchCount);
    }

    @Test
    void shouldFailMissingRequiredReferenceContentDeterministically() {
        // given
        Fixture fixture = new Fixture();
        String unavailable = blueIdOf(new Node().name("Unavailable Subject"));

        // when
        IllegalArgumentException failure = captureFailure(
                () -> fixture.blue.resolve(fixture.holderInstance(reference(unavailable))));
        int fetchCount = fixture.provider.fetches(unavailable);

        // then
        assertTrue(failure instanceof IllegalArgumentException);
        assertTrue(failure.getMessage().contains(unavailable));
        assertEquals(1, fetchCount);
    }

    @Test
    void shouldPreventContextualSnapshotEntryFromSatisfyingLaterTypedReference() {
        // given
        Fixture fixture = new Fixture();
        String unavailableId = blueIdOf(new Node().name("Unavailable Subject")
                .properties("identifier", new Node().value("not available")));
        Node untypedHolder = new Node().name("Untyped Required Holder")
                .properties("subject", new Node().schema(new Schema().required(true)));
        fixture.delegate.addSingleNodes(untypedHolder);
        String untypedHolderId = fixture.delegate.getBlueIdByName("Untyped Required Holder");

        // when
        ResolvedSnapshot contextualSnapshot = fixture.blue.resolveToSnapshot(new Node()
                .type(reference(untypedHolderId))
                .properties("subject", reference(unavailableId)));
        IllegalArgumentException failure = captureFailure(() -> fixture.blue.resolve(
                fixture.holderInstance(reference(unavailableId))));
        int fetchCount = fixture.provider.fetches(unavailableId);

        // then
        assertTrue(contextualSnapshot != null);
        assertTrue(failure instanceof IllegalArgumentException);
        assertEquals(1, fetchCount);
    }

    @Test
    void shouldPreventContextualResolvedGraphFromSatisfyingPayloadConstrainedReference() {
        // given
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

        // when
        IllegalArgumentException failure = captureFailure(
                () -> merger.merge(target, reference(unavailableId),
                        blue.language.resolve.ResolutionLimits.NO_LIMITS));
        boolean verifiedCanonicalPresent = cache.getVerifiedCanonical(unavailableId).isPresent();
        int fetchCount = provider.fetches(unavailableId);

        // then
        assertTrue(failure instanceof IllegalArgumentException);
        assertFalse(verifiedCanonicalPresent);
        assertNotSame(contextual, referenceView);
        assertTrue(referenceView.isReferenceOnly());
        assertEquals(1, fetchCount);
    }

    @Test
    void shouldUseExactVerifiedCacheEntryForTypedReference() {
        // given
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

        // when
        Node resolved = merger.resolve(fixture.holderInstance(reference(referenceId)));
        int coldFetchCount = coldCounter.fetches(referenceId);

        // then
        assertEquals(0, coldFetchCount);
        assertEquals("subject-1", resolved.getProperties().get("subject")
                .getProperties().get("identifier").getValue());
    }

    @Test
    void shouldFailProviderContentWithWrongBlueIdBeforeValidation() {
        // given
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

        // when
        RuntimeException failure = captureFailure(
                () -> blue.resolve(fixture.holderInstance(reference(expectedId))));

        // then
        assertTrue(failure instanceof RuntimeException);
        assertTrue(messageChain(failure).contains(expectedId));
        assertTrue(messageChain(failure).contains("Provider"), messageChain(failure));
    }

    @Test
    void shouldClassifyMissingTypedReferenceContentAsProviderUnavailable() {
        // given
        Fixture fixture = new Fixture();
        String missingId = fixture.blue.calculateBlueId(new Node().name("Missing Required Subject"));

        // when
        RuntimeException failure = captureFailure(
                () -> fixture.blue.resolve(fixture.holderInstance(reference(missingId))));
        int fetchCount = fixture.provider.fetches(missingId);

        // then
        assertTrue(failure instanceof RuntimeException);
        assertEquals(BlueLanguageErrorCategory.ProviderUnavailable,
                BlueLanguageErrorClassifier.classify(failure), messageChain(failure));
        assertEquals(1, fetchCount);
    }

    @Test
    void shouldUseExistingListSemanticsForMultiDocumentProviderResult() {
        // given
        List<Node> documents = Arrays.asList(new Node().value("one"), new Node().value("two"));
        BasicNodeProvider provider = new BasicNodeProvider();
        provider.processNodeList(documents);
        Blue blue = new Blue(provider);
        List<Node> canonicalDocuments = Arrays.asList(
                blue.preprocess(documents.get(0).clone()),
                blue.preprocess(documents.get(1).clone()));
        String referenceId = DirectBlueIdCalculator.calculateBlueId(canonicalDocuments);
        Node holder = new Node().name("Multi-document Holder")
                .properties("payload", new Node().schema(new Schema().minItems(2)));
        provider.addSingleNodes(holder);
        String holderId = provider.getBlueIdByName("Multi-document Holder");

        // when
        Node resolved = blue.resolve(new Node().type(reference(holderId))
                .properties("payload", reference(referenceId)));

        // then
        assertEquals(2, resolved.getProperties().get("payload").getItems().size());
    }

    @Test
    void shouldFailCyclicRequiredMaterializationWithoutStackOverflow() {
        // given
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

        // when
        RuntimeException failure = captureFailure(
                () -> blue.resolve(new Node().type(reference(holderId))
                        .properties("payload", reference(recursiveContentId))));

        // then
        assertTrue(failure instanceof RuntimeException);
        assertTrue(messageChain(failure).contains("Cyclic"), messageChain(failure));
    }

    @Test
    void shouldEscapeRfc6901PathSegmentsInSchemaFailure() {
        // given
        String key = "subject/with~markers";
        Node type = new Node().name("Escaped Holder")
                .properties(key, new Node().schema(required()));
        BasicNodeProvider provider = new BasicNodeProvider(type);
        String typeId = provider.getBlueIdByName("Escaped Holder");

        // when
        IllegalArgumentException failure = captureFailure(
                () -> new Blue(provider).resolve(new Node().type(reference(typeId))));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
        assertTrue(failure.getMessage().contains("/subject~1with~0markers"), failure.getMessage());
    }

    @Test
    void shouldUseCompletedPayloadForMinItemsAndMinFields() {
        // given
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
        Node invalidList = valid.clone().properties("list", new Node().items(new Node().value("a")));
        Node invalidObject = valid.clone().properties("object", new Node()
                .properties("a", new Node().value("a")));

        // when
        blue.resolve(valid);
        IllegalArgumentException listFailure =
                captureFailure(() -> blue.resolve(invalidList));
        IllegalArgumentException objectFailure =
                captureFailure(() -> blue.resolve(invalidObject));

        // then
        assertTrue(listFailure instanceof IllegalArgumentException);
        assertTrue(listFailure.getMessage().contains("/list"));
        assertTrue(listFailure.getMessage().contains("minimum required items"),
                listFailure.getMessage());
        assertTrue(objectFailure instanceof IllegalArgumentException);
        assertTrue(objectFailure.getMessage().contains("/object"));
        assertTrue(objectFailure.getMessage().contains("minimum required fields"));
    }

    @Test
    void shouldSatisfyRequiredWithInheritedFixedPayloads() {
        // given
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

        // when
        Node resolved = new Blue(provider).resolve(new Node().type(reference(typeId)));

        // then
        assertTrue(resolved != null);
    }

    @Test
    void shouldMakeInheritedObjectSemanticallyPresentWithFixedOrdinaryChild() {
        // given
        Node type = new Node().name("Declaration Holder")
                .properties("field", new Node().schema(required())
                        .properties("nested", new Node()
                                .description("fixed label")
                                .value("fixed")));
        BasicNodeProvider provider = new BasicNodeProvider(type);
        String typeId = provider.getBlueIdByName("Declaration Holder");

        // when
        Node resolved = new Blue(provider).resolve(new Node().type(reference(typeId)));

        // then
        assertEquals("fixed label", resolved.getProperties().get("field")
                .getProperties().get("nested").getDescription());
        assertEquals("fixed", resolved.getProperties().get("field")
                .getProperties().get("nested").getValue());
    }

    @Test
    void shouldNotSatisfyRequiredObjectPresenceWithContracts() {
        // given
        Node type = new Node().name("Contract Metadata Holder")
                .properties("field", new Node().schema(required())
                        .contracts(new Node().properties("processor", new Node().value("configured"))));
        BasicNodeProvider provider = new BasicNodeProvider(type);
        String typeId = provider.getBlueIdByName("Contract Metadata Holder");

        // when
        IllegalArgumentException inheritedFailure = captureFailure(
                () -> new Blue(provider).resolve(new Node().type(reference(typeId))));
        IllegalArgumentException instanceFailure = captureFailure(
                () -> new Blue(provider).resolve(new Node().type(reference(typeId))
                        .properties("field", new Node().contracts(new Node()
                                .properties("processor", new Node().value("configured"))))));

        // then
        assertTrue(inheritedFailure instanceof IllegalArgumentException);
        assertTrue(instanceFailure instanceof IllegalArgumentException);
        assertTrue(inheritedFailure.getMessage().contains("/field"), inheritedFailure.getMessage());
        assertTrue(instanceFailure.getMessage().contains("/field"), instanceFailure.getMessage());
    }

    @Test
    void shouldDeferNestedRequiredFieldForOmittedOptionalTypedBranchColdAndWarm() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        Node branch = new Node().name("Optional Branch")
                .properties("actor", new Node().type("Text").schema(required()));
        provider.addSingleNodes(branch);
        String branchId = provider.getBlueIdByName("Optional Branch");
        Node holder = new Node().name("Optional Branch Holder")
                .properties("branch", new Node().type(reference(branchId)));
        provider.addSingleNodes(holder);
        String holderId = provider.getBlueIdByName("Optional Branch Holder");

        Blue cold = new Blue(provider);
        Blue warm = new Blue(provider);

        // when
        Node coldResolved = cold.resolve(new Node().type(reference(holderId)));
        Node populatedWarm = warm.resolve(new Node().type(reference(holderId))
                .properties("branch", new Node().properties("actor", new Node().value("Ada"))));
        Node omittedWarm = warm.resolve(new Node().type(reference(holderId)));

        // then
        assertTrue(coldResolved != null);
        assertTrue(populatedWarm != null);
        assertTrue(omittedWarm != null);
    }

    @Test
    void shouldPreserveOptionalBranchAbsenceInNestedSchemaFreeTypeCache() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        Node leaf = new Node().name("Declaration Leaf")
                .properties("leafText", new Node().type("Text"));
        provider.addSingleNodes(leaf);
        String leafId = provider.getBlueIdByName("Declaration Leaf");
        Node helper = new Node().name("Declaration Helper")
                .properties("nested", new Node().type(reference(leafId)));
        provider.addSingleNodes(helper);
        String helperId = provider.getBlueIdByName("Declaration Helper");
        Node branch = new Node().name("Nested Optional Branch")
                .properties("helper", new Node().type(reference(helperId)))
                .properties("actor", new Node().type("Text").schema(required()));
        provider.addSingleNodes(branch);
        String branchId = provider.getBlueIdByName("Nested Optional Branch");
        Node holder = new Node().name("Nested Optional Branch Holder")
                .properties("branch", new Node().type(reference(branchId)));
        provider.addSingleNodes(holder);
        String holderId = provider.getBlueIdByName("Nested Optional Branch Holder");
        Blue blue = new Blue(provider);

        // when
        Node populated = blue.resolve(new Node().type(reference(holderId))
                .properties("branch", new Node().properties("actor", new Node().value("Ada"))));
        Node omitted = blue.resolve(new Node().type(reference(holderId)));

        // then
        assertTrue(populated != null);
        assertTrue(omitted != null);
    }

    @Test
    void shouldNotReuseExpandedDeclarationsAsPayloadForInstanceSchemaOverlay() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        Node leaf = new Node().name("Overlay Declaration Leaf")
                .properties("leafText", new Node().type("Text"));
        provider.addSingleNodes(leaf);
        String leafId = provider.getBlueIdByName("Overlay Declaration Leaf");
        Node branch = new Node().name("Overlay Declaration Branch")
                .properties("nested", new Node().type(reference(leafId)));
        provider.addSingleNodes(branch);
        String branchId = provider.getBlueIdByName("Overlay Declaration Branch");
        Node holder = new Node().name("Overlay Declaration Holder")
                .properties("branch", new Node().type(reference(branchId)));
        provider.addSingleNodes(holder);
        String holderId = provider.getBlueIdByName("Overlay Declaration Holder");
        Blue blue = new Blue(provider);

        // when
        Node omitted = blue.resolve(new Node().type(reference(holderId)));
        IllegalArgumentException failure = captureFailure(
                () -> blue.resolve(new Node().type(reference(holderId))
                        .properties("branch", new Node().schema(required()))));

        // then
        assertTrue(omitted != null);
        assertTrue(failure instanceof IllegalArgumentException);
        assertTrue(failure.getMessage().contains("/branch"), failure.getMessage());
    }

    @Test
    void shouldPreventExpandedSiblingFromActivatingOptionalParentAfterSchemaDiscoveryCache() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        Node requiredDeclaration = new Node().name("Cached Required Declaration")
                .schema(required());
        provider.addSingleNodes(requiredDeclaration);
        String requiredDeclarationId = provider.getBlueIdByName("Cached Required Declaration");
        Node leaf = new Node().name("Cached Sibling Leaf")
                .properties("leafText", new Node().type("Text"));
        provider.addSingleNodes(leaf);
        String leafId = provider.getBlueIdByName("Cached Sibling Leaf");
        Node expandedSibling = new Node().name("Cached Expanded Sibling")
                .properties("nested", new Node().type(reference(leafId)));
        provider.addSingleNodes(expandedSibling);
        String expandedSiblingId = provider.getBlueIdByName("Cached Expanded Sibling");
        Node branch = new Node().name("Cached Optional Parent")
                .properties("requiredValue", new Node().type(reference(requiredDeclarationId)))
                .properties("helper", new Node().type(reference(expandedSiblingId)));
        provider.addSingleNodes(branch);
        String branchId = provider.getBlueIdByName("Cached Optional Parent");
        Node holder = new Node().name("Cached Optional Parent Holder")
                .properties("branch", new Node().type(reference(branchId)));
        provider.addSingleNodes(holder);
        String holderId = provider.getBlueIdByName("Cached Optional Parent Holder");
        Blue blue = new Blue(provider);

        // when
        Node requiredResolved =
                blue.resolve(new Node().type(reference(requiredDeclarationId)));
        Node siblingResolved =
                blue.resolve(new Node().type(reference(expandedSiblingId)));
        Node holderResolved = blue.resolve(new Node().type(reference(holderId)));

        // then
        assertTrue(requiredResolved != null);
        assertTrue(siblingResolved != null);
        assertTrue(holderResolved != null);
    }

    @Test
    void shouldActivateNestedRequiredFieldWithSuppliedOrdinaryChild() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        Node branch = new Node().name("Activated Branch")
                .properties("actor", new Node().type("Text").schema(required()));
        provider.addSingleNodes(branch);
        String branchId = provider.getBlueIdByName("Activated Branch");
        Node holder = new Node().name("Activated Branch Holder")
                .properties("branch", new Node().type(reference(branchId)));
        provider.addSingleNodes(holder);
        String holderId = provider.getBlueIdByName("Activated Branch Holder");

        // when
        IllegalArgumentException failure = captureFailure(
                () -> new Blue(provider).resolve(new Node().type(reference(holderId))
                        .properties("branch", new Node()
                                .properties("note", new Node().value("supplied")))));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
        assertTrue(failure.getMessage().contains("/branch/actor"), failure.getMessage());
    }

    @Test
    void shouldActivateOptionalTypedBranchWithInheritedFixedField() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        Node branch = new Node().name("Fixed Branch")
                .properties("marker", new Node().value("fixed"))
                .properties("actor", new Node().type("Text").schema(required()));
        provider.addSingleNodes(branch);
        String branchId = provider.getBlueIdByName("Fixed Branch");
        Node holder = new Node().name("Fixed Branch Holder")
                .properties("branch", new Node().type(reference(branchId)));
        provider.addSingleNodes(holder);
        String holderId = provider.getBlueIdByName("Fixed Branch Holder");

        // when
        IllegalArgumentException failure = captureFailure(
                () -> new Blue(provider).resolve(new Node().type(reference(holderId))));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
        assertTrue(failure.getMessage().contains("/branch/actor"), failure.getMessage());
    }

    @Test
    void shouldActivateOptionalTypedBranchWithDirectlyInheritedObjectSubtree() {
        // given
        BasicNodeProvider provider = new BasicNodeProvider();
        Node branch = new Node().name("Declared Branch")
                .properties("actor", new Node().type("Text").schema(required()));
        provider.addSingleNodes(branch);
        String branchId = provider.getBlueIdByName("Declared Branch");
        Node holder = new Node().name("Declared Branch Holder")
                .properties("branch", new Node().type(reference(branchId))
                        .properties("marker", new Node().value("fixed subtree")));
        provider.addSingleNodes(holder);
        String holderId = provider.getBlueIdByName("Declared Branch Holder");

        // when
        IllegalArgumentException failure = captureFailure(
                () -> new Blue(provider).resolve(new Node().type(reference(holderId))));

        // then
        assertTrue(failure instanceof IllegalArgumentException);
        assertTrue(failure.getMessage().contains("/branch/actor"), failure.getMessage());
    }

    @Test
    void shouldGiveReferenceAndEquivalentMaterializedValueSameCanonicalIdentity() {
        // given
        Fixture fixture = new Fixture();
        Node materialized = new Node().type(reference(fixture.concreteSubjectId))
                .properties("identifier", new Node().value("subject-1"));
        fixture.delegate.addSingleNodes(materialized);
        String referenceId = fixture.blue.calculateBlueId(fixture.blue.preprocess(materialized.clone()));
        Node referencedInstance = fixture.holderInstance(reference(referenceId));
        Node materializedInstance = fixture.holderInstance(materialized.clone());

        // when
        String materializedBlueId =
                fixture.blue.calculateSourceDocumentBlueId(materializedInstance);
        String referencedBlueId =
                fixture.blue.calculateSourceDocumentBlueId(referencedInstance);
        Node canonical = fixture.blue.canonicalize(referencedInstance);
        Node canonicalSubject = canonical.getProperties().get("subject");

        // then
        assertEquals(materializedBlueId, referencedBlueId);
        assertTrue(canonicalSubject.isReferenceOnly(), canonicalSubject.toString());
        assertEquals(referenceId, canonicalSubject.getBlueId());
    }

    @Test
    void shouldKeepResolveAndSnapshotAlignedForValidationMaterialization() {
        // given
        Fixture fixture = new Fixture();
        Node referenced = new Node().name("Snapshot Subject")
                .type(reference(fixture.concreteSubjectId))
                .properties("identifier", new Node().value("subject-1"));
        fixture.delegate.addSingleNodes(referenced);
        String referenceId = fixture.delegate.getBlueIdByName("Snapshot Subject");
        Node instance = fixture.holderInstance(reference(referenceId));

        Node resolved = fixture.blue.resolve(instance);
        // when
        ResolvedSnapshot snapshot = fixture.blue.resolveToSnapshot(instance);

        // then
        assertEquals(resolved.getProperties().get("subject").getProperties().get("identifier").getValue(),
                snapshot.resolvedAt("/subject/identifier").getValue());
        assertTrue(snapshot.canonicalAt("/subject").isReferenceOnly(),
                snapshot.canonicalAt("/subject").toString());
        assertEquals(referenceId, snapshot.canonicalAt("/subject").getReferenceBlueId());
    }

    @Test
    void shouldApplyCompletedSchemaValidationWhenLoadingSnapshot() {
        // given
        Fixture fixture = new Fixture();

        // when
        IllegalArgumentException failure = captureFailure(
                () -> fixture.blue.loadSnapshot(fixture.holderInstance(null)));

        // then
        assertTrue(failure instanceof IllegalArgumentException,
                String.valueOf(failure));
        assertTrue(failure.getMessage().contains("/subject"), failure.getMessage());
    }

    @Test
    void shouldKeepCanonicalizationStableAcrossColdAndWarmReferenceCache() {
        // given
        Fixture fixture = new Fixture();
        Node referenced = new Node().type(reference(fixture.concreteSubjectId))
                .properties("identifier", new Node().value("subject-1"));
        fixture.delegate.addSingleNodes(referenced);
        String referenceId = fixture.blue.calculateBlueId(fixture.blue.preprocess(referenced.clone()));
        Node instance = fixture.holderInstance(reference(referenceId));

        Node cold = fixture.blue.canonicalize(instance);
        fixture.blue.resolve(instance);
        // when
        Node warm = fixture.blue.canonicalize(instance);

        // then
        assertEquals(DirectBlueIdCalculator.calculateBlueId(cold), DirectBlueIdCalculator.calculateBlueId(warm));
        assertTrue(cold.getProperties().get("subject").isReferenceOnly());
        assertTrue(warm.getProperties().get("subject").isReferenceOnly());
    }

    @Test
    void shouldPreserveNestedListReferenceIdentityAcrossPublicAndProcessingSnapshots() {
        // given
        Fixture fixture = new Fixture();
        Node referenced = new Node().name("Nested Snapshot Subject")
                .type(reference(fixture.concreteSubjectId))
                .properties("identifier", new Node().value("subject-1"));
        fixture.delegate.addSingleNodes(referenced);
        String referenceId = fixture.delegate.getBlueIdByName("Nested Snapshot Subject");
        Node source = new Node().properties("entries", new Node().items(
                fixture.holderInstance(reference(referenceId)),
                fixture.holderInstance(reference(referenceId))));
        ResolvedSnapshot publicCold = fixture.blue.resolveToSnapshot(source);
        fixture.blue.clearResolvedSnapshotCache();
        ResolvedSnapshot processingCold = snapshot(
                fixture.blue,
                fixture.blue.initializeDocument(source));
        ResolvedSnapshot publicWarm = fixture.blue.resolveToSnapshot(source);
        // when
        ResolvedSnapshot processingWarm = snapshot(
                fixture.blue,
                fixture.blue.initializeDocument(source));

        // then
        assertEquals(publicCold.blueId(), publicWarm.blueId());
        assertEquals(processingCold.blueId(), processingWarm.blueId());
        assertEquals(referenceId,
                publicCold.canonicalAt("/entries/0/subject").getReferenceBlueId());
        assertEquals(referenceId,
                processingCold.canonicalAt("/entries/1/subject").getReferenceBlueId());
        assertEquals(publicCold.canonicalAt("/entries/0/subject").resolvedStructuralKey(),
                processingCold.canonicalAt("/entries/0/subject").resolvedStructuralKey());
        assertEquals(publicCold.resolvedAt("/entries/1/subject").resolvedStructuralKey(),
                processingWarm.resolvedAt("/entries/1/subject").resolvedStructuralKey());
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
        List<Node> fetched = provider.fetchByBlueId(blue.language.identity.DirectBlueIdCalculator.calculateBlueId(node));
        if (fetched != null) {
            return blue.language.identity.DirectBlueIdCalculator.calculateBlueId(node);
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
