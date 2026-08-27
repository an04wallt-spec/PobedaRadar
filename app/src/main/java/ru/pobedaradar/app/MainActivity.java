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
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    private static final String PREFS = "pobeda_radar";
    private static final String TOKEN_KEY = "travelpayouts_token";
    private static final String DATE_FROM_KEY = "date_from";
    private static final String DATE_TO_KEY = "date_to";

    private static final int RED = Color.rgb(210, 30, 30);
    private static final int GREY = Color.rgb(105, 105, 105);
    private static final int LIGHT_GREY = Color.rgb(236, 236, 236);
    private static final int DARK = Color.rgb(28, 28, 28);

    private final DateTimeFormatter uiDate =
            DateTimeFormatter.ofPattern("dd.MM.yyyy", new Locale("ru"));

    private final DateTimeFormatter weekDate =
            DateTimeFormatter.ofPattern("dd.MM", new Locale("ru"));

    private final DateTimeFormatter monthParam =
            DateTimeFormatter.ofPattern("yyyy-MM");

    private SharedPreferences prefs;

    private boolean outbound = true;

    private LocalDate rangeFrom;
    private LocalDate rangeTo;

    private LinearLayout root;
    private LinearLayout weekBox;
    private LinearLayout tokenBlock;

    private Button outButton;
    private Button backButton;
    private Button fromButton;
    private Button toButton;

    private EditText tokenInput;

    private TextView resultText;
    private TextView statusText;

    private ProgressBar progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        restoreDates();
        buildInterface();
        refreshInterface();
    }

    /*
     * ============================================================
     * СОХРАНЕНИЕ ДАТ
     * ============================================================
     */

    private void restoreDates() {

        LocalDate today = LocalDate.now();

        try {
            rangeFrom = LocalDate.parse(
                    prefs.getString(
                            DATE_FROM_KEY,
                            today.toString()
                    )
            );
        } catch (Exception e) {
            rangeFrom = today;
        }

        try {
            rangeTo = LocalDate.parse(
                    prefs.getString(
                            DATE_TO_KEY,
                            today.plusDays(30).toString()
                    )
            );
        } catch (Exception e) {
            rangeTo = today.plusDays(30);
        }

        if (rangeFrom.isBefore(today)) {
            rangeFrom = today;
        }

        if (rangeTo.isBefore(rangeFrom)) {
            rangeTo = rangeFrom.plusDays(30);
        }

        saveDates();
    }

    private void saveDates() {

        prefs.edit()
                .putString(DATE_FROM_KEY, rangeFrom.toString())
                .putString(DATE_TO_KEY, rangeTo.toString())
                .apply();
    }

    /*
     * ============================================================
     * ИНТЕРФЕЙС
     * ============================================================
     */

    private void buildInterface() {

        root = new LinearLayout(this);

        root.setOrientation(LinearLayout.VERTICAL);

        /*
         * Никакого ScrollView.
         *
         * Верхняя строка опущена ещё примерно на 2 мм.
         */
        root.setPadding(
                dp(16),
                dp(38),
                dp(16),
                dp(10)
        );

        /*
         * Заголовок.
         */
        TextView title = makeText(
                "Москва ⇄ Газипаша · Победа (DP)",
                16,
                true
        );

        title.setTextColor(GREY);
        title.setGravity(Gravity.CENTER);
        title.setSingleLine(true);

        LinearLayout.LayoutParams titleLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(26)
                );

        root.addView(title, titleLp);

        /*
         * Направление.
         */
        LinearLayout directionRow =
                new LinearLayout(this);

        directionRow.setOrientation(
                LinearLayout.HORIZONTAL
        );

        outButton =
                makeButton("МОСКВА → GZP", 14);

        backButton =
                makeButton("GZP → МОСКВА", 14);

        directionRow.addView(
                outButton,
                new LinearLayout.LayoutParams(
                        0,
                        dp(46),
                        1
                )
        );

        View gap1 = new View(this);

        directionRow.addView(
                gap1,
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

        directionLp.topMargin = dp(4);

        root.addView(directionRow, directionLp);

        outButton.setOnClickListener(v -> {
            outbound = true;
            refreshInterface();
        });

        backButton.setOnClickListener(v -> {
            outbound = false;
            refreshInterface();
        });

        /*
         * Ближайшие даты.
         */
        TextView weekTitle =
                makeText(
                        "Ближайшие даты",
                        13,
                        true
                );

        weekTitle.setTextColor(GREY);

        LinearLayout.LayoutParams weekTitleLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(25)
                );

        weekTitleLp.topMargin = dp(7);

        root.addView(
                weekTitle,
                weekTitleLp
        );

        weekBox =
                new LinearLayout(this);

        weekBox.setOrientation(
                LinearLayout.VERTICAL
        );

        /*
         * Ровно 7 строк × 23 dp.
         */
        root.addView(
                weekBox,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(161)
                )
        );

        /*
         * Диапазон.
         */
        TextView datesCaption =
                makeText(
                        "Диапазон дат",
                        14,
                        true
                );

        LinearLayout.LayoutParams captionLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(23)
                );

        captionLp.topMargin = dp(5);

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
                makeButton("", 13);

        toButton =
                makeButton("", 13);

        datesRow.addView(
                fromButton,
                new LinearLayout.LayoutParams(
                        0,
                        dp(44),
                        1
                )
        );

        View gap2 = new View(this);

        datesRow.addView(
                gap2,
                new LinearLayout.LayoutParams(
                        dp(8),
                        1
                )
        );

        datesRow.addView(
                toButton,
                new LinearLayout.LayoutParams(
                        0,
                        dp(44),
                        1
                )
        );

        root.addView(
                datesRow,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(44)
                )
        );

        fromButton.setOnClickListener(
                v -> pickDate(true)
        );

        toButton.setOnClickListener(
                v -> pickDate(false)
        );

        /*
         * Токен.
         * После сохранения этот блок полностью исчезает.
         */
        tokenBlock =
                new LinearLayout(this);

        tokenBlock.setOrientation(
                LinearLayout.VERTICAL
        );

        tokenInput =
                new EditText(this);

        tokenInput.setTextSize(14);
        tokenInput.setHint("Travelpayouts token");
        tokenInput.setSingleLine(true);

        tokenInput.setInputType(
                InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_PASSWORD
        );

        tokenInput.setText(
                prefs.getString(
                        TOKEN_KEY,
                        ""
                )
        );

        tokenBlock.addView(
                tokenInput,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(40)
                )
        );

        LinearLayout.LayoutParams tokenLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(40)
                );

        tokenLp.topMargin = dp(5);

        root.addView(
                tokenBlock,
                tokenLp
        );

        if (!prefs.getString(
                TOKEN_KEY,
                ""
        ).isEmpty()) {

            tokenBlock.setVisibility(
                    View.GONE
            );
        }

        /*
         * Обновить радар.
         */
        Button refreshButton =
                makeButton(
                        "ОБНОВИТЬ РАДАР",
                        15
                );

        LinearLayout.LayoutParams refreshLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(46)
                );

        refreshLp.topMargin = dp(6);

        root.addView(
                refreshButton,
                refreshLp
        );

        refreshButton.setOnClickListener(
                v -> loadRadar()
        );

        /*
         * Статус.
         */
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

        resultLp.topMargin = dp(4);

        root.addView(
                resultText,
                resultLp
        );

        statusText =
                makeText(
                        "",
                        12,
                        false
                );

        statusText.setTextColor(GREY);
        statusText.setGravity(Gravity.CENTER);
        statusText.setMaxLines(2);

        root.addView(
                statusText,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(34)
                )
        );

        /*
         * Индикатор.
         */
        progress =
                new ProgressBar(this);

        progress.setVisibility(
                View.GONE
        );

        LinearLayout.LayoutParams progressLp =
                new LinearLayout.LayoutParams(
                        dp(26),
                        dp(26)
                );

        progressLp.gravity =
                Gravity.CENTER_HORIZONTAL;

        root.addView(
                progress,
                progressLp
        );

        /*
         * Победа.
         */
        Button pobedaButton =
                makeSmallButton(
                        "Проверить на сайте Победы"
                );

        LinearLayout.LayoutParams pobedaLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dp(38)
                );

        pobedaLp.gravity =
                Gravity.CENTER_HORIZONTAL;

        root.addView(
                pobedaButton,
                pobedaLp
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

                startActivity(intent);

            } catch (Exception e) {

                Toast.makeText(
                        this,
                        "Не удалось открыть сайт Победы",
                        Toast.LENGTH_SHORT
                ).show();
            }
        });

        setContentView(root);
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
                        + rangeFrom.format(uiDate)
        );

        toButton.setText(
                "ПО  "
                        + rangeTo.format(uiDate)
        );

        showBlankWeek();

        resultText.setText("");

        statusText.setText(
                outbound
                        ? "Москва → Газипаша"
                        : "Газипаша → Москва"
        );
    }

    /*
     * ============================================================
     * БЛИЖАЙШИЕ 7 ДНЕЙ
     * ============================================================
     */

    private void showBlankWeek() {

        weekBox.removeAllViews();

        for (int i = 0; i < 7; i++) {

            LocalDate day =
                    rangeFrom.plusDays(i);

            if (day.isAfter(rangeTo)) {

                addWeekRow(
                        day,
                        ""
                );

            } else {

                addWeekRow(
                        day,
                        "—"
                );
            }
        }
    }

    private void showWeek(
            List<Offer> offers
    ) {

        weekBox.removeAllViews();

        Map<LocalDate, Integer> best =
                new LinkedHashMap<>();

        for (Offer offer : offers) {

            Integer old =
                    best.get(offer.date);

            if (old == null
                    || offer.price < old) {

                best.put(
                        offer.date,
                        offer.price
                );
            }
        }

        for (int i = 0; i < 7; i++) {

            LocalDate day =
                    rangeFrom.plusDays(i);

            if (day.isAfter(rangeTo)) {

                addWeekRow(
                        day,
                        ""
                );

                continue;
            }

            Integer price =
                    best.get(day);

            addWeekRow(
                    day,
                    price == null
                            ? "нет данных"
                            : formatPrice(price)
            );
        }
    }

    private void addWeekRow(
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

        TextView d =
                makeText(
                        date.format(weekDate),
                        13,
                        false
                );

        d.setTextColor(GREY);

        TextView p =
                makeText(
                        price,
                        13,
                        true
                );

        p.setTextColor(
                price.equals("нет данных")
                        ? GREY
                        : DARK
        );

        p.setGravity(
                Gravity.END
                        | Gravity.CENTER_VERTICAL
        );

        row.addView(
                d,
                new LinearLayout.LayoutParams(
                        0,
                        dp(23),
                        1
                )
        );

        row.addView(
                p,
                new LinearLayout.LayoutParams(
                        0,
                        dp(23),
                        1
                )
        );

        weekBox.addView(row);
    }

    /*
     * ============================================================
     * ВЫБОР ДАТ
     * ============================================================
     */

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

    /*
     * ============================================================
     * ЗАГРУЗКА ЦЕН
     * ============================================================
     */

    private void loadRadar() {

        String savedToken =
                prefs.getString(
                        TOKEN_KEY,
                        ""
                );

        String token =
                savedToken;

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

        final String origin =
                outbound
                        ? "MOW"
                        : "GZP";

        final String destination =
                outbound
                        ? "GZP"
                        : "MOW";

        saveDates();

        progress.setVisibility(
                View.VISIBLE
        );

        resultText.setText(
                "Ищу цены Победы…"
        );

        statusText.setText(
                rangeFrom.format(uiDate)
                        + " — "
                        + rangeTo.format(uiDate)
        );

        new Thread(() -> {

            try {

                List<Offer> offers =
                        requestEntireRange(
                                origin,
                                destination,
                                rangeFrom,
                                rangeTo,
                                finalToken
                        );

                runOnUiThread(() -> {

                    progress.setVisibility(
                            View.GONE
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

                    showWeek(offers);

                    if (offers.isEmpty()) {

                        resultText.setText(
                                "Цены Победы не найдены"
                        );

                        statusText.setText(
                                "Рейсы могут быть — в API сейчас нет цены DP"
                        );

                    } else {

                        int min =
                                Integer.MAX_VALUE;

                        for (Offer offer : offers) {

                            min =
                                    Math.min(
                                            min,
                                            offer.price
                                    );
                        }

                        resultText.setText(
                                "Минимум "
                                        + formatPrice(min)
                        );

                        statusText.setText(
                                "Найдено цен DP: "
                                        + offers.size()
                        );
                    }
                });

            } catch (Exception e) {

                runOnUiThread(() -> {

                    progress.setVisibility(
                            View.GONE
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

    /*
     * Делим выбранный диапазон на месяцы.
     *
     * Например:
     * 25.09 — 10.10
     *
     * = отдельный запрос за сентябрь
     * + отдельный запрос за октябрь.
     */
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
                YearMonth.from(from);

        YearMonth lastMonth =
                YearMonth.from(to);

        while (!month.isAfter(lastMonth)) {

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

        /*
         * Удаляем дубли:
         * одна дата + один номер рейса + одна цена.
         */
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

    /*
     * Основной запрос:
     *
     * /aviasales/v3/prices_for_dates
     *
     * Берём много результатов за конкретный месяц,
     * потом уже сами оставляем только DP.
     */
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
                        + encode(origin)
                        + "&destination="
                        + encode(destination)
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
                        + encode(token);

        JSONObject json =
                getJson(url);

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
                    data.optJSONObject(i);

            if (item == null) {
                continue;
            }

            /*
             * Нас интересует только Победа.
             */
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

            /*
             * На всякий случай ещё фильтруем
             * прямой перелёт.
             */
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

            if (date.isBefore(from)
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

            String flight =
                    item.optString(
                            "flight_number",
                            ""
                    );

            String link =
                    item.optString(
                            "link",
                            ""
                    );

            result.add(
                    new Offer(
                            date,
                            price,
                            flight,
                            link
                    )
            );
        }

        return result;
    }

    /*
     * ============================================================
     * HTTP
     * ============================================================
     */

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
                "PobedaRadar/2.0"
        );

        int code =
                connection.getResponseCode();

        InputStream stream =
                code >= 200
                        && code < 300
                        ? connection.getInputStream()
                        : connection.getErrorStream();

        String response =
                readStream(stream);

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

            builder.append(line);
        }

        reader.close();

        return builder.toString();
    }

    /*
     * ============================================================
     * UTIL
     * ============================================================
     */

    private LocalDate parseDepartureDate(
            String value
    ) {

        if (value == null
                || value.length() < 10) {

            return null;
        }

        try {

            return OffsetDateTime
                    .parse(value)
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

        button.setEnabled(
                true
        );
    }

    private TextView makeText(
            String value,
            int size,
            boolean bold
    ) {

        TextView view =
                new TextView(this);

        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(DARK);
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

        button.setText(value);
        button.setTextSize(size);

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

        button.setText(value);

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
