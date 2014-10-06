/*
 * Copyright (C) 2012 The Android Open Source Project
 * This code has been modified. Portions copyright (C) 2013-2014 ParanoidAndroid Project.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.phone;

import android.animation.ValueAnimator;
import android.app.ActivityManager;
import android.app.ActivityManagerNative;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.media.MediaRouter;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.Profile;
import android.provider.Settings;
import android.security.KeyChain;
import android.telephony.TelephonyManager;
import android.provider.Settings.SettingNotFoundException;
import android.util.Log;
import android.util.SettingConfirmationHelper;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.WindowManagerGlobal;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.android.internal.app.MediaRouteDialogPresenter;
import com.android.internal.util.paranoid.LightbulbConstants;
import com.android.systemui.R;
import com.android.systemui.statusbar.phone.QuickSettingsModel.ActivityState;
import com.android.systemui.statusbar.phone.QuickSettingsModel.BluetoothState;
import com.android.systemui.statusbar.phone.QuickSettingsModel.RSSIState;
import com.android.systemui.statusbar.phone.QuickSettingsModel.State;
import com.android.systemui.statusbar.phone.QuickSettingsModel.UserState;
import com.android.systemui.statusbar.phone.QuickSettingsModel.WifiState;
import com.android.systemui.statusbar.policy.BatteryController;
import com.android.systemui.statusbar.policy.BluetoothController;
import com.android.systemui.statusbar.policy.LocationController;
import com.android.systemui.statusbar.policy.NetworkController;
import com.android.systemui.statusbar.policy.RotationLockController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 *
 */
class QuickSettings {
    static final boolean DEBUG_GONE_TILES = false;
    private static final String TAG = "QuickSettings";
    public static final boolean SHOW_IME_TILE = false;
    private static final String AUTO_START = "AUTO_START";
    private static final String TOGGLE_FLASHLIGHT = "TOGGLE_FLASHLIGHT";

    public enum Tile {
        USER,
        BRIGHTNESS,
        SETTINGS,
        WIFI,
        RSSI,
        ROTATION,
        BATTERY,
        AIRPLANE,
        BLUETOOTH,
        LOCATION,
        IMMERSIVE,
        LIGHTBULB,
        SLEEP,
        SOUND,
        ALARM,
        USB_MODE,
        REMOTE_DISPLAY,
        IME,
        BUGREPORT,
        SSL_CERT_WARNING
    }

    public static final String NO_TILES = "NO_TILES";
    public static final String DELIMITER = ";";
    public static final String DEFAULT_TILES = Tile.USER + DELIMITER + Tile.BRIGHTNESS
        + DELIMITER + Tile.SETTINGS + DELIMITER + Tile.WIFI + DELIMITER + Tile.RSSI
        + DELIMITER + Tile.ROTATION + DELIMITER + Tile.BATTERY + DELIMITER + Tile.BLUETOOTH
        + DELIMITER + Tile.LOCATION + DELIMITER + Tile.IMMERSIVE + DELIMITER + Tile.LIGHTBULB;

    private Context mContext;
    private PanelBar mBar;
    private QuickSettingsModel mModel;
    private ViewGroup mContainerView;

    private DevicePolicyManager mDevicePolicyManager;
    private PhoneStatusBar mStatusBarService;
    private BluetoothState mBluetoothState;
    private BluetoothAdapter mBluetoothAdapter;
    private WifiManager mWifiManager;
    private ConnectivityManager mConnectivityManager;

    private BluetoothController mBluetoothController;
    private RotationLockController mRotationLockController;
    private LocationController mLocationController;

    private AsyncTask<Void, Void, Pair<String, Drawable>> mUserInfoTask;
    private AsyncTask<Void, Void, Pair<Boolean, Boolean>> mQueryCertTask;

    boolean mTilesSetUp = false;
    boolean mUseDefaultAvatar = false;

    private Handler mHandler;
    private QuickSettingsBasicBatteryTile batteryTile;
    private int mBatteryStyle;

    private int mCurrentUserId = 0;

