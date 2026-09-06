package blue.language.processor.closure;

import blue.language.model.wire.BlueLanguageConstants;
import blue.language.model.wire.SchemaPropertyConstants;
import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.processor.ExecutionEvidenceUnavailableException;
import blue.language.processor.InvalidExecutionEvidenceException;
import blue.language.processor.NoncommittingExecutionException;
import blue.language.snapshot.FrozenNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Lossless, bounded content-addressed fragments. Transport digests are not document BlueIds. */
public final class FrozenNodeEvidenceCodec {
    @FunctionalInterface public interface Writer { void write(String identity, byte[] bytes); }
    @FunctionalInterface public interface Reader { byte[] read(String identity); }
    public static final class Limits {
        public final int fragmentBytes, depth, nodes;
        public final long totalBytes;
        public Limits(int fragmentBytes, long totalBytes, int depth, int nodes) {
            if (fragmentBytes < 1 || totalBytes < 1 || depth < 1 || nodes < 1) throw new IllegalArgumentException("Positive capacity required");
            this.fragmentBytes = fragmentBytes; this.totalBytes = totalBytes; this.depth = depth; this.nodes = nodes;
        }
        public static Limits defaults() { return new Limits(16 * 1024 * 1024, 256L * 1024 * 1024, 256, 1_000_000); }
    }
    /** Operational capacity, never a deterministic input disposition or gas outcome. */
    public static final class CapacityExceeded extends NoncommittingExecutionException {
        private static final long serialVersionUID = 1L;
        CapacityExceeded(String message) { super(message); }
    }
    static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private FrozenNodeEvidenceCodec() { }

    public static String encode(FrozenNode node, Writer writer, Limits limits) {
        return new Encoder(writer, limits).node(node);
    }
    public static FrozenNode decode(String identity, Reader reader, Limits limits) {
        return new Decoder(reader, limits).node(identity);
    }

