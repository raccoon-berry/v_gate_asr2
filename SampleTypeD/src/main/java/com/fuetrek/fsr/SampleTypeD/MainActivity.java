package com.fuetrek.fsr.SampleTypeD;


import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Handler;

import com.fuetrek.fsr.FSRServiceOpen;
import com.fuetrek.fsr.FSRServiceEventListener;
import com.fuetrek.fsr.FSRServiceEnum.*;
import com.fuetrek.fsr.entity.*;

import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;


class SyncObj{
    boolean isDone=false;
    boolean isShutdown=false;

    synchronized void initialize() {
        isDone=false;
        isShutdown=false;
    }

    synchronized String wait_(){
        try {
            // wait_()より前にnotify_()が呼ばれた場合の対策としてisDoneフラグをチェックしている
            while(isDone==false && isShutdown==false){
                wait(1000);
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        if (isShutdown) {
            return "shutdown";
        }
        return "done";
    }

    synchronized void notify_(){
        if (isShutdown==false) {
            isDone = true;
            notify();
        }
    }

    synchronized void shutdown_(){
        isShutdown=true;
        notify();
    }
}


public class MainActivity extends Activity{
    private Handler handler_;
    private Button buttonStart_;
    private ProgressBar progressLevel_;
    private TextView textResult_;
    private fsrController controller_ = new fsrController();
    private String[] yummies = {"美味","うま","うめ","おいし","旨","最高"};

    // BackendTypeはBackendType.D固定
    private static final BackendType backendType_ = BackendType.D;


    // Context
    private Activity activity_ = null;



    // FSRServiceの待ち処理でブロッキングする実装としている為、
    // UI更新を妨げないよう別スレッドとしている。
    public class fsrController extends Thread implements FSRServiceEventListener {
        FSRServiceOpen fsr_;
        SyncObj event_CompleteConnect_ = new SyncObj();
        SyncObj event_CompleteDisconnect_ = new SyncObj();
        SyncObj event_EndRecognition_ = new SyncObj();
        SyncObj event_StopRecognition_ = new SyncObj();
        Ret ret_;
        String result_;
        boolean carryOn = true;

        // 認識完了時の処理
        // (UIスレッドで動作させる為にRunnable()を使用している)
        final Runnable notifyFinished = new Runnable() {
            public void run() {
//                try {
//                    // 念のためスレッドの完了を待つ
//                    controller_.join();
//                } catch (InterruptedException e) {
//                }
                if (controller_.result_ == null) {
                    return;
                }
                textResult_.append("***Result***" + System.getProperty("line.separator"));
                String target = controller_.result_;
                int count = 0;
                for (String yummy : yummies) {
                     count += (target.length() - target.replaceAll(yummy, "").length()) / yummy.length();
                }
                textResult_.append(
                        target +
                        System.getProperty("line.separator") +
                        count +
                        "yummy" +
                        System.getProperty("line.separator") +
                        System.getProperty("line.separator"));
                buttonStart_.setEnabled(true);

            }
        };


        // 認識処理
        @Override
        public void run() {
            result_ = "";
            try {
                final ConstructorEntity construct = new ConstructorEntity();
                construct.setContext(activity_);

                // TODO apiキーをコミットしないこと
                // 別途発行されるAPIキーを設定してください(以下の値はダミーです)
                construct.setApiKey("70473974327a764c314337636a624e5131326a6b332e6d4257367446344d30354d745064572e6452696132");

                construct.setSpeechTime(10000);
                construct.setRecordSize(240);
                construct.setRecognizeTime(10000);

                // インスタンス生成
                // (thisは FSRServiceEventListenerをimplementsしている。)
                if( null == fsr_ ){
                    fsr_ = new FSRServiceOpen(this, this, construct);
                }

                if (!carryOn) {
                    return;
                }
                // connect
                fsr_.connectSession(backendType_);
                String connectStatus = event_CompleteConnect_.wait_();
                if (connectStatus.equals("shutdown")) {
                    while(carryOn) {
                        sleep(10);
                    }
                    return;
                }
                if( ret_ != Ret.RetOk ){
                    Exception e = new Exception("filed connectSession.");
                    throw e;
                }

                for (int i=0;i<3;i++){
                    result_ = execute();
                    if (result_ == null) {
                        return;
                    }
                    handler_.post(notifyFinished);
                }

                if (!carryOn) {
                    return;
                }

                // 切断
                fsr_.disconnectSession(backendType_);
                String completeStatus = event_CompleteDisconnect_.wait_();
                if (completeStatus.equals("shutdown")) {
                    while(carryOn) {
                        sleep(10);
                    }
                    return;
                }

                if (fsr_ != null) {
                    fsr_.destroy();
                    fsr_=null;
                }

            } catch (Exception e) {
                result_ = "(error)";
                e.printStackTrace();
            }
        }

        /**
         * 認識処理
         *
         * 現状は毎回インスタンス生成～destroy()を実施しているが、
         * 繰り返し認識させる場合は、以下のように制御した方がオーバーヘッドが少なくなる
         * アプリ起動時：インスタンス生成～connectSession()
         * 認識要求時　：startRecognition()～getSessionResult()
         * アプリ終了時：destroy()
         *
         * @throws Exception
         */
        public String execute() throws Exception {

            try{
                // 認識開始
                event_EndRecognition_.initialize();

                final StartRecognitionEntity startRecognitionEntity = new StartRecognitionEntity();
                startRecognitionEntity.setAutoStart(true);
                startRecognitionEntity.setAutoStop(false);				// falseにする場合はUIからstopRecognition()実行する仕組みが必要
                startRecognitionEntity.setVadOffTime((short) 500);
                startRecognitionEntity.setListenTime(0);
                startRecognitionEntity.setLevelSensibility(1);

                if (!carryOn) {
                    return null;
                }
                // 認識開始
                fsr_.startRecognition(backendType_, startRecognitionEntity);

                // 認識完了待ち
                // (setAutoStop(true)なので発話終了を検知して自動停止する)
                String endStatus = event_EndRecognition_.wait_();
                if (endStatus.equals("shutdown")) {
                    while(carryOn) {
                        sleep(10);
                    }
                    return null;
                }
                // 認識結果の取得
                if (!carryOn) {
                    return null;
                }

                RecognizeEntity recog = fsr_.getSessionResultStatus(backendType_);
                String result="(no result)";
                if( recog.getCount()>0 ){
                    // TODO 認識結果件数を指定出来る模様。100とか1000とかにしとけばいいかな。
                    ResultInfoEntity info=fsr_.getSessionResult(backendType_, 1);
                    result = info.getText();
                }

                return result;
            } catch (Exception e) {
                showErrorDialog(e);
                throw e;
//            }finally{
//                if( fsr_!=null ){
//                    fsr_.destroy();
//                    fsr_=null;
//                }
            }
        }

        public String stopRecognition() throws Exception{
            if (fsr_ == null) {
                return "fsr_がnull";
            }
            try {
                event_CompleteConnect_.shutdown_();
                event_EndRecognition_.shutdown_();
                event_CompleteDisconnect_.shutdown_();
                com.fuetrek.fsr.FSRServiceEnum.State s = fsr_.getStatus();
                if (s.equals(com.fuetrek.fsr.FSRServiceEnum.State.LISTEN)) {
                    fsr_.cancelRecognition();
                    fsr_.disconnectSession(backendType_);
                    return "cancel";
                } else if (s.equals(com.fuetrek.fsr.FSRServiceEnum.State.FEATURE)) {
                    fsr_.stopRecognition();
                    event_StopRecognition_.wait_();
                    // 認識結果の取得
                    RecognizeEntity recog = fsr_.getSessionResultStatus(backendType_);
                    String result="(no result)";
                    if( recog.getCount()>0 ){
                        // TODO 認識結果件数を指定出来る模様。100とか1000とかにしとけばいいかな。
                        ResultInfoEntity info=fsr_.getSessionResult(backendType_, 1);
                        result = info.getText();
                    }
                    // 切断
                    fsr_.disconnectSession(backendType_);
//                    event_CompleteDisconnect_.wait_();
                    return result;
                } else {
                    fsr_.disconnectSession(backendType_);
                    return "disconnect";
                }
            } catch (Exception e) {
                showErrorDialog(e);
                throw e;
            } finally {
                if (fsr_ != null) {
                    fsr_.destroy();
                    fsr_ = null;
                }
            }
        }

        // TODO FSRServiceEventListenerインターフェースにある
        // TODO 例では中断された場合にダイアログで表示している。中断した場合にどういう動きをするか実装する。
        @Override
        public void notifyAbort(Object arg0, AbortInfoEntity arg1) {
            Exception e = new Exception("Abort!!");
            showErrorDialog(e);
        }

        // TODO FSRServiceEventListenerインターフェースにある
        // TOOD 例では各種イベントが終わった事を通知している。
        @Override
        public void notifyEvent(final Object appHandle, final EventType eventType, final BackendType backendType, Object eventData) {

            switch(eventType){

            case CompleteConnect:
                // 接続完了
                ret_ = (Ret)eventData;
                event_CompleteConnect_.notify_();
                break;

            case CompleteDisconnect:
                // 切断完了
                event_CompleteDisconnect_.notify_();
                break;

            case NotifyEndRecognition:
                // 認識完了
                event_EndRecognition_.notify_();
                break;

            case CompleteStop:
                // 停止完了
                event_StopRecognition_.notify_();
                break;

            case NotifyLevel:
                // レベルメータ更新
                int level = (Integer)eventData;
                progressLevel_.setProgress(level);
                break;
            }
        }

    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        handler_ = new Handler();
        buttonStart_ = (Button) findViewById(R.id.button_start);
        progressLevel_ = (ProgressBar) findViewById(R.id.progress_level);
        textResult_ = (TextView) findViewById(R.id.text_result);
        activity_ = this;

        // コントロール初期化
        progressLevel_.setMax(100);
        textResult_.setTextSize(28.0f);
    }

    /**
     * 開始ボタン押下
     *
     * @param view ビュー
     */
    public void onClickStart(final View view) {
        textResult_.setText("");
        buttonStart_.setEnabled(false);
        controller_ = new fsrController();
        controller_.start();
    }


    public void onClickEnd(final View view) {
        try {
            controller_.result_ = controller_.stopRecognition();
        } catch (Exception e) {
            controller_.result_ = "(click end error)";
            e.printStackTrace();
        }
        handler_.post(controller_.notifyFinished);
        controller_.carryOn = false;
    }

    /**
     * エラーダイアログを表示する
     */
    public final void showErrorDialog(Exception e) {
        final Activity activity = this;
        final String text=(e.getCause()!=null)?e.getCause().toString():e.toString();
        final AlertDialog.Builder ad = new AlertDialog.Builder(activity);
        ad.setTitle("Error");
        ad.setMessage(text);
        ad.setPositiveButton("OK", new DialogInterface.OnClickListener() {
            public void onClick(final DialogInterface dialog, final int whichButton) {
                activity.setResult(Activity.RESULT_OK);
                activity.finish();
            }
        });
        ad.create();
        ad.show();
    }

    /**
     * トーストを表示する。
     */
    public final void showToast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

}
