package blue.language.processor.closure;

import blue.language.model.wire.BlueLanguageConstants;
import blue.language.processor.util.ProcessorContractConstants;
import blue.language.model.Node;
import blue.language.processor.DocumentUpdateOccurrence;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.FrozenJsonPatch;
import blue.language.processor.model.JsonPatch;
import blue.language.snapshot.FrozenNode;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigInteger;
import java.util.*;
import static blue.language.processor.closure.FrozenNodeEvidenceCodec.*;

/**
 * Data-only source-program persistence. Each step and node is an independently hashed fragment.
 * The expected manifest digest MUST come from an authenticated committed source result; a digest
 * supplied alongside untrusted bytes proves integrity, not successful source execution.
 */
public final class SourceObservationProgramCodec {
    private static final String FORMAT = "blue-source-observation-program-poc-1";
    private SourceObservationProgramCodec() { }

    /** Writes immutable fragments and returns the manifest identity to bind into the source receipt. */
    public static String encode(SourceObservationProgram program, Writer writer, Limits limits) {
        return encode(program, new Encoder(writer, limits));
    }

    /** Encodes in the enclosing receipt's shared fragment budget. */
    public static String encode(SourceObservationProgram program, Encoder e) {
        return new Encoding(e).program(program, 0);
    }

    private static String encodeOne(SourceObservationProgram program, Encoder e, List<String> borrowed) {
        Objects.requireNonNull(program, "program");
        Map<String, Object> root = map("format", FORMAT, "invocation", program.invocationIdentity(),
                "causeKind", program.causeKind().name(), "cause", program.causeIdentity(),
                "environment", environment(program.environment()), "policy", policy(program.executionPolicy()));
        ExternalEventCause external = program.externalCause();
        if (external == null) root.put("external", null);
        else {
            List<Object> order = new ArrayList<Object>();
            for (Object component : external.sourceOrder().components()) order.add(map("kind", component instanceof Number ? "integer" : "text", BlueLanguageConstants.OBJECT_VALUE, component.toString()));
            root.put("external", map("event", e.node(FrozenNode.fromResolvedNode(external.event())),
                    "eventBlueId", external.eventBlueId(), "order", order, "orderPolicy", external.externalOrderPolicyIdentity()));
        }
        List<String> owned = new ArrayList<String>(); for (DocumentId id : program.ownedDocumentIds()) owned.add(id.value());
        root.put("owned", owned); root.put("before", states(program.sourcePredecessors(), e)); root.put("after", states(program.sourceResults(), e));
        root.put("beforeTopology", topology(program.sourceBeforeBindings(), program.sourceBeforeComponents(), e));
        root.put("afterTopology", topology(program.sourceAfterBindings(), program.sourceAfterComponents(), e));
        root.put("borrowedPrograms", borrowed);
        root.put("readPins", readPins(program.sourceReadPins(), e));
        root.put("managedReaction", program.managedReaction().isPresent()
                ? ManagedReactionContextCodec.encode(program.managedReaction().get(), e) : null);
        List<Object> acceptedViews = new ArrayList<>();
        for (AcceptedAttachmentView view : program.acceptedViews()) {
            SameOriginAttachmentPolicy.Selection selection = view.selection();
            acceptedViews.add(map("identity", view.identity(), "mode", selection.mode().name(),
                    "creator", selection.creatorLineage().value(), "occurrence", selection.occurrenceIdentity(),
                    "target", selection.targetLineage().value(), "suppliedRef", selection.suppliedExactRefBlueId(),
                    "creatorSeed", view.creatorSeedIdentity(), "creatorSite", view.creatorPatchSite(),
                    "sourceSeed", view.sourceSeedIdentity(), "sourceSite", view.sourceSiteIdentity(),
                    "frontier", selection.frontier().isPresent() ? selection.frontier().get().components() : null,
                    "frontierView", view.frontierView().isPresent() ? SourceFrontierViewCodec.encode(view.frontierView().get(), e) : null,
                    "selectedView", readPins(Collections.singletonList(view.selectedView()), e)));
        }
        root.put("acceptedViews", acceptedViews);
        List<String> acceptedInitializations = new ArrayList<>();
        for (AcceptedInitializationInstallation installation : program.acceptedInitializations())
            acceptedInitializations.add(AcceptedInitializationInstallationCodec.encode(installation, e));
        root.put("acceptedInitializations", acceptedInitializations);
        List<String> steps = new ArrayList<String>();
        for (SourceObservationProgram.Step step : program.steps()) steps.add(e.blob(bytes(step(step, e))));
        root.put("steps", steps);
        List<Object> skippedWork = new ArrayList<>();
        for (SourceObservationProgram.SkippedWork skipped : program.skippedWork())
            skippedWork.add(map("work", skipped.workIdentity(), "target", skipped.targetDocumentId().value(),
                    "kind", skipped.kind().name(), "channel", skipped.channelKey(),
                    "sourceOccurrence", skipped.sourceOccurrenceIdentity(), "requestWork", skipped.requestWorkIdentity()));
        root.put("skippedWork", skippedWork);
        List<String> projections = new ArrayList<String>();
        for (SourceObservationProgram.ReferenceProjection projection : program.referenceProjections()) {
            List<Object> changes = new ArrayList<Object>();
            for (SourceObservationProgram.ReferenceChange change : projection.changes()) changes.add(map(
                    "path", change.path(), "before", e.node(change.before()), "after", e.node(change.after())));
            projections.add(e.blob(bytes(map("site", projection.siteIdentity(), "source", projection.sourceDocumentId().value(),
                    "target", projection.targetDocumentId().value(), "beforeBlueId", projection.beforeBlueId(),
                    "afterBlueId", projection.afterBlueId(), "before", e.node(projection.beforeBody()),
                    "after", e.node(projection.afterBody()), "changes", changes))));
        }
        root.put("referenceProjections", projections);
        return e.blob(bytes(root));
    }

