/*
 * Copyright 2023-2026 Fred Feng
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.github.sqljam.face.view;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * @Description: TutorialMusic synthesizes light office background music for the tutorial video: an electric piano
 *               with major seventh chords, a marimba motif, a bass and soft drums at 92 BPM. It is generated, so the
 *               video has no third party music.
 * @Author: Fred Feng
 * @Date: 26/03/2023
 * @Version 1.0.0
 */
final class TutorialMusic {

    private static final int SAMPLE_RATE = 22050;
    private static final double BEAT = 60.0 / 92;
    private static final double BAR = 4 * BEAT;
    /**
     * Fmaj7 Em7 Dm7 Cmaj7, Fmaj7 G6 Am7 Gsus: root of the bass and notes of the chord
     */
    private static final int[][] PROGRESSION = {{41, 57, 60, 64, 65}, {40, 55, 59, 62, 64}, {38, 53, 57, 60, 62},
            {36, 52, 55, 59, 60}, {41, 57, 60, 64, 65}, {43, 55, 59, 62, 64}, {45, 55, 60, 64, 67},
            {43, 55, 60, 62, 67}};
    /**
     * Marimba motifs: beat offset and note
     */
    private static final double[][][] MOTIFS = {{{0, 72}, {0.75, 76}, {1.5, 79}, {2.5, 77}, {3, 76}},
            {{0, 74}, {1, 72}, {1.5, 69}, {2.5, 72}}, {{0, 72}, {0.5, 74}, {1.5, 76}, {3, 79}},
            {{0, 77}, {1, 76}, {2, 74}, {2.5, 72}}};

    private final float[] left;
    private final float[] right;
    private final Random random = new Random(7);

    private TutorialMusic(double seconds) {
        int length = (int) (seconds * SAMPLE_RATE);
        left = new float[length];
        right = new float[length];
    }

    /**
     * Writes the music of the duration as a 16 bit stereo wave file
     */
    static void write(File file, double seconds) throws IOException {
        TutorialMusic music = new TutorialMusic(seconds);
        music.compose(seconds);
        music.save(file);
    }

    private static double frequency(double midi) {
        return 440.0 * Math.pow(2, (midi - 69) / 12.0);
    }

    private void add(double start, double[] samples, double gain, double pan) {
        int offset = (int) (start * SAMPLE_RATE);
        double leftGain = gain * Math.cos((pan + 1) * Math.PI / 4);
        double rightGain = gain * Math.sin((pan + 1) * Math.PI / 4);
        for (int i = 0; i < samples.length && offset + i < left.length; i++) {
            left[offset + i] += (float) (samples[i] * leftGain);
            right[offset + i] += (float) (samples[i] * rightGain);
        }
    }

    private static double[] electricPiano(double midi, double seconds) {
        double f = frequency(midi);
        double[] out = new double[(int) (seconds * SAMPLE_RATE)];
        for (int i = 0; i < out.length; i++) {
            double t = (double) i / SAMPLE_RATE;
            double envelope = Math.exp(-t * 1.6) * Math.min(1, t * 200);
            double tremolo = 1 + 0.08 * Math.sin(2 * Math.PI * 4.5 * t);
            out[i] = (Math.sin(2 * Math.PI * f * t) + 0.25 * Math.sin(4 * Math.PI * f * t) * Math.exp(-t * 4))
                    * envelope * tremolo;
        }
        return out;
    }

    private static double[] marimba(double midi) {
        double f = frequency(midi);
        double[] out = new double[(int) (0.6 * SAMPLE_RATE)];
        for (int i = 0; i < out.length; i++) {
            double t = (double) i / SAMPLE_RATE;
            out[i] = (Math.sin(2 * Math.PI * f * t) + 0.3 * Math.sin(8 * Math.PI * f * t) * Math.exp(-t * 30))
                    * Math.exp(-t * 7) * Math.min(1, t * 400);
        }
        return out;
    }

    private static double[] bass(double midi, double seconds) {
        double f = frequency(midi);
        double[] out = new double[(int) (seconds * SAMPLE_RATE)];
        for (int i = 0; i < out.length; i++) {
            double t = (double) i / SAMPLE_RATE;
            out[i] = (Math.sin(2 * Math.PI * f * t) + 0.2 * Math.sin(4 * Math.PI * f * t)) * Math.exp(-t * 2.2)
                    * Math.min(1, t * 150);
        }
        return out;
    }

