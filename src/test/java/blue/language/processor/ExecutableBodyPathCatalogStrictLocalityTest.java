package blue.language.processor;

import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.provider.NodeProvider;
import blue.language.runtime.BlueLanguage;
import blue.language.runtime.LanguageProcessing;
import blue.language.snapshot.FrozenNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies strict snapshot locality at authored and type-provided edges. */
final class ExecutableBodyPathCatalogStrictLocalityTest {

    @Test
    void shouldNotReadTypeOfUnrelatedAuthoredSibling() {
        // given
        Node siblingType = new Node()
                .name("Unrelated sibling type")
                .properties("inherited", new Node().value("cold"));
        String siblingTypeBlueId = blueId(siblingType);
        Node rootType = new Node()
                .name("Root type with an overlaid sibling")
                .properties(
                        "sibling",
                        new Node().properties(
                                "inherited",
                                new Node().value("base")));
        String rootTypeBlueId = blueId(rootType);
        Node document = new Node()
                .type(reference(rootTypeBlueId))
                .properties("selected", new Node().value("root-only"))
                .properties(
                        "sibling",
                        new Node().type(reference(siblingTypeBlueId)));
        List<String> providerRequests = new ArrayList<>();
        Map<String, Node> content = new LinkedHashMap<>();
        content.put(rootTypeBlueId, rootType);
        content.put(siblingTypeBlueId, siblingType);
        NodeProvider provider = recordingProvider(
                providerRequests,
                content);
        boolean siblingPathCataloged =
                ExecutableBodyPathCatalog.ordinaryReferencePaths(
                                document,
                                Collections.singleton("/"))
                        .contains("/sibling");

        // when
        resolveStrict(
                document,
                Collections.singleton("/"),
                Collections.singletonMap(
                        "unused-executable-type",
                        Collections.<String>emptyList()),
                provider);

        // then
        assertTrue(siblingPathCataloged);
        assertTrue(providerRequests.contains(rootTypeBlueId));
        assertFalse(
                providerRequests.contains(siblingTypeBlueId),
                "an unrelated authored sibling type must remain cold");
    }

    @Test
    void shouldNotReadAncestorOfInlineTypeOnUnrelatedSibling() {
        // given
        Node typeAncestor = new Node()
                .name("Unrelated inline-type ancestor")
                .properties("inherited", new Node().value("cold"));
        String typeAncestorBlueId = blueId(typeAncestor);
        Node inlineType = new Node()
                .name("Unrelated inline sibling type")
                .type(reference(typeAncestorBlueId));
        String inlineTypeBlueId = blueId(inlineType);
        Node document = new Node().properties(
                "sibling",
                new Node().type(reference(inlineTypeBlueId)));
        List<String> providerRequests = new ArrayList<>();
        Map<String, Node> content = new LinkedHashMap<>();
        content.put(inlineTypeBlueId, inlineType);
        content.put(typeAncestorBlueId, typeAncestor);
        NodeProvider provider = recordingProvider(
                providerRequests,
                content);

        // when
        resolveStrict(
                document,
                Collections.singleton("/"),
                Collections.singletonMap(
                        "unused-executable-type",
                        Collections.<String>emptyList()),
                provider);

        // then
        assertFalse(
                providerRequests.contains(typeAncestorBlueId),
                "an unopened inline type's referenced ancestry must remain cold");
    }

    @Test
    void shouldNotReadInlineExecutableBodyTypeAtDeepTypeProvidedScope() {
        // given
        Node programType = new Node()
                .name("Cold inline program type")
                .properties("inherited", new Node().value("must stay cold"));
        String programTypeBlueId = blueId(programType);
        Node handlerType = new Node().name("Type-provided child handler");
        String handlerTypeBlueId = blueId(handlerType);
        Node rootType = new Node()
                .name("Root type providing a complete scope spine")
                .properties(
                        "child",
                        new Node().properties(
                                "grandchild",
                                new Node().contracts(
                                        new Node().properties(
                                                "handler",
                                                new Node()
                                                        .type(reference(
                                                                handlerTypeBlueId))
                                                        .properties(
                                                                "program",
                                                                new Node()
                                                                        .type(reference(
                                                                                programTypeBlueId))
                                                                        .properties(
                                                                                "authored",
                                                                                new Node().value(
                                                                                        "body")))))));
        String rootTypeBlueId = blueId(rootType);
        Node document = new Node().type(reference(rootTypeBlueId));
        Map<String, Node> content = new LinkedHashMap<>();
        content.put(rootTypeBlueId, rootType);
        content.put(handlerTypeBlueId, handlerType);
        content.put(programTypeBlueId, programType);
        List<String> providerRequests = new ArrayList<>();
        NodeProvider provider = recordingProvider(providerRequests, content);

        // when
        resolveStrict(
                document,
                Collections.singleton("/child/grandchild"),
                Collections.singletonMap(
                        handlerTypeBlueId,
                        Collections.singletonList("program")),
                provider);

        // then
        assertFalse(
                providerRequests.contains(programTypeBlueId),
                "an inline executable body supplied only by a parent type must remain cold");
    }

    private static void resolveStrict(
            Node document,
            Iterable<String> openedScopePaths,
            Map<String, List<String>> executableBodyFieldsByType,
            NodeProvider provider) {
        try (BlueLanguage language = BlueLanguage.builder().build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope(provider)) {
            ExecutableBodyPathCatalog
                    .resolveCanonicalTransientIncludingTypeContracts(
                            new LanguageProcessingSnapshotManager(scope),
                            FrozenNode.fromNode(document),
                            openedScopePaths,
                            executableBodyFieldsByType);
        }
    }

    private static NodeProvider recordingProvider(
            List<String> providerRequests,
            Map<String, Node> content) {
        return blueId -> {
            providerRequests.add(blueId);
            Node exact = content.get(blueId);
            return exact != null
                    ? Collections.singletonList(exact.clone())
                    : null;
        };
    }

    private static Node reference(String blueId) {
        return new Node().blueId(blueId);
    }

    private static String blueId(Node node) {
        return DirectBlueIdCalculator.calculateBlueId(node);
    }
}
