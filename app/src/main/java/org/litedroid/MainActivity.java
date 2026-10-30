package org.litedroid;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Html;
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
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends Activity {
    // Official F-Droid full-text search API. Search responses are small;
    // never download the entire repository index into the phone's memory.
    private static final String SEARCH_URL = "https://search.f-droid.org/api/search_apps?q=";
    private static final String REPO_URL = "https://f-droid.org/repo/";
    private final ExecutorService worker = Executors.newFixedThreadPool(3);
    private final AtomicInteger latestRequest = new AtomicInteger(0);
    private final List<AppEntry> apps = new ArrayList<>();
    private LinearLayout list;
    private EditText search;
    private TextView status;
    private ProgressBar progress;

    static class AppEntry {
        String id="", name="", summary="", description="", icon="";
        String web="", source="", tracker="", donate="", license="";
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
        search.setHint("Search F-Droid apps…");
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setBackground(round(Color.WHITE, 14));
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(-1, dp(50));
        searchParams.topMargin = dp(18);
        root.addView(search, searchParams);

        status = new TextView(this);
        status.setText("Search for an app to explore the F-Droid catalogue.");
        status.setTextColor(Color.DKGRAY);
        status.setTextSize(13);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2);
        statusParams.topMargin = dp(12);
        root.addView(status, statusParams);

        progress = new ProgressBar(this);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(dp(28), dp(28));
        progressParams.gravity = Gravity.CENTER_HORIZONTAL;
        progressParams.topMargin = dp(8);
        root.addView(progress, progressParams);
        progress.setVisibility(View.GONE);

        ScrollView scroll = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(8), 0, dp(24));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { searchApps(s.toString()); }
            public void afterTextChanged(android.text.Editable e) {}
        });
    }

    private void searchApps(String rawQuery) {
        String query = rawQuery.trim();
        int requestId = latestRequest.incrementAndGet();
        apps.clear();
        list.removeAllViews();

        if (query.length() < 2) {
            progress.setVisibility(View.GONE);
            status.setText("Type at least 2 characters to search F-Droid.");
            return;
        }

        progress.setVisibility(View.VISIBLE);
        status.setText("Searching F-Droid for “" + query + "”…");
        worker.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String encoded = URLEncoder.encode(query, "UTF-8");
                URL url = new URL(SEARCH_URL + encoded);
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("User-Agent", "LiteDroid/1.2");
                connection.setRequestProperty("Accept", "application/json");
                int response = connection.getResponseCode();
                if (response != HttpURLConnection.HTTP_OK)
                    throw new Exception("F-Droid search returned HTTP " + response);

                InputStream in = connection.getInputStream();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                    if (out.size() > 3 * 1024 * 1024)
                        throw new Exception("Search response was unexpectedly large.");
                }
                in.close();

                Object json = new org.json.JSONTokener(out.toString("UTF-8")).nextValue();
                JSONArray results = findResults(json);
                if (results == null) throw new Exception("F-Droid returned an unexpected search response.");

                List<AppEntry> found = new ArrayList<>();
                for (int i=0; i<results.length(); i++) {
                    JSONObject item = results.optJSONObject(i);
                    if (item == null) continue;
                    AppEntry app = parseSearchResult(item);
                    if (!app.name.isEmpty() || !app.id.isEmpty()) found.add(app);
                }

                runOnUiThread(() -> {
                    if (requestId != latestRequest.get()) return;
                    apps.clear();
                    apps.addAll(found);
                    progress.setVisibility(View.GONE);
                    status.setText(found.size() + " results · Official F-Droid search");
                    render();
                });
            } catch (Exception ex) {
                String message = ex.getMessage() == null ? "Network error" : ex.getMessage();
                runOnUiThread(() -> {
                    if (requestId != latestRequest.get()) return;
                    progress.setVisibility(View.GONE);
                    status.setText("Search failed. Check your connection and try again.");
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                });
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }

    // Accept the common list wrappers used by search services without assuming
    // the result list is always at the JSON document's top level.
    private static JSONArray findResults(Object value) {
        if (value instanceof JSONArray) return (JSONArray) value;
        if (!(value instanceof JSONObject)) return null;
        JSONObject object = (JSONObject) value;
        String[] keys = {"results", "apps", "hits", "items", "data"};
        for (String key : keys) {
            Object child = object.opt(key);
            if (child instanceof JSONArray) return (JSONArray) child;
            if (child instanceof JSONObject) {
                JSONArray nested = findResults(child);
                if (nested != null) return nested;
                JSONObject nestedObject = (JSONObject) child;
                if (nestedObject.has("hits") && nestedObject.opt("hits") instanceof JSONObject) {
                    JSONArray nestedHits = findResults(nestedObject.opt("hits"));
                    if (nestedHits != null) return nestedHits;
                }
            }
        }
        return null;
    }

    private static AppEntry parseSearchResult(JSONObject item) {
        AppEntry e = new AppEntry();
        e.id = first(item, "packageName", "package_name", "id", "package", "appid");
        e.name = first(item, "name", "title", "label");
        if (e.name.isEmpty()) e.name = e.id;
        e.summary = first(item, "summary", "shortDescription", "short_description");
        e.description = first(item, "description", "fullDescription", "full_description");
        if (e.description.isEmpty()) e.description = e.summary;
        e.icon = first(item, "icon", "iconUrl", "icon_url", "icon_path");
        e.web = first(item, "webSite", "website", "web", "homepage");
        e.source = first(item, "sourceCode", "source_code", "source");
        e.tracker = first(item, "issueTracker", "issue_tracker", "tracker");
        e.donate = first(item, "donate", "donation");
        e.license = first(item, "license");
        return e;
    }

    private static String first(JSONObject object, String... keys) {
        for (String key : keys) {
            String value = localized(object.opt(key));
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static String localized(Object value) {
        if (value == null || value == JSONObject.NULL) return "";
        if (value instanceof JSONObject) {
            JSONObject translations = (JSONObject) value;
            String best = translations.optString("en-US", "");
            if (best.isEmpty()) best = translations.optString("en", "");
            if (best.isEmpty()) {
                java.util.Iterator<String> keys = translations.keys();
                if (keys.hasNext()) best = localized(translations.opt(keys.next()));
            }
            return best.trim();
        }
        if (value instanceof JSONArray) return "";
        return value.toString().trim();
    }

    private void render() {
        list.removeAllViews();
        if (apps.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No matching apps found.");
            empty.setTextColor(Color.GRAY);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(35), 0, dp(35));
            list.addView(empty);
            return;
        }
        for (AppEntry app : apps) list.addView(appCard(app));
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
            HttpURLConnection c = null;
            try {
                String clean = path.trim();
                if (clean.startsWith("http://")) return;
                if (!clean.startsWith("https://")) {
                    while (clean.startsWith("/")) clean = clean.substring(1);
                    if (!clean.startsWith("icons/") && !clean.startsWith("icons-")) clean = "icons-640/" + clean;
                    clean = REPO_URL + clean;
                }
                c = (HttpURLConnection) new URL(clean).openConnection();
                c.setConnectTimeout(5000);
                c.setReadTimeout(5000);
                c.setInstanceFollowRedirects(true);
                InputStream in = c.getInputStream();
                android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(in);
                in.close();
                if (bitmap != null) runOnUiThread(() -> target.setImageBitmap(bitmap));
            } catch (Exception ignored) {
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    private void showDetails(AppEntry e) {
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(8), dp(20), dp(8));
        TextView description = new TextView(this);
        description.setText(Html.fromHtml(e.description.isEmpty() ? "No description provided." : e.description, Html.FROM_HTML_MODE_COMPACT));
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
        worker.shutdownNow();
    }
}