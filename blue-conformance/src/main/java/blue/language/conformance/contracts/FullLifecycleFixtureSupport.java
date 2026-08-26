package blue.language.conformance.contracts;

import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.conformance.contracts.closure.ClosureFixtureConformance;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.NodeWireForm;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.processor.GasSchedule;
import blue.language.processor.ProcessorDiagnostic;
import blue.language.processor.closure.AdmissionCause;
import blue.language.processor.closure.AdmissionKind;
import blue.language.processor.closure.AffectedClosureSnapshot;
import blue.language.processor.closure.BlueClosureContracts;
import blue.language.processor.closure.ChannelOccurrence;
import blue.language.processor.closure.CheckpointDomainValue;
import blue.language.processor.closure.CheckpointWrite;
import blue.language.processor.closure.ClosureAttemptResult;
import blue.language.processor.closure.ClosureCommitCompanion;
import blue.language.processor.closure.ClosureEnvironment;
import blue.language.processor.closure.ClosureEvidenceFactory;
import blue.language.processor.closure.ClosureExecutionObserver;
import blue.language.processor.closure.ClosureImplementationEvidence;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ClosureProcessResult;
import blue.language.processor.closure.ClosureWorkOccurrence;
import blue.language.processor.closure.ComponentFinalizationInput;
import blue.language.processor.closure.ComponentFinalizationKernel;
import blue.language.processor.closure.ComponentFinalizationResult;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.DocumentId;
import blue.language.processor.closure.DocumentStepEvidence;
import blue.language.processor.closure.ExecutionPolicy;
import blue.language.processor.closure.FinalizedDocumentEvidence;
import blue.language.processor.closure.GasTraceEntry;
import blue.language.processor.closure.GraphChange;
import blue.language.processor.closure.ManagedDocumentGraph;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.PublicEventOccurrence;
import blue.language.processor.closure.RejectedCharge;
import blue.language.processor.closure.ResultingDocument;
import blue.language.processor.closure.ScopeAddress;
import blue.language.processor.closure.SubscriptionDelta;
import blue.language.processor.closure.SubscriptionState;
import blue.language.processor.closure.TentativeFinalization;
import blue.language.provider.CyclicSetProof;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static blue.language.conformance.contracts.FullLifecycleFixtureFiles.require;

/** Shared exact JSON/YAML projection primitives for the fixture compiler. */
final class FullLifecycleFixtureSupport {

    private FullLifecycleFixtureSupport() {
    }

    private static final ObjectMapper YAML = new ObjectMapper(
            YAMLFactory.builder()
                    .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                    .build());
    private static final ObjectMapper OUTPUT_YAML = new ObjectMapper(
            YAMLFactory.builder()
                    .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER)
                    .enable(YAMLGenerator.Feature.MINIMIZE_QUOTES)
                    .build());
    static final DirectBlueIdCalculator DIRECT =
            new DirectBlueIdCalculator();
    static final JsonNodeFactory JSON = JsonNodeFactory.instance;
    static final String DUMMY_BLUE_ID =
            "11111111111111111111111111111111";

static JsonNode readYaml(Path path) {
    try (InputStream input = Files.newInputStream(path)) {
        JsonNode value = YAML.readTree(input);
        require(value != null, "YAML root must not be null: " + path);
        return value;
    } catch (IOException exception) {
        throw new IllegalStateException("unable to read YAML " + path,
                exception);
    }
}
static byte[] deterministicYaml(JsonNode value) {
    try {
        String yaml = OUTPUT_YAML.writeValueAsString(value)
                .replace("\r\n", "\n");
        if (!yaml.endsWith("\n")) {
            yaml = yaml + "\n";
        }
        return yaml.getBytes(StandardCharsets.UTF_8);
    } catch (IOException exception) {
        throw new IllegalStateException(
                "unable to serialize executable fixture", exception);
    }
}

static Node node(JsonNode value) {
    return UncheckedObjectMapper.JSON_MAPPER.convertValue(
            value, Node.class);
}

static JsonNode wire(Node value) {
    return UncheckedObjectMapper.JSON_MAPPER.valueToTree(
            NodeWireForm.get(value, NodeWireForm.Strategy.SIMPLE));
}

static JsonNode semanticValue(Node value) {
    if (value.getValue() != null) {
        return UncheckedObjectMapper.JSON_MAPPER.valueToTree(
                value.getValue());
    }
    if (value.getItems() != null) {
        ArrayNode result = JSON.arrayNode();
        for (Node item : value.getItems()) {
            result.add(semanticValue(item));
        }
        return result;
    }
    if (value.getProperties() != null) {
        ObjectNode result = JSON.objectNode();
        for (Map.Entry<String, Node> entry
                : value.getProperties().entrySet()) {
            result.set(entry.getKey(), semanticValue(entry.getValue()));
        }
        return result;
    }
    if (value.getBlueId() != null) {
        ObjectNode result = JSON.objectNode();
        result.put(BlueLanguageConstants.OBJECT_BLUE_ID,
                value.getBlueId());
        return result;
    }
    return JSON.nullNode();
}

