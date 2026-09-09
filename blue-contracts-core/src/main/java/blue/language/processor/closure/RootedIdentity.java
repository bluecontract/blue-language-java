package blue.language.processor.closure;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.erdtman.jcs.JsonCanonicalizer;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Closed RCP draft.2 envelope constructors, separate from historical identities.
 * Callers must authenticate the actual history and topology before these hashes
 * can be used as processing authority. A hash alone never supplies that proof.
 */
final class RootedIdentity {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SHA = Pattern.compile("sha256:[0-9a-f]{64}");
    private static final Pattern BLUE = Pattern.compile(
            "[1-9A-HJ-NP-Za-km-z]{32,44}(?:#(?:0|[1-9][0-9]*))?");
    private static final Pattern DECIMAL = Pattern.compile("0|[1-9][0-9]*");
    private static final BigInteger MAX = new BigInteger("9007199254740991");

    private RootedIdentity() { }

    static String history(Map<String, Object> value) {
        closed(value, "documentId", "initialDocumentBlueId", "runtimeSemanticsIdentity", "admission");
        document(value.get("documentId"));
        blue(value.get("initialDocumentBlueId"));
        sha(value.get("runtimeSemanticsIdentity"));
        Map<String, Object> admission = object(value.get("admission"));
        String mode = text(admission.get("mode"));
        if ("FULL_HISTORY".equals(mode)) {
            closed(admission, "mode");
        } else if ("FROM_NOW".equals(mode)) {
            closed(admission, "mode", "lowerExclusiveOrder");
            Map<String, Object> bound = object(admission.get("lowerExclusiveOrder"));
            if ("BEGINNING".equals(bound.get("kind"))) closed(bound, "kind");
            else order(bound);
        } else if ("FROM_FRONTIER".equals(mode)) {
            closed(admission, "mode", "lowerExclusiveOrder");
            order(object(admission.get("lowerExclusiveOrder")));
        } else if ("CREATED_IN_OPERATION".equals(mode)) {
            closed(admission, "mode", "lowerExclusiveOrder", "creatorOperationIdentity", "birthOccurrenceIdentity");
            order(object(admission.get("lowerExclusiveOrder")));
            sha(admission.get("creatorOperationIdentity"));
            sha(admission.get("birthOccurrenceIdentity"));
        } else {
            throw invalid("ADMISSION_MODE");
        }
        return digest("blue-document-history-basis/1.0-draft.2", value);
    }

    static Map<String, Object> owner(Map<String, Object> value) {
        closed(value, "members", "internalEdges");
        List<Map<String, Object>> members = rows(value.get("members"));
        if (members.isEmpty()) throw invalid("OWNER_SETS");
        Set<String> ids = new HashSet<String>();
        for (Map<String, Object> member : members) {
            closed(member, "documentId", "historyBasisIdentity");
            String id = document(member.get("documentId"));
            sha(member.get("historyBasisIdentity"));
            if (!ids.add(id)) throw invalid("DUPLICATE_MEMBER");
        }
        List<Map<String, Object>> edges = rows(value.get("internalEdges"));
        Set<String> occurrences = new HashSet<String>();
        for (Map<String, Object> edge : edges) {
            closed(edge, "occurrenceIdentity", "parentDocumentId", "sourcePath",
                    "childDocumentId", "activationGeneration");
            String occurrence = sha(edge.get("occurrenceIdentity"));
            pointer(edge.get("sourcePath"));
            decimal(edge.get("activationGeneration"));
            if (!ids.contains(document(edge.get("parentDocumentId")))
                    || !ids.contains(document(edge.get("childDocumentId")))) {
                throw invalid("NON_INTERNAL_EDGE");
            }
            if (!occurrences.add(occurrence)) throw invalid("DUPLICATE_EDGE");
        }
        members.sort((a, b) -> compare(text(a.get("documentId")), text(b.get("documentId"))));
        edges.sort((a, b) -> compare(text(a.get("occurrenceIdentity")), text(b.get("occurrenceIdentity"))));
        return object(freeze(map("members", members, "internalEdges", edges)));
    }

