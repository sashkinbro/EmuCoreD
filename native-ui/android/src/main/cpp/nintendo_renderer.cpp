#include "nintendo_renderer.h"
#include "nintendo_shaders.h"

#include <GLES3/gl3.h>
#include <android/keycodes.h>
#include <android/log.h>
#include <algorithm>
#include <array>
#include <cmath>
#include <sstream>

#if defined(EMUCORED_DIAGNOSTICS) && EMUCORED_DIAGNOSTICS
#define NUI_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "NintendoUI", __VA_ARGS__)
#else
#define NUI_LOGD(...) ((void)0)
#endif
#define NUI_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "NintendoUI", __VA_ARGS__)

namespace nintendoui::bridge {
bool rasterizeTextBitmap(const std::string& text, float fontPx, int maxWidthPx, bool bold, int align,
                         int& outW, int& outH, std::vector<uint8_t>& outRgba);
}

namespace nintendoui {

namespace {

constexpr int kTouchDown = 0;
constexpr int kTouchUp = 1;
constexpr int kTouchMove = 2;
constexpr int kTouchCancel = 3;

enum HitAction : int {
    HIT_HUB_CARD = 1,
    HIT_BACK = 2,
    HIT_GAME_TAB = 3,
    HIT_GAME_CARD = 4,
    HIT_SAVE_SUBTAB = 5,
    HIT_SAVE_PRIMARY = 6,
    HIT_SAVE_DELETE = 7,
    HIT_SETTING_ROW = 8,
    HIT_FOOTER_THEME = 9,
    HIT_FOOTER_STANDARD = 10,
    HIT_SKIP_INTRO = 11,
};

GLuint compileShader(GLenum type, const char* src) {
    GLuint sh = glCreateShader(type);
    if (!sh) return 0;
    glShaderSource(sh, 1, &src, nullptr);
    glCompileShader(sh);
    GLint ok = GL_FALSE;
    glGetShaderiv(sh, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char logBuf[512];
        glGetShaderInfoLog(sh, sizeof(logBuf), nullptr, logBuf);
        NUI_LOGE("Shader compile error: %s", logBuf);
        glDeleteShader(sh);
        return 0;
    }
    return sh;
}

GLuint linkProgram(const char* vertSrc, const char* fragSrc) {
    GLuint vs = compileShader(GL_VERTEX_SHADER, vertSrc);
    GLuint fs = compileShader(GL_FRAGMENT_SHADER, fragSrc);
    if (!vs || !fs) {
        if (vs) glDeleteShader(vs);
        if (fs) glDeleteShader(fs);
        return 0;
    }
    GLuint prog = glCreateProgram();
    glAttachShader(prog, vs);
    glAttachShader(prog, fs);
    glLinkProgram(prog);
    glDeleteShader(vs);
    glDeleteShader(fs);
    GLint ok = GL_FALSE;
    glGetProgramiv(prog, GL_LINK_STATUS, &ok);
    if (!ok) {
        char logBuf[512];
        glGetProgramInfoLog(prog, sizeof(logBuf), nullptr, logBuf);
        NUI_LOGE("Program link error: %s", logBuf);
        glDeleteProgram(prog);
        return 0;
    }
    return prog;
}

Color4 rgbaHex(uint32_t hex, float alpha = 1.f) {
    return {
        ((hex >> 16) & 0xFF) / 255.f,
        ((hex >> 8) & 0xFF) / 255.f,
        (hex & 0xFF) / 255.f,
        alpha,
    };
}

float clampf(float v, float lo, float hi) {
    return std::max(lo, std::min(hi, v));
}

float lerpf(float a, float b, float t) {
    return a + (b - a) * clampf(t, 0.f, 1.f);
}

const std::array<const char*, 10> kConsoleOrder = {
    "3ds", "nds", "wii", "gamecube", "n64", "snes", "nes", "gba", "gbc", "gb"
};

} // namespace

NintendoRenderer::NintendoRenderer() = default;

NintendoRenderer::~NintendoRenderer() {
    releaseGlResources();
}

void NintendoRenderer::initShaders() {
    if (progBackdrop_ && progRounded_ && progTextured_ && quadVao_) return;

    progBackdrop_ = linkProgram(shaders::kQuadVert, shaders::kBackdropFrag);
    progRounded_ = linkProgram(shaders::kQuadVert, shaders::kRoundedRectFrag);
    progTextured_ = linkProgram(shaders::kQuadVert, shaders::kTexturedRectFrag);

    static constexpr float kQuadVerts[] = {
        0.f, 0.f, 0.f, 0.f,
        1.f, 0.f, 1.f, 0.f,
        0.f, 1.f, 0.f, 1.f,
        1.f, 1.f, 1.f, 1.f,
    };
    glGenVertexArrays(1, &quadVao_);
    glGenBuffers(1, &quadVbo_);
    glBindVertexArray(quadVao_);
    glBindBuffer(GL_ARRAY_BUFFER, quadVbo_);
    glBufferData(GL_ARRAY_BUFFER, sizeof(kQuadVerts), kQuadVerts, GL_STATIC_DRAW);
    glEnableVertexAttribArray(0);
    glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float), reinterpret_cast<void*>(0));
    glEnableVertexAttribArray(1);
    glVertexAttribPointer(1, 2, GL_FLOAT, GL_FALSE, 4 * sizeof(float),
                          reinterpret_cast<void*>(2 * sizeof(float)));
    glBindVertexArray(0);
}

void NintendoRenderer::destroyShaders() {
    if (progBackdrop_) { glDeleteProgram(progBackdrop_); progBackdrop_ = 0; }
    if (progRounded_) { glDeleteProgram(progRounded_); progRounded_ = 0; }
    if (progTextured_) { glDeleteProgram(progTextured_); progTextured_ = 0; }
    if (quadVbo_) { glDeleteBuffers(1, &quadVbo_); quadVbo_ = 0; }
    if (quadVao_) { glDeleteVertexArrays(1, &quadVao_); quadVao_ = 0; }
}

void NintendoRenderer::releaseGlResources() {
    std::lock_guard lock(dataMutex_);
    for (auto& [k, tex] : textTextures_) {
        if (tex.texId) glDeleteTextures(1, &tex.texId);
    }
    textTextures_.clear();
    for (auto& [k, tex] : imageTextures_) {
        if (tex.texId) glDeleteTextures(1, &tex.texId);
    }
    imageTextures_.clear();
    pendingUploads_.clear();
    destroyShaders();
}

void NintendoRenderer::onSurfaceCreated() {
    std::lock_guard lock(dataMutex_);
    // Previous GL context may have been lost across surface recreation; zero stale IDs safely.
    textTextures_.clear();
    imageTextures_.clear();
    progBackdrop_ = progRounded_ = progTextured_ = quadVao_ = quadVbo_ = 0;
    initShaders();
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_CULL_FACE);
    glEnable(GL_BLEND);
    glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
}

void NintendoRenderer::computeStageViewport() {
    float sw = static_cast<float>(std::max(1, screenW_));
    float sh = static_cast<float>(std::max(1, screenH_));
    float targetAspect = 0.f;
    switch (aspectMode_) {
        case AspectMode::Ratio16x9: targetAspect = 16.f / 9.f; break;
        case AspectMode::Ratio16x10: targetAspect = 16.f / 10.f; break;
        case AspectMode::Ratio4x3: targetAspect = 4.f / 3.f; break;
        case AspectMode::Auto:
        default: targetAspect = 0.f; break;
    }

    if (targetAspect > 0.f) {
        float cur = sw / sh;
        if (cur > targetAspect) {
            stageH_ = sh;
            stageW_ = sh * targetAspect;
        } else {
            stageW_ = sw;
            stageH_ = sw / targetAspect;
        }
        stageX_ = (sw - stageW_) * 0.5f;
        stageY_ = (sh - stageH_) * 0.5f;
    } else {
        stageX_ = 0.f;
        stageY_ = 0.f;
        stageW_ = sw;
        stageH_ = sh;
    }

    float minDim = std::min(stageW_, stageH_);
    uiScale_ = clampf(minDim / 720.f, 0.72f, 2.6f);
    bool portrait = stageW_ < stageH_;
    gameCols_ = portrait ? 2 : (stageW_ / stageH_ >= 1.45f ? 4 : 3);
}

void NintendoRenderer::onSurfaceChanged(int width, int height) {
    std::lock_guard lock(dataMutex_);
    screenW_ = std::max(1, width);
    screenH_ = std::max(1, height);
    glViewport(0, 0, screenW_, screenH_);
    computeStageViewport();
    // Force text re-rasterization only when DPI scale changed materially
    for (auto& [k, tex] : textTextures_) {
        if (tex.texId) glDeleteTextures(1, &tex.texId);
    }
    textTextures_.clear();
}

void NintendoRenderer::setLocalizedStrings(const std::unordered_map<std::string, std::string>& strings) {
    std::lock_guard lock(dataMutex_);
    strings_ = strings;
    for (auto& [k, tex] : textTextures_) {
        if (tex.texId) glDeleteTextures(1, &tex.texId);
    }
    textTextures_.clear();
    rebuildGameTabs(true);
}

void NintendoRenderer::setCatalog(std::vector<GameEntry> games, std::vector<std::string> recentPaths) {
    std::lock_guard lock(dataMutex_);
    games_ = std::move(games);
    recentPaths_ = std::move(recentPaths);
    rebuildGameTabs(screen_ == Screen::Games);
}

