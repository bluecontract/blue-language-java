package blue.language.processor;

import blue.language.Blue;
import blue.language.NodeProvider;
import blue.language.model.Node;
import blue.language.processor.model.HandlerContract;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.snapshot.FrozenNode;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectiveFragmentationCatalogTest {

    @Test
    void reportsInheritedBodyAndExactHeaderWithoutDemandingBody() {
        Fixture fixture = new Fixture();
        try (Blue blue = fixture.blue()) {
            EffectiveFragmentationCatalog catalog =
                    blue.getDocumentProcessor()
                            .effectiveFragmentationCatalog(
                                    fixture.document());

            EffectiveContractSnapshot handler =
                    contract(catalog, "/", "run");
            assertEquals("handler", handler.role());
            assertEquals(
                    fixture.handlerTypeBlueId,
                    handler.effectiveTypeBlueId());
            assertEquals(
                    Arrays.asList(
                            fixture.inheritedContributionBlueId,
                            fixture.directContributionBlueId),
                    handler.sourceContributionNodeBlueIds());
            assertEquals(
                    Collections.singletonList("program"),
                    handler.executableBodyFields());
            assertEquals(
                    Collections.singletonMap(
                            "program",
                            fixture.programBlueId),
                    handler.executableBodyNodeBlueIdsByField());
            assertEquals(
                    Collections.singletonList(
                            fixture.programBlueId),
                    handler.executableBodyNodeBlueIds());
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
            assertFalse(
                    fixture.providerRequests
                            .contains(fixture.programBlueId),
                    "catalog inspection demanded the executable body");
            assertEquals(
                    BlueIdCalculator.calculateBlueId(
                            fixture.document()),
                    catalog.rootBlueId());
        }
    }

    @Test
    void inlineContractsFragmentAndPureRootProduceSameCatalog() {
        Fixture fixture = new Fixture();
        Node inline = fixture.document();
        Node exactContracts =
                inline.getContracts().clone();
        String contractsBlueId =
                BlueIdCalculator.calculateBlueId(
                        exactContracts);
        Node fragmented =
                inline.clone()
                        .contracts(
                                new Node().blueId(
                                        contractsBlueId));
        String rootBlueId =
                BlueIdCalculator.calculateBlueId(
                        fragmented);
        fixture.content.put(
                contractsBlueId,
                exactContracts);
        fixture.content.put(
                rootBlueId,
                fragmented);

        try (Blue blue = fixture.blue()) {
            EffectiveFragmentationCatalog inlineCatalog =
                    blue.getDocumentProcessor()
                            .effectiveFragmentationCatalog(
                                    inline);
            EffectiveFragmentationCatalog fragmentedCatalog =
                    blue.getDocumentProcessor()
                            .effectiveFragmentationCatalog(
                                    fragmented);
            EffectiveFragmentationCatalog referenceCatalog =
                    blue.getDocumentProcessor()
                            .effectiveFragmentationCatalog(
                                    new Node().blueId(
                                            rootBlueId));

            assertEquals(
                    signature(inlineCatalog),
                    signature(fragmentedCatalog));
            assertEquals(
                    signature(inlineCatalog),
                    signature(referenceCatalog));
            assertEquals(
                    inlineCatalog.rootBlueId(),
                    fragmentedCatalog.rootBlueId());
            assertEquals(
                    inlineCatalog.rootBlueId(),
                    referenceCatalog.rootBlueId());
            assertEquals(rootBlueId, inlineCatalog.rootBlueId());
            assertFalse(
                    fixture.providerRequests
                            .contains(fixture.programBlueId));
        }

        /*
         * A fresh processor starts with the pure Root reference so the same
         * comparison also covers cold-reference then warm-inline order.
         */
        try (Blue cold = fixture.blue()) {
            EffectiveFragmentationCatalog coldReference =
                    cold.getDocumentProcessor()
                            .effectiveFragmentationCatalog(
                                    new Node().blueId(
                                            rootBlueId));
            EffectiveFragmentationCatalog warmInline =
                    cold.getDocumentProcessor()
                            .effectiveFragmentationCatalog(
                                    inline);
            assertEquals(
                    signature(coldReference),
                    signature(warmInline));
        }
    }

    @Test
    void reportsDirectProcessEmbeddedPath() {
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

        try (Blue blue = blue(
                new LinkedHashMap<String, Node>(),
                new ArrayList<String>())) {
            EffectiveFragmentationCatalog catalog =
                    blue.getDocumentProcessor()
                            .effectiveFragmentationCatalog(
                                    document);

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
    void inheritedProcessEmbeddedPathDefinesChildCatalogScope() {
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
                        .contracts(
                                new Node().properties(
                                        "embedded",
                                        inheritedEmbedded));
        String rootTypeBlueId =
                BlueIdCalculator.calculateBlueId(
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

        try (Blue blue = blue(content, new ArrayList<String>())) {
            EffectiveFragmentationCatalog catalog =
                    blue.getDocumentProcessor()
                            .effectiveFragmentationCatalog(
                                    document);

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
                            BlueIdCalculator.calculateBlueId(
                                    inheritedEmbedded)),
                    embedded
                            .sourceContributionNodeBlueIds());
        }
    }

    @Test
    void declaredEmbeddedReferenceIsOpenedButUnrelatedReferenceStaysCold() {
        Node child = new Node().properties(
                "value",
                new Node().value("embedded"));
        String childBlueId =
                BlueIdCalculator.calculateBlueId(child);
        Node unrelated = new Node().properties(
                "secret",
                new Node().value("cold"));
        String unrelatedBlueId =
                BlueIdCalculator.calculateBlueId(
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

        try (Blue blue = blue(content, requests)) {
            EffectiveFragmentationCatalog catalog =
                    blue.getDocumentProcessor()
                            .effectiveFragmentationCatalog(
                                    document);

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
    void referencedHandlerEventMatcherRemainsAnExactColdHeaderEdge() {
        Fixture fixture = new Fixture();
        Node eventPattern =
                new Node().properties(
                        "kind",
                        new Node().value("catalog-event"));
        String eventPatternBlueId =
                BlueIdCalculator.calculateBlueId(
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

        try (Blue blue = fixture.blue()) {
            EffectiveContractSnapshot handler =
                    contract(
                            blue.getDocumentProcessor()
                                    .effectiveFragmentationCatalog(
                                            document),
                            "/",
                            "run");

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
    void unrelatedUnavailableReferenceDoesNotBlockRootCatalog() {
        Node unavailable =
                new Node().properties(
                        "data",
                        new Node().value("unavailable"));
        String unavailableBlueId =
                BlueIdCalculator.calculateBlueId(
                        unavailable);
        List<String> requests = new ArrayList<>();

        try (Blue blue = blue(
                Collections.<String, Node>emptyMap(),
                requests)) {
            EffectiveFragmentationCatalog catalog =
                    blue.getDocumentProcessor()
                            .effectiveFragmentationCatalog(
                                    new Node().properties(
                                            "unrelated",
                                            new Node().blueId(
                                                    unavailableBlueId)));

            assertTrue(
                    catalog.effectiveContractsByScope()
                            .containsKey("/"));
            assertFalse(requests.contains(
                    unavailableBlueId));
        }
    }

    @Test
    void unsupportedTypeFailsBeforeUnrelatedBodyDemand() {
        Node body =
                new Node().properties(
                        "secret",
                        new Node().value("cold"));
        String bodyBlueId =
                BlueIdCalculator.calculateBlueId(body);
        Node unknownType =
                new Node().name(
                        "Unsupported catalog contract");
        String unknownTypeBlueId =
                BlueIdCalculator.calculateBlueId(
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
            MustUnderstandFailureException failure =
                    assertThrows(
                            MustUnderstandFailureException.class,
                            () -> blue
                                    .getDocumentProcessor()
                                    .effectiveFragmentationCatalog(
                                            document));
            assertEquals(
                    ProcessorErrorCategory
                            .UnsupportedRuntimeType,
                    failure.errorCategory());
            assertFalse(requests.contains(bodyBlueId));
        }
    }

    @Test
    void returnedCatalogAndSnapshotSurfacesAreImmutable() {
        Fixture fixture = new Fixture();
        try (Blue blue = fixture.blue()) {
            EffectiveFragmentationCatalog catalog =
                    blue.getDocumentProcessor()
                            .effectiveFragmentationCatalog(
                                    fixture.document());
            EffectiveContractSnapshot handler =
                    contract(catalog, "/", "run");

            assertThrows(
                    UnsupportedOperationException.class,
                    () -> catalog
                            .effectiveContractsByScope()
                            .put("/other",
                                    Collections
                                            .<EffectiveContractSnapshot>
                                                    emptyList()));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> catalog
                            .effectiveContractsByScope()
                            .get("/")
                            .clear());
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> handler.headerFields()
                            .put("other",
                                    FrozenNode.fromNode(
                                            new Node()
                                                    .value("x"))));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> handler
                            .executableBodyFields()
                            .add("other"));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> handler
                            .executableBodyNodeBlueIdsByField()
                            .clear());
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
            }
        }
        return value.toString();
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
                BlueIdCalculator.calculateBlueId(
                        program);
        private final Node handlerType =
                new Node()
                        .name("Catalog Handler")
                        .type(new Node().blueId(
                                RuntimeBlueIds.HANDLER));
        private final String handlerTypeBlueId =
                BlueIdCalculator.calculateBlueId(
                        handlerType);
        private final Node inheritedContribution =
                new Node().properties(
                        "program",
                        new Node().blueId(
                                programBlueId));
        private final String inheritedContributionBlueId =
                BlueIdCalculator.calculateBlueId(
                        inheritedContribution);
        private final Node scopeType =
                new Node()
                        .name("Catalog Scope")
                        .contracts(
                                new Node().properties(
                                        "run",
                                        inheritedContribution));
        private final String scopeTypeBlueId =
                BlueIdCalculator.calculateBlueId(
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
                BlueIdCalculator.calculateBlueId(
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