    static String ownerIdentity(Map<String, Object> descriptor) {
        return digest("blue-operation-owner/1.0-draft.2", owner(descriptor));
    }

    static Map<String, Object> context(Map<String, Object> descriptor) {
        Map<String, Object> owner = owner(descriptor);
        String root = text(rows(owner.get("members")).get(0).get("documentId"));
        return Collections.unmodifiableMap(map("canonicalRootDocumentId", root,
                "operationOwnerIdentity", ownerIdentity(owner)));
    }

    static String contextIdentity(Map<String, Object> descriptor) {
        return digest("blue-contracts-root-processing-context/1.0-draft.2", context(descriptor));
    }

    static Map<String, Object> delivery(Map<String, Object> value) {
        closed(value, "operationOwnerIdentity", "causeIdentity", "kind", "receivingBindings", "sourcePositionIdentity");
        sha(value.get("operationOwnerIdentity"));
        sha(value.get("causeIdentity"));
        String kind = text(value.get("kind"));
        boolean retained = "MANAGED_REVISION".equals(kind) || "MANAGED_REPRESENTATION".equals(kind);
        if (!retained && !"LIVE".equals(kind) && !"ADMISSION".equals(kind)) throw invalid("KIND");
        List<Map<String, Object>> bindings = rows(value.get("receivingBindings"));
        if (bindings.isEmpty()) throw invalid("RECEIVING_BINDINGS");
        Set<String> seen = new HashSet<String>();
        for (Map<String, Object> binding : bindings) {
            closed(binding, "documentId", "scopePath", "activationGeneration", "channelKey", "occurrenceIdentity");
            document(binding.get("documentId"));
            pointer(binding.get("scopePath"));
            decimal(binding.get("activationGeneration"));
            sha(binding.get("occurrenceIdentity"));
            if (text(binding.get("channelKey")).isEmpty()) throw invalid("CHANNEL_KEY");
            if (!seen.add(new String(canonical(binding), StandardCharsets.UTF_8))) throw invalid("DUPLICATE_BINDING");
        }
        Map<String, Object> position = object(value.get("sourcePositionIdentity"));
        if (retained) {
            closed(position, "kind", "identity");
            if (!"POSITION".equals(position.get("kind"))) throw invalid("POSITION_TAG");
            sha(position.get("identity"));
        } else {
            closed(position, "kind");
            if (!"NONE".equals(position.get("kind"))) throw invalid("POSITION_TAG");
        }
        bindings.sort((a, b) -> compare(canonical(a), canonical(b)));
        Map<String, Object> result = new LinkedHashMap<String, Object>(value);
        result.put("receivingBindings", bindings);
        return object(freeze(result));
    }

    static String deliveryIdentity(Map<String, Object> value) {
        return digest("blue-rooted-delivery-basis/1.0-draft.2", delivery(value));
    }

    static String wrapper(String name, Map<String, Object> value) {
        final String domain;
        if ("rootedInvocationIdentity".equals(name)) {
            closed(value, "baseInvocationIdentity", "rootProcessingContextIdentity", "deliveryBasisIdentity");
            domain = "blue-contracts-rooted-invocation/1.0-draft.2";
        } else if ("rootedCommitCompanionIdentity".equals(name)) {
            closed(value, "baseCommitCompanionIdentity", "rootedInvocationIdentity", "rootProcessingContextIdentity");
            domain = "blue-contracts-rooted-commit-companion/1.0-draft.2";
        } else if ("rootedTerminalKey".equals(name)) {
            closed(value, "operationOwnerIdentity", "deliveryBasisIdentity");
            domain = "blue-coordination-rooted-terminal-key/1.0-draft.2";
        } else {
            throw invalid("CONSTRUCTOR");
        }
        for (Object identity : value.values()) sha(identity);
        return digest(domain, value);
    }

    private static void order(Map<String, Object> value) {
        closed(value, "timestampUs", "timelineBlueId", "entryBlueId");
        decimal(value.get("timestampUs"));
        blue(value.get("timelineBlueId"));
        blue(value.get("entryBlueId"));
    }

