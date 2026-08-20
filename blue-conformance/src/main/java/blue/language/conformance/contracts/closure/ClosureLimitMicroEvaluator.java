package blue.language.conformance.contracts.closure;

import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.identity.CyclicSetFinalization;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import com.fasterxml.jackson.databind.JsonNode;
import org.erdtman.jcs.JsonCanonicalizer;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static blue.language.model.wire.BlueLanguageConstants.OBJECT_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_VALUE;

/** Independently constructs and measures normative closure-limit generators. */
final class ClosureLimitMicroEvaluator {

    private static final Map<String, GeneratorSpec> GENERATORS = generators();

    private ClosureLimitMicroEvaluator() {
    }

    static long measure(String limitName, JsonNode generator) {
        GeneratorSpec spec = GENERATORS.get(limitName);
        if (spec == null) {
            throw new IllegalArgumentException(
                    "Unknown closure portable limit: " + limitName);
        }
        requireObject(generator, "generator");
        String kind = requiredText(generator, "kind");
        if (!spec.kind.equals(kind)) {
            throw new IllegalArgumentException(
                    limitName + " requires generator kind " + spec.kind
                            + " but found " + kind);
        }
        JsonNode parameters = requiredObject(generator, "parameters");
        requireExactFields(parameters, spec.parameterNames, kind);
        int count = nonNegativeInt(parameters, "count");

        if ("managed-documents".equals(kind)) {
            return managedDocuments(count);
        }
        if ("embedded-edges".equals(kind)) {
            return embeddedEdges(count);
        }
        if ("cyclic-members".equals(kind)) {
            return cyclicMembers(count);
        }
        if ("cyclic-edges".equals(kind)) {
            return cyclicEdges(count,
                    nonNegativeInt(parameters, "members"));
        }
        if ("cyclic-canonical-bytes".equals(kind)) {
            return cyclicCanonicalBytes(count);
        }
        if ("graph-changes".equals(kind)) {
            return graphChanges(count);
        }
        if ("closure-expansions".equals(kind)) {
            return closureExpansions(count);
        }
        if ("work-occurrences".equals(kind)) {
            return workOccurrences(count);
        }
        if ("tentative-finalizations".equals(kind)) {
            return tentativeFinalizations(count);
        }
        throw new AssertionError(kind);
    }

    static String diagnostic(String limitName) {
        GeneratorSpec spec = GENERATORS.get(limitName);
        if (spec == null) {
            throw new IllegalArgumentException(
                    "Unknown closure portable limit: " + limitName);
        }
        return spec.diagnostic;
    }

    private static long managedDocuments(int count) {
        Set<String> documents = new LinkedHashSet<String>();
        for (int ordinal = 0; ordinal < count; ordinal++) {
            require(documents.add(generatedDocumentId(ordinal)),
                    "managed-documents generated a duplicate DocumentId");
        }
        return documents.size();
    }

    private static long embeddedEdges(int count) {
        Set<String> activeOccurrences = new LinkedHashSet<String>();
        String source = generatedDocumentId(0);
        for (int ordinal = 0; ordinal < count; ordinal++) {
            String path = String.format(
                    Locale.ROOT, "/edges/e%08d", ordinal);
            require(activeOccurrences.add(source + "\u0000" + path
                            + "\u0000" + 1L),
                    "embedded-edges generated a duplicate active occurrence");
        }
        return activeOccurrences.size();
    }

    private static long cyclicMembers(int count) {
        require(count > 0,
                "cyclic-members generator requires at least one member");
        List<Node> documents = new ArrayList<Node>(count);
        for (int ordinal = 0; ordinal < count; ordinal++) {
            LinkedHashMap<String, Object> document = object();
            document.put("memberIdentity", generatedDocumentId(ordinal));
            document.put("next", reference(
                    "this#" + ((ordinal + 1) % count)));
            documents.add(node(document));
        }
        CyclicSetFinalization result =
                CircularSetIdentityCalculator
                        .calculateCircularSetFinalization(documents);
        require(result.membersInInputOrder().size() == count,
                "cyclic-members finalization lost a member");
        return result.membersInInputOrder().size();
    }

