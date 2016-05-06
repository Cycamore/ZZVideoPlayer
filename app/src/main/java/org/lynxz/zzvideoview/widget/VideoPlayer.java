package org.lynxz.zzvideoview.widget;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.Context;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.RelativeLayout;

import org.lynxz.zzvideoview.R;
import org.lynxz.zzvideoview.ZZPlayerDemoActivity;
import org.lynxz.zzvideoview.constant.PlayState;
import org.lynxz.zzvideoview.constant.SeekBarState;
import org.lynxz.zzvideoview.constant.VideoUriProtocol;
import org.lynxz.zzvideoview.controller.AnimationImpl;
import org.lynxz.zzvideoview.controller.IControllerImpl;
import org.lynxz.zzvideoview.controller.IPlayerImpl;
import org.lynxz.zzvideoview.controller.ITitleBarImpl;
import org.lynxz.zzvideoview.util.DebugLog;
import org.lynxz.zzvideoview.util.DensityUtil;
import org.lynxz.zzvideoview.util.NetworkUtil;
import org.lynxz.zzvideoview.util.OrientationUtil;

import java.lang.ref.WeakReference;
import java.util.Calendar;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Created by zxz on 2016/4/28.
 */
public class VideoPlayer extends RelativeLayout implements View.OnTouchListener {

    private Context mContent;
    private PlayerTitleBar mTitleBar;
    private ZZVideoView mVv;
    private PlayerController mController;
    private static final String TAG = "zzVideoPlayer";
    private boolean barsIfShow = true;//标题栏控制栏是否显示

    private Uri mVideoUri;
    private String mVideoProtocol;//视频地址所用协议

    private Animation mEnterFromTop;
    private Animation mEnterFromBottom;
    private Animation mExitFromTop;
    private Animation mExitFromBottom;


    private int mDuration = 0;//视频长度
    private long mCurrentDownTime = 0;
    private long mLastDownTime = 0;
    private int mCurrentPlayState = PlayState.IDLE;
    private int mNetworkState = -1;//0-无网络

    private static final int MIN_CLICK_INTERVAL = 400;//连续两次down事件最小时间间隔(ms)
    private static final int UPDATE_TIMER_INTERVAL = 1000;
    private static final int TIME_AUTO_HIDE_BARS_DELAY = 2000;

    private static final int MSG_UPDATE_PROGRESS_TIME = 1;//更新播放进度时间
    private static final int MSG_AUTO_HIDE_BARS = 2;//隐藏标题栏和控制条

    private Timer mUpdateTimer = null;

    private ITitleBarImpl mTitleBarImpl = new ITitleBarImpl() {
        @Override
        public void onBackClick() {
            if (mIPlayerImpl != null) {
                mIPlayerImpl.onBack();
            } else {
                mHostActivity.get().finish();
            }
        }
    };

    private IControllerImpl mControllerImpl = new IControllerImpl() {
        @Override
        public void onPlayTurn() {
            //网络不正常时,不允许切换 TODO: 本地视频则跳过这一步
            if (!NetworkUtil.isNetworkAvailable(mHostActivity.get())) {
                mIPlayerImpl.onNetWorkError();
                return;
            }

            switch (mCurrentPlayState) {
                case PlayState.PLAY:
                    mController.setPlayState(PlayState.PAUSE);
                    mCurrentPlayState = PlayState.PAUSE;
                    pausePlay();
                    break;
                case PlayState.IDLE:
                case PlayState.PAUSE:
                case PlayState.COMPLETE:
                case PlayState.STOP:
                    mController.setPlayState(PlayState.PLAY);
                    mCurrentPlayState = PlayState.PLAY;
                    startPlay();
                    break;
                case PlayState.ERROR:
                    break;
            }

            sendAutoHideBarsMsg();
        }

        @Override
        public void onProgressChange(int state, int progress) {
            switch (state) {
                case SeekBarState.START_TRACKING:
                    mHandler.removeMessages(MSG_AUTO_HIDE_BARS);
                    break;
                case SeekBarState.STOP_TRACKING:
                    mVv.seekTo(progress);
                    sendAutoHideBarsMsg();
                    break;
            }
        }

        @Override
        public void onOrientationChange() {
            //            mIPlayerImpl.changeOrientation();
            OrientationUtil.changeOrientation(mHostActivity.get());
        }
    };

