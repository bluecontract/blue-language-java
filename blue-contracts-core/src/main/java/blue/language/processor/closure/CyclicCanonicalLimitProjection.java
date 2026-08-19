package blue.language.processor.closure;

import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.identity.BlueIds;
import blue.language.identity.CircularSetIdentityCalculator;
import blue.language.identity.CyclicSetFinalization;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;
import org.erdtman.jcs.JsonCanonicalizer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static blue.language.model.wire.BlueLanguageConstants.OBJECT_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_DESCRIPTION;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_NAME;
import static blue.language.model.wire.BlueLanguageConstants.OBJECT_VALUE;

/**
 * Contracts representation-neutral cyclic proof and portable-byte projection.
 *
 * <p>Language finalization continues to hash complete canonical member bodies.
 * This projection retains each {@code this#n} path and literal
 * {@code name}/{@code description}/{@code value} member, while replacing an
 * off-cycle non-literal subtree with its ordinary exact reference. The
 * collapsed form is always the portable
 * {@code cyclicCanonicalBytesPerComponent} observation. It is retained as
 * complete proof evidence only when an independent Language finalization
 * reproduces the exact finalized master and complete member-ID set; otherwise
 * the full canonical placeholder members are the proof.</p>
 */
final class CyclicCanonicalLimitProjection {
    private final DirectBlueIdCalculator directCalculator;

    CyclicCanonicalLimitProjection() {
        this(new DirectBlueIdCalculator());
    }

    CyclicCanonicalLimitProjection(
            DirectBlueIdCalculator directCalculator) {
        this.directCalculator = Objects.requireNonNull(
                directCalculator, "directCalculator");
    }

    Projection project(CyclicSetFinalization finalization) {
        CyclicSetFinalization selected = Objects.requireNonNull(
                finalization, "finalization");
        ArrayList<Object> wireMembers = new ArrayList<Object>();
        ArrayList<Node> projectedProofMembers = new ArrayList<Node>();
        for (Node member : selected.canonicalMemberBodies()) {
            Object collapsed = collapse(NodeWireForm.get(
                    member, NodeWireForm.Strategy.SIMPLE));
            wireMembers.add(collapsed);
            projectedProofMembers.add(
                    UncheckedObjectMapper.JSON_MAPPER.convertValue(
                    collapsed, Node.class));
        }
        List<Node> proofMembers = proofPreservingFinalizedMaster(
                selected, projectedProofMembers);
        return new Projection(
                proofMembers,
                canonicalByteCount(wireMembers));
    }

    /**
     * Uses the compact proof only when it independently reproduces the
     * already-finalized Language master. Some resolved representations carry
     * metadata whose wire collapse is useful for the portable byte
     * observation but is not an identity-equivalent cyclic input. In that
     * case the complete canonical placeholder set is the exact proof.
     */
    private List<Node> proofPreservingFinalizedMaster(
            CyclicSetFinalization finalized,
            List<Node> projected) {
        try {
            CyclicSetFinalization calculated =
                    new CircularSetIdentityCalculator()
                            .finalizeCyclicSet(projected);
            if (sameMemberIdentitySet(finalized, calculated)) {
                return projected;
            }
        } catch (RuntimeException invalidProjection) {
            // The full canonical set below remains the authoritative proof.
        }
        List<Node> canonical = finalized.canonicalMemberBodies();
        CyclicSetFinalization verified =
                new CircularSetIdentityCalculator()
                        .finalizeCyclicSet(canonical);
        if (!sameMemberIdentitySet(finalized, verified)) {
            throw new IllegalStateException(
                    "Language canonical cyclic proof changed finalized member identities");
        }
        return canonical;
    }

    static boolean sameMemberIdentitySet(
            CyclicSetFinalization expected,
            CyclicSetFinalization actual) {
        return sameMemberIdentitySet(
                expected.masterBlueId(),
                expected.memberBlueIdsInInputOrder(),
                actual.masterBlueId(),
                actual.memberBlueIdsInInputOrder());
    }

    static boolean sameMemberIdentitySet(
            String expectedMaster,
            List<String> expectedIds,
            String actualMaster,
            List<String> actualIds) {
        Objects.requireNonNull(expectedMaster, "expectedMaster");
        Objects.requireNonNull(expectedIds, "expectedIds");
        Objects.requireNonNull(actualMaster, "actualMaster");
        Objects.requireNonNull(actualIds, "actualIds");
        HashSet<String> expectedSet = new HashSet<String>(expectedIds);
        HashSet<String> actualSet = new HashSet<String>(actualIds);
        return expectedMaster.equals(actualMaster)
                && expectedSet.size() == expectedIds.size()
                && actualSet.size() == actualIds.size()
                && expectedSet.equals(actualSet);
    }

