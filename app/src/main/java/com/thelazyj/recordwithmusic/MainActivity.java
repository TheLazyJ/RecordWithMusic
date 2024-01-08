package com.thelazyj.recordwithmusic;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Display;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Chronometer;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.thelazyj.recordwithsound.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;

// Create an interface so I can use Callback between my 2 activity
interface MainActivityCallback {
    void updatePrefs();
}

public class MainActivity extends AppCompatActivity implements MainActivityCallback {
    private static final String TAG = "RecordWithMusic";
    private static final int REQUEST_CAMERA_PERMISSION_RESULT = 0;
    private static final int REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT = 1;
    private static final int STATE_PREVIEW = 0;
    private static final int STATE_WAIT_LOCK = 1;
    private int mCaptureState = STATE_PREVIEW;
    private TextureView mTextureView;
    private final TextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            setupCamera();
            connectCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {

        }
    };
    private CameraDevice mCameraDevice;
    private final CameraDevice.StateCallback mCameraDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            mCameraDevice = camera;
            mMediaRecorder = new MediaRecorder();
            if(mIsRecording) {
                try {
                    createVideoFileName();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                startRecord();
                mMediaRecorder.start();
                runOnUiThread(() -> {
                    mChronometer.setBase(SystemClock.elapsedRealtime());
                    mChronometer.setVisibility(View.VISIBLE);
                    mChronometer.start();
                });
            } else {
                startPreview();
            }
        }
        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            mCameraDevice = null;
        }
        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            mCameraDevice = null;
        }
    };
    private HandlerThread mBackgroundHandlerThread;
    private Handler mBackgroundHandler;
    private static String mCameraId;
    private String[] mCameraIds;
    private int currentCameraId;
    private Size mPreviewSize;
    private ImageReader mImageReader;
    private final ImageReader.OnImageAvailableListener mOnImageAvailableListener = new
            ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    mBackgroundHandler.post(new ImageSaver(reader.acquireLatestImage()));
                }
            };

    public MainActivity() {

    }

    private class ImageSaver implements Runnable {

        private final Image mImage;

        public ImageSaver(Image image) {
            mImage = image;
        }

        @Override
        public void run() {
            ByteBuffer byteBuffer = mImage.getPlanes()[0].getBuffer();
            byte[] bytes = new byte[byteBuffer.remaining()];
            byteBuffer.get(bytes);

            FileOutputStream fileOutputStream = null;
            try {
                fileOutputStream = new FileOutputStream(mImageFileName);
                fileOutputStream.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                mImage.close();

                Intent mediaStoreUpdateIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                mediaStoreUpdateIntent.setData(Uri.fromFile(new File(mImageFileName)));
                sendBroadcast(mediaStoreUpdateIntent);

                if(fileOutputStream != null) {
                    try {
                        fileOutputStream.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

        }
    }
    private MediaRecorder mMediaRecorder;
    private Chronometer mChronometer;
    private int mTotalRotation;
    private CameraCaptureSession mPreviewCaptureSession;
    private final CameraCaptureSession.CaptureCallback mPreviewCaptureCallback = new
            CameraCaptureSession.CaptureCallback() {

                private void process(CaptureResult captureResult) {
                    switch (mCaptureState) {
                        case STATE_PREVIEW:
                            // Do nothing
                            break;
                        case STATE_WAIT_LOCK:
                            mCaptureState = STATE_PREVIEW;
                            Integer afState = captureResult.get(CaptureResult.CONTROL_AF_STATE);
                            //noinspection DataFlowIssue
                            if(afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                                    afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                                //Toast.makeText(getApplicationContext(), "Auto-Focus Locked!", Toast.LENGTH_SHORT).show();
                                startStillCaptureRequest();
                            }
                            break;
                    }
                }

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);

                    process(result);
                }
            };
    private CameraCaptureSession mRecordCaptureSession;
    private final CameraCaptureSession.CaptureCallback mRecordCaptureCallback = new
            CameraCaptureSession.CaptureCallback() {

                private void process(CaptureResult captureResult) {
                    switch (mCaptureState) {
                        case STATE_PREVIEW:
                            // Do nothing
                            break;
                        case STATE_WAIT_LOCK:
                            mCaptureState = STATE_PREVIEW;
                            Integer afState = captureResult.get(CaptureResult.CONTROL_AF_STATE);
                            //noinspection DataFlowIssue
                            if(afState == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                                    afState == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED) {
                                //Toast.makeText(getApplicationContext(), "Auto-Focus Locked!", Toast.LENGTH_SHORT).show();
                                startStillCaptureRequest();
                            }
                            break;
                    }
                }

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);

                    process(result);
                }
            };
    private CaptureRequest.Builder mCaptureRequestBuilder;
    private ImageButton mRecordImageButton;
    //private Button mStillImageButton;

    //Main buttons
    private ImageButton mGalleryButton;
    private ImageButton mSwapCamera;
    private ImageButton mSetting;
    private ImageButton mfpsBtn;
    private ImageButton mratioBtn;

    //Ratio Buttons menu
    private ImageButton mratioBtn_11;
    private ImageButton mratioBtn_34;
    private ImageButton mratioBtn_916;
    private ImageButton mratioBtn_full;

    private RelativeLayout msettingLayout;
    private RelativeLayout mratioLayout;
    private RelativeLayout mfpsLayout;
    private boolean mIsRecording = false;
    private File mVideoFolder;
    private String mVideoFileName;
    private String mImageFileName;
    private Size bestSize = null;
    private Size mVideoSize;

    private SensorManager mSensorManager;
    private Sensor mRotationSensor;
    private SensorEventListener mRotationListener;
    private ViewGroup mRootView;
    private CameraManager cameraManager;



    // MENU SETTING VARIABLES --------------------
    private SharedPreferences prefs;
    private SharedPreferences.Editor editor;
    public static float BITRATE_CORRECTION_FACTOR;
    public static int FRAME_RATE;
    public static Size ASPECT_RATIO;
    public static int RATIO_IMG;
    public static int FPS_IMG;
    public static int CAMERA_LIMITS;
    public static int MAX_SIZE;

    // LATER USE MENU ----------------------------
    public static int maxWidth = 9999;
    public static float maxHeight = 9999;
    public static boolean detectRotation = true;
    public static String saveDirectory = "Record With Music";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Create permanent data for user preference
        prefs = getPreferences(MODE_PRIVATE);
        editor = prefs.edit();
        createSetting();
        setContentView(R.layout.activity_main);
        createVideoFolder();
        checkRotation();
        initCameraIds();
        mRootView = findViewById(R.id.baseLayout);
        mChronometer = findViewById(R.id.chronometer);
        mSwapCamera = findViewById(R.id.switchCamera);
        mTextureView = findViewById(R.id.cameraSurface);

        //mStillImageButton = findViewById(R.id.pictureBtn);
        mSetting = findViewById(R.id.settingBtn);
        mfpsBtn = findViewById(R.id.fpsBtn);
        mratioBtn = findViewById(R.id.ratioBtn);
        msettingLayout = findViewById(R.id.settingLayout);

        //Ratio button ID
        checkRatio(); //check ratio and generate the appropriate icon, need to be after creation of mratioBtn
        checkFPS(); //check FPS and generate the appropriate icon, need to be after creation of mfpsBtn
        mratioLayout = findViewById(R.id.ratioLayout);
        mratioBtn_11 = findViewById(R.id.ratioBtn_11);
        mratioBtn_34 = findViewById(R.id.ratioBtn_34);
        mratioBtn_916 = findViewById(R.id.ratioBtn_916);
        mratioBtn_full = findViewById(R.id.ratioBtn_full);

        //FPS button ID
        checkFPS(); //check FPS and generate the appropriate icon, need to be after creation of mfpsBtn
        mfpsLayout = findViewById(R.id.fpsLayout);
        ImageButton mhd30 = findViewById(R.id.hd30);
        ImageButton mfhd30 = findViewById(R.id.fhd30);
        ImageButton muhd30 = findViewById(R.id.uhd30);

        //Open setting menu, which is a bottom sheet dialog
        mSetting.setOnClickListener(v -> {
            SettingActivity bottomSheetDialog = new SettingActivity();
            bottomSheetDialog.show(getSupportFragmentManager(), "bottomSheetDialogFragment");
        });


        //FPS Button choice
        mfpsBtn.setOnClickListener(view -> {
            msettingLayout.setVisibility(View.GONE);
            mfpsLayout.setVisibility(View.VISIBLE);
        });

        //Frame Rate Button Sub-menu
        mhd30.setOnClickListener(view -> {
            FRAME_RATE = 30;
            FPS_IMG = 300;
            MAX_SIZE = 1280;
            updatePrefs();
            setupCamera();
            mfpsLayout.setVisibility(View.GONE);
            msettingLayout.setVisibility(View.VISIBLE);
            mfpsBtn.setImageResource(R.drawable.hd_30);
        });

        mfhd30.setOnClickListener(view -> {
            FRAME_RATE = 30;
            FPS_IMG = 301;
            MAX_SIZE = 1920;
            updatePrefs();
            setupCamera();
            mfpsLayout.setVisibility(View.GONE);
            msettingLayout.setVisibility(View.VISIBLE);
            mfpsBtn.setImageResource(R.drawable.fhd_30);
        });

        muhd30.setOnClickListener(view -> {
            FRAME_RATE = 30;
            FPS_IMG = 302;
            MAX_SIZE = 3840;
            updatePrefs();
            setupCamera();
            mfpsLayout.setVisibility(View.GONE);
            msettingLayout.setVisibility(View.VISIBLE);
            mfpsBtn.setImageResource(R.drawable.uhd_30);
        });

        //Ratio Button choice
        mratioBtn.setOnClickListener(view -> {
            msettingLayout.setVisibility(View.GONE);
            mratioLayout.setVisibility(View.VISIBLE);
        });

        //Ratio Button Sub-menu
        mratioBtn_11.setOnClickListener(view -> {
            ASPECT_RATIO = new Size(1,1);
            RATIO_IMG = 11;
            updatePrefs();
            resetCamera();
            mratioLayout.setVisibility(View.GONE);
            msettingLayout.setVisibility(View.VISIBLE);
            mratioBtn.setImageResource(R.drawable.ratio_11);
        });

        mratioBtn_34.setOnClickListener(view -> {
            ASPECT_RATIO = new Size(3,4);
            RATIO_IMG = 34;
            updatePrefs();
            resetCamera();
            mratioLayout.setVisibility(View.GONE);
            msettingLayout.setVisibility(View.VISIBLE);
            mratioBtn.setImageResource(R.drawable.ratio_34);
        });

        mratioBtn_916.setOnClickListener(view -> {
            ASPECT_RATIO = new Size(9,16);
            RATIO_IMG = 916;
            updatePrefs();
            resetCamera();
            mratioLayout.setVisibility(View.GONE);
            msettingLayout.setVisibility(View.VISIBLE);
            mratioBtn.setImageResource(R.drawable.ratio_916);
        });

        mratioBtn_full.setOnClickListener(view -> {
            Point size = displaySize();
            ASPECT_RATIO = new Size(size.x,size.y);
            RATIO_IMG = 666;
            updatePrefs();
            resetCamera();
            mratioLayout.setVisibility(View.GONE);
            msettingLayout.setVisibility(View.VISIBLE);
            mratioBtn.setImageResource(R.drawable.ratio_full);
        });

        // Switch to picture mode
        /**
         mStillImageButton.setOnClickListener(v -> {

            lockFocus();
        });**/

        // Swap between camera
        mSwapCamera.setOnClickListener(v -> {
            rotateCamera();
            closeCamera();
            setupCamera();
            connectCamera();
        });

        // Start recording video
        mRecordImageButton = findViewById(R.id.recordBtn);
        mRecordImageButton.setOnClickListener(v -> {
            if (mIsRecording) {
                Toast.makeText(getApplicationContext(),
                        "Stop Recording", Toast.LENGTH_SHORT).show();
                mChronometer.stop();
                mChronometer.setVisibility(View.INVISIBLE);
                //mStillImageButton.setVisibility(View.VISIBLE);

                mIsRecording = false;
                mRecordImageButton.setImageResource(R.drawable.startrec);

                // Starting the preview prior to stopping recording which should hopefully resolve issues being seen in Samsung devices.
                startPreview();
                mMediaRecorder.stop();
                mMediaRecorder.reset();

                Intent mediaStoreUpdateIntent = new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE);
                mediaStoreUpdateIntent.setData(Uri.fromFile(new File(mVideoFileName)));
                sendBroadcast(mediaStoreUpdateIntent);

            } else {
                Toast.makeText(getApplicationContext(),
                        "Recording", Toast.LENGTH_SHORT).show();
                mIsRecording = true;
                //mStillImageButton.setVisibility(View.INVISIBLE);
                mRecordImageButton.setImageResource(R.drawable.stoprec);
                checkWriteStoragePermission();
            }
        });
        mGalleryButton = findViewById(R.id.galleryBtn);
        mGalleryButton.setOnClickListener(v -> {
            //Open Gallery
            startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN,Intent.CATEGORY_APP_GALLERY));
        });
    }

    // What to do if the app is restarted
    @Override
    protected void onResume() {
        super.onResume();
        checkRotation();
        startBackgroundThread();
        if(mTextureView.isAvailable()) {
            setupCamera();
            connectCamera();
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);

        }

    }

    // Check for permission
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(requestCode == REQUEST_CAMERA_PERMISSION_RESULT) {
            if(grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(getApplicationContext(),
                        "Application will not run without camera services", Toast.LENGTH_SHORT).show();
            }
            if(grantResults[1] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(getApplicationContext(),
                        "Application will not have audio on record", Toast.LENGTH_SHORT).show();
            }
        }
        if(requestCode == REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT) {
            if(grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if(mIsRecording) {
                    mRecordImageButton.setImageResource(R.drawable.stoprec);
                }
                Toast.makeText(this,
                        "Permission successfully granted!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this,
                        "App needs to save video to run", Toast.LENGTH_SHORT).show();
            }
        }
    }

    // If app is paused, what to do
    @Override
    protected void onPause() {
        closeCamera();
        mSensorManager.unregisterListener(mRotationListener);
        stopBackgroundThread();

        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        View decorView = getWindow().getDecorView();
        if(hasFocus) {
            decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    }

    // When app open and the textureView is available, automatically start the process to fix the image of the camera to the texture and to determine good size
    private void setupCamera() {
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(mCameraId);
            StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            assert map != null;
            mPreviewSize = chooseBestSize(map.getOutputSizes(SurfaceTexture.class));
            mVideoSize = chooseBestSize(map.getOutputSizes(MediaRecorder.class)); // chooseBestSize is limited max height
            //mImageReader = ImageReader.newInstance(mPreviewSize.getWidth(), mPreviewSize.getHeight(), ImageFormat.JPEG, 1);
            mImageReader = ImageReader.newInstance(mVideoSize.getWidth(), mVideoSize.getHeight(), ImageFormat.JPEG, 1);
            mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mBackgroundHandler);
            adjustAspectRatio(); // adjust mTextureView to the good ratio
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // Connect the phone camera with the app and check if permission is granted
    private void connectCamera() {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if(ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED) {
                    cameraManager.openCamera(mCameraId, mCameraDeviceStateCallback, mBackgroundHandler);
                } else {
                    if(shouldShowRequestPermissionRationale(android.Manifest.permission.CAMERA)) {
                        Toast.makeText(this,
                                "Video app required access to camera", Toast.LENGTH_SHORT).show();
                    }
                    requestPermissions(new String[] {android.Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO
                    }, REQUEST_CAMERA_PERMISSION_RESULT);
                }

            } else {
                cameraManager.openCamera(mCameraId, mCameraDeviceStateCallback, mBackgroundHandler);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // Setup the surface & CaptureRequest builder to record image from the camera
    private void startRecord() {
        try {
            setupMediaRecorder();
            SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
            assert surfaceTexture != null;
            surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
            Surface previewSurface = new Surface(surfaceTexture);
            Surface recordSurface = mMediaRecorder.getSurface();
            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            mCaptureRequestBuilder.addTarget(previewSurface);
            mCaptureRequestBuilder.addTarget(recordSurface);
            mCameraDevice.createCaptureSession(Arrays.asList(previewSurface, recordSurface, mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            mRecordCaptureSession = session;
                            try {
                                mRecordCaptureSession.setRepeatingRequest(
                                        mCaptureRequestBuilder.build(), null, null
                                );
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }
                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.d(TAG, "onConfigureFailed: startRecord");
                        }
                    }, null);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // Setup the surface & CaptureRequest builder to show image from the camera
    private void startPreview() {
        SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
        assert surfaceTexture != null;
        surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        Surface previewSurface = new Surface(surfaceTexture);
        try {
            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mCaptureRequestBuilder.addTarget(previewSurface);
            mCameraDevice.createCaptureSession(Arrays.asList(previewSurface, mImageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession session) {
                            Log.d(TAG, "onConfigured: startPreview");
                            mPreviewCaptureSession = session;
                            try {
                                mPreviewCaptureSession.setRepeatingRequest(mCaptureRequestBuilder.build(),
                                        null, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }
                        @Override
                        public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                            Log.d(TAG, "onConfigureFailed: startPreview");
                        }
                    }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void startStillCaptureRequest() {
        try {
            if(mIsRecording) {
                mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_VIDEO_SNAPSHOT);
            } else {
                mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            }
            mCaptureRequestBuilder.addTarget(mImageReader.getSurface());
            mCaptureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, mTotalRotation);
            CameraCaptureSession.CaptureCallback stillCaptureCallback = new
                    CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureStarted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, long timestamp, long frameNumber) {
                            super.onCaptureStarted(session, request, timestamp, frameNumber);
                            try {
                                createImageFileName();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    };
            if(mIsRecording) {
                mRecordCaptureSession.capture(mCaptureRequestBuilder.build(), stillCaptureCallback, null);
            } else {
                mPreviewCaptureSession.capture(mCaptureRequestBuilder.build(), stillCaptureCallback, null);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void closeCamera() {
        if(mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
        if(mMediaRecorder != null) {
            mMediaRecorder.release();
            mMediaRecorder = null;
        }
    }

    private void startBackgroundThread() {
        mBackgroundHandlerThread = new HandlerThread("RecordWithMusic");
        mBackgroundHandlerThread.start();
        mBackgroundHandler = new Handler(mBackgroundHandlerThread.getLooper());
    }

    private void stopBackgroundThread() {
        mBackgroundHandlerThread.quitSafely();
        try {
            mBackgroundHandlerThread.join();
            mBackgroundHandlerThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // Adjust texture view to fit the size determined by the next function
    private void adjustAspectRatio() {
        Point size = displaySize();
        int viewWidth = size.x;
        int viewHeight = size.y;
        double aspectRatio = ((double) ASPECT_RATIO.getWidth() / ASPECT_RATIO.getHeight());
        int newWidth, newHeight;
        if (viewHeight > viewWidth / aspectRatio) {
            // limited by narrow width; restrict height
            newWidth = viewWidth;
            newHeight = (int) (viewWidth / aspectRatio);
        } else {
            // limited by short height; restrict width
            newWidth = (int) (viewHeight * aspectRatio);
            newHeight = viewHeight;
        }
        // DO NOT TOUCH LOL
        int xoff = (viewWidth - newWidth) / 2;
        int yoff = (viewHeight - newHeight) / 2;
        Matrix txform = new Matrix();
        txform.setScale((float)  newWidth / viewWidth, (float)  newHeight /viewHeight );
        txform.postTranslate(xoff, yoff);
        mTextureView.setTransform(txform);
    }

    // Determine best resolution depending of the video size of the camera and also with the ratio in consideration
    private Size chooseBestSize(Size[] videoSizes) {
        double targetRatio = (double) ASPECT_RATIO.getHeight() / ASPECT_RATIO.getWidth();
        double diff = Double.MAX_VALUE;
        for (Size size : videoSizes) {
            if (size.getWidth() <= maxWidth && size.getHeight() <= maxHeight && size.getHeight() <= MAX_SIZE) {
                Log.d("max size     ", String.valueOf(MAX_SIZE));
                Log.d("video size s ", String.valueOf(size.getHeight()));
                double newDiff = Math.abs(((double) size.getWidth() / size.getHeight()) - targetRatio);
                if (newDiff < diff) {
                    bestSize = size;
                    diff = newDiff;
                }
            }
        }
        return bestSize;
    }

    // Those 3 functions deal with creating where the file will be sent
    private void createVideoFolder() {
        File movieFile = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM);
        mVideoFolder = new File(movieFile, saveDirectory);
        if(!mVideoFolder.exists()) {
            mVideoFolder.mkdirs();
        }
    }

    // Create file that we record
    private void createVideoFileName() throws IOException {
        @SuppressLint("SimpleDateFormat") String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String prepend = "VIDEO_" + timestamp;
        File videoFile = new File(mVideoFolder, prepend + ".mp4");
        mVideoFileName = videoFile.getAbsolutePath();
    }

    // Create file and determine location for pictures (same as video)
    private void createImageFileName() throws IOException {
        @SuppressLint("SimpleDateFormat") String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String prepend = "IMAGE_" + timestamp + "_";
        File imageFile = new File(mVideoFolder, prepend + ".jpg");
        mImageFileName = imageFile.getAbsolutePath();
    }

    // Check Write Storage Permission
    private void checkWriteStoragePermission() {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if(ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED) {
                try {
                    createVideoFileName();
                } catch (IOException e) {
                    e.printStackTrace();
                }
                if(mIsRecording) {
                    startRecord();
                    mMediaRecorder.start();
                    mChronometer.setBase(SystemClock.elapsedRealtime());
                    mChronometer.setVisibility(View.VISIBLE);
                    mChronometer.start();
                }
            } else {
                if(shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
                    Toast.makeText(this, "App needs to be able to save videos", Toast.LENGTH_SHORT).show();
                }
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT);
            }
        } else {
            try {
                createVideoFileName();
            } catch (IOException e) {
                e.printStackTrace();
            }
            if(mIsRecording) {
                startRecord();
                mMediaRecorder.start();
                mChronometer.setBase(SystemClock.elapsedRealtime());
                mChronometer.setVisibility(View.VISIBLE);
                mChronometer.start();
            }
        }
    }

    // Setup the media recorder,
    private void setupMediaRecorder() throws IOException {
        mMediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mMediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mMediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mMediaRecorder.setOutputFile(mVideoFileName);
        mMediaRecorder.setVideoEncodingBitRate(getBitrate());
        mMediaRecorder.setVideoFrameRate(FRAME_RATE);
        mMediaRecorder.setVideoSize(mVideoSize.getWidth(),mVideoSize.getHeight());
        mMediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mMediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mMediaRecorder.setOrientationHint(mTotalRotation);
        mMediaRecorder.prepare();
    }

    //Lock focus on a specific point, us the auto focus of CameraDevice
    private void lockFocus() {
        mCaptureState = STATE_WAIT_LOCK;
        mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
        try {
            if(mIsRecording) {
                mRecordCaptureSession.capture(mCaptureRequestBuilder.build(), mRecordCaptureCallback, mBackgroundHandler);
            } else {
                mPreviewCaptureSession.capture(mCaptureRequestBuilder.build(), mPreviewCaptureCallback, mBackgroundHandler);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // Get the best bitrate, take size and multiply by FPS and BITRATE factor, higher bitrate = better quality && bigger size
    private int getBitrate() {
        return (int)(mVideoSize.getWidth() * mVideoSize.getHeight() * FRAME_RATE * BITRATE_CORRECTION_FACTOR);
    }

    // Determine witch rotation is the phone and adjust the hint for mMediaRecorder as the activity is stuck in portrait mode due to the camera direction
    private void checkRotation() {
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        mRotationSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        mRotationListener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                if (detectRotation) {
                    float x = event.values[0];
                    float y = event.values[1];
                    if (x > y && x > 9) {
                        //Left Landscape
                        mTotalRotation = 0;
                    } else if (x < y && x < -9) {
                        //Right Landscape
                        mTotalRotation = 180;
                    } else if (y < x && y < -9) {
                        //Reverse Portrait
                        mTotalRotation = 270;
                    } else {
                        //Portrait
                        mTotalRotation = 90;
                    }
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {
                // not used
            }
        };
        // determine how fast that sensor register, to maximize performance, the maximum delay is the limit of an int
        mSensorManager.registerListener(mRotationListener, mRotationSensor, 2147483647);
    }

    // Create a string of all the camera of the phones
    private void initCameraIds() {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            mCameraIds = cameraManager.getCameraIdList();
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
       mCameraId = mCameraIds[currentCameraId];
    }

    // Rotate through the String[] list of mCameraId
    private void rotateCamera() {
        currentCameraId++;
        if (currentCameraId >= mCameraIds.length || currentCameraId >= CAMERA_LIMITS) {
            currentCameraId = 0;
        }
        mCameraId = mCameraIds[currentCameraId];
    }

    // Flash the screen black when taking picture or recording to give more feedback to mbr
    @RequiresApi(api = Build.VERSION_CODES.M)
    private void flashScreen() {
        mRootView.setForeground(new ColorDrawable(Color.BLACK));
        mRootView.postDelayed(() -> mRootView.setForeground(null), 150);
    }

    // Create an CallBack that will you MainActivity Call back
    public void resetCamera() {
        closeCamera();
        setupCamera();
        connectCamera();
    }

    // Method that is call each time a setting is updated to the user
    @Override
    public void updatePrefs(){
        editor.putFloat("BitRate", BITRATE_CORRECTION_FACTOR);
        editor.putInt("FPS", FRAME_RATE);
        editor.putInt("ratioW",ASPECT_RATIO.getWidth());
        editor.putInt("ratioH",ASPECT_RATIO.getHeight());
        editor.putInt("cam",CAMERA_LIMITS);
        editor.putInt("ratioCam",RATIO_IMG);
        editor.putInt("fpsCam",FPS_IMG);
        editor.putInt("maxSize",MAX_SIZE);
        editor.commit();
    }

    private void createSetting(){ // The value beside the key is the default if its doesn't exist already
        BITRATE_CORRECTION_FACTOR = prefs.getFloat("BitRate", 0.10f);
        FRAME_RATE = prefs.getInt("FPS", 300);
        ASPECT_RATIO = new Size (prefs.getInt("ratioW", 3), prefs.getInt("ratioH", 4));
        CAMERA_LIMITS = prefs.getInt("cam", 2);
        RATIO_IMG = prefs.getInt("ratioCam", 34);
        FPS_IMG = prefs.getInt("fpsCam", 301);
        MAX_SIZE = prefs.getInt("maxSize", 1920);
    }

    //Check current ratio setting and identify the appropriate picture of the button when the app reopen
    public void checkRatio(){
       switch (RATIO_IMG){
           case 11:
               mratioBtn.setImageResource(R.drawable.ratio_11);
               ASPECT_RATIO = new Size(1,1);
               break;
           case 34:
               mratioBtn.setImageResource(R.drawable.ratio_34);
               ASPECT_RATIO = new Size(3,4);
               break;
           case 916:
               mratioBtn.setImageResource(R.drawable.ratio_916);
               ASPECT_RATIO = new Size(9,16);
               break;
           case 666:
               mratioBtn.setImageResource(R.drawable.ratio_full);
               Point size = displaySize();
               ASPECT_RATIO = new Size(size.x,size.y);
               break;
       }
    }

    //Check current FPS setting and identify the appropriate picture of the button
    public void checkFPS(){
        switch (FPS_IMG){
            case 300:
                mfpsBtn.setImageResource(R.drawable.hd_30);
                FRAME_RATE = 30;
                MAX_SIZE = 1280;
                break;
            case 301:
                mfpsBtn.setImageResource(R.drawable.fhd_30);
                FRAME_RATE = 30;
                MAX_SIZE = 1920;
                break;
            case 302:
                mfpsBtn.setImageResource(R.drawable.uhd_30);
                FRAME_RATE = 30;
                MAX_SIZE = 3840;
                break;
        }
    }

    public Point displaySize(){
        WindowManager windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        Display display = windowManager.getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        return size;
    }

}