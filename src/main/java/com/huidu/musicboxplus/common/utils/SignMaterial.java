package com.huidu.musicboxplus.common.utils;

import org.bukkit.Material;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

// Shared "is this a sign material" check used by the block interaction and redstone
// listeners. Computed once from the material enum; the set is immutable afterwards.
public final class SignMaterial {

    private static final Set<Material> SIGN_MATERIALS;

    static {
        Set<Material> materials = new HashSet<>();
        for (Material m : Material.values()) {
            if (m.name().endsWith("SIGN")) {
                materials.add(m);
            }
        }
        SIGN_MATERIALS = Collections.unmodifiableSet(materials);
    }

    public static boolean isSign(Material type) {
        return SIGN_MATERIALS.contains(type);
    }

    private SignMaterial() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }
}