    private static long cyclicEdges(int count, int members) {
        require(members > 0,
                "cyclic-edges generator requires at least one member");
        require(count >= members,
                "cyclic-edges cannot contain fewer edges than its ring");
        long capacity = (long) members * (long) members;
        require(count <= capacity,
                "cyclic-edges exceeds its unique directed-edge capacity");

        List<LinkedHashSet<Integer>> targets =
                new ArrayList<LinkedHashSet<Integer>>(members);
        for (int source = 0; source < members; source++) {
            targets.add(new LinkedHashSet<Integer>());
        }
        int admitted = 0;
        for (int source = 0; source < members && admitted < count; source++) {
            if (targets.get(source).add((source + 1) % members)) {
                admitted++;
            }
        }
        for (int source = 0; source < members && admitted < count; source++) {
            for (int target = 0; target < members && admitted < count;
                 target++) {
                if (targets.get(source).add(target)) {
                    admitted++;
                }
            }
        }
        require(admitted == count,
                "cyclic-edges construction stopped before its target");

        List<Node> documents = new ArrayList<Node>(members);
        int measured = 0;
        for (int source = 0; source < members; source++) {
            LinkedHashMap<String, Object> edges = object();
            for (Integer target : targets.get(source)) {
                edges.put(String.format(
                                Locale.ROOT, "e%08d", target),
                        reference("this#" + target));
                measured++;
            }
            LinkedHashMap<String, Object> document = object();
            document.put("memberIdentity", generatedDocumentId(source));
            document.put("edges", edges);
            documents.add(node(document));
        }
        CyclicSetFinalization result =
                CircularSetIdentityCalculator
                        .calculateCircularSetFinalization(documents);
        require(result.membersInInputOrder().size() == members,
                "cyclic-edges did not retain one complete component");
        require(measured == admitted,
                "cyclic-edges construction lost an occurrence");
        return measured;
    }

    private static long cyclicCanonicalBytes(int count) {
        List<Node> empty = canonicalByteRing("");
        long base = portableCanonicalByteCount(
                CircularSetIdentityCalculator
                        .calculateCircularSetFinalization(empty));
        require(count >= base,
                "cyclic-canonical-bytes target is below its minimum " + base);
        int padding = checkedInt((long) count - base,
                "cyclic-canonical-bytes padding");
        char[] value = new char[padding];
        Arrays.fill(value, 'a');
        CyclicSetFinalization finalization =
                CircularSetIdentityCalculator
                        .calculateCircularSetFinalization(
                                canonicalByteRing(new String(value)));
        return portableCanonicalByteCount(finalization);
    }

    private static long graphChanges(int count) {
        boolean active = false;
        int measured = 0;
        for (int ordinal = 0; ordinal < count; ordinal++) {
            boolean before = active;
            active = !active;
            require(before != active,
                    "graph-changes construction contains a no-op");
            measured++;
        }
        return measured;
    }

    private static long closureExpansions(int count) {
        Set<String> managed = new LinkedHashSet<String>();
        managed.add(generatedDocumentId(0));
        int measured = 0;
        for (int ordinal = 0; ordinal < count; ordinal++) {
            String target = generatedDocumentId((long) ordinal + 1L);
            require(managed.add(target),
                    "closure-expansions reused a managed target");
            measured++;
        }
        require(managed.size() == count + 1,
                "closure-expansions has the wrong final document count");
        return measured;
    }

    private static long workOccurrences(int count) {
        LinkedHashMap<String, Object> invocationValue = object();
        invocationValue.put("kind", "work-occurrences");
        invocationValue.put("count", Long.valueOf(count));
        String invocationIdentity = domainIdentity(
                "blue-contracts-limit-generator-invocation/1.0",
                invocationValue);

        LinkedHashMap<String, Object> scopeValue = object();
        scopeValue.put("documentId", generatedDocumentId(0));
        scopeValue.put("scopePath", "/");
        scopeValue.put("activationGeneration", Long.valueOf(0L));
        String scopeIdentity = domainIdentity(
                "blue-contracts-managed-scope-key/1.0", scopeValue);

        Set<String> workIdentities = new HashSet<String>();
        for (int ordinal = 0; ordinal < count; ordinal++) {
            LinkedHashMap<String, Object> causeValue = object();
            causeValue.put("kind", "work-occurrences");
            causeValue.put("ordinal", Long.valueOf(ordinal));
            String causeIdentity = domainIdentity(
                    "blue-contracts-limit-generator-cause/1.0",
                    causeValue);

            LinkedHashMap<String, Object> workValue = object();
            workValue.put("invocationIdentity", invocationIdentity);
            workValue.put("workOrdinal", Long.valueOf(ordinal));
            workValue.put("workKind", "CONTAINING_REFERENCE_UPDATE");
            workValue.put("targetManagedScopeIdentity", scopeIdentity);
            workValue.put("sourceOccurrenceIdentity", causeIdentity);
            require(workIdentities.add(domainIdentity(
                            "blue-contracts-work-occurrence/1.0", workValue)),
                    "work-occurrences repeated an exact identity");
        }
        return workIdentities.size();
    }

