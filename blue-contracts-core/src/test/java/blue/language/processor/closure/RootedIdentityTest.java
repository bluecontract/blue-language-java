package blue.language.processor.closure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Independently supplied envelope vectors; these tests do not authenticate histories. */
class RootedIdentityTest {
    private final ObjectMapper json = new ObjectMapper();

    @TestFactory
    Stream<DynamicTest> matchesEveryFrozenDraftTwoVector() throws IOException {
        List<Map<String, Object>> vectors = vectors();
        assertEquals(14, vectors.size());
        return vectors.stream().map(vector -> DynamicTest.dynamicTest((String) vector.get("id"), () -> {
            Map<String, Object> input = object(vector.get("input"));
            String constructor = (String) vector.get("constructor");
            String actual;
            if ("history".equals(constructor)) {
                actual = RootedIdentity.history(input);
            } else if ("owner".equals(constructor)) {
                assertEquals(vector.get("canonicalValue"), RootedIdentity.owner(input));
                actual = RootedIdentity.ownerIdentity(input);
            } else if ("context".equals(constructor)) {
                assertEquals(vector.get("canonicalValue"), RootedIdentity.context(input));
                actual = RootedIdentity.contextIdentity(input);
            } else if ("delivery".equals(constructor)) {
                assertEquals(vector.get("canonicalValue"), RootedIdentity.delivery(input));
                actual = RootedIdentity.deliveryIdentity(input);
            } else {
                assertEquals("wrapper", constructor);
                actual = RootedIdentity.wrapper((String) vector.get("name"), input);
            }
            assertEquals(vector.get("expected"), actual);
        }));
    }

    @Test
    void rejectsMalformedHistoryAndUnaccountedFields() throws IOException {
        Map<String, Object> history = input("HISTORY-A");
        history.put("worker", "cache-1");
        assertThrows(IllegalArgumentException.class, () -> RootedIdentity.history(history));
        history.remove("worker");
        history.put("documentId", "e\u0301");
        assertThrows(IllegalArgumentException.class, () -> RootedIdentity.history(history));
        history.put("documentId", "\uD800");
        assertThrows(IllegalArgumentException.class, () -> RootedIdentity.history(history));
        history.put("documentId", "A");
        history.put("runtimeSemanticsIdentity", Boolean.TRUE);
        assertThrows(IllegalArgumentException.class, () -> RootedIdentity.history(history));
    }

    @Test
    void ownerIgnoresSetOrderButRejectsDuplicateOrExternalMembers() throws IOException {
        Map<String, Object> owner = input("OWNER-ordered");
        String identity = RootedIdentity.ownerIdentity(owner);
        List<Map<String, Object>> members = rows(owner.get("members"));
        Collections.reverse(members);
        assertEquals(identity, RootedIdentity.ownerIdentity(owner));
        members.add(members.get(0));
        assertThrows(IllegalArgumentException.class, () -> RootedIdentity.ownerIdentity(owner));
        members.remove(members.size() - 1);
        rows(owner.get("internalEdges")).get(0).put("childDocumentId", "outside");
        assertThrows(IllegalArgumentException.class, () -> RootedIdentity.ownerIdentity(owner));
    }

    @Test
    void distinctCauseAndOccurrenceGenerationRemainDistinct() throws IOException {
        Map<String, Object> delivery = input("DELIVERY");
        String original = RootedIdentity.deliveryIdentity(delivery);
        delivery.put("causeIdentity", "sha256:" + repeat('f', 64));
        assertNotEquals(original, RootedIdentity.deliveryIdentity(delivery));
        Map<String, Object> owner = input("OWNER-ordered");
        String originalOwner = RootedIdentity.ownerIdentity(owner);
        rows(owner.get("internalEdges")).get(0).put("activationGeneration", "2");
        assertNotEquals(originalOwner, RootedIdentity.ownerIdentity(owner));
    }

    @Test
    void deliveryRejectsDuplicateRecipientsAndLivePositionEvidence() throws IOException {
        Map<String, Object> delivery = input("DELIVERY");
        List<Map<String, Object>> rows = rows(delivery.get("receivingBindings"));
        rows.add(new LinkedHashMap<String, Object>(rows.get(0)));
        assertThrows(IllegalArgumentException.class, () -> RootedIdentity.deliveryIdentity(delivery));
        rows.remove(rows.size() - 1);
        delivery.put("kind", "LIVE");
        Map<String, Object> position = new LinkedHashMap<String, Object>();
        position.put("kind", "POSITION");
        position.put("identity", "sha256:" + repeat('a', 64));
        delivery.put("sourcePositionIdentity", position);
        assertThrows(IllegalArgumentException.class, () -> RootedIdentity.deliveryIdentity(delivery));
    }

    @Test
    void beginningIsClosedFromNowOnlyAndDistinctFromOtherAdmissions() throws IOException {
        Map<String, Object> history = input("HISTORY-FROM_NOW-BEGINNING");
        String beginning = RootedIdentity.history(history);
        assertNotEquals(RootedIdentity.history(input("HISTORY-A")), beginning);
        assertNotEquals(RootedIdentity.history(input("HISTORY-FROM_NOW")), beginning);
        Map<String, Object> admission = object(history.get("admission"));
        Map<String, Object> bound = object(admission.get("lowerExclusiveOrder"));
        bound.put("timestampUs", "0");
        assertThrows(IllegalArgumentException.class, () -> RootedIdentity.history(history));
        bound.remove("timestampUs");
        bound.put("kind", "beginning");
        assertThrows(IllegalArgumentException.class, () -> RootedIdentity.history(history));
        bound.put("kind", "BEGINNING");
        admission.put("mode", "FROM_FRONTIER");
        assertThrows(IllegalArgumentException.class, () -> RootedIdentity.history(history));
        admission.put("mode", "CREATED_IN_OPERATION");
        admission.put("creatorOperationIdentity", "sha256:" + repeat('a', 64));
        admission.put("birthOccurrenceIdentity", "sha256:" + repeat('b', 64));
        assertThrows(IllegalArgumentException.class, () -> RootedIdentity.history(history));
    }

    private List<Map<String, Object>> vectors() throws IOException {
        try (java.io.InputStream input = getClass().getResourceAsStream("/rooted/identity-vectors.json")) {
            if (input == null) throw new IOException("Missing frozen identity vectors");
            Map<String, Object> document = json.readValue(input, new TypeReference<Map<String, Object>>() { });
            return rows(document.get("vectors"));
        }
    }

    private Map<String, Object> input(String id) throws IOException {
        for (Map<String, Object> vector : vectors()) {
            if (id.equals(vector.get("id"))) return object(vector.get("input"));
        }
        throw new IllegalArgumentException("Missing frozen vector " + id);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> rows(Object value) { return (List<Map<String, Object>>) value; }

    private static String repeat(char value, int length) {
        char[] chars = new char[length];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }
}
