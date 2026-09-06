package blue.language.processor.closure;

import blue.language.processor.ExternalOrderKey;
import java.util.*;

/**
 * Frozen observer selections for prospective occurrences in one canonical cause.
 * These selections belong to their creators' original execution seeds. They neither
 * change the source's intrinsic history nor authorize a lookup of its physical head.
 */
public final class SameOriginAttachmentPolicy {
    public enum Mode { FULL_HISTORY, FROM_NOW, FROM_FRONTIER }

    private static final SameOriginAttachmentPolicy EMPTY = new SameOriginAttachmentPolicy(Collections.emptyList());
    private final List<Selection> entries;
    private final Map<String, Selection> byOccurrence;
    private final Map<DocumentId, List<Selection>> byCreator;

    public SameOriginAttachmentPolicy(Collection<Selection> selections) {
        Map<String, Selection> canonical = new TreeMap<>();
        for (Selection selection : Objects.requireNonNull(selections, "selections")) {
            Selection value = Objects.requireNonNull(selection, "selection");
            if (canonical.put(value.occurrenceIdentity(), value) != null)
                throw new IllegalArgumentException("An occurrence has more than one attachment selection");
        }
        byOccurrence = Collections.unmodifiableMap(canonical);
        entries = Collections.unmodifiableList(new ArrayList<>(canonical.values()));
        Map<DocumentId, List<Selection>> creators = new HashMap<>();
        for (Selection selection : entries)
            creators.computeIfAbsent(selection.creatorLineage(), ignored -> new ArrayList<>()).add(selection);
        Map<DocumentId, List<Selection>> frozen = new HashMap<>();
        for (Map.Entry<DocumentId, List<Selection>> entry : creators.entrySet())
            frozen.put(entry.getKey(), Collections.unmodifiableList(entry.getValue()));
        byCreator = Collections.unmodifiableMap(frozen);
    }

    public static SameOriginAttachmentPolicy empty() { return EMPTY; }
    public List<Selection> entries() { return entries; }
    public Optional<Selection> selection(String occurrenceIdentity) {
        return Optional.ofNullable(byOccurrence.get(ClosureValueSupport.requireSha256Identity(occurrenceIdentity, "occurrenceIdentity")));
    }

    /** Restricts semantic input to the original creator component, never its reverse observers. */
    public SameOriginAttachmentPolicy ownedBy(Collection<DocumentId> creators) {
        Set<DocumentId> owned = new HashSet<>();
        for (DocumentId creator : Objects.requireNonNull(creators, "creators")) owned.add(Objects.requireNonNull(creator));
        List<Selection> selected = new ArrayList<>();
        for (DocumentId creator : owned) selected.addAll(byCreator.getOrDefault(creator, Collections.emptyList()));
        return selected.isEmpty() ? empty() : new SameOriginAttachmentPolicy(selected);
    }

    /**
     * Verifies the original prospective occurrence and the supplied reference's exact
     * lineage basis. A current cell or retained exact read pin establishes the reference;
     * a host assertion that a different BlueId is "the same document" does not.
     *
     * This is input-shape validation over already authenticated closure evidence. The
     * caller must bind these selections into the original owning execution seed.
     */
    public void verifyBasis(AffectedClosureSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        Map<String, ManagedOccurrenceBinding> occurrences = new HashMap<>();
        for (ManagedOccurrenceBinding binding : snapshot.occurrences()) occurrences.put(binding.occurrenceIdentity(), binding);
        Map<DocumentId, ManagedDocumentSnapshot> documents = new HashMap<>();
        for (ManagedDocumentSnapshot document : snapshot.managedDocuments()) documents.put(document.documentId(), document);
        Map<DocumentId, Set<String>> exactPins = new HashMap<>();
        for (ManagedReadPin pin : snapshot.readPins())
            exactPins.computeIfAbsent(pin.documentId(), ignored -> new HashSet<>()).add(pin.blueId());
        for (Selection selection : entries) {
            ManagedOccurrenceBinding binding = occurrences.get(selection.occurrenceIdentity());
            if (binding == null || binding.active() || binding.pendingHistoricalEpoch() != null
                    || !selection.creatorLineage().equals(binding.sourceDocumentId())
                    || !selection.targetLineage().equals(binding.targetDocumentId())
                    || !documents.containsKey(selection.creatorLineage()))
                throw new IllegalArgumentException("Attachment selection does not identify an original prospective occurrence");
            ManagedDocumentSnapshot target = documents.get(selection.targetLineage());
            if (target == null || !selection.suppliedExactRefBlueId().equals(target.blueId())
                    && !exactPins.getOrDefault(selection.targetLineage(), Collections.emptySet()).contains(selection.suppliedExactRefBlueId()))
                throw new IllegalArgumentException("Attachment reference lacks exact evidence for its selected lineage");
        }
    }

