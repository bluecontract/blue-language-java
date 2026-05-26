package blue.language;

public final class BlueLanguageErrorClassifier {

    private BlueLanguageErrorClassifier() {
    }

    public static BlueLanguageErrorCategory classify(Throwable throwable) {
        if (throwable == null) {
            return BlueLanguageErrorCategory.CanonicalizationError;
        }
        String message = messageChain(throwable);
        String lower = message.toLowerCase();

        if (lower.contains("duplicate key")) {
            return BlueLanguageErrorCategory.DuplicateKey;
        }
        if (lower.contains("provider returned content for")
                || lower.contains("wrong blueid")
                || lower.contains("computed blueid")
                || lower.contains("does not match requested")
                || lower.contains("requested blueid")
                || (lower.contains("requested") && lower.contains("blueid"))) {
            return BlueLanguageErrorCategory.ProviderBlueIdMismatch;
        }
        if (lower.contains("provider returned no content")
                || lower.contains("missing provider content")
                || lower.contains("missing blue language fixture resource")
                || lower.contains("missing fixture resource")) {
            return BlueLanguageErrorCategory.ProviderUnavailable;
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
        if (lower.contains("$pos")
                || lower.contains("$replace")
                || lower.contains("$previous")
                || lower.contains("$empty")
                || lower.contains("list control")
                || lower.contains("positional")
                || lower.contains("append-only")) {
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
        if (lower.contains("schema")
                || lower.contains("minimum")
                || lower.contains("maximum")
                || lower.contains("multiple of")
                || lower.contains("minimum length")
                || lower.contains("maximum length")
                || lower.contains("required node")
                || lower.contains("allowed enum")
                || lower.contains("wrong kind")) {
            return BlueLanguageErrorCategory.SchemaViolation;
        }
        if (lower.contains("fixed value")
                || lower.contains("values must not conflict")
                || lower.contains("value conflict")) {
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
