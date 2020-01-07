package com.arcsoft.arcfacedemo.activity;

import android.annotation.SuppressLint;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.serialport.DeviceControlSpd;
import android.support.v7.app.AppCompatActivity;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.arcsoft.arcfacedemo.R;
import com.arcsoft.arcfacedemo.faceserver.FaceServer;
import com.arcsoft.arcfacedemo.util.ConfigUtil;
import com.arcsoft.arcfacedemo.util.PlaySoundUtils;
import com.arcsoft.arcfacedemo.util.Yuv;
import com.arcsoft.face.FaceEngine;
import com.arcsoft.face.FaceInfo;
import com.speedata.libid2.IDInfor;
import com.speedata.libid2.IDManager;
import com.speedata.libid2.IDReadCallBack;
import com.speedata.libid2.IID2Service;
import com.speedata.utils.ProgressDialogUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 读取二代证照片、id去注册人脸信息
 */
public class IdCardRegisterActivity extends AppCompatActivity {

    private static final int GET_DATA = 1;
    private static final int INIT_RESULT_SUCCESS = 2;
    private static final int INIT_RESULT_FAILED = 3;
    private TextView tvInitInfor;
    private TextView tvRegisterStatus;
    private TextView tvIDInfor;
    private ImageView imgPic;
    private IID2Service iid2Service;
    private FaceServer faceServer;
    private FaceEngine ftEngine;
    @SuppressLint("HandlerLeak")
    Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
                case GET_DATA:
                    iid2Service.getIDInfor(false, true);
                    IDInfor idInfor1 = (IDInfor) msg.obj;

                    if (idInfor1.isSuccess()) {
                        PlaySoundUtils.play(1, 1);
                        tvIDInfor.setText("姓名:" + idInfor1.getName() + "\n身份证号：" + idInfor1.getNum()
                                + "\n性别：" + idInfor1.getSex()
                                + "\n民族：" + idInfor1.getNation() + "\n住址:"
                                + idInfor1.getAddress() + "\n出生：" + idInfor1.getYear() + "年" + idInfor1
                                .getMonth() + "月" + idInfor1.getDay() + "日" + "\n有效期限：" + idInfor1
                                .getDeadLine());
                        Bitmap bmps = idInfor1.getBmps();
                        imgPic.setImageBitmap(bmps);

                        List<FaceInfo> faceInfos = new ArrayList<>();
                        bmps = Bitmap.createScaledBitmap(bmps,bmps.getWidth()*4,bmps.getHeight()*4, true);
                        byte[] bytes = Yuv.getNV21(bmps.getWidth(),bmps.getHeight(),bmps);
                        ftEngine.detectFaces(bytes, bmps.getWidth(), bmps.getHeight(), FaceEngine.CP_PAF_NV21, faceInfos);
                        if (faceInfos.size() > 0) {
                            boolean result = faceServer.registerNv12(IdCardRegisterActivity.this, bytes,
                                    bmps.getWidth(), bmps.getHeight(), faceInfos.get(0),
                                    idInfor1.getName() + "," + idInfor1.getNum());
                            if (result) {
                                tvRegisterStatus.setText("注册成功");
                            } else {
                                tvRegisterStatus.setText("注册失败");
                            }
                        } else {
                            Toast.makeText(IdCardRegisterActivity.this, "没有获取到有效底片", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                    }
                    break;
                case INIT_RESULT_SUCCESS:
                    iid2Service.getIDInfor(false, true);
                    tvInitInfor.setText("初始化成功，请将二代证放到读卡区");
                    ProgressDialogUtils.dismissProgressDialog();

                    break;
                case INIT_RESULT_FAILED:
                    tvInitInfor.setText("初始化失败：" + (String) msg.obj);
                    ProgressDialogUtils.dismissProgressDialog();
                    break;
            }

        }
    };

    private void initFaceEngine() {
        ftEngine = new FaceEngine();
        faceServer.init(this);
        int ftInitCode = ftEngine.init(this, FaceEngine.ASF_DETECT_MODE_VIDEO, ConfigUtil.getFtOrient(this),
                16, 1, FaceEngine.ASF_FACE_DETECT);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_id_card_register);
        initView();
        faceServer = FaceServer.getInstance();
        initID();
        initFaceEngine();
        PlaySoundUtils.initSoundPool(this);
    }

    private void initView() {
        tvInitInfor = findViewById(R.id.tv_init_infor);
        tvRegisterStatus = findViewById(R.id.tv_msg);
        tvIDInfor = (TextView) findViewById(R.id.tv_idinfor);
        imgPic = (ImageView) findViewById(R.id.img_pic);

    }

    private void initID() {
        ProgressDialogUtils.showProgressDialog(IdCardRegisterActivity.this, "正在初始化");
        new Thread(new Runnable() {
            @Override
            public void run() {
                iid2Service = IDManager.getInstance();
                try {
                    long temp = System.currentTimeMillis();

                    final boolean result = iid2Service.initDev(IdCardRegisterActivity.this
                            , new IDReadCallBack() {
                                @Override
                                public void callBack(IDInfor infor) {
                                    Message message = new Message();
                                    message.obj = infor;
                                    message.what = GET_DATA;
                                    handler.sendMessage(message);
                                }
//                            });
//                                "id2":{"serialPort":"/dev/ttyMT0","braut":115200,"powerType":"NEW_MAIN","gpio":[12]},
                                                }, "dev/ttyMT0", 115200, DeviceControlSpd.PowerType.NEW_MAIN, 12);
                    //                            },"/dev/ttyMT1",115200, DeviceControlSpd.PowerType.MAIN,new int[]{93});

                    if (result) {
                        Message message = new Message();
                        message.what = INIT_RESULT_SUCCESS;
                        handler.sendMessage(message);
                    } else {
                        Message message = new Message();
                        message.what = INIT_RESULT_FAILED;
                        message.obj = "初始化失败";
                        handler.sendMessage(message);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    Message message = new Message();
                    message.what = INIT_RESULT_FAILED;
                    message.obj = e.getMessage();
                    handler.sendMessage(message);
                }
            }
        }).start();

    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            iid2Service.releaseDev();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
