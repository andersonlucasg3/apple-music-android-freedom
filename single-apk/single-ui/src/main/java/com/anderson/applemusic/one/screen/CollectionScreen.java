package com.anderson.applemusic.one.screen;

import android.content.Context;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.anderson.applemusic.one.media.ArtworkLoader;
import com.anderson.applemusic.one.media.MediaClient;
import com.anderson.applemusic.one.theme.Inter;
import com.anderson.applemusic.one.theme.Tokens;
import com.anderson.applemusic.one.widget.CircleButton;
import com.anderson.applemusic.one.widget.CoverCard;
import com.anderson.applemusic.one.widget.EmptyState;
import com.anderson.applemusic.one.widget.Icons;
import com.anderson.applemusic.one.widget.ListRow;
import com.anderson.applemusic.one.widget.Pill;
import com.anderson.applemusic.one.widget.Skeleton;
import com.anderson.applemusic.one.widget.TrackRow;

import java.util.ArrayList;
import java.util.List;

/**
 * Category browser used by Biblioteca (list), Rádio (grid) and the "Ver tudo"/
 * browsable-item overlay. Keeps its own stack, so every level has a breadcrumb and a
 * back action; it is deliberately not the Home screen with a different title.
 */
public class CollectionScreen implements ChildSink {

    public static final int LIST = 0;
    public static final int GRID = 1;

    protected final Context context;
    protected final ScreenHost host;
    protected final ArtworkLoader artwork;

    private final int mode;
    private final int emptyIcon;
    private final FrameLayout root;
    private final CircleButton back;
    private final TextView titleView;
    private final TextView subtitleView;
    private final LinearLayout column;
    private final Pill playAll;
    private final List<String> ids = new ArrayList<String>();
    private final List<String> titles = new ArrayList<String>();
    /** Track rows of the current node, when the node is a track list, with their media ids. */
    private final List<TrackRow> trackRows = new ArrayList<TrackRow>();
    private final List<String> trackIds = new ArrayList<String>();
    private final List<String> trackTitles = new ArrayList<String>();
    private Skeleton skeleton;
    private String failedId;
    private boolean exitOnRootBack;
    private boolean trackList;

    public CollectionScreen(Context context, ScreenHost host, ArtworkLoader artwork, int mode,
                            int emptyIcon) {
        this.context = context;
        this.host = host;
        this.artwork = artwork;
        this.mode = mode;
        this.emptyIcon = emptyIcon;

        root = new FrameLayout(context);
        root.setBackgroundColor(Tokens.BG);

        LinearLayout page = new LinearLayout(context);
        page.setOrientation(LinearLayout.VERTICAL);
        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Tokens.dp(16), Tokens.dp(10), Tokens.dp(16), Tokens.dp(6));

