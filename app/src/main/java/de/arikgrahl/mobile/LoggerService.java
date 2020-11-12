/*
 * Copyright (c) 2017 Bartek Fabiszewski
 * http://www.fabiszewski.net
 *
 * This file is part of mobile-android.
 * Licensed under GPL, either version 3, or any later.
 * See <http://www.gnu.org/licenses/>
 */

package de.arikgrahl.mobile;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.TaskStackBuilder;
import androidx.preference.PreferenceManager;

import static de.arikgrahl.mobile.MainActivity.UPDATED_PREFS;

/**
 * Background service logging positions to database
 * and synchronizing with remote server.
 *
 */

public class LoggerService extends Service {

    private static final String TAG = LoggerService.class.getSimpleName();
    public static final String BROADCAST_LOCATION_DISABLED = "de.arikgrahl.mobile.broadcast.location_disabled";
    public static final String BROADCAST_LOCATION_GPS_DISABLED = "de.arikgrahl.mobile.broadcast.gps_disabled";
    public static final String BROADCAST_LOCATION_GPS_ENABLED = "de.arikgrahl.mobile.broadcast.gps_enabled";
    public static final String BROADCAST_LOCATION_NETWORK_DISABLED = "de.arikgrahl.mobile.broadcast.network_disabled";
    public static final String BROADCAST_LOCATION_NETWORK_ENABLED = "de.arikgrahl.mobile.broadcast.network_enabled";
    public static final String BROADCAST_LOCATION_PERMISSION_DENIED = "de.arikgrahl.mobile.broadcast.location_permission_denied";
    public static final String BROADCAST_LOCATION_STARTED = "de.arikgrahl.mobile.broadcast.location_started";
    public static final String BROADCAST_LOCATION_STOPPED = "de.arikgrahl.mobile.broadcast.location_stopped";
    public static final String BROADCAST_LOCATION_UPDATED = "de.arikgrahl.mobile.broadcast.location_updated";
    private boolean liveSync = false;
    private Intent syncIntent;

    private static volatile boolean isRunning = false;
    private LoggerThread thread;
    private Looper looper;
    private LocationManager locManager;
    private LocationListener locListener;
    private DbAccess db;
    private int maxAccuracy;
    private float minDistance;
    private long accelerometerFrequency;
    private long minTimeMillis;
    // max time tolerance is half min time, but not more that 5 min
    final private long minTimeTolerance = Math.min(minTimeMillis / 2, 5 * 60 * 1000);
    final private long maxTimeMillis = minTimeMillis + minTimeTolerance;

    private static Location lastLocation = null;
    private static volatile long lastUpdateRealtime = 0;

    private final int NOTIFICATION_ID = 1526756640;
    private NotificationManager mNotificationManager;
    private boolean useGps;
    private boolean useNet;

    SensorManager sensorManager;
    private Sensor accelerometer;
    private SensorEventListener accelerometerListener;

    private long mAccLast = 0;

    /**
     * Basic initializations.
     */
    @Override
    public void onCreate() {
        if (Logger.DEBUG) { Log.d(TAG, "[onCreate]"); }

        sensorManager = (SensorManager)getSystemService(Context.SENSOR_SERVICE);
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        accelerometerListener = new mAccelerationListener();
        sensorManager.registerListener(accelerometerListener, accelerometer, SensorManager.SENSOR_DELAY_FASTEST);

        mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (mNotificationManager != null) {
            mNotificationManager.cancelAll();
        }

        locManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        locListener = new mLocationListener();

        // read user preferences
        updatePreferences();

        boolean hasLocationUpdates = requestLocationUpdates();

        if (hasLocationUpdates) {
            setRunning(true);
            sendBroadcast(BROADCAST_LOCATION_STARTED);

            syncIntent = new Intent(getApplicationContext(), WebSyncService.class);

            thread = new LoggerThread();
            thread.start();
            looper = thread.getLooper();

            db = DbAccess.getInstance();
            db.open(this);

            // start websync service if needed
            if (liveSync && db.needsSync()) {
                startService(syncIntent);
            }
        }
    }

