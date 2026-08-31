/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

#include "LAppDelegate.hpp"
#include <iostream>
#include <GLES2/gl2.h>
#include <android/log.h>
#include "LAppView.hpp"
#include "LAppPal.hpp"
#include "LAppDefine.hpp"
#include "LAppLive2DManager.hpp"
#include "LAppTextureManager.hpp"
#include "JniBridgeC.hpp"

using namespace Csm;
using namespace std;
using namespace LAppDefine;

namespace {
    LAppDelegate* s_instance = NULL;
}

LAppDelegate* LAppDelegate::GetInstance()
{
    if (s_instance == NULL)
    {
        s_instance = new LAppDelegate();
    }

    return s_instance;
}

void LAppDelegate::ReleaseInstance()
{
    if (s_instance != NULL)
    {
        delete s_instance;
    }

    s_instance = NULL;
}


void LAppDelegate::OnStart()
{
    _textureManager = new LAppTextureManager();
    _view = new LAppView();
    LAppPal::UpdateTime();
}

void LAppDelegate::OnPause()
{
    _SceneIndex = LAppLive2DManager::GetInstance()->GetSceneIndex();
}

// 之前 OnStop 会 delete _view/_textureManager + CubismFramework::Dispose，
// 但 Java 端 Live2DController.onResume() 只调 glView.onResume()，不会重新
// nativeOnStart() 重建 _view。结果权限弹窗 / 切屏 → onPause/onStop → onResume
// 这条路上 _view 长期为 NULL，GLSurfaceView 重建 surface 时 onSurfaceChanged
// 调 _view->Initialize() → null deref → SIGSEGV fault addr 0x10 (LAppView::Initialize)。
// 现在 OnStop 只保存场景索引，真正释放挪到 OnDestroy（Activity 真销毁时）。
void LAppDelegate::OnStop()
{
    _SceneIndex = LAppLive2DManager::GetInstance()->GetSceneIndex();
}

void LAppDelegate::OnDestroy()
{
    if (_view)
    {
        delete _view;
        _view = NULL;
    }
    if (_textureManager)
    {
        delete _textureManager;
        _textureManager = NULL;
    }

    LAppLive2DManager::ReleaseInstance();
    CubismFramework::Dispose();

    ReleaseInstance();
}

void LAppDelegate::Run()
{
    // 時間更新
    LAppPal::UpdateTime();

    // v27 改 v2-月夜猫娘: GLSurfaceView 改成透明，让底下的 ImageView(room_default.png
    // + 45% 蒙版)透出来。alpha=0 → framebuffer 透明 → 角色画在原位，背景由 Android 层提供。
    // 老 0.086 哑黑方案是 HyperOS 强制不透明时的退化路径（已不需要）。
    glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
    glClearDepthf(1.0f);

    //描画更新
    if (_view != NULL)
    {
        _view->Render();
    }

    if(_isActive == false)
    {
        JniBridgeC::MoveTaskToBack();
    }
}

void LAppDelegate::OnSurfaceCreate()
{
    //テクスチャサンプリング設定
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);

    //透過設定
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);

    //Initialize cubism
    CubismFramework::Initialize();

    // _view 可能在异常生命周期下为 NULL（OnStart 未跑 / Activity recreate 竞争），
    // 直接 InitializeShader 会 null deref。防御：no-op，等 OnStart 重建后再来。
    if (_view != NULL)
    {
        _view->InitializeShader();
    }
}

void LAppDelegate::OnSurfaceChanged(float width, float height)
{
    glViewport(0, 0, width, height);
    _width = width;
    _height = height;
    // v25 诊断：单行紧凑格式
    __android_log_print(ANDROID_LOG_INFO, "MoeAvatar.Live2D",
        "DIAG type=surface-changed glViewport=%.0fx%.0f", width, height);

    // _view NULL 时 no-op（同 OnSurfaceCreate 的防御理由）。
    if (_view != NULL)
    {
        _view->Initialize();
        _view->InitializeSprite();
    }

    //load model
//    if (LAppLive2DManager::GetInstance()->GetSceneIndex() != _SceneIndex)
//    {
//        LAppLive2DManager::GetInstance()->ChangeScene(_SceneIndex);
//    }

    _isActive = true;
}

LAppDelegate::LAppDelegate():
    _cubismOption(),
    _captured(false),
    _SceneIndex(0),
    _mouseX(0.0f),
    _mouseY(0.0f),
    _isActive(true),
    _textureManager(NULL),
    _view(NULL)
{
    // Setup Cubism
    _cubismOption.LogFunction = LAppPal::PrintMessage;
    _cubismOption.LoggingLevel = LAppDefine::CubismLoggingLevel;
    // SDK5: OpenGL 渲染器改为从文件加载着色器（LoadShaderProgramFromFile），
    // 必须注册文件读取/释放回调，否则每帧报 "File loader is not set." → 模型不渲染。
    _cubismOption.LoadFileFunction = LAppPal::LoadFileAsBytes;
    _cubismOption.ReleaseBytesFunction = LAppPal::ReleaseBytes;
    CubismFramework::CleanUp();
    CubismFramework::StartUp(&_cubismAllocator, &_cubismOption);
}