    /** One shared acquisition budget and deduplication context for a complete receipt. */
    public static final class Encoder {
        private final Writer writer;
        private final Limits limits;
        private final Map<FrozenNode, String> identities = new IdentityHashMap<FrozenNode, String>();
        private final Set<String> written = new HashSet<String>();
        private final Map<String, Integer> heights = new HashMap<String, Integer>();
        long total;
        public Encoder(Writer writer, Limits limits) { this.writer = Objects.requireNonNull(writer); this.limits = Objects.requireNonNull(limits); }
        Limits limits() { return limits; }
        public String node(FrozenNode node) { return node(node, 0); }
        private String node(FrozenNode node, int depth) {
            if (node == null) return null;
            checkDepth(depth, 0, limits);
            String known = identities.get(node);
            if (known != null) { checkDepth(depth, heights.get(known), limits); return known; }
            Map<String, Object> value = new TreeMap<String, Object>();
            value.put("format", "blue-frozen-node-poc-1");
            value.put("canonical", node.isStrictCanonical()); value.put("validateBlueIds", node.isStrictBlueIdValidation());
            value.put("listElement", node.isListElementContext());
            value.put("name", node.getName()); value.put("description", node.getDescription());
            value.put("reference", node.getReferenceBlueId()); value.put("previous", node.getPreviousBlueId());
            value.put("position", node.getPosition()); value.put(BlueLanguageConstants.OBJECT_MERGE_POLICY, node.getMergePolicy());
            value.put("inline", node.isInlineValue()); value.put(BlueLanguageConstants.OBJECT_VALUE, scalar(node.getValue()));
            value.put(BlueLanguageConstants.OBJECT_TYPE, node(node.getType(), depth + 1)); value.put(BlueLanguageConstants.OBJECT_ITEM_TYPE, node(node.getItemType(), depth + 1));
            value.put(BlueLanguageConstants.OBJECT_KEY_TYPE, node(node.getKeyType(), depth + 1)); value.put(BlueLanguageConstants.OBJECT_VALUE_TYPE, node(node.getValueType(), depth + 1));
            value.put(BlueLanguageConstants.OBJECT_BLUE, node(node.getBlue(), depth + 1)); value.put(BlueLanguageConstants.OBJECT_CONTRACTS, node(node.getContracts(), depth + 1));
            List<String> items = null;
            if (node.getItems() != null) { items = new ArrayList<String>(); for (FrozenNode item : node.getItems()) items.add(node(item, depth + 1)); }
            Map<String, String> properties = null;
            if (node.getProperties() != null) { properties = new TreeMap<String, String>(); for (Map.Entry<String, FrozenNode> p : node.getProperties().entrySet()) properties.put(p.getKey(), node(p.getValue(), depth + 1)); }
            value.put(BlueLanguageConstants.OBJECT_ITEMS, items); value.put("properties", properties);
            value.put(BlueLanguageConstants.OBJECT_SCHEMA, schema(node.getSchema(), depth + 1));
            byte[] encoded = bytes(value);
            int height = fragmentHeight(json(encoded), heights);
            checkDepth(depth, height, limits);
            String id = blob(encoded); heights.put(id, height); identities.put(node, id); return id;
        }
        public String blob(byte[] bytes) {
            String id = digest(bytes);
            if (written.add(id)) {
                total = Math.addExact(total, bytes.length);
                if (bytes.length > limits.fragmentBytes || total > limits.totalBytes || written.size() > limits.nodes)
                    throw new CapacityExceeded("Exact evidence exceeds acquisition capacity");
                writer.write(id, bytes.clone());
            }
            return id;
        }
        private String n(Node node, int depth) { return node == null ? null : node(FrozenNode.fromResolvedNode(node), depth); }
        private Map<String, Object> schema(Schema s, int depth) {
            if (s == null) return null;
            Map<String, Object> m = new TreeMap<String, Object>(); m.put("reference", s.getBlueId());
            m.put(SchemaPropertyConstants.KEY_REQUIRED, n(s.getRequired(), depth)); m.put(SchemaPropertyConstants.KEY_MIN_LENGTH, n(s.getMinLength(), depth)); m.put(SchemaPropertyConstants.KEY_MAX_LENGTH, n(s.getMaxLength(), depth));
            m.put(SchemaPropertyConstants.KEY_MINIMUM, n(s.getMinimum(), depth)); m.put(SchemaPropertyConstants.KEY_MAXIMUM, n(s.getMaximum(), depth));
            m.put(SchemaPropertyConstants.KEY_EXCLUSIVE_MINIMUM, n(s.getExclusiveMinimum(), depth)); m.put(SchemaPropertyConstants.KEY_EXCLUSIVE_MAXIMUM, n(s.getExclusiveMaximum(), depth));
            m.put(SchemaPropertyConstants.KEY_MULTIPLE_OF, n(s.getMultipleOf(), depth)); m.put(SchemaPropertyConstants.KEY_MIN_ITEMS, n(s.getMinItems(), depth)); m.put(SchemaPropertyConstants.KEY_MAX_ITEMS, n(s.getMaxItems(), depth));
            m.put(SchemaPropertyConstants.KEY_UNIQUE_ITEMS, n(s.getUniqueItems(), depth)); m.put(SchemaPropertyConstants.KEY_MIN_FIELDS, n(s.getMinFields(), depth)); m.put(SchemaPropertyConstants.KEY_MAX_FIELDS, n(s.getMaxFields(), depth));
            List<String> values = null; if (s.getEnum() != null) { values = new ArrayList<String>(); for (Node v : s.getEnum()) values.add(n(v, depth)); }
            m.put(SchemaPropertyConstants.KEY_ENUM, values); return m;
        }
    }