    /**
     * Start main thread, request location updates, start synchronization.
     *
     * @param intent Intent
     * @param flags Flags
     * @param startId Unique id
     * @return Always returns START_STICKY
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (Logger.DEBUG) { Log.d(TAG, "[onStartCommand]"); }

        final boolean prefsUpdated = (intent != null) && intent.getBooleanExtra(UPDATED_PREFS, false);
        if (prefsUpdated) {
            handlePrefsUpdated();
        } else {
            final Notification notification = showNotification(NOTIFICATION_ID);
            startForeground(NOTIFICATION_ID, notification);
            if (!isRunning) {
                // onCreate failed to start updates
                stopSelf();
            }
        }

        return START_STICKY;
    }

    /**
     * When user updated preferences, restart location updates, stop service on failure
     */
    private void handlePrefsUpdated() {
        // restart updates
        updatePreferences();
        if (isRunning && !restartUpdates()) {
            // no valid providers after preferences update
            stopSelf();
        }
    }

    /**
     * Check if user granted permission to access location.
     *
     * @return True if permission granted, false otherwise
     */
    private boolean canAccessLocation() {
        return (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED);
    }

    /**
     * Check if given provider exists on device
     * @param provider Provider
     * @return True if exists, false otherwise
     */
    private boolean providerExists(String provider) {
        return locManager.getAllProviders().contains(provider);
    }

