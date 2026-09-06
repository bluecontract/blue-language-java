package blue.language.processor.closure;

import blue.language.model.wire.BlueLanguageConstants;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.ProcessorDiagnostic;
import blue.language.processor.ProcessorErrorCategory;
import blue.language.processor.ProcessorStatus;
import blue.language.snapshot.FrozenNode;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.util.*;
import static blue.language.processor.closure.FrozenNodeEvidenceCodec.*;

/** Retains failure disposition evidence; full gas rows remain bound by the enclosing operation receipt. */
public final class SourceOperationFailureCodec {
    private static final String FORMAT = "blue-source-operation-failure-poc-1";
    private SourceOperationFailureCodec() { }

    public static String encode(SourceOperationFailure failure, Writer writer, Limits limits) {
        return encode(failure, new Encoder(writer, limits));
    }

    public static String encode(SourceOperationFailure failure, Encoder encoder) {
        Object external = null;
        if (failure.externalCause() != null) {
            List<Object> order = new ArrayList<Object>();
            for (Object value : failure.externalCause().sourceOrder().components()) order.add(map(
                    "kind", value instanceof Number ? "integer" : "text", BlueLanguageConstants.OBJECT_VALUE, value.toString()));
            external = map("event", encoder.node(FrozenNode.fromResolvedNode(failure.externalCause().event())),
                    BlueLanguageConstants.OBJECT_BLUE_ID, failure.externalCause().eventBlueId(), "order", order,
                    "orderPolicy", failure.externalCause().externalOrderPolicyIdentity());
        }
        List<String> owners = new ArrayList<String>(); for (DocumentId owner : failure.ownedDocumentIds()) owners.add(owner.value());
        List<Object> states = new ArrayList<Object>();
        for (SourceObservationProgram.SourceState state : failure.sourcePredecessors()) states.add(map(
                "document", state.documentId().value(), BlueLanguageConstants.OBJECT_BLUE_ID, state.blueId(), "epoch", state.epoch(),
                ProcessorContractConstants.KEY_INITIALIZED, state.initialized(), "body", encoder.node(state.frozenDocument())));
        ProcessorDiagnostic diagnostic = failure.diagnostic();
        return encoder.blob(bytes(map("format", FORMAT, "invocation", failure.invocationIdentity(), "causeKind", failure.causeKind().name(),
                "cause", failure.causeIdentity(), "external", external, "status", failure.status().name(), "owned", owners, "before", states,
                "environment", SourceObservationProgramCodec.environment(failure.environment()),
                "policy", SourceObservationProgramCodec.policy(failure.executionPolicy()), "totalGas", failure.totalGas(),
                "gasTrace", failure.gasTraceIdentity(), "rejectedCharge", failure.rejectedChargeIdentity(),
                "managedReaction", failure.managedReaction().map(context -> ManagedReactionContextCodec.encode(context, encoder)).orElse(null),
                "diagnostic", diagnostic == null ? null : map("category", diagnostic.category().name(),
                        "message", diagnostic.message(), "details", diagnostic.details()))));
    }

    /** Expected digest must be authenticated by committed source failure authority, not self-asserted. */
    public static SourceOperationFailure decode(String authenticatedIdentity, Reader reader, Limits limits) {
        return decode(authenticatedIdentity, new Decoder(reader, limits));
    }

    public static SourceOperationFailure decode(String authenticatedIdentity, Decoder decoder) {
        JsonNode root = json(decoder.blob(authenticatedIdentity));
        if (!FORMAT.equals(text(root, "format"))) throw invalid("Unexpected source failure format");
        ExternalEventCause external = null; JsonNode ext = root.get("external");
        if (ext != null && !ext.isNull()) {
            List<Object> order = new ArrayList<Object>();
            for (JsonNode value : array(ext, "order")) {
                String kind = text(value, "kind");
                if ("integer".equals(kind)) order.add(new BigInteger(text(value, BlueLanguageConstants.OBJECT_VALUE)));
                else if ("text".equals(kind)) order.add(text(value, BlueLanguageConstants.OBJECT_VALUE));
                else throw invalid("Unknown failure order component");
            }
            external = new ExternalEventCause(text(root, "cause"), decoder.materialize(text(ext, "event")), text(ext, BlueLanguageConstants.OBJECT_BLUE_ID),
                    ExternalOrderKey.of(order), text(ext, "orderPolicy"));
        }
        Set<DocumentId> owned = new TreeSet<DocumentId>();
        for (JsonNode id : array(root, "owned")) if (!owned.add(new DocumentId(nullable(id)))) throw invalid("Duplicate failed owner");
        List<SourceObservationProgram.SourceState> states = new ArrayList<SourceObservationProgram.SourceState>();
        for (JsonNode row : array(root, "before")) states.add(new SourceObservationProgram.SourceState(
                new DocumentId(text(row, "document")), text(row, BlueLanguageConstants.OBJECT_BLUE_ID), integer(row, "epoch"), bool(row, ProcessorContractConstants.KEY_INITIALIZED), decoder.node(text(row, "body"))));
        ProcessorDiagnostic diagnostic = null; JsonNode diag = root.get("diagnostic");
        if (diag != null && !diag.isNull()) {
            ProcessorDiagnostic.Builder builder = ProcessorDiagnostic.builder(ProcessorErrorCategory.valueOf(text(diag, "category"))).message(text(diag, "message"));
            JsonNode details = diag.get("details");
            if (details == null || !details.isObject()) throw invalid("Invalid failure diagnostic details");
            Iterator<Map.Entry<String, JsonNode>> entries = details.fields();
            while (entries.hasNext()) { Map.Entry<String, JsonNode> entry = entries.next(); builder.detail(entry.getKey(), nullable(entry.getValue())); }
            diagnostic = builder.build();
        }
        return new SourceOperationFailure(text(root, "invocation"), ProcessingCause.Kind.valueOf(text(root, "causeKind")),
                text(root, "cause"), external, SourceObservationProgramCodec.environment(root.get("environment")),
                SourceObservationProgramCodec.policy(root.get("policy")), ProcessorStatus.valueOf(text(root, "status")),
                owned, states, integer(root, "totalGas"), text(root, "gasTrace"), text(root, "rejectedCharge"), diagnostic,
                text(root, "managedReaction") == null ? null : ManagedReactionContextCodec.decode(text(root, "managedReaction"), decoder));
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new TreeMap<String, Object>();
        for (int index = 0; index < pairs.length; index += 2) result.put((String) pairs[index], pairs[index + 1]); return result;
    }
    private static JsonNode array(JsonNode object, String key) {
        JsonNode value = object.get(key); if (value == null || !value.isArray()) throw invalid("Expected failure array: " + key); return value;
    }
    private static long integer(JsonNode object, String key) {
        JsonNode value = object.get(key);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) throw invalid("Expected failure integer: " + key); return value.longValue();
    }
    private static boolean bool(JsonNode object, String key) {
        JsonNode value = object.get(key); if (value == null || !value.isBoolean()) throw invalid("Expected failure boolean: " + key); return value.booleanValue();
    }
}
