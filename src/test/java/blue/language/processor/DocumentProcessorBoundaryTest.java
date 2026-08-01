package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.model.FrozenJsonPatch;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.ProcessEmbedded;
import blue.language.processor.registry.RuntimeBlueIds;
import blue.language.processor.ContractBundle;
import blue.language.processor.model.SetProperty;
import blue.language.utils.BlueIdCalculator;
import blue.language.utils.TypeClassResolver;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.*;

class DocumentProcessorBoundaryTest {

    @Test
    void shouldKeepProcessorRegistryViewLiveAndUnmodifiableAcrossRegistration() {
        // given
        ContractProcessorRegistry registry = new ContractProcessorRegistry();
        Map<String, ContractProcessor<? extends blue.language.processor.model.Contract>> view =
                registry.processors();
        Set<Map.Entry<String, ContractProcessor<? extends blue.language.processor.model.Contract>>> entries =
                view.entrySet();
        SetPropertyContractProcessor processor = new SetPropertyContractProcessor();

        // when
        registry.register("retained-live-view", processor);
        Throwable mutationFailure = captureFailure(view::clear);

        // then
        assertSame(processor, view.get("retained-live-view"));
        assertEquals(1, entries.size());
        assertTrue(entries.stream().anyMatch(entry ->
                entry.getKey().equals("retained-live-view") && entry.getValue() == processor));
        assertTrue(mutationFailure instanceof UnsupportedOperationException);
    }

    @Test
    void shouldWaitForCompositeRegistrationAcrossProcessorsDuringSharedConfigurationRead() throws Exception {
        // given
        String blueId = exactTypeId(
                "shared-composite-registration");
        ContractProcessorRegistry registry = new ContractProcessorRegistry();
        BlockingTypeClassResolver resolver = new BlockingTypeClassResolver(blueId);
        DocumentProcessor registeringProcessor = new DocumentProcessor(registry, resolver, null, null);
        DocumentProcessor readingProcessor = new DocumentProcessor(registry, resolver, null, null);
        SetPropertyContractProcessor contractProcessor = new SetPropertyContractProcessor();
        ExecutorService executor = daemonExecutor(2);

        // when
        try {
            Future<?> registration = executor.submit(
                    () -> registeringProcessor.registerContractProcessor(blueId, contractProcessor));
            boolean registrationPaused =
                    resolver.awaitRegistrationPause(
                            5,
                            TimeUnit.SECONDS);
            ContractProcessor<? extends blue.language.processor.model.Contract>
                    registeredProcessor =
                    registry.processors().get(blueId);
            CountDownLatch readStarted = new CountDownLatch(1);
            Future<Boolean> read = executor.submit(() -> {
                readStarted.countDown();
                return readingProcessor.isInitialized(new Node());
            });
            boolean sharedReadStarted =
                    readStarted.await(5, TimeUnit.SECONDS);
            Throwable prematureReadFailure = captureFailure(
                    () -> read.get(
                            200,
                            TimeUnit.MILLISECONDS));
            resolver.releaseRegistration();
            registration.get(5, TimeUnit.SECONDS);
            boolean initialized =
                    read.get(5, TimeUnit.SECONDS);
            Class<?> resolvedClass =
                    resolver.resolveClass(blueId);

            // then
            assertTrue(registrationPaused,
                    "registration did not reach the registry/resolver boundary");
            assertSame(contractProcessor, registeredProcessor,
                    "the registry mutation must precede resolver publication");
            assertTrue(sharedReadStarted,
                    "shared read did not start");
            assertTrue(prematureReadFailure instanceof TimeoutException,
                    "a shared read must not observe the half-published configuration");
            assertFalse(initialized);
            assertEquals(SetProperty.class, resolvedClass);
        } finally {
            resolver.releaseRegistration();
            executor.shutdownNow();
        }
    }

