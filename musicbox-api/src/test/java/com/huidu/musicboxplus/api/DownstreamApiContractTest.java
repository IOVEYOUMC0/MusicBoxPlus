package com.huidu.musicboxplus.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import org.junit.jupiter.api.Test;

// Locks down the small slice of API surface that downstream plugins actually depend on.
//
// CustomJukeBox, the companion plugin for NBS-backed discs, uses exactly two things:
// getLocation()/getReason()/getPlayer() on six event classes, and the static methods of
// MusicBoxAPI. Everything else (getApiPlayer / getControl / getMusicBoxModel and friends)
// is marked @ApiStatus.Internal and may be refactored freely.
//
// These assertions turn "keep the API compatible" from a verbal agreement into a build-time
// check: touching any signature below breaks the build and reminds you to update downstream,
// instead of surfacing as a NoSuchMethodError at runtime.
//
// Everything goes through reflection without triggering class initialization, so no running
// server is required.
class DownstreamApiContractTest {

    private static final String EVENT_PKG = "com.huidu.musicboxplus.api.event.";

    private static final String[] LOCATION_AWARE_EVENTS = {
        "MusicBoxPauseEvent",
        "MusicBoxResumeEvent",
        "MusicBoxStopEvent",
        "MusicBoxSongChangeEvent",
        "MusicBoxPlayerDestroyEvent",
        "MusicBoxPlaybackStartEvent",
    };

    private static Class<?> load(String name) throws Exception {
        return Class.forName(name, false, DownstreamApiContractTest.class.getClassLoader());
    }

    @Test
    void everyPlayerEventExposesALocationSnapshot() throws Exception {
        for (String simpleName : LOCATION_AWARE_EVENTS) {
            Class<?> type = load(EVENT_PKG + simpleName);
            Method getLocation = type.getMethod("getLocation");
            assertEquals("org.bukkit.Location", getLocation.getReturnType().getName(),
                simpleName + ".getLocation() 必须返回 org.bukkit.Location");
            assertTrue(Modifier.isPublic(getLocation.getModifiers()),
                simpleName + ".getLocation() 必须是 public");
        }
    }

    @Test
    void everyPlayerEventExposesThePlayer() throws Exception {
        for (String simpleName : LOCATION_AWARE_EVENTS) {
            Class<?> type = load(EVENT_PKG + simpleName);
            Method getPlayer = type.getMethod("getPlayer");
            assertEquals("com.huidu.musicboxplus.api.player.MusicBoxSongPlayer",
                getPlayer.getReturnType().getName(),
                simpleName + ".getPlayer() 的返回类型是下游签名的一部分");
        }
    }

    // The events must stay Bukkit events and keep a static getHandlerList; without it Bukkit
    // cannot register listeners for them at all.
    @Test
    void everyPlayerEventStaysARegisterableBukkitEvent() throws Exception {
        Class<?> bukkitEvent = load("org.bukkit.event.Event");
        for (String simpleName : LOCATION_AWARE_EVENTS) {
            Class<?> type = load(EVENT_PKG + simpleName);
            assertTrue(bukkitEvent.isAssignableFrom(type), simpleName + " 必须继承 org.bukkit.event.Event");
            Method handlerList = type.getMethod("getHandlerList");
            assertTrue(Modifier.isStatic(handlerList.getModifiers()),
                simpleName + ".getHandlerList() 必须是 static，否则 Bukkit 注册不了");
        }
    }

    // The destroy event is the only notification that covers every termination path, and its
    // reason tells downstream apart a handover from a real end. Rename or drop REPLACED and
    // downstream wipes its own display whenever playback speed is changed.
    @Test
    void destroyEventKeepsItsReasonContract() throws Exception {
        Class<?> event = load(EVENT_PKG + "MusicBoxPlayerDestroyEvent");
        Method getReason = event.getMethod("getReason");
        Class<?> reason = getReason.getReturnType();
        assertEquals("com.huidu.musicboxplus.api.event.MusicBoxPlayerDestroyEvent$DestroyReason", reason.getName());
        assertTrue(reason.isEnum(), "DestroyReason 必须是枚举");

        java.util.Set<String> names = new java.util.HashSet<>();
        for (Object constant : reason.getEnumConstants()) {
            names.add(((Enum<?>) constant).name());
        }
        // Downstream branches on these; REPLACED above all, since a song or speed switch is a
        // handover and must not be treated as a termination.
        for (String required : new String[] {
                "REPLACED", "SONG_END", "MANUAL_STOP", "BLOCK_GONE",
                "RECORD_REMOVED", "CHUNK_UNLOAD", "WORLD_UNLOAD", "RELOAD", "SHUTDOWN", "UNKNOWN" }) {
            assertTrue(names.contains(required), "DestroyReason 缺少下游依赖的常量: " + required);
        }
    }

    // Downstream uses these to decide whether a disc is driven by MusicBox; they are the only
    // correct routing predicate.
    @Test
    void musicBoxApiKeepsTheDiscPredicates() throws Exception {
        Class<?> api = load("com.huidu.musicboxplus.api.MusicBoxAPI");
        Class<?> itemStack = load("org.bukkit.inventory.ItemStack");

        for (String name : new String[] { "isMusicBoxDisc", "isPluginDrivenJukeboxDisc" }) {
            Method m = api.getMethod(name, itemStack);
            assertTrue(Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers()),
                name + " 必须是 public static");
            assertEquals(boolean.class, m.getReturnType(), name + " 必须返回 boolean");
        }

        Method getPlayerAt = api.getMethod("getPlayerAt", load("org.bukkit.Location"));
        assertNotNull(getPlayerAt);
        assertEquals("com.huidu.musicboxplus.api.player.PositionPlayer",
            getPlayerAt.getReturnType().getName(),
            "getPlayerAt 的返回类型必须留在 api 包内，不能泄漏 core 的实现类型");
    }

    // No third-party type may appear in a public signature of the api package. While one did,
    // the library behind it could never be relocated into this plugin, and every downstream
    // plugin compiled against the interface carried a dependency on it. There are none left,
    // and this keeps one from creeping back in.
    @Test
    void apiSignaturesDoNotLeakThirdPartyTypes() throws Exception {
        Class<?> player = load("com.huidu.musicboxplus.api.player.MusicBoxSongPlayer");
        java.util.List<String> leaks = new java.util.ArrayList<>();
        for (Method m : player.getMethods()) {
            java.util.List<Class<?>> types = new java.util.ArrayList<>();
            types.add(m.getReturnType());
            java.util.Collections.addAll(types, m.getParameterTypes());
            for (Class<?> t : types) {
                if (t.getName().startsWith("com.xxmicloxx.")) {
                    leaks.add(m.getName() + " -> " + t.getName());
                }
            }
        }
        assertEquals(java.util.List.of(), leaks,
            "a public api signature now names a third-party type; use a type from the api "
                + "package instead, or downstream plugins inherit the dependency");
    }
}
