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
import android.util.JsonReader;
import android.util.JsonToken;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import kotlin.Pair;
import kotlin.jvm.functions.Function1;
import org.fdroid.index.v1.IndexV1Verifier;

public class MainActivity extends Activity {
    private static final String SEARCH_URL = "https://search.f-droid.org/api/search_apps?q=";
    private static final String REPO_URL = "https://f-droid.org/repo/";
    private static final String INDEX_URL = REPO_URL + "index-v1.jar";
    private static final String INDEX_SIGNER_SHA256 = "43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab";
    private static final long INDEX_CACHE_MAX_AGE_MS = 6L * 60L * 60L * 1000L;
    private static final int MAX_INDEX_BYTES = 100 * 1024 * 1024;
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
    private String activeExpectedSha256 = "";
    private Uri pendingInstallUri;
    private boolean detailsVisible = false;
    private int currentPage = 1;
    private boolean loadingMore = false;
    private boolean hasMorePages = false;
    private int activeSearchRequest = 0;
    private static final int PAGE_SIZE = 10;

    static class AppEntry {
        String id="", name="", summary="", description="", icon="";
        String web="", source="", tracker="", donate="", license="";
        String apkUrl="", versionName="", apkSha256="";
        long versionCode=0, apkSize=0;
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
                        Uri downloadedUri = dm.getUriForDownloadedFile(id);
                        String expectedHash = activeExpectedSha256;
                        if (downloadedUri == null || expectedHash.isEmpty()) {
                            Toast.makeText(MainActivity.this, "Download finished, but APK verification data is missing.", Toast.LENGTH_LONG).show();
                        } else {
                            worker.execute(() -> {
                                try {
                                    String actualHash = sha256(downloadedUri);
                                    if (!expectedHash.equalsIgnoreCase(actualHash)) {
                                        dm.remove(id);
                                        runOnUiThread(() -> Toast.makeText(MainActivity.this, "APK checksum did not match F-Droid's signed index. The file was rejected.", Toast.LENGTH_LONG).show());
                                        return;
                                    }
                                    runOnUiThread(() -> {
                                        pendingInstallUri = downloadedUri;
                                        startInstallerWhenAllowed();
                                    });
                                } catch (Exception verifyError) {
                                    dm.remove(id);
                                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "Could not verify the downloaded APK. It was rejected.", Toast.LENGTH_LONG).show());
                                }
                            });
                        }
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
        appList.setOnScrollListener(new android.widget.AbsListView.OnScrollListener() {
            @Override public void onScrollStateChanged(android.widget.AbsListView view, int scrollState) {}
            @Override public void onScroll(android.widget.AbsListView view, int firstVisible, int visibleCount, int totalCount) {
                if (totalCount > 0 && firstVisible + visibleCount >= totalCount - 3) loadNextPage();
            }
        });
        root.addView(appList, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);

        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { searchApps(s.toString()); }
            public void afterTextChanged(android.text.Editable e) {}
        });
    }

    private void searchApps(String rawQuery) {
        String query = rawQuery.trim(); currentQuery = query;
        int requestId = latestRequest.incrementAndGet(); activeSearchRequest = requestId;
        currentPage = 1; loadingMore = false; hasMorePages = false;
        apps.clear(); imageCache.evictAll();
        if (adapter != null) adapter.notifyDataSetChanged();
        if (query.length() < 2) { progress.setVisibility(View.GONE); status.setText("Type at least 2 characters to search."); return; }
        progress.setVisibility(View.VISIBLE); status.setText("Searching for “" + query + "”…");
        worker.execute(() -> { try {
            SearchPage page = fetchSearchPage(query, 1);
            runOnUiThread(() -> { if (requestId != latestRequest.get()) return; apps.clear(); apps.addAll(page.apps); currentPage=1; hasMorePages=page.hasMore; progress.setVisibility(View.GONE); status.setText(apps.size()+" results · Scroll down to load more"); adapter.notifyDataSetChanged(); });
        } catch (Exception e) { String message=e.getMessage()==null?"Network error":e.getMessage(); runOnUiThread(() -> { if(requestId!=latestRequest.get()) return; progress.setVisibility(View.GONE); status.setText("Search failed. Check your connection and try again."); Toast.makeText(this,message,Toast.LENGTH_LONG).show(); }); } });
    }

    private void loadNextPage() {
        if (loadingMore || !hasMorePages || currentQuery.length()<2 || detailsVisible) return;
        loadingMore=true; final int requestId=activeSearchRequest; final int nextPage=currentPage+1; status.setText("Loading more apps…");
        worker.execute(() -> { try {
            SearchPage page=fetchSearchPage(currentQuery,nextPage);
            runOnUiThread(() -> { if(requestId!=latestRequest.get()) return; int before=apps.size(); java.util.HashSet<String> seen=new java.util.HashSet<>(); for(AppEntry old:apps) seen.add(old.id.isEmpty()?old.name.toLowerCase(Locale.ROOT):old.id); for(AppEntry app:page.apps){String key=app.id.isEmpty()?app.name.toLowerCase(Locale.ROOT):app.id; if(seen.add(key)) apps.add(app);} currentPage=nextPage; hasMorePages=page.hasMore && apps.size()>before; loadingMore=false; status.setText(apps.size()+" results"+(hasMorePages?" · Scroll for more":" · End of results")); adapter.notifyDataSetChanged(); });
        } catch(Exception e){runOnUiThread(()->{if(requestId==latestRequest.get()){loadingMore=false;status.setText("Could not load more apps. Scroll down to retry.");}});} });
    }

    private static class SearchPage { final List<AppEntry> apps; final boolean hasMore; SearchPage(List<AppEntry> apps,boolean hasMore){this.apps=apps;this.hasMore=hasMore;} }
    private SearchPage fetchSearchPage(String query,int page) throws Exception {
        HttpURLConnection c=null;
        try {
            // The JSON endpoint is documented for basic searches but does not provide
            // reliable page/limit pagination. Use the website's page parameter instead.
            String address="https://search.f-droid.org/?q="+URLEncoder.encode(query,"UTF-8")+"&page="+page+"&lang=";
            c=(HttpURLConnection)new URL(address).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent","Mozilla/5.0 (compatible; LiteDroid/1.6)");
            c.setRequestProperty("Accept","text/html");
            int response=c.getResponseCode();
            if(response!=HttpURLConnection.HTTP_OK) throw new Exception("F-Droid search returned HTTP "+response);
            byte[] bytes=readLimited(c.getInputStream(),4*1024*1024);
            String html=new String(bytes,"UTF-8");
            List<AppEntry> found=parseHtmlSearchResults(html);
            return new SearchPage(found,found.size()>=PAGE_SIZE);
        } finally {
            if(c!=null)c.disconnect();
        }
    }

    private static List<AppEntry> parseHtmlSearchResults(String html) {
        List<AppEntry> found=new ArrayList<>();
        Pattern cards=Pattern.compile("(?is)<a\\b(?=[^>]*class=[\\\"'][^\\\"']*\\\\bpackage-header\\\\b[^\\\"']*[\\\"'])[^>]*>(.*?)</a>");
        Matcher cardMatcher=cards.matcher(html);
        while(cardMatcher.find()) {
            String card=cardMatcher.group(1);
            String href=htmlAttribute(cardMatcher.group(0),"href");
            String id=normalizePackageId(href);
            if(id.isEmpty()) continue;

            AppEntry app=new AppEntry();
            app.id=id;
            app.name=htmlElementText(card,"h4","package-name");
            if(app.name.isEmpty()) app.name=id;
            app.summary=htmlElementText(card,"span","package-summary");
            app.description=app.summary;
            app.icon=htmlElementAttribute(card,"img","package-icon","src");
            if(!app.icon.isEmpty() && app.icon.startsWith("//")) app.icon="https:"+app.icon;
            found.add(app);
        }
        return found;
    }

    private static String htmlElementText(String html,String tag,String className) {
        Pattern p=Pattern.compile("(?is)<"+tag+"\\\\b(?=[^>]*class=[\\\"'][^\\\"']*\\\\b"+Pattern.quote(className)+"\\\\b[^\\\"']*[\\\"'])[^>]*>(.*?)</"+tag+"\\\\s*>");
        Matcher m=p.matcher(html);
        if(!m.find()) return "";
        return Html.fromHtml(m.group(1),Html.FROM_HTML_MODE_LEGACY).toString().trim();
    }

    private static String htmlElementAttribute(String html,String tag,String className,String attribute) {
        Pattern p=Pattern.compile("(?is)<"+tag+"\\\\b(?=[^>]*class=[\\\"'][^\\\"']*\\\\b"+Pattern.quote(className)+"\\\\b[^\\\"']*[\\\"'])[^>]*>");
        Matcher m=p.matcher(html);
        return m.find()?htmlAttribute(m.group(),attribute):"";
    }

    private static String htmlAttribute(String tag,String attribute) {
        Pattern p=Pattern.compile("(?is)\\\\b"+Pattern.quote(attribute)+"\\\\s*=\\\\s*[\\\"']([^\\\"']*)[\\\"']");
        Matcher m=p.matcher(tag);
        return m.find()?m.group(1).trim():"";
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
        e.id = normalizePackageId(first(item, "packageName", "package_name", "packageId", "package_id", "id", "package", "appid"));
        // Search API responses can expose the package page URL instead of a package ID.
        if (e.id.isEmpty()) {
            e.id = normalizePackageId(first(item, "packageUrl", "package_url", "detailsUrl", "detail_url", "url", "link", "webUrl"));
        }
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

    private static String normalizePackageId(String value) {
        if (value == null) return "";
        String id = value.trim();
        // Some search responses may provide an F-Droid package-page URL instead of the bare ID.
        int marker = id.indexOf("/packages/");
        if (marker >= 0) id = id.substring(marker + "/packages/".length());
        int slash = id.indexOf('/');
        if (slash >= 0) id = id.substring(0, slash);
        int query = id.indexOf('?');
        if (query >= 0) id = id.substring(0, query);
        return id.trim();
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

        TextView apkStatus = sectionText("Checking the signed F-Droid repository index and loading screenshots…");
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
                // Fetch APK metadata separately so a screenshot-page failure cannot disable downloads.
                fetchOfficialPackageInfo(app);
                runOnUiThread(() -> {
                    if (!detailsVisible) return;
                    apkStatus.setText("Verified official version: " + app.versionName + " (code " + app.versionCode + "). APK checksum is ready.");
                    download.setEnabled(!app.apkUrl.isEmpty());
                });
            } catch (Exception e) {
                String reason = e.getMessage();
                if (reason == null || reason.trim().isEmpty()) reason = e.getClass().getSimpleName();
                final String error = reason;
                runOnUiThread(() -> {
                    if (detailsVisible) apkStatus.setText("APK info failed: " + error);
                });
            }

            // Screenshots are optional. Their failure must not affect the APK button.
            try {
                String html = fetchText(PACKAGE_PAGE + Uri.encode(app.id));
                List<String> images = findScreenshots(html, app.id);
                app.screenshots.clear();
                app.screenshots.addAll(images);
                runOnUiThread(() -> {
                    if (!detailsVisible) return;
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
                    if (detailsVisible) apkStatus.append("\nScreenshots unavailable (APK download can still work).");
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

    private void fetchOfficialPackageInfo(AppEntry app) throws Exception {
        if (app.id == null || app.id.trim().isEmpty()) {
            throw new Exception("F-Droid search did not provide this app's package ID. Try opening another result.");
        }
        app.id = normalizePackageId(app.id);
        File indexFile = getCachedIndexJar();
        IndexV1Verifier verifier = new IndexV1Verifier(indexFile, null, INDEX_SIGNER_SHA256);
        Pair<String, ApkInfo> verified = verifier.getStreamAndVerify(new Function1<InputStream, ApkInfo>() {
            @Override public ApkInfo invoke(InputStream input) {
                try { return findPackageInIndex(input, app.id); }
                catch (Exception e) { throw new IllegalStateException("Could not read the signed F-Droid index.", e); }
            }
        });
        ApkInfo info = verified.getSecond();
        if (info == null) {
            String packageLabel = app.id.isEmpty() ? "(empty package ID)" : app.id;
            throw new Exception("No APK entry matched package ID: " + packageLabel + ". The app may be missing from the main repository index.");
        }
        if (info.apkName.isEmpty() || !info.apkName.matches("[A-Za-z0-9._+-]+\\.apk") || info.apkName.contains(".."))
            throw new Exception("F-Droid index contains an invalid APK filename.");
        if (!"sha256".equalsIgnoreCase(info.hashType) || !info.sha256.matches("(?i)[0-9a-f]{64}"))
            throw new Exception("F-Droid index does not contain a valid SHA-256 checksum.");
        app.versionName = info.versionName.isEmpty() ? "Latest" : info.versionName;
        app.versionCode = info.versionCode;
        app.apkSize = info.size;
        app.apkSha256 = info.sha256.toLowerCase(Locale.ROOT);
        app.apkUrl = REPO_URL + Uri.encode(info.apkName);
    }

    private File getCachedIndexJar() throws Exception {
        File cached = new File(getCacheDir(), "fdroid-index-v1.jar");
        long age = System.currentTimeMillis() - cached.lastModified();
        if (cached.isFile() && cached.length() > 0 && age >= 0 && age < INDEX_CACHE_MAX_AGE_MS) return cached;
        File temporary = new File(getCacheDir(), "fdroid-index-v1.download");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(INDEX_URL).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(45000);
            connection.setRequestProperty("User-Agent", "LiteDroid/1.5");
            connection.setRequestProperty("Accept", "application/java-archive, application/octet-stream");
            int response = connection.getResponseCode();
            if (response != HttpURLConnection.HTTP_OK) throw new Exception("F-Droid index returned HTTP " + response + ".");
            int length = connection.getContentLength();
            if (length > MAX_INDEX_BYTES) throw new Exception("F-Droid index is larger than LiteDroid safety limit.");
            long total = 0;
            try (InputStream in = connection.getInputStream(); FileOutputStream out = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[16384];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    total += count;
                    if (total > MAX_INDEX_BYTES) throw new Exception("F-Droid index exceeded LiteDroid safety limit.");
                    out.write(buffer, 0, count);
                }
                out.getFD().sync();
            }
            if (total == 0) throw new Exception("F-Droid returned an empty repository index.");
            if (cached.exists() && !cached.delete()) throw new Exception("Could not replace the cached index.");
            if (!temporary.renameTo(cached)) throw new Exception("Could not save the F-Droid index.");
            return cached;
        } catch (Exception e) { temporary.delete(); throw e; }
        finally { if (connection != null) connection.disconnect(); }
    }

    private static class ApkInfo {
        String apkName = "", versionName = "", sha256 = "", hashType = "";
        long versionCode = 0, size = 0;
    }

    private static ApkInfo findPackageInIndex(InputStream input, String packageId) throws Exception {
        JsonReader reader = new JsonReader(new InputStreamReader(input, "UTF-8"));
        ApkInfo selected = null;
        reader.beginObject();
        while (reader.hasNext()) {
            String topKey = reader.nextName();
            if (!"packages".equals(topKey)) { reader.skipValue(); continue; }
            reader.beginObject();
            while (reader.hasNext()) {
                String id = reader.nextName();
                if (packageId.equals(id)) selected = readPackageVersions(reader);
                else reader.skipValue();
            }
            reader.endObject();
        }
        reader.endObject();
        return selected;
    }

    private static ApkInfo readPackageVersions(JsonReader reader) throws Exception {
        ApkInfo best = null;
        reader.beginArray();
        while (reader.hasNext()) {
            ApkInfo candidate = new ApkInfo();
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if ("apkName".equals(key)) candidate.apkName = readJsonString(reader);
                else if ("versionName".equals(key)) candidate.versionName = readJsonString(reader);
                else if ("hash".equals(key)) candidate.sha256 = readJsonString(reader);
                else if ("hashType".equals(key)) candidate.hashType = readJsonString(reader);
                else if ("versionCode".equals(key)) candidate.versionCode = readJsonLong(reader);
                else if ("size".equals(key)) candidate.size = readJsonLong(reader);
                else reader.skipValue();
            }
            reader.endObject();
            boolean usable = candidate.versionCode > 0 && candidate.apkName.matches("[A-Za-z0-9._+-]+\\.apk") &&
                    "sha256".equalsIgnoreCase(candidate.hashType) && candidate.sha256.matches("(?i)[0-9a-f]{64}");
            if (usable && (best == null || candidate.versionCode > best.versionCode)) best = candidate;
        }
        reader.endArray();
        return best;
    }

    private static String readJsonString(JsonReader reader) throws Exception {
        JsonToken token = reader.peek();
        if (token == JsonToken.NULL) { reader.nextNull(); return ""; }
        if (token == JsonToken.STRING || token == JsonToken.NUMBER) return reader.nextString();
        reader.skipValue();
        return "";
    }

    private static long readJsonLong(JsonReader reader) throws Exception {
        try { return Long.parseLong(readJsonString(reader)); } catch (Exception ignored) { return 0; }
    }

    private static List<String> findScreenshots(String html, String packageId) {
        List<String> result = new ArrayList<>();
        int start = html.indexOf("id=\"screenshots\"");
        if (start < 0) start = html.indexOf("id='screenshots'");
        if (start < 0) return result;
        int end = html.indexOf("package-versions", start);
        if (end < 0) end = Math.min(html.length(), start + 30000);
        String gallery = html.substring(start, end);
        Matcher tags = Pattern.compile("(?is)<img\\b[^>]*>").matcher(gallery);
        String expectedPrefix = "https://f-droid.org/repo/" + packageId + "/";
        while (tags.find() && result.size() < 8) {
            String tag = tags.group();
            String src = attribute(tag, "src");
            if (src.isEmpty()) src = attribute(tag, "data-src");
            if (src.isEmpty()) src = attribute(tag, "data-lazy-src");
            if (src.isEmpty()) continue;
            String url = absoluteUrl(src.trim());
            String lower = url.toLowerCase(Locale.ROOT);
            int queryAt = lower.indexOf('?');
            if (queryAt >= 0) lower = lower.substring(0, queryAt);
            int hashAt = lower.indexOf('#');
            if (hashAt >= 0) lower = lower.substring(0, hashAt);
            if (url.startsWith(expectedPrefix) &&
                    (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) &&
                    !result.contains(url)) result.add(url);
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
        if (TextUtils.isEmpty(app.apkUrl) || !app.apkUrl.startsWith(REPO_URL) || !app.apkSha256.matches("(?i)[0-9a-f]{64}")) {
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
            activeExpectedSha256 = app.apkSha256;
            activeDownloadId = dm.enqueue(request);
            Toast.makeText(this, "APK download started.", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not start the download.", Toast.LENGTH_LONG).show();
        }
    }

    private String sha256(Uri uri) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) throw new Exception("Downloaded APK could not be opened.");
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        byte[] hash = digest.digest();
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return hex.toString();
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