    @Test
    void shouldFailCrossProcessorRegistrationFromSharedReadCallbackWithoutDeadlocking() throws Exception {
        // given
        Node existingType = new Node().name("shared-read-callback");
        String existingBlueId = BlueIdCalculator.calculateBlueId(existingType);
        String reentrantBlueId = exactTypeId(
                "shared-read-callback-reentrant");
        ContractProcessorRegistry registry = new ContractProcessorRegistry();
        CallbackTypeClassResolver resolver = new CallbackTypeClassResolver(existingBlueId);
        DocumentProcessor readingProcessor = new DocumentProcessor(registry, resolver, null, null);
        DocumentProcessor registeringProcessor = new DocumentProcessor(registry, resolver, null, null);
        SetPropertyContractProcessor contractProcessor = new SetPropertyContractProcessor();
        readingProcessor.registerContractProcessor(
                existingBlueId, existingType, contractProcessor);
        resolver.onResolve(() -> registeringProcessor.registerContractProcessor(
                reentrantBlueId, new SetPropertyContractProcessor()));

        Node scope = new Node().contracts(new Node().properties("handler",
                new Node().type(new Node().blueId(existingBlueId))));
        ExecutorService executor = daemonExecutor(1);

        // when
        IllegalStateException failure;
        boolean reentrantRegistrationVisible;
        try {
            Future<IllegalStateException> result =
                    executor.submit(() -> captureFailure(
                            () -> readingProcessor.markersFor(
                                    scope, "/")));
            failure = getWithoutDeadlock(result);
            reentrantRegistrationVisible =
                    registry.processors()
                            .containsKey(reentrantBlueId);
        } finally {
            executor.shutdownNow();
        }

        // then
        assertEquals(IllegalStateException.class,
                failure.getClass());
        assertEquals("Document processor configuration cannot change during active processing",
                failure.getMessage());
        assertFalse(reentrantRegistrationVisible);
    }

    @Test
    void shouldNotBlockCrossProcessorCloseWhileRegistrationWaitsForSharedWrite() throws Exception {
        // given
        Node existingType = new Node().name("shared-close-callback");
        String existingBlueId = BlueIdCalculator.calculateBlueId(existingType);
        SignallingRegistry registry = new SignallingRegistry();
        CallbackTypeClassResolver resolver = new CallbackTypeClassResolver(existingBlueId);
        DocumentProcessor readingProcessor = new DocumentProcessor(registry, resolver, null, null);
        DocumentProcessor closingProcessor = new DocumentProcessor(registry, resolver, null, null);
        readingProcessor.registerContractProcessor(
                existingBlueId,
                existingType,
                new SetPropertyContractProcessor());
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch allowClose = new CountDownLatch(1);
        resolver.onResolve(() -> {
            callbackEntered.countDown();
            awaitUnchecked(allowClose);
            closingProcessor.close();
        });
        registry.armWriteAttempt();

        Node scope = new Node().contracts(new Node().properties("handler",
                new Node()
                        .type(new Node().blueId(existingBlueId))
                        .properties("channel", new Node().value("absent-channel"))));
        ExecutorService executor = daemonExecutor(2);

        // when
        boolean callbackObserved;
        boolean writeAttemptObserved;
        boolean readEmpty;
        ExecutionException failure;
        boolean closed;
        try {
            Future<Map<String, blue.language.processor.model.MarkerContract>> read =
                    executor.submit(() -> readingProcessor.markersFor(scope, "/"));
            callbackObserved =
                    callbackEntered.await(5, TimeUnit.SECONDS);
            Future<?> registration = executor.submit(() -> closingProcessor
                    .registerContractProcessor("after-close", new SetPropertyContractProcessor()));
            writeAttemptObserved =
                    registry.awaitWriteAttempt(
                            5, TimeUnit.SECONDS);

            allowClose.countDown();

            readEmpty =
                    read.get(5, TimeUnit.SECONDS).isEmpty();
            failure = captureFailure(
                    () -> registration.get(5, TimeUnit.SECONDS));
            closed = closingProcessor.isClosed();
        } finally {
            allowClose.countDown();
            executor.shutdownNow();
        }

        // then
        assertTrue(callbackObserved);
        assertTrue(writeAttemptObserved,
                "registration did not reach the shared configuration write gate");
        assertTrue(readEmpty);
        assertEquals(ExecutionException.class,
                failure.getClass());
        assertTrue(failure.getCause()
                instanceof IllegalStateException);
        assertEquals("Document processor is closed",
                failure.getCause().getMessage());
        assertTrue(closed);
    }

    @Test
    void shouldRejectEmptyPointerSegments() {
        // given
        Node document = new Node();
        DocumentProcessor processor = new DocumentProcessor();
        ProcessorInvocationState execution = new ProcessorInvocationState(processor, document);
        ContractBundle bundle = ContractBundle.builder().build();

        // when
        expectRunTermination(() -> execution.handlePatch(
                "/foo",
                bundle,
                JsonPatch.add(
                        "/foo//bar",
                        new Node().value("ok")),
                false));

        // then
        assertAtomicFailure(execution, document);
        assertFalse(execution.runtime()
                .isScopeTerminated("/foo"));
    }

