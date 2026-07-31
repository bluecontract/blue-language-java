package blue.language;

import blue.language.merge.Merger;
import blue.language.merge.MergingProcessor;
import blue.language.model.Node;
import blue.language.merge.processor.ListProcessor;
import blue.language.merge.processor.SequentialMergingProcessor;
import blue.language.merge.processor.TypeAssigner;
import blue.language.provider.BasicNodeProvider;
import blue.language.utils.NodeExpander;
import blue.language.utils.Properties;
import blue.language.utils.limits.Limits;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static blue.language.utils.BlueIdCalculator.calculateBlueId;
import static blue.language.utils.Properties.CORE_TYPE_BLUE_ID_TO_NAME_MAP;
import static org.junit.jupiter.api.Assertions.*;

public class ListProcessorTest {

    @Test
    public void shouldAssignDeclaredItemType() {
        // given
        Node listA = new Node().name("ListA")
                .type("List")
                .itemType("Integer");
        Node listB = new Node().name("ListB")
                .type(new Node().blueId(new Blue().calculateSemanticBlueId(listA)));

        List<Node> nodes = Arrays.asList(listA, listB);
        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new ListProcessor()
                )
        );

        BasicNodeProvider nodeProvider = new BasicNodeProvider(nodes);
        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node listANode = nodeProvider.findNodeByName("ListA").orElseThrow(() -> new IllegalStateException("No \"ListA\" available for NodeProvider."));
        // when
        Node result = merger.resolve(listANode, Limits.NO_LIMITS);

        // then
        assertEquals("Integer", CORE_TYPE_BLUE_ID_TO_NAME_MAP.get(result.getItemType().getBlueId()));
    }

    @Test
    public void shouldAcceptListWithValidItemTypes() throws Exception {

        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String a = "name: A";
        nodeProvider.addSingleDocs(a);

        String b = "name: B\n" +
                   "type:\n" +
                   "  blueId: " + nodeProvider.getBlueIdByName("A");
        nodeProvider.addSingleDocs(b);

        String c = "name: C\n" +
                   "type:\n" +
                   "  blueId: " + nodeProvider.getBlueIdByName("B");
        nodeProvider.addSingleDocs(c);

        String listOfB = "name: ListOfB\n" +
                         "type:\n" +
                         "  blueId: " + Properties.LIST_TYPE_BLUE_ID + "\n" +
                         "itemType:\n" +
                         "  blueId: " + nodeProvider.getBlueIdByName("B") + "\n" +
                         "items:\n" +
                         "  - type:\n" +
                         "      blueId: " + nodeProvider.getBlueIdByName("B") + "\n" +
                         "  - type:\n" +
                         "      blueId: " + nodeProvider.getBlueIdByName("C");
        nodeProvider.addSingleDocs(listOfB);

        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new TypeAssigner(),
                        new ListProcessor()
                )
        );

        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node listOfBNode = nodeProvider.getNodeByName("ListOfB");
        new NodeExpander(nodeProvider).expand(listOfBNode, Limits.NO_LIMITS);
        // when
        Node result = merger.resolve(listOfBNode);

        // then
        assertEquals("B", result.getItemType().getName());
        assertEquals(2, result.getItems().size());
        assertEquals("B", result.getItems().get(0).getType().getName());
        assertEquals("C", result.getItems().get(1).getType().getName());
    }

    @Test
    public void shouldRejectListWithInvalidItemType() throws Exception {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String a = "name: A";
        nodeProvider.addSingleDocs(a);

        String b = "name: B\n" +
                   "type:\n" +
                   "  blueId: " + nodeProvider.getBlueIdByName("A");
        nodeProvider.addSingleDocs(b);

        String listOfB = "name: ListOfB\n" +
                         "type: List\n" +
                         "itemType:\n" +
                         "  blueId: " + nodeProvider.getBlueIdByName("B") + "\n" +
                         "items:\n" +
                         "  - type:\n" +
                         "      blueId: " + nodeProvider.getBlueIdByName("B") + "\n" +
                         "  - type:\n" +
                         "      blueId: " + nodeProvider.getBlueIdByName("A");  // This should cause an error
        nodeProvider.addSingleDocs(listOfB);

        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new TypeAssigner(),
                        new ListProcessor()
                )
        );

        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node listOfBNode = nodeProvider.findNodeByName("ListOfB").orElseThrow(() -> new IllegalStateException("No \"ListOfB\" available for NodeProvider."));
        // when
        new NodeExpander(nodeProvider).expand(listOfBNode, Limits.NO_LIMITS);

        // then
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(listOfBNode));
    }

    @Test
    public void shouldResolveInheritedListItems() throws Exception {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String a = "name: A";
        nodeProvider.addSingleDocs(a);

        String b = "name: B\n" +
                   "type:\n" +
                   "  blueId: " + nodeProvider.getBlueIdByName("A");
        nodeProvider.addSingleDocs(b);

        String c = "name: C\n" +
                   "type:\n" +
                   "  blueId: " + nodeProvider.getBlueIdByName("B");
        nodeProvider.addSingleDocs(c);

        String listOfB = "name: ListOfB\n" +
                         "type:\n" +
                         "  blueId: " + Properties.LIST_TYPE_BLUE_ID + "\n" +
                         "itemType:\n" +
                         "  blueId: " + nodeProvider.getBlueIdByName("B");
        nodeProvider.addSingleDocs(listOfB);

        String inheritedList = "name: InheritedList\n" +
                               "type:\n" +
                               "  blueId: " + nodeProvider.getBlueIdByName("ListOfB") + "\n" +
                               "items:\n" +
                               "  - type:\n" +
                               "      blueId: " + nodeProvider.getBlueIdByName("B") + "\n" +
                               "  - type:\n" +
                               "      blueId: " + nodeProvider.getBlueIdByName("C");
        nodeProvider.addSingleDocs(inheritedList);

        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new TypeAssigner(),
                        new ListProcessor()
                )
        );

        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node inheritedListNode = nodeProvider.findNodeByName("InheritedList").orElseThrow(() -> new IllegalStateException("No \"InheritedList\" available for NodeProvider."));
        new NodeExpander(nodeProvider).expand(inheritedListNode, Limits.NO_LIMITS);
        // when
        Node result = merger.resolve(inheritedListNode);

        // then
        assertEquals("B", result.getItemType().getName());
        assertEquals(2, result.getItems().size());
        assertEquals("B", result.getItems().get(0).getType().getName());
        assertEquals("C", result.getItems().get(1).getType().getName());
    }

    @Test
    public void shouldRejectInheritedListWithInvalidItemType() throws Exception {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String a = "name: A";
        nodeProvider.addSingleDocs(a);

        String b = "name: B\n" +
                   "type:\n" +
                   "  blueId: " + nodeProvider.getBlueIdByName("A");
        nodeProvider.addSingleDocs(b);

        String listOfB = "name: ListOfB\n" +
                         "type:\n" +
                         "  blueId: " + Properties.LIST_TYPE_BLUE_ID + "\n" +
                         "itemType:\n" +
                         "  blueId: " + nodeProvider.getBlueIdByName("B");
        nodeProvider.addSingleDocs(listOfB);

        String inheritedList = "name: InheritedList\n" +
                               "type:\n" +
                               "  blueId: " + nodeProvider.getBlueIdByName("ListOfB") + "\n" +
                               "items:\n" +
                               "  - type:\n" +
                               "      blueId: " + nodeProvider.getBlueIdByName("B") + "\n" +
                               "  - type:\n" +
                               "      blueId: " + nodeProvider.getBlueIdByName("A");  // This should cause an error
        nodeProvider.addSingleDocs(inheritedList);

        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new TypeAssigner(),
                        new ListProcessor()
                )
        );

        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node inheritedListNode = nodeProvider.findNodeByName("InheritedList").orElseThrow(() -> new IllegalStateException("No \"InheritedList\" available for NodeProvider."));
        // when
        new NodeExpander(nodeProvider).expand(inheritedListNode, Limits.NO_LIMITS);

        // then
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(inheritedListNode));
    }

    @Test
    public void shouldPreserveItemsWhenListHasNoItemType() throws Exception {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String a = "name: A";
        nodeProvider.addSingleDocs(a);

        String listWithNoItemType = "name: ListWithNoItemType\n" +
                                    "type:\n" +
                                    "  blueId: " + Properties.LIST_TYPE_BLUE_ID + "\n" +
                                    "items:\n" +
                                    "  - type:\n" +
                                    "      blueId: " + nodeProvider.getBlueIdByName("A");
        nodeProvider.addSingleDocs(listWithNoItemType);

        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new TypeAssigner(),
                        new ListProcessor()
                )
        );

        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node listNode = nodeProvider.findNodeByName("ListWithNoItemType").orElseThrow(() -> new IllegalStateException("No \"ListWithNoItemType\" available for NodeProvider."));
        new NodeExpander(nodeProvider).expand(listNode, Limits.NO_LIMITS);
        // when
        Node result = merger.resolve(listNode);

        // then
        assertNull(result.getItemType());
        assertEquals(1, result.getItems().size());
        assertEquals("A", result.getItems().get(0).getType().getName());
    }

    @Test
    public void shouldRejectItemTypeOnNonListType() throws Exception {
        // given
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String a = "name: A";
        nodeProvider.addSingleDocs(a);

        String nonListWithItemType = "name: NonListWithItemType\n" +
                                     "type:\n" +
                                     "  blueId: " + nodeProvider.getBlueIdByName("A") + "\n" +
                                     "itemType:\n" +
                                     "  blueId: " + nodeProvider.getBlueIdByName("A");
        nodeProvider.addSingleDocs(nonListWithItemType);

        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new TypeAssigner(),
                        new ListProcessor()
                )
        );

        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node nonListNode = nodeProvider.findNodeByName("NonListWithItemType").orElseThrow(() -> new IllegalStateException("No \"NonListWithItemType\" available for NodeProvider."));
        // when
        new NodeExpander(nodeProvider).expand(nonListNode, Limits.NO_LIMITS);

        // then
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(nonListNode));
    }
}
