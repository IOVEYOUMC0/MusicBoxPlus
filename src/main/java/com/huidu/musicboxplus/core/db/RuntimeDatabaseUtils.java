package com.huidu.musicboxplus.core.db;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import org.bukkit.command.CommandSender;

import java.util.logging.Level;

public final class RuntimeDatabaseUtils {
    private RuntimeDatabaseUtils() {
    }

    public static void logFailure(String operation, Exception e) {
        MusicBox.getInstance().getLogger().log(
            Level.WARNING,
            "Database operation failed during " + operation + ": " + e.getMessage(),
            e
        );
    }

    public static void notifyUnavailable(CommandSender sender) {
        MessageUtils.send(sender, Lang.DATABASE_UNAVAILABLE);
    }
}
