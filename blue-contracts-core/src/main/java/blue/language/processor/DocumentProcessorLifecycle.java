package blue.language.processor;

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Owns processor lifecycle synchronization and deferred resource release.
 *
 * <p>Configuration locks always precede lifecycle locks. Closing or clearing
 * from inside an active read is deferred until that thread releases its final
 * read hold, matching the historical re-entrant behavior.</p>
 */
final class DocumentProcessorLifecycle {

    interface Resources {
        void clearCaches();

        void detachRuntimeCollaborators();
    }

    private final Resources resources;
    private volatile ProcessorRuntimeAccess.GenerationGuard
            runtimeGenerationGuard;
    private final ReentrantReadWriteLock lock =
            new ReentrantReadWriteLock();
    private final Lock readLock = lock.readLock();
    private final Lock writeLock = lock.writeLock();
    private volatile boolean closed;
    private volatile boolean cachesCleared;
    private volatile boolean clearRequested;

    DocumentProcessorLifecycle(Resources resources) {
        this(resources, null);
    }

    DocumentProcessorLifecycle(
            Resources resources,
            ProcessorRuntimeAccess.GenerationGuard
                    runtimeGenerationGuard) {
        this.resources = resources;
        this.runtimeGenerationGuard = runtimeGenerationGuard;
    }

    /** Acquires one registry/configuration revision and lifecycle read. */
    ReadScope openRead(ContractProcessorRegistry registry) {
        ProcessorRuntimeAccess.GenerationLease generationLease =
                runtimeGenerationGuard != null
                        ? runtimeGenerationGuard.open()
                        : null;
        Lock configurationRead = registry.configurationReadLock();
        try {
            configurationRead.lock();
            readLock.lock();
            try {
                ensureOpen();
                return new ReadScope(
                        this,
                        configurationRead,
                        generationLease);
            } catch (RuntimeException | Error failure) {
                releaseReadAndConfiguration(configurationRead);
                throw failure;
            }
        } catch (RuntimeException | Error failure) {
            if (generationLease != null) {
                generationLease.close();
            }
            throw failure;
        }
    }

    /** Acquires the legacy registry and lifecycle write boundary. */
    WriteScope openConfigurationWrite(
            ContractProcessorRegistry registry,
            boolean immutableConfiguration) {
        requireMutableLegacyConfiguration(immutableConfiguration);
        rejectWriteUpgrade(registry);
        Lock configurationWrite = registry.configurationWriteLock();
        configurationWrite.lock();
        writeLock.lock();
        try {
            ensureOpen();
            return new WriteScope(configurationWrite, writeLock);
        } catch (RuntimeException | Error failure) {
            writeLock.unlock();
            configurationWrite.unlock();
            throw failure;
        }
    }

    /** Acquires a lifecycle-only legacy configuration mutation boundary. */
    WriteScope openMutation(
            ContractProcessorRegistry registry,
            boolean immutableConfiguration) {
        requireMutableLegacyConfiguration(immutableConfiguration);
        rejectWriteUpgrade(registry);
        writeLock.lock();
        try {
            ensureOpen();
            return new WriteScope(null, writeLock);
        } catch (RuntimeException | Error failure) {
            writeLock.unlock();
            throw failure;
        }
    }

    /** Clears reloadable caches immediately or after the active read returns. */
    void clearCaches() {
        if (lock.getReadHoldCount() > 0) {
            clearRequested = true;
            return;
        }
        writeLock.lock();
        try {
            resources.clearCaches();
            clearRequested = false;
        } finally {
            writeLock.unlock();
        }
    }

    /** Begins terminal shutdown and releases collaborators when safe. */
    void close() {
        closed = true;
        if (lock.getReadHoldCount() > 0) {
            clearRequested = true;
            return;
        }
        writeLock.lock();
        try {
            clearCachesIfNeeded();
        } finally {
            writeLock.unlock();
        }
    }

    boolean isClosed() {
        return closed;
    }

    private void releaseReadAndConfiguration(Lock configurationRead) {
        try {
            releaseRead();
        } finally {
            configurationRead.unlock();
        }
    }

    private void releaseRead() {
        readLock.unlock();
        if ((closed || clearRequested) && lock.getReadHoldCount() == 0) {
            writeLock.lock();
            try {
                clearCachesIfNeeded();
            } finally {
                writeLock.unlock();
            }
        }
    }

    private void clearCachesIfNeeded() {
        if (closed) {
            if (!cachesCleared) {
                resources.clearCaches();
                cachesCleared = true;
            }
            resources.detachRuntimeCollaborators();
            runtimeGenerationGuard = null;
            clearRequested = false;
        } else if (clearRequested) {
            resources.clearCaches();
            clearRequested = false;
        }
    }

    private void rejectWriteUpgrade(ContractProcessorRegistry registry) {
        if (lock.getReadHoldCount() > 0
                || registry.isConfigurationReadHeldByCurrentThread()) {
            throw new IllegalStateException(
                    "Document processor configuration cannot change during active processing");
        }
    }

    private void requireMutableLegacyConfiguration(
            boolean immutableConfiguration) {
        if (immutableConfiguration) {
            throw new UnsupportedOperationException(
                    "DocumentProcessor configuration is immutable; build a new processor generation");
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException(
                    "Document processor is closed");
        }
    }

    /** One acquired read revision. */
    static final class ReadScope implements AutoCloseable {
        private DocumentProcessorLifecycle lifecycle;
        private Lock configurationRead;
        private ProcessorRuntimeAccess.GenerationLease generationLease;

        private ReadScope(
                DocumentProcessorLifecycle lifecycle,
                Lock configurationRead,
                ProcessorRuntimeAccess.GenerationLease generationLease) {
            this.lifecycle = lifecycle;
            this.configurationRead = configurationRead;
            this.generationLease = generationLease;
        }

        @Override
        public void close() {
            if (lifecycle != null) {
                try {
                    lifecycle.releaseReadAndConfiguration(
                            configurationRead);
                } finally {
                    lifecycle = null;
                    configurationRead = null;
                    if (generationLease != null) {
                        generationLease.close();
                        generationLease = null;
                    }
                }
            }
        }
    }

    /** One acquired legacy mutation boundary. */
    static final class WriteScope implements AutoCloseable {
        private Lock configurationWrite;
        private Lock lifecycleWrite;

        private WriteScope(
                Lock configurationWrite,
                Lock lifecycleWrite) {
            this.configurationWrite = configurationWrite;
            this.lifecycleWrite = lifecycleWrite;
        }

        @Override
        public void close() {
            if (lifecycleWrite != null) {
                lifecycleWrite.unlock();
                lifecycleWrite = null;
                if (configurationWrite != null) {
                    configurationWrite.unlock();
                    configurationWrite = null;
                }
            }
        }
    }
}
