package blue.language.utils;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TypeClassResolverTest {

    @Test
    void blueIdMapViewRemainsLiveAndUnmodifiableAcrossRegistration() {
        TypeClassResolver resolver = new TypeClassResolver();
        Map<String, Class<?>> view = resolver.getBlueIdMap();
        Set<Map.Entry<String, Class<?>>> entries = view.entrySet();

        resolver.register("retained-live-view", String.class);

        assertSame(String.class, view.get("retained-live-view"));
        assertEquals(1, entries.size());
        assertTrue(entries.stream().anyMatch(entry ->
                entry.getKey().equals("retained-live-view") && entry.getValue() == String.class));
        assertThrows(UnsupportedOperationException.class, view::clear);
    }
}