    /**
     * Reread preferences
     */
    private void updatePreferences() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        accelerometerFrequency = Long.parseLong(prefs.getString(SettingsActivity.KEY_ACCELEROMETER_FREQUENCY, "0"));
        minTimeMillis = Long.parseLong(prefs.getString(SettingsActivity.KEY_MIN_TIME, getString(R.string.pref_mintime_default))) * 1000;
        minDistance = Float.parseFloat(prefs.getString(SettingsActivity.KEY_MIN_DISTANCE, getString(R.string.pref_mindistance_default)));
        maxAccuracy = Integer.parseInt(prefs.getString(SettingsActivity.KEY_MIN_ACCURACY, getString(R.string.pref_minaccuracy_default)));
        useGps = prefs.getBoolean(SettingsActivity.KEY_USE_GPS, providerExists(LocationManager.GPS_PROVIDER));
        useNet = prefs.getBoolean(SettingsActivity.KEY_USE_NET, providerExists(LocationManager.NETWORK_PROVIDER));
        liveSync = prefs.getBoolean(SettingsActivity.KEY_LIVE_SYNC, false);
    }

    /**
     * Restart request for location updates
     *
     * @return True if succeeded, false otherwise (eg. disabled all providers)
     */
    private boolean restartUpdates() {
        if (Logger.DEBUG) { Log.d(TAG, "[location updates restart]"); }

        locManager.removeUpdates(locListener);
        return requestLocationUpdates();
    }

    /**
     * Request location updates
     * @return True if succeeded from at least one provider
     */
    @SuppressWarnings({"MissingPermission"})
    private boolean requestLocationUpdates() {
        boolean hasLocationUpdates = false;
        if (canAccessLocation()) {
            if (useNet) {
                locManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, minTimeMillis, minDistance, locListener, looper);
                if (locManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    hasLocationUpdates = true;
                    if (Logger.DEBUG) { Log.d(TAG, "[Using net provider]"); }
                }
            }
            if (useGps) {
                locManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, minTimeMillis, minDistance, locListener, looper);
                if (locManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    hasLocationUpdates = true;
                    if (Logger.DEBUG) { Log.d(TAG, "[Using gps provider]"); }
                }
            }
            if (!hasLocationUpdates) {
                // no location provider available
                sendBroadcast(BROADCAST_LOCATION_DISABLED);
                if (Logger.DEBUG) { Log.d(TAG, "[No available location updates]"); }
            }
        } else {
            // can't access location
            sendBroadcast(BROADCAST_LOCATION_PERMISSION_DENIED);
            if (Logger.DEBUG) { Log.d(TAG, "[Location permission denied]"); }
        }

        return hasLocationUpdates;
    }

    /**
     * Service cleanup
     */
    @Override
    public void onDestroy() {
        if (Logger.DEBUG) { Log.d(TAG, "[onDestroy]"); }

        if (canAccessLocation()) {
            locManager.removeUpdates(locListener);
        }
        if (db != null) {
            db.close();
        }

        setRunning(false);

        mNotificationManager.cancel(NOTIFICATION_ID);
        sendBroadcast(BROADCAST_LOCATION_STOPPED);

        if (thread != null) {
            thread.interrupt();
        }
        thread = null;

    }

    @Override
    public IBinder onBind(Intent intent) {
        throw new UnsupportedOperationException("Not implemented");
    }

    /**
     * Check if logger service is running.
     *
     * @return True if running, false otherwise
     */
    public static boolean isRunning() {
        return isRunning;
    }


    /**
     * Set service running state
     * @param isRunning True if running, false otherwise
     */
    private void setRunning(boolean isRunning) {
        LoggerService.isRunning = isRunning;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(SettingsActivity.KEY_LOGGER_RUNNING, isRunning);
        editor.apply();
    }

    /**
     * Return realtime of last update in milliseconds
     *
     * @return Time or zero if not set
     */
    public static long lastUpdateRealtime() {
        return lastUpdateRealtime;
    }

    /**
     * Reset realtime of last update
     */
    public static void resetUpdateRealtime() {

        lastUpdateRealtime = 0;
    }

    /**
     * Main service thread class handling location updates.
     */
    private class LoggerThread extends HandlerThread {
        LoggerThread() {
            super("LoggerThread");
        }
        private final String TAG = LoggerThread.class.getSimpleName();

        @Override
        public void interrupt() {
            if (Logger.DEBUG) { Log.d(TAG, "[interrupt]"); }
        }

        @Override
        public void finalize() throws Throwable {
            if (Logger.DEBUG) { Log.d(TAG, "[finalize]"); }
            super.finalize();
        }

        @Override
        public void run() {
            if (Logger.DEBUG) { Log.d(TAG, "[run]"); }
            super.run();
        }
    }

    /**
     * Show notification
     *
     * @param mId Notification Id
     */
    @SuppressWarnings("SameParameterValue")
    private Notification showNotification(int mId) {
        if (Logger.DEBUG) { Log.d(TAG, "[showNotification " + mId + "]"); }

        final String channelId = String.valueOf(mId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel(channelId);
        }
        NotificationCompat.Builder mBuilder =
                new NotificationCompat.Builder(this, channelId)
                        .setSmallIcon(R.drawable.ic_stat_notify_24dp)
                        .setContentTitle(getString(R.string.app_name))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_SERVICE)
                        .setContentText(String.format(getString(R.string.is_running), getString(R.string.app_name)));
        Intent resultIntent = new Intent(this, MainActivity.class);

        TaskStackBuilder stackBuilder = TaskStackBuilder.create(this);
        stackBuilder.addParentStack(MainActivity.class);
        stackBuilder.addNextIntent(resultIntent);
        PendingIntent resultPendingIntent = stackBuilder.getPendingIntent(0, PendingIntent.FLAG_UPDATE_CURRENT);
        mBuilder.setContentIntent(resultPendingIntent);
        Notification mNotification = mBuilder.build();
        mNotificationManager.notify(mId, mNotification);
        return mNotification;
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private void createNotificationChannel(String channelId) {
        NotificationChannel chan = new NotificationChannel(channelId, getString(R.string.app_name), NotificationManager.IMPORTANCE_HIGH);
        mNotificationManager.createNotificationChannel(chan);
    }

    /**
     * Send broadcast message
     * @param broadcast Broadcast message
     */
    private void sendBroadcast(String broadcast) {
        Intent intent = new Intent(broadcast);
        sendBroadcast(intent);
    }

    private class mAccelerationListener implements  SensorEventListener {

        @Override
        public void onSensorChanged(SensorEvent event) {
            if (Logger.DEBUG) { Log.d(TAG, "[accelerometerFrequency: \t" + accelerometerFrequency + "]"); }

            if (accelerometerFrequency == 0) {
                return;
            }
            if ((event.timestamp / 1000) - mAccLast < (1000000 / accelerometerFrequency)) {
                return;
            }
            mAccLast = event.timestamp / 1000;
            if (Logger.DEBUG) { Log.d(TAG, "[accelerometer data tracked: \t" + event.values[0] + "\t" + event.values[1] + "\t" + event.values[2] + "]"); }
            if (db == null) {
                db = DbAccess.getInstance();
                db.open(getApplicationContext());
            }
            db.writeAcceleration(System.currentTimeMillis() / 1000, event.values[0], event.values[1], event.values[2]);
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {

        }
    }

    /**
     * Location listener class
     */
    private class mLocationListener implements LocationListener {

        @Override
        public void onLocationChanged(Location loc) {

            if (Logger.DEBUG) { Log.d(TAG, "[location changed: " + loc + "]"); }

            if (!skipLocation(loc)) {

                lastLocation = loc;
                lastUpdateRealtime = loc.getElapsedRealtimeNanos() / 1000000;

                IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                Intent battery = getApplicationContext().registerReceiver(null, ifilter);
                int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                String batteryStatus;
                if (status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL) {
                    batteryStatus = "charging";
                } else {
                    batteryStatus = "discharging";
                }
                int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                float batteryLevel = level * 100 / (float)scale;

                db.writeLocation(loc, batteryStatus, batteryLevel);
                sendBroadcast(BROADCAST_LOCATION_UPDATED);
                if (liveSync) {
                    startService(syncIntent);
                }
            }
        }

        /**
         * Should the location be logged or skipped
         * @param loc Location
         * @return True if skipped
         */
        private boolean skipLocation(Location loc) {
            // accuracy radius too high
            if (loc.hasAccuracy() && loc.getAccuracy() > maxAccuracy) {
                if (Logger.DEBUG) { Log.d(TAG, "[location accuracy above limit: " + loc.getAccuracy() + " > " + maxAccuracy + "]"); }
                // reset gps provider to get better accuracy even if time and distance criteria don't change
                if (loc.getProvider().equals(LocationManager.GPS_PROVIDER)) {
                    restartUpdates();
                }
                return true;
            }
            // use network provider only if recent gps data is missing
            if (loc.getProvider().equals(LocationManager.NETWORK_PROVIDER) && lastLocation != null) {
                // we received update from gps provider not later than after maxTime period
                long elapsedMillis = SystemClock.elapsedRealtime() - lastUpdateRealtime;
                if (lastLocation.getProvider().equals(LocationManager.GPS_PROVIDER) && elapsedMillis < maxTimeMillis) {
                    // skip network provider
                    if (Logger.DEBUG) { Log.d(TAG, "[location network provider skipped]"); }
                    return true;
                }
            }
            return false;
        }

        /**
         * Callback on provider disabled
         * @param provider Provider
         */
        @Override
        public void onProviderDisabled(String provider) {
            if (Logger.DEBUG) { Log.d(TAG, "[location provider " + provider + " disabled]"); }
            if (provider.equals(LocationManager.GPS_PROVIDER)) {
                sendBroadcast(BROADCAST_LOCATION_GPS_DISABLED);
            } else if (provider.equals(LocationManager.NETWORK_PROVIDER)) {
                sendBroadcast(BROADCAST_LOCATION_NETWORK_DISABLED);
            }
        }

        /**
         * Callback on provider enabled
         * @param provider Provider
         */
        @Override
        public void onProviderEnabled(String provider) {
            if (Logger.DEBUG) { Log.d(TAG, "[location provider " + provider + " enabled]"); }
            if (provider.equals(LocationManager.GPS_PROVIDER)) {
                sendBroadcast(BROADCAST_LOCATION_GPS_ENABLED);
            } else if (provider.equals(LocationManager.NETWORK_PROVIDER)) {
                sendBroadcast(BROADCAST_LOCATION_NETWORK_ENABLED);
            }
        }


        @SuppressWarnings({"deprecation", "RedundantSuppression"})
        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {

        }
    }
}
