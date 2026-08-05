package blue.language.processor.model;

import blue.language.model.TypeBlueId;
import blue.language.processor.registry.RuntimeBlueIds;

/**
 * Processor-managed channel for initialization and termination lifecycle
 * events.
 */
@TypeBlueId(RuntimeBlueIds.LIFECYCLE_EVENT_CHANNEL)
public class LifecycleChannel extends ChannelContract {

    /** Creates an unconfigured lifecycle-event channel. */
    public LifecycleChannel() {
    }
}
