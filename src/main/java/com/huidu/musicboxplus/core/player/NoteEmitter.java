package com.huidu.musicboxplus.core.player;

import com.huidu.musicboxplus.core.engine.CompiledSong;
import com.huidu.musicboxplus.core.sound.NotePitch;
import com.huidu.musicboxplus.core.sound.SongInstruments;
import com.huidu.musicboxplus.core.sound.StereoPan;
import com.huidu.musicboxplus.core.sound.VanillaInstrument;
import io.papermc.paper.registry.RegistryAccess;
import io.papermc.paper.registry.RegistryKey;
import org.bukkit.*;
import org.bukkit.entity.Player;

// Turns one tick of a song into sound for one listener.
//
// This is the innermost loop on the server: notes at this tick, times listeners in range,
// times twenty times a second. Everything that does not vary per note is computed by the
// caller and passed in, and everything that does is read from parallel arrays.
//
// One Location is allocated per call and moved between notes rather than cloned per note.
// playSound reads the coordinates before returning, so reusing the object is safe, and it
// removes an allocation from the innermost loop for panned songs.
public final class NoteEmitter {

    // Sound source offsets for stereo run along the listener's left axis, which in Minecraft's
    // yaw convention is (cos yaw, 0, sin yaw): the facing vector is (-sin yaw, 0, cos yaw), so
    // its right-hand side is the negation of this one. Tabulated per whole degree, matching how
    // the previous engine rounded, so panned songs keep sounding identical.
    private static final double[] COS = new double[360];
    private static final double[] SIN = new double[360];

    static {
        for (int deg = 0; deg < 360; deg++) {
            COS[deg] = Math.cos(Math.toRadians(deg));
            SIN[deg] = Math.sin(Math.toRadians(deg));
        }
    }

    // Registry entries for the stock instruments, resolved once.
    //
    // Naming a sound by string makes the server parse the identifier, build a one-off sound
    // event and wrap it in a direct holder for every note of every listener, and then write the
    // whole identifier into each packet. Handing it a registry entry instead is a field read on
    // the server and a single id on the wire. Only names that are not stock instruments --
    // custom instruments, resource-pack substitutions, the suffixed octave variants -- have to
    // go by string.
    //
    // Resolved on first use rather than in a static initialiser: the registry needs a running
    // server, and this class is also loaded by tests. Entries stay null where the server has no
    // such sound, which is the case for the newest instruments on older versions.
    private static volatile Sound[] vanillaSounds;

    private static Sound[] vanillaSounds() {
        Sound[] cached = vanillaSounds;
        if (cached != null) {
            return cached;
        }
        Sound[] resolved = new Sound[VanillaInstrument.count()];
        try {
            Registry<Sound> registry = RegistryAccess.registryAccess().getRegistry(RegistryKey.SOUND_EVENT);
            for (int id = 0; id < resolved.length; id++) {
                NamespacedKey key = NamespacedKey.fromString(VanillaInstrument.soundNameById(id));
                resolved[id] = key == null ? null : registry.get(key);
            }
            // An instrument this server has no sound for plays its stand-in instead of nothing:
            // the trumpet timbres arrived in 1.26, and on anything older those notes were simply
            // dropped by the client. Filling the entry here also keeps the trumpet name out of
            // the packet, since a non-null entry is what makes play() skip the name path.
            for (int id = 0; id < resolved.length; id++) {
                VanillaInstrument fallback = resolved[id] == null
                        ? VanillaInstrument.byId(id).fallback() : null;
                if (fallback != null) {
                    resolved[id] = resolved[fallback.ordinal()];
                }
            }
        } catch (Throwable ignored) {
            // No registry available; every note falls back to naming its sound.
        }
        vanillaSounds = resolved;
        return resolved;
    }

    private NoteEmitter() {
    }

    // Volume factors that hold for the whole tick. Each term is a 0..100 percentage except the
    // range term.
    //
    // The range term only ever raises the volume. The client derives its attenuation radius as
    // max(volume, 1.0) * 16 blocks, so a value above 1 is what lets jukeboxRadius 64 be heard at
    // 64 blocks -- but a value below 1 does not shrink that radius below 16, it only makes the
    // song quieter. Which range is actually in force is decided server-side by who gets the
    // packet at all. So distances under 16 got a straight volume cut in exchange for nothing:
    // with the default speakerRadius 10 a speaker played at 10/16 = 0.625, audibly below the
    // same song heard without speaker mode.
    public static float baseVolume(int engineVolume, int playbackVolume, int listenerVolume,
                                   float distance) {
        return clampPercent(engineVolume) / 100F
                * clampPercent(playbackVolume) / 100F
                * clampPercent(listenerVolume) / 100F
                * Math.max(1F, (1F / 16F) * distance);
    }

