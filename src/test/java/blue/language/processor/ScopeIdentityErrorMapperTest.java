package blue.language.processor;

import blue.language.BlueLanguageErrorCategory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScopeIdentityErrorMapperTest {

    @Test
    void preservesProviderCategoriesFromLanguageCategories() {
        assertEquals(ProcessorErrorCategory.ProviderUnavailable,
                ScopeIdentityErrorMapper.from(BlueLanguageErrorCategory.ProviderUnavailable));
        assertEquals(ProcessorErrorCategory.ProviderBlueIdMismatch,
                ScopeIdentityErrorMapper.from(BlueLanguageErrorCategory.ProviderBlueIdMismatch));
    }

    @Test
    void classifiesProviderFailuresFromThrowables() {
        assertEquals(ProcessorErrorCategory.ProviderUnavailable,
                ScopeIdentityErrorMapper.from(
                        new IllegalStateException("No content found for blueId: missing")));
        assertEquals(ProcessorErrorCategory.ProviderBlueIdMismatch,
                ScopeIdentityErrorMapper.from(
                        new IllegalArgumentException(
                                "Provider returned content for requested BlueId but computed BlueId differs")));
    }

    @Test
    void mapsOtherLanguageFailuresToInternalProcessorError() {
        assertEquals(ProcessorErrorCategory.InternalProcessorError,
                ScopeIdentityErrorMapper.from(BlueLanguageErrorCategory.CanonicalizationError));
        assertEquals(ProcessorErrorCategory.InternalProcessorError,
                ScopeIdentityErrorMapper.from(BlueLanguageErrorCategory.InvalidBlueIdInput));
        assertEquals(ProcessorErrorCategory.InternalProcessorError,
                ScopeIdentityErrorMapper.from((BlueLanguageErrorCategory) null));
        assertEquals(ProcessorErrorCategory.InternalProcessorError,
                ScopeIdentityErrorMapper.from((Throwable) null));
    }
}
