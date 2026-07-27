package blue.language.processor;

import blue.language.BlueLanguageErrorCategory;
import blue.language.BlueLanguageErrorClassifier;

/**
 * Maps Blue Language failures raised while calculating scope identity to the
 * processor diagnostic categories exposed by Blue Contracts conformance.
 */
final class ScopeIdentityErrorMapper {

    private ScopeIdentityErrorMapper() {
    }

    static ProcessorErrorCategory from(Throwable failure) {
        return from(BlueLanguageErrorClassifier.classify(failure));
    }

    static boolean isProviderIdentityFailure(Throwable failure) {
        BlueLanguageErrorCategory category =
                BlueLanguageErrorClassifier.classify(failure);
        return category == BlueLanguageErrorCategory.ProviderUnavailable
                || category
                == BlueLanguageErrorCategory.ProviderBlueIdMismatch;
    }

    static ProcessorErrorCategory from(BlueLanguageErrorCategory category) {
        if (category == BlueLanguageErrorCategory.ProviderUnavailable) {
            return ProcessorErrorCategory.RuntimeExecutionFailure;
        }
        if (category == BlueLanguageErrorCategory.ProviderBlueIdMismatch) {
            return ProcessorErrorCategory.InvalidProcessingDocument;
        }
        return ProcessorErrorCategory.RuntimeExecutionFailure;
    }
}
