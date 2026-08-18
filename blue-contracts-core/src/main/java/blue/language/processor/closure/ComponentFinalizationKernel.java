package blue.language.processor.closure;

import blue.language.identity.BlueIds;
import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.identity.CyclicMemberFinalization;
import blue.language.identity.CyclicSetFinalization;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.Schema;
import blue.language.model.wire.ParsedJsonPointer;
import blue.language.provider.CyclicSetProof;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Pure exact-identity kernel for finalized managed-document components.
 *
 * <p>The kernel derives the active graph solely from complete occurrence
 * bindings, assigns component generations, and visits SCCs target before
 * source. It rewrites only active occurrence paths. Acyclic singletons use
 * the ordinary Language direct identity path; cyclic components delegate the
 * complete placeholder set to the unchanged Language circular-set
 * finalizer.</p>
 *
 * <p>This class performs no document-step execution, scheduling, gas charge,
 * epoch advance, durable write, or commit.</p>
 */
public final class ComponentFinalizationKernel {

    private static final String PROVISIONAL_IDENTITY =
            "sha256:0000000000000000000000000000000000000000000000000000000000000000";

    private final DirectBlueIdCalculator directCalculator;
    private final CircularSetIdentityCalculator circularCalculator;
    private final ClosureIdentityService identityService;

    /** Creates a kernel using the normative Language identity services. */
    public ComponentFinalizationKernel() {
        this(new DirectBlueIdCalculator(),
                new CircularSetIdentityCalculator(),
                ClosureIdentityService.INSTANCE);
    }

    ComponentFinalizationKernel(
            DirectBlueIdCalculator directCalculator,
            CircularSetIdentityCalculator circularCalculator,
            ClosureIdentityService identityService) {
        this.directCalculator = Objects.requireNonNull(
                directCalculator, "directCalculator");
        this.circularCalculator = Objects.requireNonNull(
                circularCalculator, "circularCalculator");
        this.identityService = Objects.requireNonNull(
                identityService, "identityService");
    }

    /**
     * Finalizes every resulting component and exact containing spine.
     *
     * @param input complete predecessor and resulting semantic state
     * @return immutable exact finalization evidence
     * @throws IllegalArgumentException if bindings, paths, generations, or
     *         cyclic evidence are incomplete or inconsistent
     */
    public ComponentFinalizationResult finalizeComponents(
            ComponentFinalizationInput input) {
        ComponentFinalizationInput selected = Objects.requireNonNull(
                input, "input");
        validateBindingClaims(selected.inputGraph().bindings());
        validateBindingClaims(selected.resultingGraph().bindings());

        ManagedDocumentGraph graph = selected.resultingGraph();
        Map<DocumentId, Node> workingBodies = workingBodies(selected, graph);
        validateActivePaths(graph, workingBodies);
        Map<DocumentId, Long> generations =
                ComponentGenerationTransition.assign(
                        selected.inputGraph(),
                        selected.inputComponentGenerations(),
                        graph);
        List<List<DocumentId>> partition =
                new SccPartitioner().partition(graph);

        LinkedHashMap<DocumentId, DocumentDraft> drafts =
                new LinkedHashMap<DocumentId, DocumentDraft>();
        ArrayList<FinalizedComponentEvidence> componentEvidence =
                new ArrayList<FinalizedComponentEvidence>();
        LinkedHashMap<DocumentId, FinalizedDocumentEvidence>
                documentEvidence =
                new LinkedHashMap<DocumentId, FinalizedDocumentEvidence>();

        for (List<DocumentId> members : partition) {
            ComponentKind kind = componentKind(graph, members);
            rewriteComponentOccurrences(
                    graph, members, kind, workingBodies, drafts);
            CyclicSetFinalization cyclicFinalization = kind
                    == ComponentKind.CYCLIC
                    ? finalizeCyclicComponent(members, workingBodies, drafts)
                    : finalizeAcyclicComponent(members, workingBodies, drafts);
            long generation = generations.get(members.get(0)).longValue();
            ComponentSnapshot snapshot = componentSnapshot(
                    kind, generation, members, drafts, cyclicFinalization);
            FinalizedComponentEvidence finalizedComponent =
                    new FinalizedComponentEvidence(
                            snapshot, cyclicFinalization);
            componentEvidence.add(finalizedComponent);
            for (DocumentId member : members) {
                DocumentDraft draft = drafts.get(member);
                FinalizedDocumentEvidence finalizedDocument =
                        new FinalizedDocumentEvidence(
                                member,
                                draft.blueId,
                                draft.document,
                                snapshot,
                                draft.canonicalMemberIndex,
                                draft.preliminaryBlueId);
                documentEvidence.put(member, finalizedDocument);
            }
        }

        ManagedDocumentGraph finalizedGraph = rebindActiveRows(
                graph, documentEvidence);
        if (!graph.adjacency().equals(finalizedGraph.adjacency())) {
            throw new IllegalStateException(
                    "Identity rebasing changed the active graph topology");
        }
        validateFinalizedReferences(finalizedGraph, documentEvidence);
        return new ComponentFinalizationResult(
                finalizedGraph,
                generations,
                componentEvidence,
                documentEvidence);
    }

