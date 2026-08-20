package com.huidu.musicboxplus.common.utils;

import com.huidu.musicboxplus.MusicBox;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class CraftEngineItemHelper {
    private static final String PLUGIN_NAME = "CraftEngine";
    private static final String ITEMS_API_CLASS = "net.momirealms.craftengine.bukkit.api.CraftEngineItems";
    private static final Set<String> WARNED_KEYS = ConcurrentHashMap.newKeySet();
    private static volatile Plugin cachedPlugin;
    private static volatile Method byIdMethod;
    // Negative cache: without it a missing API class means a Class.forName that throws on every
    // single item build. A CraftEngine reload yields a new Plugin instance, which clears it.
    private static volatile Plugin apiFailedFor;
    // buildBukkitItem lives on the definition class, resolved once per definition type. The
    // reflective getMethod() lookup is expensive and returns the same Method every time.
    private static final Map<Class<?>, Method> BUILD_METHOD_CACHE = new ConcurrentHashMap<>();

    private CraftEngineItemHelper() {
    }

    public static ItemStack buildItem(String itemId) {
        String normalizedId = normalizeId(itemId);
        if (normalizedId == null) {
            return null;
        }
        Method byId = resolveByIdMethod(normalizedId);
        if (byId == null) {
            return null;
        }
        try {
            Object definition = byId.invoke(null, normalizedId);
            if (definition == null) {
                warnOnce("missing:" + normalizedId,
                        "CraftEngine item not found: " + normalizedId,
                        "未找到 CraftEngine 物品: " + normalizedId);
                return null;
            }
            // getMethod is a reflective lookup that returns the same Method every call; keep the
            // resolved one so the hot path (song stack rendering) does not re-look it up. The
            // throwing form is kept on the miss path so a missing method still surfaces as a
            // ReflectiveOperationException like it did before, caught by the caller below.
            Class<?> definitionClass = definition.getClass();
            Method buildMethod = BUILD_METHOD_CACHE.get(definitionClass);
            if (buildMethod == null) {
                buildMethod = definitionClass.getMethod("buildBukkitItem");
                BUILD_METHOD_CACHE.put(definitionClass, buildMethod);
            }
            Object built = buildMethod.invoke(definition);
            if (built instanceof ItemStack) {
                return ((ItemStack) built).clone();
            }
            warnOnce("type:" + normalizedId,
                    "CraftEngine item did not return a Bukkit ItemStack: " + normalizedId,
                    "CraftEngine 物品未返回 Bukkit ItemStack: " + normalizedId);
        } catch (ReflectiveOperationException | LinkageError e) {
            warnOnce("error:" + normalizedId,
                    "Failed to build CraftEngine item " + normalizedId + ": " + e.getMessage(),
                    "构建 CraftEngine 物品失败 " + normalizedId + ": " + e.getMessage(),
                    e);
        }
        return null;
    }

    private static Method resolveByIdMethod(String itemId) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        if (plugin == null || !plugin.isEnabled()) {
            warnOnce("plugin-disabled:" + itemId,
                    "CraftEngine item configured but CraftEngine is not enabled: " + itemId,
                    "已配置 CraftEngine 物品但 CraftEngine 未启用: " + itemId);
            return null;
        }
        if (plugin == apiFailedFor) {
            return null;
        }
        Method method = byIdMethod;
        if (method != null && plugin == cachedPlugin) {
            return method;
        }
        synchronized (CraftEngineItemHelper.class) {
            if (byIdMethod != null && plugin == cachedPlugin) {
                return byIdMethod;
            }
            try {
                Class<?> apiClass = Class.forName(ITEMS_API_CLASS, true, plugin.getClass().getClassLoader());
                byIdMethod = apiClass.getMethod("byId", String.class);
                cachedPlugin = plugin;
                return byIdMethod;
            } catch (ReflectiveOperationException | LinkageError e) {
                warnOnce("api",
                        "CraftEngine API is not available for item compatibility: " + e.getMessage(),
                        "CraftEngine API 不可用，无法兼容物品: " + e.getMessage(),
                        e);
                byIdMethod = null;
                cachedPlugin = plugin;
                apiFailedFor = plugin;
                return null;
            }
        }
    }

    private static String normalizeId(String itemId) {
        if (itemId == null) {
            return null;
        }
        String value = itemId.trim();
        return value.isEmpty() ? null : value;
    }

    private static void warnOnce(String key, String english, String chinese) {
        warnOnce(key, english, chinese, null);
    }

    private static void warnOnce(String key, String english, String chinese, Throwable throwable) {
        if (!WARNED_KEYS.add(key)) {
            return;
        }
        MusicBox plugin = MusicBox.getInstance();
        if (plugin == null) {
            return;
        }
        String message = LogLocale.text(plugin, english, chinese);
        if (throwable == null) {
            plugin.getLogger().warning(message);
        } else {
            plugin.getLogger().log(Level.WARNING, message, throwable);
        }
    }
}
