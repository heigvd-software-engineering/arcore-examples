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
import com.google.ar.sceneform.Sceneform;
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
public class GeospatialActivity extends AppCompatActivity
    implements SampleRender.Renderer, FragmentOnAttachListener, PrivacyNoticeDialogFragment.NoticeDialogListener, BaseArFragment.OnSessionConfigurationListener, BaseArFragment.OnTapArPlaneListener {

  private static final String TAG = GeospatialActivity.class.getSimpleName();

  private static final String SHARED_PREFERENCES_SAVED_ANCHORS = "SHARED_PREFERENCES_SAVED_ANCHORS";
  private static final String ALLOW_GEOSPATIAL_ACCESS_KEY = "ALLOW_GEOSPATIAL_ACCESS";

  private static final float Z_NEAR = 0.1f;
  private static final float Z_FAR = 1000f;

  // The thresholds that are required for horizontal and heading accuracies before entering into the
  // LOCALIZED state. Once the accuracies are equal or less than these values, the app will
  // allow the user to place anchors.
  private static final double LOCALIZING_HORIZONTAL_ACCURACY_THRESHOLD_METERS = 10;
  private static final double LOCALIZING_HEADING_ACCURACY_THRESHOLD_DEGREES = 15;

  // Once in the LOCALIZED state, if either accuracies degrade beyond these amounts, the app will
  // revert back to the LOCALIZING state.
  private static final double LOCALIZED_HORIZONTAL_ACCURACY_HYSTERESIS_METERS = 10;
  private static final double LOCALIZED_HEADING_ACCURACY_HYSTERESIS_DEGREES = 10;

  private static final int LOCALIZING_TIMEOUT_SECONDS = 180;
  private static final int MAXIMUM_ANCHORS = 10;

  // Rendering. The Renderers are created here, and initialized when the GL surface is created.
  private GLSurfaceView surfaceView;

  private boolean installRequested;
  private Integer clearedAnchorsAmount = null;

  // Default anchor localisation
  private double latitude = 46.764503;
  private double longitude = 6.646427;
  private double altitude = 492;
  private double headingDegree = 20.8;

  private Anchor defaultAnchor;
  private ArFragment arFragment;

  private ViewRenderable viewRenderable;

  /** Timer to keep track of how much time has passed since localizing has started. */
  private long localizingStartTimestamp;


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

  private Session session;
  private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
  private DisplayRotationHelper displayRotationHelper;
  private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);
  private SampleRender render;
  private SharedPreferences sharedPreferences;

  private String lastStatusText;
  private TextView geospatialPoseTextView;
  private TextView statusTextView;
  private Button setAnchorButton;
  private Button clearAnchorsButton;

  private BackgroundRenderer backgroundRenderer;
  private Framebuffer virtualSceneFramebuffer;
  private boolean hasSetTextureNames = false;

  // Virtual object (ARCore geospatial)
  private Mesh virtualObjectMesh;
  private Shader virtualObjectShader;

  private final List<Anchor> anchors = new ArrayList<>();

  // Temporary matrix allocated here to reduce number of allocations for each frame.
  private final float[] modelMatrix = new float[16];
  private final float[] viewMatrix = new float[16];
  private final float[] projectionMatrix = new float[16];
  private final float[] modelViewMatrix = new float[16]; // view x model
  private final float[] modelViewProjectionMatrix = new float[16]; // projection x view x model

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    sharedPreferences = getPreferences(Context.MODE_PRIVATE);

    setContentView(R.layout.activity_main);
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
  public void onTapPlane(HitResult hitResult, Plane plane, MotionEvent motionEvent) {
    handleSetAnchorButton();
  }

  @Override
  protected void onDestroy() {
    if (session != null) {
      // Explicitly close ARCore Session to release native resources.
      // Review the API reference for important considerations before calling close() in apps with
      // more complicated lifecycle requirements:
      // https://developers.google.com/ar/reference/java/arcore/reference/com/google/ar/core/Session#close()
      session.close();
      session = null;
    }

    super.onDestroy();
  }

  @Override
  protected void onResume() {
    super.onResume();

    if (sharedPreferences.getBoolean(ALLOW_GEOSPATIAL_ACCESS_KEY, /*defValue=*/ false)) {
      createSession();
    } else {
      showPrivacyNoticeDialog();
    }

    surfaceView.onResume();
    displayRotationHelper.onResume();
  }

  private void showPrivacyNoticeDialog() {
    DialogFragment dialog = PrivacyNoticeDialogFragment.createDialog();
    dialog.show(getSupportFragmentManager(), PrivacyNoticeDialogFragment.class.getName());
  }

  private void createSession() {
    Exception exception = null;
    String message = null;
    if (session == null) {

      try {
        switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
          case INSTALL_REQUESTED:
            installRequested = true;
            return;
          case INSTALLED:
            break;
        }

        // ARCore requires camera permissions to operate. If we did not yet obtain runtime
        // permission on Android M and above, now is a good time to ask the user for it.
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
          CameraPermissionHelper.requestCameraPermission(this);
          return;
        }
        if (!LocationPermissionHelper.hasFineLocationPermission(this)) {
          LocationPermissionHelper.requestFineLocationPermission(this);
          return;
        }

        // Create the session.
        session = new Session(/* context= */ this);
      } catch (UnavailableArcoreNotInstalledException
          | UnavailableUserDeclinedInstallationException e) {
        message = "Please install ARCore";
        exception = e;
      } catch (UnavailableApkTooOldException e) {
        message = "Please update ARCore";
        exception = e;
      } catch (UnavailableSdkTooOldException e) {
        message = "Please update this app";
        exception = e;
      } catch (UnavailableDeviceNotCompatibleException e) {
        message = "This device does not support AR";
        exception = e;
      } catch (Exception e) {
        message = "Failed to create AR session";
        exception = e;
      }

      if (message != null) {
        messageSnackbarHelper.showError(this, message);
        Log.e(TAG, "Exception creating session", exception);
        return;
      }
    }

    // Note that order matters - see the note in onPause(), the reverse applies here.
    try {
      configureSession();
      // To record a live camera session for later playback, call
      // `session.startRecording(recordingConfig)` at anytime. To playback a previously recorded AR
      // session instead of using the live camera feed, call
      // `session.setPlaybackDatasetUri(Uri)` before calling `session.resume()`. To
      // learn more about recording and playback, see:
      // https://developers.google.com/ar/develop/java/recording-and-playback
      session.resume();
    } catch (CameraNotAvailableException e) {
      message = "Camera not available. Try restarting the app.";
      exception = e;
    } catch (GooglePlayServicesLocationLibraryNotLinkedException e) {
      message = "Google Play Services location library not linked or obfuscated with Proguard.";
      exception = e;
    } catch (FineLocationPermissionNotGrantedException e) {
      message = "The Android permission ACCESS_FINE_LOCATION was not granted.";
      exception = e;
    } catch (UnsupportedConfigurationException e) {
      message = "This device does not support GeospatialMode.ENABLED.";
      exception = e;
    } catch (SecurityException e) {
      message = "Camera failure or the internet permission has not been granted.";
      exception = e;
    }

    if (message != null) {
      session = null;
      messageSnackbarHelper.showError(this, message);
      Log.e(TAG, "Exception configuring and resuming the session", exception);
      return;
    }
  }

  @Override
  public void onPause() {
    super.onPause();
    if (session != null) {
      // Note that the order matters - GLSurfaceView is paused first so that it does not try
      // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
      // still call session.update() and get a SessionPausedException.
      displayRotationHelper.onPause();
      surfaceView.onPause();
      session.pause();
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      // Use toast instead of snackbar here since the activity will exit.
      Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
          .show();
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
        CameraPermissionHelper.launchPermissionSettings(this);
      }
      finish();
    }
    // Check if this result pertains to the location permission.
    if (LocationPermissionHelper.hasFineLocationPermissionsResponseInResult(permissions)
        && !LocationPermissionHelper.hasFineLocationPermission(this)) {
      // Use toast instead of snackbar here since the activity will exit.
      Toast.makeText(
              this,
              "Precise location permission is needed to run this application",
              Toast.LENGTH_LONG)
          .show();
      if (!LocationPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
        LocationPermissionHelper.launchPermissionSettings(this);
      }
      finish();
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
  }

  @Override
  public void onSurfaceCreated(SampleRender render) {
    // Prepare the rendering objects. This involves reading shaders and 3D model files, so may throw
    // an IOException.
    try {
      backgroundRenderer = new BackgroundRenderer(render);
      virtualSceneFramebuffer = new Framebuffer(render, /*width=*/ 1, /*height=*/ 1);

      // Virtual object to render (ARCore geospatial)
      Texture virtualObjectTexture =
          Texture.createFromAsset(
              render,
              "models/black_texture.png",
              Texture.WrapMode.CLAMP_TO_EDGE,
              Texture.ColorFormat.SRGB);

      virtualObjectMesh = Mesh.createFromAsset(render, "models/arrow.obj");
      virtualObjectShader =
          Shader.createFromAssets(
                  render,
                  "shaders/ar_unlit_object.vert",
                  "shaders/ar_unlit_object.frag",
                  /*defines=*/ null)
              .setTexture("u_Texture", virtualObjectTexture);

      backgroundRenderer.setUseDepthVisualization(render, false);
      backgroundRenderer.setUseOcclusion(render, false);
    } catch (IOException e) {
      Log.e(TAG, "Failed to read a required asset file", e);
      messageSnackbarHelper.showError(this, "Failed to read a required asset file: " + e);
    }
  }

  @Override
  public void onSurfaceChanged(SampleRender render, int width, int height) {
    displayRotationHelper.onSurfaceChanged(width, height);
    virtualSceneFramebuffer.resize(width, height);
  }

  public void getDefaultAnchor() {

  }

  @Override
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
      Log.e(TAG, "Camera not available during onDrawFrame", e);
      messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
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
      updateDefaultAnchor();
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


      // Calculate model/view/projection matrices
      Matrix.multiplyMM(modelViewMatrix, 0, viewMatrix, 0, modelMatrix, 0);
      Matrix.multiplyMM(modelViewProjectionMatrix, 0, projectionMatrix, 0, modelViewMatrix, 0);

      // Update shader properties and draw
      virtualObjectShader.setMat4("u_ModelViewProjection", modelViewProjectionMatrix);

      render.draw(virtualObjectMesh, virtualObjectShader, virtualSceneFramebuffer);
    }

    // Compose the virtual scene with the background.
    backgroundRenderer.drawVirtualScene(render, virtualSceneFramebuffer, Z_NEAR, Z_FAR);

  }

  @Override
  protected void onPostResume() {
    super.onPostResume();

    /** WIP Communication with a grpc service to get a default anchor **/
    //new GrpcTask(this).execute("10.0.2.2", "9090");

    // Set the default anchor
    /*Earth earth = session.getEarth();
    this.defaultAnchor = createAnchor(earth, latitude, longitude, altitude, headingDegree);
    storeAnchorParameters(latitude, longitude, altitude, headingDegree);
    messageSnackbarHelper.showMessageWithDismiss(this,"Default anchor set!");*/

    // -----

    /*ExecutorService executor = Executors.newSingleThreadExecutor();
    Handler handler = new Handler(Looper.getMainLooper());

    executor.execute(new Runnable() {
      @Override
      public void run() {
        try {
          ManagedChannel channel = ManagedChannelBuilder.forAddress("10.193.72.102", 443).usePlaintext().build();
          MapServiceGrpc.MapServiceBlockingStub stub = MapServiceGrpc.newBlockingStub(channel);
          LocalRestaurantRequest request = LocalRestaurantRequest.newBuilder().setLatitude(46.764503).setLongitude(6.646427).build();
          LocalRestaurantReply reply = stub.getLocalRestaurant(request);
          handler.post(new Runnable() {
            @Override
            public void run() {
              Earth earth = session.getEarth();
              LocalRestaurant localRestaurant = reply.getLocalRestaurant(1);
              createAnchor(earth, localRestaurant.getLatitude(), localRestaurant.getLongitude(), localRestaurant.getAltitude(), 0);
            }
          });
        } catch (Exception e) {
          StringWriter sw = new StringWriter();
          PrintWriter pw = new PrintWriter(sw);
          e.printStackTrace(pw);
          pw.flush();
          showErrorMessage(e);
        }
      }
    });*/
  }

  private void showErrorMessage(Exception e) {
    messageSnackbarHelper.showError(this, e.getMessage());
  }

  private void showSuccessMessage() {
    messageSnackbarHelper.showMessage(this, "Success!");

  }

  /** Configures the session with feature settings. */
  private void configureSession() {
    // Earth mode may not be supported on this device due to insufficient sensor quality.
    if (!session.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) {
      state = State.UNSUPPORTED;
      return;
    }

    Config config = session.getConfig();
    config.setGeospatialMode(Config.GeospatialMode.ENABLED);
    session.configure(config);
    state = State.PRETRACKING;
    localizingStartTimestamp = System.currentTimeMillis();
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
        Log.d(
            TAG, "Invalid number of anchor parameters. Expected four, found " + parameters.length);
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

  @Override
  public void onDialogPositiveClick(DialogFragment dialog) {
    if (!sharedPreferences.edit().putBoolean(ALLOW_GEOSPATIAL_ACCESS_KEY, true).commit()) {
      throw new AssertionError("Could not save the user preference to SharedPreferences!");
    }
    createSession();
  }

  /*private static class GrpcTask extends AsyncTask<String, List<LocalRestaurant>, List<LocalRestaurant>> {
    private final WeakReference<Activity> activityReference;
    private ManagedChannel channel;

    private GrpcTask(Activity activity) {
      this.activityReference = new WeakReference<Activity>(activity);
    }

    @Override
    protected List<LocalRestaurant> doInBackground(String... params) {
      String host = params[0];
      String portStr = params[1];
      int port = TextUtils.isEmpty(portStr) ? 0 : Integer.valueOf(portStr);
      try {
        channel = ManagedChannelBuilder.forAddress(host, port).usePlaintext().build();
        MapServiceGrpc.MapServiceBlockingStub stub = MapServiceGrpc.newBlockingStub(channel);
        LocalRestaurantRequest request = LocalRestaurantRequest.newBuilder().setLatitude(46.764503).setLongitude(6.646427).build();
        LocalRestaurantReply reply = stub.getLocalRestaurant(request);
        return reply.getLocalRestaurantList();
      } catch (Exception e) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        e.printStackTrace(pw);
        pw.flush();
        return null;
      }
    }

    @Override
    protected List<LocalRestaurant> onPostExecute(List<LocalRestaurant> result) {
      try {
        channel.shutdown().awaitTermination(1, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      Activity activity = activityReference.get();
      if (activity == null) {
        return;
      }
      SnackbarHelper messageSnackbar = new SnackbarHelper();
      String resultString = result.stream().map(r -> r.getName()).collect(Collectors.joining(","));
      messageSnackbar.showMessageWithDismiss(activity, resultString);
      Earth earth = session.getEarth();
      createAnchor(earth, latitude, longitude, altitude, headingDegree);
      storeAnchorParameters(latitude, longitude, altitude, headingDegree);
    }
  }*/
}


