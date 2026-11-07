package com.anderson.applemusic.one.screen;

import com.anderson.applemusic.one.media.MediaClient;

/**
 * What the screens are allowed to ask of {@code OneActivity}: browse subscriptions,
 * transport, overlays and status. Keeps the screens independent from the activity's
 * MediaBrowser/updater wiring.
 */
public interface ScreenHost {

    void loadChildren(String parentId, ChildSink sink);

    void forgetChildren(ChildSink sink);

    void play(MediaClient.Item item);

    void playId(String mediaId, String label);

    /** Plays a collection id and starts at its 0-based track {@code index}. */
    void playCollectionFrom(String collectionId, String label, int index, MediaClient.Item track);

    /** Jumps inside the session queue ({@code MediaController.skipToQueueItem}). */
    void skipToQueue(long queueId);

    /** Latest playback snapshot, for the "tocando agora" state of a freshly built list. */
    MediaClient.Snapshot nowPlaying();

    void togglePlayPause();

    void skipNext();

    void skipPrevious();

    void seek(long positionMs);

    void status(String message);

    void openSearch();

    void openAccount();

    void openUpdates();

    void openPlayer();

    void openCollection(String title, String parentId, boolean grid);

    void sessionActions();

    void itemActions(MediaClient.Item item);

    void createStation();

    void closePlayer();

    void closeSearch();

    void closeActions();

    void closeUpdates();

    void closeAccount();

    void closeCollection();

    /** Id of the mini player, the D-pad "down" target from the shelves. */
    int downFocusId();

    boolean isTv();
}
