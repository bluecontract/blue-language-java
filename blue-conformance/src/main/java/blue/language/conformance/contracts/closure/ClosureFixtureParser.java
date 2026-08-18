package blue.language.conformance.contracts.closure;

import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.model.Node;
import blue.language.processor.ExternalOrderKey;
import blue.language.processor.closure.AffectedClosureSnapshot;
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
import blue.language.processor.closure.ManagedScopeKey;
import blue.language.processor.closure.SccPartitioner;
import blue.language.processor.closure.ScopeAddress;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class ClosureFixtureParser {

    ParsedFixture parse(ClosureFixtureInventory.Entry entry) {
        JsonNode fixture = ClosureFixtureInventory.readFixture(entry);
        if (!entry.id().equals(
                    ClosureFixtureInventory.requiredText(fixture, "id"))
                || !entry.operation().equals(
                        ClosureFixtureInventory.requiredText(
                                fixture, "operation"))) {
            throw new IllegalArgumentException(
                    "Fixture envelope disagrees with inventory");
        }
        if (!"process-closure".equals(entry.operation())) {
            throw new UnsupportedOperationException(
                    "Initial closure harness supports process-closure only");
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
                parseExternalCause(
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
                graph);
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
                    ClosureFixtureInventory.requiredText(record, "blueId"),
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
            if (kind == ComponentKind.CYCLIC) {
                throw new UnsupportedOperationException(
                        "Cyclic proof admission is not implemented by the initial harness");
            }
            result.add(new ComponentSnapshot(
                    ClosureFixtureInventory.requiredText(
                            value, "componentIdentity"),
                    ClosureFixtureInventory.requiredText(
                            value, "componentStateIdentity"),
                    ClosureFixtureInventory.requiredLong(
                            value, "componentGeneration"),
                    kind,
                    parseDocumentIds(
                            ClosureFixtureInventory.requiredArray(
                                    value, "orderedMemberDocumentIds")),
                    parseTextList(ClosureFixtureInventory.requiredArray(
                            value, "orderedMemberBlueIds")),
                    null,
                    null,
                    null));
        }
        return Collections.unmodifiableList(result);
    }

    private static ExternalEventCause parseExternalCause(JsonNode cause) {
        if (!"external".equals(
                ClosureFixtureInventory.requiredText(cause, "kind"))) {
            throw new UnsupportedOperationException(
                    "Initial closure harness supports external causes only");
        }
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
            long limit = ClosureFixtureInventory.requiredLong(item, "value");
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

    static final class ParsedFixture {
        private final ClosureFixtureInventory.Entry entry;
        private final long graphGeneration;
        private final String closureIdentity;
        private final String occurrenceBindingSetIdentity;
        private final List<ManagedDocumentSnapshot> documents;
        private final List<ManagedOccurrenceBinding> occurrences;
        private final List<ComponentSnapshot> components;
        private final List<DocumentId> publicRoots;
        private final ExternalEventCause cause;
        private final List<DirectLogicalDelivery> directDeliveries;
        private final String directDeliverySnapshotIdentity;
        private final ExecutionPolicy executionPolicy;
        private final ClosureEnvironment environment;
        private final String invocationIdentity;
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
                ExternalEventCause cause,
                List<DirectLogicalDelivery> directDeliveries,
                String directDeliverySnapshotIdentity,
                ExecutionPolicy executionPolicy,
                ClosureEnvironment environment,
                String invocationIdentity,
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
