package blue.language.processor;

import blue.language.BlueLanguageErrorCategory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopeIdentityErrorMapperTest {

    @Test
    void preservesProviderCategoriesFromLanguageCategories() {
        assertEquals(ProcessorErrorCategory.RuntimeExecutionFailure,
                ScopeIdentityErrorMapper.from(BlueLanguageErrorCategory.ProviderUnavailable));
        assertEquals(ProcessorErrorCategory.InvalidProcessingDocument,
                ScopeIdentityErrorMapper.from(BlueLanguageErrorCategory.ProviderBlueIdMismatch));
    }

    @Test
    void classifiesProviderFailuresFromThrowables() {
        IllegalStateException unavailable =
                new IllegalStateException(
                        "No content found for blueId: missing");
        IllegalArgumentException mismatch =
                new IllegalArgumentException(
                        "Provider returned content for requested BlueId but computed BlueId differs");
        assertEquals(ProcessorErrorCategory.RuntimeExecutionFailure,
                ScopeIdentityErrorMapper.from(unavailable));
        assertEquals(ProcessorErrorCategory.InvalidProcessingDocument,
                ScopeIdentityErrorMapper.from(mismatch));
        assertTrue(
                ScopeIdentityErrorMapper.isProviderIdentityFailure(
                        unavailable));
        assertTrue(
                ScopeIdentityErrorMapper.isProviderIdentityFailure(
                        mismatch));
    }

    @Test
    void mapsOtherLanguageFailuresToRuntimeFailureWithoutMarkingThemAsProviderFailures() {
        assertEquals(ProcessorErrorCategory.RuntimeExecutionFailure,
                ScopeIdentityErrorMapper.from(BlueLanguageErrorCategory.CanonicalizationError));
        assertEquals(ProcessorErrorCategory.RuntimeExecutionFailure,
                ScopeIdentityErrorMapper.from(BlueLanguageErrorCategory.InvalidBlueIdInput));
        assertEquals(ProcessorErrorCategory.RuntimeExecutionFailure,
                ScopeIdentityErrorMapper.from((BlueLanguageErrorCategory) null));
        assertEquals(ProcessorErrorCategory.RuntimeExecutionFailure,
                ScopeIdentityErrorMapper.from((Throwable) null));
        assertFalse(
                ScopeIdentityErrorMapper.isProviderIdentityFailure(
                        new IllegalStateException("ordinary runtime failure")));
        assertFalse(
                ScopeIdentityErrorMapper.isProviderIdentityFailure(null));
    }
}
