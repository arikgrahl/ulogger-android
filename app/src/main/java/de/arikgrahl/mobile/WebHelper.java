/*
 * Copyright (c) 2017 Bartek Fabiszewski
 * http://www.fabiszewski.net
 *
 * This file is part of mobile-android.
 * Licensed under GPL, either version 3, or any later.
 * See <http://www.gnu.org/licenses/>
 */

package de.arikgrahl.mobile;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.preference.PreferenceManager;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookieStore;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.Map;

/**
 * Web server communication
 *
 */

class WebHelper {
    private static final String TAG = WebSyncService.class.getSimpleName();
    private static CookieManager cookieManager = null;

    private static String host;
    private static String user;
    private static String pass;

    private static final String CLIENT_SCRIPT = "client/index.php";
    private static final String PARAM_ACTION = "action";

    // addpos
    private static final String ACTION_ADDPOS = "addpos";
    static final String PARAM_TIME = "time";
    static final String PARAM_LAT = "lat";
    static final String PARAM_LON = "lon";
    static final String PARAM_ALT = "altitude";
    static final String PARAM_SPEED = "speed";
    static final String PARAM_BEARING = "bearing";
    static final String PARAM_ACCURACY = "accuracy";
    static final String PARAM_PROVIDER = "provider";
    static final String PARAM_BATTERY_STATUS = "battery_status";
    static final String PARAM_BATTERY_LEVEL = "battery_level";
    // todo add comments, images
//    static final String PARAM_COMMENT = "comment";
//    static final String PARAM_IMAGEID = "imageid";
    static final String PARAM_TRACKID = "trackid";

    private static final String ACTION_ADDACC = "addacc";
    static final String PARAM_X = "x";
    static final String PARAM_Y = "y";
    static final String PARAM_Z = "z";

    // auth
    private static final String ACTION_AUTH = "auth";
    // todo adduser not implemented (do we need it?)
//    private static final String ACTION_ADDUSER = "adduser";
    private static final String PARAM_USER = "user";
    private static final String PARAM_PASS = "pass";

    // addtrack
    private static final String ACTION_ADDTRACK = "addtrack";
    private static final String PARAM_TRACK = "track";

    private final String userAgent;
    private final Context context;

    // Socket timeout in milliseconds
    private static final int SOCKET_TIMEOUT = 30 * 1000;

    static boolean isAuthorized = false;

    /**
     * Constructor
     * @param ctx Context
     */
    WebHelper(Context ctx) {
        context = ctx;
        loadPreferences(ctx);
        userAgent = context.getString(R.string.app_name_ascii) + "/" + BuildConfig.VERSION_NAME + "; " + System.getProperty("http.agent");

        if (cookieManager == null) {
            cookieManager = new CookieManager();
            CookieHandler.setDefault(cookieManager);
        }

    }