    private static long tentativeFinalizations(int count) {
        Set<String> masters = new HashSet<String>();
        for (int ordinal = 0; ordinal < count; ordinal++) {
            String master = CircularSetIdentityCalculator
                    .calculateCircularSetFinalization(
                            Collections.singletonList(canonicalMember(
                                    generatedDocumentId(0),
                                    "this#0",
                                    Long.valueOf(ordinal))))
                    .masterBlueId();
            require(masters.add(master),
                    "tentative-finalizations repeated an exact state");
        }
        return masters.size();
    }

    private static List<Node> canonicalByteRing(String value) {
        return Arrays.asList(
                canonicalMember("a", "this#1", value),
                canonicalMember("b", "this#0", null));
    }

    /*
     * `value` is a literal object-member name in this normative generator,
     * not the Blue scalar-envelope field. Construct the Node graph directly
     * so a generic wire deserializer cannot reinterpret that member.
     */
    private static Node canonicalMember(
            String memberIdentity,
            String nextBlueId,
            Object literalValue) {
        LinkedHashMap<String, Node> properties =
                new LinkedHashMap<String, Node>();
        properties.put("memberIdentity", new Node().value(memberIdentity));
        properties.put("next", new Node().blueId(nextBlueId));
        if (literalValue != null) {
            properties.put(OBJECT_VALUE, new Node().value(literalValue));
        }
        return new Node().properties(properties);
    }

