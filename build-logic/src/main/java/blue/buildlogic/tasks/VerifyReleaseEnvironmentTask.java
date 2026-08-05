package blue.buildlogic.tasks;

import blue.buildlogic.support.SourceDateEpoch;
import java.util.regex.Pattern;
import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** Validates release channel/version pairing before any remote publication task can run. */
@DisableCachingByDefault(because = "Environment validation has no output")
public abstract class VerifyReleaseEnvironmentTask extends DefaultTask {

    private static final Pattern RC_VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+-rc\\.\\d+");
    private static final Pattern STABLE_VERSION = Pattern.compile("\\d+\\.\\d+\\.\\d+");

    @Input
    public abstract Property<String> getVersionValue();

    @Input
    @Optional
    public abstract Property<String> getReleaseChannel();

    @Input
    public abstract Property<String> getSourceDateEpoch();

    @TaskAction
    public void verify() {
        SourceDateEpoch.normalize(getSourceDateEpoch().get());
        String channel = getReleaseChannel().getOrElse("").trim();
        if (channel.isEmpty()) {
            return;
        }
        String version = getVersionValue().get();
        if (channel.equals("rc") && RC_VERSION.matcher(version).matches()) {
            return;
        }
        if (channel.equals("stable") && STABLE_VERSION.matcher(version).matches()) {
            return;
        }
        if (!channel.equals("rc") && !channel.equals("stable")) {
            throw new GradleException("BLUE_RELEASE_CHANNEL must be either 'rc' or 'stable'");
        }
        throw new GradleException(
                "BLUE_RELEASE_CHANNEL=" + channel + " is incompatible with version '" + version + "'");
    }
}