    private Map<DocumentId, Node> workingBodies(
            ComponentFinalizationInput input,
            ManagedDocumentGraph graph) {
        LinkedHashMap<DocumentId, Node> result =
                new LinkedHashMap<DocumentId, Node>();
        for (DocumentId documentId : graph.documentIds()) {
            Node body = input.localBody(documentId);
            requireNoCyclicPlaceholder(body, documentId.value());
            result.put(documentId, body);
        }
        return result;
    }

    private void validateBindingClaims(
            List<ManagedOccurrenceBinding> bindings) {
        for (ManagedOccurrenceBinding binding : bindings) {
            String occurrenceIdentity = identityService
                    .managedOccurrenceIdentity(
                            binding.sourceDocumentId(),
                            binding.sourceAddress(),
                            binding.targetDocumentId(),
                            binding.bindingPolicyIdentity());
            if (!occurrenceIdentity.equals(binding.occurrenceIdentity())) {
                throw new IllegalArgumentException(
                        "Managed occurrence identity does not match its row");
            }
            String bindingIdentity = identityService
                    .managedOccurrenceBindingIdentity(
                            binding.sourceDocumentId(),
                            binding.sourceAddress(),
                            binding.targetDocumentId(),
                            binding.expectedTargetBlueId(),
                            binding.bindingPolicyIdentity());
            if (!bindingIdentity.equals(binding.bindingIdentity())) {
                throw new IllegalArgumentException(
                        "Managed binding identity does not match its row");
            }
        }
    }

    private static void validateActivePaths(
            ManagedDocumentGraph graph,
            Map<DocumentId, Node> bodies) {
        Map<DocumentId, List<ParsedJsonPointer>> paths =
                new HashMap<DocumentId, List<ParsedJsonPointer>>();
        for (ManagedOccurrenceBinding binding : graph.activeBindings()) {
            Node body = bodies.get(binding.sourceDocumentId());
            if (NodePathEditor.getOrNull(body, binding.sourcePath()) == null) {
                throw new IllegalArgumentException(
                        "Active occurrence path is absent from its source body");
            }
            List<ParsedJsonPointer> sourcePaths = paths.get(
                    binding.sourceDocumentId());
            if (sourcePaths == null) {
                sourcePaths = new ArrayList<ParsedJsonPointer>();
                paths.put(binding.sourceDocumentId(), sourcePaths);
            }
            ParsedJsonPointer current = ParsedJsonPointer.parse(
                    binding.sourcePath());
            for (ParsedJsonPointer existing : sourcePaths) {
                if (current.overlaps(existing)) {
                    throw new IllegalArgumentException(
                            "Active occurrence paths overlap in one source document");
                }
            }
            sourcePaths.add(current);
        }
    }

    private static ComponentKind componentKind(
            ManagedDocumentGraph graph,
            List<DocumentId> members) {
        return members.size() > 1 || graph.hasSelfEdge(members.get(0))
                ? ComponentKind.CYCLIC
                : ComponentKind.ACYCLIC;
    }

