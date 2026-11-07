package com.anderson.applemusic.one.screen;

import android.content.Context;

import com.anderson.applemusic.one.media.ArtworkLoader;
import com.anderson.applemusic.one.widget.Icons;

/** Biblioteca: list of categories (Recentes, Playlists, Artistas, Álbuns...) + drill-down. */
public final class LibraryScreen extends CollectionScreen {

    public LibraryScreen(Context context, ScreenHost host, ArtworkLoader artwork) {
        super(context, host, artwork, LIST, Icons.LIBRARY);
    }
}
