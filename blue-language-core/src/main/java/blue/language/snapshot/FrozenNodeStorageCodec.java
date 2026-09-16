package blue.language.snapshot;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.*;

import static blue.language.snapshot.ExactNodeStorageCodec.*;

/**
 * Exact physical FrozenNode transport, retaining each node's construction and
 * previous-anchor modes, schema and resolved metadata. Back references retain
 * immutable subgraph sharing. Derived flags are recomputed by the normal
 * builder and exact encoding is checked after restoration.
 * Operational byte/traversal bounds and the trusted-storage contract are the same
 * as {@link ExactNodeStorageCodec}; this format never grants provider authority.
 * Previously encoded subgraphs use one back-reference token and are not traversed
 * again. The traversal bound is not a limit on the logical depth of a shared graph.
 */
public final class FrozenNodeStorageCodec {
    /** Versioned physical format binding. */
    public static final String FORMAT = "blue-language/frozen-node-storage/2";
    private final ExactNodeStorageCodec nodes;

    /**
     * Creates an exact frozen codec with operational bounds.
     * @param maximumBytes maximum complete encoded bytes
     * @param maximumDepth maximum physical codec traversal depth, from 1 through 256;
     *                     a back reference is one token, not another subtree traversal
     */
    public FrozenNodeStorageCodec(int maximumBytes, int maximumDepth) {
        nodes = new ExactNodeStorageCodec(maximumBytes, maximumDepth);
    }

    /**
     * Encodes every immutable node field and shared child relationship.
     * @param value exact immutable root
     * @return complete checksummed bytes
     */
    public byte[] encode(FrozenNode value) {
        Objects.requireNonNull(value, "value");
        return nodes.encodeEnvelope(FORMAT, out -> {
            write(out, value, 0, new IdentityHashMap<FrozenNode, Integer>());
        });
    }

    /**
     * Restores an exact frozen graph from authenticated storage bytes.
     * @param bytes complete physical bytes
     * @return immutable exact root
     */
    public FrozenNode decode(byte[] bytes) {
        FrozenNode value = nodes.decodeEnvelope(bytes, FORMAT, in -> {
            FrozenNode restored = read(in, 0, new ArrayList<FrozenNode>());
            if (restored == null) throw invalid("Missing frozen root");
            return restored;
        });
        if (!Arrays.equals(bytes, encode(value))) throw invalid("Noncanonical frozen storage");
        return value;
    }

    private void write(DataOutputStream out, FrozenNode node, int depth,
                       IdentityHashMap<FrozenNode, Integer> completed) throws IOException {
        nodes.depth(depth);
        if (node == null) { out.writeByte(0); return; }
        Integer known = completed.get(node);
        if (known != null) { out.writeByte(1); out.writeInt(known); return; }
        out.writeByte(2);
        out.writeBoolean(node.strictCanonical); out.writeBoolean(node.strictBlueIdValidation);
        out.writeBoolean(node.previousAnchorContext);
        writeText(out, node.name); writeText(out, node.description);
        write(out, node.type, depth + 1, completed); write(out, node.itemType, depth + 1, completed);
        write(out, node.keyType, depth + 1, completed); write(out, node.valueType, depth + 1, completed);
        nodes.writeValue(out, node.value, depth + 1);
        out.writeInt(node.items == null ? -1 : node.items.size());
        if (node.items != null) for (FrozenNode item : node.items) write(out, item, depth + 1, completed);
        out.writeInt(node.properties == null ? -1 : node.properties.size());
        if (node.properties != null) for (Map.Entry<String, FrozenNode> property : node.properties.entrySet()) {
            writeText(out, property.getKey()); write(out, property.getValue(), depth + 1, completed);
        }
        write(out, node.contracts, depth + 1, completed); writeText(out, node.referenceBlueId);
        nodes.writeSchema(out, node.schema, depth + 1); writeText(out, node.mergePolicy);
        writeText(out, node.previousBlueId); out.writeBoolean(node.position != null);
        if (node.position != null) out.writeInt(node.position);
        write(out, node.blue, depth + 1, completed); out.writeBoolean(node.inlineValue);
        completed.put(node, completed.size());
    }

    private FrozenNode read(DataInputStream in, int depth, List<FrozenNode> completed) throws IOException {
        nodes.depth(depth);
        int tag = in.readUnsignedByte();
        if (tag == 0) return null;
        if (tag == 1) {
            int id = in.readInt();
            if (id < 0 || id >= completed.size()) throw invalid("Invalid frozen back reference");
            return completed.get(id);
        }
        if (tag != 2) throw invalid("Unknown frozen node tag");
        FrozenNodeBuilder builder = FrozenNodeBuilder.builder().deferBlueId()
                .strictCanonical(readBoolean(in)).strictBlueIdValidation(readBoolean(in))
                .previousAnchorContext(readBoolean(in)).name(readText(in)).description(readText(in))
                .type(read(in, depth + 1, completed)).itemType(read(in, depth + 1, completed))
                .keyType(read(in, depth + 1, completed)).valueType(read(in, depth + 1, completed))
                .value(nodes.readValue(in, depth + 1));
        int items = count(in, true);
        if (items >= 0) {
            List<FrozenNode> values = new ArrayList<>();
            for (int i = 0; i < items; i++) values.add(read(in, depth + 1, completed));
            builder.items(values);
        }
        int properties = count(in, true);
        if (properties >= 0) {
            Map<String, FrozenNode> values = new LinkedHashMap<>();
            for (int i = 0; i < properties; i++) {
                String key = requiredText(in);
                if (values.containsKey(key)) throw invalid("Duplicate frozen property");
                values.put(key, read(in, depth + 1, completed));
            }
            builder.properties(values);
        }
        builder.contracts(read(in, depth + 1, completed)).referenceBlueId(readText(in))
                .schema(nodes.readSchema(in, depth + 1)).mergePolicy(readText(in)).previousBlueId(readText(in));
        if (readBoolean(in)) builder.position(in.readInt());
        FrozenNode value = builder.blue(read(in, depth + 1, completed)).inlineValue(readBoolean(in)).build();
        completed.add(value);
        return value;
    }
}
