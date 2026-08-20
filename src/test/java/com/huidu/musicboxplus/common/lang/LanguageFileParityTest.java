package com.huidu.musicboxplus.common.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

// Parity between the language files.
//
// A key missing from a translation silently falls back to English: there is no compile-time or
// run-time signal at all, so a test is the only thing that can catch it.
class LanguageFileParityTest {

    private static final Path RESOURCES = Path.of("src", "main", "resources");

    // Loads a language file keyed by String.
    //
    // The keys have to be normalised by hand: SnakeYAML follows YAML 1.1, where a bare no: / yes:
    // / on: / off: parses into a Boolean key rather than a String one, so declaring
    // Map<String, Object> up front throws ClassCastException while iterating. The language files
    // really do contain no:.
    private static Map<String, Object> load(String fileName) throws Exception {
        Path path = RESOURCES.resolve(fileName);
        assertTrue(Files.exists(path), fileName + " 不存在");
        try (InputStream in = Files.newInputStream(path);
             Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            Map<?, ?> loaded = new Yaml().load(reader);
            assertTrue(loaded != null && !loaded.isEmpty(), fileName + " 解析为空");
            Map<String, Object> normalized = new java.util.LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : loaded.entrySet()) {
                normalized.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            return normalized;
        }
    }

    @Test
    void everyEnglishKeyHasATranslation() throws Exception {
        Map<String, Object> en = load("language_en.yml");
        Map<String, Object> zh = load("language_zh_cn.yml");

        List<String> missing = new ArrayList<>(new TreeSet<>(en.keySet()));
        missing.removeAll(zh.keySet());

        assertEquals(List.of(), missing,
            "language_zh_cn.yml 缺少这些键，玩家会看到英文回退");
    }

    @Test
    void translationsDoNotIntroduceKeysEnglishLacks() throws Exception {
        Map<String, Object> en = load("language_en.yml");
        Map<String, Object> zh = load("language_zh_cn.yml");

        List<String> orphaned = new ArrayList<>(new TreeSet<>(zh.keySet()));
        orphaned.removeAll(en.keySet());

        assertEquals(List.of(), orphaned,
            "这些键只存在于中文文件，多半是拼写错误或已废弃的键");
    }

    @Test
    void translatedValuesKeepTheSameShape() throws Exception {
        Map<String, Object> en = load("language_en.yml");
        Map<String, Object> zh = load("language_zh_cn.yml");

        List<String> wrongShape = new ArrayList<>();
        for (String key : new TreeSet<>(en.keySet())) {
            Object enValue = en.get(key);
            Object zhValue = zh.get(key);
            if (zhValue == null) {
                continue;                       // reported by everyEnglishKeyHasATranslation
            }
            // A scalar must stay a scalar and a lore list must stay a list; swapping the type
            // blows up at the read site.
            boolean enIsList = enValue instanceof List;
            boolean zhIsList = zhValue instanceof List;
            if (enIsList != zhIsList) {
                wrongShape.add(key);
            }
        }

        assertEquals(List.of(), wrongShape,
            "这些键在两个文件里的值类型不一致（标量 vs 列表）");
    }
}
