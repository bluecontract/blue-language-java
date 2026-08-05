package blue.language.provider;

import blue.language.model.Node;
import blue.language.model.Schema;
import blue.language.identity.BlueIds;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Complete declared placeholder set offered as evidence for a cyclic member.
 *
 * <p>This object deliberately does not attest that the set is valid. A
 * {@link VerifyingNodeProvider} independently calculates the cyclic BlueIds
 * and verifies the returned member body before accepting provider content.</p>
 */
public final class CyclicSetProof {

    private final List<Node> declaredPlaceholderSet;

    private CyclicSetProof(List<Node> declaredPlaceholderSet) {
        if (declaredPlaceholderSet == null || declaredPlaceholderSet.isEmpty()) {
            throw new IllegalArgumentException(
                    "Cyclic-set proof requires a non-empty declared placeholder set.");
        }
        List<Node> retained = new ArrayList<>(declaredPlaceholderSet.size());
        for (Node member : declaredPlaceholderSet) {
            retained.add(Objects.requireNonNull(
                    member, "cyclic-set proof member").clone());
        }
        this.declaredPlaceholderSet = Collections.unmodifiableList(retained);
    }

    /**
     * Retains defensive copies of a complete, non-empty declared placeholder
     * set.
     *
     * @param declaredPlaceholderSet complete ordered placeholder set
     * @return immutable proof container
     * @throws IllegalArgumentException when the set is null or empty
     * @throws NullPointerException when the set contains a null member
     */
    public static CyclicSetProof fromDeclaredPlaceholderSet(
            List<Node> declaredPlaceholderSet) {
        return new CyclicSetProof(declaredPlaceholderSet);
    }

    /**
     * Returns unmodifiable defensive copies of every declared member.
     *
     * @return ordered declared placeholder set
     */
    public List<Node> declaredPlaceholderSet() {
        return defensiveCopies(declaredPlaceholderSet);
    }

    Node resolvedMember(int memberIndex, List<String> calculatedMemberBlueIds) {
        if (memberIndex < 0 || memberIndex >= declaredPlaceholderSet.size()) {
            throw new IllegalArgumentException(
                    "Cyclic-set proof member index is outside the declared set.");
        }
        if (calculatedMemberBlueIds == null
                || calculatedMemberBlueIds.size() != declaredPlaceholderSet.size()) {
            throw new IllegalArgumentException(
                    "Calculated cyclic member identities do not cover the declared set.");
        }
        Node resolved = declaredPlaceholderSet.get(memberIndex).clone();
        resolveThisReferences(resolved, calculatedMemberBlueIds);
        return resolved;
    }

    private static List<Node> defensiveCopies(List<Node> nodes) {
        List<Node> copies = new ArrayList<>(nodes.size());
        for (Node node : nodes) {
            copies.add(node.clone());
        }
        return Collections.unmodifiableList(copies);
    }

    private static void resolveThisReferences(
            Node node,
            List<String> calculatedMemberBlueIds) {
        if (node == null) {
            return;
        }
        String blueId = node.getBlueId();
        if (blueId != null
                && blueId.startsWith(
                BlueIds.THIS_MEMBER_PREFIX)) {
            String indexText = blueId.substring(
                    BlueIds.THIS_MEMBER_PREFIX.length());
            final int targetIndex;
            try {
                targetIndex = Integer.parseInt(indexText);
            } catch (NumberFormatException invalidIndex) {
                throw new IllegalArgumentException(
                        "Invalid cyclic placeholder reference: " + blueId,
                        invalidIndex);
            }
            if (targetIndex < 0 || targetIndex >= calculatedMemberBlueIds.size()) {
                throw new IllegalArgumentException(
                        "Cyclic placeholder reference points outside the declared set: "
                                + blueId);
            }
            node.blueId(calculatedMemberBlueIds.get(targetIndex));
        }
        resolveThisReferences(node.getType(), calculatedMemberBlueIds);
        resolveThisReferences(node.getItemType(), calculatedMemberBlueIds);
        resolveThisReferences(node.getKeyType(), calculatedMemberBlueIds);
        resolveThisReferences(node.getValueType(), calculatedMemberBlueIds);
        resolveThisReferences(node.getBlue(), calculatedMemberBlueIds);
        resolveThisReferences(node.getContracts(), calculatedMemberBlueIds);
        if (node.getItems() != null) {
            for (Node item : node.getItems()) {
                resolveThisReferences(item, calculatedMemberBlueIds);
            }
        }
        if (node.getProperties() != null) {
            for (Node child : node.getProperties().values()) {
                resolveThisReferences(child, calculatedMemberBlueIds);
            }
        }
        resolveThisReferences(node.getSchema(), calculatedMemberBlueIds);
    }

    private static void resolveThisReferences(
            Schema schema,
            List<String> calculatedMemberBlueIds) {
        if (schema == null) {
            return;
        }
        String blueId = schema.getBlueId();
        if (blueId != null
                && blueId.startsWith(
                BlueIds.THIS_MEMBER_PREFIX)) {
            String indexText = blueId.substring(
                    BlueIds.THIS_MEMBER_PREFIX.length());
            final int targetIndex;
            try {
                targetIndex = Integer.parseInt(
                        indexText);
            } catch (NumberFormatException invalidIndex) {
                throw new IllegalArgumentException(
                        "Invalid cyclic placeholder reference: "
                                + blueId,
                        invalidIndex);
            }
            if (targetIndex < 0
                    || targetIndex
                    >= calculatedMemberBlueIds.size()) {
                throw new IllegalArgumentException(
                        "Cyclic placeholder reference points outside "
                                + "the declared set: " + blueId);
            }
            schema.blueId(
                    calculatedMemberBlueIds.get(
                            targetIndex));
        }
        resolveThisReferences(schema.getRequired(), calculatedMemberBlueIds);
        resolveThisReferences(schema.getMinLength(), calculatedMemberBlueIds);
        resolveThisReferences(schema.getMaxLength(), calculatedMemberBlueIds);
        resolveThisReferences(schema.getMinimum(), calculatedMemberBlueIds);
        resolveThisReferences(schema.getMaximum(), calculatedMemberBlueIds);
        resolveThisReferences(
                schema.getExclusiveMinimum(), calculatedMemberBlueIds);
        resolveThisReferences(
                schema.getExclusiveMaximum(), calculatedMemberBlueIds);
        resolveThisReferences(schema.getMultipleOf(), calculatedMemberBlueIds);
        resolveThisReferences(schema.getMinItems(), calculatedMemberBlueIds);
        resolveThisReferences(schema.getMaxItems(), calculatedMemberBlueIds);
        resolveThisReferences(schema.getUniqueItems(), calculatedMemberBlueIds);
        resolveThisReferences(schema.getMinFields(), calculatedMemberBlueIds);
        resolveThisReferences(schema.getMaxFields(), calculatedMemberBlueIds);
        if (schema.getEnum() != null) {
            for (Node value : schema.getEnum()) {
                resolveThisReferences(value, calculatedMemberBlueIds);
            }
        }
    }
}
