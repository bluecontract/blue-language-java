package blue.language.processor.closure;

import blue.language.model.Node;
import blue.language.provider.CyclicSetProof;
import blue.language.snapshot.FrozenNode;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static blue.language.processor.closure.FrozenNodeEvidenceCodec.*;

/** Exact retained placement data; the enclosing committed creator program supplies execution authority. */
public final class AcceptedInitializationInstallationCodec {
    private static final String FORMAT = "blue-accepted-initialization-installation-poc-1";
    private AcceptedInitializationInstallationCodec() { }

    public static String encode(AcceptedInitializationInstallation installation, Writer writer, Limits limits) {
        return encode(installation, new Encoder(writer, limits));
    }
    public static String encode(AcceptedInitializationInstallation installation, Encoder encoder) {
        SameOriginAttachmentPolicy.Selection selection = installation.selection();
        ManagedReadPin view = installation.selectedView();
        List<String> proof = new ArrayList<>();
        if (view.cyclicProof().isPresent()) for (Node member : view.cyclicProof().get().declaredPlaceholderSet())
            proof.add(encoder.node(FrozenNode.fromResolvedNode(member)));
        return encoder.blob(bytes(map("format", FORMAT, "identity", installation.identity(), "mode", selection.mode().name(),
                "creator", selection.creatorLineage().value(), "occurrence", selection.occurrenceIdentity(),
                "source", selection.targetLineage().value(), "suppliedRef", selection.suppliedExactRefBlueId(),
                "creatorSeed", installation.creatorSeedIdentity(), "creatorSite", installation.creatorPatchSite(),
                "initialization", installation.sourceInitializationOperationIdentity(), "completionSite", installation.sourceCompletionSiteIdentity(),
                "selectedBlueId", view.blueId(), "body", encoder.node(view.frozenDocument()), "proof", proof)));
    }
    public static AcceptedInitializationInstallation decode(String authenticatedIdentity, Reader reader, Limits limits) {
        return decode(authenticatedIdentity, new Decoder(reader, limits));
    }
    public static AcceptedInitializationInstallation decode(String authenticatedIdentity, Decoder decoder) {
        JsonNode root = json(decoder.blob(authenticatedIdentity));
        if (!FORMAT.equals(text(root, "format"))) throw invalid("Unexpected initialization installation format");
        SameOriginAttachmentPolicy.Selection selection = new SameOriginAttachmentPolicy.Selection(
                SameOriginAttachmentPolicy.Mode.valueOf(text(root, "mode")), new DocumentId(text(root, "creator")),
                text(root, "occurrence"), new DocumentId(text(root, "source")), text(root, "suppliedRef"));
        List<Node> proof = new ArrayList<>();
        JsonNode members = root.get("proof");
        if (members == null || !members.isArray()) throw invalid("Expected exact cyclic proof member array");
        for (JsonNode member : members) proof.add(decoder.materialize(nullable(member)));
        ManagedReadPin pin = ManagedReadPin.fromExactEvidence(selection.targetLineage(), text(root, "selectedBlueId"),
                decoder.materialize(text(root, "body")), proof.isEmpty() ? null : CyclicSetProof.fromDeclaredPlaceholderSet(proof));
        AcceptedInitializationInstallation installation = AcceptedInitializationInstallation.fromCanonicalSite(selection,
                text(root, "creatorSeed"), text(root, "creatorSite"), text(root, "initialization"), text(root, "completionSite"), pin);
        if (!installation.identity().equals(text(root, "identity"))) throw invalid("Initialization installation identity mismatch");
        return installation;
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new TreeMap<>();
        for (int index = 0; index < pairs.length; index += 2) result.put((String) pairs[index], pairs[index + 1]);
        return result;
    }
}
