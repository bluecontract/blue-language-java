package blue.language.processor.closure;

import blue.language.model.wire.BlueLanguageConstants;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.processor.DocumentProcessor;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.TreeMap;

/** Bounded routing fragments. Cold minting is internal to authenticated state/receipt restoration. */
public final class RootChannelMetadataCodec {
    private static final String FORMAT = "blue-root-channel-metadata-poc-1";
    private RootChannelMetadataCodec() { }

    public static String encode(RootChannelMetadata metadata, FrozenNodeEvidenceCodec.Encoder encoder) {
        Map<String, Object> value = new TreeMap<String, Object>();
        value.put("format", FORMAT); value.put("lineage", metadata.documentId().value());
        value.put(BlueLanguageConstants.OBJECT_BLUE_ID, metadata.blueId()); value.put("epoch", metadata.epoch());
        value.put(ProcessorContractConstants.KEY_INITIALIZED, metadata.initialized()); value.put(ProcessorContractConstants.KEY_TERMINATED, metadata.terminated());
        value.put("registry", metadata.registryIdentity());
        value.put("routing", encoder.node(metadata.frozenRoutingDocument()));
        value.put("surface", blue.language.processor.ManagedRootSurfaceCodec.encode(metadata.surface(), encoder));
        return encoder.blob(FrozenNodeEvidenceCodec.bytes(value));
    }

    static RootChannelMetadata decodeAssociated(String authenticatedMetadataIdentity,
            DocumentProcessor processor, FrozenNodeEvidenceCodec.Decoder decoder) {
        JsonNode value = FrozenNodeEvidenceCodec.json(decoder.blob(authenticatedMetadataIdentity));
        if (!FORMAT.equals(FrozenNodeEvidenceCodec.text(value, "format"))) throw FrozenNodeEvidenceCodec.invalid("Unexpected Root metadata format");
        if (!value.path("epoch").isIntegralNumber() || !value.path(ProcessorContractConstants.KEY_INITIALIZED).isBoolean() || !value.path(ProcessorContractConstants.KEY_TERMINATED).isBoolean())
            throw FrozenNodeEvidenceCodec.invalid("Malformed Root metadata state");
        return RootChannelMetadata.restore(new DocumentId(FrozenNodeEvidenceCodec.text(value, "lineage")),
                FrozenNodeEvidenceCodec.text(value, BlueLanguageConstants.OBJECT_BLUE_ID), value.path("epoch").longValue(),
                value.path(ProcessorContractConstants.KEY_INITIALIZED).booleanValue(), value.path(ProcessorContractConstants.KEY_TERMINATED).booleanValue(),
                FrozenNodeEvidenceCodec.text(value, "registry"), decoder.node(FrozenNodeEvidenceCodec.text(value, "routing")),
                blue.language.processor.ManagedRootSurfaceCodec.decode(FrozenNodeEvidenceCodec.text(value, "surface"), decoder), processor);
    }
}
