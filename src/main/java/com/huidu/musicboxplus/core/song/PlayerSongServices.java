package com.huidu.musicboxplus.core.song;

import com.huidu.musicboxplus.core.engine.CompiledSong;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Map;
import java.util.Optional;

// Registration points for the editor module's player-music services. core knows the contracts
// and nothing about module.edit; the module registers its implementations at startup.
public final class PlayerSongServices {

    @FunctionalInterface
    public interface PlayerMusicCompiler {
        CompiledSong compile(PlayerMusicSource music, Map<Integer, String> soundOverrides);
    }

    @FunctionalInterface
    public interface SoundOverrides {
        Map<Integer, String> build();
    }

    @FunctionalInterface
    public interface PlayerMusicDiscResolver {
        Optional<PlayerMusicSource> findByDisc(ItemMeta meta);
    }

    @FunctionalInterface
    public interface MidiConverter {
        int convertFolder(java.io.File rootFolder);
    }

    private static volatile PlayerMusicCompiler compiler;
    private static volatile SoundOverrides soundOverrides;
    private static volatile PlayerMusicDiscResolver discResolver;
    private static volatile MidiConverter midiConverter;

    private PlayerSongServices() {
    }

    // Idempotent: re-registering the same implementations on every reload is harmless.
    public static void register(PlayerMusicCompiler compiler, SoundOverrides soundOverrides,
                                PlayerMusicDiscResolver discResolver, MidiConverter midiConverter) {
        PlayerSongServices.compiler = compiler;
        PlayerSongServices.soundOverrides = soundOverrides;
        PlayerSongServices.discResolver = discResolver;
        PlayerSongServices.midiConverter = midiConverter;
    }

    public static Map<Integer, String> buildSoundOverrides() {
        SoundOverrides provider = soundOverrides;
        return provider != null ? provider.build() : Map.of();
    }

    public static CompiledSong compilePlayerMusic(PlayerMusicSource music, Map<Integer, String> soundOverrides) {
        PlayerMusicCompiler c = compiler;
        return c != null ? c.compile(music, soundOverrides) : null;
    }

    public static Optional<PlayerMusicSource> findPlayerMusicByDisc(ItemMeta meta) {
        PlayerMusicDiscResolver resolver = discResolver;
        return resolver != null ? resolver.findByDisc(meta) : Optional.empty();
    }

    public static int convertMidiFolder(java.io.File rootFolder) {
        MidiConverter converter = midiConverter;
        return converter != null ? converter.convertFolder(rootFolder) : 0;
    }
}