    @Test
    void shouldDenyPatchingOutsideScope() {
        // given
        Node document = new Node();
        DocumentProcessor processor = new DocumentProcessor();
        ProcessorInvocationState execution = new ProcessorInvocationState(processor, document);
        ContractBundle bundle = ContractBundle.builder().build();

        // when
        expectRunTermination(() -> execution.handlePatch(
                "/foo",
                bundle,
                JsonPatch.add(
                        "/bar",
                        new Node().value("oops")),
                false));

        // then
        assertAtomicFailure(execution, document);
        assertFalse(execution.runtime()
                .isScopeTerminated("/foo"));
    }

    @Test
    void shouldPreventParentFromModifyingEmbeddedChildInterior() {
        // given
        Node document = new Node();
        DocumentProcessor processor = new DocumentProcessor();
        ProcessorInvocationState execution = new ProcessorInvocationState(processor, document);
        ProcessEmbedded embedded = new ProcessEmbedded().addPath("/child");
        ContractBundle bundle = ContractBundle.builder()
                .setEmbedded(embedded)
                .build();

        // when
        expectRunTermination(() -> execution.handlePatch(
                "/foo",
                bundle,
                JsonPatch.add(
                        "/foo/child/value",
                        new Node().value("nope")),
                false));

        // then
        assertAtomicFailure(execution, document);
        assertFalse(execution.runtime()
                .isScopeTerminated("/foo"));
    }

    @Test
    void shouldAllowParentToReplaceEntireEmbeddedChild() {
        // given
        Node child = new Node().properties("value", new Node().value("old"));
        Node parent = new Node().properties("child", child);
        Node document = new Node().properties("foo", parent);

        DocumentProcessor processor = new DocumentProcessor();
        ProcessorInvocationState execution = new ProcessorInvocationState(processor, document);
        ProcessEmbedded embedded = new ProcessEmbedded().addPath("/child");
        ContractBundle bundle = ContractBundle.builder()
                .setEmbedded(embedded)
                .build();

        // when
        execution.handlePatch("/foo", bundle, JsonPatch.replace("/foo/child", new Node().properties("next", new Node().value("fresh"))), false);
        Node foo = getProperty(document, "foo");
        Node replacedChild = getProperty(foo, "child");
        Node next = getProperty(replacedChild, "next");

        // then
        assertEquals("fresh", next.getValue());
    }

    @Test
    void shouldAllowParentToRemoveEntireEmbeddedChild() {
        // given
        Node child = new Node().properties("value", new Node().value("old"));
        Node parent = new Node().properties("child", child);
        Node document = new Node().properties("foo", parent);

        DocumentProcessor processor = new DocumentProcessor();
        ProcessorInvocationState execution = new ProcessorInvocationState(processor, document);
        ProcessEmbedded embedded = new ProcessEmbedded().addPath("/child");
        ContractBundle bundle = ContractBundle.builder()
                .setEmbedded(embedded)
                .build();

        // when
        execution.handlePatch("/foo", bundle, JsonPatch.remove("/foo/child"), false);
        Node foo = getProperty(document, "foo");
        Map<String, Node> props = foo.getProperties();

        // then
        assertTrue(props == null || !props.containsKey("child"));
    }

    @Test
    void shouldPreventScopeFromMutatingItsOwnRoot() {
        // given
        Node document = new Node().properties("foo", new Node().properties("value", new Node().value("existing")));
        DocumentProcessor processor = new DocumentProcessor();
        ProcessorInvocationState execution = new ProcessorInvocationState(processor, document);
        ContractBundle bundle = ContractBundle.builder().build();

        // when
        expectRunTermination(() -> execution.handlePatch(
                "/foo",
                bundle,
                JsonPatch.replace(
                        "/foo",
                        new Node().value("new")),
                false));
        Node foo = execution.result()
                .document().getAsNode("/foo");
        Node value = foo.getProperties().get("value");

        // then
        assertAtomicFailure(execution, document);
        assertFalse(execution.runtime()
                .isScopeTerminated("/foo"));
        assertEquals("existing", value.getValue());
    }

