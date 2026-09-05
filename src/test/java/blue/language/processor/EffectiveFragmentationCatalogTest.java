package blue.language.processor;

import blue.language.Blue;
import blue.language.provider.NodeProvider;
import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.FrozenNode;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectiveFragmentationCatalogTest {

    @Test
    void shouldReportInheritedExecutableBodyMetadataWithoutDemandingBody() {
        // given
        Fixture fixture = new Fixture();

        // when
        CatalogObservation observation =
                observeCatalog(fixture);

        // then
        assertEquals("handler", observation.handler.role());
        assertEquals(
                fixture.handlerTypeBlueId,
                observation.handler.effectiveTypeBlueId());
        assertEquals(
                Arrays.asList(
                        fixture.inheritedContributionBlueId,
                        fixture.directContributionBlueId),
                observation.handler
                        .sourceContributionNodeBlueIds());
        assertEquals(
                Collections.singletonList("program"),
                observation.handler.executableBodyFields());
        assertEquals(
                Collections.singletonMap(
                        "program",
                        fixture.programBlueId),
                observation.handler
                        .executableBodyNodeBlueIdsByField());
        assertEquals(
                Collections.singletonList(
                        fixture.programBlueId),
                observation.handler.executableBodyNodeBlueIds());
        assertFalse(
                fixture.providerRequests
                        .contains(fixture.programBlueId),
                "catalog inspection demanded the executable body");
        assertEquals(
                observation.expectedRootBlueId,
                observation.catalog.rootBlueId());
    }

    @Test
    void shouldReportExactInheritedExecutableBodySourceDescriptor() {
        // given
        Fixture fixture = new Fixture();

        // when
        CatalogObservation observation =
                observeCatalog(fixture);
        ExecutableBodySourceDescriptor bodySource =
                observation.bodySource;

        // then
        assertEquals("/", bodySource.scopePath());
        assertEquals("run", bodySource.contractKey());
        assertEquals(
                fixture.handlerTypeBlueId,
                bodySource.effectiveTypeBlueId());
        assertEquals("program", bodySource.bodyField());
        assertEquals(
                fixture.programBlueId,
                bodySource.bodyNodeBlueId());
        assertEquals(
                Arrays.asList(
                        fixture.inheritedContributionBlueId,
                        fixture.directContributionBlueId),
                bodySource.sourceContributionNodeBlueIds());
        assertEquals(
                fixture.inheritedContributionBlueId,
                bodySource.owningSourceContributionNodeBlueId());
        assertEquals("/program", bodySource.sourcePointer());
        assertTrue(bodySource.pureReference());
    }

    @Test
    void shouldReportEffectiveHeaderWithoutExecutableBody() {
        // given
        Fixture fixture = new Fixture();

        // when
        EffectiveContractSnapshot handler =
                observeCatalog(fixture).handler;

        // then
        assertEquals(
                "lifecycle",
                handler.headerFields()
                        .get("channel")
                        .getValue());
        assertEquals(
                "instance-overlay",
                handler.headerFields()
                        .get("label")
                        .getValue());
        assertFalse(
                handler.headerFields()
                        .containsKey("program"));
    }

    @Test
    void shouldAssignExactDescriptorOwnershipToDescendantInlineBody() {
        // given
        Fixture fixture = new Fixture();
        Node inlineProgram =
                new Node().properties(
                        "operation",
                        new Node().value(
                                "descendant"));
        Node document = fixture.document();
        Node direct =
                document.getContracts()
                        .getProperties()
                        .get("run");
        direct.properties(
                "program",
                inlineProgram.clone());
        String directBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        direct);
        String bodyBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        inlineProgram);

        // when
        try (Blue blue = fixture.blue()) {
            EffectiveContractSnapshot handler =
                    contract(
                            blue.getDocumentProcessor()
                                    .administration().effectiveFragmentationCatalog(
                                            document),
                            "/",
                            "run");
            ExecutableBodySourceDescriptor source =
                    handler
                            .executableBodySourceDescriptorsByField()
                            .get("program");

            // then
            assertEquals(
                    Arrays.asList(
                            fixture.inheritedContributionBlueId,
                            directBlueId),
                    source
                            .sourceContributionNodeBlueIds());
            assertEquals(
                    directBlueId,
                    source
                            .owningSourceContributionNodeBlueId());
            assertEquals(
                    bodyBlueId,
                    source.bodyNodeBlueId());
            assertEquals(
                    "/program",
                    source.sourcePointer());
            assertFalse(source.pureReference());
            assertFalse(
                    fixture.providerRequests
                            .contains(fixture.programBlueId),
                    "overridden inherited body was demanded");
        }
    }

    @Test
    void shouldRetainCanonicalIdentityForInlineListExecutableBody() {
        // given
        Fixture fixture = new Fixture();
        Node inlineProgram =
                new Node().items(
                        new Node()
                                .name("Increment")
                                .properties(
                                        "operation",
                                        new Node().value(
                                                "descendant")));
        String canonicalBodyBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        inlineProgram);
        String resolvedBodyBlueId =
                FrozenNode.fromResolvedNode(
                                inlineProgram)
                        .blueId();
        Node document = fixture.document();
        Node direct =
                document.getContracts()
                        .getProperties()
                        .get("run");
        direct.properties(
                "program",
                inlineProgram.clone());
        String directBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        direct);

        // when
        EffectiveContractSnapshot handler;
        try (Blue blue = fixture.blue()) {
            handler =
                    contract(
                            blue.getDocumentProcessor()
                                    .administration().effectiveFragmentationCatalog(
                                            document),
                            "/",
                            "run");
        }
        ExecutableBodySourceDescriptor source =
                handler
                        .executableBodySourceDescriptorsByField()
                        .get("program");

        // then
        assertNotEquals(
                canonicalBodyBlueId,
                resolvedBodyBlueId,
                "the fixture must distinguish exact Source identity from resolved-view identity");
        assertEquals(
                canonicalBodyBlueId,
                handler
                        .executableBodyNodeBlueIdsByField()
                        .get("program"));
        assertEquals(
                canonicalBodyBlueId,
                source.bodyNodeBlueId());
        assertEquals(
                directBlueId,
                source
                        .owningSourceContributionNodeBlueId());
        assertEquals("/program", source.sourcePointer());
        assertFalse(source.pureReference());
    }

    @Test
    void shouldAssignExactColdDescriptorOwnershipToDirectPureReferenceBody() {
        // given
        Fixture fixture = new Fixture();
        Node document = fixture.document();
        Node direct =
                document.getContracts()
                        .getProperties()
                        .get("run");
        direct.properties(
                "program",
                new Node().blueId(
                        fixture.programBlueId));
        String directBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        direct);

        // when
        try (Blue blue = fixture.blue()) {
            ExecutableBodySourceDescriptor source =
                    contract(
                            blue.getDocumentProcessor()
                                    .administration().effectiveFragmentationCatalog(
                                            document),
                            "/",
                            "run")
                            .executableBodySourceDescriptorsByField()
                            .get("program");

            // then
            assertEquals(
                    directBlueId,
                    source
                            .owningSourceContributionNodeBlueId());
            assertEquals(
                    fixture.programBlueId,
                    source.bodyNodeBlueId());
            assertEquals(
                    "/program",
                    source.sourcePointer());
            assertTrue(source.pureReference());
            assertFalse(
                    fixture.providerRequests
                            .contains(
                                    fixture.programBlueId),
                    "catalog inspection demanded a direct referenced body");
        }
    }

    @Test
    void shouldInvalidateCatalogEvidenceWhenOwningContributionChanges() {
        // given
        Fixture fixture = new Fixture();
        Node firstDocument = fixture.document();
        Node firstBody =
                new Node().properties(
                        "operation",
                        new Node().value("first"));
        firstDocument.getContracts()
                .getProperties()
                .get("run")
                .properties(
                        "program",
                        firstBody);

        Node secondDocument = firstDocument.clone();
        Node secondBody =
                new Node().properties(
                        "operation",
                        new Node().value("second"));
        secondDocument.getContracts()
                .getProperties()
                .get("run")
                .properties(
                        "program",
                        secondBody);

        // when
        try (Blue blue = fixture.blue()) {
            EffectiveFragmentationCatalog first =
                    blue.getDocumentProcessor()
                            .administration().effectiveFragmentationCatalog(
                                    firstDocument);
            EffectiveFragmentationCatalog second =
                    blue.getDocumentProcessor()
                            .administration().effectiveFragmentationCatalog(
                                    secondDocument);
            ExecutableBodySourceDescriptor firstSource =
                    contract(first, "/", "run")
                            .executableBodySourceDescriptorsByField()
                            .get("program");
            ExecutableBodySourceDescriptor secondSource =
                    contract(second, "/", "run")
                            .executableBodySourceDescriptorsByField()
                            .get("program");

            // then
            assertNotEquals(
                    firstSource
                            .owningSourceContributionNodeBlueId(),
                    secondSource
                            .owningSourceContributionNodeBlueId());
            assertNotEquals(
                    firstSource.bodyNodeBlueId(),
                    secondSource.bodyNodeBlueId());
            assertNotEquals(
                    signature(first),
                    signature(second));
        }
    }

    @Test
    void shouldKeepCyclicBodyReferenceAsOpaqueExactSourceEdge() {
        // given
        Fixture fixture = new Fixture();
        String cyclicMemberBlueId =
                fixture.programBlueId + "#0";
        Node document = fixture.document();
        document.getContracts()
                .getProperties()
                .get("run")
                .properties(
                        "program",
                        new Node().blueId(
                                cyclicMemberBlueId));

        // when
        try (Blue blue = fixture.blue()) {
            ExecutableBodySourceDescriptor source =
                    contract(
                            blue.getDocumentProcessor()
                                    .administration().effectiveFragmentationCatalog(
                                            document),
                            "/",
                            "run")
                            .executableBodySourceDescriptorsByField()
                            .get("program");

            // then
            assertEquals(
                    cyclicMemberBlueId,
                    source.bodyNodeBlueId());
            assertTrue(source.pureReference());
            assertFalse(
                    fixture.providerRequests
                            .contains(cyclicMemberBlueId),
                    "catalog inspection opened a cyclic body member");
        }
    }

    @Test
    void shouldProduceSameCatalogForInlineContractsFragmentAndPureRoot() {
        // given
        Fixture fixture = new Fixture();
        Node inline = fixture.document();
        Node exactContracts =
                inline.getContracts().clone();
        String contractsBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        exactContracts);
        Node fragmented =
                inline.clone()
                        .contracts(
                                new Node().blueId(
                                        contractsBlueId));
        fixture.content.put(
                contractsBlueId,
                exactContracts);
        String rootBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        fragmented);
        fixture.content.put(
                rootBlueId,
                fragmented);

        // when
        String inlineSurfaceSignature;
        String fragmentedSurfaceSignature;
        String referenceSurfaceSignature;
        String inlineRootBlueId;
        String fragmentedRootBlueId;
        String referenceRootBlueId;
        try (Blue blue = fixture.blue()) {
            EffectiveFragmentationCatalog inlineCatalog =
                    blue.getDocumentProcessor()
                            .administration().effectiveFragmentationCatalog(
                                    inline);
            EffectiveFragmentationCatalog fragmentedCatalog =
                    blue.getDocumentProcessor()
                            .administration().effectiveFragmentationCatalog(
                                    fragmented);
            EffectiveFragmentationCatalog referenceCatalog =
                    blue.getDocumentProcessor()
                            .administration().effectiveFragmentationCatalog(
                                    new Node().blueId(
                                            rootBlueId));
            inlineSurfaceSignature = surfaceSignature(inlineCatalog);
            fragmentedSurfaceSignature = surfaceSignature(fragmentedCatalog);
            referenceSurfaceSignature = surfaceSignature(referenceCatalog);
            inlineRootBlueId = inlineCatalog.rootBlueId();
            fragmentedRootBlueId =
                    fragmentedCatalog.rootBlueId();
            referenceRootBlueId =
                    referenceCatalog.rootBlueId();
        }

        /*
         * A fresh processor starts with the pure Root reference so the same
         * comparison also covers cold-reference then warm-inline order.
         */
        String coldReferenceSurfaceSignature;
        String warmInlineSurfaceSignature;
        try (Blue cold = fixture.blue()) {
            EffectiveFragmentationCatalog coldReference =
                    cold.getDocumentProcessor()
                            .administration().effectiveFragmentationCatalog(
                                    new Node().blueId(
                                            rootBlueId));
            EffectiveFragmentationCatalog warmInline =
                    cold.getDocumentProcessor()
                            .administration().effectiveFragmentationCatalog(
                                    inline);
            coldReferenceSurfaceSignature = surfaceSignature(coldReference);
            warmInlineSurfaceSignature = surfaceSignature(warmInline);
        }
        boolean programRequested =
                fixture.providerRequests
                        .contains(fixture.programBlueId);

        // then
        assertEquals(inlineSurfaceSignature, fragmentedSurfaceSignature);
        assertEquals(inlineSurfaceSignature, referenceSurfaceSignature);
        assertNotEquals(inlineRootBlueId, fragmentedRootBlueId);
        assertEquals(rootBlueId, fragmentedRootBlueId);
        assertEquals(rootBlueId, referenceRootBlueId);
        assertEquals(
                coldReferenceSurfaceSignature,
                warmInlineSurfaceSignature);
        assertFalse(programRequested);
    }

    @Test
    void shouldReportCanonicalRootIdentityForInlineTypeSource() {
        // given
        Fixture fixture = new Fixture();
        Node rootType = new Node()
                .name("Catalog Root Type")
                .type(new Node().blueId(
                        fixture.scopeTypeBlueId))
                .properties(
                        "inheritedRootValue",
                        new Node().value("same"));
        String rootTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(rootType);
        fixture.content.put(rootTypeBlueId, rootType);
        Node inlineRoot = fixture.document()
                .type(rootType.clone())
                .properties(
                        "inheritedRootValue",
                        new Node().value("same"));
        Node referenceRoot = fixture.document()
                .type(new Node().blueId(rootTypeBlueId))
                .properties(
                        "inheritedRootValue",
                        new Node().value("same"));

        // when
        String expectedRootBlueId;
        EffectiveFragmentationCatalog inlineCatalog;
        EffectiveFragmentationCatalog referenceCatalog;
        try (Blue blue = fixture.blue()) {
            expectedRootBlueId =
                    blue.calculateSourceDocumentBlueId(
                            referenceRoot.clone());
            inlineCatalog = blue.getDocumentProcessor()
                    .administration()
                    .effectiveFragmentationCatalog(inlineRoot);
            referenceCatalog = blue.getDocumentProcessor()
                    .administration()
                    .effectiveFragmentationCatalog(referenceRoot);
        }

        // then
        assertThrows(
                IllegalArgumentException.class,
                () -> DirectBlueIdCalculator.calculateBlueId(inlineRoot),
                "direct input must reject an uncanonicalized inline type");
        assertEquals(expectedRootBlueId, inlineCatalog.rootBlueId());
        assertEquals(expectedRootBlueId, referenceCatalog.rootBlueId());
    }

    @Test
    void shouldReportDirectProcessEmbeddedPath() {
        // given
        Node document =
                new Node()
                        .properties(
                                "child",
                                new Node().properties(
                                        "value",
                                        new Node().value(
                                                "present")))
                        .contracts(
                                new Node().properties(
                                        "embedded",
                                        new Node()
                                                .type(new Node().blueId(
                                                        RuntimeBlueIds
                                                                .PROCESS_EMBEDDED))
                                                .properties(
                                                        "paths",
                                                        new Node().items(
                                                                new Node().value(
                                                                        "/child")))));

        // when
        try (Blue blue = blue(
                new LinkedHashMap<String, Node>(),
                new ArrayList<String>())) {
            EffectiveFragmentationCatalog catalog =
                    blue.getDocumentProcessor()
                            .administration().effectiveFragmentationCatalog(
                                    document);

            // then
            assertEquals(
                    Collections.singletonList("/child"),
                    catalog
                            .effectiveProcessEmbeddedPathsByScope()
                            .get("/"));
            assertTrue(
                    catalog.effectiveContractsByScope()
                            .containsKey("/child"));
            assertEquals(
                    "process-embedded",
                    contract(
                            catalog,
                            "/",
                            "embedded").role());
        }
    }

    @Test
    void shouldExposeStructuredCollectionPlanWithExactMemberProvenance() {
        // given
        Node document =
                new Node()
                        .properties(
                                "lessons",
                                new Node()
                                        .properties(
                                                "b",
                                                new Node().properties(
                                                        "value",
                                                        new Node().value("b")))
                                        .properties(
                                                "a/b",
                                                new Node().properties(
                                                        "value",
                                                        new Node().value("slash")))
                                        .properties(
                                                "a~c",
                                                new Node().properties(
                                                        "value",
                                                        new Node().value("tilde"))))
                        .contracts(
                                new Node().properties(
                                        "embedded",
                                        new Node()
                                                .type(new Node().blueId(
                                                        RuntimeBlueIds
                                                                .PROCESS_EMBEDDED))
                                                .properties(
                                                        "collectionPaths",
                                                        new Node().items(
                                                                new Node().value(
                                                                        "/lessons")))));

        // when
        EffectiveFragmentationCatalog catalog;
        try (Blue blue = blue(
                new LinkedHashMap<String, Node>(),
                new ArrayList<String>())) {
            catalog = blue.getDocumentProcessor()
                    .administration().effectiveFragmentationCatalog(document);
        }
        EmbeddedScopePlanView rootPlan =
                catalog.scopePlansByScope().get("/");

        // then
        assertEquals(
                Collections.singletonList("/lessons"),
                rootPlan.collectionDeclarationPaths());
        assertEquals(
                Arrays.asList("a/b", "a~c", "b"),
                rootPlan.collectionMemberKeysByDeclaration()
                        .get("/lessons"));
        assertEquals(
                EmbeddedScopePlanView.CollectionState.PRESENT_COLLECTION,
                rootPlan.collectionStatesByDeclaration().get("/lessons"));
        assertEquals(
                Arrays.asList(
                        "/lessons/a~0c",
                        "/lessons/a~1b",
                        "/lessons/b"),
                rootPlan.concreteChildPaths());
        assertEquals(
                EmbeddedScopePlanView.Origin.COLLECTION_MEMBER,
                rootPlan.originsByConcretePath()
                        .get("/lessons/a~1b"));
        assertEquals(
                rootPlan.concreteChildPaths(),
                catalog.effectiveProcessEmbeddedPathsByScope().get("/"));
        assertTrue(catalog.scopePlansByScope()
                .get("/lessons/a~1b")
                .concreteChildPaths()
                .isEmpty());
    }

    @Test
    void shouldDistinguishAbsentAndPresentEmptyCollectionsInPublicPlan() {
        // given
        Node embedded = new Node()
                .type(new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties(
                        "collectionPaths",
                        new Node().items(new Node().value("/lessons")));
        Node contracts = new Node().properties("embedded", embedded);
        Node absent = Nodes.emptyObject().contracts(contracts.clone());
        Node presentEmpty = new Node()
                .properties("lessons", Nodes.emptyObject())
                .contracts(contracts.clone());

        EmbeddedScopePlanView absentPlan;
        EmbeddedScopePlanView presentPlan;

        // when
        try (Blue blue = blue(
                new LinkedHashMap<String, Node>(),
                new ArrayList<String>())) {
            absentPlan = blue.getDocumentProcessor().administration()
                    .effectiveFragmentationCatalog(absent)
                    .scopePlansByScope().get("/");
            presentPlan = blue.getDocumentProcessor().administration()
                    .effectiveFragmentationCatalog(presentEmpty)
                    .scopePlansByScope().get("/");
        }

        // then
        assertEquals(
                Collections.emptyList(),
                absentPlan.collectionMemberKeysByDeclaration()
                        .get("/lessons"));
        assertEquals(
                Collections.emptyList(),
                presentPlan.collectionMemberKeysByDeclaration()
                        .get("/lessons"));
        assertEquals(
                EmbeddedScopePlanView.CollectionState
                        .ABSENT_ZERO_OCCURRENCES,
                absentPlan.collectionStatesByDeclaration().get("/lessons"));
        assertEquals(
                EmbeddedScopePlanView.CollectionState.PRESENT_COLLECTION,
                presentPlan.collectionStatesByDeclaration().get("/lessons"));
    }

    @Test
    void shouldDefineChildCatalogScopeFromInheritedProcessEmbeddedPath() {
        // given
        Node inheritedEmbedded =
                new Node()
                        .type(new Node().blueId(
                                RuntimeBlueIds
                                        .PROCESS_EMBEDDED))
                        .properties(
                                "paths",
                                new Node().items(
                                        new Node().value(
                                                "/child")));
        Node rootType =
                new Node()
                        .name("Embedded catalog root")
                        .type(new Node().blueId(
                                BlueLanguageConstants
                                        .DICTIONARY_TYPE_BLUE_ID))
                        .contracts(
                                new Node().properties(
                                        "embedded",
                                        inheritedEmbedded));
        String rootTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        rootType);
        Node document =
                new Node()
                        .type(new Node().blueId(
                                rootTypeBlueId))
                        .properties(
                                "child",
                                new Node().properties(
                                        "value",
                                        new Node().value(
                                                "present")));
        Map<String, Node> content =
                new LinkedHashMap<>();
        content.put(rootTypeBlueId, rootType);

        // when
        try (Blue blue = blue(content, new ArrayList<String>())) {
            EffectiveFragmentationCatalog catalog =
                    blue.getDocumentProcessor()
                            .administration().effectiveFragmentationCatalog(
                                    document);

            // then
            assertEquals(
                    Collections.singletonList("/child"),
                    catalog
                            .effectiveProcessEmbeddedPathsByScope()
                            .get("/"));
            assertTrue(
                    catalog.effectiveContractsByScope()
                            .containsKey("/child"));
            EffectiveContractSnapshot embedded =
                    contract(catalog, "/", "embedded");
            assertEquals("process-embedded", embedded.role());
            assertEquals(
                    Collections.singletonList(
                            DirectBlueIdCalculator.calculateBlueId(
                                    inheritedEmbedded)),
                    embedded
                            .sourceContributionNodeBlueIds());
        }
    }

    @Test
    void shouldOpenDeclaredEmbeddedReferenceWhileUnrelatedReferenceStaysCold() {
        // given
        Node child = new Node().properties(
                "value",
                new Node().value("embedded"));
        String childBlueId =
                DirectBlueIdCalculator.calculateBlueId(child);
        Node unrelated = new Node().properties(
                "secret",
                new Node().value("cold"));
        String unrelatedBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        unrelated);
        Node document = new Node()
                .properties(
                        "child",
                        new Node().blueId(childBlueId))
                .properties(
                        "unrelated",
                        new Node().blueId(
                                unrelatedBlueId))
                .contracts(
                        new Node().properties(
                                "embedded",
                                new Node()
                                        .type(new Node().blueId(
                                                RuntimeBlueIds
                                                        .PROCESS_EMBEDDED))
                                        .properties(
                                                "paths",
                                                new Node().items(
                                                        new Node().value(
                                                                "/child")))));
        Map<String, Node> content =
                new LinkedHashMap<>();
        content.put(childBlueId, child);
        content.put(unrelatedBlueId, unrelated);
        List<String> requests = new ArrayList<>();

        // when
        try (Blue blue = blue(content, requests)) {
            EffectiveFragmentationCatalog catalog =
                    blue.getDocumentProcessor()
                            .administration().effectiveFragmentationCatalog(
                                    document);

            // then
            assertTrue(
                    catalog.effectiveContractsByScope()
                            .containsKey("/child"));
            assertTrue(requests.contains(childBlueId));
            assertFalse(
                    requests.contains(unrelatedBlueId),
                    "catalog inspection demanded unrelated data");
        }
    }

    @Test
    void shouldPreserveRegisteredRequiredRequestDeclarationAcrossSnapshots() {
        // given
        Fixture fixture = new Fixture();
        Node document = fixture.document();
        Node request = new Node().properties("amount", new Node()
                .type(new Node().blueId(BlueLanguageConstants.INTEGER_TYPE_BLUE_ID))
                .schema(new blue.language.model.Schema().required(true)));
        document.getContracts().getProperties().get("run")
                .properties("request", request);
        // when
        try (Blue blue = fixture.blue()) {
            DocumentProcessor processor = blue.getDocumentProcessor();
            Node canonical = processor.administration().canonicalizeProcessingSource(document);
            EffectiveContractSnapshot handler = contract(processor.administration()
                    .effectiveFragmentationCatalog(canonical), "/", "run");
            // then
            assertTrue(handler.headerFields().get("request").property("amount")
                    .getSchema().getRequiredValue());
            assertEquals(DirectBlueIdCalculator.calculateBlueId(canonical),
                    DirectBlueIdCalculator.calculateBlueId(processor.administration()
                            .canonicalizeProcessingSource(canonical)));
            Node resolved = processor.administration().resolveProcessingSource(document);
            assertTrue(resolved.getContracts().getProperties().get("run")
                    .getProperties().get("request").getProperties().get("amount")
                    .getSchema().getRequiredValue());
            Node invalidFixed = document.clone();
            invalidFixed.getContracts().getProperties().get("run")
                    .getProperties().get("request").getProperties().get("amount")
                    .value("wrong kind");
            assertThrows(IllegalArgumentException.class, () -> processor.administration()
                    .canonicalizeProcessingSource(invalidFixed));
            // Newly inserted child Source is not yet an opened processing scope.
            Node nested = new Node().properties("children", new Node()
                    .properties("first", canonical.clone()));
            for (boolean strict : new boolean[] {false, true}) {
                DocumentProcessingRuntime runtime = new DocumentProcessingRuntime(
                        new Node().properties("children", new Node().properties(Collections.emptyMap())),
                        null, null, processor.scopeIdentitySnapshotManager(), null,
                        new GasMeter(), processor.registry().exactSourceFieldsByType(), strict);
                assertTrue(runtime.snapshotFromDocument(nested).resolvedRoot()
                        .getNode("/children/first/contracts/run/request/amount")
                        .getSchema().getRequiredValue());
            }
            Node ordinaryRequest = new Node().properties("request", request.clone());
            assertThrows(IllegalArgumentException.class, () -> processor.administration()
                    .resolveProcessingSource(ordinaryRequest));
        }
    }

    @Test
    void shouldKeepReferencedHandlerEventMatcherAsExactColdHeaderEdge() {
        // given
        Fixture fixture = new Fixture();
        Node eventPattern =
                new Node().properties(
                        "kind",
                        new Node().value("catalog-event"));
        String eventPatternBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        eventPattern);
        fixture.content.put(
                eventPatternBlueId,
                eventPattern);
        Node document = fixture.document();
        document.getContracts()
                .getProperties()
                .get("run")
                .properties(
                        "event",
                        new Node().blueId(
                                eventPatternBlueId));

        // when
        try (Blue blue = fixture.blue()) {
            EffectiveContractSnapshot handler =
                    contract(
                            blue.getDocumentProcessor()
                                    .administration().effectiveFragmentationCatalog(
                                            document),
                            "/",
                            "run");

            // then
            assertTrue(
                    handler.headerFields()
                            .get("event")
                            .isReferenceOnly());
            assertEquals(
                    eventPatternBlueId,
                    handler.headerFields()
                            .get("event")
                            .getReferenceBlueId());
            assertFalse(
                    fixture.providerRequests
                            .contains(eventPatternBlueId));
        }
    }

    @Test
    void shouldBuildRootCatalogDespiteUnrelatedUnavailableReference() {
        // given
        Node unavailable =
                new Node().properties(
                        "data",
                        new Node().value("unavailable"));
        String unavailableBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        unavailable);
        List<String> requests = new ArrayList<>();

        // when
        try (Blue blue = blue(
                Collections.<String, Node>emptyMap(),
                requests)) {
            EffectiveFragmentationCatalog catalog =
                    blue.getDocumentProcessor()
                            .administration().effectiveFragmentationCatalog(
                                    new Node().properties(
                                            "unrelated",
                                            new Node().blueId(
                                                    unavailableBlueId)));

            // then
            assertTrue(
                    catalog.effectiveContractsByScope()
                            .containsKey("/"));
            assertFalse(requests.contains(
                    unavailableBlueId));
        }
    }

    @Test
    void shouldFailUnsupportedTypeBeforeDemandingUnrelatedBody() {
        // given
        Node body =
                new Node().properties(
                        "secret",
                        new Node().value("cold"));
        String bodyBlueId =
                DirectBlueIdCalculator.calculateBlueId(body);
        Node unknownType =
                new Node().name(
                        "Unsupported catalog contract");
        String unknownTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        unknownType);
        Node document =
                new Node().contracts(
                        new Node().properties(
                                "unsupported",
                                new Node()
                                        .type(new Node().blueId(
                                                unknownTypeBlueId))
                                        .properties(
                                                "program",
                                                new Node().blueId(
                                                        bodyBlueId))));
        Map<String, Node> content =
                new LinkedHashMap<>();
        content.put(unknownTypeBlueId, unknownType);
        content.put(bodyBlueId, body);
        List<String> requests = new ArrayList<>();
        try (Blue blue = blue(content, requests)) {
            // when
            Throwable failure =
                    captureFailure(
                            () -> blue
                                    .getDocumentProcessor()
                                    .administration().effectiveFragmentationCatalog(
                                            document));

            // then
            assertTrue(failure instanceof MustUnderstandFailureException);
            assertEquals(
                    ProcessorErrorCategory
                            .UnsupportedRuntimeType,
                    ((MustUnderstandFailureException) failure)
                            .errorCategory());
            assertFalse(requests.contains(bodyBlueId));
        }
    }

    @Test
    void shouldReturnImmutableCatalogCollections() {
        // given
        Fixture fixture = new Fixture();

        // when
        EffectiveFragmentationCatalog catalog =
                observeCatalog(fixture).catalog;
        UnsupportedOperationException scopeMapFailure =
                captureFailure(
                        () -> catalog
                                .effectiveContractsByScope()
                                .put("/other",
                                        Collections
                                                .<EffectiveContractSnapshot>
                                                        emptyList()));
        UnsupportedOperationException scopeListFailure =
                captureFailure(
                        () -> catalog
                                .effectiveContractsByScope()
                                .get("/")
                                .clear());
        UnsupportedOperationException planMapFailure =
                captureFailure(
                        () -> catalog.scopePlansByScope()
                                .clear());
        UnsupportedOperationException planListFailure =
                captureFailure(
                        () -> catalog.scopePlansByScope()
                                .get("/")
                                .concreteChildPaths()
                                .clear());

        // then
        assertEquals(UnsupportedOperationException.class,
                scopeMapFailure.getClass());
        assertEquals(UnsupportedOperationException.class,
                scopeListFailure.getClass());
        assertEquals(UnsupportedOperationException.class,
                planMapFailure.getClass());
        assertEquals(UnsupportedOperationException.class,
                planListFailure.getClass());
    }

    @Test
    void shouldReturnImmutableHeaderFields() {
        // given
        Fixture fixture = new Fixture();

        // when
        EffectiveContractSnapshot handler =
                observeCatalog(fixture).handler;
        UnsupportedOperationException failure =
                captureFailure(
                        () -> handler.headerFields()
                                .put("other",
                                        FrozenNode.fromNode(
                                                new Node()
                                                        .value("x"))));

        // then
        assertEquals(UnsupportedOperationException.class,
                failure.getClass());
    }

    @Test
    void shouldReturnImmutableExecutableBodyMetadataCollections() {
        // given
        Fixture fixture = new Fixture();

        // when
        EffectiveContractSnapshot handler =
                observeCatalog(fixture).handler;
        UnsupportedOperationException fieldsFailure =
                captureFailure(
                        () -> handler
                                .executableBodyFields()
                                .add("other"));
        UnsupportedOperationException idsFailure =
                captureFailure(
                        () -> handler
                                .executableBodyNodeBlueIdsByField()
                                .clear());
        UnsupportedOperationException descriptorsFailure =
                captureFailure(
                        () -> handler
                                .executableBodySourceDescriptorsByField()
                                .clear());

        // then
        assertEquals(UnsupportedOperationException.class,
                fieldsFailure.getClass());
        assertEquals(UnsupportedOperationException.class,
                idsFailure.getClass());
        assertEquals(UnsupportedOperationException.class,
                descriptorsFailure.getClass());
    }

    @Test
    void shouldReturnImmutableBodySourceContributions() {
        // given
        Fixture fixture = new Fixture();

        // when
        ExecutableBodySourceDescriptor bodySource =
                observeCatalog(fixture).bodySource;
        UnsupportedOperationException failure =
                captureFailure(
                        () -> bodySource
                                .sourceContributionNodeBlueIds()
                                .clear());

        // then
        assertEquals(UnsupportedOperationException.class,
                failure.getClass());
    }

    @Test
    void shouldRejectBodyDescriptorIdentityDisagreement() {
        // given
        ExecutableBodySourceDescriptor descriptor =
                new ExecutableBodySourceDescriptor(
                        "/",
                        "run",
                        "sha256:type",
                        "program",
                        "sha256:body-a",
                        Collections.singletonList(
                                "sha256:contribution"),
                        "sha256:contribution",
                        "/program",
                        false);

        // when
        IllegalArgumentException failure = captureFailure(
                () -> EffectiveContractSnapshot
                        .builder("/", "run")
                        .effectiveTypeBlueId(
                                "sha256:type")
                        .role("handler")
                        .sourceContribution(
                                "sha256:contribution")
                        .executableBody(
                                "program",
                                "sha256:body-b")
                        .executableBodySourceDescriptor(
                                "program",
                                descriptor)
                        .build());

        // then
        assertEquals(IllegalArgumentException.class,
                failure.getClass());
    }

    private static CatalogObservation observeCatalog(
            Fixture fixture) {
        try (Blue blue = fixture.blue()) {
            Node document = fixture.document();
            String expectedRootBlueId =
                    blue.calculateSourceDocumentBlueId(
                            document.clone());
            EffectiveFragmentationCatalog catalog =
                    blue.getDocumentProcessor()
                            .administration().effectiveFragmentationCatalog(
                                    document);
            EffectiveContractSnapshot handler =
                    contract(catalog, "/", "run");
            return new CatalogObservation(
                    catalog,
                    handler,
                    handler
                            .executableBodySourceDescriptorsByField()
                            .get("program"),
                    expectedRootBlueId);
        }
    }

    private static final class CatalogObservation {
        private final EffectiveFragmentationCatalog catalog;
        private final EffectiveContractSnapshot handler;
        private final ExecutableBodySourceDescriptor bodySource;
        private final String expectedRootBlueId;

        private CatalogObservation(
                EffectiveFragmentationCatalog catalog,
                EffectiveContractSnapshot handler,
                ExecutableBodySourceDescriptor bodySource,
                String expectedRootBlueId) {
            this.catalog = catalog;
            this.handler = handler;
            this.bodySource = bodySource;
            this.expectedRootBlueId = expectedRootBlueId;
        }
    }

    private static EffectiveContractSnapshot contract(
            EffectiveFragmentationCatalog catalog,
            String scope,
            String key) {
        for (EffectiveContractSnapshot snapshot :
                catalog.effectiveContractsByScope()
                        .get(scope)) {
            if (key.equals(snapshot.key())) {
                return snapshot;
            }
        }
        throw new AssertionError(
                "Missing contract " + scope + "/" + key);
    }

    private static String signature(
            EffectiveFragmentationCatalog catalog) {
        StringBuilder value =
                new StringBuilder(
                        catalog.rootBlueId());
        value.append('|')
                .append(
                        catalog
                                .effectiveProcessEmbeddedPathsByScope());
        for (Map.Entry<String,
                List<EffectiveContractSnapshot>> scope :
                catalog.effectiveContractsByScope()
                        .entrySet()) {
            value.append('|').append(scope.getKey());
            for (EffectiveContractSnapshot contract :
                    scope.getValue()) {
                value.append('|')
                        .append(contract.key())
                        .append(':')
                        .append(contract.role())
                        .append(':')
                        .append(
                                contract
                                        .effectiveTypeBlueId())
                        .append(':')
                        .append(
                                contract
                                        .sourceContributionNodeBlueIds())
                        .append(':');
                for (Map.Entry<String, FrozenNode> header :
                        contract.headerFields()
                                .entrySet()) {
                    value.append(header.getKey())
                            .append('=')
                            .append(header.getValue()
                                    .blueId())
                            .append(',');
                }
                value
                        .append(':')
                        .append(
                                contract
                                        .executableBodyFields())
                        .append(':')
                        .append(
                                contract
                                        .executableBodyNodeBlueIdsByField());
                for (Map.Entry<String,
                        ExecutableBodySourceDescriptor> body :
                        contract
                                .executableBodySourceDescriptorsByField()
                                .entrySet()) {
                    ExecutableBodySourceDescriptor source =
                            body.getValue();
                    value.append(':')
                            .append(body.getKey())
                            .append('=')
                            .append(source.scopePath())
                            .append(',')
                            .append(source.contractKey())
                            .append(',')
                            .append(source.effectiveTypeBlueId())
                            .append(',')
                            .append(source.bodyNodeBlueId())
                            .append(',')
                            .append(
                                    source
                                            .sourceContributionNodeBlueIds())
                            .append(',')
                            .append(
                                    source
                                            .owningSourceContributionNodeBlueId())
                            .append(',')
                            .append(source.sourcePointer())
                            .append(',')
                            .append(source.pureReference());
                }
            }
        }
        return value.toString();
    }

    private static String surfaceSignature(
            EffectiveFragmentationCatalog catalog) {
        String complete = signature(catalog);
        return complete.substring(complete.indexOf('|') + 1);
    }

    private static Blue blue(
            Map<String, Node> content,
            List<String> requests) {
        NodeProvider provider = blueId -> {
            requests.add(blueId);
            Node found = content.get(blueId);
            return found != null
                    ? Collections.singletonList(
                            found.clone())
                    : null;
        };
        return ProcessorTestSupport.blue(provider);
    }

    public static final class CatalogHandler
            extends HandlerContract {
        private Node program;
        private Node request;

        public Node getRequest() { return request; }
        public void setRequest(Node request) { this.request = request; }

        private String label;

        public Node getProgram() {
            return program;
        }

        public void setProgram(Node program) {
            this.program = program;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }
    }

    private static final class CatalogHandlerProcessor
            implements HandlerProcessor<CatalogHandler> {

        @Override
        public Class<CatalogHandler> contractType() {
            return CatalogHandler.class;
        }

        @Override
        public List<String> executableBodyFields() {
            return Collections.singletonList("program");
        }

        @Override
        public void execute(
                CatalogHandler contract,
                ProcessorExecutionContext context) {
            // Catalog inspection must never reach execution.
        }
    }

    private static final class Fixture {
        private final Node program =
                new Node().properties(
                        "operation",
                        new Node().value("cold"));
        private final String programBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        program);
        private final Node handlerType =
                new Node()
                        .name("Catalog Handler")
                        .type(new Node().blueId(
                                RuntimeBlueIds.HANDLER));
        private final String handlerTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        handlerType);
        private final Node inheritedContribution =
                new Node().properties(
                        "program",
                        new Node().blueId(
                                programBlueId));
        private final String inheritedContributionBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        inheritedContribution);
        private final Node scopeType =
                new Node()
                        .name("Catalog Scope")
                        .contracts(
                                new Node().properties(
                                        "run",
                                        inheritedContribution));
        private final String scopeTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        scopeType);
        private final Node directContribution =
                new Node()
                        .type(new Node().blueId(
                                handlerTypeBlueId))
                        .properties(
                                "channel",
                                new Node().value(
                                        "lifecycle"))
                        .properties(
                                "label",
                                new Node().value(
                                        "instance-overlay"));
        private final String directContributionBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        directContribution);
        private final Map<String, Node> content =
                new LinkedHashMap<>();
        private final List<String> providerRequests =
                new ArrayList<>();

        private Fixture() {
            content.put(programBlueId, program);
            content.put(
                    handlerTypeBlueId, handlerType);
            content.put(scopeTypeBlueId, scopeType);
        }

        private Node document() {
            return new Node()
                    .name("Catalog document")
                    .type(new Node().blueId(
                            scopeTypeBlueId))
                    .contracts(
                            new Node()
                                    .properties(
                                            "lifecycle",
                                            new Node().type(
                                                    new Node().blueId(
                                                            RuntimeBlueIds
                                                                    .LIFECYCLE_EVENT_CHANNEL)))
                                    .properties(
                                            "run",
                                            directContribution
                                                    .clone()));
        }

        private Blue blue() {
            Blue blue =
                    EffectiveFragmentationCatalogTest
                            .blue(
                                    content,
                                    providerRequests);
            blue.registerContractProcessor(
                    handlerTypeBlueId,
                    new CatalogHandlerProcessor());
            return blue;
        }
    }
}
