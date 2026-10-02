package at.sv.hue;

import at.sv.hue.api.BridgeConnectionFailure;
import at.sv.hue.api.hue.HueApiImpl;
import at.sv.hue.api.hue.HueEventStreamReader;
import at.sv.hue.api.hue.HueHttpsClientFactory;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class HueSchedulerMigrationStartupTest {
    @Test
    void migrationWithRuntimeFlagsDoesNotStartEventsOrScheduling(@TempDir Path directory) throws Exception {
        Path config = Files.writeString(directory.resolve("input.txt"), "");
        try (var https = mockStatic(HueHttpsClientFactory.class);
             var api = mockConstruction(HueApiImpl.class);
             var events = mockConstruction(HueEventStreamReader.class);
             var scheduling = mockConstruction(StateSchedulerImpl.class);
             var migration = mockConstruction(InputToSceneMigrator.class)) {
            https.when(() -> HueHttpsClientFactory.createHttpsClient(anyString(), anyString(), anyBoolean()))
                    .thenReturn(mock(OkHttpClient.class));

            int exitCode = new CommandLine(new HueScheduler()).execute(
                    "bridge.local", "access-token", config.toString(), "--lat=48.2", "--long=16.4",
                    "--migrate-input-to-scenes", "--enable-auto-scene-states", "--enable-scene-sync");

            assertThat(exitCode).isZero();
            assertThat(events.constructed()).isEmpty();
            assertThat(scheduling.constructed()).isEmpty();
            verify(api.constructed().getFirst()).assertConnection();
            verifyNoMoreInteractions(api.constructed().getFirst());
            assertThat(migration.constructed()).hasSize(1);
            verify(migration.constructed().getFirst()).migrate();
        }
    }

    @Test
    void failedMigrationConnectionExitsWithoutStartingBackgroundRetries(@TempDir Path directory) throws Exception {
        Path config = Files.writeString(directory.resolve("input.txt"), "");
        try (var https = mockStatic(HueHttpsClientFactory.class);
             var api = mockConstruction(HueApiImpl.class,
                     (mock, _) -> doThrow(new BridgeConnectionFailure("offline")).when(mock).assertConnection());
             var events = mockConstruction(HueEventStreamReader.class);
             var scheduling = mockConstruction(StateSchedulerImpl.class);
             var migration = mockConstruction(InputToSceneMigrator.class)) {
            https.when(() -> HueHttpsClientFactory.createHttpsClient(anyString(), anyString(), anyBoolean()))
                    .thenReturn(mock(OkHttpClient.class));
            CommandLine command = new CommandLine(new HueScheduler());
            StringWriter errors = new StringWriter();
            command.setErr(new PrintWriter(errors));

            int exitCode = command.execute("bridge.local", "access-token", config.toString(),
                    "--lat=48.2", "--long=16.4", "--migrate-input-to-scenes", "--enable-auto-scene-states");

            assertThat(exitCode).isNotZero();
            assertThat(errors.toString()).contains("offline");
            assertThat(events.constructed()).isEmpty();
            assertThat(scheduling.constructed()).isEmpty();
            assertThat(migration.constructed()).isEmpty();
        }
    }
}
