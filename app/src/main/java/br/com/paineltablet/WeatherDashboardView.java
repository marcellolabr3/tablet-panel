package br.com.paineltablet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.LightingColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Painel desenhado no Canvas para funcionar no Android 4.4. */
final class WeatherDashboardView extends View {
    private static final Locale PT_BR = new Locale("pt", "BR");
    private static final int WHITE = 0xfff5fbff;
    private int muted = 0xffc5cdd0;
    private int accent = 0xffd0dbdd;
    private int blue = 0xff8db9d5;
    private int backgroundTop = 0xff252d35;
    private int backgroundMiddle = 0xff4a5860;
    private int backgroundBottom = 0xff242d33;
    private int glowOne = 0xffc2cbd0;
    private int glowTwo = 0xff8caebc;
    private int cardFill = 0xf22d3a42;
    private int cardBorder = 0x607c929b;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cloudIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final Bitmap cloudTexture;
    private final LightingColorFilter overcastCloudTint = new LightingColorFilter(0xffdce5ec, 0);
    private final LightingColorFilter rainCloudTint = new LightingColorFilter(0xffb9cfde, 0);
    private final LightingColorFilter nightCloudTint = new LightingColorFilter(0xffcadcf1, 0);
    private float firstGlowX = 130;
    private float firstGlowY = 130;
    private Shader backgroundShader;
    private Shader firstGlowShader;
    private Shader secondGlowShader;

    private String city = "Cidade não configurada";
    private String description = "Configure a cidade";
    private String issue = "";
    private String agenda = "Carregando agenda…";
    private String photoSource = "Sem fotos";
    private String updatedAt = "";
    private int temperature;
    private int weatherCode = 2;
    private boolean isDay = true;
    private boolean hasWeather;
    private final int[] dailyCode = new int[3];
    private final int[] dailyHigh = new int[3];
    private final int[] dailyLow = new int[3];
    private final int[] dailyRain = new int[3];
    private final float[] hourlyTemp = new float[24];
    private final int[] hourlyRain = new int[24];
    private final String[] hourlyTime = new String[24];
    private int hourlyCount;

    // ---- cards ampliáveis: toque no card abre em tela cheia, toque fora fecha ----
    static final int CARD_NONE = 0;
    static final int CARD_NOW = 1;
    static final int CARD_HOURS = 2;
    static final int CARD_DAY = 3;            // +0 HOJE, +1 AMANHÃ, +2 DEPOIS
    static final int CARD_AGENDA = CARD_DAY + 3;
    private static final float EXPANDED_L = 90, EXPANDED_T = 110, EXPANDED_R = 1190, EXPANDED_B = 690;
    private static final float CLOSE_X = 1146, CLOSE_Y = 160;

    private final Runnable refreshAction;
    private final RectF pressRect = new RectF();
    private int expandedCard = CARD_NONE;
    private int expandedDay;
    private int pressedCard = CARD_NONE;
    private boolean refreshPending;
    private final Runnable refreshTimeout = new Runnable() {
        @Override public void run() {
            refreshPending = false;
            invalidate();
        }
    };

    // Os 72 pontos horários completos, para abrir o detalhe de cada dia.
    private final String[] fullStamp = new String[72];
    private final float[] fullTemp = new float[72];
    private final int[] fullRain = new int[72];
    private int fullCount;
    // Recorte do dia em foco, usado pelo gráfico ampliado.
    private final float[] detailTemp = new float[24];
    private final int[] detailRain = new int[24];
    private final String[] detailTime = new String[24];
    private int detailCount;
    private boolean detailIsToday;

    WeatherDashboardView(Context context, Runnable refreshAction) {
        super(context);
        this.refreshAction = refreshAction;
        cloudTexture = WeatherCloudTexture.get(getResources());
        updateBackgroundShaders();
    }

    void setCity(String value) {
        city = value;
        invalidate();
    }

    void setLoading() {
        if (!hasWeather) description = "Atualizando previsão…";
        issue = "";
        invalidate();
    }

    void setIssue(String value) {
        issue = value;
        if (refreshPending) {
            // Atualização pedida pelo toque em AGORA que não chegou.
            refreshPending = false;
            removeCallbacks(refreshTimeout);
            Toast.makeText(getContext(), "Não foi possível atualizar · " + value,
                    Toast.LENGTH_SHORT).show();
        }
        if (!hasWeather) description = value;
        invalidate();
    }

    void setAgenda(String value) {
        agenda = value;
        invalidate();
    }

    void setPhotoSource(String value) {
        photoSource = value;
        invalidate();
    }

