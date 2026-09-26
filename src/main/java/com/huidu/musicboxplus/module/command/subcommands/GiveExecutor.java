package com.huidu.musicboxplus.module.command.subcommands;

import com.huidu.musicboxplus.common.Permissions;
import com.huidu.musicboxplus.core.playback.PlayerWrapper;
import com.huidu.musicboxplus.core.song.MusicBoxSong;
import com.huidu.musicboxplus.module.gui.GUIActions;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

// /musicboxplus give [player] [song] - hands out a disc. Without a song argument it
// opens the give menu; the --single flag switches that menu to single-disc mode.
public class GiveExecutor extends AbstractSelect {

    public GiveExecutor() {
        super(Permissions.GIVE);
    }

    @Override
    protected void noArgs(CommandSender sender, Player player) {
        openGiveMenu(player, false);
    }

    @Override
    protected void noArgsWithFlag(CommandSender sender, Player player, String flag) {
        openGiveMenu(player, isSingleFlag(flag));
    }

    @Override
    protected void processSong(CommandSender sender, Player target, MusicBoxSong song, String[] args) {
        GUIActions.giveDisc(PlayerWrapper.getInstance(target), song);
    }

    private boolean isSingleFlag(String flag) {
        return "-s".equalsIgnoreCase(flag) || "--single".equalsIgnoreCase(flag);
    }

    private void openGiveMenu(Player player, boolean single) {
        if (single) {
            GUIActions.openGiveInventorySingle(PlayerWrapper.getInstance(player));
        } else {
            GUIActions.openGiveInventoryMany(PlayerWrapper.getInstance(player));
        }
    }
}