void NintendoRenderer::rebuildGameTabs(bool preserveSelection) {
    std::string prevTabId = (!preserveSelection || selectedGameTab_ < 0 ||
                             selectedGameTab_ >= static_cast<int>(gameTabs_.size()))
                                ? ""
                                : gameTabs_[static_cast<size_t>(selectedGameTab_)].id;

    gameTabs_.clear();
    for (auto& g : games_) g.isRecent = false;

    // Recent tab MUST appear first once >= 1 launched game exists in library
    std::vector<int> recentIndices;
    recentIndices.reserve(recentPaths_.size());
    for (const auto& rp : recentPaths_) {
        for (int i = 0; i < static_cast<int>(games_.size()); i++) {
            if (games_[static_cast<size_t>(i)].path == rp) {
                games_[static_cast<size_t>(i)].isRecent = true;
                recentIndices.push_back(i);
                break;
            }
        }
    }
    if (!recentIndices.empty()) {
        GameTab rt;
        rt.id = "recent";
        rt.label = str("nintendoui_tab_recent", "Recent");
        rt.isRecent = true;
        rt.gameIndices = std::move(recentIndices);
        gameTabs_.push_back(std::move(rt));
    }

    // Per-console tabs in Nintendo chronological/family order
    std::unordered_map<std::string, std::vector<int>> byConsole;
    std::unordered_map<std::string, std::string> labelByConsole;
    for (int i = 0; i < static_cast<int>(games_.size()); i++) {
        const auto& g = games_[static_cast<size_t>(i)];
        byConsole[g.consoleId].push_back(i);
        if (labelByConsole.find(g.consoleId) == labelByConsole.end()) {
            labelByConsole[g.consoleId] = g.consoleShort.empty() ? g.consoleLabel : g.consoleShort;
        }
    }
    for (const char* cid : kConsoleOrder) {
        auto it = byConsole.find(cid);
        if (it == byConsole.end() || it->second.empty()) continue;
        GameTab tab;
        tab.id = cid;
        tab.label = labelByConsole[cid];
        tab.isRecent = false;
        tab.gameIndices = std::move(it->second);
        gameTabs_.push_back(std::move(tab));
    }
    // Any remaining consoles not in kConsoleOrder
    for (auto& [cid, list] : byConsole) {
        bool already = std::any_of(gameTabs_.begin(), gameTabs_.end(),
                                   [&](const GameTab& t) { return !t.isRecent && t.id == cid; });
        if (!already && !list.empty()) {
            GameTab tab;
            tab.id = cid;
            tab.label = labelByConsole[cid];
            tab.isRecent = false;
            tab.gameIndices = std::move(list);
            gameTabs_.push_back(std::move(tab));
        }
    }

    if (gameTabs_.empty()) {
        selectedGameTab_ = 0;
        gameFocus_ = 0;
        gameScrollY_ = targetGameScrollY_ = 0.f;
        return;
    }

    if (preserveSelection && !prevTabId.empty()) {
        for (int i = 0; i < static_cast<int>(gameTabs_.size()); i++) {
            if (gameTabs_[static_cast<size_t>(i)].id == prevTabId) {
                selectedGameTab_ = i;
                int maxIdx = static_cast<int>(gameTabs_[static_cast<size_t>(i)].gameIndices.size()) - 1;
                gameFocus_ = std::clamp(gameFocus_, 0, std::max(0, maxIdx));
                return;
            }
        }
    }

    // Default when opening or updating from outside Games: index 0 (which is Recent when non-empty)
    selectedGameTab_ = 0;
    gameFocus_ = 0;
    gameScrollY_ = targetGameScrollY_ = 0.f;
}

void NintendoRenderer::openGamesScreenDefault() {
    screen_ = Screen::Games;
    screenFade_ = 0.f;
    // Recent tab MUST be selected first if present
    selectedGameTab_ = 0;
    gameFocus_ = 0;
    gameScrollY_ = targetGameScrollY_ = 0.f;
}

void NintendoRenderer::setQuickSaves(std::vector<QuickSaveEntry> saves) {
    std::lock_guard lock(dataMutex_);
    quickSaves_ = std::move(saves);
    if (savesSubTab_ == 0) {
        saveFocus_ = std::clamp(saveFocus_, 0, std::max(0, static_cast<int>(quickSaves_.size()) - 1));
    }
}

void NintendoRenderer::setMemorySaves(std::vector<MemorySaveEntry> saves) {
    std::lock_guard lock(dataMutex_);
    memorySaves_ = std::move(saves);
    if (savesSubTab_ == 1) {
        saveFocus_ = std::clamp(saveFocus_, 0, std::max(0, static_cast<int>(memorySaves_.size()) - 1));
    }
}

void NintendoRenderer::setSettings(int themeId, int aspectMode, bool ecoMode, bool sfxEnabled) {
    std::lock_guard lock(dataMutex_);
    theme_ = static_cast<ThemeId>(std::clamp(themeId, 0, static_cast<int>(ThemeId::Count) - 1));
    aspectMode_ = static_cast<AspectMode>(std::clamp(aspectMode, 0, static_cast<int>(AspectMode::Count) - 1));
    ecoMode_ = ecoMode;
    sfxEnabled_ = sfxEnabled;
    computeStageViewport();
}

void NintendoRenderer::setClockText(std::string clockText) {
    std::lock_guard lock(dataMutex_);
    clockText_ = std::move(clockText);
}

void NintendoRenderer::showToast(std::string message) {
    std::lock_guard lock(dataMutex_);
    toastText_ = std::move(message);
    toastTimer_ = 2.6f;
}

void NintendoRenderer::uploadImagePixels(const std::string& key, int w, int h, const std::vector<uint8_t>& rgba) {
    std::lock_guard lock(dataMutex_);
    pendingUploads_.emplace_back(key, std::make_tuple(w, h, rgba));
}

void NintendoRenderer::markImageFailed(const std::string& key) {
    std::lock_guard lock(dataMutex_);
    auto& entry = imageTextures_[key];
    entry.pending = false;
    entry.failed = true;
}

std::vector<HostAction> NintendoRenderer::drainActions() {
    std::lock_guard lock(dataMutex_);
    std::vector<HostAction> out;
    out.swap(pendingActions_);
    return out;
}

std::vector<std::pair<std::string, std::string>> NintendoRenderer::drainImageRequests(int maxCount) {
    std::lock_guard lock(dataMutex_);
    std::vector<std::pair<std::string, std::string>> out;
    const int limit = ecoMode_ ? std::min(maxCount, 1) : maxCount;
    while (!pendingImageRequests_.empty() && static_cast<int>(out.size()) < limit) {
        out.push_back(std::move(pendingImageRequests_.front()));
        pendingImageRequests_.erase(pendingImageRequests_.begin());
    }
    return out;
}

void NintendoRenderer::queueAction(HostAction action) {
    pendingActions_.push_back(std::move(action));
}

void NintendoRenderer::triggerSfx(SfxType sfx) {
    if (!sfxEnabled_) return;
    HostAction a;
    a.type = HostActionType::PlaySfx;
    a.intArg1 = static_cast<int>(sfx);
    pendingActions_.push_back(std::move(a));
}

const std::string& NintendoRenderer::str(const char* key, const char* fallback) const {
    auto it = strings_.find(key);
    if (it != strings_.end() && !it->second.empty()) return it->second;
    static const std::string kEmpty;
    auto [ins, _] = const_cast<std::unordered_map<std::string, std::string>&>(strings_)
                        .emplace(std::string(key), std::string(fallback));
    return ins->second;
}

ThemePalette NintendoRenderer::palette() const {
    switch (theme_) {
        case ThemeId::WiiPearl:
            return {
                .bgTop = rgbaHex(0xEDF4FA),
                .bgBottom = rgbaHex(0xD3E3F0),
                .headerBg = rgbaHex(0xF8FBFF, 0.92f),
                .cardBgTop = rgbaHex(0xFFFFFF, 0.96f),
                .cardBgBottom = rgbaHex(0xEDF3F8, 0.96f),
                .cardSelectedBgTop = rgbaHex(0xFFFFFF, 1.0f),
                .cardSelectedBgBottom = rgbaHex(0xE1F4FC, 1.0f),
                .borderNormal = rgbaHex(0xB8CCDD, 0.85f),
                .borderFocus = rgbaHex(0x009AC7, 1.0f),
                .accentA = rgbaHex(0x009AC7),
                .accentB = rgbaHex(0x36C2F0),
                .textPrimary = rgbaHex(0x182534),
                .textSecondary = rgbaHex(0x52697F),
                .textOnAccent = rgbaHex(0xFFFFFF),
                .tabBg = rgbaHex(0xDDE8F2, 0.90f),
                .toastBg = rgbaHex(0x122233, 0.92f),
            };
        case ThemeId::GameCubeIndigo:
            return {
                .bgTop = rgbaHex(0x161133),
                .bgBottom = rgbaHex(0x2A205E),
                .headerBg = rgbaHex(0x1D1645, 0.90f),
                .cardBgTop = rgbaHex(0x2C2361, 0.94f),
                .cardBgBottom = rgbaHex(0x20194A, 0.94f),
                .cardSelectedBgTop = rgbaHex(0x3D3285, 1.0f),
                .cardSelectedBgBottom = rgbaHex(0x2C2266, 1.0f),
                .borderNormal = rgbaHex(0x52459E, 0.72f),
                .borderFocus = rgbaHex(0x9A8CFF, 1.0f),
                .accentA = rgbaHex(0x7C68FF),
                .accentB = rgbaHex(0xB4A7FF),
                .textPrimary = rgbaHex(0xF2F3FF),
                .textSecondary = rgbaHex(0xB3B7DF),
                .textOnAccent = rgbaHex(0xFFFFFF),
                .tabBg = rgbaHex(0x231B50, 0.92f),
                .toastBg = rgbaHex(0x0F0C24, 0.95f),
            };
        case ThemeId::FamicomCrimson:
            return {
                .bgTop = rgbaHex(0x6D101B),
                .bgBottom = rgbaHex(0x430911),
                .headerBg = rgbaHex(0x5B0D17, 0.92f),
                .cardBgTop = rgbaHex(0xF8F1E3, 0.97f),
                .cardBgBottom = rgbaHex(0xEAE0CA, 0.97f),
                .cardSelectedBgTop = rgbaHex(0xFFFDF7, 1.0f),
                .cardSelectedBgBottom = rgbaHex(0xF6EAD1, 1.0f),
                .borderNormal = rgbaHex(0xBA9858, 0.70f),
                .borderFocus = rgbaHex(0xF0BA49, 1.0f),
                .accentA = rgbaHex(0xC92638),
                .accentB = rgbaHex(0xE8B44B),
                .textPrimary = rgbaHex(0x2B1917),
                .textSecondary = rgbaHex(0x6F5149),
                .textOnAccent = rgbaHex(0xFFFFFF),
                .tabBg = rgbaHex(0x821725, 0.92f),
                .toastBg = rgbaHex(0x2A070C, 0.95f),
            };
        case ThemeId::GameBoyClassic:
            return {
                .bgTop = rgbaHex(0xA4B55E),
                .bgBottom = rgbaHex(0x899A48),
                .headerBg = rgbaHex(0x98A955, 0.94f),
                .cardBgTop = rgbaHex(0xBAC976, 0.96f),
                .cardBgBottom = rgbaHex(0xA7B764, 0.96f),
                .cardSelectedBgTop = rgbaHex(0xCFDC8E, 1.0f),
                .cardSelectedBgBottom = rgbaHex(0xBBC978, 1.0f),
                .borderNormal = rgbaHex(0x4F6331, 0.78f),
                .borderFocus = rgbaHex(0x132910, 1.0f),
                .accentA = rgbaHex(0x2B4A22),
                .accentB = rgbaHex(0x436835),
                .textPrimary = rgbaHex(0x12260F),
                .textSecondary = rgbaHex(0x364D26),
                .textOnAccent = rgbaHex(0xD2DF94),
                .tabBg = rgbaHex(0x94A451, 0.92f),
                .toastBg = rgbaHex(0x12260F, 0.95f),
            };
        case ThemeId::SwitchMidnight:
        default:
            return {
                .bgTop = rgbaHex(0x11151C),
                .bgBottom = rgbaHex(0x1B222E),
                .headerBg = rgbaHex(0x161B24, 0.92f),
                .cardBgTop = rgbaHex(0x222936, 0.96f),
                .cardBgBottom = rgbaHex(0x1B212C, 0.96f),
                .cardSelectedBgTop = rgbaHex(0x2A3446, 1.0f),
                .cardSelectedBgBottom = rgbaHex(0x212938, 1.0f),
                .borderNormal = rgbaHex(0x384357, 0.80f),
                .borderFocus = rgbaHex(0x0AB9E6, 1.0f),
                .accentA = rgbaHex(0x0AB9E6),
                .accentB = rgbaHex(0xFF4554),
                .textPrimary = rgbaHex(0xF4F7FA),
                .textSecondary = rgbaHex(0x98A5B8),
                .textOnAccent = rgbaHex(0xFFFFFF),
                .tabBg = rgbaHex(0x1E2531, 0.92f),
                .toastBg = rgbaHex(0x0B0E13, 0.95f),
            };
    }
}

