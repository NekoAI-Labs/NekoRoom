/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

#include "LAppView.hpp"
#include <math.h>
#include <string>
#include "LAppPal.hpp"
#include "LAppDelegate.hpp"
#include "LAppLive2DManager.hpp"
#include "LAppTextureManager.hpp"
#include "LAppDefine.hpp"
#include "TouchManager.hpp"
#include "LAppSprite.hpp"
#include "LAppModel.hpp"

#include <Rendering/OpenGL/CubismRenderTarget_OpenGLES2.hpp>
#include <Rendering/OpenGL/CubismRenderer_OpenGLES2.hpp>

#include "JniBridgeC.hpp"
#include <android/log.h>

// v26: pending chatBg pixel buffer from JNI (defined in JniBridgeC.cpp)
extern Csm::csmUint32* s_pendingPixels;
extern int s_pendingW;
extern int s_pendingH;

using namespace std;
using namespace LAppDefine;

LAppView::LAppView():
    _programId(0),
    _back(NULL),
    _gear(NULL),
    _power(NULL),
    _changeModel(false),
    _renderSprite(NULL),
    _modelParameters(),
    _renderTarget(SelectTarget_None)
{
    _clearColor[0] = 1.0f;
    _clearColor[1] = 1.0f;
    _clearColor[2] = 1.0f;
    _clearColor[3] = 0.0f;

    // タッチ関係のイベント管理
    _touchManager = new TouchManager();

    // デバイス座標からスクリーン座標に変換するための
    _deviceToScreen = new CubismMatrix44();

    // 画面の表示の拡大縮小や移動の変換を行う行列
    _viewMatrix = new CubismViewMatrix();
}

LAppView::~LAppView()
{
    _renderBuffer.DestroyRenderTarget();
    delete _renderSprite;

    delete _viewMatrix;
    delete _deviceToScreen;
    delete _touchManager;
    delete _back;
    delete _gear;
    delete _power;
}

void LAppView::Initialize()
{
    int width = LAppDelegate::GetInstance()->GetWindowWidth();
    int height = LAppDelegate::GetInstance()->GetWindowHeight();

    // 縦サイズを基準とする
    float ratio = static_cast<float>(width) / static_cast<float>(height);
    float left = -ratio;
    float right = ratio;
    float bottom = ViewLogicalLeft;
    float top = ViewLogicalRight;

    _viewMatrix->SetScreenRect(left, right, bottom, top); // デバイスに対応する画面の範囲。 Xの左端, Xの右端, Yの下端, Yの上端
    _viewMatrix->Scale(ViewScale, ViewScale);

    _deviceToScreen->LoadIdentity();
    if (width > height)
    {
        float screenW = fabsf(right - left);
        _deviceToScreen->ScaleRelative(screenW / width, -screenW / width);
    }
    else
    {
        float screenH = fabsf(top - bottom);
        _deviceToScreen->ScaleRelative(screenH / height, -screenH / height);
    }
    _deviceToScreen->TranslateRelative(-width * 0.5f, -height * 0.5f);

    // 表示範囲の設定
    _viewMatrix->SetMaxScale(ViewMaxScale); // 限界拡大率
    _viewMatrix->SetMinScale(ViewMinScale); // 限界縮小率

    // 表示できる最大範囲
    _viewMatrix->SetMaxScreenRect(
        ViewLogicalMaxLeft,
        ViewLogicalMaxRight,
        ViewLogicalMaxBottom,
        ViewLogicalMaxTop
    );

    // v25 诊断：单行紧凑格式
    const csmFloat32* v = _viewMatrix->GetArray();
    __android_log_print(ANDROID_LOG_INFO, "MoeAvatar.Live2D",
        "DIAG type=viewmatrix-init winW=%d winH=%d ratio=%.3f m00=%.3f m11=%.3f m33=%.3f",
        width, height, ratio, v[0], v[5], v[15]);
}

void LAppView::InitializeShader()
{
    _programId = LAppDelegate::GetInstance()->CreateShader();
}

