package br.com.paineltablet;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Histórico de medições de download, desenhado sem depender de navegador moderno. */
final class InternetDashboardView extends View {
    private static final Locale PT_BR = new Locale("pt", "BR");
    private static final int WHITE = 0xfff4fcfa;
    private static final int MUTED = 0xffb7d0cd;
    private static final int GREEN = 0xff84e5c5;
    /** Faixas do mostrador: ate 10 Mbps, de 10 a 25 e acima de 25. */
    private static final int SLOW = 0xffe0736a;
    private static final int MID = 0xffe3b65f;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final long[] times = new long[24];
    private final double[] speeds = new double[24];
    private int count;
    private boolean testing;
    private boolean wifiConnected;
    private long nextTest;
    private long latencyMs;
    private double uploadMbps;
    private boolean animating;
    private long animationStart;
    private String issue = "";

    InternetDashboardView(Context context) {
        super(context);
    }

    void setHistory(JSONArray history) {
        count = 0;
        for (int i = Math.max(0, history.length() - 24); i < history.length(); i++) {
            JSONObject item = history.optJSONObject(i);
            if (item == null) continue;
            long time = item.optLong("time", 0);
            double mbps = item.optDouble("mbps", 0);
            if (time <= 0 || mbps <= 0) continue;
            times[count] = time;
            speeds[count] = mbps;
            count++;
        }
        issue = "";
        restartSweep();
        invalidate();
    }

