package blue.language;

import blue.language.api.BlueCachePolicy;
import blue.language.api.BlueCacheStats;
import blue.language.api.BlueLanguageErrorCategory;
import blue.language.api.BlueLanguageErrorClassifier;
import blue.language.api.BlueLanguageRuntime;
import blue.language.api.BlueOperationLimits;
import blue.language.api.BlueOperationOutcome;
import blue.language.api.BlueOperationResult;
import blue.language.api.BlueViewPath;
import blue.language.api.LanguageRuntimeAccess;
import blue.language.api.WeightedLruCache;
import blue.language.provider.NodeProvider;

import blue.language.model.Node;
import blue.language.preprocess.Preprocessor;
import blue.language.preprocess.TransformationProcessor;
import blue.language.preprocess.TransformationProcessorProvider;
import blue.language.processor.registry.RuntimeTypeAliases;
import blue.language.provider.BootstrapProvider;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.NodeTransformer;
import blue.language.model.wire.BlueLanguageConstants;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Map;
import java.util.Optional;

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.model.wire.BlueLanguageConstants.*;
import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.*;

public class PreprocessorTest {

    @Test
    public void shouldPreprocessSupportedTypeForms() throws Exception {
        // given
        String doc = "a:\n" +
                     "  type: Integer\n" +
                     "b:\n" +
                     "  type:\n" +
                     "    value: Integer\n" +
                     "c:\n" +
                     "  type:\n" +
                     "    blueId: 84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH\n" +
                     "d:\n" +
                     "  type: Channel";

        Blue blue = new Blue();
        // when
        Node node = blue.preprocess(blue.yamlToNode(doc));

        // then
        assertEquals(CORE_TYPE_BLUE_ID_TO_NAME_MAP.get("Integer"), node.getProperties().get("a").getType().getName());
        assertEquals("Integer", node.getProperties().get("b").getType().getValue());
        assertEquals("84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH", node.getProperties().get("c").getType().getBlueId());
        assertEquals(RuntimeTypeAliases.AGGREGATE_NAME_TO_BLUE_ID.get("Channel"), node.getProperties().get("d").getType().getBlueId());

        assertFalse(node.getProperties().get("a").getType().isInlineValue());
        assertFalse(node.getProperties().get("b").getType().isInlineValue());
        assertFalse(node.getProperties().get("c").getType().isInlineValue());
        assertFalse(node.getProperties().get("d").getType().isInlineValue());
    }

    @Test
    public void shouldRejectBlueIdObjectAsItemsPayload() throws Exception {
        // given
        String doc = "name: Abc\n" +
                     "items:\n" +
                     "  blueId: 84ZWw2aoqB6dWRM6N1qWwgcXGrjfeKexTNdWxxAEcECH";

        // when
        Throwable failure = captureFailure(() -> new Blue().yamlToNode(doc));

        // then
        assertInstanceOf(RuntimeException.class, failure);
    }

    @Test
    public void shouldRunExplicitCustomTransformationBeforeMandatoryBaseline() throws Exception {
        // given
        String doc = "blue:\n" +
                     "  transformations:\n" +
                     "    - type:\n" +
                     "        blueId: " + TEXT_TYPE_BLUE_ID + "\n" +
                     "x:\n" +
                     "  type: Integer\n" +
                     "y: ABC";
        Node node = YAML_MAPPER.readValue(doc, Node.class);

        TransformationProcessor changeABCtoXYZ = document -> NodeTransformer.transform(document, docNode -> {
            Node result = docNode.clone();
            if (docNode.getValue() != null && "ABC".equals(docNode.getValue()))
                result.value("XYZ");
            return result;
        });
        TransformationProcessorProvider provider = transformation -> {
            if (transformation.getType() != null
                    && TEXT_TYPE_BLUE_ID.equals(
                    transformation.getType().getBlueId())) {
                return Optional.of(changeABCtoXYZ);
            }
            return Optional.empty();
        };
        Preprocessor preprocessor = new Preprocessor(provider, BootstrapProvider.INSTANCE);
        // when
        Node result = preprocessor.preprocess(node);

        // then
        assertEquals(BlueLanguageConstants.INTEGER_TYPE_BLUE_ID, result.getAsText("/x/type/blueId"));
        assertEquals("XYZ", result.getAsText("/y/value"));
        assertEquals(BlueLanguageConstants.TEXT_TYPE_BLUE_ID,
                result.getAsText("/y/type/blueId"));
    }

    @Test
    public void shouldApplyMandatoryBaselineWhenBlueIsOmittedDuringPreprocessing() {
        // given
        Node raw = YAML_MAPPER.readValue("x: 1", Node.class);

        // when
        Node result = new Preprocessor(BootstrapProvider.INSTANCE).preprocess(raw);

        // then
        assertEquals(INTEGER_TYPE_BLUE_ID, result.getAsText("/x/type/blueId"));
    }

