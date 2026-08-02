package blue.language.processor.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class ProcessEmbeddedTest {

    @Test
    void shouldCopyAssignedExactPaths() {
        // given
        ProcessEmbedded embedded = new ProcessEmbedded();
        List<String> callerPaths = new ArrayList<>(
                Arrays.asList("/payment", "/delivery"));

        // when
        embedded.setPaths(callerPaths);
        callerPaths.add("/later");

        // then
        assertEquals(
                Arrays.asList("/payment", "/delivery"),
                embedded.getPaths());
    }

    @Test
    void shouldCopyAssignedCollectionPaths() {
        // given
        ProcessEmbedded embedded = new ProcessEmbedded();
        List<String> callerPaths = new ArrayList<>(
                Arrays.asList("/lessons", "/refunds"));

        // when
        embedded.setCollectionPaths(callerPaths);
        callerPaths.clear();

        // then
        assertEquals(
                Arrays.asList("/lessons", "/refunds"),
                embedded.getCollectionPaths());
    }

    @Test
    void shouldExposeExactPathsAsUnmodifiableLiveView() {
        // given
        ProcessEmbedded embedded = new ProcessEmbedded()
                .addPath("/payment");

        // when
        List<String> exposedPaths = embedded.getPaths();
        embedded.addPath("/delivery");

        // then
        assertEquals(
                Arrays.asList("/payment", "/delivery"),
                exposedPaths);
        assertThrows(
                UnsupportedOperationException.class,
                () -> exposedPaths.add("/forbidden"));
    }

    @Test
    void shouldExposeCollectionPathsAsUnmodifiableLiveView() {
        // given
        ProcessEmbedded embedded = new ProcessEmbedded()
                .addCollectionPath("/lessons");

        // when
        List<String> exposedPaths = embedded.getCollectionPaths();
        embedded.addCollectionPath("/refunds");

        // then
        assertEquals(
                Arrays.asList("/lessons", "/refunds"),
                exposedPaths);
        assertThrows(
                UnsupportedOperationException.class,
                exposedPaths::clear);
    }

    @Test
    void shouldClearExactPathsWhenAssignedNull() {
        // given
        ProcessEmbedded embedded = new ProcessEmbedded()
                .addPath("/payment");

        // when
        embedded.setPaths(null);

        // then
        assertEquals(0, embedded.getPaths().size());
    }

    @Test
    void shouldClearCollectionPathsWhenAssignedNull() {
        // given
        ProcessEmbedded embedded = new ProcessEmbedded()
                .addCollectionPath("/lessons");

        // when
        embedded.setCollectionPaths(null);

        // then
        assertEquals(0, embedded.getCollectionPaths().size());
    }

    @Test
    void shouldPreserveExactPathInsertionOrder() {
        // given
        ProcessEmbedded embedded = new ProcessEmbedded();

        // when
        embedded.addPath("/third")
                .addPath("/first")
                .addPath("/second");

        // then
        assertEquals(
                Arrays.asList("/third", "/first", "/second"),
                embedded.getPaths());
    }

    @Test
    void shouldPreserveCollectionPathInsertionOrder() {
        // given
        ProcessEmbedded embedded = new ProcessEmbedded();

        // when
        embedded.addCollectionPath("/third")
                .addCollectionPath("/first")
                .addCollectionPath("/second");

        // then
        assertEquals(
                Arrays.asList("/third", "/first", "/second"),
                embedded.getCollectionPaths());
    }

    @Test
    void shouldKeepExactAndCollectionDeclarationsIndependent() {
        // given
        ProcessEmbedded embedded = new ProcessEmbedded()
                .addPath("/payment")
                .addCollectionPath("/lessons");

        // when
        embedded.setPaths(null);

        // then
        assertEquals(0, embedded.getPaths().size());
        assertEquals(
                Arrays.asList("/lessons"),
                embedded.getCollectionPaths());
    }
}
