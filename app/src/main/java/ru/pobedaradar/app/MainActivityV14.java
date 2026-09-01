package ru.pobedaradar.app;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class MainActivityV14 extends MainActivity {

    private static final String APP_VERSION =
            "v0.15";

    private static final String PREFS =
            "pobeda_radar";

    private static final String DATE_FROM_KEY =
            "date_from";

    private static final String DATE_TO_KEY =
            "date_to";

    private static final String DIRECTION_KEY =
            "direction_outbound";

    private static final String DEADLINE_PREFIX =
            "deadline_";

    private static final String BACKGROUND_LAST_RUN_KEY =
            "background_last_run";

    private static final String BACKGROUND_LAST_SUCCESS_KEY =
            "background_last_success";

    private static final String BACKGROUND_LAST_ERROR_KEY =
            "background_last_error";

    private static final long STALE_AFTER_MS =
            TimeUnit.HOURS.toMillis(5);

    private static final int GREEN =
            Color.rgb(35, 135, 70);

    private static final int RED =
            Color.rgb(210, 30, 30);

    private static final int GREY =
            Color.rgb(105, 105, 105);

    private static final int DARK =
            Color.rgb(28, 28, 28);

    private final DateTimeFormatter timeFormat =
            DateTimeFormatter.ofPattern(
                    "HH:mm",
                    new Locale("ru")
            );

    private final DateTimeFormatter uiDate =
            DateTimeFormatter.ofPattern(
                    "dd.MM.yyyy",
                    new Locale("ru")
            );

    private SharedPreferences prefs;

    private TextView footerStatus;
    private Button deadlineButton;
    private LinearLayout bestOffersBox;


    @Override
    protected void onCreate(
            Bundle savedInstanceState
    ) {

        super.onCreate(
                savedInstanceState
        );

        prefs =
                getSharedPreferences(
                        PREFS,
                        MODE_PRIVATE
                );

        attachVersion15Interface();
        attachBackgroundStatus();

        refreshDeadlineButton();
        updateBackgroundStatus();
    }


    @Override
    protected void onResume() {

        super.onResume();

        if (prefs == null) {

            prefs =
                    getSharedPreferences(
                            PREFS,
                            MODE_PRIVATE
                    );
        }

        refreshDeadlineButton();
        updateBackgroundStatus();
    }


    @Override
    public void onWindowFocusChanged(
            boolean hasFocus
    ) {

        super.onWindowFocusChanged(
                hasFocus
        );

        if (hasFocus) {
            refreshDeadlineButton();
        }
    }


    // ============================================================
    // ИНТЕРФЕЙС 0.15
    // ============================================================

    private void attachVersion15Interface() {

        LinearLayout root =
                findMainRoot();

        if (root == null
                || root.getChildCount() < 8) {

            return;
        }


        /*
         * В старой MainActivity:
         *
         * 0 — заголовок
         * 1 — направления
         * 2 — "Ближайшие даты"
         * 3 — ближайшие даты
         * 4 — "Наилучшие предложения"
         * 5 — список лучших
         * 6 — "Диапазон дат"
         * 7 — С / ПО
         */


        // --------------------------------------------------------
        // 12 лучших предложений вместо 14
        // --------------------------------------------------------

        View bestView =
                root.getChildAt(5);

        if (bestView
                instanceof LinearLayout) {

            bestOffersBox =
                    (LinearLayout) bestView;

            ViewGroup.LayoutParams params =
                    bestOffersBox
                            .getLayoutParams();

            if (params != null) {

                /*
                 * Одна строка = 18dp.
                 *
                 * 12 × 18 = 216dp.
                 */
                params.height =
                        dp(216);

                bestOffersBox
                        .setLayoutParams(
                                params
                        );
            }


            /*
             * MainActivity технически создаёт 14 строк.
             *
             * Мы физически оставляем только первые 12.
             * Сортировка и сами данные при этом
             * остаются совершенно прежними.
             */
            bestOffersBox
                    .setOnHierarchyChangeListener(
                            new ViewGroup
                                    .OnHierarchyChangeListener() {

                                @Override
                                public void onChildViewAdded(
                                        View parent,
                                        View child
                                ) {

                                    parent.post(
                                            () ->
                                                    trimBestOffers()
                                    );
                                }

                                @Override
                                public void onChildViewRemoved(
                                        View parent,
                                        View child
                                ) {
                                }
                            }
                    );

            trimBestOffers();
        }


        // --------------------------------------------------------
        // Компенсируем высоту новой строки
        // --------------------------------------------------------

        /*
         * Убрали две строки:
         *
         * 252dp → 216dp = −36dp.
         *
         * Новая строка крайней даты = +39dp.
         *
         * Ещё 3dp убираем из старого верхнего
         * отступа заголовка "Диапазон дат".
         *
         * Итоговая высота экрана НЕ меняется.
         */

        View datesCaption =
                root.getChildAt(6);

        ViewGroup.LayoutParams captionRaw =
                datesCaption
                        .getLayoutParams();

        if (captionRaw
                instanceof LinearLayout.LayoutParams) {

            LinearLayout.LayoutParams
                    captionParams =
                    (LinearLayout.LayoutParams)
                            captionRaw;

            captionParams.topMargin =
                    0;

            datesCaption
                    .setLayoutParams(
                            captionParams
                    );
        }


        // --------------------------------------------------------
        // Новая строка крайней даты
        // --------------------------------------------------------

        LinearLayout deadlineRow =
                new LinearLayout(this);

        deadlineRow.setOrientation(
                LinearLayout.HORIZONTAL
        );

        deadlineRow.setGravity(
                Gravity.CENTER_VERTICAL
        );


        TextView deadlineLabel =
                new TextView(this);

        deadlineLabel.setText(
                "Крайняя дата вылета"
        );

        deadlineLabel.setTextSize(
                13
        );

        deadlineLabel.setTextColor(
                DARK
        );

        deadlineLabel.setGravity(
                Gravity.CENTER_VERTICAL
        );


        /*
         * Левая половина новой строки
         * имеет точно такую же ширину,
         * как поле "С" строкой выше.
         */
        deadlineRow.addView(
                deadlineLabel,
                new LinearLayout.LayoutParams(
                        0,
                        dp(39),
                        1
                )
        );


        /*
         * Тот же промежуток 8dp,
         * что между "С" и "ПО".
         */
        View gap =
                new View(this);

        deadlineRow.addView(
                gap,
                new LinearLayout.LayoutParams(
                        dp(8),
                        1
                )
        );


        deadlineButton =
                new Button(this);

        deadlineButton.setTextSize(
                13
        );

        deadlineButton.setAllCaps(
                false
        );

        deadlineButton.setTypeface(
                Typeface.DEFAULT,
                Typeface.BOLD
        );

        deadlineButton.setPadding(
                dp(4),
                0,
                dp(4),
                0
        );


        /*
         * ПРАВОЕ ПОЛЕ:
         *
         * width = 0
         * weight = 1
         * height = 39dp
         *
         * Абсолютно те же размеры,
         * что у "С" и "ПО".
         */
        deadlineRow.addView(
                deadlineButton,
                new LinearLayout.LayoutParams(
                        0,
                        dp(39),
                        1
                )
        );


        /*
         * Вставляем сразу после строки С / ПО.
         */
        root.addView(
                deadlineRow,
                8,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams
                                .MATCH_PARENT,
                        dp(39)
                )
        );


        deadlineButton.setOnClickListener(
                v -> pickDeadlineDate()
        );


        /*
         * При переключении направления
         * сразу показываем его собственную
         * крайнюю дату.
         */
        installDirectionRefreshHooks(
                root
        );
    }


    private void trimBestOffers() {

        if (bestOffersBox == null) {
            return;
        }

        while (
                bestOffersBox
                        .getChildCount()
                        > 12
        ) {

            bestOffersBox.removeViewAt(
                    bestOffersBox
                            .getChildCount()
                            - 1
            );
        }
    }


    // ============================================================
    // ПЕРЕКЛЮЧЕНИЕ НАПРАВЛЕНИЯ
    // ============================================================

    private void installDirectionRefreshHooks(
            LinearLayout root
    ) {

        if (root.getChildCount() < 2) {
            return;
        }

        View directionView =
                root.getChildAt(1);

        if (!(directionView
                instanceof LinearLayout)) {

            return;
        }

        LinearLayout directionRow =
                (LinearLayout)
                        directionView;

        if (directionRow
                .getChildCount() < 3) {

            return;
        }


        /*
         * Ничего не заменяем в существующих
         * обработчиках MainActivity.
         *
         * OnTouch возвращает false,
         * поэтому родной OnClick продолжает
         * работать как раньше.
         */

        attachRefreshTouch(
                directionRow
                        .getChildAt(0)
        );

        attachRefreshTouch(
                directionRow
                        .getChildAt(2)
        );
    }


    private void attachRefreshTouch(
            View view
    ) {

        view.setOnTouchListener(
                (v, event) -> {

                    if (event.getAction()
                            == MotionEvent.ACTION_UP) {

                        /*
                         * Даём родной MainActivity
                         * сначала сохранить новое направление.
                         */
                        v.postDelayed(
                                this::
                                        refreshDeadlineButton,
                                80
                        );
                    }

                    return false;
                }
        );
    }


    // ============================================================
    // КРАЙНЯЯ ДАТА ВЫЛЕТА
    // ============================================================

    private void refreshDeadlineButton() {

        if (deadlineButton == null
                || prefs == null) {

            return;
        }

        DateState state =
                readDateState();

        LocalDate deadline =
                readDeadline(
                        state
                );

        deadlineButton.setText(
                deadline.format(
                        uiDate
                )
        );
    }


    private void pickDeadlineDate() {

        if (prefs == null) {
            return;
        }

        DateState state =
                readDateState();

        LocalDate current =
                readDeadline(
                        state
                );


        /*
         * Запоминаем направление,
         * для которого был открыт календарь.
         */
        final boolean selectedDirection =
                state.outbound;


        DatePickerDialog dialog =
                new DatePickerDialog(
                        this,

                        (view,
                         year,
                         month,
                         day) -> {

                            LocalDate selected =
                                    LocalDate.of(
                                            year,
                                            month + 1,
                                            day
                                    );

                            prefs.edit()
                                    .putString(
                                            deadlineKey(
                                                    selectedDirection
                                            ),
                                            selected
                                                    .toString()
                                    )
                                    .apply();

                            refreshDeadlineButton();
                        },

                        current.getYear(),

                        current
                                .getMonthValue()
                                - 1,

                        current
                                .getDayOfMonth()
                );


        /*
         * Крайняя дата не может оказаться
         * вне выбранного диапазона мониторинга.
         */
        dialog.getDatePicker()
                .setMinDate(
                        toMillis(
                                state.from
                        )
                );

        dialog.getDatePicker()
                .setMaxDate(
                        toMillis(
                                state.to
                        )
                );


        dialog.show();
    }


    private DateState readDateState() {

        LocalDate today =
                LocalDate.now();


        LocalDate from =
                parseDate(
                        prefs.getString(
                                DATE_FROM_KEY,
                                today.toString()
                        ),
                        today
                );


        LocalDate to =
                parseDate(
                        prefs.getString(
                                DATE_TO_KEY,

                                today
                                        .plusDays(30)
                                        .toString()
                        ),

                        today.plusDays(30)
                );


        if (to.isBefore(from)) {
            to = from;
        }


        boolean outbound =
                prefs.getBoolean(
                        DIRECTION_KEY,
                        true
                );


        return new DateState(
                from,
                to,
                outbound
        );
    }


    private LocalDate readDeadline(
            DateState state
    ) {

        String key =
                deadlineKey(
                        state.outbound
                );


        String stored =
                prefs.getString(
                        key,
                        ""
                );


        /*
         * При первом запуске 0.15
         * крайней датой автоматически
         * становится дата "ПО".
         */
        LocalDate deadline =
                parseDate(
                        stored,
                        state.to
                );


        /*
         * Если пользователь после этого
         * изменил основной диапазон,
         * старая крайняя дата автоматически
         * остаётся внутри него.
         */
        if (deadline.isBefore(
                state.from
        )) {

            deadline =
                    state.from;
        }


        if (deadline.isAfter(
                state.to
        )) {

            deadline =
                    state.to;
        }


        /*
         * Сохраняем исправленное значение.
         */
        if (stored == null
                || stored.isEmpty()
                || !deadline
                .toString()
                .equals(stored)) {

            prefs.edit()
                    .putString(
                            key,
                            deadline.toString()
                    )
                    .apply();
        }


        return deadline;
    }


    private String deadlineKey(
            boolean outbound
    ) {

        return DEADLINE_PREFIX
                + (
                outbound
                        ? "MOW_GZP"
                        : "GZP_MOW"
        );
    }


    private LocalDate parseDate(
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


    private long toMillis(
            LocalDate date
    ) {

        return date
                .atStartOfDay(
                        ZoneId.systemDefault()
                )
                .toInstant()
                .toEpochMilli();
    }


    // ============================================================
    // СТАТУС ФОНОВОЙ РАБОТЫ
    // ============================================================

    private void attachBackgroundStatus() {

        LinearLayout root =
                findMainRoot();

        if (root == null
                || root.getChildCount() == 0) {

            return;
        }


        View bottomView =
                root.getChildAt(
                        root.getChildCount()
                                - 1
                );


        if (!(bottomView
                instanceof LinearLayout)) {

            return;
        }


        LinearLayout bottomRow =
                (LinearLayout)
                        bottomView;


        if (bottomRow
                .getChildCount() < 2) {

            return;
        }


        View versionView =
                bottomRow.getChildAt(
                        bottomRow
                                .getChildCount()
                                - 1
                );


        if (!(versionView
                instanceof TextView)) {

            return;
        }


        footerStatus =
                (TextView)
                        versionView;


        ViewGroup.LayoutParams rawParams =
                footerStatus
                        .getLayoutParams();


        if (rawParams
                instanceof LinearLayout.LayoutParams) {

            LinearLayout.LayoutParams params =
                    (LinearLayout.LayoutParams)
                            rawParams;

            params.width =
                    dp(132);

            footerStatus
                    .setLayoutParams(
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


        footerStatus.setOnClickListener(
                v ->
                        openBatterySettings()
        );
    }


    private void updateBackgroundStatus() {

        if (footerStatus == null
                || prefs == null) {

            return;
        }


        long lastRun =
                prefs.getLong(
                        BACKGROUND_LAST_RUN_KEY,
                        0L
                );


        long lastSuccess =
                prefs.getLong(
                        BACKGROUND_LAST_SUCCESS_KEY,
                        0L
                );


        String lastError =
                prefs.getString(
                        BACKGROUND_LAST_ERROR_KEY,
                        ""
                );


        String text;

        int color =
                GREY;


        if (lastRun <= 0L) {

            text =
                    "фон: — · "
                            + APP_VERSION;


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
                    age <=
                            STALE_AFTER_MS;


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


    // ============================================================
    // НАСТРОЙКИ БАТАРЕИ
    // ============================================================

    private void openBatterySettings() {

        Toast.makeText(
                this,

                "Для надёжной фоновой работы: Батарея → Без ограничений",

                Toast.LENGTH_LONG
        ).show();


        try {

            Intent intent =
                    new Intent(
                            Settings
                                    .ACTION_APPLICATION_DETAILS_SETTINGS
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


    // ============================================================
    // UTIL
    // ============================================================

    private LinearLayout findMainRoot() {

        ViewGroup content =
                findViewById(
                        android.R.id.content
                );


        if (content == null
                || content.getChildCount()
                == 0) {

            return null;
        }


        View rootView =
                content.getChildAt(0);


        if (!(rootView
                instanceof LinearLayout)) {

            return null;
        }


        return (LinearLayout)
                rootView;
    }


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


    private static class DateState {

        final LocalDate from;
        final LocalDate to;
        final boolean outbound;


        DateState(
                LocalDate from,
                LocalDate to,
                boolean outbound
        ) {

            this.from =
                    from;

            this.to =
                    to;

            this.outbound =
                    outbound;
        }
    }
}
