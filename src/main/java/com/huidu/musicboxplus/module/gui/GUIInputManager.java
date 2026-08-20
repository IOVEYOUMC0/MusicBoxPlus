package com.huidu.musicboxplus.module.gui;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.core.db.DatabaseLoader;
import com.huidu.musicboxplus.core.db.RuntimeDatabaseUtils;
import com.huidu.musicboxplus.core.db.model.PlayerPlayListModel;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.common.utils.MiniMessageUtils;
import com.huidu.musicboxplus.common.utils.scheduler.Scheduler;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.core.song.MusicBoxSongManager;
import com.huidu.musicboxplus.module.gui.playlist.PlayListEditorGUI;
import com.huidu.musicboxplus.module.gui.song.SearchResultGUI;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class GUIInputManager
implements Listener {
    private static final Logger logger = LoggerFactory.getLogger(GUIInputManager.class);
    private static final GUIInputManager instance = new GUIInputManager();
    private final Map<UUID, InputRequest> pendingInputs = new ConcurrentHashMap<UUID, InputRequest>();

    private GUIInputManager() {
        Bukkit.getPluginManager().registerEvents(this, MusicBox.getInstance());
    }

    public static GUIInputManager getInstance() {
        return instance;
    }

    public void requestInput(Player player, InputType type, String promptMessage, InputCallback callback) {
        this.requestInput(player, type, promptMessage, callback, "cancel");
    }

    public void requestInput(Player player, InputType type, Component promptMessage, InputCallback callback) {
        this.requestInput(player, type, promptMessage, callback, "cancel");
    }

    public void requestInput(Player player, InputType type, String promptMessage, InputCallback callback, String cancelKeyword) {
        UUID playerId = player.getUniqueId();
        this.pendingInputs.remove(playerId);
        InputRequest request = new InputRequest(playerId, type, promptMessage, callback, cancelKeyword);
        this.pendingInputs.put(playerId, request);
        player.sendMessage(MiniMessageUtils.processComponent(promptMessage));
        player.sendMessage(Lang.GUI_INPUT_PROMPT.toComponent());
        player.sendMessage(Lang.GUI_INPUT_CANCEL.toComponent("{keyword}", cancelKeyword));
    }

    public void requestInput(Player player, InputType type, Component promptMessage, InputCallback callback, String cancelKeyword) {
        UUID playerId = player.getUniqueId();
        this.pendingInputs.remove(playerId);
        InputRequest request = new InputRequest(playerId, type, PlainTextComponentSerializer.plainText().serialize(promptMessage), callback, cancelKeyword);
        this.pendingInputs.put(playerId, request);
        player.sendMessage(promptMessage);
        player.sendMessage(Lang.GUI_INPUT_PROMPT.toComponent());
        player.sendMessage(Lang.GUI_INPUT_CANCEL.toComponent("{keyword}", cancelKeyword));
    }

    public void requestInput(Player player, Consumer<String> callback) {
        this.requestInput(player, InputType.SEARCH_QUERY, Lang.GUI_INPUT_PROMPT.toComponent(), new InputCallback(){
            @Override
            public void onInputReceived(Player p, String input) {
                callback.accept(input);
            }

            @Override
            public void onInputCancelled(Player p) {
                MessageUtils.send(player, Lang.CANCELLED);
            }
        });
    }

    public void cancelInput(Player player) {
        UUID playerId = player.getUniqueId();
        InputRequest request = this.pendingInputs.remove(playerId);
        if (request != null) {
            request.getCallback().onInputCancelled(player);
            player.sendMessage(Lang.GUI_INPUT_CANCELLED.toComponent());
        }
    }
    
    public void cleanup(UUID playerId) {
        this.pendingInputs.remove(playerId);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerChat(AsyncChatEvent event) {
        this.handlePlayerChat(
                event.getPlayer(),
                PlainTextComponentSerializer.plainText().serialize(event.message()).trim(),
                event::setCancelled
        );
    }

    // Legacy chat event is deprecated, kept alongside AsyncChatEvent so servers with plugins
    // that still fire the legacy event keep working; suppression is local to the handler.
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOWEST)
    public void onLegacyPlayerChat(AsyncPlayerChatEvent event) {
        this.handlePlayerChat(
                event.getPlayer(),
                event.getMessage().trim(),
                event::setCancelled
        );
    }

    private void handlePlayerChat(Player player, String message, Consumer<Boolean> cancelAction) {
        UUID playerId = player.getUniqueId();
        InputRequest request = this.pendingInputs.remove(playerId);
        if (request == null) {
            return;
        }
        cancelAction.accept(true);
        Scheduler.entity(player, () -> this.processPlayerChat(player, request, message));
    }

    private void processPlayerChat(Player player, InputRequest request, String message) {
        UUID playerId = player.getUniqueId();
        if (message.equalsIgnoreCase(request.getCancelKeyword())) {
            request.getCallback().onInputCancelled(player);
            return;
        }
        if (message.isEmpty()) {
            this.pendingInputs.putIfAbsent(playerId, request);
            player.sendMessage(Lang.GUI_INPUT_INVALID.toComponent());
            return;
        }
        try {
            request.getCallback().onInputReceived(player, message);
        }
        catch (Exception e) {
            player.sendMessage(Lang.GUI_INPUT_ERROR.toComponent("{error}", e.getMessage()));
            logger.error("Error processing input for player {}: {}", player.getName(), e.getMessage(), e);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        this.pendingInputs.remove(playerId);
    }

    public boolean hasPendingInput(Player player) {
        return this.pendingInputs.containsKey(player.getUniqueId());
    }

    public InputRequest getPendingInput(Player player) {
        return this.pendingInputs.get(player.getUniqueId());
    }

    public void requestSearchInput(final PlayerWrapper wrapper) {
        requestSearchInput(wrapper, null, null, "song-list", () -> com.huidu.musicboxplus.module.gui.GUIActions.openDefaultInventory(wrapper));
    }

    public void requestSearchInput(final PlayerWrapper wrapper, final com.huidu.musicboxplus.module.gui.song.SongContainerGUI sourceGui, final com.huidu.musicboxplus.module.gui.song.SongContainerGUI.SongGUIParams params, final String guiType, final Runnable backAction) {
        Player player = wrapper.getPlayer();
        player.closeInventory();
        player.sendMessage(Lang.SEARCH_FILTER_HINT.toComponent());
        this.requestInput(player, InputType.SEARCH_QUERY, Lang.SEARCH_PLACEHOLDER.toComponent(), new InputCallback(){

            @Override
            public void onInputReceived(Player p, String input) {
                List<MusicBoxSong> results = MusicBoxSongManager.searchSongs(input);
                if (results.isEmpty()) {
                    MessageUtils.send(p, Lang.SEARCH_NO_RESULTS);
                } else {
                    MessageUtils.send(p, Lang.SEARCH_RESULTS, "{count}", String.valueOf(results.size()));
                    Scheduler.entity(p, () -> SearchResultGUI.open(wrapper, results, input, sourceGui, params, guiType, backAction));
                }
            }

            @Override
            public void onInputCancelled(Player p) {
                MessageUtils.send(p, Lang.CANCELLED);
            }
        });
    }

    public void requestPlaylistNameInput(PlayerWrapper wrapper) {
        Player player = wrapper.getPlayer();
        player.closeInventory();
        this.requestInput(player, InputType.PLAYLIST_NAME, Lang.CREATE_PLAYLIST_INPUT.toComponent(), new InputCallback(){

            @Override
            public void onInputReceived(Player p, String input) {
                if (input.length() < 1 || input.length() > 32) {
                    MessageUtils.send(p, Lang.PLAYLIST_INVALID_NAME);
                    return;
                }
                com.huidu.musicboxplus.common.utils.AsyncTaskManager.runAsync(() -> {
                    try {
                        PlayerWrapper wrapper = PlayerWrapper.getInstance(p);
                        List<PlayerPlayListModel> existingPlaylists = DatabaseLoader.getBase().getPlayLists(p.getUniqueId());
                        boolean exists = existingPlaylists.stream().anyMatch(pl -> pl.getName().equalsIgnoreCase(input));
                        if (exists) {
                            Scheduler.entity(p,
                                    () -> MessageUtils.send(p, Lang.PLAYLIST_EXISTS, "{name}", input));
                            return;
                        }
                        PlayerPlayListModel newPlaylist = DatabaseLoader.getBase().createPlayList(p.getUniqueId(), input);
                        Scheduler.entity(p, () -> {
                            wrapper.setPlayList(newPlaylist);
                            MessageUtils.send(p, Lang.PLAYLIST_CREATED, "{name}", input);
                            new PlayListEditorGUI(wrapper, newPlaylist).openPage(0);
                        });
                    }
                    catch (Exception e) {
                        Scheduler.entity(p,
                                () -> RuntimeDatabaseUtils.notifyUnavailable(p));
                        RuntimeDatabaseUtils.logFailure("create playlist", e);
                    }
                });
            }

            @Override
            public void onInputCancelled(Player p) {
                MessageUtils.send(p, Lang.CANCELLED);
            }
        });
    }

    public Map<UUID, InputRequest> getPendingInputs() {
        return this.pendingInputs;
    }

    public static enum InputType {
        PLAYLIST_NAME,
        SEARCH_QUERY,
        RENAME_PLAYLIST;

    }

    public interface InputCallback {
        void onInputReceived(Player player, String input);

        void onInputCancelled(Player player);
    }

    public static class InputRequest {
        private final UUID playerId;
        private final InputType type;
        private final String promptMessage;
        private final InputCallback callback;
        private final String cancelKeyword;

        public InputRequest(UUID playerId, InputType type, String promptMessage, InputCallback callback, String cancelKeyword) {
            this.playerId = playerId;
            this.type = type;
            this.promptMessage = promptMessage;
            this.callback = callback;
            this.cancelKeyword = cancelKeyword != null ? cancelKeyword : "cancel";
        }

        public UUID getPlayerId() {
            return this.playerId;
        }

        public InputType getType() {
            return this.type;
        }

        public String getPromptMessage() {
            return this.promptMessage;
        }

        public InputCallback getCallback() {
            return this.callback;
        }

        public String getCancelKeyword() {
            return this.cancelKeyword;
        }
    }
}
