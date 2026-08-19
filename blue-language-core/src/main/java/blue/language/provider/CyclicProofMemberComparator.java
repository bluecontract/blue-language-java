package blue.language.provider;

import blue.language.codec.jackson.UncheckedObjectMapper;
import blue.language.identity.DirectBlueIdCalculator;
import blue.language.model.Node;
import blue.language.model.NodeWireForm;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static blue.language.model.wire.BlueLanguageConstants.OBJECT_BLUE_ID;

/** Representation-neutral comparison of one resolved cyclic proof fragment. */
final class CyclicProofMemberComparator {

    private final DirectBlueIdCalculator directCalculator =
            new DirectBlueIdCalculator();
    private final Set<String> cyclicMemberBlueIds;

    CyclicProofMemberComparator(Set<String> cyclicMemberBlueIds) {
        this.cyclicMemberBlueIds = Objects.requireNonNull(
                cyclicMemberBlueIds, "cyclicMemberBlueIds");
    }

    boolean equivalent(Node proofMember, Node returnedMember) {
        Object expected = NodeWireForm.get(
                Objects.requireNonNull(proofMember, "proofMember"),
                NodeWireForm.Strategy.SIMPLE);
        Object actual = NodeWireForm.get(
                Objects.requireNonNull(returnedMember, "returnedMember"),
                NodeWireForm.Strategy.SIMPLE);
        return equivalent(expected, actual);
    }

    private boolean equivalent(Object expected, Object actual) {
        if (isPureReference(expected)) {
            return referenceMatches(
                    stringMap(expected).get(OBJECT_BLUE_ID), actual);
        }
        if (expected instanceof Map) {
            if (!(actual instanceof Map)) {
                return false;
            }
            Map<String, Object> expectedMap = stringMap(expected);
            Map<String, Object> actualMap = stringMap(actual);
            if (!expectedMap.keySet().equals(actualMap.keySet())) {
                return false;
            }
            for (Map.Entry<String, Object> entry
                    : expectedMap.entrySet()) {
                if (!equivalent(
                        entry.getValue(),
                        actualMap.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (expected instanceof List) {
            if (!(actual instanceof List)) {
                return false;
            }
            List<?> expectedList = (List<?>) expected;
            List<?> actualList = (List<?>) actual;
            if (expectedList.size() != actualList.size()) {
                return false;
            }
            for (int index = 0; index < expectedList.size(); index++) {
                if (!equivalent(
                        expectedList.get(index),
                        actualList.get(index))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(expected, actual);
    }

    private boolean referenceMatches(Object expectedIdentity, Object actual) {
        if (!(expectedIdentity instanceof String)) {
            return false;
        }
        String expectedBlueId = (String) expectedIdentity;
        if (isPureReference(actual)) {
            return expectedBlueId.equals(
                    stringMap(actual).get(OBJECT_BLUE_ID));
        }
        if (cyclicMemberBlueIds.contains(expectedBlueId)) {
            // A separately fetched cyclic member is the only complete proof
            // boundary.  Expanding another member here would require that
            // member's proof recursively, so it is not accepted as a direct
            // off-cycle fragment.
            return false;
        }
        try {
            Node materialized = UncheckedObjectMapper.JSON_MAPPER
                    .convertValue(actual, Node.class);
            String carriedIdentity = materialized.getBlueId();
            if (carriedIdentity != null) {
                if (!expectedBlueId.equals(carriedIdentity)) {
                    return false;
                }
                materialized.blueId(null);
            }
            return expectedBlueId.equals(
                    directCalculator.directBlueId(materialized));
        } catch (RuntimeException invalidMaterialization) {
            return false;
        }
    }

    private static boolean isPureReference(Object value) {
        if (!(value instanceof Map)) {
            return false;
        }
        Map<String, Object> map = stringMap(value);
        return map.size() == 1
                && map.get(OBJECT_BLUE_ID) instanceof String;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringMap(Object value) {
        return (Map<String, Object>) value;
    }
}