void LAppView::InitializeSprite()
{
    int width = LAppDelegate::GetInstance()->GetWindowWidth();
    int height = LAppDelegate::GetInstance()->GetWindowHeight();

    // NekoChat supplies the background and controls in the Android view tree.
    // The old Cubism sample sprites are optional and loading them here makes a
    // missing legacy PNG fatal through JNI (LoadFile returns null).  Do not
    // touch those assets in the chat renderer.
    delete _back;
    _back = NULL;
    delete _gear;
    _gear = NULL;
    delete _power;
    _power = NULL;

    float x = width * 0.5f;
    float y = height * 0.5f;
    float fWidth = width;
    float fHeight = height;

    // 画面全体を覆うサイズ
    x = width * 0.5f;
    y = height * 0.5f;

    if (_renderSprite == NULL)
    {
        _renderSprite = new LAppSprite(x, y, width, height, 0, _programId);
    }
    else
    {
        _renderSprite->ReSize(x, y, width, height);
    }
}

void LAppView::Render()
{
    // v26: 如果 JNI 有 pending 的 chatBg 像素，建纹理 + sprite
    if (s_pendingPixels != NULL && s_pendingW > 0 && s_pendingH > 0) {
        if (_bgTextureId != 0) {
            glDeleteTextures(1, &_bgTextureId);
            _bgTextureId = 0;
        }
        if (_bgSprite != NULL) {
            delete _bgSprite;
            _bgSprite = NULL;
        }
        glGenTextures(1, &_bgTextureId);
        glBindTexture(GL_TEXTURE_2D, _bgTextureId);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        // pixel data 已经是 [R,G,B,A] 字节序（native 端 swap 过）
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, s_pendingW, s_pendingH, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, s_pendingPixels);
        glBindTexture(GL_TEXTURE_2D, 0);
        int winW = LAppDelegate::GetInstance()->GetWindowWidth();
        int winH = LAppDelegate::GetInstance()->GetWindowHeight();
        _bgSprite = new LAppSprite(winW * 0.5f, winH * 0.5f,
                                  static_cast<float>(winW), static_cast<float>(winH),
                                  _bgTextureId, _programId);
        __android_log_print(ANDROID_LOG_INFO, "MoeAvatar.Live2D",
            "v26 bg tex created %dx%d -> GL %u, sprite at (%.0f,%.0f) size (%.0f,%.0f)",
            s_pendingW, s_pendingH, _bgTextureId,
            winW * 0.5f, winH * 0.5f, static_cast<float>(winW), static_cast<float>(winH));
        delete[] s_pendingPixels;
        s_pendingPixels = NULL;
        s_pendingW = 0;
        s_pendingH = 0;
    }

    // v26: 先画 chatBg 全屏 quad（绕过 GPU 不透明 surface，chatBg 整屏填底）
    if (_bgSprite != NULL) {
        _bgSprite->Render();
    } else if (_needRenderBack && _back != NULL) {
        // 没拿到 chatBg 时退回 SDK 自带的 back_classroom.png
        _back->Render();
    }
//    _gear->Render();
//    _power->Render();

    if(_changeModel)
    {
        _changeModel = false;
        LAppLive2DManager::GetInstance()->ChangeModelTo(_nextModelPath, _nextModelJsonFileName);
//        LAppLive2DManager::GetInstance()->NextScene();
    }

    LAppLive2DManager* Live2DManager = LAppLive2DManager::GetInstance();

    //Live2DManager->SetViewMatrix(_viewMatrix);

    // v26: 模型渲染时裁掉下边（scissor），让脚自然消失。
    // chatBg 在上面已经画完，scissor 只影响下面这一段模型绘制。
    if (_scissorBottom > 0.0f) {
        int winH = LAppDelegate::GetInstance()->GetWindowHeight();
        int clipY = static_cast<int>(winH * _scissorBottom);
        glEnable(GL_SCISSOR_TEST);
        glScissor(0, clipY,
                  LAppDelegate::GetInstance()->GetWindowWidth(),
                  winH - clipY);
    } else {
        glDisable(GL_SCISSOR_TEST);
    }

    // Cubism更新・描画
    Live2DManager->OnUpdate(_modelParameters);
    _modelParameters.changeExpression = false;
    _modelParameters.changeOutfit = false;
    _modelParameters.clearOutfit = false;

    // 关闭 scissor（Render 结尾清理，避免影响后续 on-screen 渲染）
    if (_scissorBottom > 0.0f) {
        glDisable(GL_SCISSOR_TEST);
    }

    // 各モデルが持つ描画ターゲットをテクスチャとする場合
    if (_renderTarget == SelectTarget_ModelFrameBuffer && _renderSprite)
    {
        const GLfloat uvVertex[] =
        {
            1.0f, 1.0f,
            0.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f,
        };

        for (csmUint32 i = 0; i < Live2DManager->GetModelNum(); i++)
        {
            float alpha = GetSpriteAlpha(i); // サンプルとしてαに適当な差をつける
            _renderSprite->SetColor(1.0f, 1.0f, 1.0f, alpha);

            LAppModel *model = Live2DManager->GetModel(i);
            if (model)
            {
                _renderSprite->RenderImmidiate(model->GetRenderBuffer().GetColorBuffer(), uvVertex);
            }
        }
    }
}