    private static void rewriteComponentOccurrences(
            ManagedDocumentGraph graph,
            List<DocumentId> members,
            ComponentKind kind,
            Map<DocumentId, Node> bodies,
            Map<DocumentId, DocumentDraft> finalized) {
        Map<DocumentId, Integer> internalIndexes =
                new HashMap<DocumentId, Integer>();
        for (int index = 0; index < members.size(); index++) {
            internalIndexes.put(members.get(index), Integer.valueOf(index));
        }
        for (ManagedOccurrenceBinding binding : graph.activeBindings()) {
            Integer sourceIndex = internalIndexes.get(
                    binding.sourceDocumentId());
            if (sourceIndex == null) {
                continue;
            }
            Integer targetIndex = internalIndexes.get(
                    binding.targetDocumentId());
            String replacement;
            if (targetIndex != null) {
                if (kind != ComponentKind.CYCLIC) {
                    throw new IllegalStateException(
                            "Acyclic component contains an internal edge");
                }
                replacement = BlueIds.indexedThisPlaceholder(
                        targetIndex.intValue());
            } else {
                DocumentDraft target = finalized.get(
                        binding.targetDocumentId());
                if (target == null) {
                    throw new IllegalStateException(
                            "Component order is not target before source");
                }
                replacement = target.blueId;
            }
            NodePathEditor.put(
                    bodies.get(binding.sourceDocumentId()),
                    binding.sourcePath(),
                    new Node().blueId(replacement));
        }
    }

    private CyclicSetFinalization finalizeCyclicComponent(
            List<DocumentId> members,
            Map<DocumentId, Node> bodies,
            Map<DocumentId, DocumentDraft> drafts) {
        ArrayList<Node> placeholderBodies = new ArrayList<Node>();
        for (DocumentId member : members) {
            placeholderBodies.add(bodies.get(member).clone());
        }
        CyclicSetFinalization finalization = circularCalculator
                .finalizeCyclicSet(placeholderBodies);
        List<String> canonicalBlueIds = canonicalBlueIds(finalization);
        List<CyclicMemberFinalization> inputMembers =
                finalization.membersInInputOrder();
        if (inputMembers.size() != members.size()) {
            throw new IllegalStateException(
                    "Language cyclic finalization did not cover every member");
        }
        for (int index = 0; index < members.size(); index++) {
            CyclicMemberFinalization member = inputMembers.get(index);
            if (member.inputIndex() != index) {
                throw new IllegalStateException(
                        "Language cyclic input mapping is not complete");
            }
            Node exactBody = member.canonicalMemberBody();
            materializeCyclicReferences(exactBody, canonicalBlueIds);
            requireNoCyclicPlaceholder(
                    exactBody, members.get(index).value());
            drafts.put(members.get(index), new DocumentDraft(
                    exactBody,
                    member.finalBlueId(),
                    Integer.valueOf(member.canonicalIndex()),
                    member.preliminaryBlueId()));
        }
        return finalization;
    }

    private CyclicSetFinalization finalizeAcyclicComponent(
            List<DocumentId> members,
            Map<DocumentId, Node> bodies,
            Map<DocumentId, DocumentDraft> drafts) {
        if (members.size() != 1) {
            throw new IllegalStateException(
                    "Acyclic component must contain one document");
        }
        DocumentId member = members.get(0);
        Node document = bodies.get(member).clone();
        String blueId = directCalculator.directBlueId(document);
        drafts.put(member, new DocumentDraft(
                document, blueId, null, null));
        return null;
    }

    private ComponentSnapshot componentSnapshot(
            ComponentKind kind,
            long generation,
            List<DocumentId> members,
            Map<DocumentId, DocumentDraft> drafts,
            CyclicSetFinalization finalization) {
        ArrayList<String> memberBlueIds = new ArrayList<String>();
        for (DocumentId member : members) {
            memberBlueIds.add(drafts.get(member).blueId);
        }
        String componentIdentity = identityService.componentIdentity(
                kind, generation, members);
        if (kind == ComponentKind.ACYCLIC) {
            ComponentSnapshot provisional = new ComponentSnapshot(
                    componentIdentity,
                    PROVISIONAL_IDENTITY,
                    generation,
                    kind,
                    members,
                    memberBlueIds,
                    null,
                    null,
                    null);
            String stateIdentity = identityService.componentStateIdentity(
                    provisional);
            return new ComponentSnapshot(
                    componentIdentity,
                    stateIdentity,
                    generation,
                    kind,
                    members,
                    memberBlueIds,
                    null,
                    null,
                    null);
        }

        CyclicSetFinalization selected = Objects.requireNonNull(
                finalization, "cyclicFinalization");
        CyclicSetProof proof = CyclicSetProof.fromDeclaredPlaceholderSet(
                selected.canonicalMemberBodies());
        ComponentSnapshot withoutProofIdentity = new ComponentSnapshot(
                componentIdentity,
                PROVISIONAL_IDENTITY,
                generation,
                kind,
                members,
                memberBlueIds,
                selected.masterBlueId(),
                proof,
                PROVISIONAL_IDENTITY);
        String proofIdentity = identityService.cyclicProofIdentity(
                withoutProofIdentity);
        ComponentSnapshot withoutStateIdentity = new ComponentSnapshot(
                componentIdentity,
                PROVISIONAL_IDENTITY,
                generation,
                kind,
                members,
                memberBlueIds,
                selected.masterBlueId(),
                proof,
                proofIdentity);
        String stateIdentity = identityService.componentStateIdentity(
                withoutStateIdentity);
        return new ComponentSnapshot(
                componentIdentity,
                stateIdentity,
                generation,
                kind,
                members,
                memberBlueIds,
                selected.masterBlueId(),
                proof,
                proofIdentity);
    }

