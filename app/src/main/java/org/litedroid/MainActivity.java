package org.litedroid;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.text.Html;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import android.util.LruCache;

public class MainActivity extends Activity {
    private static final String SEARCH_URL = "https://search.f-droid.org/api/search_apps?q=";
    private static final String REPO_URL = "https://f-droid.org/repo/";
    private static final String PACKAGE_PAGE = "https://f-droid.org/packages/";
    private final ExecutorService worker = Executors.newFixedThreadPool(3);
    private final AtomicInteger latestRequest = new AtomicInteger(0);
    private final List<AppEntry> apps = new ArrayList<>();
    private final LruCache<String, Bitmap> imageCache = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };

    private LinearLayout root, listPanel;
    private EditText search;
    private TextView status;
    private ProgressBar progress;
    private ListView appList;
    private AppAdapter adapter;
    private String currentQuery = "";
    private long activeDownloadId = -1;
    private Uri pendingInstallUri;
    private boolean detailsVisible = false;

    static class AppEntry {
        String id="", name="", summary="", description="", icon="";
        String web="", source="", tracker="", donate="", license="";
        String apkUrl="";
        final List<String> screenshots = new ArrayList<>();
    }

    private final BroadcastReceiver downloadReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
            long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
            if (id != activeDownloadId) return;
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
            try (Cursor cursor = dm.query(query)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int column = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS);
                    if (column >= 0 && cursor.getInt(column) == DownloadManager.STATUS_SUCCESSFUL) {
                        pendingInstallUri = dm.getUriForDownloadedFile(id);
                        if (pendingInstallUri != null) startInstallerWhenAllowed();
                        else Toast.makeText(MainActivity.this, "Download finished, but Android could not open the APK.", Toast.LENGTH_LONG).show();
                    } else {
                        int reasonCol = cursor.getColumnIndex(DownloadManager.COLUMN_REASON);
                        int reason = reasonCol >= 0 ? cursor.getInt(reasonCol) : 0;
                        Toast.makeText(MainActivity.this, "APK download failed (" + reason + ").", Toast.LENGTH_LONG).show();
                    }
                }
            } catch (Exception e) {
                Toast.makeText(MainActivity.this, "Could not check the APK download.", Toast.LENGTH_LONG).show();
            }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(20, 24, 30));
        getWindow().setNavigationBarColor(Color.rgb(20, 24, 30));
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloadReceiver, filter, Context.RECEIVER_EXPORTED);
        else registerReceiver(downloadReceiver, filter);
        showSearchScreen();
    }

    private void showSearchScreen() {
        detailsVisible = false;
        root = new LinearLayout(this);
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
        subtitle.setText("A lightweight F-Droid catalogue");
        subtitle.setTextSize(13);
        subtitle.setTextColor(Color.GRAY);
        root.addView(subtitle);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setHint("Search F-Droid apps…");
        search.setPadding(dp(14), 0, dp(14), 0);
        search.setBackground(round(Color.WHITE, 14));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, dp(50));
        sp.topMargin = dp(18);
        root.addView(search, sp);

        status = new TextView(this);
        status.setText("Search for an app to explore the catalogue.");
        status.setTextColor(Color.DKGRAY);
        status.setTextSize(13);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(-1, -2);
        stp.topMargin = dp(12);
        root.addView(status, stp);

        progress = new ProgressBar(this);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(28), dp(28));
        pp.gravity = Gravity.CENTER_HORIZONTAL;
        pp.topMargin = dp(8);
        root.addView(progress, pp);
        progress.setVisibility(View.GONE);

        appList = new ListView(this);
        appList.setDivider(null);
        appList.setCacheColorHint(Color.TRANSPARENT);
        appList.setPadding(0, dp(8), 0, dp(24));
        appList.setClipToPadding(false);
        adapter = new AppAdapter();
        appList.setAdapter(adapter);
        root.addView(appList, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { searchApps(s.toString()); }
            public void afterTextChanged(android.text.Editable e) {}
        });
    }

    private void searchApps(String rawQuery) {
        String query = rawQuery.trim();
        currentQuery = query;
        int requestId = latestRequest.incrementAndGet();
        apps.clear();
        if (adapter != null) adapter.notifyDataSetChanged();
        if (query.length() < 2) {
            progress.setVisibility(View.GONE);
            status.setText("Type at least 2 characters to search.");
            return;
        }
        progress.setVisibility(View.VISIBLE);
        status.setText("Searching for “" + query + "”…");
        worker.execute(() -> {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(SEARCH_URL + URLEncoder.encode(query, "UTF-8"));
                connection = (HttpURLConnection) url.openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(15000);
                connection.setRequestProperty("User-Agent", "LiteDroid/1.3");
                connection.setRequestProperty("Accept", "application/json");
                int response = connection.getResponseCode();
                if (response != HttpURLConnection.HTTP_OK) throw new Exception("F-Droid search returned HTTP " + response);
                byte[] bytes = readLimited(connection.getInputStream(), 3 * 1024 * 1024);
                Object json = new org.json.JSONTokener(new String(bytes, "UTF-8")).nextValue();
                JSONArray results = findResults(json);
                if (results == null) throw new Exception("Unexpected F-Droid search response.");
                List<AppEntry> found = new ArrayList<>();
                for (int i = 0; i < results.length(); i++) {
                    JSONObject item = results.optJSONObject(i);
                    if (item == null) continue;
                    AppEntry app = parseSearchResult(item);
                    if (!app.id.isEmpty() || !app.name.isEmpty()) found.add(app);
                }
                runOnUiThread(() -> {
                    if (requestId != latestRequest.get()) return;
                    apps.clear();
                    apps.addAll(found);
                    progress.setVisibility(View.GONE);
                    status.setText(found.size() + " results · Official F-Droid search");
                    adapter.notifyDataSetChanged();
                });
            } catch (Exception e) {
                String message = e.getMessage() == null ? "Network error" : e.getMessage();
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

    private static byte[] readLimited(InputStream in, int max) throws Exception {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) != -1) {
                out.write(buffer, 0, n);
                if (out.size() > max) throw new Exception("Response was unexpectedly large.");
            }
            return out.toByteArray();
        }
    }

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
        if (value == null || value == JSONObject.NULL || value instanceof JSONArray) return "";
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
        return value.toString().trim();
    }

    private class AppAdapter extends BaseAdapter {
        @Override public int getCount() { return apps.size(); }
        @Override public Object getItem(int position) { return apps.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            RowHolder h;
            if (recycled == null) {
                LinearLayout card = new LinearLayout(MainActivity.this);
                card.setOrientation(LinearLayout.HORIZONTAL);
                card.setGravity(Gravity.CENTER_VERTICAL);
                card.setPadding(dp(12), dp(12), dp(12), dp(12));
                card.setBackground(round(Color.WHITE, 14));
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, -2);
                cp.bottomMargin = dp(9);
                card.setLayoutParams(cp);
                ImageView icon = new ImageView(MainActivity.this);
                icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
                card.addView(icon, new LinearLayout.LayoutParams(dp(48), dp(48)));
                LinearLayout text = new LinearLayout(MainActivity.this);
                text.setOrientation(LinearLayout.VERTICAL);
                text.setPadding(dp(12), 0, 0, 0);
                TextView name = new TextView(MainActivity.this);
                name.setTextSize(16);
                name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                name.setTextColor(Color.rgb(28, 34, 42));
                TextView summary = new TextView(MainActivity.this);
                summary.setTextSize(13);
                summary.setTextColor(Color.GRAY);
                summary.setMaxLines(2);
                text.addView(name);
                text.addView(summary);
                card.addView(text, new LinearLayout.LayoutParams(0, -2, 1));
                h = new RowHolder();
                h.icon = icon; h.name = name; h.summary = summary;
                card.setTag(h);
                recycled = card;
            } else h = (RowHolder) recycled.getTag();

            AppEntry app = apps.get(position);
            h.name.setText(app.name);
            h.summary.setText(app.summary);
            h.icon.setImageResource(android.R.drawable.sym_def_app_icon);
            h.icon.setTag(app.icon);
            if (!app.icon.isEmpty()) loadImage(h.icon, app.icon);
            recycled.setOnClickListener(v -> showDetails(app));
            return recycled;
        }
    }

    private static class RowHolder { ImageView icon; TextView name, summary; }

    private String imageUrl(String path) {
        if (path == null || path.trim().isEmpty()) return "";
        String p = path.trim();
        if (p.startsWith("https://")) return p;
        if (p.startsWith("http://")) return "";
        while (p.startsWith("/")) p = p.substring(1);
        if (!p.startsWith("icons/") && !p.startsWith("icons-") && !p.startsWith("repo/")) p = "icons-640/" + p;
        return "https://f-droid.org/" + p;
    }

    private void loadImage(ImageView target, String rawPath) {
        String url = imageUrl(rawPath);
        if (url.isEmpty()) return;
        Bitmap cached = imageCache.get(url);
        if (cached != null) { target.setImageBitmap(cached); return; }
        target.setTag(url);
        worker.execute(() -> {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(5000); c.setReadTimeout(7000);
                c.setRequestProperty("User-Agent", "LiteDroid/1.3");
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 2;
                Bitmap bitmap;
                try (InputStream in = c.getInputStream()) { bitmap = BitmapFactory.decodeStream(in, null, options); }
                if (bitmap != null) {
                    imageCache.put(url, bitmap);
                    runOnUiThread(() -> {
                        if (url.equals(target.getTag())) target.setImageBitmap(bitmap);
                    });
                }
            } catch (Exception ignored) {
            } finally { if (c != null) c.disconnect(); }
        });
    }

    private void showDetails(AppEntry app) {
        detailsVisible = true;
        latestRequest.incrementAndGet();
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(Color.rgb(247, 248, 250));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPadding(dp(14), dp(10), dp(14), dp(10));
        top.setBackgroundColor(Color.WHITE);
        TextView back = new TextView(this);
        back.setText("‹  Back");
        back.setTextSize(17);
        back.setTextColor(Color.rgb(25, 105, 190));
        back.setPadding(0, dp(8), dp(20), dp(8));
        back.setOnClickListener(v -> returnToSearch());
        top.addView(back);
        TextView heading = new TextView(this);
        heading.setText("App details");
        heading.setTextSize(18);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setTextColor(Color.rgb(25, 31, 40));
        top.addView(heading);
        page.addView(top);

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(18), dp(18), dp(18), dp(28));

        LinearLayout identity = new LinearLayout(this);
        identity.setGravity(Gravity.CENTER_VERTICAL);
        identity.setPadding(dp(14), dp(14), dp(14), dp(14));
        identity.setBackground(round(Color.WHITE, 16));
        ImageView icon = new ImageView(this);
        icon.setScaleType(ImageView.ScaleType.CENTER_CROP);
        icon.setImageResource(android.R.drawable.sym_def_app_icon);
        identity.addView(icon, new LinearLayout.LayoutParams(dp(68), dp(68)));
        if (!app.icon.isEmpty()) loadImage(icon, app.icon);
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setPadding(dp(14), 0, 0, 0);
        TextView name = new TextView(this);
        name.setText(app.name); name.setTextSize(20);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        name.setTextColor(Color.rgb(28, 34, 42));
        TextView packageName = new TextView(this);
        packageName.setText(app.id); packageName.setTextSize(12); packageName.setTextColor(Color.GRAY);
        labels.addView(name); labels.addView(packageName);
        identity.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        body.addView(identity);

        TextView summary = sectionText(app.summary);
        if (!app.summary.isEmpty()) body.addView(summary);
        TextView description = sectionText(app.description);
        description.setText(Html.fromHtml(app.description.isEmpty() ? "No description provided." : app.description, Html.FROM_HTML_MODE_COMPACT));
        body.addView(description);

        TextView screenshotLabel = sectionHeading("Screenshots");
        screenshotLabel.setVisibility(View.GONE);
        body.addView(screenshotLabel);
        HorizontalScrollView shotsScroll = new HorizontalScrollView(this);
        shotsScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout shots = new LinearLayout(this);
        shots.setOrientation(LinearLayout.HORIZONTAL);
        shotsScroll.addView(shots);
        body.addView(shotsScroll);

        TextView apkStatus = sectionText("Checking the official F-Droid app page for screenshots and the current APK…");
        body.addView(apkStatus);
        Button download = new Button(this);
        download.setText("Download APK");
        download.setEnabled(false);
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, -2);
        dp.topMargin = dp(12);
        body.addView(download, dp);
        download.setOnClickListener(v -> downloadApk(app));

        addLink(body, "Developer website", app.web);
        addLink(body, "Source code", app.source);
        addLink(body, "Issue tracker", app.tracker);
        addLink(body, "Donate", app.donate);
        if (!app.license.isEmpty()) body.addView(sectionText("License: " + app.license));

        scroll.addView(body);
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(page);

        worker.execute(() -> {
            try {
                String html = fetchText(PACKAGE_PAGE + Uri.encode(app.id));
                app.apkUrl = findApkUrl(html);
                List<String> images = findScreenshots(html);
                app.screenshots.clear();
                app.screenshots.addAll(images);
                runOnUiThread(() -> {
                    if (!detailsVisible) return;
                    apkStatus.setText(app.apkUrl.isEmpty()
                            ? "No direct APK was found on the official app page."
                            : "Latest APK found on the official F-Droid repository.");
                    download.setEnabled(!app.apkUrl.isEmpty());
                    if (!images.isEmpty()) {
                        screenshotLabel.setVisibility(View.VISIBLE);
                        for (String image : images) {
                            ImageView shot = new ImageView(this);
                            shot.setScaleType(ImageView.ScaleType.CENTER_CROP);
                            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(220), dp(390));
                            p.rightMargin = dp(10);
                            shots.addView(shot, p);
                            loadImage(shot, image);
                        }
                    } else {
                        apkStatus.append("\nNo screenshots were listed on the app page.");
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (detailsVisible) apkStatus.setText("Could not load app-page information. Check your connection.");
                });
            }
        });
    }

    private String fetchText(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        try {
            c.setConnectTimeout(10000); c.setReadTimeout(12000);
            c.setRequestProperty("User-Agent", "LiteDroid/1.3");
            if (c.getResponseCode() != 200) throw new Exception("App page unavailable");
            return new String(readLimited(c.getInputStream(), 2 * 1024 * 1024), "UTF-8");
        } finally { c.disconnect(); }
    }

    private static String findApkUrl(String html) {
        Matcher m = Pattern.compile("(?i)href\\s*=\\s*['\"]([^'\"]+\\.apk(?:\\?[^'\"]*)?)['\"]").matcher(html);
        while (m.find()) {
            String url = absoluteUrl(m.group(1));
            try {
                Uri uri = Uri.parse(url);
                if ("https".equals(uri.getScheme()) && "f-droid.org".equalsIgnoreCase(uri.getHost())) return url;
            } catch (Exception ignored) {}
        }
        return "";
    }

    private static List<String> findScreenshots(String html) {
        List<String> result = new ArrayList<>();
        Matcher tags = Pattern.compile("(?is)<img\\b[^>]*>").matcher(html);
        while (tags.find() && result.size() < 5) {
            String tag = tags.group();
            String src = attribute(tag, "src");
            if (src.isEmpty()) src = attribute(tag, "data-src");
            if (src.isEmpty()) continue;
            String lower = src.toLowerCase(Locale.ROOT);
            if (lower.contains("icon") || lower.contains("logo") || lower.endsWith(".svg")) continue;
            String url = absoluteUrl(src);
            if (url.startsWith("https://")) result.add(url);
        }
        return result;
    }

    private static String attribute(String tag, String name) {
        Matcher m = Pattern.compile("(?i)\\b" + name + "\\s*=\\s*['\"]([^'\"]+)['\"]").matcher(tag);
        return m.find() ? m.group(1) : "";
    }

    private static String absoluteUrl(String path) {
        if (path.startsWith("https://")) return path;
        if (path.startsWith("//")) return "https:" + path;
        if (path.startsWith("/")) return "https://f-droid.org" + path;
        return "https://f-droid.org/" + path;
    }

    private void downloadApk(AppEntry app) {
        if (TextUtils.isEmpty(app.apkUrl) || !app.apkUrl.startsWith("https://f-droid.org/")) {
            Toast.makeText(this, "No trusted F-Droid APK URL is available.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            DownloadManager dm = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            String filename = app.id.replaceAll("[^A-Za-z0-9._-]", "_") + ".apk";
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(app.apkUrl));
            request.setTitle("Downloading " + app.name);
            request.setDescription("LiteDroid · Official F-Droid repository");
            request.setMimeType("application/vnd.android.package-archive");
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, filename);
            activeDownloadId = dm.enqueue(request);
            Toast.makeText(this, "APK download started.", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not start the download.", Toast.LENGTH_LONG).show();
        }
    }

    private void startInstallerWhenAllowed() {
        if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent settings = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName()));
                startActivity(settings);
                Toast.makeText(this, "Allow LiteDroid to install this APK, then return here.", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Enable 'Install unknown apps' for LiteDroid in Android settings.", Toast.LENGTH_LONG).show();
            }
            return;
        }
        openInstaller();
    }

    @Override protected void onResume() {
        super.onResume();
        if (pendingInstallUri != null && (Build.VERSION.SDK_INT < 26 || getPackageManager().canRequestPackageInstalls())) {
            openInstaller();
        }
    }

    private void openInstaller() {
        if (pendingInstallUri == null) return;
        Uri uri = pendingInstallUri;
        pendingInstallUri = null;
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Android could not open the package installer.", Toast.LENGTH_LONG).show();
        }
    }

    private void returnToSearch() {
        detailsVisible = false;
        showSearchScreen();
        if (!currentQuery.isEmpty()) {
            search.setText(currentQuery);
            search.setSelection(search.length());
        }
    }

    private TextView sectionHeading(String text) {
        TextView t = new TextView(this);
        t.setText(text); t.setTextSize(17); t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        t.setTextColor(Color.rgb(28, 34, 42));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(20); p.bottomMargin = dp(8); t.setLayoutParams(p);
        return t;
    }

    private TextView sectionText(String text) {
        TextView t = new TextView(this);
        t.setText(text); t.setTextSize(14); t.setTextColor(Color.rgb(55, 60, 68));
        t.setPadding(dp(2), dp(12), dp(2), dp(8));
        return t;
    }

    private void addLink(LinearLayout body, String label, String url) {
        if (url == null || url.trim().isEmpty()) return;
        TextView link = new TextView(this);
        link.setText(label + "  ↗\n" + url);
        link.setTextSize(13); link.setTextColor(Color.rgb(25, 105, 190));
        link.setPadding(0, dp(14), 0, 0);
        link.setOnClickListener(v -> {
            try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
            catch (Exception e) { Toast.makeText(this, "Could not open link", Toast.LENGTH_SHORT).show(); }
        });
        body.addView(link);
    }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color); d.setCornerRadius(dp(radius));
        return d;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override protected void onDestroy() {
        try { unregisterReceiver(downloadReceiver); } catch (Exception ignored) {}
        worker.shutdownNow();
        super.onDestroy();
    }
}
