package blue.language.processor.util;

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
 * Shared constants describing reserved processor keys and built-in channel types.
 */
public final class ProcessorContractConstants {

    public static final String KEY_EMBEDDED = "embedded";
    public static final String KEY_INITIALIZED = "initialized";
    public static final String KEY_TERMINATED = "terminated";
    public static final String KEY_CHECKPOINT = "checkpoint";

    public static final Set<String> RESERVED_CONTRACT_KEYS =
            Collections.unmodifiableSet(new LinkedHashSet<String>(Arrays.asList(
                    KEY_EMBEDDED,
                    KEY_INITIALIZED,
                    KEY_TERMINATED,
                    KEY_CHECKPOINT
            )));

    public static final Set<Class<? extends ChannelContract>> PROCESSOR_MANAGED_CHANNEL_TYPES =
            Collections.unmodifiableSet(new LinkedHashSet<Class<? extends ChannelContract>>(Arrays.<Class<? extends ChannelContract>>asList(
                    DocumentUpdateChannel.class,
                    TriggeredEventChannel.class,
                    LifecycleChannel.class,
                    EmbeddedNodeChannel.class
            )));

    private ProcessorContractConstants() {
    }

    public static boolean isReservedKey(String key) {
        return key != null && RESERVED_CONTRACT_KEYS.contains(key);
    }

    public static boolean isProcessorManagedChannel(ChannelContract contract) {
        if (contract == null) {
            return false;
        }
        for (Class<? extends ChannelContract> type : PROCESSOR_MANAGED_CHANNEL_TYPES) {
            if (type.isInstance(contract)) {
                return true;
            }
        }
        return false;
    }
}
