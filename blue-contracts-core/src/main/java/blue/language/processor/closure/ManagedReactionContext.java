package blue.language.processor.closure;

import blue.language.processor.ExternalOrderKey;
import java.math.BigInteger;
import java.util.*;

/**
 * Observer-owned historical reaction position, distinct from source-event provenance.
 * The exact source programs retain their original external cause and observation sites.
 * This value selects the new consumer operation and its due occurrence lanes; it does not
 * make a pending occurrence live or certify a source receipt merely by hashing its fields.
 */
public final class ManagedReactionContext {
    private final String creatingOperationIdentity, creatorExecutionSeedIdentity, creationSiteIdentity;
    private final ExternalOrderKey activationCut;
    private final String sourceReactionPositionIdentity, identity, reactionOriginIdentity;
    private final List<DueOccurrence> dueOccurrences;

    public ManagedReactionContext(String creatingOperationIdentity, String creatorExecutionSeedIdentity,
            String creationSiteIdentity, ExternalOrderKey activationCut, String sourceReactionPositionIdentity,
            Collection<DueOccurrence> dueOccurrences) {
        this.creatingOperationIdentity = digest(creatingOperationIdentity, "creatingOperationIdentity");
        this.creatorExecutionSeedIdentity = digest(creatorExecutionSeedIdentity, "creatorExecutionSeedIdentity");
        this.creationSiteIdentity = digest(creationSiteIdentity, "creationSiteIdentity");
        this.activationCut = Objects.requireNonNull(activationCut, "activationCut");
        requireOrder(activationCut.components());
        this.sourceReactionPositionIdentity = digest(sourceReactionPositionIdentity, "sourceReactionPositionIdentity");
        TreeMap<String, DueOccurrence> canonical = new TreeMap<>();
        for (DueOccurrence due : Objects.requireNonNull(dueOccurrences, "dueOccurrences")) {
            DueOccurrence value = Objects.requireNonNull(due, "dueOccurrence");
            if (canonical.put(value.occurrenceIdentity(), value) != null) throw invalid("A reaction repeats an occurrence lane");
        }
        if (canonical.isEmpty()) throw invalid("A managed reaction requires due occurrence lanes");
        this.dueOccurrences = Collections.unmodifiableList(new ArrayList<>(canonical.values()));
        reactionOriginIdentity = ClosureIdentityService.INSTANCE.identity(ClosureIdentityService.Constructor.MANAGED_REACTION_ORIGIN, originValue());
        Map<String, Object> value = new LinkedHashMap<>(); value.put("reactionOriginIdentity", reactionOriginIdentity);
        List<Object> rows = new ArrayList<>(); for (DueOccurrence due : this.dueOccurrences) rows.add(due.identityValue());
        value.put("dueOccurrences", rows);
        identity = ClosureIdentityService.INSTANCE.identity(ClosureIdentityService.Constructor.MANAGED_REACTION_CONTEXT, value);
    }

    public String creatingOperationIdentity() { return creatingOperationIdentity; }
    public String creatorExecutionSeedIdentity() { return creatorExecutionSeedIdentity; }
    public String creationSiteIdentity() { return creationSiteIdentity; }
    public ExternalOrderKey activationCut() { return activationCut; }
    public String sourceReactionPositionIdentity() { return sourceReactionPositionIdentity; }
    public List<DueOccurrence> dueOccurrences() { return dueOccurrences; }
    public String identity() { return identity; }
    /** Stable inherited origin, without consumer membership or physical receipt inventory. */
    public String reactionOriginIdentity() { return reactionOriginIdentity; }

    /** Checks exact due membership against already authenticated source capabilities and closure evidence. */
    public void verifyBasis(AffectedClosureSnapshot snapshot, Set<DocumentId> consumerOwned,
            List<SourceObservationProgram> programs, List<SourceOperationFailure> failures) {
        Objects.requireNonNull(snapshot); Objects.requireNonNull(consumerOwned);
        Map<String, ManagedOccurrenceBinding> rows = new HashMap<>();
        for (ManagedOccurrenceBinding row : snapshot.occurrences()) rows.put(row.occurrenceIdentity(), row);
        Map<String, Set<DocumentId>> sources = new HashMap<>();
        for (SourceObservationProgram program : Objects.requireNonNull(programs)) {
            if (program.causeKind() != ProcessingCause.Kind.EXTERNAL) throw invalid("Historical reactions consume external source operations, not initialization as an epoch");
            Set<DocumentId> previous = sources.put(program.invocationIdentity(), program.ownedDocumentIds());
            if (previous != null && !previous.equals(program.ownedDocumentIds())) throw invalid("Conflicting source operation ownership");
        }
        for (SourceOperationFailure failure : Objects.requireNonNull(failures)) {
            if (failure.externalCause() == null || sources.put(failure.invocationIdentity(), failure.ownedDocumentIds()) != null)
                throw invalid("Conflicting or non-external source failure");
        }
        for (DueOccurrence due : dueOccurrences) {
            ManagedOccurrenceBinding row = rows.get(due.occurrenceIdentity());
            if (row == null || !row.sourceDocumentId().equals(due.consumerLineage())
                    || !row.targetDocumentId().equals(due.sourceLineage())
                    || !consumerOwned.contains(due.consumerLineage())
                    || snapshot.managedDocument(due.sourceLineage()) == null
                    || !row.active() && row.pendingHistoricalEpoch() == null)
                throw invalid("Managed reaction targets another, retired, or unavailable occurrence activation");
            if (!sources.getOrDefault(due.sourceOperationIdentity(), Collections.emptySet()).contains(due.sourceLineage()))
                throw invalid("Managed reaction lacks its exact source operation capability");
        }
    }