    /** Returns only the collapsed portable-byte observation for an existing
     * complete proof. Proof selection is deliberately owned by
     * {@link #project(CyclicSetFinalization)}. */
    long canonicalBytesForProof(List<Node> declaredPlaceholderSet) {
        ArrayList<Object> wireMembers = new ArrayList<Object>();
        for (Node member : Objects.requireNonNull(
                declaredPlaceholderSet, "declaredPlaceholderSet")) {
            Object collapsed = collapse(NodeWireForm.get(
                    Objects.requireNonNull(member, "proof member"),
                    NodeWireForm.Strategy.SIMPLE));
            wireMembers.add(collapsed);
        }
        return canonicalByteCount(wireMembers);
    }

    private Object collapse(Object value) {
        if (value instanceof List) {
            ArrayList<Object> result = new ArrayList<Object>();
            for (Object item : (List<?>) value) {
                result.add(containsCyclicReference(item)
                        ? collapse(item)
                        : exactReference(item));
            }
            return result;
        }
        if (value instanceof Map) {
            Map<String, Object> object = stringMap(value);
            if (object.size() == 1
                    && object.containsKey(OBJECT_BLUE_ID)) {
                return portableCopy(object);
            }
            LinkedHashMap<String, Object> result =
                    new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                String key = entry.getKey();
                Object child = entry.getValue();
                if (isLiteralField(key)) {
                    result.put(key, portableCopy(child));
                } else if (containsCyclicReference(child)) {
                    result.put(key, collapse(child));
                } else {
                    result.put(key, exactReference(child));
                }
            }
            return result;
        }
        return portableCopy(value);
    }

    private Map<String, Object> exactReference(Object value) {
        Node node = UncheckedObjectMapper.JSON_MAPPER.convertValue(
                value, Node.class);
        LinkedHashMap<String, Object> reference =
                new LinkedHashMap<String, Object>();
        reference.put(
                OBJECT_BLUE_ID,
                directCalculator.directBlueId(node));
        return reference;
    }

    private static boolean containsCyclicReference(Object value) {
        if (value instanceof List) {
            for (Object item : (List<?>) value) {
                if (containsCyclicReference(item)) {
                    return true;
                }
            }
            return false;
        }
        if (value instanceof Map) {
            for (Map.Entry<String, Object> entry
                    : stringMap(value).entrySet()) {
                if (OBJECT_BLUE_ID.equals(entry.getKey())
                        && entry.getValue() instanceof String
                        && isIndexedThisReference(
                                (String) entry.getValue())) {
                    return true;
                }
                if (containsCyclicReference(entry.getValue())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isIndexedThisReference(String value) {
        int separator = BlueIds.cyclicMemberSeparatorIndex(value);
        return separator == BlueIds.THIS_PLACEHOLDER.length()
                && BlueIds.THIS_PLACEHOLDER.equals(
                BlueIds.cyclicSetMasterBlueId(value));
    }

    private static boolean isLiteralField(String key) {
        return OBJECT_NAME.equals(key)
                || OBJECT_DESCRIPTION.equals(key)
                || OBJECT_VALUE.equals(key);
    }

    private static long canonicalByteCount(List<Object> values) {
        try {
            String json = UncheckedObjectMapper.JSON_MAPPER
                    .writeValueAsString(values);
            return new JsonCanonicalizer(json).getEncodedUTF8().length;
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Unable to canonicalize cyclic Contracts limit form",
                    exception);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringMap(Object value) {
        return (Map<String, Object>) value;
    }

    private static Object portableCopy(Object value) {
        if (value instanceof List) {
            ArrayList<Object> result = new ArrayList<Object>();
            for (Object item : (List<?>) value) {
                result.add(portableCopy(item));
            }
            return result;
        }
        if (value instanceof Map) {
            LinkedHashMap<String, Object> result =
                    new LinkedHashMap<String, Object>();
            for (Map.Entry<String, Object> entry
                    : stringMap(value).entrySet()) {
                result.put(entry.getKey(), portableCopy(entry.getValue()));
            }
            return result;
        }
        return value;
    }

    /** Immutable result used at every Contracts cyclic representation edge. */
    static final class Projection {
        private final List<Node> proofMembers;
        private final long canonicalBytes;

        private Projection(List<Node> proofMembers, long canonicalBytes) {
            ArrayList<Node> retained = new ArrayList<Node>();
            for (Node member : proofMembers) {
                retained.add(Objects.requireNonNull(
                        member, "proof member").clone());
            }
            this.proofMembers = Collections.unmodifiableList(retained);
            this.canonicalBytes = ClosureValueSupport.requirePositiveSafeInteger(
                    canonicalBytes, "canonicalBytes");
        }

        List<Node> proofMembers() {
            ArrayList<Node> result = new ArrayList<Node>();
            for (Node member : proofMembers) {
                result.add(member.clone());
            }
            return Collections.unmodifiableList(result);
        }

        long canonicalBytes() {
            return canonicalBytes;
        }
    }
}
