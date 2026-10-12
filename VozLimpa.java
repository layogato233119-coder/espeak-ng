// VozLimpa.java
//
// O QUE ESTE ARQUIVO FAZ
// Pega o áudio da voz Miro e tira o chiado, deixando a voz limpa, digital
// e com volume parecido com o do áudio de referência.
//
// COMO USAR (3 passos)
// 1) Coloque este arquivo no mesmo pacote do seu app.
// 2) Quando o motor gerar um pedaço de áudio (um vetor short[] mono de 16 bits),
//    chame antes de entregar o áudio ao Android:
//        VozLimpa.processar(pcm, pcm.length, taxaDeAmostragem);
//    O áudio é alterado no próprio vetor, não precisa de retorno.
// 3) Se o motor for sherpa-onnx, configure também:
//        noiseScale = 0.35f;
//        noiseScaleW = 0.5f;
//    Isso reduz o chiado já na origem.
//
// AJUSTES
// Se ainda aparecer chiado: aumente LIMIAR_GATE (por exemplo, 0.005f).
// Se estiver cortando o final das palavras: diminua LIMIAR_GATE (por exemplo, 0.002f).

public class VozLimpa {

    // Abaixo deste volume, o som é tratado como chiado e abaixado.
    static final float LIMIAR_GATE = 0.0035f;

    // Volume máximo final da voz (parecido com o áudio de referência).
    static final float PICO_ALVO = 0.62f;

    // Tudo acima de 9500 Hz é cortado, que é onde mora o chiado.
    static final double CORTE_ALTO = 9500.0;

    // Tudo abaixo de 70 Hz é cortado, que é ronco sem utilidade.
    static final double CORTE_BAIXO = 70.0;

    // Filtro simples de frequência. Serve para cortar graves ou agudos.
    static class Filtro {
        double b0, b1, b2, a1, a2;
        double x1, x2, y1, y2;

        // passaBaixa = true: corta os agudos. false: corta os graves.
        static Filtro criar(boolean passaBaixa, double frequencia, double taxa) {
            double w0 = 2.0 * Math.PI * frequencia / taxa;
            double c = Math.cos(w0);
            double alfa = Math.sin(w0) / (2.0 * 0.7071);
            double a0 = 1.0 + alfa;

            Filtro f = new Filtro();
            if (passaBaixa) {
                f.b0 = ((1.0 - c) / 2.0) / a0;
                f.b1 = (1.0 - c) / a0;
                f.b2 = f.b0;
            } else {
                f.b0 = ((1.0 + c) / 2.0) / a0;
                f.b1 = -(1.0 + c) / a0;
                f.b2 = f.b0;
            }
            f.a1 = (-2.0 * c) / a0;
            f.a2 = (1.0 - alfa) / a0;
            return f;
        }

        // Passa uma amostra pelo filtro e devolve a amostra filtrada.
        float aplicar(float x) {
            double y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
            x2 = x1;
            x1 = x;
            y2 = y1;
            y1 = y;
            return (float) y;
        }
    }

    // Limpa o áudio no próprio vetor pcm.
    // pcm: áudio mono de 16 bits. n: quantidade de amostras. sr: taxa de amostragem.
    public static void processar(short[] pcm, int n, int sr) {
        if (pcm == null || n <= 0) return;

        // Converte para números entre -1 e 1.
        float[] x = new float[n];
        for (int i = 0; i < n; i++) {
            x[i] = pcm[i] / 32768f;
        }

        // ETAPA 1: filtros. Tira os graves inúteis e o chiado dos agudos.
        double limiteAlto = Math.min(CORTE_ALTO, sr * 0.45);
        Filtro cortaGraves = Filtro.criar(false, CORTE_BAIXO, sr);
        Filtro cortaAgudos1 = Filtro.criar(true, limiteAlto, sr);
        Filtro cortaAgudos2 = Filtro.criar(true, limiteAlto, sr);
        for (int i = 0; i < n; i++) {
            float v = cortaGraves.aplicar(x[i]);
            v = cortaAgudos1.aplicar(v);
            v = cortaAgudos2.aplicar(v);
            x[i] = v;
        }

        // ETAPA 2: gate suave. Abaixa o chiado nas pausas e no fim das frases.
        double velocidadeSubida = 1.0 - Math.exp(-1.0 / (0.002 * sr));
        double velocidadeDescida = 1.0 - Math.exp(-1.0 / (0.060 * sr));
        double suavizacao = 1.0 - Math.exp(-1.0 / (0.005 * sr));
        float nivel = 0f;
        float ganho = 1f;
        for (int i = 0; i < n; i++) {
            float forca = Math.abs(x[i]);
            double vel = forca > nivel ? velocidadeSubida : velocidadeDescida;
            nivel += (float) (vel * (forca - nivel));

            float alvo;
            if (nivel >= LIMIAR_GATE) {
                alvo = 1f;
            } else {
                alvo = Math.max(0.06f, (float) Math.pow(nivel / LIMIAR_GATE, 1.5));
            }
            ganho += (float) (suavizacao * (alvo - ganho));
            x[i] *= ganho;
        }

        // ETAPA 3: entrada e saída suaves de 8 ms. Evita estalo em frases curtas.
        int tamanhoFade = Math.min(n / 2, (int) (0.008 * sr));
        for (int i = 0; i < tamanhoFade; i++) {
            float g = (float) i / tamanhoFade;
            x[i] *= g;
            x[n - 1 - i] *= g;
        }

        // ETAPA 4: volume uniforme. Leva o ponto mais alto para o volume alvo.
        float pico = 0f;
        for (int i = 0; i < n; i++) {
            pico = Math.max(pico, Math.abs(x[i]));
        }
        float multiplicador = pico > 0.0005f ? Math.min(PICO_ALVO / pico, 4f) : 1f;

        // Volta para 16 bits, sem deixar passar do limite.
        for (int i = 0; i < n; i++) {
            float v = x[i] * multiplicador;
            if (v > 0.98f) v = 0.98f;
            if (v < -0.98f) v = -0.98f;
            pcm[i] = (short) (v * 32767f);
        }
    }
}
