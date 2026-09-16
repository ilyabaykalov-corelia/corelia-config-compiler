package ru.corelia.configuration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import static org.junit.jupiter.api.Assertions.*;

class ConfigurationCompilerTest {
    @TempDir Path target;
    @Test void packagesCustomerOperationsWithoutChangingPermissionsBodies() throws Exception {
        Path source = Path.of("../../sber-npf-corelia-config");
        Path output = target.resolve("release");
        ConfigurationCompiler.compile(source, output, "0.1.0");
        var loaded = new ConfigurationLoader().load(output.resolve("corelia"), "0.1.0");
        var json = JsonMapper.builder().build();
        var permissions = json.readTree(Files.readString(output.resolve("platform-v/graphql-permissions.fragment.json")));
        var original = json.readTree(Files.readString(Path.of("../../sber-npf-platform-v/model.graphql-permissions.json")));
        for (var permission : permissions) {
            String name = permission.path("name").asString();
            assertEquals(loaded.operations().get(name).text(), permission.path("body").asString());
            boolean found = false;
            for (var existing : original) if (name.equals(existing.path("name").asString())) {
                assertEquals(existing.path("body").asString(), permission.path("body").asString());
                assertEquals(existing.path("checkForAnyPrivilege"), permission.path("checkForAnyPrivilege"));
                found = true;
            }
            assertTrue(found, name);
        }
        assertEquals(Files.readString(output.resolve("platform-v/graphql-permissions.fragment.json")), Files.readString(output.resolve("tests/allowed-requests.json")));
        assertThrows(ConfigurationException.class, () -> ConfigurationCompiler.compile(source, output, "0.1.0"));
        Path again = target.resolve("release-again");
        ConfigurationCompiler.compile(source, again, "0.1.0");
        assertEquals(Files.readString(output.resolve("manifest.json")), Files.readString(again.resolve("manifest.json")));
    }
}
