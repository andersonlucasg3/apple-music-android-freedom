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
    private final List<String> ids = new ArrayList<String>();
    private final List<String> titles = new ArrayList<String>();
    private Skeleton skeleton;
    private String failedId;
    private boolean exitOnRootBack;

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
        skeleton = Skeleton.rows(context, 6);
        column.addView(skeleton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
        if (children.isEmpty()) {
            column.addView(empty("Nada aqui",
                    "O Apple Music não retornou itens para " + titles.get(titles.size() - 1) + "."));
            subtitleView.setText("0 itens");
            host.status("");
            return;
        }
        subtitleView.setText(children.size() + " itens");
        host.status("");
        if (mode == GRID) {
            addGrid(children);
        } else {
            addList(children);
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
        for (MediaClient.Item item : items) {
            final MediaClient.Item target = item;
            ListRow row = new ListRow(context, artwork, item.title, item.subtitle,
                    item.artworkUri, item.browsable);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    choose(target);
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    host.itemActions(target);
                    return true;
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = Tokens.dp(8);
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
