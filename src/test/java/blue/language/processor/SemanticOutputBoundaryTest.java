package blue.language.processor;

import blue.language.model.wire.BlueLanguageConstants;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.model.Nodes;
import blue.language.model.Schema;
import blue.language.processor.model.JsonPatch;
import blue.language.preprocess.provider.BasicNodeProvider;
import blue.language.snapshot.FrozenNode;
import blue.language.merge.ResolvedSnapshot;
import blue.language.identity.DirectBlueIdCalculator;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static blue.language.model.wire.BlueLanguageConstants.INTEGER_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.BOOLEAN_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.DOUBLE_TYPE_BLUE_ID;
import static blue.language.model.wire.BlueLanguageConstants.TEXT_TYPE_BLUE_ID;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SemanticOutputBoundaryTest {

    @Test
    void shouldVerifyTransientTextObjectAndListAreExactAndChargedOnce() {
        // given
        try (Invocation invocation =
                     new Invocation(new Blue())) {
            Node output =
                    new Node()
                            .properties(
                                    "message",
                                    text("hello semantic output"))
                            .properties(
                                    "items",
                                    new Node().items(
                                            text("a"),
                                            text("b")));
            long before = invocation.totalGas();
            String expectedBlueId =
                    invocation.blue.calculateSourceDocumentBlueId(
                            output);

            // when
            ExactBlueValue first =
                    invocation.boundary().admit(output);
            long afterFirst = invocation.totalGas();
            ExactBlueValue repeated =
                    invocation.boundary().admit(
                            output.clone());
            ExactBlueValue carried =
                    invocation.boundary().admit(first);

            // then
            assertEquals(
                    expectedBlueId,
                    first.blueId());
            assertEquals(first.blueId(), repeated.blueId());
            assertEquals(first, carried);
            assertTrue(afterFirst > before);
            assertEquals(
                    afterFirst,
                    invocation.totalGas(),
                    "an exact identity already admitted in this invocation must not be charged twice");
            assertTrue(
                    invocation.counter(
                            "textBlockConstructed") > 0L);
            assertTrue(
                    invocation.counter(
                            "objectMemberRebuilt") > 0L);
            assertTrue(
                    invocation.counter(
                            "listFoldStepRecomputed") > 0L);
        }
    }

    @Test
    void shouldRetainConstructedContentAfterCarryingAnOpaqueInputEdge() {
        // given
        Blue blue = new Blue();
        Node output = new Node().properties(
                "kind", text("PendingNested/Changed"));
        FrozenNode exact = FrozenNode.fromNode(
                blue.canonicalize(output.clone()));
        FrozenNode input = FrozenNode.fromNode(new Node().properties(
                "event", new Node().blueId(exact.blueId())));
        long freshConstructionGas;
        try (Invocation fresh = new Invocation(new Blue())) {
            long before = fresh.totalGas();
            fresh.boundary().admit(output.clone());
            freshConstructionGas = fresh.totalGas() - before;
        }
        try (Invocation invocation = new Invocation(blue)) {
            SemanticOutputBoundary boundary = invocation.boundary();
            boundary.carryExactInput(input, input.blueId());
            ExactBlueValue opaque = boundary.admit(
                    new Node().blueId(exact.blueId()));
            long before = invocation.totalGas();

            // when
            ExactBlueValue constructed = boundary.admit(output.clone());
            long afterConstruction = invocation.totalGas();
            ExactBlueValue repeated = boundary.admit(output.clone());
            ExactBlueValue referenced = boundary.admit(
                    new Node().blueId(exact.blueId()));

            // then
            assertTrue(opaque.frozenValue().isReferenceOnly(),
                    "an already-issued input handle remains an immutable edge");
            assertEquals(exact.blueId(), opaque.blueId());
            assertEquals(exact.blueId(), constructed.blueId());
            assertFalse(constructed.frozenValue().isReferenceOnly(),
                    "complete verified output must keep its semantic content");
            assertEquals(exact.resolvedStructuralKey(),
                    constructed.frozenValue().resolvedStructuralKey());
            assertEquals(freshConstructionGas, afterConstruction - before,
                    "the input edge does not prepay content construction");
            assertTrue(freshConstructionGas > 0L);
            assertSame(constructed, repeated);
            assertSame(constructed, referenced);
            assertEquals(afterConstruction, invocation.totalGas(),
                    "reusing complete evidence must not repeat construction");
        }
    }

    @Test
    void shouldChargeLargeTextUsingMultipleLogicalTextBlocks() {
        // given
        long smallTextGas;
        try (Invocation invocation =
                     new Invocation(new Blue())) {
            long before = invocation.totalGas();
            invocation.boundary().admit(text("short"));
            smallTextGas = invocation.totalGas() - before;
        }

        try (Invocation invocation =
                     new Invocation(new Blue())) {
            long before = invocation.totalGas();

            // when
            invocation.boundary().admit(
                    text(repeat("blue", 1024)));
            long largeTextGas =
                    invocation.totalGas() - before;
            long constructedBlocks = invocation.counter(
                    "textBlockConstructed");

            // then
            assertTrue(largeTextGas > smallTextGas);
            assertTrue(constructedBlocks > 1L);
        }
    }

    @Test
    void shouldChargeLargeIntegerUsingMultipleLogicalLimbs() {
        // given
        try (Invocation invocation =
                     new Invocation(new Blue())) {
            Node integer =
                    new Node()
                            .type(new Node().blueId(
                                    INTEGER_TYPE_BLUE_ID))
                            .value(BigInteger.ONE.shiftLeft(4096));

            // when
            invocation.boundary().admit(integer);
            long limbOperations = invocation.counter(
                    "integerLimbOperation");

            // then
            assertTrue(limbOperations > 1L);
        }
    }

    @Test
    void shouldVerifySchemaNestedNodesParticipateInSemanticConstruction() {
        // given
        try (Invocation invocation =
                     new Invocation(new Blue())) {
            BigInteger exactValue =
                    BigInteger.ONE.shiftLeft(4096);
            Node output =
                    new Node()
                            .value(exactValue)
                            .schema(
                            new Schema()
                                    .minimum(
                                            new Node().value(
                                                    exactValue.subtract(
                                                            BigInteger.ONE)))
                                    .enumValues(
                                            Arrays.asList(
                                                    new Node().value(
                                                            exactValue),
                                                    text(repeat(
                                                            "schema",
                                                            512)))));

            // when
            invocation.boundary().admit(output);

            // then
            assertTrue(
                    invocation.counter(
                            "integerLimbOperation") > 1L);
            assertTrue(
                    invocation.counter(
                            "textBlockConstructed") > 1L);
            assertTrue(
                    invocation.counter(
                            "listFoldStepRecomputed") >= 2L);
            assertTrue(
                    invocation.counter(
                            "nodeIdentityEstablished") >= 4L);
        }
    }

    @Test
    void shouldVerifyZeroBudgetRejectsAfterEvidencePreparationAndLeavesNoTrace() {
        // given
        TrackingBlue blue = new TrackingBlue();
        GasMeter meter =
                new GasMeter(
                        GasSchedule.contracts10(), 0L);
        RuntimeWorkSession session =
                new RuntimeWorkSession(
                        meter,
                        RuntimeWorkSession.Mode.PROCESSING);
        SemanticOutputBoundary boundary =
                new SemanticOutputBoundary(
                        session,
                        blue,
                        null,
                        meter.semantic());
        Throwable failure = null;
        String failureCounter = null;
        int canonicalizeCalls = -1;
        long totalGas = -1L;
        boolean traceEmpty = false;
        try {
            // when
            failure = captureFailure(
                    () -> boundary.admit(
                            text("must not normalize")));
            failureCounter = failure
                    instanceof GasLimitExceededException
                    ? ((GasLimitExceededException) failure)
                    .counter() : null;
            canonicalizeCalls =
                    blue.canonicalizeCalls.get();
            totalGas = meter.totalGas();
            traceEmpty = meter.trace().isEmpty();
        } finally {
            session.close();
            blue.close();
        }

        // then
        assertInstanceOf(
                GasLimitExceededException.class,
                failure);
        assertEquals(
                "nodeIdentityEstablished",
                failureCounter);
        assertEquals(1, canonicalizeCalls);
        assertEquals(0L, totalGas);
        assertTrue(traceEmpty);
    }

    @Test
    void shouldRetainCanonicalPrefixWhenLargeTextExhaustsGas() {
        // given
        TrackingBlue blue = new TrackingBlue();
        GasMeter meter =
                new GasMeter(
                        GasSchedule.contracts10(), 2L);
        RuntimeWorkSession session =
                new RuntimeWorkSession(
                        meter,
                        RuntimeWorkSession.Mode.PROCESSING);
        SemanticOutputBoundary boundary =
                new SemanticOutputBoundary(
                        session,
                        blue,
                        null,
                        meter.semantic());
        Throwable admissionFailure = null;
        Throwable propagationFailure = null;
        String failureCounter = null;
        int canonicalizeCalls = -1;
        long totalGas = -1L;
        int traceSize = -1;
        String firstTraceCounter = null;
        try {
            // when
            Throwable capturedAdmissionFailure = captureFailure(
                    () -> boundary.admit(
                            text(repeat(
                                    "x", 256))));
            admissionFailure = capturedAdmissionFailure;
            propagationFailure =
                    capturedAdmissionFailure
                            instanceof GasLimitExceededException
                            ? captureFailure(
                                    () -> session
                                            .propagateGasExhaustion(
                                                    (GasLimitExceededException)
                                                            capturedAdmissionFailure))
                            : null;
            failureCounter = admissionFailure
                    instanceof GasLimitExceededException
                    ? ((GasLimitExceededException)
                    admissionFailure).counter() : null;
            canonicalizeCalls =
                    blue.canonicalizeCalls.get();
            totalGas = meter.totalGas();
            traceSize = meter.trace().size();
            firstTraceCounter = meter.trace().isEmpty()
                    ? null : meter.trace().get(0).counter();
        } finally {
            session.close();
            blue.close();
        }

        // then
        assertInstanceOf(
                GasLimitExceededException.class,
                admissionFailure);
        assertEquals(
                "textBlockConstructed",
                failureCounter);
        assertEquals(1, canonicalizeCalls);
        assertEquals(1L, totalGas);
        assertEquals(1, traceSize);
        assertEquals(
                "nodeIdentityEstablished",
                firstTraceCounter);
        assertInstanceOf(
                GasLimitExceededException.class,
                propagationFailure);
        assertSame(admissionFailure, propagationFailure);
    }

    @Test
    void shouldRetryLargeTextExhaustionWithIdenticalGasTrace() {
        // given
        Node largeText = text(repeat("x", 256));

        // when
        AdmissionAttempt first =
                attemptAdmission(largeText, null, 2L);
        AdmissionAttempt retry =
                attemptAdmission(largeText, null, 2L);

        // then
        assertTrue(first.outcome.startsWith(
                "gas:textBlockConstructed:"));
        assertEquals(first.outcome, retry.outcome);
        assertEquals(first.trace, retry.trace);
        assertEquals(first.totalGas, retry.totalGas);
    }

    @Test
    void shouldVerifyDirectInlineTextPortableLimitRetainsIdentityPrefix() {
        // given
        TrackingBlue blue = new TrackingBlue();
        GasMeter meter = new GasMeter();
        RuntimeWorkSession session =
                new RuntimeWorkSession(
                        meter,
                        RuntimeWorkSession.Mode.PROCESSING);
        SemanticOutputBoundary boundary =
                new SemanticOutputBoundary(
                        session,
                        blue,
                        null,
                        meter.semantic());
        int limit =
                (int) GasSchedule.contracts10()
                        .portableLimit(
                                "directInlineIdentityTextCodePoints");
        Throwable failure = null;
        String limitName = null;
        long observed = -1L;
        int canonicalizeCalls = -1;
        long totalGas = -1L;
        String firstTraceCounter = null;
        try {
            // when
            failure = captureFailure(
                    () -> boundary.admit(
                            text(repeat(
                                    "x",
                                    limit + 1))));
            limitName = failure
                    instanceof PortableLimitExceededException
                    ? ((PortableLimitExceededException) failure)
                    .limitName() : null;
            observed = failure
                    instanceof PortableLimitExceededException
                    ? ((PortableLimitExceededException) failure)
                    .observed() : -1L;
            canonicalizeCalls =
                    blue.canonicalizeCalls.get();
            totalGas = meter.totalGas();
            firstTraceCounter = meter.trace().isEmpty()
                    ? null : meter.trace().get(0).counter();
        } finally {
            session.suspend();
            blue.close();
        }

        // then
        assertInstanceOf(
                PortableLimitExceededException.class,
                failure);
        assertEquals(
                "directInlineIdentityTextCodePoints",
                limitName);
        assertEquals(limit + 1L, observed);
        assertEquals(1, canonicalizeCalls);
        assertEquals(1L, totalGas);
        assertEquals(
                "nodeIdentityEstablished",
                firstTraceCounter);
    }

    @Test
    void shouldVerifyUnavailableReferenceEvidencePrecedesSemanticGas() {
        // given
        TrackingBlue blue = new TrackingBlue();
        GasMeter meter =
                new GasMeter(
                        GasSchedule.contracts10(), 0L);
        RuntimeWorkSession session =
                new RuntimeWorkSession(
                        meter,
                        RuntimeWorkSession.Mode.PROCESSING);
        CountingSnapshotManager manager =
                new CountingSnapshotManager();
        SemanticOutputBoundary boundary =
                new SemanticOutputBoundary(
                        session,
                        blue,
                        manager,
                        meter.semantic());
        String blueId =
                DirectBlueIdCalculator.calculateBlueId(
                        new Node().value(
                                "provider content"));
        Throwable failure = null;
        int exactDemands = -1;
        long totalGas = -1L;
        boolean traceEmpty = false;
        try {
            // when
            failure = captureFailure(
                    () -> boundary.admit(
                            new Node().blueId(blueId)));
            exactDemands = manager.exactDemands.get();
            totalGas = meter.totalGas();
            traceEmpty = meter.trace().isEmpty();
        } finally {
            session.suspend();
            blue.close();
        }

        // then
        assertInstanceOf(
                ExecutionEvidenceUnavailableException.class,
                failure);
        assertEquals(1, exactDemands);
        assertEquals(0L, totalGas);
        assertTrue(traceEmpty);
    }

    @Test
    void shouldVerifyFrozenReferenceGasRejectionAlsoPoisonsSession() {
        // given
        Blue canonicalizer = new Blue();
        FrozenNode exact;
        try {
            exact =
                    FrozenNode.fromNode(
                            canonicalizer.canonicalize(
                                    text(repeat(
                                            "frozen", 128))));
        } finally {
            canonicalizer.close();
        }
        Blue blue = new Blue();
        GasMeter meter =
                new GasMeter(
                        GasSchedule.contracts10(), 2L);
        RuntimeWorkSession session =
                new RuntimeWorkSession(
                        meter,
                        RuntimeWorkSession.Mode.PROCESSING);
        SemanticOutputBoundary boundary =
                new SemanticOutputBoundary(
                        session,
                        blue,
                        new FixedSnapshotManager(exact),
                        meter.semantic());
        FrozenNode reference =
                FrozenNode.fromResolvedNode(
                        new Node().blueId(
                                exact.blueId()));
        Throwable admissionFailure = null;
        Throwable ledgerFailure = null;
        Throwable propagationFailure = null;
        String failureCounter = null;
        boolean openAfterPropagation = true;
        try {
            // when
            Throwable capturedAdmissionFailure = captureFailure(
                    () -> boundary.admit(reference));
            admissionFailure = capturedAdmissionFailure;
            ledgerFailure = captureFailure(
                    () -> session.openLedger(
                            "late",
                            Collections.singletonMap(
                                    "step", 1L)));
            propagationFailure =
                    capturedAdmissionFailure
                            instanceof GasLimitExceededException
                            ? captureFailure(
                                    () -> session
                                            .propagateGasExhaustion(
                                                    (GasLimitExceededException)
                                                            capturedAdmissionFailure))
                            : null;
            openAfterPropagation = session.isOpen();
            failureCounter = capturedAdmissionFailure
                    instanceof GasLimitExceededException
                    ? ((GasLimitExceededException)
                    capturedAdmissionFailure).counter()
                    : null;
        } finally {
            session.close();
            blue.close();
        }

        // then
        assertInstanceOf(
                GasLimitExceededException.class,
                admissionFailure);
        assertEquals(
                "textBlockConstructed",
                failureCounter);
        assertInstanceOf(
                IllegalStateException.class,
                ledgerFailure);
        assertInstanceOf(
                GasLimitExceededException.class,
                propagationFailure);
        assertSame(admissionFailure, propagationFailure);
        assertFalse(openAfterPropagation);
    }

    @Test
    void shouldVerifyInlineAndVerifiedReferenceHaveSameIdentityAndGas() {
        // given
        Node source =
                new Node()
                        .name("Hosted Runtime Parity Value")
                        .properties("payload", text("same"));
        BasicNodeProvider provider =
                new BasicNodeProvider(source);
        String blueId =
                provider.getBlueIdByName(
                        "Hosted Runtime Parity Value");

        long inlineGas;
        String inlineBlueId;
        long referenceGas;
        String referenceBlueId;

        // when
        try (Invocation invocation =
                     new Invocation(new Blue(provider))) {
            long before = invocation.totalGas();
            ExactBlueValue inline =
                    invocation.boundary().admit(
                            source.clone());
            inlineGas = invocation.totalGas() - before;
            inlineBlueId = inline.blueId();
        }
        try (Invocation invocation =
                     new Invocation(new Blue(provider))) {
            long before = invocation.totalGas();
            ExactBlueValue referenced =
                    invocation.boundary().admit(
                            new Node().blueId(blueId));
            referenceGas =
                    invocation.totalGas() - before;
            referenceBlueId = referenced.blueId();
        }

        // then
        assertEquals(blueId, inlineBlueId);
        assertEquals(blueId, referenceBlueId);
        assertEquals(inlineGas, referenceGas);
    }

    @Test
    void shouldVerifyInlineAndVerifiedReferenceMatchAtEveryTightBudget() {
        // given
        Node source =
                new Node()
                        .name("Hosted Runtime Tight Reference")
                        .properties(
                                "payload",
                                text(repeat(
                                        "reference", 32)));
        Blue canonicalizer = new Blue();
        Node canonical;
        try {
            canonical =
                    canonicalizer.canonicalize(
                            source.clone());
        } finally {
            canonicalizer.close();
        }
        FrozenNode exact =
                FrozenNode.fromNode(canonical);
        Node reference =
                new Node().blueId(
                        exact.blueId());
        ProcessingSnapshotManager manager =
                new FixedSnapshotManager(exact);
        List<AdmissionAttempt> inlineAttempts =
                new ArrayList<>();
        List<AdmissionAttempt> referenceAttempts =
                new ArrayList<>();

        // when
        AdmissionAttempt full =
                attemptAdmission(
                        source,
                        null,
                        GasSchedule.contracts10()
                                .maxProcessGas());
        for (long limit = 0L;
             limit <= full.totalGas;
             limit++) {
            inlineAttempts.add(
                    attemptAdmission(
                            source,
                            null,
                            limit));
            referenceAttempts.add(
                    attemptAdmission(
                            reference,
                            manager,
                            limit));
        }

        // then
        for (int index = 0;
             index < inlineAttempts.size();
             index++) {
            assertSameAttempt(
                    inlineAttempts.get(index),
                    referenceAttempts.get(index),
                    "gas limit " + index);
        }
    }

    @Test
    void shouldChargeUntypedTypedAndReferenceScalarFormsIdentically() {
        for (ScalarCase scalar : scalarCases()) {
            // given
            Node untyped = new Node().value(scalar.value);
            Node explicitlyTyped = new Node()
                    .type(new Node().blueId(scalar.typeBlueId))
                    .value(scalar.value);
            FrozenNode exact =
                    FrozenNode.fromNode(untyped.clone());
            Node reference =
                    new Node().blueId(exact.blueId());
            ProcessingSnapshotManager manager =
                    new FixedSnapshotManager(exact);
            AdmissionAttempt full = attemptAdmission(
                    untyped,
                    null,
                    GasSchedule.contracts10().maxProcessGas());

            // when
            for (long limit = 0L;
                 limit <= full.totalGas;
                 limit++) {
                AdmissionAttempt untypedAttempt =
                        attemptAdmission(untyped, null, limit);

                // then
                assertSameAttempt(
                        untypedAttempt,
                        attemptAdmission(
                                explicitlyTyped, null, limit),
                        scalar.label
                                + " explicit type at gas limit "
                                + limit);
                assertSameAttempt(
                        untypedAttempt,
                        attemptAdmission(
                                reference, manager, limit),
                        scalar.label
                                + " verified reference at gas limit "
                                + limit);
            }
        }
    }

    @Test
    void shouldApplyInferredTypeToDirectObjectPortableLimit() {
        // given
        Node untyped = new Node().value("bounded scalar");
        FrozenNode exact = FrozenNode.fromNode(untyped.clone());
        GasSchedule schedule = GasScheduleTestFixtures.withPortableLimit(
                GasScheduleConstants.PortableLimit.DIRECT_OBJECT_ENTRIES,
                1L);

        // when

        // then
        assertDirectObjectLimit(
                schedule,
                untyped,
                null);
        assertDirectObjectLimit(
                schedule,
                text("bounded scalar"),
                null);
        assertDirectObjectLimit(
                schedule,
                new Node().blueId(exact.blueId()),
                new FixedSnapshotManager(exact));
    }

    @Test
    void shouldVerifyRedundantAuthoredOverridesHaveCanonicalIdentityAndGas() {
        // given
        Node productType =
                new Node()
                        .name("Hosted Runtime Product Type")
                        .properties(
                                "x",
                                new Node().value(
                                        BigInteger.ONE))
                        .properties(
                                "label",
                                new Node().value(
                                        "inherited"));
        BasicNodeProvider provider =
                new BasicNodeProvider(productType);
        String productTypeBlueId =
                provider.getBlueIdByName(
                        "Hosted Runtime Product Type");
        Node minimal =
                new Node()
                        .name("Hosted Runtime Product")
                        .type(new Node().blueId(
                                productTypeBlueId))
                        .properties(
                                "y",
                                new Node().value(
                                        BigInteger.valueOf(
                                                2L)));
        Node noisy =
                minimal.clone()
                        .properties(
                                "x",
                                new Node().value(
                                        BigInteger.ONE))
                        .properties(
                                "label",
                                new Node().value(
                                        "inherited"));

        String minimalBlueId;
        List<String> minimalTrace;
        String noisyBlueId;
        List<String> noisyTrace;

        // when
        try (Invocation invocation =
                     new Invocation(
                             new Blue(provider))) {
            minimalBlueId =
                    invocation.boundary()
                            .admit(minimal)
                            .blueId();
            minimalTrace =
                    traceFingerprint(
                            invocation.trace());
        }
        try (Invocation invocation =
                     new Invocation(
                             new Blue(provider))) {
            ExactBlueValue admitted =
                    invocation.boundary()
                            .admit(noisy);
            noisyBlueId = admitted.blueId();
            noisyTrace = traceFingerprint(
                    invocation.trace());
        }

        // then
        assertEquals(minimalBlueId, noisyBlueId);
        assertEquals(minimalTrace, noisyTrace);
    }

    @Test
    void shouldVerifyRedundantAuthoredFormsMatchAtEveryTightBudget() {
        // given
        Node productType =
                new Node()
                        .name("Hosted Runtime Budget Type")
                        .properties(
                                "x",
                                new Node().value(
                                        BigInteger.ONE))
                        .properties(
                                "label",
                                new Node().value(
                                        "inherited"));
        BasicNodeProvider provider =
                new BasicNodeProvider(productType);
        String productTypeBlueId =
                provider.getBlueIdByName(
                        "Hosted Runtime Budget Type");
        Node minimal =
                new Node()
                        .name("Hosted Runtime Budget Value")
                        .type(new Node().blueId(
                                productTypeBlueId))
                        .properties(
                                "y",
                                new Node().value(
                                        BigInteger.valueOf(
                                                2L)));
        Node noisy =
                minimal.clone()
                        .properties(
                                "x",
                                new Node().value(
                                        BigInteger.ONE))
                        .properties(
                                "label",
                                new Node().value(
                                        "inherited"));
        List<AdmissionAttempt> compactAttempts =
                new ArrayList<>();
        List<AdmissionAttempt> redundantAttempts =
                new ArrayList<>();

        // when
        AdmissionAttempt full =
                attemptAdmission(
                        new Blue(provider),
                        minimal,
                        null,
                        GasSchedule.contracts10()
                                .maxProcessGas());
        for (long limit = 0L;
             limit <= full.totalGas;
             limit++) {
            compactAttempts.add(
                    attemptAdmission(
                            new Blue(provider),
                            minimal,
                            null,
                            limit));
            redundantAttempts.add(
                    attemptAdmission(
                            new Blue(provider),
                            noisy,
                            null,
                            limit));
        }

        // then
        for (int index = 0;
             index < compactAttempts.size();
             index++) {
            assertSameAttempt(
                    compactAttempts.get(index),
                    redundantAttempts.get(index),
                    "gas limit " + index);
        }
    }

    @Test
    void shouldVerifyExactStructuralMemoHitsRemainFreeButNewAuthoredFormNeedsGas() {
        // given
        Node productType =
                new Node()
                        .name("Hosted Runtime Tight Type")
                        .properties(
                                "x",
                                new Node().value(
                                        BigInteger.ONE))
                        .properties(
                                "label",
                                new Node().value(
                                        "inherited"));
        BasicNodeProvider provider =
                new BasicNodeProvider(productType);
        String productTypeBlueId =
                provider.getBlueIdByName(
                        "Hosted Runtime Tight Type");
        Node minimal =
                new Node()
                        .name("Hosted Runtime Tight Value")
                        .type(new Node().blueId(
                                productTypeBlueId))
                        .properties(
                                "y",
                                new Node().value(
                                        BigInteger.valueOf(
                                                2L)));
        Node noisy =
                minimal.clone()
                        .properties(
                                "x",
                                new Node().value(
                                        BigInteger.ONE))
                        .properties(
                                "label",
                                new Node().value(
                                        "inherited"));

        long exactGas;
        Blue sizingBlue =
                new Blue(provider);
        GasMeter sizingMeter =
                new GasMeter();
        RuntimeWorkSession sizingSession =
                new RuntimeWorkSession(
                        sizingMeter,
                        RuntimeWorkSession.Mode.PROCESSING);
        try {
            new SemanticOutputBoundary(
                    sizingSession,
                    sizingBlue,
                    null,
                    sizingMeter.semantic())
                    .admit(minimal.clone());
            exactGas = sizingMeter.totalGas();
        } finally {
            sizingSession.suspend();
            sizingBlue.close();
        }

        Blue blue = new Blue(provider);
        GasMeter meter =
                new GasMeter(
                        GasSchedule.contracts10(),
                        exactGas);
        RuntimeWorkSession session =
                new RuntimeWorkSession(
                        meter,
                        RuntimeWorkSession.Mode.PROCESSING);
        SemanticOutputBoundary boundary =
                new SemanticOutputBoundary(
                        session,
                        blue,
                        null,
                        meter.semantic());
        String firstBlueId = null;
        String repeatedBlueId = null;
        long remainingAfterFirst = -1L;
        Throwable admissionFailure = null;
        Throwable propagationFailure = null;
        String failureCounter = null;
        long totalGas = -1L;
        try {
            // when
            ExactBlueValue first =
                    boundary.admit(minimal);
            remainingAfterFirst = meter.remainingGas();
            ExactBlueValue repeated =
                    boundary.admit(minimal.clone());
            firstBlueId = first.blueId();
            repeatedBlueId = repeated.blueId();
            Throwable capturedAdmissionFailure = captureFailure(
                    () -> boundary.admit(noisy));
            admissionFailure = capturedAdmissionFailure;
            propagationFailure =
                    capturedAdmissionFailure
                            instanceof GasLimitExceededException
                            ? captureFailure(
                                    () -> session
                                            .propagateGasExhaustion(
                                                    (GasLimitExceededException)
                                                            capturedAdmissionFailure))
                            : null;
            failureCounter = capturedAdmissionFailure
                    instanceof GasLimitExceededException
                    ? ((GasLimitExceededException)
                    capturedAdmissionFailure).counter()
                    : null;
            totalGas = meter.totalGas();
        } finally {
            session.close();
            blue.close();
        }

        // then
        assertInstanceOf(
                GasLimitExceededException.class,
                admissionFailure);
        assertEquals(0L, remainingAfterFirst);
        assertEquals(firstBlueId, repeatedBlueId);
        assertEquals(
                "nodeIdentityEstablished",
                failureCounter);
        assertEquals(exactGas, totalGas);
        assertInstanceOf(
                GasLimitExceededException.class,
                propagationFailure);
        assertSame(admissionFailure, propagationFailure);
    }

    @Test
    void shouldVerifySemanticRejectionPoisonsSessionAndGasWinsOverSuspension() {
        // given
        Blue blue = new Blue();
        GasMeter meter =
                new GasMeter(
                        GasSchedule.contracts10(), 2L);
        RuntimeWorkSession session =
                new RuntimeWorkSession(
                        meter,
                        RuntimeWorkSession.Mode.PROCESSING);
        SemanticOutputBoundary boundary =
                new SemanticOutputBoundary(
                        session,
                        blue,
                        null,
                        meter.semantic());
        Throwable admissionFailure = null;
        Throwable ledgerFailure = null;
        Throwable lateSemanticFailure = null;
        Throwable suspensionFailure = null;
        boolean openAfterSuspension = true;
        long totalGas = -1L;
        try {
            // when
            admissionFailure = captureFailure(
                    () -> boundary.admit(
                            text(repeat(
                                    "x", 256))));
            ledgerFailure = captureFailure(
                    () -> session.openLedger(
                            "late-runtime",
                            Collections.singletonMap(
                                    "step", 1L)));
            lateSemanticFailure = captureFailure(
                    () -> boundary.admit(
                            text("late-semantic")));
            suspensionFailure =
                    captureFailure(session::suspend);
            openAfterSuspension = session.isOpen();
            totalGas = meter.totalGas();
        } finally {
            session.close();
            blue.close();
        }

        // then
        assertInstanceOf(
                GasLimitExceededException.class,
                admissionFailure);
        assertInstanceOf(
                IllegalStateException.class,
                ledgerFailure);
        assertInstanceOf(
                IllegalStateException.class,
                lateSemanticFailure);
        assertInstanceOf(
                GasLimitExceededException.class,
                suspensionFailure);
        assertSame(admissionFailure, suspensionFailure);
        assertFalse(openAfterSuspension);
        assertEquals(1L, totalGas);
    }

    @Test
    void shouldPreserveOriginalSemanticGasExceptionWithTryWithResourcesClose() {
        // given
        Blue blue = new Blue();
        GasMeter meter =
                new GasMeter(
                        GasSchedule.contracts10(), 0L);
        RuntimeWorkSession session =
                new RuntimeWorkSession(
                        meter,
                        RuntimeWorkSession.Mode.PROCESSING);
        SemanticOutputBoundary boundary =
                new SemanticOutputBoundary(
                        session,
                        blue,
                        null,
                        meter.semantic());
        Throwable failure = null;
        String failureCounter = null;
        int suppressedCount = -1;
        boolean sessionOpen = true;
        try {
            // when
            failure = captureFailure(
                    () -> {
                        try (AutoCloseable ignored =
                                     session::close) {
                            boundary.admit(
                                    text("gas"));
                        }
                    });
            failureCounter = failure
                    instanceof GasLimitExceededException
                    ? ((GasLimitExceededException) failure)
                    .counter() : null;
            suppressedCount =
                    failure == null
                            ? -1
                            : failure.getSuppressed().length;
            sessionOpen = session.isOpen();
        } finally {
            session.close();
            blue.close();
        }

        // then
        assertInstanceOf(
                GasLimitExceededException.class,
                failure);
        assertEquals(
                "nodeIdentityEstablished",
                failureCounter);
        assertEquals(0, suppressedCount);
        assertFalse(sessionOpen);
    }

    @Test
    void shouldVerifyInvalidMixedReferenceFailsWithoutAdmission() {
        // given
        try (Invocation invocation =
                     new Invocation(new Blue())) {
            String blueId =
                    DirectBlueIdCalculator.calculateBlueId(
                            new Node().value("valid"));
            Node mixed =
                    new Node()
                            .blueId(blueId)
                            .value("mixed");
            long before = invocation.totalGas();

            // when
            Throwable failure = captureFailure(
                    () -> invocation.boundary()
                            .admit(mixed));
            long after = invocation.totalGas();

            // then
            assertInstanceOf(RuntimeException.class, failure);
            assertEquals(before, after);
        }
    }

    @Test
    void shouldVerifyCyclicMemberRequiresProofAndRemainsOpaque() {
        // given
        Node cyclicSet =
                new Node().items(
                        new Node()
                                .name("Runtime Cyclic A")
                                .properties(
                                        "next",
                                        new Node().blueId(
                                                "this#1")),
                        new Node()
                                .name("Runtime Cyclic B")
                                .properties(
                                        "next",
                                        new Node().blueId(
                                                "this#0")));
        BasicNodeProvider provider =
                new BasicNodeProvider(
                        Collections.singletonList(
                                cyclicSet));
        String memberBlueId =
                provider.getBlueIdByName(
                        "Runtime Cyclic A");

        // when
        try (Invocation invocation =
                     new Invocation(new Blue(provider))) {
            long before = invocation.totalGas();
            ExactBlueValue admitted =
                    invocation.boundary().admit(
                            new Node().blueId(
                                    memberBlueId));

            // then
            assertEquals(
                    memberBlueId, admitted.blueId());
            assertTrue(admitted.isCyclicMember());
            assertTrue(
                    admitted.frozenValue()
                            .isReferenceOnly());
            assertEquals(
                    before,
                    invocation.totalGas(),
                    "an opaque proven member edge has no standalone identity construction to charge");
        }
    }

    @Test
    void shouldVerifyCyclicHandleCannotReplayProofAcrossInvocations() {
        // given
        Node cyclicSet =
                new Node().items(
                        new Node()
                                .name("Runtime Replay A")
                                .properties(
                                        "next",
                                        new Node().blueId(
                                                "this#1")),
                        new Node()
                                .name("Runtime Replay B")
                                .properties(
                                        "next",
                                        new Node().blueId(
                                                "this#0")));
        BasicNodeProvider provider =
                new BasicNodeProvider(
                        Collections.singletonList(
                                cyclicSet));
        String memberBlueId =
                provider.getBlueIdByName(
                        "Runtime Replay A");
        ExactBlueValue handle;
        Throwable replayFailure;

        // when
        try (Invocation first =
                     new Invocation(
                             new Blue(provider))) {
            handle = first.boundary().admit(
                    new Node().blueId(
                            memberBlueId));
        }
        try (Invocation second =
                     new Invocation(new Blue())) {
            replayFailure = captureFailure(
                    () -> second.boundary()
                            .admit(handle));
        }

        // then
        assertInstanceOf(
                InvalidExecutionEvidenceException.class,
                replayFailure);
    }

    @Test
    void shouldCarryHostedCapabilityOnlyWithinIssuingInvocation() {
        // given
        ExactBlueValue capability;
        ExactBlueValue carried;
        Throwable crossInvocationFailure;

        // when
        try (Invocation first = new Invocation(new Blue())) {
            capability = first.boundary().admit(
                    new Node().properties("value", text("exact")));
            carried = first.boundary().carryExactCapability(capability);
        }
        try (Invocation second = new Invocation(new Blue())) {
            crossInvocationFailure = captureFailure(
                    () -> second.boundary()
                            .carryExactCapability(capability));
        }

        // then
        assertSame(capability, carried);
        assertInstanceOf(
                InvalidExecutionEvidenceException.class,
                crossInvocationFailure);
    }

    @Test
    void shouldNotExposeExactValueLookupByBlueId() {
        // given
        Class<SemanticOutputBoundary> boundaryType =
                SemanticOutputBoundary.class;

        // when
        Throwable failure = captureFailure(
                () -> boundaryType.getMethod(
                        "carryExactValue",
                        String.class,
                        FrozenNode.class));

        // then
        assertInstanceOf(NoSuchMethodException.class, failure);
    }

    @Test
    void shouldVerifyInvocationMemoIsSharedAcrossProcessorPhases() {
        // given
        Blue blue = new Blue();
        ProcessorInvocationState execution =
                new ProcessorInvocationState(
                        blue.getDocumentProcessor(),
                        Nodes.emptyObject());
        execution.preflightScope("/");
        Node output =
                new Node().properties(
                        "value",
                        text("shared across phases"));
        ExactBlueValue first;
        ExactBlueValue repeated;
        long afterFirst;
        long afterRepeated;

        try {
            // when
            try (ProcessorExecutionContext phase =
                         execution.createContext(
                                 "/",
                                 execution.bundleForScope("/"),
                                 Nodes.emptyObject(),
                                 false)) {
                first = phase.semanticOutputBoundary()
                        .admit(output);
            }
            afterFirst =
                    execution.runtime().totalGas();
            try (ProcessorExecutionContext phase =
                         execution.createContext(
                                 "/",
                                 execution.bundleForScope("/"),
                                 new Node(),
                                 false)) {
                repeated =
                        phase.semanticOutputBoundary()
                                .admit(output.clone());
            }
            afterRepeated =
                    execution.runtime().totalGas();
        } finally {
            blue.close();
        }

        // then
        assertEquals(first.blueId(), repeated.blueId());
        assertEquals(afterFirst, afterRepeated);
    }

    @Test
    void shouldVerifyRetainedBoundaryRejectsUseAfterExecutionUnitCloses() {
        // given
        Invocation invocation =
                new Invocation(new Blue());
        SemanticOutputBoundary boundary =
                invocation.boundary();

        // when
        invocation.close();
        Throwable failure = captureFailure(
                () -> boundary.admit(text("late")));

        // then
        assertInstanceOf(IllegalStateException.class, failure);
    }

    private static Throwable captureFailure(
            ThrowingOperation operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    @FunctionalInterface
    private interface ThrowingOperation {
        void run() throws Exception;
    }

    private static Node text(String value) {
        return new Node()
                .type(new Node().blueId(
                        TEXT_TYPE_BLUE_ID))
                .value(value);
    }

    private static List<ScalarCase> scalarCases() {
        return Arrays.asList(
                new ScalarCase(
                        "text", "same scalar", TEXT_TYPE_BLUE_ID),
                new ScalarCase(
                        "integer", BigInteger.valueOf(42L),
                        INTEGER_TYPE_BLUE_ID),
                new ScalarCase(
                        "double", new BigDecimal("12.5"),
                        DOUBLE_TYPE_BLUE_ID),
                new ScalarCase(
                        "boolean", Boolean.TRUE,
                        BOOLEAN_TYPE_BLUE_ID));
    }

    private static void assertDirectObjectLimit(
            GasSchedule schedule,
            Node value,
            ProcessingSnapshotManager manager) {
        Blue blue = new Blue();
        GasMeter meter = new GasMeter(schedule);
        RuntimeWorkSession session = new RuntimeWorkSession(
                meter,
                RuntimeWorkSession.Mode.PROCESSING);
        SemanticOutputBoundary boundary = new SemanticOutputBoundary(
                session,
                blue,
                manager,
                meter.semantic());
        Throwable failure;
        try {
            failure = captureFailure(
                    () -> boundary.admit(value));
        } finally {
            session.close();
            blue.close();
        }
        PortableLimitExceededException portable = assertInstanceOf(
                PortableLimitExceededException.class,
                failure);
        assertEquals(
                GasScheduleConstants.PortableLimit.DIRECT_OBJECT_ENTRIES,
                portable.limitName());
        assertEquals(2L, portable.observed());
        assertEquals(1L, portable.limit());
        assertEquals(0L, meter.totalGas());
        assertTrue(meter.trace().isEmpty());
    }

    private static String repeat(String value, int count) {
        StringBuilder builder =
                new StringBuilder(
                        value.length() * count);
        for (int index = 0; index < count; index++) {
            builder.append(value);
        }
        return builder.toString();
    }

    private static final class Invocation
            implements AutoCloseable {
        private final Blue blue;
        private final ProcessorInvocationState execution;
        private final ProcessorExecutionContext context;
        private boolean closed;

        private Invocation(Blue blue) {
            this.blue = blue;
            DocumentProcessor owner =
                    blue.getDocumentProcessor();
            this.execution =
                    new ProcessorInvocationState(
                            owner, Nodes.emptyObject());
            execution.preflightScope("/");
            this.context =
                    execution.createContext(
                            "/",
                            execution.bundleForScope("/"),
                            Nodes.emptyObject(),
                            false);
        }

        private SemanticOutputBoundary boundary() {
            return context.semanticOutputBoundary();
        }

        private long totalGas() {
            return execution.runtime().totalGas();
        }

        private long counter(String counter) {
            return execution.runtime()
                    .conformanceTrace()
                    .counterQuantity(
                            "semantic", counter);
        }

        private List<GasTraceEntry> trace() {
            return execution.runtime()
                    .conformanceTrace()
                    .gas();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            context.close();
            blue.close();
        }
    }

    private static final class ScalarCase {
        private final String label;
        private final Object value;
        private final String typeBlueId;

        private ScalarCase(
                String label,
                Object value,
                String typeBlueId) {
            this.label = label;
            this.value = value;
            this.typeBlueId = typeBlueId;
        }
    }

    private static List<String> traceFingerprint(
            List<GasTraceEntry> trace) {
        List<String> fingerprint =
                new ArrayList<>(trace.size());
        for (GasTraceEntry entry : trace) {
            fingerprint.add(
                    entry.namespace()
                            + ":" + entry.counter()
                            + ":" + entry.quantity()
                            + ":" + entry.weight()
                            + ":" + entry.reason());
        }
        return fingerprint;
    }

    private static AdmissionAttempt attemptAdmission(
            Node output,
            ProcessingSnapshotManager manager,
            long gasLimit) {
        return attemptAdmission(
                new Blue(),
                output,
                manager,
                gasLimit);
    }

    private static AdmissionAttempt attemptAdmission(
            Blue blue,
            Node output,
            ProcessingSnapshotManager manager,
            long gasLimit) {
        GasMeter meter =
                new GasMeter(
                        GasSchedule.contracts10(),
                        gasLimit);
        RuntimeWorkSession session =
                new RuntimeWorkSession(
                        meter,
                        RuntimeWorkSession.Mode.PROCESSING);
        SemanticOutputBoundary boundary =
                new SemanticOutputBoundary(
                        session,
                        blue,
                        manager,
                        meter.semantic());
        String outcome;
        try {
            ExactBlueValue admitted =
                    boundary.admit(
                            output.clone());
            outcome =
                    "success:" + admitted.blueId();
        } catch (GasLimitExceededException exhaustion) {
            outcome =
                    "gas:"
                            + exhaustion.counter()
                            + ":" + exhaustion.quantity()
                            + ":" + exhaustion.weight();
        } finally {
            session.close();
            blue.close();
        }
        return new AdmissionAttempt(
                outcome,
                meter.totalGas(),
                traceFingerprint(
                        meter.trace()));
    }

    private static void assertSameAttempt(
            AdmissionAttempt expected,
            AdmissionAttempt actual,
            String message) {
        assertEquals(
                expected.outcome,
                actual.outcome,
                message);
        assertEquals(
                expected.totalGas,
                actual.totalGas,
                message);
        assertEquals(
                expected.trace,
                actual.trace,
                message);
    }

    private static final class AdmissionAttempt {
        private final String outcome;
        private final long totalGas;
        private final List<String> trace;

        private AdmissionAttempt(
                String outcome,
                long totalGas,
                List<String> trace) {
            this.outcome = outcome;
            this.totalGas = totalGas;
            this.trace = trace;
        }
    }

    private static final class TrackingBlue
            extends Blue {
        private final AtomicInteger canonicalizeCalls =
                new AtomicInteger();

        @Override
        public Node canonicalize(Node node) {
            canonicalizeCalls.incrementAndGet();
            return super.canonicalize(node);
        }
    }

    private static final class CountingSnapshotManager
            implements ProcessingSnapshotManager {
        private final AtomicInteger exactDemands =
                new AtomicInteger();

        @Override
        public ResolvedSnapshot fromDocument(
                Node document) {
            throw new AssertionError(
                    "provider demand was not expected");
        }

        @Override
        public FrozenNode materializeVerifiedExactReference(
                FrozenNode reference) {
            exactDemands.incrementAndGet();
            throw new ExecutionEvidenceUnavailableException(
                    "provider evidence is unavailable",
                    Collections.singleton(
                            reference.getReferenceBlueId()));
        }

        @Override
        public ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                JsonPatch patch) {
            throw new AssertionError(
                    "patching was not expected");
        }
    }

    private static final class FixedSnapshotManager
            implements ProcessingSnapshotManager {
        private final FrozenNode exact;

        private FixedSnapshotManager(
                FrozenNode exact) {
            this.exact = exact;
        }

        @Override
        public ResolvedSnapshot fromDocument(
                Node document) {
            throw new AssertionError(
                    "document snapshot was not expected");
        }

        @Override
        public FrozenNode materializeVerifiedExactReference(
                FrozenNode reference) {
            assertEquals(
                    exact.blueId(),
                    reference.getReferenceBlueId());
            return exact;
        }

        @Override
        public ResolvedSnapshot applyPatch(
                ResolvedSnapshot snapshot,
                JsonPatch patch) {
            throw new AssertionError(
                    "patching was not expected");
        }
    }
}
