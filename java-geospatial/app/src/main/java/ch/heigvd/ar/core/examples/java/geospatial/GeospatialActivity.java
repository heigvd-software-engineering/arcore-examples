/*
 * Copyright 2022 Google LLC
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

package ch.heigvd.ar.core.examples.java.geospatial;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.TextUtils;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentOnAttachListener;

import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Earth;
import com.google.ar.core.Frame;
import com.google.ar.core.GeospatialPose;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;

import ch.heigvd.ar.core.examples.java.LocalRestaurant;
import ch.heigvd.ar.core.examples.java.LocalRestaurantReply;
import ch.heigvd.ar.core.examples.java.LocalRestaurantRequest;
import ch.heigvd.ar.core.examples.java.common.helpers.CameraPermissionHelper;
import ch.heigvd.ar.core.examples.java.common.helpers.DisplayRotationHelper;
import ch.heigvd.ar.core.examples.java.common.helpers.FullScreenHelper;
import ch.heigvd.ar.core.examples.java.common.helpers.LocationPermissionHelper;
import ch.heigvd.ar.core.examples.java.common.helpers.SnackbarHelper;
import ch.heigvd.ar.core.examples.java.common.helpers.TrackingStateHelper;
import ch.heigvd.ar.core.examples.java.common.samplerender.Framebuffer;
import ch.heigvd.ar.core.examples.java.common.samplerender.Mesh;
import ch.heigvd.ar.core.examples.java.common.samplerender.SampleRender;
import ch.heigvd.ar.core.examples.java.common.samplerender.Shader;
import ch.heigvd.ar.core.examples.java.common.samplerender.Texture;
import ch.heigvd.ar.core.examples.java.common.samplerender.arcore.BackgroundRenderer;
import ch.heigvd.ar.core.examples.java.MapServiceGrpc;

import ch.heigvd.ar.core.examples.java.geospatial.R;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;

import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.FineLocationPermissionNotGrantedException;
import com.google.ar.core.exceptions.GooglePlayServicesLocationLibraryNotLinkedException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import com.google.ar.core.exceptions.UnsupportedConfigurationException;
import com.google.ar.sceneform.AnchorNode;
import com.google.ar.sceneform.ArSceneView;
import com.google.ar.sceneform.Node;
import com.google.ar.sceneform.SceneView;
import com.google.ar.sceneform.Sceneform;
import com.google.ar.sceneform.math.Vector3;
import com.google.ar.sceneform.rendering.ModelRenderable;
import com.google.ar.sceneform.rendering.ViewRenderable;
import com.google.ar.sceneform.ux.ArFragment;
import com.google.ar.sceneform.ux.BaseArFragment;

import java.io.Closeable;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Main activity for the Geospatial API example.
 *
 * <p>This example shows how to use the Geospatial APIs. Once the device is localized, anchors can
 * be created at the device's geospatial location. Anchor locations are persisted across sessions
 * and will be recreated once localized.
 */
