package org.litedroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    // Official F-Droid v2 repository index. This is the live catalogue source.
    private static final String INDEX_URL = "https://f-droid.org/repo/index-v2.json";
    private static final String REPO_URL = "https://f-droid.org/repo/";
    private final ExecutorService worker = Executors.newFixedThreadPool(3);
    private final List<AppEntry> apps = new ArrayList<>();
    private LinearLayout list;
    private EditText search;
    private TextView status;
    private ProgressBar progress;

    static class AppEntry {
        String id, name, summary, description, icon;
        String web, source, tracker, donate, license;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(20, 24, 30));
        getWindow().setNavigationBarColor(Color.rgb(20, 24, 30));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), 0);
        root.setBackgroundColor(Color.rgb(247, 248, 250));

        TextView title = new TextView(this);
        title.setText("LiteDroid");
        title.setTextSize(27);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(Color.rgb(25, 31, 40));
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("A simple F-Droid catalogue");
        subtitle.setTextSize(13);
        subtitle.setTextColor(Color.GRAY);
        root.addView(subtitle);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setHint("Search apps…");
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setBackground(round(Color.WHITE, 14));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(50));
        searchParams.topMargin = dp(18);
        root.addView(search, searchParams);

        status = new TextView(this);
        status.setText("Connecting to F-Droid…");
        status.setTextColor(Color.DKGRAY);
        status.setTextSize(13);
        status.setOnClickListener(v -> loadCatalogue());
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(12);
        root.addView(status, statusParams);

        progress = new ProgressBar(this);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(28), dp(28));
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        progressParams.topMargin = dp(8);
        root.addView(progress, progressParams);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(8), 0, dp(24));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { render(s.toString()); }
            public void afterTextChanged(android.text.Editable e) {}
        });
        loadCatalogue();
    }

    private void loadCatalogue() {
        progress.setVisibility(View.VISIBLE);
        status.setText("Loading the official F-Droid catalogue…");
        worker.execute(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(INDEX_URL).openConnection();
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(45000);
                connection.setRequestProperty("User-Agent", "LiteDroid/1.1");
                connection.setRequestProperty("Accept", "application/json");
                int response = connection.getResponseCode();
                if (response != HttpURLConnection.HTTP_OK) {
                    throw new Exception("F-Droid server returned HTTP " + response);
                }

                InputStream in = connection.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                in.close();

                JSONObject root = new JSONObject(out.toString("UTF-8"));
                JSONObject appObject = root.optJSONObject("apps");
                if (appObject == null) throw new Exception("The F-Droid index format was not recognised.");

                List<AppEntry> loaded = new ArrayList<>();
                Iterator<String> ids = appObject.keys();
                while (ids.hasNext()) {
                    String id = ids.next();
                    JSONObject record = appObject.optJSONObject(id);
                    if (record == null) continue;
                    JSONObject metadata = record.optJSONObject("metadata");
                    if (metadata == null) metadata = record;
                    AppEntry entry = parseApp(metadata, id);
                    if (!entry.name.isEmpty()) loaded.add(entry);
                }

                if (loaded.isEmpty()) throw new Exception("F-Droid returned an empty app catalogue.");
                Collections.sort(loaded, Comparator.comparing(x -> x.name.toLowerCase(Locale.ROOT)));
                runOnUiThread(() -> {
                    apps.clear();
                    apps.addAll(loaded);
                    progress.setVisibility(View.GONE);
                    status.setText(apps.size() + " apps · Official F-Droid index · Tap to refresh");
                    render(search.getText().toString());
                });
            } catch (Exception ex) {
                String message = ex.getMessage() == null ? "Network error" : ex.getMessage();
                runOnUiThread(() -> {
                    progress.setVisibility(View.GONE);
                    status.setText("Could not load catalogue. Tap here to retry.");
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    private static AppEntry parseApp(JSONObject metadata, String id) {
        AppEntry e = new AppEntry();
        e.id = id;
        e.name = localized(metadata, "name", id);
        e.summary = localized(metadata, "summary", "");
        e.description = localized(metadata, "description", e.summary);
        e.icon = localized(metadata, "icon", "");
        e.web = firstLocalized(metadata, "webSite", "website", "web");
        e.source = firstLocalized(metadata, "sourceCode", "source");
        e.tracker = firstLocalized(metadata, "issueTracker", "tracker");
        e.donate = firstLocalized(metadata, "donate", "donation");
        e.license = localized(metadata, "license", "");
        return e;
    }

    private static String firstLocalized(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = localized(object, key, "");
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static String localized(JSONObject object, String key, String fallback) {
        Object value = object.opt(key);
        if (value instanceof JSONObject) {
            JSONObject translations = (JSONObject) value;
            String best = translations.optString("en-US", "");
            if (best.isEmpty()) best = translations.optString("en", "");
            if (best.isEmpty()) {
                Iterator<String> keys = translations.keys();
                if (keys.hasNext()) best = translations.optString(keys.next(), "");
            }
            return best.isEmpty() ? fallback : best;
        }
        if (value == null || value == JSONObject.NULL) return fallback;
        String result = value.toString().trim();
        return result.isEmpty() ? fallback : result;
    }

    private void render(String query) {
        if (list == null) return;
        list.removeAllViews();
        String q = query.toLowerCase(Locale.ROOT).trim();
        int count = 0;
        for (AppEntry e : apps) {
            if (!(e.name.toLowerCase(Locale.ROOT).contains(q) ||
                    e.id.toLowerCase(Locale.ROOT).contains(q) ||
                    e.summary.toLowerCase(Locale.ROOT).contains(q))) continue;
            list.addView(appCard(e));
            count++;
        }
        if (count == 0 && !apps.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No apps found");
            empty.setTextColor(Color.GRAY);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(35), 0, dp(35));
            list.addView(empty);
        }
    }

    private View appCard(AppEntry e) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.setBackground(round(Color.WHITE, 14));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
        cp.bottomMargin = dp(9);
        card.setLayoutParams(cp);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(getDrawable(android.R.drawable.sym_def_app_icon));
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        if (!e.icon.isEmpty()) loadIcon(icon, e.icon);

        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, 0, 0);
        TextView name = new TextView(this);
        name.setText(e.name);
        name.setTextSize(16);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setTextColor(Color.rgb(28, 34, 42));
        TextView summary = new TextView(this);
        summary.setText(e.summary);
        summary.setTextSize(13);
        summary.setTextColor(Color.GRAY);
        summary.setMaxLines(2);
        text.addView(name);
        text.addView(summary);
        card.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
        card.setOnClickListener(v -> showDetails(e));
        return card;
    }

    private void loadIcon(ImageView target, String path) {
        worker.execute(() -> {
            try {
                String clean = path.trim();
                if (clean.startsWith("http://") || clean.startsWith("https://")) {
                    // Only use HTTPS for remote catalogue images.
                    if (!clean.startsWith("https://")) return;
                } else {
                    while (clean.startsWith("/")) clean = clean.substring(1);
                    if (!clean.startsWith("icons/") && !clean.startsWith("icons-")) clean = "icons-640/" + clean;
                    clean = REPO_URL + clean;
                }
                HttpURLConnection c = (HttpURLConnection) new URL(clean).openConnection();
                c.setConnectTimeout(7000);
                c.setReadTimeout(7000);
                InputStream in = c.getInputStream();
                android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(in);
                in.close();
                c.disconnect();
                if (bitmap != null) runOnUiThread(() -> target.setImageBitmap(bitmap));
            } catch (Exception ignored) {}
        });
    }

    private void showDetails(AppEntry e) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(8), dp(20), dp(8));
        TextView description = new TextView(this);
        description.setText(e.description.isEmpty() ? "No description provided." : e.description);
        description.setTextSize(15);
        description.setTextColor(Color.rgb(45, 50, 58));
        body.addView(description);
        addLink(body, "Developer website", e.web);
        addLink(body, "Source code", e.source);
        addLink(body, "Issue tracker", e.tracker);
        addLink(body, "Donate", e.donate);
        if (!e.license.isEmpty()) {
            TextView license = new TextView(this);
            license.setText("License: " + e.license);
            license.setTextColor(Color.GRAY);
            license.setPadding(0, dp(12), 0, 0);
            body.addView(license);
        }
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        new AlertDialog.Builder(this).setTitle(e.name).setView(scroll)
                .setPositiveButton("Close", null).show();
    }

    private void addLink(LinearLayout body, String label, String url) {
        if (url == null || url.trim().isEmpty()) return;
        TextView link = new TextView(this);
        link.setText(label + "  ↗\n" + url);
        link.setTextSize(13);
        link.setTextColor(Color.rgb(25, 105, 190));
        link.setPadding(0, dp(14), 0, 0);
        link.setOnClickListener(v -> {
            try {
                startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)));
            } catch (Exception ex) {
                Toast.makeText(this, "Could not open link", Toast.LENGTH_SHORT).show();
            }
        });
        body.addView(link);
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }
    private int dp(int value) { return (int) (value * getResources().getDisplayMetrics().density + 0.5f); }
    @Override protected void onDestroy() {
        super.onDestroy();
        worker.shutdown();
    }
}