    private ManagedDocumentGraph rebindActiveRows(
            ManagedDocumentGraph graph,
            Map<DocumentId, FinalizedDocumentEvidence> documents) {
        ArrayList<ManagedOccurrenceBinding> rebound =
                new ArrayList<ManagedOccurrenceBinding>();
        for (ManagedOccurrenceBinding binding : graph.bindings()) {
            if (!binding.active()) {
                rebound.add(binding);
                continue;
            }
            String targetBlueId = documents.get(
                    binding.targetDocumentId()).blueId();
            String bindingIdentity = identityService
                    .managedOccurrenceBindingIdentity(
                            binding.sourceDocumentId(),
                            binding.sourceAddress(),
                            binding.targetDocumentId(),
                            targetBlueId,
                            binding.bindingPolicyIdentity());
            rebound.add(new ManagedOccurrenceBinding(
                    binding.occurrenceIdentity(),
                    bindingIdentity,
                    binding.bindingPolicyIdentity(),
                    binding.sourceDocumentId(),
                    binding.sourceAddress(),
                    binding.targetDocumentId(),
                    targetBlueId,
                    true,
                    null));
        }
        return ManagedDocumentGraph.fromBindings(
                graph.documentIds(), rebound);
    }

    private static void validateFinalizedReferences(
            ManagedDocumentGraph graph,
            Map<DocumentId, FinalizedDocumentEvidence> documents) {
        for (ManagedOccurrenceBinding binding : graph.activeBindings()) {
            Node reference = NodePathEditor.getOrNull(
                    documents.get(binding.sourceDocumentId()).document(),
                    binding.sourcePath());
            if (reference == null
                    || !reference.isReferenceOnly()
                    || !binding.expectedTargetBlueId().equals(
                            reference.getBlueId())
                    || !documents.get(binding.targetDocumentId()).blueId()
                            .equals(reference.getBlueId())) {
                throw new IllegalStateException(
                        "Finalized active occurrence is not an exact target reference");
            }
        }
    }

    private static List<String> canonicalBlueIds(
            CyclicSetFinalization finalization) {
        ArrayList<String> result = new ArrayList<String>();
        for (CyclicMemberFinalization member
                : finalization.membersInCanonicalOrder()) {
            if (member.canonicalIndex() != result.size()) {
                throw new IllegalStateException(
                        "Language canonical member indexes are not contiguous");
            }
            result.add(member.finalBlueId());
        }
        return Collections.unmodifiableList(result);
    }

    private static void requireNoCyclicPlaceholder(
            Node node,
            String documentId) {
        IdentityHashMap<Object, Boolean> visited =
                new IdentityHashMap<Object, Boolean>();
        inspectNode(node, documentId, null, visited);
    }

    private static void materializeCyclicReferences(
            Node node,
            List<String> canonicalBlueIds) {
        IdentityHashMap<Object, Boolean> visited =
                new IdentityHashMap<Object, Boolean>();
        rewriteNode(node, canonicalBlueIds, visited);
    }

