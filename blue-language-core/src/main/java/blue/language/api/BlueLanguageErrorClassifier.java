package blue.language.api;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.model.wire.JsonPointer;

import java.util.List;
import java.util.Locale;

/**
 * Maps implementation exceptions and diagnostics to the closed Language 1.0
 * error vocabulary used in conformance evidence.
 *
 * <p>The classifier walks the cause chain and deliberately falls back to
 * {@link BlueLanguageErrorCategory#CanonicalizationError} when no narrower
 * category can be proven.</p>
 */
public final class BlueLanguageErrorClassifier {

    private static final String PLAIN_BLUE_ID_PREFIX =
            "Expected canonical Base58 SHA-256 BlueId at ";
    private static final String CYCLIC_BLUE_ID_PREFIX =
            "Invalid cyclic BlueId member syntax at ";
    private static final String THIS_BLUE_ID_PREFIX =
            "\"this\" BlueId placeholders are valid only inside cyclic BlueId calculation APIs. Path: ";

    private BlueLanguageErrorClassifier() {
    }

    /**
     * Classifies a throwable without mutating or rethrowing it.
     *
     * @param throwable failure to classify
     * @return a non-null stable error category
     */
    public static BlueLanguageErrorCategory classify(Throwable throwable) {
        if (throwable == null) {
            return BlueLanguageErrorCategory.CanonicalizationError;
        }
        BlueLanguageErrorCategory malformedBlueId = classifyMalformedBlueId(throwable.getMessage());
        if (malformedBlueId != null) {
            return malformedBlueId;
        }
        String message = messageChain(throwable);
        String lower = message.toLowerCase(Locale.ROOT);

        if (lower.contains("duplicate key")) {
            return BlueLanguageErrorCategory.DuplicateKey;
        }
        if (lower.contains("provider returned reference-only content")
                || lower.contains("provider returned no content")
                || lower.contains("provider unavailable")
                || lower.contains("missing provider content")
                || lower.contains("no content found")
                || lower.contains("missing blue language fixture resource")
                || lower.contains("missing fixture resource")) {
            return BlueLanguageErrorCategory.ProviderUnavailable;
        }
        if (lower.contains("provider returned content for")
                || lower.contains("wrong blueid")
                || lower.contains("computed blueid")
                || lower.contains("does not match requested")
                || lower.contains("requested blueid")
                || (lower.contains("requested") && lower.contains("blueid"))) {
            return BlueLanguageErrorCategory.ProviderBlueIdMismatch;
        }
        if (lower.contains("type cycle")
                || lower.contains("cyclic type")) {
            return BlueLanguageErrorCategory.TypeCycle;
        }
        if (lower.contains("circular")
                || lower.contains("cyclic")
                || lower.contains("preliminary")) {
            return BlueLanguageErrorCategory.CircularSetError;
        }
        if (lower.contains("direct blueid input")
                || lower.contains("blueid input")
                || lower.contains("unresolved alias")
                || lower.contains("type alias")) {
            return BlueLanguageErrorCategory.InvalidBlueIdInput;
        }
        if (lower.contains(BlueLanguageConstants.LIST_CONTROL_POS)
                || lower.contains(BlueLanguageConstants.LIST_CONTROL_REPLACE)
                || lower.contains(BlueLanguageConstants.LIST_CONTROL_PREVIOUS)
                || lower.contains(BlueLanguageConstants.LIST_CONTROL_EMPTY)
                || lower.contains("list control")
                || lower.contains(BlueLanguageConstants.LIST_MERGE_POLICY_POSITIONAL)
                || lower.contains(BlueLanguageConstants.LIST_MERGE_POLICY_APPEND_ONLY)) {
            return BlueLanguageErrorCategory.ListControlViolation;
        }
        if (lower.contains("wrong kind")) {
            return BlueLanguageErrorCategory.SchemaViolation;
        }
        if (lower.contains("\"schema.")
                || lower.contains("schema keyword")
                || lower.contains("schema value")
                || lower.contains("schema.enum")
                || lower.contains("schema min")
                || lower.contains("multipleof must")
                || lower.contains("minimum must")
                || lower.contains("exclusiveminimum must")) {
            return BlueLanguageErrorCategory.SchemaVocabularyError;
        }
        if (lower.contains(BlueLanguageConstants.OBJECT_SCHEMA)
                || lower.contains("minimum")
                || lower.contains("maximum")
                || lower.contains("multiple of")
                || lower.contains("dictionary key")
                || lower.contains("minimum length")
                || lower.contains("maximum length")
                || lower.contains("required node")
                || lower.contains("allowed enum")
                || lower.contains("wrong kind")) {
            return BlueLanguageErrorCategory.SchemaViolation;
        }
        if (lower.contains("fixed value")
                || lower.contains("values must not conflict")
                || lower.contains("value conflict")
                || lower.contains("node values conflict")) {
            return BlueLanguageErrorCategory.FixedValueConflict;
        }
        if (lower.contains("not a subtype")
                || lower.contains("invalid type")
                || lower.contains("incompatible")
                || lower.contains("does not match")
                || lower.contains("itemtype")
                || lower.contains("keytype")
                || lower.contains("valuetype")) {
            return BlueLanguageErrorCategory.TypeCompatibilityViolation;
        }
        if (lower.contains("invalid blueid")
                || lower.contains("not a valid blueid")
                || lower.contains("plain valid blueid")
                || lower.contains("potential blueid")) {
            return BlueLanguageErrorCategory.InvalidBlueId;
        }
        if (lower.contains("reference")
                || lower.contains("blueid with sibling")
                || lower.contains("pure reference")) {
            return BlueLanguageErrorCategory.InvalidReferenceShape;
        }
        if (lower.contains("reserved")
                || lower.contains("\"blue\"")
                || lower.contains("\"properties\"")
                || lower.contains("internal field")) {
            return BlueLanguageErrorCategory.InvalidReservedField;
        }
        if (lower.contains("preprocess")
                || lower.contains("unsupported transform")) {
            return BlueLanguageErrorCategory.UnsupportedPreprocessingTransform;
        }
        if (throwable instanceof com.fasterxml.jackson.core.JsonProcessingException) {
            return BlueLanguageErrorCategory.InvalidSyntax;
        }
        return BlueLanguageErrorCategory.CanonicalizationError;
    }