    /** Restores after validating every acquired fragment; never invokes business handlers. */
    public static SourceObservationProgram decode(String authenticatedProgramIdentity, Reader reader, Limits limits) {
        return decode(authenticatedProgramIdentity, new Decoder(reader, limits));
    }

    /** Decodes in the enclosing receipt's shared verification and capacity budget. */
    public static SourceObservationProgram decode(String authenticatedProgramIdentity, Decoder d) {
        return new Decoding(d).program(authenticatedProgramIdentity, 0);
    }

    private static SourceObservationProgram decodeOne(JsonNode root, Decoder d, List<SourceObservationProgram> borrowed) {
        if (!FORMAT.equals(text(root, "format"))) throw invalid("Unexpected source observation program format");
        ClosureEnvironment environment = environment(root.get("environment"));
        ExternalEventCause external = null; JsonNode ext = root.get("external");
        if (ext != null && !ext.isNull()) {
            List<Object> order = new ArrayList<Object>();
            for (JsonNode item : array(ext, "order")) {
                String kind = text(item, "kind");
                if ("integer".equals(kind)) order.add(new BigInteger(text(item, BlueLanguageConstants.OBJECT_VALUE)));
                else if ("text".equals(kind)) order.add(text(item, BlueLanguageConstants.OBJECT_VALUE));
                else throw invalid("Unsupported source order scalar");
            }
            external = new ExternalEventCause(text(root, "cause"), d.materialize(text(ext,"event")),
                    text(ext,"eventBlueId"), ExternalOrderKey.of(order), text(ext,"orderPolicy"));
        }
        Set<DocumentId> owned = new TreeSet<DocumentId>();
        for (JsonNode id : array(root, "owned")) if (!owned.add(new DocumentId(nullable(id)))) throw invalid("Duplicate source owner");
        List<SourceObservationProgram.Step> steps = new ArrayList<SourceObservationProgram.Step>();
        for (JsonNode ref : array(root, "steps")) steps.add(step(json(d.blob(nullable(ref))), d));
        List<SourceObservationProgram.ReferenceProjection> projections = new ArrayList<SourceObservationProgram.ReferenceProjection>();
        for (JsonNode ref : array(root, "referenceProjections")) {
            JsonNode row = json(d.blob(nullable(ref)));
            List<SourceObservationProgram.ReferenceChange> changes = new ArrayList<SourceObservationProgram.ReferenceChange>();
            for (JsonNode change : array(row, "changes")) changes.add(new SourceObservationProgram.ReferenceChange(
                    text(change, "path"), d.node(text(change, "before")), d.node(text(change, "after"))));
            projections.add(new SourceObservationProgram.ReferenceProjection(text(row, "site"), new DocumentId(text(row, "source")),
                    new DocumentId(text(row, "target")), text(row, "beforeBlueId"), text(row, "afterBlueId"),
                    d.node(text(row, "before")), d.node(text(row, "after")), changes));
        }
        List<AcceptedAttachmentView> acceptedViews = new ArrayList<>();
        for (JsonNode row : array(root, "acceptedViews")) {
            SameOriginAttachmentPolicy.Selection selection = new SameOriginAttachmentPolicy.Selection(
                    SameOriginAttachmentPolicy.Mode.valueOf(text(row, "mode")), new DocumentId(text(row, "creator")),
                    text(row, "occurrence"), new DocumentId(text(row, "target")), text(row, "suppliedRef"), SourceFrontierViewCodec.order(row.get("frontier")));
            List<ManagedReadPin> selectedPins = readPins(array(row, "selectedView"), d);
            if (selectedPins.size() != 1) throw invalid("An attachment must retain exactly one selected view");
            String frontierReference = text(row, "frontierView");
            AcceptedAttachmentView view = frontierReference == null ? AcceptedAttachmentView.fromCanonicalSite(selection,
                    text(row, "creatorSeed"), text(row, "creatorSite"), text(row, "sourceSeed"),
                    text(row, "sourceSite"), selectedPins.get(0)) : AcceptedAttachmentView.fromFrontierSite(selection,
                    text(row, "creatorSeed"), text(row, "creatorSite"), SourceFrontierViewCodec.decode(frontierReference, d));
            if (!view.selectedView().blueId().equals(selectedPins.get(0).blueId())
                    || !view.selectedView().frozenDocument().blueId().equals(selectedPins.get(0).frozenDocument().blueId()))
                throw invalid("Selected frontier pin differs from its retained placement");
            if (!view.identity().equals(text(row, "identity"))) throw invalid("Accepted attachment identity mismatch");
            acceptedViews.add(view);
        }
        List<AcceptedInitializationInstallation> acceptedInitializations = new ArrayList<>();
        for (JsonNode reference : array(root, "acceptedInitializations"))
            acceptedInitializations.add(AcceptedInitializationInstallationCodec.decode(nullable(reference), d));
        List<SourceObservationProgram.SkippedWork> skippedWork = new ArrayList<>();
        for (JsonNode skipped : array(root, "skippedWork")) {
            if (!skipped.isObject() || skipped.size() != 6) throw invalid("Skipped work must have exactly six fields");
            skippedWork.add(new SourceObservationProgram.SkippedWork(text(skipped, "work"),
                    new DocumentId(text(skipped, "target")), WorkKind.valueOf(text(skipped, "kind")),
                    text(skipped, "channel"), text(skipped, "sourceOccurrence"), text(skipped, "requestWork")));
        }
        return new SourceObservationProgram(text(root,"invocation"), ProcessingCause.Kind.valueOf(text(root,"causeKind")),
                text(root,"cause"), external, environment, policy(root.get("policy")),
                states(array(root,"before"), d), states(array(root,"after"), d), owned, steps, projections,
                bindings(root.get("beforeTopology")), bindings(root.get("afterTopology")),
                components(root.get("beforeTopology"), d), components(root.get("afterTopology"), d),
                borrowed, readPins(array(root, "readPins"), d), acceptedViews,
                text(root, "managedReaction") == null ? null : ManagedReactionContextCodec.decode(text(root, "managedReaction"), d), acceptedInitializations, skippedWork);
    }