void LAppView::OnTouchesBegan(float pointX, float pointY) const
{
    _touchManager->TouchesBegan(pointX, pointY);
}

void LAppView::OnTouchesMoved(float pointX, float pointY) const
{
    float viewX = this->TransformViewX(_touchManager->GetX());
    float viewY = this->TransformViewY(_touchManager->GetY());

    _touchManager->TouchesMoved(pointX, pointY);

    LAppLive2DManager::GetInstance()->OnDrag(viewX, viewY);
}

void LAppView::OnTouchesEnded(float pointX, float pointY)
{
    // タッチ終了
    LAppLive2DManager* live2DManager = LAppLive2DManager::GetInstance();
    live2DManager->OnDrag(0.0f, 0.0f);
    {

        // シングルタップ
        float x = _deviceToScreen->TransformX(_touchManager->GetX()); // 論理座標変換した座標を取得。
        float y = _deviceToScreen->TransformY(_touchManager->GetY()); // 論理座標変換した座標を取得。
        if (DebugTouchLogEnable)
        {
            LAppPal::PrintLog("[APP]touchesEnded x:%.2f y:%.2f", x, y);
        }
        live2DManager->OnTap(x, y);

        // Android chat UI owns settings and lifecycle controls. The sample gear/power
        // sprites may be absent during Surface recreation, so never hit-test them here.
    }
}

float LAppView::TransformViewX(float deviceX) const
{
    float screenX = _deviceToScreen->TransformX(deviceX); // 論理座標変換した座標を取得。
    return _viewMatrix->InvertTransformX(screenX); // 拡大、縮小、移動後の値。
}

float LAppView::TransformViewY(float deviceY) const
{
    float screenY = _deviceToScreen->TransformY(deviceY); // 論理座標変換した座標を取得。
    return _viewMatrix->InvertTransformY(screenY); // 拡大、縮小、移動後の値。
}

float LAppView::TransformScreenX(float deviceX) const
{
    return _deviceToScreen->TransformX(deviceX);
}

float LAppView::TransformScreenY(float deviceY) const
{
    return _deviceToScreen->TransformY(deviceY);
}

void LAppView::PreModelDraw(LAppModel &refModel)
{
    // 別のレンダリングターゲットへ向けて描画する場合の使用するフレームバッファ
    Csm::Rendering::CubismRenderTarget_OpenGLES2* useTarget = NULL;

    if (_renderTarget != SelectTarget_None)
    {// 別のレンダリングターゲットへ向けて描画する場合

        // 使用するターゲット
        useTarget = (_renderTarget == SelectTarget_ViewFrameBuffer) ? &_renderBuffer : &refModel.GetRenderBuffer();

        if (!useTarget->IsValid())
        {// 描画ターゲット内部未作成の場合はここで作成
            int width = LAppDelegate::GetInstance()->GetWindowWidth();
            int height = LAppDelegate::GetInstance()->GetWindowHeight();

            // モデル描画キャンバス
            useTarget->CreateRenderTarget(static_cast<csmUint32>(width), static_cast<csmUint32>(height));
        }

        // レンダリング開始
        useTarget->BeginDraw();
        useTarget->Clear(_clearColor[0], _clearColor[1], _clearColor[2], _clearColor[3]); // 背景クリアカラー
    }
}

