package com.arcsoft.arcfacedemo.activity;

import android.Manifest;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.os.Build;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.DefaultItemAnimator;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Surface;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

import com.arcsoft.arcfacedemo.R;
import com.arcsoft.arcfacedemo.faceserver.CompareResult;
import com.arcsoft.arcfacedemo.faceserver.FaceServer;
import com.arcsoft.arcfacedemo.model.DrawInfo;
import com.arcsoft.arcfacedemo.model.FacePreviewInfo;
import com.arcsoft.arcfacedemo.util.ConfigUtil;
import com.arcsoft.arcfacedemo.util.DrawHelper;
import com.arcsoft.arcfacedemo.util.face.FaceHelper;
import com.arcsoft.arcfacedemo.util.face.FaceListener;
import com.arcsoft.arcfacedemo.util.face.LivenessType;
import com.arcsoft.arcfacedemo.util.face.RecognizeColor;
import com.arcsoft.arcfacedemo.util.face.RequestFeatureStatus;
import com.arcsoft.arcfacedemo.util.face.RequestLivenessStatus;
import com.arcsoft.arcfacedemo.widget.FaceRectView;
import com.arcsoft.arcfacedemo.widget.FaceSearchResultAdapter;
import com.arcsoft.face.AgeInfo;
import com.arcsoft.face.ErrorInfo;
import com.arcsoft.face.FaceEngine;
import com.arcsoft.face.FaceFeature;
import com.arcsoft.face.GenderInfo;
import com.arcsoft.face.LivenessInfo;
import com.arcsoft.face.VersionInfo;
import com.serenegiant.usb.CameraDialog;
import com.serenegiant.usb.DeviceFilter;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.usbcameracommon.UVCCameraHandler;
import com.serenegiant.usbcameracommon.UvcCameraDataCallBack;
import com.serenegiant.widget.CameraViewInterface;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.ObservableEmitter;
import io.reactivex.ObservableOnSubscribe;
import io.reactivex.Observer;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.disposables.Disposable;
import io.reactivex.schedulers.Schedulers;

import static com.arcsoft.arcfacedemo.util.face.FaceHelper.DEFAULT_PREVIEW_HEIGHT;
import static com.arcsoft.arcfacedemo.util.face.FaceHelper.DEFAULT_PREVIEW_WIDTH;


public class UsbCameraRegisterAndRecognizeActivity extends AppCompatActivity implements ViewTreeObserver.OnGlobalLayoutListener, CameraDialog.CameraDialogParent, View.OnLayoutChangeListener {

