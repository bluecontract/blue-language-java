package blue.language.merge;

import blue.language.snapshot.FrozenNode;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Generation-keyed single-flight coordinator for verified canonical loads.
 *
 * <p>Provider work runs outside the cache mutation lock. Contenders share the
 * same result, recursive identity demand fails deterministically, and a
 * generation transition makes every participant retry against current state.</p>
 */
final class VerifiedCanonicalLoadCoordinator {

    private static volatile Consumer<String> loadObserver;
    private static volatile Consumer<String> waitObserver;

    private final ConcurrentMap<LoadKey, LoadFlight> flights =
            new ConcurrentHashMap<>();
    private final ThreadLocal<Deque<LoadKey>> loadingStack =
            ThreadLocal.withInitial(ArrayDeque::new);

    FrozenNode getOrLoad(
            String blueId,
            Supplier<FrozenNode> loader,
            Access access) {
        while (true) {
            long loadingGeneration;
            synchronized (access.mutationLock()) {
                access.ensureCurrentGeneration();
                FrozenNode visible = access.visibleCanonical(blueId);
                if (visible != null) {
                    return visible;
                }
                loadingGeneration = access.currentGeneration();
            }

            LoadKey loadKey = new LoadKey(
                    loadingGeneration, blueId);
            Deque<LoadKey> stack = loadingStack.get();
            if (isLoadingBlueId(stack, blueId)) {
                throw recursiveLoad(blueId);
            }
            LoadFlight candidate = new LoadFlight(
                    Thread.currentThread());
            LoadFlight existing = flights.putIfAbsent(
                    loadKey, candidate);
            LoadFlight flight = existing != null
                    ? existing
                    : candidate;
            boolean ownsLoad = existing == null;
            if (!ownsLoad
                    && flight.owner == Thread.currentThread()) {
                throw recursiveLoad(blueId);
            }

            try {
                if (ownsLoad) {
                    try {
                        notifyLoadInstalled(blueId);
                        synchronized (access.mutationLock()) {
                            if (loadingGeneration
                                    != access.currentGeneration()) {
                                flight.result.completeExceptionally(
                                        RetryLoadException.INSTANCE);
                                continue;
                            }
                            access.ensureCurrentGeneration();
                            FrozenNode published =
                                    access.visibleCanonical(blueId);
                            if (published != null) {
                                flight.result.complete(published);
                                return published;
                            }
                        }
                    } catch (RuntimeException | Error failure) {
                        flight.result.completeExceptionally(failure);
                        throw failure;
                    }
                }

                FrozenNode loaded;
                if (ownsLoad) {
                    stack.addLast(loadKey);
                    try {
                        loaded = loader.get();
                        access.requireCanonical(blueId, loaded);
                        flight.result.complete(loaded);
                    } catch (Throwable failure) {
                        flight.result.completeExceptionally(failure);
                        throw propagate(failure);
                    } finally {
                        LoadKey removed = stack.removeLast();
                        if (!loadKey.equals(removed)) {
                            throw new IllegalStateException(
                                    "Verified reference load stack became unbalanced");
                        }
                        if (stack.isEmpty()) {
                            loadingStack.remove();
                        }
                    }
                } else {
                    notifyLoadWait(blueId);
                    try {
                        loaded = await(flight);
                    } catch (RetryLoadException retry) {
                        continue;
                    }
                }

                synchronized (access.mutationLock()) {
                    FrozenNode retained = access.retainLoaded(
                            loadingGeneration, blueId, loaded);
                    if (retained != null) {
                        return retained;
                    }
                }
            } finally {
                if (ownsLoad) {
                    flights.remove(loadKey, flight);
                }
            }
        }
    }

    static void setLoadObserver(Consumer<String> observer) {
        loadObserver = observer;
    }

    static void setWaitObserver(Consumer<String> observer) {
        waitObserver = observer;
    }

    private static boolean isLoadingBlueId(
            Deque<LoadKey> stack,
            String blueId) {
        for (LoadKey active : stack) {
            if (active.blueId.equals(blueId)) {
                return true;
            }
        }
        return false;
    }

    private static IllegalStateException recursiveLoad(
            String blueId) {
        return new IllegalStateException(
                "Recursive verified reference load: " + blueId);
    }

    private static void notifyLoadInstalled(String blueId) {
        Consumer<String> observer = loadObserver;
        if (observer != null) {
            observer.accept(blueId);
        }
    }

    private static void notifyLoadWait(String blueId) {
        Consumer<String> observer = waitObserver;
        if (observer != null) {
            observer.accept(blueId);
        }
    }

    private static FrozenNode await(LoadFlight flight) {
        try {
            return flight.result.join();
        } catch (CompletionException failure) {
            throw propagate(failure.getCause() != null
                    ? failure.getCause()
                    : failure);
        }
    }

    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof RuntimeException) {
            return (RuntimeException) failure;
        }
        if (failure instanceof Error) {
            throw (Error) failure;
        }
        return new IllegalStateException(
                "Verified reference load failed", failure);
    }

    /** Cache-specific admission hooks invoked under the documented lock. */
    abstract static class Access {
        abstract Object mutationLock();

        abstract long currentGeneration();

        abstract void ensureCurrentGeneration();

        abstract FrozenNode visibleCanonical(String blueId);

        abstract void requireCanonical(
                String blueId, FrozenNode canonical);

        /** Returns null when a generation transition requires a retry. */
        abstract FrozenNode retainLoaded(
                long loadingGeneration,
                String blueId,
                FrozenNode loaded);
    }

    private static final class LoadKey {
        private final long generation;
        private final String blueId;

        private LoadKey(long generation, String blueId) {
            this.generation = generation;
            this.blueId = blueId;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (!(object instanceof LoadKey)) {
                return false;
            }
            LoadKey other = (LoadKey) object;
            return generation == other.generation
                    && blueId.equals(other.blueId);
        }

        @Override
        public int hashCode() {
            return 31 * Long.hashCode(generation)
                    + blueId.hashCode();
        }
    }

    private static final class LoadFlight {
        private final Thread owner;
        private final CompletableFuture<FrozenNode> result =
                new CompletableFuture<>();

        private LoadFlight(Thread owner) {
            this.owner = owner;
        }
    }

    private static final class RetryLoadException
            extends RuntimeException {
        private static final RetryLoadException INSTANCE =
                new RetryLoadException();

        private RetryLoadException() {
            super("Verified reference load generation changed",
                    null, false, false);
        }
    }
}
