/*
 * Copyright (c) 2017 Bartek Fabiszewski
 * http://www.fabiszewski.net
 *
 * This file is part of mobile-android.
 * Licensed under GPL, either version 3, or any later.
 * See <http://www.gnu.org/licenses/>
 */

package de.arikgrahl.mobile;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.util.Log;

import org.json.JSONException;

import java.io.IOException;
import java.lang.reflect.Array;
import java.net.ConnectException;
import java.net.MalformedURLException;
import java.net.NoRouteToHostException;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static android.app.PendingIntent.FLAG_ONE_SHOT;

/**
 * Service synchronizing local database positions with remote server.
 *
 */

public class WebSyncService extends IntentService {

    private static final String TAG = WebSyncService.class.getSimpleName();
    public static final String BROADCAST_SYNC_FAILED = "de.arikgrahl.mobile.broadcast.sync_failed";
    public static final String BROADCAST_SYNC_DONE = "de.arikgrahl.mobile.broadcast.sync_done";

    private DbAccess db;
    private WebHelper web;
    private static PendingIntent pi = null;

    final private static int FIVE_MINUTES = 1000 * 60 * 5;
    final private static int BULK_SIZE = 100;


    /**
     * Constructor
     */
    public WebSyncService() {
        super("WebSyncService");
    }

    @Override
    public void onCreate() {
        super.onCreate();
        if (Logger.DEBUG) { Log.d(TAG, "[websync create]"); }

        web = new WebHelper(this);
        db = DbAccess.getInstance();
        db.open(this);
    }

    /**
     * Handle synchronization intent
     * @param intent Intent
     */
    @Override
    protected void onHandleIntent(Intent intent) {
        if (Logger.DEBUG) { Log.d(TAG, "[websync start]"); }

        cancelPending();

        if (!WebHelper.isAuthorized) {
            try {
                web.authorize();
            } catch (WebAuthException|IOException|JSONException e) {
                handleError(e);
                return;
            }
        }

        // get track id
        int trackId = getTrackId();
        if (trackId > 0) {
            doSync(trackId);
        }
    }

    /**
     * Get track id
     * If the track hasn't been registered on server yet,
     * set up new track on the server and get new id
     * @return Track id
     */
    private int getTrackId() {
        int trackId = db.getTrackId();
        if (trackId == 0) {
            String trackName = db.getTrackName();
            if (trackName == null) {
                handleError(new IllegalStateException("no track"));
                return trackId;
            }
            try {
                trackId = web.startTrack(trackName);
                db.setTrackId(trackId);
            } catch (IOException e) {
                if (Logger.DEBUG) { Log.d(TAG, "[websync io exception: " + e + "]"); }
                // schedule retry
                handleError(e);
            } catch (WebAuthException e) {
                if (Logger.DEBUG) { Log.d(TAG, "[websync auth exception: " + e + "]"); }
                WebHelper.deauthorize();
                try {
                    // reauthorize and retry
                    web.authorize();
                    trackId = web.startTrack(trackName);
                    db.setTrackId(trackId);
                } catch (WebAuthException|IOException|JSONException e2) {
                    // schedule retry
                    handleError(e2);
                }
            }
        }
        return trackId;
    }

