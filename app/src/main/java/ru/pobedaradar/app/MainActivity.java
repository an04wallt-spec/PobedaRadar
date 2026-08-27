package ru.pobedaradar.app;

import android.app.Activity;
import android.app.DatePickerDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String PREFS = "pobeda_radar";
    private static final String TOKEN_KEY = "travelpayouts_token";

    private final DateTimeFormatter apiDate = DateTimeFormatter.ISO_LOCAL_DATE;
    private final DateTimeFormatter uiDate = DateTimeFormatter.ofPattern("dd.MM.yyyy", new Locale("ru"));
    private final DateTimeFormatter cardDate = DateTimeFormatter.ofPattern("dd MMM", new Locale("ru"));

    private LinearLayout root;
    private LinearLayout resultsBox;
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
        outFrom = today.plusDays(1);
        outTo = today.plusDays(30);
        backFrom = today.plusDays(7);
        backTo = today.plusDays(45);

        buildUi();
        updateDirectionUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(28));
        scroll.addView(root);

        TextView title = text("Победа Радар", 34, true);
        root.addView(title);

        TextView subtitle = text("Москва ⇄ Газипаша · только Победа (DP)", 18, true);
        subtitle.setPadding(0, dp(12), 0, dp(18));
        root.addView(subtitle);

        LinearLayout directionRow = new LinearLayout(this);
        directionRow.setOrientation(LinearLayout.HORIZONTAL);
        outBtn = actionButton("МОСКВА → GZP");
        backBtn = actionButton("GZP → МОСКВА");
        directionRow.addView(outBtn, new LinearLayout.LayoutParams(0, dp(58), 1));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(dp(10), 1);
        View spacer = new View(this);
        directionRow.addView(spacer, gap);
        directionRow.addView(backBtn, new LinearLayout.LayoutParams(0, dp(58), 1));
        root.addView(directionRow);

        outBtn.setOnClickListener(v -> { outbound = true; updateDirectionUi(); });
        backBtn.setOnClickListener(v -> { outbound = false; updateDirectionUi(); });

        TextView rangeTitle = text("Диапазон дат", 20, true);
        rangeTitle.setPadding(0, dp(20), 0, dp(8));
        root.addView(rangeTitle);

        LinearLayout dateRow = new LinearLayout(this);
        dateRow.setOrientation(LinearLayout.HORIZONTAL);
        fromBtn = actionButton("");
        toBtn = actionButton("");
        dateRow.addView(fromBtn, new LinearLayout.LayoutParams(0, dp(62), 1));
        View spacer2 = new View(this);
        dateRow.addView(spacer2, new LinearLayout.LayoutParams(dp(10), 1));
        dateRow.addView(toBtn, new LinearLayout.LayoutParams(0, dp(62), 1));
        root.addView(dateRow);

        fromBtn.setOnClickListener(v -> pickDate(true));
        toBtn.setOnClickListener(v -> pickDate(false));

        TextView tokenTitle = text("Travelpayouts token", 17, true);
        tokenTitle.setPadding(0, dp(20), 0, dp(6));
        root.addView(tokenTitle);

        tokenInput = new EditText(this);
        tokenInput.setTextSize(16);
        tokenInput.setHint("Вставьте токен один раз");
        tokenInput.setSingleLine(true);
        tokenInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokenInput.setText(getSharedPreferences(PREFS, MODE_PRIVATE).getString(TOKEN_KEY, ""));
        tokenInput.setPadding(dp(12), 0, dp(12), 0);
        root.addView(tokenInput, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)));

        Button scan = actionButton("ОБНОВИТЬ РАДАР");
        LinearLayout.LayoutParams scanLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(62));
        scanLp.topMargin = dp(18);
        root.addView(scan, scanLp);
        scan.setOnClickListener(v -> refreshRadar());

        progress = new ProgressBar(this);
        progress.setVisibility(View.GONE);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(42), dp(42));
        pp.gravity = Gravity.CENTER_HORIZONTAL;
        pp.topMargin = dp(10);
        root.addView(progress, pp);

        summary = text("Выберите направление и диапазон дат.", 18, true);
        summary.setPadding(0, dp(20), 0, dp(8));
        root.addView(summary);

        status = text("", 15, false);
        status.setTextColor(Color.DKGRAY);
        root.addView(status);

        resultsBox = new LinearLayout(this);
        resultsBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(12);
        root.addView(resultsBox, rp);

        setContentView(scroll);
    }

    private void updateDirectionUi() {
        if (outbound) {
            outBtn.setEnabled(false);
            backBtn.setEnabled(true);
        } else {
            outBtn.setEnabled(true);
            backBtn.setEnabled(false);
        }
        LocalDate a = outbound ? outFrom : backFrom;
        LocalDate b = outbound ? outTo : backTo;
        fromBtn.setText("С\n" + a.format(uiDate));
        toBtn.setText("ПО\n" + b.format(uiDate));
        clearResults("Диапазон: " + a.format(uiDate) + " — " + b.format(uiDate));
    }

    private void pickDate(boolean start) {
        LocalDate current;
        if (outbound) current = start ? outFrom : outTo;
        else current = start ? backFrom : backTo;

        DatePickerDialog dialog = new DatePickerDialog(this, (view, year, month, day) -> {
            LocalDate chosen = LocalDate.of(year, month + 1, day);
            if (chosen.isBefore(LocalDate.now())) {
                Toast.makeText(this, "Нельзя выбрать прошедшую дату", Toast.LENGTH_SHORT).show();
                return;
            }
            if (outbound) {
                if (start) outFrom = chosen; else outTo = chosen;
                if (outTo.isBefore(outFrom)) {
                    if (start) outTo = outFrom; else outFrom = outTo;
                }
            } else {
                if (start) backFrom = chosen; else backTo = chosen;
                if (backTo.isBefore(backFrom)) {
                    if (start) backTo = backFrom; else backFrom = backTo;
                }
            }
            updateDirectionUi();
        }, current.getYear(), current.getMonthValue() - 1, current.getDayOfMonth());
        dialog.getDatePicker().setMinDate(System.currentTimeMillis() - 1000L);
        dialog.show();
    }

    private void refreshRadar() {
        String token = tokenInput.getText().toString().trim();
        if (token.isEmpty()) {
            Toast.makeText(this, "Сначала вставьте Travelpayouts token", Toast.LENGTH_LONG).show();
            tokenInput.requestFocus();
            return;
        }
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(TOKEN_KEY, token).apply();

        LocalDate from = outbound ? outFrom : backFrom;
        LocalDate to = outbound ? outTo : backTo;
        if (to.isBefore(from)) {
            Toast.makeText(this, "Конечная дата раньше начальной", Toast.LENGTH_LONG).show();
            return;
        }

        String origin = outbound ? "MOW" : "GZP";
        String destination = outbound ? "GZP" : "MOW";

        resultsBox.removeAllViews();
        summary.setText("Проверяю цены…");
        status.setText(origin + " → " + destination + " · " + from.format(uiDate) + " — " + to.format(uiDate));
        progress.setVisibility(View.VISIBLE);

        new Thread(() -> {
            try {
                List<Offer> offers = loadOffers(origin, destination, from, to, token);
                runOnUiThread(() -> showOffers(offers, origin, destination, from, to));
            } catch (Exception e) {
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    summary.setText("Не удалось обновить радар");
                    status.setText(e.getMessage() == null ? e.toString() : e.getMessage());
                });
            }
        }).start();
    }

    private List<Offer> loadOffers(String origin, String destination, LocalDate from, LocalDate to, String token) throws Exception {
        String url = "https://api.travelpayouts.com/aviasales/v3/get_special_offers"
                + "?origin=" + enc(origin)
                + "&destination=" + enc(destination)
                + "&airline=DP"
                + "&locale=ru"
                + "&currency=rub"
                + "&market=ru"
                + "&token=" + enc(token);

        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestMethod("GET");
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("User-Agent", "PobedaRadar/1.0");

        int code = c.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String body = readAll(stream);
        c.disconnect();

        if (code == 401 || code == 403) throw new Exception("Travelpayouts отклонил токен (HTTP " + code + ")");
        if (code == 429) throw new Exception("Слишком много запросов. Повторите позже (HTTP 429)");
        if (code < 200 || code >= 300) throw new Exception("Ошибка Travelpayouts: HTTP " + code + "\n" + shortText(body));

        JSONObject json = new JSONObject(body);
        if (!json.optBoolean("success", false) && !json.has("data")) {
            throw new Exception("Travelpayouts не вернул данные");
        }

        JSONArray data = json.optJSONArray("data");
        List<Offer> out = new ArrayList<>();
        if (data == null) return out;

        for (int i = 0; i < data.length(); i++) {
            JSONObject o = data.optJSONObject(i);
            if (o == null) continue;
            String airline = o.optString("airline", "");
            if (!"DP".equalsIgnoreCase(airline)) continue;

            LocalDate date = parseApiDate(o.optString("departure_at", ""));
            if (date == null || date.isBefore(from) || date.isAfter(to)) continue;

            int price = o.optInt("price", -1);
            if (price <= 0) continue;

            String flight = o.optString("flight_number", "");
            String link = o.optString("link", "");
            out.add(new Offer(date, price, airline, flight, link));
        }

        Collections.sort(out, Comparator.comparing((Offer x) -> x.date).thenComparingInt(x -> x.price));
        return out;
    }

    private void showOffers(List<Offer> offers, String origin, String destination, LocalDate from, LocalDate to) {
        progress.setVisibility(View.GONE);
        resultsBox.removeAllViews();

        if (offers.isEmpty()) {
            summary.setText("Предложений Победы в выбранном диапазоне не найдено");
            status.setText("Это не означает, что рейсов нет: Data API показывает предложения, ранее найденные пользователями Aviasales. Можно изменить диапазон и проверить снова.");
            return;
        }

        int min = Integer.MAX_VALUE;
        long sum = 0;
        for (Offer o : offers) { min = Math.min(min, o.price); sum += o.price; }
        int avg = (int) Math.round(sum / (double) offers.size());

        summary.setText("Найдено: " + offers.size() + " · минимум: " + rub(min) + " · средняя: " + rub(avg));
        status.setText("Обновлено сейчас · " + origin + " → " + destination + " · " + from.format(uiDate) + " — " + to.format(uiDate));

        for (Offer offer : offers) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(dp(16), dp(16), dp(16), dp(16));
            card.setBackgroundColor(Color.rgb(247, 247, 247));

            TextView top = text(offer.date.format(cardDate) + "   " + rub(offer.price), 24, true);
            card.addView(top);

            String rating = rating(offer.price, avg, offers.size());
            String flight = offer.flight.isEmpty() ? "DP" : "DP" + offer.flight;
            TextView bottom = text(rating + " · " + flight, 16, true);
            bottom.setPadding(0, dp(10), 0, 0);
            card.addView(bottom);

            if (!offer.link.isEmpty()) {
                card.setClickable(true);
                card.setFocusable(true);
                card.setOnClickListener(v -> openOffer(offer.link));
                TextView hint = text("Нажмите, чтобы перепроверить цену на Aviasales", 13, false);
                hint.setPadding(0, dp(8), 0, 0);
                card.addView(hint);
            }

            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            cp.bottomMargin = dp(10);
            resultsBox.addView(card, cp);
        }
    }

    private String rating(int price, int avg, int count) {
        if (count < 3) return "цена найдена";
        double ratio = price / (double) avg;
        if (ratio <= 0.75) return "🟢 очень низкая";
        if (ratio <= 0.90) return "🟢 низкая";
        if (ratio <= 1.10) return "🟡 обычная";
        return "🔴 высокая";
    }

    private void openOffer(String link) {
        String full;
        if (link.startsWith("http://") || link.startsWith("https://")) full = link;
        else full = "https://www.aviasales.ru/search/" + (link.startsWith("/") ? link.substring(1) : link);
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(full)));
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show();
        }
    }

    private void clearResults(String message) {
        if (summary != null) summary.setText(message);
        if (status != null) status.setText("");
        if (resultsBox != null) resultsBox.removeAllViews();
    }

    private LocalDate parseApiDate(String value) {
        if (value == null || value.isEmpty()) return null;
        try { return OffsetDateTime.parse(value).toLocalDate(); }
        catch (DateTimeParseException ignored) {}
        try { return LocalDate.parse(value.substring(0, Math.min(value.length(), 10)), apiDate); }
        catch (Exception ignored) { return null; }
    }

    private String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        StringBuilder b = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) b.append(line);
        r.close();
        return b.toString();
    }

    private String shortText(String s) {
        if (s == null) return "";
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }

    private String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(sp);
        t.setTextColor(Color.rgb(32, 32, 32));
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button actionButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(16);
        b.setAllCaps(false);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return b;
    }

    private String rub(int value) {
        return String.format(new Locale("ru"), "%,d ₽", value).replace(',', ' ');
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static class Offer {
        final LocalDate date;
        final int price;
        final String airline;
        final String flight;
        final String link;

        Offer(LocalDate date, int price, String airline, String flight, String link) {
            this.date = date;
            this.price = price;
            this.airline = airline;
            this.flight = flight;
            this.link = link;
        }
    }
}
