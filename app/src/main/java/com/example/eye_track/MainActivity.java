package com.example.eye_track;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.widget.Toast;

import org.pytorch.IValue;
import org.pytorch.Module;
import org.pytorch.Tensor;
import org.pytorch.torchvision.TensorImageUtils;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;

import jp.co.cyberagent.android.gpuimage.GPUImageView;


@RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
public class MainActivity extends AppCompatActivity {
    private String cameraId;
    private AutoFitTextureView textureView;
    private GPUImageView gpuImageView, v2;
    private CameraDevice cameraDevice;
    private Size previewSize;
    private CaptureRequest previewCaptureRequest;
    private CaptureRequest.Builder previewCaptureRequestBuilder;
    private CameraCaptureSession cameraCaptureSession;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        textureView = (AutoFitTextureView) findViewById(R.id.texture);
        gpuImageView = (GPUImageView)findViewById(R.id.gpuimageview);
        v2 = (GPUImageView)findViewById(R.id.gpuimageview_face);
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        if (textureView.isAvailable()) {
            setupCamera(textureView.getWidth(), textureView.getHeight());
            openCamera();
        } else {
            textureView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    @Override
    protected void onPause() {
        stopBackgroundThread();
        closeCamera();
        super.onPause();
    }

    private TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            setupCamera(width, height);
            openCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void setupCamera(int width, int height) {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(id);

                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }
                StreamConfigurationMap map =
                        cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                // Set Size để hiển thị lên màn hình
                previewSize = map.getOutputSizes(SurfaceTexture.class)[0];
//                getPreferredPreviewsSize(
//                        map.getOutputSizes(SurfaceTexture.class),
//                        width,
//                        height);
                cameraId = id;
                break;
            }
            textureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

//    private Size getPreferredPreviewsSize(Size[] mapSize, int width, int height) {
//        List<Size> collectorSize = new ArrayList<>();
//        for (Size option : mapSize) {
//            if (width > height) {
//                if (option.getWidth() > width && option.getHeight() > height) {
//                    collectorSize.add(option);
//                }
//            } else {
//                if (option.getWidth() > height && option.getHeight() > width) {
//                    collectorSize.add(option);
//                }
//            }
//        }
//        if (collectorSize.size() > 0) {
//            return Collections.min(collectorSize, new Comparator<Size>() {
//                @Override
//                public int compare(Size lhs, Size rhs) {
//                    return Long.signum(lhs.getWidth() * lhs.getHeight() - rhs.getHeight() * rhs.getWidth());
//                }
//            });
//        }
//        return mapSize[0];
//    }