LAppDelegate::~LAppDelegate()
{
}

void LAppDelegate::OnTouchBegan(double x, double y)
{
    _mouseX = static_cast<float>(x);
    _mouseY = static_cast<float>(y);

    if (_view != NULL)
    {
        _captured = true;
        _view->OnTouchesBegan(_mouseX, _mouseY);
    }
}

void LAppDelegate::OnTouchEnded(double x, double y)
{
    _mouseX = static_cast<float>(x);
    _mouseY = static_cast<float>(y);

    if (_view != NULL)
    {
        _captured = false;
        _view->OnTouchesEnded(_mouseX, _mouseY);
    }
}

void LAppDelegate::OnTouchMoved(double x, double y)
{
    _mouseX = static_cast<float>(x);
    _mouseY = static_cast<float>(y);

    if (_captured && _view != NULL)
    {
        _view->OnTouchesMoved(_mouseX, _mouseY);
    }
}

GLuint LAppDelegate::CreateShader()
{
    //バーテックスシェーダのコンパイル
    GLuint vertexShaderId = glCreateShader(GL_VERTEX_SHADER);
    const char* vertexShader =
        "#version 100\n"
        "attribute vec3 position;"
        "attribute vec2 uv;"
        "varying vec2 vuv;"
        "void main(void){"
        "    gl_Position = vec4(position, 1.0);"
        "    vuv = uv;"
        "}";
    glShaderSource(vertexShaderId, 1, &vertexShader, NULL);
    glCompileShader(vertexShaderId);

    //フラグメントシェーダのコンパイル
    GLuint fragmentShaderId = glCreateShader(GL_FRAGMENT_SHADER);
    const char* fragmentShader =
        "#version 100\n"
        "precision mediump float;"
        "varying vec2 vuv;"
        "uniform sampler2D texture;"
        "uniform vec4 baseColor;"
        "void main(void){"
        "    gl_FragColor = texture2D(texture, vuv) * baseColor;"
        "}";
    glShaderSource(fragmentShaderId, 1, &fragmentShader, NULL);
    glCompileShader(fragmentShaderId);

    //プログラムオブジェクトの作成
    GLuint programId = glCreateProgram();
    glAttachShader(programId, vertexShaderId);
    glAttachShader(programId, fragmentShaderId);

    // リンク
    glLinkProgram(programId);

    glUseProgram(programId);

    return programId;
}

void LAppDelegate::ModelChangeTo(const char* modelPath, const char* modelJsonFileName)
{
    if (_view != NULL)
    {
        _view->ChangeModelTo(modelPath, modelJsonFileName);
    }
}

void LAppDelegate::ApplyExpression(const char *expressionName)
{
    if (_view != NULL)
    {
        _view->ApplyExpression(expressionName);
    }
}

void LAppDelegate::ApplyOutfit(const char *outfitName)
{
    if (_view != NULL)
    {
        _view->ApplyOutfit(outfitName);
    }
}

void LAppDelegate::ClearOutfit()
{
    if (_view != NULL)
    {
        _view->ClearOutfit();
    }
}

void LAppDelegate::NeedRenderBack(bool render) {
    if (_view != NULL)
    {
        _view->NeedRenderBack(render);
    }
}

void LAppDelegate::ModelResize(float scale)
{
    if (_view != NULL)
    {
        _view->Resize(scale);
    }
}

void LAppDelegate::ModelTranslateX(float x)
{
    if (_view != NULL)
    {
        _view->TranslateX(x);
    }
}

void LAppDelegate::ModelTranslateY(float y)
{
    if (_view != NULL)
    {
        _view->TranslateY(y);
    }
}

void LAppDelegate::ModelAutoBlinkEyes(bool enabled)
{
    if (_view != NULL)
    {
        _view->AutoBlinkEyes(enabled);
    }
}

void LAppDelegate::ModelMouthForm(float value)
{
    if (_view != NULL)
    {
        _view->ModelMouthForm(value);
    }
}

void LAppDelegate::ModelMouthOpenY(float value)
{
    if (_view != NULL)
    {
        _view->ModelMouthOpenY(value);
    }
}

// v26: chatBg 透传。_view 可能为 NULL（异常生命周期下），防御。
void LAppDelegate::SetBackgroundPixels(const Csm::csmUint32* pixels, int width, int height)
{
    if (_view != NULL)
    {
        _view->SetBackgroundPixels(pixels, width, height);
    }
}

// v26: scissor 透传
void LAppDelegate::SetScissorBottom(float ratio)
{
    if (_view != NULL)
    {
        _view->SetScissorBottom(ratio);
    }
}