    public static final class DueOccurrence {
        private final String occurrenceIdentity, sourceOperationIdentity, expectedLanePositionIdentity;
        private final DocumentId consumerLineage, sourceLineage;
        public DueOccurrence(String occurrenceIdentity, DocumentId consumerLineage, DocumentId sourceLineage,
                String sourceOperationIdentity, String expectedLanePositionIdentity) {
            this.occurrenceIdentity = digest(occurrenceIdentity, "occurrenceIdentity");
            this.consumerLineage = Objects.requireNonNull(consumerLineage, "consumerLineage");
            this.sourceLineage = Objects.requireNonNull(sourceLineage, "sourceLineage");
            this.sourceOperationIdentity = digest(sourceOperationIdentity, "sourceOperationIdentity");
            this.expectedLanePositionIdentity = digest(expectedLanePositionIdentity, "expectedLanePositionIdentity");
        }
        public String occurrenceIdentity() { return occurrenceIdentity; }
        public DocumentId consumerLineage() { return consumerLineage; }
        public DocumentId sourceLineage() { return sourceLineage; }
        public String sourceOperationIdentity() { return sourceOperationIdentity; }
        public String expectedLanePositionIdentity() { return expectedLanePositionIdentity; }
        private Map<String, Object> identityValue() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("occurrenceIdentity", occurrenceIdentity); value.put("consumerLineage", consumerLineage.value());
            value.put("sourceLineage", sourceLineage.value()); value.put("sourceOperationIdentity", sourceOperationIdentity);
            value.put("expectedLanePositionIdentity", expectedLanePositionIdentity); return value;
        }
    }

    private Map<String, Object> originValue() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("creatingOperationIdentity", creatingOperationIdentity); value.put("creatorExecutionSeedIdentity", creatorExecutionSeedIdentity);
        value.put("creationSiteIdentity", creationSiteIdentity); value.put("activationCut", activationCut.components());
        value.put("sourceReactionPositionIdentity", sourceReactionPositionIdentity); return value;
    }

    static void validateConstructor(ClosureIdentityService.Constructor constructor, Map<String, Object> value) {
        if (constructor == ClosureIdentityService.Constructor.MANAGED_REACTION_ORIGIN) {
            fields(value, "creatingOperationIdentity", "creatorExecutionSeedIdentity", "creationSiteIdentity", "activationCut", "sourceReactionPositionIdentity");
            for (String field : Arrays.asList("creatingOperationIdentity", "creatorExecutionSeedIdentity", "creationSiteIdentity", "sourceReactionPositionIdentity"))
                digest(text(value.get(field)), field);
            requireOrder(list(value.get("activationCut"))); return;
        }
        fields(value, "reactionOriginIdentity", "dueOccurrences"); digest(text(value.get("reactionOriginIdentity")), "reactionOriginIdentity");
        String previous = null; List<?> rows = list(value.get("dueOccurrences"));
        if (rows.isEmpty()) throw invalid("Missing due occurrence lanes");
        for (Object item : rows) {
            Map<String, Object> row = object(item);
            fields(row, "occurrenceIdentity", "consumerLineage", "sourceLineage", "sourceOperationIdentity", "expectedLanePositionIdentity");
            String occurrence = digest(text(row.get("occurrenceIdentity")), "occurrenceIdentity");
            if (previous != null && previous.compareTo(occurrence) >= 0) throw invalid("Due occurrence lanes must be unique and canonically ordered");
            previous = occurrence;
            new DocumentId(text(row.get("consumerLineage"))); new DocumentId(text(row.get("sourceLineage")));
            digest(text(row.get("sourceOperationIdentity")), "sourceOperationIdentity");
            digest(text(row.get("expectedLanePositionIdentity")), "expectedLanePositionIdentity");
        }
    }
    private static void requireOrder(List<?> order) {
        if (order.size() != 2 || !(order.get(0) instanceof Number) || !(order.get(1) instanceof String)) throw invalid("Activation cut requires exact microseconds and entry BlueId");
        BigInteger micros;
        try { micros = new BigInteger(order.get(0).toString()); } catch (NumberFormatException exception) { throw invalid("Activation microseconds must be exact integers"); }
        if (micros.signum() <= 0 || micros.compareTo(BigInteger.valueOf(ClosureValueSupport.MAX_SAFE_INTEGER)) >= 0) throw invalid("Activation microseconds are outside the safe range");
        ClosureValueSupport.requireBlueId((String) order.get(1), "activation entry");
    }
    private static String digest(String value, String name) { return ClosureValueSupport.requireSha256Identity(value, name); }
    private static String text(Object value) { if (!(value instanceof String)) throw invalid("Expected reaction text"); return (String) value; }
    private static List<?> list(Object value) { if (!(value instanceof List)) throw invalid("Expected reaction array"); return (List<?>) value; }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) { if (!(value instanceof Map)) throw invalid("Expected reaction object"); return (Map<String, Object>) value; }
    private static void fields(Map<String, Object> value, String... names) { if (!value.keySet().equals(new HashSet<>(Arrays.asList(names)))) throw invalid("Invalid reaction field set"); }
    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
