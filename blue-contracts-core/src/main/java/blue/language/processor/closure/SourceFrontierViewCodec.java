package blue.language.processor.closure;

import blue.language.model.wire.BlueLanguageConstants;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import blue.language.provider.CyclicSetProof;
import blue.language.snapshot.FrozenNode;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.*;
import static blue.language.processor.closure.FrozenNodeEvidenceCodec.*;

/** Retained selected-view data; only its authenticated enclosing creator program grants installation authority. */
public final class SourceFrontierViewCodec {
    private SourceFrontierViewCodec() { }
    public static String encode(SourceFrontierView view, Encoder encoder) {
        Objects.requireNonNull(view, "view");
        SameOriginAttachmentPolicy.Selection s = view.selection();
        Map<String, Object> root = new TreeMap<>(); root.put("format", "blue-source-frontier-view-poc-1");
        root.put("identity", view.identity()); root.put("creator", s.creatorLineage().value()); root.put("occurrence", s.occurrenceIdentity());
        root.put("source", s.targetLineage().value()); root.put("suppliedRef", s.suppliedExactRefBlueId()); root.put("frontier", s.frontier().get().components());
        root.put("successfulOperation", view.successfulOperationIdentity()); root.put("terminalOperation", view.terminalOperationIdentity());
        root.put(BlueLanguageConstants.OBJECT_BLUE_ID, view.selectedView().blueId()); root.put("epoch", view.successfulEpoch());
        root.put("productionOrder", view.productionOrder().isPresent() ? view.productionOrder().get().components() : null);
        root.put("environment", SourceObservationProgramCodec.environment(view.environment())); root.put("policy", SourceObservationProgramCodec.policy(view.executionPolicy()));
        root.put("body", encoder.node(view.selectedView().frozenDocument())); List<String> proof = new ArrayList<>();
        if (view.selectedView().cyclicProof().isPresent()) for (Node member : view.selectedView().cyclicProof().get().declaredPlaceholderSet())
            proof.add(encoder.node(FrozenNode.fromResolvedNode(member)));
        root.put("proof", proof); return encoder.blob(bytes(root));
    }
    public static SourceFrontierView decode(String trustedReference, Decoder decoder) {
        JsonNode root = json(decoder.blob(trustedReference));
        if (!"blue-source-frontier-view-poc-1".equals(text(root, "format"))) throw invalid("Unexpected selected frontier format");
        SameOriginAttachmentPolicy.Selection selection = new SameOriginAttachmentPolicy.Selection(SameOriginAttachmentPolicy.Mode.FROM_FRONTIER,
                new DocumentId(text(root, "creator")), text(root, "occurrence"), new DocumentId(text(root, "source")), text(root, "suppliedRef"), order(root.get("frontier")));
        List<Node> proof = new ArrayList<>(); JsonNode array = root.get("proof");
        if (array == null || !array.isArray()) throw invalid("Missing frontier exact proof inventory");
        for (JsonNode member : array) proof.add(decoder.materialize(nullable(member)));
        ManagedReadPin pin = ManagedReadPin.fromExactEvidence(selection.targetLineage(), text(root, BlueLanguageConstants.OBJECT_BLUE_ID), decoder.materialize(text(root, "body")),
                proof.isEmpty() ? null : CyclicSetProof.fromDeclaredPlaceholderSet(proof));
        JsonNode epoch = root.get("epoch"); if (epoch == null || !epoch.isIntegralNumber() || !epoch.canConvertToLong()) throw invalid("Invalid selected frontier epoch");
        SourceFrontierView result = new SourceFrontierView(selection, text(root, "successfulOperation"), text(root, "terminalOperation"), pin, epoch.longValue(),
                order(root.get("productionOrder")), SourceObservationProgramCodec.environment(root.get("environment")), SourceObservationProgramCodec.policy(root.get("policy")));
        if (!result.identity().equals(text(root, "identity"))) throw invalid("Selected frontier identity mismatch");
        return result;
    }
    static ExternalOrderKey order(JsonNode array) {
        if (array == null || array.isNull()) return null;
        if (!array.isArray()) throw invalid("Expected exact frontier tuple");
        List<Object> values = new ArrayList<>();
        for (JsonNode value : array) {
            if (value.isIntegralNumber()) values.add(value.bigIntegerValue());
            else if (value.isTextual()) values.add(value.textValue());
            else throw invalid("Unsupported frontier scalar");
        }
        ExternalOrderKey result = ExternalOrderKey.of(values); SameOriginAttachmentPolicy.requireFrontier(result); return result;
    }
}
