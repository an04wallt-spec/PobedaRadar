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

    private static final String PREFS =
            "pobeda_radar";

    private static final String TOKEN_KEY =
            "travelpayouts_token";

    private static final String DATE_FROM_KEY =
            "date_from";

    private static final String DATE_TO_KEY =
            "date_to";

    private static final String LAST_PRICE_PREFIX =
            "last_price_";

    private static final String MIN_PRICE_PREFIX =
            "min_price_";

    private static final String LAST_UPDATE_PREFIX =
            "last_update_";

    /*
     * Крайняя дата хранится отдельно
     * для каждого направления:
     *
     * deadline_MOW_GZP
     * deadline_GZP_MOW
     */
    private static final String DEADLINE_PREFIX =
            "deadline_";

    /*
     * Последние четыре разные цены
     * для каждой даты.
     */
    private static final String TREND_PREFIX =
            "trend_";

    /*
     * Диагностика фоновой работы.
     */
    private static final String BACKGROUND_LAST_RUN_KEY =
            "background_last_run";

    private static final String BACKGROUND_LAST_SUCCESS_KEY =
            "background_last_success";

    private static final String BACKGROUND_LAST_ERROR_KEY =
            "background_last_error";

    private static final String CHANNEL_ID =
            "pobeda_price_alerts";

    /*
     * Фиксированного порога цены больше нет.
     *
     * Уведомляем о снижении от 5%.
     *
     * Это не "хорошая цена в рублях",
     * а именно заметное движение цены.
     */
    private static final double MIN_DROP_PERCENT =
            0.05;

    /*
     * В одном уведомлении показываем
     * максимум три наиболее интересных изменения.
     */
    private static final int MAX_CHANGES_IN_NOTIFICATION =
            3;

    /*
     * Для тренда храним четыре последних
     * отличающихся значения.
     */
    private static final int TREND_LENGTH =
            4;

    private final DateTimeFormatter monthParam =
            DateTimeFormatter.ofPattern(
                    "yyyy-MM"
            );

    private final DateTimeFormatter notificationDate =
            DateTimeFormatter.ofPattern(
                    "dd.MM",
                    new Locale("ru")
            );

    public PriceRadarWorker(
            @NonNull Context context,
            @NonNull WorkerParameters workerParams
    ) {
        super(
                context,
                workerParams
        );
    }

    // ============================================================
    // ОСНОВНОЙ ФОНОВЫЙ ЗАПУСК
    // ============================================================

    @NonNull
    @Override
    public Result doWork() {

        SharedPreferences prefs =
                getApplicationContext()
                        .getSharedPreferences(
                                PREFS,
                                Context.MODE_PRIVATE
                        );

        /*
         * Сам факт запуска Worker.
         */
        prefs.edit()
                .putLong(
                        BACKGROUND_LAST_RUN_KEY,
                        System.currentTimeMillis()
                )
                .apply();

        try {

            String token =
                    prefs.getString(
                            TOKEN_KEY,
                            ""
                    );

            if (token == null
                    || token.trim().isEmpty()) {

                prefs.edit()
                        .putString(
                                BACKGROUND_LAST_ERROR_KEY,
                                "Нет Travelpayouts token"
                        )
                        .apply();

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

            /*
             * Прошедшие даты не проверяем.
             */
            if (from.isBefore(today)) {
                from = today;
            }

            if (to.isBefore(from)) {
                to = from.plusDays(30);
            }

            /*
             * Москва → Газипаша.
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

            /*
             * Газипаша → Москва.
             */
            checkDirection(
                    prefs,
                    token,
                    "GZP",
                    "MOW",
                    false,
                    from,
                    to
            );

            /*
             * Оба направления успешно проверены.
             */
            prefs.edit()
                    .putLong(
                            BACKGROUND_LAST_SUCCESS_KEY,
                            System.currentTimeMillis()
                    )
                    .remove(
                            BACKGROUND_LAST_ERROR_KEY
                    )
                    .apply();

            return Result.success();

        } catch (Exception e) {

            String errorMessage =
                    e.getMessage();

            if (errorMessage == null
                    || errorMessage.trim().isEmpty()) {

                errorMessage =
                        e.getClass()
                                .getSimpleName();
            }

            prefs.edit()
                    .putString(
                            BACKGROUND_LAST_ERROR_KEY,
                            errorMessage
                    )
                    .apply();

            return Result.retry();
        }
    }

    // ============================================================
    // ПРОВЕРКА ОДНОГО НАПРАВЛЕНИЯ
    // ============================================================

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
                getBestPricesByDate(
                        offers
                );

        /*
         * Крайняя дата именно этого направления.
         *
         * Если пользователь её ещё не задавал,
         * используем конец диапазона.
         * То есть ничего не теряем.
         */
        LocalDate deadline =
                getDeadline(
                        prefs,
                        outbound,
                        from,
                        to
                );

        /*
         * Находим лучшую цену
         * во всём текущем диапазоне.
         */
        BestCurrentOffer bestCurrent =
                findBestCurrentOffer(
                        bestPrices
                );

        List<PriceChange> changes =
                new ArrayList<>();

        for (
                Map.Entry<LocalDate, Integer> entry
                : bestPrices.entrySet()
        ) {

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

            /*
             * Сначала анализируем изменение
             * относительно предыдущей проверки.
             */
            if (oldPrice > 0
                    && newPrice < oldPrice) {

                int dropAmount =
                        oldPrice - newPrice;

                double dropPercent =
                        dropAmount
                                / (double) oldPrice;

                boolean newHistoricalMinimum =
                        historicalMin > 0
                                && newPrice < historicalMin;

                /*
                 * Важная дата:
                 * не позже крайней даты вылета.
                 */
                boolean priorityDate =
                        !date.isAfter(
                                deadline
                        );

                /*
                 * Уведомление вызывается движением цены,
                 * а не произвольной стоимостью билета.
                 */
                if (dropPercent
                        >= MIN_DROP_PERCENT) {

                    changes.add(
                            new PriceChange(
                                    date,
                                    oldPrice,
                                    newPrice,
                                    dropAmount,
                                    dropPercent,
                                    newHistoricalMinimum,
                                    priorityDate
                            )
                    );
                }
            }

            /*
             * Историю тренда обновляем
             * при каждом фактическом значении.
             */
            appendTrend(
                    prefs,
                    date,
                    outbound,
                    newPrice
            );

            /*
             * Обновляем последнюю цену
             * и исторический минимум даты.
             */
            int newMinimum =
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
                            newMinimum
                    )
                    .apply();
        }

        /*
         * Отмечаем время успешной проверки
         * этого направления.
         *
         * Старый MainActivity уже умеет
         * отображать last_update_*.
         */
        prefs.edit()
                .putLong(
                        lastUpdateKey(
                                outbound
                        ),
                        System.currentTimeMillis()
                )
                .apply();

        /*
         * Если существенных снижений нет —
         * никаких уведомлений не создаём.
         */
        if (changes.isEmpty()) {
            return;
        }

        /*
         * --------------------------------------------------------
         * ПРИОРИТЕТ
         * --------------------------------------------------------
         *
         * 1. Сначала изменения до крайней даты.
         * 2. Затем более сильное процентное падение.
         * 3. Затем большее падение в рублях.
         * 4. При равенстве — более ранняя дата.
         *
         * Даты после крайней даты НЕ ИГНОРИРУЮТСЯ.
         */
        changes.sort(
                Comparator
                        .comparing(
                                (PriceChange item) ->
                                        item.priorityDate
                        )
                        .reversed()
                        .thenComparing(
                                Comparator.comparingDouble(
                                        (PriceChange item) ->
                                                item.dropPercent
                                ).reversed()
                        )
                        .thenComparing(
                                Comparator.comparingInt(
                                        (PriceChange item) ->
                                                item.dropAmount
                                ).reversed()
                        )
                        .thenComparing(
                                item ->
                                        item.date
                        )
        );

        sendNotification(
                prefs,
                outbound,
                deadline,
                changes,
                bestCurrent
        );
    }

    // ============================================================
    // УВЕДОМЛЕНИЕ
    // ============================================================

    private void sendNotification(
            SharedPreferences prefs,
            boolean outbound,
            LocalDate deadline,
            List<PriceChange> changes,
            BestCurrentOffer bestCurrent
    ) {

        Context context =
                getApplicationContext();

        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat
                .checkSelfPermission(
                        context,
                        Manifest.permission
                                .POST_NOTIFICATIONS
                )
                != PackageManager
                .PERMISSION_GRANTED) {

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
                            "Изменения цен Победы",
                            NotificationManager
                                    .IMPORTANCE_HIGH
                    );

            channel.setDescription(
                    "Pobeda Radar: изменения цен по выбранному диапазону дат"
            );

            manager.createNotificationChannel(
                    channel
            );
        }

        String route =
                outbound
                        ? "Москва → Газипаша"
                        : "Газипаша → Москва";

        String title =
                route
                        + " — цены снизились";

        StringBuilder message =
                new StringBuilder();

        int count =
                Math.min(
                        MAX_CHANGES_IN_NOTIFICATION,
                        changes.size()
                );

        for (int i = 0; i < count; i++) {

            PriceChange change =
                    changes.get(i);

            if (i > 0) {
                message.append("\n");
            }

            message.append(
                    change.date.format(
                            notificationDate
                    )
            );

            message.append(
                    ": "
            );

            message.append(
                    formatPrice(
                            change.newPrice
                    )
            );

            message.append(
                    " ← "
            );

            message.append(
                    formatNumber(
                            change.oldPrice
                    )
            );

            message.append(
                    " (−"
            );

            message.append(
                    Math.round(
                            change.dropPercent
                                    * 100
                    )
            );

            message.append(
                    "%)"
            );

            /*
             * Явно отмечаем даты,
             * попадающие в критическое окно.
             */
            if (change.priorityDate) {

                message.append(
                        " · до крайней даты"
                );
            }

            if (change.newHistoricalMinimum) {

                message.append(
                        " · мин. даты"
                );
            }
        }

        /*
         * Всегда даём контекст:
         * какая цена лучшая сейчас вообще.
         */
        if (bestCurrent != null) {

            message.append(
                    "\nЛучшая сейчас: "
            );

            message.append(
                    formatPrice(
                            bestCurrent.price
                    )
            );

            message.append(
                    " · "
            );

            message.append(
                    bestCurrent.date.format(
                            notificationDate
                    )
            );
        }

        /*
         * Для самого приоритетного изменения
         * показываем тренд последних проверок,
         * если накоплено хотя бы три разных значения.
         */
        if (!changes.isEmpty()) {

            PriceChange mainChange =
                    changes.get(0);

            List<Integer> trend =
                    readTrend(
                            prefs,
                            mainChange.date,
                            outbound
                    );

            if (trend.size() >= 3) {

                message.append(
                        "\nТренд "
                );

                message.append(
                        mainChange.date.format(
                                notificationDate
                        )
                );

                message.append(
                        ": "
                );

                for (int i = 0;
                     i < trend.size();
                     i++) {

                    if (i > 0) {
                        message.append("→");
                    }

                    message.append(
                            formatNumber(
                                    trend.get(i)
                            )
                    );
                }
            }
        }

        Intent openAppIntent =
                new Intent(
                        context,
                        MainActivityV14.class
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
                                android.R.drawable
                                        .stat_notify_more
                        )
                        .setContentTitle(
                                title
                        )
                        .setContentText(
                                message.toString()
                        )
                        .setStyle(
                                new NotificationCompat
                                        .BigTextStyle()
                                        .bigText(
                                                message.toString()
                                        )
                        )
                        .setPriority(
                                NotificationCompat
                                        .PRIORITY_HIGH
                        )
                        .setAutoCancel(
                                true
                        )
                        .setOnlyAlertOnce(
                                true
                        )
                        .setContentIntent(
                                pendingIntent
                        );

        /*
         * По-прежнему только одно
         * уведомление на направление.
         *
         * Следующее заменит предыдущее.
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
    // КРАЙНЯЯ ДАТА
    // ============================================================

    private LocalDate getDeadline(
            SharedPreferences prefs,
            boolean outbound,
            LocalDate from,
            LocalDate to
    ) {

        String stored =
                prefs.getString(
                        deadlineKey(
                                outbound
                        ),
                        ""
                );

        LocalDate deadline =
                parseStoredDate(
                        stored,
                        to
                );

        /*
         * Крайняя дата всегда должна
         * находиться внутри диапазона.
         */
        if (deadline.isBefore(from)) {
            deadline = from;
        }

        if (deadline.isAfter(to)) {
            deadline = to;
        }

        return deadline;
    }

    private String deadlineKey(
            boolean outbound
    ) {

        return DEADLINE_PREFIX
                + routeHistoryId(
                        outbound
                );
    }

    // ============================================================
    // ТРЕНД
    // ============================================================

    private String trendKey(
            LocalDate date,
            boolean outbound
    ) {

        return TREND_PREFIX
                + routeHistoryId(
                        outbound
                )
                + "_"
                + date;
    }

    private void appendTrend(
            SharedPreferences prefs,
            LocalDate date,
            boolean outbound,
            int newPrice
    ) {

        List<Integer> values =
                readTrend(
                        prefs,
                        date,
                        outbound
                );

        /*
         * Одинаковые подряд значения
         * в тренде не нужны.
         */
        if (!values.isEmpty()
                && values.get(
                        values.size() - 1
                ) == newPrice) {

            return;
        }

        values.add(
                newPrice
        );

        while (values.size()
                > TREND_LENGTH) {

            values.remove(0);
        }

        StringBuilder stored =
                new StringBuilder();

        for (int i = 0;
             i < values.size();
             i++) {

            if (i > 0) {
                stored.append(",");
            }

            stored.append(
                    values.get(i)
            );
        }

        prefs.edit()
                .putString(
                        trendKey(
                                date,
                                outbound
                        ),
                        stored.toString()
                )
                .apply();
    }

    private List<Integer> readTrend(
            SharedPreferences prefs,
            LocalDate date,
            boolean outbound
    ) {

        List<Integer> result =
                new ArrayList<>();

        String stored =
                prefs.getString(
                        trendKey(
                                date,
                                outbound
                        ),
                        ""
                );

        if (stored == null
                || stored.trim().isEmpty()) {

            return result;
        }

        String[] parts =
                stored.split(",");

        for (String part : parts) {

            try {

                int value =
                        Integer.parseInt(
                                part.trim()
                        );

                if (value > 0) {
                    result.add(value);
                }

            } catch (Exception ignored) {
            }
        }

        return result;
    }

    // ============================================================
    // ТЕКУЩАЯ ЛУЧШАЯ ЦЕНА
    // ============================================================

    private BestCurrentOffer findBestCurrentOffer(
            Map<LocalDate, Integer> prices
    ) {

        BestCurrentOffer best =
                null;

        for (
                Map.Entry<LocalDate, Integer> entry
                : prices.entrySet()
        ) {

            if (best == null
                    || entry.getValue()
                    < best.price
                    || (
                    entry.getValue()
                            == best.price
                            && entry.getKey()
                            .isBefore(
                                    best.date
                            )
            )) {

                best =
                        new BestCurrentOffer(
                                entry.getKey(),
                                entry.getValue()
                        );
            }
        }

        return best;
    }

    private Map<LocalDate, Integer> getBestPricesByDate(
            List<Offer> offers
    ) {

        Map<LocalDate, Integer> prices =
                new LinkedHashMap<>();

        for (Offer offer : offers) {

            Integer current =
                    prices.get(
                            offer.date
                    );

            if (current == null
                    || offer.price < current) {

                prices.put(
                        offer.date,
                        offer.price
                );
            }
        }

        return prices;
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

            int transfers =
                    item.optInt(
                            "transfers",
                            0
                    );

            /*
             * Только прямые.
             */
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
                        )
                                .openConnection();

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
                "PobedaRadar-Worker/0.15"
        );

        int code =
                connection.getResponseCode();

        InputStream stream =
                code >= 200
                        && code < 300
                        ? connection
                        .getInputStream()
                        : connection
                        .getErrorStream();

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
    // КЛЮЧИ И UTIL
    // ============================================================

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

    private String lastUpdateKey(
            boolean outbound
    ) {

        return LAST_UPDATE_PREFIX
                + routeHistoryId(
                        outbound
                );
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

        while (
                (line = reader.readLine())
                        != null
        ) {

            builder.append(line);
        }

        reader.close();

        return builder.toString();
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

            this.date =
                    date;

            this.price =
                    price;
        }
    }

    private static class BestCurrentOffer {

        final LocalDate date;
        final int price;

        BestCurrentOffer(
                LocalDate date,
                int price
        ) {

            this.date =
                    date;

            this.price =
                    price;
        }
    }

    private static class PriceChange {

        final LocalDate date;

        final int oldPrice;
        final int newPrice;

        final int dropAmount;
        final double dropPercent;

        final boolean newHistoricalMinimum;
        final boolean priorityDate;

        PriceChange(
                LocalDate date,
                int oldPrice,
                int newPrice,
                int dropAmount,
                double dropPercent,
                boolean newHistoricalMinimum,
                boolean priorityDate
        ) {

            this.date =
                    date;

            this.oldPrice =
                    oldPrice;

            this.newPrice =
                    newPrice;

            this.dropAmount =
                    dropAmount;

            this.dropPercent =
                    dropPercent;

            this.newHistoricalMinimum =
                    newHistoricalMinimum;

            this.priorityDate =
                    priorityDate;
        }
    }
}
