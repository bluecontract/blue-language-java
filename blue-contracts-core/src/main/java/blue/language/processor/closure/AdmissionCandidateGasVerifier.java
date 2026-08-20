package blue.language.processor.closure;

import blue.language.identity.BlueIdInputNormalizer;
import blue.language.identity.BlueIds;
import blue.language.identity.CanonicalJsonValueWriter;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.identity.NodeToBlueIdInput;
import blue.language.identity.ScalarIdentityEncoder;
import blue.language.model.Node;
import blue.language.model.NodePathEditor;
import blue.language.model.Schema;
import blue.language.processor.GasChargeContext;
import blue.language.processor.GasScheduleConstants;
import blue.language.processor.ProcessorErrorCategory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static blue.language.model.wire.BlueLanguageConstants.OBJECT_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_DESCRIPTION;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_NAME;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_VALUE;

/** Exact shared-meter verifier for the released negative admission vectors. */
final class AdmissionCandidateGasVerifier {

    private static final String CLOSURE_INVOCATION = "closureInvocation";
    private static final String MANAGED_DOCUMENT_OPENED =
            "managedDocumentOpened";
    private static final String MANAGED_OCCURRENCE_BINDING_VERIFIED =
            "managedOccurrenceBindingVerified";
    private static final String PROCESS_EMBEDDED_EDGE_EXAMINED =
            "processEmbeddedEdgeExamined";
    private static final String COMPONENT_MEMBER_PARTITIONED =
            "componentMemberPartitioned";
    private static final String COMPONENT_EDGE_PARTITIONED =
            "componentEdgePartitioned";
    private static final ClosureIdentityService IDENTITIES =
            ClosureIdentityService.INSTANCE;

    private final DirectBlueIdCalculator directIdentities =
            new DirectBlueIdCalculator();
    private final BlueIdInputNormalizer normalizer =
            new BlueIdInputNormalizer();
    private final ScalarIdentityEncoder scalarEncoder =
            new ScalarIdentityEncoder();

    /**
     * Reports whether the candidate reaches one fully implemented released
     * short-circuit.  Other admission shapes remain on the explicit Phase-B
     * capability-failure path.
     */
    boolean supportsReleasedRejection(ClosureInvocationInput input) {
        AdmissionCandidate candidate = input.admissionCandidate();
        if (candidate == null) {
            return false;
        }
        switch (candidate.kind()) {
            case BAD_CYCLIC_PROOF:
                return isMasterMismatch(
                        input.snapshot(),
                        ((AdmissionCandidate.BadCyclicProof) candidate)
                                .candidateCyclicProof());
            case AMBIGUOUS_PRELIMINARY_MEMBERS:
                return hasIndistinguishablePreliminaryMembers(
                        ((AdmissionCandidate.AmbiguousPreliminaryMembers)
                                candidate).candidateCyclicMembers());
            case INVALID_OCCURRENCE_BINDING:
                return hasExactMissingPathProbe(
                        input,
                        ((AdmissionCandidate.InvalidOccurrenceBinding)
                                candidate).candidateOccurrenceBindings());
            default:
                throw new AssertionError(
                        "Unhandled admission candidate " + candidate.kind());
        }
    }

    ProcessorErrorCategory verifyReleasedRejection(
            ClosureInvocationInput input,
            ManagedDocumentStepProcessor meter) {
        chargeAuthoritativeAdmission(input, meter);
        AdmissionCandidate candidate = input.admissionCandidate();
        switch (candidate.kind()) {
            case BAD_CYCLIC_PROOF:
                verifyBadProofMasterMismatch(
                        input.snapshot(),
                        ((AdmissionCandidate.BadCyclicProof) candidate)
                                .candidateCyclicProof(),
                        meter);
                return ProcessorErrorCategory.CyclicSetProofInvalid;
            case AMBIGUOUS_PRELIMINARY_MEMBERS:
                verifyAmbiguousPreliminaryMembers(
                        ((AdmissionCandidate.AmbiguousPreliminaryMembers)
                                candidate).candidateCyclicMembers(),
                        meter);
                return ProcessorErrorCategory
                        .CyclicPreliminaryMemberAmbiguous;
            case INVALID_OCCURRENCE_BINDING:
                verifyMissingOccurrencePath(
                        input,
                        ((AdmissionCandidate.InvalidOccurrenceBinding)
                                candidate).candidateOccurrenceBindings(),
                        meter);
                return ProcessorErrorCategory.ManagedOccurrenceBindingMissing;
            default:
                throw new AssertionError(
                        "Unhandled admission candidate " + candidate.kind());
        }
    }