    @Test
    void shouldTreatRootPatchTargetAsFatal() {
        // given
        Node document = new Node().properties("foo", new Node().value("ok"));
        DocumentProcessor processor = new DocumentProcessor();
        ProcessorInvocationState execution = new ProcessorInvocationState(processor, document);
        ContractBundle bundle = ContractBundle.builder().build();

        // when
        expectRunTermination(() -> execution.handlePatch("/", bundle, JsonPatch.remove("/"), false));
        Node foo = execution.result().document()
                .getProperties().get("foo");

        // then
        assertAtomicFailure(execution, document);
        assertFalse(execution.runtime()
                .isScopeTerminated("/"));
        assertEquals("ok", foo.getValue());
    }

    @Test
    void shouldWriteProtectReservedRootContracts() {
        // given
        Node document = new Node();
        DocumentProcessor processor = new DocumentProcessor();
        ProcessorInvocationState execution = new ProcessorInvocationState(processor, document);
        ContractBundle bundle = ContractBundle.builder().build();

        // when
        expectRunTermination(() -> execution.handlePatch("/", bundle,
                JsonPatch.add("/contracts/checkpoint", new Node().value("forbidden")), false));

        // then
        assertAtomicFailure(execution, document);
        assertFalse(execution.runtime()
                .isScopeTerminated("/"));
    }

    @Test
    void shouldWriteProtectReservedContractsWithinScope() {
        // given
        Node document = new Node().properties("foo", new Node());
        DocumentProcessor processor = new DocumentProcessor();
        ProcessorInvocationState execution = new ProcessorInvocationState(processor, document);
        ContractBundle bundle = ContractBundle.builder().build();

        // when
        expectRunTermination(() -> execution.handlePatch(
                "/foo",
                bundle,
                JsonPatch.add(
                        "/foo/contracts/initialized",
                        new Node().value("bad")),
                false));
        Node fooNode = execution.result().document()
                .getProperties().get("foo");

        // then
        assertAtomicFailure(execution, document);
        assertFalse(execution.runtime()
                .isScopeTerminated("/foo"));
        assertNotNull(fooNode);
        assertNull(fooNode.getContracts());
    }

    @Test
    void shouldPreserveReservedEmbeddedMarkerWhenFrozenAndMutableContractsReplacementIsIdentical() {
        // given
        Node embedded = new Node()
                .type(new Node().blueId(
                        RuntimeBlueIds.PROCESS_EMBEDDED))
                .properties("paths", new Node().items(new Node().value("/child")));
        Node contracts = new Node().properties("embedded", embedded);
        Node source = new Node().properties("scope", new Node().contracts(contracts));
        ContractBundle bundle = ContractBundle.builder().build();

        ProcessorInvocationState mutableExecution =
                new ProcessorInvocationState(new DocumentProcessor(), source.clone());
        mutableExecution.handlePatch("/scope", bundle,
                JsonPatch.replace("/scope/contracts", contracts.clone()), false);

        // when
        ProcessorInvocationState frozenExecution =
                new ProcessorInvocationState(new DocumentProcessor(), source.clone());
        frozenExecution.handlePatchInputs("/scope", bundle,
                PatchInput.frozenList(Collections.singletonList(FrozenJsonPatch.from(
                        JsonPatch.replace("/scope/contracts", contracts.clone())))),
                false,
                null);

        // then
        assertFalse(mutableExecution.runtime().isScopeTerminated("/scope"));
        assertFalse(frozenExecution.runtime().isScopeTerminated("/scope"));
        assertEquals(
                BlueIdCalculator.calculateUncheckedBlueId(
                        mutableExecution.result().document().getAsNode("/scope").getContracts()),
                BlueIdCalculator.calculateUncheckedBlueId(
                        frozenExecution.result().document().getAsNode("/scope").getContracts()));
    }

    private Node getProperty(Node node, String key) {
        Map<String, Node> properties = node.getProperties();
        assertNotNull(properties, "Expected properties to exist for key '" + key + "'");
        Node child = properties.get(key);
        assertNotNull(child, "Missing property '" + key + "'");
        return child;
    }

    private static String exactTypeId(String name) {
        return BlueIdCalculator.calculateBlueId(
                new Node().name(name));
    }