public class GeospatialActivity extends AppCompatActivity implements
        FragmentOnAttachListener,
        BaseArFragment.OnTapArPlaneListener,
        BaseArFragment.OnSessionConfigurationListener,
        ArFragment.OnViewCreatedListener,
        SampleRender.Renderer {
  // The thresholds that are required for horizontal and heading accuracies before entering into the
  // LOCALIZED state. Once the accuracies are equal or less than these values, the app will
  // allow the user to place anchors.
  private static final double LOCALIZING_HORIZONTAL_ACCURACY_THRESHOLD_METERS = 10;
  private static final double LOCALIZING_HEADING_ACCURACY_THRESHOLD_DEGREES = 15;

  // Once in the LOCALIZED state, if either accuracies degrade beyond these amounts, the app will
  // revert back to the LOCALIZING state.
  private static final double LOCALIZED_HORIZONTAL_ACCURACY_HYSTERESIS_METERS = 10;
  private static final double LOCALIZED_HEADING_ACCURACY_HYSTERESIS_DEGREES = 10;

  private ArFragment arFragment;
  private ViewRenderable viewRenderable;

  private SharedPreferences sharedPreferences;

  // Rendering. The Renderers are created here, and initialized when the GL surface is created.
  private GLSurfaceView surfaceView;

  private String lastStatusText;
  private TextView geospatialPoseTextView;
  private TextView statusTextView;
  private Button setAnchorButton;
  private Button clearAnchorsButton;

  private DisplayRotationHelper displayRotationHelper;
  private SampleRender render;

  private boolean installRequested;
  private Integer clearedAnchorsAmount = null;

  private Session session;
  private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);

  private final List<Anchor> anchors = new ArrayList<>();

  private static final String SHARED_PREFERENCES_SAVED_ANCHORS = "SHARED_PREFERENCES_SAVED_ANCHORS";

  private static final int MAXIMUM_ANCHORS = 10;

  private static final int LOCALIZING_TIMEOUT_SECONDS = 180;

  private BackgroundRenderer backgroundRenderer;

  private Framebuffer virtualSceneFramebuffer;
  private boolean hasSetTextureNames = false;

  private final float[] modelMatrix = new float[16];
  private final float[] projectionMatrix = new float[16];
  private final float[] viewMatrix = new float[16];
  private final float[] modelViewMatrix = new float[16]; // view x model
  private final float[] modelViewProjectionMatrix = new float[16]; // projection x view x model
  private static final float Z_NEAR = 0.1f;
  private static final float Z_FAR = 1000f;

  /** Timer to keep track of how much time has passed since localizing has started. */
  private long localizingStartTimestamp;

  private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();


  enum State {
    /** The Geospatial API has not yet been initialized. */
    UNINITIALIZED,
    /** The Geospatial API is not supported. */
    UNSUPPORTED,
    /** The Geospatial API has encountered an unrecoverable error. */
    EARTH_STATE_ERROR,
    /** The Session has started, but {@link Earth} isn't {@link TrackingState.TRACKING} yet. */
    PRETRACKING,
    /**
     * {@link Earth} is {@link TrackingState.TRACKING}, but the desired positioning confidence
     * hasn't been reached yet.
     */
    LOCALIZING,
    /** The desired positioning confidence wasn't reached in time. */
    LOCALIZING_FAILED,
    /**
     * {@link Earth} is {@link TrackingState.TRACKING} and the desired positioning confidence has
     * been reached.
     */
    LOCALIZED
  }
  private State state = State.UNINITIALIZED;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    sharedPreferences = getPreferences(Context.MODE_PRIVATE);

    setContentView(R.layout.activity_main);
    getSupportFragmentManager().addFragmentOnAttachListener(this);

    surfaceView = findViewById(R.id.surfaceview);
    geospatialPoseTextView = findViewById(R.id.geospatial_pose_view);
    statusTextView = findViewById(R.id.status_text_view);
    setAnchorButton = findViewById(R.id.set_anchor_button);
    clearAnchorsButton = findViewById(R.id.clear_anchors_button);

    // Handle the button to add a anchor to the current position of the user
    setAnchorButton.setOnClickListener(view -> handleSetAnchorButton());
    // Clear all the anchors
    clearAnchorsButton.setOnClickListener(view -> handleClearAnchorsButton());

    displayRotationHelper = new DisplayRotationHelper(/*context=*/ this);

    // Set up renderer.
    render = new SampleRender(surfaceView, this, getAssets());

    installRequested = false;
    clearedAnchorsAmount = null;

    // Load model.glb from assets folder or http url
    getSupportFragmentManager().addFragmentOnAttachListener(this);

    if (savedInstanceState == null) {
      if (Sceneform.isSupported(this)) {
        getSupportFragmentManager().beginTransaction()
                .add(R.id.arFragment, ArFragment.class, null)
                .commit();
      }
    }

    loadModels();
  }

  @Override
  public void onAttachFragment(@NonNull FragmentManager fragmentManager, @NonNull Fragment fragment) {
    if (fragment.getId() == R.id.arFragment) {
      arFragment = (ArFragment) fragment;
      arFragment.setOnSessionConfigurationListener(this);
      arFragment.setOnViewCreatedListener(this);
      arFragment.setOnTapArPlaneListener(this);
    }
  }

  @Override
  public void onSessionConfiguration(Session session, Config config) {
    if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
      config.setDepthMode(Config.DepthMode.AUTOMATIC);
    }
  }

  @Override
  public void onViewCreated(ArSceneView arSceneView) {
    arFragment.setOnViewCreatedListener(null);

    // Fine adjust the maximum frame rate
    arSceneView.setFrameRateFactor(SceneView.FrameRate.FULL);
  }

  public void loadModels() {
    WeakReference<GeospatialActivity> weakActivity = new WeakReference<>(this);
    ViewRenderable.builder()
            .setView(this, R.layout.view_text)
            .build()
            .thenAccept(viewRenderable -> {
              GeospatialActivity activity = weakActivity.get();
              if (activity != null) {
                activity.viewRenderable = viewRenderable;
              }
            })
            .exceptionally(throwable -> {
              Toast.makeText(this, "Unable to load model", Toast.LENGTH_LONG).show();
              return null;
            });
  }

  @Override
  public void onTapPlane(HitResult hitResult, Plane plane, MotionEvent motionEvent) {
    if (viewRenderable == null) {
      Toast.makeText(this, "Loading...", Toast.LENGTH_SHORT).show();
      return;
    }

    // Create the Anchor.
    Anchor anchor = hitResult.createAnchor();
    AnchorNode anchorNode = new AnchorNode(anchor);
    anchorNode.setParent(arFragment.getArSceneView().getScene());

    // Add text to anchor
    Node titleNode = new Node();
    titleNode.setParent(anchorNode);
    titleNode.setEnabled(false);
    titleNode.setLocalPosition(new Vector3(0.0f, 1.0f, 0.0f));
    titleNode.setRenderable(viewRenderable);
    titleNode.setEnabled(true);
  }

  /**
   * Handles the button that creates an anchor.
   *
   * <p>Ensure Earth is in the proper state, then create the anchor. Persist the parameters used to
   * create the anchors so that the anchors will be loaded next time the app is launched.
   */
  private void handleSetAnchorButton() {
    Earth earth = session.getEarth();
    if (earth == null || earth.getTrackingState() != TrackingState.TRACKING) {
      return;
    }

    GeospatialPose geospatialPose = earth.getCameraGeospatialPose();
    double latitude = geospatialPose.getLatitude();
    double longitude = geospatialPose.getLongitude();
    double altitude = geospatialPose.getAltitude();
    double headingDegrees = geospatialPose.getHeading();
    createAnchor(earth, latitude, longitude, altitude, headingDegrees);
    storeAnchorParameters(latitude, longitude, altitude, headingDegrees);
    runOnUiThread(() -> clearAnchorsButton.setVisibility(View.VISIBLE));
    if (clearedAnchorsAmount != null) {
      clearedAnchorsAmount = null;
    }
  }

  private void handleClearAnchorsButton() {
    clearedAnchorsAmount = anchors.size();
    anchors.clear();
    clearAnchorsFromSharedPreferences();
    clearAnchorsButton.setVisibility(View.INVISIBLE);
  }

  /**
   * Helper function to store the parameters used in anchor creation in {@link SharedPreferences}.
   */
  private void storeAnchorParameters(
          double latitude, double longitude, double altitude, double headingDegrees) {
    Set<String> anchorParameterSet =
            sharedPreferences.getStringSet(SHARED_PREFERENCES_SAVED_ANCHORS, new HashSet<>());
    HashSet<String> newAnchorParameterSet = new HashSet<>(anchorParameterSet);

    SharedPreferences.Editor editor = sharedPreferences.edit();
    newAnchorParameterSet.add(
            String.format("%.6f,%.6f,%.6f,%.6f", latitude, longitude, altitude, headingDegrees));
    editor.putStringSet(SHARED_PREFERENCES_SAVED_ANCHORS, newAnchorParameterSet);
    editor.commit();
  }

  private void clearAnchorsFromSharedPreferences() {
    SharedPreferences.Editor editor = sharedPreferences.edit();
    editor.putStringSet(SHARED_PREFERENCES_SAVED_ANCHORS, null);
    editor.commit();
  }

  /** Create an anchor at a specific geodetic location using a heading. */
  private Anchor createAnchor(
          Earth earth, double latitude, double longitude, double altitude, double headingDegrees) {
    // Convert a heading to a EUS quaternion:
    double angleRadians = Math.toRadians(180.0f - headingDegrees);
    Anchor anchor =
            earth.createAnchor(
                    latitude,
                    longitude,
                    altitude,
                    0.0f,
                    (float) Math.sin(angleRadians / 2),
                    0.0f,
                    (float) Math.cos(angleRadians / 2));
    anchors.add(anchor);
    if (anchors.size() > MAXIMUM_ANCHORS) {
      anchors.remove(0);
    }
    return anchor;
  }

  @Override
  public void onSurfaceCreated(SampleRender render) {
    // Prepare the rendering objects. This involves reading shaders and 3D model files, so may throw
    // an IOException.
    try {
      backgroundRenderer = new BackgroundRenderer(render);
      virtualSceneFramebuffer = new Framebuffer(render, /*width=*/ 1, /*height=*/ 1);


      backgroundRenderer.setUseDepthVisualization(render, false);
      backgroundRenderer.setUseOcclusion(render, false);
    } catch (IOException e) {
    }
  }

  @Override
  public void onSurfaceChanged(SampleRender render, int width, int height) {
    displayRotationHelper.onSurfaceChanged(width, height);
    virtualSceneFramebuffer.resize(width, height);
  }

  public void onDrawFrame(SampleRender render) {
    if (session == null) {
      return;
    }

    // Texture names should only be set once on a GL thread unless they change. This is done during
    // onDrawFrame rather than onSurfaceCreated since the session is not guaranteed to have been
    // initialized during the execution of onSurfaceCreated.
    if (!hasSetTextureNames) {
      session.setCameraTextureNames(
              new int[] {backgroundRenderer.getCameraColorTexture().getTextureId()});
      hasSetTextureNames = true;
    }

    // -- Update per-frame state

    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session);

    // Obtain the current frame from ARSession. When the configuration is set to
    // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
    // camera framerate.
    Frame frame;
    try {
      frame = session.update();
    } catch (CameraNotAvailableException e) {
      return;
    }
    Camera camera = frame.getCamera();

    // BackgroundRenderer.updateDisplayGeometry must be called every frame to update the coordinates
    // used to draw the background camera image.
    backgroundRenderer.updateDisplayGeometry(frame);

    // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
    trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());

    Earth earth = session.getEarth();
    if (earth != null) {
      updateGeospatialState(earth);
    }

    // Show a message based on whether tracking has failed, if planes are detected, and if the user
    // has placed any objects.
    String message = null;
    switch (state) {
      case UNINITIALIZED:
        break;
      case UNSUPPORTED:
        message = getResources().getString(R.string.status_unsupported);
        break;
      case PRETRACKING:
        message = getResources().getString(R.string.status_pretracking);
        break;
      case EARTH_STATE_ERROR:
        message = getResources().getString(R.string.status_earth_state_error);
        break;
      case LOCALIZING:
        message = getResources().getString(R.string.status_localize_hint);
        break;
      case LOCALIZING_FAILED:
        message = getResources().getString(R.string.status_localize_timeout);
        break;
      case LOCALIZED:
        if (anchors.size() > 0) {
          message =
                  getResources()
                          .getQuantityString(R.plurals.status_anchors_set, anchors.size(), anchors.size());

        } else if (clearedAnchorsAmount != null) {
          message =
                  getResources()
                          .getQuantityString(
                                  R.plurals.status_anchors_cleared, clearedAnchorsAmount, clearedAnchorsAmount);
        } else {
          message = getResources().getString(R.string.status_localize_complete);
        }
        break;
    }
    if (message == null) {
      lastStatusText = null;
      runOnUiThread(() -> statusTextView.setVisibility(View.INVISIBLE));
    } else if (lastStatusText != message) {
      lastStatusText = message;
      runOnUiThread(
              () -> {
                statusTextView.setVisibility(View.VISIBLE);
                statusTextView.setText(lastStatusText);
              });
    }

    // -- Draw background

    if (frame.getTimestamp() != 0) {
      // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
      // drawing possible leftover data from previous sessions if the texture is reused.
      backgroundRenderer.drawBackground(render);
    }

    // If not tracking, don't draw 3D objects.
    if (camera.getTrackingState() != TrackingState.TRACKING || state != State.LOCALIZED) {
      return;
    }

    // -- Draw virtual objects

    // Get projection matrix.
    camera.getProjectionMatrix(projectionMatrix, 0, Z_NEAR, Z_FAR);

    // Get camera matrix and draw.
    camera.getViewMatrix(viewMatrix, 0);

    // Visualize anchors created by touch.
    render.clear(virtualSceneFramebuffer, 0f, 0f, 0f, 0f);

    for (Anchor anchor : anchors) {
      // Get the current pose of an Anchor in world space. The Anchor pose is updated
      // during calls to session.update() as ARCore refines its estimate of the world.
      anchor.getPose().toMatrix(modelMatrix, 0);

      // Create the Anchor.
      AnchorNode anchorNode = new AnchorNode(anchor);
      anchorNode.setParent(arFragment.getArSceneView().getScene());

      // Add text to anchor
      Node titleNode = new Node();
      titleNode.setParent(anchorNode);
      titleNode.setEnabled(false);
      titleNode.setLocalPosition(new Vector3(0.0f, 1.0f, 0.0f));
      titleNode.setRenderable(viewRenderable);
      titleNode.setEnabled(true);
      /*// Calculate model/view/projection matrices
      Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0);
      Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0);

      // Update shader properties and draw
      virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix);

      render.draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer);*/
    }

    // Compose the virtual scene with the background.
    backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR);

  }

  /** Change behavior depending on the current {@link State} of the application. */
  private void updateGeospatialState(Earth earth) {
    if (state == State.PRETRACKING) {
      updatePretrackingState(earth);
    } else if (state == State.LOCALIZING) {
      updateLocalizingState(earth);
    } else if (state == State.LOCALIZED) {
      updateLocalizedState(earth);
    }
  }

  /**
   * Handles the updating for {@link State.PRETRACKING}. In this state, wait for {@link Earth} to
   * have {@link TrackingState.TRACKING}. If it hasn't been enabled by now, then we've encountered
   * an unrecoverable {@link State.EARTH_STATE_ERROR}.
   */
  private void updatePretrackingState(Earth earth) {
    if (earth.getTrackingState() == TrackingState.TRACKING) {
      state = State.LOCALIZING;
      return;
    }

    if (earth.getEarthState() != Earth.EarthState.ENABLED) {
      messageSnackbarHelper.showError(this, earth.getEarthState().toString());
      state = State.EARTH_STATE_ERROR;
      return;
    }

    runOnUiThread(() -> geospatialPoseTextView.setText(R.string.geospatial_pose_not_tracking));
  }

  /**
   * Handles the updating for {@link State.LOCALIZING}. In this state, wait for the horizontal and
   * heading threshold to improve until it reaches your threshold.
   *
   * <p>If it takes too long for the threshold to be reached, this could mean that GPS data isn't
   * accurate enough, or that the user is in an area that can't be localized with StreetView.
   */
  private void updateLocalizingState(Earth earth) {
    GeospatialPose geospatialPose = earth.getCameraGeospatialPose();
    if (geospatialPose.getHorizontalAccuracy() <= LOCALIZING_HORIZONTAL_ACCURACY_THRESHOLD_METERS
            && geospatialPose.getHeadingAccuracy() <= LOCALIZING_HEADING_ACCURACY_THRESHOLD_DEGREES) {
      state = State.LOCALIZED;
      if (anchors.isEmpty()) {
        createAnchorFromSharedPreferences(earth);
      }
      runOnUiThread(
              () -> {
                setAnchorButton.setVisibility(View.VISIBLE);
              });
      return;
    }

    if (TimeUnit.MILLISECONDS.toSeconds(System.currentTimeMillis() - localizingStartTimestamp)
            > LOCALIZING_TIMEOUT_SECONDS) {
      state = State.LOCALIZING_FAILED;
      return;
    }

    updateGeospatialPoseText(geospatialPose);
  }

  /**
   * Handles the updating for {@link State.LOCALIZED}. In this state, check the accuracy for
   * degradation and return to {@link State.LOCALIZING} if the position accuracies have dropped too
   * low.
   */
  private void updateLocalizedState(Earth earth) {
    GeospatialPose geospatialPose = earth.getCameraGeospatialPose();
    // Check if either accuracy has degraded to the point we should enter back into the LOCALIZING
    // state.
    if (geospatialPose.getHorizontalAccuracy()
            > LOCALIZING_HORIZONTAL_ACCURACY_THRESHOLD_METERS
            + LOCALIZED_HORIZONTAL_ACCURACY_HYSTERESIS_METERS
            || geospatialPose.getHeadingAccuracy()
            > LOCALIZING_HEADING_ACCURACY_THRESHOLD_DEGREES
            + LOCALIZED_HEADING_ACCURACY_HYSTERESIS_DEGREES) {
      // Accuracies have degenerated, return to the localizing state.
      state = State.LOCALIZING;
      localizingStartTimestamp = System.currentTimeMillis();
      runOnUiThread(
              () -> {
                setAnchorButton.setVisibility(View.INVISIBLE);
                clearAnchorsButton.setVisibility(View.INVISIBLE);
              });
      return;
    }

    updateGeospatialPoseText(geospatialPose);
  }

  private void updateGeospatialPoseText(GeospatialPose geospatialPose) {
    String poseText =
            getResources()
                    .getString(
                            R.string.geospatial_pose,
                            geospatialPose.getLatitude(),
                            geospatialPose.getLongitude(),
                            geospatialPose.getHorizontalAccuracy(),
                            geospatialPose.getAltitude(),
                            geospatialPose.getVerticalAccuracy(),
                            geospatialPose.getHeading(),
                            geospatialPose.getHeadingAccuracy());
    runOnUiThread(
            () -> {
              geospatialPoseTextView.setText(poseText);
            });
  }

  /** Creates all anchors that were stored in the {@link SharedPreferences}. */
  private void createAnchorFromSharedPreferences(Earth earth) {
    Set<String> anchorParameterSet =
            sharedPreferences.getStringSet(SHARED_PREFERENCES_SAVED_ANCHORS, null);
    if (anchorParameterSet == null) {
      return;
    }

    for (String anchorParameters : anchorParameterSet) {
      String[] parameters = anchorParameters.split(",");
      if (parameters.length != 4) {
        return;
      }
      double latitude = Double.parseDouble(parameters[0]);
      double longitude = Double.parseDouble(parameters[1]);
      double altitude = Double.parseDouble(parameters[2]);
      double heading = Double.parseDouble(parameters[3]);
      createAnchor(earth, latitude, longitude, altitude, heading);
    }

    runOnUiThread(() -> clearAnchorsButton.setVisibility(View.VISIBLE));
  }
}


