package br.com.paineltablet;

import android.Manifest;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.DisplayMetrics;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.CalendarContract;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.CheckBox;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TimeZone;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;

public class MainActivity extends Activity {
    private static final String TAG = "PainelTablet";
    private static final int PICK_PHOTOS = 7;
    private static final int PAGE_PHOTOS = 0;
    private static final int PAGE_WEATHER = 1;
    private static final int PAGE_INTERNET = 2;
    private static final Locale PT_BR = new Locale("pt", "BR");
    private static final long PHOTO_INTERVAL_MS = 18000;
    private static final long PHOTO_SCAN_INTERVAL_MS = 60000;
    /** Varredura completa do cartao no maximo a cada 5 minutos; eventos de midia antecipam. */
    private static final long PHOTO_RESAN_MS = 5 * 60 * 1000;
    /** Foto que falhou no decode volta apos este intervalo, em vez de sumir para sempre. */
    private static final long PHOTO_RETRY_MS = 10 * 60 * 1000;
    /** Modo limpo da tela de fotos: a barra de informacoes some apos este tempo sem toque. */
    private static final long CHROME_HIDE_MS = 5000;
    private static final int MAX_PHOTOS = 5000;
    private static final long WEATHER_INTERVAL_MS = 30 * 60 * 1000;
    private static final long SPEED_INTERVAL_MS = 3L * 60 * 60 * 1000;
    private static final int SPEED_TEST_BYTES = 4_000_000;
    private static final int SPEED_UPLOAD_BYTES = 1_000_000;
    private static final int REQ_PERMISSIONS = 11;

    private final Handler handler = new Handler();
    private final Random random = new Random();
    private final List<File> photos = new ArrayList<File>();
    private final File photoDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Painel");
    private FrameLayout photoLayer;
    private ImageView photoView;
    private ImageView photoBlurView;
    private View photoScrim;
    private View photoShade;
    private TextView clockView;
    private TextView dateView;
    private TextView photoHint;
    private TextView photoWeatherTeaser;
    private TextView photoCaption;
    private WeatherDashboardView weatherView;
    private WeatherSceneView weatherScene;
    private InternetDashboardView internetView;
    private TextView[] pageButtons;
    private LinearLayout navigationBar;
    private TextView settingsButton;
    private TextView speedButton;
    private View sleepOverlay;
    private Bitmap currentBitmap;
    private Bitmap currentBlurBitmap;
    private boolean chromeVisible = true;
    private File lastPhoto;
    private File cardRoot;
    private long lastPhotoScan;
    private boolean photoScanBusy;
    private boolean scanPending;
    private boolean restartPhotoSequence;
    private boolean usingCard;
    private int currentPage = PAGE_PHOTOS;
    private boolean running;
    private boolean weatherBusy;
    private boolean speedBusy;
    private long wakeUntil;
    private SharedPreferences prefs;
    /** Caminho -> hora da ultima falha de decode; a foto volta sozinha apos PHOTO_RETRY_MS. */
    private final Map<String, Long> photosWithFailure = new HashMap<String, Long>();
    private int photoIndex;
    private boolean permissionsAsked;
    private boolean wifiWasConnected;

