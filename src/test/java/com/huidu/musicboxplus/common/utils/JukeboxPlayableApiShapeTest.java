package com.huidu.musicboxplus.common.utils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.Test;

// Pins down the exact shape of the Paper API that JukeboxPlayableHelper relies on.
//
// The helper uses the jukebox_playable component to suppress vanilla disc music while MusicBox
// drives playback, and whether a jukebox plays anything at all depends solely on that component
// being present on the item. If a signature drifts and the lookup is done reflectively, the
// failure is silent: discs simply keep playing vanilla music.
//
// None of these assertions may trigger static initialization of the target classes — the clinit
// of DataComponentTypes needs a live server registry and throws ExceptionInInitializerError in
// the test JVM.
class JukeboxPlayableApiShapeTest {

    private static final String DATA_COMPONENT_TYPES = "io.papermc.paper.datacomponent.DataComponentTypes";
    private static final String DATA_COMPONENT_TYPE = "io.papermc.paper.datacomponent.DataComponentType";
    private static final String JUKEBOX_PLAYABLE = "io.papermc.paper.datacomponent.item.JukeboxPlayable";

    @Test
    void paperDataComponentClassesExist() {
        // initialize=false: static init of DataComponentTypes needs the server registry and
        // throws ExceptionInInitializerError in the test JVM. Only existence matters here.
        ClassLoader cl = getClass().getClassLoader();
        assertDoesNotThrow(() -> Class.forName(DATA_COMPONENT_TYPES, false, cl));
        assertDoesNotThrow(() -> Class.forName(DATA_COMPONENT_TYPE, false, cl));
        assertDoesNotThrow(() -> Class.forName(JUKEBOX_PLAYABLE, false, cl));
    }

    @Test
    void jukeboxPlayableComponentTypeFieldExists() throws Exception {
        // Again without initializing: assert only that the field is declared with the right type
        Class<?> types = Class.forName(DATA_COMPONENT_TYPES, false, getClass().getClassLoader());
        Field field = types.getField("JUKEBOX_PLAYABLE");
        assertNotNull(field);
        assertEquals("io.papermc.paper.datacomponent.DataComponentType$Valued",
            field.getType().getName(),
            "JUKEBOX_PLAYABLE 必须是 Valued 类型——setData 的重载是按这个类型选的");
    }

    // Removing the component goes through ItemStack.unsetData(DataComponentType). The parameter
    // is the base DataComponentType, not DataComponentType.Valued.
    @Test
    void unsetDataAcceptsTheBaseComponentType() throws Exception {
        Class<?> baseType = Class.forName(DATA_COMPONENT_TYPE);
        Method unsetData = ItemStack.class.getMethod("unsetData", baseType);
        assertNotNull(unsetData);
    }

    // Writing the component goes through ItemStack.setData(DataComponentType.Valued, Object).
    // The parameter must be the nested Valued type; looking it up with the base
    // DataComponentType throws NoSuchMethodException.
    @Test
    void setDataRequiresTheNestedValuedType() throws Exception {
        Class<?> valuedType = Class.forName("io.papermc.paper.datacomponent.DataComponentType$Valued");
        Method setData = ItemStack.class.getMethod("setData", valuedType, Object.class);
        assertNotNull(setData);
    }

    // The JukeboxPlayable factory takes a JukeboxSong, not a NamespacedKey.
    @Test
    void jukeboxPlayableFactoryTakesAJukeboxSong() throws Exception {
        Class<?> jukeboxPlayable = Class.forName(JUKEBOX_PLAYABLE);
        Method factory = jukeboxPlayable.getMethod("jukeboxPlayable", org.bukkit.JukeboxSong.class);
        assertNotNull(factory);
    }

    // Bukkit fallback path: the jukeboxPlayable accessors on ItemMeta must exist.
    @Test
    void bukkitFallbackAccessorsExist() throws Exception {
        Class<?> component = Class.forName("org.bukkit.inventory.meta.components.JukeboxPlayableComponent");
        assertNotNull(ItemMeta.class.getMethod("getJukeboxPlayable"));
        assertNotNull(ItemMeta.class.getMethod("setJukeboxPlayable", component));
    }

    // JukeboxPlayableHelper must not use reflection: a failed lookup is swallowed silently,
    // while a direct API call surfaces a signature mismatch at compile time.
    @Test
    void helperDoesNotUseReflection() {
        for (Field f : JukeboxPlayableHelper.class.getDeclaredFields()) {
            assertNotEquals(Method.class, f.getType(),
                "JukeboxPlayableHelper 不应再缓存反射 Method：" + f.getName()
                    + "。反射查找失败会被静默吞掉，导致抑制原版播放无声失效。");
        }
    }
}