    public QuickSettings(Context context, QuickSettingsContainerView container) {
        mDevicePolicyManager
            = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        mContext = context;
        mContainerView = container;
        mModel = new QuickSettingsModel(context);
        mBluetoothState = new QuickSettingsModel.BluetoothState();
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        mConnectivityManager =
                   (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);

        mHandler = new Handler();

        IntentFilter filter = new IntentFilter();
        filter.addAction(DisplayManager.ACTION_WIFI_DISPLAY_STATUS_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(Intent.ACTION_USER_SWITCHED);
        filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
        filter.addAction(KeyChain.ACTION_STORAGE_CHANGED);
        mContext.registerReceiver(mReceiver, filter);

        IntentFilter profileFilter = new IntentFilter();
        profileFilter.addAction(ContactsContract.Intents.ACTION_PROFILE_CHANGED);
        profileFilter.addAction(Intent.ACTION_USER_INFO_CHANGED);
        mContext.registerReceiverAsUser(mProfileReceiver, UserHandle.ALL, profileFilter,
                null, null);

        mCurrentUserId = ActivityManager.getCurrentUser();
    }

    void setBar(PanelBar bar) {
        mBar = bar;
    }

    public void setService(PhoneStatusBar phoneStatusBar) {
        mStatusBarService = phoneStatusBar;
    }

    public PhoneStatusBar getService() {
        return mStatusBarService;
    }

    public void setImeWindowStatus(boolean visible) {
        mModel.onImeWindowStatusChanged(visible);
    }

    void setup(NetworkController networkController, BluetoothController bluetoothController,
            BatteryController batteryController, LocationController locationController,
            RotationLockController rotationLockController) {

        // shutdown controllers
        shutdown(networkController, bluetoothController,
                 batteryController,locationController,
                 rotationLockController);

        // setup controllers
        mBluetoothController = bluetoothController;
        mRotationLockController = rotationLockController;
        mLocationController = locationController;

        setupQuickSettings();
        updateResources();
        applyLocationEnabledStatus();

        networkController.addNetworkSignalChangedCallback(mModel);
        bluetoothController.addStateChangedCallback(mModel);
        batteryController.addStateChangedCallback(mModel);
        locationController.addSettingsChangedCallback(mModel);
        rotationLockController.addRotationLockControllerCallback(mModel);
    }

    private void queryForSslCaCerts() {
        mQueryCertTask = new AsyncTask<Void, Void, Pair<Boolean, Boolean>>() {
            @Override
            protected Pair<Boolean, Boolean> doInBackground(Void... params) {
                boolean hasCert = DevicePolicyManager.hasAnyCaCertsInstalled();
                boolean isManaged = mDevicePolicyManager.getDeviceOwner() != null;

                return Pair.create(hasCert, isManaged);
            }
            @Override
            protected void onPostExecute(Pair<Boolean, Boolean> result) {
                super.onPostExecute(result);
                boolean hasCert = result.first;
                boolean isManaged = result.second;
                mModel.setSslCaCertWarningTileInfo(hasCert, isManaged);
            }
        };
        mQueryCertTask.execute();
    }

    private void queryForUserInformation() {
        Context currentUserContext = null;
        UserInfo userInfo = null;
        try {
            userInfo = ActivityManagerNative.getDefault().getCurrentUser();
            currentUserContext = mContext.createPackageContextAsUser("android", 0,
                    new UserHandle(userInfo.id));
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Couldn't create user context", e);
            throw new RuntimeException(e);
        } catch (RemoteException e) {
            Log.e(TAG, "Couldn't get user info", e);
        }
        final int userId = userInfo.id;
        final String userName = userInfo.name;

        final Context context = currentUserContext;
        mUserInfoTask = new AsyncTask<Void, Void, Pair<String, Drawable>>() {
            @Override
            protected Pair<String, Drawable> doInBackground(Void... params) {
                final UserManager um = UserManager.get(mContext);

                // Fall back to the UserManager nickname if we can't read the name from the local
                // profile below.
                String name = userName;
                Drawable avatar = null;
                Bitmap rawAvatar = um.getUserIcon(userId);
                if (rawAvatar != null) {
                    avatar = new BitmapDrawable(mContext.getResources(), rawAvatar);
                } else {
                    avatar = mContext.getResources().getDrawable(R.drawable.ic_qs_default_user);
                    mUseDefaultAvatar = true;
                }

                // If it's a single-user device, get the profile name, since the nickname is not
                // usually valid
                if (um.getUsers().size() <= 1) {
                    // Try and read the display name from the local profile
                    final Cursor cursor = context.getContentResolver().query(
                            Profile.CONTENT_URI, new String[] {Phone._ID, Phone.DISPLAY_NAME},
                            null, null, null);
                    if (cursor != null) {
                        try {
                            if (cursor.moveToFirst()) {
                                name = cursor.getString(cursor.getColumnIndex(Phone.DISPLAY_NAME));
                            }
                        } finally {
                            cursor.close();
                        }
                    }
                }
                return new Pair<String, Drawable>(name, avatar);
            }

            @Override
            protected void onPostExecute(Pair<String, Drawable> result) {
                super.onPostExecute(result);
                mModel.setUserTileInfo(result.first, result.second);
                mUserInfoTask = null;
            }
        };
        mUserInfoTask.execute();
    }

    public void shutdown(NetworkController networkController, BluetoothController bluetoothController,
                         BatteryController batteryController, LocationController locationController,
                         RotationLockController rotationLockController) {
        networkController.removeNetworkSignalChangedCallback(mModel);
        bluetoothController.removeStateChangedCallback(mModel);
        batteryController.removeStateChangedCallback(mModel);
        locationController.removeSettingsChangedCallback(mModel);
        rotationLockController.removeRotationLockControllerCallback(mModel);

        mContainerView.removeAllViews();
    }

    private void setupQuickSettings() {
        addTiles(mContainerView, false);
        addTemporaryTiles(mContainerView);

        queryForUserInformation();
        queryForSslCaCerts();
        mTilesSetUp = true;
    }

    private void startSettingsActivity(String action) {
        Intent intent = new Intent(action);
        startSettingsActivity(intent);
    }

    private void startSettingsActivity(Intent intent) {
        startSettingsActivity(intent, true);
    }

    private void collapsePanels() {
        getService().animateCollapsePanels();
    }

    private void startSettingsActivity(Intent intent, boolean onlyProvisioned) {
        if (onlyProvisioned && !getService().isDeviceProvisioned()) return;
        try {
            // Dismiss the lock screen when Settings starts.
            ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
        } catch (RemoteException e) {
        }
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mContext.startActivityAsUser(intent, new UserHandle(UserHandle.USER_CURRENT));
        collapsePanels();
    }
    
    public void updateBattery() {
        if (batteryTile == null || mModel == null) {
            return;
        }
        mBatteryStyle = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.STATUS_BAR_BATTERY_STYLE, 0, UserHandle.USER_CURRENT);
        batteryTile.updateBatterySettings();
        mModel.refreshBatteryTile();
    }

    private boolean immsersiveStyleSelected() {
        int selection = Settings.System.getIntForUser(mContext.getContentResolver(),
                            Settings.System.PIE_STATE, 0, UserHandle.USER_CURRENT);
        return selection == 1 || selection == 2;
    }

    private void addTiles(ViewGroup parent, boolean addMissing) {
        // Load all the customizable tiles. If not yet modified by the user, load default ones.
        // After enabled tiles are loaded, proceed to load missing tiles and set them to View.GONE.
        // If all the tiles were deleted, they are still loaded, but their visibility is changed
        String tileContainer = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.QUICK_SETTINGS_TILES, UserHandle.USER_CURRENT);
        if(tileContainer == null) tileContainer = DEFAULT_TILES;
        Tile[] allTiles = Tile.values();
        String[] storedTiles = tileContainer.split(DELIMITER);
        List<String> allTilesArray = enumToStringArray(allTiles);
        List<String> storedTilesArray = Arrays.asList(storedTiles);

