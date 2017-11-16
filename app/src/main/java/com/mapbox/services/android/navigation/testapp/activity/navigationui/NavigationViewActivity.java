package com.mapbox.services.android.navigation.testapp.activity.navigationui;

import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v4.app.FragmentActivity;
import android.util.Log;

import com.mapbox.directions.v5.DirectionsCriteria;
import com.mapbox.directions.v5.MapboxDirections;
import com.mapbox.directions.v5.models.DirectionsResponse;
import com.mapbox.directions.v5.models.DirectionsRoute;
import com.mapbox.geojson.LineString;
import com.mapbox.geojson.Point;
import com.mapbox.mapboxsdk.Mapbox;
import com.mapbox.mapboxsdk.maps.MapView;
import com.mapbox.mapboxsdk.maps.MapboxMap;
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback;
import com.mapbox.mapboxsdk.plugins.locationlayer.LocationLayerMode;
import com.mapbox.mapboxsdk.plugins.locationlayer.LocationLayerPlugin;
import com.mapbox.mapboxsdk.style.layers.LineLayer;
import com.mapbox.mapboxsdk.style.layers.Property;
import com.mapbox.mapboxsdk.style.layers.PropertyFactory;
import com.mapbox.mapboxsdk.style.sources.GeoJsonOptions;
import com.mapbox.mapboxsdk.style.sources.GeoJsonSource;
import com.mapbox.services.android.navigation.testapp.R;
import com.mapbox.services.android.navigation.ui.v5.camera.NavigationCamera;
import com.mapbox.services.android.navigation.v5.location.MockLocationEngine;
import com.mapbox.services.android.navigation.v5.navigation.MapboxNavigation;
import com.mapbox.services.android.navigation.v5.navigation.MapboxNavigationOptions;
import com.mapbox.services.android.navigation.v5.navigation.NavigationEventListener;
import com.mapbox.services.android.navigation.v5.navigation.NavigationRoute;
import com.mapbox.services.android.navigation.v5.offroute.OffRouteListener;
import com.mapbox.services.android.navigation.v5.routeprogress.ProgressChangeListener;
import com.mapbox.services.android.navigation.v5.routeprogress.RouteProgress;
import com.mapbox.services.android.telemetry.location.LocationEngine;
import com.mapbox.services.android.telemetry.location.LocationEngineListener;
import com.mapbox.services.commons.models.Position;
import com.mapbox.services.commons.utils.PolylineUtils;
import com.mapbox.services.constants.Constants;
import com.mapbox.services.exceptions.ServicesException;
import com.mapbox.turf.TurfMisc;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import butterknife.BindView;
import butterknife.ButterKnife;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import timber.log.Timber;

import static com.mapbox.services.commons.models.Position.fromLngLat;

