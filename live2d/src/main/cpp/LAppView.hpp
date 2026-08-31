/**
 * Copyright(c) Live2D Inc. All rights reserved.
 *
 * Use of this source code is governed by the Live2D Open Software license
 * that can be found at https://www.live2d.com/eula/live2d-open-software-license-agreement_en.html.
 */

#pragma once

#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <Math/CubismMatrix44.hpp>
#include <Math/CubismViewMatrix.hpp>
#include "CubismFramework.hpp"
#include "LAppDelegate.hpp"
#include <Rendering/OpenGL/CubismRenderTarget_OpenGLES2.hpp>
#include <string>

class TouchManager;
class LAppSprite;
class LAppModel;

/**
* @brief 描画クラス
*/
class LAppView
{
public:

    /**
     * @brief LAppModelのレンダリング先
     */
    enum SelectTarget
    {
        SelectTarget_None,                ///< デフォルトのフレームバッファにレンダリング
        SelectTarget_ModelFrameBuffer,    ///< LAppModelが各自持つフレームバッファにレンダリング
        SelectTarget_ViewFrameBuffer,     ///< LAppViewの持つフレームバッファにレンダリング
    };

    /**
    * @brief コンストラクタ
    */
    LAppView();

    /**
    * @brief デストラクタ
    */
    ~LAppView();

    /**
    * @brief 初期化する。
    */
    void Initialize();

    /**
    * @brief 描画する。
    */
    void Render();

    /**
    * @brief シェーダーの初期化を行う。
    */
    void InitializeShader();

    /**
    * @brief 画像の初期化を行う。
    */
    void InitializeSprite();

    /**
    * @brief タッチされたときに呼ばれる。
    *
    * @param[in]       pointX            スクリーンX座標
    * @param[in]       pointY            スクリーンY座標
    */
    void OnTouchesBegan(float pointX, float pointY) const;

    /**
    * @brief タッチしているときにポインタが動いたら呼ばれる。
    *
    * @param[in]       pointX            スクリーンX座標
    * @param[in]       pointY            スクリーンY座標
    */
    void OnTouchesMoved(float pointX, float pointY) const;

    /**
    * @brief タッチが終了したら呼ばれる。
    *
    * @param[in]       pointX            スクリーンX座標
    * @param[in]       pointY            スクリーンY座標
    */
    void OnTouchesEnded(float pointX, float pointY);

    /**
    * @brief X座標をView座標に変換する。
    *
    * @param[in]       deviceX            デバイスX座標
    */
    float TransformViewX(float deviceX) const;

    /**
    * @brief Y座標をView座標に変換する。
    *
    * @param[in]       deviceY            デバイスY座標
    */
    float TransformViewY(float deviceY) const;

    /**
    * @brief X座標をScreen座標に変換する。
    *
    * @param[in]       deviceX            デバイスX座標
    */
    float TransformScreenX(float deviceX) const;

    /**
    * @brief Y座標をScreen座標に変換する。
    *
    * @param[in]       deviceY            デバイスY座標
    */
    float TransformScreenY(float deviceY) const;

    /**
     * @brief   モデル1体を描画する直前にコールされる
     */
    void PreModelDraw(LAppModel &refModel);

    /**
     * @brief   モデル1体を描画した直後にコールされる
     */
    void PostModelDraw(LAppModel &refModel);

    /**
     * @brief   別レンダリングターゲットにモデルを描画するサンプルで
     *           描画時のαを決定する
     */
    float GetSpriteAlpha(int assign) const;

    /**
     * @brief レンダリング先を切り替える
     */
    void SwitchRenderingTarget(SelectTarget targetType);

    /**
     * @brief レンダリング先をデフォルト以外に切り替えた際の背景クリア色設定
     * @param[in]   r   赤(0.0~1.0)
     * @param[in]   g   緑(0.0~1.0)
     * @param[in]   b   青(0.0~1.0)
     */
    void SetRenderTargetClearColor(float r, float g, float b);

    void ChangeModelTo(std::string modelPath, std::string modelJsonFileName);

    void ApplyExpression(const char* expressionName);

    void ApplyOutfit(const char* outfitName);

    void ClearOutfit();

    void NeedRenderBack(bool needRender);

    void Resize(float scale);

    void TranslateX(float x);

    void TranslateY(float y);

    void AutoBlinkEyes(bool enabled);

    void ModelMouthForm(float value);

    void ModelMouthOpenY(float value);

    /**
     * v26: 把 chatBg 喂进 GL，渲染成模型下面的全屏 quad。
     * pixels 是 ARGB 已 swap 成 [R,G,B,A] 字节序的 uint32 数组。
     * Render() 之前调用都安全；会在下一次 Render() 实际建纹理。
     */
    void SetBackgroundPixels(const Csm::csmUint32* pixels, int width, int height);

    /**
     * v26: 模型渲染时裁掉下 ratio * windowHeight 像素（0 = 不裁，0.35 = 裁下 35%）。
     * chatBg 不受 scissor 影响（chatBg 整屏填底，scissor 只作用在模型那段）。
     */
    void SetScissorBottom(float ratio);

private:
    TouchManager* _touchManager;                 ///< タッチマネージャー
    Csm::CubismMatrix44* _deviceToScreen;    ///< デバイスからスクリーンへの行列
    Csm::CubismViewMatrix* _viewMatrix;      ///< viewMatrix
    std::string _nextModelPath;              ///< 次のモデルパス
    std::string _nextModelJsonFileName;      ///< 次のモデルJsonファイル名前
    LAppModelParameters _modelParameters;    ///< モデルのパラメータ


    GLuint _programId;                       ///< シェーダID
    LAppSprite* _back;                       ///< 背景画像
    LAppSprite* _gear;                       ///< ギア画像
    LAppSprite* _power;                      ///< 電源画像
    bool _changeModel;                       ///< モデル切り替えフラグ
    bool _needRenderBack = false;

    // レンダリング先を別ターゲットにする方式の場合に使用
    LAppSprite* _renderSprite;                                      ///< モードによっては_renderBufferのテクスチャを描画
    Csm::Rendering::CubismRenderTarget_OpenGLES2 _renderBuffer;   ///< モードによってはCubismモデル結果をこっちにレンダリング
    SelectTarget _renderTarget;     ///< レンダリング先の選択肢
    float _clearColor[4];           ///< レンダリングターゲットのクリアカラー

    // v26: chatBg 全屏 quad
    LAppSprite* _bgSprite = NULL;           ///< chatBg 纹理 sprite
    GLuint _bgTextureId = 0;               ///< chatBg 纹理 ID

    // v26: 半身体裁切
    float _scissorBottom = 0.0f;           ///< 0 = 不裁；0.35 = 裁掉下 35%
};