    void setWeather(String place, String condition, JSONObject current,
                    JSONObject daily, JSONObject hourly) {
        int previous = temperature;
        boolean hadWeather = hasWeather;
        city = place;
        description = condition;
        temperature = Math.round((float) current.optDouble("temperature_2m", 0));
        weatherCode = current.optInt("weather_code", 2);
        isDay = current.optInt("is_day", 1) == 1;
        applyPalette();
        for (int i = 0; i < 3; i++) {
            dailyCode[i] = readInt(daily, "weather_code", i, 2);
            dailyHigh[i] = readInt(daily, "temperature_2m_max", i, 0);
            dailyLow[i] = readInt(daily, "temperature_2m_min", i, 0);
            dailyRain[i] = readInt(daily, "precipitation_probability_max", i, 0);
        }
        JSONArray times = hourly.optJSONArray("time");
        JSONArray temps = hourly.optJSONArray("temperature_2m");
        JSONArray rains = hourly.optJSONArray("precipitation_probability");
        int total = times == null || temps == null ? 0
                : Math.min(times.length(), temps.length());
        // O Open-Meteo devolve da meia-noite de hoje; o grafico comeca na hora atual.
        int start = startHour(current);
        hourlyCount = Math.max(0, Math.min(24, total - start));
        for (int i = 0; i < hourlyCount; i++) {
            int source = start + i;
            hourlyTemp[i] = (float) temps.optDouble(source, Float.NaN);
            hourlyRain[i] = rains == null ? 0
                    : Math.max(0, Math.min(100, rains.optInt(source, 0)));
            String time = times.optString(source, "");
            hourlyTime[i] = time.length() >= 16 ? time.substring(11, 16) : "";
        }
        // Guarda os 72 pontos completos: é deles que sai o detalhe de cada dia.
        fullCount = 0;
        if (times != null && temps != null) {
            for (int i = 0; i < times.length() && fullCount < fullStamp.length; i++) {
                fullStamp[fullCount] = times.optString(i, "");
                fullTemp[fullCount] = (float) temps.optDouble(i, Float.NaN);
                fullRain[fullCount] = rains == null ? 0
                        : Math.max(0, Math.min(100, rains.optInt(i, 0)));
                fullCount++;
            }
        }
        updatedAt = new SimpleDateFormat("HH:mm", PT_BR).format(new Date());
        hasWeather = true;
        issue = "";
        if (refreshPending) announceRefresh(previous, hadWeather);
        invalidate();
    }

    /** Aviso rápido do toque em AGORA: diz se a temperatura mudou ou não. */
    private void announceRefresh(int before, boolean hadWeather) {
        refreshPending = false;
        removeCallbacks(refreshTimeout);
        String message = !hadWeather ? "Clima atualizado · " + temperature + " °C"
                : before == temperature ? "Sem mudança · " + temperature + " °C"
                : before + " °C → " + temperature + " °C";
        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
    }

    private void applyPalette() {
        if (!isDay && weatherCode != 3) {
            backgroundTop = 0xff0b1b32;
            backgroundMiddle = 0xff24476c;
            backgroundBottom = 0xff0b1c31;
            glowOne = 0xffa0c7f4;
            glowTwo = 0xff4e83b7;
            cardFill = 0xf21a3049;
            cardBorder = 0x827fa4c7;
            accent = 0xffc9e0f7;
            muted = 0xffb9cee2;
            blue = 0xff8ccaf2;
        } else if (weatherCode <= 1) {
            backgroundTop = 0xff285b82;
            backgroundMiddle = 0xff74a9c5;
            backgroundBottom = 0xffc6aa80;
            glowOne = 0xffffd58b;
            glowTwo = 0xffcdebf2;
            cardFill = 0xf2244258;
            cardBorder = 0x96b9d7e7;
            accent = 0xffffe5a9;
            muted = 0xffd8e8ed;
            blue = 0xff9fd8f0;
        } else if (weatherCode == 2) {
            backgroundTop = 0xff3f627a;
            backgroundMiddle = 0xff7e9dad;
            backgroundBottom = 0xff566a76;
            glowOne = 0xffffd9a2;
            glowTwo = 0xffb6d9e7;
            cardFill = 0xf22a4354;
            cardBorder = 0x92b9cbd5;
            accent = 0xffffdfa9;
            muted = 0xffd4e0e6;
            blue = 0xffa0d0eb;
        } else if (weatherCode == 3) {
            backgroundTop = 0xff4b5660;
            backgroundMiddle = 0xff7b858c;
            backgroundBottom = 0xff3c4852;
            glowOne = 0xffd5dde2;
            glowTwo = 0xffaebbc3;
            cardFill = 0xf236424e;
            cardBorder = 0x91b2c0ca;
            accent = 0xffe2e9eb;
            muted = 0xffd0dce1;
            blue = 0xffacd6eb;
        } else if (weatherCode == 45 || weatherCode == 48) {
            backgroundTop = 0xff68757b;
            backgroundMiddle = 0xffa8b6ba;
            backgroundBottom = 0xff63757e;
            glowOne = 0xfff3f7f5;
            glowTwo = 0xffd5e3e8;
            cardFill = 0xf2394d57;
            cardBorder = 0x9bc4d4da;
            accent = 0xfff0f5f3;
            muted = 0xffe0e9e9;
            blue = 0xffc6e9f5;
        } else if ((weatherCode >= 71 && weatherCode <= 77)
                || weatherCode == 85 || weatherCode == 86) {
            backgroundTop = 0xff66829a;
            backgroundMiddle = 0xffadc3ce;
            backgroundBottom = 0xff778e9e;
            glowOne = 0xfff6fbff;
            glowTwo = 0xffdcecf3;
            cardFill = 0xf2385264;
            cardBorder = 0x9ec5dbe7;
            accent = 0xfff4faff;
            muted = 0xffe0eef3;
            blue = 0xffbce8fb;
        } else if (weatherCode >= 95) {
            backgroundTop = 0xff222e3d;
            backgroundMiddle = 0xff4a5662;
            backgroundBottom = 0xff1b2a3a;
            glowOne = 0xffb8c7d5;
            glowTwo = 0xff819aaf;
            cardFill = 0xf223303e;
            cardBorder = 0x8796aabb;
            accent = 0xffffdfa8;
            muted = 0xffc9d5df;
            blue = 0xff95ceec;
        } else {
            backgroundTop = 0xff2c4051;
            backgroundMiddle = 0xff5e707b;
            backgroundBottom = 0xff253747;
            glowOne = 0xffc4d1db;
            glowTwo = 0xff8baec7;
            cardFill = 0xf22a3b4a;
            cardBorder = 0x8ea3b8c7;
            accent = 0xffdce8ec;
            muted = 0xffc9d7df;
            blue = 0xff9bcdeb;
        }
        updateBackgroundShaders();
    }

