package blue.language.processor.closure;

import blue.language.model.wire.BlueLanguageConstants;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.DocumentProcessor;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import static blue.language.processor.closure.FrozenNodeEvidenceCodec.*;

/**
 * Bounded bodyless transport of previously verified owning component authority.
 *
 * <p>The decoder's root digest MUST come from an authenticated committed
 * receipt/state association. Hash verification detects damaged fragments; it
 * does not establish that arbitrary caller-authored headers were ever verified
 * or executed. Coordination checks the enclosing receipt's selected member
 * state and authority-root association before calling this decoder. The closed
 * Language decoder checks all reconstructible internal identities and shapes.
 * Neither document bodies nor cyclic proof payloads are encoded.</p>
 */
public final class ReusableComponentAuthorityCodec {
    private static final String FORMAT = "blue-reusable-component-authority-poc-1";
    private ReusableComponentAuthorityCodec() { }

    public static String encode(ReusableComponentAuthority authority, Writer writer, Limits limits) {
        return encode(authority, new Encoder(writer, limits));
    }
    public static String encode(ReusableComponentAuthority authority, Encoder encoder) {
        List<Object> headers = new ArrayList<Object>(), rows = new ArrayList<Object>();
        capacity(authority.transportHeaders().size() + authority.outgoingBindings().size(), encoder.limits());
        for (ReusableComponentAuthority.Header header : authority.transportHeaders().values()) {
            headers.add(map("lineage", header.documentId.value(), BlueLanguageConstants.OBJECT_BLUE_ID, header.blueId,
                    ProcessorContractConstants.KEY_INITIALIZED, header.initialized, ProcessorContractConstants.KEY_TERMINATED, header.terminated, "publicRoot", header.publicRoot,
                    "epoch", header.epoch, "generation", header.generation, "bodyIdentity", header.exactBodyIdentity,
                    "rootMetadata", header.metadata == null ? null : RootChannelMetadataCodec.encode(header.metadata, encoder)));
        }
        for (ManagedOccurrenceBinding row : authority.outgoingBindings()) rows.add(map(
                "occurrence", row.occurrenceIdentity(), "binding", row.bindingIdentity(), "policy", row.bindingPolicyIdentity(),
                "source", row.sourceDocumentId().value(), "path", row.sourcePath(), "activation", row.activationGeneration(),
                "target", row.targetDocumentId().value(), "expected", row.expectedTargetBlueId(),
                "active", row.active(), "pendingEpoch", row.pendingHistoricalEpoch()));
        Map<String, Object> indexes = new TreeMap<String, Object>(), preliminary = new TreeMap<String, Object>();
        for (Map.Entry<DocumentId, Integer> entry : authority.canonicalMemberIndexes().entrySet()) indexes.put(entry.getKey().value(), entry.getValue());
        for (Map.Entry<DocumentId, String> entry : authority.preliminaryBlueIds().entrySet()) preliminary.put(entry.getKey().value(), entry.getValue());
        return encoder.blob(bytes(map("format", FORMAT, "component", component(authority.component()),
                "canonicalComponent", component(authority.canonicalSemanticAuthority().component()),
                "members", headers, "bindings", rows, "canonicalIndexes", indexes, "preliminaryBlueIds", preliminary)));
    }