    private Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            int what = msg.what;
            if (what == MSG_UPDATE_PROGRESS_TIME) {
                mController.updateProgress(getCurrentTime(), getBufferProgress());
            } else if (what == MSG_AUTO_HIDE_BARS) {
                animateShowOrHideBars(false);
            }
        }
    };

    private MediaPlayer.OnPreparedListener mPreparedListener = new MediaPlayer.OnPreparedListener() {
        @Override
        public void onPrepared(MediaPlayer mp) {
            mDuration = mp.getDuration();
            mController.updateProgress(0, 0, mDuration);
            sendAutoHideBarsMsg();
        }
    };

    private MediaPlayer.OnErrorListener mErrorListener = new MediaPlayer.OnErrorListener() {
        @Override
        public boolean onError(MediaPlayer mp, int what, int extra) {
            return true;
        }
    };
    private MediaPlayer.OnCompletionListener mCompletionListener = new MediaPlayer.OnCompletionListener() {
        @Override
        public void onCompletion(MediaPlayer mp) {
            mController.setPlayState(PlayState.STOP);
            mController.updateProgress(0, 0);
            mCurrentPlayState = PlayState.COMPLETE;
            stopUpdateTimer();
        }
    };
    private MediaPlayer.OnInfoListener mInfoListener = new MediaPlayer.OnInfoListener() {
        @Override
        public boolean onInfo(MediaPlayer mp, int what, int extra) {
            sendAutoHideBarsMsg();
            return false;
        }
    };
    private WeakReference<Activity> mHostActivity;

    /**
     * 播放器控制功能对外开放接口,包括返回按钮,播放等...
     */
    public void setPlayerController(IPlayerImpl IPlayerImpl) {
        mIPlayerImpl = IPlayerImpl;
    }

    private IPlayerImpl mIPlayerImpl = null;

    public VideoPlayer(Context context) {
        super(context);
        initView(context);
    }

    public VideoPlayer(Context context, AttributeSet attrs) {
        super(context, attrs);
        initView(context);
    }

    public VideoPlayer(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        initView(context);
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public VideoPlayer(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        initView(context);
    }

    private void initView(Context context) {
        mContent = context;
        inflate(context, R.layout.zz_video_player, this);
        View rlPlayer = findViewById(R.id.rl_player);
        mVv = (ZZVideoView) findViewById(R.id.zzvv_main);
        mTitleBar = (PlayerTitleBar) findViewById(R.id.pt_title_bar);
        mController = (PlayerController) findViewById(R.id.pc_controller);

        initAnimation();

        mTitleBar.setTitleBarImpl(mTitleBarImpl);
        mController.setControllerImpl(mControllerImpl);

        mVv.setOnTouchListener(this);
        rlPlayer.setOnTouchListener(this);
        mVv.setOnPreparedListener(mPreparedListener);
        //        mVv.setOnInfoListener(mInfoListener);
        mVv.setOnCompletionListener(mCompletionListener);
        mVv.setOnErrorListener(mErrorListener);
    }

    /**
     * 初始化标题栏/控制栏显隐动画效果
     */
    private void initAnimation() {
        mEnterFromTop = AnimationUtils.loadAnimation(mContent, R.anim.enter_from_top);
        mEnterFromBottom = AnimationUtils.loadAnimation(mContent, R.anim.enter_from_bottom);
        mExitFromTop = AnimationUtils.loadAnimation(mContent, R.anim.exit_from_top);
        mExitFromBottom = AnimationUtils.loadAnimation(mContent, R.anim.exit_from_bottom);

        mEnterFromTop.setAnimationListener(new AnimationImpl() {
            @Override
            public void onAnimationEnd(Animation animation) {
                super.onAnimationEnd(animation);
                mTitleBar.setVisibility(VISIBLE);
            }
        });
        mEnterFromBottom.setAnimationListener(new AnimationImpl() {
            @Override
            public void onAnimationEnd(Animation animation) {
                super.onAnimationEnd(animation);
                mController.setVisibility(VISIBLE);
            }
        });
        mExitFromTop.setAnimationListener(new AnimationImpl() {
            @Override
            public void onAnimationEnd(Animation animation) {
                super.onAnimationEnd(animation);
                mTitleBar.setVisibility(GONE);
            }
        });
        mExitFromBottom.setAnimationListener(new AnimationImpl() {
            @Override
            public void onAnimationEnd(Animation animation) {
                super.onAnimationEnd(animation);
                mController.setVisibility(GONE);
            }
        });
    }

    /**
     * 设置视频标题
     */

    public void setTitle(String title) {
        mTitleBar.setTitle(title);
    }

    private void load() {
        if (VideoUriProtocol.PROTOCOL_HTTP.equalsIgnoreCase(mVideoProtocol)) {
            mVv.setVideoPath(mVideoUri.toString());
        } else if (VideoUriProtocol.PROTOCOL_ANDROID_RESOURCE.equalsIgnoreCase(mVideoProtocol)) {
            mVv.setVideoURI(mVideoUri);
        }
    }

    public void startPlay() {
        mVv.start();
        mController.setPlayState(PlayState.PLAY);
        resetUpdateTimer();
    }

    public void pausePlay() {
        mVv.pause();
        //        stopUpdateTimer();
    }

    public void stopPlay() {
        mVv.stopPlayback();
    }

    /**
     * 设置视频播放路径
     * 1. 设置当前项目中res/raw目录中的文件: "android.resource://" + getPackageName() + "/" + R.raw.yourName
     * 2. 设置网络视频文件: "http:\//****\/abc.mp4"
     *
     * @param path
     * @return 设置成功返回 true
     */
    public void setVideoUri(@NonNull Activity act, @NonNull String path) {
        mHostActivity = new WeakReference<Activity>(act);
        mVideoUri = Uri.parse(path);
        mVideoProtocol = mVideoUri.getScheme();
        DebugLog.i(TAG, "setVideoUri path = " + path + " mVideoProtocol = " + mVideoProtocol);
    }

    public void loadAndStartVideo(@NonNull Activity act, @NonNull String path) {
        setVideoUri(act, path);
        load();
        startPlay();
    }


    @Override
    public boolean onTouch(View v, MotionEvent event) {

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                mCurrentDownTime = Calendar.getInstance().getTimeInMillis();
                if (isTouchEventValid()) {
                    mHandler.removeMessages(MSG_AUTO_HIDE_BARS);
                    if (mController.getVisibility() == VISIBLE) {
                        showOrHideBars(false, true);
                    } else {
                        showOrHideBars(true, true);
                    }
                    mLastDownTime = mCurrentDownTime;
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
                sendAutoHideBarsMsg();
                break;
        }
        return false;
    }


    /**
     * 显隐标题栏和控制条
     *
     * @param show          是否显示
     * @param animateEffect 是否需要动画效果
     */
    private void showOrHideBars(boolean show, boolean animateEffect) {
        if (animateEffect) {
            animateShowOrHideBars(show);
        } else {
            forceShowOrHideBars(show);
        }
    }

    /**
     * 直接显隐标题栏和控制栏
     */
    private void forceShowOrHideBars(boolean show) {
        mTitleBar.clearAnimation();
        mController.clearAnimation();

        if (show) {
            mController.setVisibility(VISIBLE);
            mTitleBar.setVisibility(VISIBLE);
        } else {
            mController.setVisibility(GONE);
            mTitleBar.setVisibility(GONE);
        }
    }

    /**
     * 带动画效果的显隐标题栏和控制栏
     */
    private void animateShowOrHideBars(boolean show) {
        mTitleBar.clearAnimation();
        mController.clearAnimation();

        if (show) {
            if (mTitleBar.getVisibility() != VISIBLE) {
                mTitleBar.startAnimation(mEnterFromTop);
                mController.startAnimation(mEnterFromBottom);
            }
        } else {
            if (mTitleBar.getVisibility() != GONE) {
                mTitleBar.startAnimation(mExitFromTop);
                mController.startAnimation(mExitFromBottom);
            }
        }
    }

    /**
     * 判断连续两次触摸事件间隔是否符合要求,避免快速点击等问题
     *
     * @return
     */
    private boolean isTouchEventValid() {
        if (mCurrentDownTime - mLastDownTime >= MIN_CLICK_INTERVAL) {
            return true;
        }
        return false;
    }

    private void resetUpdateTimer() {
        stopUpdateTimer();
        mUpdateTimer = new Timer();
        mUpdateTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                mHandler.sendEmptyMessage(MSG_UPDATE_PROGRESS_TIME);
            }
        }, 0, UPDATE_TIMER_INTERVAL);
    }

    private void stopUpdateTimer() {
        if (mUpdateTimer != null) {
            mUpdateTimer.cancel();
            mUpdateTimer = null;
        }
    }

    private int getCurrentTime() {
        return mVv.getCurrentPosition();
    }

    /**
     * @return 缓冲百分比 0-100
     */
    private int getBufferProgress() {
        return mVv.getBufferPercentage();
    }

    /**
     * 发送message给handler,自动隐藏标题栏
     */
    private void sendAutoHideBarsMsg() {
        //  初始自动隐藏标题栏和控制栏
        mHandler.removeMessages(MSG_AUTO_HIDE_BARS);
        mHandler.sendEmptyMessageDelayed(MSG_AUTO_HIDE_BARS, TIME_AUTO_HIDE_BARS_DELAY);
    }

    /**
     * 屏幕方向改变时,回调该方法
     *
     * @param orientation 新屏幕方向:<br>
     *                    <ol>
     *                    <li>{@link OrientationUtil#HORIZONTAL HORIZONTAL}</li>
     *                    <li>{@link OrientationUtil#VERTICAL VERTICAL}</li>
     *                    </ol>
     */
    public void updateActivityOrientation() {
        int orientation = OrientationUtil.getOrientation(mHostActivity.get());

        //更新播放器宽高
        float width = DensityUtil.getWidthInPx(mHostActivity.get());
        float height = DensityUtil.getHeightInPx(mHostActivity.get());
        if (orientation == OrientationUtil.HORIZONTAL) {
            getLayoutParams().height = (int) height;
            getLayoutParams().width = (int) width;
        } else {
            width = DensityUtil.getWidthInPx(mHostActivity.get());
            height = DensityUtil.dip2px(mHostActivity.get(), 200f);
        }
        getLayoutParams().height = (int) height;
        getLayoutParams().width = (int) width;

        //需要强制显示再隐藏控制条,不然若切换为横屏时控制条是隐藏的,首次触摸显示时,会显示在200dp的位置
        forceShowOrHideBars(true);
        sendAutoHideBarsMsg();

        //更新全屏图标
        mController.setOrientation(orientation);
    }
}
