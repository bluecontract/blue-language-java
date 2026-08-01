package blue.language.processor;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static blue.language.processor.FailureCapture.captureFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ExternalChannelDependencySnapshotValueTest {

    @Test
    void shouldDefensivelyFreezeEvidenceAndRetainIdentityOrder() {
        // given
        List<String> intrinsic = new ArrayList<>(
                Collections.singletonList("intrinsic"));
        ExternalChannelDependencySnapshot.Entry entry = entry("external");
        ExternalChannelDependencySnapshot.TypeFamily family =
                exactFamily("owner", "external");
        ExternalChannelDependencySnapshot.ChannelEntry channel =
                channel("channel", "header");
        List<ExternalChannelDependencySnapshot.Entry> entries =
                new ArrayList<>(Collections.singletonList(entry));
        List<ExternalChannelDependencySnapshot.TypeFamily> families =
                new ArrayList<>(Collections.singletonList(family));
        List<ExternalChannelDependencySnapshot.ChannelEntry> channels =
                new ArrayList<>(Collections.singletonList(channel));
        List<String> catalogKeys = new ArrayList<>(
                Arrays.asList("zeta", "channel", "alpha"));

        // when
        ExternalChannelDependencySnapshot snapshot =
                new ExternalChannelDependencySnapshot(
                        intrinsic,
                        entries,
                        families,
                        true,
                        channels,
                        true,
                        catalogKeys);
        intrinsic.clear();
        entries.clear();
        families.clear();
        channels.clear();
        catalogKeys.clear();

        // then
        assertEquals(Collections.singletonList("intrinsic"),
                snapshot.intrinsicNodeBlueIds());
        assertEquals(Collections.singletonList(entry), snapshot.entries());
        assertEquals(Collections.singletonList(family),
                snapshot.typeFamilies());
        assertEquals(Collections.singletonList(channel),
                snapshot.channelEntries());
        assertEquals(Arrays.asList("alpha", "channel", "zeta"),
                snapshot.channelCatalogContractKeys());
        assertEquals(6,
                snapshot.deterministicDependencyNodeBlueIds().size());
        assertEquals("intrinsic",
                snapshot.deterministicDependencyNodeBlueIds().get(0));
        assertEquals(entry.identityBlueId(),
                snapshot.deterministicDependencyNodeBlueIds().get(1));
        assertEquals(family.identityBlueId(),
                snapshot.deterministicDependencyNodeBlueIds().get(2));
        assertEquals(channel.identityBlueId(),
                snapshot.deterministicDependencyNodeBlueIds().get(4));
    }

    @Test
    void shouldCompareAndCoverSnapshotsByExactSemanticState() {
        // given
        ExternalChannelDependencySnapshot.Entry entry = entry("external");
        ExternalChannelDependencySnapshot.TypeFamily family =
                exactFamily("owner", "external");
        ExternalChannelDependencySnapshot available =
                new ExternalChannelDependencySnapshot(
                        Arrays.asList("first", "second"),
                        Collections.singletonList(entry),
                        Collections.singletonList(family),
                        true);
        ExternalChannelDependencySnapshot reconstructed =
                new ExternalChannelDependencySnapshot(
                        available.intrinsicNodeBlueIds(),
                        available.entries(),
                        available.typeFamilies(),
                        available.wholeSameScopeExternalSurface());
        ExternalChannelDependencySnapshot subset =
                new ExternalChannelDependencySnapshot(
                        Collections.singletonList("second"),
                        Collections.singletonList(entry),
                        Collections.<ExternalChannelDependencySnapshot
                                .TypeFamily>emptyList(),
                        false);
        ExternalChannelDependencySnapshot changed =
                new ExternalChannelDependencySnapshot(
                        Collections.singletonList("second"),
                        Collections.singletonList(entry("changed")),
                        Collections.<ExternalChannelDependencySnapshot
                                .TypeFamily>emptyList(),
                        false);

        // when
        boolean coversSubset = available.covers(subset);
        boolean coversChanged = available.covers(changed);

        // then
        assertEquals(available, reconstructed);
        assertEquals(available.hashCode(), reconstructed.hashCode());
        assertTrue(coversSubset);
        assertFalse(coversChanged);
        assertTrue(available.covers(ExternalChannelDependencySnapshot.none()));
    }

    @Test
    void shouldRejectWholeCatalogThatOmitsCapturedChannelKey() {
        // given
        ExternalChannelDependencySnapshot.ChannelEntry channel =
                channel("channel", "header");

        // when
        IllegalArgumentException failure = captureFailure(
                () -> new ExternalChannelDependencySnapshot(
                        Collections.<String>emptyList(),
                        Collections.<ExternalChannelDependencySnapshot.Entry>
                                emptyList(),
                        Collections.<ExternalChannelDependencySnapshot
                                .TypeFamily>emptyList(),
                        false,
                        Collections.singletonList(channel),
                        true,
                        Collections.singletonList("different")));

        // then
        assertEquals(
                "Channel catalog raw-key membership omits a Channel entry",
                failure.getMessage());
    }

    private ExternalChannelDependencySnapshot.Entry entry(String channelKey) {
        return new ExternalChannelDependencySnapshot.Entry(
                channelKey,
                2,
                "external-type",
                Collections.singletonList("source-" + channelKey),
                Collections.singletonList("dependency-" + channelKey),
                "domain-" + channelKey);
    }

    private ExternalChannelDependencySnapshot.TypeFamily exactFamily(
            String owner,
            String memberKey) {
        return new ExternalChannelDependencySnapshot.TypeFamily(
                owner,
                "external-type",
                Collections.singletonList(
                        new ExternalChannelDependencySnapshot.Member(
                                memberKey,
                                2,
                                Collections.singletonList(
                                        "source-" + memberKey),
                                Collections.singletonList(
                                        "dependency-" + memberKey))));
    }

    private ExternalChannelDependencySnapshot.ChannelEntry channel(
            String channelKey,
            String headerIdentity) {
        return new ExternalChannelDependencySnapshot.ChannelEntry(
                channelKey,
                3,
                "channel-type",
                EffectiveContractSnapshotConstants.Role.EXTERNAL_CHANNEL,
                Collections.singletonList("source-" + channelKey),
                Collections.singletonList("dependency-" + channelKey),
                headerIdentity);
    }
}
