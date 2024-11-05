package ru.spliterash.musicbox.commands.subcommands;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ru.spliterash.musicbox.gui.GUIActions;
import ru.spliterash.musicbox.players.PlayerWrapper;
import ru.spliterash.musicbox.song.MusicBoxSong;

public class ShopExecutor extends AbstractSelect {
    public ShopExecutor() {
        super("musicbox.shop");
    }

    @Override
    protected void noArgs(CommandSender sender, Player player) {
        GUIActions.openShopInventory(PlayerWrapper.getInstance(player));
    }

    @Override
    protected void processSong(CommandSender sender, Player player, MusicBoxSong song, String[] args) {
        GUIActions.playerBuyMusic(PlayerWrapper.getInstance(player), song);
    }
}