    private void assertAtomicFailure(
            ProcessorInvocationState execution,
            Node exactInput) {
        DocumentProcessingResult result =
                execution.result();
        assertEquals(ProcessorStatus.RUNTIME_FATAL,
                result.status());
        assertFalse(result.commits());
        assertTrue(result.events().isEmpty());
        assertEquals(exactInput.toString(),
                result.document().toString());
        assertTrue(execution.runtime().isRunTerminated());
    }

    private void expectRunTermination(Runnable action) {
        try {
            action.run();
            fail("Expected run termination");
        } catch (RuntimeException ex) {
            assertEquals("blue.language.processor.RunTerminationException",
                    ex.getClass().getName());
        }
    }

    private static ExecutorService daemonExecutor(int threads) {
        return Executors.newFixedThreadPool(threads, task -> {
            Thread thread = new Thread(task, "document-processor-boundary-test");
            thread.setDaemon(true);
            return thread;
        });
    }

    private static <T> T getWithoutDeadlock(Future<T> future) throws Exception {
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            if (cause instanceof Exception) {
                throw (Exception) cause;
            }
            throw ex;
        }
    }

    private static void awaitUnchecked(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for test release");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("test wait interrupted", ex);
        }
    }

    private static final class SignallingRegistry extends ContractProcessorRegistry {
        private volatile CountDownLatch writeAttempt;

        private void armWriteAttempt() {
            writeAttempt = new CountDownLatch(1);
        }

        private boolean awaitWriteAttempt(long timeout, TimeUnit unit)
                throws InterruptedException {
            CountDownLatch current = writeAttempt;
            return current != null && current.await(timeout, unit);
        }

        @Override
        Lock configurationWriteLock() {
            Lock delegate = super.configurationWriteLock();
            CountDownLatch signal = writeAttempt;
            if (signal == null) {
                return delegate;
            }
            return new SignallingLock(delegate, signal);
        }
    }

    private static final class SignallingLock implements Lock {
        private final Lock delegate;
        private final CountDownLatch signal;

        private SignallingLock(Lock delegate, CountDownLatch signal) {
            this.delegate = delegate;
            this.signal = signal;
        }

        @Override
        public void lock() {
            signal.countDown();
            delegate.lock();
        }

        @Override
        public void lockInterruptibly() throws InterruptedException {
            signal.countDown();
            delegate.lockInterruptibly();
        }

        @Override
        public boolean tryLock() {
            signal.countDown();
            return delegate.tryLock();
        }

        @Override
        public boolean tryLock(long time, TimeUnit unit) throws InterruptedException {
            signal.countDown();
            return delegate.tryLock(time, unit);
        }

        @Override
        public void unlock() {
            delegate.unlock();
        }

        @Override
        public Condition newCondition() {
            return delegate.newCondition();
        }
    }

    private static final class BlockingTypeClassResolver extends TypeClassResolver {
        private final String blockingBlueId;
        private final CountDownLatch registrationPaused = new CountDownLatch(1);
        private final CountDownLatch registrationReleased = new CountDownLatch(1);

        private BlockingTypeClassResolver(String blockingBlueId) {
            this.blockingBlueId = blockingBlueId;
        }

        @Override
        public TypeClassResolver register(String blueId, Class<?> clazz) {
            if (blockingBlueId.equals(blueId)) {
                registrationPaused.countDown();
                awaitRelease();
            }
            return super.register(blueId, clazz);
        }

        private boolean awaitRegistrationPause(long timeout, TimeUnit unit) throws InterruptedException {
            return registrationPaused.await(timeout, unit);
        }

        private void releaseRegistration() {
            registrationReleased.countDown();
        }

        private void awaitRelease() {
            try {
                if (!registrationReleased.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("timed out waiting to release registration");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("registration interrupted", ex);
            }
        }
    }

    private static final class CallbackTypeClassResolver extends TypeClassResolver {
        private final String callbackBlueId;
        private final AtomicBoolean callbackPending = new AtomicBoolean();
        private volatile Runnable callback;

        private CallbackTypeClassResolver(String callbackBlueId) {
            this.callbackBlueId = callbackBlueId;
        }

        private void onResolve(Runnable callback) {
            this.callback = callback;
            callbackPending.set(true);
        }

        @Override
        public Class<?> resolveClass(String blueId) {
            Runnable current = callback;
            if (callbackBlueId.equals(blueId)
                    && current != null
                    && callbackPending.compareAndSet(true, false)) {
                current.run();
            }
            return super.resolveClass(blueId);
        }
    }
}