    /** One shared verification/capacity context; callers supply authenticated root identities. */
    public static final class Decoder {
        private final Reader reader;
        private final Limits limits;
        private final Map<String, FrozenNode> nodes = new HashMap<String, FrozenNode>();
        private final Map<String, Long> expanded = new HashMap<String, Long>();
        private final Map<String, Integer> heights = new HashMap<String, Integer>();
        private final Map<String, byte[]> blobs = new HashMap<String, byte[]>();
        private final Set<String> active = new HashSet<String>();
        long total;
        private long materializedNodes;
        public Decoder(Reader reader, Limits limits) { this.reader = Objects.requireNonNull(reader); this.limits = Objects.requireNonNull(limits); }
        Limits limits() { return limits; }
        public FrozenNode node(String id) { return node(id, 0); }
        private FrozenNode node(String id, int depth) {
            if (id == null) return null;
            checkDepth(depth, 0, limits);
            FrozenNode known = nodes.get(id);
            if (known != null) { checkDepth(depth, heights.get(id), limits); return known; }
            if (!active.add(id)) throw invalid("Cyclic physical node fragments; semantic cycles must use exact Blue references");
            try {
                JsonNode m = json(verifiedBlob(id));
                if (!"blue-frozen-node-poc-1".equals(text(m, "format"))) throw invalid("Unexpected exact node format");
                Node n = new Node().name(text(m, "name")).description(text(m, "description"))
                        .blueId(text(m, "reference")).previousBlueId(text(m, "previous"))
                        .mergePolicy(text(m, BlueLanguageConstants.OBJECT_MERGE_POLICY)).inlineValue(m.path("inline").asBoolean());
                if (!m.path("position").isNull()) n.position(m.path("position").intValue());
                n.value(scalar(m.get(BlueLanguageConstants.OBJECT_VALUE)));
                Map<String,FrozenNode> attributes=new LinkedHashMap<String,FrozenNode>(); long expandedCount=1;
                for(String key:Arrays.asList(BlueLanguageConstants.OBJECT_TYPE,BlueLanguageConstants.OBJECT_ITEM_TYPE,BlueLanguageConstants.OBJECT_KEY_TYPE,BlueLanguageConstants.OBJECT_VALUE_TYPE,BlueLanguageConstants.OBJECT_BLUE,BlueLanguageConstants.OBJECT_CONTRACTS)) {
                    String ref=text(m,key);attributes.put(key,node(ref,depth+1));expandedCount=addExpanded(expandedCount,ref);
                }
                List<FrozenNode> items=null;
                if (!m.path(BlueLanguageConstants.OBJECT_ITEMS).isNull()) { items=new ArrayList<FrozenNode>(); for(JsonNode r:m.path(BlueLanguageConstants.OBJECT_ITEMS)) {String ref=nullable(r);items.add(node(ref,depth+1));expandedCount=addExpanded(expandedCount,ref);} }
                Map<String,FrozenNode> properties=null;
                if (!m.path("properties").isNull()) {properties=new LinkedHashMap<String,FrozenNode>();Iterator<Map.Entry<String,JsonNode>> it=m.path("properties").fields();while(it.hasNext()){Map.Entry<String,JsonNode> p=it.next();String ref=nullable(p.getValue());properties.put(p.getKey(),node(ref,depth+1));expandedCount=addExpanded(expandedCount,ref);}}
                long priorMaterialized=materializedNodes;
                n.schema(schema(m.get(BlueLanguageConstants.OBJECT_SCHEMA), depth));
                expandedCount=Math.addExact(expandedCount,materializedNodes-priorMaterialized);
                if(expandedCount>limits.nodes)throw new CapacityExceeded("Expanded exact node exceeds materialization capacity");
                int height = fragmentHeight(m, heights);
                checkDepth(depth, height, limits);
                FrozenNode result=blue.language.snapshot.FrozenNodeBuilder.fromRetainedParts(FrozenNode.fromResolvedNode(n),attributes,items,properties,
                        m.path("canonical").asBoolean(),m.path("validateBlueIds").asBoolean(),m.path("listElement").asBoolean());
                expanded.put(id,expandedCount);heights.put(id,height);nodes.put(id,result);return result;
            } finally { active.remove(id); }
        }
        public byte[] blob(String id) {
            return verifiedBlob(id).clone();
        }
        private byte[] verifiedBlob(String id) {
            if (id == null || !id.matches("[0-9a-f]{64}")) throw invalid("Invalid exact transport SHA-256 identity");
            byte[] known = blobs.get(id); if (known != null) return known;
            byte[] bytes = reader.read(id);
            if (bytes == null) throw new ExecutionEvidenceUnavailableException("Exact source program fragment unavailable", Collections.singleton(id));
            total = Math.addExact(total, bytes.length);
            if (bytes.length > limits.fragmentBytes || total > limits.totalBytes || blobs.size() >= limits.nodes)
                throw new CapacityExceeded("Exact evidence exceeds acquisition capacity");
            if (!digest(bytes).equals(id)) throw invalid("Exact source program fragment digest mismatch");
            byte[] stable = bytes.clone(); blobs.put(id, stable); return stable;
        }
        private long addExpanded(long count,String ref) {long next=count+(ref==null?0:expanded.get(ref));if(next>limits.nodes)throw new CapacityExceeded("Expanded exact node exceeds materialization capacity");return next;}
        public Node materialize(String id) {node(id);return materializeKnown(id);}
        private Node materializeKnown(String id) {
            if(id==null)return null;
            materializedNodes=Math.addExact(materializedNodes,expanded.get(id));
            if(materializedNodes>limits.nodes)throw new CapacityExceeded("Exact evidence copy budget exceeded");
            return nodes.get(id).toNode();
        }
        private Node child(JsonNode m, String key, int depth) { String id=text(m,key);node(id,depth+1);return materializeKnown(id); }
        private Schema schema(JsonNode m, int depth) {
            if (m == null || m.isNull()) return null;
            Schema s = new Schema().blueId(text(m, "reference"));
            s.required(child(m,SchemaPropertyConstants.KEY_REQUIRED,depth)).minLength(child(m,SchemaPropertyConstants.KEY_MIN_LENGTH,depth)).maxLength(child(m,SchemaPropertyConstants.KEY_MAX_LENGTH,depth));
            s.minimum(child(m,SchemaPropertyConstants.KEY_MINIMUM,depth)).maximum(child(m,SchemaPropertyConstants.KEY_MAXIMUM,depth));
            s.exclusiveMinimum(child(m,SchemaPropertyConstants.KEY_EXCLUSIVE_MINIMUM,depth)).exclusiveMaximum(child(m,SchemaPropertyConstants.KEY_EXCLUSIVE_MAXIMUM,depth));
            s.multipleOf(child(m,SchemaPropertyConstants.KEY_MULTIPLE_OF,depth)).minItems(child(m,SchemaPropertyConstants.KEY_MIN_ITEMS,depth)).maxItems(child(m,SchemaPropertyConstants.KEY_MAX_ITEMS,depth));
            s.uniqueItems(child(m,SchemaPropertyConstants.KEY_UNIQUE_ITEMS,depth)).minFields(child(m,SchemaPropertyConstants.KEY_MIN_FIELDS,depth)).maxFields(child(m,SchemaPropertyConstants.KEY_MAX_FIELDS,depth));
            if (!m.path(SchemaPropertyConstants.KEY_ENUM).isNull()) { List<Node> values = new ArrayList<Node>(); for (JsonNode ref : m.path(SchemaPropertyConstants.KEY_ENUM)) {String id=nullable(ref);node(id,depth+1);values.add(materializeKnown(id));} s.enumValues(values); }
            return s;
        }
    }

