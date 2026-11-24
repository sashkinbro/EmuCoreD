#pragma once

#include <GLES3/gl3.h>
#include <array>
#include <cstdint>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace nintendoui {

enum class Screen : int {
    Intro = 0,
    Hub = 1,
    Games = 2,
    Saves = 3,
    Settings = 4,
};

enum class ThemeId : int {
    WiiPearl = 0,
    GameCubeIndigo = 1,
    FamicomCrimson = 2,
    SwitchMidnight = 3,
    GameBoyClassic = 4,
    Count = 5,
};

enum class AspectMode : int {
    Auto = 0,
    Ratio16x9 = 1,
    Ratio16x10 = 2,
    Ratio4x3 = 3,
    Count = 4,
};

enum class SfxType : int {
    Move = 0,
    Confirm = 1,
    Back = 2,
    TabSwitch = 3,
    Launch = 4,
    Boot = 5,
};

enum class HostActionType : int {
    None = 0,
    LaunchGame = 1,
    LoadSaveState = 2,
    DeleteSaveState = 3,
    BackupMemorySave = 4,
    DeleteMemorySave = 5,
    SwitchToStandardUi = 6,
    PersistSettings = 7,
    PlaySfx = 8,
};

struct HostAction {
    HostActionType type = HostActionType::None;
    std::string arg1;
    std::string arg2;
    int intArg1 = 0;
    int intArg2 = 0;
};

struct GameEntry {
    std::string path;
    std::string title;
    std::string consoleId;    // e.g. "3ds", "nds", "wii", "gamecube", "n64", "snes", "nes", "gba", "gbc", "gb"
    std::string consoleShort; // e.g. "3DS", "DS", "WII", "GC", "N64", "SNES", "NES", "GBA", "GBC", "GB"
    std::string consoleLabel; // localized/display name
    std::string coverKey;
    std::string coverPath;
    bool isRecent = false;
};

struct QuickSaveEntry {
    std::string id;
    std::string gamePath;
    std::string gameTitle;
    std::string consoleId;
    std::string consoleShort;
    std::string slotLabel;
    std::string timestampText;
    std::string sizeText;
    std::string previewPath;
    int slot = 0;
    long long lastModified = 0;
};

struct MemorySaveEntry {
    std::string id;
    std::string filePath;
    std::string title;
    std::string consoleId;
    std::string consoleShort;
    std::string sizeText;
    std::string timestampText;
};

struct GameTab {
    std::string id; // "recent" or consoleId
    std::string label;
    bool isRecent = false;
    std::vector<int> gameIndices;
};

struct CachedTexture {
    GLuint texId = 0;
    int width = 0;
    int height = 0;
    bool pending = false;
    bool failed = false;
    uint64_t lastUsedFrame = 0;
};

struct Color4 {
    float r = 0.f, g = 0.f, b = 0.f, a = 1.f;
};

struct ThemePalette {
    Color4 bgTop;
    Color4 bgBottom;
    Color4 headerBg;
    Color4 cardBgTop;
    Color4 cardBgBottom;
    Color4 cardSelectedBgTop;
    Color4 cardSelectedBgBottom;
    Color4 borderNormal;
    Color4 borderFocus;
    Color4 accentA;
    Color4 accentB;
    Color4 textPrimary;
    Color4 textSecondary;
    Color4 textOnAccent;
    Color4 tabBg;
    Color4 toastBg;
};

struct UiRect {
    float x = 0.f;
    float y = 0.f;
    float w = 0.f;
    float h = 0.f;

    bool contains(float px, float py) const {
        return px >= x && px <= (x + w) && py >= y && py <= (y + h);
    }
};

struct HitTarget {
    UiRect rect;
    int actionCode = 0;
    int param0 = 0;
    int param1 = 0;
};

class NintendoRenderer {
public:
    NintendoRenderer();
    ~NintendoRenderer();

    void onSurfaceCreated();
    void onSurfaceChanged(int width, int height);
    void onDrawFrame(float dtSeconds);
    void releaseGlResources();

    void setLocalizedStrings(const std::unordered_map<std::string, std::string>& strings);
    void setCatalog(std::vector<GameEntry> games, std::vector<std::string> recentPaths);
    void setQuickSaves(std::vector<QuickSaveEntry> saves);
    void setMemorySaves(std::vector<MemorySaveEntry> saves);
    void setSettings(int themeId, int aspectMode, bool ecoMode, bool sfxEnabled);
    void setClockText(std::string clockText);
    void showToast(std::string message);

    // Input from Android UI / Gamepad
    void onTouchEvent(int action, float x, float y);
    bool onKeyEvent(int keyCode, bool down);
    void onAnalogAxis(float axisX, float axisY);
    void onBackPressed(bool& handled);

    // GL-safe async upload delivery from Kotlin decoder
    void uploadImagePixels(const std::string& key, int w, int h, const std::vector<uint8_t>& rgba);
    void markImageFailed(const std::string& key);

    std::vector<HostAction> drainActions();
    std::vector<std::pair<std::string, std::string>> drainImageRequests(int maxCount);