    private static long portableCanonicalByteCount(
            CyclicSetFinalization finalization) {
        List<Object> members = new ArrayList<Object>();
        for (Node member : finalization.canonicalMemberBodies()) {
            members.add(collapse(NodeWireForm.get(
                    member, NodeWireForm.Strategy.SIMPLE)));
        }
        try {
            String json = UncheckedObjectMapper.JSON_MAPPER
                    .writeValueAsString(members);
            return new JsonCanonicalizer(json).getEncodedUTF8().length;
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "Unable to canonicalize cyclic limit construction",
                    failure);
        }
    }

    private static Object collapse(Object value) {
        if (value instanceof List) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (List<?>) value) {
                result.add(containsCyclicReference(item)
                        ? collapse(item) : exactReference(item));
            }
            return result;
        }
        if (value instanceof Map) {
            Map<String, Object> selected = stringMap(value);
            if (selected.size() == 1
                    && selected.containsKey(OBJECT_BLUE_ID)) {
                return portableCopy(selected);
            }
            LinkedHashMap<String, Object> result = object();
            for (Map.Entry<String, Object> entry : selected.entrySet()) {
                String key = entry.getKey();
                Object child = entry.getValue();
                if ("name".equals(key)
                        || "description".equals(key)
                        || OBJECT_VALUE.equals(key)) {
                    result.put(key, portableCopy(child));
                } else if (containsCyclicReference(child)) {
                    result.put(key, collapse(child));
                } else {
                    result.put(key, exactReference(child));
                }
            }
            return result;
        }
        return portableCopy(value);
    }

    private static Map<String, Object> exactReference(Object value) {
        LinkedHashMap<String, Object> result = object();
        result.put(OBJECT_BLUE_ID, DirectBlueIdCalculator.calculateBlueId(
                node(value)));
        return result;
    }

    private static boolean containsCyclicReference(Object value) {
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (containsCyclicReference(item)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Map) {
            for (Map.Entry<String, Object> entry
                    : stringMap(value).entrySet()) {
                if (OBJECT_BLUE_ID.equals(entry.getKey())
                        && entry.getValue() instanceof String
                        && ((String) entry.getValue()).startsWith("this#")) {
                    return true;
                }
                if (containsCyclicReference(entry.getValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Object portableCopy(Object value) {
        if (value instanceof List) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (List<?>) value) {
                result.add(portableCopy(item));
            }
            return result;
        }
        if (value instanceof Map) {
            LinkedHashMap<String, Object> result = object();
            for (Map.Entry<String, Object> entry
                    : stringMap(value).entrySet()) {
                result.put(entry.getKey(), portableCopy(entry.getValue()));
            }
            return result;
        }
        return value;
    }

    private static String domainIdentity(String domain, Object value) {
        LinkedHashMap<String, Object> envelope = object();
        envelope.put("domain", domain);
        envelope.put(OBJECT_VALUE, value);
        try {
            String json = UncheckedObjectMapper.JSON_MAPPER
                    .writeValueAsString(envelope);
            byte[] canonical = new JsonCanonicalizer(json).getEncodedUTF8();
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical);
            StringBuilder result = new StringBuilder("sha256:");
            for (byte item : digest) {
                result.append(String.format(
                        Locale.ROOT, "%02x", item & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        } catch (Exception failure) {
            throw new IllegalArgumentException(
                    "Unable to canonicalize limit generator identity",
                    failure);
        }
    }

    private static String generatedDocumentId(long ordinal) {
        require(ordinal >= 0L && ordinal <= 99999999L,
                "generated DocumentId ordinal out of range: " + ordinal);
        return String.format(Locale.ROOT, "document-%08d", ordinal);
    }

    private static Node node(Object value) {
        return UncheckedObjectMapper.JSON_MAPPER.convertValue(
                value, Node.class);
    }

    private static Map<String, Object> reference(String blueId) {
        LinkedHashMap<String, Object> result = object();
        result.put(OBJECT_BLUE_ID, blueId);
        return result;
    }

    private static LinkedHashMap<String, Object> object() {
        return new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static JsonNode requiredObject(JsonNode value, String field) {
        JsonNode child = value == null ? null : value.get(field);
        requireObject(child, field);
        return child;
    }

    private static void requireObject(JsonNode value, String field) {
        if (value == null || !value.isObject()) {
            throw new IllegalArgumentException(field + " must be an object");
        }
    }

    private static String requiredText(JsonNode value, String field) {
        JsonNode child = value == null ? null : value.get(field);
        if (child == null || !child.isTextual() || child.asText().isEmpty()) {
            throw new IllegalArgumentException(
                    field + " must be non-empty text");
        }
        return child.asText();
    }

    private static int nonNegativeInt(JsonNode value, String field) {
        JsonNode child = value == null ? null : value.get(field);
        if (child == null || !child.isIntegralNumber()
                || !child.canConvertToInt() || child.intValue() < 0) {
            throw new IllegalArgumentException(
                    field + " must be a non-negative 32-bit integer");
        }
        return child.intValue();
    }

    private static int checkedInt(long value, String field) {
        if (value < 0L || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    field + " is outside the constructive range");
        }
        return (int) value;
    }

    private static void requireExactFields(
            JsonNode object,
            List<String> expected,
            String kind) {
        Set<String> actual = new LinkedHashSet<String>();
        object.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(new LinkedHashSet<String>(expected))) {
            throw new IllegalArgumentException(
                    kind + " parameters expected " + expected
                            + " but found " + actual);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    private static Map<String, GeneratorSpec> generators() {
        LinkedHashMap<String, GeneratorSpec> result =
                new LinkedHashMap<String, GeneratorSpec>();
        add(result, "managedDocumentsPerClosure", "managed-documents",
                "ManagedDocumentsPerClosureExceeded", "count");
        add(result, "processEmbeddedEdgesPerClosure", "embedded-edges",
                "ProcessEmbeddedEdgesPerClosureExceeded", "count");
        add(result, "cyclicMembersPerComponent", "cyclic-members",
                "CyclicComponentMemberLimitExceeded", "count");
        add(result, "cyclicEdgesPerComponent", "cyclic-edges",
                "CyclicComponentEdgeLimitExceeded", "count", "members");
        add(result, "cyclicCanonicalBytesPerComponent",
                "cyclic-canonical-bytes",
                "CyclicComponentCanonicalBytesExceeded", "count");
        add(result, "closureGraphChangesPerInvocation", "graph-changes",
                "ClosureGraphChangeLimitExceeded", "count");
        add(result, "closureExpansionsPerInvocation", "closure-expansions",
                "ClosureExpansionLimitExceeded", "count");
        add(result, "closureWorkOccurrencesPerInvocation", "work-occurrences",
                "ClosureWorkOccurrenceLimitExceeded", "count");
        add(result, "closureTentativeFinalizationsPerInvocation",
                "tentative-finalizations",
                "ClosureTentativeFinalizationLimitExceeded", "count");
        return Collections.unmodifiableMap(result);
    }

    private static void add(
            Map<String, GeneratorSpec> target,
            String limit,
            String kind,
            String diagnostic,
            String... parameters) {
        target.put(limit, new GeneratorSpec(
                kind, diagnostic, Arrays.asList(parameters)));
    }

    private static final class GeneratorSpec {
        private final String kind;
        private final String diagnostic;
        private final List<String> parameterNames;

        private GeneratorSpec(
                String kind,
                String diagnostic,
                List<String> parameterNames) {
            this.kind = kind;
            this.diagnostic = diagnostic;
            this.parameterNames = Collections.unmodifiableList(
                    new ArrayList<String>(parameterNames));
        }
    }
}
