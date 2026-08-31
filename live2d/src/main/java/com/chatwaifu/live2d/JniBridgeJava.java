/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

package com.chatwaifu.live2d;

import android.app.Activity;
import android.content.Context;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

public class JniBridgeJava {

    private static final String LIBRARY_NAME = "chatwaifu-live2d";
    private static Activity _activityInstance;
    private static Context _context;
    private static Live2DLoadInterface _loadCallback;


    static {
        System.loadLibrary(LIBRARY_NAME);
    }

    // Native -----------------------------------------------------------------

    public static native void nativeOnStart();

    public static native void nativeOnPause();

    public static native void nativeOnStop();

    public static native void nativeOnDestroy();

    public static native void nativeOnSurfaceCreated();

    public static native void nativeOnSurfaceChanged(int width, int height);

    public static native void nativeOnDrawFrame();

    public static native void nativeOnTouchesBegan(float pointX, float pointY);

    public static native void nativeOnTouchesEnded(float pointX, float pointY);

    public static native void nativeOnTouchesMoved(float pointX, float pointY);

    public static native void nativeProjectChangeTo(String modelPath, String modelJsonFileName);

    public static native void nativeApplyExpression(String expressionName);

    /** v0.5 M2: outfit 通道，走独立 ExpressionManager，参数集不与 emotion 冲突。 */
    public static native void nativeApplyOutfit(String outfitName);

    /** Stop the persistent outfit channel and return to the model's base outfit. */
    public static native void nativeClearOutfit();

    public static native void needRenderBack(boolean back);

    public static native void nativeProjectScale(float scale);

    public static native void nativeProjectTransformX(float transform);

    public static native void nativeProjectTransformY(float transform);

    public static native void nativeAutoBlinkEyes(boolean enabled);

    public static native void nativeProjectMouthForm(float value);

    public static native void nativeProjectMouthOpenY(float value);

    /**
     * v26: 把 chatBg drawable 转成的 ARGB int[] 喂进 native，Render() 把它画在
     * Live2D 模型下面的全屏 quad 上。绕过 GLSurfaceView 透明 surface 不可用
     * 的问题（Redmi K70 / HyperOS GPU 强制不透明）。
     */
    public static native void nativeSetBackground(int[] argb, int width, int height);

    /**
     * v26: 把模型渲染的 scissor 下边切掉 ratio*height 像素（0.0 = 不切，0.35 = 切掉下 35%）。
     * 配合 chatBg 整屏填底，做到"头肩 + 上半身"裁切，砍掉脚。
     */
    public static native void nativeSetScissorBottom(float ratio);

    // Java -----------------------------------------------------------------

    public static void SetContext(Context context) {
        _context = context;
    }

    public static void SetActivityInstance(Activity activity) {
        _activityInstance = activity;
    }

    public static void setLive2DLoadInterface(Live2DLoadInterface loadInterface) {
        _loadCallback = loadInterface;
    }

    public static byte[] LoadFile(String filePath) {
        InputStream fileData = null;
        try {
            if (filePath.startsWith("/")) {
                fileData = new FileInputStream(filePath);
            } else {
                fileData = _context.getAssets().open(filePath);
            }
            int fileSize = fileData.available();
            byte[] fileBuffer = new byte[fileSize];
            fileData.read(fileBuffer, 0, fileSize);
            return fileBuffer;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        } finally {
            try {
                if (fileData != null) {
                    fileData.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static void MoveTaskToBack() {
        // 聊天 App 不做 Live2D sample 的"电源键→最小化"行为，且 _activityInstance 从未注入
        // （注入了也不想 tap 头像就把 App 切后台）——空实现兜底，避免 NPE 闪退。
        if (_activityInstance != null) _activityInstance.moveTaskToBack(true);
    }

    public static void OnLoadError() {
        if (_loadCallback != null) {
            _loadCallback.onLoadError();
        }
    }

    public static void OnLoadDone() {
        if (_loadCallback != null) {
            _loadCallback.onLoadDone();
        }
    }

    public static void OnLoadOneMotion(String motionGroup, int index, String motionName) {
        if (_loadCallback != null) {
            _loadCallback.onLoadOneMotion(motionGroup, index, motionName);
        }
    }

    public static void OnLoadOneExpression(String expressionName, int index) {
        if (_loadCallback != null) {
            _loadCallback.onLoadOneExpression(expressionName, index);
        }
    }

    public interface Live2DLoadInterface {
        void onLoadError();

        void onLoadDone();

        void onLoadOneMotion(String motionGroup, int index, String motionName);

        void onLoadOneExpression(String expressionName, int index);
    }
}
