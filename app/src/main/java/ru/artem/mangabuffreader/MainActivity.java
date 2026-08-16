package ru.artem.mangabuffreader;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DownloadManager;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.Signature;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final String HOME_URL = "https://mangabuff.ru/";
    private static final String PREFS_NAME = "mangabuff_reader";
    private static final String PREF_LAST_URL = "last_url";
    private static final String PREF_LAST_SCROLL_PREFIX = "scroll_";
    private static final String PREF_LAST_SCROLL_RATIO_PREFIX = "scroll_ratio_";
    private static final String PREF_READER_BUTTON_SIZE = "reader_button_size_dp";
    private static final String PREF_READER_BUTTON_COLOR = "reader_button_color";
    private static final String PREF_LAST_UPDATE_CHECK = "last_update_check";
    private static final String PREF_PENDING_INSTALL = "pending_update_install";
    private static final String UPDATE_MANIFEST_URL =
            "https://raw.githubusercontent.com/EnoTik20/mangabuff-reader/main/update.json";
    private static final String UPDATE_APK_FILE = "MangaBuff-Reader-update.apk";
    private static final String APK_MIME_TYPE = "application/vnd.android.package-archive";
    private static final long UPDATE_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L;
    private static final int FILE_CHOOSER_REQUEST = 4001;
    private static final int DEFAULT_READER_BUTTON_SIZE_DP = 36;
    private static final int DEFAULT_READER_BUTTON_COLOR = 0xffe53935;
    private static final int BOTTOM_BAR_HEIGHT_DP = 64;

    private FrameLayout root;
    private WebView webView;
    private ProgressBar progressBar;
    private LinearLayout bottomBar;
    private TextView exitReaderChip;
    private LinearLayout readerPanel;
    private TextView readerProgressText;
    private ProgressBar readerProgressBar;
    private ScrollView favoritesPanel;
    private LinearLayout favoritesList;
    private View errorPanel;
    private TextView errorText;
    private View readerButton;
    private SharedPreferences preferences;
    private FavoriteStore favoriteStore;
    private ValueCallback<Uri[]> fileChooserCallback;
    private boolean readerMode;
    private boolean favoritesOpenedFromReader;
    private long lastSiteCleanupAt;
    private final ExecutorService posterExecutor = Executors.newFixedThreadPool(3);
    private final LruCache<String, Bitmap> posterCache = new LruCache<String, Bitmap>(16 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return Math.max(1, value.getByteCount() / 1024);
        }
    };
    private String initialUrl;
    private String currentSlug = "";
    private String currentTitle = "";
    private int currentVolume;
    private int currentChapter;
    private int currentLatestChapter;
    private int currentChapterProgress;
    private long lastProgressSaveAt;
    private boolean progressQueryPending;
    private volatile boolean updateCheckRunning;
    private volatile boolean updateDownloadRunning;

    private static final class UpdateInfo {
        final int versionCode;
        final String versionName;
        final String apkUrl;
        final String sha256;
        final String changes;

        UpdateInfo(
                int versionCode,
                String versionName,
                String apkUrl,
                String sha256,
                String changes
        ) {
            this.versionCode = versionCode;
            this.versionName = versionName;
            this.apkUrl = apkUrl;
            this.sha256 = sha256;
            this.changes = changes;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        favoriteStore = new FavoriteStore(preferences);
        configureWindow();
        buildInterface();
        configureWebView();

        if (savedInstanceState != null && webView.restoreState(savedInstanceState) != null) {
        } else {
            initialUrl = safeSavedUrl(preferences.getString(PREF_LAST_URL, HOME_URL));
            webView.loadUrl(initialUrl);
        }

        webView.postDelayed(() -> checkForUpdates(false), 1800);
    }

    private void configureWindow() {
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(9, 9, 11));
        window.setNavigationBarColor(Color.rgb(9, 9, 11));
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private void buildInterface() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(9, 9, 11));

        webView = new WebView(this);
        FrameLayout.LayoutParams webParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        webView.setLayoutParams(webParams);
        webView.setBackgroundColor(Color.rgb(9, 9, 11));
        root.addView(webView);

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progressBar.setMax(100);
        progressBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(229, 57, 53)));
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(3),
                Gravity.TOP
        );
        root.addView(progressBar, progressParams);

        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER);
        bottomBar.setPadding(dp(3), dp(3), dp(3), dp(3));
        bottomBar.setBackgroundColor(Color.rgb(16, 16, 19));
        bottomBar.setElevation(dp(12));

        addNavigationItem(bottomBar, "←", "Назад", view -> goBack());
        addNavigationItem(bottomBar, "⌂", "Главная", view -> loadHome());
        addNavigationItem(bottomBar, "★", "Избранное", view -> showFavorites());
        readerButton = addNavigationItem(bottomBar, "▣", "Читать", view -> {
            if (!isChapterUrl(webView.getUrl())) {
                Toast.makeText(this, "Режим чтения включается внутри главы", Toast.LENGTH_SHORT).show();
                return;
            }
            setReaderMode(true);
        });
        addNavigationItem(bottomBar, "⋮", "Ещё", this::showAppMenu);

        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(BOTTOM_BAR_HEIGHT_DP),
                Gravity.BOTTOM
        );
        root.addView(bottomBar, barParams);
        webView.setPadding(0, 0, 0, dp(BOTTOM_BAR_HEIGHT_DP));

        exitReaderChip = new TextView(this);
        exitReaderChip.setText("☰");
        exitReaderChip.setTextColor(Color.WHITE);
        exitReaderChip.setTextSize(19);
        exitReaderChip.setGravity(Gravity.CENTER);
        exitReaderChip.setContentDescription("Показать меню");
        exitReaderChip.setElevation(dp(8));
        exitReaderChip.setVisibility(View.GONE);
        exitReaderChip.setOnClickListener(view -> showReaderPanel());
        FrameLayout.LayoutParams chipParams = new FrameLayout.LayoutParams(
                dp(DEFAULT_READER_BUTTON_SIZE_DP),
                dp(DEFAULT_READER_BUTTON_SIZE_DP),
                Gravity.END | Gravity.BOTTOM
        );
        chipParams.setMargins(dp(12), dp(12), dp(12), dp(18));
        root.addView(exitReaderChip, chipParams);
        applyReaderButtonStyle();

        readerPanel = createReaderPanel();
        FrameLayout.LayoutParams readerPanelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        );
        readerPanelParams.setMargins(dp(10), dp(10), dp(10), dp(10));
        root.addView(readerPanel, readerPanelParams);

        favoritesPanel = createFavoritesPanel();
        FrameLayout.LayoutParams favoritesParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        favoritesParams.setMargins(0, 0, 0, dp(BOTTOM_BAR_HEIGHT_DP));
        root.addView(favoritesPanel, favoritesParams);

        errorPanel = createErrorPanel();
        FrameLayout.LayoutParams errorParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        );
        errorParams.setMargins(dp(24), dp(24), dp(24), dp(BOTTOM_BAR_HEIGHT_DP + 24));
        root.addView(errorPanel, errorParams);

        setContentView(root);
    }

    private View addNavigationItem(
            LinearLayout parent,
            String iconText,
            String labelText,
            View.OnClickListener listener
    ) {
        LinearLayout item = new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(dp(1), dp(4), dp(1), dp(3));
        item.setOnClickListener(listener);
        item.setContentDescription(labelText);

        TextView icon = new TextView(this);
        icon.setText(iconText);
        icon.setTextColor(Color.rgb(248, 248, 250));
        icon.setTextSize(19);
        icon.setGravity(Gravity.CENTER);
        icon.setIncludeFontPadding(false);
        item.addView(icon, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        TextView label = new TextView(this);
        label.setText(labelText);
        label.setTextColor(Color.rgb(205, 205, 212));
        label.setTextSize(10);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setIncludeFontPadding(false);
        item.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(18)
        ));

        parent.addView(item, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
        ));
        return item;
    }

    private LinearLayout createReaderPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(14));
        panel.setBackground(roundedDrawable(Color.rgb(19, 19, 23), dp(22)));
        panel.setElevation(dp(16));
        panel.setVisibility(View.GONE);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        readerProgressText = new TextView(this);
        readerProgressText.setText("Прогресс главы: 0%");
        readerProgressText.setTextColor(Color.WHITE);
        readerProgressText.setTextSize(15);
        readerProgressText.setSingleLine(true);
        readerProgressText.setGravity(Gravity.CENTER_VERTICAL);
        readerProgressText.setIncludeFontPadding(false);
        header.addView(readerProgressText, new LinearLayout.LayoutParams(0, dp(40), 1f));

        TextView close = createRoundCloseButton(view -> hideReaderPanel());
        header.addView(close, new LinearLayout.LayoutParams(dp(38), dp(38)));
        panel.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(42)
        ));

        readerProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        readerProgressBar.setMax(100);
        readerProgressBar.setProgress(0);
        readerProgressBar.setProgressTintList(ColorStateList.valueOf(Color.rgb(229, 57, 53)));
        readerProgressBar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(62, 62, 68)));
        panel.addView(readerProgressBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(4)
        ));

        LinearLayout primaryActions = new LinearLayout(this);
        primaryActions.setOrientation(LinearLayout.HORIZONTAL);
        primaryActions.setGravity(Gravity.CENTER);
        primaryActions.setPadding(0, dp(10), 0, 0);
        addReaderAction(primaryActions, "★", "Избранное", view -> showFavorites());
        addReaderAction(primaryActions, "↗", "Браузер", view -> openCurrentInBrowser());
        addReaderAction(primaryActions, "⇧", "Поделиться", view -> shareCurrentChapter());
        panel.addView(primaryActions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(64)
        ));

        LinearLayout siteActions = new LinearLayout(this);
        siteActions.setOrientation(LinearLayout.HORIZONTAL);
        siteActions.setGravity(Gravity.CENTER);
        siteActions.setPadding(0, dp(8), 0, 0);
        addReaderAction(siteActions, "☷", "Главы", view -> triggerSiteReaderAction(
                ".reader-menu__item--chapters",
                "Список глав недоступен"
        ));
        addReaderAction(siteActions, "▱", "Закладка", view -> triggerSiteReaderAction(
                ".reader-menu__item--bookmark",
                "Закладка сайта недоступна"
        ));
        addReaderAction(siteActions, "⚙", "Настройки", view -> triggerSiteReaderAction(
                ".reader-menu__item--settings",
                "Настройки сайта недоступны"
        ));
        panel.addView(siteActions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(62)
        ));

        TextView leaveReader = new TextView(this);
        leaveReader.setText("×  Выйти из режима чтения");
        leaveReader.setTextColor(Color.rgb(235, 235, 240));
        leaveReader.setTextSize(14);
        leaveReader.setGravity(Gravity.CENTER);
        leaveReader.setIncludeFontPadding(false);
        leaveReader.setBackground(roundedDrawable(Color.rgb(38, 38, 44), dp(14)));
        leaveReader.setOnClickListener(view -> setReaderMode(false));
        LinearLayout.LayoutParams leaveParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(46)
        );
        leaveParams.setMargins(0, dp(10), 0, 0);
        panel.addView(leaveReader, leaveParams);
        return panel;
    }

    private void addReaderAction(
            LinearLayout parent,
            String iconText,
            String labelText,
            View.OnClickListener listener
    ) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(dp(2), dp(4), dp(2), dp(4));
        tile.setBackground(roundedDrawable(Color.rgb(39, 39, 45), dp(13)));
        tile.setOnClickListener(listener);
        tile.setContentDescription(labelText);

        TextView icon = new TextView(this);
        icon.setText(iconText);
        icon.setTextColor(Color.WHITE);
        icon.setTextSize(18);
        icon.setGravity(Gravity.CENTER);
        icon.setIncludeFontPadding(false);
        tile.addView(icon, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
        ));

        TextView label = new TextView(this);
        label.setText(labelText);
        label.setTextColor(Color.rgb(220, 220, 226));
        label.setTextSize(10);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setIncludeFontPadding(false);
        tile.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(18)
        ));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
        );
        params.setMargins(dp(4), 0, dp(4), 0);
        parent.addView(tile, params);
    }

    private TextView createRoundCloseButton(View.OnClickListener listener) {
        TextView close = new TextView(this);
        close.setText("×");
        close.setTextColor(Color.rgb(245, 245, 248));
        close.setTextSize(23);
        close.setGravity(Gravity.CENTER);
        close.setIncludeFontPadding(false);
        close.setBackground(circleDrawable(Color.rgb(43, 43, 49)));
        close.setContentDescription("Закрыть");
        close.setOnClickListener(listener);
        return close;
    }

    private Button compactButton(String text, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextColor(Color.rgb(245, 245, 247));
        button.setMinHeight(0);
        button.setMinWidth(0);
        button.setGravity(Gravity.CENTER);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setOnClickListener(listener);
        return button;
    }

    private ScrollView createFavoritesPanel() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(9, 9, 11));
        scrollView.setVisibility(View.GONE);

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(18), dp(16), dp(24));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText("★ Избранное");
        title.setTextColor(Color.WHITE);
        title.setTextSize(23);
        title.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1f));
        TextView close = createRoundCloseButton(view -> hideFavorites());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(dp(38), dp(38));
        closeParams.setMargins(0, dp(5), dp(5), 0);
        header.addView(close, closeParams);
        content.addView(header);

        TextView hint = new TextView(this);
        hint.setText("Добавляй тайтлы через меню ⋮. Глава и прогресс обновляются во время чтения.");
        hint.setTextColor(Color.rgb(170, 170, 178));
        hint.setTextSize(13);
        hint.setPadding(0, 0, 0, dp(14));
        content.addView(hint);

        favoritesList = new LinearLayout(this);
        favoritesList.setOrientation(LinearLayout.VERTICAL);
        content.addView(favoritesList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        scrollView.addView(content);
        return scrollView;
    }

    private void showReaderPanel() {
        if (!readerMode) {
            return;
        }
        updateReadingProgress(true);
        exitReaderChip.setVisibility(View.GONE);
        readerPanel.setVisibility(View.VISIBLE);
    }

    private void hideReaderPanel() {
        readerPanel.setVisibility(View.GONE);
        if (readerMode) {
            exitReaderChip.setVisibility(View.VISIBLE);
        }
    }

    private void triggerSiteReaderAction(String selector, String missingMessage) {
        hideReaderPanel();
        String script = "(function(){try{var e=document.querySelector("
                + JSONObject.quote(selector)
                + ");if(!e)return 'missing';e.click();return 'ok';}"
                + "catch(x){return 'error';}})();";
        webView.evaluateJavascript(script, value -> {
            if (!"\"ok\"".equals(value)) {
                Toast.makeText(MainActivity.this, missingMessage, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void showFavorites() {
        if (isFavoritesVisible()) {
            return;
        }
        favoritesOpenedFromReader = readerMode;
        if (favoritesOpenedFromReader) {
            saveCurrentScroll();
            readerPanel.setVisibility(View.GONE);
            exitReaderChip.setVisibility(View.GONE);
            webView.setKeepScreenOn(false);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            showSystemBars();
        }
        errorPanel.setVisibility(View.GONE);
        webView.setVisibility(View.INVISIBLE);
        populateFavorites();
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) favoritesPanel.getLayoutParams();
        if (params != null) {
            params.bottomMargin = favoritesOpenedFromReader ? 0 : dp(BOTTOM_BAR_HEIGHT_DP);
            favoritesPanel.setLayoutParams(params);
        }
        favoritesPanel.setVisibility(View.VISIBLE);
        favoritesPanel.bringToFront();
    }

    private void hideFavorites() {
        favoritesPanel.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        if (favoritesOpenedFromReader && readerMode) {
            bottomBar.setVisibility(View.GONE);
            readerPanel.setVisibility(View.GONE);
            exitReaderChip.setVisibility(View.VISIBLE);
            webView.setPadding(0, 0, 0, 0);
            webView.setKeepScreenOn(true);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            hideSystemBars();
        }
        favoritesOpenedFromReader = false;
    }

    private boolean isFavoritesVisible() {
        return favoritesPanel != null && favoritesPanel.getVisibility() == View.VISIBLE;
    }

    private void populateFavorites() {
        favoritesList.removeAllViews();
        List<FavoriteStore.Item> items = favoriteStore.getAll();
        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Здесь пока пусто\n\nОткрой тайтл MangaBuff, нажми ⋮ и выбери «Добавить в избранное».");
            empty.setTextColor(Color.rgb(180, 180, 188));
            empty.setTextSize(15);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(18), dp(70), dp(18), dp(30));
            favoritesList.addView(empty, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
            return;
        }

        for (FavoriteStore.Item item : items) {
            favoritesList.addView(createFavoriteCard(item));
        }
    }

    private View createFavoriteCard(FavoriteStore.Item item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(13), dp(14), dp(12));
        card.setBackground(roundedDrawable(Color.rgb(24, 24, 29), dp(18)));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardParams.setMargins(0, 0, 0, dp(12));
        card.setLayoutParams(cardParams);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.TOP);

        ImageView poster = new ImageView(this);
        poster.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        poster.setImageResource(R.drawable.ic_launcher);
        poster.setPadding(dp(17), dp(25), dp(17), dp(25));
        poster.setAlpha(0.58f);
        poster.setBackground(roundedDrawable(Color.rgb(42, 42, 49), dp(13)));
        poster.setClipToOutline(true);
        poster.setContentDescription("Обложка: " + item.title);
        poster.setOnClickListener(view -> openFavoriteItem(item));
        body.addView(poster, new LinearLayout.LayoutParams(dp(86), dp(122)));
        String posterAddress = item.posterUrl;
        if ((posterAddress == null || posterAddress.isEmpty())
                && item.slug != null
                && !item.slug.isEmpty()) {
            posterAddress = "https://mangabuff.ru/img/manga/posters/"
                    + Uri.encode(item.slug)
                    + ".jpg";
        }
        loadFavoritePoster(poster, posterAddress);

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        detailsParams.setMargins(dp(13), 0, 0, 0);
        body.addView(details, detailsParams);

        TextView title = new TextView(this);
        title.setText(item.title);
        title.setTextColor(Color.WHITE);
        title.setTextSize(17);
        title.setMaxLines(2);
        title.setIncludeFontPadding(false);
        details.addView(title);

        String chapterText;
        if (item.chapter > 0) {
            chapterText = (item.volume > 0 ? "Том " + item.volume + " · " : "")
                    + "Глава " + item.chapter + " · " + item.chapterProgress + "% главы";
        } else {
            chapterText = "Чтение ещё не начато";
        }
        if (item.latestChapter > 0) {
            chapterText += " · доступно " + item.latestChapter;
        }
        TextView meta = new TextView(this);
        meta.setText(chapterText);
        meta.setTextColor(Color.rgb(184, 184, 192));
        meta.setTextSize(12);
        meta.setPadding(0, dp(6), 0, dp(8));
        meta.setMaxLines(3);
        details.addView(meta);

        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(item.overallProgress());
        progress.setProgressTintList(ColorStateList.valueOf(Color.rgb(229, 57, 53)));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(Color.rgb(58, 58, 64)));
        details.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(5)
        ));

        TextView overall = new TextView(this);
        overall.setText("Общий прогресс: " + item.overallProgress() + "%");
        overall.setTextColor(Color.rgb(150, 150, 158));
        overall.setTextSize(11);
        overall.setPadding(0, dp(5), 0, dp(7));
        details.addView(overall);
        card.addView(body);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(0, dp(7), 0, 0);
        Button open = compactButton(
                item.chapter > 0 ? "Продолжить" : "Открыть",
                view -> openFavoriteItem(item)
        );
        open.setTextColor(Color.rgb(255, 110, 103));
        Button remove = compactButton("Удалить", view -> {
            favoriteStore.remove(item.slug);
            populateFavorites();
        });
        actions.addView(open, new LinearLayout.LayoutParams(0, dp(42), 1f));
        actions.addView(remove, new LinearLayout.LayoutParams(0, dp(42), 1f));
        card.addView(actions);
        return card;
    }

    private void openFavoriteItem(FavoriteStore.Item item) {
        hideFavorites();
        String destination = item.lastUrl == null || item.lastUrl.isEmpty()
                ? item.titleUrl
                : item.lastUrl;
        if (isAllowedWebUrl(destination)) {
            if (readerMode && !isChapterUrl(destination)) {
                setReaderMode(false);
            }
            webView.loadUrl(destination);
        }
    }

    private void loadFavoritePoster(ImageView poster, String address) {
        if (!isAllowedPosterAddress(address)) {
            return;
        }
        poster.setTag(address);
        Bitmap cached = posterCache.get(address);
        if (cached != null && !cached.isRecycled()) {
            showFavoritePoster(poster, cached);
            return;
        }

        String userAgent = webView == null
                ? "MangaBuff-Reader/" + getInstalledVersionName()
                : webView.getSettings().getUserAgentString();
        String cookie = CookieManager.getInstance().getCookie(address);
        posterExecutor.execute(() -> {
            Bitmap bitmap = downloadFavoritePoster(address, userAgent, cookie);
            if (bitmap == null) {
                return;
            }
            posterCache.put(address, bitmap);
            runOnUiThread(() -> {
                if (address.equals(poster.getTag()) && canShowUi()) {
                    showFavoritePoster(poster, bitmap);
                }
            });
        });
    }

    private void showFavoritePoster(ImageView poster, Bitmap bitmap) {
        poster.setPadding(0, 0, 0, 0);
        poster.setAlpha(1f);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        poster.setImageBitmap(bitmap);
    }

    private boolean isAllowedPosterAddress(String address) {
        if (address == null || address.trim().isEmpty()) {
            return false;
        }
        try {
            Uri uri = Uri.parse(address);
            String host = uri.getHost();
            return "https".equalsIgnoreCase(uri.getScheme())
                    && host != null
                    && ("mangabuff.ru".equalsIgnoreCase(host)
                    || host.toLowerCase(Locale.ROOT).endsWith(".mangabuff.ru"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private Bitmap downloadFavoritePoster(String address, String userAgent, String cookie) {
        HttpURLConnection connection = null;
        try {
            URL current = new URL(address);
            for (int redirect = 0; redirect <= 4; redirect++) {
                if (!isAllowedPosterAddress(current.toString())) {
                    return null;
                }
                connection = (HttpURLConnection) current.openConnection();
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(20000);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept", "image/avif,image/webp,image/*,*/*;q=0.8");
                connection.setRequestProperty("User-Agent", userAgent);
                connection.setRequestProperty("Referer", "https://mangabuff.ru/");
                if (cookie != null && !cookie.isEmpty()) {
                    connection.setRequestProperty("Cookie", cookie);
                }

                int status = connection.getResponseCode();
                if (status == HttpURLConnection.HTTP_MOVED_PERM
                        || status == HttpURLConnection.HTTP_MOVED_TEMP
                        || status == HttpURLConnection.HTTP_SEE_OTHER
                        || status == 307
                        || status == 308) {
                    String location = connection.getHeaderField("Location");
                    connection.disconnect();
                    connection = null;
                    if (location == null || location.trim().isEmpty()) {
                        return null;
                    }
                    current = new URL(current, location);
                    continue;
                }
                if (status != HttpURLConnection.HTTP_OK) {
                    return null;
                }
                String contentType = connection.getContentType();
                if (contentType != null
                        && !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
                    return null;
                }

                try (InputStream input = new BufferedInputStream(connection.getInputStream());
                     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int total = 0;
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        total += read;
                        if (total > 8 * 1024 * 1024) {
                            return null;
                        }
                        output.write(buffer, 0, read);
                    }
                    byte[] imageBytes = output.toByteArray();
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.length, bounds);
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                        return null;
                    }
                    int sample = 1;
                    while (bounds.outWidth / sample > 480 || bounds.outHeight / sample > 720) {
                        sample *= 2;
                    }
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inSampleSize = sample;
                    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    return BitmapFactory.decodeByteArray(
                            imageBytes,
                            0,
                            imageBytes.length,
                            options
                    );
                }
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
        return null;
    }

    private void applyReaderButtonStyle() {
        int sizeDp = Math.max(28, Math.min(56,
                preferences.getInt(PREF_READER_BUTTON_SIZE, DEFAULT_READER_BUTTON_SIZE_DP)));
        int color = preferences.getInt(PREF_READER_BUTTON_COLOR, DEFAULT_READER_BUTTON_COLOR);
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) exitReaderChip.getLayoutParams();
        if (params != null) {
            params.width = dp(sizeDp);
            params.height = dp(sizeDp);
            exitReaderChip.setLayoutParams(params);
        }
        exitReaderChip.setTextSize(Math.max(14, sizeDp * 0.43f));
        exitReaderChip.setBackground(circleDrawable(Color.argb(205, Color.red(color), Color.green(color), Color.blue(color))));
    }

    private void showReaderButtonSettings() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(6), dp(22), 0);

        int currentSize = Math.max(28, Math.min(56,
                preferences.getInt(PREF_READER_BUTTON_SIZE, DEFAULT_READER_BUTTON_SIZE_DP)));
        TextView sizeLabel = new TextView(this);
        sizeLabel.setText("Размер: " + currentSize + " dp");
        sizeLabel.setTextColor(Color.WHITE);
        sizeLabel.setTextSize(15);
        content.addView(sizeLabel);

        SeekBar size = new SeekBar(this);
        size.setMax(28);
        size.setProgress(currentSize - 28);
        size.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                sizeLabel.setText("Размер: " + (28 + progress) + " dp");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });
        content.addView(size);

        TextView colorLabel = new TextView(this);
        colorLabel.setText("Цвет кнопки");
        colorLabel.setTextColor(Color.WHITE);
        colorLabel.setTextSize(15);
        colorLabel.setPadding(0, dp(10), 0, dp(4));
        content.addView(colorLabel);

        RadioGroup colors = new RadioGroup(this);
        int[] colorValues = {0xffe53935, 0xff6f6f78, 0xff1976d2, 0xff7b1fa2, 0xff00897b};
        String[] colorNames = {"Красный", "Серый", "Синий", "Фиолетовый", "Бирюзовый"};
        int selectedColor = preferences.getInt(PREF_READER_BUTTON_COLOR, DEFAULT_READER_BUTTON_COLOR);
        for (int index = 0; index < colorValues.length; index++) {
            RadioButton radio = new RadioButton(this);
            radio.setId(View.generateViewId());
            radio.setTag(colorValues[index]);
            radio.setText(colorNames[index]);
            radio.setTextColor(Color.WHITE);
            radio.setButtonTintList(ColorStateList.valueOf(colorValues[index]));
            colors.addView(radio);
            if (colorValues[index] == selectedColor) {
                radio.setChecked(true);
            }
        }
        content.addView(colors);

        new AlertDialog.Builder(this)
                .setTitle("Кнопка ☰")
                .setView(content)
                .setPositiveButton("Сохранить", (dialog, which) -> {
                    int color = DEFAULT_READER_BUTTON_COLOR;
                    View checked = colors.findViewById(colors.getCheckedRadioButtonId());
                    if (checked != null && checked.getTag() instanceof Integer) {
                        color = (Integer) checked.getTag();
                    }
                    preferences.edit()
                            .putInt(PREF_READER_BUTTON_SIZE, 28 + size.getProgress())
                            .putInt(PREF_READER_BUTTON_COLOR, color)
                            .apply();
                    applyReaderButtonStyle();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void refreshCurrentMangaMetadata() {
        if (!isMangaUrl(webView.getUrl())) {
            clearCurrentMangaMetadata();
            return;
        }
        webView.evaluateJavascript(mangaMetadataScript(), value -> {
            FavoriteStore.Item item = parseMangaMetadata(value);
            if (item == null) {
                return;
            }
            applyCurrentMangaMetadata(item);
            if (favoriteStore.contains(item.slug)) {
                favoriteStore.upsert(item);
            }
        });
    }

    private void toggleCurrentFavorite() {
        if (!isMangaUrl(webView.getUrl())) {
            Toast.makeText(this, "Сначала открой тайтл или его главу", Toast.LENGTH_SHORT).show();
            return;
        }
        webView.evaluateJavascript(mangaMetadataScript(), value -> {
            FavoriteStore.Item item = parseMangaMetadata(value);
            if (item == null || item.slug.isEmpty()) {
                Toast.makeText(this, "Не удалось определить тайтл", Toast.LENGTH_LONG).show();
                return;
            }
            applyCurrentMangaMetadata(item);
            if (favoriteStore.contains(item.slug)) {
                favoriteStore.remove(item.slug);
                Toast.makeText(this, "Удалено из избранного", Toast.LENGTH_SHORT).show();
            } else {
                favoriteStore.upsert(item);
                Toast.makeText(this, "Добавлено в избранное", Toast.LENGTH_SHORT).show();
            }
            if (isFavoritesVisible()) {
                populateFavorites();
            }
        });
    }

    private String mangaMetadataScript() {
        return "(function(){try{"
                + "var p=location.pathname.split('/').filter(Boolean);"
                + "if(p.length<2||p[0]!=='manga')return null;"
                + "var slug=p[1];"
                + "var n=document.querySelector('.manga__name');"
                + "var og=document.querySelector('meta[property=\\\"og:title\\\"]');"
                + "var title=n?n.textContent.trim():(og?og.content:document.title);"
                + "title=String(title||slug).replace(/\\s+\\d+(?:[.,]\\d+)?\\s+глава.*$/i,'')"
                + ".replace(/\\s+[-–]\\s+(?:Манга|Манхва|Маньхуа).*$/i,'').trim();"
                + "var pi=document.querySelector('.manga__img img');"
                + "var poi=document.querySelector('meta[property=\\\"og:image\\\"]');"
                + "var poster=pi?(pi.currentSrc||pi.src):(poi?poi.content:'');"
                + "if(poster){try{poster=new URL(poster,location.href).href;}catch(e){poster='';}}"
                + "var latest=0;"
                + "document.querySelectorAll('[data-chapter],a[href*=\\\"/manga/\\\"]').forEach(function(a){"
                + "var c=parseInt(a.getAttribute('data-chapter')||'',10);"
                + "if(!c){try{var q=new URL(a.href,location.href).pathname.split('/').filter(Boolean);"
                + "if(q[0]==='manga'&&q[1]===slug&&q.length>=4)c=parseInt(q[3],10)||0;}catch(e){}}"
                + "if(c>latest)latest=c;});"
                + "var volume=p.length>=3?(parseInt(p[2],10)||0):0;"
                + "var chapter=p.length>=4?(parseInt(p[3],10)||0):0;"
                + "return {slug:slug,title:title,titleUrl:location.origin+'/manga/'+slug,posterUrl:poster,"
                + "lastUrl:chapter?location.href:'',volume:volume,chapter:chapter,latestChapter:latest};"
                + "}catch(e){return null;}})();";
    }

    private FavoriteStore.Item parseMangaMetadata(String value) {
        try {
            if (value == null || "null".equals(value)) {
                return null;
            }
            JSONObject json = new JSONObject(value);
            FavoriteStore.Item item = new FavoriteStore.Item();
            item.slug = json.optString("slug", "").trim();
            item.title = json.optString("title", item.slug).trim();
            item.titleUrl = json.optString("titleUrl", "").trim();
            item.posterUrl = json.optString("posterUrl", "").trim();
            item.lastUrl = json.optString("lastUrl", "").trim();
            item.volume = Math.max(0, json.optInt("volume", 0));
            item.chapter = Math.max(0, json.optInt("chapter", 0));
            item.latestChapter = Math.max(0, json.optInt("latestChapter", 0));
            item.chapterProgress = item.chapter > 0
                    && item.slug.equals(currentSlug)
                    && item.chapter == currentChapter
                    ? currentChapterProgress
                    : 0;
            item.updatedAt = System.currentTimeMillis();
            if (item.titleUrl.isEmpty()) {
                item.titleUrl = "https://mangabuff.ru/manga/" + item.slug;
            }
            return item.slug.isEmpty() ? null : item;
        } catch (Exception ignored) {
            return null;
        }
    }

    private void applyCurrentMangaMetadata(FavoriteStore.Item item) {
        boolean titleChanged = !item.slug.equals(currentSlug);
        boolean chapterChanged = item.chapter != currentChapter;
        currentSlug = item.slug;
        currentTitle = item.title;
        currentVolume = item.volume;
        currentChapter = item.chapter;
        if (titleChanged || chapterChanged) {
            currentChapterProgress = item.chapterProgress;
        }
        currentLatestChapter = titleChanged
                ? item.latestChapter
                : Math.max(currentLatestChapter, item.latestChapter);
    }

    private void clearCurrentMangaMetadata() {
        currentSlug = "";
        currentTitle = "";
        currentVolume = 0;
        currentChapter = 0;
        currentLatestChapter = 0;
        currentChapterProgress = 0;
    }

    private boolean isMangaUrl(String url) {
        if (!isAllowedWebUrl(url)) {
            return false;
        }
        try {
            List<String> segments = Uri.parse(url).getPathSegments();
            return segments.size() >= 2 && "manga".equalsIgnoreCase(segments.get(0));
        } catch (Exception ignored) {
            return false;
        }
    }

    private void openCurrentInBrowser() {
        String url = webView.getUrl();
        if (!isAllowedWebUrl(url)) {
            Toast.makeText(this, "Нет страницы для открытия", Toast.LENGTH_SHORT).show();
            return;
        }
        openExternal(Intent.ACTION_VIEW, Uri.parse(url));
    }

    private void shareCurrentChapter() {
        String url = webView.getUrl();
        if (!isChapterUrl(url)) {
            Toast.makeText(this, "Поделиться можно внутри главы", Toast.LENGTH_SHORT).show();
            return;
        }
        String title = currentTitle.isEmpty() ? "Глава MangaBuff" : currentTitle;
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("text/plain");
        share.putExtra(Intent.EXTRA_SUBJECT, title);
        share.putExtra(Intent.EXTRA_TEXT, title + "\n" + url);
        try {
            startActivity(Intent.createChooser(share, "Поделиться главой"));
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, "Нет приложения для отправки ссылки", Toast.LENGTH_LONG).show();
        }
    }

    private void showAppMenu(View anchor) {
        Dialog sheet = new Dialog(this);
        FrameLayout outer = new FrameLayout(this);
        outer.setPadding(dp(12), dp(8), dp(12), dp(12));

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14), dp(12), dp(14), dp(16));
        panel.setBackground(roundedDrawable(Color.rgb(20, 20, 24), dp(26)));
        panel.setElevation(dp(18));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("Ещё");
        title.setTextColor(Color.WHITE);
        title.setTextSize(22);
        title.setIncludeFontPadding(false);
        titles.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText("Быстрые действия");
        subtitle.setTextColor(Color.rgb(154, 154, 164));
        subtitle.setTextSize(12);
        subtitle.setIncludeFontPadding(false);
        subtitle.setPadding(0, dp(2), 0, 0);
        titles.addView(subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(0, dp(52), 1f));

        TextView close = createRoundCloseButton(view -> sheet.dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
        panel.addView(header);

        List<View> items = new ArrayList<>();
        items.add(createMoreMenuItem(sheet, "↻", "Обновить", webView::reload));
        items.add(createMoreMenuItem(sheet, "↗", "В браузере", this::openCurrentInBrowser));

        if (!currentSlug.isEmpty() || isMangaUrl(webView.getUrl())) {
            boolean favorite = favoriteStore.contains(currentSlug);
            items.add(createMoreMenuItem(
                    sheet,
                    favorite ? "★" : "☆",
                    favorite ? "Убрать из избранного" : "В избранное",
                    this::toggleCurrentFavorite
            ));
        }
        items.add(createMoreMenuItem(sheet, "☰", "Настроить кнопку", this::showReaderButtonSettings));
        items.add(createMoreMenuItem(sheet, "⇩", "Обновления", () -> checkForUpdates(true)));
        items.add(createMoreMenuItem(sheet, "ⓘ", "О приложении", this::showAboutDialog));

        LinearLayout grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(0, dp(8), 0, 0);
        for (int index = 0; index < items.size(); index += 2) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            View first = items.get(index);
            LinearLayout.LayoutParams firstParams = new LinearLayout.LayoutParams(
                    0,
                    dp(68),
                    1f
            );
            firstParams.setMargins(dp(3), dp(4), dp(3), dp(4));
            row.addView(first, firstParams);

            View second = index + 1 < items.size() ? items.get(index + 1) : new View(this);
            LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(
                    0,
                    dp(68),
                    1f
            );
            secondParams.setMargins(dp(3), dp(4), dp(3), dp(4));
            row.addView(second, secondParams);
            grid.addView(row, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(76)
            ));
        }
        panel.addView(grid);
        outer.addView(panel, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM
        ));

        sheet.setContentView(outer);
        sheet.setCanceledOnTouchOutside(true);
        sheet.show();
        Window window = sheet.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.58f;
            window.setAttributes(attributes);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setNavigationBarColor(Color.rgb(9, 9, 11));
        }
    }

    private View createMoreMenuItem(
            Dialog sheet,
            String iconText,
            String labelText,
            Runnable action
    ) {
        LinearLayout tile = new LinearLayout(this);
        tile.setOrientation(LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER_VERTICAL);
        tile.setPadding(dp(13), dp(7), dp(10), dp(7));
        tile.setBackground(roundedDrawable(Color.rgb(38, 38, 45), dp(17)));
        tile.setElevation(dp(2));

        TextView icon = new TextView(this);
        icon.setText(iconText);
        icon.setTextColor(Color.rgb(255, 105, 98));
        icon.setTextSize(21);
        icon.setGravity(Gravity.CENTER);
        icon.setIncludeFontPadding(false);
        tile.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(44)));

        TextView label = new TextView(this);
        label.setText(labelText);
        label.setTextColor(Color.rgb(240, 240, 244));
        label.setTextSize(13);
        label.setMaxLines(2);
        label.setGravity(Gravity.CENTER_VERTICAL);
        label.setIncludeFontPadding(false);
        tile.addView(label, new LinearLayout.LayoutParams(0, dp(48), 1f));

        tile.setOnClickListener(view -> {
            sheet.dismiss();
            action.run();
        });
        return tile;
    }

    private void showAboutDialog() {
        new AlertDialog.Builder(this)
                .setTitle("MangaBuff Reader")
                .setMessage(
                        "Версия " + getInstalledVersionName()
                                + " · сборка " + getInstalledVersionCode() + "\n\n"
                                + "Неофициальное персональное приложение для комфортного чтения MangaBuff. "
                                + "Данные авторизации хранятся локально средствами Android WebView."
                )
                .setPositiveButton("Проверить обновления", (dialog, which) -> checkForUpdates(true))
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void checkForUpdates(boolean userInitiated) {
        if (updateCheckRunning) {
            if (userInitiated) {
                Toast.makeText(this, "Проверка уже выполняется", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        long now = System.currentTimeMillis();
        long lastCheck = preferences.getLong(PREF_LAST_UPDATE_CHECK, 0L);
        if (!userInitiated && now - lastCheck < UPDATE_CHECK_INTERVAL_MS) {
            return;
        }

        updateCheckRunning = true;
        if (userInitiated) {
            Toast.makeText(this, "Проверяю обновления…", Toast.LENGTH_SHORT).show();
        }

        new Thread(() -> {
            try {
                String json = downloadSmallText(UPDATE_MANIFEST_URL);
                UpdateInfo update = parseUpdateInfo(json);
                long installedVersion = getInstalledVersionCode();
                preferences.edit()
                        .putLong(PREF_LAST_UPDATE_CHECK, System.currentTimeMillis())
                        .apply();

                runOnUiThread(() -> {
                    if (!canShowUi()) {
                        return;
                    }
                    if (update.versionCode > installedVersion) {
                        showUpdateAvailable(update);
                    } else if (userInitiated) {
                        Toast.makeText(
                                MainActivity.this,
                                "Установлена последняя версия " + getInstalledVersionName(),
                                Toast.LENGTH_LONG
                        ).show();
                    }
                });
            } catch (Exception exception) {
                if (userInitiated) {
                    runOnUiThread(() -> {
                        if (canShowUi()) {
                            Toast.makeText(
                                    MainActivity.this,
                                    "Не удалось проверить обновления. Попробуй позже.",
                                    Toast.LENGTH_LONG
                            ).show();
                        }
                    });
                }
            } finally {
                updateCheckRunning = false;
            }
        }, "MangaBuff-update-check").start();
    }

    private String downloadSmallText(String address) throws Exception {
        HttpURLConnection connection = openHttpsConnection(address);
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > 128 * 1024) {
                    throw new IOException("Файл обновления слишком большой");
                }
                output.write(buffer, 0, read);
            }
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    private UpdateInfo parseUpdateInfo(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        int versionCode = root.getInt("versionCode");
        String versionName = root.getString("versionName").trim();
        String apkUrl = root.getString("apkUrl").trim();
        String sha256 = root.getString("sha256").trim().toLowerCase(Locale.ROOT);
        String changes = root.optString("changes", "Исправления и улучшения.").trim();

        if (versionCode < 1 || versionName.isEmpty() || versionName.length() > 40) {
            throw new IOException("Некорректная версия обновления");
        }
        if (!sha256.matches("[0-9a-f]{64}")) {
            throw new IOException("Некорректная контрольная сумма");
        }
        if (changes.isEmpty()) {
            changes = "Исправления и улучшения.";
        } else if (changes.length() > 3000) {
            changes = changes.substring(0, 3000);
        }

        Uri apkUri = Uri.parse(apkUrl);
        String host = apkUri.getHost();
        String path = apkUri.getPath();
        if (!"https".equalsIgnoreCase(apkUri.getScheme())
                || host == null
                || !"github.com".equalsIgnoreCase(host)
                || path == null
                || !path.startsWith("/EnoTik20/mangabuff-reader/releases/download/")
                || !path.toLowerCase(Locale.ROOT).endsWith(".apk")) {
            throw new IOException("Некорректный адрес APK");
        }

        return new UpdateInfo(versionCode, versionName, apkUrl, sha256, changes);
    }

    private HttpURLConnection openHttpsConnection(String address) throws Exception {
        URL current = new URL(address);
        for (int redirect = 0; redirect <= 6; redirect++) {
            if (!"https".equalsIgnoreCase(current.getProtocol())) {
                throw new IOException("Разрешено только HTTPS-соединение");
            }

            HttpURLConnection connection = (HttpURLConnection) current.openConnection();
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept", "application/json, application/octet-stream;q=0.9, */*;q=0.8");
            connection.setRequestProperty("User-Agent", "MangaBuff-Reader/" + getInstalledVersionName());
            int status = connection.getResponseCode();

            if (status == HttpURLConnection.HTTP_MOVED_PERM
                    || status == HttpURLConnection.HTTP_MOVED_TEMP
                    || status == HttpURLConnection.HTTP_SEE_OTHER
                    || status == 307
                    || status == 308) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.trim().isEmpty()) {
                    throw new IOException("Пустой адрес перенаправления");
                }
                current = new URL(current, location);
                continue;
            }

            if (status != HttpURLConnection.HTTP_OK) {
                connection.disconnect();
                throw new IOException("Сервер обновлений вернул HTTP " + status);
            }
            return connection;
        }
        throw new IOException("Слишком много перенаправлений");
    }

    private void showUpdateAvailable(UpdateInfo update) {
        String message = update.changes
                + "\n\nУстановлена: " + getInstalledVersionName()
                + "\nДоступна: " + update.versionName;
        new AlertDialog.Builder(this)
                .setTitle("Доступно обновление")
                .setMessage(message)
                .setPositiveButton("Скачать", (dialog, which) -> downloadUpdate(update))
                .setNegativeButton("Позже", null)
                .show();
    }

    private void downloadUpdate(UpdateInfo update) {
        if (updateDownloadRunning) {
            Toast.makeText(this, "Обновление уже скачивается", Toast.LENGTH_SHORT).show();
            return;
        }
        updateDownloadRunning = true;

        ProgressDialog progress = new ProgressDialog(this);
        progress.setTitle("Загрузка обновления " + update.versionName);
        progress.setMessage("Подключение к GitHub…");
        progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
        progress.setIndeterminate(true);
        progress.setCancelable(false);
        progress.show();

        new Thread(() -> {
            try {
                File apk = downloadAndVerifyApk(update, progress);
                runOnUiThread(() -> {
                    if (progress.isShowing()) {
                        progress.dismiss();
                    }
                    if (canShowUi()) {
                        requestApkInstall(apk);
                    }
                });
            } catch (Exception exception) {
                File updateFile = getUpdateFile();
                if (updateFile.exists()) {
                    updateFile.delete();
                }
                runOnUiThread(() -> {
                    if (progress.isShowing()) {
                        progress.dismiss();
                    }
                    if (canShowUi()) {
                        Toast.makeText(
                                MainActivity.this,
                                "Обновление не установлено: файл не прошёл проверку или не загрузился.",
                                Toast.LENGTH_LONG
                        ).show();
                    }
                });
            } finally {
                updateDownloadRunning = false;
            }
        }, "MangaBuff-update-download").start();
    }

    private File downloadAndVerifyApk(UpdateInfo update, ProgressDialog progress) throws Exception {
        File target = getUpdateFile();
        File temporary = new File(getCacheDir(), UPDATE_APK_FILE + ".part");
        if (temporary.exists()) {
            temporary.delete();
        }
        if (target.exists()) {
            target.delete();
        }

        HttpURLConnection connection = openHttpsConnection(update.apkUrl);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int contentLength = connection.getContentLength();
        runOnUiThread(() -> {
            if (contentLength > 0) {
                progress.setIndeterminate(false);
                progress.setMax(100);
                progress.setProgress(0);
                progress.setMessage("Скачивание APK…");
            } else {
                progress.setMessage("Скачивание APK…");
            }
        });

        long downloaded = 0L;
        long lastProgressUpdate = 0L;
        try (InputStream input = new BufferedInputStream(connection.getInputStream());
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                downloaded += read;

                long now = System.currentTimeMillis();
                if (contentLength > 0 && now - lastProgressUpdate >= 200L) {
                    int percent = (int) Math.min(100L, downloaded * 100L / contentLength);
                    runOnUiThread(() -> progress.setProgress(percent));
                    lastProgressUpdate = now;
                }
            }
            output.getFD().sync();
        } finally {
            connection.disconnect();
        }

        String actualHash = toHex(digest.digest());
        if (!actualHash.equalsIgnoreCase(update.sha256)) {
            temporary.delete();
            throw new IOException("SHA-256 не совпадает");
        }
        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("Не удалось сохранить APK");
        }

        try {
            verifyDownloadedApk(target, update.versionCode);
        } catch (Exception exception) {
            target.delete();
            throw exception;
        }
        return target;
    }

    private void verifyDownloadedApk(File apk, int expectedVersionCode) throws Exception {
        PackageManager manager = getPackageManager();
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
        PackageInfo candidate = manager.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        PackageInfo installed = manager.getPackageInfo(getPackageName(), flags);

        if (candidate == null || !getPackageName().equals(candidate.packageName)) {
            throw new IOException("APK принадлежит другому приложению");
        }
        long candidateVersion = getLongVersionCode(candidate);
        long installedVersion = getLongVersionCode(installed);
        if (candidateVersion != expectedVersionCode || candidateVersion <= installedVersion) {
            throw new IOException("Некорректный номер версии APK");
        }

        Set<String> installedCertificates = certificateDigests(installed);
        Set<String> candidateCertificates = certificateDigests(candidate);
        installedCertificates.retainAll(candidateCertificates);
        if (installedCertificates.isEmpty()) {
            throw new IOException("Подпись APK не совпадает");
        }
    }

    private Set<String> certificateDigests(PackageInfo info) throws Exception {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            signatures = info.signingInfo.getApkContentsSigners();
        } else {
            signatures = info.signatures;
        }
        if (signatures == null || signatures.length == 0) {
            throw new IOException("В APK отсутствует подпись");
        }

        Set<String> result = new HashSet<>();
        for (Signature signature : signatures) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            result.add(toHex(digest.digest(signature.toByteArray())));
        }
        return result;
    }

    private String toHex(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            builder.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return builder.toString();
    }

    private void requestApkInstall(File apk) {
        if (!apk.exists()) {
            preferences.edit().putBoolean(PREF_PENDING_INSTALL, false).apply();
            Toast.makeText(this, "Файл обновления не найден", Toast.LENGTH_LONG).show();
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            preferences.edit().putBoolean(PREF_PENDING_INSTALL, true).apply();
            Intent settingsIntent = new Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName())
            );
            try {
                startActivity(settingsIntent);
                Toast.makeText(
                        this,
                        "Разреши установку обновлений из MangaBuff Reader и вернись назад.",
                        Toast.LENGTH_LONG
                ).show();
            } catch (ActivityNotFoundException exception) {
                preferences.edit().putBoolean(PREF_PENDING_INSTALL, false).apply();
                Toast.makeText(this, "Не удалось открыть разрешение установки", Toast.LENGTH_LONG).show();
            }
            return;
        }

        preferences.edit().putBoolean(PREF_PENDING_INSTALL, false).apply();
        Uri contentUri = UpdateFileProvider.getUpdateUri(this);
        Intent installIntent = new Intent(Intent.ACTION_VIEW);
        installIntent.setDataAndType(contentUri, APK_MIME_TYPE);
        installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        List<ResolveInfo> handlers = getPackageManager().queryIntentActivities(
                installIntent,
                PackageManager.MATCH_DEFAULT_ONLY
        );
        for (ResolveInfo handler : handlers) {
            if (handler.activityInfo != null) {
                grantUriPermission(
                        handler.activityInfo.packageName,
                        contentUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                );
            }
        }

        try {
            startActivity(installIntent);
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, "Системный установщик APK не найден", Toast.LENGTH_LONG).show();
        }
    }

    private File getUpdateFile() {
        return new File(getCacheDir(), UPDATE_APK_FILE);
    }

    private long getInstalledVersionCode() {
        try {
            return getLongVersionCode(getPackageManager().getPackageInfo(getPackageName(), 0));
        } catch (PackageManager.NameNotFoundException exception) {
            return 0L;
        }
    }

    private long getLongVersionCode(PackageInfo info) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return info.getLongVersionCode();
        }
        return info.versionCode;
    }

    private String getInstalledVersionName() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName == null ? "—" : info.versionName;
        } catch (PackageManager.NameNotFoundException exception) {
            return "—";
        }
    }

    private boolean canShowUi() {
        return !isFinishing() && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1 || !isDestroyed());
    }

    private View createErrorPanel() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER);
        panel.setPadding(dp(28), dp(28), dp(28), dp(28));
        panel.setBackgroundColor(Color.rgb(9, 9, 11));
        panel.setVisibility(View.GONE);

        TextView title = new TextView(this);
        title.setText("Не удалось открыть MangaBuff");
        title.setTextColor(Color.WHITE);
        title.setTextSize(20);
        title.setGravity(Gravity.CENTER);
        panel.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        errorText = new TextView(this);
        errorText.setText("Проверь интернет-соединение и попробуй ещё раз.");
        errorText.setTextColor(Color.rgb(180, 180, 186));
        errorText.setTextSize(14);
        errorText.setGravity(Gravity.CENTER);
        errorText.setPadding(0, dp(12), 0, dp(18));
        panel.addView(errorText, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        Button retry = new Button(this);
        retry.setText("Повторить");
        retry.setAllCaps(false);
        retry.setTextColor(Color.WHITE);
        retry.setBackground(roundedDrawable(Color.rgb(229, 57, 53), dp(14)));
        retry.setOnClickListener(view -> {
            errorPanel.setVisibility(View.GONE);
            webView.reload();
        });
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(dp(180), dp(50));
        panel.addView(retry, retryParams);

        Button browser = new Button(this);
        browser.setText("Открыть в браузере");
        browser.setAllCaps(false);
        browser.setTextColor(Color.rgb(225, 225, 230));
        browser.setBackground(roundedDrawable(Color.rgb(42, 42, 48), dp(14)));
        browser.setOnClickListener(view -> openCurrentInBrowser());
        LinearLayout.LayoutParams browserParams = new LinearLayout.LayoutParams(dp(180), dp(50));
        browserParams.setMargins(0, dp(10), 0, 0);
        panel.addView(browser, browserParams);
        return panel;
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportZoom(true);
        settings.setLoadWithOverviewMode(false);
        settings.setUseWideViewPort(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        String defaultUserAgent = settings.getUserAgentString();
        if (defaultUserAgent != null) {
            settings.setUserAgentString(defaultUserAgent
                    .replace("; wv", "")
                    .replace("Version/4.0 ", ""));
        }

        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);

        webView.setVerticalScrollBarEnabled(false);
        webView.setHorizontalScrollBarEnabled(false);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setWebViewClient(new MangaBuffWebViewClient());
        webView.setWebChromeClient(new MangaBuffChromeClient());
        webView.setDownloadListener(new MangaBuffDownloadListener());
        webView.setOnScrollChangeListener((view, scrollX, scrollY, oldScrollX, oldScrollY) -> {
            if (!readerMode) {
                return;
            }
            updateReadingProgress(false);
            long now = System.currentTimeMillis();
            if (now - lastProgressSaveAt >= 1200L) {
                lastProgressSaveAt = now;
                saveCurrentScroll();
            }
        });
    }

    private final class MangaBuffWebViewClient extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame()) {
                return false;
            }
            return handleNavigation(request.getUrl().toString());
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleNavigation(url);
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(
                WebView view,
                WebResourceRequest request
        ) {
            Uri uri = request == null ? null : request.getUrl();
            if (isBlockedTrackingOrAdRequest(uri)) {
                return new WebResourceResponse(
                        "text/plain",
                        "UTF-8",
                        new ByteArrayInputStream(new byte[0])
                );
            }
            return super.shouldInterceptRequest(view, request);
        }

        @Override
        public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setProgress(5);
            errorPanel.setVisibility(View.GONE);
            lastSiteCleanupAt = 0L;
            updateReaderAvailability(url);
            scheduleSiteCleanup();
        }

        @Override
        public void onLoadResource(WebView view, String url) {
            super.onLoadResource(view, url);
            String pageUrl = view.getUrl();
            if (!isAllowedWebUrl(pageUrl)) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now - lastSiteCleanupAt < 650L) {
                return;
            }
            lastSiteCleanupAt = now;
            injectPersistentSiteCleanup();
            if (readerMode && isChapterUrl(pageUrl)) {
                injectReaderCss(true);
            }
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            super.onPageCommitVisible(view, url);
            injectPersistentSiteCleanup();
            if (isChapterUrl(url)) {
                setReaderMode(true);
            }
            scheduleSiteCleanup();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            progressBar.setVisibility(View.GONE);

            if (isAllowedWebUrl(url)) {
                preferences.edit().putString(PREF_LAST_URL, url).apply();
            }

            injectPersistentSiteCleanup();
            updateReaderAvailability(url);
            if (isChapterUrl(url)) {
                setReaderMode(true);
            } else if (readerMode) {
                setReaderMode(false);
            }
            scheduleSiteCleanup();
            refreshCurrentMangaMetadata();
            view.postDelayed(() -> refreshCurrentMangaMetadata(), 1000);

            if (isChapterUrl(url)) {
                restoreSavedPosition(url);
                view.postDelayed(() -> updateReadingProgress(false), 1200);
            }
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            super.doUpdateVisitedHistory(view, url, isReload);
            updateReaderAvailability(url);
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            super.onReceivedError(view, request, error);
            if (request.isForMainFrame()) {
                CharSequence description = error.getDescription();
                showError(description == null ? "Ошибка загрузки страницы" : description.toString());
            }
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            super.onReceivedHttpError(view, request, response);
            if (request.isForMainFrame() && response.getStatusCode() >= 500) {
                showError("Сервер временно недоступен: HTTP " + response.getStatusCode());
            }
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
            showError("Ошибка защищённого HTTPS-соединения");
        }
    }

    private void scheduleSiteCleanup() {
        int[] delays = {120, 450, 1100, 2400, 5000};
        for (int delay : delays) {
            webView.postDelayed(() -> {
                String currentUrl = webView.getUrl();
                if (!isAllowedWebUrl(currentUrl)) {
                    return;
                }
                injectPersistentSiteCleanup();
                if (readerMode && isChapterUrl(currentUrl)) {
                    injectReaderCss(true);
                }
            }, delay);
        }
    }

    private final class MangaBuffChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progressBar.setProgress(newProgress);
            progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
        }

        @Override
        public boolean onShowFileChooser(
                WebView webView,
                ValueCallback<Uri[]> newCallback,
                FileChooserParams fileChooserParams
        ) {
            if (fileChooserCallback != null) {
                fileChooserCallback.onReceiveValue(null);
            }
            fileChooserCallback = newCallback;
            try {
                Intent chooser = fileChooserParams.createIntent();
                startActivityForResult(chooser, FILE_CHOOSER_REQUEST);
                return true;
            } catch (ActivityNotFoundException exception) {
                fileChooserCallback = null;
                Toast.makeText(MainActivity.this, "На устройстве нет выбора файлов", Toast.LENGTH_SHORT).show();
                return false;
            }
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            request.deny();
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(
                String origin,
                GeolocationPermissions.Callback callback
        ) {
            callback.invoke(origin, false, false);
        }
    }

    private final class MangaBuffDownloadListener implements DownloadListener {
        @Override
        public void onDownloadStart(
                String url,
                String userAgent,
                String contentDisposition,
                String mimeType,
                long contentLength
        ) {
            Uri uri;
            try {
                uri = Uri.parse(url);
            } catch (Exception exception) {
                Toast.makeText(MainActivity.this, "Некорректная ссылка на файл", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!"https".equalsIgnoreCase(uri.getScheme())) {
                Toast.makeText(MainActivity.this, "Небезопасная загрузка заблокирована", Toast.LENGTH_SHORT).show();
                return;
            }

            try {
                String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
                DownloadManager.Request request = new DownloadManager.Request(uri);
                if (mimeType != null && !mimeType.isEmpty()) {
                    request.setMimeType(mimeType);
                }
                if (userAgent != null && !userAgent.isEmpty()) {
                    request.addRequestHeader("User-Agent", userAgent);
                }
                String cookies = CookieManager.getInstance().getCookie(url);
                if (cookies != null && !cookies.isEmpty()) {
                    request.addRequestHeader("Cookie", cookies);
                }
                request.setTitle(fileName);
                request.setDescription("Загрузка из MangaBuff");
                request.setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                );
                request.setAllowedOverMetered(true);
                request.setAllowedOverRoaming(false);
                request.setDestinationInExternalFilesDir(
                        MainActivity.this,
                        Environment.DIRECTORY_DOWNLOADS,
                        fileName
                );

                DownloadManager manager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
                if (manager != null) {
                    manager.enqueue(request);
                    Toast.makeText(MainActivity.this, "Загрузка началась", Toast.LENGTH_SHORT).show();
                }
            } catch (Exception exception) {
                Toast.makeText(MainActivity.this, "Не удалось начать загрузку", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private boolean handleNavigation(String url) {
        if (url == null || url.trim().isEmpty()) {
            return true;
        }

        Uri uri = Uri.parse(url);
        String scheme = uri.getScheme();
        if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
            if (isAllowedWebUrl(url)) {
                saveCurrentScroll();
                if (isFavoritesVisible()) {
                    hideFavorites();
                }
                return false;
            }
            openExternal(Intent.ACTION_VIEW, uri);
            return true;
        }

        if ("intent".equalsIgnoreCase(scheme)) {
            try {
                Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                startActivity(intent);
            } catch (URISyntaxException | ActivityNotFoundException ignored) {
                Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show();
            }
            return true;
        }

        if ("mailto".equalsIgnoreCase(scheme)
                || "tel".equalsIgnoreCase(scheme)
                || "tg".equalsIgnoreCase(scheme)) {
            openExternal(Intent.ACTION_VIEW, uri);
            return true;
        }

        return true;
    }

    private void openExternal(String action, Uri uri) {
        try {
            startActivity(new Intent(action, uri));
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, "Подходящее приложение не найдено", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isAllowedWebUrl(String url) {
        if (url == null) {
            return false;
        }
        try {
            Uri uri = Uri.parse(url);
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
                return false;
            }
            if (host == null) {
                return false;
            }
            String normalized = host.toLowerCase(Locale.ROOT);
            return "mangabuff.ru".equals(normalized) || normalized.endsWith(".mangabuff.ru");
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean isBlockedTrackingOrAdRequest(Uri uri) {
        if (uri == null || uri.getHost() == null) {
            return false;
        }
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        if ("mc.yandex.ru".equals(host)
                || "strm.yandex.ru".equals(host)
                || "avatars.mds.yandex.net".equals(host)
                || "an.yandex.ru".equals(host)
                || host.endsWith(".yandexadexchange.net")
                || host.endsWith(".adfox.ru")
                || host.endsWith(".doubleclick.net")
                || host.endsWith(".google-analytics.com")
                || host.endsWith(".googletagmanager.com")) {
            return true;
        }
        return ("yandex.ru".equals(host) || host.endsWith(".yandex.ru"))
                && (path.startsWith("/ads/") || path.startsWith("/an/"));
    }

    private boolean isChapterUrl(String url) {
        if (!isAllowedWebUrl(url)) {
            return false;
        }
        try {
            List<String> segments = Uri.parse(url).getPathSegments();
            return segments.size() >= 4
                    && "manga".equalsIgnoreCase(segments.get(0))
                    && !segments.get(1).isEmpty()
                    && !segments.get(2).isEmpty()
                    && !segments.get(3).isEmpty();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void updateReaderAvailability(String url) {
        boolean available = isChapterUrl(url);
        readerButton.setEnabled(available);
        readerButton.setAlpha(available ? 1f : 0.42f);
    }

    private void setReaderMode(boolean enabled) {
        if (enabled && !isChapterUrl(webView.getUrl())) {
            return;
        }
        readerMode = enabled;
        if (enabled && isFavoritesVisible()) {
            hideFavorites();
        }
        bottomBar.setVisibility(enabled ? View.GONE : View.VISIBLE);
        readerPanel.setVisibility(View.GONE);
        exitReaderChip.setVisibility(enabled ? View.VISIBLE : View.GONE);
        webView.setPadding(0, 0, 0, enabled ? 0 : dp(BOTTOM_BAR_HEIGHT_DP));
        webView.setKeepScreenOn(enabled);

        if (enabled) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            hideSystemBars();
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            showSystemBars();
        }
        injectReaderCss(enabled);
        if (enabled) {
            updateReadingProgress(false);
        }
    }

    private void injectReaderCss(boolean enabled) {
        String script;
        if (enabled) {
            script = "(function(){try{"
                    + "window.__mb_reader_active=true;window.__mb_last_cleanup_error='';"
                    + "window.__mb_hide_reader_node=function(x){if(!x)return;"
                    + "x.style.setProperty('display','none','important');"
                    + "x.style.setProperty('height','0','important');"
                    + "x.style.setProperty('min-height','0','important');"
                    + "x.style.setProperty('max-height','0','important');"
                    + "x.style.setProperty('margin','0','important');"
                    + "x.style.setProperty('padding','0','important');"
                    + "x.style.setProperty('overflow','hidden','important');"
                    + "x.setAttribute('aria-hidden','true');};"
                    + "window.__mb_apply_reader_cleanup=function(){"
                    + "['.reader__header','.reader-menu','#back-to-top'].forEach(function(q){try{"
                    + "Array.prototype.forEach.call(document.querySelectorAll(q),window.__mb_hide_reader_node);"
                    + "}catch(e){window.__mb_last_cleanup_error=String(e);}});"
                    + "Array.prototype.forEach.call(document.querySelectorAll('html,body,.reader,.reader__container,.reader__pages'),"
                    + "function(x){x.style.setProperty('margin-top','0','important');"
                    + "x.style.setProperty('padding-top','0','important');});return true;};"
                    + "var old=document.getElementById('__mb_reader_css');"
                    + "if(old&&old.parentNode)old.parentNode.removeChild(old);"
                    + "var s=document.createElement('style');s.id='__mb_reader_css';"
                    + "s.textContent='.reader__header,.reader-menu,.reader__top-a,.rek,.tg-prompt,#tg-prompt,"
                    + "[class*=\\\"tg-prompt\\\"],[id*=\\\"tg-prompt\\\"],.tg-button,.button-telegram,"
                    + "#back-to-top,[id^=\\\"yandex_rtb_\\\"]{display:none!important;"
                    + "height:0!important;min-height:0!important;max-height:0!important;margin:0!important;"
                    + "padding:0!important;overflow:hidden!important;}"
                    + "html,body,.reader,.reader__container,.reader__pages{margin-top:0!important;padding-top:0!important;}"
                    + ".reader__pages img{display:block!important;max-width:100%!important;height:auto!important;}';"
                    + "var host=document.head||document.documentElement;if(host)host.appendChild(s);"
                    + "window.__mb_apply_reader_cleanup();"
                    + "setTimeout(window.__mb_apply_reader_cleanup,100);"
                    + "setTimeout(window.__mb_apply_reader_cleanup,600);"
                    + "setTimeout(window.__mb_apply_reader_cleanup,1800);"
                    + "document.documentElement.style.colorScheme='dark';"
                    + "return 'mb-reader-ok';}catch(e){window.__mb_last_cleanup_error=String(e);"
                    + "return 'mb-reader-error:'+String(e);}})();";
            executePageScript(script, "mb-reader-ok");
        } else {
            script = "(function(){try{window.__mb_reader_active=false;"
                    + "var s=document.getElementById('__mb_reader_css');"
                    + "if(s&&s.parentNode)s.parentNode.removeChild(s);"
                    + "Array.prototype.forEach.call(document.querySelectorAll('.reader__header,.reader-menu,#back-to-top'),"
                    + "function(x){['display','height','min-height','max-height','margin','padding','overflow'].forEach("
                    + "function(p){x.style.removeProperty(p);});x.removeAttribute('aria-hidden');});"
                    + "Array.prototype.forEach.call(document.querySelectorAll('html,body,.reader,.reader__container,.reader__pages'),"
                    + "function(x){x.style.removeProperty('margin-top');x.style.removeProperty('padding-top');});"
                    + "document.documentElement.style.colorScheme='';return 'mb-reader-off';"
                    + "}catch(e){return 'mb-reader-error:'+String(e);}})();";
            executePageScript(script, "mb-reader-off");
        }
    }

    private void injectPersistentSiteCleanup() {
        String script = "(function(){try{window.__mb_last_cleanup_error='';"
                + "window.__mb_remove_node=function(x){if(x&&x!==document.body&&x.parentNode)"
                + "x.parentNode.removeChild(x);};"
                + "window.__mb_ancestor=function(x,q){while(x&&x!==document.body){try{"
                + "if(x.matches&&x.matches(q))return x;}catch(e){}x=x.parentElement;}return null;};"
                + "window.__mb_promo_root=function(x){var known=window.__mb_ancestor(x,"
                + "'.tg-prompt,.reader__wrapper,[class*=\\\"tg-prompt\\\"],[class*=\\\"telegram\\\"],"
                + "[class*=\\\"tg-promo\\\"],[id*=\\\"tg-prompt\\\"],[id*=\\\"telegram\\\"]');"
                + "if(known)return known;var p=x;for(var i=0;i<7&&p&&p!==document.body;i++,p=p.parentElement){"
                + "try{if(getComputedStyle(p).position==='fixed')return p;}catch(e){}}"
                + "return x&&x.parentElement?x.parentElement:x;};"
                + "window.__mb_clean_always=function(){"
                + "var selectors=['.tg-prompt','#tg-prompt','[class*=\\\"tg-prompt\\\"]',"
                + "'[id*=\\\"tg-prompt\\\"]','.tg-button','.button-telegram','.reader__top-a','.rek',"
                + "'[id^=\\\"yandex_rtb_\\\"]','[class*=\\\"telegram-promo\\\"]','[class*=\\\"tg-promo\\\"]'];"
                + "selectors.forEach(function(q){try{Array.prototype.forEach.call(document.querySelectorAll(q),"
                + "function(x){var target=x;"
                + "if(q==='.tg-button'||q==='.button-telegram')target=window.__mb_promo_root(x);"
                + "if(q.indexOf('yandex_rtb_')>=0){var ad=window.__mb_ancestor(x,'.rek,.reader__top-a');"
                + "if(ad)target=ad;}window.__mb_remove_node(target);});}"
                + "catch(e){window.__mb_last_cleanup_error=String(e);}});"
                + "try{Array.prototype.forEach.call(document.querySelectorAll('a[href*=\\\"t.me/\\\"]'),"
                + "function(a){window.__mb_remove_node(window.__mb_promo_root(a));});}catch(e){}"
                + "try{Array.prototype.forEach.call(document.querySelectorAll('a,button,[role=\\\"button\\\"],"
                + ".tg-prompt__title,.tg-prompt__subtitle'),function(x){"
                + "var t=String(x.innerText||x.textContent||'').replace(/\\s+/g,' ').toLowerCase();"
                + "if(t.length<240&&(t.indexOf('алмазы и промокоды')>=0||t.indexOf('telegram-канале mangabuff')>=0"
                + "||t.indexOf('уведомления о новых главах')>=0||t.indexOf('уведомить о новой главе')>=0))"
                + "window.__mb_remove_node(window.__mb_promo_root(x));});}catch(e){}"
                + "if(window.__mb_reader_active&&window.__mb_apply_reader_cleanup)"
                + "window.__mb_apply_reader_cleanup();return true;};"
                + "window.__mb_clean_always();"
                + "if(!window.__mb_clean_observer&&document.documentElement&&window.MutationObserver){"
                + "window.__mb_clean_observer=new MutationObserver(function(){"
                + "if(window.__mb_clean_pending)return;window.__mb_clean_pending=true;"
                + "setTimeout(function(){window.__mb_clean_pending=false;window.__mb_clean_always();},20);});"
                + "window.__mb_clean_observer.observe(document.documentElement,{childList:true,subtree:true});}"
                + "setTimeout(window.__mb_clean_always,120);setTimeout(window.__mb_clean_always,700);"
                + "setTimeout(window.__mb_clean_always,2000);setTimeout(window.__mb_clean_always,5000);"
                + "return 'mb-site-ok';}catch(e){window.__mb_last_cleanup_error=String(e);"
                + "return 'mb-site-error:'+String(e);}})();";
        executePageScript(script, "mb-site-ok");
    }

    private void executePageScript(String script, String successMarker) {
        webView.evaluateJavascript(script, value -> {
            if (JSONObject.quote(successMarker).equals(value)) {
                return;
            }
            String currentUrl = webView.getUrl();
            if (isAllowedWebUrl(currentUrl)) {
                webView.loadUrl("javascript:" + script);
            }
        });
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private void showSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private void goBack() {
        if (isFavoritesVisible()) {
            hideFavorites();
            return;
        }
        if (readerPanel.getVisibility() == View.VISIBLE) {
            hideReaderPanel();
            return;
        }
        if (webView.canGoBack()) {
            saveCurrentScroll();
            webView.goBack();
        } else {
            loadHome();
        }
    }

    private void loadHome() {
        if (isFavoritesVisible()) {
            hideFavorites();
        }
        if (!HOME_URL.equals(webView.getUrl())) {
            saveCurrentScroll();
            setReaderMode(false);
            webView.loadUrl(HOME_URL);
        }
    }

    private void showError(String details) {
        progressBar.setVisibility(View.GONE);
        errorText.setText(details + "\n\nПроверь интернет-соединение и нажми «Повторить».");
        errorPanel.setVisibility(View.VISIBLE);
    }

    private String safeSavedUrl(String candidate) {
        return isAllowedWebUrl(candidate) ? candidate : HOME_URL;
    }

    private String scrollKey(String url) {
        return PREF_LAST_SCROLL_PREFIX + Integer.toHexString(url == null ? 0 : url.hashCode());
    }

    private String scrollRatioKey(String url) {
        return PREF_LAST_SCROLL_RATIO_PREFIX + Integer.toHexString(url == null ? 0 : url.hashCode());
    }

    private void updateReadingProgress(boolean force) {
        if (!isChapterUrl(webView.getUrl()) || (progressQueryPending && !force)) {
            return;
        }
        progressQueryPending = true;
        webView.evaluateJavascript(
                "(function(){var y=Math.max(0,window.scrollY||document.documentElement.scrollTop||0);"
                        + "var h=Math.max(document.body?document.body.scrollHeight:0,"
                        + "document.documentElement?document.documentElement.scrollHeight:0);"
                        + "var m=Math.max(1,h-window.innerHeight);return Math.max(0,Math.min(100,Math.round(y*100/m)));})()",
                value -> {
                    progressQueryPending = false;
                    try {
                        int progress = Integer.parseInt(value == null ? "0" : value.replace("\"", "").trim());
                        currentChapterProgress = Math.max(0, Math.min(100, progress));
                        if (readerPanel.getVisibility() == View.VISIBLE) {
                            String chapterLabel = currentChapter > 0 ? "Глава " + currentChapter + " · " : "";
                            readerProgressText.setText(chapterLabel + currentChapterProgress + "%");
                            readerProgressBar.setProgress(currentChapterProgress);
                        }
                    } catch (NumberFormatException ignored) {
                        // DOM мог обновиться во время вычисления.
                    }
                }
        );
    }

    private void restoreSavedPosition(String url) {
        int savedY = preferences.getInt(scrollKey(url), 0);
        float savedRatio = preferences.getFloat(scrollRatioKey(url), 0f);
        if (savedY <= 0 && savedRatio <= 0f) {
            return;
        }
        String ratio = String.format(Locale.ROOT, "%.8f", Math.max(0f, Math.min(1f, savedRatio)));
        String script = "(function(){"
                + "window.__mb_restore_cancelled=false;"
                + "document.addEventListener('touchstart',function(){window.__mb_restore_cancelled=true;},"
                + "{once:true,passive:true});"
                + "var y=" + Math.max(0, savedY) + ",r=" + ratio + ",tries=0,stable=0,last=-1;"
                + "function go(){if(window.__mb_restore_cancelled)return;"
                + "var h=Math.max(document.body?document.body.scrollHeight:0,"
                + "document.documentElement?document.documentElement.scrollHeight:0);"
                + "var max=Math.max(0,h-window.innerHeight);var target=r>0?Math.round(max*r):y;"
                + "window.scrollTo(0,Math.max(0,target));stable=(h===last)?stable+1:0;last=h;tries++;"
                + "var pending=Array.prototype.some.call(document.images,function(i){return !i.complete;});"
                + "if(tries<48&&(pending||stable<5))setTimeout(go,250);else window.__mb_restore_cancelled=true;}"
                + "setTimeout(go,180);})();";
        webView.evaluateJavascript(script, null);
    }

    private void saveCurrentScroll() {
        String url = webView.getUrl();
        if (!isAllowedWebUrl(url)) {
            return;
        }
        webView.evaluateJavascript(
                "(function(){var y=Math.max(0,Math.round(window.scrollY||document.documentElement.scrollTop||0));"
                        + "var h=Math.max(document.body?document.body.scrollHeight:0,"
                        + "document.documentElement?document.documentElement.scrollHeight:0);"
                        + "var m=Math.max(1,h-window.innerHeight);"
                        + "return {y:y,ratio:Math.max(0,Math.min(1,y/m)),percent:Math.max(0,Math.min(100,Math.round(y*100/m)))};})()",
                value -> {
                    if (value == null) {
                        return;
                    }
                    try {
                        JSONObject state = new JSONObject(value);
                        int y = Math.max(0, state.optInt("y", 0));
                        float ratio = (float) Math.max(0.0, Math.min(1.0, state.optDouble("ratio", 0.0)));
                        int percent = Math.max(0, Math.min(100, state.optInt("percent", 0)));
                        preferences.edit()
                                .putInt(scrollKey(url), y)
                                .putFloat(scrollRatioKey(url), ratio)
                                .putString(PREF_LAST_URL, url)
                                .apply();
                        currentChapterProgress = percent;
                        if (!currentSlug.isEmpty() && isChapterUrl(url)) {
                            favoriteStore.updateProgress(
                                    currentSlug,
                                    url,
                                    currentVolume,
                                    currentChapter,
                                    percent
                            );
                        }
                    } catch (Exception ignored) {
                        // Страница могла закрыться до ответа JavaScript.
                    }
                }
        );
    }

    private GradientDrawable circleDrawable(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.OVAL);
        drawable.setColor(color);
        return drawable;
    }

    private GradientDrawable roundedDrawable(int color, int radiusPx) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(radiusPx);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != FILE_CHOOSER_REQUEST || fileChooserCallback == null) {
            return;
        }

        Uri[] result = null;
        if (resultCode == RESULT_OK) {
            if (data != null && data.getClipData() != null) {
                ClipData clipData = data.getClipData();
                result = new Uri[clipData.getItemCount()];
                for (int index = 0; index < clipData.getItemCount(); index++) {
                    result[index] = clipData.getItemAt(index).getUri();
                }
            } else if (data != null && data.getData() != null) {
                result = new Uri[]{data.getData()};
            }
        }

        fileChooserCallback.onReceiveValue(result);
        fileChooserCallback = null;
    }

    @Override
    public void onBackPressed() {
        if (isFavoritesVisible()) {
            hideFavorites();
        } else if (readerPanel.getVisibility() == View.VISIBLE) {
            hideReaderPanel();
        } else if (readerMode) {
            setReaderMode(false);
        } else if (webView.canGoBack()) {
            saveCurrentScroll();
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        webView.onResume();
        webView.resumeTimers();
        if (readerMode && !isFavoritesVisible()) {
            hideSystemBars();
        }

        if (preferences.getBoolean(PREF_PENDING_INSTALL, false)) {
            File apk = getUpdateFile();
            if (!apk.exists()) {
                preferences.edit().putBoolean(PREF_PENDING_INSTALL, false).apply();
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                    || getPackageManager().canRequestPackageInstalls()) {
                requestApkInstall(apk);
            }
        }
    }

    @Override
    protected void onPause() {
        saveCurrentScroll();
        CookieManager.getInstance().flush();
        webView.onPause();
        webView.pauseTimers();
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        webView.saveState(outState);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && readerMode && !isFavoritesVisible()) {
            hideSystemBars();
        }
    }

    @Override
    protected void onDestroy() {
        posterExecutor.shutdownNow();
        posterCache.evictAll();
        if (fileChooserCallback != null) {
            fileChooserCallback.onReceiveValue(null);
            fileChooserCallback = null;
        }
        if (webView != null) {
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) {
                parent.removeView(webView);
            }
            webView.stopLoading();
            webView.setWebChromeClient(null);
            webView.setWebViewClient(null);
            webView.destroy();
        }
        super.onDestroy();
    }
}