    private static double[] kick() {
        double[] out = new double[(int) (0.25 * SAMPLE_RATE)];
        double phase = 0;
        for (int i = 0; i < out.length; i++) {
            double t = (double) i / SAMPLE_RATE;
            phase += 2 * Math.PI * (50 + 70 * Math.exp(-t * 30)) / SAMPLE_RATE;
            out[i] = Math.sin(phase) * Math.exp(-t * 14);
        }
        return out;
    }

    private double[] noise(double seconds, double decay, boolean highPass) {
        double[] out = new double[(int) (seconds * SAMPLE_RATE)];
        double previous = 0;
        for (int i = 0; i < out.length; i++) {
            double x = random.nextDouble() * 2 - 1;
            out[i] = (highPass ? x - previous : x) * Math.exp(-(double) i / SAMPLE_RATE * decay);
            previous = x;
        }
        return out;
    }

    private void compose(double seconds) {
        double[] hat = noise(0.05, 90, true);
        double[] snap = noise(0.12, 35, false);
        double[] kick = kick();
        int bars = (int) (seconds / BAR) + 1;
        for (int bar = 0; bar < bars; bar++) {
            double start = bar * BAR;
            int[] chord = PROGRESSION[bar % PROGRESSION.length];
            for (double hit : new double[]{0, 1.5}) {
                for (int j = 1; j < chord.length; j++) {
                    add(start + hit * BEAT + j * 0.012, electricPiano(chord[j], hit == 0 ? 2.2 : 1.6), 0.06,
                            -0.25 + j * 0.15);
                }
            }
            // Two bars of piano as the intro
            if (bar >= 2) {
                add(start, bass(chord[0], BEAT * 1.8), 0.22, 0);
                add(start + 2.5 * BEAT, bass(chord[0] + 7, BEAT * 1.2), 0.16, 0);
                add(start, kick, 0.30, 0);
                add(start + 2 * BEAT, kick, 0.30, 0);
                add(start + BEAT, snap, 0.05, 0.1);
                add(start + 3 * BEAT, snap, 0.05, 0.1);
                for (int eighth = 0; eighth < 8; eighth++) {
                    add(start + eighth * BEAT / 2 + (eighth % 2 == 1 ? 0.02 : 0), hat, eighth % 2 == 1 ? 0.05 : 0.08,
                            0.35);
                }
            }
            if (bar >= 4 && (bar / 8) % 2 == 0 || bar >= 20) {
                for (double[] note : MOTIFS[bar % MOTIFS.length]) {
                    add(start + note[0] * BEAT, marimba(note[1]), 0.10, 0.2);
                }
            }
        }
    }

    private void save(File file) throws IOException {
        float peak = 1e-6f;
        for (int i = 0; i < left.length; i++) {
            peak = Math.max(peak, Math.max(Math.abs(left[i]), Math.abs(right[i])));
        }
        int dataSize = left.length * 4;
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            out.writeBytes("RIFF");
            out.writeInt(Integer.reverseBytes(36 + dataSize));
            out.writeBytes("WAVEfmt ");
            out.writeInt(Integer.reverseBytes(16));
            out.writeShort(Short.reverseBytes((short) 1));
            out.writeShort(Short.reverseBytes((short) 2));
            out.writeInt(Integer.reverseBytes(SAMPLE_RATE));
            out.writeInt(Integer.reverseBytes(SAMPLE_RATE * 4));
            out.writeShort(Short.reverseBytes((short) 4));
            out.writeShort(Short.reverseBytes((short) 16));
            out.writeBytes("data");
            out.writeInt(Integer.reverseBytes(dataSize));
            for (int i = 0; i < left.length; i++) {
                out.writeShort(Short.reverseBytes((short) (left[i] / peak * 0.8f * Short.MAX_VALUE)));
                out.writeShort(Short.reverseBytes((short) (right[i] / peak * 0.8f * Short.MAX_VALUE)));
            }
        }
    }
}
