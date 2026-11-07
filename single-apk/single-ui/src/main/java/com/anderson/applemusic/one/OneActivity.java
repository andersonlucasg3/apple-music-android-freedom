package com.anderson.applemusic.one;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The single-APK UI: plain framework views (no resources, no AndroidX, no Compose) so it
 * can live as an extra dex inside the patched Apple Music engine APK.
 *
 * <p>Browse ({@code __AUTO_ROOT__}), playback, session actions and catalogue search are
 * wired to {@link MediaClient} / {@link CatalogSearch}; artwork goes through
 * {@link ArtworkLoader}. Every pixel is created in code, all strings are literal.
 */
public final class OneActivity extends Activity
        implements MediaClient.Listener, UpdateManager.Listener {

    private static final String TAG = "AppleMusicOne";
    static final String ACTION_INSTALL_STATUS = "com.anderson.applemusic.one.INSTALL_STATUS";

    /** Auth type we ask the engine for: always show the Apple ID page. */
    private static final String AUTH_TYPE_CLASS =
            "com.apple.mediaservices.amskit.authentication.AuthenticateRequest$Type";
    /** Time for the host activity to build its window/ComposeView before we drive it. */
    private static final long SIGN_IN_SETTLE_MS = 400L;

    private static final int COLOR_BG = 0xFF000000;
    private static final int COLOR_SURFACE = 0xFF1C1C1E;
    private static final int COLOR_SURFACE_FOCUS = 0xFF313134;
    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_TEXT_DIM = 0xFFA1A1A6;
    private static final int COLOR_ACCENT = 0xFFFA243C;

    private static final long TICK_MS = 500L;
    private static final long ACTION_TIMEOUT_MS = 2500L;
    private static final String ACTION_CREATE_STATION = "CREATE_STATION";
    private static final String ACTION_FAVORITE = "FAVORITE";
    private static final String ACTION_ADD_TO_LIBRARY = "ADD_TO_LIBRARY";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor();

    private MediaClient client;
    private ArtworkLoader artwork;
    private CatalogSearch catalog;
    private UpdateManager updater;

    private AlertDialog updateDialog;
    private TextView updateMessage;
    private ProgressBar updateProgress;
    private TextView updateCheckButton;
    private TextView updateDownloadButton;
    private TextView updateCancelButton;
    private UpdateManager.Update pendingUpdate;

    private LinearLayout tabBar;
    private LinearLayout content;
    private TextView status;

    private LinearLayout miniPlayer;
    private ImageView miniCover;
    private TextView miniTitle;
    private TextView miniArtist;
    private TextView miniPlay;
    private TextView nowPlayingButton;

    private FrameLayout playerOverlay;
    private ImageView playerCover;
    private TextView playerTitle;
    private TextView playerArtist;
    private TextView playerTime;
    private SeekBar seekBar;
    private TextView playerPlay;
    private TextView playerStation;

    private FrameLayout searchOverlay;
    private EditText searchInput;
    private TextView searchStatus;
    private LinearLayout searchResults;

    private final List<MediaClient.Item> tabs = new ArrayList<MediaClient.Item>();
    private final Map<String, LinearLayout> sections = new HashMap<String, LinearLayout>();
    private final List<Level> levels = new ArrayList<Level>();
    private LinearLayout levelContainer;
    private int selectedTab = -1;

    private MediaClient.Snapshot latest = new MediaClient.Snapshot();
    private boolean seekTracking;
    private boolean rootReceived;
    private boolean hasTouch;
    private int lastArtworkHash;

    private static final class Level {
        final String id;
        final String title;

        Level(String id, String title) {
            this.id = id;
            this.title = title;
        }
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Same style the injected android:theme points at; belt and braces if the binary
        // manifest keeps the engine's own theme.
        setTheme(android.R.style.Theme_Material_NoActionBar);
        hasTouch = getPackageManager().hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);
        client = new MediaClient(this);
        artwork = new ArtworkLoader(this);
        catalog = new CatalogSearch(this);
        updater = new UpdateManager(this);
        updater.setListener(this);
        client.setListener(this);
        buildUi();
        client.connect();
        handleInstallStatus(getIntent());
        updater.autoCheck();
    }

    // --- UI scaffolding -----------------------------------------------------------

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable background(int fill, boolean focused) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(10));
        if (focused) drawable.setStroke(dp(2), COLOR_ACCENT);
        return drawable;
    }

    private void applyFocusBackground(final View view, final int normalFill) {
        view.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackground(background(hasFocus ? COLOR_SURFACE_FOCUS : normalFill, hasFocus));
            }
        });
    }

    private TextView text(String value, float sizeSp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sizeSp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }

    private TextView button(String label, View.OnClickListener listener) {
        TextView view = text(label, 13f, COLOR_TEXT, true);
        view.setGravity(Gravity.CENTER);
        view.setPadding(dp(12), dp(8), dp(12), dp(8));
        view.setBackground(background(COLOR_SURFACE, false));
        view.setFocusable(true);
        view.setClickable(true);
        view.setOnClickListener(listener);
        applyFocusBackground(view, COLOR_SURFACE);
        return view;
    }

    private void buildUi() {
        final FrameLayout rootFrame = new FrameLayout(this);
        rootFrame.setBackgroundColor(COLOR_BG);
        // Android 15+ draws edge-to-edge for targetSdk 35+; keep the UI out of the
        // status/navigation bars on touch devices and TV alike.
        rootFrame.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public android.view.WindowInsets onApplyWindowInsets(View v,
                                                                 android.view.WindowInsets insets) {
                v.setPadding(0, insets.getSystemWindowInsetTop(), 0,
                        insets.getSystemWindowInsetBottom());
                return insets;
            }
        });

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);
        rootFrame.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Top bar: title + actions.
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(16), dp(10), dp(16), dp(6));

        TextView logo = text("Apple Music", 18f, COLOR_TEXT, true);
        logo.setLetterSpacing(0.02f);
        topBar.addView(logo, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View spacer = new View(this);
        topBar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        topBar.addView(button("Buscar", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openSearch();
            }
        }), topBarButtonParams());
        topBar.addView(button("Conta", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAccount();
            }
        }), topBarButtonParams());
        // D-pad path to the full player (the mini bar is only reachable with touch).
        nowPlayingButton = button("Tocando", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPlayer();
            }
        });
        nowPlayingButton.setVisibility(View.GONE);
        topBar.addView(nowPlayingButton, topBarButtonParams());
        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Engine tabs (Início / Rádio / Biblioteca) + the update shortcut on the right.
        LinearLayout tabsRow = new LinearLayout(this);
        tabsRow.setOrientation(LinearLayout.HORIZONTAL);
        tabsRow.setGravity(Gravity.CENTER_VERTICAL);
        tabsRow.setPadding(dp(12), dp(2), dp(12), dp(2));

        HorizontalScrollView tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabBar = new LinearLayout(this);
        tabBar.setOrientation(LinearLayout.HORIZONTAL);
        tabScroll.addView(tabBar, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams tabScrollParams = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tabsRow.addView(tabScroll, tabScrollParams);
        LinearLayout.LayoutParams updateParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        updateParams.leftMargin = dp(8);
        tabsRow.addView(button("Atualizações", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showUpdates();
            }
        }), updateParams);
        root.addView(tabsRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Browse content.
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(7), dp(16), dp(7));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        status = text("", 12f, COLOR_TEXT_DIM, false);
        status.setPadding(dp(16), dp(4), dp(16), dp(4));
        status.setMaxLines(2);
        status.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(status, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        buildMiniPlayer(root);
        rootFrame.addView(buildPlayerOverlay(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        rootFrame.addView(buildSearchOverlay(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(rootFrame);
        main.postDelayed(ticker, TICK_MS);
    }

    private LinearLayout.LayoutParams topBarButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(6);
        return params;
    }

    private void buildMiniPlayer(LinearLayout root) {
        miniPlayer = new LinearLayout(this);
        miniPlayer.setOrientation(LinearLayout.HORIZONTAL);
        miniPlayer.setGravity(Gravity.CENTER_VERTICAL);
        miniPlayer.setPadding(dp(12), dp(8), dp(12), dp(8));
        miniPlayer.setBackgroundColor(COLOR_SURFACE);
        miniPlayer.setFocusable(true);
        miniPlayer.setClickable(true);
        miniPlayer.setId(View.generateViewId());
        miniPlayer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openPlayer();
            }
        });
        applyFocusBackground(miniPlayer, COLOR_SURFACE);

        miniCover = new ImageView(this);
        miniCover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        miniCover.setBackgroundColor(0xFF2C2C2E);
        LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(dp(52), dp(52));
        coverParams.rightMargin = dp(12);
        miniPlayer.addView(miniCover, coverParams);

        LinearLayout textBlock = new LinearLayout(this);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        miniTitle = text("", 14f, COLOR_TEXT, true);
        miniTitle.setMaxLines(1);
        miniTitle.setEllipsize(TextUtils.TruncateAt.END);
        miniArtist = text("", 12f, COLOR_TEXT_DIM, false);
        miniArtist.setMaxLines(1);
        miniArtist.setEllipsize(TextUtils.TruncateAt.END);
        textBlock.addView(miniTitle);
        textBlock.addView(miniArtist);
        miniPlayer.addView(textBlock, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        miniPlay = button("▶", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                client.togglePlayPause();
            }
        });
        miniPlay.setMinWidth(dp(48));
        miniPlay.setGravity(Gravity.CENTER);
        miniPlayer.addView(miniPlay, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(12);
        params.rightMargin = dp(12);
        params.bottomMargin = dp(10);
        miniPlayer.setVisibility(View.GONE);
        root.addView(miniPlayer, params);
    }

    private FrameLayout buildPlayerOverlay() {
        playerOverlay = new FrameLayout(this);
        playerOverlay.setBackgroundColor(0xFF0B0B0B);
        playerOverlay.setVisibility(View.GONE);
        playerOverlay.setClickable(true);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(24), dp(16), dp(24), dp(20));
        playerOverlay.addView(sheet, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text("Tocando agora", 18f, COLOR_TEXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(button("Fechar", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closePlayer();
            }
        }));
        sheet.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        playerCover = new ImageView(this);
        playerCover.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        playerCover.setBackgroundColor(0xFF1A1A1A);
        LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        coverParams.topMargin = dp(12);
        coverParams.bottomMargin = dp(12);
        sheet.addView(playerCover, coverParams);

        playerTitle = text("", 20f, COLOR_TEXT, true);
        playerTitle.setMaxLines(2);
        playerTitle.setEllipsize(TextUtils.TruncateAt.END);
        sheet.addView(playerTitle);

        playerArtist = text("", 14f, COLOR_TEXT_DIM, false);
        playerArtist.setMaxLines(1);
        playerArtist.setEllipsize(TextUtils.TruncateAt.END);
        sheet.addView(playerArtist);

        seekBar = new SeekBar(this);
        seekBar.setMax(1000);
        seekBar.setPadding(0, dp(8), 0, 0);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser && latest.durationMs > 0L) {
                    playerTime.setText(format(durationFor(progress)) + " / "
                            + format(latest.durationMs));
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                seekTracking = true;
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                seekTracking = false;
                if (latest.durationMs > 0L) {
                    client.seekTo(durationFor(bar.getProgress()));
                }
            }
        });
        sheet.addView(seekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        playerTime = text("0:00 / 0:00", 12f, COLOR_TEXT_DIM, false);
        sheet.addView(playerTime);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER);
        controls.setPadding(0, dp(10), 0, 0);
        controls.addView(button("◀◀", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                client.skipPrevious();
            }
        }), controlParams());
        playerPlay = button("▶", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                client.togglePlayPause();
            }
        });
        controls.addView(playerPlay, controlParams());
        controls.addView(button("▶▶", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                client.skipNext();
            }
        }), controlParams());
        sheet.addView(controls, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER);
        actionRow.setPadding(0, dp(8), 0, 0);
        actionRow.addView(button("Ações", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showActions();
            }
        }), controlParams());
        playerStation = button("Criar Estação", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                createStation();
            }
        });
        playerStation.setVisibility(View.GONE);
        actionRow.addView(playerStation, controlParams());
        sheet.addView(actionRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return playerOverlay;
    }

    private LinearLayout.LayoutParams controlParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(6);
        params.rightMargin = dp(6);
        return params;
    }

    private FrameLayout buildSearchOverlay() {
        searchOverlay = new FrameLayout(this);
        searchOverlay.setBackgroundColor(COLOR_BG);
        searchOverlay.setVisibility(View.GONE);
        searchOverlay.setClickable(true);

        LinearLayout sheet = new LinearLayout(this);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setPadding(dp(24), dp(16), dp(24), dp(16));
        searchOverlay.addView(sheet, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(text("Buscar", 18f, COLOR_TEXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        header.addView(button("Fechar", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeSearch();
            }
        }));
        sheet.addView(header);

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPadding(0, dp(10), 0, dp(8));
        searchInput = new EditText(this);
        searchInput.setHint("Músicas, álbuns, artistas");
        searchInput.setHintTextColor(COLOR_TEXT_DIM);
        searchInput.setTextColor(COLOR_TEXT);
        searchInput.setTextSize(16f);
        searchInput.setSingleLine(true);
        searchInput.setInputType(InputType.TYPE_CLASS_TEXT);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setBackground(background(COLOR_SURFACE, false));
        searchInput.setPadding(dp(12), dp(8), dp(12), dp(8));
        searchInput.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, android.view.KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    submitSearch();
                    return true;
                }
                return false;
            }
        });
        inputRow.addView(searchInput, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams searchButtonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchButtonParams.leftMargin = dp(8);
        inputRow.addView(button("Buscar", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                submitSearch();
            }
        }), searchButtonParams);
        sheet.addView(inputRow);

        searchStatus = text("", 12f, COLOR_TEXT_DIM, false);
        searchStatus.setPadding(0, dp(4), 0, dp(6));
        sheet.addView(searchStatus);

        ScrollView resultsScroll = new ScrollView(this);
        searchResults = new LinearLayout(this);
        searchResults.setOrientation(LinearLayout.VERTICAL);
        resultsScroll.addView(searchResults, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        sheet.addView(resultsScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView fallback = button("Abrir a busca do Apple Music", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                CatalogSearch.openEngineSearch(OneActivity.this);
            }
        });
        LinearLayout.LayoutParams fallbackParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fallbackParams.topMargin = dp(8);
        sheet.addView(fallback, fallbackParams);

        return searchOverlay;
    }

    // --- browse -------------------------------------------------------------------

    @Override
    public void onConnected() {
        Log.i(TAG, "connected");
        setMessage("Conectado ao Apple Music");
        loadAsync(MediaClient.AUTO_ROOT);
    }

    @Override
    public void onDisconnected(String message) {
        Log.w(TAG, "disconnected: " + message);
        setMessage(message);
    }

    @Override
    public void onChildrenFailed(String parentId) {
        Log.w(TAG, "children failed: " + parentId);
        setMessage("Não foi possível carregar " + parentId);
    }

    @Override
    public void onChildren(String parentId, List<MediaClient.Item> children) {
        if (MediaClient.AUTO_ROOT.equals(parentId)) {
            onRoot(children);
            return;
        }
        if (!levels.isEmpty() && levels.get(levels.size() - 1).id.equals(parentId)) {
            fillLevel(children);
            return;
        }
        if (selectedTab >= 0 && selectedTab < tabs.size()
                && tabs.get(selectedTab).id.equals(parentId)) {
            buildTabRoot(children);
            return;
        }
        LinearLayout cards = sections.get(parentId);
        if (cards != null) {
            populateShelf(cards, children);
            return;
        }
        Log.w(TAG, "unhandled children for " + parentId);
    }

    private void onRoot(List<MediaClient.Item> children) {
        if (rootReceived) return;
        List<MediaClient.Item> found = new ArrayList<MediaClient.Item>();
        for (MediaClient.Item item : children) {
            if (item.browsable) found.add(item);
        }
        if (found.isEmpty()) {
            setMessage("Apple Music não expôs o catálogo de mídia");
            return;
        }
        rootReceived = true;
        tabs.clear();
        tabs.addAll(found);
        buildTabs();
        selectTab(0);
        // TV has no touch: start the D-pad on the selected tab, not on the top bar.
        if (!hasTouch) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    focusSelectedTab();
                }
            });
        }
    }

    private void focusSelectedTab() {
        if (selectedTab >= 0 && selectedTab < tabBar.getChildCount()) {
            tabBar.getChildAt(selectedTab).requestFocus();
        }
    }

    private static View firstFocusable(View root) {
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = firstFocusable(group.getChildAt(i));
                if (found != null) return found;
            }
            return null;
        }
        if (root != null && root.isFocusable() && root.getVisibility() == View.VISIBLE) {
            return root;
        }
        return null;
    }

    private void buildTabs() {
        tabBar.removeAllViews();
        for (int i = 0; i < tabs.size(); i++) {
            final int index = i;
            TextView tab = button(tabs.get(i).title, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    selectTab(index);
                }
            });
            tab.setTag(Integer.valueOf(index));
            // D-pad: DOWN from the tab row enters the browse content directly.
            tab.setOnKeyListener(new View.OnKeyListener() {
                @Override
                public boolean onKey(View v, int keyCode, KeyEvent event) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN
                            && keyCode == KeyEvent.KEYCODE_DPAD_DOWN) {
                        View target = firstFocusable(content);
                        if (target != null) {
                            target.requestFocus();
                            return true;
                        }
                    }
                    return false;
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.rightMargin = dp(8);
            tabBar.addView(tab, params);
        }
    }

    private void selectTab(int index) {
        if (index < 0 || index >= tabs.size()) return;
        if (index == selectedTab && levels.isEmpty() && !tabs.isEmpty()) {
            // Re-selecting the current tab is a no-op unless we are in a level.
        }
        selectedTab = index;
        updateTabStyles();
        showTabRoot();
    }

    private void updateTabStyles() {
        for (int i = 0; i < tabBar.getChildCount(); i++) {
            View child = tabBar.getChildAt(i);
            boolean selected = i == selectedTab;
            child.setBackground(background(selected ? COLOR_ACCENT : COLOR_SURFACE, false));
            if (child instanceof TextView) {
                ((TextView) child).setTextColor(COLOR_TEXT);
            }
        }
    }

    private void showTabRoot() {
        levels.clear();
        levelContainer = null;
        sections.clear();
        content.removeAllViews();
        if (selectedTab < 0 || selectedTab >= tabs.size()) return;
        MediaClient.Item tab = tabs.get(selectedTab);
        setMessage("Carregando " + tab.title + "…");
        loadAsync(tab.id);
    }

    private void buildTabRoot(List<MediaClient.Item> children) {
        content.removeAllViews();
        sections.clear();
        if (selectedTab < 0 || selectedTab >= tabs.size()) return;
        MediaClient.Item tab = tabs.get(selectedTab);

        if (isLibrary(tab)) {
            content.addView(sectionHeader("Biblioteca"));
            for (MediaClient.Item item : children) {
                content.addView(row(item));
            }
            if (children.isEmpty()) content.addView(empty("Biblioteca vazia"));
            setMessage("");
            return;
        }

        List<MediaClient.Item> shelves = new ArrayList<MediaClient.Item>();
        List<MediaClient.Item> playables = new ArrayList<MediaClient.Item>();
        for (MediaClient.Item item : children) {
            if (item.browsable) shelves.add(item);
            else if (item.playable) playables.add(item);
        }
        if (shelves.isEmpty() && playables.isEmpty()) {
            content.addView(empty("Nada para mostrar"));
            setMessage("");
            return;
        }
        for (MediaClient.Item shelf : shelves) {
            LinearLayout cards = addShelf(shelf.title, shelf.id);
            loadAsync(shelf.id);
        }
        if (!playables.isEmpty()) {
            LinearLayout cards = addShelf("Tocar agora", null);
            populateShelf(cards, playables);
        }
        setMessage("");
    }

    private boolean isLibrary(MediaClient.Item tab) {
        return tab.id.endsWith("/library") || "Biblioteca".equalsIgnoreCase(tab.title);
    }

    private TextView sectionHeader(String title) {
        TextView header = text(title, 18f, COLOR_TEXT, true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(12);
        params.bottomMargin = dp(8);
        header.setLayoutParams(params);
        return header;
    }

    private View empty(String message) {
        TextView view = text(message, 14f, COLOR_TEXT_DIM, false);
        view.setPadding(0, dp(16), 0, dp(16));
        return view;
    }

    private LinearLayout addShelf(String title, String id) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.addView(sectionHeader(title));

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(cards, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        block.addView(scroll);

        content.addView(block);
        if (id != null) sections.put(id, cards);
        return cards;
    }

    private void populateShelf(LinearLayout cards, List<MediaClient.Item> items) {
        cards.removeAllViews();
        for (MediaClient.Item item : items) {
            cards.addView(card(item));
        }
        for (int i = 0; i < cards.getChildCount(); i++) {
            View child = cards.getChildAt(i);
            if (i > 0) child.setNextFocusLeftId(cards.getChildAt(i - 1).getId());
            if (i < cards.getChildCount() - 1) {
                child.setNextFocusRightId(cards.getChildAt(i + 1).getId());
            }
            child.setNextFocusDownId(miniPlayer.getId());
        }
    }

    private View card(final MediaClient.Item item) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setFocusable(true);
        card.setClickable(true);
        card.setPadding(dp(6), dp(6), dp(6), dp(10));
        card.setId(View.generateViewId());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(156), ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(12);
        card.setLayoutParams(params);

        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(0xFF2C2C2E);
        card.addView(cover, new LinearLayout.LayoutParams(dp(144), dp(144)));
        artwork.load(item.artworkUri, cover);

        TextView title = text(item.title, 13f, COLOR_TEXT, true);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setPadding(0, dp(6), 0, 0);
        card.addView(title);

        TextView subtitle = text(item.subtitle, 12f, COLOR_TEXT_DIM, false);
        subtitle.setMaxLines(1);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(subtitle);

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onItemClick(item);
            }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showItemActions(item);
                return true;
            }
        });
        applyFocusBackground(card, COLOR_SURFACE);
        return card;
    }

    private View row(final MediaClient.Item item) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));
        row.setId(View.generateViewId());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(6);
        row.setLayoutParams(params);

        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(0xFF2C2C2E);
        LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(dp(56), dp(56));
        coverParams.rightMargin = dp(12);
        row.addView(cover, coverParams);
        artwork.load(item.artworkUri, cover);

        LinearLayout textBlock = new LinearLayout(this);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(item.title, 15f, COLOR_TEXT, true);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        TextView subtitle = text(item.subtitle, 12f, COLOR_TEXT_DIM, false);
        subtitle.setMaxLines(1);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        textBlock.addView(title);
        textBlock.addView(subtitle);
        row.addView(textBlock, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onItemClick(item);
            }
        });
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                showItemActions(item);
                return true;
            }
        });
        applyFocusBackground(row, COLOR_SURFACE);
        return row;
    }

    private void onItemClick(MediaClient.Item item) {
        if (item.playable) {
            client.play(item.id);
            setMessage("Tocando " + item.title);
        } else if (item.browsable) {
            pushLevel(item);
        } else {
            setMessage("Item não reproduzível");
        }
    }

    // --- one drill-down level -------------------------------------------------------

    private void pushLevel(MediaClient.Item item) {
        levels.add(new Level(item.id, item.title));
        renderLevel();
        loadAsync(item.id);
    }

    private void renderLevel() {
        content.removeAllViews();
        sections.clear();
        if (levels.isEmpty()) return;
        Level level = levels.get(levels.size() - 1);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, dp(8), 0, dp(8));
        TextView back = button("‹ Voltar", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                backOneLevel();
            }
        });
        LinearLayout.LayoutParams backParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        backParams.rightMargin = dp(12);
        header.addView(back, backParams);
        header.addView(text(level.title, 18f, COLOR_TEXT, true));
        content.addView(header);

        levelContainer = new LinearLayout(this);
        levelContainer.setOrientation(LinearLayout.VERTICAL);
        content.addView(levelContainer);
        setMessage("Carregando " + level.title + "…");
    }

    private void fillLevel(List<MediaClient.Item> children) {
        if (levelContainer == null) return;
        levelContainer.removeAllViews();
        if (children.isEmpty()) {
            levelContainer.addView(empty("Nada aqui"));
        } else {
            for (MediaClient.Item item : children) levelContainer.addView(row(item));
        }
        setMessage("");
    }

    private void backOneLevel() {
        if (levels.isEmpty()) return;
        levels.remove(levels.size() - 1);
        if (levels.isEmpty()) {
            showTabRoot();
        } else {
            renderLevel();
            loadAsync(levels.get(levels.size() - 1).id);
        }
    }

    private void loadAsync(final String parentId) {
        loader.execute(new Runnable() {
            @Override
            public void run() {
                client.subscribe(parentId);
            }
        });
    }

    // --- playback ------------------------------------------------------------------

    @Override
    public void onPlayback(MediaClient.Snapshot snapshot) {
        latest = snapshot;
        applyPlayback();
    }

    private void applyPlayback() {
        MediaClient.Snapshot snapshot = latest;
        boolean visible = snapshot.hasSession
                && (snapshot.title.length() > 0 || snapshot.playing);
        miniPlayer.setVisibility(visible ? View.VISIBLE : View.GONE);
        nowPlayingButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        if (visible) {
            miniTitle.setText(snapshot.title);
            miniArtist.setText(join(snapshot.artist, snapshot.album));
            miniPlay.setText(snapshot.playing ? "▌▌" : "▶");
            if (snapshot.artwork != null
                    && snapshot.artwork.hashCode() != lastArtworkHash) {
                lastArtworkHash = snapshot.artwork.hashCode();
                miniCover.setImageBitmap(snapshot.artwork);
            }
        }
        if (playerOverlay.getVisibility() != View.VISIBLE) return;
        playerTitle.setText(snapshot.title);
        playerArtist.setText(join(snapshot.artist, snapshot.album));
        if (snapshot.artwork != null) playerCover.setImageBitmap(snapshot.artwork);
        playerPlay.setText(snapshot.playing ? "▌▌" : "▶");
        playerStation.setVisibility(
                snapshot.findActionEndingWith(ACTION_CREATE_STATION) != null
                        ? View.VISIBLE : View.GONE);
        if (!seekTracking) {
            int progress = snapshot.durationMs > 0L
                    ? (int) (snapshot.positionMs * 1000L / snapshot.durationMs) : 0;
            seekBar.setProgress(Math.max(0, Math.min(1000, progress)));
        }
        playerTime.setText(format(snapshot.positionMs) + " / " + format(snapshot.durationMs));
    }

    private long durationFor(int progress) {
        return latest.durationMs > 0L ? latest.durationMs * progress / 1000L : 0L;
    }

    private static String join(String first, String second) {
        if (first.length() == 0) return second;
        if (second.length() == 0 || second.equals(first)) return first;
        return first + " · " + second;
    }

    private static String format(long millis) {
        if (millis <= 0L) return "0:00";
        long seconds = millis / 1000L;
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L);
    }

    private void openPlayer() {
        if (!latest.hasSession) {
            setMessage("Nada tocando");
            return;
        }
        playerOverlay.setVisibility(View.VISIBLE);
        applyPlayback();
        playerPlay.requestFocus();
    }

    private void closePlayer() {
        playerOverlay.setVisibility(View.GONE);
    }

    private void showActions() {
        final List<MediaClient.Action> actions = latest.actions;
        if (actions.isEmpty()) {
            setMessage("Nenhuma ação disponível nesta sessão");
            return;
        }
        String[] titles = new String[actions.size()];
        for (int i = 0; i < actions.size(); i++) {
            MediaClient.Action action = actions.get(i);
            titles[i] = action.title.length() > 0 ? action.title : action.action;
        }
        new AlertDialog.Builder(this)
                .setTitle("Ações da sessão")
                .setItems(titles, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        MediaClient.Action action = actions.get(which);
                        client.sendCustomAction(action.action);
                        setMessage(feedback(action.action));
                    }
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    private void createStation() {
        MediaClient.Action station = latest.findActionEndingWith(ACTION_CREATE_STATION);
        if (station == null) {
            setMessage("Criar estação indisponível nesta faixa");
            return;
        }
        client.sendCustomAction(station.action);
        setMessage("Criando estação…");
    }

    /** Long-press on a card/row: the session actions applied to that item. */
    private void showItemActions(final MediaClient.Item item) {
        final List<MediaClient.Action> actions = latest.actions;
        if (actions.isEmpty()) {
            setMessage("Nenhuma ação disponível nesta sessão");
            return;
        }
        String[] titles = new String[actions.size()];
        for (int i = 0; i < actions.size(); i++) {
            MediaClient.Action action = actions.get(i);
            titles[i] = action.title.length() > 0 ? action.title : action.action;
        }
        new AlertDialog.Builder(this)
                .setTitle(item.title)
                .setItems(titles, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        runItemAction(item, actions.get(which));
                    }
                })
                .setNegativeButton("Fechar", null)
                .show();
    }

    private void runItemAction(final MediaClient.Item item, final MediaClient.Action action) {
        if (item.playable) {
            setMessage(action.action.endsWith(ACTION_CREATE_STATION)
                    ? "Criando estação…" : action.title + "…");
            client.play(item.id);
            pollAction(action.action, SystemClock.elapsedRealtime() + ACTION_TIMEOUT_MS);
            return;
        }
        if (latest.hasAction(action.action)) {
            client.sendCustomAction(action.action);
            setMessage(feedback(action.action));
        } else {
            setMessage("Indisponível para este item");
        }
    }

    /** The engine only publishes actions for the current track; wait for PLAYING. */
    private void pollAction(final String actionId, final long deadline) {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                MediaClient.Snapshot snapshot = latest;
                if (snapshot.playing && snapshot.hasAction(actionId)) {
                    client.sendCustomAction(actionId);
                    setMessage(feedback(actionId));
                    return;
                }
                if (SystemClock.elapsedRealtime() < deadline) {
                    main.postDelayed(this, 150L);
                } else {
                    setMessage("Indisponível para este item");
                }
            }
        }, 150L);
    }

    private static String feedback(String actionId) {
        if (actionId.endsWith(ACTION_CREATE_STATION)) return "Criando estação…";
        if (actionId.endsWith(ACTION_FAVORITE)) return "Favorito aplicado";
        if (actionId.endsWith(ACTION_ADD_TO_LIBRARY)) return "Adicionado à Biblioteca";
        return "Ação enviada";
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (latest.playing) client.refreshPlayback();
            main.postDelayed(this, TICK_MS);
        }
    };

    // --- search ---------------------------------------------------------------------

    private void openSearch() {
        searchOverlay.setVisibility(View.VISIBLE);
        searchStatus.setText("");
        searchInput.requestFocus();
    }

    private void closeSearch() {
        searchOverlay.setVisibility(View.GONE);
    }

    private void submitSearch() {
        final String term = searchInput.getText().toString().trim();
        if (term.length() == 0) return;
        searchStatus.setText("Buscando \"" + term + "\"…");
        searchResults.removeAllViews();
        catalog.search(term, new CatalogSearch.Callback() {
            @Override
            public void onResults(List<CatalogSearch.Result> results) {
                searchResults.removeAllViews();
                if (results.isEmpty()) {
                    searchStatus.setText("Nenhum resultado");
                    return;
                }
                searchStatus.setText(results.size() + " resultado(s)");
                for (CatalogSearch.Result result : results) {
                    searchResults.addView(searchRow(result));
                }
            }

            @Override
            public void onFailure(String message, boolean tokenMissing) {
                searchStatus.setText(tokenMissing
                        ? message + " — use a busca do Apple Music abaixo"
                        : message);
            }
        });
    }

    private View searchRow(final CatalogSearch.Result result) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setClickable(true);
        row.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = dp(6);
        row.setLayoutParams(params);

        ImageView cover = new ImageView(this);
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(0xFF2C2C2E);
        LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(dp(56), dp(56));
        coverParams.rightMargin = dp(12);
        row.addView(cover, coverParams);
        artwork.load(result.artworkUrl, cover);

        LinearLayout textBlock = new LinearLayout(this);
        textBlock.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(result.title, 15f, COLOR_TEXT, true);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        TextView subtitle = text(result.kind + " · " + result.artist, 12f, COLOR_TEXT_DIM, false);
        subtitle.setMaxLines(1);
        subtitle.setEllipsize(TextUtils.TruncateAt.END);
        textBlock.addView(title);
        textBlock.addView(subtitle);
        row.addView(textBlock, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                client.play(result.id);
                searchStatus.setText("Tocando " + result.title);
            }
        });
        applyFocusBackground(row, COLOR_SURFACE);
        return row;
    }

    // --- account / version ------------------------------------------------------------

    /**
     * Starts the engine's Apple ID sign-in from our UI.
     *
     * <p>The engine has no auth activity: the sign-in is the Compose
     * {@code AuthenticationDialogAms}, rendered over the engine's {@code MainActivity} (a
     * {@code StoreBaseActivity}) by its {@code DialogConnectorViewModel}. We start the
     * engine activity, then by reflection: {@code S()} installs the full-screen
     * StoreApiDialogs ComposeView (so its home is covered) and
     * {@code showAMSAuthWithType(Interactive)} opens the AMS dialog, which performs the
     * sign-in itself. BACK returns to this activity.
     */
    private void openAccount() {
        final Application app = getApplication();
        final ActivityWatcher watcher = new ActivityWatcher(MediaClient.ACCOUNT_ACTIVITY) {
            @Override
            void onTarget(Activity activity) {
                app.unregisterActivityLifecycleCallbacks(this);
                showAppleIdDialog(activity);
            }
        };
        try {
            Intent intent = new Intent();
            intent.setClassName(MediaClient.PACKAGE, MediaClient.ACCOUNT_ACTIVITY);
            app.registerActivityLifecycleCallbacks(watcher);
            startActivity(intent);
        } catch (Throwable t) {
            app.unregisterActivityLifecycleCallbacks(watcher);
            Log.e(TAG, "sign-in host unavailable, falling back to the engine UI", t);
            openEngine();
        }
    }

    private void showAppleIdDialog(final Activity host) {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Class<?> authType = Class.forName(AUTH_TYPE_CLASS);
                    Object interactive = null;
                    for (Object value : authType.getEnumConstants()) {
                        if ("Interactive".equals(((Enum<?>) value).name())) interactive = value;
                    }
                    if (interactive == null) throw new IllegalStateException("no Interactive type");
                    // S(): adds the StoreApiDialogs ComposeView over the engine's screen.
                    host.getClass().getMethod("S").invoke(host);
                    // U(): the activity-scoped DialogConnectorViewModel; show the AMS dialog,
                    // which starts the sign-in itself (AuthenticationDialogAms -> startSignIn).
                    Object vm = host.getClass().getMethod("U").invoke(host);
                    vm.getClass().getMethod("showAMSAuthWithType", authType).invoke(vm, interactive);
                } catch (Throwable t) {
                    Log.e(TAG, "cannot show the Apple ID dialog, falling back to the engine UI", t);
                    openEngine();
                }
            }
        }, SIGN_IN_SETTLE_MS);
    }

    private void openEngine() {
        try {
            Intent intent = new Intent();
            intent.setClassName(MediaClient.PACKAGE, MediaClient.ACCOUNT_ACTIVITY);
            startActivity(intent);
        } catch (Throwable t) {
            Log.e(TAG, "cannot open the engine account screen", t);
            setMessage("Não foi possível abrir o Apple Music");
        }
    }

    /** Detects the engine activity we just started so it can be driven by reflection. */
    private abstract static class ActivityWatcher implements Application.ActivityLifecycleCallbacks {
        private final String target;

        ActivityWatcher(String target) {
            this.target = target;
        }

        abstract void onTarget(Activity activity);

        @Override
        public void onActivityCreated(Activity activity, Bundle state) {
            if (target.equals(activity.getClass().getName())) onTarget(activity);
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityResumed(Activity activity) {
        }

        @Override
        public void onActivityPaused(Activity activity) {
        }

        @Override
        public void onActivityStopped(Activity activity) {
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle state) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
        }
    }

    private void showUpdates() {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(20), dp(8), dp(20), 0);

        updateMessage = text("", 14f, COLOR_TEXT, false);
        updateMessage.setLineSpacing(dp(2), 1f);
        panel.addView(updateMessage, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        updateProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        updateProgress.setMax(100);
        updateProgress.setVisibility(View.GONE);
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        progressParams.topMargin = dp(12);
        panel.addView(updateProgress, progressParams);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.END);
        updateCheckButton = button("Verificar", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updater.check();
            }
        });
        updateDownloadButton = button("Baixar e instalar", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updater.download(pendingUpdate);
            }
        });
        updateCancelButton = button("Cancelar", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                updater.cancel();
            }
        });
        actions.addView(updateCheckButton);
        actions.addView(updateDownloadButton, topBarButtonParams());
        actions.addView(updateCancelButton, topBarButtonParams());
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionParams.topMargin = dp(16);
        panel.addView(actions, actionParams);

        updateDialog = new AlertDialog.Builder(this)
                .setTitle("Atualizações")
                .setView(panel)
                .setPositiveButton("Fechar", null)
                .create();
        updateDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                updateDialog = null;
                updateMessage = null;
                updateProgress = null;
                updateCheckButton = null;
                updateDownloadButton = null;
                updateCancelButton = null;
            }
        });
        updateDialog.show();
        pendingUpdate = updater.currentState().update;
        renderUpdateState(updater.currentState());
        updater.check();
    }

    @Override
    public void onUpdateState(UpdateManager.State state) {
        if (state.update != null) pendingUpdate = state.update;
        if (updateDialog != null && updateDialog.isShowing()) {
            renderUpdateState(state);
            return;
        }
        if (state.phase == UpdateManager.AVAILABLE && state.update != null) {
            setMessage("Atualização " + state.update.version + " disponível — toque em Atualizações");
        } else if (state.phase == UpdateManager.INSTALLED || state.phase == UpdateManager.ERROR) {
            setMessage(state.message);
        }
    }

    private void renderUpdateState(UpdateManager.State state) {
        if (updateMessage == null) return;
        String message = state.message;
        if (state.phase == UpdateManager.IDLE || state.message == null || state.message.length() == 0) {
            message = "Instalada: " + Version.NAME + " (" + Version.CODE + ")"
                    + "\nEngine: " + engineVersion()
                    + "\n\nVerifique se há uma versão nova.";
        } else if (state.phase == UpdateManager.CHECKING) {
            message = message + "\nInstalada: " + Version.NAME + " (" + Version.CODE + ")";
        }
        updateMessage.setText(message);

        boolean downloading = state.phase == UpdateManager.DOWNLOADING;
        updateProgress.setVisibility(downloading ? View.VISIBLE : View.GONE);
        if (downloading && state.progress >= 0) updateProgress.setProgress(state.progress);
        setVisible(updateCheckButton, state.phase == UpdateManager.IDLE
                || state.phase == UpdateManager.NONE
                || state.phase == UpdateManager.INSTALLED
                || state.phase == UpdateManager.ERROR
                || state.phase == UpdateManager.AVAILABLE);
        setVisible(updateDownloadButton,
                state.phase == UpdateManager.AVAILABLE && state.update != null);
        setVisible(updateCancelButton, downloading);
    }

    private static void setVisible(View view, boolean visible) {
        if (view != null) view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private String engineVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName + " (" + info.versionCode + ")";
        } catch (Throwable t) {
            return "?";
        }
    }

    /**
     * PackageInstaller sends the session status to the PendingIntent built in
     * {@link UpdateManager}. For {@code STATUS_PENDING_USER_ACTION} the system intent is
     * forwarded from this foreground activity (a background receiver would be blocked on
     * Android 10+).
     */
    private void handleInstallStatus(Intent intent) {
        if (intent == null) return;
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
        if (status == Integer.MIN_VALUE) return;
        // Mark handled so a recreation of the activity does not replay the dialog.
        intent.putExtra(PackageInstaller.EXTRA_STATUS, Integer.MIN_VALUE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            intent.removeExtra(Intent.EXTRA_INTENT);
            if (confirm == null) {
                updater.onInstallResult(false, "O instalador não retornou a confirmação.");
                return;
            }
            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                startActivity(confirm);
            } catch (Throwable t) {
                Log.e(TAG, "cannot open the package installer", t);
                updater.onInstallResult(false, "Não foi possível abrir o instalador do sistema.");
            }
            return;
        }
        String detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
        boolean ok = status == PackageInstaller.STATUS_SUCCESS;
        updater.onInstallResult(ok, ok
                ? "Atualização instalada."
                : "Instalação não concluída"
                + (detail != null && detail.length() > 0 ? ": " + detail : "."));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleInstallStatus(intent);
    }

    // --- misc -------------------------------------------------------------------------

    private void setMessage(String message) {
        status.setText(message == null ? "" : message);
        Log.i(TAG, "status: " + message);
    }

    @Override
    public void onBackPressed() {
        if (playerOverlay.getVisibility() == View.VISIBLE) {
            closePlayer();
            return;
        }
        if (searchOverlay.getVisibility() == View.VISIBLE) {
            closeSearch();
            return;
        }
        if (!levels.isEmpty()) {
            backOneLevel();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        main.removeCallbacks(ticker);
        if (client != null) client.disconnect();
        if (artwork != null) artwork.shutdown();
        if (catalog != null) catalog.shutdown();
        if (updater != null) updater.shutdown();
        loader.shutdownNow();
        super.onDestroy();
    }
}
