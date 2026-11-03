package br.com.paineltablet;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.LightingColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import java.util.Random;

/** Fundo animado do clima; fica atrás dos cartões e para quando a tela é ocultada. */
final class WeatherSceneView extends View {
    /** Nuvens grandes em camadas: as últimas atravessam também o espaço entre os cards. */
    private static final float[] CLOUD_X = {230, 700, 1170, 110, 560, 1080, 360, 920};
    private static final float[] CLOUD_Y = {112, 64, 128, 292, 335, 290, 515, 520};
    private static final float[] CLOUD_WIDTH = {690, 780, 710, 730, 790, 740, 820, 750};
    private static final float[] CLOUD_ALPHA = {.78f, .74f, .68f, .50f, .55f, .48f, .36f, .32f};
    private static final float[] CLOUD_RATE = {.14f, .11f, .16f, .13f, .10f, .15f, .09f, .12f};

    private static final int[] RAIN_MAX_DROPS = {380, 240, 120};
    private static final int[] RAIN_DRIZZLE_DROPS = {130, 80, 25};
    private static final int[] RAIN_HEAVY_DROPS = {320, 190, 90};
    private static final int[] RAIN_REGULAR_DROPS = {250, 160, 70};
    private static final int[] RAIN_COLOR = {
            0x30bed4e0, 0x50deecf3,
            0x59bbd3e1, 0x83e8f5fa,
            0x78a8c7d8, 0xb6f2fbff
    };
    private static final float[] RAIN_WIDTH = {.7f, 1.1f, 1.6f};

    private static final class RainDrop {
        final float x;
        final float phase;
        final float speed;
        final float length;
        final float slant;
        final boolean bright;

        RainDrop(Random random, int layer) {
            x = random.nextFloat() * 1480;
            phase = random.nextFloat() * 900;
            speed = (layer == 0 ? 205 : layer == 1 ? 330 : 490)
                    * (.78f + random.nextFloat() * .44f);
            length = (layer == 0 ? 9 : layer == 1 ? 17 : 29)
                    * (.65f + random.nextFloat() * .7f);
            slant = .08f + random.nextFloat() * .07f;
            bright = random.nextFloat() < .24f;
        }
    }

    private final WeatherDashboardView dashboard;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cloudPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF cloudBounds = new RectF();
    private final RainDrop[][] rainDrops = new RainDrop[3][];
    private final float[][] rainLines = new float[6][];
    private final int[] rainLineLengths = new int[6];
    private final Bitmap cloudTexture;
    private final Path moonShape = new Path();
    private final Shader sunGlow = new RadialGradient(1050, 105, 305,
            new int[] {0x9affedb0, 0x36ffe4a0, 0x00ffe4a0},
            new float[] {0, .48f, 1}, Shader.TileMode.CLAMP);
    private final Shader sunCore = new RadialGradient(1050, 105, 63,
            new int[] {0xfffffae6, 0xffffde8a, 0x00ffcc72},
            new float[] {0, .57f, 1}, Shader.TileMode.CLAMP);
    private final Shader sunAtmosphere = new RadialGradient(1050, 105, 470,
            new int[] {0x44fff3cf, 0x1affdb9b, 0x00ffdb9b},
            new float[] {0, .52f, 1}, Shader.TileMode.CLAMP);
    private final Shader moonGlow = new RadialGradient(1060, 105, 200,
            new int[] {0x68bdd9ff, 0x00bdd9ff}, null, Shader.TileMode.CLAMP);
    private final Shader moonSurface = new RadialGradient(1044, 83, 74,
            new int[] {0xfff9fbff, 0xffd6e6f3, 0xff9eb7d0},
            new float[] {0, .62f, 1}, Shader.TileMode.CLAMP);
    private final Shader fogVeil = new LinearGradient(0, 300, 0, 800,
            new int[] {0x00dce6eb, 0x24dce6eb, 0x08dce6eb},
            new float[] {0, .58f, 1}, Shader.TileMode.CLAMP);
    private final Shader rainMist = new LinearGradient(0, 140, 0, 800,
            new int[] {0x00334558, 0x183b5769, 0x385a7686},
            new float[] {0, .58f, 1}, Shader.TileMode.CLAMP);
    private final Shader distantLightning = new RadialGradient(975, 215, 420,
            new int[] {0x82e1edff, 0x28c5dcf5, 0x00c5dcf5},
            new float[] {0, .42f, 1}, Shader.TileMode.CLAMP);
    private final Shader headerShade = new LinearGradient(0, 0, 0, 155,
            new int[] {0x64101c2a, 0x00101c2a}, null, Shader.TileMode.CLAMP);
    private int weatherCode;
    private boolean isDay;
    private boolean hasWeather;
    private boolean paused = true;
    private long animationStartMs;
    private long pauseStartedMs;