    /** Cartao inserido/removido ou midia indexada: reage na hora, sem esperar a varredura. */
    private final BroadcastReceiver mediaReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action == null) return;
            if (Intent.ACTION_MEDIA_MOUNTED.equals(action)
                    || Intent.ACTION_MEDIA_SCANNER_FINISHED.equals(action)) {
                refreshPhotos(true);
            } else if (Intent.ACTION_MEDIA_UNMOUNTED.equals(action)
                    || Intent.ACTION_MEDIA_REMOVED.equals(action)) {
                dropPhotoList();
            }
        }
    };

    /** Wi-Fi caiu/voltou: atualiza o painel na hora e avisa o operador. */
    private final BroadcastReceiver networkReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            boolean wifi = isWifiConnected();
            if (wifi == wifiWasConnected) return;
            wifiWasConnected = wifi;
            internetView.setWifiConnected(wifi);
            if (!wifi) {
                if (running && !isSleeping()) {
                    Toast.makeText(context, "Wi-Fi desconectado — testes automaticos pausados",
                            Toast.LENGTH_LONG).show();
                }
            } else checkSpeedSchedule();
        }
    };

    private final Runnable clockTask = new Runnable() {
        @Override public void run() {
            updateClock();
            if (running) handler.postDelayed(this, 30000);
        }
    };
    private final Runnable photoTask = new Runnable() {
        @Override public void run() {
            if (currentPage == PAGE_PHOTOS && !isSleeping()) showNextPhoto();
            if (running) handler.postDelayed(this, PHOTO_INTERVAL_MS);
        }
    };
    private final Runnable photoScanTask = new Runnable() {
        @Override public void run() {
            // Revalida a lista a cada minuto, mas so faz a varredura completa a cada PHOTO_RESAN_MS.
            if (System.currentTimeMillis() - lastPhotoScan >= PHOTO_RESAN_MS) refreshPhotos(false);
            if (running) handler.postDelayed(this, PHOTO_SCAN_INTERVAL_MS);
        }
    };
    private final Runnable calendarTask = new Runnable() {
        @Override public void run() {
            updateCalendar();
            if (running) handler.postDelayed(this, 5 * 60 * 1000);
        }
    };
    private final Runnable weatherTask = new Runnable() {
        @Override public void run() {
            updateWeather();
            if (running) handler.postDelayed(this, WEATHER_INTERVAL_MS);
        }
    };
    private final Runnable speedTask = new Runnable() {
        @Override public void run() {
            checkSpeedSchedule();
            if (running) handler.postDelayed(this, 60000);
        }
    };

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("painel", MODE_PRIVATE);
        String cityExtra = getIntent().getStringExtra("city");
        if (cityExtra != null && cityExtra.trim().length() > 0) {
            prefs.edit().putString("city", cityExtra.trim()).remove("latitude").remove("longitude").apply();
        }
        photoDir.mkdirs();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        hideSystemUi();
        buildScreen();
        loadCachedPhotos();
        ensurePermissions();
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
    }

    @Override protected void onResume() {
        super.onResume();
        running = true;
        lastPhotoScan = 0;
        wifiWasConnected = isWifiConnected();
        IntentFilter mediaFilter = new IntentFilter();
        mediaFilter.addDataScheme("file");
        mediaFilter.addAction(Intent.ACTION_MEDIA_MOUNTED);
        mediaFilter.addAction(Intent.ACTION_MEDIA_UNMOUNTED);
        mediaFilter.addAction(Intent.ACTION_MEDIA_REMOVED);
        mediaFilter.addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED);
        try {
            registerReceiver(mediaReceiver, mediaFilter);
            registerReceiver(networkReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        } catch (Exception ex) {
            Log.w(TAG, "Nao foi possivel observar midia/rede", ex);
        }
        if (!permissionsAsked) ensurePermissions();
        handler.post(clockTask);
        handler.post(photoTask);
        handler.post(photoScanTask);
        handler.post(calendarTask);
        handler.post(weatherTask);
        handler.post(speedTask);
        restoreSpeedHistory();
        updateNightMode();
        hideSystemUi();
    }

    @Override protected void onPause() {
        super.onPause();
        running = false;
        weatherScene.setPaused(true);
        try {
            unregisterReceiver(mediaReceiver);
            unregisterReceiver(networkReceiver);
        } catch (Exception ignored) {
            // Registrados em outro ciclo: nada a desfazer.
        }
        handler.removeCallbacksAndMessages(null);
    }

    @Override public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_BACKGROUND || level == TRIM_MEMORY_UI_HIDDEN) releasePhoto();
    }

    @Override public void onLowMemory() {
        super.onLowMemory();
        releasePhoto();
    }

    /** Libera a bitmap quando o sistema pede memoria; a proxima troca recarrega. */
    private void releasePhoto() {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                photoView.setImageDrawable(null);
                photoBlurView.setImageDrawable(null);
                photoScrim.setVisibility(View.GONE);
                if (currentBitmap != null) {
                    currentBitmap.recycle();
                    currentBitmap = null;
                }
                if (currentBlurBitmap != null) {
                    currentBlurBitmap.recycle();
                    currentBlurBitmap = null;
                }
                lastPhoto = null;
                photoCaption.setVisibility(View.GONE);
            }
        });
    }

    /**
     * Android 6+ exige permissao em tempo de execucao; no Android 4.4 elas sao
     * concedidas na instalacao. A chamada nova fica isolada para nao quebrar o 4.4.
     */
    private void ensurePermissions() {
        if (Build.VERSION.SDK_INT < 23) {
            permissionsAsked = true;
            return;
        }
        if (permissionsAsked) return;
        permissionsAsked = true;
        List<String> missing = new ArrayList<String>();
        if (!RuntimePermissions.granted(this, Manifest.permission.READ_EXTERNAL_STORAGE)) {
            missing.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if (!RuntimePermissions.granted(this, Manifest.permission.READ_CALENDAR)) {
            missing.add(Manifest.permission.READ_CALENDAR);
        }
        if (missing.isEmpty()) return;
        RuntimePermissions.request(this, missing.toArray(new String[missing.size()]), REQ_PERMISSIONS);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQ_PERMISSIONS) return;
        boolean storage = RuntimePermissions.granted(this, Manifest.permission.READ_EXTERNAL_STORAGE);
        boolean calendar = RuntimePermissions.granted(this, Manifest.permission.READ_CALENDAR);
        if (storage) {
            photosWithFailure.clear();
            lastPhotoScan = 0;
            refreshPhotos(true);
        } else {
            Toast.makeText(this, "Sem permissao de leitura, as fotos do cartao nao aparecerao",
                    Toast.LENGTH_LONG).show();
        }
        if (calendar) updateCalendar();
    }

    /** Evita referenciar API 23 dentro da classe carregada pelo Dalvik do Android 4.4. */
    @TargetApi(23)
    private static final class RuntimePermissions {
        static boolean granted(Context context, String permission) {
            return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
        }

        static void request(Activity activity, String[] permissions, int requestCode) {
            activity.requestPermissions(permissions, requestCode);
        }
    }

    @Override protected void onDestroy() {
        super.onDestroy();
        photoView.setImageDrawable(null);
        if (currentBitmap != null) currentBitmap.recycle();
    }

    private void buildScreen() {
        FrameLayout root = new FrameLayout(this);
        GradientDrawable background = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[] {0xff1b394d, 0xff182530, 0xff101823});
        root.setBackground(background);
        setContentView(root);

        photoLayer = new FrameLayout(this);
        root.addView(photoLayer, new FrameLayout.LayoutParams(-1, -1));

        // Fundo da moldura: copia miúda da foto esticada na tela inteira + escurecimento.
        photoBlurView = new ImageView(this);
        photoBlurView.setScaleType(ImageView.ScaleType.FIT_XY);
        photoLayer.addView(photoBlurView, new FrameLayout.LayoutParams(-1, -1));
        photoScrim = new View(this);
        photoScrim.setBackgroundColor(0xb3111820);
        photoScrim.setVisibility(View.GONE);
        photoLayer.addView(photoScrim, new FrameLayout.LayoutParams(-1, -1));

        // A foto vai inteira, sem cortar; as barras laterais caem sobre o fundo desfocado.
        photoView = new ImageView(this);
        photoView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        photoLayer.addView(photoView, new FrameLayout.LayoutParams(-1, -1));

        photoShade = new View(this);
        photoShade.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[] {0xb8000000, 0x57000000, 0x10000000}));
        photoLayer.addView(photoShade, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setPadding(dp(34), dp(24), dp(24), dp(20));
        FrameLayout.LayoutParams leftParams = new FrameLayout.LayoutParams(dp(650), -1, Gravity.LEFT);
        photoLayer.addView(left, leftParams);

        clockView = label(72, Color.WHITE, false);
        clockView.setShadowLayer(5, 1, 2, 0x99000000);
        left.addView(clockView);
        dateView = label(25, 0xffe7edf2, false);
        dateView.setShadowLayer(4, 1, 2, 0x99000000);
        left.addView(dateView);

        photoHint = label(25, Color.WHITE, false);
        photoHint.setGravity(Gravity.CENTER);
        photoHint.setBackgroundColor(0x99000000);
        photoHint.setPadding(dp(22), dp(18), dp(22), dp(18));
        LinearLayout.LayoutParams hintParams = new LinearLayout.LayoutParams(dp(440), -2);
        hintParams.topMargin = dp(150);
        left.addView(photoHint, hintParams);

        photoWeatherTeaser = label(21, Color.WHITE, true);
        photoWeatherTeaser.setText("Toque para ver a previsão");
        photoWeatherTeaser.setPadding(dp(18), dp(11), dp(18), dp(11));
        GradientDrawable teaserBackground = new GradientDrawable();
        teaserBackground.setColor(0xb51b3b50);
        teaserBackground.setCornerRadius(dp(17));
        photoWeatherTeaser.setBackground(teaserBackground);
        FrameLayout.LayoutParams teaserParams = new FrameLayout.LayoutParams(-2, -2,
                Gravity.LEFT | Gravity.BOTTOM);
        teaserParams.leftMargin = dp(34);
        teaserParams.bottomMargin = dp(28);
        photoLayer.addView(photoWeatherTeaser, teaserParams);
        photoWeatherTeaser.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                setPage(PAGE_WEATHER);
            }
        });

        photoCaption = label(15, 0xffe9f2f5, false);
        photoCaption.setGravity(Gravity.RIGHT);
        photoCaption.setPadding(dp(14), dp(9), dp(14), dp(9));
        GradientDrawable captionBackground = new GradientDrawable();
        captionBackground.setColor(0x99000000);
        captionBackground.setCornerRadius(dp(13));
        photoCaption.setBackground(captionBackground);
        photoCaption.setVisibility(View.GONE);
        FrameLayout.LayoutParams captionParams = new FrameLayout.LayoutParams(-2, -2,
                Gravity.RIGHT | Gravity.BOTTOM);
        captionParams.rightMargin = dp(22);
        captionParams.bottomMargin = dp(78);
        photoLayer.addView(photoCaption, captionParams);

        weatherView = new WeatherDashboardView(this, new Runnable() {
            @Override public void run() { updateWeather(); }
        });
        weatherView.setCity(prefs.getString("city", "Cidade não configurada"));
        weatherScene = new WeatherSceneView(this, weatherView);
        root.addView(weatherScene, new FrameLayout.LayoutParams(-1, -1));
        root.addView(weatherView, new FrameLayout.LayoutParams(-1, -1));
        internetView = new InternetDashboardView(this);
        root.addView(internetView, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout navigation = new LinearLayout(this);
        navigation.setOrientation(LinearLayout.HORIZONTAL);
        navigation.setPadding(dp(5), dp(5), dp(5), dp(5));
        GradientDrawable navBackground = new GradientDrawable();
        navBackground.setColor(0xc914202a);
        navBackground.setCornerRadius(dp(18));
        navigation.setBackground(navBackground);
        String[] pages = {"FOTOS", "CLIMA", "INTERNET"};
        pageButtons = new TextView[pages.length];
        for (int i = 0; i < pages.length; i++) {
            final int targetPage = i;
            TextView button = label(16, Color.WHITE, true);
            button.setText(pages[i]);
            button.setGravity(Gravity.CENTER);
            button.setPadding(dp(18), dp(10), dp(18), dp(10));
            button.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) { setPage(targetPage); }
            });
            pageButtons[i] = button;
            navigation.addView(button);
        }
        navigationBar = navigation;
        FrameLayout.LayoutParams navParams = new FrameLayout.LayoutParams(-2, -2,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        navParams.topMargin = dp(14);
        root.addView(navigation, navParams);

        speedButton = label(18, Color.WHITE, true);
        speedButton.setText("Medir agora  ↻");
        speedButton.setGravity(Gravity.CENTER);
        speedButton.setPadding(dp(18), dp(10), dp(18), dp(10));
        GradientDrawable speedBackground = new GradientDrawable();
        speedBackground.setColor(0xe0478f80);
        speedBackground.setCornerRadius(dp(16));
        speedButton.setBackground(speedBackground);
        speedButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { measureSpeed(true); }
        });
        // Pílula alinhada à direita da linha do título RESUMO, sem cobrir nenhum card.
        FrameLayout.LayoutParams speedParams = new FrameLayout.LayoutParams(-2, -2,
                Gravity.RIGHT | Gravity.BOTTOM);
        speedParams.rightMargin = dp(34);
        speedParams.bottomMargin = dp(267);
        root.addView(speedButton, speedParams);

        TextView settings = label(18, Color.WHITE, true);
        settings.setText("Configurar  ⚙");
        settings.setPadding(dp(18), dp(10), dp(18), dp(10));
        GradientDrawable settingsBackground = new GradientDrawable();
        settingsBackground.setColor(0xaa112839);
        settingsBackground.setCornerRadius(dp(15));
        settings.setBackground(settingsBackground);
        settings.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showSettings(); }
        });
        settingsButton = settings;
        FrameLayout.LayoutParams settingsParams = new FrameLayout.LayoutParams(-2, -2,
                Gravity.RIGHT | Gravity.BOTTOM);
        settingsParams.rightMargin = dp(22);
        settingsParams.bottomMargin = dp(18);
        root.addView(settings, settingsParams);

        sleepOverlay = new View(this);
        sleepOverlay.setBackgroundColor(Color.BLACK);
        sleepOverlay.setVisibility(View.GONE);
        sleepOverlay.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                wakeUntil = System.currentTimeMillis() + 2 * 60 * 1000;
                updateNightMode();
            }
        });
        root.addView(sleepOverlay, new FrameLayout.LayoutParams(-1, -1));
        setPage(PAGE_PHOTOS);
    }

    private void setPage(int page) {
        currentPage = page;
        photoLayer.setVisibility(page == PAGE_PHOTOS ? View.VISIBLE : View.GONE);
        weatherScene.setVisibility(page == PAGE_WEATHER ? View.VISIBLE : View.GONE);
        weatherView.setVisibility(page == PAGE_WEATHER ? View.VISIBLE : View.GONE);
        weatherScene.setPaused(page != PAGE_WEATHER || isSleeping() || !running);
        internetView.setVisibility(page == PAGE_INTERNET ? View.VISIBLE : View.GONE);
        speedButton.setVisibility(page == PAGE_INTERNET ? View.VISIBLE : View.GONE);
        for (int i = 0; i < pageButtons.length; i++) {
            GradientDrawable tab = new GradientDrawable();
            tab.setColor(i == page ? 0xff477d81 : Color.TRANSPARENT);
            tab.setCornerRadius(dp(14));
            pageButtons[i].setBackground(tab);
        }
        if (page == PAGE_PHOTOS && photos.isEmpty() && !photoScanBusy) refreshPhotos(true);
        if (page != PAGE_WEATHER) weatherView.collapse();
        handler.removeCallbacks(hideChromeTask);
        if (page == PAGE_PHOTOS) showChromeAndArm();
        else setPhotosChrome(true);
    }

    /**
     * Modo limpo da tela de fotos: relógio, menu, resumo do clima, rodapé e Configurar
     * somem sozinhos; qualquer toque traz tudo de volta e recomeça a contagem.
     */
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN && running
                && currentPage == PAGE_PHOTOS && !isSleeping()) {
            showChromeAndArm();
        }
        return super.dispatchTouchEvent(event);
    }

    private void showChromeAndArm() {
        if (!chromeVisible) setPhotosChrome(true);
        handler.removeCallbacks(hideChromeTask);
        handler.postDelayed(hideChromeTask, CHROME_HIDE_MS);
    }

    private final Runnable hideChromeTask = new Runnable() {
        @Override public void run() {
            if (!running || currentPage != PAGE_PHOTOS || isSleeping()) return;
            if (chromeVisible) setPhotosChrome(false);
        }
    };

    private void setPhotosChrome(boolean visible) {
        chromeVisible = visible;
        animateVisibility(clockView, visible);
        animateVisibility(dateView, visible);
        animateVisibility(navigationBar, visible);
        animateVisibility(settingsButton, visible);
        animateVisibility(photoWeatherTeaser, visible);
        animateVisibility(photoShade, visible);
        // O rodapé com o nome da foto só aparece se houver foto na tela.
        animateVisibility(photoCaption, visible && currentBitmap != null);
    }

    private void animateVisibility(final View view, boolean visible) {
        if (view == null) return;
        view.animate().cancel();
        view.clearAnimation();
        if (visible) {
            view.setVisibility(View.VISIBLE);
            view.setAlpha(0f);
            view.animate().alpha(1f).setDuration(220).start();
        } else {
            view.setAlpha(1f);
            view.animate().alpha(0f).setDuration(220).withEndAction(new Runnable() {
                @Override public void run() {
                    view.setAlpha(1f);
                    view.setVisibility(View.GONE);
                }
            }).start();
        }
    }

    private TextView label(int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setTextColor(color);
        view.setTextSize(sp);
        if (bold) view.setTypeface(null, 1);
        return view;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void updateClock() {
        Date now = new Date();
        clockView.setText(new SimpleDateFormat("HH:mm", PT_BR).format(now));
        String date = new SimpleDateFormat("EEEE, d 'de' MMMM", PT_BR).format(now);
        dateView.setText(Character.toUpperCase(date.charAt(0)) + date.substring(1));
        weatherView.invalidate();
        internetView.setWifiConnected(isWifiConnected());
        internetView.invalidate();
        updateNightMode();
    }

    private boolean isRestTime() {
        if (!prefs.getBoolean("night_enabled", true)) return false;
        Calendar now = Calendar.getInstance();
        int minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        return isRestMinute(minute);
    }

    private boolean isRestMinute(int minute) {
        if (!prefs.getBoolean("night_enabled", true)) return false;
        int start = prefs.getInt("night_start", 23 * 60);
        int end = prefs.getInt("night_end", 7 * 60);
        if (start == end) return false;
        return start < end ? minute >= start && minute < end : minute >= start || minute < end;
    }

    private long nextSpeedTestTime(long lastAttempt) {
        long candidate = lastAttempt == 0 ? System.currentTimeMillis()
                : Math.max(System.currentTimeMillis(), lastAttempt + SPEED_INTERVAL_MS);
        Calendar next = Calendar.getInstance();
        next.setTimeInMillis(candidate);
        int minute = next.get(Calendar.HOUR_OF_DAY) * 60 + next.get(Calendar.MINUTE);
        if (!isRestMinute(minute)) return candidate;
        int start = prefs.getInt("night_start", 23 * 60);
        int end = prefs.getInt("night_end", 7 * 60);
        if (start > end && minute >= start) next.add(Calendar.DAY_OF_YEAR, 1);
        next.set(Calendar.HOUR_OF_DAY, end / 60);
        next.set(Calendar.MINUTE, end % 60);
        next.set(Calendar.SECOND, 0);
        next.set(Calendar.MILLISECOND, 0);
        return next.getTimeInMillis();
    }

    private boolean isSleeping() {
        return isRestTime() && System.currentTimeMillis() >= wakeUntil;
    }

    private void updateNightMode() {
        boolean sleeping = isSleeping();
        sleepOverlay.setVisibility(sleeping ? View.VISIBLE : View.GONE);
        weatherScene.setPaused(sleeping || currentPage != PAGE_WEATHER || !running);
        WindowManager.LayoutParams params = getWindow().getAttributes();
        float wanted = sleeping ? 0.01f : WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        if (params.screenBrightness != wanted) {
            params.screenBrightness = wanted;
            getWindow().setAttributes(params);
        }
    }

    private boolean isWifiConnected() {
        ConnectivityManager manager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo network = manager == null ? null : manager.getActiveNetworkInfo();
        return network != null && network.isConnected() && network.getType() == ConnectivityManager.TYPE_WIFI;
    }

    private void checkSpeedSchedule() {
        if (speedBusy || isRestTime()) return;
        long lastAttempt = prefs.getLong("speed_last_attempt", 0);
        if (lastAttempt == 0 || System.currentTimeMillis() - lastAttempt >= SPEED_INTERVAL_MS) {
            if (isWifiConnected()) measureSpeed(false);
            else internetView.setIssue("Aguardando conexão Wi-Fi");
        }
    }

    private void restoreSpeedHistory() {
        JSONArray history = new JSONArray();
        try {
            history = new JSONArray(prefs.getString("speed_history", "[]"));
        } catch (Exception ex) {
            Log.w(TAG, "Histórico de velocidade ilegível", ex);
        }
        internetView.setHistory(history);
        JSONObject last = history.length() == 0 ? null : history.optJSONObject(history.length() - 1);
        if (last != null) {
            internetView.setLastResult(last.optLong("latency", 0), last.optDouble("upload", 0));
        }
        long lastAttempt = prefs.getLong("speed_last_attempt", 0);
        internetView.setNextTest(nextSpeedTestTime(lastAttempt));
    }

    private void measureSpeed(boolean manual) {
        if (speedBusy) return;
        if (!isWifiConnected()) {
            internetView.setIssue("Conecte o Wi-Fi para medir a velocidade");
            if (manual) Toast.makeText(this, "Conecte o Wi-Fi", Toast.LENGTH_SHORT).show();
            return;
        }
        speedBusy = true;
        final long attempt = System.currentTimeMillis();
        prefs.edit().putLong("speed_last_attempt", attempt).apply();
        internetView.setNextTest(nextSpeedTestTime(attempt));
        internetView.setTesting(true);
        speedButton.setEnabled(false);
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    final SpeedResult result = runSpeedTest();
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            saveSpeedResult(attempt, result);
                            internetView.setTesting(false);
                            speedBusy = false;
                            speedButton.setEnabled(true);
                        }
                    });
                } catch (final Exception ex) {
                    Log.w(TAG, "Falha ao medir velocidade", ex);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            internetView.setIssue("Não foi possível medir agora. Tente novamente.");
                            internetView.setTesting(false);
                            speedBusy = false;
                            speedButton.setEnabled(true);
                        }
                    });
                }
            }
        }, "medir-internet").start();
    }

    /** Latencia, download e upload. Upload que falhar nao invalida a medição. */
    private SpeedResult runSpeedTest() throws Exception {
        postStage("Medindo latência…");
        long latency = measureLatency();
        postStage("Medindo download…");
        double download = downloadSpeed();
        postStage("Medindo upload…");
        double upload = 0;
        try {
            upload = uploadSpeed();
        } catch (Exception ex) {
            Log.w(TAG, "Upload nao medido", ex);
        }
        return new SpeedResult(download, latency, upload);
    }

    private void postStage(final String stage) {
        runOnUiThread(new Runnable() {
            @Override public void run() {
                internetView.setStage(stage);
            }
        });
    }

    /** Duas requisicoes na mesma conexao: a primeira aquece DNS/TLS, a segunda mede a resposta. */
    private long measureLatency() throws Exception {
        String suffix = "?bytes=1&r=" + System.currentTimeMillis();
        try {
            return latencyOver("https://speed.cloudflare.com/__down" + suffix);
        } catch (javax.net.ssl.SSLException ex) {
            Log.w(TAG, "TLS antigo; medindo latencia por HTTP", ex);
            return latencyOver("http://speed.cloudflare.com/__down" + suffix);
        }
    }

    private long latencyOver(String address) throws Exception {
        requestMillis(address);
        return requestMillis(address);
    }

    private long requestMillis(String address) throws Exception {
        long start = SystemClock.elapsedRealtime();
        HttpURLConnection connection = openConnection(address);
        try {
            int code = connection.getResponseCode();
            if (code != 200) throw new Exception("HTTP " + code);
            long elapsed = SystemClock.elapsedRealtime() - start;
            return Math.max(1, elapsed);
        } finally {
            connection.disconnect();
        }
    }

    private double uploadSpeed() throws Exception {
        try {
            return uploadOver("https://speed.cloudflare.com/__up");
        } catch (javax.net.ssl.SSLException ex) {
            Log.w(TAG, "TLS antigo; medindo upload por HTTP", ex);
            return uploadOver("http://speed.cloudflare.com/__up");
        }
    }

    private double uploadOver(String address) throws Exception {
        byte[] payload = new byte[SPEED_UPLOAD_BYTES];
        for (int i = 0; i < payload.length; i += 4) payload[i] = (byte) (i % 251);
        HttpURLConnection connection = openConnection(address);
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(payload.length);
        connection.setReadTimeout(30000);
        long start = SystemClock.elapsedRealtime();
        try {
            OutputStream output = connection.getOutputStream();
            try {
                output.write(payload);
                output.flush();
            } finally {
                output.close();
            }
            int code = connection.getResponseCode();
            long end = SystemClock.elapsedRealtime();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            if (end <= start) throw new Exception("Sem tempo de medicao");
            return payload.length * 8.0 / ((end - start) / 1000.0) / 1000000.0;
        } finally {
            connection.disconnect();
        }
    }

    private static final class SpeedResult {
        final double mbps;
        final long latencyMs;
        final double uploadMbps;

        SpeedResult(double mbps, long latencyMs, double uploadMbps) {
            this.mbps = mbps;
            this.latencyMs = latencyMs;
            this.uploadMbps = uploadMbps;
        }
    }

    private double downloadSpeed() throws Exception {
        String suffix = "?bytes=" + SPEED_TEST_BYTES + "&r=" + System.currentTimeMillis();
        try {
            return downloadSpeedFrom("https://speed.cloudflare.com/__down" + suffix);
        } catch (javax.net.ssl.SSLException ex) {
            Log.w(TAG, "TLS antigo; tentando medição HTTP", ex);
            return downloadSpeedFrom("http://speed.cloudflare.com/__down" + suffix);
        }
    }

    private double downloadSpeedFrom(String address) throws Exception {
        HttpURLConnection connection = openConnection(address);
        connection.setReadTimeout(15000);
        try {
            if (connection.getResponseCode() != 200) throw new Exception("HTTP " + connection.getResponseCode());
            InputStream input = connection.getInputStream();
            byte[] buffer = new byte[16 * 1024];
            long firstByte = 0;
            long total = 0;
            long end = 0;
            try {
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (firstByte == 0) firstByte = SystemClock.elapsedRealtime();
                    total += count;
                    end = SystemClock.elapsedRealtime();
                    if (end - firstByte > 35000) break;
                }
            } finally {
                input.close();
            }
            if (total < 100000 || end <= firstByte) throw new Exception("Download incompleto");
            return total * 8.0 / ((end - firstByte) / 1000.0) / 1000000.0;
        } finally {
            connection.disconnect();
        }
    }

    /** Conexao com TLS 1.2 forcado: o Android 4.4 nao habilita por padrao. */
    private HttpURLConnection openConnection(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Cache-Control", "no-cache, no-store");
        connection.setRequestProperty("Pragma", "no-cache");
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", "PainelTablet/1.5");
        if (connection instanceof HttpsURLConnection) {
            SSLContext tls = SSLContext.getInstance("TLSv1.2");
            tls.init(null, null, null);
            ((HttpsURLConnection) connection).setSSLSocketFactory(tls.getSocketFactory());
        }
        return connection;
    }

    private void saveSpeedResult(long time, SpeedResult result) {
        try {
            JSONArray previous = new JSONArray(prefs.getString("speed_history", "[]"));
            JSONArray history = new JSONArray();
            for (int i = Math.max(0, previous.length() - 23); i < previous.length(); i++) {
                history.put(previous.getJSONObject(i));
            }
            JSONObject entry = new JSONObject();
            entry.put("time", time);
            entry.put("mbps", Math.round(result.mbps * 10.0) / 10.0);
            entry.put("latency", result.latencyMs);
            entry.put("upload", Math.round(result.uploadMbps * 10.0) / 10.0);
            history.put(entry);
            prefs.edit().putString("speed_history", history.toString()).apply();
            internetView.setHistory(history);
            internetView.setLastResult(result.latencyMs, result.uploadMbps);
        } catch (Exception ex) {
            Log.w(TAG, "Não foi possível salvar medição", ex);
            internetView.setIssue("Medição feita, mas o histórico não foi salvo");
        }
    }

    private void showNextPhoto() {
        if (photos.isEmpty()) {
            photoHint.setText(photoScanBusy ? "Procurando fotos no cartão SD…"
                    : cardRoot != null ? "Cartão SD sem fotos JPG, PNG ou WebP"
                    : "Insira um cartão SD com fotos\nou toque em Configurar");
            photoHint.setVisibility(View.VISIBLE);
            photoView.setImageDrawable(null);
            photoBlurView.setImageDrawable(null);
            photoScrim.setVisibility(View.GONE);
            photoCaption.setVisibility(View.GONE);
            if (currentBitmap != null) { currentBitmap.recycle(); currentBitmap = null; }
            if (currentBlurBitmap != null) { currentBlurBitmap.recycle(); currentBlurBitmap = null; }
            lastPhoto = null;
            updateStatus();
            return;
        }
        photoHint.setVisibility(View.GONE);
        File next = pickNextPhoto();
        if (next == null) {
            // Todas as fotos em espera de nova tentativa: avisa sem apagar a atual.
            showCaptionText("Nenhuma foto legível agora");
            return;
        }
        Bitmap decoded = decodePhoto(next);
        if (decoded == null) {
            // Nao some para sempre: fica 10 minutos fora e volta sozinho na proxima tentativa.
            photosWithFailure.put(next.getAbsolutePath(), System.currentTimeMillis());
            photoCaption.setVisibility(View.GONE);
            return;
        }
        // A bitmap anterior so e reciclada depois que a nova esta pronta: evita
        // piscar a tela. O decode usa o tamanho da tela, entao o pico fica pequeno.
        Bitmap previous = currentBitmap;
        Bitmap previousBlur = currentBlurBitmap;
        Bitmap blur = buildBackgroundBlur(decoded);
        currentBitmap = decoded;
        currentBlurBitmap = blur;
        lastPhoto = next;
        photoBlurView.setImageBitmap(blur);
        photoScrim.setVisibility(View.VISIBLE);
        photoView.setImageBitmap(decoded);
        AlphaAnimation fade = new AlphaAnimation(0.25f, 1f);
        fade.setDuration(550);
        photoView.startAnimation(fade);
        if (previous != null && previous != decoded) previous.recycle();
        if (previousBlur != null && previousBlur != previous && previousBlur != blur) {
            previousBlur.recycle();
        }
        showPhotoCaption(next);
    }

    /**
     * Corte repetido pela metade: a copia miuda, esticada na tela inteira, vira o
     * fundo desfocado da moldura sem usar RenderScript (custo quase zero no 4.4).
     */
    private Bitmap buildBackgroundBlur(Bitmap source) {
        Bitmap current = source;
        int guard = 0;
        while ((current.getWidth() > 96 || current.getHeight() > 72) && guard++ < 8) {
            int width = Math.max(8, current.getWidth() / 2);
            int height = Math.max(8, current.getHeight() / 2);
            Bitmap next = Bitmap.createScaledBitmap(current, width, height, true);
            if (current != source) current.recycle();
            current = next;
        }
        if (current == source) {
            // Foto minuscula demais: copia para nao dividir a mesma bitmap com a foto.
            current = source.copy(Bitmap.Config.RGB_565, false);
        }
        return current;
    }

    private void showCaptionText(String text) {
        photoCaption.setText(text);
        photoCaption.setVisibility(chromeVisible ? View.VISIBLE : View.GONE);
    }

    /** Escolhe a proxima foto, pulando as que falharam ha menos de PHOTO_RETRY_MS. */
    private File pickNextPhoto() {
        boolean sequential = prefs.getBoolean("photo_sequential", false);
        int attempts = Math.max(1, photos.size());
        for (int i = 0; i < attempts; i++) {
            File candidate;
            if (sequential) {
                if (photoIndex >= photos.size()) photoIndex = 0;
                candidate = photos.get(photoIndex++);
            } else {
                candidate = photos.get(random.nextInt(photos.size()));
                if (photos.size() > 1 && candidate.equals(lastPhoto)) {
                    candidate = photos.get((photos.indexOf(candidate) + 1) % photos.size());
                }
            }
            if (!inFailureCooldown(candidate)) return candidate;
        }
        return null;
    }

    private boolean inFailureCooldown(File file) {
        Long failedAt = photosWithFailure.get(file.getAbsolutePath());
        if (failedAt == null) return false;
        if (System.currentTimeMillis() - failedAt > PHOTO_RETRY_MS) {
            photosWithFailure.remove(file.getAbsolutePath());
            return false;
        }
        return true;
    }

    private void showPhotoCaption(File file) {
        try {
            String when = new SimpleDateFormat("dd/MM/yyyy HH:mm", PT_BR)
                    .format(new Date(file.lastModified()));
            showCaptionText(file.getName() + "  ·  " + when);
        } catch (Exception ex) {
            photoCaption.setVisibility(View.GONE);
        }
    }

    private void refreshPhotos(boolean force) {
        if (photoScanBusy) {
            // Pedido cheio durante outra varredura: refaz em seguida, nao perde.
            if (force) scanPending = true;
            return;
        }
        if (!force && System.currentTimeMillis() - lastPhotoScan < PHOTO_RESAN_MS) return;
        photoScanBusy = true;
        final boolean sequential = prefs.getBoolean("photo_sequential", false);
        final String extraDir = prefs.getString("extra_dir", "").trim();
        new Thread(new Runnable() {
            @Override public void run() {
                final File card = findCardRoot();
                final List<File> found = new ArrayList<File>();
                if (card != null) collectPhotos(card, found, new HashSet<String>(), 0);
                final boolean fromCard = !found.isEmpty();
                if (!fromCard) collectPhotos(photoDir, found, new HashSet<String>(), 0);
                if (extraDir.length() > 0) {
                    File extra = new File(extraDir);
                    if (extra.isDirectory()) collectPhotos(extra, found, new HashSet<String>(), 0);
                }
                if (sequential) sortByDate(found);
                savePhotoList(found);
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        cardRoot = card;
                        usingCard = fromCard;
                        photos.clear();
                        photos.addAll(found);
                        // Continua depois da foto atual, mesmo quando a varredura
                        // atualiza a lista; reiniciar aqui repetia sempre as primeiras.
                        boolean restart = restartPhotoSequence
                                && sequential == prefs.getBoolean("photo_sequential", false)
                                && extraDir.equals(prefs.getString("extra_dir", "").trim());
                        if (restart) restartPhotoSequence = false;
                        int currentIndex = sequential && !restart ? photos.indexOf(lastPhoto) : -1;
                        photoIndex = currentIndex >= 0 ? (currentIndex + 1) % photos.size() : 0;
                        lastPhotoScan = System.currentTimeMillis();
                        photoScanBusy = false;
                        updateStatus();
                        if (running && currentPage == PAGE_PHOTOS && !isSleeping()
                                && (restart || currentBitmap == null || !photos.contains(lastPhoto))) {
                            showNextPhoto();
                        }
                        if (scanPending) {
                            scanPending = false;
                            refreshPhotos(true);
                        }
                    }
                });
            }
        }, "procurar-fotos").start();
    }

    /** Cartao removido: reavalia as fontes na hora em vez de esperar a proxima varredura. */
    private void dropPhotoList() {
        photos.clear();
        photoIndex = 0;
        lastPhotoScan = 0;
        photoCaption.setVisibility(View.GONE);
        updateStatus();
        refreshPhotos(true);
    }

    private void sortByDate(List<File> found) {
        Collections.sort(found, new Comparator<File>() {
            @Override public int compare(File first, File second) {
                long diff = second.lastModified() - first.lastModified();
                return diff > 0 ? 1 : diff < 0 ? -1 : 0;
            }
        });
    }

    private File photoListFile() {
        return new File(getFilesDir(), "fotos.txt");
    }

    /** Guarda a lista em disco para as fotos aparecerem na hora no proximo boot. */
    private void savePhotoList(List<File> found) {
        FileWriter writer = null;
        try {
            writer = new FileWriter(photoListFile());
            for (File file : found) writer.write(file.getAbsolutePath() + "\n");
        } catch (Exception ex) {
            Log.w(TAG, "Nao foi possivel salvar a lista de fotos", ex);
        } finally {
            if (writer != null) {
                try { writer.close(); } catch (Exception ignored) { /* melhor esforco */ }
            }
        }
    }

    /** Carrega a lista salva; a varredura em segundo plano confirma e atualiza. */
    private void loadCachedPhotos() {
        if (!photoListFile().exists()) return;
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(photoListFile()), "UTF-8"));
            List<File> found = new ArrayList<File>();
            String line;
            while ((line = reader.readLine()) != null && found.size() < MAX_PHOTOS) {
                if (line.length() > 0) found.add(new File(line));
            }
            if (found.isEmpty()) return;
            if (prefs.getBoolean("photo_sequential", false)) sortByDate(found);
            photos.addAll(found);
            // A primeira foto aparece pelo photoTask, que roda logo em seguida.
            updateStatus();
        } catch (Exception ex) {
            Log.w(TAG, "Lista de fotos salva nao pôde ser lida", ex);
        } finally {
            if (reader != null) {
                try { reader.close(); } catch (Exception ignored) { /* melhor esforco */ }
            }
        }
    }

    private File findCardRoot() {
        List<String> candidates = new ArrayList<String>();
        candidates.add("/storage/sdcard1"); // caminho do cartão neste tablet
        String secondary = System.getenv("SECONDARY_STORAGE");
        if (secondary != null) {
            for (String path : secondary.split(":")) candidates.add(path);
        }
        candidates.add("/storage/extSdCard");
        candidates.add("/storage/external_SD");
        candidates.add("/mnt/extSdCard");
        for (String path : candidates) {
            File candidate = new File(path);
            if (candidate.isDirectory() && candidate.listFiles() != null) return candidate;
        }
        return null;
    }

    private void collectPhotos(File directory, List<File> found, Set<String> visited, int depth) {
        if (depth > 7 || found.size() >= MAX_PHOTOS) return;
        try {
            if (!visited.add(directory.getCanonicalPath())) return;
        } catch (Exception ex) {
            return;
        }
        File[] entries = directory.listFiles();
        if (entries == null) return;
        for (File entry : entries) {
            if (found.size() >= MAX_PHOTOS) return;
            String name = entry.getName();
            if (name.startsWith(".")) continue;
            if (entry.isDirectory()) {
                if (!name.equalsIgnoreCase("Android") && !name.equalsIgnoreCase("LOST.DIR")
                        && !name.equalsIgnoreCase("System Volume Information")) {
                    collectPhotos(entry, found, visited, depth + 1);
                }
            } else if (entry.isFile()) {
                String lower = name.toLowerCase(Locale.US);
                if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                        || lower.endsWith(".png") || lower.endsWith(".webp")) found.add(entry);
            }
        }
    }

    private Bitmap decodePhoto(File file) {
        try {
            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), probe);
            if (probe.outWidth <= 0 || probe.outHeight <= 0) return null;
            int degrees = photoDegrees(file);
            // A imagem e reduzida para o tamanho da tela: menos memoria e decode mais rapido
            // no tablet antigo. Fotos rotacionadas trocam largura e altura na conta.
            boolean swapped = degrees == 90 || degrees == 270;
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            int limitWidth = swapped ? metrics.heightPixels : metrics.widthPixels;
            int limitHeight = swapped ? metrics.widthPixels : metrics.heightPixels;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inPreferredConfig = Bitmap.Config.RGB_565;
            opts.inSampleSize = 1;
            while (probe.outWidth / opts.inSampleSize > limitWidth
                    || probe.outHeight / opts.inSampleSize > limitHeight) {
                opts.inSampleSize *= 2;
            }
            Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
            if (bitmap == null) return null;
            if (degrees == 0) return bitmap;
            Matrix matrix = new Matrix();
            matrix.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(),
                    bitmap.getHeight(), matrix, true);
            bitmap.recycle();
            return rotated;
        } catch (Exception ex) {
            Log.w(TAG, "Foto ignorada: " + file.getName(), ex);
            return null;
        }
    }

    private int photoDegrees(File file) {
        try {
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            return orientation == ExifInterface.ORIENTATION_ROTATE_90 ? 90
                    : orientation == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                    : orientation == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        } catch (Exception ignored) {
            return 0; // PNG e WebP nao tem EXIF; a imagem continua valida.
        }
    }

    private void updateCalendar() {
        StringBuilder text = new StringBuilder();
        long now = System.currentTimeMillis();
        Uri uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .appendPath(Long.toString(now))
                .appendPath(Long.toString(now + 14L * 24 * 60 * 60 * 1000)).build();
        String[] columns = {CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.CALENDAR_DISPLAY_NAME};
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, columns,
                    CalendarContract.Instances.VISIBLE + "=1", null, CalendarContract.Instances.BEGIN + " ASC");
            int shown = 0;
            while (cursor != null && cursor.moveToNext() && shown < 6) {
                String title = cursor.getString(0);
                long start = cursor.getLong(1);
                boolean allDay = cursor.getInt(2) != 0;
                if (title == null || title.trim().length() == 0) continue;
                if (shown > 0) text.append("\n\n");
                text.append(formatEventDate(start, allDay)).append("\n").append(title.trim());
                shown++;
            }
            if (shown == 0) text.append("Nenhum compromisso nos próximos 14 dias");
        } catch (Exception ex) {
            Log.w(TAG, "Não foi possível ler agenda", ex);
            text.setLength(0);
            text.append("Não foi possível ler a agenda");
        } finally {
            if (cursor != null) cursor.close();
        }
        weatherView.setAgenda(text.toString());
    }

    private String formatEventDate(long millis, boolean allDay) {
        Calendar event = Calendar.getInstance(allDay ? TimeZone.getTimeZone("UTC") : TimeZone.getDefault());
        event.setTimeInMillis(millis);
        Calendar today = Calendar.getInstance();
        String day;
        if (sameDay(event, today)) day = "Hoje";
        else {
            today.add(Calendar.DAY_OF_YEAR, 1);
            if (sameDay(event, today)) day = "Amanhã";
            else {
                SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, d MMM", PT_BR);
                if (allDay) dateFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
                day = dateFormat.format(new Date(millis));
            }
        }
        return allDay ? day + " · dia todo" : day + " · " + new SimpleDateFormat("HH:mm", PT_BR).format(new Date(millis));
    }

    private boolean sameDay(Calendar first, Calendar second) {
        return first.get(Calendar.YEAR) == second.get(Calendar.YEAR)
                && first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR);
    }

    private void updateWeather() {
        final String city = prefs.getString("city", "").trim();
        if (city.length() == 0) {
            weatherView.setCity("Cidade não configurada");
            weatherView.setIssue("Toque em Configurar para escolher a cidade");
            photoWeatherTeaser.setText("Configure a cidade para ver o clima");
            return;
        }
        if (weatherBusy) return;
        weatherBusy = true;
        weatherView.setCity(city);
        weatherView.setLoading();
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    String coordinates = prefs.getString("latitude", "");
                    String longitude = prefs.getString("longitude", "");
                    String displayCity = prefs.getString("display_city", city);
                    if (coordinates.length() == 0 || longitude.length() == 0) {
                        String encoded = URLEncoder.encode(city, "UTF-8");
                        JSONObject geo = getJson("http://geocoding-api.open-meteo.com/v1/search?name=" + encoded
                                + "&count=1&language=pt&format=json&countryCode=BR");
                        JSONArray results = geo.optJSONArray("results");
                        if (results == null || results.length() == 0) throw new Exception("Cidade não encontrada");
                        JSONObject place = results.getJSONObject(0);
                        coordinates = Double.toString(place.getDouble("latitude"));
                        longitude = Double.toString(place.getDouble("longitude"));
                        displayCity = place.optString("name", city);
                        prefs.edit().putString("latitude", coordinates).putString("longitude", longitude)
                                .putString("display_city", displayCity).apply();
                    }
                    // Sem forecast_hours: o Open-Meteo devolve 72 pontos (3 dias) a partir
                    // de 00:00. O recorte comeca na hora atual na hora de desenhar.
                    JSONObject weather = getJson("http://api.open-meteo.com/v1/forecast?latitude=" + coordinates
                            + "&longitude=" + longitude
                            + "&current=temperature_2m,weather_code,is_day"
                            + "&hourly=temperature_2m,precipitation_probability"
                            + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                            + "&timezone=auto&forecast_days=3");
                    final JSONObject current = weather.getJSONObject("current");
                    final JSONObject daily = weather.getJSONObject("daily");
                    final JSONObject hourly = weather.getJSONObject("hourly");
                    final String placeName = displayCity;
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            String condition = weatherDescription(current.optInt("weather_code", -1));
                            weatherView.setWeather(placeName, condition, current, daily, hourly);
                            weatherScene.setWeather(current.optInt("weather_code", 2),
                                    current.optInt("is_day", 1) == 1);
                            photoWeatherTeaser.setText(placeName + "  ·  "
                                    + Math.round(current.optDouble("temperature_2m")) + "°C  ·  "
                                    + condition + "  ›");
                        }
                    });
                } catch (final Exception ex) {
                    Log.w(TAG, "Falha na previsão", ex);
                    // getMessage() pode ser nulo (ex.: IOException de rede): nunca chamar
                    // contains() direto, senao o proprio tratamento derruba o app.
                    String detail = ex.getMessage() == null ? "" : ex.getMessage();
                    final String issue = detail.contains("Cidade não encontrada")
                            ? "Cidade não encontrada"
                            : "Previsão temporariamente indisponível";
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            weatherView.setIssue(issue);
                            photoWeatherTeaser.setText("Toque para conferir a previsão  ›");
                        }
                    });
                } finally {
                    weatherBusy = false;
                }
            }
        }, "previsao-do-tempo").start();
    }

    private void updateStatus() {
        weatherView.setPhotoSource(usingCard ? "Fotos: cartão SD"
                : photos.isEmpty() ? "Sem fotos" : "Fotos: tablet");
    }

    private JSONObject getJson(String address) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(address).openConnection();
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(12000);
        connection.setRequestProperty("User-Agent", "PainelTablet/1.0");
        try {
            if (connection.getResponseCode() != 200) throw new Exception("HTTP " + connection.getResponseCode());
            InputStream stream = connection.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) data.append(line);
            reader.close();
            return new JSONObject(data.toString());
        } finally {
            connection.disconnect();
        }
    }

    private String weatherDescription(int code) {
        if (code == 0) return "Céu limpo";
        if (code == 1) return "Predominantemente ensolarado";
        if (code == 2) return "Parcialmente nublado";
        if (code == 3) return "Nublado";
        if (code == 45 || code == 48) return "Neblina";
        if (code >= 51 && code <= 57) return "Garoa";
        if (code >= 61 && code <= 67) return "Chuva";
        if (code >= 71 && code <= 77) return "Neve";
        if (code >= 80 && code <= 82) return "Pancadas de chuva";
        if (code == 85 || code == 86) return "Pancadas de neve";
        if (code >= 95) return "Tempestade";
        return "Previsão do tempo";
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), dp(10), dp(22), dp(12));
        box.setFocusableInTouchMode(true);
        box.requestFocus();
        TextView prompt = label(18, Color.WHITE, false);
        prompt.setText("Cidade para a previsão do tempo:");
        box.addView(prompt);
        final EditText cityInput = new EditText(this);
        cityInput.setSingleLine(true);
        cityInput.setText(prefs.getString("city", ""));
        cityInput.setHint("Ex.: Campinas");
        box.addView(cityInput);
        TextView restTitle = label(18, Color.WHITE, true);
        restTitle.setText("Descanso noturno");
        restTitle.setPadding(0, dp(15), 0, dp(4));
        box.addView(restTitle);
        final CheckBox restEnabled = new CheckBox(this);
        restEnabled.setText("Escurecer a tela no horário de dormir");
        restEnabled.setTextColor(Color.WHITE);
        restEnabled.setTextSize(16);
        restEnabled.setChecked(prefs.getBoolean("night_enabled", true));
        box.addView(restEnabled);
        final int[] start = {prefs.getInt("night_start", 23 * 60)};
        final int[] end = {prefs.getInt("night_end", 7 * 60)};
        final TextView startButton = label(17, Color.WHITE, false);
        startButton.setText("Começa: " + formatMinute(start[0]) + "   ›");
        startButton.setPadding(dp(8), dp(8), dp(8), dp(8));
        box.addView(startButton);
        startButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                new TimePickerDialog(MainActivity.this, (picker, hour, minute) -> {
                    start[0] = hour * 60 + minute;
                    startButton.setText("Começa: " + formatMinute(start[0]) + "   ›");
                }, start[0] / 60, start[0] % 60, true).show();
            }
        });
        final TextView endButton = label(17, Color.WHITE, false);
        endButton.setText("Termina: " + formatMinute(end[0]) + "   ›");
        endButton.setPadding(dp(8), dp(8), dp(8), dp(8));
        box.addView(endButton);
        endButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                new TimePickerDialog(MainActivity.this, (picker, hour, minute) -> {
                    end[0] = hour * 60 + minute;
                    endButton.setText("Termina: " + formatMinute(end[0]) + "   ›");
                }, end[0] / 60, end[0] % 60, true).show();
            }
        });
        TextView speedHelp = label(16, 0xffcccccc, false);
        speedHelp.setPadding(0, dp(12), 0, 0);
        speedHelp.setText("O teste roda no Wi-Fi a cada 3 horas fora do descanso e mede "
                + "download, upload e latência. Também pode ser iniciado na tela Internet.");
        box.addView(speedHelp);
        TextView photosHelp = label(16, 0xffcccccc, false);
        photosHelp.setPadding(0, dp(14), 0, 0);
        photosHelp.setText("O painel lê automaticamente fotos JPG, PNG e WebP de todas as pastas "
                + "do cartão SD, a cada 5 minutos ou assim que o cartão mudar. Sem cartão, usa as "
                + "fotos de " + photoDir.getAbsolutePath() + ".");
        box.addView(photosHelp);

        TextView photosTitle = label(18, Color.WHITE, true);
        photosTitle.setText("Como mostrar as fotos");
        photosTitle.setPadding(0, dp(15), 0, dp(4));
        box.addView(photosTitle);
        final CheckBox orderRecent = new CheckBox(this);
        orderRecent.setText("Ordem cronológica (mais recentes primeiro)");
        orderRecent.setTextColor(Color.WHITE);
        orderRecent.setTextSize(16);
        orderRecent.setChecked(prefs.getBoolean("photo_sequential", false));
        box.addView(orderRecent);
        TextView extraDirLabel = label(16, 0xffcccccc, false);
        extraDirLabel.setText("Pasta extra com fotos (opcional):");
        extraDirLabel.setPadding(0, dp(10), 0, 0);
        box.addView(extraDirLabel);
        final EditText extraDirInput = new EditText(this);
        extraDirInput.setSingleLine(true);
        extraDirInput.setText(prefs.getString("extra_dir", ""));
        extraDirInput.setHint("Ex.: /storage/sdcard1/Igreja");
        extraDirInput.setSelectAllOnFocus(true);
        box.addView(extraDirInput);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        AlertDialog settingsDialog = new AlertDialog.Builder(this)
                .setTitle("Painel do Tablet · v" + BuildConfig.VERSION_NAME)
                .setView(scroll)
                .setPositiveButton("Salvar", (dialog, which) -> {
                    String city = cityInput.getText().toString().trim();
                    String extraDir = extraDirInput.getText().toString().trim();
                    boolean orderChanged = orderRecent.isChecked()
                            != prefs.getBoolean("photo_sequential", false);
                    boolean folderChanged = !extraDir.equals(prefs.getString("extra_dir", ""));
                    SharedPreferences.Editor edit = prefs.edit()
                            .putBoolean("night_enabled", restEnabled.isChecked())
                            .putInt("night_start", start[0])
                            .putInt("night_end", end[0])
                            .putBoolean("photo_sequential", orderRecent.isChecked())
                            .putString("extra_dir", extraDir);
                    if (!city.equals(prefs.getString("city", ""))) {
                        edit.putString("city", city).remove("latitude").remove("longitude")
                                .remove("display_city");
                        edit.apply();
                        updateWeather();
                    } else edit.apply();
                    if (orderChanged || folderChanged) {
                        restartPhotoSequence = true;
                        lastPhotoScan = 0;
                        refreshPhotos(true);
                    }
                    updateNightMode();
                    internetView.setNextTest(nextSpeedTestTime(prefs.getLong("speed_last_attempt", 0)));
                })
                .setNeutralButton("Copiar fotos para tablet", (dialog, which) -> openPhotoPicker())
                .setNegativeButton("Fechar", null)
                .create();
        settingsDialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        // Com a janela aberta o modo limpo fica suspenso; volta quando ela fecha.
        handler.removeCallbacks(hideChromeTask);
        settingsDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface dialog) {
                if (currentPage == PAGE_PHOTOS) showChromeAndArm();
            }
        });
        settingsDialog.show();
    }

    private String formatMinute(int minute) {
        return String.format(PT_BR, "%02d:%02d", minute / 60, minute % 60);
    }

    private void openPhotoPicker() {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("image/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(Intent.createChooser(intent, "Escolher fotos"), PICK_PHOTOS);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != PICK_PHOTOS || resultCode != RESULT_OK || data == null) return;
        final List<Uri> selected = new ArrayList<Uri>();
        ClipData clip = data.getClipData();
        if (clip != null) {
            for (int i = 0; i < clip.getItemCount(); i++) selected.add(clip.getItemAt(i).getUri());
        } else if (data.getData() != null) selected.add(data.getData());
        if (selected.isEmpty()) return;
        new Thread(new Runnable() {
            @Override public void run() {
                int copied = 0;
                ContentResolver resolver = getContentResolver();
                for (Uri uri : selected) {
                    try {
                        String mime = resolver.getType(uri);
                        String extension = "image/png".equals(mime) ? ".png"
                                : "image/webp".equals(mime) ? ".webp" : ".jpg";
                        File destination = new File(photoDir, "foto-" + System.currentTimeMillis() + "-" + copied + extension);
                        InputStream in = resolver.openInputStream(uri);
                        if (in == null) continue;
                        FileOutputStream out = new FileOutputStream(destination);
                        byte[] buffer = new byte[16 * 1024];
                        int length;
                        while ((length = in.read(buffer)) != -1) out.write(buffer, 0, length);
                        out.close();
                        in.close();
                        copied++;
                    } catch (Exception ex) {
                        Log.w(TAG, "Não foi possível copiar foto", ex);
                    }
                }
                final int total = copied;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        Toast.makeText(MainActivity.this, total + " foto(s) adicionada(s)", Toast.LENGTH_LONG).show();
                        lastPhotoScan = 0;
                        refreshPhotos(true);
                    }
                });
            }
        }, "copiar-fotos").start();
    }
}
