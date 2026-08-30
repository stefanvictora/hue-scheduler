package at.sv.hue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class HueSchedulerCliTest {

    @Test
    void sceneSchedulesDoNotRequireAConfigurationFile() {
        CommandLine commandLine = new CommandLine(new HueScheduler());

        assertDoesNotThrow(() -> commandLine.parseArgs(
                "bridge.local", "access-token",
                "--lat=48.2", "--long=16.4",
                "--enable-auto-scene-states"));
    }

    @Test
    void positionalConfigurationFileRemainsSupported() {
        CommandLine commandLine = new CommandLine(new HueScheduler());

        CommandLine.ParseResult parseResult = commandLine.parseArgs(
                "bridge.local", "access-token",
                "--lat=48.2", "--long=16.4",
                "input.txt");

        assertThat(parseResult.<Path>matchedPositionalValue(2, null))
                .isEqualTo(Path.of("input.txt"));
    }

    @Test
    void startupRequiresAConfigurationFileWhenSceneSchedulesAreDisabled() {
        CommandLine commandLine = new CommandLine(new HueScheduler());
        StringWriter standardError = new StringWriter();
        commandLine.setErr(new PrintWriter(standardError));

        int exitCode = commandLine.execute(
                "not-a-valid-url", "SGVsbG8.V29ybGQ",
                "--lat=48.2", "--long=16.4");

        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.USAGE);
        assertThat(standardError.toString())
                .contains("CONFIG_FILE is required unless --enable-auto-scene-states is set");
    }

    @Test
    void inputMigrationRequiresAConfigurationFile() {
        CommandLine commandLine = new CommandLine(new HueScheduler());
        StringWriter standardError = new StringWriter();
        commandLine.setErr(new PrintWriter(standardError));

        int exitCode = commandLine.execute(
                "not-a-valid-url", "access-token",
                "--lat=48.2", "--long=16.4",
                "--migrate-input-to-scenes");

        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.USAGE);
        assertThat(standardError.toString())
                .contains("CONFIG_FILE is required when --migrate-input-to-scenes is set");
    }

    @Test
    void explicitlyConfiguredFileMustBeReadableEvenWithSceneSchedulesEnabled(@TempDir Path temporaryDirectory) {
        Path missingFile = temporaryDirectory.resolve("missing-input.txt");
        CommandLine commandLine = new CommandLine(new HueScheduler());
        StringWriter standardError = new StringWriter();
        commandLine.setErr(new PrintWriter(standardError));

        int exitCode = commandLine.execute(
                "not-a-valid-url", "access-token",
                "--lat=48.2", "--long=16.4",
                "--enable-auto-scene-states",
                missingFile.toString());

        assertThat(exitCode).isEqualTo(CommandLine.ExitCode.SOFTWARE);
        assertThat(standardError.toString())
                .contains("Given config file '" + missingFile.toAbsolutePath() + "' does not exist or is not readable!");
    }
}