    WeatherSceneView(Context context, WeatherDashboardView dashboard) {
        super(context);
        this.dashboard = dashboard;
        cloudTexture = WeatherCloudTexture.get(getResources());
        Random rainRandom = new Random(0x5241494e);
        for (int layer = 0; layer < rainDrops.length; layer++) {
            rainDrops[layer] = new RainDrop[RAIN_MAX_DROPS[layer]];
            for (int i = 0; i < rainDrops[layer].length; i++) {
                rainDrops[layer][i] = new RainDrop(rainRandom, layer);
            }
        }
        for (int i = 0; i < rainLines.length; i++) {
            rainLines[i] = new float[RAIN_MAX_DROPS[i / 2] * 4];
        }
        animationStartMs = pauseStartedMs = SystemClock.uptimeMillis();
        Path moonDisk = new Path();
        moonDisk.addCircle(1060, 105, 43, Path.Direction.CW);
        Path moonShadow = new Path();
        moonShadow.addCircle(1079, 88, 43, Path.Direction.CW);
        moonShape.op(moonDisk, moonShadow, Path.Op.DIFFERENCE);
    }

    void setWeather(int code, boolean day) {
        if (!hasWeather || weatherCode != code || isDay != day) {
            animationStartMs = SystemClock.uptimeMillis();
            if (paused) pauseStartedMs = animationStartMs;
        }
        weatherCode = code;
        isDay = day;
        hasWeather = true;
        int tint = code == 3 ? 0xffe6ebef : !day ? 0xffa8bdd6
                : isSnow(code) || isFog(code) ? 0xffe8eef2
                : isRain(code) ? 0xffcbd5de
                : code <= 2 ? 0xffffffff : 0xffe6ebef;
        cloudPaint.setColorFilter(new LightingColorFilter(tint, 0));
        invalidate();
    }

    void setPaused(boolean value) {
        if (paused == value) return;
        long now = SystemClock.uptimeMillis();
        if (value) pauseStartedMs = now;
        else animationStartMs += now - pauseStartedMs;
        paused = value;
        if (!paused) invalidate();
    }

