package ru.pobedaradar.app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PriceRadarWorker extends Worker {

    private static final String PREFS = "pobeda_radar";
    private static final String TOKEN_KEY = "travelpayouts_token";
    private static final String LEGACY_DATE_FROM_KEY = "date_from";
    private static final String LEGACY_DATE_TO_KEY = "date_to";

    private static final String LAST_PRICE_PREFIX = "last_price_";
    private static final String MIN_PRICE_PREFIX = "min_price_";
    private static final String LAST_SEEN_PREFIX = "last_seen_";
    private static final String LAST_UPDATE_PREFIX = "last_update_";
    private static final String TREND_PREFIX = "trend_";

    private static final String BACKGROUND_LAST_RUN_KEY = "background_last_run";
    private static final String BACKGROUND_LAST_SUCCESS_KEY = "background_last_success";
    private static final String BACKGROUND_LAST_ERROR_KEY = "background_last_error";

    private static final String CHANNEL_ID = "pobeda_price_alerts";
    private static final double MIN_DROP_PERCENT = 0.05;
    private static final int MAX_CHANGES_IN_NOTIFICATION = 3;
    private static final int TREND_LENGTH = 4;

    private final DateTimeFormatter monthParam = DateTimeFormatter.ofPattern("yyyy-MM");
    private final DateTimeFormatter notificationDate = DateTimeFormatter.ofPattern("dd.MM", new Locale("ru"));

    public PriceRadarWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {
        SharedPreferences prefs = getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit().putLong(BACKGROUND_LAST_RUN_KEY, System.currentTimeMillis()).apply();

        try {
            String token = prefs.getString(TOKEN_KEY, "");
            if (token == null || token.trim().isEmpty()) {
                prefs.edit().putString(BACKGROUND_LAST_ERROR_KEY, "Нет Travelpayouts token").apply();
                return Result.success();
            }

            PriceHistoryStore history = new PriceHistoryStore(getApplicationContext());
            history.seedLegacy(prefs);

            checkDirection(prefs, history, token, true);
            checkDirection(prefs, history, token, false);

            prefs.edit()
                    .putLong(BACKGROUND_LAST_SUCCESS_KEY, System.currentTimeMillis())
                    .remove(BACKGROUND_LAST_ERROR_KEY)
                    .apply();
            return Result.success();
        } catch (Exception e) {
            String message = e.getMessage();
            if (message == null || message.trim().isEmpty()) message = e.getClass().getSimpleName();
            prefs.edit().putString(BACKGROUND_LAST_ERROR_KEY, message).apply();
            return Result.retry();
        }
    }

    private void checkDirection(SharedPreferences prefs, PriceHistoryStore history,
                                String token, boolean outbound) throws Exception {
        String route = routeId(outbound);
        String origin = outbound ? "MOW" : "GZP";
        String destination = outbound ? "GZP" : "MOW";
        Range wide = loadRange(prefs, route, true);
        Range narrow = loadRange(prefs, route, false);

        List<Offer> offers = requestEntireRange(origin, destination, wide.from, wide.to, token);
        Map<LocalDate, Integer> prices = getBestPricesByDate(offers);
        List<PriceChange> changes = new ArrayList<>();
        long now = System.currentTimeMillis();
        SharedPreferences.Editor editor = prefs.edit();

        for (Map.Entry<LocalDate, Integer> entry : prices.entrySet()) {
            LocalDate date = entry.getKey();
            int newPrice = entry.getValue();
            String lastKey = lastPriceKey(date, outbound);
            int oldPrice = prefs.getInt(lastKey, -1);
            int historicalMin = prefs.getInt(minPriceKey(date, outbound), -1);

            if (oldPrice > 0 && newPrice < oldPrice) {
                int drop = oldPrice - newPrice;
                double percent = drop / (double) oldPrice;
                if (percent >= MIN_DROP_PERCENT) {
                    boolean priority = !date.isBefore(narrow.from) && !date.isAfter(narrow.to);
                    changes.add(new PriceChange(date, oldPrice, newPrice, drop, percent, priority));
                }
            }

            appendTrend(prefs, date, outbound, newPrice);
            int newMin = historicalMin <= 0 ? newPrice : Math.min(historicalMin, newPrice);
            editor.putInt(lastKey, newPrice);
            editor.putInt(minPriceKey(date, outbound), newMin);
            editor.putLong(lastSeenKey(date, outbound), now);
            history.record(route, date, now, newPrice);
        }

        editor.putLong(lastUpdateKey(outbound), now).apply();

        if (!changes.isEmpty()) {
            changes.sort(Comparator
                    .comparing((PriceChange c) -> c.priority).reversed()
                    .thenComparing(Comparator.comparingDouble((PriceChange c) -> c.dropPercent).reversed())
                    .thenComparing(c -> c.date));
            sendNotification(outbound, changes);
        }
    }

    private Range loadRange(SharedPreferences prefs, String route, boolean wide) {
        LocalDate today = LocalDate.now();
        LocalDate legacyFrom = parseDate(prefs.getString(LEGACY_DATE_FROM_KEY, today.toString()), today);
        LocalDate legacyTo = parseDate(prefs.getString(LEGACY_DATE_TO_KEY, today.plusDays(30).toString()),
                today.plusDays(30));
        if (legacyFrom.isBefore(today)) legacyFrom = today;
        if (legacyTo.isBefore(legacyFrom)) legacyTo = legacyFrom.plusDays(30);

        String fromKey = (wide ? "wide_from_" : "narrow_from_") + route;
        String toKey = (wide ? "wide_to_" : "narrow_to_") + route;
        LocalDate defaultFrom = legacyFrom;
        LocalDate defaultTo = wide ? legacyTo : min(legacyFrom.plusDays(6), legacyTo);
        LocalDate from = parseDate(prefs.getString(fromKey, defaultFrom.toString()), defaultFrom);
        LocalDate to = parseDate(prefs.getString(toKey, defaultTo.toString()), defaultTo);
        if (from.isBefore(today)) from = today;
        if (to.isBefore(from)) to = from;
        return new Range(from, to);
    }

    private LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }

    private LocalDate parseDate(String value, LocalDate fallback) {
        try {
            return LocalDate.parse(value);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private void sendNotification(boolean outbound, List<PriceChange> changes) {
        Context context = getApplicationContext();
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return;

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Изменения цен Победы", NotificationManager.IMPORTANCE_HIGH);
            channel.setDescription("Pobeda Radar: изменения цен по выбранным диапазонам");
            manager.createNotificationChannel(channel);
        }

        String route = outbound ? "Москва → Газипаша" : "Газипаша → Москва";
        StringBuilder message = new StringBuilder();
        int count = Math.min(MAX_CHANGES_IN_NOTIFICATION, changes.size());
        for (int i = 0; i < count; i++) {
            PriceChange c = changes.get(i);
            if (i > 0) message.append("\n");
            message.append(c.date.format(notificationDate))
                    .append(": ").append(formatPrice(c.newPrice))
                    .append(" ← ").append(formatNumber(c.oldPrice))
                    .append(" (−").append(Math.round(c.dropPercent * 100)).append("%)");
            if (c.priority) message.append(" · узкий диапазон");
        }

        Intent intent = new Intent(context, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, outbound ? 3001 : 3002, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentTitle(route + " — цены снизились")
                .setContentText(message.toString().replace('\n', ' '))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message.toString()))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent);
        manager.notify(outbound ? 4101 : 4102, builder.build());
    }

    private void appendTrend(SharedPreferences prefs, LocalDate date, boolean outbound, int newPrice) {
        String key = TREND_PREFIX + routeId(outbound) + "_" + date;
        String old = prefs.getString(key, "");
        List<Integer> values = new ArrayList<>();
        if (old != null && !old.trim().isEmpty()) {
            for (String part : old.split(",")) {
                try {
                    values.add(Integer.parseInt(part));
                } catch (Exception ignored) {
                }
            }
        }
        if (values.isEmpty() || values.get(values.size() - 1) != newPrice) values.add(newPrice);
        while (values.size() > TREND_LENGTH) values.remove(0);
        StringBuilder out = new StringBuilder();
        for (Integer value : values) {
            if (out.length() > 0) out.append(',');
            out.append(value);
        }
        prefs.edit().putString(key, out.toString()).apply();
    }

    private String routeId(boolean outbound) {
        return outbound ? "MOW_GZP" : "GZP_MOW";
    }

    private String lastPriceKey(LocalDate date, boolean outbound) {
        return LAST_PRICE_PREFIX + routeId(outbound) + "_" + date;
    }

    private String minPriceKey(LocalDate date, boolean outbound) {
        return MIN_PRICE_PREFIX + routeId(outbound) + "_" + date;
    }

    private String lastSeenKey(LocalDate date, boolean outbound) {
        return LAST_SEEN_PREFIX + routeId(outbound) + "_" + date;
    }

    private String lastUpdateKey(boolean outbound) {
        return LAST_UPDATE_PREFIX + routeId(outbound);
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
            if (price > 0) result.add(new Offer(date, price));
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
        if (code == 429) throw new Exception("Слишком много запросов");
        if (code < 200 || code >= 300) throw new Exception("Travelpayouts HTTP " + code);
        JSONObject json = new JSONObject(response);
        if (!json.optBoolean("success", true)) throw new Exception("Travelpayouts вернул ошибку");
        return json;
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

    private String encode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8");
    }

    private String formatPrice(int value) {
        return String.format(new Locale("ru"), "%,d ₽", value).replace(',', ' ');
    }

    private String formatNumber(int value) {
        return String.format(new Locale("ru"), "%,d", value).replace(',', ' ');
    }

    private static final class Range {
        final LocalDate from;
        final LocalDate to;
        Range(LocalDate from, LocalDate to) {
            this.from = from;
            this.to = to;
        }
    }

    private static final class Offer {
        final LocalDate date;
        final int price;
        Offer(LocalDate date, int price) {
            this.date = date;
            this.price = price;
        }
    }

    private static final class PriceChange {
        final LocalDate date;
        final int oldPrice;
        final int newPrice;
        final int dropAmount;
        final double dropPercent;
        final boolean priority;
        PriceChange(LocalDate date, int oldPrice, int newPrice, int dropAmount,
                    double dropPercent, boolean priority) {
            this.date = date;
            this.oldPrice = oldPrice;
            this.newPrice = newPrice;
            this.dropAmount = dropAmount;
            this.dropPercent = dropPercent;
            this.priority = priority;
        }
    }
}