    /** The trusted root must be obtained from the enclosing committed owning receipt, not its untrusted payload. */
    public static ReusableComponentAuthority decode(String trustedAuthorityRoot, Reader reader, Limits limits, DocumentProcessor processor) {
        return decode(trustedAuthorityRoot, new Decoder(reader, limits), processor);
    }
    public static ReusableComponentAuthority decode(String trustedAuthorityRoot, Decoder decoder, DocumentProcessor processor) {
        JsonNode root = json(decoder.blob(trustedAuthorityRoot));
        fields(root, "format", "component", "canonicalComponent", "members", "bindings", "canonicalIndexes", "preliminaryBlueIds");
        if (!FORMAT.equals(text(root, "format"))) throw invalid("Unexpected reusable component authority format");
        JsonNode memberRows = array(root, "members"), bindingRows = array(root, "bindings");
        capacity((long) memberRows.size() + bindingRows.size(), decoder.limits());
        ComponentSnapshot component = component(root.get("component")), canonical = component(root.get("canonicalComponent"));
        Map<DocumentId, ReusableComponentAuthority.Header> headers = new LinkedHashMap<DocumentId, ReusableComponentAuthority.Header>();
        DocumentId previous = null;
        for (JsonNode row : memberRows) {
            fields(row, "lineage", BlueLanguageConstants.OBJECT_BLUE_ID, ProcessorContractConstants.KEY_INITIALIZED, ProcessorContractConstants.KEY_TERMINATED, "publicRoot", "epoch", "generation", "bodyIdentity", "rootMetadata");
            DocumentId member = new DocumentId(text(row, "lineage"));
            if (previous != null && previous.compareTo(member) >= 0) throw invalid("Member headers must be unique and canonically ordered");
            previous = member;
            String metadata = text(row, "rootMetadata");
            headers.put(member, new ReusableComponentAuthority.Header(member, text(row, BlueLanguageConstants.OBJECT_BLUE_ID), bool(row, ProcessorContractConstants.KEY_INITIALIZED),
                    bool(row, ProcessorContractConstants.KEY_TERMINATED), bool(row, "publicRoot"), integer(row, "epoch"), integer(row, "generation"),
                    text(row, "bodyIdentity"), metadata == null ? null : RootChannelMetadataCodec.decodeAssociated(metadata, processor, decoder)));
        }
        List<ManagedOccurrenceBinding> rows = new ArrayList<ManagedOccurrenceBinding>();
        for (JsonNode row : bindingRows) {
            fields(row, "occurrence", "binding", "policy", "source", "path", "activation", "target", "expected", "active", "pendingEpoch");
            rows.add(ManagedOccurrenceBinding.verified(text(row, "occurrence"), text(row, "binding"), text(row, "policy"),
                    new DocumentId(text(row, "source")), ScopeAddress.embedded(text(row, "path"), integer(row, "activation")),
                    new DocumentId(text(row, "target")), text(row, "expected"), bool(row, "active"),
                    pendingEpoch(row.get("pendingEpoch"))));
        }
        Map<DocumentId, Integer> indexes = new TreeMap<DocumentId, Integer>();
        JsonNode indexValues = object(root, "canonicalIndexes");
        Iterator<Map.Entry<String, JsonNode>> indexIterator = indexValues.fields();
        while (indexIterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = indexIterator.next();
            long index = integer(entry.getValue());
            if (index > Integer.MAX_VALUE) throw invalid("Cyclic member index exceeds supported component size");
            indexes.put(new DocumentId(entry.getKey()), (int) index);
        }
        Map<DocumentId, String> preliminary = new TreeMap<DocumentId, String>();
        Iterator<Map.Entry<String, JsonNode>> preliminaryIterator = object(root, "preliminaryBlueIds").fields();
        while (preliminaryIterator.hasNext()) {
            Map.Entry<String, JsonNode> entry = preliminaryIterator.next();
            preliminary.put(new DocumentId(entry.getKey()), nullable(entry.getValue()));
        }
        return ReusableComponentAuthority.restoreAuthenticated(component, headers, rows, indexes, preliminary, canonical);
    }

    private static Object component(ComponentSnapshot value) {
        List<String> members = new ArrayList<String>();
        for (DocumentId member : value.orderedMemberDocumentIds()) members.add(member.value());
        return map("identity", value.componentIdentity(), "stateIdentity", value.componentStateIdentity(),
                "generation", value.componentGeneration(), "kind", value.kind().name(), "members", members,
                "blueIds", value.orderedMemberBlueIds(), "master", value.masterBlueId(), "proofIdentity", value.cyclicProofIdentity());
    }
    private static ComponentSnapshot component(JsonNode value) {
        fields(value, "identity", "stateIdentity", "generation", "kind", "members", "blueIds", "master", "proofIdentity");
        List<DocumentId> members = new ArrayList<DocumentId>();
        List<String> blueIds = new ArrayList<String>();
        for (JsonNode member : array(value, "members")) members.add(new DocumentId(nullable(member)));
        for (JsonNode blueId : array(value, "blueIds")) blueIds.add(nullable(blueId));
        return ComponentSnapshot.restoreAuthenticatedHeader(text(value, "identity"), text(value, "stateIdentity"),
                integer(value, "generation"), ComponentKind.valueOf(text(value, "kind")), members, blueIds,
                text(value, "master"), text(value, "proofIdentity"));
    }
    private static void capacity(long elements, Limits limits) {
        if (elements > limits.nodes) throw new CapacityExceeded("Reusable component metadata exceeds acquisition capacity");
    }
    private static JsonNode array(JsonNode value, String key) {
        JsonNode selected = value.get(key);
        if (selected == null || !selected.isArray()) throw invalid("Expected authority array: " + key);
        return selected;
    }
    private static JsonNode object(JsonNode value, String key) {
        JsonNode selected = value.get(key);
        if (selected == null || !selected.isObject()) throw invalid("Expected authority object: " + key);
        return selected;
    }
    private static boolean bool(JsonNode value, String key) {
        JsonNode selected = value.get(key);
        if (selected == null || !selected.isBoolean()) throw invalid("Expected authority boolean: " + key);
        return selected.booleanValue();
    }
    private static long integer(JsonNode value, String key) { return integer(value.get(key)); }
    private static Long pendingEpoch(JsonNode value) {
        if (value == null || value.isNull()) return null;
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < -1L)
            throw invalid("Expected authored (-1) or initialized historical cursor");
        return value.longValue() == -1L ? -1L : integer(value);
    }
    private static long integer(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw invalid("Expected safe authority integer");
        return ClosureValueSupport.requireSafeInteger(value.longValue(), "authority integer");
    }
    private static void fields(JsonNode value, String... names) {
        if (value == null || !value.isObject()) throw invalid("Expected closed authority object");
        Set<String> actual = new HashSet<String>(); value.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(new HashSet<String>(Arrays.asList(names)))) throw invalid("Unexpected or missing authority fields");
    }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new TreeMap<String, Object>();
        for (int index = 0; index < pairs.length; index += 2) result.put((String) pairs[index], pairs[index + 1]);
        return result;
    }
}
