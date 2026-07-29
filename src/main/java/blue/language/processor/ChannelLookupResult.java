package blue.language.processor;

import java.util.Objects;
import java.util.Optional;

/**
 * Exact result of one declared same-scope Channel-header lookup.
 *
 * <p>{@link Kind#ABSENT} means the complete declared catalog proves that no
 * effective Contract exists at the raw key. {@link Kind#NON_CHANNEL} means an
 * effective Contract exists there, but its runtime role is not a Channel.
 * Missing or changed evidence and reads outside the declared dependency
 * surface fail closed before a result is returned.</p>
 */
public final class ChannelLookupResult {

    /**
     * Exhaustive outcomes of a lookup against the declared contract catalog.
     */
    public enum Kind {
        /** The key resolves to an immutable Channel snapshot. */
        CHANNEL,
        /** The complete catalog proves that the key has no effective contract. */
        ABSENT,
        /** The key has an effective contract whose runtime role is not Channel. */
        NON_CHANNEL
    }

    private static final ChannelLookupResult ABSENT =
            new ChannelLookupResult(Kind.ABSENT, null);
    private static final ChannelLookupResult NON_CHANNEL =
            new ChannelLookupResult(Kind.NON_CHANNEL, null);

    private final Kind kind;
    private final ChannelMemberSnapshot channel;

    private ChannelLookupResult(
            Kind kind,
            ChannelMemberSnapshot channel) {
        this.kind = Objects.requireNonNull(kind, "kind");
        this.channel = channel;
        if ((kind == Kind.CHANNEL) != (channel != null)) {
            throw new IllegalArgumentException(
                    "CHANNEL lookup results require exactly one snapshot");
        }
    }

    /**
     * Creates a successful Channel lookup.
     *
     * @param channel immutable snapshot found at the declared key
     * @return lookup result containing {@code channel}
     */
    public static ChannelLookupResult channel(
            ChannelMemberSnapshot channel) {
        return new ChannelLookupResult(
                Kind.CHANNEL,
                Objects.requireNonNull(channel, "channel"));
    }

    /**
     * Returns the shared result for a key proven to be absent.
     *
     * @return absent lookup result
     */
    public static ChannelLookupResult absent() {
        return ABSENT;
    }

    /**
     * Returns the shared result for a key occupied by a non-Channel contract.
     *
     * @return non-Channel lookup result
     */
    public static ChannelLookupResult nonChannel() {
        return NON_CHANNEL;
    }

    /**
     * Returns the exact lookup outcome.
     *
     * @return outcome kind
     */
    public Kind kind() {
        return kind;
    }

    /**
     * Reports whether the lookup contains a Channel snapshot.
     *
     * @return {@code true} only for {@link Kind#CHANNEL}
     */
    public boolean isChannel() {
        return kind == Kind.CHANNEL;
    }

    /**
     * Reports whether the catalog proved that the key is absent.
     *
     * @return {@code true} only for {@link Kind#ABSENT}
     */
    public boolean isAbsent() {
        return kind == Kind.ABSENT;
    }

    /**
     * Reports whether the key is occupied by a non-Channel contract.
     *
     * @return {@code true} only for {@link Kind#NON_CHANNEL}
     */
    public boolean isNonChannel() {
        return kind == Kind.NON_CHANNEL;
    }

    /**
     * Returns the immutable Channel snapshot, when the lookup succeeded.
     *
     * @return present snapshot for {@link Kind#CHANNEL}; otherwise empty
     */
    public Optional<ChannelMemberSnapshot> channel() {
        return Optional.ofNullable(channel);
    }
}
