package blue.language;

import blue.language.merge.Merger;
import blue.language.merge.MergingProcessor;
import blue.language.model.Node;
import blue.language.merge.processor.DictionaryProcessor;
import blue.language.merge.processor.SequentialMergingProcessor;
import blue.language.merge.processor.TypeAssigner;
import blue.language.provider.BasicNodeProvider;
import blue.language.utils.NodeExtender;
import blue.language.utils.limits.Limits;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static blue.language.utils.BlueIdCalculator.calculateBlueId;
import static blue.language.utils.Properties.*;
import static org.junit.jupiter.api.Assertions.*;

public class DictionaryProcessorTest {

    @Test
    public void testKeyTypeAndValueTypeAssignment() {
        Node dictA = new Node().name("DictA")
                .type("Dictionary")
                .keyType("Text")
                .valueType("Integer");
        Node dictB = new Node().name("DictB")
                .type(new Node().blueId(calculateBlueId(dictA)));

        BasicNodeProvider nodeProvider = new BasicNodeProvider(Arrays.asList(dictA, dictB));
        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new DictionaryProcessor()
                )
        );

        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node dictANode = nodeProvider.findNodeByName("DictA").orElseThrow(() -> new IllegalStateException("No \"DictA\" available for NodeProvider."));
        Node result = merger.resolve(dictANode, Limits.NO_LIMITS);

        assertEquals("Text", CORE_TYPE_BLUE_ID_TO_NAME_MAP.get(result.getKeyType().getBlueId()));
        assertEquals("Integer", CORE_TYPE_BLUE_ID_TO_NAME_MAP.get(result.getValueType().getBlueId()));
    }

    @Test
    public void testDictionaryWithValidTypes() throws Exception {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String a = "name: A";
        nodeProvider.addSingleDocs(a);

        String b = "name: B\n" +
                   "type:\n" +
                   "  blueId: " + nodeProvider.getBlueIdByName("A");
        nodeProvider.addSingleDocs(b);

        String dictOfAToB = "name: DictOfAToB\n" +
                            "type: Dictionary\n" +
                            "keyType: " + TEXT_TYPE + "\n" +
                            "valueType: \n" +
                            "  blueId: " + nodeProvider.getBlueIdByName("A") + "\n" +
                            "key1:\n" +
                            "  type:\n" +
                            "    blueId: " + nodeProvider.getBlueIdByName("A") + "\n" +
                            "key2:\n" +
                            "  type:\n" +
                            "    blueId: " + nodeProvider.getBlueIdByName("B");
        nodeProvider.addSingleDocs(dictOfAToB);

        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new TypeAssigner(),
                        new DictionaryProcessor()
                )
        );

        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node dictOfAToBNode = nodeProvider.getNodeByName("DictOfAToB");
        new NodeExtender(nodeProvider).extend(dictOfAToBNode, Limits.NO_LIMITS);
        Node result = merger.resolve(dictOfAToBNode);

        assertEquals("Text", CORE_TYPE_BLUE_ID_TO_NAME_MAP.get(result.getKeyType().getBlueId()));
        assertEquals("A", result.getValueType().getName());
        assertEquals(2, result.getProperties().size());
        assertEquals("A", result.getProperties().get("key1").getType().getName());
        assertEquals("B", result.getProperties().get("key2").getType().getName());
    }

    @Test
    public void testDictionaryWithInvalidKeyType() throws Exception {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String dictWithInvalidKeyType = "name: DictWithInvalidKeyType\n" +
                                        "type: Dictionary\n" +
                                        "keyType: " + DICTIONARY_TYPE + "\n" +
                                        "valueType: " + TEXT_TYPE;
        nodeProvider.addSingleDocs(dictWithInvalidKeyType);

        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new TypeAssigner(),
                        new DictionaryProcessor()
                )
        );

        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node dictNode = nodeProvider.findNodeByName("DictWithInvalidKeyType").orElseThrow(() -> new IllegalStateException("No \"DictWithInvalidKeyType\" available for NodeProvider."));
        new NodeExtender(nodeProvider).extend(dictNode, Limits.NO_LIMITS);

        assertThrows(IllegalArgumentException.class, () -> merger.resolve(dictNode));
    }

    @Test
    public void testDictionaryWithInvalidValueType() throws Exception {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String a = "name: A";
        nodeProvider.addSingleDocs(a);

        String dictWithInvalidValue = "name: DictWithInvalidValue\n" +
                                      "type: Dictionary\n" +
                                      "keyType: " + TEXT_TYPE + "\n" +
                                      "valueType: \n" +
                                      "  blueId: " + nodeProvider.getBlueIdByName("A") + "\n" +
                                      "key1:\n" +
                                      "  type: " + TEXT_TYPE;  // This should cause an error
        nodeProvider.addSingleDocs(dictWithInvalidValue);

        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new TypeAssigner(),
                        new DictionaryProcessor()
                )
        );

        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node dictNode = nodeProvider.findNodeByName("DictWithInvalidValue").orElseThrow(() -> new IllegalStateException("No \"DictWithInvalidValue\" available for NodeProvider."));
        new NodeExtender(nodeProvider).extend(dictNode, Limits.NO_LIMITS);

        assertThrows(IllegalArgumentException.class, () -> merger.resolve(dictNode));
    }

    @Test
    public void testNonDictionaryTypeWithKeyTypeOrValueType() throws Exception {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();

        String nonDictWithKeyType = "name: NonDictWithKeyType\n" +
                                    "type: " + TEXT_TYPE + "\n" +
                                    "keyType: " + TEXT_TYPE;
        nodeProvider.addSingleDocs(nonDictWithKeyType);

        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new TypeAssigner(),
                        new DictionaryProcessor()
                )
        );

        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node nonDictNode = nodeProvider.findNodeByName("NonDictWithKeyType").orElseThrow(() -> new IllegalStateException("No \"NonDictWithKeyType\" available for NodeProvider."));
        new NodeExtender(nodeProvider).extend(nonDictNode, Limits.NO_LIMITS);

        assertThrows(IllegalArgumentException.class, () -> merger.resolve(nonDictNode));
    }

}