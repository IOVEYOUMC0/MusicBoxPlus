package com.huidu.musicboxplus.core.sound;

// Note -> the pitch argument of playSound.
//
// playSound only accepts a pitch in [0.5, 2.0], which is exactly two octaves. Under
// twelve-tone equal temperament a semitone is 100 cents and an octave 1200, so
// pitch = 2^(cents/1200). All 2401 values are precomputed to avoid a Math.pow per
// note per listener.
//
// A note's absolute position on the pitch axis is always expressed as
// cents = key * 100 + finePitch, typed as int. int is required rather than
// byte/short: finePitch may be negative and adding it to key crosses the byte
// boundary; keeping the two apart and taking the modulus of each yields negative
// array indices.
//
// Pure arithmetic with no server dependency, so it is fully unit-testable.
public final class NotePitch {

    private static final int CENTS_PER_OCTAVE = 1200;

    // Width Minecraft can actually play: two octaves
    private static final int WINDOW_CENTS = 2 * CENTS_PER_OCTAVE;

    // Transpose mode folds notes into NBS keys 33..57, i.e. 3300..5700 cents
    private static final int WINDOW_LOW_CENTS = 3300;
    private static final int WINDOW_HIGH_CENTS = 5700;

    // Start of the lowest bucket: NBS key -15 == -1500 cents.
    // The five buckets start at keys -15 / 9 / 33 / 57 / 81, 24 keys each.
    private static final int LOWEST_BUCKET_START_CENTS = -1500;
    private static final int BUCKET_COUNT = 5;
    private static final int ALL_BUCKETS_CENTS = BUCKET_COUNT * WINDOW_CENTS;

    // Sound-name suffixes in the resource pack, one per bucket in index order; the
    // middle bucket is the vanilla sound and therefore carries no suffix.
    // This is the naming convention agreed with the resource pack (config.yml's
    // enable10octave documents that a pack is required) - renaming these breaks
    // every existing pack.
    private static final String[] BUCKET_SUFFIX = {"_-2", "_-1", "", "_1", "_2"};

    // The suffix-less bucket: the two octaves the vanilla sounds already cover.
    public static final int NATURAL_BUCKET = 2;

    // PITCH_TABLE[c] = 2^((c - 1200) / 1200), the frequency multiplier c cents from
    // the centre. The ends are 0.5 and 2.0, exactly spanning playSound's range.
    private static final float[] PITCH_TABLE = new float[WINDOW_CENTS + 1];

    static {
        for (int i = 0; i <= WINDOW_CENTS; i++) {
            PITCH_TABLE[i] = (float) Math.pow(2, (i - CENTS_PER_OCTAVE) / (double) CENTS_PER_OCTAVE);
        }
    }

    private NotePitch() {
    }

    // Absolute position of a note on the pitch axis, in cents. finePitch may be negative.
    public static int totalCents(int key, int finePitch) {
        return key * 100 + finePitch;
    }

    // Transpose mode: keep the vanilla sounds and shift the note by whole octaves into
    // the two Minecraft can play. Notes already inside the window keep their octave;
    // notes outside land on the nearest edge octave.
    public static float transposedPitch(int key, int finePitch) {
        int cents = foldIntoRange(totalCents(key, finePitch), WINDOW_LOW_CENTS, WINDOW_HIGH_CENTS);
        return PITCH_TABLE[cents - WINDOW_LOW_CENTS];
    }

    // Bucket mode (enable10octave in the config): no transposition, use the resource
    // pack's sample for the matching octave instead.
    // Returns bucket 0..4, mapping to _-2 / _-1 / no suffix / _1 / _2.
    public static int bucketIndex(int key, int finePitch) {
        return bucketOffset(key, finePitch) / WINDOW_CENTS;
    }

    // Pitch of the note within its own bucket, for bucket mode.
    public static float bucketPitch(int key, int finePitch) {
        return PITCH_TABLE[bucketOffset(key, finePitch) % WINDOW_CENTS];
    }

    // Sound-name suffix for a bucket; empty string for the middle one.
    public static String bucketSuffix(int bucket) {
        return BUCKET_SUFFIX[Math.max(0, Math.min(BUCKET_COUNT - 1, bucket))];
    }



    // Position of the note relative to the start of the lowest bucket, guaranteed to
    // land in [0, total width of the five buckets).
    // Notes beyond the five buckets fold back by whole octaves into the outermost one:
    // the pack has no samples for them anyway, and folding at least preserves the pitch
    // class, which sounds far closer than clamping to the endpoint.
    private static int bucketOffset(int key, int finePitch) {
        int offset = totalCents(key, finePitch) - LOWEST_BUCKET_START_CENTS;
        return foldIntoRange(offset, 0, ALL_BUCKETS_CENTS - 1);
    }

    // Move cents into [lo, hi] by adding or subtracting whole octaves.
    // Callers guarantee the range width is a multiple of an octave, so this converges.
    //
    // Deliberately a loop rather than a division. Notes sit at most an octave or two outside
    // the window in practice, so this runs zero to two times, whereas an integer division costs
    // twenty-odd cycles on every call and measured slower on real songs. Even the worst case
    // the format allows -- an unsigned byte key with a signed short fine pitch, around 58000
    // cents -- stays under fifty iterations.
    private static int foldIntoRange(int cents, int lo, int hi) {
        while (cents < lo) {
            cents += CENTS_PER_OCTAVE;
        }
        while (cents > hi) {
            cents -= CENTS_PER_OCTAVE;
        }
        return cents;
    }
}
