package com.huidu.musicboxplus;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Guards against config keys that silently do nothing.
//
// The config is deserialized with SnakeYAML under BeanAccess.FIELD and
// setSkipMissingProperties(true), so a key with no backing field is dropped without a word: the
// getter keeps returning whatever it returns and the server owner's setting is ignored forever.
// That is exactly what happened when Lombok was removed - `@Getter private boolean x = true`
// became a getter body declaring a LOCAL `boolean x = true`, which compiles, reads correctly, and
// binds nothing. Eighty-one keys were inert.
//
// Two checks, both purely reflective/textual so no server is needed:
//  1. no getter may return a local variable it just declared (the exact Lombok-removal shape)
//  2. every key in the shipped config.yml must have a field to land in
class ConfigBindingTest {

    private static final Path CONFIG = Path.of("src", "main", "resources", "config.yml");
    private static final Path SOURCE =
            Path.of("src", "main", "java", "com", "huidu", "musicboxplus", "MusicBoxConfig.java");

    // Sections read straight from the YAML by ConfigManager rather than through MusicBoxConfig,
    // so they deliberately have no settings class. Keep this list as short as it can be: every
    // entry is a section this test can no longer protect.
    private static final List<String> RAW_READ_SECTIONS = List.of("sign");

    @Test
    void noGetterReturnsAFreshLocalInsteadOfAField() throws IOException {
        List<String> source = Files.readAllLines(SOURCE, StandardCharsets.UTF_8);
        Pattern declaration = Pattern.compile("^\\s*(?:final\\s+)?[A-Za-z_][\\w<>,\\[\\] .]*\\s+(\\w+)\\s*=\\s*.+;\\s*$");
        Pattern returnLocal = Pattern.compile("^\\s*return\\s+(\\w+);\\s*$");

        List<String> offenders = new ArrayList<>();
        for (int i = 0; i < source.size() - 1; i++) {
            Matcher declared = declaration.matcher(source.get(i));
            if (!declared.matches()) {
                continue;
            }
            Matcher returned = returnLocal.matcher(source.get(i + 1));
            if (returned.matches() && returned.group(1).equals(declared.group(1))) {
                offenders.add("MusicBoxConfig.java:" + (i + 1) + " returns local '" + declared.group(1) + "'");
            }
        }

        assertEquals(List.of(), offenders,
                "these getters return a local they just declared, so the matching config key binds to nothing");
    }

    @Test
    void everyShippedConfigKeyHasABackingField() throws Exception {
        Map<String, Class<?>> sections = sectionTypes();
        List<String> unbound = new ArrayList<>();

        for (Map.Entry<String, List<String>> entry : shippedKeys().entrySet()) {
            String section = entry.getKey();
            if (RAW_READ_SECTIONS.contains(section)) {
                continue;
            }
            Class<?> type = sections.get(section);
            if (type == null) {
                unbound.add(section + ": no settings class for this section");
                continue;
            }
            for (String key : entry.getValue()) {
                String path = section.isEmpty() ? key : section + "." + key;
                if (RAW_READ_SECTIONS.contains(path)) {
                    continue;
                }
                if (!hasField(type, key)) {
                    unbound.add(path + " -> no field on " + type.getSimpleName());
                }
            }
        }

        assertEquals(List.of(), unbound,
                "config.yml ships these keys but nothing deserializes them, so setting them does nothing");
    }

    // A default install must behave the same whether config.yml is present or was deleted.
    @Test
    void shippedValuesMatchTheCodeDefaults() throws Exception {
        MusicBoxConfig fromFile;
        try (InputStream stream = Files.newInputStream(CONFIG)) {
            fromFile = MusicBoxConfig.parseConfig(stream);
        }
        MusicBoxConfig fromCode = MusicBoxConfig.createDefault();

        List<String> drift = new ArrayList<>();
        for (Method method : MusicBoxConfig.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(method.getModifiers()) || method.getParameterCount() != 0) {
                continue;
            }
            String name = method.getName();
            if (!name.startsWith("get") && !name.startsWith("is")) {
                continue;
            }
            if (!method.getReturnType().isPrimitive() && method.getReturnType() != String.class) {
                continue;
            }
            Object a = method.invoke(fromFile);
            Object b = method.invoke(fromCode);
            if (a == null ? b != null : !a.equals(b)) {
                drift.add(name + "(): config.yml=" + a + " createDefault()=" + b);
            }
        }

        assertEquals(List.of(), drift,
                "deleting config.yml would change behaviour: the shipped file and createDefault() disagree");
    }

    private static boolean hasField(Class<?> type, String name) {
        for (Field field : type.getDeclaredFields()) {
            if (field.getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    // Maps a yaml section name to the class its keys deserialize into, by reading the declared
    // field types of MusicBoxConfig rather than a hand-written table that would drift.
    private static Map<String, Class<?>> sectionTypes() {
        Map<String, Class<?>> sections = new LinkedHashMap<>();
        sections.put("", MusicBoxConfig.class);
        for (Field field : MusicBoxConfig.class.getDeclaredFields()) {
            Class<?> type = field.getType();
            if (type.getEnclosingClass() == MusicBoxConfig.class) {
                sections.put(field.getName(), type);
            }
        }
        // One level deeper: the only nested-in-nested section in the file.
        for (Field field : sections.getOrDefault("database", Void.class).getDeclaredFields()) {
            if (field.getType().getEnclosingClass() == MusicBoxConfig.class) {
                sections.put("database." + field.getName(), field.getType());
            }
        }
        return sections;
    }

    // section name -> keys directly under it. Indentation-based, which is all this file needs;
    // list items and comments are skipped.
    private static Map<String, List<String>> shippedKeys() throws IOException {
        Map<String, List<String>> keys = new LinkedHashMap<>();
        List<String> path = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();

        for (String raw : Files.readAllLines(CONFIG, StandardCharsets.UTF_8)) {
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("- ")) {
                continue;
            }
            Matcher matcher = Pattern.compile("^(\\s*)(\\w+):\\s*(.*)$").matcher(raw);
            if (!matcher.matches()) {
                continue;
            }
            int indent = matcher.group(1).length();
            String key = matcher.group(2);
            String value = matcher.group(3).trim();

            while (!indents.isEmpty() && indents.get(indents.size() - 1) >= indent) {
                indents.remove(indents.size() - 1);
                path.remove(path.size() - 1);
            }
            String section = String.join(".", path);
            keys.computeIfAbsent(section, ignored -> new ArrayList<>()).add(key);

            // An empty value opens a section; {} and [] are values, not sections.
            if (value.isEmpty()) {
                path.add(key);
                indents.add(indent);
            }
        }
        assertTrue(keys.containsKey(""), "config.yml parsed as empty");
        return keys;
    }
}