    private static void inspectNode(
            Node node,
            String documentId,
            List<String> replacements,
            IdentityHashMap<Object, Boolean> visited) {
        if (node == null || visited.put(node, Boolean.TRUE) != null) {
            return;
        }
        String blueId = node.getBlueId();
        if (BlueIds.isCyclicCalculationPlaceholder(blueId)) {
            if (replacements == null) {
                throw new IllegalArgumentException(
                        "Cyclic placeholder escaped into local body "
                                + documentId);
            }
            node.blueId(replacementFor(blueId, replacements));
        }
        inspectNode(node.getType(), documentId, replacements, visited);
        inspectNode(node.getItemType(), documentId, replacements, visited);
        inspectNode(node.getKeyType(), documentId, replacements, visited);
        inspectNode(node.getValueType(), documentId, replacements, visited);
        inspectNode(node.getBlue(), documentId, replacements, visited);
        inspectNode(node.getContracts(), documentId, replacements, visited);
        inspectSchema(node.getSchema(), documentId, replacements, visited);
        if (node.getItems() != null) {
            for (Node item : node.getItems()) {
                inspectNode(item, documentId, replacements, visited);
            }
        }
        if (node.getProperties() != null) {
            for (Node child : node.getProperties().values()) {
                inspectNode(child, documentId, replacements, visited);
            }
        }
    }

    private static void inspectSchema(
            Schema schema,
            String documentId,
            List<String> replacements,
            IdentityHashMap<Object, Boolean> visited) {
        if (schema == null || visited.put(schema, Boolean.TRUE) != null) {
            return;
        }
        String blueId = schema.getBlueId();
        if (BlueIds.isCyclicCalculationPlaceholder(blueId)) {
            if (replacements == null) {
                throw new IllegalArgumentException(
                        "Cyclic placeholder escaped into local body "
                                + documentId);
            }
            schema.blueId(replacementFor(blueId, replacements));
        }
        inspectNode(schema.getRequired(), documentId, replacements, visited);
        inspectNode(schema.getMinLength(), documentId, replacements, visited);
        inspectNode(schema.getMaxLength(), documentId, replacements, visited);
        inspectNode(schema.getMinimum(), documentId, replacements, visited);
        inspectNode(schema.getMaximum(), documentId, replacements, visited);
        inspectNode(schema.getExclusiveMinimum(), documentId,
                replacements, visited);
        inspectNode(schema.getExclusiveMaximum(), documentId,
                replacements, visited);
        inspectNode(schema.getMultipleOf(), documentId, replacements, visited);
        inspectNode(schema.getMinItems(), documentId, replacements, visited);
        inspectNode(schema.getMaxItems(), documentId, replacements, visited);
        inspectNode(schema.getUniqueItems(), documentId,
                replacements, visited);
        inspectNode(schema.getMinFields(), documentId, replacements, visited);
        inspectNode(schema.getMaxFields(), documentId, replacements, visited);
        if (schema.getEnum() != null) {
            for (Node value : schema.getEnum()) {
                inspectNode(value, documentId, replacements, visited);
            }
        }
    }

    private static void rewriteNode(
            Node node,
            List<String> replacements,
            IdentityHashMap<Object, Boolean> visited) {
        inspectNode(node, "cyclic finalization", replacements, visited);
    }

    private static String replacementFor(
            String placeholder,
            List<String> replacements) {
        if (placeholder == null
                || !placeholder.startsWith(BlueIds.THIS_MEMBER_PREFIX)) {
            throw new IllegalArgumentException(
                    "Expected canonical indexed this reference");
        }
        String indexText = placeholder.substring(
                BlueIds.THIS_MEMBER_PREFIX.length());
        int index;
        try {
            index = Integer.parseInt(indexText);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(
                    "Invalid indexed this reference", exception);
        }
        if (!Integer.toString(index).equals(indexText)
                || index < 0 || index >= replacements.size()) {
            throw new IllegalArgumentException(
                    "Indexed this reference is outside the cyclic set");
        }
        return replacements.get(index);
    }

    private static final class DocumentDraft {

        private final Node document;
        private final String blueId;
        private final Integer canonicalMemberIndex;
        private final String preliminaryBlueId;

        private DocumentDraft(
                Node document,
                String blueId,
                Integer canonicalMemberIndex,
                String preliminaryBlueId) {
            this.document = document.clone();
            this.blueId = blueId;
            this.canonicalMemberIndex = canonicalMemberIndex;
            this.preliminaryBlueId = preliminaryBlueId;
        }
    }
}
