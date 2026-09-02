package blue.language;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.identity.CanonicalTypeIdentityLookup;
import blue.language.runtime.LanguageRuntimeAccess;
import blue.language.provider.NodeProvider;

import blue.language.merge.Merger;
import blue.language.merge.MergingProcessor;
import blue.language.model.Node;
import blue.language.merge.processor.DictionaryProcessor;
import blue.language.merge.processor.SequentialMergingProcessor;
import blue.language.merge.processor.TypeAssigner;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.graph.NodeExpander;
import blue.language.resolve.ResolutionLimits;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.identity.DirectBlueIdCalculator.calculateBlueId;
import static blue.language.model.wire.BlueLanguageConstants.*;
import static org.junit.jupiter.api.Assertions.*;

public class DictionaryProcessorTest {

    @Test
    public void shouldAssignDictionaryKeyAndValueTypes() {
        // given
        Node dictA = new Node().name("DictA")
                .type("Dictionary")
                .keyType("Text")
                .valueType("Integer");
        Node dictB = new Node().name("DictB")
                .type(new Node().blueId(new Blue().calculateSourceDocumentBlueId(dictA)));

        BasicNodeProvider nodeProvider = new BasicNodeProvider(Arrays.asList(dictA, dictB));
        MergingProcessor mergingProcessor = new SequentialMergingProcessor(
                Arrays.asList(
                        new DictionaryProcessor()
                )
        );

        Merger merger = new Merger(mergingProcessor, nodeProvider);
        Node dictANode = nodeProvider.findNodeByName("DictA").orElseThrow(() -> new IllegalStateException("No \"DictA\" available for NodeProvider."));
        // when
        Node result = merger.resolve(dictANode, ResolutionLimits.NO_LIMITS);

        // then
        assertEquals("Text", CORE_TYPE_BLUE_ID_TO_NAME_MAP.get(result.getKeyType().getBlueId()));
        assertEquals("Integer", CORE_TYPE_BLUE_ID_TO_NAME_MAP.get(result.getValueType().getBlueId()));
    }

    @Test
    public void shouldResolveDictionaryWithValidKeyAndValueTypes() throws Exception {
        // given
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
        // when
        Node result = merger.resolve(dictOfAToBNode);

        // then
        assertEquals("Text", CORE_TYPE_BLUE_ID_TO_NAME_MAP.get(result.getKeyType().getBlueId()));
        assertEquals("A", result.getValueType().getName());
        assertEquals(2, result.getProperties().size());
        assertEquals("A", result.getProperties().get("key1").getType().getName());
        assertEquals("B", result.getProperties().get("key2").getType().getName());
    }

    @Test
    public void shouldRejectDictionaryWithInvalidKeyType() throws Exception {
        // given
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
        // when
        new NodeExpander(nodeProvider).expand(dictNode, ResolutionLimits.NO_LIMITS);

        // then
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(dictNode));
    }

    @Test
    public void shouldRejectDictionaryWithInvalidValueType() throws Exception {
        // given
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
        // when
        new NodeExpander(nodeProvider).expand(dictNode, ResolutionLimits.NO_LIMITS);

        // then
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(dictNode));
    }

    @Test
    public void shouldRejectDictionaryTypeFieldsOnNonDictionaryNode() throws Exception {
        // given
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
        // when
        new NodeExpander(nodeProvider).expand(nonDictNode, ResolutionLimits.NO_LIMITS);

        // then
        assertThrows(IllegalArgumentException.class, () -> merger.resolve(nonDictNode));
    }

    @Test
    void shouldValidateTypelessOverlayAgainstInheritedDictionaryType() {
        // given
        BasicNodeProvider nodeProvider =
                new BasicNodeProvider();
        Node target =
                new Node()
                        .type(new Node().blueId(
                                DICTIONARY_TYPE_BLUE_ID))
                        .keyType(new Node().blueId(
                                TEXT_TYPE_BLUE_ID))
                        .valueType(new Node().blueId(
                                INTEGER_TYPE_BLUE_ID));
        Node source =
                new Node()
                        .keyType(new Node().blueId(
                                TEXT_TYPE_BLUE_ID))
                        .valueType(new Node().blueId(
                                INTEGER_TYPE_BLUE_ID))
                        .properties(
                                "answer",
                                new Node()
                                        .type(new Node().blueId(
                                                INTEGER_TYPE_BLUE_ID))
                                        .value(42));
        DictionaryProcessor processor =
                new DictionaryProcessor();

        // when
        processor.process(
                target,
                source,
                nodeProvider,
                null,
                CanonicalTypeIdentityLookup.incomplete());

        // then
        assertEquals(
                DICTIONARY_TYPE_BLUE_ID,
                target.getType().getBlueId());
        assertEquals(
                INTEGER_TYPE_BLUE_ID,
                target.getValueType().getBlueId());
    }

    @Test
    void shouldRejectExplicitNonDictionaryTypeDespiteInheritedDictionaryTarget() {
        // given
        BasicNodeProvider nodeProvider =
                new BasicNodeProvider();
        Node target =
                new Node().type(
                        new Node().blueId(
                                DICTIONARY_TYPE_BLUE_ID));
        Node source =
                new Node()
                        .type(new Node().blueId(
                                TEXT_TYPE_BLUE_ID))
                        .keyType(new Node().blueId(
                                TEXT_TYPE_BLUE_ID));
        DictionaryProcessor processor =
                new DictionaryProcessor();

        // when
        Throwable failure =
                captureFailure(
                        () -> processor.process(
                                target,
                                source,
                                nodeProvider,
                                null,
                                CanonicalTypeIdentityLookup.incomplete()));

        // then
        assertInstanceOf(
                IllegalArgumentException.class,
                failure);
        assertEquals(
                "Source node with keyType or valueType must have a Dictionary type",
                failure.getMessage());
    }

}