    private void openCamera() {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[] {Manifest.permission.CAMERA}, 100);
                return;
            }
            cameraManager.openCamera(cameraId, cameraDeviceStateCallback, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void closeCamera(){
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    private CameraDevice.StateCallback cameraDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
        }
    };

    private CameraCaptureSession.CaptureCallback cameraSessionCaptureCallback =
            new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request,
                                             long timestamp, long frameNumber) {
                    super.onCaptureStarted(session, request, timestamp, frameNumber);
                }

                @Override
                public void onCaptureCompleted(CameraCaptureSession session,
                                               CaptureRequest request, TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                }

                @Override
                public void onCaptureFailed(CameraCaptureSession session,
                                            CaptureRequest request, CaptureFailure failure) {
                    super.onCaptureFailed(session, request, failure);
                }
            };

    private void createCameraPreviewSession() {
        try {
            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface previewSurface = new Surface(surfaceTexture);

            previewCaptureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

            previewCaptureRequestBuilder.addTarget(previewSurface);

            getModel(this);
            startBackgroundThread();
            cameraDevice.createCaptureSession(Arrays.asList(previewSurface),
                    // Hàm Callback trả về kết quả khi khởi tạo.
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (cameraDevice == null) {
                                return;
                            }
                            try {
                                previewCaptureRequest = previewCaptureRequestBuilder.build();
                                cameraCaptureSession = session;
                                cameraCaptureSession.setRepeatingRequest(
                                        previewCaptureRequest,
                                        cameraSessionCaptureCallback,
                                        backgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            Toast.makeText(getApplicationContext(),
                                    "Create camera session fail", Toast.LENGTH_SHORT).show();
                        }
                    },
                    null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private Object lock = new Object();
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private boolean runsegmentor = false;

    private void startBackgroundThread(){
        backgroundThread = new HandlerThread("haizzz");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
        synchronized (lock) {
            runsegmentor = true;
        }
        backgroundHandler.post(periodicSegment);
    }

    private void stopBackgroundThread() {
        try {
            backgroundThread.quitSafely();
            backgroundThread.join();
            backgroundThread = null;
            backgroundHandler = null;
            synchronized (lock) {
                runsegmentor = false;
            }
        } catch (InterruptedException e) {
            Log.e("TAG", "Interrupted when stopping background thread", e);
        }catch (Exception e){

        }
    }

    private Runnable periodicSegment = new Runnable() {
        @Override
        public void run() {
            synchronized (lock) {
                if (runsegmentor) {
                    if(landmark_detect!=null)
                        segmentFrame();
                }
            }
            try {
                backgroundHandler.post(periodicSegment);
            }catch (Exception e){

            }
        }
    };
    private void segmentFrame() {
        if (cameraDevice == null) {
            return;
        }
        long t1 = SystemClock.uptimeMillis();
        bitmap = textureView.getBitmap();
        int hhh = textureView.getHeight(), www = textureView.getWidth();
        int l = Math.min(textureView.getHeight(), textureView.getWidth());
        bitmap = Bitmap.createBitmap(bitmap, www/2-l/2, hhh/2-l/2, l, l);
        fgd = Bitmap.createScaledBitmap(bitmap, sz_image, sz_image, true);
        bitmap = Bitmap.createScaledBitmap(bitmap, piu, piu, true);
        predict();
        long t2 = SystemClock.uptimeMillis();
        System.out.println("total "+(t2-t1));
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                    gpuImageView.setImage(result);
                    v2.setImage(fgd);
            }
        });
    }