    // For players whose sound follows the listener, so there is no range falloff to apply.
    public static float baseVolume(int engineVolume, int playbackVolume, int listenerVolume) {
        return clampPercent(engineVolume) / 100F
                * clampPercent(playbackVolume) / 100F
                * clampPercent(listenerVolume) / 100F;
    }

    public static float noteVolume(float baseVolume, int layerVolume, int velocity) {
        return baseVolume * (layerVolume / 100F) * (velocity / 100F);
    }

    private static int clampPercent(int value) {
        return Math.max(0, Math.min(100, value));
    }

    // stereoWidth is how many blocks a hard-panned note moves sideways; 0 disables panning.
    public static void emitTick(Player listener, Location at, CompiledSong song, int tick,
                                float baseVolume, SoundCategory category,
                                boolean tenOctave, float stereoWidth) {
        emitTick(listener, at, song, tick, baseVolume, category, tenOctave, stereoWidth, 0F);
    }

    // fakeStereoWidth widens a song that carries no panning of its own by sending every note
    // twice, once to each side. It costs a second packet per note per listener, so it applies
    // only when the song has nothing real to pan, and only for players that ask for it.
    public static void emitTick(Player listener, Location at, CompiledSong song, int tick,
                                float baseVolume, SoundCategory category, boolean tenOctave,
                                float stereoWidth, float fakeStereoWidth) {
        int end = song.noteEnd(tick);
        int start = song.noteStart(tick);
        if (start >= end || baseVolume <= 0F) {
            return;
        }

        SongInstruments instruments = song.instruments();
        Sound[] entries = vanillaSounds();
        boolean panned = stereoWidth > 0F && song.isStereo();
        boolean widened = !panned && fakeStereoWidth > 0F;

        Location source = at;
        double leftX = 0;
        double leftZ = 0;
        if (panned || widened) {
            int yaw = ((int) at.getYaw() % 360 + 360) % 360;
            leftX = COS[yaw];
            leftZ = SIN[yaw];
            source = at.clone();
        }

        for (int note = start; note < end; note++) {
            float volume = noteVolume(baseVolume, song.layerVolume(note), song.velocity(note));
            if (volume <= 0F) {
                continue;
            }

            int key = song.key(note);
            int finePitch = song.finePitch(note);
            int instrument = song.instrument(note);

            String sound;
            float pitch;
            boolean naturalBucket;
            if (tenOctave) {
                int bucket = NotePitch.bucketIndex(key, finePitch);
                naturalBucket = bucket == NotePitch.NATURAL_BUCKET;
                sound = instruments.soundName(instrument, bucket);
                pitch = NotePitch.bucketPitch(key, finePitch);
            } else {
                naturalBucket = true;
                sound = instruments.baseSoundName(instrument);
                pitch = NotePitch.transposedPitch(key, finePitch);
            }
            // Instruments with no sound file exist in real songs, most often as the marker a
            // tempo change is stored on. Sending an empty sound name is a wasted packet per
            // listener.
            if (sound.isEmpty()) {
                continue;
            }
            // A name only has to be sent when it is not a stock instrument: a custom
            // instrument, a resource-pack substitution, or one of the suffixed octave variants.
            Sound entry = naturalBucket && instrument >= 0 && instrument < entries.length
                    && instruments.isPlainVanilla(instrument)
                    ? entries[instrument] : null;

            if (panned) {
                float offset = StereoPan.leftOffset(song.layerPanning(note), song.panning(note),
                        stereoWidth);
                source.setX(at.getX() + leftX * offset);
                source.setZ(at.getZ() + leftZ * offset);
            } else if (widened) {
                source.setX(at.getX() + leftX * fakeStereoWidth);
                source.setZ(at.getZ() + leftZ * fakeStereoWidth);
                play(listener, source, entry, sound, category, volume, pitch);
                source.setX(at.getX() - leftX * fakeStereoWidth);
                source.setZ(at.getZ() - leftZ * fakeStereoWidth);
            }

            play(listener, source, entry, sound, category, volume, pitch);
        }
    }

    private static void play(Player listener, Location source, Sound entry, String sound,
                             SoundCategory category, float volume, float pitch) {
        if (entry != null) {
            listener.playSound(source, entry, category, volume, pitch);
        } else {
            listener.playSound(source, sound, category, volume, pitch);
        }
    }
}
