package blue.buildlogic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

final class PluginDescriptorsTest {

    @Test
    void shouldPublishEveryRequiredConventionPluginId() throws Exception {
        // given
        Map<String, String> plugins = Map.of(
                "blue.java8-library-conventions", Java8LibraryConventionsPlugin.class.getName(),
                "blue.reproducible-archives", ReproducibleArchivesPlugin.class.getName(),
                "blue.api-baseline", ApiBaselinePlugin.class.getName(),
                "blue.conformance-package", ConformancePackagePlugin.class.getName(),
                "blue.release-evidence", ReleaseEvidencePlugin.class.getName(),
                "blue.jreleaser-publishing", JReleaserPublishingPlugin.class.getName(),
                "blue.jmh-conventions", JmhConventionsPlugin.class.getName());

        for (Map.Entry<String, String> plugin : plugins.entrySet()) {
            // when
            String resource = "META-INF/gradle-plugins/" + plugin.getKey() + ".properties";
            InputStream input = getClass().getClassLoader().getResourceAsStream(resource);

            // then
            assertNotNull(input, resource);
            Properties descriptor = new Properties();
            try (InputStream closeable = input) {
                descriptor.load(closeable);
            }
            assertEquals(plugin.getValue(), descriptor.getProperty("implementation-class"));
        }
    }
}
