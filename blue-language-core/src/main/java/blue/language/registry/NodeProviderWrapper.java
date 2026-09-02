package blue.language.registry;

import blue.language.model.Node;
import blue.language.provider.NodeProvider;
import blue.language.provider.NodeProviderResult;
import blue.language.provider.PotentialBlueIdNodeProvider;
import blue.language.provider.SequentialNodeProvider;
import blue.language.provider.VerifiedNodeProvider;
import blue.language.provider.VerifyingNodeProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Builds the verified provider graph used by Language operations.
 *
 * <p>The Language bootstrap provider is inserted ahead of caller providers,
 * and every external result-producing leaf is independently evidence-verified.
 * Runtime-specific providers must be composed explicitly by the owning
 * runtime before this Language boundary is applied.</p>
 */
public class NodeProviderWrapper {

    /**
     * Creates a provider-graph wrapper helper.
     */
    public NodeProviderWrapper() {
    }

    /**
     * Returns a provider graph with bootstrap and verification boundaries.
     *
     * @param originalProvider caller-supplied provider graph
     * @return secured provider graph
     */
    public static NodeProvider wrap(NodeProvider originalProvider) {
        NodeProvider verifiedProvider =
                verifyProviderGraph(originalProvider);
        if (verifiedProvider.getClass()
                == VerificationOnlyProvider.class) {
            return verifiedProvider;
        }
        if (hasBootstrapAtTopLevel(verifiedProvider)) {
            return verifiedProvider;
        }
        return new SequentialNodeProvider(
                Arrays.asList(
                        BootstrapProvider.INSTANCE,
                        verifiedProvider
                )
        );
    }

    /**
     * Returns an independently verified view of exactly the supplied provider
     * graph, without inserting the Language bootstrap provider or any other
     * fallback.
     *
     * <p>This hook lets Language-owned bridges preserve a deliberate
     * fallback-free boundary through nested Language components. The private
     * return marker cannot be imitated by an ordinary provider implementation;
     * ordinary runtime construction still restores its normal bootstrap
     * composition.</p>
     *
     * @param originalProvider complete caller-supplied provider graph
     * @return verification-only view with no implicit fallback
     */
    protected static NodeProvider verifyOnly(
            NodeProvider originalProvider) {
        if (originalProvider != null
                && originalProvider.getClass()
                == VerificationOnlyProvider.class) {
            return originalProvider;
        }
        return new VerificationOnlyProvider(
                verifyProviderGraph(originalProvider));
    }

    /**
     * Preserves a verification-only provider through one operation guard.
     *
     * <p>The provider graph is verified before the private guard wrapper is
     * installed. The guard receives only a synchronous {@link Runnable} for
     * the already-verified delegate call, so it can hold lifecycle admission
     * around that complete call but cannot provide substitute evidence.</p>
     *
     * @param originalProvider complete caller-supplied provider graph
     * @param operationGuard guard that invokes each delegate call once while
     *                       holding the required operation admission
     * @return guarded verification-only view with no implicit fallback
     */
    protected static NodeProvider verifyOnlyGuarded(
            NodeProvider originalProvider,
            Consumer<Runnable> operationGuard) {
        VerificationOnlyProvider verifiedProvider =
                (VerificationOnlyProvider) verifyOnly(originalProvider);
        return new VerificationOnlyProvider(
                new GuardedVerifiedProvider(
                        verifiedProvider.delegate,
                        Objects.requireNonNull(
                                operationGuard, "operationGuard")));
    }

    /**
     * Binary-compatibility entry point for callers compiled against the
     * legacy method name.
     *
     * <p>Language 1.0 has no host-trusted provider bypass. Despite the legacy
     * name, this method applies the same strict direct-node verification as
     * {@link #wrap(NodeProvider)}.</p>
     *
     * @param originalProvider caller-supplied provider graph
     * @return secured provider graph
     */
    public static NodeProvider unverified(
            NodeProvider originalProvider) {
        NodeProvider verifiedProvider =
                verifyProviderGraph(originalProvider);
        if (verifiedProvider.getClass()
                == VerificationOnlyProvider.class) {
            verifiedProvider = ((VerificationOnlyProvider)
                    verifiedProvider).delegate;
        }
        if (hasBootstrapAtTopLevel(verifiedProvider)) {
            return verifiedProvider;
        }
        return new SequentialNodeProvider(
                Arrays.asList(
                        BootstrapProvider.INSTANCE,
                        verifiedProvider
                )
        );
    }

    /**
     * Reports the Language 1.0 trust rule to released callers that still
     * probe the former host-trust marker.
     *
     * @param provider provider being probed
     * @return always {@code false}
     */
    public static boolean isExplicitlyHostTrusted(
            NodeProvider provider) {
        return false;
    }