    private static final String TAG = "UsbCameraAct";
    private static final int MAX_DETECT_NUM = 10;
    /**
     * 当FR成功，活体未成功时，FR等待活体的时间
     */
    private static final int WAIT_LIVENESS_INTERVAL = 100;
    /**
     * 失败重试间隔时间（ms）
     */
    private static final long FAIL_RETRY_INTERVAL = 1000;
    /**
     * 出错重试最大次数
     */
    private static final int MAX_RETRY_TIME = 3;
    /**
     * 注册人脸状态码，准备注册
     */
    private static final int REGISTER_STATUS_READY = 0;
    /**
     * 注册人脸状态码，注册中
     */
    private static final int REGISTER_STATUS_PROCESSING = 1;
    /**
     * 注册人脸状态码，注册结束（无论成功失败）
     */
    private static final int REGISTER_STATUS_DONE = 2;
    private static final int ACTION_REQUEST_PERMISSIONS = 0x001;
    private static final float SIMILAR_THRESHOLD = 0.8F;
    /**
     * 所需的所有权限信息
     */
    private static final String[] NEEDED_PERMISSIONS = new String[]{
            Manifest.permission.CAMERA,
            Manifest.permission.READ_PHONE_STATE

    };
    private static final float[] BANDWIDTH_FACTORS = {0.5f, 0.5f};
    int previewCount = 0;
    EditText editName;
    private DrawHelper drawHelper;
    /**
     * VIDEO模式人脸检测引擎，用于预览帧人脸追踪
     */
    private FaceEngine ftEngine;
    private int ftInitCode = -1;
    /**
     * 用于特征提取的引擎
     */
    private FaceEngine frEngine;
    private int frInitCode = -1;
    /**
     * IMAGE模式活体检测引擎，用于预览帧人脸活体检测
     */
    private FaceEngine flEngine;
    private int flInitCode = -1;
    /**
     * 人脸追踪
     */
    private FaceHelper faceHelper;
    private List<CompareResult> compareResultList;
    private FaceSearchResultAdapter adapter;
    /**
     * 活体检测的开关
     */
    private boolean livenessDetect = true;
    private int registerStatus = REGISTER_STATUS_DONE;
    private ConcurrentHashMap<Integer, Integer> requestFeatureStatusMap = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Integer, Integer> extractErrorRetryMap = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Integer, Integer> livenessMap = new ConcurrentHashMap<>();
    private ConcurrentHashMap<Integer, Integer> livenessErrorRetryMap = new ConcurrentHashMap<>();
    private CompositeDisposable getFeatureDelayedDisposables = new CompositeDisposable();
    private CompositeDisposable delayFaceTaskCompositeDisposable = new CompositeDisposable();
    final FaceListener faceListener = new FaceListener() {
        @Override
        public void onFail(Exception e) {
            Log.e(TAG, "onFail: " + e.getMessage());
        }

        //请求FR的回调
        @Override
        public void onFaceFeatureInfoGet(@Nullable final FaceFeature faceFeature, final Integer requestId, final Integer errorCode) {
            //FR成功
            if (faceFeature != null) {
                Log.i(TAG, "onPreview: fr end = " + System.currentTimeMillis() + " trackId = " + requestId);
                Integer liveness = livenessMap.get(requestId);
                //不做活体检测的情况，直接搜索
                if (!livenessDetect) {
                    searchFace(faceFeature, requestId);
                }
                //活体检测通过，搜索特征
                else if (liveness != null && liveness == LivenessInfo.ALIVE) {
                    searchFace(faceFeature, requestId);
                }
                //活体检测未出结果，或者非活体，延迟执行该函数
                else {
                    if (requestFeatureStatusMap.containsKey(requestId)) {
                        Observable.timer(WAIT_LIVENESS_INTERVAL, TimeUnit.MILLISECONDS)
                                .subscribe(new Observer<Long>() {
                                    Disposable disposable;

                                    @Override
                                    public void onSubscribe(Disposable d) {
                                        disposable = d;
                                        getFeatureDelayedDisposables.add(disposable);
                                    }

                                    @Override
                                    public void onNext(Long aLong) {
                                        onFaceFeatureInfoGet(faceFeature, requestId, errorCode);
                                    }

                                    @Override
                                    public void onError(Throwable e) {

                                    }

                                    @Override
                                    public void onComplete() {
                                        getFeatureDelayedDisposables.remove(disposable);
                                    }
                                });
                    }
                }

            }
            //特征提取失败
            else {
                if (increaseAndGetValue(extractErrorRetryMap, requestId) > MAX_RETRY_TIME) {
                    extractErrorRetryMap.put(requestId, 0);

                    String msg;
                    // 传入的FaceInfo在指定的图像上无法解析人脸，此处使用的是RGB人脸数据，一般是人脸模糊
                    if (errorCode != null && errorCode == ErrorInfo.MERR_FSDK_FACEFEATURE_LOW_CONFIDENCE_LEVEL) {
                        msg = getString(R.string.low_confidence_level);
                    } else {
                        msg = "ExtractCode:" + errorCode;
                    }
                    faceHelper.setName(requestId, getString(R.string.recognize_failed_notice, msg));
                    // 在尝试最大次数后，特征提取仍然失败，则认为识别未通过
                    requestFeatureStatusMap.put(requestId, RequestFeatureStatus.FAILED);
                    retryRecognizeDelayed(requestId);
                } else {
                    requestFeatureStatusMap.put(requestId, RequestFeatureStatus.TO_RETRY);
                }
            }
        }

        @Override
        public void onFaceLivenessInfoGet(@Nullable LivenessInfo livenessInfo, final Integer requestId, Integer errorCode) {
            if (livenessInfo != null) {
                int liveness = livenessInfo.getLiveness();
                livenessMap.put(requestId, liveness);
                // 非活体，重试
                if (liveness == LivenessInfo.NOT_ALIVE) {
                    faceHelper.setName(requestId, getString(R.string.recognize_failed_notice, "NOT_ALIVE"));
                    // 延迟 FAIL_RETRY_INTERVAL 后，将该人脸状态置为UNKNOWN，帧回调处理时会重新进行活体检测
                    retryLivenessDetectDelayed(requestId);
                }
            } else {
                if (increaseAndGetValue(livenessErrorRetryMap, requestId) > MAX_RETRY_TIME) {
                    livenessErrorRetryMap.put(requestId, 0);
                    String msg;
                    // 传入的FaceInfo在指定的图像上无法解析人脸，此处使用的是RGB人脸数据，一般是人脸模糊
                    if (errorCode != null && errorCode == ErrorInfo.MERR_FSDK_FACEFEATURE_LOW_CONFIDENCE_LEVEL) {
                        msg = getString(R.string.low_confidence_level);
                    } else {
                        msg = "ProcessCode:" + errorCode;
                    }
                    faceHelper.setName(requestId, getString(R.string.recognize_failed_notice, msg));
                    retryLivenessDetectDelayed(requestId);
                } else {
                    livenessMap.put(requestId, LivenessInfo.UNKNOWN);
                }
            }
        }


    };
    /**
     * 绘制人脸框的控件
     */
    private FaceRectView faceRectView;
    /**
     * USB 预览界面
     */
    private CameraViewInterface mUVCCameraViewFirst;
    private List<UsbDevice> usbDeviceList;
    private Switch switchLivenessDetect;
    private USBMonitor mUSBMonitor;
    private UVCCameraHandler mHandlerFirst;
    /**
     * 监听USB
     */
    private final USBMonitor.OnDeviceConnectListener mOnDeviceConnectListener = new USBMonitor.OnDeviceConnectListener() {
        @Override
        public void onAttach(final UsbDevice device) {
            if (usbDeviceList.size() == 0) {
                usbDeviceList.add(device);
            }
            Toast.makeText(UsbCameraRegisterAndRecognizeActivity.this, "onAttach USB_DEVICE:" + device.getDeviceName() + usbDeviceList.size(), Toast.LENGTH_SHORT).show();
            mUSBMonitor.requestPermission(usbDeviceList.get(0));
        }

        @Override
        public void onConnect(final UsbDevice device, final USBMonitor.UsbControlBlock ctrlBlock, final boolean createNew) {
            //设备连接成功
            Log.v(TAG, "onConnect:" + device.getDeviceName());
            Toast.makeText(UsbCameraRegisterAndRecognizeActivity.this, "Connect:" + device.getDeviceName() + usbDeviceList.size(), Toast.LENGTH_SHORT).show();
            Log.v(TAG, "mHandlerFirst.isOpened():" + mHandlerFirst.isOpened());
            if (mHandlerFirst.isOpened()) {
                mHandlerFirst.close();
            } else {
                mHandlerFirst.open(ctrlBlock);
                startPreview();
                Log.v(TAG, "onConnect init finish");
            }
        }

        @Override
        public void onDisconnect(final UsbDevice device, final USBMonitor.UsbControlBlock ctrlBlock) {
            Log.v(TAG, "onDisconnect:" + device.getDeviceName());
            Toast.makeText(UsbCameraRegisterAndRecognizeActivity.this, "Disconnect:" + device.getDeviceName() + usbDeviceList.size(), Toast.LENGTH_SHORT).show();
            if (usbDeviceList.size() == 0) {
                return;
            }
            if (ctrlBlock.getDeviceName().equals(usbDeviceList.get(0).getDeviceName())) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(UsbCameraRegisterAndRecognizeActivity.this, "断开连接:" + device.getDeviceName(), Toast.LENGTH_SHORT).show();
                        Log.i(TAG, "run:关闭1");
                        mHandlerFirst.close();
                    }
                });
            }
            if (usbDeviceList.size() == 1) {
                usbDeviceList.remove(usbDeviceList.get(0));
            }

        }

        @Override
        public void onDettach(final UsbDevice device) {
            Log.v(TAG, "onDettach:" + device.getDeviceName());
            Toast.makeText(UsbCameraRegisterAndRecognizeActivity.this, "USB_DEVICE_DETACHED", Toast.LENGTH_SHORT).show();

        }

        @Override
        public void onCancel(final UsbDevice device) {
            Log.v(TAG, "onCancel:");
            if (usbDeviceList.size() == 1) {
                usbDeviceList.remove(usbDeviceList.get(0));
            }
        }
    };
    private String registerName = "";
    /**
     * USB 相机数据回调
     */
    UvcCameraDataCallBack firstDataCallBack = new UvcCameraDataCallBack() {
        @Override
        public void getData(byte[] nv21) {
            Log.v(TAG, "数据回调:" + nv21.length);
            previewCount++;
            if (previewCount % 2 == 1) {
                if (previewCount > 2048) {
                    previewCount = 0;
                }
                return;
            }
            if (faceRectView != null) {
                faceRectView.clearFaceInfo();
                Log.v(TAG, "faceRectView.clearFaceInfo");
            }
            List<FacePreviewInfo> facePreviewInfoList = faceHelper.onPreviewFrame(nv21);
            if (facePreviewInfoList != null && faceRectView != null && drawHelper != null) {

                drawPreviewInfo(facePreviewInfoList);
            }
            registerFace(nv21, facePreviewInfoList);
            clearLeftFace(facePreviewInfoList);

            if (facePreviewInfoList != null && facePreviewInfoList.size() > 0) {
                for (int i = 0; i < facePreviewInfoList.size(); i++) {
                    Integer status = requestFeatureStatusMap.get(facePreviewInfoList.get(i).getTrackId());
                    /**
                     * 在活体检测开启，在人脸识别状态不为成功或人脸活体状态不为处理中（ANALYZING）且不为处理完成（ALIVE、NOT_ALIVE）时重新进行活体检测
                     */
                    if (livenessDetect && (status == null || status != RequestFeatureStatus.SUCCEED)) {
                        Integer liveness = livenessMap.get(facePreviewInfoList.get(i).getTrackId());
                        if (liveness == null
                                || (liveness != LivenessInfo.ALIVE && liveness != LivenessInfo.NOT_ALIVE && liveness != RequestLivenessStatus.ANALYZING)) {
                            livenessMap.put(facePreviewInfoList.get(i).getTrackId(), RequestLivenessStatus.ANALYZING);
                            faceHelper.requestFaceLiveness(nv21, facePreviewInfoList.get(i).getFaceInfo(), DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT, FaceEngine.CP_PAF_NV21, facePreviewInfoList.get(i).getTrackId(), LivenessType.RGB);
                        }
                    }
                    /**
                     * 对于每个人脸，若状态为空或者为失败，则请求特征提取（可根据需要添加其他判断以限制特征提取次数），
                     * 特征提取回传的人脸特征结果在{@link FaceListener#onFaceFeatureInfoGet(FaceFeature, Integer, Integer)}中回传
                     */
                    if (status == null
                            || status == RequestFeatureStatus.TO_RETRY) {
                        requestFeatureStatusMap.put(facePreviewInfoList.get(i).getTrackId(), RequestFeatureStatus.SEARCHING);
                        faceHelper.requestFaceFeature(nv21, facePreviewInfoList.get(i).getFaceInfo(), DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT, FaceEngine.CP_PAF_NV21, facePreviewInfoList.get(i).getTrackId());
                        Log.i(TAG, "onPreview: fr start = " + System.currentTimeMillis() + " trackId = " + facePreviewInfoList.get(i).getTrackId());
                    }
                }
            }
        }
    };
    //Activity最外层的Layout视图
    private View activityRootView;
    //屏幕高度
    private int screenHeight = 0;
    //软件盘弹起后所占高度阀值
    private int keyHeight = 0;

    private void startPreview() {
        final SurfaceTexture st = mUVCCameraViewFirst.getSurfaceTexture();
        mHandlerFirst.startPreview(new Surface(st));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_usb_register_and_recognize);
        //保持亮屏
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WindowManager.LayoutParams attributes = getWindow().getAttributes();
            attributes.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
            getWindow().setAttributes(attributes);
        }

        // Activity启动后就锁定为启动时的方向
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED);
        //本地人脸库初始化
        FaceServer.getInstance().init(this);
        initView();
        initCamera();
        initEngine();

    }

    @Override
    protected void onResume() {
        super.onResume();
        //添加layout大小发生改变监听器
        activityRootView.addOnLayoutChangeListener(this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mUSBMonitor.register();
        if (mUVCCameraViewFirst != null) {
            mUVCCameraViewFirst.onResume();
        }
    }

    @Override
    protected void onStop() {
        mHandlerFirst.close();
        if (mUVCCameraViewFirst != null) {
            mUVCCameraViewFirst.onPause();
        }
        if (mUSBMonitor != null) {
            mUSBMonitor.unregister();//usb管理器解绑
        }
        super.onStop();

    }

    private void initCamera() {
        mUSBMonitor = new USBMonitor(this, mOnDeviceConnectListener);
        final List<DeviceFilter> filter = DeviceFilter.getDeviceFilters(this, R.xml.device_filter);
        usbDeviceList = mUSBMonitor.getDeviceList(filter.get(0));

        //设置显示宽高
        mUVCCameraViewFirst.setAspectRatio(faceRectView.getWidth() / (float) faceRectView.getHeight());
        mHandlerFirst = UVCCameraHandler.createHandler(this, mUVCCameraViewFirst
                , 2, DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT, UVCCamera.FRAME_FORMAT_YUYV
                , BANDWIDTH_FACTORS[0], firstDataCallBack);

        mHandlerFirst.addCallback(new UVCCameraHandler.CameraCallback() {
            @Override
            public void onOpen() {

            }

            @Override
            public void onClose() {

            }

            @Override
            public void onStartPreview() {

            }

            @Override
            public void onStopPreview() {
                startPreview();
            }

            @Override
            public void onStartRecording() {

            }

            @Override
            public void onStopRecording() {

            }

            @Override
            public void onError(Exception e) {
                e.printStackTrace();
            }
        });


    }

    private void initView() {
        activityRootView = findViewById(R.id.root_layout);
        mUVCCameraViewFirst = findViewById(R.id.camera_view_first);
        faceRectView = findViewById(R.id.single_camera_face_rect_view);
        switchLivenessDetect = findViewById(R.id.single_camera_switch_liveness_detect);
        switchLivenessDetect.setChecked(livenessDetect);
        switchLivenessDetect.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                CameraDialog.showDialog(UsbCameraRegisterAndRecognizeActivity.this);
                livenessDetect = isChecked;
            }
        });
        editName = findViewById(R.id.edv_name);
        RecyclerView recyclerShowFaceInfo = findViewById(R.id.single_camera_recycler_view_person);
        compareResultList = new ArrayList<>();
        adapter = new FaceSearchResultAdapter(compareResultList, this);
        recyclerShowFaceInfo.setAdapter(adapter);
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int spanCount = (int) (dm.widthPixels / (getResources().getDisplayMetrics().density * 100 + 0.5f));
        recyclerShowFaceInfo.setLayoutManager(new GridLayoutManager(this, spanCount));
        recyclerShowFaceInfo.setItemAnimator(new DefaultItemAnimator());

        //获取屏幕高度
        screenHeight = this.getWindowManager().getDefaultDisplay().getHeight();
        //阀值设置为屏幕高度的1/3
        keyHeight = screenHeight / 3;

    }

    /**
     * 初始化引擎
     */
    private void initEngine() {
        ftEngine = new FaceEngine();
        ftInitCode = ftEngine.init(this, FaceEngine.ASF_DETECT_MODE_VIDEO, ConfigUtil.getFtOrient(this),
                16, MAX_DETECT_NUM, FaceEngine.ASF_FACE_DETECT);

        frEngine = new FaceEngine();
        frInitCode = frEngine.init(this, FaceEngine.ASF_DETECT_MODE_IMAGE, FaceEngine.ASF_OP_0_ONLY,
                16, MAX_DETECT_NUM, FaceEngine.ASF_FACE_RECOGNITION);

        flEngine = new FaceEngine();
        flInitCode = flEngine.init(this, FaceEngine.ASF_DETECT_MODE_IMAGE, FaceEngine.ASF_OP_0_ONLY,
                16, MAX_DETECT_NUM, FaceEngine.ASF_LIVENESS);


        VersionInfo versionInfo = new VersionInfo();
        ftEngine.getVersion(versionInfo);
        Log.i(TAG, "initEngine:  init: " + ftInitCode + "  version:" + versionInfo);

        if (ftInitCode != ErrorInfo.MOK) {
            String error = getString(R.string.specific_engine_init_failed, "ftEngine", ftInitCode);
            Log.i(TAG, "initEngine: " + error);
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
        }
        if (frInitCode != ErrorInfo.MOK) {
            String error = getString(R.string.specific_engine_init_failed, "frEngine", frInitCode);
            Log.i(TAG, "initEngine: " + error);
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
        }
        if (flInitCode != ErrorInfo.MOK) {
            String error = getString(R.string.specific_engine_init_failed, "flEngine", flInitCode);
            Log.i(TAG, "initEngine: " + error);
            Toast.makeText(this, error, Toast.LENGTH_SHORT).show();
        }
        initFaceHelper(null);
    }

    /**
     * 销毁引擎，faceHelper中可能会有特征提取耗时操作仍在执行，加锁防止crash
     */
    private void unInitEngine() {
        if (ftInitCode == ErrorInfo.MOK && ftEngine != null) {
            synchronized (ftEngine) {
                int ftUnInitCode = ftEngine.unInit();
                Log.i(TAG, "unInitEngine: " + ftUnInitCode);
            }
        }
        if (frInitCode == ErrorInfo.MOK && frEngine != null) {
            synchronized (frEngine) {
                int frUnInitCode = frEngine.unInit();
                Log.i(TAG, "unInitEngine: " + frUnInitCode);
            }
        }
        if (flInitCode == ErrorInfo.MOK && flEngine != null) {
            synchronized (flEngine) {
                int flUnInitCode = flEngine.unInit();
                Log.i(TAG, "unInitEngine: " + flUnInitCode);
            }
        }
    }

    @Override
    protected void onDestroy() {

        unInitEngine();
        if (faceHelper != null) {
            ConfigUtil.setTrackedFaceCount(this, faceHelper.getTrackedFaceCount());
            faceHelper.release();
            faceHelper = null;
        }
        if (getFeatureDelayedDisposables != null) {
            getFeatureDelayedDisposables.clear();
        }
        if (delayFaceTaskCompositeDisposable != null) {
            delayFaceTaskCompositeDisposable.clear();
        }

        FaceServer.getInstance().unInit();
        if (mUSBMonitor != null) {
            mUSBMonitor.destroy();
            mUSBMonitor = null;
        }
        mUVCCameraViewFirst = null;

        super.onDestroy();
    }

    public void safeDispose(Disposable disposable) {
        if (disposable != null && !disposable.isDisposed()) {
            disposable.dispose();
        }
    }

    private boolean checkPermissions(String[] neededPermissions) {
        if (neededPermissions == null || neededPermissions.length == 0) {
            return true;
        }
        boolean allGranted = true;
        for (String neededPermission : neededPermissions) {
            allGranted &= ContextCompat.checkSelfPermission(this, neededPermission) == PackageManager.PERMISSION_GRANTED;
        }
        return allGranted;
    }

    private void initFaceHelper(Integer trackedFaceCount) {

        drawHelper = new DrawHelper(DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT, getWindowManager().getDefaultDisplay().getWidth(), getWindowManager().getDefaultDisplay().getHeight(), 0
                , 0, false, false, false);
        Log.i(TAG, "initFaceHelper: ");
        // 记录切换时的人脸序号
        if (faceHelper != null) {
            trackedFaceCount = faceHelper.getTrackedFaceCount();
            faceHelper.release();
        }
        faceHelper = new FaceHelper.Builder()
                .ftEngine(ftEngine)
                .frEngine(frEngine)
                .flEngine(flEngine)
                .frQueueSize(MAX_DETECT_NUM)
                .flQueueSize(MAX_DETECT_NUM)
                .previewSize(null)
                .faceListener(faceListener)
                .trackedFaceCount(trackedFaceCount == null ? ConfigUtil.getTrackedFaceCount(UsbCameraRegisterAndRecognizeActivity.this.getApplicationContext()) : trackedFaceCount)
                .build();
    }

    private void registerFace(final byte[] nv21, final List<FacePreviewInfo> facePreviewInfoList) {
        if (registerStatus == REGISTER_STATUS_READY && facePreviewInfoList != null && facePreviewInfoList.size() > 0) {
            registerStatus = REGISTER_STATUS_PROCESSING;
            Observable.create(new ObservableOnSubscribe<Boolean>() {
                @Override
                public void subscribe(ObservableEmitter<Boolean> emitter) {
                    boolean success = FaceServer.getInstance().registerNv21(UsbCameraRegisterAndRecognizeActivity.this, nv21.clone(), DEFAULT_PREVIEW_WIDTH, DEFAULT_PREVIEW_HEIGHT,
                            facePreviewInfoList.get(0).getFaceInfo(), registerName);
                    //                            "registered " + faceHelper.getTrackedFaceCount());
                    Log.d(TAG, "registerNv21 " + success);
                    emitter.onNext(success);
                }
            })
                    .subscribeOn(Schedulers.computation())
                    .observeOn(AndroidSchedulers.mainThread())
                    .subscribe(new Observer<Boolean>() {
                        @Override
                        public void onSubscribe(Disposable d) {

                        }

                        @Override
                        public void onNext(Boolean success) {
                            String result = success ? "register success!" : "register failed!";
                            Toast.makeText(UsbCameraRegisterAndRecognizeActivity.this, result, Toast.LENGTH_SHORT).show();
                            registerStatus = REGISTER_STATUS_DONE;
                        }

                        @Override
                        public void onError(Throwable e) {
                            e.printStackTrace();
                            Toast.makeText(UsbCameraRegisterAndRecognizeActivity.this, "register failed!", Toast.LENGTH_SHORT).show();
                            registerStatus = REGISTER_STATUS_DONE;
                        }

                        @Override
                        public void onComplete() {

                        }
                    });
        }
    }

    private void drawPreviewInfo(List<FacePreviewInfo> facePreviewInfoList) {
        Log.i(TAG, "drawPreviewInfo: " + facePreviewInfoList.size());
        List<DrawInfo> drawInfoList = new ArrayList<>();
        for (int i = 0; i < facePreviewInfoList.size(); i++) {
            //TODO 此处可以获取更多注册信息
            String name = faceHelper.getName(facePreviewInfoList.get(i).getTrackId());
            Integer liveness = livenessMap.get(facePreviewInfoList.get(i).getTrackId());
            Integer recognizeStatus = requestFeatureStatusMap.get(facePreviewInfoList.get(i).getTrackId());
            Log.i(TAG, "name: " + name + " liveness " + liveness + " recognizeStatus " + recognizeStatus);
            // 根据识别结果和活体结果设置颜色
            int color = RecognizeColor.COLOR_UNKNOWN;
            if (recognizeStatus != null) {
                if (recognizeStatus == RequestFeatureStatus.FAILED) {
                    color = RecognizeColor.COLOR_FAILED;
                }
                if (recognizeStatus == RequestFeatureStatus.SUCCEED) {
                    color = RecognizeColor.COLOR_SUCCESS;
                }
            }
            if (liveness != null && liveness == LivenessInfo.NOT_ALIVE) {
                color = RecognizeColor.COLOR_FAILED;
            }

            drawInfoList.add(new DrawInfo(drawHelper.adjustRect(facePreviewInfoList.get(i).getFaceInfo().getRect()),
                    GenderInfo.UNKNOWN, AgeInfo.UNKNOWN_AGE, liveness == null ? LivenessInfo.UNKNOWN : liveness, color,
                    name == null ? String.valueOf(facePreviewInfoList.get(i).getTrackId()) : name));
        }
        drawHelper.draw(faceRectView, drawInfoList);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == ACTION_REQUEST_PERMISSIONS) {
            boolean isAllGranted = true;
            for (int grantResult : grantResults) {
                isAllGranted &= (grantResult == PackageManager.PERMISSION_GRANTED);
            }
            if (isAllGranted) {
                initEngine();
                initCamera();
            } else {
                Toast.makeText(this, R.string.permission_denied, Toast.LENGTH_SHORT).show();
            }
        }
    }

    /**
     * 删除已经离开的人脸
     *
     * @param facePreviewInfoList
     *         人脸和trackId列表
     */
    private void clearLeftFace(List<FacePreviewInfo> facePreviewInfoList) {

        if (compareResultList != null) {
            for (int i = compareResultList.size() - 1; i >= 0; i--) {
                if (!requestFeatureStatusMap.containsKey(compareResultList.get(i).getTrackId())) {
                    compareResultList.remove(i);
                    adapter.notifyItemRemoved(i);
                }
            }
        }
        if (facePreviewInfoList == null || facePreviewInfoList.size() == 0) {
            requestFeatureStatusMap.clear();
            livenessMap.clear();
            livenessErrorRetryMap.clear();
            extractErrorRetryMap.clear();
            if (getFeatureDelayedDisposables != null) {
                getFeatureDelayedDisposables.clear();
            }
            return;
        }
        Enumeration<Integer> keys = requestFeatureStatusMap.keys();
        while (keys.hasMoreElements()) {
            int key = keys.nextElement();
            boolean contained = false;
            for (FacePreviewInfo facePreviewInfo : facePreviewInfoList) {
                if (facePreviewInfo.getTrackId() == key) {
                    contained = true;
                    break;
                }
            }
            if (!contained) {
                requestFeatureStatusMap.remove(key);
                livenessMap.remove(key);
                livenessErrorRetryMap.remove(key);
                extractErrorRetryMap.remove(key);
            }
        }
    }

    private void searchFace(final FaceFeature frFace, final Integer requestId) {
        Observable
                .create(new ObservableOnSubscribe<CompareResult>() {
                    @Override
                    public void subscribe(ObservableEmitter<CompareResult> emitter) {
                        Log.i(TAG, "subscribe: Logfr search start = " + System.currentTimeMillis() + " trackId = " + requestId);
                        CompareResult compareResult = FaceServer.getInstance().getTopOfFaceLib(frFace);
                        Log.i(TAG, "subscribe: fr search end = " + System.currentTimeMillis() + " trackId = " + requestId);
                        emitter.onNext(compareResult);

                    }
                })
                .subscribeOn(Schedulers.computation())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(new Observer<CompareResult>() {
                    @Override
                    public void onSubscribe(Disposable d) {

                    }

                    @Override
                    public void onNext(CompareResult compareResult) {
                        if (compareResult == null || compareResult.getUserName() == null) {
                            requestFeatureStatusMap.put(requestId, RequestFeatureStatus.FAILED);
                            faceHelper.setName(requestId, "VISITOR " + requestId);
                            return;
                        }

                        //                        Log.i(TAG, "onNext: fr search get result  = " + System.currentTimeMillis() + " trackId = " + requestId + "  similar = " + compareResult.getSimilar());
                        if (compareResult.getSimilar() > SIMILAR_THRESHOLD) {
                            boolean isAdded = false;
                            if (compareResultList == null) {
                                requestFeatureStatusMap.put(requestId, RequestFeatureStatus.FAILED);
                                faceHelper.setName(requestId, "VISITOR " + requestId);
                                return;
                            }
                            for (CompareResult compareResult1 : compareResultList) {
                                if (compareResult1.getTrackId() == requestId) {
                                    isAdded = true;
                                    break;
                                }
                            }
                            if (!isAdded) {
                                //对于多人脸搜索，假如最大显示数量为 MAX_DETECT_NUM 且有新的人脸进入，则以队列的形式移除
                                if (compareResultList.size() >= MAX_DETECT_NUM) {
                                    compareResultList.remove(0);
                                    adapter.notifyItemRemoved(0);
                                }
                                //添加显示人员时，保存其trackId
                                compareResult.setTrackId(requestId);
                                compareResultList.add(compareResult);
                                adapter.notifyItemInserted(compareResultList.size() - 1);
                            }
                            requestFeatureStatusMap.put(requestId, RequestFeatureStatus.SUCCEED);
                            faceHelper.setName(requestId, getString(R.string.recognize_success_notice, compareResult.getUserName()));

                        } else {
                            faceHelper.setName(requestId, getString(R.string.recognize_failed_notice, "NOT_REGISTERED"));
                            retryRecognizeDelayed(requestId);
                        }
                    }

                    @Override
                    public void onError(Throwable e) {
                        faceHelper.setName(requestId, getString(R.string.recognize_failed_notice, "NOT_REGISTERED"));
                        retryRecognizeDelayed(requestId);
                    }

                    @Override
                    public void onComplete() {

                    }
                });
    }

    /**
     * 将准备注册的状态置为{@link #REGISTER_STATUS_READY}
     *
     * @param view
     *         注册按钮
     */
    public void register(View view) {
        final EditText editText = new EditText(this);

        new AlertDialog.Builder(this).setTitle("请输入注册姓名").setPositiveButton("确定", null).setView(editText).show();
        registerName = editText.getText().toString();
        if (registerName.equals("")) {
            Toast.makeText(this, "姓名不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        //        registerName = editName.getText().toString();
        //        if (registerName.equals("")) {
        //            Toast.makeText(this, "姓名不能为空", Toast.LENGTH_SHORT).show();
        //            return;
        //        }
        if (registerStatus == REGISTER_STATUS_DONE) {
            registerStatus = REGISTER_STATUS_READY;
        }
    }

    /**
     * 在{@link #}第一次布局完成后，去除该监听，并且进行引擎和相机的初始化
     */
    @Override
    public void onGlobalLayout() {
        if (!checkPermissions(NEEDED_PERMISSIONS)) {
            ActivityCompat.requestPermissions(this, NEEDED_PERMISSIONS, ACTION_REQUEST_PERMISSIONS);
        } else {
            initEngine();
            initCamera();
        }
    }

    /**
     * 将map中key对应的value增1回传
     *
     * @param countMap
     *         map
     * @param key
     *         key
     *
     * @return 增1后的value
     */
    public int increaseAndGetValue(Map<Integer, Integer> countMap, int key) {
        if (countMap == null) {
            return 0;
        }
        Integer value = countMap.get(key);
        if (value == null) {
            value = 0;
        }
        countMap.put(key, ++value);
        return value;
    }

    /**
     * 延迟 FAIL_RETRY_INTERVAL 重新进行活体检测
     *
     * @param requestId
     *         人脸ID
     */
    private void retryLivenessDetectDelayed(final Integer requestId) {
        Observable.timer(FAIL_RETRY_INTERVAL, TimeUnit.MILLISECONDS)
                .subscribe(new Observer<Long>() {
                    Disposable disposable;

                    @Override
                    public void onSubscribe(Disposable d) {
                        disposable = d;
                        delayFaceTaskCompositeDisposable.add(disposable);
                    }

                    @Override
                    public void onNext(Long aLong) {

                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                    }

                    @Override
                    public void onComplete() {
                        // 将该人脸状态置为UNKNOWN，帧回调处理时会重新进行活体检测
                        if (livenessDetect) {
                            faceHelper.setName(requestId, Integer.toString(requestId));
                        }
                        livenessMap.put(requestId, LivenessInfo.UNKNOWN);
                        delayFaceTaskCompositeDisposable.remove(disposable);
                    }
                });
    }

    /**
     * 延迟 FAIL_RETRY_INTERVAL 重新进行人脸识别
     *
     * @param requestId
     *         人脸ID
     */
    private void retryRecognizeDelayed(final Integer requestId) {
        requestFeatureStatusMap.put(requestId, RequestFeatureStatus.FAILED);
        Observable.timer(FAIL_RETRY_INTERVAL, TimeUnit.MILLISECONDS)
                .subscribe(new Observer<Long>() {
                    Disposable disposable;

                    @Override
                    public void onSubscribe(Disposable d) {
                        disposable = d;
                        delayFaceTaskCompositeDisposable.add(disposable);
                    }

                    @Override
                    public void onNext(Long aLong) {

                    }

                    @Override
                    public void onError(Throwable e) {
                        e.printStackTrace();
                    }

                    @Override
                    public void onComplete() {
                        // 将该人脸特征提取状态置为FAILED，帧回调处理时会重新进行活体检测
                        faceHelper.setName(requestId, Integer.toString(requestId));
                        requestFeatureStatusMap.put(requestId, RequestFeatureStatus.TO_RETRY);
                        delayFaceTaskCompositeDisposable.remove(disposable);
                    }
                });
    }


    @Override
    public USBMonitor getUSBMonitor() {
        return mUSBMonitor;
    }

    @Override
    public void onDialogResult(boolean canceled) {
        if (!canceled) {

        }
    }

    @Override
    public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
        //old是改变前的左上右下坐标点值，没有old的是改变后的左上右下坐标点值
        //        System.out.println(oldLeft + " " + oldTop +" " + oldRight + " " + oldBottom);
        //        System.out.println(left + " " + top +" " + right + " " + bottom);
        //现在认为只要控件将Activity向上推的高度超过了1/3屏幕高，就认为软键盘弹起
        if (oldBottom != 0 && bottom != 0 && (oldBottom - bottom > keyHeight)) {

            Toast.makeText(this, "监听到软键盘弹起...", Toast.LENGTH_SHORT).show();


        } else if (oldBottom != 0 && bottom != 0 && (bottom - oldBottom > keyHeight)) {
            Toast.makeText(this, "监听到软件盘关闭...", Toast.LENGTH_SHORT).show();
            startPreview();
        }

    }
}