        for(String tile : addMissing ? allTilesArray : storedTilesArray) {
            boolean addTile = storedTilesArray.contains(tile);
            if(addMissing) addTile = !addTile;
            if(addTile) {
                if(Tile.USER.toString().equals(tile.toString())) { // User tile
                    final QuickSettingsBasicUserTile userTile
                        = new QuickSettingsBasicUserTile(mContext);
                    userTile.setTileId(Tile.USER);
                    userTile.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            collapsePanels();
                            final UserManager um = UserManager.get(mContext);
                            if (um.getUsers(true).size() > 1) {
                                try {
                                    WindowManagerGlobal.getWindowManagerService().lockNow(null);
                                } catch (RemoteException e) {
                                    Log.e(TAG, "Couldn't show user switcher", e);
                                }
                            } else {
                                Intent intent = ContactsContract.QuickContact
                                        .composeQuickContactsIntent(mContext, v,
                                        ContactsContract.Profile.CONTENT_URI,
                                        ContactsContract.QuickContact.MODE_LARGE, null);
                                mContext.startActivityAsUser(intent,
                                        new UserHandle(UserHandle.USER_CURRENT));
                            }
                        }
                    });
                    userTile.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            collapsePanels();
                            startSettingsActivity(
                                    android.provider.Settings.ACTION_SYNC_SETTINGS);
                            return true; // Consume click
                        }
                    });
                    mModel.addUserTile(userTile, new QuickSettingsModel.RefreshCallback() {
                        @Override
                        public void refreshView(QuickSettingsTileView view, State state) {
                            UserState us = (UserState) state;
                            userTile.setImageDrawable(us.avatar);
                            userTile.setText(state.label);
                            userTile.setContentDescription(mContext.getString(
                                    R.string.accessibility_quick_settings_user, state.label));
                        }
                    });
                    parent.addView(userTile);
                    if(addMissing) userTile.setVisibility(View.GONE);
                } else if(Tile.BRIGHTNESS.toString().equals(tile.toString())) { // Brightness tile
                    final QuickSettingsBasicTile brightnessTile
                            = new QuickSettingsBasicTile(mContext);
                    brightnessTile.setTileId(Tile.BRIGHTNESS);
                    brightnessTile.setImageResource(R.drawable.ic_qs_brightness_auto_off);
                    brightnessTile.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            collapsePanels();
                            showBrightnessDialog();
                        }
                    });
                    brightnessTile.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            boolean automaticAvailable = mContext.getResources().getBoolean(
                                    com.android.internal.R.bool.config_automatic_brightness_available);
                            // If we have automatic brightness available, toggle it
                            if (automaticAvailable) {
                                int automatic;
                                try {
                                    automatic = Settings.System.getIntForUser(mContext.getContentResolver(),
                                            Settings.System.SCREEN_BRIGHTNESS_MODE,
                                            UserHandle.USER_CURRENT);
                                } catch (SettingNotFoundException snfe) {
                                    automatic = 0;
                                }
                                Settings.System.putIntForUser(mContext.getContentResolver(),
                                        Settings.System.SCREEN_BRIGHTNESS_MODE, automatic != 0 ? 0 : 1,
                                        UserHandle.USER_CURRENT);
                            }
                            return true; // Consume click
                        }
                    });
                    mModel.addBrightnessTile(brightnessTile,
                            new QuickSettingsModel.BasicRefreshCallback(brightnessTile));
                    parent.addView(brightnessTile);
                    if(addMissing) brightnessTile.setVisibility(View.GONE);
                } else if(Tile.SETTINGS.toString().equals(tile.toString())) { // Settings tile
                    final QuickSettingsBasicTile settingsTile
                            = new QuickSettingsBasicTile(mContext);
                    settingsTile.setTileId(Tile.SETTINGS);
                    settingsTile.setImageResource(R.drawable.ic_qs_settings);
                    settingsTile.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            collapsePanels();
                            startSettingsActivity(
                                    android.provider.Settings.ACTION_SETTINGS);
                        }
                    });
                    mModel.addSettingsTile(settingsTile,
                            new QuickSettingsModel.BasicRefreshCallback(settingsTile));
                    parent.addView(settingsTile);
                    if(addMissing) settingsTile.setVisibility(View.GONE);
                } else if(Tile.WIFI.toString().equals(tile.toString())) { // Wi-fi tile
                    final QuickSettingsDualWifiTile wifiTile
                            = new QuickSettingsDualWifiTile(mContext);
                    wifiTile.setTileId(Tile.WIFI);
                    // Front side (Turn on/off wifi connection)
                    wifiTile.setFrontOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            final boolean enable =
                                    (mWifiManager.getWifiState() !=
                                            WifiManager.WIFI_STATE_ENABLED);
                            new AsyncTask<Void, Void, Void>() {
                                @Override
                                protected Void doInBackground(Void... args) {
                                    // Disable tethering if enabling Wifi
                                    final int wifiApState = mWifiManager.getWifiApState();
                                    if (enable &&
                                            ((wifiApState == WifiManager.WIFI_AP_STATE_ENABLING) ||
                                            (wifiApState == WifiManager.WIFI_AP_STATE_ENABLED))) {
                                        mWifiManager.setWifiApEnabled(null, false);
                                    }

                                    mWifiManager.setWifiEnabled(enable);
                                    return null;
                                }
                            }.execute();
                        }
                    });
                    wifiTile.setFrontOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            collapsePanels();
                            startSettingsActivity(
                                    android.provider.Settings.ACTION_WIFI_SETTINGS);
                            return true; // Consume click
                        }
                    });
                    mModel.addWifiTile(wifiTile.getFront(), new NetworkActivityCallback() {
                        @Override
                        public void refreshView(QuickSettingsTileView view, State state) {
                            WifiState wifiState = (WifiState) state;
                            wifiTile.setFrontImageResource(wifiState.iconId);
                            setActivity(view, wifiState);
                            wifiTile.setFrontText(wifiState.label);
                            wifiTile.setFrontContentDescription(mContext.getString(
                                    R.string.accessibility_quick_settings_wifi,
                                    wifiState.signalContentDescription,
                                    (wifiState.connected) ? wifiState.label : ""));
                        }
                    });
                    // Back side (Turn on/off wifi AP)
                    wifiTile.setBackOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            mModel.toggleWifiApState();
                        }
                    });
                    wifiTile.setBackOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            collapsePanels();
                            Intent intent = new Intent(Intent.ACTION_MAIN);
                            intent.setClassName("com.android.settings", "com.android.settings.TetherSettings");
                            startSettingsActivity(intent);
                            return true; // Consume click
                        }
                    });
                    mModel.addWifiApTile(wifiTile.getBack(), new QuickSettingsModel.RefreshCallback() {
                        @Override
                        public void refreshView(QuickSettingsTileView unused, State state) {
                            wifiTile.setBackImageResource(state.iconId);
                            wifiTile.setBackText(state.label);
                        }
                    });
                    parent.addView(wifiTile);
                    if(addMissing) wifiTile.setVisibility(View.GONE);
                } else if(Tile.RSSI.toString().equals(tile.toString())) { // RSSI tile
                    if (mModel.deviceHasMobileData()) {
                        final QuickSettingsDualRssiTile rssiTile
                            = new QuickSettingsDualRssiTile(mContext);
                        rssiTile.setTileId(Tile.RSSI);
                        // Front side (Turn on/off data)
                        rssiTile.setFrontText(mContext.getString(R.string.quick_settings_network_type));
                        rssiTile.setFrontOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                boolean currentState = mConnectivityManager.getMobileDataEnabled();
                                mConnectivityManager.setMobileDataEnabled(!currentState);
                                mModel.refreshRssiTile();
                            }
                        });
                        rssiTile.setFrontOnLongClickListener(new View.OnLongClickListener() {
                            @Override
                            public boolean onLongClick(View v) {
                                collapsePanels();
                                Intent intent = new Intent();
                                intent.setComponent(new ComponentName("com.android.settings",
                                        "com.android.settings.Settings$DataUsageSummaryActivity"));
                                startSettingsActivity(intent);
                                return true; // Consume click
                            }
                        });
                        mModel.addRSSITile(rssiTile.getFront(), new NetworkActivityCallback() {
                            @Override
                            public void refreshView(QuickSettingsTileView view, State state) {
                                RSSIState rssiState = (RSSIState) state;
                                // Force refresh
                                rssiTile.setFrontImageDrawable(null);
                                rssiTile.setFrontImageResource(rssiState.signalIconId);

                                if (rssiState.dataTypeIconId > 0) {
                                    rssiTile.setFrontImageOverlayResource(rssiState.dataTypeIconId);
                                } else if (!mModel.isMobileDataEnabled(mContext)) {
                                    rssiTile.setFrontImageOverlayResource(R.drawable.ic_qs_signal_data_off);
                                } else {
                                    rssiTile.setFrontImageOverlayDrawable(null);
                                }

                                setActivity(view, rssiState);

                                rssiTile.setFrontText(state.label);
                                rssiTile.setFrontContentDescription(mContext.getResources().getString(
                                        R.string.accessibility_quick_settings_mobile,
                                        rssiState.signalContentDescription,
                                        rssiState.dataContentDescription,
                                        state.label));
                            }
                        });
                        // Back side (Mobile networks modes)
                        if (mModel.mUsesAospDialer) {
                            rssiTile.setBackTextResource(R.string.quick_settings_network_unknown);
                        } else {
                            rssiTile.setBackTextResource(R.string.quick_settings_network_disabled);
                        }
                        rssiTile.setBackImageResource(R.drawable.ic_qs_unexpected_network);
                        rssiTile.setBackOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (mModel.mUsesAospDialer) {
                                    mModel.toggleMobileNetworkState();
                                } else {
                                    collapsePanels();
                                    Toast.makeText(mContext,
                                                   R.string.quick_settings_network_toast_disabled,
                                                   Toast.LENGTH_SHORT).show();
                                }
                            }
                        });
                        rssiTile.setBackOnLongClickListener(new View.OnLongClickListener() {
                            @Override
                            public boolean onLongClick(View v) {
                                Intent intent = new Intent(Intent.ACTION_MAIN);
                                intent.setClassName("com.android.phone", "com.android.phone.Settings");
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startSettingsActivity(intent);
                                return true;
                            }
                        });
                        mModel.addMobileNetworkTile(rssiTile.getBack(), new QuickSettingsModel.RefreshCallback() {
                            @Override
                            public void refreshView(QuickSettingsTileView unused, State mobileNetworkState) {
                                rssiTile.setBackImageResource(mobileNetworkState.iconId);
                                rssiTile.setBackText(mobileNetworkState.label);
                            }
                        });
                        parent.addView(rssiTile);
                        if(addMissing) rssiTile.setVisibility(View.GONE);
                    }
                } else if(Tile.ROTATION.toString().equals(tile.toString())) { // Rotation Lock Tile
                    if (mContext.getResources()
                            .getBoolean(R.bool.quick_settings_show_rotation_lock)
                                    || DEBUG_GONE_TILES) {
                        final QuickSettingsBasicTile rotationLockTile
                            = new QuickSettingsBasicTile(mContext);
                        rotationLockTile.setTileId(Tile.ROTATION);
                        rotationLockTile.setOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View view) {
                                final boolean locked = mRotationLockController.isRotationLocked();
                                mRotationLockController.setRotationLocked(!locked);
                            }
                        });
                        rotationLockTile.setOnLongClickListener(new View.OnLongClickListener() {
                            @Override
                            public boolean onLongClick(View v) {
                                collapsePanels();
                                startSettingsActivity(
                                        android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS);
                                return true; // Consume click
                            }
                        });
                        mModel.addRotationLockTile(rotationLockTile, mRotationLockController,
                                new QuickSettingsModel.RefreshCallback() {
                                    @Override
                                    public void refreshView(QuickSettingsTileView view,
                                            State state) {
                                        QuickSettingsModel.RotationLockState rotationLockState =
                                                (QuickSettingsModel.RotationLockState) state;
                                        // always enabled
                                        view.setEnabled(true);
                                        if (state.iconId != 0) {
                                            // needed to flush any cached IDs
                                            rotationLockTile.setImageDrawable(null);
                                            rotationLockTile.setImageResource(state.iconId);
                                        }
                                        if (state.label != null) {
                                            rotationLockTile.setText(state.label);
                                        }
                                    }
                                });
                        parent.addView(rotationLockTile);
                        if(addMissing) rotationLockTile.setVisibility(View.GONE);
                    }
                } else if(Tile.BATTERY.toString().equals(tile.toString())) { // Battery tile
                    batteryTile = new QuickSettingsBasicBatteryTile(mContext);
                    batteryTile.setTileId(Tile.BATTERY);
                    updateBattery();
                    batteryTile.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            collapsePanels();
                            startSettingsActivity(Intent.ACTION_POWER_USAGE_SUMMARY);
                        }
                    });
                    mModel.addBatteryTile(batteryTile, new QuickSettingsModel.RefreshCallback() {
                        @Override
                        public void refreshView(QuickSettingsTileView unused, State state) {
                            QuickSettingsModel.BatteryState batteryState =
                                    (QuickSettingsModel.BatteryState) state;
                            String t;
                            if (batteryState.batteryLevel == 100) {
                                t = mContext.getString(
                                        R.string.quick_settings_battery_charged_label);
                            } else {
                                if (batteryState.pluggedIn) {
                                    t = mBatteryStyle != 3 // circle percent
                                        ? mContext.getString(R.string.quick_settings_battery_charging_label,
                                            batteryState.batteryLevel)
                                        : mContext.getString(R.string.quick_settings_battery_charging);
                                } else {     // battery bar or battery circle
                                    t = (mBatteryStyle == 0 || mBatteryStyle == 2)
                                        ? mContext.getString(R.string.status_bar_settings_battery_meter_format,
                                            batteryState.batteryLevel)
                                        : mContext.getString(R.string.quick_settings_battery_discharging);
                                }
                            }
                            batteryTile.setText(t);
                            batteryTile.setContentDescription(mContext.getString(
                                    R.string.accessibility_quick_settings_battery, t));
                        }
                    });
                    parent.addView(batteryTile);
                    if(addMissing) batteryTile.setVisibility(View.GONE);
                } else if(Tile.AIRPLANE.toString().equals(tile.toString())) { // Airplane Mode tile
                    final QuickSettingsBasicTile airplaneTile
                            = new QuickSettingsBasicTile(mContext);
                    airplaneTile.setTileId(Tile.AIRPLANE);
                    mModel.addAirplaneModeTile(airplaneTile,
                            new QuickSettingsModel.RefreshCallback() {
                        @Override
                        public void refreshView(QuickSettingsTileView unused, State state) {
                            airplaneTile.setImageResource(state.iconId);

                            String airplaneState = mContext.getString(
                                    (state.enabled) ? R.string.accessibility_desc_on
                                            : R.string.accessibility_desc_off);
                            airplaneTile.setContentDescription(
                                    mContext.getString(
                                            R.string.accessibility_quick_settings_airplane,
                                            airplaneState));
                            airplaneTile.setText(state.label);
                        }
                    });
                    parent.addView(airplaneTile);
                    if(addMissing) airplaneTile.setVisibility(View.GONE);
                } else if(Tile.BLUETOOTH.toString().equals(tile.toString())) { // Bluetooth tile
                    if (mModel.deviceSupportsBluetooth()
                            || DEBUG_GONE_TILES) {
                        final QuickSettingsDualBasicTile bluetoothTile
                            = new QuickSettingsDualBasicTile(mContext);
                        bluetoothTile.setDefaultContent();
                        bluetoothTile.setTileId(Tile.BLUETOOTH);
                        // Front side (Turn on/off bluetooth)
                        bluetoothTile.setFrontOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                if (mBluetoothAdapter.isEnabled()) {
                                    mBluetoothAdapter.disable();
                                } else {
                                    mBluetoothAdapter.enable();
                                }
                            }
                        });
                        bluetoothTile.setFrontOnLongClickListener(new View.OnLongClickListener() {
                            @Override
                            public boolean onLongClick(View v) {
                                collapsePanels();
                                startSettingsActivity(
                                        android.provider.Settings.ACTION_BLUETOOTH_SETTINGS);

                                return true; // Consume click
                            }
                        });
                        // Back side (Toggle discoverability)
                        bluetoothTile.setBackOnClickListener(new View.OnClickListener() {
                            @Override
                            public void onClick(View v) {
                                // instead of just returning, assume user wants to turn on bluetooth
                                if (!mBluetoothAdapter.isEnabled()) {
                                    bluetoothTile.swapTiles(true);
                                    return;
                                }
                                if (mBluetoothAdapter.getScanMode()
                                        != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                                    mBluetoothAdapter.setScanMode(
                                            BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE, 300);
                                    bluetoothTile.setBackImageResource(
                                            R.drawable.ic_qs_bluetooth_discoverable);
                                    bluetoothTile.setBackText(mContext.getString(
                                            R.string.quick_settings_bluetooth_discoverable_label));
                                } else {
                                    mBluetoothAdapter.setScanMode(
                                            BluetoothAdapter.SCAN_MODE_CONNECTABLE, 300);
                                    bluetoothTile.setBackImageResource(
                                            R.drawable.ic_qs_bluetooth_discoverable_off);
                                    bluetoothTile.setBackText(mContext.getString(
                                            R.string.quick_settings_bluetooth_not_discoverable_label));
                                }
                            }
                        });
                        mModel.addBluetoothTile(bluetoothTile.getFront(),
                                new QuickSettingsModel.RefreshCallback() {
                            @Override
                            public void refreshView(QuickSettingsTileView unused, State state) {
                                BluetoothState bluetoothState = (BluetoothState) state;
                                bluetoothTile.setFrontImageResource(state.iconId);
                                bluetoothTile.setFrontContentDescription(mContext.getString(
                                        R.string.accessibility_quick_settings_bluetooth,
                                        bluetoothState.stateContentDescription));
                                bluetoothTile.setFrontText(state.label);
                            }
                        });
                        mModel.addBluetoothExtraTile(bluetoothTile.getBack(), new QuickSettingsModel.RefreshCallback() {
                            @Override
                            public void refreshView(QuickSettingsTileView unused, State state) {
                                BluetoothState bluetoothState = (BluetoothState) state;
                                bluetoothTile.setBackImageResource(state.iconId);
                                bluetoothTile.setBackContentDescription(mContext.getString(
                                            R.string.accessibility_quick_settings_bluetooth,
                                            bluetoothState.stateContentDescription));
                                bluetoothTile.setBackText(state.label);
                                if (mBluetoothAdapter.getScanMode()
                                    == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
                                    bluetoothTile.setBackImageResource(R.drawable.ic_qs_bluetooth_discoverable);
                                    bluetoothTile.setBackText(
                                            mContext.getString(R.string.quick_settings_bluetooth_discoverable_label));
                                } else {
                                    bluetoothTile.setBackImageResource(R.drawable.ic_qs_bluetooth_discoverable_off);
                                    bluetoothTile.setBackText(
                                            mContext.getString(R.string.quick_settings_bluetooth_not_discoverable_label));
                                }
                            }
                        });
                        parent.addView(bluetoothTile);
                        if(addMissing) bluetoothTile.setVisibility(View.GONE);
                    }
                } else if(Tile.LOCATION.toString().equals(tile.toString())) { // Location tile
                    final QuickSettingsDualBasicTile locationTile
                            = new QuickSettingsDualBasicTile(mContext);
                    locationTile.setDefaultContent();
                    locationTile.setTileId(Tile.LOCATION);
                    // Front side (Turn on/off location services)
                    locationTile.setFrontImageResource(R.drawable.ic_qs_location_on);
                    locationTile.setFrontTextResource(R.string.quick_settings_location_label);
                    locationTile.setFrontOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            boolean newLocationEnabledState
                                    = !mLocationController.isLocationEnabled();
                            if (mLocationController.setLocationEnabled(newLocationEnabledState)
                                    && newLocationEnabledState) {
                                // If we've successfully switched from location off to on, close
                                // the notifications tray to show the network location provider
                                // consent dialog.
                                Intent closeDialog
                                        = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
                                mContext.sendBroadcast(closeDialog);
                            }
                        }
                    });
                    locationTile.setFrontOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            collapsePanels();
                            startSettingsActivity(
                                    android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS);
                            return true; // Consume click
                        }
                    });
                    mModel.addLocationTile(locationTile.getFront(),
                            new QuickSettingsModel.RefreshCallback() {
                        @Override
                        public void refreshView(QuickSettingsTileView unused, State state) {
                            locationTile.setFrontImageResource(state.iconId);
                            locationTile.setFrontText(state.label);
                        }
                    });
                    // Back side (Toggle location services accuracy)
                    locationTile.setBackOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if(!mLocationController.isLocationEnabled()) {
                                locationTile.swapTiles(true);
                                return;
                            }
                            int newLocationMode = mLocationController.locationMode();
                            if (mLocationController.isLocationEnabled()) {
                                if (mLocationController.setBackLocationEnabled(newLocationMode)) {
                                    if (mLocationController.isLocationAllowPanelCollapse()) {
                                        Intent closeDialog = new Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
                                        mContext.sendBroadcast(closeDialog);
                                    }
                                }
                            }
                        }} );
                    mModel.addLocationExtraTile(locationTile.getBack(), mLocationController,
                            new QuickSettingsModel.RefreshCallback() {
                        @Override
                        public void refreshView(QuickSettingsTileView unused, State state) {
                            locationTile.setBackImageResource(state.iconId);
                            locationTile.setBackText(state.label);
                        }
                    });
                    parent.addView(locationTile);
                    if(addMissing) locationTile.setVisibility(View.GONE);
                } else if(Tile.IMMERSIVE.toString().equals(tile.toString())) { // Immersive mode tile
                    final QuickSettingsDualBasicTile immersiveTile
                            = new QuickSettingsDualBasicTile(mContext);
                    immersiveTile.setDefaultContent();
                    immersiveTile.setTileId(Tile.IMMERSIVE);
                    // Front side (Toggles global immersive state On/Off)
                    immersiveTile.setFrontImageResource(R.drawable.ic_qs_immersive_global_off);
                    immersiveTile.setFrontTextResource(R.string.quick_settings_immersive_global_off_label);
                    immersiveTile.setFrontOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (!immsersiveStyleSelected() && mModel.getImmersiveMode() == 0) {
                                // reset on the spot value to 0 if is set to ASK_LATER
                                // so pie observer detects the change and switches to immersive even on more
                                // ask later choices
                                Settings.System.putIntForUser(mContext.getContentResolver(),
                                        Settings.System.PIE_STATE, 0, mCurrentUserId);
                                // launch on the spot dialog
                                selectImmersiveStyle();
                            } else {
                                mModel.switchImmersiveGlobal();
                                mModel.refreshImmersiveGlobalTile();
                            }
                        }
                    });
                    mModel.addImmersiveGlobalTile(immersiveTile.getFront(),
                            new QuickSettingsModel.RefreshCallback() {
                        @Override
                        public void refreshView(QuickSettingsTileView unused, State state) {
                            immersiveTile.setFrontImageResource(state.iconId);
                            immersiveTile.setFrontText(state.label);
                        }
                    });
                    // Back side (Toggles active immersive modes if global is on)
                    immersiveTile.setBackImageResource(R.drawable.ic_qs_immersive_off);
                    immersiveTile.setBackTextResource(R.string.quick_settings_immersive_mode_off_label);
                    immersiveTile.setBackOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            // instead of just returning, assume user wants to turn on/off immersive
                            if(mModel.getImmersiveMode() == 0 || mModel.getImmersiveMode() == 4) {
                                immersiveTile.swapTiles(true);
                                return;
                            }
                            mModel.switchImmersiveMode();
                            mModel.refreshImmersiveModeTile();
                        }
                    });
                    mModel.addImmersiveModeTile(immersiveTile.getBack(), new QuickSettingsModel.RefreshCallback() {
                        @Override
                        public void refreshView(QuickSettingsTileView unused, State state) {
                            immersiveTile.setBackImageResource(state.iconId);
                            immersiveTile.setBackText(state.label);
                        }
                    });
                    parent.addView(immersiveTile);
                    if(addMissing) immersiveTile.setVisibility(View.GONE);
                } else if(Tile.LIGHTBULB.toString().equals(tile.toString())) { // Lightbulb tile
                    final QuickSettingsBasicTile lightbulbTile
                            = new QuickSettingsBasicTile(mContext);
                    lightbulbTile.setTileId(Tile.LIGHTBULB);
                    lightbulbTile.setImageResource(R.drawable.ic_qs_lightbulb_on);
                    lightbulbTile.setTextResource(R.string.quick_settings_lightbulb_label);
                    lightbulbTile.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            if (!mModel.mLightbulbActive && !mModel.deviceHasCameraFlash()) {
                                collapsePanels();
                                startSettingsActivity(LightbulbConstants.INTENT_LAUNCH_APP);
                            }
                            Intent intent = new Intent(TOGGLE_FLASHLIGHT);
                            intent.putExtra(AUTO_START, true);
                            mContext.sendBroadcast(intent);
                        }
                    });
                    lightbulbTile.setOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            collapsePanels();
                            startSettingsActivity(LightbulbConstants.INTENT_LAUNCH_APP);
                            return true; // consume click
                        }
                    });
                    mModel.addLightbulbTile(lightbulbTile, new QuickSettingsModel.RefreshCallback() {
                        @Override
                        public void refreshView(QuickSettingsTileView unused, State state) {
                            lightbulbTile.setImageResource(state.iconId);
                            lightbulbTile.setText(state.label);
                        }
                    });
                    parent.addView(lightbulbTile);
                    if(addMissing) lightbulbTile.setVisibility(View.GONE);
                } else if(Tile.SLEEP.toString().equals(tile.toString())) { // Sleep tile
                    final PowerManager pm = (PowerManager) mContext.getSystemService(Context.POWER_SERVICE);
                    final QuickSettingsDualBasicTile sleepTile
                            = new QuickSettingsDualBasicTile(mContext);
                    sleepTile.setDefaultContent();
                    sleepTile.setTileId(Tile.SLEEP);
                    // Front side (Put device into sleep mode)
                    sleepTile.setFrontImageResource(R.drawable.ic_qs_sleep_action);
                    sleepTile.setFrontTextResource(R.string.quick_settings_sleep_action_label);
                    sleepTile.setFrontOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            pm.goToSleep(SystemClock.uptimeMillis());
                        }
                    });
                    sleepTile.setFrontOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            collapsePanels();
                            Intent intent = new Intent(Intent.ACTION_POWERMENU);
                            mContext.sendBroadcast(intent);
                            return true; // Consume click
                        }
                    });
                    // Back side (Toggle screen off timeout)
                    mModel.addSleepTimeTile(sleepTile.getBack(), new QuickSettingsModel.RefreshCallback() {
                        @Override
                        public void refreshView(QuickSettingsTileView view, State state) {
                            sleepTile.setBackImageResource(state.iconId);
                            sleepTile.setBackText(state.label);
                        }
                    });
                    sleepTile.setBackOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            collapsePanels();
                            startSettingsActivity(android.provider.Settings.ACTION_DISPLAY_SETTINGS);
                            return true;
                        }
                    });
                    parent.addView(sleepTile);
                    if(addMissing) sleepTile.setVisibility(View.GONE);
                // Sound tile
                } else if(Tile.SOUND.toString().equals(tile.toString())) {
                    final QuickSettingsDualBasicTile soundTile
                            = new QuickSettingsDualBasicTile(mContext);
                    soundTile.setDefaultContent();
                    soundTile.setTileId(Tile.SOUND);
                    // Front side (Ringer tile)
                    soundTile.setFrontImageResource(R.drawable.ic_qs_ringer_normal);
                    soundTile.setFrontTextResource(R.string.quick_settings_ringer_mode_normal_label);
                    soundTile.setFrontOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            mModel.switchRingerMode();
                            mModel.refreshRingerModeTile();
                        }
                    });
                    soundTile.setFrontOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            collapsePanels();
                            startSettingsActivity(android.provider.Settings.ACTION_SOUND_SETTINGS);
                            return true;
                        }
                    });
                    mModel.addRingerModeTile(soundTile.getFront(), new QuickSettingsModel.RefreshCallback() {
                        @Override
                        public void refreshView(QuickSettingsTileView unused, State state) {
                            soundTile.setFrontImageResource(state.iconId);
                            soundTile.setFrontText(state.label);
                        }
                    });
                    // Back side (Volume tile)
                    soundTile.setBackImageResource(R.drawable.ic_qs_volume);
                    soundTile.setBackTextResource(R.string.quick_settings_volume_label);
                    soundTile.setBackOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            collapsePanels();
                            AudioManager am = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
                            am.adjustVolume(AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI);
                        }
                    });
                    soundTile.setBackOnLongClickListener(new View.OnLongClickListener() {
                        @Override
                        public boolean onLongClick(View v) {
                            collapsePanels();
                            startSettingsActivity(android.provider.Settings.ACTION_SOUND_SETTINGS);
                            return true;
                        }
                    });
                    parent.addView(soundTile);
                    if(addMissing) soundTile.setVisibility(View.GONE);
                }
            }
        }
        if(!addMissing) addTiles(parent, true);
    }

    private void addTemporaryTiles(final ViewGroup parent) {
        // Alarm tile
        final QuickSettingsBasicTile alarmTile
                = new QuickSettingsBasicTile(mContext);
        alarmTile.setTemporary(true);
        alarmTile.setTileId(Tile.ALARM);
        alarmTile.setImageResource(R.drawable.ic_qs_alarm_on);
        alarmTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startSettingsActivity(new Intent(AlarmClock.ACTION_SHOW_ALARMS));
            }
        });
        mModel.addAlarmTile(alarmTile, new QuickSettingsModel.RefreshCallback() {
            @Override
            public void refreshView(QuickSettingsTileView unused, State alarmState) {
                alarmTile.setText(alarmState.label);
                alarmTile.setVisibility(alarmState.enabled ? View.VISIBLE : View.GONE);
                alarmTile.setContentDescription(mContext.getString(
                        R.string.accessibility_quick_settings_alarm, alarmState.label));
            }
        });
        parent.addView(alarmTile);

        // Usb Mode
        final QuickSettingsBasicTile usbModeTile
                = new QuickSettingsBasicTile(mContext);
        usbModeTile.setTemporary(true);
        usbModeTile.setTileId(Tile.USB_MODE);
        usbModeTile.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (mConnectivityManager.getTetherableWifiRegexs().length != 0) {
                    Intent intent = new Intent();
                    intent.setComponent(new ComponentName(
                            "com.android.settings",
                            "com.android.settings.Settings$TetherSettingsActivity"));
                    startSettingsActivity(intent);
                }
                return true;
            }
        });

        mModel.addUsbModeTile(usbModeTile, new QuickSettingsModel.RefreshCallback() {
            @Override
            public void refreshView(QuickSettingsTileView unused, State usbState) {
                usbModeTile.setImageResource(usbState.iconId);
                usbModeTile.setText(usbState.label);
                usbModeTile.setVisibility(usbState.enabled ? View.VISIBLE : View.GONE);
            }
        });

        parent.addView(usbModeTile);

        // Remote Display
        QuickSettingsBasicTile remoteDisplayTile
                = new QuickSettingsBasicTile(mContext);
        remoteDisplayTile.setTemporary(true);
        remoteDisplayTile.setTileId(Tile.REMOTE_DISPLAY);
        remoteDisplayTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collapsePanels();

                final Dialog[] dialog = new Dialog[1];
                dialog[0] = MediaRouteDialogPresenter.createDialog(mContext,
                        MediaRouter.ROUTE_TYPE_REMOTE_DISPLAY,
                        new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialog[0].dismiss();
                        startSettingsActivity(
                                android.provider.Settings.ACTION_WIFI_DISPLAY_SETTINGS);
                    }
                });
                dialog[0].getWindow().setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY);
                dialog[0].show();
            }
        });
        mModel.addRemoteDisplayTile(remoteDisplayTile,
                new QuickSettingsModel.BasicRefreshCallback(remoteDisplayTile)
                        .setShowWhenEnabled(true));
        parent.addView(remoteDisplayTile);

        final QuickSettingsBasicTile imeTile;
        if (SHOW_IME_TILE || DEBUG_GONE_TILES) {
            // IME
            imeTile = new QuickSettingsBasicTile(mContext);
            imeTile.setTemporary(true);
            imeTile.setTileId(Tile.IME);
            imeTile.setImageResource(R.drawable.ic_qs_ime);
            imeTile.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        collapsePanels();
                        Intent intent = new Intent(Settings.ACTION_SHOW_INPUT_METHOD_PICKER);
                        PendingIntent pendingIntent = PendingIntent.getBroadcast(mContext, 0, intent, 0);
                        pendingIntent.send();
                    } catch (Exception e) {}
                }
            });
            mModel.addImeTile(imeTile,
                    new QuickSettingsModel.BasicRefreshCallback(imeTile)
                            .setShowWhenEnabled(true));
            parent.addView(imeTile);
        } else {
            imeTile = null;
        }

        // Bug reports
        final QuickSettingsBasicTile bugreportTile
                = new QuickSettingsBasicTile(mContext);
        bugreportTile.setTemporary(true);
        bugreportTile.setTileId(Tile.BUGREPORT);
        bugreportTile.setImageResource(com.android.internal.R.drawable.stat_sys_adb);
        bugreportTile.setTextResource(com.android.internal.R.string.bugreport_title);
        bugreportTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collapsePanels();
                showBugreportDialog();
            }
        });
        mModel.addBugreportTile(bugreportTile, new QuickSettingsModel.RefreshCallback() {
            @Override
            public void refreshView(QuickSettingsTileView view, State state) {
                view.setVisibility(state.enabled ? View.VISIBLE : View.GONE);
            }
        });
        parent.addView(bugreportTile);
        /*
        QuickSettingsTileView mediaTile = (QuickSettingsTileView)
                inflater.inflate(R.layout.quick_settings_tile, parent, false);
        mediaTile.setContent(R.layout.quick_settings_tile_media, inflater);
        parent.addView(mediaTile);
        QuickSettingsTileView imeTile = (QuickSettingsTileView)
                inflater.inflate(R.layout.quick_settings_tile, parent, false);
        imeTile.setContent(R.layout.quick_settings_tile_ime, inflater);
        imeTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                parent.removeViewAt(0);
            }
        });
        parent.addView(imeTile);
        */

        // SSL CA Cert Warning.
        final QuickSettingsBasicTile sslCaCertWarningTile =
                new QuickSettingsBasicTile(mContext, null, R.layout.quick_settings_tile_monitoring);
        sslCaCertWarningTile.setTemporary(true);
        sslCaCertWarningTile.setTileId(Tile.SSL_CERT_WARNING);
        sslCaCertWarningTile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collapsePanels();
                startSettingsActivity(Settings.ACTION_MONITORING_CERT_INFO);
            }
        });

        sslCaCertWarningTile.setImageResource(
                com.android.internal.R.drawable.indicator_input_error);
        sslCaCertWarningTile.setTextResource(R.string.ssl_ca_cert_warning);

        mModel.addSslCaCertWarningTile(sslCaCertWarningTile,
                new QuickSettingsModel.BasicRefreshCallback(sslCaCertWarningTile)
                        .setShowWhenEnabled(true));
        parent.addView(sslCaCertWarningTile);

        final String hideTiles = Settings.System.getStringForUser(mContext.getContentResolver(),
                Settings.System.QUICK_SETTINGS_HIDE_TILES, UserHandle.USER_CURRENT);
        if (hideTiles != null) {
            for (final String tile : hideTiles.split(DELIMITER)) {
                if (tile.equals(Tile.ALARM.toString())) {
                    alarmTile.setHideRequested(true);
                } else if (tile.equals(Tile.USB_MODE.toString())) {
                    usbModeTile.setHideRequested(true);
                } else if (tile.equals(Tile.REMOTE_DISPLAY.toString())) {
                    remoteDisplayTile.setHideRequested(true);
                } else if (tile.equals(Tile.IME.toString())) {
                    if (imeTile != null) {
                        imeTile.setHideRequested(true);
                    }
                } else if (tile.equals(Tile.BUGREPORT.toString())) {
                    bugreportTile.setHideRequested(true);
                } else if (tile.equals(Tile.SSL_CERT_WARNING.toString())) {
                    sslCaCertWarningTile.setHideRequested(true);
                }
            }
        }
    }

    List<String> enumToStringArray(Tile[] enumData) {
        List<String> array = new ArrayList<String>();
        for(Tile tile : enumData) {
            array.add(tile.toString());
        }
        return array;
    }

    void updateResources() {
        Resources r = mContext.getResources();

        // Update the battery tile
        updateBattery();

        QuickSettingsContainerView container = ((QuickSettingsContainerView)mContainerView);

        container.updateSpan();
        container.updateResources();
        mContainerView.requestLayout();
    }

    private void selectImmersiveStyle() {
        Resources r = mContext.getResources();

        SettingConfirmationHelper.showConfirmationDialogForSetting(
                mContext,
                r.getString(R.string.enable_pie_control_title),
                r.getString(R.string.enable_pie_control_message),
                r.getDrawable(R.drawable.want_some_slice),
                Settings.System.PIE_STATE,
                null);
    }

    private void showBrightnessDialog() {
        Intent intent = new Intent(Intent.ACTION_SHOW_BRIGHTNESS_DIALOG);
        mContext.sendBroadcast(intent);
    }

    private void showBugreportDialog() {
        final AlertDialog.Builder builder = new AlertDialog.Builder(mContext);
        builder.setPositiveButton(com.android.internal.R.string.report, new OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which == DialogInterface.BUTTON_POSITIVE) {
                    // Add a little delay before executing, to give the
                    // dialog a chance to go away before it takes a
                    // screenshot.
                    mHandler.postDelayed(new Runnable() {
                        @Override public void run() {
                            try {
                                ActivityManagerNative.getDefault()
                                        .requestBugReport();
                            } catch (RemoteException e) {
                            }
                        }
                    }, 500);
                }
            }
        });
        builder.setMessage(com.android.internal.R.string.bugreport_message);
        builder.setTitle(com.android.internal.R.string.bugreport_title);
        builder.setCancelable(true);
        final Dialog dialog = builder.create();
        dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ALERT);
        try {
            WindowManagerGlobal.getWindowManagerService().dismissKeyguard();
        } catch (RemoteException e) {
        }
        dialog.show();
    }

    private void applyBluetoothStatus() {
        mModel.onBluetoothStateChange(mBluetoothState);
    }

    private void applyLocationEnabledStatus() {
        mModel.onLocationSettingsChanged(mLocationController.isLocationEnabled());
    }

    void reloadUserInfo() {
        if (mUserInfoTask != null) {
            mUserInfoTask.cancel(false);
            mUserInfoTask = null;
        }
        if (mTilesSetUp) {
            queryForUserInformation();
            queryForSslCaCerts();
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(action)) {
                int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                        BluetoothAdapter.ERROR);
                mBluetoothState.enabled = (state == BluetoothAdapter.STATE_ON);
                applyBluetoothStatus();
            } else if (BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED.equals(action)) {
                int status = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE,
                        BluetoothAdapter.STATE_DISCONNECTED);
                mBluetoothState.connected = (status == BluetoothAdapter.STATE_CONNECTED);
                applyBluetoothStatus();
            } else if (Intent.ACTION_USER_SWITCHED.equals(action)) {
                reloadUserInfo();
            } else if (Intent.ACTION_CONFIGURATION_CHANGED.equals(action)) {
                if (mUseDefaultAvatar) {
                    queryForUserInformation();
                }
            } else if (KeyChain.ACTION_STORAGE_CHANGED.equals(action)) {
                queryForSslCaCerts();
            }
        }
    };

    private final BroadcastReceiver mProfileReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (ContactsContract.Intents.ACTION_PROFILE_CHANGED.equals(action) ||
                    Intent.ACTION_USER_INFO_CHANGED.equals(action)) {
                try {
                    final int currentUser = ActivityManagerNative.getDefault().getCurrentUser().id;
                    final int changedUser =
                            intent.getIntExtra(Intent.EXTRA_USER_HANDLE, getSendingUserId());
                    if (changedUser == currentUser) {
                        reloadUserInfo();
                    }
                } catch (RemoteException e) {
                    Log.e(TAG, "Couldn't get current user id for profile change", e);
                }
            }

        }
    };

    private abstract static class NetworkActivityCallback
            implements QuickSettingsModel.RefreshCallback {
        private final long mDefaultDuration = new ValueAnimator().getDuration();
        private final long mShortDuration = mDefaultDuration / 3;

        public void setActivity(View view, ActivityState state) {
            setVisibility(view.findViewById(R.id.activity_in), state.activityIn);
            setVisibility(view.findViewById(R.id.activity_out), state.activityOut);
        }

        private void setVisibility(View view, boolean visible) {
            final float newAlpha = visible ? 1 : 0;
            if (view.getAlpha() != newAlpha) {
                view.animate()
                    .setDuration(visible ? mShortDuration : mDefaultDuration)
                    .alpha(newAlpha)
                    .start();
            }
        }
    }
}