    /** Ao aparecer na tela, o mostrador varre do zero ate a ultima medição. */
    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView == this && visibility == VISIBLE && count > 0) restartSweep();
    }

    private void restartSweep() {
        animationStart = SystemClock.uptimeMillis();
        animating = true;
    }

    /** Valor apresentado no mostrador; anima a varredura quando checa medição nova. */
    private float displayedSpeed() {
        if (count == 0) return 0;
        float target = (float) speeds[count - 1];
        if (!animating) return target;
        float progress = (SystemClock.uptimeMillis() - animationStart) / 900f;
        if (progress >= 1f) {
            animating = false;
            return target;
        }
        float eased = 1f - (1f - progress) * (1f - progress) * (1f - progress);
        postInvalidateDelayed(16);
        return target * eased;
    }

    void setTesting(boolean value) {
        testing = value;
        if (value) issue = "Medindo…";
        else if (issue.startsWith("Medindo")) issue = "";
        invalidate();
    }

    /** Mensagem de fase durante o teste (latencia, download, upload). */
    void setStage(String value) {
        if (!testing) return;
        issue = value;
        invalidate();
    }

    void setIssue(String value) {
        issue = value;
        invalidate();
    }

    void setNextTest(long value) {
        nextTest = value;
        invalidate();
    }

    /** Latencia e upload da medição mais recente (0 = ainda nao medidos). */
    void setLastResult(long latency, double upload) {
        latencyMs = latency;
        uploadMbps = upload;
        invalidate();
    }

    void setWifiConnected(boolean value) {
        if (wifiConnected != value) {
            wifiConnected = value;
            invalidate();
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.scale(getWidth() / 1280f, getHeight() / 800f);
        background(canvas);
        header(canvas);
        gauge(canvas);
        historyGraph(canvas);
        summary(canvas);
        text(canvas, "Medições de download, upload e latência neste tablet  ·  Servidor: Cloudflare", 44, 775, 14, MUTED, false);
        canvas.restore();
    }

    private void background(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(0, 0, 1280, 800,
                new int[] {0xff101c24, 0xff244640, 0xff111e25}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, 1280, 800, paint);
        paint.setShader(new RadialGradient(1050, 180, 560,
                new int[] {0x3b4cae90, 0x004cae90}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(1050, 180, 560, paint);
        paint.setShader(null);
    }

    private void header(Canvas canvas) {
        text(canvas, "INTERNET", 44, 48, 18, GREEN, true);
        text(canvas, "Velocidade da conexão", 44, 112, 42, WHITE, true);
        String now = new SimpleDateFormat("HH:mm", PT_BR).format(new Date());
        String day = new SimpleDateFormat("EEEE, d 'de' MMMM", PT_BR).format(new Date());
        textRight(canvas, now, 1238, 62, 38, WHITE, true);
        textRight(canvas, day, 1238, 100, 19, MUTED, false);
    }

    private void gauge(Canvas canvas) {
        card(canvas, 34, 151, 621, 469);
        text(canvas, "DOWNLOAD", 64, 196, 17, GREEN, true);
        String status = wifiConnected ? "WI-FI CONECTADO" : "SEM WI-FI";
        styleText(15, MUTED, true);
        paint.setTextAlign(Paint.Align.RIGHT);
        wifiIcon(canvas, 592 - paint.measureText(status) - 26, 191,
                wifiConnected ? GREEN : SLOW);
        textRight(canvas, status, 592, 196, 15, MUTED, true);

        double last = count == 0 ? 0 : speeds[count - 1];
        double scale = Math.max(25, Math.ceil(last * 1.3 / 25) * 25);
        double shown = displayedSpeed();
        rect.set(204, 216, 452, 464);
        drawZones(canvas, scale, -1);
        drawZones(canvas, scale, shown);
        zoneTicks(canvas, scale);
        paint.setStyle(Paint.Style.FILL);
        textCentered(canvas, count > 0 ? formatSpeed(shown) : "--", 328, 345, 78, WHITE, true);
        textCentered(canvas, "Mbps", 328, 389, 25, MUTED, false);
        text(canvas, "0", 178, 428, 14, MUTED, false);
        textRight(canvas, String.format(PT_BR, "%.0f", scale), 478, 428, 14, MUTED, false);
        // Laterais livres do mostrador: latência e upload da ultima medição.
        text(canvas, "LATÊNCIA", 64, 306, 13, MUTED, true);
        text(canvas, latencyMs > 0 ? latencyMs + " ms" : "—", 64, 344, 26, WHITE, true);
        textRight(canvas, "UPLOAD", 592, 306, 13, MUTED, true);
        textRight(canvas, uploadMbps > 0 ? formatSpeed(uploadMbps) + " Mbps" : "—",
                592, 344, 26, WHITE, true);
    }

    /**
     * Trilho e mostrador em tres faixas: ate 10 Mbps (lento), de 10 a 25 (regular) e
     * acima de 25 (bom). value menor que zero desenha apenas o trilho apagado.
     */
    private void drawZones(Canvas canvas, double scale, double value) {
        double[] from = {0, 10, 25};
        double[] to = {10, 25, scale};
        int[] solid = {SLOW, MID, GREEN};
        int[] dim = {0x40e0736a, 0x40e3b65f, 0x445b877b};
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(19);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int zone = 0; zone < 3; zone++) {
            if (to[zone] <= from[zone]) continue;
            double segmentFrom = from[zone];
            double segmentTo = to[zone];
            if (value >= 0) {
                if (value <= segmentFrom) continue;
                segmentTo = Math.min(value, segmentTo);
            }
            float arcStart = (float) (150 + 240 * (segmentFrom / scale));
            float arcSpan = (float) (240 * ((segmentTo - segmentFrom) / scale));
            if (arcSpan <= 0) continue;
            paint.setColor(value >= 0 ? solid[zone] : dim[zone]);
            canvas.drawArc(rect, arcStart, arcSpan, false, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    /** Marcas finas separando as faixas do mostrador. */
    private void zoneTicks(Canvas canvas, double scale) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3);
        paint.setStrokeCap(Paint.Cap.BUTT);
        paint.setColor(0xa61a2a33);
        for (int i = 0; i < 2; i++) {
            double angle = Math.toRadians(150 + 240 * ((i == 0 ? 10 : 25) / scale));
            float cos = (float) Math.cos(angle);
            float sin = (float) Math.sin(angle);
            canvas.drawLine(328 + cos * 110, 340 + sin * 110,
                    328 + cos * 138, 340 + sin * 138, paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }

    /** Ícone de Wi-Fi (sem barras de sinal) desenhado antes do rótulo de status. */
    private void wifiIcon(Canvas canvas, float centerX, float centerY, int color) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(color);
        for (int i = 0; i < 2; i++) {
            float radius = i == 0 ? 17 : 10;
            rect.set(centerX - radius, centerY - radius, centerX + radius, centerY + radius);
            canvas.drawArc(rect, -158, 136, false, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(centerX, centerY, 3, paint);
    }

    private void historyGraph(Canvas canvas) {
        card(canvas, 671, 151, 1246, 469);
        text(canvas, "HISTÓRICO", 700, 194, 18, GREEN, true);
        textRight(canvas, "ÚLTIMAS 24 MEDIÇÕES", 1216, 194, 14, MUTED, false);
        if (count == 0) {
            text(canvas, "A primeira medição aparecerá aqui", 744, 314, 22, MUTED, false);
            return;
        }
        double maximum = 25;
        for (int i = 0; i < count; i++) maximum = Math.max(maximum, speeds[i]);
        maximum = Math.ceil(maximum / 25) * 25;
        final float left = 725, right = 1212, top = 247, bottom = 412;
        for (int row = 0; row < 4; row++) {
            float y = top + row * (bottom - top) / 3;
            line(canvas, left, y, right, y, 0x365c8077, 1);
            textRight(canvas, String.format(PT_BR, "%.0f", maximum * (3 - row) / 3),
                    710, y + 5, 14, MUTED, false);
        }
        Path path = new Path();
        for (int i = 0; i < count; i++) {
            float x = count == 1 ? (left + right) / 2 : left + i * (right - left) / (count - 1);
            float y = (float) (bottom - speeds[i] * (bottom - top) / maximum);
            if (i == 0) path.moveTo(x, y);
            else path.lineTo(x, y);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(GREEN);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < count; i++) {
            float x = count == 1 ? (left + right) / 2 : left + i * (right - left) / (count - 1);
            float y = (float) (bottom - speeds[i] * (bottom - top) / maximum);
            paint.setColor(i == count - 1 ? WHITE : GREEN);
            canvas.drawCircle(x, y, i == count - 1 ? 6 : 3, paint);
        }
        text(canvas, formatDate(times[0]), left, 449, 14, MUTED, false);
        textRight(canvas, formatDate(times[count - 1]), right, 449, 14, MUTED, false);
    }

    private void summary(Canvas canvas) {
        text(canvas, "RESUMO", 44, 518, 19, GREEN, true);
        card(canvas, 34, 540, 409, 738);
        card(canvas, 423, 540, 798, 738);
        card(canvas, 812, 540, 1246, 738);
        text(canvas, "ÚLTIMA MEDIÇÃO", 58, 578, 16, MUTED, true);
        text(canvas, count == 0 ? "Aguardando" : formatSpeed(speeds[count - 1]) + " Mbps",
                58, 640, 38, WHITE, true);
        text(canvas, count == 0 ? "Nenhuma ainda" : formatDate(times[count - 1]),
                58, 684, 18, MUTED, false);

        text(canvas, "MÉDIA RECENTE", 447, 578, 16, MUTED, true);
        double sum = 0;
        for (int i = 0; i < count; i++) sum += speeds[i];
        text(canvas, count == 0 ? "--" : formatSpeed(sum / count) + " Mbps",
                447, 640, 38, WHITE, true);
        text(canvas, count == 1 ? "1 medição guardada" : count + " medições guardadas",
                447, 684, 18, MUTED, false);

        text(canvas, "PRÓXIMO TESTE AUTOMÁTICO", 836, 578, 16, MUTED, true);
        String next = nextTest == 0 ? "Em breve" : formatDate(nextTest);
        text(canvas, next, 836, 620, 24, WHITE, true);
        text(canvas, issue.length() > 0 ? issue : "A cada 3 horas · somente no Wi-Fi",
                836, 653, 15, MUTED, false);
    }

    private String formatSpeed(double value) {
        return value >= 100 ? String.format(PT_BR, "%.0f", value)
                : String.format(PT_BR, "%.1f", value);
    }

    private String formatDate(long value) {
        return new SimpleDateFormat("dd/MM  HH:mm", PT_BR).format(new Date(value));
    }

    private void card(Canvas canvas, float left, float top, float right, float bottom) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xb5233c3c);
        rect.set(left, top, right, bottom);
        canvas.drawRoundRect(rect, 25, 25, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        paint.setColor(0x6075a999);
        canvas.drawRoundRect(rect, 25, 25, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void line(Canvas canvas, float x1, float y1, float x2, float y2, int color, float width) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(width);
        paint.setColor(color);
        canvas.drawLine(x1, y1, x2, y2, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void text(Canvas canvas, String value, float x, float y, float size, int color, boolean bold) {
        styleText(size, color, bold);
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(value, x, y, paint);
    }

    private void textRight(Canvas canvas, String value, float x, float y, float size, int color, boolean bold) {
        styleText(size, color, bold);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(value, x, y, paint);
    }

    private void textCentered(Canvas canvas, String value, float x, float y, float size, int color, boolean bold) {
        styleText(size, color, bold);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(value, x, y, paint);
    }

    private void styleText(float size, int color, boolean bold) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(android.graphics.Typeface.create("sans-serif", bold ? 1 : 0));
        paint.setTextSize(size);
        paint.setColor(color);
    }
}
