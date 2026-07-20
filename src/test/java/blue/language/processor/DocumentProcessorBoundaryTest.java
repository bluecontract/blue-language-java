package blue.language.processor;

import blue.language.model.Node;
import blue.language.processor.contracts.SetPropertyContractProcessor;
import blue.language.processor.model.FrozenJsonPatch;
import blue.language.processor.model.JsonPatch;
import blue.language.processor.model.ProcessEmbedded;
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

import static org.junit.jupiter.api.Assertions.*;

class DocumentProcessorBoundaryTest {

    @Test
    void processorRegistryViewRemainsLiveAndUnmodifiableAcrossRegistration() {
        ContractProcessorRegistry registry = new ContractProcessorRegistry();
        Map<String, ContractProcessor<? extends blue.language.processor.model.Contract>> view =
                registry.processors();
        Set<Map.Entry<String, ContractProcessor<? extends blue.language.processor.model.Contract>>> entries =
                view.entrySet();
        SetPropertyContractProcessor processor = new SetPropertyContractProcessor();

        registry.register("retained-live-view", processor);

        assertSame(processor, view.get("retained-live-view"));
        assertEquals(1, entries.size());
        assertTrue(entries.stream().anyMatch(entry ->
                entry.getKey().equals("retained-live-view") && entry.getValue() == processor));
        assertThrows(UnsupportedOperationException.class, view::clear);
    }

    @Test
    void sharedConfigurationReadWaitsForCompositeRegistrationAcrossProcessors() throws Exception {
        String blueId = "shared-composite-registration";
        ContractProcessorRegistry registry = new ContractProcessorRegistry();
        BlockingTypeClassResolver resolver = new BlockingTypeClassResolver(blueId);
        DocumentProcessor registeringProcessor = new DocumentProcessor(registry, resolver, null, null);
        DocumentProcessor readingProcessor = new DocumentProcessor(registry, resolver, null, null);
        SetPropertyContractProcessor contractProcessor = new SetPropertyContractProcessor();
        ExecutorService executor = daemonExecutor(2);

        try {
            Future<?> registration = executor.submit(
                    () -> registeringProcessor.registerContractProcessor(blueId, contractProcessor));

            assertTrue(resolver.awaitRegistrationPause(5, TimeUnit.SECONDS),
                    "registration did not reach the registry/resolver boundary");
            assertSame(contractProcessor, registry.processors().get(blueId),
                    "the registry mutation must precede resolver publication");

            CountDownLatch readStarted = new CountDownLatch(1);
            Future<Boolean> read = executor.submit(() -> {
                readStarted.countDown();
                return readingProcessor.isInitialized(new Node());
            });
            assertTrue(readStarted.await(5, TimeUnit.SECONDS), "shared read did not start");
            assertThrows(TimeoutException.class,
                    () -> read.get(200, TimeUnit.MILLISECONDS),
                    "a shared read must not observe the half-published configuration");

            resolver.releaseRegistration();
            registration.get(5, TimeUnit.SECONDS);
            assertFalse(read.get(5, TimeUnit.SECONDS));
            assertEquals(SetProperty.class, resolver.resolveClass(blueId));
        } finally {
            resolver.releaseRegistration();
            executor.shutdownNow();
        }
    }

