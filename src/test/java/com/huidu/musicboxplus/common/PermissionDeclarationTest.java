package com.huidu.musicboxplus.common;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// A permission the code checks but plugin.yml never declares is not "ungated" -- Bukkit falls back
// to PermissionDefault.OP for an unregistered node, so it silently becomes op-only. The plugin
// rename left every node in plugin.yml on the old musicbox. prefix while Permissions.java moved to
// musicboxplus., which locked non-op players out of the entire plugin and looked, from the
// server owner's side, like a permissions plugin that had stopped working.
class PermissionDeclarationTest {

    private static final Path PLUGIN_YML = Path.of("src", "main", "resources", "plugin.yml");

    @Test
    void everyCheckedPermissionIsDeclared() throws Exception {
        List<String> declared = declaredNodes();
        List<String> missing = new ArrayList<>();
        for (String node : checkedNodes()) {
            if (!declared.contains(node)) {
                missing.add(node);
            }
        }
        assertEquals(List.of(), missing,
                "these nodes are checked in code but absent from plugin.yml, so Bukkit treats them "
                        + "as op-only however the server owner grants them");
    }

    private static List<String> checkedNodes() throws Exception {
        List<String> nodes = new ArrayList<>();
        for (Field field : Permissions.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            field.setAccessible(true);
            String value = (String) field.get(null);
            // Prefix constants are concatenated with a number at the call site and are not nodes.
            if (value != null && !value.endsWith(".")) {
                nodes.add(value);
            }
        }
        assertTrue(nodes.size() > 10, "Permissions.java yielded almost nothing; the reflection broke");
        return nodes;
    }

    // Read as text, not through YamlConfiguration: Bukkit splits keys on '.', so a node named
    // musicboxplus.use comes back as a nested section rather than the key the server matches.
    private static List<String> declaredNodes() throws Exception {
        List<String> nodes = new ArrayList<>();
        boolean inPermissions = false;
        for (String line : Files.readAllLines(PLUGIN_YML)) {
            if (!line.startsWith(" ") && !line.isBlank()) {
                inPermissions = line.startsWith("permissions:");
                continue;
            }
            if (!inPermissions) {
                continue;
            }
            Matcher matcher = NODE.matcher(line);
            if (matcher.matches()) {
                nodes.add(matcher.group(1));
            }
        }
        assertTrue(nodes.size() > 10, "no permission nodes parsed out of plugin.yml");
        return nodes;
    }

    private static final Pattern NODE = Pattern.compile("^ {2}([A-Za-z0-9_.]+):\s*$");
}
