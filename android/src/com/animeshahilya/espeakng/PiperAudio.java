/*
 * Copyright (C) 2026 Animesh Ahilya
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 */

// ARQUIVO COMPLETO. Substitua TODO o conteúdo de
// android/src/com/animeshahilya/espeakng/PiperAudio.java por este texto.
//
// NOVIDADE: limpeza digital da voz (método limpar, chamado no fim de process).
// Corta os agudos acima de 6 kHz em 8 dB e abaixa o chiado nas pausas.
// Vale para todas as vozes naturais. Para desligar, mude limpezaDigital para false.
// Se ainda houver chiado, aumente LIMIAR_GATE (ex.: 0.005f).
// Se cortar o final das palavras, diminua LIMIAR_GATE (ex.: 0.002f).

package com.animeshahilya.espeakng;

import java.util.Arrays;
import java.util.List;

/**
 * Pós-processamento da saída float do Piper para PCM de 16 bits.
 * Java puro, testável fora do dispositivo.
 */
final class PiperAudio {
    private PiperAudio() {
    }

    /** Liga ou desliga a limpeza digital de todas as vozes naturais. */
    static volatile boolean limpezaDigital = true;
    /** Abaixo deste volume o som é tratado como chiado (cerca de -49 dBFS). */
    static final float LIMIAR_GATE = 0.0035f;
    /** Ganho mínimo do gate (cerca de -24 dB). */
    static final float GATE_MIN = 0.06f;

    /** Nível de fala (RMS dos quadros de fala, em dBFS) para o qual toda voz é ajustada. */
    static final float TARGET_SPEECH_DBFS = -20f;
    /** Quadros dentro deste intervalo do mais alto são fala, para o nível. */
    static final float SPEECH_RANGE_DB = 30f;
    /** Quadros tão abaixo da fala são silêncio, para cortar. */
    static final float SILENCE_BELOW_SPEECH_DB = 30f;
    static final int FRAME_MS = 10;
    /** Margem mantida de cada lado da fala cortada. */
    static final int TRIM_MARGIN_MS = 20;
    /** Vozes com chiado: o ruído inicial é cortado com margem maior. */
    static final float QUIET_START_BELOW_SPEECH_DB = 18f;
    static final int QUIET_START_MARGIN_MS = 30;
    /** Rampas nas pontas cortadas, para não dar estalo. */
    static final int EDGE_FADE_MS = 5;
    static final int QUIET_START_FADE_MS = 10;

    private static final double TARGET_SPEECH_LINEAR = Math.pow(10, TARGET_SPEECH_DBFS / 20.0);
    private static final double SPEECH_FLOOR_FACTOR = Math.pow(10, -SPEECH_RANGE_DB / 10.0);
    private static final double SILENCE_FACTOR = Math.pow(10, -SILENCE_BELOW_SPEECH_DB / 10.0);
    private static final double QUIET_START_FACTOR = Math.pow(10, -QUIET_START_BELOW_SPEECH_DB / 10.0);

    /** Rampa de 5 ms, para áudio cortado no meio do sinal começar sem estalo. */
    static void fadeIn(float[] audio, int sampleRate) {
        if (audio == null || audio.length == 0 || sampleRate <= 0) {
            return;
        }
        final int n = Math.min(audio.length, sampleRate / 200);
        for (int i = 0; i < n; i++) {
            audio[i] *= (float) i / n;
        }
    }

    /** Rampa linear nas primeiras (ou últimas) n amostras. */
    private static void fade(short[] pcm, int n, boolean in) {
        n = Math.min(n, pcm.length);
        for (int i = 0; i < n; i++) {
            final int at = in ? i : pcm.length - 1 - i;
            pcm[at] = (short) (pcm[at] * i / n);
        }
    }

    /**
     * Filtro que reduz 8 dB acima de 6 kHz (biquad RBJ), para vozes com chiado.
     * Guarda estado: uma instância por bloco.
     */
    static final class TrebleCut {
        static final double CORNER_HZ = 6000;
        static final double GAIN_DB = -8;
        private final double b0, b1, b2, a1, a2;
        private double x1, x2, y1, y2;

