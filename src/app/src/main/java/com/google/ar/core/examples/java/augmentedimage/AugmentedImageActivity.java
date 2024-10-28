/*
 * Copyright 2018 Google LLC
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

package com.google.ar.core.examples.java.augmentedimage;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.RequestManager;
import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.AugmentedImage;
import com.google.ar.core.AugmentedImageDatabase;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.examples.java.augmentedimage.rendering.AugmentedImageRenderer;
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper;
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper;
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper;
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper;
import com.google.ar.core.examples.java.common.helpers.TrackingStateHelper;
import com.google.ar.core.examples.java.common.rendering.BackgroundRenderer;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * This app extends the HelloAR Java app to include image tracking functionality.
 *
 * <p>In this example, we assume all images are static or moving slowly with a large occupation of
 * the screen. If the target is actively moving, we recommend to check
 * AugmentedImage.getTrackingMethod() and render only when the tracking method equals to
 * FULL_TRACKING. See details in <a
 * href="https://developers.google.com/ar/develop/java/augmented-images/">Recognize and Augment
 * Images</a>.
 */
public class AugmentedImageActivity extends AppCompatActivity implements GLSurfaceView.Renderer {
    private static final String TAG = AugmentedImageActivity.class.getSimpleName();

    // Rendering. The Renderers are created here, and initialized when the GL surface is created.
    private GLSurfaceView surfaceView;
    //  private ImageView fitToScanView;
//  private RequestManager glideRequestManager;
    private GestureDetector gestureDetector;
    private boolean installRequested;

    private float[] viewMatrix = new float[16];
    private float[] projectionMatrix = new float[16];

    private Session session;
    private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
    private DisplayRotationHelper displayRotationHelper;
    private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);

    private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
    private final AugmentedImageRenderer augmentedImageRenderer = new AugmentedImageRenderer();

    private boolean shouldConfigureSession = false;

    // Augmented image configuration and rendering.
    // Load a single image (true) or a pre-generated image database (false).
    private final boolean useSingleImage = false;
    // Augmented image and its associated center pose anchor, keyed by index of the augmented image in
    // the
    // database.
    private final Map<Integer, Pair<AugmentedImage, Anchor>> augmentedImageMap = new HashMap<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        surfaceView = findViewById(R.id.surfaceview);
        displayRotationHelper = new DisplayRotationHelper(/*context=*/ this);

        // Set up renderer.
        surfaceView.setPreserveEGLContextOnPause(true);
        surfaceView.setEGLContextClientVersion(2);
        surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
        surfaceView.setRenderer(this);
        surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        surfaceView.setWillNotDraw(false);

