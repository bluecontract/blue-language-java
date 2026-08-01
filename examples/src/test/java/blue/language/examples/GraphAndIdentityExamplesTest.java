package blue.language.examples;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GraphAndIdentityExamplesTest {

    @Test
    void shouldSpecializeTypeWithoutMutatingOverlay() {
        // given
        String expectedTypeBlueId = TEXT_TYPE_BLUE_ID;

        // when
        SpecializationExample.Result result = SpecializationExample.run();

        // then
        assertEquals(expectedTypeBlueId,
                result.getSpecialization().getType().getBlueId());
        assertEquals("hello", result.getSpecialization().getValue());
        assertNull(result.getOriginalOverlay().getType());
        assertNotEquals(expectedTypeBlueId,
                result.getSpecializationBlueId());
    }

    @Test
    void shouldExpandAndCollapseVerifiedProviderContent() {
        // given
        String expectedValue = "provider content";

        // when
        ExpandCollapseProviderExample.Result result =
                ExpandCollapseProviderExample.run();

        // then
        assertEquals(expectedValue, result.getExpanded().getValue());
        assertEquals(result.getBlueId(),
                result.getCollapsed().getBlueId());
        assertTrue(result.getCollapsed().isReferenceOnly());
    }

    @Test
    void shouldPreserveIdentityAcrossResolvedCanonicalAndMinimizedForms() {
        // given
        String inheritedField = "inherited";

        // when
        SemanticFormsExample.Result result = SemanticFormsExample.run();

        // then
        assertTrue(result.getResolved().getProperties()
                .containsKey(inheritedField));
        assertTrue(!result.getCanonical().getProperties()
                .containsKey(inheritedField));
        assertTrue(!result.getMinimized().getProperties()
                .containsKey(inheritedField));
        assertTrue(!result.getBlueId().isEmpty());
    }

    @Test
    void shouldReuseListPrefixAndRecomputeOnlyChangedSuffix() {
        // given
        String emptyIdentity = "";

        // when
        IncrementalListIdentityExample.Result result =
                IncrementalListIdentityExample.run();

        // then
        assertNotEquals(emptyIdentity, result.getPrefixBlueId());
        assertEquals(result.getRecomputedSuffixBlueId(),
                result.getUpdatedCompleteBlueId());
        assertNotEquals(result.getPrefixBlueId(),
                result.getAppendedBlueId());
    }

    @Test
    void shouldValidateUnconstrainedDictionaryAndRequiredFieldsDifferently() {
        // given
        String expectedScalar = "any scalar";
        String expectedMember = "dictionary member";

        // when
        UnconstrainedFieldExample.Result result =
                UnconstrainedFieldExample.run();

        // then
        assertEquals(expectedScalar, result.getResolvedScalar());
        assertEquals(expectedMember, result.getResolvedMember());
        assertInstanceOf(IllegalArgumentException.class,
                result.getDictionaryScalarFailure());
        assertInstanceOf(IllegalArgumentException.class,
                result.getMissingRequiredFailure());
    }

    @Test
    void shouldCalculateReleasedCyclicMemberBlueIdsInCallerOrder() {
        // given
        java.util.List<String> expected = Arrays.asList(
                CyclicSetIdentityExample.FIRST_MEMBER_BLUE_ID,
                CyclicSetIdentityExample.SECOND_MEMBER_BLUE_ID);

        // when
        CyclicSetIdentityExample.Result result =
                CyclicSetIdentityExample.run();

        // then
        assertEquals(expected, result.getMemberBlueIds());
    }
}
