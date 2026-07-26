package blue.language.snapshot;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.processor.util.NodeCanonicalizer;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.NodeToBlueIdInput;
import blue.language.utils.Nodes;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.erdtman.jcs.JsonCanonicalizer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.utils.Properties.*;
import static blue.language.utils.UncheckedObjectMapper.JSON_MAPPER;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrozenCanonicalDigesterTest {

    @Test
    void directIdentitySizingAcceptsTheCanonicalEmptyListPlaceholder() {
        Node list = new Node().items(
                Nodes.emptyPlaceholder());

        assertTrue(NodeCanonicalizer
                .directIdentityCanonicalSize(list) > 0L);
    }

    @Test
    void streamingWriterMatchesGenericJcsForRepresentativeFrozenInputs() throws Exception {
        List<Node> cases = representativeNodes();
        for (int index = 0; index < cases.size(); index++) {
            FrozenNode frozen = FrozenNode.fromNode(cases.get(index));
            byte[] json = JSON_MAPPER.writeValueAsBytes(FrozenNodeToBlueIdInput.get(frozen));
            byte[] expected = new JsonCanonicalizer(json).getEncodedUTF8();
            ByteArraySink sink = new ByteArraySink();

            FrozenCanonicalWriter.write(frozen, sink);

            assertArrayEquals(expected, sink.bytes(), "canonical bytes at case " + index);
        }
    }

    @Test
    void streamingDigesterMatchesGenericOracleForRepresentativeFrozenInputs() {
        List<Node> cases = representativeNodes();
        for (int index = 0; index < cases.size(); index++) {
            FrozenNode frozen = FrozenNode.fromNode(cases.get(index));
            assertEquals(FrozenCanonicalDigester.calculateGenericOracle(frozen),
                    FrozenCanonicalDigester.calculateBlueId(frozen),
                    "BlueId at case " + index);
        }
    }

    @Test
    void typedSchemaScalarsAndMergePolicyMatchMutableIdentityWithoutFallback() {
        BigInteger beyondSafeInteger = new BigInteger("900719925474099200000000000000000001");
        Schema schema = new Schema()
                .required(true)
                .minLength(BigInteger.ZERO)
                .maxLength(beyondSafeInteger)
                .minimum(new BigDecimal("-10.5"))
                .maximum(new BigDecimal("10.5"))
                .exclusiveMinimum(new BigDecimal("-9.25"))
                .exclusiveMaximum(new BigDecimal("9.25"))
                .multipleOf(new BigDecimal("0.125"))
                .minItems(BigInteger.ONE)
                .maxItems(beyondSafeInteger)
                .uniqueItems(true)
                .minFields(BigInteger.valueOf(2L))
                .maxFields(beyondSafeInteger)
                .enumValues(Arrays.asList(
                        new Node().value("text"),
                        new Node().value(true),
                        new Node().value(new BigDecimal("1.25")),
                        new Node().value(beyondSafeInteger)));
        Node mutable = new Node()
                .mergePolicy("append-only")
                .schema(schema)
                .items(new Node().value("entry"));
        FrozenNode frozen = FrozenNode.fromNode(mutable);
        AtomicInteger fallbacks = new AtomicInteger();
        FrozenCanonicalDigester.Observer observer = new FrozenCanonicalDigester.Observer() {
            @Override
            public void genericFallback() {
                fallbacks.incrementAndGet();
            }
        };

        String mutableIdentity = BlueIdCalculator.calculateBlueId(mutable);
        assertEquals(mutableIdentity, FrozenCanonicalDigester.calculateGenericOracle(frozen));
        assertEquals(mutableIdentity, FrozenCanonicalDigester.calculateBlueId(frozen, observer));
        assertEquals(0, fallbacks.get());
    }

    @Test
    void canonicalScalarWriterMatchesJcsAcrossDeterministicUnicodeAndNumberCorpus() throws Exception {
        Random random = new Random(0x4a435346524f5a45L);
        for (int index = 0; index < 20_000; index++) {
            Object value = scalarValue(random, index);
            byte[] json = JSON_MAPPER.writeValueAsBytes(Arrays.asList(value));
            byte[] wrapped = new JsonCanonicalizer(json).getEncodedUTF8();
            byte[] expected = Arrays.copyOfRange(wrapped, 1, wrapped.length - 1);
            ByteArraySink sink = new ByteArraySink();
            FrozenCanonicalWriter.writeCanonicalValue(value, sink);
            assertArrayEquals(expected, sink.bytes(), "scalar canonical bytes at case " + index);
        }
    }

    @Test
    void streamingDigestMatchesGenericOracleForOneHundredThousandGeneratedFrozenTrees() {
        Random random = new Random(0x424c554549444a43L);
        AtomicInteger fallbacks = new AtomicInteger();
        FrozenCanonicalDigester.Observer observer = new FrozenCanonicalDigester.Observer() {
            @Override
            public void genericFallback() {
                fallbacks.incrementAndGet();
            }
        };
        for (int index = 0; index < 100_000; index++) {
            Node generated = generatedNode(random, index);
            FrozenNode frozen = FrozenNode.fromNode(generated);
            String expected = FrozenCanonicalDigester.calculateGenericOracle(frozen);
            String mutableExpected = BlueIdCalculator.calculateBlueId(frozen.toNode());
            String actual = FrozenCanonicalDigester.calculateBlueId(frozen, observer);
            assertEquals(mutableExpected, expected, "independent generic oracle case " + index);
            assertEquals(expected, actual, "generated identity case " + index);
        }
        assertEquals(0, fallbacks.get(), "generated supported cases must stay on the streaming path");
    }

    @Test
    void officialCanonicalSizeMatchesLegacyGasRepresentationForGeneratedTrees() {
        Random random = new Random(0x47415353495a454cL);
        for (int index = 0; index < 10_000; index++) {
            Node generated = generatedNode(random, index);
            FrozenNode frozen = FrozenNode.fromNode(generated);
            assertEquals(NodeCanonicalizer.canonicalSize(generated),
                    FrozenCanonicalWriter.officialCanonicalSize(frozen),
                    "official canonical size case " + index);
        }
    }

    @Test
    void rawJsonContainersAndNonInferredNumbersKeepCanonicalSizeParity() {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put("items", Arrays.<Object>asList("first", BigInteger.valueOf(2), true));
        raw.put("nested", Collections.<String, Object>singletonMap("key", "value"));
        raw.put("float", Float.valueOf(0.1f));
        raw.put("dropped", null);
        Map<String, Object> nestedNullMap = new LinkedHashMap<>();
        nestedNullMap.put("kept", "value");
        nestedNullMap.put("dropped", null);
        raw.put("mapInList", Collections.<Object>singletonList(nestedNullMap));
        List<Node> cases = Arrays.asList(
                new Node().value(raw),
                new Node().value(Byte.valueOf((byte) 7)),
                new Node().value(Character.valueOf('x')),
                new Node().value(new Character[] {'a', null, '\u20ac'}),
                new Node().value(new String[] {"a", "b"}),
                new Node().value(new String[] {null}),
                new Node().value(new byte[] {1, 2}),
                new Node().value(new char[] {'a', 'b'}),
                new Node().value(new Object[] {
                        null,
                        Collections.emptyMap(),
                        Collections.singletonMap("kept", "value")
                }));

        for (Node authored : cases) {
            FrozenNode frozen = FrozenNode.fromNode(authored);
            assertEquals(NodeCanonicalizer.canonicalSize(authored),
                    FrozenCanonicalWriter.officialCanonicalSize(frozen));
            assertEquals(BlueIdCalculator.calculateBlueId(authored), frozen.blueId());
        }

        Map<String, Object> invalidRaw = new LinkedHashMap<>();
        invalidRaw.put("nullsInList", Collections.<Object>singletonList(null));
        Node invalid = new Node().value(invalidRaw);
        assertSameFailure(invalid);
    }

    @Test
    void unhandledConcreteContainerArraysMatchMutableCanonicalOracles() throws Exception {
        CustomJsonList custom = new CustomJsonList();
        custom.add(Collections.<String, Object>singletonMap("kind", "custom"));

        List<Object> singleton = Collections.<Object>singletonList(
                Collections.<String, Object>singletonMap("kind", "jdk-singleton"));
        Object singletonArray = Array.newInstance(singleton.getClass(), 1);
        Array.set(singletonArray, 0, singleton);

        for (Object rawArray : Arrays.asList(
                new CustomJsonList[] {custom}, singletonArray)) {
            Node authored = new Node().value(rawArray);
            FrozenNode frozen = FrozenNode.fromNode(authored);
            byte[] json = JSON_MAPPER.writeValueAsBytes(NodeToBlueIdInput.get(authored));
            byte[] expectedCanonical = new JsonCanonicalizer(json).getEncodedUTF8();
            ByteArraySink sink = new ByteArraySink();

            FrozenCanonicalWriter.write(frozen, sink);

            assertArrayEquals(expectedCanonical, sink.bytes());
            assertEquals(BlueIdCalculator.calculateBlueId(authored), frozen.blueId());
            assertEquals(FrozenCanonicalDigester.calculateGenericOracle(frozen),
                    FrozenCanonicalDigester.calculateBlueId(frozen));
            assertEquals(NodeCanonicalizer.canonicalSize(authored),
                    FrozenCanonicalWriter.officialCanonicalSize(frozen));
        }
    }

    @Test
    void enumsPreserveJacksonWireBytesAndUseGenericDigestFallback() throws Exception {
        List<Enum<?>> values = Arrays.<Enum<?>>asList(
                DefaultWireEnum.DEFAULT_VALUE,
                AnnotatedWireEnum.ANNOTATED_VALUE);
        List<String> expectedJson = Arrays.asList(
                "\"DEFAULT_VALUE\"",
                "\"wire-value\"");
        AtomicInteger fallbacks = new AtomicInteger();
        FrozenCanonicalDigester.Observer observer = new FrozenCanonicalDigester.Observer() {
            @Override
            public void genericFallback() {
                fallbacks.incrementAndGet();
            }
        };

        for (int index = 0; index < values.size(); index++) {
            Enum<?> value = values.get(index);
            assertEquals(expectedJson.get(index), JSON_MAPPER.writeValueAsString(value));
            assertFalse(FrozenCanonicalWriter.supportsCanonicalValue(value));

            ByteArraySink directSink = new ByteArraySink();
            FrozenCanonicalWriter.writeCanonicalValue(value, directSink);
            byte[] wrappedOracle = new JsonCanonicalizer(
                    JSON_MAPPER.writeValueAsBytes(Collections.singletonList(value)))
                    .getEncodedUTF8();
            byte[] directOracle = Arrays.copyOfRange(
                    wrappedOracle, 1, wrappedOracle.length - 1);
            assertArrayEquals(directOracle, directSink.bytes());

            Node authored = new Node().value(value);
            FrozenNode frozen = FrozenNode.fromNode(authored);
            byte[] canonicalInputJson = JSON_MAPPER.writeValueAsBytes(
                    NodeToBlueIdInput.get(authored));
            byte[] canonicalInputOracle = new JsonCanonicalizer(canonicalInputJson)
                    .getEncodedUTF8();
            ByteArraySink nodeSink = new ByteArraySink();
            FrozenCanonicalWriter.write(frozen, nodeSink);

            assertArrayEquals(canonicalInputOracle, nodeSink.bytes());
            assertEquals(BlueIdCalculator.calculateBlueId(authored), frozen.blueId());
            assertEquals(NodeCanonicalizer.canonicalSize(authored),
                    FrozenCanonicalWriter.officialCanonicalSize(frozen));
            assertEquals(FrozenCanonicalDigester.calculateGenericOracle(frozen),
                    FrozenCanonicalDigester.calculateBlueId(frozen, observer));
        }
        assertEquals(values.size(), fallbacks.get());
    }

    @Test
    void invalidInputDiagnosticsRemainCompatibleWithExistingOracles() {
        assertSameFailure(new Node().blueId(TEXT_TYPE_BLUE_ID + "#member"));
        RuntimeException previousFailure = assertThrows(RuntimeException.class,
                () -> FrozenNode.fromNode(new Node().items(
                        new Node().value("before"),
                        new Node().previousBlueId(TEXT_TYPE_BLUE_ID))));
        assertEquals("\"$previous\" must appear only as the first list item.",
                previousFailure.getMessage(),
                "FrozenNode list construction keeps its rc.14 diagnostic");
        Node invalidSchema = new Node().schema(new Schema().minLength(
                new Node().value(1).blue(new Node().value("directive"))));
        RuntimeException schemaFailure = assertThrows(RuntimeException.class,
                () -> FrozenNode.fromNode(invalidSchema));
        assertEquals("\"blue\" is a preprocessing directive and must not be present in BlueId input. "
                        + "Call preprocess/canonicalize/calculateSemanticBlueId first. Path: /",
                schemaFailure.getMessage(),
                "rc.11 FrozenNode schema diagnostics use the nested-node root path");
        assertSameFailure(new Node().schema(new Schema().enumValues(
                Arrays.asList(new Node()))));
    }

    @Test
    void genericFallbackPreservesReservedPropertyAndEmptySchemaCleaning() {
        List<Node> cases = Arrays.asList(
                new Node().properties("child", new Node()
                        .name("discarded")
                        .properties(OBJECT_NAME, new Node().value("override"))),
                new Node().properties("child", new Node()
                        .type(new Node().blueId(TEXT_TYPE_BLUE_ID))
                        .properties(OBJECT_TYPE, new Node().value("override"))),
                new Node().properties("child", new Node()
                        .schema(new Schema().minimum(new Node()))),
                new Node().value(Collections.<String, Object>singletonMap(
                        "empty", Collections.emptyMap())),
                new Node().schema(new Schema().enumValues(Collections.singletonList(
                        new Node().value(Collections.<String, Object>singletonMap(
                                "key", "value"))))),
                new Node().schema(new Schema().minimum(new Node().value(
                        Collections.<String, Object>singletonMap("key", "value")))),
                new Node().schema(new Schema().minLength(new Node().value(
                        Collections.<Object>singletonList(1)))),
                new Node().items(
                        new Node().properties(LIST_CONTROL_PREVIOUS,
                                new Node().blueId(TEXT_TYPE_BLUE_ID)),
                        new Node().value("tail")));
        AtomicInteger fallbacks = new AtomicInteger();
        FrozenCanonicalDigester.Observer observer = new FrozenCanonicalDigester.Observer() {
            @Override
            public void genericFallback() {
                fallbacks.incrementAndGet();
            }
        };

        for (int index = 0; index < cases.size(); index++) {
            FrozenNode frozen = FrozenNode.fromNode(cases.get(index));
            assertEquals(FrozenCanonicalDigester.calculateGenericOracle(frozen),
                    FrozenCanonicalDigester.calculateBlueId(frozen, observer),
                    "fallback identity case " + index);
        }
        assertTrue(fallbacks.get() >= 3,
                "reserved-key representations must stay on the compatibility oracle");
    }

    private static void assertSameFailure(Node input) {
        RuntimeException expected = assertThrows(RuntimeException.class,
                () -> BlueIdCalculator.calculateBlueId(input));
        RuntimeException actual = assertThrows(RuntimeException.class,
                () -> FrozenNode.fromNode(input));
        assertEquals(expected.getClass(), actual.getClass());
        assertEquals(expected.getMessage(), actual.getMessage());
    }

    private static void assertSameIdentityFailure(Node input) {
        RuntimeException expected = assertThrows(RuntimeException.class,
                () -> BlueIdCalculator.calculateBlueId(input));
        RuntimeException actual = assertThrows(RuntimeException.class,
                () -> FrozenNode.fromNode(input).blueId());
        assertEquals(expected.getClass(), actual.getClass());
        assertEquals(expected.getMessage(), actual.getMessage());
    }

    private static List<Node> representativeNodes() {
        List<Node> cases = new ArrayList<>();
        cases.add(new Node());
        cases.add(new Node().value("plain"));
        cases.add(new Node().value("controls\u0000\b\t\n\f\r\"\\/"));
        cases.add(new Node().value("zażółć-\uD83D\uDE80-\u2028"));
        cases.add(new Node().value(BigInteger.valueOf(9007199254740991L)));
        cases.add(new Node().value(new BigInteger("9007199254740992")));
        cases.add(new Node().value(new BigDecimal("-0.0000001")));
        cases.add(new Node().type(new Node().blueId(DOUBLE_TYPE_BLUE_ID)).value("-0"));
        cases.add(new Node().blueId(TEXT_TYPE_BLUE_ID));
        cases.add(new Node().items(new Node().value("a"), new Node().value(BigInteger.valueOf(2))));
        cases.add(new Node().items(new Node().properties(
                LIST_CONTROL_EMPTY, new Node().value(true)), new Node().value("tail")));
        cases.add(new Node().items(
                new Node().previousBlueId(TEXT_TYPE_BLUE_ID), new Node().value("tail")));
        cases.add(new Node()
                .name("root")
                .description("description")
                .properties(new LinkedHashMap<String, Node>() {{
                    put("z", new Node().value("last"));
                    put("β", new Node().value(true));
                    put("alpha", new Node().items(new Node().value(1), new Node().value(2)));
                }}));
        Schema schema = new Schema()
                .required(true)
                .minLength(BigInteger.ZERO)
                .maximum(new BigDecimal("10.25"))
                .enumValues(Arrays.asList(new Node().value("x"), new Node().value(BigInteger.ONE)));
        cases.add(new Node().schema(schema).value("schema-value"));
        cases.add(new Node().contracts(new Node().properties(
                "handler", new Node().type(new Node().blueId(TEXT_TYPE_BLUE_ID)).value("run"))));
        return cases;
    }

    private static Object scalarValue(Random random, int index) {
        switch (index % 8) {
            case 0:
                return "text-" + index + "-\u0000-\b\t\n\f\r-\"\\-zażółć-\uD83D\uDE80-"
                        + (char) random.nextInt(0x10000);
            case 1:
                return BigInteger.valueOf(random.nextLong() & 0x1fffffffffffffL);
            case 2:
                return new BigInteger(80, random).multiply(index % 3 == 0
                        ? BigInteger.valueOf(-1) : BigInteger.ONE);
            case 3:
                return BigDecimal.valueOf((random.nextDouble() - 0.5d) * 1_000_000d);
            case 4:
                return Boolean.TRUE;
            case 5:
                return Boolean.FALSE;
            case 6:
                return Double.longBitsToDouble(random.nextLong() & 0x7fefffffffffffffL);
            default:
                return null;
        }
    }

    private static Node generatedNode(Random random, int index) {
        switch (index % 12) {
            case 0:
                return new Node().value("s-" + index + "-\u0000-zażółć-\uD83D\uDE80-"
                        + (char) random.nextInt(0x10000));
            case 1: {
                BigInteger integer = new BigInteger(72, random);
                return new Node().value(index % 4 == 1 ? integer : integer.negate());
            }
            case 2:
                return new Node().value(BigDecimal.valueOf(
                        (random.nextDouble() - 0.5d) * Math.pow(10, index % 20 - 10)));
            case 3:
                return new Node().value((index & 1) == 0);
            case 4: {
                Map<String, Node> properties = new LinkedHashMap<>();
                properties.put("z-" + index, new Node().value(index));
                properties.put("a/" + index, new Node().value("v-" + random.nextInt()));
                properties.put("β~" + index, new Node().properties(
                        "nested", new Node().value(index % 7)));
                return new Node().properties(properties);
            }
            case 5:
                return new Node().items(
                        new Node().value("head-" + index),
                        new Node().items(new Node().value(index), new Node().value(index + 1L)),
                        new Node().properties("tail", new Node().value(index % 3 == 0)));
            case 6:
                return new Node().items();
            case 7:
                return new Node().type(new Node().blueId(DOUBLE_TYPE_BLUE_ID))
                        .value(index % 2 == 0 ? "-0" : String.valueOf(random.nextDouble()));
            case 8:
                return new Node().blueId(TEXT_TYPE_BLUE_ID);
            case 9:
                return new Node().items(
                        new Node().previousBlueId(TEXT_TYPE_BLUE_ID),
                        new Node().value("append-" + index));
            case 10:
                return new Node().items(
                        new Node().properties(LIST_CONTROL_EMPTY, new Node().value(true)),
                        new Node().value(index));
            default: {
                Schema schema = new Schema()
                        .required((index & 1) == 0)
                        .minLength(BigInteger.valueOf(index % 17))
                        .minimum(BigDecimal.valueOf(index % 19, index % 5))
                        .enumValues(Arrays.asList(
                                new Node().value("enum-" + index),
                                new Node().type(new Node().blueId(INTEGER_TYPE_BLUE_ID)).value(index)));
                return new Node().schema(schema).contracts(new Node().properties(
                        "audit-" + index, new Node().value(true)));
            }
        }
    }

    private static final class CustomJsonList extends ArrayList<Object> {
        private static final long serialVersionUID = 1L;
    }

    private enum DefaultWireEnum {
        DEFAULT_VALUE
    }

    private enum AnnotatedWireEnum {
        @JsonProperty("wire-value")
        ANNOTATED_VALUE
    }

    private static final class ByteArraySink implements FrozenCanonicalWriter.CanonicalByteSink {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        @Override
        public void writeByte(int value) {
            output.write(value);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            output.write(bytes, offset, length);
        }

        private byte[] bytes() {
            return output.toByteArray();
        }
    }
}