    private static void chargeAuthoritativeAdmission(
            ClosureInvocationInput input,
            ManagedDocumentStepProcessor meter) {
        AffectedClosureSnapshot snapshot = input.snapshot();
        meter.charge(
                GasScheduleConstants.Namespace.PROCESSOR,
                GasScheduleConstants.ProcessorCounter.PROCESS_INVOCATION,
                1L,
                GasChargeContext.reason("admission.process"));
        meter.charge(
                GasScheduleConstants.Namespace.PROCESSOR,
                CLOSURE_INVOCATION,
                1L,
                GasChargeContext.reason("admission.closure"));
        if (!input.directDeliveries().isEmpty()) {
            meter.charge(
                    GasScheduleConstants.Namespace.PROCESSOR,
                    GasScheduleConstants.ProcessorCounter
                            .DELIVERY_SNAPSHOT_ENTRY,
                    input.directDeliveries().size(),
                    GasChargeContext.reason(
                            "admission.direct-deliveries"));
        }
        for (ManagedDocumentSnapshot document : snapshot.managedDocuments()) {
            meter.charge(
                    GasScheduleConstants.Namespace.PROCESSOR,
                    MANAGED_DOCUMENT_OPENED,
                    1L,
                    context(document.documentId(),
                            "admission.document."
                                    + document.documentId().value()));
        }
        for (ManagedOccurrenceBinding binding : snapshot.occurrences()) {
            meter.charge(
                    GasScheduleConstants.Namespace.PROCESSOR,
                    MANAGED_OCCURRENCE_BINDING_VERIFIED,
                    1L,
                    context(binding.sourceDocumentId(),
                            "admission.binding."
                                    + binding.occurrenceIdentity()));
            if (binding.active()) {
                meter.charge(
                        GasScheduleConstants.Namespace.PROCESSOR,
                        PROCESS_EMBEDDED_EDGE_EXAMINED,
                        1L,
                        context(binding.sourceDocumentId(),
                                "admission.edge."
                                        + binding.occurrenceIdentity()));
            }
        }
        for (ComponentSnapshot component : snapshot.components()) {
            Set<DocumentId> members = new HashSet<DocumentId>(
                    component.orderedMemberDocumentIds());
            for (DocumentId member : component.orderedMemberDocumentIds()) {
                meter.charge(
                        GasScheduleConstants.Namespace.PROCESSOR,
                        COMPONENT_MEMBER_PARTITIONED,
                        1L,
                        context(member, "admission.component-member"));
            }
            for (ManagedOccurrenceBinding binding : snapshot.occurrences()) {
                if (binding.active()
                        && members.contains(binding.sourceDocumentId())
                        && members.contains(binding.targetDocumentId())) {
                    meter.charge(
                            GasScheduleConstants.Namespace.PROCESSOR,
                            COMPONENT_EDGE_PARTITIONED,
                            1L,
                            context(binding.sourceDocumentId(),
                                    "admission.component-edge"));
                }
            }
        }
    }

    private static boolean isMasterMismatch(
            AffectedClosureSnapshot snapshot,
            AdmissionCandidate.CandidateCyclicProof proof) {
        ComponentSnapshot component = component(
                snapshot, proof.componentIdentity());
        return component != null
                && component.kind() == ComponentKind.CYCLIC
                && component.componentIdentity().equals(
                        proof.componentIdentity())
                && !component.masterBlueId().equals(proof.masterBlueId());
    }