    private static Object readPins(List<ManagedReadPin> pins, Encoder e) {
        List<Object> rows = new ArrayList<>();
        for (ManagedReadPin pin : pins) {
            List<String> proof = new ArrayList<>();
            if (pin.cyclicProof().isPresent()) for (Node member : pin.cyclicProof().get().declaredPlaceholderSet())
                proof.add(e.node(FrozenNode.fromResolvedNode(member)));
            rows.add(map("document", pin.documentId().value(), BlueLanguageConstants.OBJECT_BLUE_ID, pin.blueId(),
                    "body", e.node(pin.frozenDocument()), "proof", proof));
        }
        return rows;
    }

    private static List<ManagedReadPin> readPins(JsonNode rows, Decoder d) {
        List<ManagedReadPin> pins = new ArrayList<>(); Set<ManagedReadPin> unique = new TreeSet<>();
        for (JsonNode row : rows) {
            List<Node> proof = new ArrayList<>();
            for (JsonNode member : array(row, "proof")) proof.add(d.materialize(nullable(member)));
            ManagedReadPin pin = ManagedReadPin.fromExactEvidence(new DocumentId(text(row, "document")), text(row, BlueLanguageConstants.OBJECT_BLUE_ID),
                    d.materialize(text(row, "body")), proof.isEmpty() ? null : blue.language.provider.CyclicSetProof.fromDeclaredPlaceholderSet(proof));
            if (!unique.add(pin)) throw invalid("Repeated source exact read pin");
            pins.add(pin);
        }
        return pins;
    }

