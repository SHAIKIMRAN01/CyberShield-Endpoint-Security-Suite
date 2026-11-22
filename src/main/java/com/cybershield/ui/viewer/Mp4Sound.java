package com.cybershield.ui.viewer;

import net.sourceforge.jaad.aac.AACException;
import net.sourceforge.jaad.aac.Decoder;
import net.sourceforge.jaad.aac.SampleBuffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads the sound out of an MP4 (or M4A) held in memory, for PCs where JavaFX can't
 * decode AAC. The AAC frames are decoded in Java (JAAD) into a WAV, and the video's
 * sound tracks are switched off in place (their box is renamed "free", which every MP4
 * reader skips), so JavaFX plays the picture and the WAV side by side. Nothing is
 * written to disk.
 */
final class Mp4Sound {

    /** Bigger decoded sound than this (about 45 minutes of CD-quality stereo) isn't decoded. */
    static final long MAX_WAV_BYTES = 512L * 1024 * 1024;

    static final class TooLongException extends IOException {
        TooLongException() {
            super("The sound is too long to prepare here.");
        }
    }

    /** One track of the MP4: where its box is, what it holds, and where its samples are. */
    record Track(int trakPosition, String handler, String codec, byte[] audioConfig, long[] sampleOffsets, int[] sampleSizes) {

        boolean isAac() {
            return "soun".equals(handler) && "mp4a".equals(codec) && audioConfig != null;
        }

    }

    private Mp4Sound() {
    }

    /** The tracks of an MP4, or an empty list if it isn't one this can read. */
    static List<Track> tracks(ByteBuffer data) {

        List<Track> tracks = new ArrayList<>();

        try {

            for (int[] moov : children(data, 0, data.limit(), "moov")) {
                for (int[] trak : children(data, moov[1], moov[2], "trak")) {
                    tracks.add(track(data, trak));
                }
            }

        } catch (RuntimeException damaged) {
            return List.of();
        }

        return tracks;

    }

    static Track firstAac(List<Track> tracks) {

        return tracks.stream().filter(Track::isAac).findFirst().orElse(null);

    }

    static boolean hasVideo(List<Track> tracks) {

        return tracks.stream().anyMatch(track -> "vide".equals(track.handler()));

    }

    /** Switches every sound track off by renaming its box to "free". Sizes and offsets stay put. */
    static void muteInPlace(ByteBuffer data, List<Track> tracks) {

        byte[] free = "free".getBytes(StandardCharsets.US_ASCII);

        for (Track track : tracks) {

            if ("soun".equals(track.handler())) {
                for (int i = 0; i < 4; i++) {
                    data.put(track.trakPosition() + 4 + i, free[i]);
                }
            }

        }

    }

    /** Decodes an AAC track into a WAV (16-bit PCM) in a direct buffer. */
    static ByteBuffer decodeToWav(ByteBuffer data, Track track) throws IOException {

        int frames = track.sampleSizes().length;

        if (frames == 0) {
            throw new IOException("The sound track is empty.");
        }

        Decoder decoder = new Decoder(track.audioConfig());

        SampleBuffer pcm = new SampleBuffer();

        pcm.setBigEndian(false);

        ByteBuffer wav = null;

        int frameBytes = 0;

        byte[] silence = new byte[0];

        for (int i = 0; i < frames; i++) {

            byte[] frame = new byte[track.sampleSizes()[i]];

            data.get((int) track.sampleOffsets()[i], frame);

            byte[] decoded;

            try {

                decoder.decodeFrame(frame, pcm);

                decoded = pcm.getData();

            } catch (AACException | RuntimeException damagedFrame) {

                // Keep the timing: a damaged frame becomes the same length of silence.
                decoded = silence;

            }

            if (wav == null) {

                if (decoded.length == 0) {
                    continue;
                }

                frameBytes = decoded.length;

                silence = new byte[frameBytes];

                long size = 44L + (long) frameBytes * frames;

                if (size > MAX_WAV_BYTES) {
                    throw new TooLongException();
                }

                wav = ByteBuffer.allocateDirect((int) size).order(ByteOrder.LITTLE_ENDIAN);

                wav.position(44);

                // Frames before the first good one were silent.
                for (int skipped = 0; skipped < i; skipped++) {
                    wav.put(silence);
                }

                writeHeader(wav, pcm.getSampleRate(), pcm.getChannels(), pcm.getBitsPerSample());

            }

            wav.put(decoded, 0, Math.min(decoded.length, wav.remaining()));

        }

        if (wav == null) {
            throw new IOException("The sound couldn't be decoded.");
        }

        int dataBytes = wav.position() - 44;

        wav.putInt(4, 36 + dataBytes);
        wav.putInt(40, dataBytes);

        wav.flip();

        return wav;

    }

