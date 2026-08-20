package com.huidu.musicboxplus.module.edit;

import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

final class MusicEditTextInputManager {
    private static final Map<UUID, TextInputData> SESSIONS = new ConcurrentHashMap<>();

    private MusicEditTextInputManager() {
    }

    static void cleanupSession(UUID playerUUID) {
        SESSIONS.remove(playerUUID);
    }

    static void cleanupAllSessions() {
        SESSIONS.clear();
    }

    static void startTextInput(Player player, PlayerMusic music, String type) {
        startTextInput(player, music, type, null, null);
    }

    static void startTextInput(Player player, PlayerMusic music, String type, Consumer<String> submitHandler) {
        startTextInput(player, music, type, submitHandler, null);
    }

    static void startTextInput(Player player, PlayerMusic music, String type, Consumer<String> submitHandler, Runnable cancelHandler) {
        SESSIONS.put(player.getUniqueId(), new TextInputData(music, type, submitHandler, cancelHandler));
        player.closeInventory();

        switch (type) {
            case "name":
                MessageUtils.send(player, Lang.EDIT_INPUT_NAME_PROMPT);
                break;
            case "description":
                MessageUtils.send(player, Lang.EDIT_INPUT_DESCRIPTION_PROMPT, "{description}", music.getDescription());
                break;
            case "publish_description":
                MessageUtils.send(player, Lang.EDIT_INPUT_DESCRIPTION_PROMPT_ALT);
                break;
            case "publish_price":
                MessageUtils.send(player, Lang.EDIT_INPUT_PRICE_PROMPT, "{price}", "0");
                break;
            case "shop_search":
                MessageUtils.send(player, Lang.EDIT_INPUT_SEARCH_PROMPT);
                break;
            default:
                MessageUtils.send(player, Lang.EDIT_INPUT_GENERIC_PROMPT);
        }
        MessageUtils.send(player, Lang.EDIT_INPUT_CANCEL_HINT);
    }

    static TextInputData getTextInputSession(UUID playerUUID) {
        return SESSIONS.remove(playerUUID);
    }

    static void restoreTextInputSession(UUID playerUUID, TextInputData data) {
        if (playerUUID == null || data == null) {
            return;
        }
        SESSIONS.put(playerUUID, data);
    }

    static boolean hasTextInputSession(UUID playerUUID) {
        return SESSIONS.containsKey(playerUUID);
    }

    static void cleanupModuleSessions(java.util.function.Predicate<String> keepType) {
        SESSIONS.entrySet().removeIf(entry -> keepType == null || !keepType.test(entry.getValue().type));
    }

    static final class TextInputData {
        final PlayerMusic music;
        final String type;
        final Consumer<String> submitHandler;
        final Runnable cancelHandler;

        TextInputData(PlayerMusic music, String type, Consumer<String> submitHandler, Runnable cancelHandler) {
            this.music = music;
            this.type = type;
            this.submitHandler = submitHandler;
            this.cancelHandler = cancelHandler;
        }
    }
}
