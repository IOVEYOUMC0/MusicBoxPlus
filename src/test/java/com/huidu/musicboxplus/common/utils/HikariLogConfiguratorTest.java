package com.huidu.musicboxplus.common.utils;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Which loggers the plugin is allowed to reconfigure.
//
// The names it sets are applied to the JVM's global logging -- JUL levels, slf4j-simple system
// properties, and log4j core levels -- and nothing ever restores them. That is acceptable for the
// plugin's own classes and not acceptable for anyone else's, so this pins that every name is inside
// the relocated HikariCP package.
//
// shadowJar rewrites `com.zaxxer.hikari` to `com.huidu.musicboxplus.shadow.hikari`, logger name
// strings included, so an unrelocated entry cannot even be reached by the plugin's own database code
// -- it would only ever turn some other plugin's HikariCP logging up or down, for the rest of the JVM
// session.
class HikariLogConfiguratorTest {

    private static final String OWN_PACKAGE = "com.huidu.musicboxplus.shadow.hikari";

    @SuppressWarnings("unchecked")
    private static List<String> loggerNames() throws Exception {
        Field field = HikariLogConfigurator.class.getDeclaredField("LOGGER_NAMES");
        field.setAccessible(true);
        return (List<String>) field.get(null);
    }

    @Test
    void onlyThisPluginsOwnShadedHikariIsReconfigured() throws Exception {
        List<String> names = loggerNames();
        assertFalse(names.isEmpty(), "there should be something to configure");

        for (String name : names) {
            assertTrue(name.equals(OWN_PACKAGE) || name.startsWith(OWN_PACKAGE + "."),
                    "not the plugin's own logger: " + name);
            assertFalse(name.startsWith("com.zaxxer"),
                    "an unrelocated name reaches another plugin's HikariCP: " + name);
        }
    }
}
