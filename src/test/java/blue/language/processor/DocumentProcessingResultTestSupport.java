package blue.language.processor;

import blue.language.Blue;
import blue.language.snapshot.ResolvedSnapshot;
import blue.language.utils.BlueIdCalculator;

public final class DocumentProcessingResultTestSupport {

    private DocumentProcessingResultTestSupport() {
    }

    public static String diagnosticMessage(DocumentProcessingResult result) {
        return result != null && result.diagnostic() != null
                ? result.diagnostic().message()
                : null;
    }

    public static ProcessorErrorCategory diagnosticCategory(
            DocumentProcessingResult result) {
        return result != null && result.diagnostic() != null
                ? result.diagnostic().category()
                : null;
    }

    public static boolean isCapabilityFailure(DocumentProcessingResult result) {
        return result != null
                && result.status()
                == ProcessorStatus.CAPABILITY_FAILURE;
    }

    public static String documentBlueId(DocumentProcessingResult result) {
        return result != null
                ? BlueIdCalculator.calculateBlueId(result.document())
                : null;
    }

    public static ResolvedSnapshot snapshot(
            Blue blue,
            DocumentProcessingResult result) {
        return blue.loadSnapshot(result.document());
    }

    public static blue.language.model.Node resolvedDocument(
            Blue blue,
            DocumentProcessingResult result) {
        return snapshot(blue, result).resolvedRoot();
    }
}