Color4 NintendoRenderer::consoleBadgeColor(const std::string& consoleId) const {
    if (consoleId == "3ds") return rgbaHex(0xCE181E);
    if (consoleId == "nds") return rgbaHex(0x4D586B);
    if (consoleId == "wii") return rgbaHex(0x009AC7);
    if (consoleId == "gamecube") return rgbaHex(0x6956CE);
    if (consoleId == "n64") return rgbaHex(0x1F8A4C);
    if (consoleId == "snes") return rgbaHex(0x6E50B8);
    if (consoleId == "nes") return rgbaHex(0xB8252E);
    if (consoleId == "gba") return rgbaHex(0x493EA6);
    if (consoleId == "gbc") return rgbaHex(0xD98218);
    return rgbaHex(0x557536); // gb
}

void NintendoRenderer::drawBackdrop() {
    if (!progBackdrop_ || !quadVao_) return;
    const auto pal = palette();
    glUseProgram(progBackdrop_);
    glUniform4f(glGetUniformLocation(progBackdrop_, "uRect"), 0.f, 0.f,
                static_cast<float>(screenW_), static_cast<float>(screenH_));
    glUniform2f(glGetUniformLocation(progBackdrop_, "uScreen"),
                static_cast<float>(screenW_), static_cast<float>(screenH_));
    glUniform4f(glGetUniformLocation(progBackdrop_, "uBgTop"),
                pal.bgTop.r, pal.bgTop.g, pal.bgTop.b, pal.bgTop.a);
    glUniform4f(glGetUniformLocation(progBackdrop_, "uBgBottom"),
                pal.bgBottom.r, pal.bgBottom.g, pal.bgBottom.b, pal.bgBottom.a);
    glUniform4f(glGetUniformLocation(progBackdrop_, "uAccentA"),
                pal.accentA.r, pal.accentA.g, pal.accentA.b, pal.accentA.a);
    glUniform4f(glGetUniformLocation(progBackdrop_, "uAccentB"),
                pal.accentB.r, pal.accentB.g, pal.accentB.b, pal.accentB.a);
    glUniform1f(glGetUniformLocation(progBackdrop_, "uTime"), time_);
    glUniform1i(glGetUniformLocation(progBackdrop_, "uEcoMode"), ecoMode_ ? 1 : 0);
    glUniform1i(glGetUniformLocation(progBackdrop_, "uThemeStyle"), static_cast<int>(theme_));

    glBindVertexArray(quadVao_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    // Subtle pillarbox/letterbox frame when forced aspect ratio doesn't match screen
    if (stageX_ > 1.f || stageY_ > 1.f) {
        Color4 bar{0.f, 0.f, 0.f, 0.62f};
        if (stageX_ > 1.f) {
            drawRoundedRect(0.f, 0.f, stageX_, static_cast<float>(screenH_), 0.f, bar, bar, bar, 0.f);
            drawRoundedRect(stageX_ + stageW_, 0.f, static_cast<float>(screenW_) - (stageX_ + stageW_),
                            static_cast<float>(screenH_), 0.f, bar, bar, bar, 0.f);
        }
        if (stageY_ > 1.f) {
            drawRoundedRect(0.f, 0.f, static_cast<float>(screenW_), stageY_, 0.f, bar, bar, bar, 0.f);
            drawRoundedRect(0.f, stageY_ + stageH_, static_cast<float>(screenW_),
                            static_cast<float>(screenH_) - (stageY_ + stageH_), 0.f, bar, bar, bar, 0.f);
        }
    }
}

void NintendoRenderer::drawRoundedRect(float x, float y, float w, float h, float radius,
                                       const Color4& top, const Color4& bottom,
                                       const Color4& border, float borderWidth, float glow) {
    if (!progRounded_ || !quadVao_ || w <= 0.5f || h <= 0.5f) return;
    glUseProgram(progRounded_);
    glUniform4f(glGetUniformLocation(progRounded_, "uRect"), x, y, w, h);
    glUniform2f(glGetUniformLocation(progRounded_, "uScreen"),
                static_cast<float>(screenW_), static_cast<float>(screenH_));
    glUniform4f(glGetUniformLocation(progRounded_, "uColorTop"), top.r, top.g, top.b, top.a);
    glUniform4f(glGetUniformLocation(progRounded_, "uColorBottom"), bottom.r, bottom.g, bottom.b, bottom.a);
    glUniform4f(glGetUniformLocation(progRounded_, "uBorderColor"), border.r, border.g, border.b, border.a);
    glUniform1f(glGetUniformLocation(progRounded_, "uRadius"), radius);
    glUniform1f(glGetUniformLocation(progRounded_, "uBorderWidth"), borderWidth);
    glUniform1f(glGetUniformLocation(progRounded_, "uGlow"), ecoMode_ ? 0.f : glow);

    glBindVertexArray(quadVao_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

void NintendoRenderer::drawTexturedRect(float x, float y, float w, float h, GLuint texId,
                                        const Color4& tint, float radius, bool rounded) {
    if (!progTextured_ || !quadVao_ || !texId || w <= 0.5f || h <= 0.5f) return;
    glUseProgram(progTextured_);
    glUniform4f(glGetUniformLocation(progTextured_, "uRect"), x, y, w, h);
    glUniform2f(glGetUniformLocation(progTextured_, "uScreen"),
                static_cast<float>(screenW_), static_cast<float>(screenH_));
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, texId);
    glUniform1i(glGetUniformLocation(progTextured_, "uTex"), 0);
    glUniform4f(glGetUniformLocation(progTextured_, "uTint"), tint.r, tint.g, tint.b, tint.a);
    glUniform1f(glGetUniformLocation(progTextured_, "uRadius"), radius);
    glUniform1i(glGetUniformLocation(progTextured_, "uUseRounded"), rounded ? 1 : 0);

    glBindVertexArray(quadVao_);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

GLuint NintendoRenderer::acquireTextTexture(const std::string& text, float fontPx, int maxWidthPx,
                                              bool bold, int align, int& outW, int& outH) {
    if (text.empty()) {
        outW = outH = 0;
        return 0;
    }
    const int quantizedW = std::max(32, ((maxWidthPx + 7) / 8) * 8);
    const int quantizedSize = std::max(10, static_cast<int>(std::round(fontPx)));
    std::ostringstream oss;
    oss << quantizedSize << ':' << quantizedW << ':' << (bold ? 1 : 0) << ':' << align << ':' << text;
    const std::string cacheKey = oss.str();

    auto it = textTextures_.find(cacheKey);
    if (it != textTextures_.end()) {
        it->second.lastUsedFrame = frameIndex_;
        outW = it->second.width;
        outH = it->second.height;
        return it->second.texId;
    }

    int w = 0, h = 0;
    std::vector<uint8_t> rgba;
    if (!bridge::rasterizeTextBitmap(text, static_cast<float>(quantizedSize), quantizedW, bold, align, w, h, rgba)) {
        return 0;
    }

    GLuint tex = 0;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data());

    textTextures_[cacheKey] = CachedTexture{tex, w, h, false, false, frameIndex_};
    outW = w;
    outH = h;
    return tex;
}

GLuint NintendoRenderer::acquireCoverTexture(const std::string& key, const std::string& path) {
    if (key.empty() || path.empty()) return 0;
    auto it = imageTextures_.find(key);
    if (it != imageTextures_.end()) {
        it->second.lastUsedFrame = frameIndex_;
        return it->second.texId;
    }
    auto& entry = imageTextures_[key];
    entry.pending = true;
    entry.lastUsedFrame = frameIndex_;
    pendingImageRequests_.emplace_back(key, path);
    return 0;
}

void NintendoRenderer::evictUnusedTexturesIfNeeded() {
    if (textTextures_.size() > 280) {
        std::vector<std::pair<uint64_t, std::string>> order;
        order.reserve(textTextures_.size());
        for (const auto& [k, v] : textTextures_) order.emplace_back(v.lastUsedFrame, k);
        std::sort(order.begin(), order.end());
        size_t removeCount = textTextures_.size() - 200;
        for (size_t i = 0; i < removeCount; i++) {
            auto it = textTextures_.find(order[i].second);
            if (it != textTextures_.end()) {
                if (it->second.texId) glDeleteTextures(1, &it->second.texId);
                textTextures_.erase(it);
            }
        }
    }
    if (imageTextures_.size() > 140) {
        std::vector<std::pair<uint64_t, std::string>> order;
        order.reserve(imageTextures_.size());
        for (const auto& [k, v] : imageTextures_) {
            if (!v.pending) order.emplace_back(v.lastUsedFrame, k);
        }
        std::sort(order.begin(), order.end());
        size_t removeCount = std::min(order.size(), imageTextures_.size() - 100);
        for (size_t i = 0; i < removeCount; i++) {
            auto it = imageTextures_.find(order[i].second);
            if (it != imageTextures_.end()) {
                if (it->second.texId) glDeleteTextures(1, &it->second.texId);
                imageTextures_.erase(it);
            }
        }
    }
}

void NintendoRenderer::drawText(const std::string& text, float x, float y, float maxW,
                                float fontPx, const Color4& color, int align, bool bold, float* outH) {
    int w = 0, h = 0;
    GLuint tex = acquireTextTexture(text, fontPx, static_cast<int>(maxW), bold, align, w, h);
    if (outH) *outH = static_cast<float>(h);
    if (!tex || w <= 0 || h <= 0) return;

    float drawX = x;
    if (align == 1) drawX = x + (maxW - static_cast<float>(w)) * 0.5f;
    else if (align == 2) drawX = x + (maxW - static_cast<float>(w));
    drawTexturedRect(drawX, y, static_cast<float>(w), static_cast<float>(h), tex, color, 0.f, false);
}

void NintendoRenderer::drawConsoleBadge(float x, float y, float w, float h, const std::string& consoleId,
                                        const std::string& shortLabel) {
    Color4 c = consoleBadgeColor(consoleId);
    Color4 dark{c.r * 0.8f, c.g * 0.8f, c.b * 0.8f, 1.f};
    Color4 border{1.f, 1.f, 1.f, 0.25f};
    drawRoundedRect(x, y, w, h, h * 0.38f, c, dark, border, 1.2f * uiScale_);
    float textH = 0.f;
    float fpx = clampf(h * 0.56f, 12.f, 28.f);
    drawText(shortLabel, x, y + (h - fpx * 1.25f) * 0.5f, w, fpx, {1.f, 1.f, 1.f, 1.f}, 1, true, &textH);
}

void NintendoRenderer::renderIntro() {
    const auto pal = palette();
    float dur = ecoMode_ ? 1.25f : 1.85f;
    float p = clampf(introElapsed_ / dur, 0.f, 1.f);
    float ease = 1.f - std::pow(1.f - clampf(p / 0.55f, 0.f, 1.f), 3.f);
    float fadeOut = p > 0.82f ? 1.f - clampf((p - 0.82f) / 0.18f, 0.f, 1.f) : 1.f;

    float cx = stageX_ + stageW_ * 0.5f;
    float cy = stageY_ + stageH_ * 0.46f;
    float emblemSize = 96.f * uiScale_ * lerpf(0.75f, 1.0f, ease);

    // Subtle geometric Nintendo-inspired twin-capsule emblem + outer halo (no cringy text!)
    Color4 glowBorder{pal.accentA.r, pal.accentA.g, pal.accentA.b, 0.65f * fadeOut};
    drawRoundedRect(cx - emblemSize * 1.08f, cy - emblemSize * 0.56f,
                    emblemSize * 0.94f, emblemSize * 1.12f, 22.f * uiScale_,
                    {pal.accentA.r, pal.accentA.g, pal.accentA.b, 0.95f * fadeOut},
                    {pal.accentA.r * 0.8f, pal.accentA.g * 0.8f, pal.accentA.b * 0.8f, 0.95f * fadeOut},
                    glowBorder, 2.5f * uiScale_, 0.6f * fadeOut);
    drawRoundedRect(cx + emblemSize * 0.14f, cy - emblemSize * 0.56f,
                    emblemSize * 0.94f, emblemSize * 1.12f, 22.f * uiScale_,
                    {pal.accentB.r, pal.accentB.g, pal.accentB.b, 0.95f * fadeOut},
                    {pal.accentB.r * 0.8f, pal.accentB.g * 0.8f, pal.accentB.b * 0.8f, 0.95f * fadeOut},
                    {pal.accentB.r, pal.accentB.g, pal.accentB.b, 0.65f * fadeOut},
                    2.5f * uiScale_, 0.6f * fadeOut);
    // Clean analog dot accents inside twin capsules
    Color4 dotColor{pal.textOnAccent.r, pal.textOnAccent.g, pal.textOnAccent.b, fadeOut};
    float dotR = 16.f * uiScale_;
    drawRoundedRect(cx - emblemSize * 0.61f - dotR * 0.5f, cy - emblemSize * 0.24f, dotR, dotR, dotR * 0.5f,
                    dotColor, dotColor, dotColor, 0.f);
    drawRoundedRect(cx + emblemSize * 0.61f - dotR * 0.5f, cy + emblemSize * 0.08f, dotR, dotR, dotR * 0.5f,
                    dotColor, dotColor, dotColor, 0.f);

    float wordY = cy + emblemSize * 0.78f;
    Color4 titleColor{pal.textPrimary.r, pal.textPrimary.g, pal.textPrimary.b, ease * fadeOut};
    drawText("EmuCoreD", stageX_, wordY, stageW_, 42.f * uiScale_, titleColor, 1, true);

    float barW = 240.f * uiScale_ * ease;
    drawRoundedRect(cx - barW * 0.5f, wordY + 56.f * uiScale_, barW, 4.f * uiScale_, 2.f * uiScale_,
                    {pal.accentA.r, pal.accentA.g, pal.accentA.b, 0.9f * fadeOut},
                    {pal.accentB.r, pal.accentB.g, pal.accentB.b, 0.9f * fadeOut},
                    {0, 0, 0, 0}, 0.f);

    hitTargets_.push_back({{stageX_, stageY_, stageW_, stageH_}, HIT_SKIP_INTRO, 0, 0});
}

void NintendoRenderer::renderHeader(const std::string& titleText, bool showBackButton) {
    const auto pal = palette();
    float padX = 28.f * uiScale_;
    float topY = stageY_ + 18.f * uiScale_;
    float barH = 60.f * uiScale_;

    drawRoundedRect(stageX_ + padX, topY, stageW_ - padX * 2.f, barH, 18.f * uiScale_,
                    pal.headerBg, pal.headerBg, pal.borderNormal, 1.5f * uiScale_);

    float titleX = stageX_ + padX + 20.f * uiScale_;
    if (showBackButton) {
        float backW = 112.f * uiScale_;
        float backH = 38.f * uiScale_;
        float backX = stageX_ + padX + 12.f * uiScale_;
        float backY = topY + (barH - backH) * 0.5f;
        drawRoundedRect(backX, backY, backW, backH, 12.f * uiScale_,
                        pal.tabBg, pal.tabBg, pal.borderFocus, 1.5f * uiScale_);
        drawText(str("nintendoui_action_back", "Back"), backX + 6.f * uiScale_,
                 backY + 7.f * uiScale_, backW - 12.f * uiScale_, 19.f * uiScale_,
                 pal.textPrimary, 1, true);
        hitTargets_.push_back({{backX, backY, backW, backH}, HIT_BACK, 0, 0});
        titleX = backX + backW + 16.f * uiScale_;
    }

    drawText(titleText, titleX, topY + 14.f * uiScale_, stageW_ * 0.56f, 26.f * uiScale_,
             pal.textPrimary, 0, true);

    // Clock & status pill on right side
    float clockW = 110.f * uiScale_;
    drawText(clockText_, stageX_ + stageW_ - padX - clockW - 14.f * uiScale_,
             topY + 17.f * uiScale_, clockW, 21.f * uiScale_, pal.textSecondary, 2, true);
}

void NintendoRenderer::renderHub() {
    const auto pal = palette();
    renderHeader("EmuCoreD", false);

    float padX = 28.f * uiScale_;
    float areaX = stageX_ + padX;
    float areaY = stageY_ + 98.f * uiScale_;
    float areaW = stageW_ - padX * 2.f;
    float areaH = stageH_ - 176.f * uiScale_;

    bool portrait = stageW_ < stageH_;
    std::array<std::string, 3> titles = {
        str("nintendoui_menu_games", "Games"),
        str("nintendoui_menu_saves", "Save Data"),
        str("nintendoui_menu_settings", "Settings"),
    };

    std::string recCountText = !recentPaths_.empty()
        ? str("nintendoui_tab_recent", "Recent") + " • "
        : "";
    std::string gamesSub = recCountText + std::to_string(games_.size()) + " • " +
                           std::to_string(std::max(0, static_cast<int>(gameTabs_.size()) - (!recentPaths_.empty() ? 1 : 0))) + " " +
                           str("nintendoui_unit_consoles", "consoles");
    std::string savesSub = std::to_string(quickSaves_.size()) + " " + str("nintendoui_saves_tab_quick", "Quick Saves") +
                           "  •  " + std::to_string(memorySaves_.size()) + " " + str("nintendoui_saves_tab_memory", "Memory Cards");
    std::array<std::string, 3> subs = {
        gamesSub,
        savesSub,
        str("nintendoui_settings_subtitle", "Themes • Aspect • Eco Mode"),
    };

    float gap = 20.f * uiScale_;
    for (int i = 0; i < 3; i++) {
        bool focused = (hubFocus_ == i);
        UiRect r;
        if (portrait) {
            float ch = (areaH - gap * 2.f) / 3.f;
            r = {areaX, areaY + i * (ch + gap), areaW, ch};
        } else {
            float cw = (areaW - gap * 2.f) / 3.f;
            float ch = std::min(areaH * 0.78f, cw * 1.05f);
            float cy = areaY + (areaH - ch) * 0.45f;
            r = {areaX + i * (cw + gap), cy, cw, ch};
        }

        float scaleBoost = focused ? 1.035f + 0.008f * std::sin(focusPulse_) : 1.0f;
        float dw = r.w * scaleBoost;
        float dh = r.h * scaleBoost;
        float dx = r.x - (dw - r.w) * 0.5f;
        float dy = r.y - (dh - r.h) * 0.5f;

        Color4 top = focused ? pal.cardSelectedBgTop : pal.cardBgTop;
        Color4 bot = focused ? pal.cardSelectedBgBottom : pal.cardBgBottom;
        Color4 border = focused ? pal.borderFocus : pal.borderNormal;
        float bw = focused ? 3.5f * uiScale_ : 1.5f * uiScale_;
        float glow = focused ? (0.65f + 0.25f * std::sin(focusPulse_)) : 0.f;

        drawRoundedRect(dx, dy, dw, dh, 24.f * uiScale_, top, bot, border, bw, glow);

        // Top Nintendo accent bar inside channel tile
        Color4 barC = (i == 0) ? pal.accentA : (i == 1 ? pal.accentB : border);
        drawRoundedRect(dx + 18.f * uiScale_, dy + 16.f * uiScale_, dw - 36.f * uiScale_,
                        8.f * uiScale_, 4.f * uiScale_, barC, barC, barC, 0.f);

        // Clean geometric channel emblem inside the card
        float iconSize = portrait ? dh * 0.44f : dh * 0.34f;
        float iconX = portrait ? dx + 26.f * uiScale_ : dx + (dw - iconSize) * 0.5f;
        float iconY = portrait ? dy + (dh - iconSize) * 0.5f : dy + dh * 0.22f;
        drawRoundedRect(iconX, iconY, iconSize, iconSize, 18.f * uiScale_,
                        barC, {barC.r * 0.75f, barC.g * 0.75f, barC.b * 0.75f, 1.f},
                        {1.f, 1.f, 1.f, 0.25f}, 1.5f * uiScale_);
        if (i == 0) {
            // Game card miniature grid motif
            float cell = iconSize * 0.24f;
            float gx = iconX + iconSize * 0.22f;
            float gy = iconY + iconSize * 0.22f;
            Color4 wcol{1.f, 1.f, 1.f, 0.92f};
            for (int r0 = 0; r0 < 2; r0++)
                for (int c0 = 0; c0 < 2; c0++)
                    drawRoundedRect(gx + c0 * (cell + 6.f * uiScale_), gy + r0 * (cell + 6.f * uiScale_),
                                    cell, cell, 4.f * uiScale_, wcol, wcol, wcol, 0.f);
        } else if (i == 1) {
            // Memory card / Save cartridge notch silhouette
            Color4 wcol{1.f, 1.f, 1.f, 0.92f};
            drawRoundedRect(iconX + iconSize * 0.24f, iconY + iconSize * 0.20f,
                            iconSize * 0.52f, iconSize * 0.60f, 6.f * uiScale_, wcol, wcol, wcol, 0.f);
            drawRoundedRect(iconX + iconSize * 0.34f, iconY + iconSize * 0.26f,
                            iconSize * 0.32f, iconSize * 0.16f, 2.f * uiScale_, barC, barC, barC, 0.f);
        } else {
            // Settings slider bars motif
            Color4 wcol{1.f, 1.f, 1.f, 0.92f};
            for (int r0 = 0; r0 < 3; r0++) {
                float sy = iconY + iconSize * (0.28f + r0 * 0.19f);
                drawRoundedRect(iconX + iconSize * 0.22f, sy, iconSize * 0.56f, 5.f * uiScale_,
                                2.5f * uiScale_, wcol, wcol, wcol, 0.f);
            }
        }

        if (portrait) {
            float tx = iconX + iconSize + 20.f * uiScale_;
            float tw = dx + dw - tx - 18.f * uiScale_;
            drawText(titles[static_cast<size_t>(i)], tx, dy + dh * 0.28f, tw, 28.f * uiScale_, pal.textPrimary, 0, true);
            drawText(subs[static_cast<size_t>(i)], tx, dy + dh * 0.58f, tw, 17.f * uiScale_, pal.textSecondary, 0, false);
        } else {
            float textY = dy + dh * 0.66f;
            drawText(titles[static_cast<size_t>(i)], dx + 16.f * uiScale_, textY,
                     dw - 32.f * uiScale_, 27.f * uiScale_, pal.textPrimary, 1, true);
            drawText(subs[static_cast<size_t>(i)], dx + 14.f * uiScale_, textY + 38.f * uiScale_,
                     dw - 28.f * uiScale_, 16.f * uiScale_, pal.textSecondary, 1, false);
        }

        hitTargets_.push_back({{dx, dy, dw, dh}, HIT_HUB_CARD, i, 0});
    }
}

void NintendoRenderer::renderGames() {
    const auto pal = palette();
    renderHeader(str("nintendoui_menu_games", "Games"), true);

    float padX = 28.f * uiScale_;
    float tabY = stageY_ + 88.f * uiScale_;
    float tabH = 42.f * uiScale_;

    if (gameTabs_.empty()) {
        drawText(str("nintendoui_empty_games", "No games found in configured folders."),
                 stageX_ + padX, stageY_ + stageH_ * 0.44f, stageW_ - padX * 2.f,
                 22.f * uiScale_, pal.textSecondary, 1, false);
        return;
    }

    // Horizontal tab strip (Recent first when available + per-console tabs)
    float curX = stageX_ + padX;
    float maxTabEnd = stageX_ + stageW_ - padX;
    for (int i = 0; i < static_cast<int>(gameTabs_.size()); i++) {
        const auto& tab = gameTabs_[static_cast<size_t>(i)];
        bool active = (i == selectedGameTab_);
        std::string label = tab.label + " (" + std::to_string(tab.gameIndices.size()) + ")";
        float pillW = clampf((static_cast<float>(label.size()) * 11.5f + 34.f) * uiScale_,
                             96.f * uiScale_, 220.f * uiScale_);
        if (curX + pillW > maxTabEnd) break;

        Color4 bgTop = active ? pal.accentA : pal.tabBg;
        Color4 bgBot = active ? Color4{pal.accentA.r * 0.85f, pal.accentA.g * 0.85f, pal.accentA.b * 0.85f, 1.f} : pal.tabBg;
        Color4 brd = active ? pal.borderFocus : pal.borderNormal;
        Color4 txt = active ? pal.textOnAccent : pal.textPrimary;

        drawRoundedRect(curX, tabY, pillW, tabH, 14.f * uiScale_, bgTop, bgBot, brd,
                        active ? 2.f * uiScale_ : 1.f * uiScale_);
        drawText(label, curX + 8.f * uiScale_, tabY + 9.f * uiScale_, pillW - 16.f * uiScale_,
                 17.f * uiScale_, txt, 1, active);

        hitTargets_.push_back({{curX, tabY, pillW, tabH}, HIT_GAME_TAB, i, 0});
        curX += pillW + 10.f * uiScale_;
    }

    const auto& curTab = gameTabs_[static_cast<size_t>(std::clamp(selectedGameTab_, 0, static_cast<int>(gameTabs_.size()) - 1))];
    float gridX = stageX_ + padX;
    float gridY = tabY + tabH + 16.f * uiScale_;
    float gridW = stageW_ - padX * 2.f;
    float gridH = (stageY_ + stageH_ - 64.f * uiScale_) - gridY;

    int cols = std::max(1, gameCols_);
    float gap = 16.f * uiScale_;
    float cardW = (gridW - gap * (cols - 1)) / static_cast<float>(cols);
    float coverH = cardW * 0.82f;
    float cardH = coverH + 62.f * uiScale_;

    glEnable(GL_SCISSOR_TEST);
    glScissor(static_cast<GLint>(stageX_), static_cast<GLint>(screenH_ - (gridY + gridH)),
              static_cast<GLsizei>(stageW_), static_cast<GLsizei>(std::max(1.f, gridH)));

    for (int idx = 0; idx < static_cast<int>(curTab.gameIndices.size()); idx++) {
        int row = idx / cols;
        int col = idx % cols;
        float cx = gridX + col * (cardW + gap);
        float cy = gridY + row * (cardH + gap) - gameScrollY_;
        if (cy + cardH < gridY - 20.f || cy > gridY + gridH + 20.f) continue;

        const auto& g = games_[static_cast<size_t>(curTab.gameIndices[static_cast<size_t>(idx)])];
        bool focused = (idx == gameFocus_);

        float s = focused ? 1.035f : 1.0f;
        float dw = cardW * s;
        float dh = cardH * s;
        float dx = cx - (dw - cardW) * 0.5f;
        float dy = cy - (dh - cardH) * 0.5f;

        drawRoundedRect(dx, dy, dw, dh, 16.f * uiScale_,
                        focused ? pal.cardSelectedBgTop : pal.cardBgTop,
                        focused ? pal.cardSelectedBgBottom : pal.cardBgBottom,
                        focused ? pal.borderFocus : pal.borderNormal,
                        focused ? 3.f * uiScale_ : 1.2f * uiScale_,
                        focused ? 0.5f : 0.f);

        float artPad = 8.f * uiScale_;
        float artW = dw - artPad * 2.f;
        float artH = coverH - artPad;
        GLuint coverTex = acquireCoverTexture(g.coverKey, g.coverPath);
        if (coverTex) {
            drawTexturedRect(dx + artPad, dy + artPad, artW, artH, coverTex, {1.f, 1.f, 1.f, 1.f},
                             11.f * uiScale_, true);
        } else {
            Color4 c = consoleBadgeColor(g.consoleId);
            drawRoundedRect(dx + artPad, dy + artPad, artW, artH, 11.f * uiScale_,
                            {c.r * 0.42f, c.g * 0.42f, c.b * 0.42f, 1.f},
                            {c.r * 0.20f, c.g * 0.20f, c.b * 0.20f, 1.f},
                            {1.f, 1.f, 1.f, 0.14f}, 1.f * uiScale_);
            drawConsoleBadge(dx + artPad + (artW - 88.f * uiScale_) * 0.5f,
                             dy + artPad + (artH - 32.f * uiScale_) * 0.5f,
                             88.f * uiScale_, 32.f * uiScale_, g.consoleId, g.consoleShort);
        }

        // Console pill corner overlay
        drawConsoleBadge(dx + artPad + 6.f * uiScale_, dy + artPad + 6.f * uiScale_,
                         64.f * uiScale_, 23.f * uiScale_, g.consoleId, g.consoleShort);

        drawText(g.title, dx + 10.f * uiScale_, dy + coverH + 6.f * uiScale_,
                 dw - 20.f * uiScale_, 17.f * uiScale_, pal.textPrimary, 0, focused);

        // Clamp hit target inside visible scissor bounds
        float visibleTop = std::max(dy, gridY);
        float visibleBot = std::min(dy + dh, gridY + gridH);
        if (visibleBot > visibleTop) {
            hitTargets_.push_back({{dx, visibleTop, dw, visibleBot - visibleTop}, HIT_GAME_CARD, idx, 0});
        }
    }

    glDisable(GL_SCISSOR_TEST);
}

void NintendoRenderer::renderSaves() {
    const auto pal = palette();
    renderHeader(str("nintendoui_menu_saves", "Save Data"), true);

    float padX = 28.f * uiScale_;
    float tabY = stageY_ + 88.f * uiScale_;
    float tabH = 42.f * uiScale_;
    float tabW = std::min(280.f * uiScale_, (stageW_ - padX * 2.f - 12.f * uiScale_) * 0.5f);

    std::array<std::string, 2> tabLabels = {
        str("nintendoui_saves_tab_quick", "Quick Saves") + " (" + std::to_string(quickSaves_.size()) + ")",
        str("nintendoui_saves_tab_memory", "Memory Cards") + " (" + std::to_string(memorySaves_.size()) + ")"
    };
    for (int i = 0; i < 2; i++) {
        bool active = (savesSubTab_ == i);
        float tx = stageX_ + padX + i * (tabW + 12.f * uiScale_);
        drawRoundedRect(tx, tabY, tabW, tabH, 14.f * uiScale_,
                        active ? pal.accentA : pal.tabBg,
                        active ? pal.accentA : pal.tabBg,
                        active ? pal.borderFocus : pal.borderNormal,
                        active ? 2.f * uiScale_ : 1.f * uiScale_);
        drawText(tabLabels[static_cast<size_t>(i)], tx + 10.f * uiScale_, tabY + 9.f * uiScale_,
                 tabW - 20.f * uiScale_, 18.f * uiScale_, active ? pal.textOnAccent : pal.textPrimary, 1, active);
        hitTargets_.push_back({{tx, tabY, tabW, tabH}, HIT_SAVE_SUBTAB, i, 0});
    }

    float listX = stageX_ + padX;
    float listY = tabY + tabH + 16.f * uiScale_;
    float listW = stageW_ - padX * 2.f;
    float listH = (stageY_ + stageH_ - 64.f * uiScale_) - listY;

    int count = savesSubTab_ == 0 ? static_cast<int>(quickSaves_.size()) : static_cast<int>(memorySaves_.size());
    if (count == 0) {
        drawText(savesSubTab_ == 0
                     ? str("nintendoui_empty_quicksaves", "No quick save states yet.")
                     : str("nintendoui_empty_memsaves", "No memory cards or battery saves found."),
                 listX, listY + listH * 0.40f, listW, 21.f * uiScale_, pal.textSecondary, 1, false);
        return;
    }

    float rowH = 96.f * uiScale_;
    float gap = 12.f * uiScale_;

    glEnable(GL_SCISSOR_TEST);
    glScissor(static_cast<GLint>(stageX_), static_cast<GLint>(screenH_ - (listY + listH)),
              static_cast<GLsizei>(stageW_), static_cast<GLsizei>(std::max(1.f, listH)));

    for (int i = 0; i < count; i++) {
        float ry = listY + i * (rowH + gap) - saveScrollY_;
        if (ry + rowH < listY - 12.f || ry > listY + listH + 12.f) continue;

        bool focused = (i == saveFocus_);
        drawRoundedRect(listX, ry, listW, rowH, 16.f * uiScale_,
                        focused ? pal.cardSelectedBgTop : pal.cardBgTop,
                        focused ? pal.cardSelectedBgBottom : pal.cardBgBottom,
                        focused ? pal.borderFocus : pal.borderNormal,
                        focused ? 2.5f * uiScale_ : 1.2f * uiScale_,
                        focused ? 0.4f : 0.f);

        float thumbW = 112.f * uiScale_;
        float thumbH = rowH - 16.f * uiScale_;
        float thumbX = listX + 10.f * uiScale_;
        float thumbY = ry + 8.f * uiScale_;

        if (savesSubTab_ == 0) {
            const auto& qs = quickSaves_[static_cast<size_t>(i)];
            GLuint tex = acquireCoverTexture("qs:" + qs.id, qs.previewPath);
            if (tex) {
                drawTexturedRect(thumbX, thumbY, thumbW, thumbH, tex, {1.f, 1.f, 1.f, 1.f}, 10.f * uiScale_, true);
            } else {
                drawConsoleBadge(thumbX + 12.f * uiScale_, thumbY + (thumbH - 32.f * uiScale_) * 0.5f,
                                 88.f * uiScale_, 32.f * uiScale_, qs.consoleId, qs.consoleShort);
            }

            float btnW = clampf(108.f * uiScale_, 88.f * uiScale_, listW * 0.16f);
            float btnH = 38.f * uiScale_;
            float delX = listX + listW - btnW - 12.f * uiScale_;
            float actX = delX - btnW - 10.f * uiScale_;
            float btnY = ry + (rowH - btnH) * 0.5f;

            float textX = thumbX + thumbW + 14.f * uiScale_;
            float textW = std::max(80.f, actX - textX - 10.f * uiScale_);
            drawText(qs.gameTitle, textX, ry + 14.f * uiScale_, textW, 20.f * uiScale_, pal.textPrimary, 0, true);
            drawText(qs.consoleShort + " • " + qs.slotLabel + " • " + qs.sizeText + " • " + qs.timestampText,
                     textX, ry + 50.f * uiScale_, textW, 15.f * uiScale_, pal.textSecondary, 0, false);

            drawRoundedRect(actX, btnY, btnW, btnH, 10.f * uiScale_, pal.accentA, pal.accentA, pal.borderFocus, 1.f * uiScale_);
            drawText(str("nintendoui_action_load", "Load"), actX, btnY + 8.f * uiScale_, btnW, 17.f * uiScale_,
                     pal.textOnAccent, 1, true);

            bool confirmDel = (pendingDeleteSaveIdx_ == i);
            Color4 delCol = confirmDel ? rgbaHex(0xD9303E) : pal.tabBg;
            drawRoundedRect(delX, btnY, btnW, btnH, 10.f * uiScale_, delCol, delCol,
                            confirmDel ? rgbaHex(0xFF8A93) : pal.borderNormal, 1.2f * uiScale_);
            drawText(str("nintendoui_action_delete", "Delete"), delX, btnY + 8.f * uiScale_, btnW, 17.f * uiScale_,
                     confirmDel ? Color4{1.f, 1.f, 1.f, 1.f} : pal.textPrimary, 1, true);

            if (ry >= listY - 8.f && ry + rowH <= listY + listH + 8.f) {
                hitTargets_.push_back({{actX, btnY, btnW, btnH}, HIT_SAVE_PRIMARY, i, 0});
                hitTargets_.push_back({{delX, btnY, btnW, btnH}, HIT_SAVE_DELETE, i, 0});
            }
        } else {
            const auto& ms = memorySaves_[static_cast<size_t>(i)];
            drawConsoleBadge(thumbX + 10.f * uiScale_, thumbY + (thumbH - 34.f * uiScale_) * 0.5f,
                             92.f * uiScale_, 34.f * uiScale_, ms.consoleId, ms.consoleShort);

            float btnW = clampf(114.f * uiScale_, 92.f * uiScale_, listW * 0.17f);
            float btnH = 38.f * uiScale_;
            float delX = listX + listW - btnW - 12.f * uiScale_;
            float actX = delX - btnW - 10.f * uiScale_;
            float btnY = ry + (rowH - btnH) * 0.5f;

            float textX = thumbX + thumbW + 14.f * uiScale_;
            float textW = std::max(80.f, actX - textX - 10.f * uiScale_);
            drawText(ms.title, textX, ry + 14.f * uiScale_, textW, 20.f * uiScale_, pal.textPrimary, 0, true);
            drawText(ms.consoleShort + " • " + ms.sizeText + " • " + ms.timestampText,
                     textX, ry + 50.f * uiScale_, textW, 15.f * uiScale_, pal.textSecondary, 0, false);

            drawRoundedRect(actX, btnY, btnW, btnH, 10.f * uiScale_, pal.accentA, pal.accentA, pal.borderFocus, 1.f * uiScale_);
            drawText(str("nintendoui_action_backup", "Backup"), actX, btnY + 8.f * uiScale_, btnW, 17.f * uiScale_,
                     pal.textOnAccent, 1, true);

            bool confirmDel = (pendingDeleteSaveIdx_ == i);
            Color4 delCol = confirmDel ? rgbaHex(0xD9303E) : pal.tabBg;
            drawRoundedRect(delX, btnY, btnW, btnH, 10.f * uiScale_, delCol, delCol,
                            confirmDel ? rgbaHex(0xFF8A93) : pal.borderNormal, 1.2f * uiScale_);
            drawText(str("nintendoui_action_delete", "Delete"), delX, btnY + 8.f * uiScale_, btnW, 17.f * uiScale_,
                     confirmDel ? Color4{1.f, 1.f, 1.f, 1.f} : pal.textPrimary, 1, true);

            if (ry >= listY - 8.f && ry + rowH <= listY + listH + 8.f) {
                hitTargets_.push_back({{actX, btnY, btnW, btnH}, HIT_SAVE_PRIMARY, i, 0});
                hitTargets_.push_back({{delX, btnY, btnW, btnH}, HIT_SAVE_DELETE, i, 0});
            }
        }
    }

    glDisable(GL_SCISSOR_TEST);
}

void NintendoRenderer::renderSettings() {
    const auto pal = palette();
    renderHeader(str("nintendoui_menu_settings", "Settings"), true);

    float padX = 28.f * uiScale_;
    float listX = stageX_ + padX;
    float listY = stageY_ + 96.f * uiScale_;
    float listW = stageW_ - padX * 2.f;
    float rowH = 74.f * uiScale_;
    float gap = 12.f * uiScale_;

    std::string themeName;
    switch (theme_) {
        case ThemeId::WiiPearl: themeName = str("nintendoui_theme_wii", "Wii Pearl"); break;
        case ThemeId::GameCubeIndigo: themeName = str("nintendoui_theme_gc", "GameCube Indigo"); break;
        case ThemeId::FamicomCrimson: themeName = str("nintendoui_theme_famicom", "Famicom Crimson"); break;
        case ThemeId::GameBoyClassic: themeName = str("nintendoui_theme_gb", "Game Boy Classic"); break;
        default: themeName = str("nintendoui_theme_switch", "Switch Midnight"); break;
    }

    std::string aspectName;
    switch (aspectMode_) {
        case AspectMode::Ratio16x9: aspectName = "16:9"; break;
        case AspectMode::Ratio16x10: aspectName = "16:10"; break;
        case AspectMode::Ratio4x3: aspectName = "4:3"; break;
        default: aspectName = str("nintendoui_aspect_auto", "Auto"); break;
    }

    std::array<std::pair<std::string, std::string>, 5> rows = {{
        {str("nintendoui_setting_theme", "Nintendo Theme"), themeName},
        {str("nintendoui_setting_aspect", "Screen Aspect"), aspectName},
        {str("nintendoui_setting_eco", "Eco Performance Mode"),
         ecoMode_ ? str("nintendoui_state_on", "On") : str("nintendoui_state_off", "Off")},
        {str("nintendoui_setting_sfx", "UI Sound Effects"),
         sfxEnabled_ ? str("nintendoui_state_on", "On") : str("nintendoui_state_off", "Off")},
        {str("nintendoui_setting_standard_ui", "Switch to Standard View"),
         str("nintendoui_action_open", "Open")},
    }};

    for (int i = 0; i < 5; i++) {
        float ry = listY + i * (rowH + gap);
        bool focused = (settingFocus_ == i);
        drawRoundedRect(listX, ry, listW, rowH, 16.f * uiScale_,
                        focused ? pal.cardSelectedBgTop : pal.cardBgTop,
                        focused ? pal.cardSelectedBgBottom : pal.cardBgBottom,
                        focused ? pal.borderFocus : pal.borderNormal,
                        focused ? 2.5f * uiScale_ : 1.2f * uiScale_,
                        focused ? 0.35f : 0.f);

        drawText(rows[static_cast<size_t>(i)].first, listX + 20.f * uiScale_, ry + 21.f * uiScale_,
                 listW * 0.56f, 21.f * uiScale_, pal.textPrimary, 0, focused);

        float pillW = std::min(240.f * uiScale_, listW * 0.36f);
        float pillH = 40.f * uiScale_;
        float px = listX + listW - pillW - 18.f * uiScale_;
        float py = ry + (rowH - pillH) * 0.5f;
        drawRoundedRect(px, py, pillW, pillH, 12.f * uiScale_,
                        focused ? pal.accentA : pal.tabBg,
                        focused ? pal.accentA : pal.tabBg,
                        pal.borderFocus, 1.2f * uiScale_);
        drawText(rows[static_cast<size_t>(i)].second, px + 8.f * uiScale_, py + 8.f * uiScale_,
                 pillW - 16.f * uiScale_, 18.f * uiScale_,
                 focused ? pal.textOnAccent : pal.textPrimary, 1, true);

        hitTargets_.push_back({{listX, ry, listW, rowH}, HIT_SETTING_ROW, i, 0});
    }
}

void NintendoRenderer::renderFooter() {
    const auto pal = palette();
    float padX = 28.f * uiScale_;
    float fy = stageY_ + stageH_ - 52.f * uiScale_;
    float fh = 38.f * uiScale_;

    float pillW = std::min(190.f * uiScale_, stageW_ * 0.30f);
    drawRoundedRect(stageX_ + padX, fy, pillW, fh, 12.f * uiScale_,
                    pal.tabBg, pal.tabBg, pal.borderNormal, 1.f * uiScale_);
    drawText(str("nintendoui_setting_theme", "Theme"), stageX_ + padX + 8.f * uiScale_,
             fy + 8.f * uiScale_, pillW - 16.f * uiScale_, 16.f * uiScale_, pal.textPrimary, 1, true);
    hitTargets_.push_back({{stageX_ + padX, fy, pillW, fh}, HIT_FOOTER_THEME, 0, 0});

    float stdW = std::min(230.f * uiScale_, stageW_ * 0.36f);
    float stdX = stageX_ + stageW_ - padX - stdW;
    drawRoundedRect(stdX, fy, stdW, fh, 12.f * uiScale_,
                    pal.tabBg, pal.tabBg, pal.borderNormal, 1.f * uiScale_);
    drawText(str("nintendoui_setting_standard_ui", "Standard View"), stdX + 8.f * uiScale_,
             fy + 8.f * uiScale_, stdW - 16.f * uiScale_, 16.f * uiScale_, pal.textPrimary, 1, true);
    hitTargets_.push_back({{stdX, fy, stdW, fh}, HIT_FOOTER_STANDARD, 0, 0});
}

void NintendoRenderer::renderToast() {
    if (toastTimer_ <= 0.f || toastText_.empty()) return;
    const auto pal = palette();
    float alpha = clampf(toastTimer_ * 2.f, 0.f, 1.f);
    float tw = std::min(460.f * uiScale_, stageW_ * 0.72f);
    float th = 48.f * uiScale_;
    float tx = stageX_ + (stageW_ - tw) * 0.5f;
    float ty = stageY_ + stageH_ - 112.f * uiScale_;
    Color4 bg = pal.toastBg;
    bg.a *= alpha;
    drawRoundedRect(tx, ty, tw, th, 16.f * uiScale_, bg, bg,
                    {pal.accentA.r, pal.accentA.g, pal.accentA.b, alpha}, 1.5f * uiScale_);
    drawText(toastText_, tx + 14.f * uiScale_, ty + 12.f * uiScale_, tw - 28.f * uiScale_,
             18.f * uiScale_, {1.f, 1.f, 1.f, alpha}, 1, true);
}

void NintendoRenderer::onDrawFrame(float dtSeconds) {
    std::lock_guard lock(dataMutex_);
    float dt = clampf(dtSeconds, 0.001f, 0.05f);
    time_ += dt;
    frameIndex_++;
    focusPulse_ += dt * (ecoMode_ ? 2.0f : 4.2f);
    screenFade_ = clampf(screenFade_ + dt * 5.0f, 0.f, 1.f);
    if (toastTimer_ > 0.f) toastTimer_ = std::max(0.f, toastTimer_ - dt);
    if (pendingDeleteTimer_ > 0.f) {
        pendingDeleteTimer_ = std::max(0.f, pendingDeleteTimer_ - dt);
        if (pendingDeleteTimer_ == 0.f) pendingDeleteSaveIdx_ = -1;
    }

    // Upload budgeted cover/thumbnail textures on GL thread
    int uploadLimit = ecoMode_ ? 1 : 2;
    while (!pendingUploads_.empty() && uploadLimit-- > 0) {
        auto item = std::move(pendingUploads_.front());
        pendingUploads_.erase(pendingUploads_.begin());
        const std::string& key = item.first;
        int w = std::get<0>(item.second);
        int h = std::get<1>(item.second);
        const auto& rgba = std::get<2>(item.second);
        if (w > 0 && h > 0 && rgba.size() == static_cast<size_t>(w) * h * 4) {
            auto& texEntry = imageTextures_[key];
            if (texEntry.texId) glDeleteTextures(1, &texEntry.texId);
            glGenTextures(1, &texEntry.texId);
            glBindTexture(GL_TEXTURE_2D, texEntry.texId);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, rgba.data());
            texEntry.width = w;
            texEntry.height = h;
            texEntry.pending = false;
            texEntry.failed = false;
            texEntry.lastUsedFrame = frameIndex_;
        }
    }

    evictUnusedTexturesIfNeeded();

    gameScrollY_ = lerpf(gameScrollY_, targetGameScrollY_, dt * 14.f);
    saveScrollY_ = lerpf(saveScrollY_, targetSaveScrollY_, dt * 14.f);

    if (screen_ == Screen::Intro) {
        if (!bootSfxPlayed_) {
            bootSfxPlayed_ = true;
            triggerSfx(SfxType::Boot);
        }
        introElapsed_ += dt;
        float dur = ecoMode_ ? 1.25f : 1.85f;
        if (introElapsed_ >= dur) {
            screen_ = Screen::Hub;
            screenFade_ = 0.f;
        }
    }

    hitTargets_.clear();
    glClearColor(0.04f, 0.05f, 0.08f, 1.f);
    glClear(GL_COLOR_BUFFER_BIT);
    drawBackdrop();

    switch (screen_) {
        case Screen::Intro: renderIntro(); break;
        case Screen::Hub: renderHub(); renderFooter(); break;
        case Screen::Games: renderGames(); renderFooter(); break;
        case Screen::Saves: renderSaves(); renderFooter(); break;
        case Screen::Settings: renderSettings(); renderFooter(); break;
    }

    renderToast();
}

void NintendoRenderer::activateHubCard(int idx) {
    triggerSfx(SfxType::Confirm);
    if (idx == 0) {
        openGamesScreenDefault();
    } else if (idx == 1) {
        screen_ = Screen::Saves;
        screenFade_ = 0.f;
        saveFocus_ = 0;
        saveScrollY_ = targetSaveScrollY_ = 0.f;
    } else if (idx == 2) {
        screen_ = Screen::Settings;
        screenFade_ = 0.f;
        settingFocus_ = 0;
    }
}

void NintendoRenderer::activateGameSelection() {
    if (gameTabs_.empty()) return;
    int tIdx = std::clamp(selectedGameTab_, 0, static_cast<int>(gameTabs_.size()) - 1);
    const auto& tab = gameTabs_[static_cast<size_t>(tIdx)];
    if (tab.gameIndices.empty()) return;
    int fIdx = std::clamp(gameFocus_, 0, static_cast<int>(tab.gameIndices.size()) - 1);
    const auto& g = games_[static_cast<size_t>(tab.gameIndices[static_cast<size_t>(fIdx)])];
    triggerSfx(SfxType::Launch);
    HostAction a;
    a.type = HostActionType::LaunchGame;
    a.arg1 = g.path;
    a.arg2 = g.title;
    queueAction(std::move(a));
}

void NintendoRenderer::activateSaveSelection() {
    if (savesSubTab_ == 0) {
        if (quickSaves_.empty()) return;
        int idx = std::clamp(saveFocus_, 0, static_cast<int>(quickSaves_.size()) - 1);
        const auto& qs = quickSaves_[static_cast<size_t>(idx)];
        triggerSfx(SfxType::Launch);
        HostAction a;
        a.type = HostActionType::LoadSaveState;
        a.arg1 = qs.gamePath;
        a.arg2 = qs.id;
        a.intArg1 = qs.slot;
        queueAction(std::move(a));
    } else {
        if (memorySaves_.empty()) return;
        int idx = std::clamp(saveFocus_, 0, static_cast<int>(memorySaves_.size()) - 1);
        const auto& ms = memorySaves_[static_cast<size_t>(idx)];
        triggerSfx(SfxType::Confirm);
        HostAction a;
        a.type = HostActionType::BackupMemorySave;
        a.arg1 = ms.filePath;
        a.arg2 = ms.title;
        queueAction(std::move(a));
    }
}

void NintendoRenderer::activateSettingRow(int row, int delta) {
    triggerSfx(SfxType::Confirm);
    if (row == 0) {
        int next = (static_cast<int>(theme_) + delta + static_cast<int>(ThemeId::Count)) %
                   static_cast<int>(ThemeId::Count);
        theme_ = static_cast<ThemeId>(next);
    } else if (row == 1) {
        int next = (static_cast<int>(aspectMode_) + delta + static_cast<int>(AspectMode::Count)) %
                   static_cast<int>(AspectMode::Count);
        aspectMode_ = static_cast<AspectMode>(next);
        computeStageViewport();
    } else if (row == 2) {
        ecoMode_ = !ecoMode_;
    } else if (row == 3) {
        sfxEnabled_ = !sfxEnabled_;
    } else if (row == 4) {
        HostAction a;
        a.type = HostActionType::SwitchToStandardUi;
        queueAction(std::move(a));
        return;
    }

    HostAction p;
    p.type = HostActionType::PersistSettings;
    p.intArg1 = static_cast<int>(theme_);
    p.intArg2 = (static_cast<int>(aspectMode_) & 0xF) | (ecoMode_ ? 0x10 : 0) | (sfxEnabled_ ? 0x20 : 0);
    queueAction(std::move(p));
}

void NintendoRenderer::ensureGameFocusVisible() {
    float padX = 28.f * uiScale_;
    float gridW = stageW_ - padX * 2.f;
    float gridY = stageY_ + 88.f * uiScale_ + 42.f * uiScale_ + 16.f * uiScale_;
    float gridH = (stageY_ + stageH_ - 64.f * uiScale_) - gridY;
    int cols = std::max(1, gameCols_);
    float gap = 16.f * uiScale_;
    float cardW = (gridW - gap * (cols - 1)) / static_cast<float>(cols);
    float cardH = cardW * 0.82f + 62.f * uiScale_;
    int row = gameFocus_ / cols;
    float top = row * (cardH + gap);
    float bot = top + cardH;
    if (top < targetGameScrollY_) targetGameScrollY_ = top;
    if (bot > targetGameScrollY_ + gridH) targetGameScrollY_ = std::max(0.f, bot - gridH);
}

void NintendoRenderer::ensureSaveFocusVisible() {
    float listY = stageY_ + 88.f * uiScale_ + 42.f * uiScale_ + 16.f * uiScale_;
    float listH = (stageY_ + stageH_ - 64.f * uiScale_) - listY;
    float rowH = 96.f * uiScale_;
    float gap = 12.f * uiScale_;
    float top = saveFocus_ * (rowH + gap);
    float bot = top + rowH;
    if (top < targetSaveScrollY_) targetSaveScrollY_ = top;
    if (bot > targetSaveScrollY_ + listH) targetSaveScrollY_ = std::max(0.f, bot - listH);
}

void NintendoRenderer::handleTap(float x, float y) {
    // Reverse order so top/overlay controls win hit tests
    for (int i = static_cast<int>(hitTargets_.size()) - 1; i >= 0; i--) {
        const auto& h = hitTargets_[static_cast<size_t>(i)];
        if (!h.rect.contains(x, y)) continue;
        switch (h.actionCode) {
            case HIT_SKIP_INTRO:
                screen_ = Screen::Hub;
                screenFade_ = 0.f;
                triggerSfx(SfxType::Confirm);
                return;
            case HIT_HUB_CARD:
                hubFocus_ = h.param0;
                activateHubCard(h.param0);
                return;
            case HIT_BACK: {
                bool dummy = false;
                onBackPressed(dummy);
                return;
            }
            case HIT_GAME_TAB:
                selectedGameTab_ = h.param0;
                gameFocus_ = 0;
                gameScrollY_ = targetGameScrollY_ = 0.f;
                triggerSfx(SfxType::TabSwitch);
                return;
            case HIT_GAME_CARD:
                gameFocus_ = h.param0;
                activateGameSelection();
                return;
            case HIT_SAVE_SUBTAB:
                savesSubTab_ = h.param0;
                saveFocus_ = 0;
                saveScrollY_ = targetSaveScrollY_ = 0.f;
                pendingDeleteSaveIdx_ = -1;
                triggerSfx(SfxType::TabSwitch);
                return;
            case HIT_SAVE_PRIMARY:
                saveFocus_ = h.param0;
                pendingDeleteSaveIdx_ = -1;
                activateSaveSelection();
                return;
            case HIT_SAVE_DELETE:
                saveFocus_ = h.param0;
                if (pendingDeleteSaveIdx_ == h.param0 && pendingDeleteTimer_ > 0.f) {
                    pendingDeleteSaveIdx_ = -1;
                    pendingDeleteTimer_ = 0.f;
                    triggerSfx(SfxType::Back);
                    HostAction a;
                    if (savesSubTab_ == 0 && h.param0 < static_cast<int>(quickSaves_.size())) {
                        a.type = HostActionType::DeleteSaveState;
                        a.arg1 = quickSaves_[static_cast<size_t>(h.param0)].id;
                    } else if (savesSubTab_ == 1 && h.param0 < static_cast<int>(memorySaves_.size())) {
                        a.type = HostActionType::DeleteMemorySave;
                        a.arg1 = memorySaves_[static_cast<size_t>(h.param0)].filePath;
                    }
                    if (a.type != HostActionType::None) queueAction(std::move(a));
                } else {
                    pendingDeleteSaveIdx_ = h.param0;
                    pendingDeleteTimer_ = 3.5f;
                    toastText_ = str("nintendoui_confirm_delete", "Tap Delete again to confirm");
                    toastTimer_ = 2.6f;
                    triggerSfx(SfxType::Move);
                }
                return;
            case HIT_SETTING_ROW:
                settingFocus_ = h.param0;
                activateSettingRow(h.param0, 1);
                return;
            case HIT_FOOTER_THEME:
                activateSettingRow(0, 1);
                return;
            case HIT_FOOTER_STANDARD:
                activateSettingRow(4, 1);
                return;
        }
    }
}

void NintendoRenderer::onTouchEvent(int action, float x, float y) {
    std::lock_guard lock(dataMutex_);
    if (action == kTouchDown) {
        touchDown_ = true;
        touchDragged_ = false;
        touchStartX_ = x;
        touchStartY_ = y;
        touchLastY_ = y;
    } else if (action == kTouchMove && touchDown_) {
        float dy = y - touchLastY_;
        float dist = std::hypot(x - touchStartX_, y - touchStartY_);
        if (dist > 14.f * uiScale_) touchDragged_ = true;
        if (touchDragged_) {
            if (screen_ == Screen::Games && !gameTabs_.empty()) {
                int cols = std::max(1, gameCols_);
                const auto& curTab = gameTabs_[static_cast<size_t>(selectedGameTab_)];
                int rows = (static_cast<int>(curTab.gameIndices.size()) + cols - 1) / cols;
                float gridW = stageW_ - 56.f * uiScale_;
                float cardW = (gridW - 16.f * uiScale_ * (cols - 1)) / cols;
                float rowStep = cardW * 0.82f + 78.f * uiScale_;
                float maxScroll = std::max(0.f, rows * rowStep - (stageH_ - 220.f * uiScale_));
                targetGameScrollY_ = clampf(targetGameScrollY_ - dy, 0.f, maxScroll);
                gameScrollY_ = targetGameScrollY_;
            } else if (screen_ == Screen::Saves) {
                int count = savesSubTab_ == 0 ? static_cast<int>(quickSaves_.size())
                                              : static_cast<int>(memorySaves_.size());
                float maxScroll = std::max(0.f, count * (108.f * uiScale_) - (stageH_ - 220.f * uiScale_));
                targetSaveScrollY_ = clampf(targetSaveScrollY_ - dy, 0.f, maxScroll);
                saveScrollY_ = targetSaveScrollY_;
            }
        }
        touchLastY_ = y;
    } else if (action == kTouchUp) {
        if (touchDown_ && !touchDragged_) {
            handleTap(x, y);
        }
        touchDown_ = false;
        touchDragged_ = false;
    } else if (action == kTouchCancel) {
        touchDown_ = false;
        touchDragged_ = false;
    }
}

void NintendoRenderer::onBackPressed(bool& handled) {
    handled = false;
    if (screen_ == Screen::Intro) {
        screen_ = Screen::Hub;
        screenFade_ = 0.f;
        triggerSfx(SfxType::Confirm);
        handled = true;
    } else if (screen_ != Screen::Hub) {
        screen_ = Screen::Hub;
        screenFade_ = 0.f;
        pendingDeleteSaveIdx_ = -1;
        triggerSfx(SfxType::Back);
        handled = true;
    }
}

bool NintendoRenderer::onKeyEvent(int keyCode, bool down) {
    std::lock_guard lock(dataMutex_);
    if (!down) return false;

    if (screen_ == Screen::Intro) {
        screen_ = Screen::Hub;
        screenFade_ = 0.f;
        triggerSfx(SfxType::Confirm);
        return true;
    }

    const bool isUp = (keyCode == AKEYCODE_DPAD_UP);
    const bool isDown = (keyCode == AKEYCODE_DPAD_DOWN);
    const bool isLeft = (keyCode == AKEYCODE_DPAD_LEFT);
    const bool isRight = (keyCode == AKEYCODE_DPAD_RIGHT);
    const bool isConfirm = (keyCode == AKEYCODE_BUTTON_A || keyCode == AKEYCODE_DPAD_CENTER ||
                            keyCode == AKEYCODE_ENTER);
    const bool isBack = (keyCode == AKEYCODE_BUTTON_B || keyCode == AKEYCODE_BACK ||
                         keyCode == AKEYCODE_ESCAPE);
    const bool isL1 = (keyCode == AKEYCODE_BUTTON_L1);
    const bool isR1 = (keyCode == AKEYCODE_BUTTON_R1);
    const bool isX = (keyCode == AKEYCODE_BUTTON_X);

    if (isBack) {
        bool handled = false;
        onBackPressed(handled);
        return handled;
    }

    if (screen_ == Screen::Hub) {
        if (isLeft || isUp) {
            hubFocus_ = (hubFocus_ + 2) % 3;
            triggerSfx(SfxType::Move);
            return true;
        }
        if (isRight || isDown) {
            hubFocus_ = (hubFocus_ + 1) % 3;
            triggerSfx(SfxType::Move);
            return true;
        }
        if (isConfirm) {
            activateHubCard(hubFocus_);
            return true;
        }
        return false;
    }

    if (screen_ == Screen::Games && !gameTabs_.empty()) {
        if (isL1 || isR1) {
            int n = static_cast<int>(gameTabs_.size());
            selectedGameTab_ = (selectedGameTab_ + (isR1 ? 1 : (n - 1))) % n;
            gameFocus_ = 0;
            gameScrollY_ = targetGameScrollY_ = 0.f;
            triggerSfx(SfxType::TabSwitch);
            return true;
        }
        const auto& tab = gameTabs_[static_cast<size_t>(selectedGameTab_)];
        int total = static_cast<int>(tab.gameIndices.size());
        if (total == 0) return false;
        int cols = std::max(1, gameCols_);
        if (isLeft) { gameFocus_ = (gameFocus_ + total - 1) % total; ensureGameFocusVisible(); triggerSfx(SfxType::Move); return true; }
        if (isRight) { gameFocus_ = (gameFocus_ + 1) % total; ensureGameFocusVisible(); triggerSfx(SfxType::Move); return true; }
        if (isUp) {
            if (gameFocus_ - cols >= 0) {
                gameFocus_ -= cols;
                ensureGameFocusVisible();
                triggerSfx(SfxType::Move);
                return true;
            }
            return true;
        }
        if (isDown) {
            if (gameFocus_ + cols < total) {
                gameFocus_ += cols;
                ensureGameFocusVisible();
                triggerSfx(SfxType::Move);
                return true;
            }
            return true;
        }
        if (isConfirm) {
            activateGameSelection();
            return true;
        }
    }

    if (screen_ == Screen::Saves) {
        if (isL1 || isR1) {
            savesSubTab_ = 1 - savesSubTab_;
            saveFocus_ = 0;
            saveScrollY_ = targetSaveScrollY_ = 0.f;
            pendingDeleteSaveIdx_ = -1;
            triggerSfx(SfxType::TabSwitch);
            return true;
        }
        int total = savesSubTab_ == 0 ? static_cast<int>(quickSaves_.size())
                                      : static_cast<int>(memorySaves_.size());
        if (total == 0) return false;
        if (isUp) { saveFocus_ = (saveFocus_ + total - 1) % total; ensureSaveFocusVisible(); triggerSfx(SfxType::Move); return true; }
        if (isDown) { saveFocus_ = (saveFocus_ + 1) % total; ensureSaveFocusVisible(); triggerSfx(SfxType::Move); return true; }
        if (isConfirm) { activateSaveSelection(); return true; }
        if (isX) {
            if (pendingDeleteSaveIdx_ == saveFocus_ && pendingDeleteTimer_ > 0.f) {
                int idx = saveFocus_;
                pendingDeleteSaveIdx_ = -1;
                pendingDeleteTimer_ = 0.f;
                HostAction a;
                if (savesSubTab_ == 0 && idx < static_cast<int>(quickSaves_.size())) {
                    a.type = HostActionType::DeleteSaveState;
                    a.arg1 = quickSaves_[static_cast<size_t>(idx)].id;
                } else if (savesSubTab_ == 1 && idx < static_cast<int>(memorySaves_.size())) {
                    a.type = HostActionType::DeleteMemorySave;
                    a.arg1 = memorySaves_[static_cast<size_t>(idx)].filePath;
                }
                if (a.type != HostActionType::None) {
                    triggerSfx(SfxType::Back);
                    queueAction(std::move(a));
                }
            } else {
                pendingDeleteSaveIdx_ = saveFocus_;
                pendingDeleteTimer_ = 3.5f;
                toastText_ = str("nintendoui_confirm_delete", "Press again to confirm delete");
                toastTimer_ = 2.6f;
                triggerSfx(SfxType::Move);
            }
            return true;
        }
    }

    if (screen_ == Screen::Settings) {
        if (isUp) { settingFocus_ = (settingFocus_ + 4) % 5; triggerSfx(SfxType::Move); return true; }
        if (isDown) { settingFocus_ = (settingFocus_ + 1) % 5; triggerSfx(SfxType::Move); return true; }
        if (isLeft) { activateSettingRow(settingFocus_, -1); return true; }
        if (isRight || isConfirm) { activateSettingRow(settingFocus_, 1); return true; }
    }

    return false;
}

void NintendoRenderer::onAnalogAxis(float axisX, float axisY) {
    int dirX = axisX > 0.55f ? 1 : (axisX < -0.55f ? -1 : 0);
    int dirY = axisY > 0.55f ? 1 : (axisY < -0.55f ? -1 : 0);

    bool changed = false;
    {
        std::lock_guard lock(dataMutex_);
        if (dirX != lastAnalogDirX_ || dirY != lastAnalogDirY_) {
            lastAnalogDirX_ = dirX;
            lastAnalogDirY_ = dirY;
            changed = true;
        }
    }
    if (!changed) return;
    if (dirX < 0) onKeyEvent(AKEYCODE_DPAD_LEFT, true);
    else if (dirX > 0) onKeyEvent(AKEYCODE_DPAD_RIGHT, true);
    if (dirY < 0) onKeyEvent(AKEYCODE_DPAD_UP, true);
    else if (dirY > 0) onKeyEvent(AKEYCODE_DPAD_DOWN, true);
}

} // namespace nintendoui