    @Test
    public void shouldPreventBlueImportsFromRedefiningCanonicalCoreAliases() {
        // given
        Node raw = YAML_MAPPER.readValue(
                "blue:\n" +
                "  imports:\n" +
                "    Text:\n" +
                "      blueId: " + TEXT_TYPE_BLUE_ID + "\n" +
                "x:\n" +
                "  type: Text",
                Node.class);

        raw.getBlue().getProperties().get("imports")
                .getProperties().get("Text")
                .blueId(INTEGER_TYPE_BLUE_ID);

        // when
        Throwable error = captureFailure(
                () -> new Preprocessor(
                        BootstrapProvider.INSTANCE)
                        .preprocess(raw));

        // then
        assertInstanceOf(IllegalArgumentException.class, error);
        assertTrue(error.getMessage().contains(
                "cannot be rebound by blue.imports"));
    }

    @Test
    public void shouldPreserveTypeConsistencyAcrossMultiplePreprocessingPasses() throws Exception {
        // given
        String doc = "a:\n" +
                     "  type: Text\n" +
                     "b:\n" +
                     "  type:\n" +
                     "    blueId: " + TEXT_TYPE_BLUE_ID;

        Blue blue = new Blue();
        Node node = blue.yamlToNode(doc);

        // when
        Node preprocessedOnce = blue.preprocess(node);
        Node preprocessedTwice = blue.preprocess(preprocessedOnce);
        String aTypeBlueId = preprocessedTwice.getProperties().get("a").getType().getAsText("/blueId");
        String bTypeBlueId = preprocessedTwice.getProperties().get("b").getType().getAsText("/blueId");

        // then
        assertEquals(aTypeBlueId, bTypeBlueId);

        assertEquals(preprocessedOnce.getAsText("/blueId"), preprocessedTwice.getAsText("/blueId"));
    }

    @Test
    public void shouldPreserveProcessedAndRawNodeRepresentationsDuringDeserialization() throws Exception {
        // given
        String doc = "x: 1\n" +
                     "y:\n" +
                     "  value: 1\n" +
                     "z:\n" +
                     "  type: Integer\n" +
                     "  value: 1\n" +
                     "v:\n" +
                     "  type:\n" +
                     "    blueId: " + INTEGER_TYPE_BLUE_ID + "\n" +
                     "  value: 1";

        Blue blue = new Blue();
        Node expectedPreprocessed = new Node()
                .properties(
                        "x", new Node().type(new Node().blueId(INTEGER_TYPE_BLUE_ID).inlineValue(false)).value(BigInteger.ONE).inlineValue(true),
                        "y", new Node().type(new Node().blueId(INTEGER_TYPE_BLUE_ID).inlineValue(false)).value(BigInteger.ONE).inlineValue(false),
                        "z", new Node().type(new Node().blueId(INTEGER_TYPE_BLUE_ID).inlineValue(false)).value(BigInteger.ONE).inlineValue(false),
                        "v", new Node().type(new Node().blueId(INTEGER_TYPE_BLUE_ID).inlineValue(false)).value(BigInteger.ONE).inlineValue(false)
                )
                .inlineValue(false);
        Node expectedRaw = new Node()
                .properties(
                        "x", new Node().value(BigInteger.ONE).inlineValue(true),
                        "y", new Node().value(BigInteger.ONE).inlineValue(false),
                        "z", new Node().type(new Node().value("Integer").inlineValue(true)).value(BigInteger.ONE).inlineValue(false),
                        "v", new Node().type(new Node().blueId(INTEGER_TYPE_BLUE_ID).inlineValue(false)).value(BigInteger.ONE).inlineValue(false)
                )
                .inlineValue(false);

        // when
        Node preprocessedNode = blue.yamlToNode(doc);
        Node rawNode = YAML_MAPPER.readValue(doc, Node.class);

        // then
        assertNodesEqual(expectedPreprocessed, preprocessedNode);
        assertNodesEqual(expectedRaw, rawNode);
    }

    @Test
    public void shouldReplaceTypeAliasesAndRemoveBlueImports() {
        // given
        String personBlueId = BlueIdCalculator.calculateBlueId(new Node().value("PersonType"));
        String keyBlueId = BlueIdCalculator.calculateBlueId(new Node().value("KeyType"));
        String valueBlueId = BlueIdCalculator.calculateBlueId(new Node().value("ValueType"));
        String doc = "blue:\n" +
                     "  imports:\n" +
                     "    Person:\n" +
                     "      blueId: " + personBlueId + "\n" +
                     "    Key:\n" +
                     "      blueId: " + keyBlueId + "\n" +
                     "    Value:\n" +
                     "      blueId: " + valueBlueId + "\n" +
                     "person:\n" +
                     "  type: Person\n" +
                     "people:\n" +
                     "  type: List\n" +
                     "  itemType: Person\n" +
                     "dict:\n" +
                     "  type: Dictionary\n" +
                     "  keyType: Key\n" +
                     "  valueType: Value";

        // when
        Node node = new Blue().yamlToNode(doc);

        // then
        assertNull(node.getBlue());
        assertEquals(personBlueId, node.getAsText("/person/type/blueId"));
        assertEquals(personBlueId, node.getAsText("/people/itemType/blueId"));
        assertEquals(keyBlueId, node.getAsText("/dict/keyType/blueId"));
        assertEquals(valueBlueId, node.getAsText("/dict/valueType/blueId"));
    }