//
    String TAG = "______________________________";
    private Bitmap fgd, bitmap;

    static Module face_detect, landmark_detect;
    static float[] mean = {127, 127, 127};
    static float[] std = {0.229f, 0.224f, 0.225f};
    static public Bitmap result;

    public static void getModel(Context context){
        face_detect = Module.load(assetFilePath(context, "face_detect.pt"));
        landmark_detect = Module.load(assetFilePath(context, "landmark_detect.pt"));
        prior(piu);
    }

    Tensor inputTensor, ip, ut;
    Tensor t[] = new Tensor[3];
    IValue outputTensor;
    IValue[] outputTuple;
    float x1, y1, x2, y2;
    float[] l, lm;
    int xx, yy, _w, _h, hh = piu, ww = piu;
    private static ArrayList<ArrayList<Float>> bb = new ArrayList<>();
    static int piu = 320, sz_image = 320;

    public void predict(){
        inputTensor = TensorImageUtils.bitmapToFloat32Tensor(
                Bitmap.createScaledBitmap(bitmap, piu, piu,true),
                TensorImageUtils.TORCHVISION_NORM_MEAN_RGB, TensorImageUtils.TORCHVISION_NORM_STD_RGB);

        long t1 = SystemClock.uptimeMillis();
        outputTensor = face_detect.forward(IValue.from(inputTensor));
        long t2 = SystemClock.uptimeMillis();
        System.out.println("t face detect "+(t2-t1));
        outputTuple = outputTensor.toTuple();
        for (int i = 0; i < outputTuple.length; i++) {
            t[i] = outputTuple[i].toTensor();
        }
        float[] score = t[1].getDataAsFloatArray();
        int idx = 1;
        for(int i = 1; i < score.length; i+=2){
            if(score[i]>score[idx]) {
                idx = i;
            }
        }
//        System.out.println(score[idx]);
        idx /= 2;
//        System.out.println(idx);

        l = t[0].getDataAsFloatArray();
        x1 = (float) (bb.get(idx).get(0) + l[idx*4]*0.1*bb.get(idx).get(2));
        y1 = (float) (bb.get(idx).get(1) + l[idx*4 + 1]*0.1*bb.get(idx).get(3));
        x2 = (float) (bb.get(idx).get(2)*Math.exp(l[idx*4 + 2]*0.2));
        y2 = (float) (bb.get(idx).get(3)*Math.exp(l[idx*4 + 3]*0.2));
        x1 -= x2/2;
        y1 -= y2/2;
        x1 *= sz_image; y1 *= sz_image; x2 *= sz_image; y2 *= sz_image;

        xx = Math.max(0, (int)(x1));
        yy = Math.max(0, (int)(y1));
        _w = (int)Math.min(ww-x1, (int)(x2));
        _h = (int)Math.min(hh-y2, (int)(y2));
//        long xx1 = SystemClock.uptimeMillis();
        bitmap = Bitmap.createBitmap(fgd, xx, yy, Math.max(1, _w), Math.max(1, _h/2));
//        bitmap = Bitmap.createBitmap(bitmap, xx, yy, Math.min(Math.max(_w, 1), ww-xx), Math.min(Math.max((_h/2), 1), hh-yy));
        bitmap = Bitmap.createScaledBitmap(bitmap, 112, 56,true);
//        return;
        ip = TensorImageUtils.bitmapToFloat32Tensor(bitmap,
                TensorImageUtils.TORCHVISION_NORM_MEAN_RGB, TensorImageUtils.TORCHVISION_NORM_STD_RGB);
        t1 = SystemClock.uptimeMillis();
        ut = landmark_detect.forward(IValue.from(ip)).toTensor();
        t2 = SystemClock.uptimeMillis();
        System.out.println("t landmark detect  " + (t2-t1));
        lm = ut.getDataAsFloatArray();
        perform();
        result = (Bitmap.createScaledBitmap(bitmap, (int)(112*4), (int)(56*4), true));
    }

    public void perform() {
//        Bitmap bmOut = Bitmap.createBitmap(inp.getWidth(), inp.getHeight(), inp.getConfig());
        int A, R, G, B;
        int w = 112;
        int h = 56;
        int[] colors = new int[w * h];
        bitmap.getPixels(colors, 0, 112, 0, 0, 112, 56);
        int i = 0;
        int j = 0;
        int pos;
        for (i = 0; i < h; i++) {
            for (j = 0; j < w; j++) {
                pos = i * w + j;
                A = (colors[pos] >> 24) & 0xFF;
                R = (colors[pos] >> 16) & 0xFF;
                G = (colors[pos] >> 8) & 0xFF;
                B = colors[pos] & 0xFF;
                colors[pos] = Color.argb(A, R, G, B);
            }
        }
        for(int o = 0; o < 16; o+=2){
            pos = (int)(lm[o]*112)+(int)(lm[o+1]*112)*112;
            if(pos<w*h && 0<=pos)
                colors[pos] = Color.rgb(255, 0, 0);
        }
        bitmap.setPixels(colors, 0, w, 0, 0, w, h);
    }

    public static String assetFilePath(Context context, String assetName) {
        File file = new File(context.getFilesDir(), assetName);

        try (InputStream is = context.getAssets().open(assetName)) {
            try (OutputStream os = new FileOutputStream(file)) {
                byte[] buffer = new byte[4 * 1024];
                int read;
                while ((read = is.read(buffer)) != -1) {
                    os.write(buffer, 0, read);
                }
                os.flush();
            }
            return file.getAbsolutePath();
        } catch (IOException e) {
            Log.e("pytorchandroid", "Error process asset " + assetName + " to file path");
        }
        return null;
    }

    public static void prior(int sz){
        bb = new ArrayList<>();
        int[][] min_sz = {{16, 32}, {64, 128}, {256, 512}};
        int[] step = {8, 16, 32};
        int[][] fm = {{sz/8, sz/8}, {sz/16, sz/16}, {sz/32, sz/32}};
        for(int i = 0; i < 3; i++){
            int h = fm[i][0], w = fm[i][1];
            for(int r = 0; r < h; r++){
                for(int c = 0; c < w; c++){
                    for(int l = 0; l < 2; l++){
                        float mz = min_sz[i][l];
                        float s_kx = mz/sz;
                        float s_ky = s_kx;
                        float d_cx = (float) ((r+0.5)*step[i]/sz);
                        float d_cy = (float) ((c+0.5)*step[i]/sz);
                        ArrayList<Float> tmp = new ArrayList<>();
                        tmp.add(d_cy);tmp.add(d_cx);tmp.add(s_kx);tmp.add(s_ky);
                        bb.add(tmp);
                    }
                }
            }
        }

//        for(ArrayList<Float> x: bb){
//            for(float val: x){
//                System.out.print(x+" ");
//            }
//            System.out.println();
//        }
    }
}