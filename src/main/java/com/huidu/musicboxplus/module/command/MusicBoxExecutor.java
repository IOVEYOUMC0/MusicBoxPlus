package com.huidu.musicboxplus.module.command;

import com.huidu.musicboxplus.MusicBox;
import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.common.lang.Lang;
import com.huidu.musicboxplus.common.utils.ArrayUtils;
import com.huidu.musicboxplus.common.utils.MessageUtils;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.module.command.subcommands.*;
import com.huidu.musicboxplus.module.gui.GUIActions;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class MusicBoxExecutor
implements TabExecutor {
    // Sub-commands are stateless (Mute only references back for help), so the registry is
    // built once and reused until the module enablement flags change, instead of re-created
    // on every command and tab completion. The snapshot string is the invalidation key.
    private static final class Registry {
        final String snapshot;
        final Map<String, SubCommand> commands;

        Registry(String snapshot, Map<String, SubCommand> commands) {
            this.snapshot = snapshot;
            this.commands = commands;
        }
    }

    private volatile Registry registry;

    private Map<String, SubCommand> getSubCommands() {
        MusicBox plugin = MusicBox.getInstance();
        String snapshot = registrySnapshot(plugin);
        Registry current = this.registry;
        if (current != null && current.snapshot.equals(snapshot)) {
            return current.commands;
        }
        synchronized (this) {
            current = this.registry;
            if (current != null && current.snapshot.equals(snapshot)) {
                return current.commands;
            }
            Map<String, SubCommand> subs = buildSubCommands(plugin);
            this.registry = new Registry(snapshot, subs);
            return subs;
        }
    }

    private static String registrySnapshot(MusicBox plugin) {
        return plugin.isShopModuleEnabled() + "|"
                + plugin.isPlayerMusicShopModuleEnabled() + "|"
                + plugin.isPublishModuleEnabled() + "|"
                + plugin.isGiveModuleEnabled() + "|"
                + plugin.isPlaylistsModuleEnabled() + "|"
                + plugin.isPlaybackModuleEnabled() + "|"
                + plugin.isSongTagsModuleEnabled() + "|"
                + plugin.isTextPlayerModuleEnabled() + "|"
                + plugin.isEditorModuleEnabled();
    }

    // LinkedHashMap keeps the registration order, which drives the tab-completion order.
    private Map<String, SubCommand> buildSubCommands(MusicBox plugin) {
        Map<String, SubCommand> subs = new LinkedHashMap<String, SubCommand>();
        if (plugin.isShopModuleEnabled()) {
            subs.put("shop", new ShopExecutor());
        }
        if (plugin.isPlayerMusicShopModuleEnabled() || plugin.isPublishModuleEnabled()) {
            // Legacy alias kept for compatibility; the visible primary entry is now /musicboxplus shop
            subs.put("shopmusic", new ShopMusicExecutor());
        }
        if (plugin.isPublishModuleEnabled()) {
            // Admin review commands: /musicboxplus publish review|list|approve|reject
            subs.put("publish", new PublishAdminExecutor());
        }
        if (plugin.isGiveModuleEnabled()) {
            subs.put("give", new GiveExecutor());
        }
        if (plugin.isPlaylistsModuleEnabled()) {
            subs.put("playlist", new PlaylistExecutor());
        }
        if (plugin.isPlaybackModuleEnabled()) {
            subs.put("play", new PlayExecutor());
            subs.put("stop", new StopExecutor());
            subs.put("volume", new VolumeExecutor());
            subs.put("silent", new SilentExecutor());
            subs.put("speed", new SpeedExecutor());
            subs.put("autoplay", new AutoPlayExecutor());
            subs.put("mute", new Mute(this));
        }
        subs.put("reload", new ReloadExecutor());
        if (plugin.isSongTagsModuleEnabled()) {
            subs.put("tag", new SongTagExecutor());
        }
        if (plugin.isTextPlayerModuleEnabled()) {
            subs.put("textplayer", new TextPlayerExecutor());
        }
        if (plugin.isEditorModuleEnabled()) {
            subs.put("edit", new EditExecutor());
        }
        return subs;
    }

    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String[] args) {
        SubCommand executor = args.length > 0 ? this.getSubCommands().get(args[0].toLowerCase()) : null;
        if (MusicBox.getInstance().isStartingUp() && !(executor instanceof ReloadExecutor)) {
            MessageUtils.send(sender, Lang.PLUGIN_NOT_LOADED);
            return true;
        }
        if (executor instanceof ReloadExecutor) {
            if (executor.canExecute(sender)) {
                executor.execute(sender, ArrayUtils.removeFirst(String.class, args));
            } else {
                MessageUtils.send(sender, Lang.NO_PERMISSIONS);
            }
            return true;
        }
        if (!sender.hasPermission(Permissions.USE)) {
            MessageUtils.send(sender, Lang.NO_PERMISSIONS);
            return true;
        }
        if (args.length == 0) {
            if (sender instanceof Player && MusicBox.getInstance().isPlaybackModuleEnabled()) {
                GUIActions.openDefaultInventory(PlayerWrapper.getInstance((Player) sender));
            } else {
                this.sendHelp(sender);
            }
            return true;
        }
        if (executor == null) {
            this.sendHelp(sender);
            return true;
        }
        if (executor.canExecute(sender)) {
            executor.execute(sender, ArrayUtils.removeFirst(String.class, args));
        } else {
            MessageUtils.send(sender, Lang.NO_PERMISSIONS);
        }
        return true;
    }

    public void sendHelp(CommandSender sender) {
        MusicBox plugin = MusicBox.getInstance();
        MessageUtils.send(sender, Lang.COMMAND_HELP);
        if (sender.hasPermission(Permissions.USE)) {
            if (plugin.isPlaybackModuleEnabled()) {
                MessageUtils.send(sender, Lang.COMMAND_HELP_MAIN);
                MessageUtils.send(sender, Lang.COMMAND_HELP_PLAY);
                MessageUtils.send(sender, Lang.COMMAND_HELP_STOP);
                if (sender.hasPermission(Permissions.VOLUME)) {
                    MessageUtils.send(sender, Lang.COMMAND_HELP_VOLUME);
                }
                MessageUtils.send(sender, Lang.COMMAND_HELP_SILENT);
                if (sender.hasPermission(Permissions.SPEED)) {
                    MessageUtils.send(sender, Lang.COMMAND_HELP_SPEED);
                }
                if (sender.hasPermission(Permissions.MUTE) || sender.hasPermission(Permissions.ADMIN)) {
                    MessageUtils.send(sender, Lang.COMMAND_HELP_MUTE);
                }
            }
            if (plugin.isPlaylistsModuleEnabled() && sender.hasPermission(Permissions.PLAYLIST)) {
                MessageUtils.send(sender, Lang.COMMAND_HELP_PLAYLIST);
            }
        }
        if (plugin.isShopModuleEnabled() && sender.hasPermission(Permissions.SHOP)) {
            MessageUtils.send(sender, Lang.COMMAND_HELP_SHOP);
            if (plugin.isPlayerMusicShopModuleEnabled() && sender.hasPermission(Permissions.SHOPMUSIC)) {
                MessageUtils.send(sender, Lang.COMMAND_HELP_SHOP_PLAYER);
            }
            if (plugin.isPublishModuleEnabled() && sender.hasPermission(Permissions.SHOPMUSIC)) {
                MessageUtils.send(sender, Lang.COMMAND_HELP_SHOP_PUBLISH);
            }
        } else if (!plugin.isShopModuleEnabled()
                && plugin.isPublishModuleEnabled()
                && sender.hasPermission(Permissions.SHOPMUSIC)) {
            MessageUtils.send(sender, Lang.COMMAND_HELP_SHOP_MUSIC);
        }
        if (plugin.isGiveModuleEnabled() && sender.hasPermission(Permissions.GIVE)) {
            MessageUtils.send(sender, Lang.COMMAND_HELP_GIVE);
        }
        if (plugin.isEditorModuleEnabled() && sender.hasPermission(Permissions.EDIT)) {
            MessageUtils.send(sender, Lang.COMMAND_HELP_EDIT);
            MessageUtils.send(sender, Lang.COMMAND_HELP_EDIT_CREATE);
            MessageUtils.send(sender, Lang.COMMAND_HELP_EDIT_LIST);
            MessageUtils.send(sender, Lang.COMMAND_HELP_EDIT_IMPORT);
            MessageUtils.send(sender, Lang.COMMAND_HELP_EDIT_EXPORT);
            if (plugin.isWebEditorModuleEnabled() && plugin.isWebEditorEnabled()) {
                MessageUtils.send(sender, Lang.COMMAND_HELP_EDIT_WEB);
            }
        }
        if (!sender.hasPermission(Permissions.ADMIN) && sender.hasPermission(Permissions.RELOAD)) {
            MessageUtils.send(sender, Lang.COMMAND_HELP_RELOAD);
        }
        if (plugin.isSongTagsModuleEnabled() && sender.hasPermission(Permissions.ADMIN)) {
            MessageUtils.send(sender, Lang.COMMAND_HELP_TAG);
        }
        if (plugin.isTextPlayerModuleEnabled() && sender.hasPermission(Permissions.ADMIN)) {
            MessageUtils.send(sender, Lang.COMMAND_HELP_TEXT_PLAYER);
        }
        if (sender.hasPermission(Permissions.ADMIN)) {
            sendAdminHelp(sender, plugin);
        }
    }

    private void sendAdminHelp(CommandSender sender, MusicBox plugin) {
        MessageUtils.send(sender, Lang.ADMIN_HELP);
        if (plugin.isShopModuleEnabled()) {
            MessageUtils.send(sender, Lang.ADMIN_HELP_SHOP);
        }
        if (plugin.isGiveModuleEnabled()) {
            MessageUtils.send(sender, Lang.ADMIN_HELP_GIVE);
        }
        if (plugin.isPlaybackModuleEnabled()) {
            MessageUtils.send(sender, Lang.ADMIN_HELP_PLAY);
            MessageUtils.send(sender, Lang.ADMIN_HELP_STOP);
            MessageUtils.send(sender, Lang.ADMIN_HELP_VOLUME);
            MessageUtils.send(sender, Lang.ADMIN_HELP_SILENT);
            MessageUtils.send(sender, Lang.ADMIN_HELP_MUTE);
        }
        MessageUtils.send(sender, Lang.ADMIN_HELP_RELOAD);
        if (plugin.isSongTagsModuleEnabled()) {
            MessageUtils.send(sender, Lang.ADMIN_HELP_TAG);
        }
        if (plugin.isTextPlayerModuleEnabled()) {
            MessageUtils.send(sender, Lang.ADMIN_HELP_TEXT_PLAYER);
        }
    }

    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String[] args) {
        if (args.length <= 1) {
            // First level comes straight from the registry: whatever the sender could run is
            // offered, so tab completion can never drift out of sync with the registered
            // commands or their permission checks.
            String prefix = args.length == 1 ? args[0].toLowerCase() : "";
            ArrayList<String> tabComplete = new ArrayList<String>();
            for (Map.Entry<String, SubCommand> entry : this.getSubCommands().entrySet()) {
                if (entry.getKey().startsWith(prefix) && entry.getValue().canExecute(sender)) {
                    tabComplete.add(entry.getKey());
                }
            }
            return tabComplete;
        }
        SubCommand executor = this.getSubCommands().get(args[0].toLowerCase());
        if (executor != null && executor.canExecute(sender)) {
            return executor.tabComplete(sender, ArrayUtils.removeFirst(String.class, args));
        }
        return Collections.emptyList();
    }
}
