package blue.language.processor.closure;

import blue.language.model.wire.BlueLanguageConstants;
import blue.language.processor.util.ProcessorContractConstants;
import java.util.*;

/**
 * Stable pre-cut SCC execution authority. The caller has admitted the immutable
 * invocation once. Consumed independent source operations belong to final
 * settlement authority, never the receipts currently known by a worker. Final group membership and
 * settlement identity cannot re-anchor already produced work or events.
 */
final class SameOriginSeedIdentity {
    private static final ClosureIdentityService IDS = ClosureIdentityService.INSTANCE;
    private final String identity;
    private final Set<DocumentId> members;

    private SameOriginSeedIdentity(String identity, Set<DocumentId> members) {
        this.identity = identity; this.members = Collections.unmodifiableSet(new HashSet<>(members));
    }

    static SameOriginSeedIdentity of(ClosureInvocationInput input, List<DocumentId> preCutComponentMembers,
                                    Map<DocumentId, String> consumedTerminalSourceReceipts) {
        if (!Objects.requireNonNull(consumedTerminalSourceReceipts).isEmpty())
            throw invalid("Consumed source dispositions belong to final settlement, not a stable execution seed");
        return of(input, preCutComponentMembers, SameOriginAttachmentPolicy.empty());
    }

    /** The Session verifies the full frozen policy once; each seed binds only its creators' choices. */
    static SameOriginSeedIdentity of(ClosureInvocationInput input, List<DocumentId> preCutComponentMembers,
                                    SameOriginAttachmentPolicy attachmentPolicy) {
        return new Factory(input, attachmentPolicy).of(preCutComponentMembers);
    }

    /** One index per driver, also reusable after invalidation; never scans all unrelated seeds per owner. */
    static final class Factory {
        private final ClosureInvocationInput input;
        private final SameOriginAttachmentPolicy policy;
        private final Map<DocumentId, ComponentSnapshot> components = new HashMap<>();
        private final Map<DocumentId, List<ManagedOccurrenceBinding>> outgoing = new HashMap<>();
        private final Map<DocumentId, List<DirectLogicalDelivery>> deliveries = new HashMap<>();
        private final Map<String, Object> environment;

        Factory(ClosureInvocationInput input, SameOriginAttachmentPolicy policy) {
            this.input = Objects.requireNonNull(input, "input");
            this.policy = Objects.requireNonNull(policy, "policy");
            for (ComponentSnapshot component : input.snapshot().components())
                for (DocumentId member : component.orderedMemberDocumentIds()) components.put(member, component);
            for (ManagedOccurrenceBinding binding : input.snapshot().occurrences())
                outgoing.computeIfAbsent(binding.sourceDocumentId(), ignored -> new ArrayList<>()).add(binding);
            for (DirectLogicalDelivery delivery : input.directDeliveries())
                deliveries.computeIfAbsent(delivery.targetDocumentId(), ignored -> new ArrayList<>()).add(delivery);
            environment = environment(input);
        }

        SameOriginSeedIdentity of(List<DocumentId> members) {
            return create(input, members, policy, this);
        }
    }