        TrebleCut(int sampleRate) {
            sampleRate = Math.max(8000, sampleRate);
            final double a = Math.pow(10, GAIN_DB / 40);
            final double w = 2 * Math.PI * Math.min(CORNER_HZ, 0.45 * sampleRate) / sampleRate;
            final double cos = Math.cos(w);
            final double s = 2 * Math.sqrt(a) * Math.sin(w) / (2 * Math.sqrt(0.5));
            final double a0 = (a + 1) - (a - 1) * cos + s;
            b0 = a * ((a + 1) + (a - 1) * cos + s) / a0;
            b1 = -2 * a * ((a - 1) + (a + 1) * cos) / a0;
            b2 = a * ((a + 1) + (a - 1) * cos - s) / a0;
            a1 = 2 * ((a - 1) - (a + 1) * cos) / a0;
            a2 = ((a + 1) - (a - 1) * cos - s) / a0;
        }

        /** Devolve a cópia filtrada; a entrada continua como está. */
        float[] apply(float[] in) {
            final float[] out = new float[in.length];
            for (int i = 0; i < in.length; i++) {
                final double y = b0 * in[i] + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
                x2 = x1;
                x1 = in[i];
                y2 = y1;
                y1 = y;
                out[i] = (float) y;
            }
            return out;
        }
    }

    /**
     * Limpeza digital do trecho já em PCM de 16 bits, feita no próprio vetor:
     * corta os agudos com chiado e abaixa o chiado nas pausas e nos finais.
     */
    static void limpar(short[] pcm, int sampleRate) {
        if (pcm == null || pcm.length == 0 || sampleRate <= 0) {
            return;
        }
        final int n = pcm.length;
        float[] x = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = pcm[i] / 32768f;
        }
        x = new TrebleCut(sampleRate).apply(x);