    // This recursion is explicitly acquisition-bounded, never a semantic depth or gas limit.
    // A later iterative reader can raise the stack-safety ceiling without changing identities.
    private static void programDepth(int depth, int height, Limits limits) {
        if ((long) depth + height > Math.min(limits.depth, 256))
            throw new CapacityExceeded("Borrowed program nesting exceeds acquisition/stack-safety capacity");
    }

    private static final class Encoding {
        final Encoder encoder;
        final Map<SourceObservationProgram, String> known = new IdentityHashMap<>();
        final Map<SourceObservationProgram, Integer> heights = new IdentityHashMap<>();
        final Map<String, String> operationRoots = new HashMap<>();
        final Set<String> active = new HashSet<>();
        int count;
        Encoding(Encoder encoder) { this.encoder = Objects.requireNonNull(encoder); }
        String program(SourceObservationProgram program, int depth) {
            Objects.requireNonNull(program); programDepth(depth, 0, encoder.limits());
            String cached = known.get(program);
            if (cached != null) { programDepth(depth, heights.get(program), encoder.limits()); return cached; }
            if (++count > encoder.limits().nodes) throw new CapacityExceeded("Borrowed program count exceeds acquisition capacity");
            if (!active.add(program.invocationIdentity())) throw invalid("Borrowed source programs form an execution-identity cycle");
            try {
                List<SourceObservationProgram> dependencies = new ArrayList<>(program.borrowedPrograms());
                dependencies.sort(Comparator.comparing(SourceObservationProgram::invocationIdentity));
                List<String> references = new ArrayList<>(); int height = 0;
                for (SourceObservationProgram dependency : dependencies) {
                    references.add(program(dependency, depth + 1)); height = Math.max(height, 1 + heights.get(dependency));
                }
                programDepth(depth, height, encoder.limits());
                String root = encodeOne(program, encoder, references);
                String previous = operationRoots.putIfAbsent(program.invocationIdentity(), root);
                if (previous != null && !previous.equals(root)) throw invalid("One producing operation has conflicting program contents");
                known.put(program, root); heights.put(program, height); return root;
            } finally { active.remove(program.invocationIdentity()); }
        }
    }

    private static final class Decoding {
        final Decoder decoder;
        final Map<String, SourceObservationProgram> known = new HashMap<>();
        final Map<String, Integer> heights = new HashMap<>();
        final Map<String, String> operationRoots = new HashMap<>();
        final Set<String> active = new HashSet<>();
        int count;
        Decoding(Decoder decoder) { this.decoder = Objects.requireNonNull(decoder); }
        SourceObservationProgram program(String identity, int depth) {
            programDepth(depth, 0, decoder.limits());
            SourceObservationProgram cached = known.get(identity);
            if (cached != null) { programDepth(depth, heights.get(identity), decoder.limits()); return cached; }
            if (++count > decoder.limits().nodes) throw new CapacityExceeded("Borrowed program count exceeds acquisition capacity");
            if (!active.add(identity)) throw invalid("Cyclic borrowed program fragments");
            try {
                JsonNode root = json(decoder.blob(identity));
                List<SourceObservationProgram> dependencies = new ArrayList<>(); int height = 0;
                for (JsonNode reference : array(root, "borrowedPrograms")) {
                    String child = nullable(reference); dependencies.add(program(child, depth + 1));
                    height = Math.max(height, 1 + heights.get(child));
                }
                programDepth(depth, height, decoder.limits());
                SourceObservationProgram value = decodeOne(root, decoder, dependencies);
                String previous = operationRoots.putIfAbsent(value.invocationIdentity(), identity);
                if (previous != null && !previous.equals(identity)) throw invalid("One producing operation has conflicting program contents");
                known.put(identity, value); heights.put(identity, height); return value;
            } finally { active.remove(identity); }
        }
    }

