package blue.language.processor.closure;

import java.util.*;

/** Closed immutable settlement-identity data. Byte/constructor validity is not execution authority. */
public final class SameOriginGroupEvidence {
    private final String identity;
    private final Map<DocumentId, String> seeds, consumed;
    private final List<Admission> admissions;

    private SameOriginGroupEvidence(String expected, Map<DocumentId, String> seeds,
            List<Admission> admissions, Map<DocumentId, String> consumed) {
        this.seeds = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(seeds)));
        this.consumed = Collections.unmodifiableMap(new TreeMap<>(Objects.requireNonNull(consumed)));
        this.admissions = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(admissions)));
        List<SameOriginGroupIdentity.Admission> accepted = new ArrayList<>();
        for (Admission admission : this.admissions)
            accepted.add(new SameOriginGroupIdentity.Admission(admission.canonicalSite(), admission.members()));
        this.identity = SameOriginGroupIdentity.of(this.seeds, accepted, this.consumed).identity();
        if (!identity.equals(ClosureValueSupport.requireSha256Identity(expected, "groupIdentity")))
            throw new IllegalArgumentException("Group identity differs from original seeds/admissions/dependencies");
    }

    /** Validates a restored constructor; the enclosing committed receipt must authenticate this evidence. */
    public static SameOriginGroupEvidence fromExactEvidence(String identity, Map<DocumentId, String> seeds,
            List<Admission> admissions, Map<DocumentId, String> consumed) {
        return new SameOriginGroupEvidence(identity, seeds, admissions, consumed);
    }

    static SameOriginGroupEvidence fromOperation(SameOriginOperationResult result) {
        List<Admission> accepted = new ArrayList<>();
        for (SameOriginOperationResult.Admission admission : result.admissions())
            accepted.add(new Admission(admission.canonicalSite(), admission.members()));
        return fromExactEvidence(result.operationIdentity(), result.originalSeedByMember(), accepted, result.consumedSourceOperations());
    }

    public String identity() { return identity; }
    public Map<DocumentId, String> originalSeedByMember() { return seeds; }
    public List<Admission> admissions() { return admissions; }
    public Map<DocumentId, String> consumedSourceOperations() { return consumed; }

    public static final class Admission {
        private final String site;
        private final Set<DocumentId> members;
        public Admission(String site, Collection<DocumentId> members) {
            SameOriginGroupIdentity.Admission validated = new SameOriginGroupIdentity.Admission(site, members);
            this.site = validated.canonicalSite(); this.members = validated.members();
        }
        public String canonicalSite() { return site; }
        public Set<DocumentId> members() { return members; }
    }
}
