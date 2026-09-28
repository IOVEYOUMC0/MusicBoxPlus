package com.huidu.musicboxplus.common.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

// The two component caches in MiniMessageUtils.
//
// The shared 1024-entry LRU exists so that the finite set of static GUI strings is parsed once. The
// control-panel progress bar rebuilds its name and lore from the current tick on every 2 Hz refresh,
// producing strings that are new every time, one miss each -- those used to go into the shared cache
// and evict all of it within a minute. They now have their own small cache.
//
// Identity is the observable: processComponent returns the cached instance itself, so assertSame
// proves a hit and assertNotSame proves an eviction.
class MiniMessageUtilsCacheTest {

    // Comfortably above MiniMessageUtils' shared cache cap (1024), which is private.
    private static final int SHARED_CACHE_OVERFLOW = 1200;

    @Test
    void dynamicStringsCannotEvictTheSharedCache() {
        String button = "<gray>Now playing</gray>";
        Component cached = MiniMessageUtils.processComponent(button);

        for (int tick = 0; tick < SHARED_CACHE_OVERFLOW; tick++) {
            MiniMessageUtils.processDynamicComponent("<yellow>tick " + tick + "</yellow>");
        }

        assertSame(cached, MiniMessageUtils.processComponent(button),
                "per-tick progress-bar strings evicted the static GUI strings' cache");
    }

    @Test
    void theSharedCacheStillEvictsWhenItOverflows() {
        // Control for the test above: this is what an eviction looks like, so the assertion there is
        // capable of failing. Distinct strings pushed through the shared path must evict it.
        String button = "<gray>Static button</gray>";
        Component cached = MiniMessageUtils.processComponent(button);

        for (int i = 0; i < SHARED_CACHE_OVERFLOW; i++) {
            MiniMessageUtils.processComponent("<gray>shared " + i + "</gray>");
        }

        assertNotSame(cached, MiniMessageUtils.processComponent(button),
                "the shared cache is not bounded any more; the dynamic-cache test would pass trivially");
    }

    @Test
    void dynamicStringsAreStillMemoized() {
        String name = "<yellow>tick 42</yellow>";

        // Two viewers watching the same song at the same tick build the same string, so the dynamic
        // side still has to dedupe; it is separate, not uncached.
        assertSame(MiniMessageUtils.processDynamicComponent(name),
                MiniMessageUtils.processDynamicComponent(name));
    }

    @Test
    void bothCachesRenderTheSameComponent() {
        String lore = "<gold>50%</gold> &7- 1:23";

        assertEquals(MiniMessageUtils.toPlainText(MiniMessageUtils.processComponent(lore)),
                MiniMessageUtils.toPlainText(MiniMessageUtils.processDynamicComponent(lore)),
                "choosing the dynamic cache must not change what is rendered");
        assertEquals(MiniMessageUtils.toLegacyText(lore),
                MiniMessageUtils.toLegacyText(MiniMessageUtils.processDynamicComponent(lore)));
    }

    @Test
    void dynamicComponentsHandlesNullOrEmptyLore() {
        assertEquals(0, MiniMessageUtils.processDynamicComponents(null).size());
        assertEquals(0, MiniMessageUtils.processDynamicComponents(java.util.List.of()).size());
    }
}