    /** A declaration, not an accepted activation or a processed source receipt. */
    public static final class Selection {
        private final Mode mode;
        private final DocumentId creatorLineage, targetLineage;
        private final String occurrenceIdentity, suppliedExactRefBlueId, identity;
        private final ExternalOrderKey frontier;

        public Selection(Mode mode, DocumentId creatorLineage, String occurrenceIdentity,
                         DocumentId targetLineage, String suppliedExactRefBlueId) {
            this(mode, creatorLineage, occurrenceIdentity, targetLineage, suppliedExactRefBlueId, null);
        }

        /** F is a frozen observer choice, not proof that the source prefix through F is complete. */
        public Selection(Mode mode, DocumentId creatorLineage, String occurrenceIdentity,
                         DocumentId targetLineage, String suppliedExactRefBlueId, ExternalOrderKey frontier) {
            this.mode = Objects.requireNonNull(mode, "mode");
            this.creatorLineage = Objects.requireNonNull(creatorLineage, "creatorLineage");
            this.occurrenceIdentity = ClosureValueSupport.requireSha256Identity(occurrenceIdentity, "occurrenceIdentity");
            this.targetLineage = Objects.requireNonNull(targetLineage, "targetLineage");
            this.suppliedExactRefBlueId = ClosureValueSupport.requireBlueId(suppliedExactRefBlueId, "suppliedExactRefBlueId");
            if ((mode == Mode.FROM_FRONTIER) != (frontier != null))
                throw new IllegalArgumentException("Only FROM_FRONTIER requires an exact frontier");
            if (frontier != null) requireFrontier(frontier);
            this.frontier = frontier;
            identity = ClosureIdentityService.INSTANCE.identity(ClosureIdentityService.Constructor.SAME_ORIGIN_ATTACHMENT_SELECTION, identityValue());
        }

        public Mode mode() { return mode; }
        public DocumentId creatorLineage() { return creatorLineage; }
        public String occurrenceIdentity() { return occurrenceIdentity; }
        public DocumentId targetLineage() { return targetLineage; }
        public String suppliedExactRefBlueId() { return suppliedExactRefBlueId; }
        public Optional<ExternalOrderKey> frontier() { return Optional.ofNullable(frontier); }
        String identity() { return identity; }
        Map<String, Object> identityValue() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("mode", mode.name()); value.put("creatorLineage", creatorLineage.value());
            value.put("occurrenceIdentity", occurrenceIdentity); value.put("targetLineage", targetLineage.value());
            value.put("suppliedExactRefBlueId", suppliedExactRefBlueId);
            value.put("frontier", frontier == null ? null : frontier.components());
            return Collections.unmodifiableMap(value);
        }
    }

    static void requireFrontier(ExternalOrderKey frontier) {
        List<Object> order = Objects.requireNonNull(frontier, "frontier").components();
        if (order.size() != 2 || !(order.get(0) instanceof java.math.BigInteger) || !(order.get(1) instanceof String))
            throw new IllegalArgumentException("Frontier requires exact microseconds and entry BlueId");
        java.math.BigInteger micros = (java.math.BigInteger) order.get(0);
        if (micros.signum() <= 0 || micros.compareTo(java.math.BigInteger.valueOf(ClosureValueSupport.MAX_SAFE_INTEGER)) >= 0)
            throw new IllegalArgumentException("Frontier microseconds outside the safe range");
        ClosureValueSupport.requireBlueId((String) order.get(1), "frontier entry");
    }
}