public class NavigationViewActivity extends FragmentActivity implements
  OnMapReadyCallback,
  NavigationEventListener,
  OffRouteListener,
  ProgressChangeListener {

  private MapboxMap map;
  private MapboxNavigation navigation;
  private NavigationCamera camera;
  private LocationEngine locationEngine;
  private LocationLayerPlugin locationLayer;
  private SharedPreferences preferences;

  private Location userLocation;
  private DirectionsRoute currentRoute;
  private DirectionsRoute tourRoute;
  private Integer currentLegIndex = 0;
  private Integer initialLegIndex;

  private List<JSONObject> waypoints = new ArrayList<>();
  private JSONObject departureWaypoint;
  private boolean isRouteUpdating = false;

  private static final String WAYPOINTS_RESPONSE = "directions-2-route.json";

  @BindView(R.id.mapView)
  MapView mapView;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    try {
      JSONArray jsonArray = new JSONArray(loadJsonFromAsset(WAYPOINTS_RESPONSE));
      for (int i = 0 ; i < jsonArray.length(); i++) {
        waypoints.add(jsonArray.getJSONObject(i));
      }
    } catch (JSONException e) {
      e.printStackTrace();
    }

    preferences = PreferenceManager.getDefaultSharedPreferences(this);

    setContentView(R.layout.activity_reroute);

    ButterKnife.bind(this);

    mapView.onCreate(savedInstanceState);
    mapView.getMapAsync(this);

    // Initialize MapboxNavigation and add listeners
    navigation = new MapboxNavigation(
      this,
      Mapbox.getAccessToken(),
      MapboxNavigationOptions.builder()
        .manuallyEndNavigationUponCompletion(true)
        .maximumDistanceOffRoute(40)
        .build()
    );

    // Create a mock engine
    locationEngine = new MockLocationEngine(1000, 30, true);
    navigation.setLocationEngine(locationEngine);

    navigation.addNavigationEventListener(this);
    navigation.addProgressChangeListener(this);
    navigation.addOffRouteListener(this);

    initialLegIndex = preferences.getInt("waypoint_index", 0);
    departureWaypoint = waypoints.get(0);
    waypoints = waypoints.subList(1, waypoints.size());
  }

  @SuppressLint("MissingPermission")
  @Override
  public void onMapReady(MapboxMap mapboxMap) {
    map = mapboxMap;

    map.setStyleUrl(getString(R.string.navigation_guidance_day_v2), new MapboxMap.OnStyleLoadedListener() {
      @Override
      public void onStyleLoaded(String style) {
        initLocationLayer();
        initCamera();

        enableLocation();
      }
    });
  }

  @Override
  public void onProgressChange(Location location, RouteProgress routeProgress) {
    if (location.getLongitude() != 0 && location.getLatitude() != 0) {
      onUserLocationChange(location);

      if (routeProgress.distanceRemaining() <= 40 && !isRouteUpdating) {
        if (currentLegIndex != waypoints.size() - 1) {
          currentLegIndex++;
          goToNextLeg();
        } else {
          onTourFinish();
        }
      }
    }
  }

  private void renderNavigation() {
    // Re-render route (to remove past route)
    drawCurrentRoute(fromLngLat(userLocation.getLongitude(), userLocation.getLatitude()), currentRoute);
  }

  private void onUserLocationChange(Location location) {
    userLocation = location;

    locationLayer.forceLocationUpdate(location);

    if (!isRouteUpdating && (currentRoute == null || tourRoute == null)) {
      updateTourRoute(userLocation, waypoints);
    }
  }

  private void goToNextLeg() {
    // Save waypoint index in preferences (in case app crashes...)
    preferences.edit().putInt("waypoint_index", currentLegIndex + initialLegIndex).apply();

    Point waypoint = null;
    try {
      waypoint = Point.fromLngLat(
        Double.parseDouble(waypoints.get(initialLegIndex + currentLegIndex).getJSONObject("value").getString("longitude")),
        Double.parseDouble(waypoints.get(initialLegIndex + currentLegIndex).getJSONObject("value").getString("latitude"))
      );
    } catch (JSONException e) {
      e.printStackTrace();
    }

    updateCurrentRoute(userLocation, waypoint);
  }

  /**
   * Initializes the {@link NavigationCamera} that will be used to follow
   * the {@link Location} updates from {@link MapboxNavigation}.
   */
  private void initCamera() {
    camera = new NavigationCamera(mapView, map, navigation);
  }

  /**
   * Initializes the {@link LocationLayerPlugin} to be used to draw the current
   * location.
   */
  @SuppressWarnings({"MissingPermission"})
  private void initLocationLayer() {
    locationLayer = new LocationLayerPlugin(mapView, map, null);
    locationLayer.setLocationLayerEnabled(LocationLayerMode.NAVIGATION);
  }

  private void updateCurrentRoute(Location userLocation, Point waypoint) throws ServicesException {
    Point origin = Point.fromLngLat(userLocation.getLongitude(), userLocation.getLatitude());
    Double bearing = userLocation.hasBearing() ? Float.valueOf(userLocation.getBearing()).doubleValue() : null;

    NavigationRoute.Builder navigationRoute = NavigationRoute.builder()
      .accessToken(Mapbox.getAccessToken())
      .profile(DirectionsCriteria.PROFILE_DRIVING)
      .origin(origin, bearing, 90d)
      .destination(waypoint);

    isRouteUpdating = true;

    // Show loading UI
//    routeInfosFragment.setLoadingState();

    navigationRoute.build().getRoute(new Callback<DirectionsResponse>() {
      @Override
      public void onResponse(Call<DirectionsResponse> call, Response<DirectionsResponse> response) {
        Timber.d("Response code: " + response.code());

        if (response.body() == null || response.body().routes().isEmpty()) {
          Timber.d("No routes found.");
          return;
        }

        isRouteUpdating = false;

        currentRoute = response.body().routes().get(0);

        navigation.startNavigation(currentRoute);

        renderNavigation();
      }

      @Override
      public void onFailure(Call<DirectionsResponse> call, Throwable throwable) {
        Log.e("getRoute()", throwable.toString());
      }
    });
  }

  private void updateTourRoute(Location userLocation, List<JSONObject> waypoints) throws ServicesException {
    Point origin = Point.fromLngLat(userLocation.getLongitude(), userLocation.getLatitude());

    List<Point> waypointsPoints = new ArrayList<>();

    for (JSONObject jsonObject : waypoints) {
      try {
        waypointsPoints.add(Point.fromLngLat(
          Double.parseDouble(jsonObject.getJSONObject("value").getString("longitude")),
          Double.parseDouble(jsonObject.getJSONObject("value").getString("latitude"))
        ));
      } catch (JSONException e) {
        e.printStackTrace();
      }
    }

    Double bearing = userLocation.hasBearing() ? Float.valueOf(userLocation.getBearing()).doubleValue() : null;

    isRouteUpdating = true;

    MapboxDirections.Builder directionsBuilder = MapboxDirections.builder()
      .accessToken(Mapbox.getAccessToken())
      .profile(DirectionsCriteria.PROFILE_DRIVING)
      .origin(origin)
      .addBearing(bearing, 90d)
      .steps(true)
      .continueStraight(true)
      .geometries(DirectionsCriteria.GEOMETRY_POLYLINE6)
      .overview(DirectionsCriteria.OVERVIEW_FULL)
      .roundaboutExits(true);

    for (Point waypoint: waypointsPoints) {
      directionsBuilder.addWaypoint(waypoint);
      directionsBuilder.addBearing(null, null);
    }

    directionsBuilder.build().enqueueCall(new Callback<DirectionsResponse>() {
      @Override
      public void onResponse(Call<DirectionsResponse> call, Response<DirectionsResponse> response) {
        Timber.d(call.request().url().toString());
        Timber.d("Response code: " + response.code());

        if (response.body() == null || response.body().routes().isEmpty()) {
          Timber.d("No routes found.");
          return;
        }

        tourRoute = response.body().routes().get(0);

        ((MockLocationEngine) locationEngine).setRoute(tourRoute);

        drawTourRoute(tourRoute);
        goToNextLeg();
      }

      @Override
      public void onFailure(Call<DirectionsResponse> call, Throwable throwable) {
        Timber.d(call.request().url().toString());
        Log.e("getRoute()", throwable.toString());
      }
    });
  }

  private void drawTourRoute(DirectionsRoute tourRoute) {
    LineString geometry = LineString.fromPolyline(tourRoute.geometry(), com.mapbox.services.Constants.PRECISION_6);
    GeoJsonOptions routeGeoJsonOptions = new GeoJsonOptions().withMaxZoom(16);
    GeoJsonSource routeSource = new GeoJsonSource("tour-route-source", geometry.toJson() , routeGeoJsonOptions);
    map.addSource(routeSource);

    LineLayer lineLayer = new LineLayer("tour-line-layer", routeSource.getId());
    lineLayer.setProperties(
      PropertyFactory.lineColor(Color.parseColor("#3fa9e0")),
      PropertyFactory.lineOpacity(0.6f),
      PropertyFactory.lineWidth(10f),
      PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
      PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
    );
    map.addLayerBelow(lineLayer, "road-label-small");
  }

  private void drawCurrentRoute(Position origin, DirectionsRoute route) {
    // Decode the geometry and draw the route from current position to end of route
    List<Position> routeCoords = PolylineUtils.decode(route.geometry(), Constants.PRECISION_6);

    LineString slicedRouteLine = TurfMisc.lineSlice(
      Point.fromLngLat(origin.getLongitude(), origin.getLatitude()),
      Point.fromLngLat(routeCoords.get(routeCoords.size() - 1).getLongitude(), routeCoords.get(routeCoords.size() - 1).getLatitude()),
      LineString.fromPolyline(route.geometry(), Constants.PRECISION_6)
    );

    // Determine whether the source needs to be added or updated
    GeoJsonSource source = map.getSourceAs("leg-route-source");
    if (source == null) {
      GeoJsonOptions routeGeoJsonOptions = new GeoJsonOptions().withMaxZoom(16);
      GeoJsonSource routeSource = new GeoJsonSource("leg-route-source",
        slicedRouteLine.toJson(), routeGeoJsonOptions);
      map.addSource(routeSource);

      LineLayer lineLayer = new LineLayer("leg-line-layer", routeSource.getId());
      LineLayer lineBorderLayer = new LineLayer("leg-line-border-layer",  routeSource.getId());
      lineLayer.setProperties(
        PropertyFactory.lineColor(Color.parseColor("#3fa9e0")),
        PropertyFactory.lineWidth(10f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
      );
      lineBorderLayer.setProperties(
        PropertyFactory.lineColor(Color.parseColor("#1f91cc")),
        PropertyFactory.lineWidth(16f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)
      );
      map.addLayerBelow(lineLayer, "road-label-small");
      map.addLayerBelow(lineBorderLayer, "leg-line-layer");
    } else {
      source.setGeoJson(slicedRouteLine.toJson());
    }
  }

  private void resetWaypointIndex() {
    preferences.edit().putInt("waypoint_index", 0).apply();
    initialLegIndex = 0;
    currentLegIndex = 0;
  }

  @SuppressLint("MissingPermission")
  private void enableLocation() {
    locationEngine.addLocationEngineListener(new LocationEngineListener() {
      @Override
      public void onConnected() {
        locationEngine.requestLocationUpdates();
      }

      @Override
      public void onLocationChanged(Location location) {
        // Retrieve first found user location and unsubscribe
        if (location != null) {
          onUserLocationChange(location);
          locationEngine.removeLocationEngineListener(this);
        }
      }
    });

    userLocation = locationEngine.getLastLocation();

    if(userLocation == null) {
      Point loc = null;
      try {
        loc = Point.fromLngLat(
          Double.parseDouble(departureWaypoint.getJSONObject("value").getString("longitude")),
          Double.parseDouble(departureWaypoint.getJSONObject("value").getString("latitude"))
        );
      } catch (JSONException e) {
        e.printStackTrace();
      }

      ((MockLocationEngine)locationEngine).setLastLocation(loc);

      userLocation = locationEngine.getLastLocation();
    }

    onUserLocationChange(userLocation);
  }

  @Override
  public void onRunning(boolean running) {
    if (running) {
      Timber.d("onRunning: Started");

      camera.start(currentRoute);
    } else {
      Timber.d("onRunning: Stopped");
    }
  }

  private void onTourFinish() {
    // Reset saved waypoint index from preferences
    resetWaypointIndex();
  }

  /**
   * Used to determine if a rawLocation has a bearing.
   *
   * @return true if bearing exists, false if not
   */
  private boolean locationHasBearing() {
    return userLocation != null && userLocation.hasBearing();
  }

  @Override
  public void userOffRoute(Location location) {
    Timber.d("offRoute");
    userLocation = location;
    goToNextLeg();
  }

  @SuppressLint("MissingPermission")
  @Override
  public void onStart() {
    super.onStart();
    if (locationLayer != null) {
      locationLayer.onStart();
    }
    mapView.onStart();
  }

  @Override
  public void onResume() {
    super.onResume();
    mapView.onResume();

    if(currentRoute != null) {
      navigation.startNavigation(currentRoute);
    }
  }

  @Override
  public void onPause() {
    super.onPause();
    mapView.onPause();
  }

  @Override
  public void onStop() {
    super.onStop();
    locationLayer.onStop();
    mapView.onStop();

    if (locationEngine != null) {
      locationEngine.removeLocationUpdates();
      locationEngine.deactivate();
    }

    if (navigation != null) {
      // End the navigation session
      navigation.endNavigation();
    }
  }

  @Override
  public void onLowMemory() {
    super.onLowMemory();
    mapView.onLowMemory();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    mapView.onDestroy();

    // Remove all navigation listeners
    navigation.removeNavigationEventListener(this);
    navigation.removeProgressChangeListener(this);

    navigation.onDestroy();
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    mapView.onSaveInstanceState(outState);
  }

  @Override
  public void onBackPressed() {
    resetWaypointIndex();
    super.onBackPressed();
  }

  private String loadJsonFromAsset(String filename) {
    // Using this method to load in GeoJSON files from the assets folder.

    try {
      InputStream is = getAssets().open(filename);
      int size = is.available();
      byte[] buffer = new byte[size];
      is.read(buffer);
      is.close();
      return new String(buffer, "UTF-8");

    } catch (IOException ex) {
      ex.printStackTrace();
      return null;
    }
  }
}
