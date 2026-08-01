package blue.language.processor;

import blue.language.processor.model.ChannelContract;
import blue.language.processor.model.DocumentUpdateChannel;
import blue.language.processor.model.EmbeddedNodeChannel;
import blue.language.processor.model.LifecycleChannel;
import blue.language.processor.model.TriggeredEventChannel;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Owns the closed set of channel types whose lifecycle and delivery are
 * controlled directly by the Contracts processor.
 */
final class ProcessorManagedChannelTypes {

    static final Set<Class<? extends ChannelContract>> TYPES =
            Collections.unmodifiableSet(
                    new LinkedHashSet<Class<? extends ChannelContract>>(
                            Arrays.<Class<? extends ChannelContract>>asList(
                                    DocumentUpdateChannel.class,
                                    TriggeredEventChannel.class,
                                    LifecycleChannel.class,
                                    EmbeddedNodeChannel.class)));

    private ProcessorManagedChannelTypes() {
    }

    /** Returns whether the processor owns delivery for the supplied channel. */
    static boolean contains(ChannelContract contract) {
        if (contract == null) {
            return false;
        }
        for (Class<? extends ChannelContract> type : TYPES) {
            if (type.isInstance(contract)) {
                return true;
            }
        }
        return false;
    }
}