    private static void checkDepth(int depth, int height, Limits limits) {
        // This is an operational stack-safety ceiling, not a semantic content or gas limit.
        // An iterative codec may raise it without changing any encoded identities.
        if ((long) depth + height > Math.min(limits.depth, 256))
            throw new CapacityExceeded("Exact node depth exceeds acquisition/stack-safety capacity");
    }

    private static int fragmentHeight(JsonNode node, Map<String, Integer> heights) {
        List<String> children = new ArrayList<String>();
        for (String key : Arrays.asList(BlueLanguageConstants.OBJECT_TYPE, BlueLanguageConstants.OBJECT_ITEM_TYPE, BlueLanguageConstants.OBJECT_KEY_TYPE, BlueLanguageConstants.OBJECT_VALUE_TYPE, BlueLanguageConstants.OBJECT_BLUE, BlueLanguageConstants.OBJECT_CONTRACTS))
            children.add(text(node, key));
        for (JsonNode value : node.path(BlueLanguageConstants.OBJECT_ITEMS)) children.add(nullable(value));
        for (JsonNode value : node.path("properties")) children.add(nullable(value));
        JsonNode schema = node.path(BlueLanguageConstants.OBJECT_SCHEMA);
        for (String key : Arrays.asList(SchemaPropertyConstants.KEY_REQUIRED, SchemaPropertyConstants.KEY_MIN_LENGTH, SchemaPropertyConstants.KEY_MAX_LENGTH, SchemaPropertyConstants.KEY_MINIMUM, SchemaPropertyConstants.KEY_MAXIMUM,
                SchemaPropertyConstants.KEY_EXCLUSIVE_MINIMUM, SchemaPropertyConstants.KEY_EXCLUSIVE_MAXIMUM, SchemaPropertyConstants.KEY_MULTIPLE_OF, SchemaPropertyConstants.KEY_MIN_ITEMS, SchemaPropertyConstants.KEY_MAX_ITEMS,
                SchemaPropertyConstants.KEY_UNIQUE_ITEMS, SchemaPropertyConstants.KEY_MIN_FIELDS, SchemaPropertyConstants.KEY_MAX_FIELDS)) children.add(text(schema, key));
        for (JsonNode value : schema.path(SchemaPropertyConstants.KEY_ENUM)) children.add(nullable(value));
        int height = 0;
        for (String child : children) if (child != null) height = Math.max(height, heights.get(child) + 1);
        return height;
    }

