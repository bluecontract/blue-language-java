package blue.language.model.wire;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class BlueLanguageConstantsTest {

    @Test
    void shouldExposeExactLanguageReservedFieldPolicy() {
        // given
        Set<String> expected = new LinkedHashSet<>(Arrays.asList(
                BlueLanguageConstants.OBJECT_NAME,
                BlueLanguageConstants.OBJECT_DESCRIPTION,
                BlueLanguageConstants.OBJECT_TYPE,
                BlueLanguageConstants.OBJECT_ITEM_TYPE,
                BlueLanguageConstants.OBJECT_KEY_TYPE,
                BlueLanguageConstants.OBJECT_VALUE_TYPE,
                BlueLanguageConstants.OBJECT_VALUE,
                BlueLanguageConstants.OBJECT_ITEMS,
                BlueLanguageConstants.OBJECT_BLUE_ID,
                BlueLanguageConstants.OBJECT_BLUE,
                BlueLanguageConstants.OBJECT_SCHEMA,
                BlueLanguageConstants.OBJECT_MERGE_POLICY,
                BlueLanguageConstants.OBJECT_CONTRACTS,
                BlueLanguageConstants.LEGACY_OBJECT_PROPERTIES,
                BlueLanguageConstants.LEGACY_OBJECT_CONSTRAINTS));

        // when
        Set<String> actual =
                BlueLanguageConstants.LANGUAGE_RESERVED_FIELDS;

        // then
        assertEquals(expected, actual);
    }

    @Test
    void shouldKeepLanguageReservedFieldPolicyImmutable() {
        // given
        Set<String> reserved =
                BlueLanguageConstants.LANGUAGE_RESERVED_FIELDS;

        // when
        assertThrows(
                UnsupportedOperationException.class,
                () -> reserved.add("applicationField"));

        // then
        assertFalse(reserved.contains("applicationField"));
    }

    @Test
    void shouldTreatListControlsAsOrdinaryFieldsOutsideListControlPosition() {
        // given
        Set<String> listControls = new LinkedHashSet<>(Arrays.asList(
                BlueLanguageConstants.LIST_CONTROL_PREVIOUS,
                BlueLanguageConstants.LIST_CONTROL_POS,
                BlueLanguageConstants.LIST_CONTROL_REPLACE,
                BlueLanguageConstants.LIST_CONTROL_EMPTY));

        // when
        boolean anyReserved = listControls.stream().anyMatch(
                BlueLanguageConstants::isLanguageReservedField);

        // then
        assertFalse(anyReserved);
        assertFalse(BlueLanguageConstants.isLanguageReservedField(null));
    }
}