    @Test
    public void shouldRejectInvalidBlueImportShapes() {
        // given
        String personBlueId = BlueIdCalculator.calculateBlueId(new Node().value("PersonType"));
        String valueImport = "blue:\n" +
                "  imports:\n" +
                "    Person:\n" +
                "      value: x\n" +
                "x:\n" +
                "  type: Person";
        String defaultAliasOverride = "blue:\n" +
                "  imports:\n" +
                "    Text:\n" +
                "      blueId: " + personBlueId + "\n" +
                "x:\n" +
                "  type: Text";
        String duplicateImport = "blue:\n" +
                "  imports:\n" +
                "    Person:\n" +
                "      blueId: " + personBlueId + "\n" +
                "    Person:\n" +
                "      blueId: " + personBlueId + "\n" +
                "x:\n" +
                "  type: Person";
        String malformedBlueId = "blue:\n" +
                "  imports:\n" +
                "    Person:\n" +
                "      blueId: not-a-real-blueid\n" +
                "x:\n" +
                "  type: Person";
        String relativeCyclicBlueId = "blue:\n" +
                "  imports:\n" +
                "    Person:\n" +
                "      blueId: this#0\n" +
                "x:\n" +
                "  type: Person";
        String absoluteCyclicBlueId = "blue:\n" +
                "  imports:\n" +
                "    Person:\n" +
                "      blueId: " + personBlueId + "#0\n" +
                "x:\n" +
                "  type: Person";

        // when
        Throwable valueImportFailure = captureFailure(
                () -> new Blue().yamlToNode(valueImport));
        Throwable defaultAliasFailure = captureFailure(
                () -> new Blue().yamlToNode(defaultAliasOverride));
        Throwable duplicateImportFailure = captureFailure(
                () -> new Blue().yamlToNode(duplicateImport));
        Throwable malformedBlueIdFailure = captureFailure(
                () -> new Blue().yamlToNode(malformedBlueId));
        Throwable relativeCyclicFailure = captureFailure(
                () -> new Blue().yamlToNode(relativeCyclicBlueId));
        Throwable absoluteCyclicFailure = captureFailure(
                () -> new Blue().yamlToNode(absoluteCyclicBlueId));

        // then
        assertInstanceOf(RuntimeException.class, valueImportFailure);
        assertInstanceOf(RuntimeException.class, defaultAliasFailure);
        assertInstanceOf(RuntimeException.class, duplicateImportFailure);
        assertInstanceOf(RuntimeException.class, malformedBlueIdFailure);
        assertInstanceOf(RuntimeException.class, relativeCyclicFailure);
        assertInstanceOf(RuntimeException.class, absoluteCyclicFailure);
    }

    @Test
    public void shouldPreserveOtherBlueTransformsWhenProcessingImports() {
        // given
        String personBlueId = BlueIdCalculator.calculateBlueId(new Node().value("PersonType"));
        String doc = "blue:\n" +
                     "  imports:\n" +
                     "    Person:\n" +
                     "      blueId: " + personBlueId + "\n" +
                     "  transformations:\n" +
                     "    - type:\n" +
                     "        blueId: " + TEXT_TYPE_BLUE_ID + "\n" +
                     "x:\n" +
                     "  type: Person\n" +
                     "y: ABC";
        Node node = YAML_MAPPER.readValue(doc, Node.class);

        TransformationProcessor changeABCtoXYZ = document -> NodeTransformer.transform(document, docNode -> {
            Node result = docNode.clone();
            if ("ABC".equals(docNode.getValue())) {
                result.value("XYZ");
            }
            return result;
        });
        TransformationProcessorProvider provider = transformation -> {
            if (transformation.getType() != null
                    && TEXT_TYPE_BLUE_ID.equals(
                    transformation.getType().getBlueId())) {
                return Optional.of(changeABCtoXYZ);
            }
            return Optional.empty();
        };

        // when
        Node result = new Preprocessor(provider, BootstrapProvider.INSTANCE).preprocess(node);

        // then
        assertEquals(personBlueId, result.getAsText("/x/type/blueId"));
        assertEquals("XYZ", result.getAsText("/y/value"));
        assertNull(result.getBlue());
    }

    private void assertNodesEqual(Node expected, Node actual) {
        assertEquals(expected.isInlineValue(), actual.isInlineValue());
        assertEquals(expected.getValue(), actual.getValue());

        if (expected.getType() != null) {
            assertNotNull(actual.getType());
            assertNodesEqual(expected.getType(), actual.getType());
        } else {
            assertNull(actual.getType());
        }

        if (expected.getProperties() != null) {
            assertNotNull(actual.getProperties());
            assertEquals(expected.getProperties().size(), actual.getProperties().size());
            for (Map.Entry<String, Node> entry : expected.getProperties().entrySet()) {
                assertTrue(actual.getProperties().containsKey(entry.getKey()));
                assertNodesEqual(entry.getValue(), actual.getProperties().get(entry.getKey()));
            }
        } else {
            assertNull(actual.getProperties());
        }

        assertEquals(expected.getBlueId(), actual.getBlueId());
    }

}
