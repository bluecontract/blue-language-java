package blue.language.snapshot;

import blue.language.Blue;
import blue.language.model.Node;
import blue.language.provider.BasicNodeProvider;
import blue.language.utils.BlueIdCalculator;
import org.junit.jupiter.api.Test;

import static blue.language.utils.UncheckedObjectMapper.YAML_MAPPER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ResolvedSnapshotTest {

    @Test
    void resolveToSnapshotExposesCanonicalResolvedAndBlueIdAsImmutableViews() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Product\n" +
                "label: inherited");
        Blue blue = new Blue(nodeProvider);
        Node noisy = YAML_MAPPER.readValue(
                "name: Instance\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Product") + "\n" +
                "label: inherited\n" +
                "local: local-value", Node.class);

        ResolvedSnapshot snapshot = blue.resolveToSnapshot(noisy);
        Node canonical = snapshot.canonicalRoot();
        Node resolved = snapshot.resolvedRoot();

        assertEquals(snapshot.blueId(), BlueIdCalculator.calculateBlueId(canonical));
        assertFalse(canonical.getProperties().containsKey("label"));
        assertEquals("inherited", resolved.getAsText("/label"));

        canonical.properties("mutated", new Node().value(true));
        resolved.properties("label", new Node().value("changed"));

        assertFalse(snapshot.canonicalRoot().getProperties().containsKey("mutated"));
        assertEquals("inherited", snapshot.resolvedRoot().getAsText("/label"));
    }

    @Test
    void loadSnapshotTrustsCanonicalBlueIdButStillBuildsResolvedView() {
        BasicNodeProvider nodeProvider = new BasicNodeProvider();
        nodeProvider.addSingleDocs(
                "name: Product\n" +
                "label: inherited");
        Blue blue = new Blue(nodeProvider);
        Node canonical = YAML_MAPPER.readValue(
                "name: Instance\n" +
                "type:\n" +
                "  blueId: " + nodeProvider.getBlueIdByName("Product") + "\n" +
                "local: local-value", Node.class);

        String expectedBlueId = BlueIdCalculator.calculateBlueId(canonical);
        ResolvedSnapshot snapshot = blue.loadSnapshot(canonical);
        canonical.properties("local", new Node().value("changed"));

        assertEquals(expectedBlueId, snapshot.blueId());
        assertEquals("inherited", snapshot.resolvedRoot().getAsText("/label"));
        assertEquals("local-value", snapshot.resolvedRoot().getAsText("/local"));
    }
}
