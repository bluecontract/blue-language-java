package blue.language.conformance.contracts.closure;

import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.model.Node;
import blue.language.model.wire.BlueLanguageConstants;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.closure.AffectedClosureSnapshot;
import blue.language.processor.closure.AdmissionCause;
import blue.language.processor.closure.AdmissionCandidate;
import blue.language.processor.closure.AdmissionKind;
import blue.language.processor.closure.ClosureEnvironment;
import blue.language.processor.closure.ClosureInvocationInput;
import blue.language.processor.closure.ComponentKind;
import blue.language.processor.closure.ComponentSnapshot;
import blue.language.processor.closure.DirectLogicalDelivery;
import blue.language.processor.closure.DocumentId;
import blue.language.processor.closure.ExecutionPolicy;
import blue.language.processor.closure.ExternalEventCause;
import blue.language.processor.closure.ManagedDocumentGraph;
import blue.language.processor.closure.ManagedDocumentSnapshot;
import blue.language.processor.closure.ManagedOccurrenceBinding;
import blue.language.processor.closure.ManagedRevisionCause;
import blue.language.processor.closure.ManagedScopeKey;
import blue.language.processor.closure.ProcessingCause;
import blue.language.processor.closure.SccPartitioner;
import blue.language.processor.closure.ScopeAddress;
import blue.language.provider.CyclicSetProof;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ClosureFixtureParser {

    ParsedFixture parse(ClosureFixtureInventory.Entry entry) {
        return parse(entry, ClosureFixtureInventory.readFixture(entry));
    }

    ParsedFixture parse(
            ClosureFixtureInventory.Entry entry,
            JsonNode fixture) {
        if (!entry.id().equals(
                    ClosureFixtureInventory.requiredText(fixture, "id"))
                || !entry.operation().equals(
                        ClosureFixtureInventory.requiredText(
                                fixture, "operation"))) {
            throw new IllegalArgumentException(
                    "Fixture envelope disagrees with inventory");
        }
        if (!"process-closure".equals(entry.operation())
                && !"admit-closure".equals(entry.operation())) {
            throw new UnsupportedOperationException(
                    "Closure harness supports process-closure and admit-closure only");
        }
        JsonNode input = ClosureFixtureInventory.requiredObject(fixture, "input");
        List<ManagedDocumentSnapshot> documents = parseDocuments(
                ClosureFixtureInventory.requiredObject(input, "documents"));
        List<ManagedOccurrenceBinding> occurrences = parseOccurrences(
                ClosureFixtureInventory.requiredArray(input, "occurrences"));
        List<ComponentSnapshot> components = parseComponents(
                ClosureFixtureInventory.requiredArray(input, "components"));
        List<DocumentId> publicRoots = parseDocumentIds(
                ClosureFixtureInventory.requiredArray(
                        input, "publicRootDocumentIds"));
        ManagedDocumentGraph graph = ManagedDocumentGraph.fromBindings(
                documentIds(documents), occurrences);
        List<List<DocumentId>> partition = new SccPartitioner().partition(graph);
        List<List<DocumentId>> declaredPartition = componentMembers(components);
        if (!partition.equals(declaredPartition)) {
            throw new IllegalArgumentException(
                    "Fixture component order/partition disagrees with active graph");
        }
        return new ParsedFixture(
                entry,
                ClosureFixtureInventory.requiredLong(input, "graphGeneration"),
                ClosureFixtureInventory.requiredText(input, "closureIdentity"),
                ClosureFixtureInventory.requiredText(
                        input, "occurrenceBindingSetIdentity"),
                documents,
                occurrences,
                components,
                publicRoots,
                parseCause(
                        ClosureFixtureInventory.requiredObject(input, "cause")),
                parseDirectDeliveries(
                        ClosureFixtureInventory.requiredArray(
                                input, "directDeliveries")),
                ClosureFixtureInventory.requiredText(
                        input, "directDeliverySnapshotIdentity"),
                parseExecutionPolicy(
                        ClosureFixtureInventory.requiredObject(
                                input, "gasPolicy")),
                parseEnvironment(
                        ClosureFixtureInventory.requiredObject(
                                input, "environment")),
                ClosureFixtureInventory.requiredText(
                        input, "invocationIdentity"),
                parseAdmissionCandidate(input),
                nullableText(input, "admissionCandidateIdentity"),
                graph);
    }

    private static AdmissionCandidate parseAdmissionCandidate(
            JsonNode input) {
        JsonNode value = input.get("admissionCandidate");
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException(
                    "admissionCandidate must be null or an object");
        }
        String kind = ClosureFixtureInventory.requiredText(value, "kind");
        JsonNode evidence = ClosureFixtureInventory.requiredObject(
                value, "evidence");
        if ("BAD_CYCLIC_PROOF".equals(kind)) {
            JsonNode proof = ClosureFixtureInventory.requiredObject(
                    evidence, "candidateCyclicProof");
            ArrayList<AdmissionCandidate.CandidateMemberState> states =
                    new ArrayList<AdmissionCandidate.CandidateMemberState>();
            for (JsonNode state : ClosureFixtureInventory.requiredArray(
                    proof, "memberStates")) {
                states.add(new AdmissionCandidate.CandidateMemberState(
                        new DocumentId(ClosureFixtureInventory.requiredText(
                                state, "documentId")),
                        ClosureFixtureInventory.requiredText(
                                state,
                                BlueLanguageConstants.OBJECT_BLUE_ID)));
            }
            ArrayList<Node> placeholders = new ArrayList<Node>();
            for (JsonNode placeholder : ClosureFixtureInventory.requiredArray(
                    proof, "declaredPlaceholderSet")) {
                placeholders.add(node(placeholder));
            }
            return AdmissionCandidate.badCyclicProof(
                    new AdmissionCandidate.CandidateCyclicProof(
                            ClosureFixtureInventory.requiredText(
                                    proof, "componentIdentity"),
                            ClosureFixtureInventory.requiredText(
                                    proof, "masterBlueId"),
                            states,
                            placeholders));
        }
        if ("AMBIGUOUS_PRELIMINARY_MEMBERS".equals(kind)) {
            ArrayList<AdmissionCandidate.CandidateCyclicMember> members =
                    new ArrayList<AdmissionCandidate.CandidateCyclicMember>();
            for (JsonNode member : ClosureFixtureInventory.requiredArray(
                    evidence, "candidateCyclicMembers")) {
                members.add(new AdmissionCandidate.CandidateCyclicMember(
                        new DocumentId(ClosureFixtureInventory.requiredText(
                                member, "documentId")),
                        node(ClosureFixtureInventory.requiredObject(
                                member, "document"))));
            }
            return AdmissionCandidate.ambiguousPreliminaryMembers(members);
        }
        if ("INVALID_OCCURRENCE_BINDING".equals(kind)) {
            ArrayList<AdmissionCandidate.CandidateOccurrenceBinding> rows =
                    new ArrayList<AdmissionCandidate.CandidateOccurrenceBinding>();
            for (JsonNode row : ClosureFixtureInventory.requiredArray(
                    evidence, "candidateOccurrenceBindings")) {
                rows.add(new AdmissionCandidate.CandidateOccurrenceBinding(
                        ClosureFixtureInventory.requiredText(
                                row, "occurrenceIdentity"),
                        ClosureFixtureInventory.requiredText(
                                row, "bindingIdentity"),
                        ClosureFixtureInventory.requiredText(
                                row, "bindingPolicyIdentity"),
                        new DocumentId(ClosureFixtureInventory.requiredText(
                                row, "sourceDocumentId")),
                        ClosureFixtureInventory.requiredText(
                                row, "sourcePath"),
                        ClosureFixtureInventory.requiredLong(
                                row, "activationGeneration"),
                        new DocumentId(ClosureFixtureInventory.requiredText(
                                row, "targetDocumentId")),
                        ClosureFixtureInventory.requiredText(
                                row, "expectedTargetBlueId"),
                        ClosureFixtureInventory.requiredBoolean(
                                row, "active")));
            }
            return AdmissionCandidate.invalidOccurrenceBinding(rows);
        }
        throw new UnsupportedOperationException(
                "Unsupported admission candidate kind " + kind);
    }

    private static List<ManagedDocumentSnapshot> parseDocuments(
            JsonNode values) {
        ArrayList<ManagedDocumentSnapshot> result =
                new ArrayList<ManagedDocumentSnapshot>();
        Iterator<Map.Entry<String, JsonNode>> fields = values.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode record = field.getValue();
            String declaredId = ClosureFixtureInventory.requiredText(
                    record, "documentId");
            if (!field.getKey().equals(declaredId)) {
                throw new IllegalArgumentException(
                        "Document map key and documentId disagree");
            }
            result.add(new ManagedDocumentSnapshot(
                    new DocumentId(declaredId),
                    ClosureFixtureInventory.requiredText(
                            record, BlueLanguageConstants.OBJECT_BLUE_ID),
                    node(ClosureFixtureInventory.requiredObject(
                            record, "document")),
                    ClosureFixtureInventory.requiredBoolean(
                            record, "initialized"),
                    ClosureFixtureInventory.requiredBoolean(
                            record, "terminated"),
                    ClosureFixtureInventory.requiredBoolean(
                            record, "publicRoot"),
                    ClosureFixtureInventory.requiredLong(record, "epoch"),
                    ClosureFixtureInventory.requiredLong(
                            record, "componentGeneration")));
        }
        Collections.sort(result);
        return Collections.unmodifiableList(result);
    }

    private static List<ManagedOccurrenceBinding> parseOccurrences(
            JsonNode values) {
        ArrayList<ManagedOccurrenceBinding> result =
                new ArrayList<ManagedOccurrenceBinding>();
        for (JsonNode value : values) {
            JsonNode pending = value.get("pendingHistoricalEpoch");
            Long pendingEpoch = pending == null || pending.isNull()
                    ? null
                    : Long.valueOf(ClosureFixtureInventory.requiredLong(
                            value, "pendingHistoricalEpoch"));
            result.add(new ManagedOccurrenceBinding(
                    ClosureFixtureInventory.requiredText(
                            value, "occurrenceIdentity"),
                    ClosureFixtureInventory.requiredText(
                            value, "bindingIdentity"),
                    ClosureFixtureInventory.requiredText(
                            value, "bindingPolicyIdentity"),
                    new DocumentId(ClosureFixtureInventory.requiredText(
                            value, "sourceDocumentId")),
                    ScopeAddress.embedded(
                            ClosureFixtureInventory.requiredText(
                                    value, "sourcePath"),
                            ClosureFixtureInventory.requiredLong(
                                    value, "activationGeneration")),
                    new DocumentId(ClosureFixtureInventory.requiredText(
                            value, "targetDocumentId")),
                    ClosureFixtureInventory.requiredText(
                            value, "expectedTargetBlueId"),
                    ClosureFixtureInventory.requiredBoolean(value, "active"),
                    pendingEpoch));
        }
        Collections.sort(result);
        return Collections.unmodifiableList(result);
    }

    private static List<ComponentSnapshot> parseComponents(JsonNode values) {
        ArrayList<ComponentSnapshot> result =
                new ArrayList<ComponentSnapshot>();
        for (JsonNode value : values) {
            ComponentKind kind = ComponentKind.valueOf(
                    ClosureFixtureInventory.requiredText(value, "kind"));
            List<DocumentId> memberDocumentIds = parseDocumentIds(
                    ClosureFixtureInventory.requiredArray(
                            value, "orderedMemberDocumentIds"));
            List<String> memberBlueIds = parseTextList(
                    ClosureFixtureInventory.requiredArray(
                            value, "orderedMemberBlueIds"));
            ParsedCyclicEvidence cyclicEvidence = kind == ComponentKind.CYCLIC
                    ? parseCyclicEvidence(
                            value, memberDocumentIds, memberBlueIds)
                    : ParsedCyclicEvidence.absent();
            result.add(new ComponentSnapshot(
                    ClosureFixtureInventory.requiredText(
                            value, "componentIdentity"),
                    ClosureFixtureInventory.requiredText(
                            value, "componentStateIdentity"),
                    ClosureFixtureInventory.requiredLong(
                            value, "componentGeneration"),
                    kind,
                    memberDocumentIds,
                    memberBlueIds,
                    cyclicEvidence.masterBlueId,
                    cyclicEvidence.proof,
                    cyclicEvidence.proofIdentity));
        }
        return Collections.unmodifiableList(result);
    }

    /** Retains the authored canonical proof representation without reprojecting it. */
    private static ParsedCyclicEvidence parseCyclicEvidence(
            JsonNode component,
            List<DocumentId> memberDocumentIds,
            List<String> memberBlueIds) {
        String componentIdentity = ClosureFixtureInventory.requiredText(
                component, "componentIdentity");
        String masterBlueId = ClosureFixtureInventory.requiredText(
                component, "masterBlueId");
        JsonNode evidence = ClosureFixtureInventory.requiredObject(
                component, "completeCyclicProof");
        requireEqual(
                "completeCyclicProof.componentIdentity",
                componentIdentity,
                ClosureFixtureInventory.requiredText(
                        evidence, "componentIdentity"));
        requireEqual(
                "completeCyclicProof.masterBlueId",
                masterBlueId,
                ClosureFixtureInventory.requiredText(
                        evidence, "masterBlueId"));

        JsonNode states = ClosureFixtureInventory.requiredArray(
                evidence, "memberStates");
        if (states.size() != memberDocumentIds.size()
                || memberBlueIds.size() != memberDocumentIds.size()) {
            throw new IllegalArgumentException(
                    "Cyclic proof member evidence has different cardinalities");
        }
        for (int index = 0; index < states.size(); index++) {
            JsonNode state = states.get(index);
            requireEqual(
                    "completeCyclicProof.memberStates[" + index
                            + "].documentId",
                    memberDocumentIds.get(index).value(),
                    ClosureFixtureInventory.requiredText(
                            state, "documentId"));
            requireEqual(
                    "completeCyclicProof.memberStates[" + index
                            + "].blueId",
                    memberBlueIds.get(index),
                    ClosureFixtureInventory.requiredText(
                            state, BlueLanguageConstants.OBJECT_BLUE_ID));
        }

        ArrayList<Node> placeholders = new ArrayList<Node>();
        for (JsonNode placeholder : ClosureFixtureInventory.requiredArray(
                evidence, "declaredPlaceholderSet")) {
            placeholders.add(node(placeholder));
        }
        if (placeholders.size() != memberDocumentIds.size()) {
            throw new IllegalArgumentException(
                    "Cyclic placeholder set must cover every component member");
        }
        return new ParsedCyclicEvidence(
                masterBlueId,
                CyclicSetProof.fromDeclaredPlaceholderSet(placeholders),
                ClosureFixtureInventory.requiredText(
                        component, "cyclicProofIdentity"));
    }

    private static void requireEqual(
            String field,
            String expected,
            String actual) {
        if (!expected.equals(actual)) {
            throw new IllegalArgumentException(
                    field + " disagrees with component evidence");
        }
    }

    private static ProcessingCause parseCause(JsonNode cause) {
        String kind = ClosureFixtureInventory.requiredText(cause, "kind");
        if ("external".equals(kind)) {
            return parseExternalCause(cause);
        }
        if ("managed-revision".equals(kind)) {
            return parseManagedRevisionCause(cause);
        }
        if ("admission".equals(kind)) {
            return new AdmissionCause(
                    ClosureFixtureInventory.requiredText(
                            cause, "causeIdentity"),
                    AdmissionKind.valueOf(
                            ClosureFixtureInventory.requiredText(
                                    cause, "admissionKind")),
                    ClosureFixtureInventory.requiredText(cause, "label"),
                    nullableText(cause, "triggeringEventBlueId"),
                    nullableText(cause, "parentTransitionIdentity"),
                    ClosureFixtureInventory.requiredText(
                            cause, "policyIdentity"));
        }
        throw new UnsupportedOperationException(
                "Closure harness does not support cause kind " + kind);
    }

    private static ExternalEventCause parseExternalCause(JsonNode cause) {
        ArrayList<Object> sourceOrder = new ArrayList<Object>();
        for (JsonNode component : ClosureFixtureInventory.requiredArray(
                cause, "sourceOrder")) {
            if (component.isIntegralNumber()) {
                sourceOrder.add(component.bigIntegerValue());
            } else if (component.isTextual()) {
                sourceOrder.add(component.asText());
            } else {
                throw new IllegalArgumentException(
                        "External sourceOrder supports Integer/Text only");
            }
        }
        return new ExternalEventCause(
                ClosureFixtureInventory.requiredText(cause, "causeIdentity"),
                node(cause.get("event")),
                ClosureFixtureInventory.requiredText(cause, "eventBlueId"),
                ExternalOrderKey.of(sourceOrder),
                ClosureFixtureInventory.requiredText(
                        cause, "externalOrderPolicyIdentity"));
    }

    private static ManagedRevisionCause parseManagedRevisionCause(
            JsonNode cause) {
        return new ManagedRevisionCause(
                ClosureFixtureInventory.requiredText(
                        cause, "causeIdentity"),
                ClosureFixtureInventory.requiredText(
                        cause, "targetOccurrenceIdentity"),
                new DocumentId(ClosureFixtureInventory.requiredText(
                        cause, "childDocumentId")),
                ClosureFixtureInventory.requiredLong(cause, "fromEpoch"),
                ClosureFixtureInventory.requiredLong(cause, "toEpoch"),
                ClosureFixtureInventory.requiredText(cause, "beforeBlueId"),
                ClosureFixtureInventory.requiredText(cause, "afterBlueId"),
                node(ClosureFixtureInventory.requiredObject(
                        cause, "afterDocument")),
                ClosureFixtureInventory.requiredText(
                        cause, "originalSourceCauseIdentity"),
                ClosureFixtureInventory.requiredText(
                        cause, "sourceRevisionReceiptIdentity"),
                parseManagedRevisionCyclicProof(cause));
    }

    private static CyclicSetProof parseManagedRevisionCyclicProof(
            JsonNode cause) {
        JsonNode value = cause.get("afterCyclicProof");
        if (value == null || value.isNull()) {
            return null;
        }
        if (!value.isObject()) {
            throw new IllegalArgumentException(
                    "afterCyclicProof must be an object");
        }
        ArrayList<Node> placeholders = new ArrayList<Node>();
        for (JsonNode placeholder : ClosureFixtureInventory.requiredArray(
                value, "declaredPlaceholderSet")) {
            placeholders.add(node(placeholder));
        }
        if (placeholders.isEmpty()) {
            throw new IllegalArgumentException(
                    "afterCyclicProof must contain at least one member");
        }
        return CyclicSetProof.fromDeclaredPlaceholderSet(placeholders);
    }

    private static List<DirectLogicalDelivery> parseDirectDeliveries(
            JsonNode values) {
        ArrayList<DirectLogicalDelivery> result =
                new ArrayList<DirectLogicalDelivery>();
        for (JsonNode value : values) {
            if (!"/".equals(ClosureFixtureInventory.requiredText(
                        value, "scopePath"))
                    || ClosureFixtureInventory.requiredLong(
                            value, "activationGeneration") != 0L) {
                throw new IllegalArgumentException(
                        "Closure direct delivery must target managed Root");
            }
            result.add(new DirectLogicalDelivery(
                    ManagedScopeKey.root(new DocumentId(
                            ClosureFixtureInventory.requiredText(
                                    value, "targetDocumentId"))),
                    ClosureFixtureInventory.requiredText(value, "channelKey"),
                    ClosureFixtureInventory.requiredText(
                            value, "logicalDeliveryKey"),
                    ClosureFixtureInventory.requiredLong(
                            value, "rawOccurrenceOrder")));
        }
        return Collections.unmodifiableList(result);
    }

    private static ExecutionPolicy parseExecutionPolicy(JsonNode policy) {
        LinkedHashMap<DocumentId, Long> localLimits =
                new LinkedHashMap<DocumentId, Long>();
        JsonNode limits = ClosureFixtureInventory.requiredObject(
                policy, "localLimits");
        Iterator<Map.Entry<String, JsonNode>> fields = limits.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (!entry.getValue().isIntegralNumber()) {
                throw new IllegalArgumentException(
                        "Local gas limit must be an Integer");
            }
            localLimits.put(
                    new DocumentId(entry.getKey()),
                    Long.valueOf(entry.getValue().longValue()));
        }
        return new ExecutionPolicy(
                ClosureFixtureInventory.requiredText(policy, "policyIdentity"),
                ClosureFixtureInventory.requiredLong(policy, "sharedLimit"),
                localLimits,
                ClosureFixtureInventory.requiredText(policy, "label"));
    }

    private static ClosureEnvironment parseEnvironment(JsonNode environment) {
        return new ClosureEnvironment(
                ClosureFixtureInventory.requiredText(
                        environment, "blueLanguageSpecificationIdentity"),
                ClosureFixtureInventory.requiredText(
                        environment, "contractsSpecificationIdentity"),
                ClosureFixtureInventory.requiredText(
                        environment, "runtimeRegistryIdentity"),
                ClosureFixtureInventory.requiredText(
                        environment, "gasManifestIdentity"),
                labeledEvidence(environment,
                        "managedDocumentIdentityPolicy"),
                labeledEvidence(environment, "managedBindingPolicy"),
                labeledEvidence(environment, "exactNodeProviderDomain"),
                labeledEvidence(environment, "externalOrderPolicy"),
                portableLimitEvidence(environment, "portableLimitPolicy"),
                ClosureFixtureInventory.requiredText(
                        environment, "cyclicFinalizerIdentity"),
                ClosureFixtureInventory.requiredText(
                        environment, "cyclicProofVerifierIdentity"));
    }

    private static ClosureEnvironment.LabeledIdentityEvidence labeledEvidence(
            JsonNode value, String field) {
        JsonNode evidence = ClosureFixtureInventory.requiredObject(
                value, field);
        return new ClosureEnvironment.LabeledIdentityEvidence(
                ClosureFixtureInventory.requiredText(evidence, "identity"),
                ClosureFixtureInventory.requiredText(evidence, "label"));
    }

    private static ClosureEnvironment.PortableLimitPolicyEvidence
            portableLimitEvidence(JsonNode value, String field) {
        JsonNode evidence = ClosureFixtureInventory.requiredObject(
                value, field);
        LinkedHashMap<String, Long> limits =
                new LinkedHashMap<String, Long>();
        for (JsonNode item : ClosureFixtureInventory.requiredArray(
                evidence, "limits")) {
            String name = ClosureFixtureInventory.requiredText(item, "name");
            long limit = ClosureFixtureInventory.requiredLong(
                    item, BlueLanguageConstants.OBJECT_VALUE);
            if (limits.put(name, Long.valueOf(limit)) != null) {
                throw new IllegalArgumentException(
                        "Duplicate portable limit name: " + name);
            }
        }
        return new ClosureEnvironment.PortableLimitPolicyEvidence(
                ClosureFixtureInventory.requiredText(evidence, "identity"),
                ClosureFixtureInventory.requiredText(evidence, "label"),
                limits);
    }

    private static List<DocumentId> parseDocumentIds(JsonNode values) {
        ArrayList<DocumentId> result = new ArrayList<DocumentId>();
        for (JsonNode value : values) {
            if (!value.isTextual()) {
                throw new IllegalArgumentException("DocumentId must be Text");
            }
            result.add(new DocumentId(value.asText()));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<String> parseTextList(JsonNode values) {
        ArrayList<String> result = new ArrayList<String>();
        for (JsonNode value : values) {
            if (!value.isTextual() || value.asText().isEmpty()) {
                throw new IllegalArgumentException("Expected non-empty Text");
            }
            result.add(value.asText());
        }
        return Collections.unmodifiableList(result);
    }

    private static List<DocumentId> documentIds(
            List<ManagedDocumentSnapshot> documents) {
        ArrayList<DocumentId> result = new ArrayList<DocumentId>();
        for (ManagedDocumentSnapshot document : documents) {
            result.add(document.documentId());
        }
        return result;
    }

    private static List<List<DocumentId>> componentMembers(
            List<ComponentSnapshot> components) {
        ArrayList<List<DocumentId>> result =
                new ArrayList<List<DocumentId>>();
        for (ComponentSnapshot component : components) {
            result.add(component.orderedMemberDocumentIds());
        }
        return result;
    }

    private static Node node(JsonNode value) {
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Exact Blue node is required");
        }
        return UncheckedObjectMapper.JSON_MAPPER.convertValue(value, Node.class);
    }

    private static String nullableText(JsonNode value, String field) {
        JsonNode child = value.get(field);
        if (child == null || child.isNull()) {
            return null;
        }
        if (!child.isTextual() || child.asText().isEmpty()) {
            throw new IllegalArgumentException(
                    field + " must be null or non-empty Text");
        }
        return child.asText();
    }

    private static final class ParsedCyclicEvidence {
        private final String masterBlueId;
        private final CyclicSetProof proof;
        private final String proofIdentity;

        private ParsedCyclicEvidence(
                String masterBlueId,
                CyclicSetProof proof,
                String proofIdentity) {
            this.masterBlueId = masterBlueId;
            this.proof = proof;
            this.proofIdentity = proofIdentity;
        }

        private static ParsedCyclicEvidence absent() {
            return new ParsedCyclicEvidence(null, null, null);
        }
    }

    static final class ParsedFixture {
        private final ClosureFixtureInventory.Entry entry;
        private final long graphGeneration;
        private final String closureIdentity;
        private final String occurrenceBindingSetIdentity;
        private final List<ManagedDocumentSnapshot> documents;
        private final List<ManagedOccurrenceBinding> occurrences;
        private final List<ComponentSnapshot> components;
        private final List<DocumentId> publicRoots;
        private final ProcessingCause cause;
        private final List<DirectLogicalDelivery> directDeliveries;
        private final String directDeliverySnapshotIdentity;
        private final ExecutionPolicy executionPolicy;
        private final ClosureEnvironment environment;
        private final String invocationIdentity;
        private final AdmissionCandidate admissionCandidate;
        private final String admissionCandidateIdentity;
        private final ManagedDocumentGraph graph;

        ParsedFixture(
                ClosureFixtureInventory.Entry entry,
                long graphGeneration,
                String closureIdentity,
                String occurrenceBindingSetIdentity,
                List<ManagedDocumentSnapshot> documents,
                List<ManagedOccurrenceBinding> occurrences,
                List<ComponentSnapshot> components,
                List<DocumentId> publicRoots,
                ProcessingCause cause,
                List<DirectLogicalDelivery> directDeliveries,
                String directDeliverySnapshotIdentity,
                ExecutionPolicy executionPolicy,
                ClosureEnvironment environment,
                String invocationIdentity,
                AdmissionCandidate admissionCandidate,
                String admissionCandidateIdentity,
                ManagedDocumentGraph graph) {
            this.entry = entry;
            this.graphGeneration = graphGeneration;
            this.closureIdentity = closureIdentity;
            this.occurrenceBindingSetIdentity = occurrenceBindingSetIdentity;
            this.documents = documents;
            this.occurrences = occurrences;
            this.components = components;
            this.publicRoots = publicRoots;
            this.cause = cause;
            this.directDeliveries = directDeliveries;
            this.directDeliverySnapshotIdentity = directDeliverySnapshotIdentity;
            this.executionPolicy = executionPolicy;
            this.environment = environment;
            this.invocationIdentity = invocationIdentity;
            this.admissionCandidate = admissionCandidate;
            this.admissionCandidateIdentity = admissionCandidateIdentity;
            this.graph = graph;
        }

        ClosureInvocationInput admit() {
            AffectedClosureSnapshot snapshot = new AffectedClosureSnapshot(
                    closureIdentity,
                    graphGeneration,
                    documents,
                    occurrences,
                    occurrenceBindingSetIdentity,
                    components,
                    publicRoots);
            if ("admit-closure".equals(entry.operation())) {
                if (!(cause instanceof AdmissionCause)
                        || !directDeliveries.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Admission fixture requires an admission cause and zero direct deliveries");
                }
                if ((admissionCandidate == null)
                        != (admissionCandidateIdentity == null)) {
                    throw new IllegalArgumentException(
                            "admissionCandidate and identity must be both present or absent");
                }
                return ClosureInvocationInput.admitClosure(
                        invocationIdentity,
                        snapshot,
                        (AdmissionCause) cause,
                        admissionCandidate,
                        admissionCandidateIdentity,
                        directDeliverySnapshotIdentity,
                        executionPolicy,
                        environment);
            }
            return ClosureInvocationInput.processClosure(
                    invocationIdentity,
                    snapshot,
                    cause,
                    directDeliveries,
                    directDeliverySnapshotIdentity,
                    executionPolicy,
                    environment);
        }

        ClosureFixtureInventory.Entry entry() {
            return entry;
        }

        List<ManagedDocumentSnapshot> documents() {
            return documents;
        }

        List<ComponentSnapshot> components() {
            return components;
        }

        ManagedDocumentGraph graph() {
            return graph;
        }
    }
}