    private static void writeHeader(ByteBuffer wav, int sampleRate, int channels, int bitsPerSample) {

        int blockAlign = channels * bitsPerSample / 8;

        wav.put(0, "RIFF".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(4, 0);
        wav.put(8, "WAVEfmt ".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(16, 16);
        wav.putShort(20, (short) 1);
        wav.putShort(22, (short) channels);
        wav.putInt(24, sampleRate);
        wav.putInt(28, sampleRate * blockAlign);
        wav.putShort(32, (short) blockAlign);
        wav.putShort(34, (short) bitsPerSample);
        wav.put(36, "data".getBytes(StandardCharsets.US_ASCII));
        wav.putInt(40, 0);

    }

    // ------------------------------------------------------------- boxes

    /** {start of the box, start of its content, end} for each child box of the given type. */
    private static List<int[]> children(ByteBuffer data, int from, int to, String type) {

        List<int[]> found = new ArrayList<>();

        int position = from;

        while (position + 8 <= to) {

            long size = Integer.toUnsignedLong(data.getInt(position));

            String name = type(data, position + 4);

            int header = 8;

            if (size == 1) {
                size = data.getLong(position + 8);
                header = 16;
            } else if (size == 0) {
                size = to - position;
            }

            if (size < header || position + size > to) {
                break;
            }

            if (name.equals(type)) {
                found.add(new int[] {position, position + header, (int) (position + size)});
            }

            position += (int) size;

        }

        return found;

    }

    private static int[] child(ByteBuffer data, int[] parent, String type) {

        List<int[]> found = children(data, parent[1], parent[2], type);

        return found.isEmpty() ? null : found.get(0);

    }

    private static int[] path(ByteBuffer data, int[] box, String... types) {

        int[] current = box;

        for (String type : types) {

            current = child(data, current, type);

            if (current == null) {
                return null;
            }

        }

        return current;

    }

    private static String type(ByteBuffer data, int at) {

        byte[] name = new byte[4];

        data.get(at, name);

        return new String(name, StandardCharsets.ISO_8859_1);

    }

    private static Track track(ByteBuffer data, int[] trak) {

        int[] hdlr = path(data, trak, "mdia", "hdlr");

        String handler = hdlr == null ? "" : type(data, hdlr[1] + 8);

        int[] stbl = path(data, trak, "mdia", "minf", "stbl");

        if (stbl == null) {
            return new Track(trak[0], handler, "", null, new long[0], new int[0]);
        }

        int[] stsd = child(data, stbl, "stsd");

        String codec = "";

        byte[] audioConfig = null;

        if (stsd != null && data.getInt(stsd[1] + 4) > 0) {

            int entry = stsd[1] + 8;

            int entrySize = data.getInt(entry);

            codec = type(data, entry + 4);

            if ("mp4a".equals(codec)) {
                audioConfig = audioConfig(data, entry, entry + entrySize);
            }

        }

        int[] sizes = sampleSizes(data, child(data, stbl, "stsz"));

        long[] chunkOffsets = chunkOffsets(data, stbl);

        long[] offsets = sampleOffsets(data, child(data, stbl, "stsc"), chunkOffsets, sizes);

        return new Track(trak[0], handler, codec, audioConfig, offsets, sizes);

    }

    /** The AudioSpecificConfig inside the esds box of an mp4a sample entry. */
    private static byte[] audioConfig(ByteBuffer data, int entryStart, int entryEnd) {

        // Sound entries come in several versions with different headers, so look for the esds box.
        for (int at = entryStart + 8; at + 8 <= entryEnd; at++) {

            if (!"esds".equals(type(data, at + 4))) {
                continue;
            }

            int end = Math.min(entryEnd, at + data.getInt(at));

            int position = at + 12; // box header and version/flags

            while (position < end) {

                int tag = data.get(position) & 0xFF;

                position++;

                int length = 0;

                for (int i = 0; i < 4; i++) {
                    int b = data.get(position++) & 0xFF;
                    length = (length << 7) | (b & 0x7F);
                    if ((b & 0x80) == 0) {
                        break;
                    }
                }

                if (tag == 0x03) {

                    int flags = data.get(position + 2) & 0xFF;

                    position += 3;

                    if ((flags & 0x80) != 0) {
                        position += 2;
                    }

                    if ((flags & 0x40) != 0) {
                        position += 1 + (data.get(position) & 0xFF);
                    }

                    if ((flags & 0x20) != 0) {
                        position += 2;
                    }

                } else if (tag == 0x04) {

                    position += 13;

                } else if (tag == 0x05) {

                    byte[] config = new byte[length];

                    data.get(position, config);

                    return config;

                } else {

                    position += length;

                }

            }

        }

        return null;

    }

    private static int[] sampleSizes(ByteBuffer data, int[] stsz) {

        if (stsz == null) {
            return new int[0];
        }

        int fixed = data.getInt(stsz[1] + 4);

        int count = data.getInt(stsz[1] + 8);

        int[] sizes = new int[count];

        for (int i = 0; i < count; i++) {
            sizes[i] = fixed != 0 ? fixed : data.getInt(stsz[1] + 12 + i * 4);
        }

        return sizes;

    }

    private static long[] chunkOffsets(ByteBuffer data, int[] stbl) {

        int[] stco = child(data, stbl, "stco");

        if (stco != null) {

            int count = data.getInt(stco[1] + 4);

            long[] offsets = new long[count];

            for (int i = 0; i < count; i++) {
                offsets[i] = Integer.toUnsignedLong(data.getInt(stco[1] + 8 + i * 4));
            }

            return offsets;

        }

        int[] co64 = child(data, stbl, "co64");

        if (co64 == null) {
            return new long[0];
        }

        int count = data.getInt(co64[1] + 4);

        long[] offsets = new long[count];

        for (int i = 0; i < count; i++) {
            offsets[i] = data.getLong(co64[1] + 8 + i * 8);
        }

        return offsets;

    }

    private static long[] sampleOffsets(ByteBuffer data, int[] stsc, long[] chunkOffsets, int[] sizes) {

        long[] offsets = new long[sizes.length];

        if (stsc == null || chunkOffsets.length == 0) {
            return offsets;
        }

        int runs = data.getInt(stsc[1] + 4);

        int sample = 0;

        for (int run = 0; run < runs && sample < sizes.length; run++) {

            int entry = stsc[1] + 8 + run * 12;

            int firstChunk = data.getInt(entry);

            int perChunk = data.getInt(entry + 4);

            int lastChunk = run + 1 < runs ? data.getInt(entry + 12) - 1 : chunkOffsets.length;

            for (int chunk = firstChunk; chunk <= lastChunk && chunk <= chunkOffsets.length; chunk++) {

                long offset = chunkOffsets[chunk - 1];

                for (int i = 0; i < perChunk && sample < sizes.length; i++) {
                    offsets[sample] = offset;
                    offset += sizes[sample];
                    sample++;
                }

            }

        }

        return offsets;

    }

}
