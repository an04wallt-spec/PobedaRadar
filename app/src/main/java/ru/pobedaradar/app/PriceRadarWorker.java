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
     * Условия тревоги.
     *
     * 1. Цена 6000 ₽ или ниже.
     * 2. Новый исторический минимум.
     * 3. Снижение минимум на 15% относительно прошлой проверки.
     */
    private static final int ABSOLUTE_ALERT_PRICE = 6000;
    private static final double DROP_ALERT_PERCENT = 0.15;

    private final DateTimeFormatter monthParam =
            DateTimeFormatter.ofPattern("yyyy-MM");

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

            /*
             * Пока пользователь хотя бы один раз
             * не сохранил токен в основном приложении,
             * фоновому радару делать нечего.
             */
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
             * Проверяем оба направления.
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

            /*
             * При временной сетевой ошибке WorkManager
             * попробует ещё раз позднее.
             */
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

        /*
         * Для каждой даты выбираем
         * минимальную найденную цену Победы.
         */
        Map<LocalDate, Integer> bestPrices =
                new LinkedHashMap<>();

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

            boolean alert =
                    shouldAlert(
                            oldPrice,
                            historicalMin,
                            newPrice
                    );

            /*
             * Уведомляем ДО записи нового значения,
             * чтобы корректно сравнить со старой ценой.
             */
            if (alert) {

                sendNotification(
                        outbound,
                        date,
                        newPrice,
                        oldPrice,
                        historicalMin
                );
            }

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
    }

    private boolean shouldAlert(
            int oldPrice,
            int historicalMin,
            int newPrice
    ) {

        /*
         * Абсолютно низкая цена.
         */
        if (newPrice <= ABSOLUTE_ALERT_PRICE) {
            return true;
        }

        /*
         * Новый исторический минимум.
         */
        if (historicalMin > 0
                && newPrice < historicalMin) {

            return true;
        }

        /*
         * Падение минимум на 15%.
         */
        if (oldPrice > 0
                && newPrice < oldPrice) {

            double drop =
                    (oldPrice - newPrice)
                            / (double) oldPrice;

            return drop >= DROP_ALERT_PERCENT;
        }

        return false;
    }

    private void sendNotification(
            boolean outbound,
            LocalDate date,
            int newPrice,
            int oldPrice,
            int historicalMin
    ) {

        Context context =
                getApplicationContext();

        /*
         * Android 13+ требует явного разрешения.
         */
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

        /*
         * Канал уведомлений Android 8+.
         */
        if (Build.VERSION.SDK_INT >= 26) {

            NotificationChannel channel =
                    new NotificationChannel(
                            CHANNEL_ID,
                            "Низкие цены Победы",
                            NotificationManager.IMPORTANCE_HIGH
                    );

            channel.setDescription(
                    "Уведомления Pobeda Radar о снижении цен"
            );

            manager.createNotificationChannel(
                    channel
            );
        }

        String route =
                outbound
                        ? "Москва → Газипаша"
                        : "Газипаша → Москва";

        StringBuilder message =
                new StringBuilder();

        message.append(
                formatPrice(newPrice)
        );

        message.append(
                " на "
        );

        message.append(
                date.format(
                        DateTimeFormatter.ofPattern(
                                "dd.MM.yyyy"
                        )
                )
        );

        if (oldPrice > 0
                && newPrice < oldPrice) {

            message.append(
                    " · ↓"
            );

            message.append(
                    formatNumber(
                            oldPrice - newPrice
                    )
            );
        }

        if (historicalMin > 0
                && newPrice < historicalMin) {

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
                        1001,
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
                                "Победа: интересная цена"
                        )
                        .setContentText(
                                route
                                        + " · "
                                        + message
                        )
                        .setStyle(
                                new NotificationCompat.BigTextStyle()
                                        .bigText(
                                                route
                                                        + "\n"
                                                        + message
                                        )
                        )
                        .setPriority(
                                NotificationCompat.PRIORITY_HIGH
                        )
                        .setAutoCancel(true)
                        .setContentIntent(
                                pendingIntent
                        );

        /*
         * Для каждой даты/маршрута свой ID.
         * Поэтому уведомления разных дат
         * не будут беспорядочно перетирать друг друга.
         */
        int notificationId =
                Math.abs(
                        (
                                route
                                        + date.toString()
                        ).hashCode()
                );

        manager.notify(
                notificationId,
                builder.build()
        );
    }

    /*
     * ============================================================
     * ЗАПРОС ДАННЫХ
     * ============================================================
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

        /*
         * ВАЖНО:
         * это тот же prices_for_dates,
         * который уже работает в основном приложении.
         */
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

            /*
             * Только Победа.
             */
            if (!"DP".equalsIgnoreCase(
                    airline
            )) {
                continue;
            }

            /*
             * Только прямые рейсы.
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
                "PobedaRadar-Worker/0.8"
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

    /*
     * ============================================================
     * UTIL
     * ============================================================
     */

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
}