    private static SameOriginSeedIdentity create(ClosureInvocationInput input, List<DocumentId> preCutComponentMembers,
                                                SameOriginAttachmentPolicy attachmentPolicy, Factory basis) {
        Objects.requireNonNull(input, "input"); Objects.requireNonNull(preCutComponentMembers, "preCutComponentMembers");
        Set<DocumentId> owned = new HashSet<>(preCutComponentMembers);
        if (owned.isEmpty() || owned.size() != preCutComponentMembers.size()) throw invalid("Seed members must be nonempty and unique");
        ComponentSnapshot selected = basis.components.get(preCutComponentMembers.get(0));
        if (selected == null || !selected.orderedMemberDocumentIds().equals(preCutComponentMembers))
            throw invalid("Seed ownership must be one complete pre-cut component in its intrinsic member order");
        List<Object> states = new ArrayList<>();
        for (DocumentId member : preCutComponentMembers) {
            ManagedDocumentSnapshot document = input.snapshot().managedDocument(member);
            if (document == null) throw invalid("Missing owned seed state");
            states.add(map("documentId", member.value(), BlueLanguageConstants.OBJECT_BLUE_ID, document.blueId(), "epoch", document.epoch(),
                    ProcessorContractConstants.KEY_INITIALIZED, document.initialized(), ProcessorContractConstants.KEY_TERMINATED, document.terminated(),
                    "precedingOperation", input.semanticPredecessors().get(member)));
        }
        List<ManagedOccurrenceBinding> outgoing = new ArrayList<>();
        for (DocumentId member : owned) outgoing.addAll(basis.outgoing.getOrDefault(member, Collections.emptyList()));
        Collections.sort(outgoing);
        List<Object> bindings = new ArrayList<>();
        TreeMap<DocumentId, Set<String>> pins = new TreeMap<>();
        for (ManagedOccurrenceBinding binding : outgoing) {
            bindings.add(map("occurrenceIdentity", binding.occurrenceIdentity(), "bindingIdentity", binding.bindingIdentity(),
                    "active", binding.active(), "pendingHistoricalEpoch", binding.pendingHistoricalEpoch()));
            if (binding.active() && !owned.contains(binding.targetDocumentId())) {
                Set<String> views = pins.get(binding.targetDocumentId());
                if (views == null) { views = new TreeSet<>(); pins.put(binding.targetDocumentId(), views); }
                views.add(binding.expectedTargetBlueId());
            }
        }
        // Required exact views are semantic; whether a body is resident or supplied as a read-pin is physical.
        List<Object> pinValues = new ArrayList<>();
        for (Map.Entry<DocumentId, Set<String>> entry : pins.entrySet()) for (String blueId : entry.getValue())
            pinValues.add(map("documentId", entry.getKey().value(), BlueLanguageConstants.OBJECT_BLUE_ID, blueId));
        List<DirectLogicalDelivery> deliveries = new ArrayList<>();
        for (DocumentId member : owned) deliveries.addAll(basis.deliveries.getOrDefault(member, Collections.emptyList()));
        Collections.sort(deliveries, new Comparator<DirectLogicalDelivery>() {
            @Override public int compare(DirectLogicalDelivery a, DirectLogicalDelivery b) {
                int c = a.targetScope().compareTo(b.targetScope()); if (c != 0) return c;
                c = ClosureValueSupport.comparePortableText(a.channelKey(), b.channelKey());
                return c != 0 ? c : ClosureValueSupport.comparePortableText(a.logicalDeliveryKey(), b.logicalDeliveryKey());
            }
        });
        List<Object> direct = new ArrayList<>(); Set<String> uniqueDirect = new HashSet<>();
        for (DirectLogicalDelivery delivery : deliveries) {
            String id = directIdentity(delivery);
            if (!uniqueDirect.add(id)) throw invalid("Repeated seed-local direct delivery");
            direct.add(id);
        }
        List<Object> attachmentPolicies = new ArrayList<>();
        for (SameOriginAttachmentPolicy.Selection selection : Objects.requireNonNull(attachmentPolicy).ownedBy(owned).entries())
            attachmentPolicies.add(selection.identity());
        Map<String, Object> preimage = map("operation", input.operation().wireValue(), "causeIdentity", input.cause().causeIdentity(),
                "componentKind", selected.kind().name(), "ownedMembers", states, "ownedBindings", bindings,
                "exactPins", pinValues, "ownedDirectDeliveries", direct, "attachmentPolicies", attachmentPolicies,
                "environment", basis.environment);
        return new SameOriginSeedIdentity(IDS.identity(ClosureIdentityService.Constructor.SAME_ORIGIN_SEED, preimage), owned);
    }

