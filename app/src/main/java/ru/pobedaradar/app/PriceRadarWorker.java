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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PriceRadarWorker extends Worker {

    private static final String PREFS = "pobeda_radar";

    private static final String TOKEN_KEY = "travelpayouts_token";
    private static final String DATE_FROM_KEY = "date_from";
    private static final String DATE_TO_KEY = "date_to";

    private static final String LAST_PRICE_PREFIX = "last_price_";
    private static final String MIN_PRICE_PREFIX = "min_price_";

    private static final String CHANNEL_ID = "pobeda_price_alerts";

    /*
     * Пороговые значения.
     */
    private static final int ABSOLUTE_ALERT_PRICE = 6000;
    private static final double DROP_ALERT_PERCENT = 0.15;

    private final DateTimeFormatter monthParam =
            DateTimeFormatter.ofPattern("yyyy-MM");

    private final DateTimeFormatter notificationDate =
            DateTimeFormatter.ofPattern(
                    "dd.MM.yyyy",
                    new Locale("ru")
            );

    public PriceRadarWorker(
            @NonNull Context context,
            @NonNull WorkerParameters workerParams
    ) {
        super(context, workerParams);
    }

    @NonNull
    @Override
    public Result doWork() {

        try {

            SharedPreferences prefs =
                    getApplicationContext()
                            .getSharedPreferences(
                                    PREFS,
                                    Context.MODE_PRIVATE
                            );

            String token =
                    prefs.getString(
                            TOKEN_KEY,
                            ""
                    );

            if (token.isEmpty()) {
                return Result.success();
            }

            LocalDate today =
                    LocalDate.now();

            LocalDate from =
                    parseStoredDate(
                            prefs.getString(
                                    DATE_FROM_KEY,
                                    today.toString()
                            ),
                            today
                    );

            LocalDate to =
                    parseStoredDate(
                            prefs.getString(
                                    DATE_TO_KEY,
                                    today.plusDays(30).toString()
                            ),
                            today.plusDays(30)
                    );

            if (from.isBefore(today)) {
                from = today;
            }

            if (to.isBefore(from)) {
                to = from.plusDays(30);
            }

            /*
             * Оба направления проверяются,
             * но каждое может создать максимум
             * ОДНО уведомление за проход.
             */
            checkDirection(
                    prefs,
                    token,
                    "MOW",
                    "GZP",
                    true,
                    from,
                    to
            );

            checkDirection(
                    prefs,
                    token,
                    "GZP",
                    "MOW",
                    false,
                    from,
                    to
            );

            return Result.success();

        } catch (Exception e) {

            return Result.retry();
        }
    }

    private void checkDirection(
            SharedPreferences prefs,
            String token,
            String origin,
            String destination,
            boolean outbound,
            LocalDate from,
            LocalDate to
    ) throws Exception {

        List<Offer> offers =
                requestEntireRange(
                        origin,
                        destination,
                        from,
                        to,
                        token
                );

        Map<LocalDate, Integer> bestPrices =
                new LinkedHashMap<>();

        /*
         * Для каждой даты берём
         * минимальную найденную цену.
         */
        for (Offer offer : offers) {

            Integer current =
                    bestPrices.get(
                            offer.date
                    );

            if (current == null
                    || offer.price < current) {

                bestPrices.put(
                        offer.date,
                        offer.price
                );
            }
        }

        /*
         * Лучшее событие этого прохода.
         */
        AlertCandidate bestAlert =
                null;

        for (Map.Entry<LocalDate, Integer> entry
                : bestPrices.entrySet()) {

            LocalDate date =
                    entry.getKey();

            int newPrice =
                    entry.getValue();

            int oldPrice =
                    prefs.getInt(
                            lastPriceKey(
                                    date,
                                    outbound
                            ),
                            -1
                    );

            int historicalMin =
                    prefs.getInt(
                            minPriceKey(
                                    date,
                                    outbound
                            ),
                            -1
                    );

            AlertCandidate candidate =
                    createCandidate(
                            date,
                            newPrice,
                            oldPrice,
                            historicalMin
                    );

            if (candidate != null) {

                if (bestAlert == null
                        || candidate.score > bestAlert.score) {

                    bestAlert =
                            candidate;
                }
            }

            /*
             * Независимо от уведомления
             * обновляем историю всех дат.
             */
            int newHistoricalMin =
                    historicalMin <= 0
                            ? newPrice
                            : Math.min(
                            historicalMin,
                            newPrice
                    );

            prefs.edit()
                    .putInt(
                            lastPriceKey(
                                    date,
                                    outbound
                            ),
                            newPrice
                    )
                    .putInt(
                            minPriceKey(
                                    date,
                                    outbound
                            ),
                            newHistoricalMin
                    )
                    .apply();
        }

        /*
         * За направление — максимум одно уведомление.
         */
        if (bestAlert != null) {

            sendNotification(
                    outbound,
                    bestAlert
            );
        }
    }

    private AlertCandidate createCandidate(
            LocalDate date,
            int newPrice,
            int oldPrice,
            int historicalMin
    ) {

        boolean absoluteLow =
                newPrice <= ABSOLUTE_ALERT_PRICE;

        boolean newHistoricalMinimum =
                historicalMin > 0
                        && newPrice < historicalMin;

        boolean significantDrop =
                false;

        double dropPercent =
                0.0;

        int dropAmount =
                0;

        if (oldPrice > 0
                && newPrice < oldPrice) {

            dropAmount =
                    oldPrice - newPrice;

            dropPercent =
                    dropAmount
                            / (double) oldPrice;

            significantDrop =
                    dropPercent
                            >= DROP_ALERT_PERCENT;
        }

        if (!absoluteLow
                && !newHistoricalMinimum
                && !significantDrop) {

            return null;
        }

        /*
         * SCORE:
         *
         * Сильное процентное падение
         * получает максимальный приоритет.
         *
         * Затем новый исторический минимум.
         *
         * Затем просто цена ниже 6000.
         */
        double score = 0;

        if (significantDrop) {

            score +=
                    10000
                            + dropPercent * 10000;
        }

        if (newHistoricalMinimum) {

            score += 5000;

            if (historicalMin > 0) {

                score +=
                        historicalMin - newPrice;
            }
        }

        if (absoluteLow) {

            score +=
                    2000
                            + (
                            ABSOLUTE_ALERT_PRICE
                                    - newPrice
                    );
        }

        return new AlertCandidate(
                date,
                newPrice,
                oldPrice,
                historicalMin,
                dropAmount,
                dropPercent,
                absoluteLow,
                newHistoricalMinimum,
                significantDrop,
                score
        );
    }

    private void sendNotification(
            boolean outbound,
            AlertCandidate alert
    ) {

        Context context =
                getApplicationContext();

        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
        ) != PackageManager.PERMISSION_GRANTED) {

            return;
        }

        NotificationManager manager =
                (NotificationManager)
                        context.getSystemService(
                                Context.NOTIFICATION_SERVICE
                        );

        if (manager == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= 26) {

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "Низкие цены Победы",
                            NotificationManager.IMPORTANCE_HIGH
                    );

            channel.setDescription(
                    "Уведомления Pobeda Radar о заметном снижении цен"
            );

            manager.createNotificationChannel(
                    channel
            );
        }

        String route =
                outbound
                        ? "Москва → Газипаша"
                        : "Газипаша → Москва";

        String title;

        if (alert.significantDrop) {

            title =
                    "Победа: цена снизилась";

        } else if (
                alert.newHistoricalMinimum
        ) {

            title =
                    "Победа: новый минимум";

        } else {

            title =
                    "Победа: низкая цена";
        }

        StringBuilder message =
                new StringBuilder();

        message.append(route);

        message.append(
                " · "
        );

        message.append(
                formatPrice(
                        alert.newPrice
                )
        );

        message.append(
                " на "
        );

        message.append(
                alert.date.format(
                        notificationDate
                )
        );

        if (alert.dropAmount > 0) {

            message.append(
                    " · ↓"
            );

            message.append(
                    formatNumber(
                            alert.dropAmount
                    )
            );
        }

        if (alert.newHistoricalMinimum) {

            message.append(
                    " · новый минимум"
            );
        }

        Intent openAppIntent =
                new Intent(
                        context,
                        MainActivity.class
                );

        openAppIntent.setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
        );

        PendingIntent pendingIntent =
                PendingIntent.getActivity(
                        context,
                        outbound
                                ? 1001
                                : 1002,
                        openAppIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT
                                | PendingIntent.FLAG_IMMUTABLE
                );

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(
                        context,
                        CHANNEL_ID
                )
                        .setSmallIcon(
                                android.R.drawable.stat_notify_more
                        )
                        .setContentTitle(
                                title
                        )
                        .setContentText(
                                message.toString()
                        )
                        .setStyle(
                                new NotificationCompat.BigTextStyle()
                                        .bigText(
                                                message.toString()
                                        )
                        )
                        .setPriority(
                                NotificationCompat.PRIORITY_HIGH
                        )
                        .setAutoCancel(true)
                        .setOnlyAlertOnce(true)
                        .setContentIntent(
                                pendingIntent
                        );

        /*
         * КЛЮЧЕВОЕ ИЗМЕНЕНИЕ:
         *
         * один постоянный ID на каждое направление.
         *
         * Следующая тревога не создаёт новую карточку,
         * а ОБНОВЛЯЕТ существующую.
         */
        int notificationId =
                outbound
                        ? 91101
                        : 91102;

        manager.notify(
                notificationId,
                builder.build()
        );
    }

    // ============================================================
    // API
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
                YearMonth.from(from);

        YearMonth lastMonth =
                YearMonth.from(to);

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

            result.add(
                    new Offer(
                            date,
                            price
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
                "PobedaRadar-Worker/0.9"
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
                    "Travelpayouts: слишком много запросов"
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

    private LocalDate parseStoredDate(
            String value,
            LocalDate fallback
    ) {

        try {

            return LocalDate.parse(
                    value
            );

        } catch (Exception e) {

            return fallback;
        }
    }

    private String routeHistoryId(
            boolean outbound
    ) {

        return outbound
                ? "MOW_GZP"
                : "GZP_MOW";
    }

    private String lastPriceKey(
            LocalDate date,
            boolean outbound
    ) {

        return LAST_PRICE_PREFIX
                + routeHistoryId(
                outbound
        )
                + "_"
                + date;
    }

    private String minPriceKey(
            LocalDate date,
            boolean outbound
    ) {

        return MIN_PRICE_PREFIX
                + routeHistoryId(
                outbound
        )
                + "_"
                + date;
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

    private String formatNumber(
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

    // ============================================================
    // DATA
    // ============================================================

    private static class Offer {

        final LocalDate date;
        final int price;

        Offer(
                LocalDate date,
                int price
        ) {

            this.date = date;
            this.price = price;
        }
    }

    private static class AlertCandidate {

        final LocalDate date;

        final int newPrice;
        final int oldPrice;
        final int historicalMin;

        final int dropAmount;
        final double dropPercent;

        final boolean absoluteLow;
        final boolean newHistoricalMinimum;
        final boolean significantDrop;

        final double score;

        AlertCandidate(
                LocalDate date,
                int newPrice,
                int oldPrice,
                int historicalMin,
                int dropAmount,
                double dropPercent,
                boolean absoluteLow,
                boolean newHistoricalMinimum,
                boolean significantDrop,
                double score
        ) {

            this.date =
                    date;

            this.newPrice =
                    newPrice;

            this.oldPrice =
                    oldPrice;

            this.historicalMin =
                    historicalMin;

            this.dropAmount =
                    dropAmount;

            this.dropPercent =
                    dropPercent;

            this.absoluteLow =
                    absoluteLow;

            this.newHistoricalMinimum =
                    newHistoricalMinimum;

            this.significantDrop =
                    significantDrop;

            this.score =
                    score;
        }
    }
}