        back = new CircleButton(context, Icons.CHEVRON_LEFT, 44f, Tokens.SURFACE,
                Tokens.TEXT, 22f);
        LinearLayout.LayoutParams backParams =
                new LinearLayout.LayoutParams(Tokens.dp(44), Tokens.dp(44));
        backParams.rightMargin = Tokens.dp(14);
        header.addView(back, backParams);
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                back();
            }
        });

        LinearLayout heading = new LinearLayout(context);
        heading.setOrientation(LinearLayout.VERTICAL);
        titleView = Inter.text(context, "", Tokens.SECTION, Inter.BOLD, Tokens.TEXT);
        titleView.setMaxLines(1);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        subtitleView = Inter.text(context, "", Tokens.CAPTION, Inter.REGULAR, Tokens.TEXT_DIM);
        subtitleView.setMaxLines(1);
        heading.addView(titleView);
        heading.addView(subtitleView);
        header.addView(heading, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // "Tocar": plays the whole collection from the first track. Only meaningful when the
        // node lists tracks (albums/playlists), so it stays hidden for category lists.
        playAll = Pill.accent(context, Icons.PLAY, "Tocar");
        playAll.setVisibility(View.GONE);
        LinearLayout.LayoutParams playAllParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        playAllParams.leftMargin = Tokens.dp(12);
        playAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (ids.isEmpty()) return;
                host.playId(ids.get(ids.size() - 1), titles.get(titles.size() - 1));
            }
        });
        header.addView(playAll, playAllParams);

        page.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout contentHost = new FrameLayout(context);
        ScrollView scroll = new ScrollView(context);
        scroll.setFillViewport(true);
        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(Tokens.dp(16), Tokens.dp(4), Tokens.dp(16), Tokens.dp(24));
        scroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        contentHost.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        page.addView(contentHost, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
    }

    public View view() {
        return root;
    }

    /** Overlay browser: the back affordance also closes the screen at its root. */
    public void setExitOnRootBack(boolean exit) {
        exitOnRootBack = exit;
        renderHeader();
    }

    /** Starts at a browse node (the tab root). */
    public void bind(String parentId, String title) {
        ids.clear();
        titles.clear();
        open(parentId, title);
    }

    private void open(String parentId, String title) {
        ids.add(parentId);
        titles.add(title);
        renderHeader();
        load(parentId);
    }

    private void load(String parentId) {
        failedId = null;
        showLoading();
        host.loadChildren(parentId, this);
    }

    private void renderHeader() {
        if (ids.isEmpty()) return;
        titleView.setText(titles.get(titles.size() - 1));
        back.setVisibility(ids.size() > 1 || exitOnRootBack ? View.VISIBLE : View.GONE);
        subtitleView.setText(ids.size() > 1 ? breadcrumb() : "Apple Music");
    }

    private String breadcrumb() {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < titles.size(); i++) {
            if (i > 0) builder.append("  ›  ");
            builder.append(titles.get(i));
        }
        return builder.toString();
    }

    private void showLoading() {
        stopSkeleton();
        column.removeAllViews();
        clearTrackRows();
        trackList = false;
        playAll.setVisibility(View.GONE);
        skeleton = Skeleton.rows(context, 6);
        column.addView(skeleton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private void clearTrackRows() {
        trackRows.clear();
        trackIds.clear();
        trackTitles.clear();
        trackList = false;
    }

    private void stopSkeleton() {
        if (skeleton != null) {
            skeleton.stop();
            column.removeView(skeleton);
            skeleton = null;
        }
    }

    @Override
    public void onChildren(String parentId, List<MediaClient.Item> children) {
        if (ids.isEmpty() || !parentId.equals(ids.get(ids.size() - 1))) return;
        stopSkeleton();
        column.removeAllViews();
        clearTrackRows();
        if (children.isEmpty()) {
            column.addView(empty("Nada aqui",
                    "O Apple Music não retornou itens para " + titles.get(titles.size() - 1) + "."));
            subtitleView.setText("0 itens");
            host.status("");
            return;
        }
        trackList = isTrackList(parentId, children);
        playAll.setVisibility(trackList ? View.VISIBLE : View.GONE);
        subtitleView.setText(children.size() + (trackList ? " faixas" : " itens"));
        host.status("");
        if (mode == GRID) {
            addGrid(children);
        } else {
            addList(children);
        }
        updatePlayback(host.nowPlaying());
    }

    /**
     * True when the node lists tracks rather than more collections. The engine's Auto tree
     * marks albums/playlists as playable leaves too, so "playable and not browsable" is not
     * enough: the node must also be the songs node or hand out bare track ids (catalogue
     * style) instead of child browse ids.
     */
    private static boolean isTrackList(String parentId, List<MediaClient.Item> children) {
        for (MediaClient.Item child : children) {
            if (child.browsable || !child.playable) return false;
        }
        if (children.isEmpty()) return false;
        if (parentId != null && parentId.contains("/songs")) return true;
        for (MediaClient.Item child : children) {
            for (int i = 0; i < child.id.length(); i++) {
                if (!Character.isDigit(child.id.charAt(i))) return false;
            }
        }
        return true;
    }

    /**
     * Repaints the "tocando agora" line: the accent glyph sits on the row whose media id
     * (or, when the queue has no media id, whose title) matches the session metadata.
     */
    public void updatePlayback(MediaClient.Snapshot snapshot) {
        if (snapshot == null || trackRows.isEmpty()) return;
        for (int i = 0; i < trackRows.size(); i++) {
            String mediaId = trackIds.get(i);
            boolean playing = mediaId.length() > 0
                    ? mediaId.equals(snapshot.mediaId)
                    : trackTitles.get(i).equalsIgnoreCase(snapshot.title);
            trackRows.get(i).setPlaying(playing);
        }
    }

    @Override
    public void onFailed(String parentId) {
        if (ids.isEmpty() || !parentId.equals(ids.get(ids.size() - 1))) return;
        stopSkeleton();
        column.removeAllViews();
        failedId = parentId;
        EmptyState failure = new EmptyState(context, Icons.CLOSE, "Não foi possível carregar",
                "Sem conexão com o Apple Music (" + titles.get(titles.size() - 1) + ").");
        Pill retry = Pill.surface(context, Icons.REFRESH, "Tentar de novo");
        retry.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (failedId != null) load(failedId);
            }
        });
        failure.addAction(retry);
        column.addView(failure, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        host.status("Não foi possível carregar " + titles.get(titles.size() - 1));
    }

    private View empty(String title, String message) {
        return new EmptyState(context, emptyIcon, title, message);
    }

    private void addList(List<MediaClient.Item> items) {
        final String collectionId = ids.get(ids.size() - 1);
        final String collectionTitle = titles.get(titles.size() - 1);
        for (int i = 0; i < items.size(); i++) {
            final MediaClient.Item item = items.get(i);
            final int index = i;
            View row;
            if (trackList) {
                TrackRow track = new TrackRow(context, i + 1, item.title, item.subtitle);
                track.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        host.playCollectionFrom(collectionId, collectionTitle, index, item);
                    }
                });
                track.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        host.itemActions(item);
                        return true;
                    }
                });
                trackRows.add(track);
                trackIds.add(item.id);
                trackTitles.add(item.title);
                row = track;
            } else {
                ListRow listRow = new ListRow(context, artwork, item.title, item.subtitle,
                        item.artworkUri, item.browsable);
                listRow.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        choose(item);
                    }
                });
                listRow.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(View v) {
                        host.itemActions(item);
                        return true;
                    }
                });
                row = listRow;
            }
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = Tokens.dp(trackList ? 2 : 8);
            column.addView(row, params);
        }
    }

    private void addGrid(List<MediaClient.Item> items) {
        int columns = Tokens.wide() ? 5 : 2;
        int gap = Tokens.dp(12);
        int available = Tokens.screenWidth() - Tokens.dp(32);
        int size = Math.max(Tokens.dp(96), (available - gap * (columns - 1)) / columns);
        LinearLayout row = null;
        for (int i = 0; i < items.size(); i++) {
            if (i % columns == 0) {
                row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
                rowParams.bottomMargin = gap;
                column.addView(row, rowParams);
            }
            final MediaClient.Item item = items.get(i);
            CoverCard card = new CoverCard(context, artwork, item, size);
            card.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    choose(item);
                }
            });
            card.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    host.itemActions(item);
                    return true;
                }
            });
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(size, size);
            cardParams.rightMargin = gap;
            row.addView(card, cardParams);
        }
    }

    private void choose(MediaClient.Item item) {
        if (item.browsable) {
            open(item.id, item.title);
        } else if (item.playable) {
            host.play(item);
        } else {
            host.status("Item não reproduzível");
        }
    }

    /** Breadcrumb back: false when the screen is already at its tab root. */
    public boolean back() {
        if (ids.size() <= 1) {
            if (exitOnRootBack) {
                host.closeCollection();
                return true;
            }
            return false;
        }
        ids.remove(ids.size() - 1);
        titles.remove(titles.size() - 1);
        renderHeader();
        load(ids.get(ids.size() - 1));
        return true;
    }

    public void dispose() {
        host.forgetChildren(this);
        if (skeleton != null) {
            skeleton.stop();
            skeleton = null;
        }
    }
}