    String identity() { return identity; }
    String directDeliveryIdentity(DirectLogicalDelivery delivery) {
        requireOwned(delivery.targetDocumentId()); return directIdentity(delivery);
    }
    String workIdentity(long seedLocalOrdinal, WorkKind kind, ManagedScopeKey target, String sourceOccurrenceIdentity) {
        requireOwned(target.documentId());
        return IDS.identity(ClosureIdentityService.Constructor.SAME_ORIGIN_WORK, map("executionSeed", identity,
                "ordinal", seedLocalOrdinal, "workKind", Objects.requireNonNull(kind).name(),
                "targetScope", IDS.managedScopeKeyIdentity(target), "sourceOccurrence", sourceOccurrenceIdentity));
    }
    String eventIdentity(long seedLocalOrdinal, DocumentId producingLineage, String causingWorkIdentity, String eventBlueId) {
        requireOwned(producingLineage);
        return IDS.identity(ClosureIdentityService.Constructor.SAME_ORIGIN_EVENT, map("executionSeed", identity,
                "ordinal", seedLocalOrdinal, "producingLineage", producingLineage.value(), "causingWork", causingWorkIdentity, "eventBlueId", eventBlueId));
    }
    String transitionIdentity(long seedLocalOrdinal, DocumentId document, String beforeBlueId, String causingWorkIdentity) {
        requireOwned(document);
        return IDS.identity(ClosureIdentityService.Constructor.SAME_ORIGIN_TRANSITION, map("executionSeed", identity,
                "ordinal", seedLocalOrdinal, "documentId", document.value(), "beforeBlueId", beforeBlueId, "causingWork", causingWorkIdentity));
    }

    private void requireOwned(DocumentId document) { if (!members.contains(document)) throw invalid("Work target is outside its original execution seed"); }
    private static String directIdentity(DirectLogicalDelivery delivery) {
        return IDS.identity(ClosureIdentityService.Constructor.SAME_ORIGIN_DIRECT_DELIVERY, map("targetDocumentId", delivery.targetDocumentId().value(),
                "scopePath", delivery.targetScope().address().path(), "activationGeneration", delivery.targetScope().address().activationGeneration(),
                "channelKey", delivery.channelKey(), "logicalDeliveryKey", delivery.logicalDeliveryKey()));
    }
    private static Map<String, Object> environment(ClosureInvocationInput input) {
        ClosureEnvironment e = input.environment();
        return map("language", e.blueLanguageSpecificationIdentity(), BlueLanguageConstants.OBJECT_CONTRACTS, e.contractsSpecificationIdentity(),
                "registry", e.runtimeRegistryIdentity(), "gasManifest", e.gasManifestIdentity(), "gasPolicy", input.executionPolicy().identity(),
                "documentPolicy", e.managedDocumentIdentityPolicyIdentity(), "bindingPolicy", e.managedBindingPolicyIdentity(),
                "providerDomain", e.exactNodeProviderDomainIdentity(), "externalOrder", e.externalOrderPolicyIdentity(),
                "portableLimits", e.portableLimitPolicyIdentity(), "cyclicFinalizer", e.cyclicFinalizerIdentity(), "cyclicVerifier", e.cyclicProofVerifierIdentity());
    }