    /**
     * Synchronize all positions in database.
     * Skips already synchronized, uploads new ones
     * @param trackId Current track id
     */
    private void doSync(int trackId) {
        if (Logger.DEBUG) {
            Log.d(TAG, "doSync");
        }
        // iterate over positions in db
        try (Cursor cursor = db.getUnsynced()) {
            if (Logger.DEBUG) {
                Log.d(TAG, "sync positions");
            }
            while (cursor.moveToNext()) {
                int rowId = cursor.getInt(cursor.getColumnIndex(DbContract.Positions._ID));
                Map<String, String> params = cursorToMap(cursor);
                params.put(WebHelper.PARAM_TRACKID, String.valueOf(trackId));
                web.postPosition(params);
                db.setSynced(rowId);
                Intent intent = new Intent(BROADCAST_SYNC_DONE);
                sendBroadcast(intent);
            }
        } catch (IOException e) {
            // handle web errors
            if (Logger.DEBUG) {
                Log.d(TAG, "[websync io exception: " + e + "]");
            }
            // schedule retry
            handleError(e);
        } catch (WebAuthException e) {
            if (Logger.DEBUG) {
                Log.d(TAG, "[websync auth exception: " + e + "]");
            }
            WebHelper.deauthorize();
            try {
                // reauthorize and retry
                web.authorize();
                doSync(trackId);
            } catch (WebAuthException | IOException | JSONException e2) {
                // schedule retry
                handleError(e2);
            }
        }

        Map<String, String>[] params = new HashMap[BULK_SIZE];
        int[] rowIds = new int[BULK_SIZE];
        int i = 0;

        try (Cursor cursor = db.getUnsyncedAccelerations()) {
            if (Logger.DEBUG) {
                Log.d(TAG, "sync accelerations");
            }
            while (cursor.moveToNext()) {
                rowIds[i] = cursor.getInt(cursor.getColumnIndex(DbContract.Accelerations._ID));
                params[i] = cursorToMapAcceleration(cursor);
                params[i].put(WebHelper.PARAM_TRACKID, String.valueOf(trackId));
                i++;
                if (i == 10) {
                    i = 0;
                    syncAccelerations(params, rowIds);
                }
            }
            syncAccelerations(params, rowIds);
        } catch (WebAuthException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void syncAccelerations(Map<String, String>[] params, int[] rowIds) throws IOException, WebAuthException {
        web.postAcceleration(params);
        db.setSyncedAcceleration(rowIds);
        for (int i = 0; i < BULK_SIZE; i++) {
            params[i] = null;
            rowIds[i] = 0;
        }
    }

    /**
     * Actions performed in case of synchronization error.
     * Send broadcast to main activity, schedule retry if tracking is on.
     *
     * @param e Exception
     */
    private void handleError(Exception e) {
        String message;
        if (e instanceof UnknownHostException) {
            message = getString(R.string.e_unknown_host, e.getMessage());
        } else if (e instanceof MalformedURLException || e instanceof URISyntaxException) {
            message = getString(R.string.e_bad_url, e.getMessage());
        } else if (e instanceof ConnectException || e instanceof NoRouteToHostException) {
            message = getString(R.string.e_connect, e.getMessage());
        } else if (e instanceof IllegalStateException) {
            message = getString(R.string.e_illegal_state, e.getMessage());
        } else {
            message = e.getMessage();
        }
        if (Logger.DEBUG) { Log.d(TAG, "[websync retry: " + message + "]"); }

        db.setError(message);
        Intent intent = new Intent(BROADCAST_SYNC_FAILED);
        intent.putExtra("message", message);
        sendBroadcast(intent);
        // retry only if tracking is on
        if (LoggerService.isRunning()) {
            setPending();
        }
    }

    /**
     * Set pending alarm
     */
    private void setPending() {
        if (Logger.DEBUG) { Log.d(TAG, "[websync set alarm]"); }
        AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        Intent syncIntent = new Intent(getApplicationContext(), WebSyncService.class);
        pi = PendingIntent.getService(this, 0, syncIntent, FLAG_ONE_SHOT);
        if (am != null) {
            am.set(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + FIVE_MINUTES, pi);
        }
    }

    /**
     * Cancel pending alarm
     */
    private void cancelPending() {
        if (hasPending()) {
            if (Logger.DEBUG) { Log.d(TAG, "[websync cancel alarm]"); }
            AlarmManager am = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
            if (am != null) {
                am.cancel(pi);
            }
            pi = null;
        }
    }

    /**
     * Is pending alarm set
     * @return True if has pending alarm set
     */
    private boolean hasPending() {
        return pi != null;
    }

    /**
     * Convert cursor to map of request parameters
     *
     * @param cursor Cursor
     * @return Map of parameters
     */
    private Map<String, String> cursorToMap(Cursor cursor) {
        Map<String, String> params = new HashMap<>();
        params.put(WebHelper.PARAM_TIME, DbAccess.getTime(cursor));
        params.put(WebHelper.PARAM_LAT, DbAccess.getLatitude(cursor));
        params.put(WebHelper.PARAM_LON, DbAccess.getLongitude(cursor));
        params.put(WebHelper.PARAM_BATTERY_STATUS, DbAccess.getBatteryStatus(cursor));
        params.put(WebHelper.PARAM_BATTERY_LEVEL, DbAccess.getBatteryLevel(cursor));
        if (DbAccess.hasAltitude(cursor)) {
            params.put(WebHelper.PARAM_ALT, DbAccess.getAltitude(cursor));
        }
        if (DbAccess.hasSpeed(cursor)) {
            params.put(WebHelper.PARAM_SPEED, DbAccess.getSpeed(cursor));
        }
        if (DbAccess.hasBearing(cursor)) {
            params.put(WebHelper.PARAM_BEARING, DbAccess.getBearing(cursor));
        }
        if (DbAccess.hasAccuracy(cursor)) {
            params.put(WebHelper.PARAM_ACCURACY, DbAccess.getAccuracy(cursor));
        }
        if (DbAccess.hasProvider(cursor)) {
            params.put(WebHelper.PARAM_PROVIDER, DbAccess.getProvider(cursor));
        }
        return params;
    }

    private Map<String, String> cursorToMapAcceleration(Cursor cursor) {
        Map<String, String> params = new HashMap<>();
        params.put(WebHelper.PARAM_TIME, DbAccess.getTime(cursor));
        params.put(WebHelper.PARAM_X, DbAccess.getX(cursor));
        params.put(WebHelper.PARAM_Y, DbAccess.getY(cursor));
        params.put(WebHelper.PARAM_Z, DbAccess.getZ(cursor));
        return params;
    }

    /**
     * Cleanup
     */
    @Override
    public void onDestroy() {
        if (Logger.DEBUG) { Log.d(TAG, "[websync stop]"); }
        if (db != null) {
            db.close();
        }
        super.onDestroy();
    }

}
