package blue.language.snapshot;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.processor.util.NodeCanonicalizer;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.NodeToBlueIdInput;
import blue.language.model.Nodes;
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

import static blue.language.processor.FailureCapture.captureFailure;
import static blue.language.model.wire.BlueLanguageConstants.*;
import static blue.language.codec.jackson.UncheckedObjectMapper.JSON_MAPPER;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FrozenCanonicalDigesterTest {

    @Test
    void shouldAcceptCanonicalEmptyListPlaceholderDuringDirectIdentitySizing() {
        // given
        Node list = new Node().items(
                Nodes.emptyPlaceholder());

        // when
        long canonicalSize = NodeCanonicalizer
                .directIdentityCanonicalSize(list);

        // then
        assertTrue(canonicalSize > 0L);
    }

    @Test
    void shouldMatchGenericJcsWhenStreamingRepresentativeFrozenInputs() throws Exception {
        // given
        List<Node> cases = representativeNodes();

        // when
        List<CanonicalBytesObservation> observations =
                new ArrayList<>();
        for (int index = 0; index < cases.size(); index++) {
            FrozenNode frozen = FrozenNode.fromNode(cases.get(index));
            byte[] json = JSON_MAPPER.writeValueAsBytes(FrozenNodeToBlueIdInput.get(frozen));
            byte[] expected = new JsonCanonicalizer(json).getEncodedUTF8();
            ByteArraySink sink = new ByteArraySink();

            FrozenCanonicalWriter.write(frozen, sink);
            observations.add(new CanonicalBytesObservation(
                    expected,
                    sink.bytes(),
                    index));
        }

        // then
        for (CanonicalBytesObservation observation
                : observations) {
            assertArrayEquals(
                    observation.expected,
                    observation.actual,
                    "canonical bytes at case "
                            + observation.index);
        }
    }

    @Test
    void shouldMatchGenericOracleWhenDigestingRepresentativeFrozenInputs() {
        // given
        List<Node> cases = representativeNodes();

        // when
        List<IdentityObservation> observations =
                new ArrayList<>();
        for (int index = 0; index < cases.size(); index++) {
            FrozenNode frozen = FrozenNode.fromNode(cases.get(index));
            observations.add(new IdentityObservation(
                    FrozenCanonicalDigester
                            .calculateGenericOracle(frozen),
                    FrozenCanonicalDigester
                            .calculateBlueId(frozen),
                    index));
        }

        // then
        for (IdentityObservation observation
                : observations) {
            assertEquals(
                    observation.expected,
                    observation.actual,
                    "BlueId at case " + observation.index);
        }
    }

    @Test
    void shouldMatchMutableIdentityForTypedSchemaScalarsAndMergePolicyWithoutFallback() {
        // given
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

        // when
        String mutableIdentity = DirectBlueIdCalculator.calculateBlueId(mutable);
        String genericIdentity = FrozenCanonicalDigester
                .calculateGenericOracle(frozen);
        String streamingIdentity = FrozenCanonicalDigester
                .calculateBlueId(frozen, observer);
        int fallbackCount = fallbacks.get();

        // then
        assertEquals(mutableIdentity, genericIdentity);
        assertEquals(mutableIdentity, streamingIdentity);
        assertEquals(0, fallbackCount);
    }

    @Test
    void shouldPreserveDirectSchemaEnumOrderWithoutLeavingFrozenFastPath() throws Exception {
        // given
        Node mutable = new Node()
                .schema(new Schema().enumValues(Arrays.asList(
                        new Node().value("B"),
                        new Node().value("A"),
                        new Node().value("B"))))
                .value("A");
        FrozenNode frozen = FrozenNode.fromNode(mutable);
        AtomicInteger fallbacks = new AtomicInteger();
        FrozenCanonicalDigester.Observer observer =
                new FrozenCanonicalDigester.Observer() {
                    @Override
                    public void genericFallback() {
                        fallbacks.incrementAndGet();
                    }
                };
        ByteArraySink identitySink = new ByteArraySink();
        ByteArraySink officialSink = new ByteArraySink();

        // when
        String mutableIdentity = DirectBlueIdCalculator.calculateBlueId(mutable);
        String genericIdentity =
                FrozenCanonicalDigester.calculateGenericOracle(frozen);
        String streamingIdentity =
                FrozenCanonicalDigester.calculateBlueId(frozen, observer);
        FrozenCanonicalWriter.write(frozen, identitySink);
        FrozenCanonicalWriter.writeOfficial(frozen, officialSink);
        byte[] expectedIdentityBytes = new JsonCanonicalizer(
                JSON_MAPPER.writeValueAsBytes(
                        FrozenNodeToBlueIdInput.get(frozen)))
                .getEncodedUTF8();

        // then
        assertEquals(mutableIdentity, genericIdentity);
        assertEquals(mutableIdentity, streamingIdentity);
        assertEquals(0, fallbacks.get());
        assertArrayEquals(expectedIdentityBytes, identitySink.bytes());
        assertTrue(
                new String(officialSink.bytes(), StandardCharsets.UTF_8)
                        .contains("\"enum\":[\"B\",\"A\",\"B\"]"));
        assertEquals("B", mutable.getSchema().getEnum().get(0).getValue());
        assertEquals(3, mutable.getSchema().getEnum().size());
    }

    @Test
    void shouldMatchJcsAcrossDeterministicUnicodeAndNumberCorpus() throws Exception {
        // given
        Random random = new Random(0x4a435346524f5a45L);

        // when
        List<Integer> mismatches = new ArrayList<>();
        for (int index = 0; index < 20_000; index++) {
            Object value = scalarValue(random, index);
            byte[] json = JSON_MAPPER.writeValueAsBytes(Arrays.asList(value));
            byte[] wrapped = new JsonCanonicalizer(json).getEncodedUTF8();
            byte[] expected = Arrays.copyOfRange(wrapped, 1, wrapped.length - 1);
            ByteArraySink sink = new ByteArraySink();
            FrozenCanonicalWriter.writeCanonicalValue(value, sink);
            if (!Arrays.equals(expected, sink.bytes())) {
                mismatches.add(index);
            }
        }

        // then
        assertTrue(mismatches.isEmpty(),
                "scalar canonical byte mismatches: "
                        + mismatches);
    }

    @Test
    void shouldMatchGenericOracleForOneHundredThousandStreamingFrozenTreeDigests() {
        // given
        Random random = new Random(0x424c554549444a43L);
        AtomicInteger fallbacks = new AtomicInteger();
        FrozenCanonicalDigester.Observer observer = new FrozenCanonicalDigester.Observer() {
            @Override
            public void genericFallback() {
                fallbacks.incrementAndGet();
            }
        };

        // when
        List<Integer> genericOracleMismatches =
                new ArrayList<>();
        List<Integer> identityMismatches =
                new ArrayList<>();
        for (int index = 0; index < 100_000; index++) {
            Node generated = generatedNode(random, index);
            FrozenNode frozen = FrozenNode.fromNode(generated);
            String expected = FrozenCanonicalDigester.calculateGenericOracle(frozen);
            String mutableExpected = DirectBlueIdCalculator.calculateBlueId(frozen.toNode());
            String actual = FrozenCanonicalDigester.calculateBlueId(frozen, observer);
            if (!mutableExpected.equals(expected)) {
                genericOracleMismatches.add(index);
            }
            if (!expected.equals(actual)) {
                identityMismatches.add(index);
            }
        }
        int fallbackCount = fallbacks.get();

        // then
        assertTrue(genericOracleMismatches.isEmpty(),
                "independent generic oracle mismatches: "
                        + genericOracleMismatches);
        assertTrue(identityMismatches.isEmpty(),
                "generated identity mismatches: "
                        + identityMismatches);
        assertEquals(0, fallbackCount,
                "generated supported cases must stay on the streaming path");
    }

    @Test
    void shouldMatchLegacyGasRepresentationWhenSizingGeneratedTreesCanonically() {
        // given
        Random random = new Random(0x47415353495a454cL);

        // when
        List<Integer> mismatches = new ArrayList<>();
        for (int index = 0; index < 10_000; index++) {
            Node generated = generatedNode(random, index);
            FrozenNode frozen = FrozenNode.fromNode(generated);
            if (NodeCanonicalizer.canonicalSize(generated)
                    != FrozenCanonicalWriter
                    .officialCanonicalSize(frozen)) {
                mismatches.add(index);
            }
        }

        // then
        assertTrue(mismatches.isEmpty(),
                "official canonical size mismatches: "
                        + mismatches);
    }

    @Test
    void shouldKeepCanonicalSizeParityForRawJsonContainersAndNonInferredNumbers() {
        // given
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
        Map<String, Object> invalidRaw = new LinkedHashMap<>();
        invalidRaw.put("nullsInList",
                Collections.<Object>singletonList(null));
        Node invalid = new Node().value(invalidRaw);

        // when
        List<CanonicalIdentityObservation> observations =
                new ArrayList<>();
        for (Node authored : cases) {
            FrozenNode frozen = FrozenNode.fromNode(authored);
            observations.add(new CanonicalIdentityObservation(
                    NodeCanonicalizer.canonicalSize(authored),
                    FrozenCanonicalWriter
                            .officialCanonicalSize(frozen),
                    DirectBlueIdCalculator.calculateBlueId(authored),
                    frozen.blueId()));
        }
        FailurePair invalidFailure = sameFailure(invalid);

        // then
        for (CanonicalIdentityObservation observation
                : observations) {
            assertEquals(observation.expectedSize,
                    observation.actualSize);
            assertEquals(observation.expectedIdentity,
                    observation.actualIdentity);
        }
        assertSameFailure(invalidFailure);
    }

    @Test
    void shouldMatchMutableCanonicalOraclesForUnhandledConcreteContainerArrays() throws Exception {
        // given
        CustomJsonList custom = new CustomJsonList();
        custom.add(Collections.<String, Object>singletonMap("kind", "custom"));

        List<Object> singleton = Collections.<Object>singletonList(
                Collections.<String, Object>singletonMap("kind", "jdk-singleton"));
        Object singletonArray = Array.newInstance(singleton.getClass(), 1);
        Array.set(singletonArray, 0, singleton);

        // when
        List<ContainerArrayObservation> observations =
                new ArrayList<>();
        for (Object rawArray : Arrays.asList(
                new CustomJsonList[] {custom}, singletonArray)) {
            Node authored = new Node().value(rawArray);
            FrozenNode frozen = FrozenNode.fromNode(authored);
            byte[] json = JSON_MAPPER.writeValueAsBytes(NodeToBlueIdInput.get(authored));
            byte[] expectedCanonical = new JsonCanonicalizer(json).getEncodedUTF8();
            ByteArraySink sink = new ByteArraySink();

            FrozenCanonicalWriter.write(frozen, sink);
            observations.add(new ContainerArrayObservation(
                    expectedCanonical,
                    sink.bytes(),
                    DirectBlueIdCalculator.calculateBlueId(authored),
                    frozen.blueId(),
                    FrozenCanonicalDigester
                            .calculateGenericOracle(frozen),
                    FrozenCanonicalDigester
                            .calculateBlueId(frozen),
                    NodeCanonicalizer.canonicalSize(authored),
                    FrozenCanonicalWriter
                            .officialCanonicalSize(frozen)));
        }

        // then
        for (ContainerArrayObservation observation
                : observations) {
            assertArrayEquals(observation.expectedBytes,
                    observation.actualBytes);
            assertEquals(observation.expectedMutableIdentity,
                    observation.frozenIdentity);
            assertEquals(observation.genericIdentity,
                    observation.streamingIdentity);
            assertEquals(observation.expectedSize,
                    observation.actualSize);
        }
    }

    @Test
    void shouldPreserveJacksonWireBytesForEnumsAndUseGenericDigestFallback() throws Exception {
        // given
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

        // when
        List<EnumObservation> observations =
                new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            Enum<?> value = values.get(index);
            ByteArraySink directSink = new ByteArraySink();
            FrozenCanonicalWriter.writeCanonicalValue(value, directSink);
            byte[] wrappedOracle = new JsonCanonicalizer(
                    JSON_MAPPER.writeValueAsBytes(Collections.singletonList(value)))
                    .getEncodedUTF8();
            byte[] directOracle = Arrays.copyOfRange(
                    wrappedOracle, 1, wrappedOracle.length - 1);

            Node authored = new Node().value(value);
            FrozenNode frozen = FrozenNode.fromNode(authored);
            byte[] canonicalInputJson = JSON_MAPPER.writeValueAsBytes(
                    NodeToBlueIdInput.get(authored));
            byte[] canonicalInputOracle = new JsonCanonicalizer(canonicalInputJson)
                    .getEncodedUTF8();
            ByteArraySink nodeSink = new ByteArraySink();
            FrozenCanonicalWriter.write(frozen, nodeSink);
            observations.add(new EnumObservation(
                    expectedJson.get(index),
                    JSON_MAPPER.writeValueAsString(value),
                    FrozenCanonicalWriter
                            .supportsCanonicalValue(value),
                    directOracle,
                    directSink.bytes(),
                    canonicalInputOracle,
                    nodeSink.bytes(),
                    DirectBlueIdCalculator.calculateBlueId(authored),
                    frozen.blueId(),
                    NodeCanonicalizer.canonicalSize(authored),
                    FrozenCanonicalWriter
                            .officialCanonicalSize(frozen),
                    FrozenCanonicalDigester
                            .calculateGenericOracle(frozen),
                    FrozenCanonicalDigester
                            .calculateBlueId(frozen, observer)));
        }
        int fallbackCount = fallbacks.get();

        // then
        for (EnumObservation observation : observations) {
            assertEquals(observation.expectedJson,
                    observation.actualJson);
            assertFalse(observation.directlySupported);
            assertArrayEquals(observation.expectedDirectBytes,
                    observation.actualDirectBytes);
            assertArrayEquals(observation.expectedNodeBytes,
                    observation.actualNodeBytes);
            assertEquals(observation.expectedMutableIdentity,
                    observation.frozenIdentity);
            assertEquals(observation.expectedSize,
                    observation.actualSize);
            assertEquals(observation.genericIdentity,
                    observation.streamingIdentity);
        }
        assertEquals(values.size(), fallbackCount);
    }

    @Test
    void shouldKeepInvalidInputDiagnosticsCompatibleWithExistingOracles() {
        // given
        Node invalidMemberReference = new Node()
                .blueId(TEXT_TYPE_BLUE_ID + "#member");
        Node invalidPrevious = new Node().items(
                new Node().value("before"),
                new Node().previousBlueId(
                        TEXT_TYPE_BLUE_ID));
        Node invalidSchema = new Node().schema(
                new Schema().minLength(
                        new Node().value(1).blue(
                                new Node().value(
                                        "directive"))));
        Node invalidEnum = new Node().schema(
                new Schema().enumValues(
                        Arrays.asList(new Node())));

        // when
        FailurePair memberFailure =
                sameFailure(invalidMemberReference);
        Throwable previousFailure = captureFailure(
                () -> FrozenNode.fromNode(
                        invalidPrevious));
        Throwable schemaFailure = captureFailure(
                () -> FrozenNode.fromNode(
                        invalidSchema));
        FailurePair enumFailure =
                sameFailure(invalidEnum);

        // then
        assertSameFailure(memberFailure);
        assertInstanceOf(RuntimeException.class,
                previousFailure);
        assertEquals("\"$previous\" must appear only as the first list item.",
                previousFailure.getMessage(),
                "FrozenNode list construction keeps its rc.14 diagnostic");
        assertInstanceOf(RuntimeException.class,
                schemaFailure);
        assertEquals("\"blue\" is a preprocessing directive and must not be present in BlueId input. "
                        + "Call preprocess/canonicalize/calculateSourceDocumentBlueId first. Path: /",
                schemaFailure.getMessage(),
                "rc.11 FrozenNode schema diagnostics use the nested-node root path");
        assertSameFailure(enumFailure);
    }

    @Test
    void shouldPreserveReservedPropertyAndEmptySchemaCleaningDuringGenericFallback() {
        // given
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

        // when
        List<IdentityObservation> observations =
                new ArrayList<>();
        for (int index = 0; index < cases.size(); index++) {
            FrozenNode frozen = FrozenNode.fromNode(cases.get(index));
            observations.add(new IdentityObservation(
                    FrozenCanonicalDigester
                            .calculateGenericOracle(frozen),
                    FrozenCanonicalDigester
                            .calculateBlueId(frozen, observer),
                    index));
        }
        int fallbackCount = fallbacks.get();

        // then
        for (IdentityObservation observation
                : observations) {
            assertEquals(observation.expected,
                    observation.actual,
                    "fallback identity case "
                            + observation.index);
        }
        assertTrue(fallbackCount >= 3,
                "reserved-key representations must stay on the compatibility oracle");
    }

    private static FailurePair sameFailure(Node input) {
        Throwable expected = captureFailure(
                () -> DirectBlueIdCalculator
                        .calculateBlueId(input));
        Throwable actual = captureFailure(
                () -> FrozenNode.fromNode(input));
        return new FailurePair(expected, actual);
    }

    private static void assertSameFailure(
            FailurePair failure) {
        assertInstanceOf(RuntimeException.class,
                failure.expected);
        assertInstanceOf(RuntimeException.class,
                failure.actual);
        assertEquals(failure.expected.getClass(),
                failure.actual.getClass());
        assertEquals(failure.expected.getMessage(),
                failure.actual.getMessage());
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

    private static final class CanonicalBytesObservation {
        private final byte[] expected;
        private final byte[] actual;
        private final int index;

        private CanonicalBytesObservation(
                byte[] expected,
                byte[] actual,
                int index) {
            this.expected = expected;
            this.actual = actual;
            this.index = index;
        }
    }

    private static final class IdentityObservation {
        private final String expected;
        private final String actual;
        private final int index;

        private IdentityObservation(
                String expected,
                String actual,
                int index) {
            this.expected = expected;
            this.actual = actual;
            this.index = index;
        }
    }

    private static final class CanonicalIdentityObservation {
        private final long expectedSize;
        private final long actualSize;
        private final String expectedIdentity;
        private final String actualIdentity;

        private CanonicalIdentityObservation(
                long expectedSize,
                long actualSize,
                String expectedIdentity,
                String actualIdentity) {
            this.expectedSize = expectedSize;
            this.actualSize = actualSize;
            this.expectedIdentity = expectedIdentity;
            this.actualIdentity = actualIdentity;
        }
    }

    private static final class ContainerArrayObservation {
        private final byte[] expectedBytes;
        private final byte[] actualBytes;
        private final String expectedMutableIdentity;
        private final String frozenIdentity;
        private final String genericIdentity;
        private final String streamingIdentity;
        private final long expectedSize;
        private final long actualSize;

        private ContainerArrayObservation(
                byte[] expectedBytes,
                byte[] actualBytes,
                String expectedMutableIdentity,
                String frozenIdentity,
                String genericIdentity,
                String streamingIdentity,
                long expectedSize,
                long actualSize) {
            this.expectedBytes = expectedBytes;
            this.actualBytes = actualBytes;
            this.expectedMutableIdentity =
                    expectedMutableIdentity;
            this.frozenIdentity = frozenIdentity;
            this.genericIdentity = genericIdentity;
            this.streamingIdentity = streamingIdentity;
            this.expectedSize = expectedSize;
            this.actualSize = actualSize;
        }
    }

    private static final class EnumObservation {
        private final String expectedJson;
        private final String actualJson;
        private final boolean directlySupported;
        private final byte[] expectedDirectBytes;
        private final byte[] actualDirectBytes;
        private final byte[] expectedNodeBytes;
        private final byte[] actualNodeBytes;
        private final String expectedMutableIdentity;
        private final String frozenIdentity;
        private final long expectedSize;
        private final long actualSize;
        private final String genericIdentity;
        private final String streamingIdentity;

        private EnumObservation(
                String expectedJson,
                String actualJson,
                boolean directlySupported,
                byte[] expectedDirectBytes,
                byte[] actualDirectBytes,
                byte[] expectedNodeBytes,
                byte[] actualNodeBytes,
                String expectedMutableIdentity,
                String frozenIdentity,
                long expectedSize,
                long actualSize,
                String genericIdentity,
                String streamingIdentity) {
            this.expectedJson = expectedJson;
            this.actualJson = actualJson;
            this.directlySupported = directlySupported;
            this.expectedDirectBytes = expectedDirectBytes;
            this.actualDirectBytes = actualDirectBytes;
            this.expectedNodeBytes = expectedNodeBytes;
            this.actualNodeBytes = actualNodeBytes;
            this.expectedMutableIdentity =
                    expectedMutableIdentity;
            this.frozenIdentity = frozenIdentity;
            this.expectedSize = expectedSize;
            this.actualSize = actualSize;
            this.genericIdentity = genericIdentity;
            this.streamingIdentity = streamingIdentity;
        }
    }

    private static final class FailurePair {
        private final Throwable expected;
        private final Throwable actual;

        private FailurePair(
                Throwable expected,
                Throwable actual) {
            this.expected = expected;
            this.actual = actual;
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