    /** Invoked by the closed canonical identity registry after portable-value normalization. */
    static void validateConstructor(ClosureIdentityService.Constructor kind, Map<String, Object> value) {
        switch (kind) {
            case SAME_ORIGIN_DIRECT_DELIVERY:
                text(value, "targetDocumentId"); text(value, "channelKey"); text(value, "logicalDeliveryKey");
                String path = text(value, "scopePath"); long activation = number(value.get("activationGeneration"));
                if ("/".equals(path)) { if (activation != 0L) throw invalid("Root generation must be zero"); }
                else ScopeAddress.embedded(path, activation);
                return;
            case SAME_ORIGIN_WORK:
                sha(value, "executionSeed", false); number(value.get("ordinal"));
                WorkKind.valueOf(text(value, "workKind")); sha(value, "targetScope", false); sha(value, "sourceOccurrence", false);
                return;
            case SAME_ORIGIN_EVENT:
                sha(value, "executionSeed", false); number(value.get("ordinal")); text(value, "producingLineage");
                sha(value, "causingWork", false); blue(value, "eventBlueId"); return;
            case SAME_ORIGIN_TRANSITION:
                sha(value, "executionSeed", false); number(value.get("ordinal")); text(value, "documentId");
                blue(value, "beforeBlueId"); sha(value, "causingWork", false); return;
            case SAME_ORIGIN_SEED:
                String operation = text(value, "operation");
                if (!"process-closure".equals(operation)) throw invalid("Same-origin seeds require a processing invocation");
                sha(value, "causeIdentity", false); ComponentKind.valueOf(text(value, "componentKind"));
                Set<String> members = new HashSet<>();
                for (Object item : list(value.get("ownedMembers"))) {
                    Map<String, Object> row = object(item); fields(row, "documentId", BlueLanguageConstants.OBJECT_BLUE_ID, "epoch", ProcessorContractConstants.KEY_INITIALIZED, ProcessorContractConstants.KEY_TERMINATED, "precedingOperation");
                    if (!members.add(text(row, "documentId"))) throw invalid("Duplicate seed member");
                    blue(row, BlueLanguageConstants.OBJECT_BLUE_ID); number(row.get("epoch")); bool(row, ProcessorContractConstants.KEY_INITIALIZED); bool(row, ProcessorContractConstants.KEY_TERMINATED); sha(row, "precedingOperation", true);
                }
                if (members.isEmpty()) throw invalid("Empty seed member set");
                for (Object item : list(value.get("ownedBindings"))) {
                    Map<String, Object> row = object(item); fields(row, "occurrenceIdentity", "bindingIdentity", "active", "pendingHistoricalEpoch");
                    sha(row, "occurrenceIdentity", false); sha(row, "bindingIdentity", false); bool(row, "active");
                    Object cursor = row.get("pendingHistoricalEpoch");
                    if (cursor != null && (!(cursor instanceof Number) || ((Number) cursor).longValue() < -1L)) throw invalid("Invalid historical epoch");
                }
                for (Object item : list(value.get("exactPins"))) {
                    Map<String, Object> row = object(item); fields(row, "documentId", BlueLanguageConstants.OBJECT_BLUE_ID); text(row, "documentId"); blue(row, BlueLanguageConstants.OBJECT_BLUE_ID);
                }
                for (Object item : list(value.get("ownedDirectDeliveries"))) ClosureValueSupport.requireSha256Identity((String) item, "direct delivery");
                Set<String> policyIdentities = new HashSet<>();
                for (Object item : list(value.get("attachmentPolicies"))) {
                    String policyIdentity = ClosureValueSupport.requireSha256Identity((String) item, "attachment policy");
                    if (!policyIdentities.add(policyIdentity)) throw invalid("Duplicate attachment policy");
                }
                Map<String, Object> environment = object(value.get("environment"));
                fields(environment, "language", BlueLanguageConstants.OBJECT_CONTRACTS, "registry", "gasManifest", "gasPolicy", "documentPolicy", "bindingPolicy", "providerDomain",
                        "externalOrder", "portableLimits", "cyclicFinalizer", "cyclicVerifier");
                for (String key : environment.keySet()) sha(environment, key, false);
                return;
            default: throw invalid("Not a same-origin constructor");
        }
    }
    private static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map)) throw invalid("Expected seed object"); return (Map<String, Object>) value;
    }
    private static List<?> list(Object value) { if (!(value instanceof List)) throw invalid("Expected seed array"); return (List<?>) value; }
    private static void fields(Map<String, Object> value, String... keys) {
        if (!value.keySet().equals(new HashSet<>(Arrays.asList(keys)))) throw invalid("Invalid closed seed field set");
    }
    private static String text(Map<String, Object> value, String key) {
        if (!(value.get(key) instanceof String)) throw invalid("Expected seed text " + key);
        return ClosureValueSupport.requireNonEmptyText((String) value.get(key), key);
    }
    private static void sha(Map<String, Object> value, String key, boolean nullable) {
        if (!nullable || value.get(key) != null) ClosureValueSupport.requireSha256Identity(text(value, key), key);
    }
    private static void blue(Map<String, Object> value, String key) { ClosureValueSupport.requireBlueId(text(value, key), key); }
    private static long number(Object value) {
        if (!(value instanceof Number)) throw invalid("Expected seed integer");
        return ClosureValueSupport.requireSafeInteger(((Number) value).longValue(), "seed integer");
    }
    private static void bool(Map<String, Object> value, String key) { if (!(value.get(key) instanceof Boolean)) throw invalid("Expected seed boolean " + key); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
