package blue.language.processor;

import blue.language.BlueLanguageErrorCategory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopeIdentityErrorMapperTest {

    @Test
    void shouldPreserveProviderCategoriesFromLanguageCategories() {
        // given
        BlueLanguageErrorCategory unavailable =
                BlueLanguageErrorCategory.ProviderUnavailable;
        BlueLanguageErrorCategory mismatch =
                BlueLanguageErrorCategory.ProviderBlueIdMismatch;

        // when
        ProcessorErrorCategory unavailableCategory =
                ScopeIdentityErrorMapper.from(unavailable);
        ProcessorErrorCategory mismatchCategory =
                ScopeIdentityErrorMapper.from(mismatch);

        // then
        assertEquals(ProcessorErrorCategory.RuntimeExecutionFailure,
                unavailableCategory);
        assertEquals(ProcessorErrorCategory.InvalidProcessingDocument,
                mismatchCategory);
    }

    @Test
    void shouldClassifyProviderFailuresFromThrowables() {
        // given
        IllegalStateException unavailable =
                new IllegalStateException(
                        "No content found for blueId: missing");
        IllegalArgumentException mismatch =
                new IllegalArgumentException(
                        "Provider returned content for requested BlueId but computed BlueId differs");

        // when
        ProcessorErrorCategory unavailableCategory =
                ScopeIdentityErrorMapper.from(unavailable);
        ProcessorErrorCategory mismatchCategory =
                ScopeIdentityErrorMapper.from(mismatch);
        boolean unavailableIsProviderFailure =
                ScopeIdentityErrorMapper.isProviderIdentityFailure(
                        unavailable);
        boolean mismatchIsProviderFailure =
                ScopeIdentityErrorMapper.isProviderIdentityFailure(
                        mismatch);

        // then
        assertEquals(ProcessorErrorCategory.RuntimeExecutionFailure,
                unavailableCategory);
        assertEquals(ProcessorErrorCategory.InvalidProcessingDocument,
                mismatchCategory);
        assertTrue(unavailableIsProviderFailure);
        assertTrue(mismatchIsProviderFailure);
    }

    @Test
    void shouldMapOtherLanguageFailuresToRuntimeFailureWithoutMarkingThemAsProviderFailures() {
        // given
        IllegalStateException ordinaryFailure =
                new IllegalStateException("ordinary runtime failure");

        // when
        ProcessorErrorCategory canonicalization =
                ScopeIdentityErrorMapper.from(
                        BlueLanguageErrorCategory.CanonicalizationError);
        ProcessorErrorCategory invalidBlueId =
                ScopeIdentityErrorMapper.from(
                        BlueLanguageErrorCategory.InvalidBlueIdInput);
        ProcessorErrorCategory nullLanguageCategory =
                ScopeIdentityErrorMapper.from(
                        (BlueLanguageErrorCategory) null);
        ProcessorErrorCategory nullFailure =
                ScopeIdentityErrorMapper.from((Throwable) null);
        boolean ordinaryIsProviderFailure =
                ScopeIdentityErrorMapper.isProviderIdentityFailure(
                        ordinaryFailure);
        boolean nullIsProviderFailure =
                ScopeIdentityErrorMapper.isProviderIdentityFailure(null);

        // then
        assertEquals(ProcessorErrorCategory.RuntimeExecutionFailure,
                canonicalization);
        assertEquals(ProcessorErrorCategory.RuntimeExecutionFailure,
                invalidBlueId);
        assertEquals(ProcessorErrorCategory.RuntimeExecutionFailure,
                nullLanguageCategory);
        assertEquals(ProcessorErrorCategory.RuntimeExecutionFailure,
                nullFailure);
        assertFalse(ordinaryIsProviderFailure);
        assertFalse(nullIsProviderFailure);
    }
}