    @Override protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView == this && visibility == VISIBLE && !paused) invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.save();
        canvas.scale(getWidth() / 1280f, getHeight() / 800f);
        dashboard.drawSceneBackground(canvas);
        if (hasWeather) {
            double seconds = ((paused ? pauseStartedMs : SystemClock.uptimeMillis())
                    - animationStartMs) / 1000.0;
            if (isDay && weatherCode <= 2) drawSun(canvas, seconds);
            if (!isDay && weatherCode <= 2) {
                drawStars(canvas, seconds);
                drawMoon(canvas);
            }
            if (weatherCode <= 1) drawClearSkyClouds(canvas, seconds);
            else drawClouds(canvas, seconds);
            if (isFog(weatherCode)) drawFog(canvas, seconds);
            if (weatherCode >= 95) drawDistantLightning(canvas, seconds);
            paint.setStyle(Paint.Style.FILL);
            paint.setAlpha(255);
            paint.setShader(headerShade);
            canvas.drawRect(0, 0, 1280, 155, paint);
            paint.setShader(null);
            if (isRain(weatherCode)) drawRain(canvas, seconds);
            if (isSnow(weatherCode)) drawSnow(canvas, seconds);
        }
        canvas.restore();
        if (hasWeather && !paused && isShown() && getWindowVisibility() == VISIBLE) {
            postInvalidateDelayed(isRain(weatherCode) || isSnow(weatherCode) ? 50 : 100);
        }
    }

    private boolean isRain(int code) {
        return (code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95;
    }

    private boolean isSnow(int code) {
        return (code >= 71 && code <= 77) || code == 85 || code == 86;
    }

    private boolean isFog(int code) {
        return code == 45 || code == 48;
    }

    private void drawSun(Canvas canvas, double seconds) {
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha((int) (242 + Math.sin(seconds * .38) * 12));
        paint.setShader(sunAtmosphere);
        canvas.drawCircle(1050, 105, 470, paint);
        paint.setAlpha(255);
        paint.setShader(sunGlow);
        canvas.drawCircle(1050, 105, 305, paint);
        paint.setShader(null);
        float centerX = 1050;
        float centerY = 105;
        for (int i = 0; i < 18; i++) {
            double angle = i * Math.PI / 9;
            float inner = 76;
            float outer = 137 + (float) Math.sin(seconds * .7 + i * .8) * 9;
            paint.setColor(0x26ffe7a5);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(2f);
            canvas.drawLine(centerX + (float) Math.cos(angle) * inner,
                    centerY + (float) Math.sin(angle) * inner,
                    centerX + (float) Math.cos(angle) * outer,
                    centerY + (float) Math.sin(angle) * outer, paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(sunCore);
        canvas.drawCircle(centerX, centerY, 63, paint);
        paint.setShader(null);
    }

    private void drawMoon(Canvas canvas) {
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        paint.setShader(moonGlow);
        canvas.drawCircle(1060, 105, 200, paint);
        paint.setShader(moonSurface);
        canvas.drawPath(moonShape, paint);
        paint.setShader(null);
        canvas.save();
        canvas.clipPath(moonShape);
        paint.setColor(0x23849eb9);
        canvas.drawCircle(1036, 103, 9, paint);
        canvas.drawCircle(1067, 137, 5, paint);
        canvas.drawCircle(1047, 63, 6, paint);
        canvas.restore();
    }

    private void drawStars(Canvas canvas, double seconds) {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 25; i++) {
            float x = (i * 179 + 53) % 1260 + 10;
            float y = (i * 83 + 19) % 415 + 20;
            float glow = (float) (.5 + .5 * Math.sin(seconds * (i % 3 == 0 ? 1.2 : .7) + i * 1.7));
            paint.setColor(((int) (20 + 94 * glow) << 24) | 0x00e2f1ff);
            canvas.drawCircle(x, y, i % 5 == 0 ? 2.2f : 1.5f, paint);
        }
    }

    /** Nuvens claras e altas dão profundidade ao céu aberto sem encobrir o sol. */
    private void drawClearSkyClouds(Canvas canvas, double seconds) {
        if (cloudTexture == null) return;
        for (int i = 0; i < 3; i++) {
            float width = i == 1 ? 710 : 820;
            float x = wrapCloudX(i == 0 ? 300 : i == 1 ? 1050 : 570,
                    width, (float) seconds * (3.2f + i * 1.1f));
            float y = i == 0 ? 115 : i == 1 ? 360 : 645;
            float height = width * .43f;
            cloudPaint.setAlpha((int) (255 * (isDay ? .15f : .11f) * (i == 2 ? .7f : 1f)));
            cloudBounds.set(x - width / 2, y - height / 2, x + width / 2, y + height / 2);
            canvas.drawBitmap(cloudTexture, null, cloudBounds, cloudPaint);
        }
    }

    /** A textura, os tamanhos e a composição do nublado são os mesmos; só a posição avança. */
    private void drawClouds(Canvas canvas, double seconds) {
        if (cloudTexture == null) return;
        int count = weatherCode == 2 ? 3 : CLOUD_X.length;
        float strength = weatherCode == 2 ? .72f
                : weatherCode == 3 ? 1f : !isDay ? .84f : 1f;
        for (int i = 0; i < count; i++) {
            float width = CLOUD_WIDTH[i];
            float x = CLOUD_X[i] + (float) Math.sin(seconds * CLOUD_RATE[i] + i * 1.4)
                    * (36 + i * 2.5f);
            float y = CLOUD_Y[i] + (float) Math.sin(seconds * .14 + i * 2.1) * 3;
            float height = width * .43f;
            cloudPaint.setAlpha((int) (255 * CLOUD_ALPHA[i] * strength));
            canvas.save();
            canvas.translate(x, y);
            canvas.rotate((i % 2 == 0 ? 1 : -1) * (1.5f + i * .25f));
            if (i % 3 == 1) canvas.scale(-1, 1);
            cloudBounds.set(-width / 2, -height / 2, width / 2, height / 2);
            canvas.drawBitmap(cloudTexture, null, cloudBounds, cloudPaint);
            canvas.restore();
        }
    }

    /** Reaparece só depois de sair completamente da tela, sem salto visível. */
    private float wrapCloudX(float start, float width, float travel) {
        float margin = 40;
        float distance = 1280 + width + margin * 2;
        return (start + width / 2 + margin + travel) % distance - width / 2 - margin;
    }

    private void drawFog(Canvas canvas, double seconds) {
        if (cloudTexture != null) {
            for (int i = 0; i < 3; i++) {
                float width = 1500 + i * 120;
                float x = wrapCloudX(230 + i * 400, width,
                        (float) seconds * (2.6f + i * 1.2f));
                float y = 330 + i * 180;
                cloudPaint.setAlpha(35 + i * 6);
                cloudBounds.set(x - width / 2, y - 210, x + width / 2, y + 210);
                canvas.drawBitmap(cloudTexture, null, cloudBounds, cloudPaint);
            }
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        paint.setShader(fogVeil);
        canvas.drawRect(0, 300, 1280, 800, paint);
        paint.setShader(null);
    }

    private void drawDistantLightning(Canvas canvas, double seconds) {
        double moment = seconds % 14.0;
        double first = Math.max(0, 1 - Math.abs(moment - .20) / .20);
        double second = Math.max(0, 1 - Math.abs(moment - .53) / .12) * .58;
        float flash = (float) Math.max(first, second);
        if (flash <= 0) return;
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha((int) (120 * flash));
        paint.setShader(distantLightning);
        canvas.drawCircle(975, 215, 420, paint);
        paint.setShader(null);
        paint.setAlpha(255);
        Path bolt = new Path();
        bolt.moveTo(1030, 150);
        bolt.lineTo(1007, 207);
        bolt.lineTo(1025, 211);
        bolt.lineTo(971, 298);
        bolt.lineTo(987, 234);
        bolt.lineTo(970, 231);
        bolt.close();
        paint.setColor(((int) (92 * flash) << 24) | 0x00ecf6ff);
        canvas.drawPath(bolt, paint);
    }

    private void drawRain(Canvas canvas, double seconds) {
        boolean drizzle = weatherCode >= 51 && weatherCode <= 57;
        boolean storm = weatherCode >= 95;
        boolean heavy = storm || weatherCode == 65 || weatherCode == 67
                || weatherCode == 82;
        int[] counts = drizzle ? RAIN_DRIZZLE_DROPS
                : storm ? RAIN_MAX_DROPS
                : heavy ? RAIN_HEAVY_DROPS
                : RAIN_REGULAR_DROPS;
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        paint.setShader(rainMist);
        canvas.drawRect(0, 140, 1280, 800, paint);
        paint.setShader(null);
        for (int i = 0; i < rainLineLengths.length; i++) rainLineLengths[i] = 0;
        float force = storm ? 1.3f : drizzle ? .68f : heavy ? 1.13f : 1f;
        float wind = storm ? .18f : .11f;
        float lengthScale = drizzle ? .72f : storm ? 1.12f : 1f;
        for (int layer = 0; layer < rainDrops.length; layer++) {
            for (int i = 0; i < counts[layer]; i++) {
                RainDrop drop = rainDrops[layer][i];
                double travel = seconds * drop.speed * force;
                float y = (float) ((drop.phase + travel) % 900) - 45;
                float x = (float) ((drop.x - travel * wind) % 1480);
                if (x < 0) x += 1480;
                x -= 100;
                float length = drop.length * lengthScale;
                float dx = length * (drop.slant + wind * .38f);
                int group = layer * 2 + (drop.bright ? 1 : 0);
                float[] lines = rainLines[group];
                int offset = rainLineLengths[group];
                lines[offset] = x;
                lines[offset + 1] = y;
                lines[offset + 2] = x - dx;
                lines[offset + 3] = y + length;
                rainLineLengths[group] = offset + 4;
            }
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for (int layer = 0; layer < rainDrops.length; layer++) {
            if (layer == 2 && !drizzle) {
                paint.setStrokeWidth(4.5f);
                paint.setColor(0x1bdaefff);
                canvas.drawLines(rainLines[4], 0, rainLineLengths[4], paint);
                canvas.drawLines(rainLines[5], 0, rainLineLengths[5], paint);
            }
            paint.setStrokeWidth(RAIN_WIDTH[layer]);
            for (int brightness = 0; brightness < 2; brightness++) {
                int color = RAIN_COLOR[layer * 2 + brightness];
                if (drizzle) color = (color & 0x00ffffff)
                        | (((color >>> 24) * 2 / 3) << 24);
                paint.setColor(color);
                int group = layer * 2 + brightness;
                canvas.drawLines(rainLines[group], 0, rainLineLengths[group], paint);
            }
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawSnow(Canvas canvas, double seconds) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 90; i++) {
            float speed = 22 + (i % 5) * 9;
            float x = (i * 227 + 43) % 1320 - 20
                    + (float) Math.sin(seconds * .65 + i * 1.4) * (9 + i % 4 * 4);
            float y = (float) ((i * 149 + seconds * speed) % 880) - 40;
            paint.setColor(i % 4 == 0 ? 0xbbeaf5ff : 0x80f5fbff);
            canvas.drawCircle(x, y, i % 4 == 0 ? 2.8f : 1.6f, paint);
        }
    }
}
