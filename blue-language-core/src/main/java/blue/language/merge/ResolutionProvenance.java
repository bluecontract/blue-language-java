package blue.language.merge;

/**
 * Immutable provenance attached to one resolver-produced snapshot pair.
 *
 * <p>Most resolutions do not qualify as verified standalone reference
 * evidence. In that case {@link #verifiedReferenceResolution()} returns
 * {@code null}, preserving the existing fail-closed cache boundary.</p>
 */
public final class ResolutionProvenance {

    private static final ResolutionProvenance NONE =
            new ResolutionProvenance(null);

    private final VerifiedReferenceResolution verifiedReferenceResolution;

    private ResolutionProvenance(
            VerifiedReferenceResolution verifiedReferenceResolution) {
        this.verifiedReferenceResolution = verifiedReferenceResolution;
    }

    /** Returns provenance with no cache-admissible reference evidence. */
    public static ResolutionProvenance none() {
        return NONE;
    }

    /** Returns provenance carrying resolver-issued reference evidence. */
    static ResolutionProvenance verified(
            VerifiedReferenceResolution verifiedReferenceResolution) {
        if (verifiedReferenceResolution == null) {
            return NONE;
        }
        return new ResolutionProvenance(verifiedReferenceResolution);
    }

    /** Returns verified reference evidence, or {@code null} when ineligible. */
    public VerifiedReferenceResolution verifiedReferenceResolution() {
        return verifiedReferenceResolution;
    }
}
