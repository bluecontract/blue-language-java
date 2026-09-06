package blue.language.processor.closure;

import blue.language.snapshot.FrozenNode;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import static blue.language.processor.closure.FrozenNodeEvidenceCodec.*;

/** Portable closed demand data, not execution continuation or authority to admit a resource. */
public final class ClosureResourceDemandCodec {
    private static final String FORMAT = "blue-closure-resource-demand-poc-1";

    private ClosureResourceDemandCodec() { }

    public static String encode(ClosureResourceDemand demand, Writer writer, Limits limits) {
        return encode(demand, new Encoder(writer, limits));
    }

    /** Shares the enclosing portable record's aggregate limits and fragment deduplication. */
    public static String encode(ClosureResourceDemand demand, Encoder encoder) {
        Objects.requireNonNull(demand, "demand");
        Map<String, Object> value = new TreeMap<String, Object>();
        value.put("format", FORMAT);
        value.put("kind", demand.kind().name());
        value.put("identity", demand.demandIdentity());
        value.put("source", demand.sourceDocumentId().value());
        value.put("sourcePath", demand.sourcePath());
        value.put("suppliedValue", demand.suppliedValueBlueId());
        if (demand instanceof ManagedOccurrenceEvidenceDemand) {
            ManagedOccurrenceEvidenceDemand occurrence = (ManagedOccurrenceEvidenceDemand) demand;
            value.put("cause", occurrence.logicalCauseIdentity());
            value.put("inputClosure", occurrence.inputClosureIdentity());
            value.put("inputGeneration", occurrence.inputGraphGeneration());
            value.put("declaration", occurrence.processEmbeddedDeclarationIdentity());
            value.put("ordinal", occurrence.demandOrdinal());
            value.put("inlineValue", occurrence.suppliedExactValue().isPresent()
                    ? encoder.node(FrozenNode.fromResolvedNode(occurrence.suppliedExactValue().get())) : null);
        } else if (demand instanceof SourceInitializationDemand) {
            SourceInitializationDemand initialization = (SourceInitializationDemand) demand;
            SameOriginAttachmentPolicy.Selection selection = initialization.selection();
            value.put("target", selection.targetLineage().value());
            value.put("occurrence", selection.occurrenceIdentity());
            value.put("creatorSeed", initialization.creatorSeedIdentity());
            value.put("creatorPatchSite", initialization.creatorPatchSite());
            value.put("environment", SourceObservationProgramCodec.environment(initialization.environment()));
            value.put("policy", SourceObservationProgramCodec.policy(initialization.executionPolicy()));
        } else if (!(demand instanceof ExactNodeDemand)) {
            throw invalid("Unknown resource demand kind");
        }
        return encoder.blob(bytes(value));
    }

    /** The root is the retained original Need record, never a self-asserted resource capability. */
    public static ClosureResourceDemand decode(String retainedIdentity, Reader reader, Limits limits) {
        return decode(retainedIdentity, new Decoder(reader, limits), new Encoder((key, content) -> { }, limits));
    }

    /** Shares both read and canonical-validation budgets with the enclosing record. */
    public static ClosureResourceDemand decode(String retainedIdentity, Decoder decoder, Encoder canonicalEncoder) {
        JsonNode value = json(decoder.blob(retainedIdentity));
        if (!FORMAT.equals(text(value, "format"))) throw invalid("Unexpected resource demand format");
        DocumentId source = new DocumentId(text(value, "source"));
        String path = text(value, "sourcePath"), supplied = text(value, "suppliedValue");
        String identity = text(value, "identity");
        ClosureResourceDemand demand;
        switch (ClosureResourceDemand.Kind.valueOf(text(value, "kind"))) {
            case EXACT_NODE:
                demand = new ExactNodeDemand(identity, supplied, source, path);
                break;
            case MANAGED_OCCURRENCE_EVIDENCE:
                String inline = text(value, "inlineValue");
                demand = inline == null
                        ? new ManagedOccurrenceEvidenceDemand(identity, text(value, "cause"), text(value, "inputClosure"),
                                integer(value, "inputGeneration"), source, path, text(value, "declaration"), supplied, integer(value, "ordinal"))
                        : new ManagedOccurrenceEvidenceDemand(identity, text(value, "cause"), text(value, "inputClosure"),
                                integer(value, "inputGeneration"), source, path, text(value, "declaration"), supplied,
                                integer(value, "ordinal"), decoder.materialize(inline));
                break;
            case CANONICAL_INITIALIZATION:
                SameOriginAttachmentPolicy.Selection selection = new SameOriginAttachmentPolicy.Selection(
                        SameOriginAttachmentPolicy.Mode.FULL_HISTORY, source, text(value, "occurrence"),
                        new DocumentId(text(value, "target")), supplied);
                demand = new SourceInitializationDemand(selection, path, text(value, "creatorSeed"), text(value, "creatorPatchSite"),
                        SourceObservationProgramCodec.environment(value.get("environment")), SourceObservationProgramCodec.policy(value.get("policy")));
                if (!identity.equals(demand.demandIdentity())) throw invalid("Initialization demand identity mismatch");
                break;
            default:
                throw invalid("Unknown resource demand kind");
        }
        // Re-encoding rejects unknown, omitted, or noncanonical fields, including nested constructor evidence.
        String canonical = encode(demand, canonicalEncoder);
        if (!retainedIdentity.equals(canonical)) throw invalid("Resource demand is not a closed canonical record");
        return demand;
    }

    private static long integer(JsonNode value, String field) {
        JsonNode number = value.get(field);
        if (number == null || !number.isIntegralNumber() || !number.canConvertToLong()) throw invalid("Expected demand integer: " + field);
        return number.longValue();
    }
}