    @Test
    void crossProcessorRegistrationFromSharedReadCallbackFailsInsteadOfDeadlocking() throws Exception {
        String existingBlueId = "shared-read-callback";
        String reentrantBlueId = "shared-read-callback-reentrant";
        ContractProcessorRegistry registry = new ContractProcessorRegistry();
        CallbackTypeClassResolver resolver = new CallbackTypeClassResolver(existingBlueId);
        DocumentProcessor readingProcessor = new DocumentProcessor(registry, resolver, null, null);
        DocumentProcessor registeringProcessor = new DocumentProcessor(registry, resolver, null, null);
        SetPropertyContractProcessor contractProcessor = new SetPropertyContractProcessor();
        readingProcessor.registerContractProcessor(existingBlueId, contractProcessor);
        resolver.onResolve(() -> registeringProcessor.registerContractProcessor(
                reentrantBlueId, new SetPropertyContractProcessor()));

        Node scope = new Node().contracts(new Node().properties("handler",
                new Node().type(new Node().blueId(existingBlueId))));
        ExecutorService executor = daemonExecutor(1);
        try {
            Future<IllegalStateException> result = executor.submit(() -> assertThrows(
                    IllegalStateException.class,
                    () -> readingProcessor.markersFor(scope, "/")));

            IllegalStateException failure = getWithoutDeadlock(result);
            assertEquals("Document processor configuration cannot change during active processing",
                    failure.getMessage());
            assertFalse(registry.processors().containsKey(reentrantBlueId));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void registrationWaitingForSharedWriteDoesNotBlockCrossProcessorClose() throws Exception {
        String existingBlueId = "shared-close-callback";
        SignallingRegistry registry = new SignallingRegistry();
        CallbackTypeClassResolver resolver = new CallbackTypeClassResolver(existingBlueId);
        DocumentProcessor readingProcessor = new DocumentProcessor(registry, resolver, null, null);
        DocumentProcessor closingProcessor = new DocumentProcessor(registry, resolver, null, null);
        readingProcessor.registerContractProcessor(
                existingBlueId, new SetPropertyContractProcessor());
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
        try {
            Future<Map<String, blue.language.processor.model.MarkerContract>> read =
                    executor.submit(() -> readingProcessor.markersFor(scope, "/"));
            assertTrue(callbackEntered.await(5, TimeUnit.SECONDS));
            Future<?> registration = executor.submit(() -> closingProcessor
                    .registerContractProcessor("after-close", new SetPropertyContractProcessor()));
            assertTrue(registry.awaitWriteAttempt(5, TimeUnit.SECONDS),
                    "registration did not reach the shared configuration write gate");

            allowClose.countDown();

            assertTrue(read.get(5, TimeUnit.SECONDS).isEmpty());
            ExecutionException failure = assertThrows(
                    ExecutionException.class,
                    () -> registration.get(5, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertEquals("Document processor is closed", failure.getCause().getMessage());
            assertTrue(closingProcessor.isClosed());
        } finally {
            allowClose.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsEmptyPointerSegments() {
        Node document = new Node();
        DocumentProcessor processor = new DocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(processor, document);
        ContractBundle bundle = ContractBundle.builder().build();

        execution.handlePatch("/foo", bundle, JsonPatch.add("/foo//bar", new Node().value("ok")), false);

        Node resultDoc = execution.result().document();
        Node terminated = resultDoc.getAsNode("/foo/contracts/terminated");
        assertNotNull(terminated);
        assertEquals("fatal", terminated.getProperties().get("cause").getValue());
        assertTrue(execution.runtime().isScopeTerminated("/foo"));
    }

    @Test
    void deniesPatchingOutsideScope() {
        Node document = new Node();
        DocumentProcessor processor = new DocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(processor, document);
        ContractBundle bundle = ContractBundle.builder().build();

        execution.handlePatch("/foo", bundle, JsonPatch.add("/bar", new Node().value("oops")), false);

        Node resultDoc = execution.result().document();
        Node contracts = resultDoc.getAsNode("/foo/contracts");
        Map<String, Node> contractProps = contracts.getProperties();
        assertNotNull(contractProps);
        Node terminated = contractProps.get("terminated");
        assertNotNull(terminated);
        assertEquals("fatal", terminated.getProperties().get("cause").getValue());
        assertTrue(execution.runtime().isScopeTerminated("/foo"));
        Node foo = resultDoc.getAsNode("/foo");
        Map<String, Node> fooProps = foo.getProperties();
        assertFalse(fooProps != null && fooProps.containsKey("bar"));
    }

    @Test
    void parentCannotModifyEmbeddedChildInterior() {
        Node document = new Node();
        DocumentProcessor processor = new DocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(processor, document);
        ProcessEmbedded embedded = new ProcessEmbedded().addPath("/child");
        ContractBundle bundle = ContractBundle.builder()
                .setEmbedded(embedded)
                .build();

        execution.handlePatch("/foo", bundle, JsonPatch.add("/foo/child/value", new Node().value("nope")), false);

        Node resultDoc = execution.result().document();
        Node contracts = resultDoc.getAsNode("/foo/contracts");
        Map<String, Node> contractProps = contracts.getProperties();
        assertNotNull(contractProps);
        Node terminated = contractProps.get("terminated");
        assertNotNull(terminated);
        assertEquals("fatal", terminated.getProperties().get("cause").getValue());
        assertTrue(execution.runtime().isScopeTerminated("/foo"));
        Node foo = resultDoc.getAsNode("/foo");
        Map<String, Node> fooProps = foo.getProperties();
        assertFalse(fooProps != null && fooProps.containsKey("child"));
    }

    @Test
    void parentMayReplaceEntireEmbeddedChild() {
        Node child = new Node().properties("value", new Node().value("old"));
        Node parent = new Node().properties("child", child);
        Node document = new Node().properties("foo", parent);

        DocumentProcessor processor = new DocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(processor, document);
        ProcessEmbedded embedded = new ProcessEmbedded().addPath("/child");
        ContractBundle bundle = ContractBundle.builder()
                .setEmbedded(embedded)
                .build();

        execution.handlePatch("/foo", bundle, JsonPatch.replace("/foo/child", new Node().properties("next", new Node().value("fresh"))), false);

        Node foo = getProperty(document, "foo");
        Node replacedChild = getProperty(foo, "child");
        Node next = getProperty(replacedChild, "next");
        assertEquals("fresh", next.getValue());
    }

    @Test
    void parentMayRemoveEntireEmbeddedChild() {
        Node child = new Node().properties("value", new Node().value("old"));
        Node parent = new Node().properties("child", child);
        Node document = new Node().properties("foo", parent);

        DocumentProcessor processor = new DocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(processor, document);
        ProcessEmbedded embedded = new ProcessEmbedded().addPath("/child");
        ContractBundle bundle = ContractBundle.builder()
                .setEmbedded(embedded)
                .build();

        execution.handlePatch("/foo", bundle, JsonPatch.remove("/foo/child"), false);

        Node foo = getProperty(document, "foo");
        Map<String, Node> props = foo.getProperties();
        assertTrue(props == null || !props.containsKey("child"));
    }

    @Test
    void scopeCannotMutateItsOwnRoot() {
        Node document = new Node().properties("foo", new Node().properties("value", new Node().value("existing")));
        DocumentProcessor processor = new DocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(processor, document);
        ContractBundle bundle = ContractBundle.builder().build();

        execution.handlePatch("/foo", bundle, JsonPatch.replace("/foo", new Node().value("new")), false);

        Node resultDoc = execution.result().document();
        Node contracts = resultDoc.getAsNode("/foo/contracts");
        Map<String, Node> contractProps = contracts.getProperties();
        assertNotNull(contractProps);
        Node terminated = contractProps.get("terminated");
        assertNotNull(terminated);
        assertEquals("fatal", terminated.getProperties().get("cause").getValue());
        assertTrue(execution.runtime().isScopeTerminated("/foo"));
        Node foo = resultDoc.getAsNode("/foo");
        Node value = foo.getProperties().get("value");
        assertEquals("existing", value.getValue());
    }

    @Test
    void rootPatchTargetIsFatal() {
        Node document = new Node().properties("foo", new Node().value("ok"));
        DocumentProcessor processor = new DocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(processor, document);
        ContractBundle bundle = ContractBundle.builder().build();

        expectRunTermination(() -> execution.handlePatch("/", bundle, JsonPatch.remove("/"), false));

        Node resultDoc = execution.result().document();
        Node contracts = resultDoc.getAsNode("/contracts");
        Map<String, Node> contractProps = contracts.getProperties();
        assertNotNull(contractProps);
        Node terminated = contractProps.get("terminated");
        assertNotNull(terminated);
        assertEquals("fatal", terminated.getProperties().get("cause").getValue());
        assertTrue(execution.runtime().isScopeTerminated("/"));
        Node foo = resultDoc.getProperties().get("foo");
        assertEquals("ok", foo.getValue());
    }

    @Test
    void reservedRootContractsAreWriteProtected() {
        Node document = new Node();
        DocumentProcessor processor = new DocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(processor, document);
        ContractBundle bundle = ContractBundle.builder().build();

        expectRunTermination(() -> execution.handlePatch("/", bundle,
                JsonPatch.add("/contracts/checkpoint", new Node().value("forbidden")), false));

        Node resultDoc = execution.result().document();
        Node contracts = resultDoc.getAsNode("/contracts");
        Map<String, Node> contractProps = contracts.getProperties();
        assertNotNull(contractProps);
        Node terminated = contractProps.get("terminated");
        assertNotNull(terminated);
        assertEquals("fatal", terminated.getProperties().get("cause").getValue());
        assertTrue(execution.runtime().isScopeTerminated("/"));
    }

    @Test
    void reservedContractsWithinScopeAreWriteProtected() {
        Node document = new Node().properties("foo", new Node());
        DocumentProcessor processor = new DocumentProcessor();
        ProcessorEngine.Execution execution = new ProcessorEngine.Execution(processor, document);
        ContractBundle bundle = ContractBundle.builder().build();

        execution.handlePatch("/foo", bundle,
                JsonPatch.add("/foo/contracts/initialized", new Node().value("bad")), false);

        Node resultDoc = execution.result().document();
        Node contracts = resultDoc.getAsNode("/foo/contracts");
        Map<String, Node> contractProps = contracts.getProperties();
        assertNotNull(contractProps);
        Node terminated = contractProps.get("terminated");
        assertNotNull(terminated);
        assertEquals("fatal", terminated.getProperties().get("cause").getValue());
        assertTrue(execution.runtime().isScopeTerminated("/foo"));
        Node fooNode = resultDoc.getProperties().get("foo");
        assertNotNull(fooNode);
        assertTrue(fooNode.getContracts() != null);
    }

    @Test
    void frozenAndMutableIdenticalContractsReplacementPreserveReservedEmbeddedMarker() {
        Node embedded = new Node()
                .type(new Node().blueId(
                        "8FVc8MPz6DcTMgcY3RXU6EBpGa9arWPJ141K2H86yi8Q"))
                .properties("paths", new Node().items(new Node().value("/child")));
        Node contracts = new Node().properties("embedded", embedded);
        Node source = new Node().properties("scope", new Node().contracts(contracts));
        ContractBundle bundle = ContractBundle.builder().build();

        ProcessorEngine.Execution mutableExecution =
                new ProcessorEngine.Execution(new DocumentProcessor(), source.clone());
        mutableExecution.handlePatch("/scope", bundle,
                JsonPatch.replace("/scope/contracts", contracts.clone()), false);

        ProcessorEngine.Execution frozenExecution =
                new ProcessorEngine.Execution(new DocumentProcessor(), source.clone());
        frozenExecution.handlePatchInputs("/scope", bundle,
                PatchInput.frozenList(Collections.singletonList(FrozenJsonPatch.from(
                        JsonPatch.replace("/scope/contracts", contracts.clone())))),
                false,
                null);

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
