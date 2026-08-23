package blue.language.processor.closure;

import blue.language.processor.DocumentProcessor;
import blue.language.model.Node;
import blue.language.processor.ManagedRootSubscriptionSurface;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Production composition root for affected-closure admission and processing.
 *
 * <p>Every invocation executes against one read-locked configuration revision
 * of the supplied ordinary {@link DocumentProcessor}. A no-argument instance
 * owns a default processor generation. An instance created with an existing
 * processor borrows it and never closes it.</p>
 *
 * <p>This facade intentionally lives in the closure package. It keeps closure
 * model types out of the ordinary processor package while ensuring that the
 * same configured runtime and lifecycle boundary are used for every isolated
 * managed-document step.</p>
 */
public final class BlueClosureContracts
        implements ClosureProcessor, AutoCloseable {

    private final DocumentProcessor owner;
    private final DefaultClosureProcessor processor;
    private final boolean ownsOwner;
    private boolean closed;

    /** Creates a composition root that owns a default processor generation. */
    public BlueClosureContracts() {
        this(new DocumentProcessor(), null, true);
    }

    /**
     * Creates an owning default composition root with an evidence observer.
     *
     * @param observer supplemental implementation-evidence observer
     */
    public BlueClosureContracts(ClosureExecutionObserver observer) {
        this(new DocumentProcessor(), observer, true);
    }

    /**
     * Creates a composition root borrowing an existing processor generation.
     *
     * @param owner configured ordinary document processor
     */
    public BlueClosureContracts(DocumentProcessor owner) {
        this(owner, null, false);
    }

    /**
     * Creates a borrowing composition root with an evidence observer.
     *
     * @param owner configured ordinary document processor
     * @param observer supplemental implementation-evidence observer
     */
    public BlueClosureContracts(
            DocumentProcessor owner,
            ClosureExecutionObserver observer) {
        this(owner, observer, false);
    }

    private BlueClosureContracts(
            DocumentProcessor owner,
            ClosureExecutionObserver observer,
            boolean ownsOwner) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.processor = observer == null
                ? new DefaultClosureProcessor(owner)
                : new DefaultClosureProcessor(owner, observer);
        this.ownsOwner = ownsOwner;
    }

    /**
     * Processes one verified affected-closure invocation atomically against a
     * captured ordinary runtime revision.
     *
     * @param input exact invocation input and evidence
     * @return complete result or exact retry disposition
     */
    @Override
    public synchronized ClosureAttemptResult processClosure(
            final ClosureInvocationInput input) {
        ensureOpen();
        return owner.withCapturedConfiguration(
                new Supplier<ClosureAttemptResult>() {
                    @Override
                    public ClosureAttemptResult get() {
                        return processor.processClosure(input);
                    }
                });
    }

    /**
     * Admits one verified closure candidate against a captured ordinary
     * runtime revision.
     *
     * @param input exact admission invocation input and evidence
     * @return complete admission result or exact retry disposition
     */
    @Override
    public synchronized ClosureAttemptResult admitClosure(
            final ClosureInvocationInput input) {
        ensureOpen();
        return owner.withCapturedConfiguration(
                new Supplier<ClosureAttemptResult>() {
                    @Override
                    public ClosureAttemptResult get() {
                        return processor.admitClosure(input);
                    }
                });
    }

    /**
     * Admits one verified closure candidate through the complete lifecycle work
     * and event queues against a captured ordinary runtime revision.
     *
     * @param input exact admission invocation input and evidence
     * @return complete admission result or exact retry disposition
     */
    @Override
    public synchronized ClosureAttemptResult admitClosureWithLifecycleQueue(
            final ClosureInvocationInput input) {
        ensureOpen();
        return owner.withCapturedConfiguration(
                new Supplier<ClosureAttemptResult>() {
                    @Override
                    public ClosureAttemptResult get() {
                        return processor.admitClosureWithLifecycleQueue(input);
                    }
                });
    }

    /**
     * Projects the exact externally routable surface of one independently
     * managed Root without traversing a Process Embedded declaration.
     *
     * <p>The projection uses the same captured processor configuration and
     * deterministic header-function boundary as closure execution. It is
     * intended for host publication after a verified closure result.</p>
     *
     * @param exactRoot exact independently managed Root
     * @return immutable Root-only Channel and external-subscription surface
     */
    public synchronized ManagedRootSubscriptionSurface
            projectRootSubscriptionSurface(final Node exactRoot) {
        ensureOpen();
        Objects.requireNonNull(exactRoot, "exactRoot");
        return owner.withCapturedConfiguration(
                new Supplier<ManagedRootSubscriptionSurface>() {
                    @Override
                    public ManagedRootSubscriptionSurface get() {
                        try (ManagedDocumentStepProcessor steps =
                                     new ManagedDocumentStepProcessor(owner)) {
                            return steps.projectRootSubscriptionSurface(
                                    exactRoot);
                        }
                    }
                });
    }

    /**
     * Closes this composition root and its ordinary processor when owned.
     * Borrowed processor generations remain live.
     */
    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            if (ownsOwner) {
                owner.close();
            }
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "Blue closure contracts composition root is closed");
        }
    }
}
