package blue.language.provider;

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

import blue.language.model.Node;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static blue.language.TestUtils.useNodeNameAsBlueIdProvider;
import static blue.language.provider.Types.isSubtype;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

        // then
        assertTrue(isSubtype(b, a, nodeProvider));
        assertTrue(isSubtype(c, a, nodeProvider));
        assertTrue(isSubtype(a, a, nodeProvider));
        assertTrue(isSubtype(b, b, nodeProvider));
        assertTrue(isSubtype(b, c, nodeProvider));

    }

    @Test
    public void shouldIgnoreNameAndDescriptionButNotStructureForSubtypeCompatibility() {
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

        // then
        assertTrue(isSubtype(left, right, nodeProvider));
        assertTrue(isSubtype(right, left, nodeProvider));
        assertFalse(isSubtype(left, differentStructure, nodeProvider));
    }

    @Test
    public void shouldRecognizeEquivalentInlineAndReferencedSubtypeVariations() throws Exception {

        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String person = "name: Person\n" +
                        "surname:\n" +
                        "  type: Text\n" +
                        "age:\n" +
                        "  type: Integer";
        nodeProvider.addSingleDocs(person);

        String alice = "name: Alice\n" +
                       "type:\n" +
                       "  blueId: " + nodeProvider.getBlueIdByName("Person");

        String alice2 = "name: Alice2\n" +
                        "type:\n" +
                        "  name: Person\n" +
                        "  surname:\n" +
                        "    type: Text\n" +
                        "  age:\n" +
                        "    type: Integer";

        String alice3 = "name: Alice3\n" +
                        "type:\n" +
                        "  name: Person\n" +
                        "  surname:\n" +
                        "    type: Text\n" +
                        "  age:\n" +
                        "    type: Integer";
        // when
        nodeProvider.addSingleDocs(alice, alice2, alice3);

        // then
        assertTrue(isSubtype(nodeProvider.getNodeByName("Alice"), nodeProvider.getNodeByName("Alice"), nodeProvider));
        assertFalse(isSubtype(nodeProvider.getNodeByName("Person"), nodeProvider.getNodeByName("Alice"), nodeProvider));

        assertTrue(isSubtype(nodeProvider.getNodeByName("Alice"), nodeProvider.getNodeByName("Person"), nodeProvider));
        assertTrue(isSubtype(nodeProvider.getNodeByName("Alice2"), nodeProvider.getNodeByName("Person"), nodeProvider));
        assertTrue(isSubtype(nodeProvider.getNodeByName("Alice3"), nodeProvider.getNodeByName("Person"), nodeProvider));
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
                nodeProvider.getNodeByName("A"),
                nodeProvider));
    }

}