void LAppView::PostModelDraw(LAppModel &refModel)
{
    // 別のレンダリングターゲットへ向けて描画する場合の使用するフレームバッファ
    Csm::Rendering::CubismRenderTarget_OpenGLES2* useTarget = NULL;

    if (_renderTarget != SelectTarget_None)
    {// 別のレンダリングターゲットへ向けて描画する場合

        // 使用するターゲット
        useTarget = (_renderTarget == SelectTarget_ViewFrameBuffer) ? &_renderBuffer : &refModel.GetRenderBuffer();

        // レンダリング終了
        useTarget->EndDraw();

        // LAppViewの持つフレームバッファを使うなら、スプライトへの描画はここ
        if (_renderTarget == SelectTarget_ViewFrameBuffer && _renderSprite)
        {
            const GLfloat uvVertex[] =
            {
                1.0f, 1.0f,
                0.0f, 1.0f,
                0.0f, 0.0f,
                1.0f, 0.0f,
            };

            _renderSprite->SetColor(1.0f, 1.0f, 1.0f, GetSpriteAlpha(0));
            _renderSprite->RenderImmidiate(useTarget->GetColorBuffer(), uvVertex);
        }
    }
}

void LAppView::SwitchRenderingTarget(SelectTarget targetType)
{
    _renderTarget = targetType;
}

void LAppView::SetRenderTargetClearColor(float r, float g, float b)
{
    _clearColor[0] = r;
    _clearColor[1] = g;
    _clearColor[2] = b;
}

float LAppView::GetSpriteAlpha(int assign) const
{
    // assignの数値に応じて適当に決定
    float alpha = 0.25f + static_cast<float>(assign) * 0.5f; // サンプルとしてαに適当な差をつける
    if (alpha > 1.0f)
    {
        alpha = 1.0f;
    }
    if (alpha < 0.1f)
    {
        alpha = 0.1f;
    }

    return alpha;
}

void LAppView::ChangeModelTo(std::string modelPath, std::string modelJsonFileName)
{
    _nextModelPath = modelPath;
    _nextModelJsonFileName = modelJsonFileName;
    _changeModel = true;
}

void LAppView::ApplyExpression(const char* expressionName) {
    _modelParameters.changeExpression = true;
    _modelParameters.nextExpressionName = expressionName;
}

void LAppView::ApplyOutfit(const char* outfitName) {
    _modelParameters.changeOutfit = true;
    _modelParameters.clearOutfit = false;
    _modelParameters.nextOutfitName = outfitName;
}

void LAppView::ClearOutfit() {
    _modelParameters.changeOutfit = false;
    _modelParameters.clearOutfit = true;
}

void LAppView::NeedRenderBack(bool needRender) {
    _needRenderBack = needRender;
    __android_log_print(ANDROID_LOG_INFO, "MoeAvatar.Live2D", "native background render=%s",
                        needRender ? "true" : "false");
}

void LAppView::Resize(float scale)
{
    _modelParameters.modelScale = scale;
}

void LAppView::TranslateX(float x)
{
    _modelParameters.modelTranslateX = x;
}

void LAppView::TranslateY(float y)
{
    _modelParameters.modelTranslateY = y;
}

void LAppView::AutoBlinkEyes(bool enabled)
{
    _modelParameters.autoBlinkEyesEnabled = enabled;
}

void LAppView::ModelMouthForm(float value)
{
    _modelParameters.mouthForm = value;
}

void LAppView::ModelMouthOpenY(float value)
{
    _modelParameters.mouthOpenY = value;
}

void LAppView::SetBackgroundPixels(const Csm::csmUint32* pixels, int width, int height)
{
    // 实际逻辑在 Render() 里跑（要 GL 上下文）。
    // 这里只记录一下 JNI 路径已经下发的参数；s_pendingPixels/s_pendingW/s_pendingH
    // 在 JniBridgeC.cpp 里维护（同一进程同一文件，能直接 extern）。
    (void)pixels; (void)width; (void)height;
    __android_log_print(ANDROID_LOG_INFO, "MoeAvatar.Live2D",
        "v26 SetBackgroundPixels called (deferred to next Render)");
}

void LAppView::SetScissorBottom(float ratio)
{
    if (ratio < 0.0f) ratio = 0.0f;
    if (ratio > 0.95f) ratio = 0.95f;
    _scissorBottom = ratio;
    __android_log_print(ANDROID_LOG_INFO, "MoeAvatar.Live2D",
        "v26 SetScissorBottom=%.3f", ratio);
}
