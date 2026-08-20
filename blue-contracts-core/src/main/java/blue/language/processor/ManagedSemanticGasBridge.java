package blue.language.processor;

import java.util.Objects;

/**
 * Narrow attribution-bound semantic gas surface for closure finalization.
 *
 * <p>The bridge exposes only normative formulas needed to establish document
 * and component identities. It cannot replace the session meter, alter a gas
 * policy, clear semantic memoization, or open an independent ledger.</p>
 */
public final class ManagedSemanticGasBridge {

    private final SemanticGasMeter semantic;
    private final GasSchedule schedule;
    private final GasChargeContext attribution;

    ManagedSemanticGasBridge(
            SemanticGasMeter semantic,
            GasSchedule schedule,
            GasChargeContext attribution) {
        this.semantic = Objects.requireNonNull(semantic, "semantic");
        this.schedule = Objects.requireNonNull(schedule, "schedule");
        this.attribution = Objects.requireNonNull(
                attribution, "attribution");
    }

    /**
     * Charges the first semantic opening of one exact node manifest.
     *
     * @param nodeBlueId exact node identity
     * @return whether this invocation opened the manifest for the first time
     */
    public boolean openNodeManifest(String nodeBlueId) {
        return semantic.openNodeManifest(nodeBlueId, attribution);
    }

    /**
     * Charges established exact node identities.
     *
     * @param quantity non-negative identity count
     */
    public void nodeIdentitiesEstablished(long quantity) {
        semantic.nodeIdentitiesEstablished(quantity, attribution);
    }

    /**
     * Charges rebuilt direct object members.
     *
     * @param quantity non-negative rebuilt-member count
     */
    public void objectMembersRebuilt(long quantity) {
        semantic.objectMembersRebuilt(quantity, attribution);
    }

    /**
     * Charges all fold steps of a complete list identity.
     *
     * @param resultLength non-negative resulting list length
     */
    public void fullListIdentity(long resultLength) {
        semantic.fullListIdentity(resultLength, attribution);
    }

    /**
     * Charges canonical direct-identity input bytes using the bound formula.
     *
     * @param canonicalUtf8Bytes non-negative canonical byte count
     */
    public void directIdentityInput(long canonicalUtf8Bytes) {
        semantic.directIdentityInput(canonicalUtf8Bytes, attribution);
    }

    /**
     * Charges already-counted stable-sort comparisons.
     *
     * @param quantity non-negative comparator count
     */
    public void sortComparisons(long quantity) {
        semantic.sortComparisons(quantity, attribution);
    }

    /**
     * Charges already-counted scalar comparisons.
     *
     * @param quantity non-negative comparison count
     */
    public void scalarComparisons(long quantity) {
        semantic.scalarComparisons(quantity, attribution);
    }

    /**
     * Charges examination of logical Unicode code points.
     *
     * @param codePointCount non-negative code-point count
     */
    public void textCodePointsExamined(long codePointCount) {
        semantic.textCodePointsExamined(codePointCount, attribution);
    }

    /**
     * Charges the same examined prefix for several Text operands as one
     * canonical trace entry. Block rounding is applied per operand.
     *
     * @param codePointCountPerOperand non-negative examined prefix length
     * @param operandCount non-negative operand count
     */
    public void textOperandsExamined(
            long codePointCountPerOperand,
            long operandCount) {
        semantic.textOperandsExamined(
                codePointCountPerOperand,
                operandCount,
                attribution);
    }

    /**
     * Charges construction of logical Unicode code points.
     *
     * @param codePointCount non-negative code-point count
     */
    public void textCodePointsConstructed(long codePointCount) {
        semantic.textCodePointsConstructed(codePointCount, attribution);
    }

    /**
     * Resolves one exact portable limit from this session's bound manifest.
     *
     * @param limitName exact manifest limit name
     * @return non-negative configured limit
     */
    public long portableLimit(String limitName) {
        return schedule.portableLimit(
                Objects.requireNonNull(limitName, "limitName"));
    }
}