    private static Object topology(List<ManagedOccurrenceBinding> bindings, List<ComponentSnapshot> components, Encoder e) {
        List<Object> groups = new ArrayList<>();
        for (ComponentSnapshot component : components) {
            List<String> members = new ArrayList<>(), proof = new ArrayList<>();
            for (DocumentId member : component.orderedMemberDocumentIds()) members.add(member.value());
            if (component.completeCyclicProof() != null) for (Node member : component.completeCyclicProof().declaredPlaceholderSet())
                proof.add(e.node(FrozenNode.fromResolvedNode(member)));
            groups.add(map("identity", component.componentIdentity(), "stateIdentity", component.componentStateIdentity(),
                    "generation", component.componentGeneration(), "kind", component.kind().name(), "members", members,
                    "blueIds", component.orderedMemberBlueIds(), "master", component.masterBlueId(),
                    "proofIdentity", component.cyclicProofIdentity(), "proof", proof));
        }
        return map("bindings", bindingRows(bindings), "components", groups);
    }

    private static List<Object> bindingRows(List<ManagedOccurrenceBinding> bindings) {
        List<Object> edges = new ArrayList<>();
        for (ManagedOccurrenceBinding binding : bindings) edges.add(map("occurrence", binding.occurrenceIdentity(),
                "binding", binding.bindingIdentity(), "policy", binding.bindingPolicyIdentity(),
                "source", binding.sourceDocumentId().value(), "path", binding.sourcePath(),
                "activation", binding.sourceAddress().activationGeneration(), "target", binding.targetDocumentId().value(),
                "expected", binding.expectedTargetBlueId(), "active", binding.active(), "pendingEpoch", binding.pendingHistoricalEpoch()));
        return edges;
    }

    private static List<ManagedOccurrenceBinding> bindings(JsonNode topology) {
        return bindingRows(array(topology, "bindings"));
    }

    private static List<ManagedOccurrenceBinding> bindingRows(JsonNode rows) {
        List<ManagedOccurrenceBinding> bindings = new ArrayList<>();
        for (JsonNode row : rows) {
            JsonNode pending = row.get("pendingEpoch");
            bindings.add(ManagedOccurrenceBinding.verified(text(row, "occurrence"), text(row, "binding"), text(row, "policy"),
                    new DocumentId(text(row, "source")), ScopeAddress.embedded(text(row, "path"), integer(row, "activation")),
                    new DocumentId(text(row, "target")), text(row, "expected"), bool(row, "active"),
                    pending == null || pending.isNull() ? null : integer(pending)));
        }
        return bindings;
    }

    private static List<ComponentSnapshot> components(JsonNode topology, Decoder d) {
        List<ComponentSnapshot> components = new ArrayList<>();
        for (JsonNode row : array(topology, "components")) {
            List<DocumentId> members = new ArrayList<>(); List<String> blueIds = new ArrayList<>(); List<Node> proof = new ArrayList<>();
            for (JsonNode member : array(row, "members")) members.add(new DocumentId(nullable(member)));
            for (JsonNode blueId : array(row, "blueIds")) blueIds.add(nullable(blueId));
            for (JsonNode member : array(row, "proof")) proof.add(d.materialize(nullable(member)));
            ComponentSnapshot component = new ComponentSnapshot(text(row, "identity"), text(row, "stateIdentity"),
                    integer(row, "generation"), ComponentKind.valueOf(text(row, "kind")), members, blueIds,
                    text(row, "master"), proof.isEmpty() ? null : blue.language.provider.CyclicSetProof.fromDeclaredPlaceholderSet(proof),
                    text(row, "proofIdentity"));
            ClosureIdentityService ids = ClosureIdentityService.INSTANCE;
            if (!ids.componentIdentity(component.kind(), component.componentGeneration(), members).equals(component.componentIdentity())
                    || !ids.componentStateIdentity(component).equals(component.componentStateIdentity())
                    || component.kind() == ComponentKind.CYCLIC && !ids.cyclicProofIdentity(component).equals(component.cyclicProofIdentity()))
                throw invalid("Retained source component identity mismatch");
            components.add(component);
        }
        return components;
    }