//    fitToScanView = findViewById(R.id.image_view_fit_to_scan);
//    glideRequestManager = Glide.with(this);
//    glideRequestManager
//        .load(Uri.parse("file:///android_asset/fit_to_scan.png"))
//        .into(fitToScanView);

        installRequested = false;

        gestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                handleTap(e);
                return true;
            }

            @Override
            public boolean onDown(MotionEvent e) {
                return true; // This is necessary to ensure onSingleTapUp is triggered
            }
        });

        surfaceView.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));
    }

    private void handleTap(MotionEvent event) {
        // Obtener la posición del toque
        float x = event.getX();
        float y = event.getY();

        // Verificar si el toque está en el objeto
        for (Pair<AugmentedImage, Anchor> pair : augmentedImageMap.values()) {
            AugmentedImage augmentedImage = pair.first;
            if (augmentedImage.getTrackingState() == TrackingState.TRACKING) {
                // Obtener las coordenadas del objeto
                float[] objectCoords = new float[16];
                augmentedImage.getCenterPose().toMatrix(objectCoords, 0);

                // Verificar si el toque está dentro del área del objeto
                if (isPointInsideObject(x, y, objectCoords)) {
                    // Abrir la página web en el navegador externo
                    openWebPage("https://www.uade.edu.ar");
                    break;
                }
            }
        }
    }

    private float[] getObjectDimensions(float[] objectCoords) {
        // Define the corners of the object in world space
        float[] worldCoordsTopLeft = {objectCoords[0], objectCoords[1], objectCoords[2], 1.0f};
        float[] worldCoordsBottomRight = {objectCoords[8], objectCoords[9], objectCoords[10], 1.0f};

        // Log the world coordinates for debugging
        Log.d(TAG, "World coordinates top-left: (" + worldCoordsTopLeft[0] + ", " + worldCoordsTopLeft[1] + ", " + worldCoordsTopLeft[2] + ")");
        Log.d(TAG, "World coordinates bottom-right: (" + worldCoordsBottomRight[0] + ", " + worldCoordsBottomRight[1] + ", " + worldCoordsBottomRight[2] + ")");

        // Convert world coordinates to view coordinates
        float[] viewCoordsTopLeft = new float[4];
        float[] viewCoordsBottomRight = new float[4];
        Matrix.multiplyMV(viewCoordsTopLeft, 0, viewMatrix, 0, worldCoordsTopLeft, 0);
        Matrix.multiplyMV(viewCoordsBottomRight, 0, viewMatrix, 0, worldCoordsBottomRight, 0);

        // Log the view coordinates for debugging
        Log.d(TAG, "View coordinates top-left: (" + viewCoordsTopLeft[0] + ", " + viewCoordsTopLeft[1] + ", " + viewCoordsTopLeft[2] + ")");
        Log.d(TAG, "View coordinates bottom-right: (" + viewCoordsBottomRight[0] + ", " + viewCoordsBottomRight[1] + ", " + viewCoordsBottomRight[2] + ")");

        // Convert view coordinates to screen coordinates
        float[] screenCoordsTopLeft = new float[4];
        float[] screenCoordsBottomRight = new float[4];
        Matrix.multiplyMV(screenCoordsTopLeft, 0, projectionMatrix, 0, viewCoordsTopLeft, 0);
        Matrix.multiplyMV(screenCoordsBottomRight, 0, projectionMatrix, 0, viewCoordsBottomRight, 0);

        // Log the screen coordinates for debugging
        Log.d(TAG, "Screen coordinates top-left: (" + screenCoordsTopLeft[0] + ", " + screenCoordsTopLeft[1] + ", " + screenCoordsTopLeft[2] + ")");
        Log.d(TAG, "Screen coordinates bottom-right: (" + screenCoordsBottomRight[0] + ", " + screenCoordsBottomRight[1] + ", " + screenCoordsBottomRight[2] + ")");

        // Normalize the screen coordinates
        float screenXTopLeft = (screenCoordsTopLeft[0] / screenCoordsTopLeft[3] + 1) / 2 * surfaceView.getWidth();
        float screenYTopLeft = (1 - screenCoordsTopLeft[1] / screenCoordsTopLeft[3]) / 2 * surfaceView.getHeight();
        float screenXBottomRight = (screenCoordsBottomRight[0] / screenCoordsBottomRight[3] + 1) / 2 * surfaceView.getWidth();
        float screenYBottomRight = (1 - screenCoordsBottomRight[1] / screenCoordsBottomRight[3]) / 2 * surfaceView.getHeight();

        // Calculate the width and height of the object on the screen
        float objectWidth = Math.abs(screenXBottomRight - screenXTopLeft);
        float objectHeight = Math.abs(screenYBottomRight - screenYTopLeft);

        // Log the calculated dimensions for debugging
        Log.d(TAG, "Calculated object dimensions: width=" + objectWidth + ", height=" + objectHeight);

        return new float[]{objectWidth, objectHeight};
    }

    private boolean isPointInsideObject(float x, float y, float[] objectCoords) {
        // Extract the object's center coordinates from the matrix
        float[] worldCoords = new float[4];
        worldCoords[0] = objectCoords[12];
        worldCoords[1] = objectCoords[13];
        worldCoords[2] = objectCoords[14];
        worldCoords[3] = 1.0f;

        // Convert world coordinates to view coordinates
        float[] viewCoords = new float[4];
        Matrix.multiplyMV(viewCoords, 0, viewMatrix, 0, worldCoords, 0);

        // Convert view coordinates to screen coordinates
        float[] screenCoords = new float[4];
        Matrix.multiplyMV(screenCoords, 0, projectionMatrix, 0, viewCoords, 0);

        // Normalize the screen coordinates
        float screenX = (screenCoords[0] / screenCoords[3] + 1) / 2 * surfaceView.getWidth();
        float screenY = (1 - screenCoords[1] / screenCoords[3]) / 2 * surfaceView.getHeight();

        // Log the coordinates for debugging
        Log.d(TAG, "Touch coordinates: (" + x + ", " + y + ")");
        Log.d(TAG, "Object screen coordinates: (" + screenX + ", " + screenY + ")");

        float[] dimensions = getObjectDimensions(objectCoords);
        float objectWidth = dimensions[0];
        float objectHeight = dimensions[1];

        // Log the object dimensions for debugging
        Log.d(TAG, "Object dimensions: width=" + objectWidth + ", height=" + objectHeight);

        // Check if the touch point is inside the object's area
        boolean isInside = x >= screenX - objectWidth / 2 && x <= screenX + objectWidth / 2 &&
                y >= screenY - objectHeight / 2 && y <= screenY + objectHeight / 2;

        Log.d(TAG, "Is touch inside object: " + isInside);
        return isInside;
    }

    private void openWebPage(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        startActivity(intent);
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

        if (session == null) {
            Exception exception = null;
            String message = null;
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

                session = new Session(/* context = */ this);
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
            } catch (Exception e) {
                message = "This device does not support AR";
                exception = e;
            }

            if (message != null) {
                messageSnackbarHelper.showError(this, message);
                Log.e(TAG, "Exception creating session", exception);
                return;
            }

            shouldConfigureSession = true;
        }

        if (shouldConfigureSession) {
            configureSession();
            shouldConfigureSession = false;
        }

        // Note that order matters - see the note in onPause(), the reverse applies here.
        try {
            session.resume();
        } catch (CameraNotAvailableException e) {
            messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
            session = null;
            return;
        }
        surfaceView.onResume();
        displayRotationHelper.onResume();