    private static void verifyBadProofMasterMismatch(
            AffectedClosureSnapshot snapshot,
            AdmissionCandidate.CandidateCyclicProof proof,
            ManagedDocumentStepProcessor meter) {
        ComponentSnapshot component = component(
                snapshot, proof.componentIdentity());
        if (component == null || component.kind() != ComponentKind.CYCLIC) {
            throw new IllegalStateException(
                    "Released bad-proof lane lost its cyclic component");
        }
        meter.charge(
                GasScheduleConstants.Namespace.SEMANTIC,
                GasScheduleConstants.SemanticCounter
                        .VALIDATION_MEMBER_EXAMINED,
                1L,
                GasChargeContext.reason("candidate.bad-proof.record"));
        if (compareToken(
                    proof.componentIdentity(),
                    component.componentIdentity(),
                    "candidate.bad-proof.component",
                    meter) != 0) {
            throw new IllegalStateException(
                    "Released bad-proof lane expected an equal component identity");
        }
        if (compareToken(
                    proof.masterBlueId(),
                    component.masterBlueId(),
                    "candidate.bad-proof.master",
                    meter) == 0) {
            throw new IllegalStateException(
                    "Released bad-proof lane expected a master mismatch");
        }
    }

    private boolean hasIndistinguishablePreliminaryMembers(
            List<AdmissionCandidate.CandidateCyclicMember> members) {
        List<PreliminaryMember> preliminary = preliminaries(members);
        for (int left = 0; left < preliminary.size(); left++) {
            for (int right = left + 1; right < preliminary.size(); right++) {
                if (preliminary.get(left).blueId.equals(
                            preliminary.get(right).blueId)
                        && preliminary.get(left).canonicalInput.equals(
                            preliminary.get(right).canonicalInput)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void verifyAmbiguousPreliminaryMembers(
            List<AdmissionCandidate.CandidateCyclicMember> members,
            ManagedDocumentStepProcessor meter) {
        for (AdmissionCandidate.CandidateCyclicMember member : members) {
            meter.charge(
                    GasScheduleConstants.Namespace.SEMANTIC,
                    GasScheduleConstants.SemanticCounter
                            .VALIDATION_MEMBER_EXAMINED,
                    1L,
                    context(member.documentId(),
                            "candidate.ambiguous.member."
                                    + member.documentId().value()));
        }

        Set<String> existing = candidateDescendantIdentities(members);
        Set<String> established = new LinkedHashSet<String>();
        List<PreliminaryMember> preliminary = preliminaries(members);
        for (PreliminaryMember member : preliminary) {
            establishExactValue(
                    member.projected,
                    member.documentId,
                    meter,
                    existing,
                    established,
                    "candidate.ambiguous.preliminary");
        }
        if (!stableSortRejectsDuplicate(preliminary, meter)) {
            throw new IllegalStateException(
                    "Released ambiguity lane found no indistinguishable members");
        }
    }

    private boolean stableSortRejectsDuplicate(
            List<PreliminaryMember> input,
            ManagedDocumentStepProcessor meter) {
        if (input.size() < 2) {
            return false;
        }
        ArrayList<PreliminaryMember> source =
                new ArrayList<PreliminaryMember>(input);
        ArrayList<PreliminaryMember> target =
                new ArrayList<PreliminaryMember>(
                        Collections.nCopies(
                                input.size(), (PreliminaryMember) null));
        for (int width = 1;
                width < source.size();
                width = width > source.size() / 2
                        ? source.size() : width * 2) {
            for (int start = 0;
                    start < source.size();
                    start += width * 2) {
                int middle = Math.min(start + width, source.size());
                int end = Math.min(start + width * 2, source.size());
                int left = start;
                int right = middle;
                int output = start;
                while (left < middle && right < end) {
                    PreliminaryMember leftMember = source.get(left);
                    PreliminaryMember rightMember = source.get(right);
                    meter.charge(
                            GasScheduleConstants.Namespace.SEMANTIC,
                            GasScheduleConstants.SemanticCounter
                                    .SORT_COMPARISON,
                            1L,
                            GasChargeContext.reason(
                                    "candidate.ambiguous.sort"));
                    int compared = compareToken(
                            leftMember.blueId,
                            rightMember.blueId,
                            "candidate.ambiguous.preliminary-identity",
                            meter);
                    if (compared == 0) {
                        compared = compareToken(
                                leftMember.canonicalInput,
                                rightMember.canonicalInput,
                                "candidate.ambiguous.canonical-input",
                                meter);
                        if (compared == 0) {
                            return true;
                        }
                    }
                    if (compared <= 0) {
                        target.set(output++, source.get(left++));
                    } else {
                        target.set(output++, source.get(right++));
                    }
                }
                while (left < middle) {
                    target.set(output++, source.get(left++));
                }
                while (right < end) {
                    target.set(output++, source.get(right++));
                }
            }
            ArrayList<PreliminaryMember> swap = source;
            source = target;
            target = swap;
        }
        return false;
    }

    private List<PreliminaryMember> preliminaries(
            List<AdmissionCandidate.CandidateCyclicMember> members) {
        ArrayList<PreliminaryMember> result =
                new ArrayList<PreliminaryMember>();
        for (AdmissionCandidate.CandidateCyclicMember member : members) {
            Node zeroed = zeroed(member.document());
            Object projected = normalizer.normalizeCanonicalInput(
                    NodeToBlueIdInput
                            .getAllowingCyclicPlaceholders(zeroed));
            result.add(new PreliminaryMember(
                    member.documentId(),
                    projected,
                    directIdentities.directBlueIdFromCanonicalInput(
                            projected),
                    new String(
                            CanonicalJsonValueWriter.write(projected),
                            StandardCharsets.UTF_8)));
        }
        return result;
    }

    private Set<String> candidateDescendantIdentities(
            List<AdmissionCandidate.CandidateCyclicMember> members) {
        LinkedHashSet<String> result = new LinkedHashSet<String>();
        for (AdmissionCandidate.CandidateCyclicMember member : members) {
            Object projected = normalizer.normalizeCanonicalInput(
                    NodeToBlueIdInput.getAllowingCyclicPlaceholders(
                            member.document()));
            collectExisting(projected, false, result);
        }
        return result;
    }

    private void collectExisting(
            Object value,
            boolean includeSelf,
            Set<String> collected) {
        IdentityFacts direct = facts(value);
        if (direct.pureReference) {
            collected.add(direct.blueId);
            return;
        }
        if (includeSelf) {
            collected.add(direct.blueId);
        }
        for (Object child : direct.children) {
            collectExisting(child, true, collected);
        }
    }

    private String establishExactValue(
            Object value,
            DocumentId documentId,
            ManagedDocumentStepProcessor meter,
            Set<String> existing,
            Set<String> established,
            String reasonPrefix) {
        IdentityFacts direct = facts(value);
        if (direct.pureReference
                || existing.contains(direct.blueId)
                || established.contains(direct.blueId)) {
            return direct.blueId;
        }
        for (int index = 0; index < direct.children.size(); index++) {
            establishExactValue(
                    direct.children.get(index),
                    documentId,
                    meter,
                    existing,
                    established,
                    reasonPrefix + ".child." + index);
        }
        meter.semanticGas(context(
                documentId, reasonPrefix + ".node"))
                .nodeIdentitiesEstablished(1L);
        if (direct.list) {
            meter.semanticGas(context(
                    documentId, reasonPrefix + ".list"))
                    .fullListIdentity(direct.listLength);
        } else {
            meter.semanticGas(context(
                    documentId, reasonPrefix + ".members"))
                    .objectMembersRebuilt(direct.directMemberCount);
            meter.semanticGas(context(
                    documentId, reasonPrefix + ".hash"))
                    .directIdentityInput(direct.canonicalInputBytes);
        }
        established.add(direct.blueId);
        return direct.blueId;
    }

    private IdentityFacts facts(Object supplied) {
        Object value = normalizer.normalizeCanonicalInput(supplied);
        if (value instanceof String
                || value instanceof Number
                || value instanceof Boolean) {
            return facts(scalarEncoder.encode(value));
        }
        String blueId = directIdentities.directBlueIdFromCanonicalInput(value);
        if (isPureReference(value)) {
            return IdentityFacts.reference(blueId);
        }
        if (value instanceof List) {
            List<?> values = (List<?>) value;
            return IdentityFacts.list(
                    blueId,
                    new ArrayList<Object>(values),
                    values.size());
        }
        if (!(value instanceof Map)) {
            throw new IllegalArgumentException(
                    "Unsupported canonical identity value");
        }
        Map<String, Object> map = castMap(value);
        TreeMap<String, Object> ordered = new TreeMap<String, Object>(map);
        ArrayList<Object> children = new ArrayList<Object>();
        TreeMap<String, Object> contributions =
                new TreeMap<String, Object>();
        for (Map.Entry<String, Object> entry : ordered.entrySet()) {
            if (isLiteralField(entry.getKey())) {
                contributions.put(entry.getKey(), entry.getValue());
            } else {
                Object child = entry.getValue();
                children.add(child);
                contributions.put(
                        entry.getKey(),
                        Collections.<String, Object>singletonMap(
                                OBJECT_BLUE_ID,
                                directIdentities
                                        .directBlueIdFromCanonicalInput(
                                                normalizer
                                                        .normalizeCanonicalInput(
                                                                child))));
            }
        }
        return IdentityFacts.object(
                blueId,
                children,
                contributions.size(),
                CanonicalJsonValueWriter.write(contributions).length);
    }

    private static boolean hasExactMissingPathProbe(
            ClosureInvocationInput input,
            List<AdmissionCandidate.CandidateOccurrenceBinding> bindings) {
        if (bindings.size() != 1) {
            return false;
        }
        AdmissionCandidate.CandidateOccurrenceBinding binding =
                bindings.get(0);
        ScopeAddress address = ScopeAddress.embedded(
                binding.sourcePath(), binding.activationGeneration());
        if (!binding.bindingPolicyIdentity().equals(
                    input.environment().managedBindingPolicyIdentity())
                || !binding.occurrenceIdentity().equals(
                    IDENTITIES.managedOccurrenceIdentity(
                            binding.sourceDocumentId(),
                            address,
                            binding.targetDocumentId(),
                            binding.bindingPolicyIdentity()))
                || !binding.bindingIdentity().equals(
                    IDENTITIES.managedOccurrenceBindingIdentity(
                            binding.sourceDocumentId(),
                            address,
                            binding.targetDocumentId(),
                            binding.expectedTargetBlueId(),
                            binding.bindingPolicyIdentity()))) {
            return false;
        }
        ManagedDocumentSnapshot source = input.snapshot().managedDocument(
                binding.sourceDocumentId());
        ManagedDocumentSnapshot target = input.snapshot().managedDocument(
                binding.targetDocumentId());
        return source != null
                && target != null
                && binding.active()
                && target.blueId().equals(binding.expectedTargetBlueId())
                && firstMissingPrefix(
                        source.document(), binding.sourcePath()) != null;
    }

    private static void verifyMissingOccurrencePath(
            ClosureInvocationInput input,
            List<AdmissionCandidate.CandidateOccurrenceBinding> bindings,
            ManagedDocumentStepProcessor meter) {
        AdmissionCandidate.CandidateOccurrenceBinding binding =
                bindings.get(0);
        meter.charge(
                GasScheduleConstants.Namespace.PROCESSOR,
                MANAGED_OCCURRENCE_BINDING_VERIFIED,
                1L,
                context(binding.sourceDocumentId(),
                        "candidate.invalid-binding.row"));
        ManagedDocumentSnapshot source = input.snapshot().managedDocument(
                binding.sourceDocumentId());
        String missing = firstMissingPrefix(
                source.document(), binding.sourcePath());
        int attemptedSegments = segmentCount(missing);
        for (int segment = 0; segment < attemptedSegments; segment++) {
            meter.charge(
                    GasScheduleConstants.Namespace.PROCESSOR,
                    GasScheduleConstants.ProcessorCounter
                            .POINTER_SEGMENT_TRAVERSED,
                    1L,
                    context(binding.sourceDocumentId(),
                            "candidate.invalid-binding.path.missing"));
        }
        if (missing == null) {
            throw new IllegalStateException(
                    "Released invalid-binding lane expected a missing path");
        }
    }

    private static String firstMissingPrefix(Node source, String path) {
        String[] segments = path.substring(1).split("/", -1);
        StringBuilder prefix = new StringBuilder();
        for (String segment : segments) {
            prefix.append('/').append(segment);
            if (NodePathEditor.getOrNull(
                        source, prefix.toString()) == null) {
                return prefix.toString();
            }
        }
        return null;
    }

    private static int segmentCount(String pointer) {
        return pointer == null ? 0 : pointer.substring(1).split("/", -1).length;
    }

    private static int compareToken(
            String left,
            String right,
            String reasonPrefix,
            ManagedDocumentStepProcessor meter) {
        TextComparison comparison = compareCodePoints(left, right);
        meter.semanticGas(GasChargeContext.reason(
                reasonPrefix + ".scalar")).scalarComparisons(1L);
        meter.semanticGas(GasChargeContext.reason(
                reasonPrefix + ".text")).textOperandsExamined(
                        comparison.codePointsRead, 2L);
        return comparison.result;
    }

    private static TextComparison compareCodePoints(
            String left, String right) {
        int leftOffset = 0;
        int rightOffset = 0;
        long read = 0L;
        while (leftOffset < left.length() && rightOffset < right.length()) {
            int leftCodePoint = left.codePointAt(leftOffset);
            int rightCodePoint = right.codePointAt(rightOffset);
            read++;
            if (leftCodePoint != rightCodePoint) {
                return new TextComparison(
                        Integer.compare(leftCodePoint, rightCodePoint),
                        read);
            }
            leftOffset += Character.charCount(leftCodePoint);
            rightOffset += Character.charCount(rightCodePoint);
        }
        return new TextComparison(
                Boolean.compare(
                        leftOffset < left.length(),
                        rightOffset < right.length()),
                Math.max(1L, read));
    }

    private static ComponentSnapshot component(
            AffectedClosureSnapshot snapshot, String componentIdentity) {
        for (ComponentSnapshot component : snapshot.components()) {
            if (component.componentIdentity().equals(componentIdentity)) {
                return component;
            }
        }
        return null;
    }

    private static GasChargeContext context(
            DocumentId documentId, String reason) {
        return GasChargeContext.closure(
                documentId == null ? null : documentId.value(),
                null,
                null,
                null,
                null,
                null,
                null,
                reason);
    }

    private static boolean isLiteralField(String key) {
        return OBJECT_NAME.equals(key)
                || OBJECT_DESCRIPTION.equals(key)
                || OBJECT_VALUE.equals(key);
    }

    private static boolean isPureReference(Object value) {
        if (!(value instanceof Map)) {
            return false;
        }
        Map<?, ?> map = (Map<?, ?>) value;
        return map.size() == 1 && map.get(OBJECT_BLUE_ID) instanceof String;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static Node zeroed(Node source) {
        Node result = source.clone();
        replaceCyclicPlaceholders(
                result,
                new IdentityHashMap<Object, Boolean>(),
                BlueIds.CYCLIC_CALCULATION_ZERO_PLACEHOLDER);
        return result;
    }

    private static void replaceCyclicPlaceholders(
            Node node,
            IdentityHashMap<Object, Boolean> visited,
            String replacement) {
        if (node == null || visited.put(node, Boolean.TRUE) != null) {
            return;
        }
        if (BlueIds.isCyclicCalculationPlaceholder(node.getBlueId())) {
            node.blueId(replacement);
        }
        replaceCyclicPlaceholders(node.getType(), visited, replacement);
        replaceCyclicPlaceholders(node.getItemType(), visited, replacement);
        replaceCyclicPlaceholders(node.getKeyType(), visited, replacement);
        replaceCyclicPlaceholders(node.getValueType(), visited, replacement);
        replaceCyclicPlaceholders(node.getBlue(), visited, replacement);
        replaceCyclicPlaceholders(node.getContracts(), visited, replacement);
        replaceCyclicPlaceholders(node.getSchema(), visited, replacement);
        if (node.getItems() != null) {
            for (Node child : node.getItems()) {
                replaceCyclicPlaceholders(child, visited, replacement);
            }
        }
        if (node.getProperties() != null) {
            for (Node child : node.getProperties().values()) {
                replaceCyclicPlaceholders(child, visited, replacement);
            }
        }
    }

    private static void replaceCyclicPlaceholders(
            Schema schema,
            IdentityHashMap<Object, Boolean> visited,
            String replacement) {
        if (schema == null || visited.put(schema, Boolean.TRUE) != null) {
            return;
        }
        if (BlueIds.isCyclicCalculationPlaceholder(schema.getBlueId())) {
            schema.blueId(replacement);
        }
        for (Node child : schemaChildren(schema)) {
            replaceCyclicPlaceholders(child, visited, replacement);
        }
    }

    private static List<Node> schemaChildren(Schema schema) {
        ArrayList<Node> children = new ArrayList<Node>();
        children.add(schema.getRequired());
        children.add(schema.getMinLength());
        children.add(schema.getMaxLength());
        children.add(schema.getMinimum());
        children.add(schema.getMaximum());
        children.add(schema.getExclusiveMinimum());
        children.add(schema.getExclusiveMaximum());
        children.add(schema.getMultipleOf());
        children.add(schema.getMinItems());
        children.add(schema.getMaxItems());
        children.add(schema.getUniqueItems());
        children.add(schema.getMinFields());
        children.add(schema.getMaxFields());
        if (schema.getEnum() != null) {
            children.addAll(schema.getEnum());
        }
        return children;
    }

    private static final class PreliminaryMember {
        private final DocumentId documentId;
        private final Object projected;
        private final String blueId;
        private final String canonicalInput;

        private PreliminaryMember(
                DocumentId documentId,
                Object projected,
                String blueId,
                String canonicalInput) {
            this.documentId = documentId;
            this.projected = projected;
            this.blueId = blueId;
            this.canonicalInput = canonicalInput;
        }
    }

    private static final class TextComparison {
        private final int result;
        private final long codePointsRead;

        private TextComparison(int result, long codePointsRead) {
            this.result = result;
            this.codePointsRead = codePointsRead;
        }
    }

    private static final class IdentityFacts {
        private final String blueId;
        private final boolean pureReference;
        private final boolean list;
        private final List<Object> children;
        private final long directMemberCount;
        private final long canonicalInputBytes;
        private final long listLength;

        private IdentityFacts(
                String blueId,
                boolean pureReference,
                boolean list,
                List<Object> children,
                long directMemberCount,
                long canonicalInputBytes,
                long listLength) {
            this.blueId = blueId;
            this.pureReference = pureReference;
            this.list = list;
            this.children = Collections.unmodifiableList(
                    new ArrayList<Object>(children));
            this.directMemberCount = directMemberCount;
            this.canonicalInputBytes = canonicalInputBytes;
            this.listLength = listLength;
        }

        private static IdentityFacts reference(String blueId) {
            return new IdentityFacts(
                    blueId, true, false,
                    Collections.emptyList(), 0L, 0L, 0L);
        }

        private static IdentityFacts list(
                String blueId, List<Object> children, long length) {
            return new IdentityFacts(
                    blueId, false, true,
                    children, 0L, 0L, length);
        }

        private static IdentityFacts object(
                String blueId,
                List<Object> children,
                long memberCount,
                long canonicalBytes) {
            return new IdentityFacts(
                    blueId, false, false,
                    children, memberCount, canonicalBytes, 0L);
        }
    }
}
