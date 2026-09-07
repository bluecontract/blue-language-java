package blue.language.runtime;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.Blue;
import blue.language.api.BlueCachePolicy;
import blue.language.codec.BlueFormat;
import blue.language.conformance.ConformanceEngine;
import blue.language.model.Node;
import blue.language.snapshot.ImmutableBluePatch;
import blue.language.provider.NodeProvider;
import blue.language.snapshot.CanonicalPatchResult;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BlueLanguageCompositionTest {

    @Test
    void shouldExposeFocusedServicesOverOneRuntimeConfiguration() {
        // given
        BlueLanguage language = BlueLanguage.builder().build();

        // when
        Object[] services = {
                language.codec(),
                language.preprocessing(),
                language.graph(),
                language.resolution(),
                language.identity(),
                language.snapshots(),
                language.matching(),
                language.patching()
        };

        // then
        for (Object service : services) {
            assertNotNull(service);
        }
        language.close();
    }

    @Test
    void shouldCalculateSourceIdentityThroughCanonicalDirectPath() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node source = language.codec().parseSource(
                    "type: Text\nvalue: hello", BlueFormat.YAML);

            // when
            Node canonical = language.identity()
                    .canonicalIdentityInput(source);
            String sourceBlueId = language.identity()
                    .sourceDocumentBlueId(source);
            String directBlueId = language.identity()
                    .directBlueId(canonical);

            // then
            assertEquals(TEXT_TYPE_BLUE_ID,
                    canonical.getType().getBlueId());
            assertEquals(directBlueId, sourceBlueId);
        }
    }

    @Test
    void shouldKeepCanonicalPatchingInsideLanguageService() {
        // given
        try (BlueLanguage language = BlueLanguage.builder().build()) {
            Node canonical = new Node()
                    .properties("left", new Node().value("before"));

            // when
            CanonicalPatchResult result = language.patching().apply(
                    canonical,
                    ImmutableBluePatch.replace(
                            "/left", new Node().value("after")));

            // then
            assertEquals("after",
                    result.root().property("left").getValue());
            assertFalse(result.blueId().isEmpty());
        }
    }

    @Test
    void shouldReleaseOwnedStateAndRejectSemanticWorkAfterClose() {
        // given
        BlueLanguage language = BlueLanguage.builder().build();
        Node source = new Node().value("before-close");

        // when
        language.snapshots().resolve(source);
        language.close();

        // then
        assertTrue(language.snapshots().stats().isClosed());
        assertThrows(IllegalStateException.class,
                () -> language.resolution().resolve(source));
        assertEquals("\"before-close\"",
                language.codec().writeSimple(source, BlueFormat.JSON));
    }

    @Test
    void shouldCloseIdempotently() {
        // given
        BlueLanguage language = BlueLanguage.builder().build();

        // when
        language.close();
        language.close();

        // then
        assertTrue(language.snapshots().stats().isClosed());
    }

    @Test
    void shouldGiveConformanceEnginesIndependentLifecycleOwnership() {
        // given
        BlueLanguageRuntime runtime = BlueLanguageRuntime.create(
                blueId -> null,
                BlueCachePolicy.boundedDefaults(),
                Collections.emptyMap());
        ConformanceEngine first = runtime.newConformanceEngine();

        // when
        first.close();
        Node resolvedAfterEngineClose = runtime.resolution().resolve(
                new Node().value("runtime-still-open"));
        ConformanceEngine second = runtime.newConformanceEngine();
        runtime.close();

        // then
        assertEquals("runtime-still-open",
                resolvedAfterEngineClose.getValue());
        assertTrue(second.conforms(new Node().value("engine-still-open")));
        assertThrows(IllegalStateException.class,
                runtime::newConformanceEngine);
        second.close();
    }

    @Test
    void shouldApplyCustomReferenceAdmissionOnlyToCacheRetention() {
        // given
        Node content = new Node().value("admission-target");
        String blueId = DirectBlueIdCalculator.calculateBlueId(content);
        BlueLanguageRuntime defaults = BlueLanguageRuntime.create(
                requested -> blueId.equals(requested)
                        ? Collections.singletonList(content.clone())
                        : null,
                BlueCachePolicy.boundedDefaults(),
                Collections.emptyMap());
        BlueLanguageRuntime excluded = BlueLanguageRuntime.create(
                requested -> blueId.equals(requested)
                        ? Collections.singletonList(content.clone())
                        : null,
                BlueCachePolicy.boundedDefaults(),
                Collections.emptyMap(),
                requested -> false);

        // when
        Node defaultResult = defaults.resolution().resolve(
                new Node().type(new Node().blueId(blueId)));
        Node excludedResult = excluded.resolution().resolve(
                new Node().type(new Node().blueId(blueId)));

        // then
        assertEquals(defaultResult.toString(), excludedResult.toString());
        assertTrue(defaults.snapshots().stats()
                .region("verifiedReferences").entries() > 0);
        assertEquals(0, excluded.snapshots().stats()
                .region("verifiedReferences").entries());
        defaults.close();
        excluded.close();
    }

    @Test
    void shouldMatchDeferredSnapshotSemanticsAndProviderDemand() {
        // given
        Node deferredContent = new Node().properties(
                "body", new Node().value("deferred"));
        String deferredBlueId =
                DirectBlueIdCalculator.calculateBlueId(deferredContent);
        Node ordinaryType = new Node().properties(
                "inherited", new Node().value("resolved"));
        String ordinaryBlueId =
                DirectBlueIdCalculator.calculateBlueId(ordinaryType);
        Node source = new Node()
                .properties("selected",
                        new Node().blueId(deferredBlueId))
                .properties("ordinary",
                        new Node().type(
                                new Node().blueId(ordinaryBlueId)));
        AtomicInteger legacyDeferredDemands = new AtomicInteger();
        AtomicInteger legacyOrdinaryDemands = new AtomicInteger();
        AtomicInteger focusedDeferredDemands = new AtomicInteger();
        AtomicInteger focusedOrdinaryDemands = new AtomicInteger();
        NodeProvider legacyProvider = requested -> fixtureContent(
                requested,
                deferredBlueId,
                deferredContent,
                legacyDeferredDemands,
                ordinaryBlueId,
                ordinaryType,
                legacyOrdinaryDemands);
        NodeProvider focusedProvider = requested -> fixtureContent(
                requested,
                deferredBlueId,
                deferredContent,
                focusedDeferredDemands,
                ordinaryBlueId,
                ordinaryType,
                focusedOrdinaryDemands);

        // when
        ResolvedSnapshot legacy;
        try (Blue blue = new Blue(legacyProvider)) {
            legacy = blue.resolveToSnapshotPreservingPaths(
                    source, Collections.singleton("/selected"));
        }
        ResolvedSnapshot focused;
        try (BlueLanguageRuntime runtime = BlueLanguageRuntime.create(
                focusedProvider,
                BlueCachePolicy.boundedDefaults(),
                Collections.emptyMap())) {
            focused = runtime.snapshots().resolvePreservingPaths(
                    source, Collections.singleton("/selected"));
        }

        // then
        assertEquals(legacy.canonicalRoot().toString(),
                focused.canonicalRoot().toString());
        assertEquals(legacy.resolvedRoot().toString(),
                focused.resolvedRoot().toString());
        assertEquals(legacy.blueId(), focused.blueId());
        assertFalse(legacy.isResolutionComplete());
        assertFalse(focused.isResolutionComplete());
        assertEquals(0, legacyDeferredDemands.get());
        assertEquals(0, focusedDeferredDemands.get());
        assertTrue(legacyOrdinaryDemands.get() > 0);
        assertEquals(legacyOrdinaryDemands.get(),
                focusedOrdinaryDemands.get());
    }

    @Test
    void shouldKeepPureReferenceColdAcrossConfiguredAndIsolatedProcessingScopes() {
        // given
        Node deferredContent = new Node().properties(
                "program", new Node().value("must stay cold"));
        String deferredBlueId =
                DirectBlueIdCalculator.calculateBlueId(deferredContent);
        Node ordinaryType = new Node().properties(
                "inherited", new Node().value("resolved"));
        String ordinaryBlueId =
                DirectBlueIdCalculator.calculateBlueId(ordinaryType);
        Node source = new Node()
                .properties("selected", new Node().blueId(deferredBlueId))
                .properties("ordinary", new Node().type(
                        new Node().blueId(ordinaryBlueId)));
        AtomicInteger configuredOrdinaryDemands = new AtomicInteger();
        AtomicInteger isolatedOrdinaryDemands = new AtomicInteger();
        AtomicInteger configuredDeferredDemands = new AtomicInteger();
        AtomicInteger isolatedDeferredDemands = new AtomicInteger();
        NodeProvider configuredProvider = verificationProvider(
                deferredBlueId,
                deferredContent,
                configuredDeferredDemands,
                ordinaryBlueId,
                ordinaryType,
                configuredOrdinaryDemands);
        NodeProvider isolatedProvider = verificationProvider(
                deferredBlueId,
                deferredContent,
                isolatedDeferredDemands,
                ordinaryBlueId,
                ordinaryType,
                isolatedOrdinaryDemands);

        // when
        ResolvedSnapshot configured;
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(configuredProvider)
                .build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            configured = scope.resolvePreservingPaths(
                    source, Collections.singleton("/selected"));
        }
        ResolvedSnapshot isolated;
        try (BlueLanguage language = BlueLanguage.builder().build();
             LanguageProcessing.Scope scope = language.processing()
                     .openScope(isolatedProvider)) {
            isolated = scope.resolvePreservingPaths(
                    source, Collections.singleton("/selected"));
        }

        // then
        assertDeferredPureReferenceSnapshot(
                source, deferredBlueId, configured);
        assertDeferredPureReferenceSnapshot(
                source, deferredBlueId, isolated);
        assertTrue(configuredOrdinaryDemands.get() > 0);
        assertTrue(isolatedOrdinaryDemands.get() > 0);
        assertEquals(0, configuredDeferredDemands.get());
        assertEquals(0, isolatedDeferredDemands.get());
    }

    @Test
    void shouldCanonicalizePreservedPureReferenceWithoutOpeningIt() {
        // given
        Node unavailable = new Node().name("Unavailable preserved content");
        String unavailableBlueId =
                DirectBlueIdCalculator.calculateBlueId(unavailable);
        Node source = new Node().properties(
                "selected", new Node().blueId(unavailableBlueId));

        // when
        ResolvedSnapshot snapshot;
        try (BlueLanguage language = BlueLanguage.builder()
                .nodeProvider(ignored -> null)
                .build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            snapshot = scope.resolvePreservingPaths(
                    source,
                    Collections.singleton("/selected"));
        }

        // then
        assertFalse(snapshot.isResolutionComplete());
        assertTrue(snapshot.canonicalTypeIdentities()
                .hasCompleteCoverage());
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(source),
                snapshot.blueId());
        assertEquals(
                unavailableBlueId,
                snapshot.resolvedNodeAt("/selected").getBlueId());
    }

    @Test
    void shouldRejectWholeIdentityUntilPreservedInlineTypeEvidenceIsComplete() {
        // given
        Node inlineType = new Node()
                .name("Deferred inline type")
                .properties("inherited", new Node().value("from-type"));
        Node source = new Node().properties(
                "selected",
                new Node()
                        .type(inlineType)
                        .properties("local", new Node().value("authored")));

        // when
        ResolvedSnapshot limited;
        ResolvedSnapshot retried;
        ResolvedSnapshot eager;
        try (BlueLanguage language = BlueLanguage.builder().build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            limited = scope.resolvePreservingPaths(
                    source,
                    Collections.singleton("/selected"));
            retried = scope.resolveTransient(source);
        }
        try (BlueLanguage language = BlueLanguage.builder().build();
             LanguageProcessing.Scope scope =
                     language.processing().openScope()) {
            eager = scope.resolveTransient(source);
        }

        // then
        assertFalse(limited.isResolutionComplete());
        assertFalse(limited.hasCanonicalIdentity());
        assertFalse(limited.canonicalTypeIdentities()
                .hasCompleteCoverage());
        assertEquals(
                "Deferred inline type",
                limited.sourceRoot()
                        .getProperties()
                        .get("selected")
                        .getType()
                        .getName());
        assertThrows(IllegalStateException.class, limited::canonicalRoot);
        assertThrows(IllegalStateException.class, limited::blueId);
        assertTrue(retried.isResolutionComplete());
        assertTrue(retried.hasCanonicalIdentity());
        assertEquals(eager.blueId(), retried.blueId());
        assertTrue(eager.frozenCanonicalRoot().sameResolvedStructure(
                retried.frozenCanonicalRoot()));
        assertNull(source.getProperties().get("selected")
                .getType().getBlueId());
    }

    private static NodeProvider verificationProvider(
            String deferredBlueId,
            Node deferredContent,
            AtomicInteger deferredDemands,
            String ordinaryBlueId,
            Node ordinaryType,
            AtomicInteger ordinaryDemands) {
        return requested -> {
            if (deferredBlueId.equals(requested)) {
                deferredDemands.incrementAndGet();
                return Collections.singletonList(deferredContent.clone());
            }
            if (ordinaryBlueId.equals(requested)) {
                ordinaryDemands.incrementAndGet();
                return Collections.singletonList(ordinaryType.clone());
            }
            return null;
        };
    }

    private static void assertDeferredPureReferenceSnapshot(
            Node source,
            String deferredBlueId,
            ResolvedSnapshot snapshot) {
        assertFalse(snapshot.isResolutionComplete());
        assertTrue(snapshot.canonicalTypeIdentities()
                .hasCompleteCoverage());
        assertEquals(
                DirectBlueIdCalculator.calculateBlueId(source),
                snapshot.blueId());
        assertEquals(
                deferredBlueId,
                snapshot.canonicalNodeAt("/selected").getBlueId());
        assertEquals(
                deferredBlueId,
                snapshot.resolvedNodeAt("/selected").getBlueId());
        assertEquals(
                "resolved",
                snapshot.resolvedNodeAt("/ordinary/inherited").getValue());
    }

    private static java.util.List<Node> fixtureContent(
            String requested,
            String deferredBlueId,
            Node deferredContent,
            AtomicInteger deferredDemands,
            String ordinaryBlueId,
            Node ordinaryContent,
            AtomicInteger ordinaryDemands) {
        if (deferredBlueId.equals(requested)) {
            deferredDemands.incrementAndGet();
            return Collections.singletonList(deferredContent.clone());
        }
        if (ordinaryBlueId.equals(requested)) {
            ordinaryDemands.incrementAndGet();
            return Collections.singletonList(ordinaryContent.clone());
        }
        return null;
    }
}
