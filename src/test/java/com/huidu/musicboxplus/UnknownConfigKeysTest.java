package com.huidu.musicboxplus;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;

class UnknownConfigKeysTest {
    private static List<String> warnings(String user, String defaults) {
        List<String> messages = new ArrayList<>();
        Logger logger = Logger.getAnonymousLogger();
        logger.setUseParentHandlers(false);
        logger.addHandler(new Handler() {
            public void publish(LogRecord record) { messages.add(record.getMessage()); }
            public void flush() { }
            public void close() { }
        });
        MusicBoxConfig.warnUnknownKeys(user.getBytes(StandardCharsets.UTF_8),
                new ByteArrayInputStream(defaults.getBytes(StandardCharsets.UTF_8)), logger);
        return messages;
    }

    @Test
    void acceptsDocumentedDynamicSoundKeys() {
        assertTrue(warnings("resourcePackInstruments:\n  soundKeys:\n    HARP: piano\n",
                "resourcePackInstruments:\n  soundKeys: {}\n").isEmpty());
    }

    @Test
    void stillWarnsAboutMisspelledFixedProperties() {
        var result = warnings("resourcePackInstruments:\n  enabld: true\n",
                "resourcePackInstruments:\n  enabled: false\n  soundKeys: {}\n");
        assertEquals(1, result.size());
        assertTrue(result.get(0).contains("resourcePackInstruments.enabld"));
    }
}