    private static BlueLanguageErrorCategory classifyMalformedBlueId(String message) {
        String path = malformedBlueIdPath(message);
        if (path == null) {
            return null;
        }
        List<String> segments = JsonPointer.split(path);
        int size = segments.size();
        if (size >= 2
                && BlueLanguageConstants.LIST_CONTROL_PREVIOUS.equals(
                        segments.get(size - 2))
                && BlueLanguageConstants.OBJECT_BLUE_ID.equals(segments.get(size - 1))) {
            return BlueLanguageErrorCategory.ListControlViolation;
        }
        return BlueLanguageErrorCategory.InvalidBlueId;
    }

    private static String malformedBlueIdPath(String message) {
        if (message == null) {
            return null;
        }
        if (message.startsWith(PLAIN_BLUE_ID_PREFIX)) {
            return withoutDiagnosticPeriod(message.substring(PLAIN_BLUE_ID_PREFIX.length()));
        }
        if (message.startsWith(CYCLIC_BLUE_ID_PREFIX)) {
            return withoutDiagnosticPeriod(message.substring(CYCLIC_BLUE_ID_PREFIX.length()));
        }
        if (message.startsWith(THIS_BLUE_ID_PREFIX)) {
            return message.substring(THIS_BLUE_ID_PREFIX.length());
        }
        return null;
    }

    private static String withoutDiagnosticPeriod(String path) {
        return path.endsWith(".") ? path.substring(0, path.length() - 1) : path;
    }

    private static String messageChain(Throwable throwable) {
        StringBuilder builder = new StringBuilder();
        Throwable current = throwable;
        while (current != null) {
            if (current.getMessage() != null) {
                builder.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return builder.toString();
    }
}