    // Snapshot for diagnostics/tests
    int currentScreen() const { return static_cast<int>(screen_); }
    int currentTheme() const { return static_cast<int>(theme_); }
    int currentTab() const { return selectedGameTab_; }
    bool isRecentTabFirst() const { return !gameTabs_.empty() && gameTabs_[0].isRecent; }

private:
    void initShaders();
    void destroyShaders();
    void computeStageViewport();
    void rebuildGameTabs(bool preserveSelection);
    void openGamesScreenDefault();

    void drawBackdrop();
    void drawRoundedRect(float x, float y, float w, float h, float radius,
                         const Color4& top, const Color4& bottom,
                         const Color4& border, float borderWidth, float glow = 0.f);
    void drawTexturedRect(float x, float y, float w, float h, GLuint texId,
                          const Color4& tint, float radius = 0.f, bool rounded = false);
    void drawText(const std::string& text, float x, float y, float maxW,
                  float fontPx, const Color4& color, int align = 0, bool bold = false,
                  float* outH = nullptr);
    void drawConsoleBadge(float x, float y, float w, float h, const std::string& consoleId,
                          const std::string& shortLabel);

    void renderIntro();
    void renderHeader(const std::string& titleText, bool showBackButton);
    void renderHub();
    void renderGames();
    void renderSaves();
    void renderSettings();
    void renderFooter();
    void renderToast();

    GLuint acquireTextTexture(const std::string& text, float fontPx, int maxWidthPx, bool bold,
                              int align, int& outW, int& outH);
    GLuint acquireCoverTexture(const std::string& key, const std::string& path);
    void evictUnusedTexturesIfNeeded();

    void queueAction(HostAction action);
    void triggerSfx(SfxType sfx);
    const std::string& str(const char* key, const char* fallback) const;
    ThemePalette palette() const;
    Color4 consoleBadgeColor(const std::string& consoleId) const;

    void activateHubCard(int idx);
    void activateGameSelection();
    void activateSaveSelection();
    void activateSettingRow(int row, int delta);
    void ensureGameFocusVisible();
    void ensureSaveFocusVisible();
    void handleTap(float x, float y);

    mutable std::mutex dataMutex_;

    // Surface & logical stage coordinates (supports 16:9, 16:10, 4:3, portrait & tablets cleanly)
    int screenW_ = 1280;
    int screenH_ = 720;
    float stageX_ = 0.f;
    float stageY_ = 0.f;
    float stageW_ = 1280.f;
    float stageH_ = 720.f;
    float uiScale_ = 1.f;
    float time_ = 0.f;
    uint64_t frameIndex_ = 0;

    // Navigation & animation state
    Screen screen_ = Screen::Intro;
    float introElapsed_ = 0.f;
    bool bootSfxPlayed_ = false;
    float screenFade_ = 1.f;
    float focusPulse_ = 0.f;

    int hubFocus_ = 0; // 0=Games, 1=Saves, 2=Settings
    int selectedGameTab_ = 0;
    int gameFocus_ = 0;
    float gameScrollY_ = 0.f;
    float targetGameScrollY_ = 0.f;
    int gameCols_ = 4;

    int savesSubTab_ = 0; // 0=QuickSaves, 1=MemoryCards
    int saveFocus_ = 0;
    float saveScrollY_ = 0.f;
    float targetSaveScrollY_ = 0.f;

    int settingFocus_ = 0; // 0=Theme, 1=Aspect, 2=Eco, 3=Sfx, 4=StandardUI

    // Preferences
    ThemeId theme_ = ThemeId::WiiPearl;
    AspectMode aspectMode_ = AspectMode::Auto;
    bool ecoMode_ = false;
    bool sfxEnabled_ = true;

    // Toast & delete confirm
    std::string toastText_;
    float toastTimer_ = 0.f;
    int pendingDeleteSaveIdx_ = -1;
    float pendingDeleteTimer_ = 0.f;

    // Catalog & save data
    std::vector<GameEntry> games_;
    std::vector<std::string> recentPaths_;
    std::vector<GameTab> gameTabs_;
    std::vector<QuickSaveEntry> quickSaves_;
    std::vector<MemorySaveEntry> memorySaves_;
    std::unordered_map<std::string, std::string> strings_;
    std::string clockText_ = "12:00";

    // Touch tracking
    bool touchDown_ = false;
    bool touchDragged_ = false;
    float touchStartX_ = 0.f;
    float touchStartY_ = 0.f;
    float touchLastY_ = 0.f;
    std::vector<HitTarget> hitTargets_;

    // Analog stick repeat timer
    float analogRepeatTimer_ = 0.f;
    int lastAnalogDirX_ = 0;
    int lastAnalogDirY_ = 0;

    // Output action queues (drained by Kotlin)
    std::vector<HostAction> pendingActions_;
    std::vector<std::pair<std::string, std::string>> pendingImageRequests_;

    // GL handles
    GLuint quadVao_ = 0;
    GLuint quadVbo_ = 0;
    GLuint progBackdrop_ = 0;
    GLuint progRounded_ = 0;
    GLuint progTextured_ = 0;

    std::unordered_map<std::string, CachedTexture> textTextures_;
    std::unordered_map<std::string, CachedTexture> imageTextures_;
    std::vector<std::pair<std::string, std::tuple<int, int, std::vector<uint8_t>>>> pendingUploads_;
};

} // namespace nintendoui