        final double attack = 1.0 - Math.exp(-1.0 / (0.002 * sampleRate));
        final double release = 1.0 - Math.exp(-1.0 / (0.060 * sampleRate));
        final double smooth = 1.0 - Math.exp(-1.0 / (0.005 * sampleRate));
        float env = LIMIAR_GATE;
        float gain = 1f;
        for (int i = 0; i < n; i++) {
            final float a = Math.abs(x[i]);
            env += (float) ((a > env ? attack : release) * (a - env));
            final float target = env >= LIMIAR_GATE
                    ? 1f
                    : Math.max(GATE_MIN, (float) Math.pow(env / LIMIAR_GATE, 1.5));
            gain += (float) (smooth * (target - gain));
            float v = x[i] * gain;
            if (v > 0.98f) v = 0.98f;
            if (v < -0.98f) v = -0.98f;
            pcm[i] = (short) Math.round(v * 32767f);
        }
    }

    /** Acima deste valor o limitador suave reduz as amostras em vez de cortar. */
    static final float LIMITER_KNEE = 0.7f;
    private static final double LIMITER_ROOM = 1.0 - LIMITER_KNEE;
    private static final double INV_LIMITER_ROOM = 1.0 / (1.0 - LIMITER_KNEE);

    /** Pausa mais longa mantida dentro de um bloco em ritmo de leitura normal. */
    static final int MAX_PAUSE_MS = 250;
    /** Nunca mais curto que isto. */
    static final int MIN_PAUSE_CAP_MS = 120;

    /** Resultado de process: PCM mais o que foi cortado do áudio do modelo. */
    static final class Pcm {
        final short[] samples;
        /** Amostras removidas do início. */
        final int trimmedLead;
        /** Amostras da saída do modelo que sobram antes de qualquer ajuste de tempo. */
        final int speechSamples;
        /** Pausas encurtadas: pares (índice da amostra do modelo, amostras removidas). */
        final int[] cuts;

        Pcm(short[] samples, int trimmedLead, int speechSamples) {
            this(samples, trimmedLead, speechSamples, new int[0]);
        }

        Pcm(short[] samples, int trimmedLead, int speechSamples, int[] cuts) {
            this.samples = samples;
            this.trimmedLead = trimmedLead;
            this.speechSamples = speechSamples;
            this.cuts = cuts;
        }
    }

    /** Onde a amostra s do modelo aparece na saída, antes de qualquer ajuste de tempo. */
    static double toOutput(double s, int trimmedLead, int[] cuts) {
        double p = s - trimmedLead;
        if (cuts == null) {
            return p;
        }
        for (int i = 0; i + 1 < cuts.length && s > cuts[i]; i += 2) {
            p -= Math.min(cuts[i + 1], s - cuts[i]);
        }
        return p;
    }

    /** Limite de pausa para o ritmo de leitura (porcentagem) e a velocidade. */
    static int maxPauseMs(int pauseScalePercent, float speed) {
        return Math.max(MIN_PAUSE_CAP_MS,
                Math.round(MAX_PAUSE_MS * Math.max(0, pauseScalePercent) / 100f / Math.max(1f, speed)));
    }

    /**
     * Leva um trecho ao nível alvo, aplica o volume e um limitador suave, e corta o
     * silêncio das pontas de cada bloco.
     *
     * @param volume ganho linear depois da normalização (1 = nível alvo)
     */
    static Pcm process(float[] audio, float volume, int sampleRate, boolean trim) {
        return process(audio, volume, sampleRate, trim, 0);
    }

    /** Como acima, também encurtando pausas dentro do bloco para maxPauseMs (0 = manter). */
    static Pcm process(float[] audio, float volume, int sampleRate, boolean trim, int maxPauseMs) {
        return process(audio, volume, sampleRate, trim, trim, maxPauseMs, 0);
    }

    /**
     * Um pedaço de um bloco renderizado em partes: o silêncio é cortado só nas pontas
     * do próprio pedaço, e um pedaço posterior usa o nível do trecho inteiro.
     *
     * @param level potência de fala para definir o nível, 0 = a deste áudio
     */
    static Pcm process(float[] audio, float volume, int sampleRate, boolean trimLead,
                       boolean trimTail, int maxPauseMs, double level) {
        return process(audio, volume, sampleRate, trimLead, trimTail, maxPauseMs, level, false);
    }

    /** quietStart: corta também o ruído inicial de uma voz com chiado. */
    static Pcm process(float[] audio, float volume, int sampleRate, boolean trimLead,
                       boolean trimTail, int maxPauseMs, double level, boolean quietStart) {
        if (audio == null || audio.length == 0) {
            return new Pcm(new short[0], 0, 0);
        }
        final int frame = frameSize(sampleRate);
        final double[] power = framePowers(audio, frame);
        final int frames = power.length;
        final double speech = level > 0 ? level : speech(power);
        if (speech <= 0) {
            return new Pcm(new short[0], 0, 0);
        }

        int from = 0;
        int to = audio.length;
        int[] cuts = new int[0];
        if (trimLead || trimTail) {
            final double silence = speech * SILENCE_FACTOR;
            int first = 0;
            while (trimLead && first < frames && power[first] < silence) {
                first++;
            }
            int start = first;
            if (trimLead && quietStart) {
                final double voice = speech * QUIET_START_FACTOR;
                while (start < frames && power[start] < voice) {
                    start++;
                }
            }
            int last = frames - 1;
            while (trimTail && last > first && power[last] < silence) {
                last--;
            }
            final int margin = sampleRate * TRIM_MARGIN_MS / 1000;
            from = !trimLead ? 0 : start > first
                    ? Math.max(0, start * frame - sampleRate * QUIET_START_MARGIN_MS / 1000)
                    : Math.max(0, first * frame - margin);
            to = trimTail ? Math.min(audio.length, (last + 1) * frame + margin) : audio.length;
            if (maxPauseMs > 0 && start <= last) {
                cuts = pauseCuts(power, silence, start, last, frame, sampleRate * maxPauseMs / 1000);
            }
        }

        if (to <= from) {
            return new Pcm(new short[0], 0, 0);
        }

        int removed = 0;
        for (int i = 0; i + 1 < cuts.length; i += 2) {
            final int cutStart = Math.max(from, cuts[i]);
            final int cutEnd = Math.min(to, cuts[i] + cuts[i + 1]);
            if (cutEnd > cutStart) {
                removed += (cutEnd - cutStart);
            }
        }
        final int outLen = to - from - removed;
        if (outLen <= 0) {
            return new Pcm(new short[0], 0, 0);
        }
        final double gain = TARGET_SPEECH_LINEAR / Math.sqrt(speech) * volume;
        final short[] out = new short[outLen];
        int o = 0;
        int c = 0;
        for (int i = from; i < to && o < outLen; i++) {
            if (c + 1 < cuts.length && i >= cuts[c]) {
                final int cutEnd = cuts[c] + cuts[c + 1];
                if (i < cutEnd) {
                    i = cutEnd - 1;
                    c += 2;
                    continue;
                }
                c += 2;
            }
            out[o++] = (short) Math.round(limit(audio[i] * gain) * 32767);
        }
        final short[] finalOut = (o == outLen) ? out : Arrays.copyOf(out, o);
        if (limpezaDigital && finalOut.length > 0) {
            limpar(finalOut, sampleRate);
        }
        if (from > 0 && finalOut.length > 0) {
            fade(finalOut, sampleRate * (quietStart ? QUIET_START_FADE_MS : EDGE_FADE_MS) / 1000, true);
        }
        if (to < audio.length && finalOut.length > 0) {
            fade(finalOut, sampleRate * EDGE_FADE_MS / 1000, false);
        }
        return new Pcm(finalOut, from, finalOut.length, cuts);
    }

    /** Potência média dos quadros de fala de 10 ms; 0 para silêncio. */
    static double speechPower(float[] audio, int sampleRate) {
        return audio == null || audio.length == 0 ? 0 : speech(framePowers(audio, frameSize(sampleRate)));
    }

    private static int frameSize(int sampleRate) {
        return Math.max(1, sampleRate * FRAME_MS / 1000);
    }

    private static double[] framePowers(float[] audio, int frame) {
        final double[] power = new double[(audio.length + frame - 1) / frame];
        for (int f = 0; f < power.length; f++) {
            final int end = Math.min(audio.length, (f + 1) * frame);
            double sum = 0;
            for (int i = f * frame; i < end; i++) {
                sum += audio[i] * (double) audio[i];
            }
            power[f] = sum / (end - f * frame);
        }
        return power;
    }

    /** Os quadros dentro de SPEECH_RANGE_DB do mais alto são fala. */
    private static double speech(double[] power) {
        double highest = 0;
        for (double p : power) {
            if (p > highest) highest = p;
        }
        if (highest < 1e-16) {
            return 0;
        }
        final double speechFloor = highest * SPEECH_FLOOR_FACTOR;
        double speech = 0;
        int speaking = 0;
        for (double p : power) {
            if (p >= speechFloor) {
                speech += p;
                speaking++;
            }
        }
        return speech / speaking;
    }

    /** (início, duração) do meio de cada trecho silencioso maior que keep amostras. */
    private static int[] pauseCuts(double[] power, double silence, int first, int last, int frame,
                                   int keep) {
        int[] cuts = new int[8];
        int n = 0;
        int f = first;
        while (f <= last) {
            if (power[f] >= silence) {
                f++;
                continue;
            }
            int end = f;
            while (end <= last && power[end] < silence) {
                end++;
            }
            final int length = (end - f) * frame;
            if (length > keep) {
                if (n + 2 > cuts.length) {
                    cuts = Arrays.copyOf(cuts, cuts.length * 2);
                }
                cuts[n++] = f * frame + keep / 2;
                cuts[n++] = length - keep;
            }
            f = end;
        }
        return Arrays.copyOf(cuts, n);
    }

    /** Limitador suave: linear até o joelho, depois curva até a escala cheia sem passar. */
    static double limit(double s) {
        final double a = Math.abs(s);
        if (a <= LIMITER_KNEE) {
            return s;
        }
        return Math.signum(s) * (LIMITER_KNEE + LIMITER_ROOM * Math.tanh((a - LIMITER_KNEE) * INV_LIMITER_ROOM));
    }

    static byte[] toBytes(short[] samples, int count) {
        final byte[] bytes = new byte[count * 2];
        int b = 0;
        for (int i = 0; i < count; i++) {
            final short s = samples[i];
            bytes[b++] = (byte) (s & 0xff);
            bytes[b++] = (byte) ((s >> 8) & 0xff);
        }
        return bytes;
    }

    /**
     * Pausa depois de um bloco, em amostras. Segue o ritmo de leitura (pauseScale, em
     * porcentagem) e diminui com a velocidade, como as pausas do eSpeak.
     */
    static int pauseSamples(int sampleRate, boolean sentence, int pauseScalePercent, float speed) {
        final float base = sentence ? 0.20f : 0.08f;
        final float scaled = base * Math.max(0, pauseScalePercent) / 100f / Math.max(1f, speed);
        return Math.round(sampleRate * scaled);
    }

    /**
     * Início de cada palavra dentro de um bloco, em quadros, estimado pela posição da
     * primeira letra no texto do bloco.
     *
     * @param words pares [início, fim) de pontos de código, relativos ao bloco
     * @param textLength tamanho do bloco em pontos de código
     * @param frames quadros de áudio produzidos
     */
    static int[] estimateWordFrames(int[] words, int textLength, int frames) {
        if (words == null) {
            return new int[0];
        }
        return estimateWordFrames(words, 0, words.length / 2, textLength, frames);
    }

    /** Início de cada palavra dentro de um pedaço, em quadros. */
    static int[] estimateWordFrames(int[] words, int wordFrom, int wordTo, int textLength, int frames) {
        if (words == null || textLength <= 0 || frames <= 0 || wordFrom >= wordTo) {
            return new int[0];
        }
        final int safeFrom = Math.max(0, wordFrom);
        final int safeTo = Math.min(words.length / 2, wordTo);
        final int count = Math.max(0, safeTo - safeFrom);
        final int[] out = new int[count];
        if (count == 0) {
            return out;
        }
        final int pieceTextStart = safeFrom == 0 ? 0 : words[2 * safeFrom];
        final int pieceTextEnd = safeTo == words.length / 2 ? textLength : words[2 * safeTo];
        final int span = Math.max(1, pieceTextEnd - pieceTextStart);
        for (int w = 0; w < count; w++) {
            final long pos = Math.max(0, words[2 * (safeFrom + w)] - pieceTextStart);
            out[w] = (int) Math.min(frames, pos * frames / span);
        }
        return out;
    }

    /**
     * Início exato de cada palavra a partir das durações do próprio modelo. Devolve
     * null quando não há durações ou quando as palavras fonéticas não casam uma a uma
     * com as palavras do texto.
     */
    static int[] alignedWordFrames(List<Integer> wordStartIds, float[] durations,
                                   int hopLength, int textWords, int trimmedLead,
                                   int speechSamples, int outSamples) {
        return alignedWordFrames(wordStartIds, durations, hopLength, textWords, trimmedLead,
                new int[0], speechSamples, outSamples);
    }

    /** Como acima, para um trecho cujas pausas longas foram encurtadas (Pcm.cuts). */
    static int[] alignedWordFrames(List<Integer> wordStartIds, float[] durations,
                                   int hopLength, int textWords, int trimmedLead, int[] cuts,
                                   int speechSamples, int outSamples) {
        if (durations == null || wordStartIds == null || wordStartIds.size() != textWords
                || speechSamples <= 0) {
            return null;
        }
        final int[] out = new int[textWords];
        final double scale = outSamples / (double) speechSamples;
        double samples = 0;
        int id = 0;
        for (int w = 0; w < textWords; w++) {
            final int target = wordStartIds.get(w);
            if (target > durations.length) {
                return null;
            }
            while (id < target) {
                samples += durations[id++] * hopLength;
            }
            final double inSpeech = Math.max(0, Math.min(speechSamples,
                    toOutput(samples, trimmedLead, cuts)));
            out[w] = (int) Math.min(outSamples, Math.round(inSpeech * scale));
            if (w > 0 && out[w] < out[w - 1]) {
                out[w] = out[w - 1];
            }
        }
        return out;
    }

    /** Pares [início, fim) de pontos de código das palavras separadas por espaço no texto. */
    static int[] findWords(String text) {
        if (text == null || text.isEmpty()) {
            return new int[0];
        }
        final int len = text.length();
        int[] spans = new int[16];
        int n = 0;
        int cp = 0;
        int wordStart = -1;
        for (int i = 0; i < len; ) {
            final char ch = text.charAt(i);
            final int c;
            final int charCount;
            if (ch < 0xD800 || ch > 0xDFFF) {
                c = ch;
                charCount = 1;
            } else {
                c = text.codePointAt(i);
                charCount = Character.charCount(c);
            }
            i += charCount;
            final boolean space = (c <= ' ')
                    ? (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\f')
                    : (Character.isWhitespace(c) || Character.isSpaceChar(c));
            if (!space && wordStart < 0) {
                wordStart = cp;
            } else if (space && wordStart >= 0) {
                if (n + 2 > spans.length) {
                    spans = Arrays.copyOf(spans, spans.length * 2);
                }
                spans[n++] = wordStart;
                spans[n++] = cp;
                wordStart = -1;
            }
            cp++;
        }
        if (wordStart >= 0) {
            if (n + 2 > spans.length) {
                spans = Arrays.copyOf(spans, spans.length + 2);
            }
            spans[n++] = wordStart;
            spans[n++] = cp;
        }
        return Arrays.copyOf(spans, n);
    }
}