    /**
     * Send post request
     * @param params Request parameters
     * @return Server response
     * @throws IOException Connection error
     * @throws WebAuthException Authorization error
     */
    private String postWithParams(Map<String, String> params) throws IOException, WebAuthException {
        URL url = new URL(host + "/" + CLIENT_SCRIPT);
        if (Logger.DEBUG) { Log.d(TAG, "[postWithParams: " + url + " : " + params + "]"); }
        String response;

        StringBuilder dataString = new StringBuilder();
        for (Map.Entry<String, String> p : params.entrySet()) {
            String key = p.getKey();
            String value = p.getValue();
            if (dataString.length() > 0) {
                dataString.append("&");
            }
            dataString.append(URLEncoder.encode(key, "UTF-8"))
                      .append("=")
                      .append(URLEncoder.encode(value, "UTF-8"));
        }
        byte[] data = dataString.toString().getBytes();

        HttpURLConnection connection = null;
        InputStream in = null;
        OutputStream out = null;
        try {
            boolean redirect;
            int redirectTries = 5;
            do {
                redirect = false;
                connection = (HttpURLConnection) url.openConnection();
                connection.setDoOutput(true);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                connection.setRequestProperty("Content-Length", Integer.toString(data.length));
                connection.setRequestProperty("User-Agent", userAgent);
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(SOCKET_TIMEOUT);
                connection.setReadTimeout(SOCKET_TIMEOUT);
                connection.setUseCaches(true);

                out = new BufferedOutputStream(connection.getOutputStream());
                out.write(data);
                out.flush();

                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_MOVED_PERM
                        || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                        || responseCode == HttpURLConnection.HTTP_SEE_OTHER
                        || responseCode == 307) {
                    URL base = connection.getURL();
                    String location = connection.getHeaderField("Location");
                    if (Logger.DEBUG) { Log.d(TAG, "[postWithParams redirect: " + location + "]"); }
                    if (location == null || redirectTries == 0) {
                        throw new IOException(context.getString(R.string.e_illegal_redirect, responseCode));
                    }
                    redirect = true;
                    redirectTries--;
                    url = new URL(base, location);
                    String h1 = base.getHost();
                    String h2 = url.getHost();
                    if (h1 != null && !h1.equalsIgnoreCase(h2)) {
                        throw new IOException(context.getString(R.string.e_illegal_redirect, responseCode));
                    }
                    try {
                        out.close();
                        connection.getInputStream().close();
                        connection.disconnect();
                    } catch (final IOException e) {
                        if (Logger.DEBUG) { Log.d(TAG, "[connection cleanup failed (ignored)]"); }
                    }
                }
                else if (responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) {
                    throw new WebAuthException(context.getString(R.string.e_auth_failure, responseCode));
                }
                else if (responseCode != HttpURLConnection.HTTP_OK) {
                    throw new IOException(context.getString(R.string.e_http_code, responseCode));
                }
            } while (redirect);

            in = new BufferedInputStream(connection.getInputStream());

            StringBuilder sb = new StringBuilder();
            BufferedReader br = new BufferedReader(new InputStreamReader(in));
            String inputLine;
            while ((inputLine = br.readLine()) != null) {
                sb.append(inputLine);
            }
            response = sb.toString();
        } finally {
            try {
                if (out != null) {
                    out.close();
                }
                if (in != null) {
                    in.close();
                }
                if (connection != null) {
                    connection.disconnect();
                }
            } catch (final IOException e) {
                if (Logger.DEBUG) { Log.d(TAG, "[connection cleanup failed (ignored)]"); }
            }
        }
        if (Logger.DEBUG) { Log.d(TAG, "[postWithParams response: " + response + "]"); }
        return response;
    }

    /**
     * Upload position to server
     * @param params Map of parameters (position properties)
     * @throws IOException Connection error
     * @throws WebAuthException Authorization error
     */
    void postPosition(Map<String, String> params) throws IOException, WebAuthException {
        if (Logger.DEBUG) { Log.d(TAG, "[postPosition]"); }
        params.put(PARAM_ACTION, ACTION_ADDPOS);
        String response = postWithParams(params);
        boolean error = true;
        try {
            JSONObject json = new JSONObject(response);
            error = json.getBoolean("error");
        } catch (JSONException e) {
            if (Logger.DEBUG) { Log.d(TAG, "[postPosition json failed: " + e + "]"); }
        }
        if (error) {
            throw new IOException(context.getString(R.string.e_server_response));
        }
    }

    void postAcceleration(Map<String, String> params) throws IOException, WebAuthException {
        params.put(PARAM_ACTION, ACTION_ADDACC);
        String response = postWithParams(params);
        boolean error = true;
        try {
            JSONObject json = new JSONObject(response);
            error = json.getBoolean("error");
        } catch (JSONException e) {
            if (Logger.DEBUG) { Log.d(TAG, "[postAcceleration json failed: " + e + "]"); }
        }
        if (error) {
            throw new IOException(context.getString(R.string.e_server_response));
        }
    }

    /**
     * Start new track on server
     * @param name Track name
     * @return Track id
     * @throws IOException Connection error
     * @throws WebAuthException Authorization error
     */
    int startTrack(String name) throws IOException, WebAuthException {
        if (Logger.DEBUG) { Log.d(TAG, "[startTrack: " + name + "]"); }
        Map<String, String> params = new HashMap<>();
        params.put(PARAM_ACTION, ACTION_ADDTRACK);
        params.put(PARAM_TRACK, name);
        try {
            String response = postWithParams(params);
            JSONObject json = new JSONObject(response);
            boolean error = json.getBoolean("error");
            if (error) {
                throw new IOException(context.getString(R.string.e_server_response));
            } else {
                return json.getInt("trackid");
            }
        } catch (JSONException e) {
            if (Logger.DEBUG) { Log.d(TAG, "[startTrack json failed: " + e + "]"); }
            throw new IOException(e);
        }
    }

    /**
     * Authorize on server
     * @throws IOException Connection error
     * @throws WebAuthException Authorization error
     * @throws JSONException Response parsing error
     */
    void authorize() throws IOException, WebAuthException, JSONException {
        if (Logger.DEBUG) { Log.d(TAG, "[authorize]"); }
        Map<String, String> params = new HashMap<>();
        params.put(PARAM_ACTION, ACTION_AUTH);
        params.put(PARAM_USER, user);
        params.put(PARAM_PASS, pass);
        String response = postWithParams(params);
        JSONObject json = new JSONObject(response);
        boolean error = json.getBoolean("error");
        if (error) {
            throw new WebAuthException(context.getString(R.string.e_server_response));
        }
        isAuthorized = true;
    }

    /**
     * Remove authorization by removing session cookie
     */
    static void deauthorize() {
        if (Logger.DEBUG) { Log.d(TAG, "[deauthorize]"); }
        if (cookieManager != null) {
            CookieStore store = cookieManager.getCookieStore();
            store.removeAll();
        }
        isAuthorized = false;
    }

    /**
     * Get settings from shared preferences
     * @param context Context
     */
    private static void loadPreferences(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        user = prefs.getString(SettingsActivity.KEY_USERNAME, "NULL");
        pass = prefs.getString(SettingsActivity.KEY_PASS, "NULL");
        host = prefs.getString(SettingsActivity.KEY_HOST, "NULL").replaceAll("/+$", "");
    }

    /**
     * Reload settings from shared preferences.
     * @param context Context
     */
     static void updatePreferences(Context context) {
        loadPreferences(context);
        deauthorize();
    }

    /**
     * Check whether given url is valid.
     * Uses relaxed pattern (@see WebPatterns#WEB_URL_RELAXED)
     * @param url URL
     * @return True if valid, false otherwise
     */
    static boolean isValidURL(String url) {
        return WebPatterns.WEB_URL_RELAXED.matcher(url).matches();
    }

}
