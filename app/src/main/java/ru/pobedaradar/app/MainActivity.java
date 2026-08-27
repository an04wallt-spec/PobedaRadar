package ru.pobedaradar.app;

import android.Manifest;
import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private static final String APP_VERSION = "v0.8";

    private static final String PREFS = "pobeda_radar";

    private static final String TOKEN_KEY = "travelpayouts_token";
    private static final String DATE_FROM_KEY = "date_from";
    private static final String DATE_TO_KEY = "date_to";
    private static final String DIRECTION_KEY = "direction_outbound";

    private static final String LAST_PRICE_PREFIX = "last_price_";
    private static final String MIN_PRICE_PREFIX = "min_price_";
    private static final String LAST_SEEN_PREFIX = "last_seen_";
    private static final String LAST_UPDATE_PREFIX = "last_update_";

    private static final String BACKGROUND_WORK_NAME =
            "pobeda_background_radar";

    private static final int NOTIFICATION_PERMISSION_REQUEST = 2001;

    private static final int RED = Color.rgb(210, 30, 30);
    private static final int GREEN = Color.rgb(35, 135, 70);
    private static final int GREY = Color.rgb(105, 105, 105);
    private static final int LIGHT_GREY = Color.rgb(236, 236, 236);
    private static final int DARK = Color.rgb(28, 28, 28);

    private final DateTimeFormatter uiDate =
            DateTimeFormatter.ofPattern(
                    "dd.MM.yyyy",
                    new Locale("ru")
            );

    private final DateTimeFormatter shortUiDate =
            DateTimeFormatter.ofPattern(
                    "dd.MM",
                    new Locale("ru")
            );

    private final DateTimeFormatter monthParam =
            DateTimeFormatter.ofPattern(
                    "yyyy-MM"
            );

    private final DateTimeFormatter timeFormat =
            DateTimeFormatter.ofPattern(
                    "HH:mm",
                    new Locale("ru")
            );

    private SharedPreferences prefs;

    private boolean outbound;

    private LocalDate rangeFrom;
    private LocalDate rangeTo;

    private LinearLayout root;
    private LinearLayout weekBox;
    private LinearLayout tokenBlock;

    private Button outButton;
    private Button backButton;
    private Button fromButton;
    private Button toButton;
    private Button refreshButton;

    private EditText tokenInput;

    private TextView resultText;
    private TextView statusText;

    private ProgressBar progress;

    private boolean requestRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs =
                getSharedPreferences(
                        PREFS,
                        MODE_PRIVATE
                );

        outbound =
                prefs.getBoolean(
                        DIRECTION_KEY,
                        true
                );

        restoreDates();

        buildInterface();

        refreshInterface();

        /*
         * Android 13+ один раз попросит
         * разрешение на уведомления.
         */
        requestNotificationPermission();

        /*
         * Ставим фоновый радар.
         */
        scheduleBackgroundRadar();
    }

    // ============================================================
    // УВЕДОМЛЕНИЯ И ФОНОВЫЙ РАДАР
    // ============================================================

    private void requestNotificationPermission() {

        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED) {

            ActivityCompat.requestPermissions(
                    this,
                    new String[]{
                            Manifest.permission.POST_NOTIFICATIONS
                    },
                    NOTIFICATION_PERMISSION_REQUEST
            );
        }
    }

    private void scheduleBackgroundRadar() {

        /*
         * Работа выполняется только при наличии сети.
         */
        Constraints constraints =
                new Constraints.Builder()
                        .setRequiredNetworkType(
                                NetworkType.CONNECTED
                        )
                        .build();

        /*
         * Ориентировочно каждые 3 часа.
         *
         * Android может немного сдвигать время запуска,
         * чтобы экономить батарею.
         */
        PeriodicWorkRequest request =
                new PeriodicWorkRequest.Builder(
                        PriceRadarWorker.class,
                        3,
                        TimeUnit.HOURS
                )
                        .setConstraints(
                                constraints
                        )
                        .build();

        WorkManager
                .getInstance(this)
                .enqueueUniquePeriodicWork(
                        BACKGROUND_WORK_NAME,
                        ExistingPeriodicWorkPolicy.UPDATE,
                        request
                );
    }

    // ============================================================
    // ДАТЫ
    // ============================================================

    private void restoreDates() {

        LocalDate today =
                LocalDate.now();

        try {

            rangeFrom =
                    LocalDate.parse(
                            prefs.getString(
                                    DATE_FROM_KEY,
                                    today.toString()
                            )
                    );

        } catch (Exception e) {

            rangeFrom = today;
        }

        try {

            rangeTo =
                    LocalDate.parse(
                            prefs.getString(
                                    DATE_TO_KEY,
                                    today.plusDays(30).toString()
                            )
                    );

        } catch (Exception e) {

            rangeTo =
                    today.plusDays(30);
        }

        if (rangeFrom.isBefore(today)) {

            rangeFrom = today;
        }

        if (rangeTo.isBefore(rangeFrom)) {

            rangeTo =
                    rangeFrom.plusDays(30);
        }

        saveDates();
    }

    private void saveDates() {

        prefs.edit()
                .putString(
                        DATE_FROM_KEY,
                        rangeFrom.toString()
                )
                .putString(
                        DATE_TO_KEY,
                        rangeTo.toString()
                )
                .apply();
    }

    // ============================================================
    // ИНТЕРФЕЙС
    // ============================================================

    private void buildInterface() {

        root =
                new LinearLayout(this);

        root.setOrientation(
                LinearLayout.VERTICAL
        );

        /*
         * ScrollView отсутствует специально.
         * Всё рассчитано на один экран.
         */
        root.setPadding(
                dp(16),
                dp(38),
                dp(16),
                dp(6)
        );

        // --------------------------------------------------------
        // Заголовок
        // --------------------------------------------------------

        TextView title =
                makeText(
                        "Москва ⇄ Газипаша · Победа (DP)",
                        16,
                        true
                );

        title.setTextColor(
                GREY
        );

        title.setGravity(
                Gravity.CENTER
        );

        title.setSingleLine(
                true
        );

        root.addView(
                title,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(26)
                )
        );

        // --------------------------------------------------------
        // Направления
        // --------------------------------------------------------

        LinearLayout directionRow =
                new LinearLayout(this);

        directionRow.setOrientation(
                LinearLayout.HORIZONTAL
        );

        outButton =
                makeButton(
                        "МОСКВА → GZP",
                        14
                );

        backButton =
                makeButton(
                        "GZP → МОСКВА",
                        14
                );

        directionRow.addView(
                outButton,
                new LinearLayout.LayoutParams(
                        0,
                        dp(46),
                        1
                )
        );

        View directionGap =
                new View(this);

        directionRow.addView(
                directionGap,
                new LinearLayout.LayoutParams(
                        dp(8),
                        1
                )
        );

        directionRow.addView(
                backButton,
                new LinearLayout.LayoutParams(
                        0,
                        dp(46),
                        1
                )
        );

        LinearLayout.LayoutParams directionLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(46)
                );

        directionLp.topMargin =
                dp(4);

        root.addView(
                directionRow,
                directionLp
        );

        outButton.setOnClickListener(v -> {

            if (requestRunning) {
                return;
            }

            outbound = true;

            saveDirection();

            refreshInterface();
        });

        backButton.setOnClickListener(v -> {

            if (requestRunning) {
                return;
            }

            outbound = false;

            saveDirection();

            refreshInterface();
        });

        // --------------------------------------------------------
        // Ближайшие даты
        // --------------------------------------------------------

        TextView weekTitle =
                makeText(
                        "Ближайшие даты",
                        13,
                        true
                );

        weekTitle.setTextColor(
                GREY
        );

        LinearLayout.LayoutParams weekTitleLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(24)
                );

        weekTitleLp.topMargin =
                dp(6);

        root.addView(
                weekTitle,
                weekTitleLp
        );

        weekBox =
                new LinearLayout(this);

        weekBox.setOrientation(
                LinearLayout.VERTICAL
        );

        root.addView(
                weekBox,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(154)
                )
        );

        // --------------------------------------------------------
        // Диапазон
        // --------------------------------------------------------

        TextView datesCaption =
                makeText(
                        "Диапазон дат",
                        14,
                        true
                );

        LinearLayout.LayoutParams captionLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(22)
                );

        captionLp.topMargin =
                dp(4);

        root.addView(
                datesCaption,
                captionLp
        );

        LinearLayout datesRow =
                new LinearLayout(this);

        datesRow.setOrientation(
                LinearLayout.HORIZONTAL
        );

        fromButton =
                makeButton(
                        "",
                        13
                );

        toButton =
                makeButton(
                        "",
                        13
                );

        datesRow.addView(
                fromButton,
                new LinearLayout.LayoutParams(
                        0,
                        dp(42),
                        1
                )
        );

        View dateGap =
                new View(this);

        datesRow.addView(
                dateGap,
                new LinearLayout.LayoutParams(
                        dp(8),
                        1
                )
        );

        datesRow.addView(
                toButton,
                new LinearLayout.LayoutParams(
                        0,
                        dp(42),
                        1
                )
        );

        root.addView(
                datesRow,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(42)
                )
        );

        fromButton.setOnClickListener(v -> {

            if (!requestRunning) {

                pickDate(true);
            }
        });

        toButton.setOnClickListener(v -> {

            if (!requestRunning) {

                pickDate(false);
            }
        });

        // --------------------------------------------------------
        // TOKEN
        // --------------------------------------------------------

        tokenBlock =
                new LinearLayout(this);

        tokenBlock.setOrientation(
                LinearLayout.VERTICAL
        );

        tokenInput =
                new EditText(this);

        tokenInput.setTextSize(
                14
        );

        tokenInput.setHint(
                "Travelpayouts token"
        );

        tokenInput.setSingleLine(
                true
        );

        tokenInput.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );

        String savedToken =
                prefs.getString(
                        TOKEN_KEY,
                        ""
                );

        tokenInput.setText(
                savedToken
        );

        tokenBlock.addView(
                tokenInput,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(38)
                )
        );

        LinearLayout.LayoutParams tokenLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(38)
                );

        tokenLp.topMargin =
                dp(4);

        root.addView(
                tokenBlock,
                tokenLp
        );

        if (!savedToken.isEmpty()) {

            tokenBlock.setVisibility(
                    View.GONE
            );
        }

        // --------------------------------------------------------
        // Обновить
        // --------------------------------------------------------

        refreshButton =
                makeButton(
                        "ОБНОВИТЬ РАДАР",
                        15
                );

        LinearLayout.LayoutParams refreshLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(44)
                );

        refreshLp.topMargin =
                dp(5);

        root.addView(
                refreshButton,
                refreshLp
        );

        refreshButton.setOnClickListener(
                v -> loadRadar()
        );

        // --------------------------------------------------------
        // Минимум
        // --------------------------------------------------------

        resultText =
                makeText(
                        "",
                        14,
                        true
                );

        resultText.setGravity(
                Gravity.CENTER
        );

        LinearLayout.LayoutParams resultLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(24)
                );

        resultLp.topMargin =
                dp(3);

        root.addView(
                resultText,
                resultLp
        );

        // --------------------------------------------------------
        // Статус
        // --------------------------------------------------------

        statusText =
                makeText(
                        "",
                        12,
                        false
                );

        statusText.setTextColor(
                GREY
        );

        statusText.setGravity(
                Gravity.CENTER
        );

        statusText.setSingleLine(
                true
        );

        root.addView(
                statusText,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(26)
                )
        );

        // --------------------------------------------------------
        // Прогресс
        // --------------------------------------------------------

        progress =
                new ProgressBar(this);

        progress.setVisibility(
                View.GONE
        );

        LinearLayout.LayoutParams progressLp =
                new LinearLayout.LayoutParams(
                        dp(22),
                        dp(22)
                );

        progressLp.gravity =
                Gravity.CENTER_HORIZONTAL;

        root.addView(
                progress,
                progressLp
        );

        // --------------------------------------------------------
        // Нижняя строка
        // --------------------------------------------------------

        LinearLayout bottomRow =
                new LinearLayout(this);

        bottomRow.setOrientation(
                LinearLayout.HORIZONTAL
        );

        bottomRow.setGravity(
                Gravity.CENTER_VERTICAL
        );

        Button pobedaButton =
                makeSmallButton(
                        "Проверить на сайте Победы"
                );

        bottomRow.addView(
                pobedaButton,
                new LinearLayout.LayoutParams(
                        0,
                        dp(34),
                        1
                )
        );

        TextView version =
                makeText(
                        APP_VERSION,
                        10,
                        false
                );

        version.setTextColor(
                GREY
        );

        version.setGravity(
                Gravity.END
                        | Gravity.CENTER_VERTICAL
        );

        bottomRow.addView(
                version,
                new LinearLayout.LayoutParams(
                        dp(48),
                        dp(34)
                )
        );

        root.addView(
                bottomRow,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(34)
                )
        );

        pobedaButton.setOnClickListener(v -> {

            try {

                Intent intent =
                        new Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(
                                        "https://www.pobeda.aero/"
                                )
                        );

                startActivity(
                        intent
                );

            } catch (Exception e) {

                Toast.makeText(
                        this,
                        "Не удалось открыть сайт Победы",
                        Toast.LENGTH_SHORT
                ).show();
            }
        });

        setContentView(
                root
        );
    }

    private void saveDirection() {

        prefs.edit()
                .putBoolean(
                        DIRECTION_KEY,
                        outbound
                )
                .apply();
    }

    private void refreshInterface() {

        styleDirection(
                outButton,
                outbound
        );

        styleDirection(
                backButton,
                !outbound
        );

        fromButton.setText(
                "С  "
                        + rangeFrom.format(
                        uiDate
                )
        );

        toButton.setText(
                "ПО  "
                        + rangeTo.format(
                        uiDate
                )
        );

        showStoredWeek();

        resultText.setText(
                ""
        );

        long lastUpdate =
                getLastUpdate(
                        outbound
                );

        if (lastUpdate > 0) {

            statusText.setText(
                    routeText()
                            + " · обновлено "
                            + formatTime(
                            lastUpdate
                    )
            );

        } else {

            statusText.setText(
                    routeText()
            );
        }
    }

    private String routeText() {

        return outbound
                ? "Москва → Газипаша"
                : "Газипаша → Москва";
    }

    // ============================================================
    // БЛИЖАЙШИЕ ДАТЫ
    // ============================================================

    private void showStoredWeek() {

        weekBox.removeAllViews();

        for (int i = 0; i < 7; i++) {

            LocalDate day =
                    rangeFrom.plusDays(i);

            if (day.isAfter(rangeTo)) {

                addWeekRow(
                        day,
                        "",
                        "",
                        GREY
                );

                continue;
            }

            int storedPrice =
                    getStoredLastPrice(
                            day,
                            outbound
                    );

            int storedMinimum =
                    getStoredMinPrice(
                            day,
                            outbound
                    );

            if (storedPrice > 0) {

                String info =
                        "ранее";

                if (storedMinimum > 0) {

                    info +=
                            " · мин "
                                    + formatPriceCompact(
                                    storedMinimum
                            );
                }

                addWeekRow(
                        day,
                        formatPrice(
                                storedPrice
                        ),
                        info,
                        GREY
                );

            } else {

                addWeekRow(
                        day,
                        "—",
                        "",
                        GREY
                );
            }
        }
    }

    private void showWeekAndSaveHistory(
            List<Offer> offers,
            boolean historyOutbound
    ) {

        weekBox.removeAllViews();

        Map<LocalDate, Integer> currentPrices =
                getBestPricesByDate(
                        offers
                );

        for (int i = 0; i < 7; i++) {

            LocalDate day =
                    rangeFrom.plusDays(i);

            if (day.isAfter(rangeTo)) {

                addWeekRow(
                        day,
                        "",
                        "",
                        GREY
                );

                continue;
            }

            Integer newPrice =
                    currentPrices.get(
                            day
                    );

            int oldPrice =
                    getStoredLastPrice(
                            day,
                            historyOutbound
                    );

            int oldMinimum =
                    getStoredMinPrice(
                            day,
                            historyOutbound
                    );

            /*
             * API ничего свежего не дал:
             * последнюю известную цену не уничтожаем.
             */
            if (newPrice == null) {

                if (oldPrice > 0) {

                    String info =
                            "ранее";

                    if (oldMinimum > 0) {

                        info +=
                                " · мин "
                                        + formatPriceCompact(
                                        oldMinimum
                                );
                    }

                    addWeekRow(
                            day,
                            formatPrice(
                                    oldPrice
                            ),
                            info,
                            GREY
                    );

                } else {

                    addWeekRow(
                            day,
                            "нет данных",
                            "",
                            GREY
                    );
                }

                continue;
            }

            int difference =
                    oldPrice > 0
                            ? newPrice - oldPrice
                            : 0;

            /*
             * Сохраняем новую цену.
             */
            savePriceHistory(
                    day,
                    newPrice,
                    historyOutbound
            );

            int historicalMinimum =
                    getStoredMinPrice(
                            day,
                            historyOutbound
                    );

            String change;

            int changeColor =
                    GREY;

            if (oldPrice <= 0) {

                change = "";

            } else if (difference > 0) {

                change =
                        "↑"
                                + formatShortNumber(
                                difference
                        );

                changeColor =
                        RED;

            } else if (difference < 0) {

                change =
                        "↓"
                                + formatShortNumber(
                                Math.abs(
                                        difference
                                )
                        );

                changeColor =
                        GREEN;

            } else {

                change = "=";
            }

            if (historicalMinimum > 0) {

                if (!change.isEmpty()) {

                    change +=
                            " · ";
                }

                change +=
                        "мин "
                                + formatPriceCompact(
                                historicalMinimum
                        );
            }

            addWeekRow(
                    day,
                    formatPrice(
                            newPrice
                    ),
                    change,
                    changeColor
            );
        }
    }

    private Map<LocalDate, Integer> getBestPricesByDate(
            List<Offer> offers
    ) {

        Map<LocalDate, Integer> prices =
                new LinkedHashMap<>();

        for (Offer offer : offers) {

            Integer old =
                    prices.get(
                            offer.date
                    );

            if (old == null
                    || offer.price < old) {

                prices.put(
                        offer.date,
                        offer.price
                );
            }
        }

        return prices;
    }

    private void addWeekRow(
            LocalDate date,
            String price,
            String info,
            int infoColor
    ) {

        LinearLayout row =
                new LinearLayout(this);

        row.setOrientation(
                LinearLayout.HORIZONTAL
        );

        row.setGravity(
                Gravity.CENTER_VERTICAL
        );

        TextView dateText =
                makeText(
                        date.format(
                                shortUiDate
                        ),
                        13,
                        false
                );

        dateText.setTextColor(
                GREY
        );

        row.addView(
                dateText,
                new LinearLayout.LayoutParams(
                        0,
                        dp(22),
                        0.65f
                )
        );

        TextView priceText =
                makeText(
                        price,
                        13,
                        true
                );

        priceText.setGravity(
                Gravity.END
                        | Gravity.CENTER_VERTICAL
        );

        if ("нет данных".equals(price)
                || "—".equals(price)
                || price.isEmpty()) {

            priceText.setTextColor(
                    GREY
            );

        } else {

            priceText.setTextColor(
                    DARK
            );
        }

        row.addView(
                priceText,
                new LinearLayout.LayoutParams(
                        0,
                        dp(22),
                        1.0f
                )
        );

        TextView infoText =
                makeText(
                        info,
                        11,
                        true
                );

        infoText.setGravity(
                Gravity.END
                        | Gravity.CENTER_VERTICAL
        );

        infoText.setTextColor(
                infoColor
        );

        infoText.setSingleLine(
                true
        );

        row.addView(
                infoText,
                new LinearLayout.LayoutParams(
                        0,
                        dp(22),
                        1.45f
                )
        );

        weekBox.addView(
                row
        );
    }

    // ============================================================
    // ХРАНЕНИЕ ЦЕН
    // ============================================================

    private String routeHistoryId(
            boolean isOutbound
    ) {

        return isOutbound
                ? "MOW_GZP"
                : "GZP_MOW";
    }

    private String lastPriceKey(
            LocalDate date,
            boolean isOutbound
    ) {

        return LAST_PRICE_PREFIX
                + routeHistoryId(
                isOutbound
        )
                + "_"
                + date;
    }

    private String minPriceKey(
            LocalDate date,
            boolean isOutbound
    ) {

        return MIN_PRICE_PREFIX
                + routeHistoryId(
                isOutbound
        )
                + "_"
                + date;
    }

    private String lastSeenKey(
            LocalDate date,
            boolean isOutbound
    ) {

        return LAST_SEEN_PREFIX
                + routeHistoryId(
                isOutbound
        )
                + "_"
                + date;
    }

    private String lastUpdateKey(
            boolean isOutbound
    ) {

        return LAST_UPDATE_PREFIX
                + routeHistoryId(
                isOutbound
        );
    }

    private int getStoredLastPrice(
            LocalDate date,
            boolean isOutbound
    ) {

        return prefs.getInt(
                lastPriceKey(
                        date,
                        isOutbound
                ),
                -1
        );
    }

    private int getStoredMinPrice(
            LocalDate date,
            boolean isOutbound
    ) {

        return prefs.getInt(
                minPriceKey(
                        date,
                        isOutbound
                ),
                -1
        );
    }

    private long getLastUpdate(
            boolean isOutbound
    ) {

        return prefs.getLong(
                lastUpdateKey(
                        isOutbound
                ),
                0L
        );
    }

    private void savePriceHistory(
            LocalDate date,
            int currentPrice,
            boolean isOutbound
    ) {

        int oldMinimum =
                getStoredMinPrice(
                        date,
                        isOutbound
                );

        int newMinimum =
                oldMinimum <= 0
                        ? currentPrice
                        : Math.min(
                        oldMinimum,
                        currentPrice
                );

        long now =
                System.currentTimeMillis();

        prefs.edit()
                .putInt(
                        lastPriceKey(
                                date,
                                isOutbound
                        ),
                        currentPrice
                )
                .putInt(
                        minPriceKey(
                                date,
                                isOutbound
                        ),
                        newMinimum
                )
                .putLong(
                        lastSeenKey(
                                date,
                                isOutbound
                        ),
                        now
                )
                .apply();
    }

    private void saveLastUpdate(
            boolean isOutbound
    ) {

        prefs.edit()
                .putLong(
                        lastUpdateKey(
                                isOutbound
                        ),
                        System.currentTimeMillis()
                )
                .apply();
    }

    // ============================================================
    // ВЫБОР ДАТ
    // ============================================================

    private void pickDate(
            boolean start
    ) {

        LocalDate current =
                start
                        ? rangeFrom
                        : rangeTo;

        DatePickerDialog dialog =
                new DatePickerDialog(
                        this,
                        (view, year, month, day) -> {

                            LocalDate selected =
                                    LocalDate.of(
                                            year,
                                            month + 1,
                                            day
                                    );

                            if (start) {

                                rangeFrom =
                                        selected;

                                if (rangeTo.isBefore(
                                        rangeFrom
                                )) {

                                    rangeTo =
                                            rangeFrom;
                                }

                            } else {

                                rangeTo =
                                        selected;

                                if (rangeTo.isBefore(
                                        rangeFrom
                                )) {

                                    rangeFrom =
                                            rangeTo;
                                }
                            }

                            saveDates();

                            refreshInterface();

                        },
                        current.getYear(),
                        current.getMonthValue() - 1,
                        current.getDayOfMonth()
                );

        dialog.getDatePicker()
                .setMinDate(
                        System.currentTimeMillis()
                                - 1000
                );

        dialog.show();
    }

    // ============================================================
    // РУЧНОЙ ЗАПРОС
    // ============================================================

    private void loadRadar() {

        if (requestRunning) {
            return;
        }

        String token =
                prefs.getString(
                        TOKEN_KEY,
                        ""
                );

        if (token.isEmpty()) {

            token =
                    tokenInput
                            .getText()
                            .toString()
                            .trim();
        }

        if (token.isEmpty()) {

            Toast.makeText(
                    this,
                    "Введите Travelpayouts token",
                    Toast.LENGTH_LONG
            ).show();

            tokenBlock.setVisibility(
                    View.VISIBLE
            );

            return;
        }

        final String finalToken =
                token;

        final boolean requestedOutbound =
                outbound;

        final LocalDate requestedFrom =
                rangeFrom;

        final LocalDate requestedTo =
                rangeTo;

        final String origin =
                requestedOutbound
                        ? "MOW"
                        : "GZP";

        final String destination =
                requestedOutbound
                        ? "GZP"
                        : "MOW";

        saveDates();

        setLoadingState(
                true
        );

        resultText.setText(
                "Ищу цены Победы…"
        );

        statusText.setText(
                requestedFrom.format(
                        shortUiDate
                )
                        + "–"
                        + requestedTo.format(
                        shortUiDate
                )
        );

        new Thread(() -> {

            try {

                List<Offer> offers =
                        requestEntireRange(
                                origin,
                                destination,
                                requestedFrom,
                                requestedTo,
                                finalToken
                        );

                runOnUiThread(() -> {

                    setLoadingState(
                            false
                    );

                    prefs.edit()
                            .putString(
                                    TOKEN_KEY,
                                    finalToken
                            )
                            .apply();

                    tokenBlock.setVisibility(
                            View.GONE
                    );

                    saveLastUpdate(
                            requestedOutbound
                    );

                    if (outbound
                            == requestedOutbound
                            && rangeFrom.equals(
                            requestedFrom
                    )
                            && rangeTo.equals(
                            requestedTo
                    )) {

                        showWeekAndSaveHistory(
                                offers,
                                requestedOutbound
                        );

                    } else {

                        saveHistoryWithoutDrawing(
                                offers,
                                requestedOutbound
                        );

                        showStoredWeek();
                    }

                    if (offers.isEmpty()) {

                        resultText.setText(
                                "Свежих цен Победы нет"
                        );

                        statusText.setText(
                                "Обновлено "
                                        + formatTime(
                                        getLastUpdate(
                                                requestedOutbound
                                        )
                                )
                        );

                    } else {

                        showSummary(
                                offers,
                                requestedFrom,
                                requestedTo,
                                requestedOutbound
                        );
                    }
                });

            } catch (Exception e) {

                runOnUiThread(() -> {

                    setLoadingState(
                            false
                    );

                    resultText.setText(
                            "Ошибка обновления"
                    );

                    statusText.setText(
                            e.getMessage() == null
                                    ? e.toString()
                                    : e.getMessage()
                    );
                });
            }

        }).start();
    }

    private void setLoadingState(
            boolean loading
    ) {

        requestRunning =
                loading;

        progress.setVisibility(
                loading
                        ? View.VISIBLE
                        : View.GONE
        );

        refreshButton.setEnabled(
                !loading
        );

        refreshButton.setText(
                loading
                        ? "ОБНОВЛЯЮ…"
                        : "ОБНОВИТЬ РАДАР"
        );

        outButton.setEnabled(
                !loading
        );

        backButton.setEnabled(
                !loading
        );

        fromButton.setEnabled(
                !loading
        );

        toButton.setEnabled(
                !loading
        );
    }

    private void showSummary(
            List<Offer> offers,
            LocalDate requestedFrom,
            LocalDate requestedTo,
            boolean requestedOutbound
    ) {

        int min =
                Integer.MAX_VALUE;

        Set<LocalDate> minDates =
                new LinkedHashSet<>();

        Set<LocalDate> uniqueDates =
                new LinkedHashSet<>();

        for (Offer offer : offers) {

            uniqueDates.add(
                    offer.date
            );

            if (offer.price < min) {

                min =
                        offer.price;

                minDates.clear();

                minDates.add(
                        offer.date
                );

            } else if (
                    offer.price == min
            ) {

                minDates.add(
                        offer.date
                );
            }
        }

        resultText.setText(
                "Минимум: "
                        + formatPrice(
                        min
                )
                        + " · "
                        + formatMinDates(
                        minDates
                )
        );

        statusText.setText(
                "Обновлено "
                        + formatTime(
                        getLastUpdate(
                                requestedOutbound
                        )
                )
                        + " · "
                        + uniqueDates.size()
                        + " дат"
        );
    }

    private String formatMinDates(
            Set<LocalDate> dates
    ) {

        if (dates == null
                || dates.isEmpty()) {

            return "";
        }

        StringBuilder builder =
                new StringBuilder();

        int index = 0;

        for (LocalDate date : dates) {

            if (index > 0) {

                builder.append(
                        ", "
                );
            }

            if (dates.size() == 1) {

                builder.append(
                        date.format(
                                uiDate
                        )
                );

            } else {

                builder.append(
                        date.format(
                                shortUiDate
                        )
                );
            }

            index++;
        }

        return builder.toString();
    }

    private void saveHistoryWithoutDrawing(
            List<Offer> offers,
            boolean historyOutbound
    ) {

        Map<LocalDate, Integer> currentPrices =
                getBestPricesByDate(
                        offers
                );

        for (Map.Entry<LocalDate, Integer> entry
                : currentPrices.entrySet()) {

            savePriceHistory(
                    entry.getKey(),
                    entry.getValue(),
                    historyOutbound
            );
        }
    }

    // ============================================================
    // API — ОСТАВЛЕН ТОТ ЖЕ РАБОЧИЙ
    // ============================================================

    private List<Offer> requestEntireRange(
            String origin,
            String destination,
            LocalDate from,
            LocalDate to,
            String token
    ) throws Exception {

        List<Offer> result =
                new ArrayList<>();

        YearMonth month =
                YearMonth.from(
                        from
                );

        YearMonth lastMonth =
                YearMonth.from(
                        to
                );

        while (!month.isAfter(
                lastMonth
        )) {

            result.addAll(
                    requestMonth(
                            origin,
                            destination,
                            month,
                            from,
                            to,
                            token
                    )
            );

            month =
                    month.plusMonths(1);
        }

        Map<String, Offer> unique =
                new LinkedHashMap<>();

        for (Offer offer : result) {

            String key =
                    offer.date
                            + "|"
                            + offer.flight
                            + "|"
                            + offer.price;

            unique.put(
                    key,
                    offer
            );
        }

        result =
                new ArrayList<>(
                        unique.values()
                );

        result.sort(
                Comparator
                        .comparing(
                                (Offer o) ->
                                        o.date
                        )
                        .thenComparingInt(
                                o ->
                                        o.price
                        )
        );

        return result;
    }

    private List<Offer> requestMonth(
            String origin,
            String destination,
            YearMonth month,
            LocalDate from,
            LocalDate to,
            String token
    ) throws Exception {

        String url =
                "https://api.travelpayouts.com"
                        + "/aviasales/v3/prices_for_dates"
                        + "?origin="
                        + encode(
                                origin
                        )
                        + "&destination="
                        + encode(
                                destination
                        )
                        + "&departure_at="
                        + encode(
                                month.format(
                                        monthParam
                                )
                        )
                        + "&one_way=true"
                        + "&direct=true"
                        + "&unique=false"
                        + "&sorting=price"
                        + "&currency=rub"
                        + "&market=ru"
                        + "&limit=1000"
                        + "&page=1"
                        + "&token="
                        + encode(
                                token
                        );

        JSONObject json =
                getJson(
                        url
                );

        JSONArray data =
                json.optJSONArray(
                        "data"
                );

        List<Offer> result =
                new ArrayList<>();

        if (data == null) {

            return result;
        }

        for (int i = 0;
             i < data.length();
             i++) {

            JSONObject item =
                    data.optJSONObject(
                            i
                    );

            if (item == null) {

                continue;
            }

            String airline =
                    item.optString(
                            "airline",
                            ""
                    );

            if (!"DP".equalsIgnoreCase(
                    airline
            )) {

                continue;
            }

            int transfers =
                    item.optInt(
                            "transfers",
                            0
                    );

            if (transfers != 0) {

                continue;
            }

            LocalDate date =
                    parseDepartureDate(
                            item.optString(
                                    "departure_at",
                                    ""
                            )
                    );

            if (date == null) {

                continue;
            }

            if (date.isBefore(
                    from
            )
                    || date.isAfter(
                    to
            )) {

                continue;
            }

            int price =
                    item.optInt(
                            "price",
                            -1
                    );

            if (price <= 0) {

                continue;
            }

            result.add(
                    new Offer(
                            date,
                            price,
                            item.optString(
                                    "flight_number",
                                    ""
                            ),
                            item.optString(
                                    "link",
                                    ""
                            )
                    )
            );
        }

        return result;
    }

    private JSONObject getJson(
            String urlString
    ) throws Exception {

        HttpURLConnection connection =
                (HttpURLConnection)
                        new URL(
                                urlString
                        ).openConnection();

        connection.setRequestMethod(
                "GET"
        );

        connection.setConnectTimeout(
                15000
        );

        connection.setReadTimeout(
                25000
        );

        connection.setRequestProperty(
                "Accept",
                "application/json"
        );

        connection.setRequestProperty(
                "User-Agent",
                "PobedaRadar/0.8"
        );

        int code =
                connection.getResponseCode();

        InputStream stream =
                code >= 200
                        && code < 300
                        ? connection.getInputStream()
                        : connection.getErrorStream();

        String response =
                readStream(
                        stream
                );

        connection.disconnect();

        if (code == 401
                || code == 403) {

            throw new Exception(
                    "Travelpayouts отклонил токен"
            );
        }

        if (code == 429) {

            throw new Exception(
                    "Слишком много запросов. Повтори позже."
            );
        }

        if (code < 200
                || code >= 300) {

            throw new Exception(
                    "Travelpayouts HTTP "
                            + code
            );
        }

        JSONObject json =
                new JSONObject(
                        response
                );

        if (!json.optBoolean(
                "success",
                true
        )) {

            throw new Exception(
                    "Travelpayouts вернул ошибку"
            );
        }

        return json;
    }

    // ============================================================
    // UTIL
    // ============================================================

    private String formatTime(
            long millis
    ) {

        try {

            return Instant
                    .ofEpochMilli(
                            millis
                    )
                    .atZone(
                            ZoneId.systemDefault()
                    )
                    .toLocalTime()
                    .format(
                            timeFormat
                    );

        } catch (Exception e) {

            return "";
        }
    }

    private String readStream(
            InputStream stream
    ) throws Exception {

        if (stream == null) {

            return "";
        }

        BufferedReader reader =
                new BufferedReader(
                        new InputStreamReader(
                                stream,
                                StandardCharsets.UTF_8
                        )
                );

        StringBuilder builder =
                new StringBuilder();

        String line;

        while ((line =
                reader.readLine())
                != null) {

            builder.append(
                    line
            );
        }

        reader.close();

        return builder.toString();
    }

    private LocalDate parseDepartureDate(
            String value
    ) {

        if (value == null
                || value.length() < 10) {

            return null;
        }

        try {

            return OffsetDateTime
                    .parse(
                            value
                    )
                    .toLocalDate();

        } catch (Exception ignored) {
        }

        try {

            return LocalDate.parse(
                    value.substring(
                            0,
                            10
                    )
            );

        } catch (Exception ignored) {
        }

        return null;
    }

    private void styleDirection(
            Button button,
            boolean selected
    ) {

        GradientDrawable background =
                new GradientDrawable();

        background.setCornerRadius(
                dp(5)
        );

        background.setColor(
                selected
                        ? Color.WHITE
                        : LIGHT_GREY
        );

        if (selected) {

            background.setStroke(
                    dp(3),
                    RED
            );

        } else {

            background.setStroke(
                    dp(1),
                    Color.rgb(
                            205,
                            205,
                            205
                    )
            );
        }

        button.setBackground(
                background
        );

        button.setTextColor(
                DARK
        );

        button.setAlpha(
                1.0f
        );
    }

    private TextView makeText(
            String value,
            int size,
            boolean bold
    ) {

        TextView view =
                new TextView(this);

        view.setText(
                value
        );

        view.setTextSize(
                size
        );

        view.setTextColor(
                DARK
        );

        view.setGravity(
                Gravity.CENTER_VERTICAL
        );

        if (bold) {

            view.setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
            );
        }

        return view;
    }

    private Button makeButton(
            String value,
            int size
    ) {

        Button button =
                new Button(this);

        button.setText(
                value
        );

        button.setTextSize(
                size
        );

        button.setAllCaps(
                false
        );

        button.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );

        button.setPadding(
                dp(4),
                0,
                dp(4),
                0
        );

        return button;
    }

    private Button makeSmallButton(
            String value
    ) {

        Button button =
                new Button(this);

        button.setText(
                value
        );

        button.setTextSize(
                12
        );

        button.setAllCaps(
                false
        );

        button.setPadding(
                dp(12),
                0,
                dp(12),
                0
        );

        return button;
    }

    private String formatPrice(
            int value
    ) {

        return String.format(
                        new Locale("ru"),
                        "%,d ₽",
                        value
                )
                .replace(
                        ',',
                        ' '
                );
    }

    private String formatPriceCompact(
            int value
    ) {

        return String.format(
                        new Locale("ru"),
                        "%,d",
                        value
                )
                .replace(
                        ',',
                        ' '
                );
    }

    private String formatShortNumber(
            int value
    ) {

        return String.format(
                        new Locale("ru"),
                        "%,d",
                        value
                )
                .replace(
                        ',',
                        ' '
                );
    }

    private String encode(
            String value
    ) throws Exception {

        return URLEncoder.encode(
                value,
                "UTF-8"
        );
    }

    private int dp(
            int value
    ) {

        return (int) (
                value
                        * getResources()
                        .getDisplayMetrics()
                        .density
                        + 0.5f
        );
    }

    // ============================================================
    // DATA
    // ============================================================

    private static class Offer {

        final LocalDate date;
        final int price;
        final String flight;
        final String link;

        Offer(
                LocalDate date,
                int price,
                String flight,
                String link
        ) {

            this.date = date;
            this.price = price;
            this.flight = flight;
            this.link = link;
        }
    }
}
