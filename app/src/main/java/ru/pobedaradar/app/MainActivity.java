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
import android.widget.ScrollView;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {

    private static final String APP_VERSION = "v0.16";
    private static final String PREFS = "pobeda_radar";
    private static final String TOKEN_KEY = "travelpayouts_token";
    private static final String DIRECTION_KEY = "direction_outbound";

    // Старые ключи оставлены для обратной совместимости и первичного переноса диапазонов.
    private static final String LEGACY_DATE_FROM_KEY = "date_from";
    private static final String LEGACY_DATE_TO_KEY = "date_to";

    private static final String LAST_PRICE_PREFIX = "last_price_";
    private static final String MIN_PRICE_PREFIX = "min_price_";
    private static final String LAST_SEEN_PREFIX = "last_seen_";
    private static final String LAST_UPDATE_PREFIX = "last_update_";

    private static final String BACKGROUND_WORK_NAME = "pobeda_background_radar";
    private static final int NOTIFICATION_PERMISSION_REQUEST = 2001;

    private static final int RED = Color.rgb(210, 30, 30);
    private static final int GREEN = Color.rgb(35, 135, 70);
    private static final int GREY = Color.rgb(105, 105, 105);
    private static final int LIGHT_GREY = Color.rgb(236, 236, 236);
    private static final int RECOMMEND_GREY = Color.rgb(224, 224, 224);
    private static final int DARK = Color.rgb(28, 28, 28);

    private final DateTimeFormatter uiDate = DateTimeFormatter.ofPattern("dd.MM.yyyy", new Locale("ru"));
    private final DateTimeFormatter shortUiDate = DateTimeFormatter.ofPattern("dd.MM", new Locale("ru"));
    private final DateTimeFormatter monthParam = DateTimeFormatter.ofPattern("yyyy-MM");
    private final DateTimeFormatter timeFormat = DateTimeFormatter.ofPattern("HH:mm", new Locale("ru"));

    private SharedPreferences prefs;
    private PriceHistoryStore historyStore;

    private boolean outbound;
    private LocalDate wideFrom;
    private LocalDate wideTo;
    private LocalDate narrowFrom;
    private LocalDate narrowTo;

    private LinearLayout nearestBox;
    private LinearLayout bestOffersBox;
    private LinearLayout tokenBlock;
    private Button outButton;
    private Button backButton;
    private Button wideFromButton;
    private Button wideToButton;
    private Button narrowFromButton;
    private Button narrowToButton;
    private Button refreshButton;
    private EditText tokenInput;
    private TextView recommendationText;
    private TextView resultText;
    private TextView statusText;
    private ProgressBar progress;
    private boolean requestRunning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        historyStore = new PriceHistoryStore(this);
        historyStore.seedLegacy(prefs);

        outbound = prefs.getBoolean(DIRECTION_KEY, true);
        loadRangesForCurrentRoute();
        buildInterface();
        refreshInterface();
        requestNotificationPermission();
        scheduleBackgroundRadar();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (historyStore != null && recommendationText != null) {
            updateRecommendation();
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST);
        }
    }

    private void scheduleBackgroundRadar() {
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                PriceRadarWorker.class, 3, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build();
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                BACKGROUND_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request);
    }

    private String routeId() {
        return outbound ? "MOW_GZP" : "GZP_MOW";
    }

    private String key(String kind) {
        return kind + "_" + routeId();
    }

    private LocalDate safeDate(String value, LocalDate fallback) {
        try {
            return LocalDate.parse(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void loadRangesForCurrentRoute() {
        LocalDate today = LocalDate.now();
        LocalDate legacyFrom = safeDate(
                prefs.getString(LEGACY_DATE_FROM_KEY, today.toString()), today);
        LocalDate legacyTo = safeDate(
                prefs.getString(LEGACY_DATE_TO_KEY, today.plusDays(30).toString()),
                today.plusDays(30));

        if (legacyFrom.isBefore(today)) legacyFrom = today;
        if (legacyTo.isBefore(legacyFrom)) legacyTo = legacyFrom.plusDays(30);

        wideFrom = safeDate(prefs.getString(key("wide_from"), legacyFrom.toString()), legacyFrom);
        wideTo = safeDate(prefs.getString(key("wide_to"), legacyTo.toString()), legacyTo);
        if (wideFrom.isBefore(today)) wideFrom = today;
        if (wideTo.isBefore(wideFrom)) wideTo = wideFrom.plusDays(30);

        LocalDate defaultNarrowTo = wideFrom.plusDays(6);
        if (defaultNarrowTo.isAfter(wideTo)) defaultNarrowTo = wideTo;
        narrowFrom = safeDate(prefs.getString(key("narrow_from"), wideFrom.toString()), wideFrom);
        narrowTo = safeDate(prefs.getString(key("narrow_to"), defaultNarrowTo.toString()), defaultNarrowTo);
        clampNarrowToWide();
        saveRanges();
    }

    private void clampNarrowToWide() {
        if (narrowFrom.isBefore(wideFrom)) narrowFrom = wideFrom;
        if (narrowFrom.isAfter(wideTo)) narrowFrom = wideTo;
        if (narrowTo.isBefore(narrowFrom)) narrowTo = narrowFrom;
        if (narrowTo.isAfter(wideTo)) narrowTo = wideTo;
    }

    private void saveRanges() {
        prefs.edit()
                .putString(key("wide_from"), wideFrom.toString())
                .putString(key("wide_to"), wideTo.toString())
                .putString(key("narrow_from"), narrowFrom.toString())
                .putString(key("narrow_to"), narrowTo.toString())
                .apply();
    }

    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(28), dp(16), dp(12));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));

        TextView title = makeText("Москва ⇄ Газипаша · Победа (DP)", 16, true);
        title.setTextColor(GREY);
        title.setGravity(Gravity.CENTER);
        root.addView(title, match(dp(28)));

        LinearLayout directionRow = new LinearLayout(this);
        directionRow.setOrientation(LinearLayout.HORIZONTAL);
        outButton = makeButton("МОСКВА → GZP", 14);
        backButton = makeButton("GZP → МОСКВА", 14);
        directionRow.addView(outButton, weighted(dp(44), 1));
        directionRow.addView(space(dp(8)), new LinearLayout.LayoutParams(dp(8), 1));
        directionRow.addView(backButton, weighted(dp(44), 1));
        root.addView(directionRow, match(dp(44)));

        outButton.setOnClickListener(v -> switchDirection(true));
        backButton.setOnClickListener(v -> switchDirection(false));

        TextView nearestTitle = makeText("Ближайшие даты", 13, true);
        nearestTitle.setTextColor(GREY);
        root.addView(nearestTitle, match(dp(23)));

        nearestBox = new LinearLayout(this);
        nearestBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(nearestBox, match(dp(270))); // 15 × 18dp

        recommendationText = makeText("РЕКОМЕНДАЦИЯ: НАБЛЮДАТЬ", 16, true);
        recommendationText.setGravity(Gravity.CENTER);
        recommendationText.setTextColor(DARK);
        GradientDrawable recBg = new GradientDrawable();
        recBg.setColor(RECOMMEND_GREY);
        recBg.setCornerRadius(dp(6));
        recommendationText.setBackground(recBg);
        LinearLayout.LayoutParams recLp = match(dp(50));
        recLp.topMargin = dp(6);
        recLp.bottomMargin = dp(6);
        root.addView(recommendationText, recLp);

        root.addView(makeRangeTitle("Диапазон дат широкий"), match(dp(23)));
        LinearLayout wideRow = new LinearLayout(this);
        wideRow.setOrientation(LinearLayout.HORIZONTAL);
        wideFromButton = makeButton("", 13);
        wideToButton = makeButton("", 13);
        wideRow.addView(wideFromButton, weighted(dp(39), 1));
        wideRow.addView(space(dp(8)), new LinearLayout.LayoutParams(dp(8), 1));
        wideRow.addView(wideToButton, weighted(dp(39), 1));
        root.addView(wideRow, match(dp(39)));

        root.addView(makeRangeTitle("Диапазон дат узкий"), match(dp(23)));
        LinearLayout narrowRow = new LinearLayout(this);
        narrowRow.setOrientation(LinearLayout.HORIZONTAL);
        narrowFromButton = makeButton("", 13);
        narrowToButton = makeButton("", 13);
        narrowRow.addView(narrowFromButton, weighted(dp(39), 1));
        narrowRow.addView(space(dp(8)), new LinearLayout.LayoutParams(dp(8), 1));
        narrowRow.addView(narrowToButton, weighted(dp(39), 1));
        root.addView(narrowRow, match(dp(39)));

        wideFromButton.setOnClickListener(v -> pickDate(0));
        wideToButton.setOnClickListener(v -> pickDate(1));
        narrowFromButton.setOnClickListener(v -> pickDate(2));
        narrowToButton.setOnClickListener(v -> pickDate(3));

        TextView bestTitle = makeText("3 наилучших предложения широкого диапазона", 13, true);
        bestTitle.setTextColor(GREY);
        LinearLayout.LayoutParams bestTitleLp = match(dp(24));
        bestTitleLp.topMargin = dp(5);
        root.addView(bestTitle, bestTitleLp);

        bestOffersBox = new LinearLayout(this);
        bestOffersBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(bestOffersBox, match(dp(54)));

        tokenBlock = new LinearLayout(this);
        tokenBlock.setOrientation(LinearLayout.VERTICAL);
        tokenInput = new EditText(this);
        tokenInput.setTextSize(14);
        tokenInput.setHint("Travelpayouts token");
        tokenInput.setSingleLine(true);
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        String savedToken = prefs.getString(TOKEN_KEY, "");
        tokenInput.setText(savedToken);
        tokenBlock.addView(tokenInput, match(dp(38)));
        LinearLayout.LayoutParams tokenLp = match(dp(38));
        tokenLp.topMargin = dp(5);
        root.addView(tokenBlock, tokenLp);
        if (savedToken != null && !savedToken.isEmpty()) tokenBlock.setVisibility(View.GONE);

        refreshButton = makeButton("ОБНОВИТЬ РАДАР", 14);
        LinearLayout.LayoutParams refreshLp = match(dp(42));
        refreshLp.topMargin = dp(5);
        root.addView(refreshButton, refreshLp);
        refreshButton.setOnClickListener(v -> loadRadar());

        resultText = makeText("", 13, true);
        resultText.setGravity(Gravity.CENTER);
        root.addView(resultText, match(dp(24)));

        statusText = makeText("", 11, false);
        statusText.setTextColor(GREY);
        statusText.setGravity(Gravity.CENTER);
        root.addView(statusText, match(dp(22)));

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        progressLp.gravity = Gravity.CENTER_HORIZONTAL;
        root.addView(progress, progressLp);

        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.CENTER_VERTICAL);
        Button pobedaButton = makeSmallButton("Проверить на сайте Победы");
        bottomRow.addView(pobedaButton, weighted(dp(32), 1));
        TextView version = makeText(APP_VERSION, 10, false);
        version.setTextColor(GREY);
        version.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        bottomRow.addView(version, new LinearLayout.LayoutParams(dp(55), dp(32)));
        root.addView(bottomRow, match(dp(32)));

        pobedaButton.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.pobeda.aero/")));
            } catch (Exception e) {
                Toast.makeText(this, "Не удалось открыть сайт Победы", Toast.LENGTH_SHORT).show();
            }
        });

        setContentView(scroll);
    }

    private TextView makeRangeTitle(String text) {
        TextView title = makeText(text, 13, true);
        title.setGravity(Gravity.CENTER);
        return title;
    }

    private void switchDirection(boolean newOutbound) {
        if (requestRunning || outbound == newOutbound) return;
        outbound = newOutbound;
        prefs.edit().putBoolean(DIRECTION_KEY, outbound).apply();
        loadRangesForCurrentRoute();
        refreshInterface();
    }

    private void refreshInterface() {
        styleDirection(outButton, outbound);
        styleDirection(backButton, !outbound);
        wideFromButton.setText("С  " + wideFrom.format(uiDate));
        wideToButton.setText("ПО  " + wideTo.format(uiDate));
        narrowFromButton.setText("С  " + narrowFrom.format(uiDate));
        narrowToButton.setText("ПО  " + narrowTo.format(uiDate));
        showStoredNearest();
        showStoredBestOffers();
        updateRecommendation();
        resultText.setText("");
        long lastUpdate = getLastUpdate(outbound);
        statusText.setText(lastUpdate > 0
                ? routeText() + " · обновлено " + formatTime(lastUpdate)
                : routeText());
    }

    private void updateRecommendation() {
        PriceHistoryStore.Recommendation rec = historyStore.recommend(
                routeId(), narrowFrom, narrowTo, wideFrom, wideTo);
        recommendationText.setText("РЕКОМЕНДАЦИЯ: " + rec.verdict);
        if ("ПОКУПКА".equals(rec.verdict)) recommendationText.setTextColor(GREEN);
        else if ("ПОДОЖДАТЬ".equals(rec.verdict)) recommendationText.setTextColor(RED);
        else recommendationText.setTextColor(DARK);
    }

    private String routeText() {
        return outbound ? "Москва → Газипаша" : "Газипаша → Москва";
    }

    private void showStoredNearest() {
        nearestBox.removeAllViews();
        for (int i = 0; i < 15; i++) {
            LocalDate day = wideFrom.plusDays(i);
            if (day.isAfter(wideTo)) {
                addPriceRow(nearestBox, day, "", "", GREY);
                continue;
            }
            int price = getStoredLastPrice(day, outbound);
            int min = getStoredMinPrice(day, outbound);
            String info = min > 0 ? "мин " + formatPriceCompact(min) : "";
            addPriceRow(nearestBox, day, price > 0 ? formatPrice(price) : "—", info, GREY);
        }
    }

    private void showNearestCurrent(Map<LocalDate, Integer> current, boolean requestedOutbound,
                                    LocalDate requestedFrom, LocalDate requestedTo) {
        nearestBox.removeAllViews();
        for (int i = 0; i < 15; i++) {
            LocalDate day = requestedFrom.plusDays(i);
            if (day.isAfter(requestedTo)) {
                addPriceRow(nearestBox, day, "", "", GREY);
                continue;
            }
            Integer price = current.get(day);
            if (price == null) price = getStoredLastPrice(day, requestedOutbound);
            int min = getStoredMinPrice(day, requestedOutbound);
            String info = min > 0 ? "мин " + formatPriceCompact(min) : "";
            addPriceRow(nearestBox, day, price != null && price > 0 ? formatPrice(price) : "—", info, GREY);
        }
    }

    private void showStoredBestOffers() {
        List<BestOffer> best = new ArrayList<>();
        LocalDate day = wideFrom;
        while (!day.isAfter(wideTo)) {
            int price = getStoredLastPrice(day, outbound);
            if (price > 0) best.add(new BestOffer(day, price));
            day = day.plusDays(1);
        }
        drawBestOffers(best);
    }

    private void showBestOffers(Map<LocalDate, Integer> byDate) {
        List<BestOffer> best = new ArrayList<>();
        for (Map.Entry<LocalDate, Integer> e : byDate.entrySet()) {
            if (!e.getKey().isBefore(wideFrom) && !e.getKey().isAfter(wideTo)) {
                best.add(new BestOffer(e.getKey(), e.getValue()));
            }
        }
        drawBestOffers(best);
    }

    private void drawBestOffers(List<BestOffer> best) {
        bestOffersBox.removeAllViews();
        best.sort(Comparator.comparingInt((BestOffer b) -> b.price).thenComparing(b -> b.date));
        for (int i = 0; i < 3; i++) {
            if (i < best.size()) {
                BestOffer item = best.get(i);
                addPriceRow(bestOffersBox, item.date, formatPrice(item.price), "", GREY);
            } else {
                addPriceRow(bestOffersBox, wideFrom, "", "", GREY);
            }
        }
    }

    private void addPriceRow(LinearLayout parent, LocalDate date, String price, String info, int infoColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView dateText = makeText(date.format(shortUiDate), 13, false);
        dateText.setTextColor(GREY);
        row.addView(dateText, weighted(dp(18), 0.65f));
        TextView priceText = makeText(price, 13, true);
        priceText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        priceText.setTextColor(price.isEmpty() || "—".equals(price) ? GREY : DARK);
        row.addView(priceText, weighted(dp(18), 1.0f));
        TextView infoText = makeText(info, 11, true);
        infoText.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        infoText.setTextColor(infoColor);
        infoText.setSingleLine(true);
        row.addView(infoText, weighted(dp(18), 1.45f));
        parent.addView(row);
    }

    private String lastPriceKey(LocalDate date, boolean isOutbound) {
        return LAST_PRICE_PREFIX + routeHistoryId(isOutbound) + "_" + date;
    }

    private String minPriceKey(LocalDate date, boolean isOutbound) {
        return MIN_PRICE_PREFIX + routeHistoryId(isOutbound) + "_" + date;
    }

    private String lastSeenKey(LocalDate date, boolean isOutbound) {
        return LAST_SEEN_PREFIX + routeHistoryId(isOutbound) + "_" + date;
    }

    private String lastUpdateKey(boolean isOutbound) {
        return LAST_UPDATE_PREFIX + routeHistoryId(isOutbound);
    }

    private String routeHistoryId(boolean isOutbound) {
        return isOutbound ? "MOW_GZP" : "GZP_MOW";
    }

    private int getStoredLastPrice(LocalDate date, boolean isOutbound) {
        return prefs.getInt(lastPriceKey(date, isOutbound), -1);
    }

    private int getStoredMinPrice(LocalDate date, boolean isOutbound) {
        return prefs.getInt(minPriceKey(date, isOutbound), -1);
    }

    private long getLastUpdate(boolean isOutbound) {
        return prefs.getLong(lastUpdateKey(isOutbound), 0L);
    }

    private void saveSnapshot(Map<LocalDate, Integer> prices, boolean isOutbound) {
        long now = System.currentTimeMillis();
        String route = routeHistoryId(isOutbound);
        SharedPreferences.Editor editor = prefs.edit();
        for (Map.Entry<LocalDate, Integer> entry : prices.entrySet()) {
            LocalDate date = entry.getKey();
            int price = entry.getValue();
            int oldMin = getStoredMinPrice(date, isOutbound);
            int newMin = oldMin <= 0 ? price : Math.min(oldMin, price);
            editor.putInt(lastPriceKey(date, isOutbound), price);
            editor.putInt(minPriceKey(date, isOutbound), newMin);
            editor.putLong(lastSeenKey(date, isOutbound), now);
            historyStore.record(route, date, now, price);
        }
        editor.putLong(lastUpdateKey(isOutbound), now).apply();
    }

    private void pickDate(int which) {
        if (requestRunning) return;
        LocalDate current;
        if (which == 0) current = wideFrom;
        else if (which == 1) current = wideTo;
        else if (which == 2) current = narrowFrom;
        else current = narrowTo;

        DatePickerDialog dialog = new DatePickerDialog(this, (view, year, month, day) -> {
            LocalDate selected = LocalDate.of(year, month + 1, day);
            if (which == 0) {
                wideFrom = selected;
                if (wideTo.isBefore(wideFrom)) wideTo = wideFrom;
                clampNarrowToWide();
            } else if (which == 1) {
                wideTo = selected;
                if (wideTo.isBefore(wideFrom)) wideFrom = wideTo;
                clampNarrowToWide();
            } else if (which == 2) {
                narrowFrom = selected;
                clampNarrowToWide();
            } else {
                narrowTo = selected;
                clampNarrowToWide();
            }
            saveRanges();
            refreshInterface();
        }, current.getYear(), current.getMonthValue() - 1, current.getDayOfMonth());
        dialog.getDatePicker().setMinDate(System.currentTimeMillis() - 1000);
        dialog.show();
    }

    private void loadRadar() {
        if (requestRunning) return;
        String token = prefs.getString(TOKEN_KEY, "");
        if (token == null) token = "";
        if (token.trim().isEmpty()) token = tokenInput.getText().toString().trim();
        if (token.isEmpty()) {
            tokenBlock.setVisibility(View.VISIBLE);
            Toast.makeText(this, "Введите Travelpayouts token", Toast.LENGTH_LONG).show();
            return;
        }

        final String finalToken = token;
        final boolean requestedOutbound = outbound;
        final LocalDate requestedFrom = wideFrom;
        final LocalDate requestedTo = wideTo;
        final String origin = requestedOutbound ? "MOW" : "GZP";
        final String destination = requestedOutbound ? "GZP" : "MOW";

        saveRanges();
        setLoadingState(true);
        resultText.setText("Ищу цены Победы…");
        statusText.setText(requestedFrom.format(shortUiDate) + "–" + requestedTo.format(shortUiDate));

        new Thread(() -> {
            try {
                List<Offer> offers = requestEntireRange(origin, destination,
                        requestedFrom, requestedTo, finalToken);
                Map<LocalDate, Integer> prices = getBestPricesByDate(offers);
                saveSnapshot(prices, requestedOutbound);

                runOnUiThread(() -> {
                    setLoadingState(false);
                    prefs.edit().putString(TOKEN_KEY, finalToken).apply();
                    tokenBlock.setVisibility(View.GONE);

                    if (outbound == requestedOutbound
                            && wideFrom.equals(requestedFrom)
                            && wideTo.equals(requestedTo)) {
                        showNearestCurrent(prices, requestedOutbound, requestedFrom, requestedTo);
                        showBestOffers(prices);
                        updateRecommendation();
                    } else {
                        refreshInterface();
                    }

                    if (offers.isEmpty()) {
                        resultText.setText("Свежих цен Победы нет");
                    } else {
                        BestOffer best = null;
                        for (Map.Entry<LocalDate, Integer> e : prices.entrySet()) {
                            if (best == null || e.getValue() < best.price
                                    || (e.getValue() == best.price && e.getKey().isBefore(best.date))) {
                                best = new BestOffer(e.getKey(), e.getValue());
                            }
                        }
                        resultText.setText(best == null ? "Цены не найдены"
                                : "Минимум: " + formatPrice(best.price) + " · " + best.date.format(uiDate));
                    }
                    statusText.setText("Обновлено " + formatTime(getLastUpdate(requestedOutbound))
                            + " · " + prices.size() + " дат");
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setLoadingState(false);
                    resultText.setText("Ошибка обновления");
                    statusText.setText(e.getMessage() == null ? e.toString() : e.getMessage());
                });
            }
        }).start();
    }

    private void setLoadingState(boolean loading) {
        requestRunning = loading;
        progress.setVisibility(loading ? View.VISIBLE : View.GONE);
        refreshButton.setEnabled(!loading);
        refreshButton.setText(loading ? "ОБНОВЛЯЮ…" : "ОБНОВИТЬ РАДАР");
        outButton.setEnabled(!loading);
        backButton.setEnabled(!loading);
        wideFromButton.setEnabled(!loading);
        wideToButton.setEnabled(!loading);
        narrowFromButton.setEnabled(!loading);
        narrowToButton.setEnabled(!loading);
    }

    private Map<LocalDate, Integer> getBestPricesByDate(List<Offer> offers) {
        Map<LocalDate, Integer> result = new LinkedHashMap<>();
        for (Offer offer : offers) {
            Integer old = result.get(offer.date);
            if (old == null || offer.price < old) result.put(offer.date, offer.price);
        }
        return result;
    }

    private List<Offer> requestEntireRange(String origin, String destination,
                                           LocalDate from, LocalDate to, String token) throws Exception {
        List<Offer> result = new ArrayList<>();
        YearMonth month = YearMonth.from(from);
        YearMonth last = YearMonth.from(to);
        while (!month.isAfter(last)) {
            result.addAll(requestMonth(origin, destination, month, from, to, token));
            month = month.plusMonths(1);
        }
        result.sort(Comparator.comparing((Offer o) -> o.date).thenComparingInt(o -> o.price));
        return result;
    }

    private List<Offer> requestMonth(String origin, String destination, YearMonth month,
                                     LocalDate from, LocalDate to, String token) throws Exception {
        String url = "https://api.travelpayouts.com/aviasales/v3/prices_for_dates"
                + "?origin=" + encode(origin)
                + "&destination=" + encode(destination)
                + "&departure_at=" + encode(month.format(monthParam))
                + "&one_way=true&direct=true&unique=false&sorting=price"
                + "&currency=rub&market=ru&limit=1000&page=1&token=" + encode(token);

        JSONObject json = getJson(url);
        JSONArray data = json.optJSONArray("data");
        List<Offer> result = new ArrayList<>();
        if (data == null) return result;
        for (int i = 0; i < data.length(); i++) {
            JSONObject item = data.optJSONObject(i);
            if (item == null) continue;
            if (!"DP".equalsIgnoreCase(item.optString("airline", ""))) continue;
            if (item.optInt("transfers", 0) != 0) continue;
            LocalDate date = parseDepartureDate(item.optString("departure_at", ""));
            if (date == null || date.isBefore(from) || date.isAfter(to)) continue;
            int price = item.optInt("price", -1);
            if (price <= 0) continue;
            result.add(new Offer(date, price));
        }
        return result;
    }

    private JSONObject getJson(String urlString) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(25000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "PobedaRadar/0.16");
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300
                ? connection.getInputStream() : connection.getErrorStream();
        String response = readStream(stream);
        connection.disconnect();
        if (code == 401 || code == 403) throw new Exception("Travelpayouts отклонил токен");
        if (code == 429) throw new Exception("Слишком много запросов. Повтори позже.");
        if (code < 200 || code >= 300) throw new Exception("Travelpayouts HTTP " + code);
        JSONObject json = new JSONObject(response);
        if (!json.optBoolean("success", true)) throw new Exception("Travelpayouts вернул ошибку");
        return json;
    }

    private LocalDate parseDepartureDate(String value) {
        if (value == null || value.length() < 10) return null;
        try {
            return OffsetDateTime.parse(value).toLocalDate();
        } catch (Exception ignored) {
        }
        try {
            return LocalDate.parse(value.substring(0, 10));
        } catch (Exception ignored) {
            return null;
        }
    }

    private String readStream(InputStream stream) throws Exception {
        if (stream == null) return "";
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) builder.append(line);
        reader.close();
        return builder.toString();
    }

    private String formatTime(long millis) {
        try {
            return Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime().format(timeFormat);
        } catch (Exception e) {
            return "";
        }
    }

    private void styleDirection(Button button, boolean selected) {
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(5));
        bg.setColor(selected ? Color.WHITE : LIGHT_GREY);
        bg.setStroke(dp(selected ? 3 : 1), selected ? RED : Color.rgb(205, 205, 205));
        button.setBackground(bg);
        button.setTextColor(DARK);
    }

    private TextView makeText(String value, int size, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(DARK);
        view.setGravity(Gravity.CENTER_VERTICAL);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private Button makeButton(String value, int size) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(size);
        button.setAllCaps(false);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setPadding(dp(4), 0, dp(4), 0);
        return button;
    }

    private Button makeSmallButton(String value) {
        Button button = new Button(this);
        button.setText(value);
        button.setTextSize(11);
        button.setAllCaps(false);
        button.setPadding(dp(10), 0, dp(10), 0);
        return button;
    }

    private View space(int width) {
        return new View(this);
    }

    private LinearLayout.LayoutParams match(int height) {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height);
    }

    private LinearLayout.LayoutParams weighted(int height, float weight) {
        return new LinearLayout.LayoutParams(0, height, weight);
    }

    private String formatPrice(int value) {
        return String.format(new Locale("ru"), "%,d ₽", value).replace(',', ' ');
    }

    private String formatPriceCompact(int value) {
        return String.format(new Locale("ru"), "%,d", value).replace(',', ' ');
    }

    private String encode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static final class Offer {
        final LocalDate date;
        final int price;
        Offer(LocalDate date, int price) {
            this.date = date;
            this.price = price;
        }
    }

    private static final class BestOffer {
        final LocalDate date;
        final int price;
        BestOffer(LocalDate date, int price) {
            this.date = date;
            this.price = price;
        }
    }
}
