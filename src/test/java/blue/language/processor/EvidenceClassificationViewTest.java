package blue.language.processor;

import blue.language.Blue;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.provider.NodeProvider;
import blue.language.merge.ResolvedSnapshot;
import blue.language.model.wire.JsonPointer;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.runtime.BlueLanguage;
import blue.language.runtime.LanguageProcessing;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that Phase-B projection remains bounded to admitted scope ancestry. */
final class EvidenceClassificationViewTest {

    private static final String SELECTED_SCOPE = "/selectedScope";
    private static final String SELECTED_CHANNEL = "incoming";
    private static final String UNSELECTED_HANDLER = "unselectedHandler";
    private static final String UNSELECTED_REFERENCE_HEADER =
            "unselectedReferenceHeader";
    private static final String UNRELATED_SCOPE = "unrelatedSibling";
    private static final String UNRELATED_ROUTE = "unrelatedRoute";

    @Test
    void shouldPreserveNominalTypeWithoutDemandingUnselectedHeadersOrBodies() {
        // given
        Node selectedContract = new Node()
                .properties("order", new Node().value(0));
        Node forbiddenBody = new Node()
                .properties("forbidden", new Node().value(true));
        String forbiddenBodyBlueId =
                DirectBlueIdCalculator.calculateBlueId(forbiddenBody);
        Node forbiddenHeader = new Node()
                .type(new Node().blueId(RuntimeBlueIds.HANDLER))
                .properties(
                        "result",
                        new Node().blueId(forbiddenBodyBlueId));
        String forbiddenHeaderBlueId =
                DirectBlueIdCalculator.calculateBlueId(forbiddenHeader);
        Node scopeType = new Node()
                .name("Phase-B sparse scope type")
                .contracts(new Node()
                        .properties(
                                SELECTED_CHANNEL,
                                selectedContract.clone())
                        .properties(
                                UNSELECTED_HANDLER,
                                new Node()
                                        .type(new Node().blueId(
                                                RuntimeBlueIds.HANDLER))
                                        .properties(
                                                "result",
                                                new Node().blueId(
                                                        forbiddenBodyBlueId)))
                        .properties(
                                UNSELECTED_REFERENCE_HEADER,
                                new Node().blueId(
                                        forbiddenHeaderBlueId)));
        String scopeTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(scopeType);
        List<String> requests = new ArrayList<>();
        NodeProvider provider = blueId -> {
            requests.add(blueId);
            if (scopeTypeBlueId.equals(blueId)) {
                return Collections.singletonList(scopeType.clone());
            }
            if (forbiddenBodyBlueId.equals(blueId)) {
                throw new AssertionError(
                        "Phase-B demanded an unselected inherited body");
            }
            if (forbiddenHeaderBlueId.equals(blueId)) {
                throw new AssertionError(
                        "Phase-B demanded an unselected inherited header");
            }
            return null;
        };
        Node root = new Node().type(
                new Node().blueId(scopeTypeBlueId));
        Map<String, Set<String>> selectedKeys = new LinkedHashMap<>();
        selectedKeys.put(
                JsonPointer.ROOT,
                Collections.singleton(SELECTED_CHANNEL));

        // when
        ResolvedSnapshot classification;
        Set<String> preserved = new LinkedHashSet<>();
        try (Blue blue = ProcessorTestSupport.blue(provider)) {
            DocumentProcessor processor = blue.getDocumentProcessor();
            EvidenceClassificationView view =
                    new EvidenceClassificationView(
                            ProcessorInvocationServices.configured(processor),
                            null,
                            root,
                            null,
                            () -> null);
            view.pruneContracts(
                    root,
                    JsonPointer.ROOT,
                    selectedKeys);
            view.collectInheritedColdContractPaths(
                    root,
                    JsonPointer.ROOT,
                    selectedKeys,
                    preserved,
                    new LinkedHashSet<String>());
            view.collectColdReferencePaths(
                    root,
                    JsonPointer.ROOT,
                    false,
                    selectedKeys.keySet(),
                    preserved);
            classification = processor.snapshotManager()
                    .fromDocumentTransientPreservingPaths(
                            root,
                            preserved);
        }

        // then
        assertNotNull(root.getType());
        assertTrue(root.getType().isReferenceOnly());
        assertEquals(
                scopeTypeBlueId,
                root.getType().getBlueId());
        assertTrue(preserved.contains(
                "/contracts/" + UNSELECTED_HANDLER));
        assertTrue(preserved.contains(
                "/contracts/" + UNSELECTED_REFERENCE_HEADER));
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(selectedContract),
                DirectBlueIdCalculator.calculateBlueId(
                        classification.resolvedRoot().getContracts()
                                .getProperties().get(SELECTED_CHANNEL)));
        assertTrue(requests.contains(scopeTypeBlueId));
        assertFalse(requests.contains(forbiddenHeaderBlueId));
        assertFalse(requests.contains(forbiddenBodyBlueId));
    }

    @Test
    void shouldKeepTypeProvidedUnrelatedSiblingTypeColdDuringClassification() {
        // given
        Node unrelatedSiblingType = new Node()
                .name("Phase-B unrelated sibling type")
                .properties("payload", new Node().value("must stay cold"));
        String unrelatedSiblingTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(
                        unrelatedSiblingType);
        Node rootType = new Node()
                .name("Phase-B root type with unrelated sibling")
                .contracts(new Node().properties(
                        SELECTED_CHANNEL,
                        new Node().value("selected")))
                .properties(
                        UNRELATED_SCOPE,
                        new Node().type(new Node().blueId(
                                unrelatedSiblingTypeBlueId)));
        String rootTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(rootType);
        List<String> requests = new ArrayList<>();
        NodeProvider provider = blueId -> {
            requests.add(blueId);
            if (rootTypeBlueId.equals(blueId)) {
                return Collections.singletonList(rootType.clone());
            }
            if (unrelatedSiblingTypeBlueId.equals(blueId)) {
                throw new AssertionError(
                        "Phase-B demanded an unrelated sibling type");
            }
            return null;
        };
        Node root = new Node().type(new Node().blueId(rootTypeBlueId));
        Map<String, Set<String>> selectedKeys = new LinkedHashMap<>();
        selectedKeys.put(
                JsonPointer.ROOT,
                Collections.singleton(SELECTED_CHANNEL));
        Set<String> preserved = new LinkedHashSet<>();

        // when
        try (BlueLanguage language = BlueLanguage.builder().build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope(provider);
             DocumentProcessor processor = new DocumentProcessor();
             ProcessorInvocationServices services =
                     ProcessorInvocationServices.platform(
                             processor,
                             new LanguageProcessingSnapshotManager(scope),
                             scope.runtimeAccess(),
                             scope.newConformanceEngine())) {
            EvidenceClassificationView view =
                    new EvidenceClassificationView(
                            services,
                            null,
                            root,
                            null,
                            () -> null);
            view.pruneContracts(root, JsonPointer.ROOT, selectedKeys);
            view.collectInheritedColdContractPaths(
                    root,
                    JsonPointer.ROOT,
                    selectedKeys,
                    preserved,
                    new LinkedHashSet<String>());
            view.collectColdReferencePaths(
                    root,
                    JsonPointer.ROOT,
                    false,
                    selectedKeys.keySet(),
                    preserved);
            services.snapshotManager()
                    .fromDocumentTransientPreservingPaths(root, preserved);
        }

        // then
        assertTrue(requests.contains(rootTypeBlueId));
        assertFalse(
                requests.contains(unrelatedSiblingTypeBlueId),
                "Phase-B classification must not demand a type-provided sibling outside the retained channel surface");
    }

    @Test
    void shouldRetainTypeThatSuppliesSelectedDescendantContract() {
        // given
        Node unrelatedType = new Node()
                .name("Cold type-provided sibling")
                .properties("payload", new Node().value("must stay cold"));
        String unrelatedTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(unrelatedType);
        Node selectedContract = new Node().value("selected");
        Node rootType = new Node()
                .name("Root type supplying the selected child")
                .properties(
                        "child",
                        new Node().contracts(new Node().properties(
                                SELECTED_CHANNEL,
                                selectedContract.clone())))
                .properties(
                        UNRELATED_SCOPE,
                        new Node().type(new Node().blueId(
                                unrelatedTypeBlueId)));
        String rootTypeBlueId =
                DirectBlueIdCalculator.calculateBlueId(rootType);
        List<String> requests = new ArrayList<>();
        NodeProvider provider = blueId -> {
            requests.add(blueId);
            if (rootTypeBlueId.equals(blueId)) {
                return Collections.singletonList(rootType.clone());
            }
            if (unrelatedTypeBlueId.equals(blueId)) {
                throw new AssertionError(
                        "Phase-B demanded a type outside the selected spine");
            }
            return null;
        };
        Node root = new Node()
                .type(new Node().blueId(rootTypeBlueId))
                .properties(
                        "child",
                        new Node().properties(
                                "authoredState",
                                new Node().value(true)));
        ExternalDeliverySnapshot delivery =
                ExternalDeliverySnapshot.builder(
                                "/child", SELECTED_CHANNEL)
                        .effectiveTypeBlueId("selected-type")
                        .checkpointDomainBlueId("checkpoint-domain")
                        .checkpointSubjectBlueId("checkpoint-subject")
                        .build();
        VerifiedExecutionEvidence evidence =
                VerifiedExecutionEvidence.builder("root", "event")
                        .revisions(1L, 1L)
                        .runtimeRegistryIdentity("registry")
                        .eventOrderKey(ExternalOrderKey.of(
                                Collections.<Object>singletonList(1)))
                        .delivery(delivery)
                        .build();
        FrozenNode selected;
        FrozenNode resolved;

        // when
        try (BlueLanguage language = BlueLanguage.builder().build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope(provider);
             DocumentProcessor processor = new DocumentProcessor();
             ProcessorInvocationServices services =
                     ProcessorInvocationServices.platform(
                             processor,
                             new LanguageProcessingSnapshotManager(scope),
                             scope.runtimeAccess(),
                             scope.newConformanceEngine())) {
            EvidenceClassificationView view =
                    new EvidenceClassificationView(
                            services,
                            null,
                            root,
                            null,
                            () -> evidence);
            selected = view.selectedAt("/child");
            resolved = view.resolvedAt("/child");
        }

        // then
        assertNotNull(root.getType());
        assertEquals(rootTypeBlueId, root.getType().getBlueId());
        assertNotNull(selected);
        assertNotNull(resolved);
        assertTrue(selected.getProperties().containsKey("authoredState"));
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(selectedContract),
                DirectBlueIdCalculator.calculateBlueId(
                        resolved.getContracts()
                                .property(SELECTED_CHANNEL)
                                .toNode()));
        assertTrue(requests.contains(rootTypeBlueId));
        assertFalse(requests.contains(unrelatedTypeBlueId));
    }

    @Test
    void shouldKeepUnrelatedSiblingRouteStateAndBodyReferencesCold() {
        // given
        Node selectedScope = new Node().contracts(new Node()
                .properties(SELECTED_CHANNEL, new Node().value("selected"))
                .properties(
                        UNSELECTED_HANDLER,
                        new Node().properties(
                                "body",
                                referenceTo("unselected-body")))
                .properties(
                        ProcessorContractConstants.KEY_CHECKPOINT,
                        referenceTo("selected-checkpoint")));
        Node unrelatedScope = new Node()
                .properties("body", referenceTo("unrelated-body"))
                .contracts(new Node()
                        .properties(
                                ProcessorContractConstants.KEY_CHECKPOINT,
                                referenceTo("unrelated-checkpoint"))
                        .properties(
                                ProcessorContractConstants.KEY_TERMINATED,
                                referenceTo("unrelated-termination"))
                        .properties(
                                UNRELATED_ROUTE,
                                processEmbeddedReferenceHeader(
                                        "unrelated-paths")));
        Node root = new Node()
                .properties("selectedScope", selectedScope)
                .properties(UNRELATED_SCOPE, unrelatedScope)
                .contracts(new Node().properties(
                        "selectedRoute",
                        processEmbeddedReferenceHeader(
                                "selected-paths")));
        Map<String, Set<String>> selectedKeys = new LinkedHashMap<>();
        selectedKeys.put(
                SELECTED_SCOPE,
                Collections.singleton(SELECTED_CHANNEL));
        Set<String> preserved = new LinkedHashSet<>();

        // when
        try (DocumentProcessor processor = new DocumentProcessor()) {
            EvidenceClassificationView view =
                    new EvidenceClassificationView(
                            ProcessorInvocationServices.configured(processor),
                            null,
                            root,
                            null,
                            () -> null);
            view.pruneContracts(root, JsonPointer.ROOT, selectedKeys);
            view.collectColdReferencePaths(
                    root,
                    JsonPointer.ROOT,
                    false,
                    selectedKeys.keySet(),
                    preserved);
        }

        // then
        Node selectedContracts = root.getProperties()
                .get("selectedScope")
                .getContracts();
        assertNotNull(selectedContracts);
        assertNotNull(selectedContracts.getProperties()
                .get(SELECTED_CHANNEL));
        assertNotNull(selectedContracts.getProperties()
                .get(ProcessorContractConstants.KEY_CHECKPOINT));
        assertNull(selectedContracts.getProperties()
                .get(UNSELECTED_HANDLER));
        assertNotNull(root.getContracts().getProperties()
                .get("selectedRoute"));

        Node retainedUnrelated = root.getProperties()
                .get(UNRELATED_SCOPE);
        assertEquals(
                unrelatedScope,
                retainedUnrelated);
        assertTrue(retainedUnrelated.getContracts()
                .getProperties()
                .containsKey(ProcessorContractConstants.KEY_CHECKPOINT));
        assertTrue(retainedUnrelated.getContracts()
                .getProperties()
                .containsKey(ProcessorContractConstants.KEY_TERMINATED));
        assertTrue(retainedUnrelated.getContracts()
                .getProperties()
                .containsKey(UNRELATED_ROUTE));

        String unrelatedPath = "/" + UNRELATED_SCOPE;
        assertTrue(preserved.contains(unrelatedPath));
        assertFalse(preserved.contains(
                unrelatedPath + "/contracts/"
                        + ProcessorContractConstants.KEY_CHECKPOINT));
        assertFalse(preserved.contains(
                unrelatedPath + "/contracts/"
                        + ProcessorContractConstants.KEY_TERMINATED));
        assertFalse(preserved.contains(
                unrelatedPath + "/contracts/" + UNRELATED_ROUTE));
        assertFalse(preserved.contains(
                unrelatedPath + "/body"));
    }

    private static Node processEmbeddedReferenceHeader(String value) {
        return new Node()
                .type(new Node().blueId(RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties("paths", referenceTo(value));
    }

    private static Node referenceTo(String value) {
        Node exact = new Node().value(value);
        return new Node().blueId(
                DirectBlueIdCalculator.calculateBlueId(exact));
    }
}