    private static void closed(Map<String, Object> value, String... keys) {
        if (value == null || !value.keySet().equals(new HashSet<String>(Arrays.asList(keys)))) {
            throw invalid("CLOSED_FIELDS");
        }
    }

    private static String text(Object value) {
        if (!(value instanceof String)) throw invalid("STRING_REQUIRED");
        String string = (String) value;
        for (int index = 0; index < string.length(); index++) {
            char ch = string.charAt(index);
            if (Character.isHighSurrogate(ch)) {
                if (++index == string.length() || !Character.isLowSurrogate(string.charAt(index))) {
                    throw invalid("LONE_SURROGATE");
                }
            } else if (Character.isLowSurrogate(ch)) {
                throw invalid("LONE_SURROGATE");
            }
        }
        return string;
    }

    private static String sha(Object value) {
        String string = text(value);
        if (!SHA.matcher(string).matches()) throw invalid("SHA_REQUIRED");
        return string;
    }

    private static void blue(Object value) {
        if (!BLUE.matcher(text(value)).matches()) throw invalid("BLUEID_GRAMMAR");
    }

    private static String document(Object value) {
        return new DocumentId(text(value)).value();
    }

    private static void decimal(Object value) {
        String string = text(value);
        if (!DECIMAL.matcher(string).matches() || string.length() > 16
                || new BigInteger(string).compareTo(MAX) > 0) throw invalid("DECIMAL");
    }

    private static void pointer(Object value) {
        String string = text(value);
        if (!string.isEmpty() && !string.startsWith("/")) throw invalid("POINTER");
        for (int index = 0; index < string.length(); index++) {
            if (string.charAt(index) == '~'
                    && (++index == string.length() || (string.charAt(index) != '0' && string.charAt(index) != '1'))) {
                throw invalid("POINTER");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value) {
        if (!(value instanceof Map)) throw invalid("OBJECT_REQUIRED");
        return (Map<String, Object>) value;
    }

    private static List<Map<String, Object>> rows(Object value) {
        if (!(value instanceof List)) throw invalid("ARRAY_REQUIRED");
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Object row : (List<?>) value) result.add(object(row));
        return result;
    }

    private static Object freeze(Object value) {
        if (value instanceof String) return text(value);
        if (value instanceof List) {
            List<Object> result = new ArrayList<Object>();
            for (Object item : (List<?>) value) result.add(freeze(item));
            return Collections.unmodifiableList(result);
        }
        if (value instanceof Map) {
            Map<String, Object> result = new LinkedHashMap<String, Object>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                String key = text(entry.getKey());
                for (int index = 0; index < key.length(); index++) {
                    if (key.charAt(index) > 127) throw invalid("ASCII_KEYS");
                }
                result.put(key, freeze(entry.getValue()));
            }
            return Collections.unmodifiableMap(result);
        }
        throw invalid("ENVELOPE_SUBSET");
    }

    private static byte[] canonical(Object value) {
        try {
            return new JsonCanonicalizer(JSON.writeValueAsString(freeze(value))).getEncodedUTF8();
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot encode rooted identity", failure);
        }
    }

    private static String digest(String domain, Map<String, Object> value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(canonical(map("domain", domain, "value", value)));
            StringBuilder result = new StringBuilder("sha256:");
            for (byte part : bytes) {
                int unsigned = part & 255;
                if (unsigned < 16) result.append('0');
                result.append(Integer.toHexString(unsigned));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 unavailable", failure);
        }
    }

    private static int compare(String left, String right) {
        return compare(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
    }

    private static int compare(byte[] left, byte[] right) {
        for (int index = 0; index < Math.min(left.length, right.length); index++) {
            int delta = (left[index] & 255) - (right[index] & 255);
            if (delta != 0) return delta;
        }
        return Integer.compare(left.length, right.length);
    }

    private static Map<String, Object> map(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], entries[index + 1]);
        }
        return result;
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Invalid rooted identity: " + reason);
    }
}