    static byte[] bytes(Object value) {
        try { return JSON.writeValueAsBytes(value); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Cannot encode exact evidence", failure); }
    }
    static JsonNode json(byte[] bytes) {
        try { return JSON.readTree(bytes); }
        catch (java.io.IOException failure) { throw invalid("Malformed exact evidence JSON"); }
    }
    static String text(JsonNode value, String field) { return nullable(value.get(field)); }
    static String nullable(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw invalid("Expected exact evidence text");
        return value.textValue();
    }
    static Node mutable(FrozenNode n) { return n == null ? null : n.toNode(); }
    static InvalidExecutionEvidenceException invalid(String message) { return new InvalidExecutionEvidenceException(message); }
    public static String digest(byte[] bytes) {
        try { byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes); char[] out = new char[64]; char[] hex = "0123456789abcdef".toCharArray(); for (int i = 0; i < hash.length; i++) { out[2*i] = hex[(hash[i] & 255) >>> 4]; out[2*i+1] = hex[hash[i] & 15]; } return new String(out); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static Object scalar(Object value) {
        if (value == null) return null;
        Map<String, Object> m = new TreeMap<String, Object>();
        if (value instanceof String) { m.put("kind", "text"); m.put(BlueLanguageConstants.OBJECT_VALUE, value); }
        else if (value instanceof Boolean) { m.put("kind", "boolean"); m.put(BlueLanguageConstants.OBJECT_VALUE, value); }
        else if (value instanceof BigDecimal || value instanceof Double || value instanceof Float) { m.put("kind", "decimal"); m.put(BlueLanguageConstants.OBJECT_VALUE, value.toString()); }
        else if (value instanceof Byte) { m.put("kind", "byte"); m.put(BlueLanguageConstants.OBJECT_VALUE, value.toString()); }
        else if (value instanceof Short) { m.put("kind", "short"); m.put(BlueLanguageConstants.OBJECT_VALUE, value.toString()); }
        else if (value instanceof BigInteger || value instanceof Integer || value instanceof Long) { m.put("kind", "integer"); m.put(BlueLanguageConstants.OBJECT_VALUE, value.toString()); }
        else throw invalid("Unsupported exact scalar kind");
        return m;
    }
    private static Object scalar(JsonNode m) {
        if (m == null || m.isNull()) return null;
        switch (text(m, "kind")) {
            case "text": return text(m, BlueLanguageConstants.OBJECT_VALUE);
            case "boolean": if (!m.path(BlueLanguageConstants.OBJECT_VALUE).isBoolean()) throw invalid("Expected boolean scalar"); return m.path(BlueLanguageConstants.OBJECT_VALUE).booleanValue();
            case "integer": return new BigInteger(text(m, BlueLanguageConstants.OBJECT_VALUE));
            case "byte": return Byte.valueOf(text(m, BlueLanguageConstants.OBJECT_VALUE));
            case "short": return Short.valueOf(text(m, BlueLanguageConstants.OBJECT_VALUE));
            case "decimal": return new BigDecimal(text(m, BlueLanguageConstants.OBJECT_VALUE));
            default: throw invalid("Unsupported exact scalar kind");
        }
    }
}
