package com.anderson.applemusic.one;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Catalogue search against Apple Music's amp-api, ported from
 * {@code core/SearchClient.kt} to framework-only Java.
 *
 * <p>The developer token is read from the engine's DataStore file
 * ({@code files/datastore/encrypted_tokens.preferences_pb}) and decrypted with the
 * {@code music_token_store_key} key of the AndroidKeyStore. This code runs inside the
 * engine APK, so both the file and the key belong to the current UID.
 */
final class CatalogSearch {

    static final class Result {
        final String id;
        final String kind;
        final String title;
        final String artist;
        final String artworkUrl;

        Result(String id, String kind, String title, String artist, String artworkUrl) {
            this.id = id;
            this.kind = kind;
            this.title = title;
            this.artist = artist;
            this.artworkUrl = artworkUrl;
        }
    }

    interface Callback {
        void onResults(List<Result> results);

        void onFailure(String message, boolean tokenMissing);
    }

    private static final String TAG = "AppleMusicOne";
    private static final String ENDPOINT = "https://amp-api.music.apple.com/v1/catalog";
    private static final String FALLBACK_STOREFRONT = "us";
    private static final String FALLBACK_LANGUAGE = "en-US";
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120 Mobile Safari/537.36";

    private static final String TOKEN_PATH = "files/datastore/encrypted_tokens.preferences_pb";
    private static final String DEVELOPER_TOKEN_KEY = "key_api_token_musicAndroid";
    private static final String KEYSTORE_ALIAS = "music_token_store_key";
    private static final String ENGINE_SEARCH_URL = "https://music.apple.com/search";

    private static final String[][] KINDS = {
            {"songs", "Música", "artistName"},
            {"albums", "Álbum", "artistName"},
            {"artists", "Artista", "genreNames"},
            {"playlists", "Playlist", "curatorName"},
    };

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final java.util.concurrent.ExecutorService executor =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile String cachedToken;

    CatalogSearch(Activity activity) {
        this.activity = activity;
    }

