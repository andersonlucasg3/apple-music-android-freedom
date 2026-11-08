package com.anderson.applemusic.one.screen;

import com.anderson.applemusic.one.media.CatalogSearch;
import com.anderson.applemusic.one.media.MediaClient;

import java.util.Locale;

/**
 * Opens one item of the engine's library tree. Library rows only carry the persistent id
 * (no catalogue id), so the rich screen is reached by resolving the name; a miss keeps the
 * previous browse behaviour instead of dead-ending the tap.
 *
 * <p>Shared by the Biblioteca sections/filter and the local scope of the search.
 */
public final class LibraryOpener {

    private LibraryOpener() {
    }

    public static void open(final ScreenHost host, final CatalogSearch catalog,
                            final MediaClient.Item item) {
        if (item == null) return;
        String last = lastSegment(item.id).toLowerCase(Locale.ROOT);
        if (last.startsWith("6-")) {
            resolveArtist(host, catalog, item);
            return;
        }
        if (last.startsWith("3-")) {
            resolveAlbum(host, catalog, item);
            return;
        }
        if (last.startsWith("4-")) {
            resolvePlaylist(host, catalog, item);
            return;
        }
        if (item.playable) {
            host.play(item);
            return;
        }
        host.status("Item não reproduzível");
    }

    private static void resolveArtist(final ScreenHost host, CatalogSearch catalog,
                                      final MediaClient.Item item) {
        host.status("Abrindo " + item.title + "…");
        catalog.findArtist(item.title, new CatalogSearch.MatchCallback() {
            @Override
            public void onMatch(CatalogSearch.Result match) {
                host.openArtist(match.id, item.title,
                        match.artworkUrl != null ? match.artworkUrl : item.artworkUri);
            }

            @Override
            public void onFailure(String message) {
                host.status("Artista sem catálogo · abrindo os álbuns da biblioteca");
                host.openCollection(item.title, item.id, false);
            }
        });
    }

    private static void resolveAlbum(final ScreenHost host, CatalogSearch catalog,
                                     final MediaClient.Item item) {
        host.status("Abrindo " + item.title + "…");
        catalog.findAlbumMatch(item.title, item.subtitle, new CatalogSearch.MatchCallback() {
            @Override
            public void onMatch(CatalogSearch.Result match) {
                host.openAlbum(match.id, item.title, item.subtitle,
                        match.artworkUrl != null ? match.artworkUrl : item.artworkUri);
            }

            @Override
            public void onFailure(String message) {
                host.status("Álbum sem catálogo · abrindo a lista de faixas");
                host.openLibraryCollection(CollectionScreen.ALBUM, item.title, item.subtitle,
                        item.id);
            }
        });
    }

    private static void resolvePlaylist(final ScreenHost host, CatalogSearch catalog,
                                        final MediaClient.Item item) {
        host.status("Abrindo " + item.title + "…");
        catalog.findPlaylistMatch(item.title, item.subtitle,
                new CatalogSearch.MatchCallback() {
                    @Override
                    public void onMatch(CatalogSearch.Result match) {
                        host.openPlaylist(match.id, item.title, item.subtitle,
                                match.artworkUrl != null ? match.artworkUrl
                                        : item.artworkUri);
                    }

                    @Override
                    public void onFailure(String message) {
                        host.status("Playlist sem catálogo · abrindo a lista de faixas");
                        host.openLibraryCollection(CollectionScreen.PLAYLIST, item.title,
                                item.subtitle, item.id);
                    }
                });
    }

    static String lastSegment(String id) {
        if (id == null) return "";
        int slash = id.lastIndexOf('/');
        return slash < 0 ? id : id.substring(slash + 1);
    }
}
