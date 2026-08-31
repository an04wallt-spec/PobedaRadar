package ru.pobedaradar.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class MainActivityV14 extends MainActivity {

    private static final String APP_VERSION = "v0.14";

    private static final String PREFS =
            "pobeda_radar";

    private static final String BACKGROUND_LAST_RUN_KEY =
            "background_last_run";

    private static final String BACKGROUND_LAST_SUCCESS_KEY =
            "background_last_success";

    private static final String BACKGROUND_LAST_ERROR_KEY =
            "background_last_error";

    /*
     * WorkManager не обязан запускаться ровно каждые 3 часа.
     * Поэтому тревогу показываем только если успешной проверки
     * не было больше 5 часов.
     */
    private static final long STALE_AFTER_MS =
            TimeUnit.HOURS.toMillis(5);

    private static final int GREEN =
            Color.rgb(35, 135, 70);

    private static final int RED =
            Color.rgb(210, 30, 30);

    private static final int GREY =
            Color.rgb(105, 105, 105);

    private final DateTimeFormatter timeFormat =
            DateTimeFormatter.ofPattern(
                    "HH:mm",
                    new Locale("ru")
            );

    private SharedPreferences diagnosticsPrefs;

    private TextView footerStatus;


    @Override
    protected void onCreate(
            Bundle savedInstanceState
    ) {

        /*
         * Сначала полностью запускаем старую рабочую MainActivity.
         *
         * Вся логика поиска цен, истории, дат,
         * списков и ручного обновления остаётся прежней.
         */
        super.onCreate(
                savedInstanceState
        );

        diagnosticsPrefs =
                getSharedPreferences(
                        PREFS,
                        MODE_PRIVATE
                );

        /*
         * После создания старого интерфейса
         * аккуратно используем место,
         * где раньше отображалась версия.
         */
        attachBackgroundStatus();

        updateBackgroundStatus();
    }


    @Override
    protected void onResume() {

        super.onResume();

        if (diagnosticsPrefs == null) {

            diagnosticsPrefs =
                    getSharedPreferences(
                            PREFS,
                            MODE_PRIVATE
                    );
        }

        /*
         * При возврате из настроек Android
         * или при обычном открытии приложения
         * сразу перечитываем состояние фонового радара.
         */
        updateBackgroundStatus();
    }


    /*
     * ============================================================
     * НИЖНЯЯ СТРОКА
     * ============================================================
     *
     * Никаких новых вертикальных блоков не создаём.
     *
     * Находим существующую нижнюю строку MainActivity:
     *
     * [ Проверить на сайте Победы ]   [ v0.11 ]
     *
     * и превращаем правую часть в:
     *
     * [ фон: 07:15 ✓ · v0.14 ]
     *
     * Поэтому весь основной экран остаётся
     * на прежнем месте.
     */
    private void attachBackgroundStatus() {

        ViewGroup content =
                findViewById(
                        android.R.id.content
                );

        if (content == null
                || content.getChildCount() == 0) {

            return;
        }


        View rootView =
                content.getChildAt(0);

        if (!(rootView
                instanceof LinearLayout)) {

            return;
        }


        LinearLayout root =
                (LinearLayout) rootView;

        if (root.getChildCount() == 0) {

            return;
        }


        /*
         * В рабочей MainActivity
         * последним элементом root является bottomRow.
         */
        View bottomView =
                root.getChildAt(
                        root.getChildCount() - 1
                );

        if (!(bottomView
                instanceof LinearLayout)) {

            return;
        }


        LinearLayout bottomRow =
                (LinearLayout) bottomView;

        if (bottomRow.getChildCount() < 2) {

            return;
        }


        /*
         * Последний TextView в bottomRow —
         * существующая версия приложения.
         */
        View versionView =
                bottomRow.getChildAt(
                        bottomRow.getChildCount() - 1
                );

        if (!(versionView
                instanceof TextView)) {

            return;
        }


        footerStatus =
                (TextView) versionView;


        /*
         * Немного расширяем правую область.
         * Высота строки остаётся прежней.
         */
        ViewGroup.LayoutParams rawParams =
                footerStatus.getLayoutParams();

        if (rawParams
                instanceof LinearLayout.LayoutParams) {

            LinearLayout.LayoutParams params =
                    (LinearLayout.LayoutParams)
                            rawParams;

            params.width =
                    dp(132);

            footerStatus.setLayoutParams(
                    params
            );
        }


        footerStatus.setTextSize(
                9
        );

        footerStatus.setTextColor(
                GREY
        );

        footerStatus.setGravity(
                Gravity.END
                        | Gravity.CENTER_VERTICAL
        );

        footerStatus.setSingleLine(
                true
        );


        /*
         * Нажатие на статус открывает
         * системную карточку Pobeda Radar.
         *
         * Там на Samsung можно выбрать:
         *
         * Батарея → Без ограничений.
         */
        footerStatus.setOnClickListener(
                v -> openBatterySettings()
        );
    }


    /*
     * ============================================================
     * СОСТОЯНИЕ ФОНОВОЙ ПРОВЕРКИ
     * ============================================================
     */
    private void updateBackgroundStatus() {

        if (footerStatus == null
                || diagnosticsPrefs == null) {

            return;
        }


        long lastRun =
                diagnosticsPrefs.getLong(
                        BACKGROUND_LAST_RUN_KEY,
                        0L
                );


        long lastSuccess =
                diagnosticsPrefs.getLong(
                        BACKGROUND_LAST_SUCCESS_KEY,
                        0L
                );


        String lastError =
                diagnosticsPrefs.getString(
                        BACKGROUND_LAST_ERROR_KEY,
                        ""
                );


        String text;

        int color =
                GREY;


        /*
         * Worker ещё ни разу не запускался.
         */
        if (lastRun <= 0L) {

            text =
                    "фон: — · "
                            + APP_VERSION;

        /*
         * Последний запуск завершился ошибкой.
         */
        } else if (
                lastError != null
                        && !lastError.isEmpty()
                        && lastRun > lastSuccess
        ) {

            text =
                    "фон: ошибка · "
                            + APP_VERSION;

            color =
                    RED;

        /*
         * Есть успешный фоновый запуск.
         */
        } else {

            long referenceTime =
                    lastSuccess > 0L
                            ? lastSuccess
                            : lastRun;


            long age =
                    Math.max(
                            0L,
                            System.currentTimeMillis()
                                    - referenceTime
                    );


            boolean fresh =
                    age <= STALE_AFTER_MS;


            text =
                    "фон: "
                            + formatTime(
                            referenceTime
                    )
                            + (
                            fresh
                                    ? " ✓ · "
                                    : " ⚠ · "
                    )
                            + APP_VERSION;


            color =
                    fresh
                            ? GREEN
                            : RED;
        }


        footerStatus.setText(
                text
        );

        footerStatus.setTextColor(
                color
        );
    }


    /*
     * ============================================================
     * НАСТРОЙКИ БАТАРЕИ
     * ============================================================
     */
    private void openBatterySettings() {

        Toast.makeText(
                this,
                "Для надёжной фоновой работы: Батарея → Без ограничений",
                Toast.LENGTH_LONG
        ).show();


        try {

            Intent intent =
                    new Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                    );


            intent.setData(
                    Uri.parse(
                            "package:"
                                    + getPackageName()
                    )
            );


            startActivity(
                    intent
            );

        } catch (Exception ignored) {

        }
    }


    /*
     * ============================================================
     * UTIL
     * ============================================================
     */
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

            return "—";
        }
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
}
