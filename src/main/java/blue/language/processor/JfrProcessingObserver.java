package blue.language.processor;

import blue.language.utils.Properties;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Java Flight Recorder exporter with a Java 8-compatible reflective boundary.
 *
 * <p>On Java runtimes without JFR this observer is an allocation-light no-op.
 * JFR linkage, event creation, and commit failures are always suppressed and
 * therefore cannot affect processing or gas.</p>
 */
public final class JfrProcessingObserver implements ProcessingObserver, AutoCloseable {

    private final EventWriter writer;

    /** Creates an observer, enabling it only when the runtime provides JFR. */
    public JfrProcessingObserver() {
        this.writer = EventWriter.create();
    }

    /**
     * Reports whether this runtime accepted the dynamic JFR event type.
     *
     * @return {@code true} when observations can be committed to JFR
     */
    public boolean isAvailable() {
        return writer.isAvailable();
    }

    /**
     * Commits one JFR event when recording is enabled.
     *
     * @param observation immutable observation
     */
    @Override
    public void record(ProcessingObservation observation) {
        if (observation != null) {
            writer.write(observation);
        }
    }

    /** Unregisters the dynamically created event type when supported. */
    @Override
    public void close() {
        writer.close();
    }

    private static final class EventWriter {

        private static final EventWriter UNAVAILABLE = new EventWriter();

        private final Object factory;
        private final Method newEvent;
        private final Method shouldCommit;
        private final Method set;
        private final Method commit;
        private final Method unregister;

        private EventWriter() {
            this.factory = null;
            this.newEvent = null;
            this.shouldCommit = null;
            this.set = null;
            this.commit = null;
            this.unregister = null;
        }

        private EventWriter(
                Object factory,
                Method newEvent,
                Method shouldCommit,
                Method set,
                Method commit,
                Method unregister) {
            this.factory = factory;
            this.newEvent = newEvent;
            this.shouldCommit = shouldCommit;
            this.set = set;
            this.commit = commit;
            this.unregister = unregister;
        }

        private static EventWriter create() {
            try {
                Class<?> descriptorType = Class.forName("jdk.jfr.ValueDescriptor");
                Constructor<?> descriptor = descriptorType.getConstructor(
                        Class.class, String.class);
                List<Object> fields = new ArrayList<>();
                fields.add(descriptor.newInstance(String.class, "metricId"));
                fields.add(descriptor.newInstance(String.class, "kind"));
                fields.add(descriptor.newInstance(
                        long.class,
                        Properties.OBJECT_VALUE));
                fields.add(descriptor.newInstance(String.class, "context"));

                Class<?> factoryType = Class.forName("jdk.jfr.EventFactory");
                Method create = factoryType.getMethod("create", List.class, List.class);
                Object factory = create.invoke(null, Collections.emptyList(), fields);
                Method newEvent = factoryType.getMethod("newEvent");
                Method unregister = factoryType.getMethod("unregister");

                Class<?> eventType = Class.forName("jdk.jfr.Event");
                return new EventWriter(
                        factory,
                        newEvent,
                        eventType.getMethod("shouldCommit"),
                        eventType.getMethod("set", int.class, Object.class),
                        eventType.getMethod("commit"),
                        unregister);
            } catch (Throwable ignored) {
                return UNAVAILABLE;
            }
        }

        private boolean isAvailable() {
            return factory != null;
        }

        private void write(ProcessingObservation observation) {
            if (!isAvailable()) {
                return;
            }
            try {
                Object event = newEvent.invoke(factory);
                if (!Boolean.TRUE.equals(shouldCommit.invoke(event))) {
                    return;
                }
                set.invoke(event, 0, observation.metricId().externalName());
                set.invoke(event, 1, observation.kind().name());
                set.invoke(event, 2, observation.value());
                set.invoke(event, 3, observation.context().compactString());
                commit.invoke(event);
            } catch (ThreadDeath failure) {
                throw failure;
            } catch (VirtualMachineError failure) {
                throw failure;
            } catch (Throwable ignored) {
                // JFR is an operational side channel only.
            }
        }

        private void close() {
            if (!isAvailable()) {
                return;
            }
            try {
                unregister.invoke(factory);
            } catch (ThreadDeath failure) {
                throw failure;
            } catch (VirtualMachineError failure) {
                throw failure;
            } catch (Throwable ignored) {
                // Closing telemetry must not affect processor shutdown.
            }
        }
    }
}
