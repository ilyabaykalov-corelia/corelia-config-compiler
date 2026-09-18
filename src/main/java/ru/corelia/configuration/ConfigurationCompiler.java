package ru.corelia.configuration;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** Build-time packaging of a validated contract, exact operations and explicit privilege metadata. */
public final class ConfigurationCompiler {
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    public static void main(String[] args) throws IOException {
        if (args.length != 4) throw new IllegalArgumentException("Usage: ConfigurationCompiler SOURCE OUTPUT CORELIA_VERSION PLATFORM_AC_FILE");
        compile(Path.of(args[0]), Path.of(args[1]), args[2], Path.of(args[3]));
    }
    public static void compile(Path source, Path output, String version, Path accessControl) throws IOException {
        source = source.toRealPath();
        Path parent = output.toAbsolutePath().normalize().getParent().toRealPath();
        output = parent.resolve(output.getFileName());
        if (Files.exists(output)) throw new ConfigurationException("Output must not exist; compile into a new release directory");
        if (output.startsWith(source)) throw new ConfigurationException("Output must be outside the source package");
        var loaded = new ConfigurationLoader().load(source, version);
        accessControl = accessControl.toRealPath();
        String accessText = Files.readString(accessControl);
        ru.corelia.integration.PlatformVPermissionChecker.fromText(accessText, loaded);
        Path permissionFile = source.resolve("operation-permissions.json").toRealPath();
        if (!permissionFile.startsWith(source)) throw new ConfigurationException("Permissions escape source package");
        JsonNode permissionSource = JSON.readTree(Files.readString(permissionFile));
        if (permissionSource == null || !permissionSource.isArray()) throw new ConfigurationException("operation-permissions.json must be an array");
        var permissions = JSON.createArrayNode();
        var remaining = new HashSet<>(loaded.operations().keySet());
        for (JsonNode permission : permissionSource) {
            if (!permission.isObject()) throw new ConfigurationException("Invalid permission");
            AttributeSchema.keywords(permission, Set.of("name", "checkForAnyPrivilege", "checkSelects", "allowEmptyChecks", "disableJwtVerification"), "permission");
            for (String flag : List.of("allowEmptyChecks", "disableJwtVerification"))
                if (permission.has(flag) && !permission.path(flag).isBoolean()) throw new ConfigurationException("Invalid permission flag: " + flag);
            if (permission.has("checkSelects")) {
                if (!permission.path("checkSelects").isArray()) throw new ConfigurationException("Invalid checkSelects");
                for (JsonNode check : permission.path("checkSelects")) {
                    if (!check.isObject()) throw new ConfigurationException("Invalid checkSelect");
                    AttributeSchema.keywords(check, Set.of("conditionValue", "orderValue", "typeName", "description"), "checkSelect");
                    for (String key : List.of("conditionValue", "orderValue", "typeName")) DocumentTypeDefinition.requiredText(check, key);
                }
            }
            String name = DocumentTypeDefinition.requiredText(permission, "name");
            if (!remaining.remove(name)) throw new ConfigurationException("Unknown or duplicate permission operation: " + name);
            JsonNode privileges = permission.path("checkForAnyPrivilege");
            if (!privileges.isArray() || privileges.isEmpty()) throw new ConfigurationException("Explicit privileges required: " + name);
            for (JsonNode privilege : privileges) if (!privilege.isTextual() || privilege.asString().isBlank()) throw new ConfigurationException("Invalid privilege: " + name);
            ObjectNode compiled = (ObjectNode) permission.deepCopy();
            compiled.put("body", loaded.operations().get(name).text());
            permissions.add(compiled);
        }
        if (!remaining.isEmpty()) throw new ConfigurationException("Missing privilege metadata for operations: " + remaining);
        Path staging = Files.createTempDirectory(parent, ".corelia-config-");
        try {
            Path runtime = Files.createDirectories(staging.resolve("corelia"));
            Files.writeString(runtime.resolve("platform-v-ac.json"), accessText);
            ObjectNode config = JSON.createObjectNode().put("schemaVersion", 2);
            config.set(
                    "compatibility",
                    JSON.readTree(Files.readString(source.resolve("configuration.json")))
                            .path("compatibility")
                            .deepCopy());
            var sources = config.putObject("sources");
            sources.put("entities", "data-model/entities"); sources.put("ui", "ui"); sources.put("operations", "operations"); sources.put("permissions", "permissions");
            Path graphql = Files.createDirectories(runtime.resolve("graphql"));
            Path operations = Files.createDirectories(runtime.resolve("operations"));
            Path entities = Files.createDirectories(runtime.resolve("data-model/entities"));
            Path ui = Files.createDirectories(runtime.resolve("ui"));
            Path authorization = Files.createDirectories(runtime.resolve("permissions"));
            var hashes = JSON.createObjectNode();
            for (var entry : loaded.operations().entrySet()) {
                String relative = "graphql/" + entry.getKey() + ".graphql";
                Files.writeString(graphql.resolve(entry.getKey() + ".graphql"), entry.getValue().text());
                String filename = kebab(entry.getKey()) + ".json";
                ObjectNode operation = JSON.createObjectNode().put("id", entry.getKey()).put("file", "../" + relative).put("multiaggregate", entry.getValue().multiaggregate());
                Files.writeString(operations.resolve(filename), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(operation));
                hashes.put(entry.getKey(), sha256(entry.getValue().text()));
            }
            for (DocumentTypeDefinition definition : loaded.documentTypes().all()) {
                String filename = kebab(definition.id()) + ".json";
                ObjectNode entity = (ObjectNode) definition.definition();
                entity.remove("ui"); entity.remove("authorization");
                Files.writeString(entities.resolve(filename), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(entity));
                ObjectNode uiFragment = JSON.createObjectNode().put("id", definition.id()); uiFragment.set("ui", definition.ui());
                Files.writeString(ui.resolve(filename), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(uiFragment));
                ObjectNode permissionFragment = JSON.createObjectNode().put("id", definition.id()); permissionFragment.set("authorization", definition.authorization());
                Files.writeString(authorization.resolve(filename), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(permissionFragment));
            }
            Files.writeString(runtime.resolve("configuration.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(config));
            Files.createDirectories(staging.resolve("platform-v"));
            Files.writeString(staging.resolve("platform-v/ac.json"), accessText);
            Files.createDirectories(staging.resolve("tests"));
            String serialized = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(permissions);
            Files.writeString(staging.resolve("platform-v/graphql-permissions.fragment.json"), serialized);
            Files.writeString(staging.resolve("tests/allowed-requests.json"), serialized);
            var manifest = JSON.createObjectNode().put("schemaVersion", 1).put("coreliaVersion", version);
            manifest.set("operationSha256", hashes);
            manifest.put("accessControlSha256", sha256(accessText));
            manifest.put("configurationSha256", sha256(Files.readString(runtime.resolve("configuration.json"))));
            Files.writeString(staging.resolve("manifest.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(manifest));
            new ConfigurationLoader().load(runtime, version);
            Files.move(staging, output, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (Files.exists(staging)) try (var paths = Files.walk(staging)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }
    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String kebab(String id) { return id.replaceAll("([a-z0-9])([A-Z])", "$1-$2").replace('_', '-').toLowerCase(java.util.Locale.ROOT); }
}
