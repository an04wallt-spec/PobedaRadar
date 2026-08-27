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

    private static final int RED = Color.rgb(210, 32, 32);
    private static final int GREY_TEXT = Color.rgb(105, 105, 105);
    private static final int BUTTON_BG = Color.rgb(235, 235, 235);

    private final DateTimeFormatter uiDate =
            DateTimeFormatter.ofPattern("dd.MM.yyyy", new Locale("ru"));

    private final DateTimeFormatter shortDate =
            DateTimeFormatter.ofPattern("dd.MM", new Locale("ru"));

    private LinearLayout root;
    private LinearLayout resultsBox;
    private LinearLayout nearestBox;
    private LinearLayout tokenBlock;

    private TextView summary;
    private TextView status;

    private ProgressBar progress;
    private EditText tokenInput;

    private Button outBtn;
    private Button backBtn;
    private Button fromBtn;
    private Button toBtn;

    private boolean outbound = true;

    private LocalDate outFrom;
    private LocalDate outTo;
    private LocalDate backFrom;
    private LocalDate backTo;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LocalDate today = LocalDate.now();

        outFrom = today;
        outTo = today.plusDays(30);

        backFrom = today;
        backTo = today.plusDays(30);

        buildUi();
        updateDirectionUi();
    }

    private void buildUi() {

        ScrollView scroll = new ScrollView(this);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        // Верхнюю строку опускаем примерно на 2 мм.
        root.setPadding(dp(18), dp(22), dp(18), dp(28));

        scroll.addView(root);

        // Один компактный серый заголовок.
        TextView routeTitle =
                text("Москва ⇄ Газипаша · Победа (DP)", 17, true);

        routeTitle.setTextColor(GREY_TEXT);
        routeTitle.setSingleLine(true);
        routeTitle.setPadding(0, dp(5), 0, dp(16));

        root.addView(routeTitle);

        // Направление.
        LinearLayout directionRow = new LinearLayout(this);
        directionRow.setOrientation(LinearLayout.HORIZONTAL);

        outBtn = actionButton("МОСКВА → GZP");
        backBtn = actionButton("GZP → МОСКВА");

        directionRow.addView(
                outBtn,
                new LinearLayout.LayoutParams(0, dp(58), 1)
        );

        View spacer = new View(this);
        directionRow.addView(
                spacer,
                new LinearLayout.LayoutParams(dp(10), 1)
        );

        directionRow.addView(
                backBtn,
                new LinearLayout.LayoutParams(0, dp(58), 1)
        );

        root.addView(directionRow);

        outBtn.setOnClickListener(v -> {
            outbound = true;
            updateDirectionUi();
        });

        backBtn.setOnClickListener(v -> {
            outbound = false;
            updateDirectionUi();
        });

        // Ближайшие даты.
        TextView nearestTitle =
                text("Ближайшие даты", 14, true);

        nearestTitle.setTextColor(GREY_TEXT);
        nearestTitle.setPadding(0, dp(15), 0, dp(5));

        root.addView(nearestTitle);

        nearestBox = new LinearLayout(this);
        nearestBox.setOrientation(LinearLayout.VERTICAL);

        root.addView(nearestBox);

        showEmptyNearestWeek();

        // Диапазон.
        TextView rangeTitle =
                text("Диапазон дат", 19, true);

        rangeTitle.setPadding(0, dp(18), 0, dp(7));
        root.addView(rangeTitle);

        LinearLayout dateRow = new LinearLayout(this);
        dateRow.setOrientation(LinearLayout.HORIZONTAL);

        fromBtn = actionButton("");
        toBtn = actionButton("");

        dateRow.addView(
                fromBtn,
                new LinearLayout.LayoutParams(0, dp(60), 1)
        );

        View spacer2 = new View(this);
        dateRow.addView(
                spacer2,
                new LinearLayout.LayoutParams(dp(10), 1)
        );

        dateRow.addView(
                toBtn,
                new LinearLayout.LayoutParams(0, dp(60), 1)
        );

        root.addView(dateRow);

        fromBtn.setOnClickListener(v -> pickDate(true));
        toBtn.setOnClickListener(v -> pickDate(false));

        // Токен.
        tokenBlock = new LinearLayout(this);
        tokenBlock.setOrientation(LinearLayout.VERTICAL);

        TextView tokenTitle =
                text("Travelpayouts token", 16, true);

        tokenTitle.setPadding(0, dp(18), 0, dp(5));

        tokenBlock.addView(tokenTitle);

        tokenInput = new EditText(this);
        tokenInput.setTextSize(16);
        tokenInput.setHint("Вставьте токен");

        tokenInput.setSingleLine(true);
        tokenInput.setInputType(
                InputType.TYPE_CLASS_TEXT |
                        InputType.TYPE_TEXT_VARIATION_PASSWORD
        );

        SharedPreferences prefs =
                getSharedPreferences(PREFS, MODE_PRIVATE);

        String savedToken =
                prefs.getString(TOKEN_KEY, "");

        tokenInput.setText(savedToken);

        tokenBlock.addView(
                tokenInput,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(52)
                )
        );

        root.addView(tokenBlock);

        // Если токен уже сохранён — поле вообще не показываем.
        if (!savedToken.isEmpty()) {
            tokenBlock.setVisibility(View.GONE);
        }

        // Обновить.
        Button scan = actionButton("ОБНОВИТЬ РАДАР");

        LinearLayout.LayoutParams scanLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        dp(62)
                );

        scanLp.topMargin = dp(18);

        root.addView(scan, scanLp);

        scan.setOnClickListener(v -> refreshRadar());

        // Проверка непосредственно у Победы.
        Button pobedaSite =
                smallButton("Проверить на сайте Победы");

        LinearLayout.LayoutParams siteLp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        dp(44)
                );

        siteLp.gravity = Gravity.CENTER_HORIZONTAL;
        siteLp.topMargin = dp(8);

        root.addView(pobedaSite, siteLp);

        pobedaSite.setOnClickListener(v -> {
            try {
                Intent browser = new Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://pobeda.aero/")
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

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);

        LinearLayout.LayoutParams pp =
                new LinearLayout.LayoutParams(dp(40), dp(40));

        pp.gravity = Gravity.CENTER_HORIZONTAL;
        pp.topMargin = dp(10);

        root.addView(progress, pp);

        summary = text(
                "Выберите направление и диапазон дат.",
                17,
                true
        );

        summary.setPadding(0, dp(18), 0, dp(6));

        root.addView(summary);

        status = text("", 14, false);
        status.setTextColor(GREY_TEXT);

        root.addView(status);

        resultsBox = new LinearLayout(this);
        resultsBox.setOrientation(LinearLayout.VERTICAL);

        LinearLayout.LayoutParams rp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        rp.topMargin = dp(10);

        root.addView(resultsBox, rp);

        setContentView(scroll);
    }

    private void updateDirectionUi() {

        styleDirectionButton(outBtn, outbound);
        styleDirectionButton(backBtn, !outbound);

        LocalDate a = outbound ? outFrom : backFrom;
        LocalDate b = outbound ? outTo : backTo;

        fromBtn.setText("С\n" + a.format(uiDate));
        toBtn.setText("ПО\n" + b.format(uiDate));

        showEmptyNearestWeek();

        summary.setText(
                "Диапазон: " +
                        a.format(uiDate) +
                        " — " +
                        b.format(uiDate)
        );

        status.setText("");
        resultsBox.removeAllViews();
    }

    private void styleDirectionButton(Button button, boolean active) {

        GradientDrawable bg = new GradientDrawable();

        bg.setCornerRadius(dp(5));
        bg.setColor(BUTTON_BG);

        if (active) {
            // Активная кнопка — чёткая красная рамка.
            bg.setStroke(dp(3), RED);
            button.setTextColor(Color.rgb(25, 25, 25));
        } else {
            bg.setStroke(
                    dp(1),
                    Color.rgb(205, 205, 205)
            );
            button.setTextColor(Color.rgb(55, 55, 55));
        }

        button.setBackground(bg);
        button.setEnabled(true);
        button.setAlpha(1.0f);
    }

    private void showEmptyNearestWeek() {

        nearestBox.removeAllViews();

        LocalDate start =
                outbound ? outFrom : backFrom;

        for (int i = 0; i < 7; i++) {

            LocalDate date = start.plusDays(i);

            addNearestRow(
                    date,
                    "—"
            );
        }
    }

    private void showNearestWeek(List<Offer> offers) {

        nearestBox.removeAllViews();

        LocalDate start =
                outbound ? outFrom : backFrom;

        for (int i = 0; i < 7; i++) {

            LocalDate date = start.plusDays(i);

            Integer bestPrice = null;

            for (Offer offer : offers) {

                if (offer.date.equals(date)) {

                    if (bestPrice == null ||
                            offer.price < bestPrice) {

                        bestPrice = offer.price;
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

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView d =
                text(date.format(shortDate), 14, false);

        d.setTextColor(GREY_TEXT);

        TextView p =
                text(price, 14, true);

        p.setTextColor(GREY_TEXT);
        p.setGravity(Gravity.END);

        row.addView(
                d,
                new LinearLayout.LayoutParams(
                        0,
                        dp(25),
                        1
                )
        );

        row.addView(
                p,
                new LinearLayout.LayoutParams(
                        0,
                        dp(25),
                        1
                )
        );

        nearestBox.addView(row);
    }

    private void pickDate(boolean start) {

        LocalDate current;

        if (outbound) {
            current = start ? outFrom : outTo;
        } else {
            current = start ? backFrom : backTo;
        }

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

                            if (outbound) {

                                if (start) outFrom = chosen;
                                else outTo = chosen;

                                if (outTo.isBefore(outFrom)) {
                                    outTo = outFrom;
                                }

                            } else {

                                if (start) backFrom = chosen;
                                else backTo = chosen;

                                if (backTo.isBefore(backFrom)) {
                                    backTo = backFrom;
                                }
                            }

                            updateDirectionUi();

                        },
                        current.getYear(),
                        current.getMonthValue() - 1,
                        current.getDayOfMonth()
                );

        dialog.getDatePicker().setMinDate(
                System.currentTimeMillis() - 1000
        );

        dialog.show();
    }

    private void refreshRadar() {

        SharedPreferences prefs =
                getSharedPreferences(PREFS, MODE_PRIVATE);

        String token =
                prefs.getString(TOKEN_KEY, "");

        // При первом запуске берём введённый токен.
        if (token.isEmpty()) {
            token = tokenInput.getText()
                    .toString()
                    .trim();
        }

        if (token.isEmpty()) {

            Toast.makeText(
                    this,
                    "Введите Travelpayouts token",
                    Toast.LENGTH_LONG
            ).show();

            tokenBlock.setVisibility(View.VISIBLE);
            return;
        }

        final String finalToken = token;

        LocalDate from =
                outbound ? outFrom : backFrom;

        LocalDate to =
                outbound ? outTo : backTo;

        String origin =
                outbound ? "MOW" : "GZP";

        String destination =
                outbound ? "GZP" : "MOW";

        progress.setVisibility(View.VISIBLE);

        summary.setText("Проверяю цены…");

        status.setText(
                origin + " → " + destination +
                        " · " +
                        from.format(uiDate) +
                        " — " +
                        to.format(uiDate)
        );

        resultsBox.removeAllViews();

        new Thread(() -> {

            try {

                List<Offer> offers =
                        loadOffers(
                                origin,
                                destination,
                                from,
                                to,
                                finalToken
                        );

                runOnUiThread(() -> {

                    // Сохраняем токен.
                    prefs.edit()
                            .putString(
                                    TOKEN_KEY,
                                    finalToken
                            )
                            .apply();

                    // И больше его не показываем.
                    tokenBlock.setVisibility(View.GONE);

                    showOffers(
                            offers,
                            origin,
                            destination,
                            from,
                            to
                    );
                });

            } catch (Exception e) {

                runOnUiThread(() -> {

                    progress.setVisibility(View.GONE);

                    summary.setText(
                            "Не удалось обновить радар"
                    );

                    status.setText(
                            e.getMessage() == null
                                    ? e.toString()
                                    : e.getMessage()
                    );

                    // Если токен ещё не был сохранён,
                    // оставляем поле доступным.
                    if (prefs.getString(
                            TOKEN_KEY,
                            ""
                    ).isEmpty()) {

                        tokenBlock.setVisibility(View.VISIBLE);
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
                        + "?origin=" + enc(origin)
                        + "&destination=" + enc(destination)
                        + "&airline=DP"
                        + "&locale=ru"
                        + "&currency=rub"
                        + "&market=ru"
                        + "&token=" + enc(token);

        HttpURLConnection connection =
                (HttpURLConnection)
                        new URL(requestUrl).openConnection();

        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestMethod("GET");

        connection.setRequestProperty(
                "Accept",
                "application/json"
        );

        connection.setRequestProperty(
                "User-Agent",
                "PobedaRadar/1.2"
        );

        int code = connection.getResponseCode();

        InputStream stream =
                code >= 200 && code < 300
                        ? connection.getInputStream()
                        : connection.getErrorStream();

        String body = readAll(stream);

        connection.disconnect();

        if (code == 401 || code == 403) {
            throw new Exception(
                    "Travelpayouts отклонил токен"
            );
        }

        if (code < 200 || code >= 300) {
            throw new Exception(
                    "Travelpayouts HTTP " + code
            );
        }

        JSONObject json =
                new JSONObject(body);

        JSONArray data =
                json.optJSONArray("data");

        List<Offer> offers =
                new ArrayList<>();

        if (data == null) {
            return offers;
        }

        for (int i = 0; i < data.length(); i++) {

            JSONObject item =
                    data.optJSONObject(i);

            if (item == null) continue;

            String airline =
                    item.optString(
                            "airline",
                            ""
                    );

            if (!"DP".equalsIgnoreCase(airline)) {
                continue;
            }

            LocalDate date =
                    parseDate(
                            item.optString(
                                    "departure_at",
                                    ""
                            )
                    );

            if (date == null ||
                    date.isBefore(from) ||
                    date.isAfter(to)) {

                continue;
            }

            int price =
                    item.optInt(
                            "price",
                            -1
                    );

            if (price <= 0) continue;

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
                                (Offer o) -> o.date
                        )
                        .thenComparingInt(
                                o -> o.price
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

        progress.setVisibility(View.GONE);

        showNearestWeek(offers);

        resultsBox.removeAllViews();

        if (offers.isEmpty()) {

            summary.setText(
                    "Ценовых данных пока нет"
            );

            status.setText(
                    "Это не означает отсутствие рейсов Победы. " +
                            "Travelpayouts не всегда содержит цену " +
                            "по каждой существующей дате."
            );

            return;
        }

        int min = Integer.MAX_VALUE;
        long total = 0;

        for (Offer offer : offers) {

            min = Math.min(
                    min,
                    offer.price
            );

            total += offer.price;
        }

        int avg =
                (int) Math.round(
                        total /
                                (double) offers.size()
                );

        summary.setText(
                "Найдено: " +
                        offers.size() +
                        " · минимум " +
                        rub(min) +
                        " · средняя " +
                        rub(avg)
        );

        status.setText(
                origin + " → " + destination +
                        " · " +
                        from.format(uiDate) +
                        " — " +
                        to.format(uiDate)
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
                Color.rgb(247, 247, 247)
        );

        bg.setCornerRadius(dp(5));

        card.setBackground(bg);

        TextView main =
                text(
                        offer.date.format(uiDate) +
                                "   " +
                                rub(offer.price),
                        21,
                        true
                );

        card.addView(main);

        TextView info =
                text(
                        priceRating(
                                offer.price,
                                avg,
                                count
                        ) +
                                " · DP" +
                                offer.flight,
                        14,
                        false
                );

        info.setTextColor(GREY_TEXT);
        info.setPadding(0, dp(6), 0, 0);

        card.addView(info);

        LinearLayout.LayoutParams lp =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                );

        lp.bottomMargin = dp(8);

        resultsBox.addView(card, lp);
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
                price / (double) avg;

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

    private LocalDate parseDate(String value) {

        try {

            return OffsetDateTime
                    .parse(value)
                    .toLocalDate();

        } catch (Exception ignored) {
        }

        try {

            return LocalDate.parse(
                    value.substring(0, 10)
            );

        } catch (Exception ignored) {
            return null;
        }
    }

    private String readAll(
            InputStream stream
    ) throws Exception {

        if (stream == null) return "";

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

        while ((line = reader.readLine()) != null) {
            result.append(line);
        }

        reader.close();

        return result.toString();
    }

    private String enc(String value)
            throws Exception {

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
                Color.rgb(32, 32, 32)
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

    private String rub(int value) {

        return String.format(
                        new Locale("ru"),
                        "%,d ₽",
                        value
                )
                .replace(',', ' ');
    }

    private int dp(int value) {

        return (int) (
                value *
                        getResources()
                                .getDisplayMetrics()
                                .density +
                        0.5f
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