    private void updateBackgroundShaders() {
        firstGlowX = weatherCode <= 2 ? 1050 : 130;
        firstGlowY = weatherCode <= 2 ? 105 : 130;
        backgroundShader = new LinearGradient(0, 0, 1280, 800,
                new int[] {backgroundTop, backgroundMiddle, backgroundBottom}, null, Shader.TileMode.CLAMP);
        firstGlowShader = new RadialGradient(firstGlowX, firstGlowY, 470,
                new int[] {withAlpha(glowOne, 0x48), withAlpha(glowOne, 0)}, null, Shader.TileMode.CLAMP);
        secondGlowShader = new RadialGradient(1100, 640, 420,
                new int[] {withAlpha(glowTwo, 0x38), withAlpha(glowTwo, 0)}, null, Shader.TileMode.CLAMP);
    }

    private int withAlpha(int color, int alpha) {
        return (alpha << 24) | (color & 0x00ffffff);
    }

    /** Hora local do momento atual (hora ISO do Open-Meteo), para comecar o grafico "agora". */
    private int startHour(JSONObject current) {
        String moment = current.optString("time", "");
        if (moment.length() < 13) return 0;
        try {
            return Math.max(0, Math.min(23, Integer.parseInt(moment.substring(11, 13))));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private int readInt(JSONObject object, String key, int index, int fallback) {
        JSONArray values = object.optJSONArray(key);
        return values == null ? fallback : (int) Math.round(values.optDouble(index, fallback));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.scale(getWidth() / 1280f, getHeight() / 800f);
        header(canvas);
        hero(canvas);
        chart(canvas);
        dailyCards(canvas);
        agendaCard(canvas);
        text(canvas, photoSource + "  ·  Dados: Open-Meteo", 44, 775, 14, muted, false);
        pressFeedback(canvas);
        if (expandedCard != CARD_NONE) expanded(canvas);
        canvas.restore();
    }

    void drawSceneBackground(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(backgroundShader);
        canvas.drawRect(0, 0, 1280, 800, paint);
        paint.setShader(firstGlowShader);
        canvas.drawCircle(firstGlowX, firstGlowY, 470, paint);
        paint.setShader(secondGlowShader);
        canvas.drawCircle(1100, 640, 420, paint);
        paint.setShader(null);
        if (!isDay && hasWeather && weatherCode <= 2) {
            for (int i = 0; i < 25; i++) {
                paint.setColor(i % 4 == 0 ? 0x72d9ecff : 0x3cc7dfff);
                float x = (i * 179 + 53) % 1260 + 10;
                float y = (i * 83 + 19) % 415 + 20;
                canvas.drawCircle(x, y, i % 5 == 0 ? 1.8f : 1.1f, paint);
            }
        }
    }

    private void header(Canvas canvas) {
        text(canvas, "PREVISÃO DO TEMPO", 44, 48, 18, accent, true);
        text(canvas, city, 44, 113, 45, WHITE, true);
        String time = new SimpleDateFormat("HH:mm", PT_BR).format(new Date());
        String day = new SimpleDateFormat("EEEE, d 'de' MMMM", PT_BR).format(new Date());
        textRight(canvas, time, 1238, 62, 38, WHITE, true);
        textRight(canvas, day, 1238, 100, 19, muted, false);
    }

    private void hero(Canvas canvas) {
        card(canvas, 34, 151, 621, 469);
        paint.setColor(Color.WHITE);
        paint.setShader(new RadialGradient(181, 305, 139,
                new int[] {withAlpha(glowOne, 0x45), withAlpha(glowOne, 0)}, null, Shader.TileMode.CLAMP));
        canvas.drawCircle(181, 305, 139, paint);
        paint.setShader(null);
        weatherIcon(canvas, weatherCode, isDay, 180, 295, 130);
        text(canvas, hasWeather ? temperature + "°" : "--°", 319, 302, 96, WHITE, true);
        text(canvas, description, 322, 350, 27, WHITE, false);
        String detail;
        if (issue.length() > 0) {
            // Sem conexao: mantem os dados antigos e diz de quando eles sao.
            detail = hasWeather ? issue + "  ·  últimos dados de " + updatedAt : issue;
        } else if (hasWeather) {
            detail = "Condição atual  ·  atualizado às " + updatedAt;
        } else {
            detail = "Aguardando dados do tempo";
        }
        textFitted(canvas, detail, 322, 394, 278, 16, muted, false);
        text(canvas, "AGORA", 66, 198, 17, accent, true);
    }

    private void chart(Canvas canvas) {
        card(canvas, 671, 151, 1246, 469);
        text(canvas, "PRÓXIMAS 24 HORAS", 700, 194, 18, accent, true);
        textRight(canvas, "TEMPERATURA  /  CHUVA", 1216, 194, 13, muted, false);
        if (hourlyCount < 2) {
            text(canvas, hasWeather ? "Dados horários indisponíveis" : "Atualizando gráfico…",
                    745, 315, 22, muted, false);
            return;
        }
        float minimum = Float.MAX_VALUE;
        float maximum = -Float.MAX_VALUE;
        for (int i = 0; i < hourlyCount; i++) {
            if (!Float.isNaN(hourlyTemp[i])) {
                minimum = Math.min(minimum, hourlyTemp[i]);
                maximum = Math.max(maximum, hourlyTemp[i]);
            }
        }
        if (minimum == Float.MAX_VALUE) return;
        minimum = (float) Math.floor(minimum - 2);
        maximum = (float) Math.ceil(maximum + 2);
        if (maximum - minimum < 5) maximum = minimum + 5;
        final float left = 720, right = 1215, top = 245, bottom = 351;
        for (int j = 0; j < 3; j++) {
            float y = top + j * (bottom - top) / 2;
            line(canvas, left, y, right, y, withAlpha(muted, 0x35), 1);
            textRight(canvas, Math.round(maximum - j * (maximum - minimum) / 2) + "°",
                    710, y + 6, 14, muted, false);
        }
        Path path = new Path();
        boolean started = false;
        for (int i = 0; i < hourlyCount; i++) {
            if (Float.isNaN(hourlyTemp[i])) continue;
            float x = left + i * (right - left) / (hourlyCount - 1);
            float y = bottom - (hourlyTemp[i] - minimum) * (bottom - top) / (maximum - minimum);
            if (!started) { path.moveTo(x, y); started = true; }
            else path.lineTo(x, y);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(4);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(accent);
        canvas.drawPath(path, paint);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < hourlyCount; i += 5) {
            if (Float.isNaN(hourlyTemp[i])) continue;
            float x = left + i * (right - left) / (hourlyCount - 1);
            float y = bottom - (hourlyTemp[i] - minimum) * (bottom - top) / (maximum - minimum);
            paint.setColor(WHITE);
            canvas.drawCircle(x, y, 5, paint);
            textCentered(canvas, Math.round(hourlyTemp[i]) + "°", x, y - 13, 14, WHITE, true);
            textCentered(canvas, hourlyTime[i], x, 453, 14, muted, false);
        }
        text(canvas, "CHUVA %", 701, 382, 13, blue, true);
        for (int i = 0; i < hourlyCount; i++) {
            if (hourlyRain[i] == 0) continue;
            float x = left + i * (right - left) / (hourlyCount - 1);
            float h = Math.max(3, hourlyRain[i] * 0.34f);
            paint.setColor(blue);
            rect.set(x - 4, 420 - h, x + 4, 420);
            canvas.drawRoundRect(rect, 4, 4, paint);
        }
    }

    private void dailyCards(Canvas canvas) {
        text(canvas, "PRÓXIMOS DIAS", 44, 518, 19, accent, true);
        String[] labels = {"HOJE", "AMANHÃ", "DEPOIS"};
        for (int i = 0; i < 3; i++) {
            int x = 34 + i * 270;
            card(canvas, x, 540, x + 257, 738);
            text(canvas, labels[i], x + 19, 575, 16, muted, true);
            weatherIcon(canvas, dailyCode[i], true, x + 62, 643, 50);
            text(canvas, hasWeather ? dailyHigh[i] + "°" : "--°", x + 121, 648, 42, WHITE, true);
            text(canvas, hasWeather ? "mín " + dailyLow[i] + "°" : "mín --°",
                    x + 124, 676, 16, muted, false);
            text(canvas, hasWeather ? "Chuva " + dailyRain[i] + "%" : "Chuva --%",
                    x + 19, 713, 16, blue, false);
        }
    }

    private void agendaCard(Canvas canvas) {
        text(canvas, "AGENDA", 854, 518, 19, accent, true);
        card(canvas, 844, 540, 1246, 738);
        String[] events = agenda.split("\\n\\n");
        int count = Math.min(events.length, 3);
        if (events.length > count) {
            textRight(canvas, "+" + (events.length - count) + " compromissos", 1236, 518, 15, muted, true);
        }
        for (int i = 0; i < count; i++) {
            String[] lines = events[i].split("\\n", 2);
            float y = 580 + i * 53;
            if (lines.length > 1) {
                text(canvas, lines[0], 864, y, 15, muted, false);
                textFitted(canvas, lines[1], 864, y + 24, 346, 19, WHITE, true);
            } else {
                textFitted(canvas, lines[0], 864, y + 15, 346, 18, WHITE, false);
            }
        }
    }

    private void weatherIcon(Canvas canvas, int code, boolean day, float cx, float cy, float size) {
        boolean fog = code == 45 || code == 48;
        boolean rain = (code >= 51 && code <= 67) || (code >= 80 && code <= 82);
        boolean snow = (code >= 71 && code <= 77) || code == 85 || code == 86;
        boolean thunder = code >= 95;
        boolean clear = code <= 1;
        if (clear || code == 2) {
            if (day) {
                float sunX = cx - size * .19f;
                float sunY = cy - size * .16f;
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.WHITE);
                paint.setShader(new RadialGradient(sunX, sunY, size * .58f,
                        new int[] {0x99fff2c5, 0x42ffe1a1, 0x00ffe1a1},
                        new float[] {0, .52f, 1}, Shader.TileMode.CLAMP));
                canvas.drawCircle(sunX, sunY, size * .58f, paint);
                paint.setShader(null);
                for (int i = 0; i < 12; i++) {
                    double angle = i * Math.PI / 6;
                    float x1 = cx - size * 0.19f + (float) Math.cos(angle) * size * 0.34f;
                    float y1 = cy - size * 0.16f + (float) Math.sin(angle) * size * 0.34f;
                    float x2 = cx - size * 0.19f + (float) Math.cos(angle) * size * 0.47f;
                    float y2 = cy - size * 0.16f + (float) Math.sin(angle) * size * 0.47f;
                    line(canvas, x1, y1, x2, y2, 0x66ffdc93, Math.max(2, size * 0.03f));
                }
                paint.setColor(Color.WHITE);
                paint.setShader(new RadialGradient(sunX - size * .07f,
                        sunY - size * .08f, size * .36f,
                        new int[] {0xfffffff4, 0xffffe9af, 0xfff9ba68},
                        new float[] {0, .58f, 1}, Shader.TileMode.CLAMP));
                canvas.drawCircle(sunX, sunY, size * .27f, paint);
                paint.setShader(null);
            } else {
                Path moonDisk = new Path();
                moonDisk.addCircle(cx - size * 0.18f, cy - size * 0.16f,
                        size * 0.33f, Path.Direction.CW);
                Path moonShadow = new Path();
                moonShadow.addCircle(cx - size * 0.03f, cy - size * 0.29f,
                        size * 0.33f, Path.Direction.CW);
                Path moon = new Path();
                moon.op(moonDisk, moonShadow, Path.Op.DIFFERENCE);
                paint.setColor(Color.WHITE);
                paint.setStyle(Paint.Style.FILL);
                paint.setShader(new RadialGradient(cx - size * .28f, cy - size * .29f,
                        size * .52f, new int[] {0xffffffff, 0xffe4eef9, 0xff9eb7d0},
                        new float[] {0, .62f, 1}, Shader.TileMode.CLAMP));
                canvas.drawPath(moon, paint);
                paint.setShader(null);
            }
            if (clear) return;
        }
        if (cloudTexture != null) {
            cloudIconPaint.setColorFilter(!day ? nightCloudTint : rain || thunder
                    ? rainCloudTint : code == 3 || fog ? overcastCloudTint : null);
            float shift = code == 2 ? size * .15f : 0;
            rect.set(cx - size * .72f + shift, cy - size * .30f,
                    cx + size * .68f + shift, cy + size * .42f);
            canvas.drawBitmap(cloudTexture, null, rect, cloudIconPaint);
        } else {
            // Se a textura não puder ser carregada, mantém um símbolo simples.
            fillCircle(canvas, cx - size * .22f, cy + size * .06f, size * .30f, 0xffb7d7e5);
            fillCircle(canvas, cx + size * .10f, cy - size * .07f, size * .38f, 0xffeef8fb);
            fillCircle(canvas, cx + size * .39f, cy + size * .08f, size * .28f, 0xffd7ebf2);
            paint.setColor(0xffd7ebf2);
            rect.set(cx - size * .49f, cy + size * .08f,
                    cx + size * .66f, cy + size * .37f);
            canvas.drawRoundRect(rect, size * .15f, size * .15f, paint);
        }
        if (fog) {
            for (int i = 0; i < 3; i++)
                line(canvas, cx - size * 0.38f, cy + size * (0.53f + i * 0.13f),
                        cx + size * 0.47f, cy + size * (0.53f + i * 0.13f), muted, size * 0.045f);
        } else if (rain || thunder) {
            for (int i = 0; i < 3; i++) {
                float x = cx - size * 0.28f + i * size * 0.29f;
                line(canvas, x, cy + size * 0.48f, x - size * 0.09f,
                        cy + size * 0.67f, blue, size * 0.055f);
            }
            if (thunder) {
                Path bolt = new Path();
                bolt.moveTo(cx + size * 0.06f, cy + size * 0.33f);
                bolt.lineTo(cx - size * 0.08f, cy + size * 0.67f);
                bolt.lineTo(cx + size * 0.07f, cy + size * 0.63f);
                bolt.lineTo(cx - size * 0.01f, cy + size * 0.95f);
                bolt.lineTo(cx + size * 0.32f, cy + size * 0.52f);
                bolt.lineTo(cx + size * 0.13f, cy + size * 0.57f);
                bolt.close();
                paint.setColor(0xffffd27f);
                canvas.drawPath(bolt, paint);
            }
        } else if (snow) {
            for (int i = 0; i < 3; i++)
                fillCircle(canvas, cx - size * 0.27f + i * size * 0.27f,
                        cy + size * 0.58f, size * 0.055f, WHITE);
        }
    }

    private void card(Canvas canvas, float left, float top, float right, float bottom) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(cardFill);
        rect.set(left, top, right, bottom);
        canvas.drawRoundRect(rect, 25, 25, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(1);
        paint.setColor(cardBorder);
        canvas.drawRoundRect(rect, 25, 25, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void fillCircle(Canvas canvas, float x, float y, float radius, int color) {
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(color);
        canvas.drawCircle(x, y, radius, paint);
    }

    private void line(Canvas canvas, float x1, float y1, float x2, float y2,
                      int color, float width) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(width);
        paint.setColor(color);
        canvas.drawLine(x1, y1, x2, y2, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void text(Canvas canvas, String value, float x, float y, float size,
                      int color, boolean bold) {
        styleText(size, color, bold);
        paint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(value, x, y, paint);
    }

    private void styleText(float size, int color, boolean bold) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setTypeface(bold ? android.graphics.Typeface.create("sans-serif", 1)
                : android.graphics.Typeface.create("sans-serif", 0));
        paint.setTextSize(size);
        paint.setColor(color);
    }

    private void textRight(Canvas canvas, String value, float x, float y, float size,
                           int color, boolean bold) {
        styleText(size, color, bold);
        paint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(value, x, y, paint);
    }

    private void textCentered(Canvas canvas, String value, float x, float y, float size,
                              int color, boolean bold) {
        styleText(size, color, bold);
        paint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(value, x, y, paint);
    }

    private void textFitted(Canvas canvas, String value, float x, float y, float width,
                            float size, int color, boolean bold) {
        styleText(size, color, bold);
        paint.setTextAlign(Paint.Align.LEFT);
        String fit = value;
        while (fit.length() > 1 && paint.measureText(fit) > width)
            fit = fit.substring(0, fit.length() - 2) + "…";
        canvas.drawText(fit, x, y, paint);
    }

    // ------------------------------------------------------------ cards ampliados

    /** Fecha o card ampliado quando a pessoa sai da aba Clima. */
    void collapse() {
        if (expandedCard == CARD_NONE) return;
        expandedCard = CARD_NONE;
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        if (getWidth() == 0 || getHeight() == 0) return false;
        float x = event.getX() * 1280f / getWidth();
        float y = event.getY() * 800f / getHeight();
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                pressedCard = expandedCard == CARD_NONE ? hitTest(x, y) : CARD_NONE;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP: {
                int target = pressedCard;
                pressedCard = CARD_NONE;
                if (expandedCard != CARD_NONE) handleExpandedTap(x, y);
                else if (target != CARD_NONE && hitTest(x, y) == target) openCard(target);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                pressedCard = CARD_NONE;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    private int hitTest(float x, float y) {
        if (inside(x, y, 34, 151, 621, 469)) return CARD_NOW;
        if (inside(x, y, 671, 151, 1246, 469)) return CARD_HOURS;
        for (int i = 0; i < 3; i++) {
            float left = 34 + i * 270;
            if (inside(x, y, left, 540, left + 257, 738)) return CARD_DAY + i;
        }
        if (inside(x, y, 844, 540, 1246, 738)) return CARD_AGENDA;
        return CARD_NONE;
    }

    private boolean inside(float x, float y, float left, float top, float right, float bottom) {
        return x >= left && x <= right && y >= top && y <= bottom;
    }

    private boolean cardBounds(int code, RectF out) {
        if (code == CARD_NOW) { out.set(34, 151, 621, 469); return true; }
        if (code == CARD_HOURS) { out.set(671, 151, 1246, 469); return true; }
        if (code >= CARD_DAY && code < CARD_AGENDA) {
            float left = 34 + (code - CARD_DAY) * 270;
            out.set(left, 540, left + 257, 738);
            return true;
        }
        if (code == CARD_AGENDA) { out.set(844, 540, 1246, 738); return true; }
        return false;
    }

    private void openCard(int code) {
        if (code == CARD_NOW) {
            expandedCard = code;
            requestRefresh();
            return;
        }
        expandedDay = code >= CARD_DAY && code < CARD_AGENDA ? code - CARD_DAY : 0;
        expandedCard = code;
    }

    private void requestRefresh() {
        refreshPending = true;
        removeCallbacks(refreshTimeout);
        postDelayed(refreshTimeout, 60000);
        refreshAction.run();
        invalidate();
    }

    /** Fora do card ou no × fecha; dentro do card AGORA, atualiza de novo. */
    private void handleExpandedTap(float x, float y) {
        float dx = x - CLOSE_X, dy = y - CLOSE_Y;
        boolean close = dx * dx + dy * dy <= 44 * 44;
        if (close || !inside(x, y, EXPANDED_L, EXPANDED_T, EXPANDED_R, EXPANDED_B)) {
            expandedCard = CARD_NONE;
            return;
        }
        if (expandedCard == CARD_NOW) requestRefresh();
    }

    private void pressFeedback(Canvas canvas) {
        if (pressedCard == CARD_NONE || !cardBounds(pressedCard, pressRect)) return;
        paint.setShader(null);
        paint.setColor(0x26ffffff);
        canvas.drawRoundRect(pressRect, 25, 25, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3);
        paint.setColor(0x99ffffff);
        canvas.drawRoundRect(pressRect, 25, 25, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void expanded(Canvas canvas) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xb3070d14);
        canvas.drawRect(0, 0, 1280, 800, paint);
        card(canvas, EXPANDED_L, EXPANDED_T, EXPANDED_R, EXPANDED_B);
        fillCircle(canvas, CLOSE_X, CLOSE_Y, 30, 0x33ffffff);
        textCentered(canvas, "×", CLOSE_X, CLOSE_Y + 15, 44, WHITE, true);
        // Os cards de dia são CARD_DAY+0/1/2 (HOJE/AMANHÃ/DEPOIS) — por isso não dá
        // para usar switch com case CARD_DAY: só o HOJE cairia nele.
        if (expandedCard == CARD_NOW) expandedNow(canvas);
        else if (expandedCard == CARD_HOURS) expandedHours(canvas);
        else if (expandedCard >= CARD_DAY && expandedCard < CARD_AGENDA) expandedDayView(canvas);
        else if (expandedCard == CARD_AGENDA) expandedAgenda(canvas);
        textCentered(canvas, "Toque fora do card para fechar", 640, 674, 17,
                withAlpha(muted, 0xcc), false);
    }

    private void expandedNow(Canvas canvas) {
        text(canvas, "AGORA", 130, 174, 22, accent, true);
        weatherIcon(canvas, weatherCode, isDay, 300, 400, 210);
        text(canvas, hasWeather ? temperature + "°" : "--°", 470, 405, 150, WHITE, true);
        text(canvas, description, 476, 470, 46, WHITE, false);
        String detail;
        if (refreshPending) detail = "Atualizando…";
        else if (issue.length() > 0) detail = issue + "  ·  dados de " + updatedAt;
        else if (hasWeather) detail = "Atualizado às " + updatedAt;
        else detail = "Aguardando dados do tempo";
        textFitted(canvas, detail, 476, 528, 660, 28, muted, false);
        text(canvas, refreshPending ? "Buscando a previsão agora…" : "Toque aqui para atualizar",
                476, 592, 24, accent, false);
        text(canvas, city, 130, 645, 22, muted, false);
    }

    private void expandedHours(Canvas canvas) {
        text(canvas, "PRÓXIMAS 24 HORAS", 130, 174, 28, accent, true);
        textRight(canvas, "TEMPERATURA  /  CHUVA", 1100, 214, 18, muted, false);
        loadDetail(false);
        if (detailCount < 2) {
            text(canvas, hasWeather ? "Dados horários indisponíveis" : "Atualizando gráfico…",
                    480, 400, 30, muted, false);
            return;
        }
        drawDetailChart(canvas, 190, 1130, 275, 460, 545, 630, 2);
        text(canvas, "CHUVA %", 190, 530, 16, blue, true);
    }

    private void expandedDayView(Canvas canvas) {
        int day = expandedDay;
        String[] labels = {"HOJE", "AMANHÃ", "DEPOIS"};
        text(canvas, labels[day] + "  ·  " + dayName(day), 130, 174, 28, accent, true);
        textRight(canvas, "HORA A HORA", 1100, 214, 18, muted, false);
        weatherIcon(canvas, dailyCode[day], true, 205, 330, 96);
        text(canvas, hasWeather ? dailyHigh[day] + "°" : "--°", 290, 340, 66, WHITE, true);
        text(canvas, hasWeather ? "mín " + dailyLow[day] + "°" : "mín --°", 295, 384, 26, muted, false);
        text(canvas, hasWeather ? "Chuva " + dailyRain[day] + "%" : "Chuva --%", 295, 428, 26, blue, false);
        loadDetail(true);
        if (detailCount < 2) {
            text(canvas, "Dados horários indisponíveis", 640, 420, 26, muted, false);
            return;
        }
        drawDetailChart(canvas, 545, 1130, 275, 455, 530, 615, 3);
        text(canvas, "CHUVA %", 545, 512, 16, blue, true);
        if (day == 0) nowMarker(canvas, 545, 1130, 275, 455);
    }

    private void expandedAgenda(Canvas canvas) {
        text(canvas, "AGENDA", 130, 174, 28, accent, true);
        textRight(canvas, "Próximos compromissos", 1100, 214, 18, muted, false);
        String[] events = agenda.split("\\n\\n");
        if (events.length == 1 && events[0].indexOf('\n') < 0) {
            // Mensagem única: sem compromissos ou falha de leitura.
            textFitted(canvas, events[0], 140, 340, 1000, 40, WHITE, false);
            return;
        }
        float y = 250;
        for (int i = 0; i < events.length && i < 6; i++) {
            String[] lines = events[i].split("\n", 2);
            if (lines.length > 1) {
                text(canvas, lines[0], 140, y, 21, muted, false);
                textFitted(canvas, lines[1], 140, y + 44, 1000, 34, WHITE, true);
            } else {
                textFitted(canvas, lines[0], 140, y + 30, 1000, 30, WHITE, false);
            }
            y += 72;
        }
    }

    /** Marca a hora atual no gráfico do dia de hoje. */
    private void nowMarker(Canvas canvas, float left, float right, float top, float bottom) {
        String hour = new SimpleDateFormat("HH", PT_BR).format(new Date());
        for (int i = 0; i < detailCount; i++) {
            if (detailTime[i] == null || !detailTime[i].startsWith(hour)) continue;
            float x = left + i * (right - left) / Math.max(1, detailCount - 1);
            line(canvas, x, top - 12, x, bottom + 6, withAlpha(WHITE, 0x66), 2);
            textCentered(canvas, "agora", x, top - 20, 15, WHITE, true);
            return;
        }
    }

    /** Recorte usado pelo gráfico ampliado: as 24 horas da tela ou o dia escolhido. */
    private void loadDetail(boolean dayView) {
        detailCount = 0;
        if (!dayView) {
            for (int i = 0; i < hourlyCount; i++) {
                detailTemp[i] = hourlyTemp[i];
                detailRain[i] = hourlyRain[i];
                detailTime[i] = hourlyTime[i];
                detailCount++;
            }
            return;
        }
        String prefix = dayKey(expandedDay);
        for (int i = 0; i < fullCount && detailCount < detailTemp.length; i++) {
            String stamp = fullStamp[i];
            if (stamp == null || !stamp.startsWith(prefix)) continue;
            detailTemp[detailCount] = fullTemp[i];
            detailRain[detailCount] = fullRain[i];
            detailTime[detailCount] = stamp.length() >= 16 ? stamp.substring(11, 16) : "";
            detailCount++;
        }
    }

    private String dayKey(int offset) {
        return new SimpleDateFormat("yyyy-MM-dd", PT_BR)
                .format(new Date(System.currentTimeMillis() + offset * 86400000L));
    }

    private String dayName(int offset) {
        return new SimpleDateFormat("EEE, d 'de' MMM", PT_BR)
                .format(new Date(System.currentTimeMillis() + offset * 86400000L));
    }

    /**
     * Gráfico ampliado: grade com os valores, curva com área preenchida, horas no
     * eixo e barras de chuva embaixo. labelStep define de quanto em quanto rotular.
     */
    private void drawDetailChart(Canvas canvas, float left, float right, float top, float bottom,
                                 float rainTop, float rainBottom, int labelStep) {
        float minimum = Float.MAX_VALUE;
        float maximum = -Float.MAX_VALUE;
        for (int i = 0; i < detailCount; i++) {
            if (Float.isNaN(detailTemp[i])) continue;
            minimum = Math.min(minimum, detailTemp[i]);
            maximum = Math.max(maximum, detailTemp[i]);
        }
        if (minimum == Float.MAX_VALUE) return;
        minimum = (float) Math.floor(minimum - 1);
        maximum = (float) Math.ceil(maximum + 1);
        if (maximum - minimum < 4) maximum = minimum + 4;

        for (int j = 0; j < 5; j++) {
            float y = top + j * (bottom - top) / 4;
            line(canvas, left, y, right, y, withAlpha(muted, 0x30), 1);
            textRight(canvas, Math.round(maximum - j * (maximum - minimum) / 4) + "°",
                    left - 14, y + 6, 16, muted, false);
        }

        Path curve = new Path();
        Path area = new Path();
        boolean started = false;
        float lastX = left;
        float step = (right - left) / Math.max(1, detailCount - 1);
        for (int i = 0; i < detailCount; i++) {
            if (Float.isNaN(detailTemp[i])) continue;
            float x = left + i * step;
            float y = bottom - (detailTemp[i] - minimum) * (bottom - top) / (maximum - minimum);
            if (!started) { curve.moveTo(x, y); area.moveTo(x, bottom); started = true; }
            curve.lineTo(x, y);
            area.lineTo(x, y);
            lastX = x;
        }
        if (!started) return;
        area.lineTo(lastX, bottom);
        area.close();
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(accent, 0x33));
        canvas.drawPath(area, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(5);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setColor(accent);
        canvas.drawPath(curve, paint);
        paint.setStyle(Paint.Style.FILL);

        for (int i = 0; i < detailCount; i += labelStep) {
            if (Float.isNaN(detailTemp[i])) continue;
            float x = left + i * step;
            float y = bottom - (detailTemp[i] - minimum) * (bottom - top) / (maximum - minimum);
            fillCircle(canvas, x, y, 5, WHITE);
            textCentered(canvas, Math.round(detailTemp[i]) + "°", x, y - 16, 17, WHITE, true);
            textCentered(canvas, detailTime[i] == null ? "" : detailTime[i], x,
                    bottom + 34, 17, muted, false);
        }

        if (rainBottom > rainTop) {
            paint.setColor(blue);
            float half = Math.max(4, step * 0.3f);
            for (int i = 0; i < detailCount; i++) {
                if (detailRain[i] <= 0) continue;
                float x = left + i * step;
                float h = Math.max(3, detailRain[i] * (rainBottom - rainTop) / 100f);
                rect.set(x - half, rainBottom - h, x + half, rainBottom);
                canvas.drawRoundRect(rect, 4, 4, paint);
            }
        }
    }
}