    /**
     * Secures every result-producing leaf independently. This preserves
     * cyclic-set-aware verification while preventing one verified sibling
     * from conferring trust on an unrelated plain sibling.
     */
    private static NodeProvider verifyProviderGraph(
            NodeProvider provider) {
        if (provider == null) {
            throw new NullPointerException("provider");
        }
        if (provider == BootstrapProvider.INSTANCE
                || provider.getClass()
                == VerificationOnlyProvider.class
                || provider.getClass()
                == VerifyingNodeProvider.class
                || provider.getClass()
                == VerifiedNodeProvider.class) {
            return provider;
        }
        if (provider.getClass()
                == PotentialBlueIdNodeProvider.class) {
            PotentialBlueIdNodeProvider filtered =
                    (PotentialBlueIdNodeProvider) provider;
            NodeProvider verifiedDelegate =
                    verifyProviderGraph(filtered.delegate());
            return verifiedDelegate == filtered.delegate()
                    ? filtered
                    : new PotentialBlueIdNodeProvider(
                    verifiedDelegate);
        }
        if (provider.getClass()
                == SequentialNodeProvider.class) {
            List<NodeProvider> providers =
                    ((SequentialNodeProvider) provider)
                            .getNodeProviders();
            List<NodeProvider> verified =
                    new ArrayList<>(providers.size());
            boolean changed = false;
            for (NodeProvider member : providers) {
                NodeProvider secured =
                        verifyProviderGraph(member);
                verified.add(secured);
                changed |= secured != member;
            }
            return changed
                    ? new SequentialNodeProvider(verified)
                    : provider;
        }
        return new VerifyingNodeProvider(provider);
    }

    private static boolean hasBootstrapAtTopLevel(
            NodeProvider provider) {
        return provider instanceof SequentialNodeProvider
                && provider.getClass()
                == SequentialNodeProvider.class
                && ((SequentialNodeProvider) provider)
                .getNodeProviders().stream()
                .anyMatch(member ->
                        member == BootstrapProvider.INSTANCE);
    }

    /** Unforgeable marker preserving an explicitly fallback-free graph. */
    private static final class VerificationOnlyProvider
            implements NodeProvider {
        private final NodeProvider delegate;

        private VerificationOnlyProvider(NodeProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<Node> fetchByBlueId(
                String blueId) {
            return delegate.fetchByBlueId(blueId);
        }

        @Override
        public NodeProviderResult fetchResultByBlueId(
                String blueId) {
            return delegate.fetchResultByBlueId(blueId);
        }
    }

    /** Lifecycle wrapper that cannot substitute unverified provider results. */
    private static final class GuardedVerifiedProvider
            implements NodeProvider {
        private final NodeProvider delegate;
        private final Consumer<Runnable> operationGuard;

        private GuardedVerifiedProvider(
                NodeProvider delegate,
                Consumer<Runnable> operationGuard) {
            this.delegate = delegate;
            this.operationGuard = operationGuard;
        }

        @Override
        public List<Node> fetchByBlueId(String blueId) {
            return invoke(() -> delegate.fetchByBlueId(blueId));
        }

        @Override
        public NodeProviderResult fetchResultByBlueId(String blueId) {
            return invoke(() -> delegate.fetchResultByBlueId(blueId));
        }

        private <T> T invoke(Supplier<T> providerCall) {
            GuardedCall<T> guardedCall = new GuardedCall<>(providerCall);
            operationGuard.accept(guardedCall);
            return guardedCall.result();
        }
    }

    /** Enforces a synchronous, exactly-once guarded delegate invocation. */
    private static final class GuardedCall<T> implements Runnable {
        private static final String INVALID_GUARD_MESSAGE =
                "Provider operation guard must invoke its delegate "
                        + "exactly once and synchronously";

        private final Supplier<T> providerCall;
        private final Thread ownerThread;

        private int invocationCount;
        private boolean completed;
        private T value;
        private RuntimeException runtimeFailure;
        private Error errorFailure;

        private GuardedCall(Supplier<T> providerCall) {
            this.providerCall = providerCall;
            this.ownerThread = Thread.currentThread();
        }

        @Override
        public synchronized void run() {
            invocationCount++;
            if (invocationCount != 1
                    || Thread.currentThread() != ownerThread) {
                throw new IllegalStateException(INVALID_GUARD_MESSAGE);
            }
            try {
                value = providerCall.get();
            } catch (RuntimeException failure) {
                runtimeFailure = failure;
            } catch (Error failure) {
                errorFailure = failure;
            } finally {
                completed = true;
            }
        }

        private synchronized T result() {
            if (invocationCount != 1 || !completed) {
                throw new IllegalStateException(INVALID_GUARD_MESSAGE);
            }
            if (runtimeFailure != null) {
                throw runtimeFailure;
            }
            if (errorFailure != null) {
                throw errorFailure;
            }
            return value;
        }
    }

}
