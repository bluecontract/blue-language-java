package blue.language.processor.closure;

import blue.language.processor.ExternalOrderKey;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.util.*;
import static blue.language.processor.closure.FrozenNodeEvidenceCodec.*;

/** Data-only retained placement. Its digest must be authenticated by the completed operation receipt. */
public final class ManagedReactionContextCodec {
    private static final String FORMAT = "blue-managed-reaction-context-poc-1";
    private ManagedReactionContextCodec() { }

    public static String encode(ManagedReactionContext context, Writer writer, Limits limits) {
        return encode(context, new Encoder(writer, limits));
    }

    public static String encode(ManagedReactionContext context, Encoder encoder) {
        Objects.requireNonNull(context, "context");
        List<Object> due = new ArrayList<>();
        for (ManagedReactionContext.DueOccurrence row : context.dueOccurrences())
            due.add(map("occurrence", row.occurrenceIdentity(), "consumer", row.consumerLineage().value(),
                    "source", row.sourceLineage().value(), "sourceOperation", row.sourceOperationIdentity(),
                    "previousPosition", row.expectedLanePositionIdentity()));
        return encoder.blob(bytes(map("format", FORMAT, "identity", context.identity(),
                "creatingOperation", context.creatingOperationIdentity(), "creatorSeed", context.creatorExecutionSeedIdentity(),
                "creationSite", context.creationSiteIdentity(), "activationMicros", context.activationCut().components().get(0).toString(),
                "activationEntry", context.activationCut().components().get(1),
                "sourcePosition", context.sourceReactionPositionIdentity(), "due", due)));
    }

    public static ManagedReactionContext decode(String authenticatedIdentity, Reader reader, Limits limits) {
        return decode(authenticatedIdentity, new Decoder(reader, limits));
    }

    public static ManagedReactionContext decode(String authenticatedIdentity, Decoder decoder) {
        JsonNode root = json(decoder.blob(authenticatedIdentity));
        if (!FORMAT.equals(text(root, "format"))) throw invalid("Unexpected managed reaction context format");
        JsonNode rows = root.get("due");
        if (rows == null || !rows.isArray()) throw invalid("Expected due occurrence array");
        List<ManagedReactionContext.DueOccurrence> due = new ArrayList<>();
        for (JsonNode row : rows) due.add(new ManagedReactionContext.DueOccurrence(text(row, "occurrence"),
                new DocumentId(text(row, "consumer")), new DocumentId(text(row, "source")),
                text(row, "sourceOperation"), text(row, "previousPosition")));
        ManagedReactionContext context = new ManagedReactionContext(text(root, "creatingOperation"), text(root, "creatorSeed"),
                text(root, "creationSite"), ExternalOrderKey.of(Arrays.asList(new BigInteger(text(root, "activationMicros")),
                text(root, "activationEntry"))), text(root, "sourcePosition"), due);
        if (!context.identity().equals(text(root, "identity"))) throw invalid("Managed reaction context identity mismatch");
        return context;
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new TreeMap<>();
        for (int index = 0; index < pairs.length; index += 2) result.put((String) pairs[index], pairs[index + 1]);
        return result;
    }
}
