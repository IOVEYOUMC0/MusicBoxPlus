package com.huidu.musicboxplus.common.utils;

import com.huidu.musicboxplus.MusicBox;

import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class HikariLogConfigurator {
    // Only this plugin's own copy of HikariCP.
    //
    // The unrelocated `com.zaxxer.hikari.*` names used to be in this list too, and they are not ours:
    // shadowJar relocates the bundled HikariCP to `com.huidu.musicboxplus.shadow.hikari` and rewrites
    // the logger names with it -- verified by extracting the shaded HikariDataSource from the built
    // jar, whose only hikari string is the relocated one. So those four entries could only ever reach
    // some *other* HikariCP on the same JVM (another plugin, or the server itself), and turn its
    // logging to FINE when this plugin runs with debug: true, or suppress its INFO output when it
    // does not. A plugin has no business changing another plugin's log level, and it did so
    // permanently: the JUL levels and the slf4j-simple system properties are never restored.
    private static final List<String> LOGGER_NAMES = List.of(
        "com.huidu.musicboxplus.shadow.hikari",
        "com.huidu.musicboxplus.shadow.hikari.HikariConfig",
        "com.huidu.musicboxplus.shadow.hikari.HikariDataSource",
        "com.huidu.musicboxplus.shadow.hikari.pool.HikariPool"
    );

    private HikariLogConfigurator() {
    }

    public static void configure(MusicBox plugin) {
        boolean debugEnabled = plugin != null && plugin.getConfigObject() != null && plugin.getConfigObject().isDebug();
        applySimpleLoggerProperties(debugEnabled);
        applyJulLevels(debugEnabled);
        applyLog4jLevels(debugEnabled);
    }

    private static void applySimpleLoggerProperties(boolean debugEnabled) {
        String level = debugEnabled ? "debug" : "warn";
        for (String loggerName : LOGGER_NAMES) {
            System.setProperty("org.slf4j.simpleLogger.log." + loggerName, level);
        }
    }

    private static void applyJulLevels(boolean debugEnabled) {
        Level level = debugEnabled ? Level.FINE : Level.WARNING;
        for (String loggerName : LOGGER_NAMES) {
            Logger.getLogger(loggerName).setLevel(level);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void applyLog4jLevels(boolean debugEnabled) {
        try {
            Class<?> levelClass = Class.forName("org.apache.logging.log4j.Level");
            Object level = Enum.valueOf((Class<? extends Enum>) levelClass.asSubclass(Enum.class), debugEnabled ? "DEBUG" : "WARN");
            Class<?> configuratorClass = Class.forName("org.apache.logging.log4j.core.config.Configurator");
            Method setLevel = configuratorClass.getMethod("setLevel", String.class, levelClass);
            for (String loggerName : LOGGER_NAMES) {
                setLevel.invoke(null, loggerName, level);
            }
        } catch (Throwable ignored) {
            // Log4j is not guaranteed to be present on every server runtime.
        }
    }
}