    private static Object states(List<SourceObservationProgram.SourceState> states, Encoder e) {
        List<Object> rows = new ArrayList<Object>();
        for (SourceObservationProgram.SourceState state : states) rows.add(map("document", state.documentId().value(),
                BlueLanguageConstants.OBJECT_BLUE_ID, state.blueId(), "epoch", state.epoch(), ProcessorContractConstants.KEY_INITIALIZED, state.initialized(),
                "resident", state.hasResidentBody(), "body", state.hasResidentBody() ? e.node(state.frozenDocument()) : null));
        return rows;
    }
    private static List<SourceObservationProgram.SourceState> states(JsonNode rows, Decoder d) {
        List<SourceObservationProgram.SourceState> result = new ArrayList<SourceObservationProgram.SourceState>();
        for (JsonNode row : rows) {
            DocumentId id = new DocumentId(text(row,"document"));
            String blueId = text(row,BlueLanguageConstants.OBJECT_BLUE_ID); long epoch = integer(row,"epoch"); boolean initialized = bool(row,ProcessorContractConstants.KEY_INITIALIZED);
            if (bool(row, "resident")) result.add(new SourceObservationProgram.SourceState(id, blueId, epoch, initialized, d.node(text(row,"body"))));
            else {
                if (!row.has("body") || !row.get("body").isNull()) throw invalid("Header-only source state cannot contain a body fragment");
                result.add(SourceObservationProgram.SourceState.headerOnly(id, blueId, epoch, initialized));
            }
        }
        return result;
    }
    private static Object step(SourceObservationProgram.Step step, Encoder e) {
        List<String> events = new ArrayList<String>(); for (FrozenNode event : step.frozenEvents()) events.add(e.node(event));
        List<Object> patches = new ArrayList<Object>(); for (FrozenJsonPatch patch : step.orderedPatches()) patches.add(patch(patch,e));
        List<Object> actions = new ArrayList<Object>();
        for (SourceObservationProgram.Action action : step.actions()) {
            if (action instanceof SourceObservationProgram.Patch) {
                SourceObservationProgram.Patch p = (SourceObservationProgram.Patch) action;
                if (p.siteIdentity() == null || p.transitionIdentity() == null) throw invalid("Source patch is missing its original site or transition identity");
                if (p.resultingBindings() == null) throw invalid("Source patch is missing its exact reconciled outgoing rows");
                for (ManagedOccurrenceBinding binding : p.resultingBindings())
                    if (!binding.sourceDocumentId().equals(step.targetDocumentId())) throw invalid("Patch rows name another source document");
                List<Object> updates = new ArrayList<Object>();
                for (DocumentUpdateOccurrence u : p.updates()) updates.add(map("path",u.path(), "before",e.node(u.frozenBefore()), "after",e.node(u.frozenAfter()),
                        "op",u.op().name(), "origin",u.originScope(), "recipients",u.recipientChain(), "dispatch",e.node(u.frozenRootDispatchContracts())));
                actions.add(map("kind","patch", "site", p.siteIdentity(), "transition", p.transitionIdentity(), "document",e.node(p.frozenDocument()), "patch",patch(p.patch(),e), "updates",updates,
                        "resultingBindings", bindingRows(p.resultingBindings())));
            } else if (action instanceof SourceObservationProgram.Enqueue) {
                SourceObservationProgram.Enqueue event = (SourceObservationProgram.Enqueue) action;
                actions.add(map("kind","enqueue", "contract",event.contractKey(), "event",e.node(event.frozenEvent()),
                        "eventBlueId",event.eventBlueId(), "occurrence",event.occurrenceIdentity()));
            } else if (action instanceof SourceObservationProgram.TerminationRequest) {
                SourceObservationProgram.TerminationRequest termination = (SourceObservationProgram.TerminationRequest) action;
                actions.add(map("kind", "termination", "cause", termination.cause(), "reason", termination.reason()));
            } else throw invalid("Unsupported source observation action");
        }
        return map("work", step.workIdentity(), "entrySite", step.entrySiteIdentity(), "document",step.targetDocumentId().value(), "kind",step.kind().name(), "channel",step.channelKey(),
                "payload",e.node(step.frozenPayload()), "before",e.node(step.frozenBefore()), "after",e.node(step.frozenAfter()),
                "identityAffecting",step.identityAffecting(), "events",events, "patches",patches, "actions",actions);
    }
    private static SourceObservationProgram.Step step(JsonNode row, Decoder d) {
        List<FrozenNode> events = new ArrayList<FrozenNode>(); for (JsonNode ref : array(row,"events")) events.add(d.node(nullable(ref)));
        List<FrozenJsonPatch> patches = new ArrayList<FrozenJsonPatch>(); for (JsonNode p : array(row,"patches")) patches.add(patch(p,d));
        List<SourceObservationProgram.Action> actions = new ArrayList<SourceObservationProgram.Action>();
        for (JsonNode a : array(row,"actions")) {
            if ("patch".equals(text(a,"kind"))) {
                List<DocumentUpdateOccurrence> updates = new ArrayList<DocumentUpdateOccurrence>();
                for (JsonNode u : array(a,"updates")) {
                    List<String> recipients = new ArrayList<String>(); for (JsonNode scope : array(u,"recipients")) recipients.add(nullable(scope));
                    updates.add(DocumentUpdateOccurrence.fromRetainedFrozenEvidence(text(u,"path"),d.node(text(u,"before")),
                            d.node(text(u,"after")),JsonPatch.Op.valueOf(text(u,"op")),text(u,"origin"),recipients,d.node(text(u,"dispatch"))));
                }
                String transition = text(a, "transition");
                if (text(a, "site") == null || transition == null) throw invalid("Retained source patch has no original site or transition identity");
                List<ManagedOccurrenceBinding> exactRows = bindingRows(array(a, "resultingBindings"));
                for (ManagedOccurrenceBinding binding : exactRows)
                    if (!binding.sourceDocumentId().value().equals(text(row, "document"))) throw invalid("Patch rows name another source document");
                actions.add(new SourceObservationProgram.Patch(text(a,"site"),transition,d.node(text(a,"document")),patch(a.get("patch"),d),updates)
                        .withResultingBindings(exactRows));
            } else if ("enqueue".equals(text(a,"kind"))) {
                actions.add(new SourceObservationProgram.Enqueue(text(a,"contract"),d.node(text(a,"event")),text(a,"eventBlueId"),text(a,"occurrence")));
            } else if ("termination".equals(text(a,"kind"))) {
                if (a.size() != 3 || !a.has("cause") || !a.has("reason")) throw invalid("Malformed termination action fields");
                actions.add(new SourceObservationProgram.TerminationRequest(text(a, "cause"), text(a, "reason")));
            } else throw invalid("Unsupported retained action kind");
        }
        String work = text(row, "work");
        if (work == null) throw invalid("Retained source step has no original work identity");
        return new SourceObservationProgram.Step(work,text(row,"entrySite"),new DocumentId(text(row,"document")),WorkKind.valueOf(text(row,"kind")),text(row,"channel"),
                d.node(text(row,"payload")),d.node(text(row,"before")),d.node(text(row,"after")),events,patches,bool(row,"identityAffecting"),actions);
    }
    private static Object patch(FrozenJsonPatch p, Encoder e) {
        return map("op",p.getOp().name(), "path",p.getPath(), BlueLanguageConstants.OBJECT_VALUE,e.node(p.getValue()));
    }
    private static FrozenJsonPatch patch(JsonNode p, Decoder d) {
        String path = text(p,"path");
        switch (JsonPatch.Op.valueOf(text(p,"op"))) {
            case ADD: return FrozenJsonPatch.add(path,d.node(text(p,BlueLanguageConstants.OBJECT_VALUE)));
            case REPLACE: return FrozenJsonPatch.replace(path,d.node(text(p,BlueLanguageConstants.OBJECT_VALUE)));
            case REMOVE: if (text(p,BlueLanguageConstants.OBJECT_VALUE) != null) throw invalid("Remove patch carries a value"); return FrozenJsonPatch.remove(path);
            default: throw invalid("Unsupported retained patch operation");
        }
    }
    private static String node(Encoder e, Node node) { return node == null ? null : e.node(FrozenNode.fromResolvedNode(node)); }

