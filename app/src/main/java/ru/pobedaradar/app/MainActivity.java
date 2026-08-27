package ru.pobedaradar.app;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String PREFS = "pobeda_radar";

    private static final String TOKEN_KEY = "travelpayouts_token";
    private static final String DATE_FROM_KEY = "date_from";
    private static final String DATE_TO_KEY = "date_to";

    private static final int RED = Color.rgb(210, 32, 32);
    private static final int GREY_TEXT = Color.rgb(105, 105, 105);
    private static final int BUTTON_BG = Color.rgb(235, 235, 235);
    private static final int BUTTON_ACTIVE_BG = Color.rgb(248, 248, 248);

    private final DateTimeFormatter uiDate =
            DateTimeFormatter.ofPattern("dd.MM.yyyy", new Locale("ru"));

    private final DateTimeFormatter shortDate =
            DateTimeFormatter.ofPattern("dd.MM", new Locale("ru"));

    private LinearLayout root;
    private LinearLayout resultsBox;
    private LinearLayout nearestBox;
    private LinearLayout tokenBlock;
    private LinearLayout tokenSavedBlock;

    private TextView summary;
    private TextView status;

    private ProgressBar progress;
    private EditText tokenInput;

    private Button outBtn;
    private Button backBtn;
    private Button fromBtn;
    private Button toBtn;

    private boolean outbound = true;

    /*
     * Один общий диапазон для обоих направлений.
     * Переключение направления его больше не меняет.
     */
    private LocalDate rangeFrom;
    private LocalDate rangeTo;

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        loadSavedDates();

        buildUi();
        updateDirectionUi();
    }

    private void loadSavedDates() {

        LocalDate today = LocalDate.now();

        String savedFrom =
                prefs.getString(DATE_FROM_KEY, "");

        String savedTo =
                prefs.getString(DATE_TO_KEY, "");

        try {
            rangeFrom = savedFrom.isEmpty()
                    ? today
                    : LocalDate.parse(savedFrom);
        } catch (Exception e) {
            rangeFrom = today;
        }

        try {
            rangeTo = savedTo.isEmpty()
                    ? today.plusDays(30)
                    : LocalDate.parse(savedTo);
        } catch (Exception e) {
            rangeTo = today.plusDays(30);
        }

        if (rangeFrom.isBefore(today)) {
            rangeFrom = today;
        }

        if (rangeTo.isBefore(rangeFrom)) {
            rangeTo = rangeFrom.plusDays(30);
        }
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

    private void buildUi() {

        ScrollView scroll = new ScrollView(this);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        /*
         * Ещё примерно на 2 мм ниже,
         * чем в предыдущей версии.
         */
        root.setPadding(
                dp(18),
                dp(30),
                dp(18),
                dp(28)
        );

        scroll.addView(root);

        /*
         * Верхняя строка.
         */
        TextView routeTitle =
                text(
                        "Москва ⇄ Газипаша · Победа (DP)",
                        17,
                        true
                );

        routeTitle.setTextColor(GREY_TEXT);
        routeTitle.setSingleLine(true);
        routeTitle.setGravity(Gravity.CENTER);

        LinearLayout.LayoutParams titleParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        titleParams.bottomMargin = dp(16);

        root.addView(
                routeTitle,
                titleParams
        );

        /*
         * Направления.
         */
        LinearLayout directionRow =
                new LinearLayout(this);

        directionRow.setOrientation(
                LinearLayout.HORIZONTAL
        );

        outBtn =
                actionButton("МОСКВА → GZP");

        backBtn =
                actionButton("GZP → МОСКВА");

        directionRow.addView(
                outBtn,
                new LinearLayout.LayoutParams(
                        0,
                        dp(58),
                        1
                )
        );

        View directionSpacer =
                new View(this);

        directionRow.addView(
                directionSpacer,
                new LinearLayout.LayoutParams(
                        dp(10),
                        1
                )
        );

        directionRow.addView(
                backBtn,
                new LinearLayout.LayoutParams(
                        0,
                        dp(58),
                        1
                )
        );

        root.addView(directionRow);

        outBtn.setOnClickListener(v -> {

            outbound = true;

            /*
             * ВАЖНО:
             * даты здесь НЕ меняем.
             */
            updateDirectionUi();
        });

        backBtn.setOnClickListener(v -> {

            outbound = false;

            /*
             * ВАЖНО:
             * даты здесь НЕ меняем.
             */
            updateDirectionUi();
        });

        /*
         * Ближайшие даты.
         */
        TextView nearestTitle =
                text(
                        "Ближайшие даты",
                        14,
                        true
                );

        nearestTitle.setTextColor(GREY_TEXT);
        nearestTitle.setPadding(
                0,
                dp(15),
                0,
                dp(5)
        );

        root.addView(nearestTitle);

        nearestBox =
                new LinearLayout(this);

        nearestBox.setOrientation(
                LinearLayout.VERTICAL
        );

        root.addView(nearestBox);

        showEmptyNearestWeek();

        /*
         * Диапазон дат.
         */
        TextView rangeTitle =
                text(
                        "Диапазон дат",
                        19,
                        true
                );

        rangeTitle.setPadding(
                0,
                dp(18),
                0,
                dp(7)
        );

        root.addView(rangeTitle);

        LinearLayout dateRow =
                new LinearLayout(this);

        dateRow.setOrientation(
                LinearLayout.HORIZONTAL
        );

        fromBtn = actionButton("");
        toBtn = actionButton("");

        dateRow.addView(
                fromBtn,
                new LinearLayout.LayoutParams(
                        0,
                        dp(60),
                        1
                )
        );

        View dateSpacer =
                new View(this);

        dateRow.addView(
                dateSpacer,
                new LinearLayout.LayoutParams(
                        dp(10),
                        1
                )
        );

        dateRow.addView(
                toBtn,
                new LinearLayout.LayoutParams(
                        0,
                        dp(60),
                        1
                )
        );

        root.addView(dateRow);

        fromBtn.setOnClickListener(
                v -> pickDate(true)
        );

        toBtn.setOnClickListener(
                v -> pickDate(false)
        );

        /*
         * TOKEN.
         */
        tokenBlock =
                new LinearLayout(this);

        tokenBlock.setOrientation(
                LinearLayout.VERTICAL
        );

        TextView tokenTitle =
                text(
                        "Travelpayouts token",
                        16,
                        true
                );

        tokenTitle.setPadding(
                0,
                dp(18),
                0,
                dp(5)
        );

        tokenBlock.addView(tokenTitle);

        tokenInput =
                new EditText(this);

        tokenInput.setTextSize(16);
        tokenInput.setHint("Вставьте токен");
        tokenInput.setSingleLine(true);

        tokenInput.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );

        String savedToken =
                prefs.getString(
                        TOKEN_KEY,
                        ""
                );

        tokenInput.setText(savedToken);

        tokenBlock.addView(
                tokenInput,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(52)
                )
        );

        root.addView(tokenBlock);

        /*
         * Компактный блок,
         * когда токен уже сохранён.
         */
        tokenSavedBlock =
                new LinearLayout(this);

        tokenSavedBlock.setOrientation(
                LinearLayout.HORIZONTAL
        );

        tokenSavedBlock.setGravity(
                Gravity.CENTER_VERTICAL
        );

        tokenSavedBlock.setPadding(
                0,
                dp(13),
                0,
                0
        );

        TextView tokenSavedText =
                text(
                        "Travelpayouts подключён",
                        13,
                        false
                );

        tokenSavedText.setTextColor(
                GREY_TEXT
        );

        tokenSavedBlock.addView(
                tokenSavedText,
                new LinearLayout.LayoutParams(
                        0,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        1
                )
        );

        Button changeToken =
                smallButton(
                        "Изменить токен"
                );

        changeToken.setOnClickListener(v -> {

            tokenSavedBlock.setVisibility(
                    View.GONE
            );

            tokenBlock.setVisibility(
                    View.VISIBLE
            );

            tokenInput.setText(
                    prefs.getString(
                            TOKEN_KEY,
                            ""
                    )
            );

            tokenInput.requestFocus();
        });

        tokenSavedBlock.addView(
                changeToken
        );

        root.addView(tokenSavedBlock);

        if (savedToken.isEmpty()) {

            tokenBlock.setVisibility(
                    View.VISIBLE
            );

            tokenSavedBlock.setVisibility(
                    View.GONE
            );

        } else {

            tokenBlock.setVisibility(
                    View.GONE
            );

            tokenSavedBlock.setVisibility(
                    View.VISIBLE
            );
        }

        /*
         * ОБНОВИТЬ РАДАР.
         */
        Button scan =
                actionButton(
                        "ОБНОВИТЬ РАДАР"
                );

        LinearLayout.LayoutParams scanParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(62)
                );

        scanParams.topMargin = dp(15);

        root.addView(
                scan,
                scanParams
        );

        scan.setOnClickListener(
                v -> refreshRadar()
        );

        /*
         * Проверка непосредственно
         * на сайте Победы.
         */
        Button pobedaSite =
                smallButton(
                        "Проверить на сайте Победы"
                );

        LinearLayout.LayoutParams siteParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dp(44)
                );

        siteParams.gravity =
                Gravity.CENTER_HORIZONTAL;

        siteParams.topMargin =
                dp(6);

        root.addView(
                pobedaSite,
                siteParams
        );

        pobedaSite.setOnClickListener(v -> {

            try {

                Intent browser =
                        new Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(
                                        "https://pobeda.aero/"
                                )
                        );

                startActivity(browser);

            } catch (Exception e) {

                Toast.makeText(
                        this,
                        "Не удалось открыть сайт Победы",
                        Toast.LENGTH_SHORT
                ).show();
            }
        });

        /*
         * Индикатор загрузки.
         */
        progress =
                new ProgressBar(this);

        progress.setVisibility(
                View.GONE
        );

        LinearLayout.LayoutParams progressParams =
                new LinearLayout.LayoutParams(
                        dp(40),
                        dp(40)
                );

        progressParams.gravity =
                Gravity.CENTER_HORIZONTAL;

        progressParams.topMargin =
                dp(8);

        root.addView(
                progress,
                progressParams
        );

        /*
         * Результат.
         */
        summary =
                text(
                        "Выберите направление и диапазон дат.",
                        17,
                        true
                );

        summary.setPadding(
                0,
                dp(16),
                0,
                dp(6)
        );

        root.addView(summary);

        status =
                text(
                        "",
                        14,
                        false
                );

        status.setTextColor(
                GREY_TEXT
        );

        root.addView(status);

        resultsBox =
                new LinearLayout(this);

        resultsBox.setOrientation(
                LinearLayout.VERTICAL
        );

        LinearLayout.LayoutParams resultsParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        resultsParams.topMargin =
                dp(10);

        root.addView(
                resultsBox,
                resultsParams
        );

        setContentView(scroll);
    }

    private void updateDirectionUi() {

        styleDirectionButton(
                outBtn,
                outbound
        );

        styleDirectionButton(
                backBtn,
                !outbound
        );

        /*
         * ВСЕГДА показываем
         * один и тот же выбранный диапазон.
         */
        fromBtn.setText(
                "С\n"
                        + rangeFrom.format(uiDate)
        );

        toBtn.setText(
                "ПО\n"
                        + rangeTo.format(uiDate)
        );

        showEmptyNearestWeek();

        summary.setText(
                "Диапазон: "
                        + rangeFrom.format(uiDate)
                        + " — "
                        + rangeTo.format(uiDate)
        );

        status.setText("");

        resultsBox.removeAllViews();
    }

    private void styleDirectionButton(
            Button button,
            boolean active
    ) {

        GradientDrawable bg =
                new GradientDrawable();

        bg.setCornerRadius(
                dp(5)
        );

        if (active) {

            bg.setColor(
                    BUTTON_ACTIVE_BG
            );

            bg.setStroke(
                    dp(3),
                    RED
            );

            button.setTextColor(
                    Color.rgb(
                            25,
                            25,
                            25
                    )
            );

        } else {

            bg.setColor(
                    BUTTON_BG
            );

            bg.setStroke(
                    dp(1),
                    Color.rgb(
                            205,
                            205,
                            205
                    )
            );

            button.setTextColor(
                    Color.rgb(
                            55,
                            55,
                            55
                    )
            );
        }

        button.setBackground(bg);
        button.setEnabled(true);
        button.setAlpha(1.0f);
    }

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

                            LocalDate chosen =
                                    LocalDate.of(
                                            year,
                                            month + 1,
                                            day
                                    );

                            if (start) {

                                rangeFrom =
                                        chosen;

                                if (rangeTo.isBefore(
                                        rangeFrom
                                )) {

                                    rangeTo =
                                            rangeFrom;
                                }

                            } else {

                                rangeTo =
                                        chosen;

                                if (rangeTo.isBefore(
                                        rangeFrom
                                )) {

                                    rangeFrom =
                                            rangeTo;
                                }
                            }

                            /*
                             * Сохраняем немедленно,
                             * а не после запроса.
                             */
                            saveDates();

                            updateDirectionUi();
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

    /*
     * 7 ближайших календарных дат
     * начиная с начала выбранного диапазона.
     */
    private void showEmptyNearestWeek() {

        nearestBox.removeAllViews();

        int days =
                getNearestDaysCount();

        for (int i = 0; i < days; i++) {

            LocalDate date =
                    rangeFrom.plusDays(i);

            addNearestRow(
                    date,
                    "—"
            );
        }
    }

    private int getNearestDaysCount() {

        long totalDays =
                java.time.temporal.ChronoUnit.DAYS
                        .between(
                                rangeFrom,
                                rangeTo
                        ) + 1;

        return (int) Math.min(
                7,
                Math.max(
                        1,
                        totalDays
                )
        );
    }

    private void showNearestWeek(
            List<Offer> offers
    ) {

        nearestBox.removeAllViews();

        int days =
                getNearestDaysCount();

        for (int i = 0; i < days; i++) {

            LocalDate date =
                    rangeFrom.plusDays(i);

            Integer bestPrice =
                    null;

            for (Offer offer : offers) {

                if (offer.date.equals(date)) {

                    if (bestPrice == null
                            || offer.price < bestPrice) {

                        bestPrice =
                                offer.price;
                    }
                }
            }

            addNearestRow(
                    date,
                    bestPrice == null
                            ? "нет данных"
                            : rub(bestPrice)
            );
        }
    }

    private void addNearestRow(
            LocalDate date,
            String price
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
                text(
                        date.format(shortDate),
                        14,
                        false
                );

        dateText.setTextColor(
                GREY_TEXT
        );

        TextView priceText =
                text(
                        price,
                        14,
                        true
                );

        priceText.setTextColor(
                GREY_TEXT
        );

        priceText.setGravity(
                Gravity.END
                        | Gravity.CENTER_VERTICAL
        );

        row.addView(
                dateText,
                new LinearLayout.LayoutParams(
                        0,
                        dp(25),
                        1
                )
        );

        row.addView(
                priceText,
                new LinearLayout.LayoutParams(
                        0,
                        dp(25),
                        1
                )
        );

        nearestBox.addView(row);
    }

    private void refreshRadar() {

        String token =
                prefs.getString(
                        TOKEN_KEY,
                        ""
                );

        /*
         * Если пользователь открыл
         * "Изменить токен",
         * используем новое значение.
         */
        if (tokenBlock.getVisibility()
                == View.VISIBLE) {

            String typedToken =
                    tokenInput
                            .getText()
                            .toString()
                            .trim();

            if (!typedToken.isEmpty()) {
                token = typedToken;
            }
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

            tokenSavedBlock.setVisibility(
                    View.GONE
            );

            return;
        }

        final String finalToken =
                token;

        /*
         * Ещё раз сохраняем выбранные даты
         * перед запросом.
         */
        saveDates();

        String origin =
                outbound
                        ? "MOW"
                        : "GZP";

        String destination =
                outbound
                        ? "GZP"
                        : "MOW";

        progress.setVisibility(
                View.VISIBLE
        );

        summary.setText(
                "Проверяю цены…"
        );

        status.setText(
                origin
                        + " → "
                        + destination
                        + " · "
                        + rangeFrom.format(uiDate)
                        + " — "
                        + rangeTo.format(uiDate)
        );

        resultsBox.removeAllViews();

        new Thread(() -> {

            try {

                List<Offer> offers =
                        loadOffers(
                                origin,
                                destination,
                                rangeFrom,
                                rangeTo,
                                finalToken
                        );

                runOnUiThread(() -> {

                    /*
                     * Успешный запрос —
                     * сохраняем токен.
                     */
                    prefs.edit()
                            .putString(
                                    TOKEN_KEY,
                                    finalToken
                            )
                            .apply();

                    tokenInput.setText(
                            finalToken
                    );

                    tokenBlock.setVisibility(
                            View.GONE
                    );

                    tokenSavedBlock.setVisibility(
                            View.VISIBLE
                    );

                    showOffers(
                            offers,
                            origin,
                            destination,
                            rangeFrom,
                            rangeTo
                    );
                });

            } catch (Exception e) {

                runOnUiThread(() -> {

                    progress.setVisibility(
                            View.GONE
                    );

                    summary.setText(
                            "Не удалось обновить радар"
                    );

                    status.setText(
                            e.getMessage() == null
                                    ? e.toString()
                                    : e.getMessage()
                    );

                    if (prefs.getString(
                            TOKEN_KEY,
                            ""
                    ).isEmpty()) {

                        tokenBlock.setVisibility(
                                View.VISIBLE
                        );

                        tokenSavedBlock.setVisibility(
                                View.GONE
                        );
                    }
                });
            }

        }).start();
    }

    private List<Offer> loadOffers(
            String origin,
            String destination,
            LocalDate from,
            LocalDate to,
            String token
    ) throws Exception {

        String requestUrl =
                "https://api.travelpayouts.com/aviasales/v3/get_special_offers"
                        + "?origin="
                        + enc(origin)
                        + "&destination="
                        + enc(destination)
                        + "&airline=DP"
                        + "&locale=ru"
                        + "&currency=rub"
                        + "&market=ru"
                        + "&token="
                        + enc(token);

        HttpURLConnection connection =
                (HttpURLConnection)
                        new URL(
                                requestUrl
                        ).openConnection();

        connection.setConnectTimeout(
                15000
        );

        connection.setReadTimeout(
                20000
        );

        connection.setRequestMethod(
                "GET"
        );

        connection.setRequestProperty(
                "Accept",
                "application/json"
        );

        connection.setRequestProperty(
                "User-Agent",
                "PobedaRadar/1.3"
        );

        int code =
                connection.getResponseCode();

        InputStream stream =
                code >= 200
                        && code < 300
                        ? connection.getInputStream()
                        : connection.getErrorStream();

        String body =
                readAll(stream);

        connection.disconnect();

        if (code == 401
                || code == 403) {

            throw new Exception(
                    "Travelpayouts отклонил токен"
            );
        }

        if (code == 429) {

            throw new Exception(
                    "Слишком много запросов. Повторите позже."
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
                new JSONObject(body);

        JSONArray data =
                json.optJSONArray(
                        "data"
                );

        List<Offer> offers =
                new ArrayList<>();

        if (data == null) {
            return offers;
        }

        for (int i = 0;
             i < data.length();
             i++) {

            JSONObject item =
                    data.optJSONObject(i);

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

            LocalDate date =
                    parseDate(
                            item.optString(
                                    "departure_at",
                                    ""
                            )
                    );

            if (date == null
                    || date.isBefore(from)
                    || date.isAfter(to)) {

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

            offers.add(
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

        offers.sort(
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

        return offers;
    }

    private void showOffers(
            List<Offer> offers,
            String origin,
            String destination,
            LocalDate from,
            LocalDate to
    ) {

        progress.setVisibility(
                View.GONE
        );

        showNearestWeek(
                offers
        );

        resultsBox.removeAllViews();

        if (offers.isEmpty()) {

            summary.setText(
                    "Ценовых данных пока нет"
            );

            status.setText(
                    "Это не означает отсутствие рейсов Победы. "
                            + "Travelpayouts не всегда содержит цену "
                            + "по каждой существующей дате."
            );

            return;
        }

        int min =
                Integer.MAX_VALUE;

        long total = 0;

        for (Offer offer : offers) {

            min =
                    Math.min(
                            min,
                            offer.price
                    );

            total +=
                    offer.price;
        }

        int avg =
                (int)
                        Math.round(
                                total
                                        / (double)
                                        offers.size()
                        );

        summary.setText(
                "Найдено: "
                        + offers.size()
                        + " · минимум "
                        + rub(min)
                        + " · средняя "
                        + rub(avg)
        );

        status.setText(
                origin
                        + " → "
                        + destination
                        + " · "
                        + from.format(uiDate)
                        + " — "
                        + to.format(uiDate)
        );

        for (Offer offer : offers) {

            addOfferCard(
                    offer,
                    avg,
                    offers.size()
            );
        }
    }

    private void addOfferCard(
            Offer offer,
            int avg,
            int count
    ) {

        LinearLayout card =
                new LinearLayout(this);

        card.setOrientation(
                LinearLayout.VERTICAL
        );

        card.setPadding(
                dp(15),
                dp(14),
                dp(15),
                dp(14)
        );

        GradientDrawable bg =
                new GradientDrawable();

        bg.setColor(
                Color.rgb(
                        247,
                        247,
                        247
                )
        );

        bg.setCornerRadius(
                dp(5)
        );

        card.setBackground(bg);

        TextView main =
                text(
                        offer.date.format(uiDate)
                                + "   "
                                + rub(offer.price),
                        21,
                        true
                );

        card.addView(main);

        String flightText =
                offer.flight == null
                        || offer.flight.isEmpty()
                        ? "DP"
                        : offer.flight.toUpperCase()
                        .startsWith("DP")
                        ? offer.flight
                        : "DP" + offer.flight;

        TextView info =
                text(
                        priceRating(
                                offer.price,
                                avg,
                                count
                        )
                                + " · "
                                + flightText,
                        14,
                        false
                );

        info.setTextColor(
                GREY_TEXT
        );

        info.setPadding(
                0,
                dp(6),
                0,
                0
        );

        card.addView(info);

        if (offer.link != null
                && !offer.link.isEmpty()) {

            card.setClickable(true);
            card.setFocusable(true);

            card.setOnClickListener(
                    v -> openOffer(
                            offer.link
                    )
            );
        }

        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        params.bottomMargin =
                dp(8);

        resultsBox.addView(
                card,
                params
        );
    }

    private void openOffer(
            String link
    ) {

        try {

            String fullLink =
                    link.startsWith("http://")
                            || link.startsWith("https://")
                            ? link
                            : "https://www.aviasales.ru"
                            + (link.startsWith("/")
                            ? link
                            : "/" + link);

            Intent browser =
                    new Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(fullLink)
                    );

            startActivity(browser);

        } catch (Exception e) {

            Toast.makeText(
                    this,
                    "Не удалось открыть предложение",
                    Toast.LENGTH_SHORT
            ).show();
        }
    }

    private String priceRating(
            int price,
            int avg,
            int count
    ) {

        if (count < 3) {
            return "цена найдена";
        }

        double ratio =
                price
                        / (double) avg;

        if (ratio <= 0.75) {
            return "очень низкая";
        }

        if (ratio <= 0.90) {
            return "низкая";
        }

        if (ratio <= 1.10) {
            return "обычная";
        }

        return "высокая";
    }

    private LocalDate parseDate(
            String value
    ) {

        if (value == null
                || value.isEmpty()) {

            return null;
        }

        try {

            return OffsetDateTime
                    .parse(value)
                    .toLocalDate();

        } catch (Exception ignored) {
        }

        try {

            if (value.length() >= 10) {

                return LocalDate.parse(
                        value.substring(
                                0,
                                10
                        )
                );
            }

        } catch (Exception ignored) {
        }

        return null;
    }

    private String readAll(
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

        StringBuilder result =
                new StringBuilder();

        String line;

        while ((line =
                reader.readLine())
                != null) {

            result.append(line);
        }

        reader.close();

        return result.toString();
    }

    private String enc(
            String value
    ) throws Exception {

        return URLEncoder.encode(
                value,
                "UTF-8"
        );
    }

    private TextView text(
            String value,
            int size,
            boolean bold
    ) {

        TextView view =
                new TextView(this);

        view.setText(value);
        view.setTextSize(size);

        view.setTextColor(
                Color.rgb(
                        32,
                        32,
                        32
                )
        );

        if (bold) {

            view.setTypeface(
                    Typeface.DEFAULT,
                    Typeface.BOLD
            );
        }

        return view;
    }

    private Button actionButton(
            String value
    ) {

        Button button =
                new Button(this);

        button.setText(value);
        button.setTextSize(15);
        button.setAllCaps(false);

        button.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );

        return button;
    }

    private Button smallButton(
            String value
    ) {

        Button button =
                new Button(this);

        button.setText(value);
        button.setTextSize(13);
        button.setAllCaps(false);

        return button;
    }

    private String rub(
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
