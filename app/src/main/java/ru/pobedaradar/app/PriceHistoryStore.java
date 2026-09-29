package ru.pobedaradar.app;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Долговременная история наблюдений цен.
 *
 * Старые SharedPreferences не заменяются и не удаляются: база работает
 * параллельно с ними и используется для накопительной аналитики.
 */
public final class PriceHistoryStore extends SQLiteOpenHelper {

    private static final String DB_NAME = "pobeda_price_history.db";
    private static final int DB_VERSION = 1;
    private static final String TABLE = "observations";
    private static final String LEGACY_MIGRATION_KEY = "history_legacy_seeded_v1";

    public PriceHistoryStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE + " (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "route TEXT NOT NULL," +
                "flight_date TEXT NOT NULL," +
                "observed_at INTEGER NOT NULL," +
                "price INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX idx_observations_route_date_time ON " + TABLE +
                "(route, flight_date, observed_at)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Версия 1. Историю при будущих обновлениях не удалять.
    }

    public void record(String route, LocalDate flightDate, long observedAt, int price) {
        if (route == null || flightDate == null || price <= 0) return;
        ContentValues values = new ContentValues();
        values.put("route", route);
        values.put("flight_date", flightDate.toString());
        values.put("observed_at", observedAt);
        values.put("price", price);
        getWritableDatabase().insert(TABLE, null, values);
    }

    /**
     * Однократно переносит в новую БД то, что реально сохранилось в старой схеме:
     * последнюю известную цену по каждой дате. Минимумы не превращаем в фиктивные
     * наблюдения, потому что неизвестно, когда они были зафиксированы.
     */
    public void seedLegacy(SharedPreferences prefs) {
        if (prefs.getBoolean(LEGACY_MIGRATION_KEY, false)) return;

        Map<String, ?> all = prefs.getAll();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (Map.Entry<String, ?> entry : all.entrySet()) {
                String key = entry.getKey();
                if (!key.startsWith("last_price_")) continue;
                Object raw = entry.getValue();
                if (!(raw instanceof Integer)) continue;

                String tail = key.substring("last_price_".length());
                String route;
                String dateText;
                if (tail.startsWith("MOW_GZP_")) {
                    route = "MOW_GZP";
                    dateText = tail.substring("MOW_GZP_".length());
                } else if (tail.startsWith("GZP_MOW_")) {
                    route = "GZP_MOW";
                    dateText = tail.substring("GZP_MOW_".length());
                } else {
                    continue;
                }

                try {
                    LocalDate date = LocalDate.parse(dateText);
                    int price = (Integer) raw;
                    long seen = prefs.getLong("last_seen_" + route + "_" + dateText,
                            System.currentTimeMillis());
                    if (price > 0 && !hasAny(db, route, dateText)) {
                        ContentValues values = new ContentValues();
                        values.put("route", route);
                        values.put("flight_date", date.toString());
                        values.put("observed_at", seen);
                        values.put("price", price);
                        db.insert(TABLE, null, values);
                    }
                } catch (Exception ignored) {
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        prefs.edit().putBoolean(LEGACY_MIGRATION_KEY, true).apply();
    }

    private boolean hasAny(SQLiteDatabase db, String route, String date) {
        try (Cursor c = db.rawQuery(
                "SELECT 1 FROM " + TABLE + " WHERE route=? AND flight_date=? LIMIT 1",
                new String[]{route, date})) {
            return c.moveToFirst();
        }
    }

    public Recommendation recommend(
            String route,
            LocalDate narrowFrom,
            LocalDate narrowTo,
            LocalDate wideFrom,
            LocalDate wideTo
    ) {
        WindowStats narrow = stats(route, narrowFrom, narrowTo);
        WindowStats wide = stats(route, wideFrom, wideTo);

        if (narrow.latestCount == 0) {
            return new Recommendation("НАБЛЮДАТЬ", 0, narrow.observationCount,
                    "Недостаточно накопленных данных");
        }

        int score = 0;
        StringBuilder reason = new StringBuilder();

        if (narrow.previousCount > 0 && narrow.previousAverage > 0) {
            double move = (narrow.latestAverage - narrow.previousAverage) /
                    narrow.previousAverage;
            if (move >= 0.025) {
                score += 2;
                appendReason(reason, "цена растёт");
            } else if (move <= -0.025) {
                score -= 2;
                appendReason(reason, "цена снижается");
            }
        }

        if (narrow.minimumAverage > 0) {
            double toMin = narrow.latestAverage / narrow.minimumAverage;
            if (toMin <= 1.04) {
                score += 2;
                appendReason(reason, "цена близка к минимуму истории");
            } else if (toMin >= 1.15) {
                score -= 1;
                appendReason(reason, "цена заметно выше исторического минимума");
            }
        }

        if (wide.latestCount > 0 && wide.previousCount > 0 && wide.previousAverage > 0) {
            double wideMove = (wide.latestAverage - wide.previousAverage) /
                    wide.previousAverage;
            if (wideMove >= 0.02) score += 1;
            if (wideMove <= -0.02) score -= 1;
        }

        int month = narrowFrom.getMonthValue();
        double seasonal = seasonalAverage(route, month);
        if (seasonal > 0) {
            if (narrow.latestAverage <= seasonal * 0.95) {
                score += 1;
                appendReason(reason, "ниже сезонного среднего");
            } else if (narrow.latestAverage >= seasonal * 1.10) {
                score -= 1;
                appendReason(reason, "выше сезонного среднего");
            }
        }

        long days = ChronoUnit.DAYS.between(LocalDate.now(), narrowFrom);
        if (days <= 14) {
            score += 2;
            appendReason(reason, "до вылета мало времени");
        } else if (days <= 30) {
            score += 1;
        }

        // Пока выборка небольшая, не выдаём сильную рекомендацию без явного сигнала.
        int observations = narrow.observationCount + wide.observationCount;
        String verdict;
        if (score >= 2) verdict = "ПОКУПКА";
        else if (score <= -2) verdict = "ПОДОЖДАТЬ";
        else verdict = "НАБЛЮДАТЬ";

        int confidence;
        if (observations >= 120) confidence = 3;
        else if (observations >= 30) confidence = 2;
        else confidence = 1;

        if (reason.length() == 0) {
            reason.append("явного ценового сигнала пока нет");
        }

        return new Recommendation(verdict, confidence, observations, reason.toString());
    }

    private WindowStats stats(String route, LocalDate from, LocalDate to) {
        WindowStats result = new WindowStats();
        Map<String, DateSeries> byDate = new HashMap<>();

        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT flight_date, observed_at, price FROM " + TABLE +
                        " WHERE route=? AND flight_date>=? AND flight_date<=?" +
                        " ORDER BY flight_date ASC, observed_at DESC, id DESC",
                new String[]{route, from.toString(), to.toString()})) {

            while (c.moveToNext()) {
                String date = c.getString(0);
                int price = c.getInt(2);
                DateSeries series = byDate.get(date);
                if (series == null) {
                    series = new DateSeries();
                    byDate.put(date, series);
                }
                result.observationCount++;
                if (series.latest <= 0) {
                    series.latest = price;
                } else if (series.previous <= 0) {
                    series.previous = price;
                }
                if (series.minimum <= 0 || price < series.minimum) {
                    series.minimum = price;
                }
            }
        }

        double latestSum = 0;
        double previousSum = 0;
        double minSum = 0;
        for (DateSeries series : byDate.values()) {
            if (series.latest > 0) {
                latestSum += series.latest;
                result.latestCount++;
            }
            if (series.previous > 0) {
                previousSum += series.previous;
                result.previousCount++;
            }
            if (series.minimum > 0) {
                minSum += series.minimum;
                result.minimumCount++;
            }
        }

        if (result.latestCount > 0) result.latestAverage = latestSum / result.latestCount;
        if (result.previousCount > 0) result.previousAverage = previousSum / result.previousCount;
        if (result.minimumCount > 0) result.minimumAverage = minSum / result.minimumCount;
        return result;
    }

    private double seasonalAverage(String route, int month) {
        String mm = String.format(Locale.US, "%02d", month);
        try (Cursor c = getReadableDatabase().rawQuery(
                "SELECT AVG(price) FROM " + TABLE +
                        " WHERE route=? AND substr(flight_date,6,2)=?",
                new String[]{route, mm})) {
            if (c.moveToFirst() && !c.isNull(0)) return c.getDouble(0);
        }
        return 0;
    }

    private static void appendReason(StringBuilder builder, String text) {
        if (builder.length() > 0) builder.append("; ");
        builder.append(text);
    }

    private static final class DateSeries {
        int latest;
        int previous;
        int minimum;
    }

    private static final class WindowStats {
        int observationCount;
        int latestCount;
        int previousCount;
        int minimumCount;
        double latestAverage;
        double previousAverage;
        double minimumAverage;
    }

    public static final class Recommendation {
        public final String verdict;
        public final int confidence;
        public final int observations;
        public final String reason;

        Recommendation(String verdict, int confidence, int observations, String reason) {
            this.verdict = verdict;
            this.confidence = confidence;
            this.observations = observations;
            this.reason = reason;
        }
    }
}
