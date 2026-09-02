package blue.language.provider;

import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.identity.CanonicalTypeIdentityEvidence;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.merge.ResolvedSnapshot;
import blue.language.preprocess.provider.BasicNodeProvider;

import blue.language.Blue;
import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.runtime.LanguageRuntimeAccess;
import blue.language.resolve.ResolutionLimits;

import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static blue.language.TestUtils.useNodeNameAsBlueIdProvider;
import static blue.language.provider.Types.isSubtype;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TypesTest {

    @Test
    public void shouldResolveBasicTypeInheritance() throws Exception {

        // given
        Node a = new Node().name("A");
        Node b = new Node().name("B").type(a);
        Node c = new Node().name("C").type(b);

        List<Node> nodes = Arrays.asList(a, b, c);
        // when
        NodeProvider nodeProvider = useNodeNameAsBlueIdProvider(nodes);
        CanonicalTypeIdentityLookup identities = identities(a, b, c);

        // then
        assertTrue(isSubtype(b, a, nodeProvider, identities));
        assertTrue(isSubtype(c, a, nodeProvider, identities));
        assertTrue(isSubtype(a, a, nodeProvider, identities));
        assertTrue(isSubtype(b, b, nodeProvider, identities));
        assertFalse(isSubtype(b, c, nodeProvider, identities));

    }

    @Test
    public void shouldNotManufactureLabelStrippingSubtypeAliases() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        Blue blue = new Blue(nodeProvider);
        Node left = blue.yamlToNode(
                "name: Left label\n" +
                "description: Left description\n" +
                "x:\n" +
                "  type: Integer");
        Node right = blue.yamlToNode(
                "name: Right label\n" +
                "description: Right description\n" +
                "x:\n" +
                "  type: Integer");
        // when
        Node differentStructure = blue.yamlToNode(
                "name: Left label\n" +
                "description: Left description\n" +
                "x:\n" +
                "  type: Text");
        CanonicalTypeIdentityLookup identities = identities(
                left, right, differentStructure);

        // then
        assertFalse(isSubtype(left, right, nodeProvider, identities));
        assertFalse(isSubtype(right, left, nodeProvider, identities));
        assertFalse(isSubtype(
                left, differentStructure, nodeProvider, identities));
        assertThrows(
                IllegalStateException.class,
                () -> isSubtype(
                        left,
                        right,
                        nodeProvider,
                        CanonicalTypeIdentityLookup.incomplete()));
        assertThrows(
                IllegalStateException.class,
                () -> isSubtype(
                        left,
                        left,
                        nodeProvider,
                        CanonicalTypeIdentityLookup.incomplete()));
    }

    @Test
    public void shouldRecognizeEquivalentInlineAndReferencedTypesWithEvidence() throws Exception {

        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String person = "name: Person\n" +
                        "surname:\n" +
                        "  type: Text\n" +
                        "age:\n" +
                        "  type: Integer";
        nodeProvider.addSingleDocs(person);

        Node inlinePerson = nodeProvider.getNodeByName("Person").clone();
        Node referencedPerson = new Node().blueId(
                nodeProvider.getBlueIdByName("Person"));
        CanonicalTypeIdentityLookup identities = identity(
                inlinePerson,
                nodeProvider.getBlueIdByName("Person"));

        // when
        boolean inlineMatchesReference = isSubtype(
                inlinePerson,
                referencedPerson,
                nodeProvider,
                identities);

        // then
        assertTrue(inlineMatchesReference);
        assertTrue(isSubtype(
                referencedPerson,
                inlinePerson,
                nodeProvider,
                identities));
        assertThrows(
                IllegalStateException.class,
                () -> isSubtype(
                        inlinePerson,
                        referencedPerson,
                        nodeProvider,
                        CanonicalTypeIdentityLookup.incomplete()));
    }

    @Test
    public void shouldTraverseFetchedTypeHierarchyForReferenceOnlyCustomSubtype() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs("name: A");
        // when
        nodeProvider.addSingleDocs(
                "name: B\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("A"));

        // then
        assertTrue(isSubtype(
                new Node().blueId(nodeProvider.getBlueIdByName("B")),
                new Node().blueId(nodeProvider.getBlueIdByName("A")),
                nodeProvider,
                CanonicalTypeIdentityLookup.incomplete()));
    }

    @Test
    public void shouldTraverseFetchedInlineParentWithResolverEvidence() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs("name: Root Type");
        String rootBlueId = nodeProvider.getBlueIdByName("Root Type");
        nodeProvider.addSingleDocs(
                "name: Derived Type\n"
                        + "type:\n"
                        + "  name: Inline Parent\n"
                        + "  type:\n"
                        + "    blueId: " + rootBlueId);
        String derivedBlueId = nodeProvider.getBlueIdByName("Derived Type");
        Blue blue = new Blue(nodeProvider);
        ResolvedSnapshot snapshot = blue.resolveToSnapshot(
                new Node().type(new Node().blueId(derivedBlueId)));

        // when
        boolean subtype = isSubtype(
                new Node().blueId(derivedBlueId),
                new Node().blueId(rootBlueId),
                nodeProvider,
                snapshot.canonicalTypeIdentities());

        // then
        assertTrue(subtype);
    }

    @Test
    public void shouldKeepFacadeSubtypeTraversalWithinGlobalResolutionLimits() {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs("name: Bounded Supertype");
        String supertypeBlueId = nodeProvider.getBlueIdByName(
                "Bounded Supertype");
        nodeProvider.addSingleDocs(
                "name: Bounded Candidate\n"
                        + "type:\n"
                        + "  blueId: " + supertypeBlueId);
        String candidateBlueId = nodeProvider.getBlueIdByName(
                "Bounded Candidate");
        Blue blue = new Blue(nodeProvider);

        // when
        boolean unboundedSubtype = blue.isNodeSubtypeOf(
                new Node().blueId(candidateBlueId),
                new Node().blueId(supertypeBlueId));

        blue.setGlobalLimits(ResolutionLimits.deferringReferencesAt(
                Collections.singleton("/candidateType")));

        boolean boundedSubtype = blue.isNodeSubtypeOf(
                new Node().blueId(candidateBlueId),
                new Node().blueId(supertypeBlueId));

        // then
        assertTrue(unboundedSubtype);
        assertFalse(boundedSubtype);
    }

    private static CanonicalTypeIdentityLookup identities(Node... nodes) {
        Map<Node, String> idsByNode = new IdentityHashMap<>();
        for (Node node : nodes) {
            idsByNode.put(
                    node,
                    DirectBlueIdCalculator.calculateBlueId(node));
        }
        return new CanonicalTypeIdentityLookup() {
            @Override
            public boolean hasCompleteCoverage() {
                return true;
            }

            @Override
            public Optional<CanonicalTypeIdentityEvidence>
            findCanonicalTypeIdentityEvidence(Node completedType) {
                return Optional.of(CanonicalTypeIdentityEvidence.identityOnly(
                        requireCanonicalTypeBlueId(completedType)));
            }

            @Override
            public String requireCanonicalTypeBlueId(Node completedType) {
                String blueId = idsByNode.get(completedType);
                if (blueId == null) {
                    throw new IllegalStateException(
                            "No test identity for completed type");
                }
                return blueId;
            }
        };
    }

    private static CanonicalTypeIdentityLookup identity(
            Node completedType,
            String canonicalBlueId) {
        return new CanonicalTypeIdentityLookup() {
            @Override
            public boolean hasCompleteCoverage() {
                return true;
            }

            @Override
            public Optional<CanonicalTypeIdentityEvidence>
            findCanonicalTypeIdentityEvidence(Node requestedType) {
                return Optional.of(CanonicalTypeIdentityEvidence.identityOnly(
                        requireCanonicalTypeBlueId(requestedType)));
            }

            @Override
            public String requireCanonicalTypeBlueId(Node requestedType) {
                if (requestedType != completedType) {
                    throw new IllegalStateException(
                            "No test identity for completed type");
                }
                return canonicalBlueId;
            }
        };
    }

}