    static Object environment(ClosureEnvironment e) {
        return map("language",e.blueLanguageSpecificationIdentity(), BlueLanguageConstants.OBJECT_CONTRACTS,e.contractsSpecificationIdentity(),
                "runtime",e.runtimeRegistryIdentity(), "gas",e.gasManifestIdentity(), "identityPolicy",label(e.managedDocumentIdentityPolicy()),
                "bindingPolicy",label(e.managedBindingPolicy()), "provider",label(e.exactNodeProviderDomain()), "order",label(e.externalOrderPolicy()),
                "portable",map("identity",e.portableLimitPolicyIdentity(),"label",e.portableLimitPolicy().label(),"limits",e.portableLimitPolicy().limits()),
                "cyclicFinalizer",e.cyclicFinalizerIdentity(), "cyclicVerifier",e.cyclicProofVerifierIdentity());
    }
    static ClosureEnvironment environment(JsonNode e) {
        JsonNode portable=e.get("portable"); Map<String,Long> limits=new LinkedHashMap<String,Long>();
        Iterator<Map.Entry<String,JsonNode>> it=portable.path("limits").fields(); while(it.hasNext()){Map.Entry<String,JsonNode> p=it.next();limits.put(p.getKey(),integer(p.getValue()));}
        return new ClosureEnvironment(text(e,"language"),text(e,BlueLanguageConstants.OBJECT_CONTRACTS),text(e,"runtime"),text(e,"gas"),
                label(e.get("identityPolicy")),label(e.get("bindingPolicy")),label(e.get("provider")),label(e.get("order")),
                new ClosureEnvironment.PortableLimitPolicyEvidence(text(portable,"identity"),text(portable,"label"),limits),text(e,"cyclicFinalizer"),text(e,"cyclicVerifier"));
    }
    private static Object label(ClosureEnvironment.LabeledIdentityEvidence v) { return map("identity",v.identity(),"label",v.label()); }
    private static ClosureEnvironment.LabeledIdentityEvidence label(JsonNode v) { return new ClosureEnvironment.LabeledIdentityEvidence(text(v,"identity"),text(v,"label")); }
    static Object policy(ExecutionPolicy p) {
        Map<String,Long> limits=new TreeMap<String,Long>();for(Map.Entry<DocumentId,Long> l:p.localLimits().entrySet())limits.put(l.getKey().value(),l.getValue());
        return map("identity",p.identity(),"label",p.label(),"sharedLimit",p.sharedLimit(),"localLimits",limits);
    }
    static ExecutionPolicy policy(JsonNode p) {
        Map<DocumentId,Long> limits=new TreeMap<DocumentId,Long>();Iterator<Map.Entry<String,JsonNode>> it=p.path("localLimits").fields();
        while(it.hasNext()){Map.Entry<String,JsonNode> l=it.next();limits.put(new DocumentId(l.getKey()),integer(l.getValue()));}
        return new ExecutionPolicy(text(p,"identity"),integer(p,"sharedLimit"),limits,text(p,"label"));
    }
    private static Map<String,Object> map(Object... pairs) { Map<String,Object> m=new TreeMap<String,Object>();for(int i=0;i<pairs.length;i+=2)m.put((String)pairs[i],pairs[i+1]);return m; }
    private static JsonNode array(JsonNode p,String field) {JsonNode v=p.get(field);if(v==null||!v.isArray())throw invalid("Expected evidence array: "+field);return v;}
    private static boolean bool(JsonNode p,String field) {JsonNode v=p.get(field);if(v==null||!v.isBoolean())throw invalid("Expected evidence boolean: "+field);return v.booleanValue();}
    private static long integer(JsonNode p,String field){return integer(p.get(field));}
    private static long integer(JsonNode v){if(v==null||!v.isIntegralNumber()||!v.canConvertToLong())throw invalid("Expected evidence integer");return v.longValue();}
}