    void search(final String query, final Callback callback) {
        final String term = query == null ? "" : query.trim();
        if (term.length() == 0) {
            callback.onResults(Collections.<Result>emptyList());
            return;
        }
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String token = developerToken();
                    if (token == null) {
                        postFailure(callback, "Não foi possível ler o token do Apple Music", true);
                        return;
                    }
                    HttpResult response = request(term, token);
                    if (response.unauthorized) {
                        // The engine refreshes the JWT in the file; read it again.
                        cachedToken = null;
                        token = developerToken();
                        if (token == null) {
                            postFailure(callback, "Token do Apple Music expirado", true);
                            return;
                        }
                        response = request(term, token);
                    }
                    if (response.unauthorized) {
                        postFailure(callback,
                                "Token do Apple Music expirado. Abra o app original e entre na conta.",
                                false);
                        return;
                    }
                    if (response.error != null) {
                        postFailure(callback, response.error, false);
                        return;
                    }
                    final List<Result> results = parse(response.body);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            callback.onResults(results);
                        }
                    });
                } catch (Throwable t) {
                    Log.e(TAG, "search failed", t);
                    postFailure(callback, "Sem conexão com o Apple Music", false);
                }
            }
        });
    }

    private void postFailure(final Callback callback, final String message,
                             final boolean tokenMissing) {
        main.post(new Runnable() {
            @Override
            public void run() {
                callback.onFailure(message, tokenMissing);
            }
        });
    }

    void shutdown() {
        executor.shutdownNow();
    }

    // --- amp-api -----------------------------------------------------------------

    private static final class HttpResult {
        String body;
        boolean unauthorized;
        String error;
    }

    private HttpResult request(String term, String token) {
        HttpResult result = new HttpResult();
        HttpURLConnection connection = null;
        String url = ENDPOINT + "/" + storefront() + "/search?term=" + encode(term)
                + "&types=songs,albums,artists,playlists&limit=8&l=" + language();
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("Authorization", "Bearer " + token);
            connection.setRequestProperty("Origin", "https://music.apple.com");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            result.body = stream != null ? text(stream) : "";
            Log.i(TAG, "search HTTP " + code + " bytes=" + result.body.length() + " term=\"" + term + "\"");
            if (code == HttpURLConnection.HTTP_OK) {
                return result;
            }
            if (code == HttpURLConnection.HTTP_UNAUTHORIZED
                    || code == HttpURLConnection.HTTP_FORBIDDEN) {
                result.unauthorized = true;
            } else {
                result.error = "Busca falhou (HTTP " + code + ")";
            }
        } catch (Throwable t) {
            Log.e(TAG, "search request failed", t);
            result.error = "Sem conexão com o Apple Music";
        } finally {
            if (connection != null) connection.disconnect();
        }
        return result;
    }

    private List<Result> parse(String body) {
        List<Result> found = new ArrayList<Result>();
        JSONObject results;
        try {
            results = new JSONObject(body).optJSONObject("results");
        } catch (org.json.JSONException t) {
            throw new IllegalArgumentException("resposta inesperada do Apple Music", t);
        }
        if (results == null) return found;
        for (String[] kind : KINDS) {
            JSONObject group = results.optJSONObject(kind[0]);
            if (group == null) continue;
            JSONArray data = group.optJSONArray("data");
            if (data == null) continue;
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                if (item == null) continue;
                String id = item.optString("id");
                if (id.length() == 0) continue;
                JSONObject attributes = item.optJSONObject("attributes");
                if (attributes == null) attributes = new JSONObject();
                String title = attributes.optString("name");
                if (title.length() == 0) continue;
                String artist = attributes.optString(kind[2]);
                if (artist.length() == 0) {
                    JSONArray genres = attributes.optJSONArray("genreNames");
                    if (genres != null) artist = genres.optString(0);
                }
                found.add(new Result(id, kind[1], title, artist, artwork(attributes)));
            }
        }
        return found;
    }

    private static String artwork(JSONObject attributes) {
        JSONObject artwork = attributes.optJSONObject("artwork");
        if (artwork == null) return null;
        String url = artwork.optString("url");
        if (url.length() == 0) return null;
        return url.replace("{w}", "300").replace("{h}", "300");
    }

    // --- engine token ------------------------------------------------------------

    private String developerToken() {
        String token = cachedToken;
        if (token != null) return token;
        try {
            byte[] pb = bytes(tokenFile());
            String cipher = prefValue(pb, DEVELOPER_TOKEN_KEY + "__cipher");
            String iv = prefValue(pb, DEVELOPER_TOKEN_KEY + "__iv");
            if (cipher == null || iv == null) {
                Log.w(TAG, "engine token keys not found in the DataStore file");
                return null;
            }
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            KeyStore.Entry entry = keyStore.getEntry(KEYSTORE_ALIAS, null);
            if (!(entry instanceof KeyStore.SecretKeyEntry)) {
                Log.w(TAG, "AndroidKeyStore has no " + KEYSTORE_ALIAS);
                return null;
            }
            SecretKey key = ((KeyStore.SecretKeyEntry) entry).getSecretKey();
            Cipher decipher = Cipher.getInstance("AES/GCM/NoPadding");
            decipher.init(Cipher.DECRYPT_MODE, key,
                    new GCMParameterSpec(128, Base64.decode(iv, Base64.DEFAULT)));
            token = new String(decipher.doFinal(Base64.decode(cipher, Base64.DEFAULT)), "UTF-8");
        } catch (Throwable t) {
            Log.w(TAG, "cannot read the engine developer token: " + t);
            return null;
        }
        if (token.length() == 0) return null;
        cachedToken = token;
        return token;
    }

    private File tokenFile() {
        ApplicationInfo info = activity.getApplicationInfo();
        return new File(info.dataDir, TOKEN_PATH);
    }

    /**
     * The engine's DataStore protobuf is androidx.datastore Preferences:
     * {@code entry = { 1: key, 2: { 5: value } }}, so right after the key bytes come
     * {@code [0x12][varint len][0x2A][varint len][len bytes of value]}.
     */
    private static String prefValue(byte[] pb, String key) {
        byte[] needle = key.getBytes(java.nio.charset.Charset.forName("UTF-8"));
        int at = indexOf(pb, needle);
        if (at < 0) return null;
        int position = at + needle.length;
        if (position + 1 >= pb.length || (pb[position] & 0xFF) != 0x12) return null;
        int[] next = readVarint(pb, position + 1);
        if (next == null) return null;
        position = next[1];
        if (position >= pb.length || (pb[position] & 0xFF) != 0x2A) return null;
        int[] length = readVarint(pb, position + 1);
        if (length == null) return null;
        int start = length[1];
        int size = length[0];
        if (size < 0 || start + size > pb.length) return null;
        return new String(pb, start, size, java.nio.charset.Charset.forName("UTF-8"));
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    /** @return {value, nextOffset} */
    private static int[] readVarint(byte[] bytes, int start) {
        int value = 0;
        int shift = 0;
        int position = start;
        while (position < bytes.length && shift < 32) {
            int b = bytes[position++] & 0xFF;
            value |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return new int[]{value, position};
            shift += 7;
        }
        return null;
    }

    private static byte[] bytes(File file) throws Exception {
        FileInputStream input = new FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) out.write(buffer, 0, read);
            return out.toByteArray();
        } finally {
            input.close();
        }
    }

    private static String text(InputStream input) throws Exception {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) > 0) out.write(buffer, 0, read);
            return out.toString("UTF-8");
        } finally {
            input.close();
        }
    }

    // --- device ------------------------------------------------------------------

    private static String storefront() {
        String country = Locale.getDefault().getCountry();
        return country.length() == 2 ? country.toLowerCase(Locale.ROOT) : FALLBACK_STOREFRONT;
    }

    private static String language() {
        Locale locale = Locale.getDefault();
        if (locale.getLanguage().length() == 2 && locale.getCountry().length() == 2) {
            return locale.getLanguage().toLowerCase(Locale.ROOT) + "-"
                    + locale.getCountry().toUpperCase(Locale.ROOT);
        }
        return FALLBACK_LANGUAGE;
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (Throwable t) {
            return value;
        }
    }

    /** Fallback when the token cannot be read: opens the engine's own search screen. */
    static void openEngineSearch(Activity activity) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(ENGINE_SEARCH_URL));
            intent.setClassName(MediaClient.PACKAGE, MediaClient.ACCOUNT_ACTIVITY);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Throwable t) {
            Log.e(TAG, "cannot open the engine search", t);
        }
    }
}
