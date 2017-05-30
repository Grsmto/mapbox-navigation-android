package com.mapbox.services.android.navigation.testapp.activity;

import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;

import com.mapbox.mapboxsdk.Mapbox;
import com.mapbox.mapboxsdk.annotations.MarkerOptions;
import com.mapbox.mapboxsdk.annotations.Polyline;
import com.mapbox.mapboxsdk.annotations.PolylineOptions;
import com.mapbox.mapboxsdk.geometry.LatLng;
import com.mapbox.mapboxsdk.maps.MapView;
import com.mapbox.mapboxsdk.maps.MapboxMap;
import com.mapbox.mapboxsdk.maps.OnMapReadyCallback;
import com.mapbox.services.Constants;
import com.mapbox.services.android.navigation.testapp.R;
import com.mapbox.services.android.navigation.v5.MapboxNavigation;
import com.mapbox.services.android.navigation.v5.RouteProgress;
import com.mapbox.services.android.navigation.v5.listeners.NavigationEventListener;
import com.mapbox.services.android.navigation.v5.listeners.OffRouteListener;
import com.mapbox.services.android.navigation.v5.listeners.ProgressChangeListener;
import com.mapbox.services.android.telemetry.location.LocationEngine;
import com.mapbox.services.android.telemetry.location.LocationEngineListener;
import com.mapbox.services.api.directions.v5.models.DirectionsResponse;
import com.mapbox.services.api.directions.v5.models.DirectionsRoute;
import com.mapbox.services.commons.geojson.LineString;
import com.mapbox.services.commons.models.Position;

import java.util.ArrayList;
import java.util.List;

import butterknife.BindView;
import butterknife.ButterKnife;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;
import timber.log.Timber;

public class RerouteActivity extends AppCompatActivity implements OnMapReadyCallback, LocationEngineListener,
  Callback<DirectionsResponse>, MapboxMap.OnMapClickListener, NavigationEventListener, OffRouteListener,
  ProgressChangeListener {

  @BindView(R.id.mapView)
  MapView mapView;

  private LocationEngine locationEngine;
  private MapboxNavigation navigation;
  private MapboxMap mapboxMap;
  private boolean running;
  private Polyline polyline;

  Position origin = Position.fromCoordinates(-87.6900, 41.8529);
  Position destination = Position.fromCoordinates(-87.8921, 41.9794);

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_reroute);
    ButterKnife.bind(this);

    mapView.onCreate(savedInstanceState);
    mapView.getMapAsync(this);

    // Initialize MapboxNavigation and add listeners
    navigation = new MapboxNavigation(this, Mapbox.getAccessToken());
    navigation.addNavigationEventListener(this);

  }

  @Override
  public void onMapReady(MapboxMap mapboxMap) {
    this.mapboxMap = mapboxMap;
    mapboxMap.setOnMapClickListener(this);

    // Use the mockLocationEngine
    locationEngine = new MockLocationEngine(1000, 30, false);
    locationEngine.addLocationEngineListener(this);

    mapboxMap.setLocationSource(locationEngine);
    mapboxMap.setMyLocationEnabled(true);
    navigation.setLocationEngine(locationEngine);

    // Acquire the navigation's route
    navigation.getRoute(origin, destination, this);
  }

  @Override
  public void onMapClick(@NonNull LatLng point) {
    if (!running || mapboxMap == null) {
      return;
    }

    mapboxMap.addMarker(new MarkerOptions().position(point));
    mapboxMap.setOnMapClickListener(null);

    Position newDestination = Position.fromLngLat(point.getLongitude(), point.getLatitude());
    ((MockLocationEngine) locationEngine).moveToLocation(newDestination);
  }

  @Override
  public void onRunning(boolean running) {
    this.running = running;
    if (running) {
      navigation.addOffRouteListener(this);
      navigation.addProgressChangeListener(this);
    }
  }

  @Override
  public void userOffRoute(Location location) {
    Position newOrigin = Position.fromLngLat(location.getLongitude(), location.getLatitude());
    navigation.getRoute(newOrigin, destination, location.getBearing(), this);
    Timber.d("offRoute");
    mapboxMap.addMarker(new MarkerOptions().position(new LatLng(location.getLatitude(), location.getLongitude())));
  }


  @Override
  public void onProgressChange(Location location, RouteProgress routeProgress) {
    System.out.println(routeProgress.getCurrentLegProgress().getStepIndex());
  }

  @Override
  public void onResponse(Call<DirectionsResponse> call, Response<DirectionsResponse> response) {
    DirectionsRoute route = response.body().getRoutes().get(0);
    if (route == null) {
      Timber.d("route is null");
      return;
    }

    // First run
    drawRoute(route);
    if (!running) {
      ((MockLocationEngine) locationEngine).setRoute(route);
      navigation.startNavigation(route);
    } else {
      navigation.updateRoute(route);
    }
  }

  @Override
  public void onFailure(Call<DirectionsResponse> call, Throwable throwable) {
    Timber.e("Getting directions failed: ", throwable);
  }

  private void drawRoute(DirectionsRoute route) {
    List<LatLng> points = new ArrayList<>();
    List<Position> coords = LineString.fromPolyline(route.getGeometry(), Constants.PRECISION_6).getCoordinates();

    for (Position position : coords) {
      points.add(new LatLng(position.getLatitude(), position.getLongitude()));
    }

    if (points.size() > 0) {

      if (polyline != null) {
        mapboxMap.removePolyline(polyline);
      }

      // Draw polyline on map
      polyline = mapboxMap.addPolyline(new PolylineOptions()
        .addAll(points)
        .color(Color.parseColor("#4264fb"))
        .width(5));
    }
  }

  @Override
  @SuppressWarnings( {"MissingPermission"})
  public void onConnected() {
    locationEngine.requestLocationUpdates();
  }

  @Override
  public void onLocationChanged(Location location) {

  }

  @Override
  public void onResume() {
    super.onResume();
    mapView.onResume();
  }

  @Override
  protected void onStart() {
    super.onStart();
    mapView.onStart();
  }

  @Override
  protected void onStop() {
    super.onStop();
    mapView.onStop();

    if (locationEngine != null) {
      locationEngine.removeLocationEngineListener(this);
      locationEngine.removeLocationUpdates();
      locationEngine.deactivate();
    }

    if (navigation != null) {
      // End the navigation session
      navigation.endNavigation();
    }
  }

  @Override
  public void onPause() {
    super.onPause();
    mapView.onPause();
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
  }

  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    mapView.onSaveInstanceState(outState);
  }
}