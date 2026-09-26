package com.huidu.musicboxplus.module.sign;

import com.huidu.musicboxplus.MusicBox;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Sign;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

// Who owns a music sign, stored on the sign block itself.
//
// The `signs` table keys on location alone and has no owner column, so before this the owner was
// only ever held in the live SignPlayer: every restart and every reload rebuilt protected signs
// with a null owner, and isOwnerOrAdmin() reads null as "everyone owns it". The fix has to
// persist somewhere, and the sign block is the natural place -- restorePreventedPlayers already
// holds the Sign it is about to rebuild, the data moves with the block, and adding a column to a
// shipped schema would need a migration path this plugin has no mechanism for.
final class SignOwner {

    // Lazily built: NamespacedKey needs the plugin instance, which does not exist at class-init.
    private static volatile NamespacedKey key;

    private SignOwner() {
    }

    private static NamespacedKey key() {
        NamespacedKey local = key;
        if (local == null) {
            local = new NamespacedKey(MusicBox.getInstance(), "sign_owner");
            key = local;
        }
        return local;
    }

    @Nullable
    static UUID read(@Nullable Sign sign) {
        if (sign == null) {
            return null;
        }
        String raw = sign.getPersistentDataContainer().get(key(), PersistentDataType.STRING);
        if (raw == null) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            // Hand-edited or corrupted: treat as unowned rather than throwing on every sign load.
            return null;
        }
    }

    // Only ever writes; a null owner leaves whatever is already stored alone, so the reload path
    // that still constructs players without an owner cannot erase a real one.
    static void write(@Nullable Sign sign, @Nullable UUID ownerUuid) {
        if (sign == null || ownerUuid == null) {
            return;
        }
        if (ownerUuid.equals(read(sign))) {
            return;
        }
        sign.getPersistentDataContainer().set(key(), PersistentDataType.STRING, ownerUuid.toString());
        sign.update(true);
    }
}
