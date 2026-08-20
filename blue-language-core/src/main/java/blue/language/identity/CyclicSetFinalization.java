package blue.language.identity;

import blue.language.model.Node;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Complete immutable result of one canonical cyclic-set finalization.
 *
 * <p>The result retains both caller-input and canonical member order, the
 * normalized canonical member-input bytes, and every member's
 * input-to-canonical mapping. Mutable {@link Node} bodies and byte arrays are
 * never exposed.</p>
 */
public final class CyclicSetFinalization {

    private final String masterBlueId;
    private final List<CyclicMemberFinalization> membersInInputOrder;
    private final List<CyclicMemberFinalization> membersInCanonicalOrder;
    private final byte[] canonicalIdentityInputBytes;

    CyclicSetFinalization(
            String masterBlueId,
            List<CyclicMemberFinalization> membersInInputOrder,
            List<CyclicMemberFinalization> membersInCanonicalOrder,
            byte[] canonicalIdentityInputBytes) {
        this.masterBlueId = Objects.requireNonNull(
                masterBlueId, "masterBlueId");
        this.membersInInputOrder = immutableMembers(
                membersInInputOrder, "membersInInputOrder");
        this.membersInCanonicalOrder = immutableMembers(
                membersInCanonicalOrder, "membersInCanonicalOrder");
        if (this.membersInInputOrder.isEmpty()
                || this.membersInInputOrder.size()
                != this.membersInCanonicalOrder.size()) {
            throw new IllegalArgumentException(
                    "Cyclic finalization member orders must be non-empty and complete");
        }
        validateOrders();
        this.canonicalIdentityInputBytes = Objects.requireNonNull(
                canonicalIdentityInputBytes,
                "canonicalIdentityInputBytes").clone();
    }

    /**
     * Returns the final cyclic-set master BlueId.
     *
     * @return final master identity without a member suffix
     */
    public String masterBlueId() {
        return masterBlueId;
    }

    /**
     * Returns immutable member results in caller-supplied order.
     *
     * @return complete input-order member mapping
     */
    public List<CyclicMemberFinalization> membersInInputOrder() {
        return membersInInputOrder;
    }

    /**
     * Returns immutable member results in canonical master-fold order.
     *
     * @return complete canonical member sequence
     */
    public List<CyclicMemberFinalization> membersInCanonicalOrder() {
        return membersInCanonicalOrder;
    }

    /**
     * Returns final member BlueIds in the same order as the supplied input.
     *
     * @return immutable final member identity list
     */
    public List<String> memberBlueIdsInInputOrder() {
        List<String> result = new ArrayList<>(
                membersInInputOrder.size());
        for (CyclicMemberFinalization member : membersInInputOrder) {
            result.add(member.finalBlueId());
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Returns canonical {@code this#n} member bodies in master-fold order.
     *
     * <p>The returned list is unmodifiable and every element is a defensive
     * mutable copy suitable for constructing independent proof evidence.</p>
     *
     * @return defensive canonical member-body list
     */
    public List<Node> canonicalMemberBodies() {
        List<Node> result = new ArrayList<>(
                membersInCanonicalOrder.size());
        for (CyclicMemberFinalization member
                : membersInCanonicalOrder) {
            result.add(member.canonicalMemberBody());
        }
        return Collections.unmodifiableList(result);
    }

    /**
     * Returns the RFC 8785 encoding of the normalized canonical member input.
     *
     * <p>The Language list identity algorithm recursively folds this input;
     * the returned evidence is not a Contracts portable-limit projection.</p>
     *
     * @return defensive canonical-byte copy
     */
    public byte[] canonicalIdentityInputBytes() {
        return canonicalIdentityInputBytes.clone();
    }

    /**
     * Returns the byte count of the normalized canonical member input.
     *
     * @return canonical identity-input byte count
     */
    public long canonicalIdentityInputByteCount() {
        return canonicalIdentityInputBytes.length;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof CyclicSetFinalization)) {
            return false;
        }
        CyclicSetFinalization that = (CyclicSetFinalization) other;
        return masterBlueId.equals(that.masterBlueId)
                && membersInInputOrder.equals(
                        that.membersInInputOrder)
                && membersInCanonicalOrder.equals(
                        that.membersInCanonicalOrder)
                && Arrays.equals(
                        canonicalIdentityInputBytes,
                        that.canonicalIdentityInputBytes);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(
                masterBlueId,
                membersInInputOrder,
                membersInCanonicalOrder);
        result = 31 * result
                + Arrays.hashCode(canonicalIdentityInputBytes);
        return result;
    }

    private static List<CyclicMemberFinalization> immutableMembers(
            List<CyclicMemberFinalization> source,
            String field) {
        Objects.requireNonNull(source, field);
        List<CyclicMemberFinalization> result = new ArrayList<>(
                source.size());
        for (CyclicMemberFinalization member : source) {
            result.add(Objects.requireNonNull(
                    member, field + " member"));
        }
        return Collections.unmodifiableList(result);
    }

    private void validateOrders() {
        boolean[] inputIndexes = new boolean[
                membersInInputOrder.size()];
        boolean[] canonicalIndexes = new boolean[
                membersInCanonicalOrder.size()];
        for (int index = 0;
             index < membersInInputOrder.size();
             index++) {
            CyclicMemberFinalization member =
                    membersInInputOrder.get(index);
            if (member.inputIndex() != index
                    || member.canonicalIndex()
                    >= canonicalIndexes.length
                    || canonicalIndexes[member.canonicalIndex()]) {
                throw new IllegalArgumentException(
                        "Invalid cyclic input-order member mapping");
            }
            canonicalIndexes[member.canonicalIndex()] = true;
        }
        for (int index = 0;
             index < membersInCanonicalOrder.size();
             index++) {
            CyclicMemberFinalization member =
                    membersInCanonicalOrder.get(index);
            if (member.canonicalIndex() != index
                    || member.inputIndex() >= inputIndexes.length
                    || inputIndexes[member.inputIndex()]
                    || member != membersInInputOrder.get(
                            member.inputIndex())) {
                throw new IllegalArgumentException(
                        "Invalid cyclic canonical-order member mapping");
            }
            inputIndexes[member.inputIndex()] = true;
        }
    }
}