//    fitToScanView.setVisibility(View.VISIBLE);
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
            Toast.makeText(
                            this, "Camera permissions are needed to run this application", Toast.LENGTH_LONG)
                    .show();
            if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
                // Permission denied with checking "Do not ask again".
                CameraPermissionHelper.launchPermissionSettings(this);
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
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

        // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
        try {
            // Create the texture and pass it to ARCore session to be filled during update().
            backgroundRenderer.createOnGlThread(/*context=*/ this);
            augmentedImageRenderer.createOnGlThread(/*context=*/ this);
        } catch (IOException e) {
            Log.e(TAG, "Failed to read an asset file", e);
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        displayRotationHelper.onSurfaceChanged(width, height);
        GLES20.glViewport(0, 0, width, height);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        // Limpiar la pantalla para notificar al controlador que no debe cargar ningún píxel del cuadro anterior.
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        if (session == null) {
            return;
        }
        // Notificar a la sesión de ARCore que el tamaño de la vista cambió para que la matriz de perspectiva y
        // el fondo de video se ajusten correctamente.
        displayRotationHelper.updateSessionIfNeeded(session);

        try {
            session.setCameraTextureName(backgroundRenderer.getTextureId());

            // Obtener el cuadro actual de ARSession. Cuando la configuración está establecida en
            // UpdateMode.BLOCKING (es por defecto), esto limitará la renderización a la
            // velocidad de fotogramas de la cámara.
            Frame frame = session.update();
            Camera camera = frame.getCamera();

            // Mantener la pantalla desbloqueada mientras se realiza el seguimiento, pero permitir que se bloquee cuando el seguimiento se detenga.
            trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());

            // Si el cuadro está listo, renderizar la imagen de vista previa de la cámara en la superficie GL.
            backgroundRenderer.draw(frame);

            // Obtener la matriz de proyección.
            camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f);

            // Obtener la matriz de la cámara y dibujar.
            camera.getViewMatrix(viewMatrix, 0);

            // Calcular la iluminación a partir de la intensidad promedio de la imagen.
            final float[] colorCorrectionRgba = new float[4];
            frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);

            // Visualizar imágenes aumentadas.
            drawAugmentedImages(frame, projectionMatrix, viewMatrix, colorCorrectionRgba);
        } catch (Throwable t) {
            // Evitar que la aplicación se bloquee debido a excepciones no controladas.
            Log.e(TAG, "Excepción en el hilo de OpenGL", t);
        }
    }

    private void configureSession() {
        Config config = new Config(session);
        config.setFocusMode(Config.FocusMode.AUTO);
        if (!setupAugmentedImageDatabase(config)) {
            messageSnackbarHelper.showError(this, "Could not setup augmented image database");
        }
        session.configure(config);
    }

    private void drawAugmentedImages(
            Frame frame, float[] projmtx, float[] viewmtx, float[] colorCorrectionRgba) {
        Collection<AugmentedImage> updatedAugmentedImages =
                frame.getUpdatedTrackables(AugmentedImage.class);

        // Iterate to update augmentedImageMap, remove elements we cannot draw.
        for (AugmentedImage augmentedImage : updatedAugmentedImages) {
            switch (augmentedImage.getTrackingState()) {
                case PAUSED:
                    // When an image is in PAUSED state, but the camera is not PAUSED, it has been detected,
                    // but not yet tracked.
//                    String text = String.format("Detected Image %d", augmentedImage.getIndex());
//                    messageSnackbarHelper.showMessage(this, text);
                    break;

                case TRACKING:
                    // Have to switch to UI Thread to update View.
//          this.runOnUiThread(
//              new Runnable() {
//                @Override
//                public void run() {
//                  fitToScanView.setVisibility(View.GONE);
//                }
//              });

                    // Create a new anchor for newly found images.
                    if (!augmentedImageMap.containsKey(augmentedImage.getIndex())) {
                        Anchor centerPoseAnchor = augmentedImage.createAnchor(augmentedImage.getCenterPose());
                        augmentedImageMap.put(
                                augmentedImage.getIndex(), Pair.create(augmentedImage, centerPoseAnchor));
                    }
                    break;

                case STOPPED:
                    augmentedImageMap.remove(augmentedImage.getIndex());
                    break;

                default:
                    break;
            }
        }

        // Draw all images in augmentedImageMap
        for (Pair<AugmentedImage, Anchor> pair : augmentedImageMap.values()) {
            AugmentedImage augmentedImage = pair.first;
            Anchor centerAnchor = augmentedImageMap.get(augmentedImage.getIndex()).second;
            switch (augmentedImage.getTrackingState()) {
                case TRACKING:
                    augmentedImageRenderer.draw(
                            viewmtx, projmtx, augmentedImage, centerAnchor, colorCorrectionRgba);
                    break;
                default:
                    break;
            }
        }
    }

    private boolean setupAugmentedImageDatabase(Config config) {
        AugmentedImageDatabase augmentedImageDatabase;

        // There are two ways to configure an AugmentedImageDatabase:
        // 1. Add Bitmap to DB directly
        // 2. Load a pre-built AugmentedImageDatabase
        // Option 2) has
        // * shorter setup time
        // * doesn't require images to be packaged in apk.
        if (useSingleImage) {
            Bitmap augmentedImageBitmap = loadAugmentedImageBitmap();
            if (augmentedImageBitmap == null) {
                return false;
            }

            augmentedImageDatabase = new AugmentedImageDatabase(session);
            augmentedImageDatabase.addImage("image_name", augmentedImageBitmap);
            // If the physical size of the image is known, you can instead use:
            //     augmentedImageDatabase.addImage("image_name", augmentedImageBitmap, widthInMeters);
            // This will improve the initial detection speed. ARCore will still actively estimate the
            // physical size of the image as it is viewed from multiple viewpoints.
        } else {
            // This is an alternative way to initialize an AugmentedImageDatabase instance,
            // load a pre-existing augmented image database.
            try (InputStream is = getAssets().open("uade.imgdb")) {
                augmentedImageDatabase = AugmentedImageDatabase.deserialize(session, is);
            } catch (IOException e) {
                Log.e(TAG, "IO exception loading augmented image database.", e);
                return false;
            }
        }

        config.setAugmentedImageDatabase(augmentedImageDatabase);
        return true;
    }

    private Bitmap loadAugmentedImageBitmap() {
        try (InputStream is = getAssets().open("uade.jpg")) {
            return BitmapFactory.decodeStream(is);
        } catch (IOException e) {
            Log.e(TAG, "IO exception loading augmented image bitmap.", e);
        }
        return null;
    }
}