static ObjectNode requiredObject(JsonNode value, String label) {
    require(value != null && value.isObject(),
            label + " must be an object");
    return (ObjectNode) value;
}

static ObjectNode object(JsonNode parent, String field) {
    return requiredObject(parent.get(field), field);
}

static ArrayNode array(JsonNode parent, String field) {
    JsonNode value = parent.get(field);
    require(value != null && value.isArray(),
            field + " must be an array");
    return (ArrayNode) value;
}

static ArrayNode optionalArray(JsonNode parent, String field) {
    return parent.has(field) ? array(parent, field) : JSON.arrayNode();
}

static String text(JsonNode parent, String field) {
    JsonNode value = parent.get(field);
    require(value != null && value.isTextual()
                    && !value.textValue().isEmpty(),
            field + " must be non-empty Text");
    return value.textValue();
}

static String nullableText(JsonNode parent, String field) {
    JsonNode value = parent.get(field);
    if (value == null || value.isNull()) {
        return null;
    }
    require(value.isTextual() && !value.textValue().isEmpty(),
            field + " must be null or non-empty Text");
    return value.textValue();
}

static long requiredLong(JsonNode parent, String field) {
    JsonNode value = parent.get(field);
    require(value != null && value.isIntegralNumber()
                    && value.canConvertToLong() && value.longValue() >= 0L,
            field + " must be a non-negative Integer");
    return value.longValue();
}

static boolean requiredBoolean(JsonNode parent, String field) {
    JsonNode value = parent.get(field);
    require(value != null && value.isBoolean(),
            field + " must be Boolean");
    return value.booleanValue();
}

static String sha256(String value, String label) {
    require(value != null && value.matches("[0-9a-f]{64}"),
            label + " identity must be a lowercase SHA-256 digest");
    return "sha256:" + value;
}

static List<String> textValues(ArrayNode values) {
    ArrayList<String> result = new ArrayList<String>();
    for (JsonNode value : values) {
        require(value.isTextual() && !value.textValue().isEmpty(),
                "array item must be non-empty Text");
        result.add(value.textValue());
    }
    return result;
}

static List<Long> longValues(ArrayNode values) {
    ArrayList<Long> result = new ArrayList<Long>();
    for (JsonNode value : values) {
        require(value.isIntegralNumber() && value.longValue() >= 0L,
                "array item must be a non-negative Integer");
        result.add(Long.valueOf(value.longValue()));
    }
    return result;
}

static ArrayNode documentIds(List<DocumentId> values) {
    ArrayNode result = JSON.arrayNode();
    for (DocumentId value : values) {
        result.add(value.value());
    }
    return result;
}

static ArrayNode textArray(List<String> values) {
    ArrayNode result = JSON.arrayNode();
    for (String value : values) {
        result.add(value);
    }
    return result;
}

static void nullable(
        ObjectNode target,
        String field,
        Object value) {
    if (value == null) {
        target.putNull(field);
    } else if (value instanceof Long) {
        target.put(field, ((Long) value).longValue());
    } else if (value instanceof Integer) {
        target.put(field, ((Integer) value).intValue());
    } else {
        target.put(field, value.toString());
    }
}

static void nullableDocument(
        ObjectNode target,
        String field,
        DocumentId value) {
    if (value == null) {
        target.putNull(field);
    } else {
        target.put(field, value.value());
    }
}

static void nullableNode(
        ObjectNode target,
        String field,
        JsonNode value) {
    if (value == null) {
        target.putNull(field);
    } else {
        target.set(field, value);
    }
}

static void copyIfPresent(
        ObjectNode source,
        ObjectNode target,
        String field) {
    if (source.has(field)) {
        target.set(field, source.get(field));
    }
}

static void rejectUnresolvedMacros(JsonNode value, String label) {
    rejectUnresolvedMacros(value, label, "");
}

static void rejectUnresolvedMacros(
        JsonNode value,
        String label,
        String pointer) {
    if (value.isObject()) {
        Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            require(!field.getKey().startsWith("$"),
                    label + " contains unresolved macro "
                            + field.getKey() + " at " + pointer);
            if (BlueLanguageConstants.OBJECT_BLUE_ID.equals(field.getKey())
                    && field.getValue().isTextual()) {
                String identity = field.getValue().textValue();
                boolean canonicalProofPlaceholder =
                        identity.startsWith("this#")
                                && pointer.contains(
                                "/completeCyclicProof/declaredPlaceholderSet/");
                require((!identity.startsWith("this#")
                                || canonicalProofPlaceholder)
                                && !DUMMY_BLUE_ID.equals(identity),
                        label + " contains unresolved document identity at "
                                + pointer + "/" + field.getKey() + ": "
                                + identity);
            }
            rejectUnresolvedMacros(
                    field.getValue(), label,
                    pointer + "/" + field.getKey().replace("~", "~0")
                            .replace("/", "~1"));
        }
    } else if (value.isArray()) {
        for (int index = 0; index < value.size(); index++) {
            rejectUnresolvedMacros(
                    value.get(index), label, pointer + "/" + index);
        }
    }
}
}
