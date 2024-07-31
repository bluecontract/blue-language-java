package blue.language;

import blue.language.merge.Merger;
import blue.language.merge.MergingProcessor;
import blue.language.model.Node;
import blue.language.merge.processor.ListProcessor;
import blue.language.merge.processor.SequentialMergingProcessor;
import blue.language.merge.processor.TypeAssigner;
import blue.language.provider.BasicNodeProvider;
import blue.language.utils.NodeExtender;
import blue.language.utils.limits.Limits;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static blue.language.utils.BlueIdCalculator.calculateBlueId;
import static blue.language.utils.Properties.CORE_TYPE_BLUE_ID_TO_NAME_MAP;
import static org.junit.jupiter.api.Assertions.*;

public class ListProcessorTest {

    @Test
    public void testItemTypeAssignment() {
        Node listA = new Node().name("ListA")
                .type("List")
                .itemType("Integer");
        Node listB = new Node().name("ListB")
                .type(new Node().blueId(calculateBlueId(listA)));

        List<Node> nodes = Arrays.asList(listA, listB);
        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new ListProcessor()
                )
        );

        BasicNodeProvider nodeProvider = new BasicNodeProvider(nodes);
        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node listANode = nodeProvider.findNodeByName("ListA").orElseThrow(() -> new IllegalStateException("No \"ListA\" available for NodeProvider."));
        Node result = merger.resolve(listANode, Limits.NO_LIMITS);

        assertEquals("Integer", CORE_TYPE_BLUE_ID_TO_NAME_MAP.get(result.getItemType().getBlueId()));
    }

    @Test
    public void testListWithValidItemTypes() throws Exception {

        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String a = "name: A";
        nodeProvider.addSingleDocs(a);

        String b = "name: B\n" +
                   "type: " + nodeProvider.getBlueIdByName("A");
        nodeProvider.addSingleDocs(b);

        String c = "name: C\n" +
                   "type: " + nodeProvider.getBlueIdByName("B");
        nodeProvider.addSingleDocs(c);

        String listOfB = "name: ListOfB\n" +
                         "type: List\n" +
                         "itemType: " +  nodeProvider.getBlueIdByName("B") + "\n" +
                         "items:\n" +
                         "  - type: " + nodeProvider.getBlueIdByName("B") + "\n" +
                         "  - type: " + nodeProvider.getBlueIdByName("C");
        nodeProvider.addSingleDocs(listOfB);

        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new TypeAssigner(),
                        new ListProcessor()
                )
        );

        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node listOfBNode = nodeProvider.getNodeByName("ListOfB");
        new NodeExtender(nodeProvider).extend(listOfBNode, Limits.NO_LIMITS);
        Node result = merger.resolve(listOfBNode);

        assertEquals("B", result.getItemType().getName());
        assertEquals(2, result.getItems().size());
        assertEquals("B", result.getItems().get(0).getType().getName());
        assertEquals("C", result.getItems().get(1).getType().getName());
    }

    @Test
    public void testListWithInvalidItemType() throws Exception {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String a = "name: A";
        nodeProvider.addSingleDocs(a);

        String b = "name: B\n" +
                   "type: " + nodeProvider.getBlueIdByName("A");
        nodeProvider.addSingleDocs(b);

        String listOfB = "name: ListOfB\n" +
                         "type: List\n" +
                         "itemType: " +  nodeProvider.getBlueIdByName("B") + "\n" +
                         "items:\n" +
                         "  - type: " + nodeProvider.getBlueIdByName("B") + "\n" +
                         "  - type: " + nodeProvider.getBlueIdByName("A");  // This should cause an error
        nodeProvider.addSingleDocs(listOfB);

        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new TypeAssigner(),
                        new ListProcessor()
                )
        );

        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node listOfBNode = nodeProvider.findNodeByName("ListOfB").orElseThrow(() -> new IllegalStateException("No \"ListOfB\" available for NodeProvider."));
        new NodeExtender(nodeProvider).extend(listOfBNode, Limits.NO_LIMITS);

        assertThrows(IllegalArgumentException.class, () -> merger.resolve(listOfBNode));
    }

    @Test
    public void testInheritedList() throws Exception {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String a = "name: A";
        nodeProvider.addSingleDocs(a);

        String b = "name: B\n" +
                   "type: " + nodeProvider.getBlueIdByName("A");
        nodeProvider.addSingleDocs(b);

        String c = "name: C\n" +
                   "type: " + nodeProvider.getBlueIdByName("B");
        nodeProvider.addSingleDocs(c);

        String listOfB = "name: ListOfB\n" +
                         "type: List\n" +
                         "itemType: " +  nodeProvider.getBlueIdByName("B");
        nodeProvider.addSingleDocs(listOfB);

        String inheritedList = "name: InheritedList\n" +
                               "type: " + nodeProvider.getBlueIdByName("ListOfB") + "\n" +
                               "items:\n" +
                               "  - type: " + nodeProvider.getBlueIdByName("B") + "\n" +
                               "  - type: " + nodeProvider.getBlueIdByName("C");
        nodeProvider.addSingleDocs(inheritedList);

        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new TypeAssigner(),
                        new ListProcessor()
                )
        );

        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node inheritedListNode = nodeProvider.findNodeByName("InheritedList").orElseThrow(() -> new IllegalStateException("No \"InheritedList\" available for NodeProvider."));
        new NodeExtender(nodeProvider).extend(inheritedListNode, Limits.NO_LIMITS);
        Node result = merger.resolve(inheritedListNode);

        assertEquals("B", result.getItemType().getName());
        assertEquals(2, result.getItems().size());
        assertEquals("B", result.getItems().get(0).getType().getName());
        assertEquals("C", result.getItems().get(1).getType().getName());
    }

    @Test
    public void testInheritedListWithInvalidItemType() throws Exception {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String a = "name: A";
        nodeProvider.addSingleDocs(a);

        String b = "name: B\n" +
                   "type: " + nodeProvider.getBlueIdByName("A");
        nodeProvider.addSingleDocs(b);

        String listOfB = "name: ListOfB\n" +
                         "type: List\n" +
                         "itemType: " +  nodeProvider.getBlueIdByName("B");
        nodeProvider.addSingleDocs(listOfB);

        String inheritedList = "name: InheritedList\n" +
                               "type: " + nodeProvider.getBlueIdByName("ListOfB") + "\n" +
                               "items:\n" +
                               "  - type: " + nodeProvider.getBlueIdByName("B") + "\n" +
                               "  - type: " + nodeProvider.getBlueIdByName("A");  // This should cause an error
        nodeProvider.addSingleDocs(inheritedList);

        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new TypeAssigner(),
                        new ListProcessor()
                )
        );

        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node inheritedListNode = nodeProvider.findNodeByName("InheritedList").orElseThrow(() -> new IllegalStateException("No \"InheritedList\" available for NodeProvider."));
        new NodeExtender(nodeProvider).extend(inheritedListNode, Limits.NO_LIMITS);

        assertThrows(IllegalArgumentException.class, () -> merger.resolve(inheritedListNode));
    }

    @Test
    public void testListWithNoItemType() throws Exception {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String a = "name: A";
        nodeProvider.addSingleDocs(a);

        String listWithNoItemType = "name: ListWithNoItemType\n" +
                                    "type: List\n" +
                                    "items:\n" +
                                    "  - type: " + nodeProvider.getBlueIdByName("A");
        nodeProvider.addSingleDocs(listWithNoItemType);

        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new TypeAssigner(),
                        new ListProcessor()
                )
        );

        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node listNode = nodeProvider.findNodeByName("ListWithNoItemType").orElseThrow(() -> new IllegalStateException("No \"ListWithNoItemType\" available for NodeProvider."));
        new NodeExtender(nodeProvider).extend(listNode, Limits.NO_LIMITS);
        Node result = merger.resolve(listNode);

        assertNull(result.getItemType());
        assertEquals(1, result.getItems().size());
        assertEquals("A", result.getItems().get(0).getType().getName());
    }

    @Test
    public void testNonListTypeWithItemType() throws Exception {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String a = "name: A";
        nodeProvider.addSingleDocs(a);

        String nonListWithItemType = "name: NonListWithItemType\n" +
                                     "type: " + nodeProvider.getBlueIdByName("A") + "\n" +
                                     "itemType: " + nodeProvider.getBlueIdByName("A");
        nodeProvider.addSingleDocs(nonListWithItemType);

        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new TypeAssigner(),
                        new ListProcessor()
                )
        );

        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node nonListNode = nodeProvider.findNodeByName("NonListWithItemType").orElseThrow(() -> new IllegalStateException("No \"NonListWithItemType\" available for NodeProvider."));
        new NodeExtender(nodeProvider).extend(nonListNode, Limits.NO_LIMITS);

        assertThrows(IllegalArgumentException.class, () -> merger.resolve(nonListNode));
    }
}