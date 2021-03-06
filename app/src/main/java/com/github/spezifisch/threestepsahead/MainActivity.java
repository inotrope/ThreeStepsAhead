package com.github.spezifisch.threestepsahead;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.support.design.widget.NavigationView;
import android.support.design.widget.Snackbar;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.GravityCompat;
import android.support.v4.widget.DrawerLayout;
import android.support.v7.app.ActionBarDrawerToggle;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import org.osmdroid.api.IMapController;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.views.overlay.ItemizedIconOverlay;
import org.osmdroid.views.overlay.OverlayItem;

import java.util.LinkedList;

public class MainActivity extends AppCompatActivity
        implements IPC.LocationUpdateListener, IPC.StateUpdateListener,
        NavigationView.OnNavigationItemSelectedListener {
    static final String TAG = "MainActivity";
    private static final boolean DEBUG = false;

    private Menu menu;
    private MapViewLoc map;
    private IMapController mapController;

    private ItemizedIconOverlay markersOverlay;
    private OverlayItem markerOverlay;
    private Drawable myMarker;

    // IPC to JoystickService
    private SettingsStorage settingsStorage;
    private IPC.SettingsClient settings = new IPC.SettingsClient();
    private IPC.Client serviceClient = new IPC.Client(settings);
    private long lastLocationUpdate = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // start joystick overlay
        startService(new Intent(this, JoystickService.class));

        // settings
        settingsStorage = SettingsStorage.getSettingsStorage(getApplicationContext());
        settings.setSettingsStorage(settingsStorage);

        // start IPC
        serviceClient.connect(getApplicationContext());
        settings.setTagSuffix("MainActivity");
        settings.setClient(serviceClient);
        settings.setOnLocationUpdateListener(this);
        settings.setOnStateUpdateListener(this);

        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // OSM map
        map = (MapViewLoc) findViewById(R.id.map);
        map.setTileSource(TileSourceFactory.MAPNIK);
        //map.setBuiltInZoomControls(true); // interferes with Snackbar
        map.setMultiTouchControls(true);

        // set zoom
        mapController = map.getController();
        mapController.setZoom(17);

        // set marker
        myMarker = ContextCompat.getDrawable(this, R.drawable.pointer);
        markersOverlay = new ItemizedIconOverlay<>(new LinkedList<OverlayItem>(),
                myMarker, null, getApplicationContext());
        map.getOverlays().add(markersOverlay);

        // marker location from settings
        Location loc = settings.getLocation();
        updateMarker(loc, true);

        // zoom out for unset location
        if (Math.abs(loc.getLatitude()) < 0.1 && Math.abs(loc.getLongitude()) < 0.1) {
            mapController.setZoom(2);
        }

        // map click handler
        map.addTapListener(new MapViewLoc.OnTapListener() {
            @Override
            public void onMapTapped(GeoPoint geoPoint) {}

            @Override
            public void onMapTapped(Location location) {
                Snackbar.make(findViewById(R.id.map),
                        "Latitude: " + location.getLatitude() + " Longitude: " + location.getLongitude(),
                        Snackbar.LENGTH_LONG)
                .setAction("Action", null).show();

                // write to settings for GPSMan
                settings.sendLocation(location);

                updateMarker(location, false);
            }
        });

        // custom zoom buttons
        final ImageView zIn = (ImageView)findViewById(R.id.zoomIn);
        final ImageView zOut = (ImageView)findViewById(R.id.zoomOut);
        zIn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mapController.zoomIn();
                if (!map.canZoomIn()) {
                    zIn.setEnabled(false);
                }
                if (map.canZoomOut()) {
                    zOut.setEnabled(true);
                }
            }
        });
        zOut.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mapController.zoomOut();
                if (!map.canZoomOut()) {
                    zOut.setEnabled(false);
                }
                if (map.canZoomIn()) {
                    zIn.setEnabled(true);
                }
            }
        });

        // navigation drawer
        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(
                this, drawer, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close);
        drawer.setDrawerListener(toggle);
        toggle.syncState();

        NavigationView navigationView = (NavigationView) findViewById(R.id.nav_view);
        navigationView.setNavigationItemSelectedListener(this);

        // warning if Xposed hook failed
        if (!settings.isXposedLoaded()) {
            Log.e(TAG, "Xposed failed!");

            Snackbar.make(findViewById(R.id.map),
                    "Xposed Hooks not found! Did you restart yet?", Snackbar.LENGTH_INDEFINITE)
                    .setAction("Action", null).show();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy: bye");
        settings.saveSettings();
    }

    @Override
    public void onBackPressed() {
        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.main, menu);
        this.menu = menu;

        // show version
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            String version = "v" + pInfo.versionName;
            TextView t = (TextView) findViewById(R.id.drawer_version);
            t.setText(version);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Couldn't show version.");
        }

        // update service start/stop button
        updateState(settings.isEnabled());

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_startstop) {
            // toggle location spoofer
            boolean state = !settings.isEnabled();
            settings.sendState(state);
            boolean ok = updateState(state);

            String sstate = state ? "on" : "off";
            String t = "Location Spoofer now " + sstate;
            if (!ok) {
                t = "Failed activating Location Spoofer";
            }

            Snackbar.make(findViewById(R.id.map), t, Snackbar.LENGTH_LONG)
                    .setAction("Action", null).show();
            return true;
        } else if (id == R.id.action_joystick) {
            // toggle joystick
            boolean state = !settingsStorage.isJoystickEnabled();
            JoystickService.get().showJoystick(state);

            try {
                settingsStorage.put("show_joystick", state);
            } catch (Throwable e) {
            }
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onNavigationItemSelected(MenuItem item) {
        // Handle navigation view item clicks here.
        int id = item.getItemId();

        if (id == R.id.nav_about) {
            Uri webpage = Uri.parse("https://github.com/spezifisch/threestepsahead/");
            Intent intent = new Intent(Intent.ACTION_VIEW, webpage);
            if (intent.resolveActivity(getPackageManager()) != null) {
                startActivity(intent);
            }
        } else if (id == R.id.nav_manage) {
            Snackbar.make(findViewById(R.id.map), "Backend is more important right now ;)", Snackbar.LENGTH_SHORT)
                    .setAction("Action", null).show();
        }

        DrawerLayout drawer = (DrawerLayout) findViewById(R.id.drawer_layout);
        drawer.closeDrawer(GravityCompat.START);
        return true;
    }

    @Override
    public void OnLocationUpdate(Location loc) {
        if (DEBUG) {
            Log.d(TAG, "got location update:" + loc);
        }

        updateMarker(loc, true);

        // throttle settings updates
        long now = System.currentTimeMillis();
        long elapsed = now - lastLocationUpdate;
        lastLocationUpdate = now;

        if (elapsed > 60*1000) {
            settings.saveSettings();
        }
    }

    @Override
    public void OnStateUpdate() {
        updateState(settings.isEnabled());

        // always update
        lastLocationUpdate = System.currentTimeMillis();
        settings.saveSettings();
    }

    private void updateMarker(GeoPoint pt, boolean center) {
        final String markerTitle = "Fake Location", markerDescription = "You seem to be here.";

        // add marker for new location
        markerOverlay = new OverlayItem(markerTitle, markerDescription, pt);
        markerOverlay.setMarker(myMarker);

        // replace marker. there's no method for updating it directly
        markersOverlay.removeAllItems();
        markersOverlay.addItem(markerOverlay);

        // pan map?
        if (center) {
            mapController.setCenter(pt);
        } else {
            // redraw map to show new marker
            findViewById(R.id.map).invalidate();
        }
    }

    private void updateMarker(Location loc, boolean center) {
        int lat = (int) (loc.getLatitude() * 1E6);
        int lng = (int) (loc.getLongitude() * 1E6);
        GeoPoint point = new GeoPoint(lat, lng);
        updateMarker(point, center);
    }

    private boolean updateState(boolean enabled) {
        if (menu == null) {
            return false;
        }

        boolean ok = true;
        int id;
        if (enabled) {
            if (settings.isXposedLoaded()) {
                id = R.string.service_started;
            } else {
                id = R.string.service_failed;
                ok = false;
            }
        } else {
            id = R.string.service_stopped;
        }
        menu.findItem(R.id.action_startstop).setTitle(getString(id));

        return ok;
    }
}
