package com.huidu.musicboxplus.common.utils;

import com.huidu.musicboxplus.common.lang.Lang;
import org.bukkit.command.CommandSender;

import java.util.Collection;

public final class MessageUtils {
    private MessageUtils() {}

    public static void send(CommandSender sender, Lang lang, String... replacements) {
        if (sender == null || lang == null) {
            return;
        }
        sender.sendMessage(lang.toComponent(replacements));
    }

    public static void send(CommandSender sender, String message) {
        if (sender == null || message == null) {
            return;
        }
        sender.sendMessage(MiniMessageUtils.processComponent(message));
    }

    public static void sendAll(CommandSender sender, Collection<String> messages) {
        if (sender == null || messages == null) {
            return;
        }
        for (String message : messages) {
            send(sender, message);
        }
    }
}
