# SPDX-License-Identifier: GPL-3.0-or-later
include("${CMAKE_CURRENT_LIST_DIR}/../../core-common/diagnostics.cmake")
if(NOT EMUCORED_DIAGNOSTICS)
    file(READ "${CMAKE_SOURCE_DIR}/Source/Core/Common/Logging/LogManager.cpp" source)
    emucored_replace_checked(source [==[
                       fmt::string_view format, const fmt::format_args& args)
{
  auto* instance = LogManager::GetInstance();
  if (instance == nullptr)
]==] [==[
                       fmt::string_view format, const fmt::format_args& args)
{
#if !defined(EMUCORED_DIAGNOSTICS) || EMUCORED_DIAGNOSTICS
  auto* instance = LogManager::GetInstance();
  if (instance == nullptr)
]==])
    emucored_replace_checked(source [==[
  const auto message = fmt::vformat(format, args);
  instance->Log(level, type, file, line, message.c_str());
}

]==] [==[
  const auto message = fmt::vformat(format, args);
  instance->Log(level, type, file, line, message.c_str());
#endif
}

]==])
    emucored_replace_checked(source [==[
LogManager::LogManager()
{
  // create log containers
  m_log[LogType::ACHIEVEMENTS] = {"RetroAchievements", "Achievements"};
]==] [==[
LogManager::LogManager()
{
  m_effective_level.store(static_cast<LogLevel>(0), std::memory_order_relaxed);
#if !defined(EMUCORED_DIAGNOSTICS) || EMUCORED_DIAGNOSTICS
  // create log containers
  m_log[LogType::ACHIEVEMENTS] = {"RetroAchievements", "Achievements"};
]==])
    emucored_replace_checked(source [==[
  m_config_changed_callback_id =
      Config::AddConfigChangedCallback([this]() { SetEffectiveLogLevel(); });
}

LogManager::~LogManager()
{
  Config::RemoveConfigChangedCallback(m_config_changed_callback_id);
}

void LogManager::SaveSettings()
{
  Config::ConfigChangeCallbackGuard config_guard;

]==] [==[
  m_config_changed_callback_id =
      Config::AddConfigChangedCallback([this]() { SetEffectiveLogLevel(); });
#endif
}

LogManager::~LogManager()
{
#if !defined(EMUCORED_DIAGNOSTICS) || EMUCORED_DIAGNOSTICS
  Config::RemoveConfigChangedCallback(m_config_changed_callback_id);
#endif
}

void LogManager::SaveSettings()
{
#if !defined(EMUCORED_DIAGNOSTICS) || EMUCORED_DIAGNOSTICS
  Config::ConfigChangeCallbackGuard config_guard;

]==])
    emucored_replace_checked(source [==[

  Config::Save();
}

]==] [==[

  Config::Save();
#endif
}

]==])
    emucored_replace_checked(source [==[
LogLevel LogManager::GetEffectiveLogLevel() const
{
  return m_effective_level.load(std::memory_order_relaxed);
}

]==] [==[
LogLevel LogManager::GetEffectiveLogLevel() const
{
  return static_cast<LogLevel>(0);
}

]==])
    emucored_replace_checked(source [==[
bool LogManager::IsEnabled(LogType type, LogLevel level) const
{
  return m_log[type].m_enable && GetEffectiveLogLevel() >= level;
}

]==] [==[
bool LogManager::IsEnabled(LogType type, LogLevel level) const
{
  return false;
}

]==])
    emucored_replace_checked(source [==[
bool LogManager::IsListenerEnabled(LogListener::LISTENER id) const
{
  return m_listener_ids[id];
}

]==] [==[
bool LogManager::IsListenerEnabled(LogListener::LISTENER id) const
{
  return false;
}

]==])
    file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/diagnostics/Common/Logging/LogManager.cpp" "${source}")
    emucored_use_source(common "${CMAKE_SOURCE_DIR}/Source/Core/Common/Logging/LogManager.cpp" "${CMAKE_CURRENT_BINARY_DIR}/diagnostics/Common/Logging/LogManager.cpp")
    file(READ "${CMAKE_SOURCE_DIR}/Source/Core/Common/Logging/Log.h" source)
    emucored_replace_checked(source [==[
// fmtlib capable API

#define GENERIC_LOG_FMT(t, v, format, ...)                                                         \
  do                                                                                               \
]==] [==[
// fmtlib capable API

#if defined(EMUCORED_DIAGNOSTICS) && !EMUCORED_DIAGNOSTICS
#define GENERIC_LOG_FMT(...) ((void)0)
#else
#define GENERIC_LOG_FMT(t, v, format, ...)                                                         \
  do                                                                                               \
]==])
    emucored_replace_checked(source [==[
    }                                                                                              \
  } while (0)

#define ERROR_LOG_FMT(t, ...)                                                                      \
]==] [==[
    }                                                                                              \
  } while (0)

#endif

#define ERROR_LOG_FMT(t, ...)                                                                      \
]==])
    file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/diagnostics/Common/Logging/Log.h" "${source}")
    file(READ "${CMAKE_SOURCE_DIR}/Source/Core/AudioCommon/AudioCommon.cpp" source)
    emucored_replace_checked(source [==[
void StartAudioDump(Core::System& system)
{
  const SoundStream* const sound_stream = system.GetSoundStream();

]==] [==[
void StartAudioDump(Core::System& system)
{
#if !defined(EMUCORED_DIAGNOSTICS) || EMUCORED_DIAGNOSTICS
  const SoundStream* const sound_stream = system.GetSoundStream();

]==])
    emucored_replace_checked(source [==[
  sound_stream->GetMixer()->StartLogDSPAudio(audio_file_name_dsp);
  system.SetAudioDumpStarted(true);
}

]==] [==[
  sound_stream->GetMixer()->StartLogDSPAudio(audio_file_name_dsp);
  system.SetAudioDumpStarted(true);
#endif
}

]==])
    file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/diagnostics/AudioCommon/AudioCommon.cpp" "${source}")
    emucored_use_source(audiocommon "${CMAKE_SOURCE_DIR}/Source/Core/AudioCommon/AudioCommon.cpp" "${CMAKE_CURRENT_BINARY_DIR}/diagnostics/AudioCommon/AudioCommon.cpp")
    file(READ "${CMAKE_SOURCE_DIR}/Source/Core/VideoCommon/FrameDumper.cpp" source)
    emucored_replace_checked(source [==[
    }

    if (Config::Get(Config::MAIN_MOVIE_DUMP_FRAMES))
    {
      if (!frame_dump_started)
]==] [==[
    }

    if (false)
    {
      if (!frame_dump_started)
]==])
    emucored_replace_checked(source [==[
bool FrameDumper::StartFrameDumpToImage(const FrameData&)
{
  m_frame_dump_image_counter = 1;
  if (!Config::Get(Config::MAIN_MOVIE_DUMP_FRAMES_SILENT))
  {
    // Only check for the presence of the first image to confirm overwriting.
    // A previous run will always have at least one image, and it's safe to assume that if the user
    // has allowed the first image to be overwritten, this will apply any remaining images as well.
    std::string filename = GetFrameDumpNextImageFileName();
    if (File::Exists(filename))
    {
      if (!AskYesNoFmtT("Frame dump image(s) '{0}' already exists. Overwrite?", filename))
        return false;
    }
  }

  return true;
}

void FrameDumper::DumpFrameToImage(const FrameData& frame)
{
  DumpFrameToPNG(frame, GetFrameDumpNextImageFileName());
  m_frame_dump_image_counter++;
}

]==] [==[
bool FrameDumper::StartFrameDumpToImage(const FrameData&)
{
  return false;
}

void FrameDumper::DumpFrameToImage(const FrameData& frame)
{
}

]==])
    emucored_replace_checked(source [==[
    return true;

  if (Config::Get(Config::MAIN_MOVIE_DUMP_FRAMES))
    return true;

]==] [==[
    return true;

  if (false)
    return true;

]==])
    emucored_replace_checked(source [==[
int FrameDumper::GetRequiredResolutionLeastCommonMultiple() const
{
  if (Config::Get(Config::MAIN_MOVIE_DUMP_FRAMES))
    return VIDEO_ENCODER_LCM;
  return 1;
]==] [==[
int FrameDumper::GetRequiredResolutionLeastCommonMultiple() const
{
  if (false)
    return VIDEO_ENCODER_LCM;
  return 1;
]==])
    file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/diagnostics/VideoCommon/FrameDumper.cpp" "${source}")
    emucored_use_source(videocommon "${CMAKE_SOURCE_DIR}/Source/Core/VideoCommon/FrameDumper.cpp" "${CMAKE_CURRENT_BINARY_DIR}/diagnostics/VideoCommon/FrameDumper.cpp")
    file(READ "${CMAKE_SOURCE_DIR}/Source/Core/VideoCommon/VideoConfig.cpp" source)
    emucored_replace_checked(source [==[
  bOverlayScissorStats = Config::Get(Config::GFX_OVERLAY_SCISSOR_STATS);
  bShowInternalResolution = Config::Get(Config::GFX_SHOW_INTERNAL_RESOLUTION);
  bDumpTextures = Config::Get(Config::GFX_DUMP_TEXTURES);
  bDumpMipmapTextures = Config::Get(Config::GFX_DUMP_MIP_TEXTURES);
  bDumpBaseTextures = Config::Get(Config::GFX_DUMP_BASE_TEXTURES);
  bHiresTextures = Config::Get(Config::GFX_HIRES_TEXTURES);
  bCacheHiresTextures = Config::Get(Config::GFX_CACHE_HIRES_TEXTURES);
  bDumpEFBTarget = Config::Get(Config::GFX_DUMP_EFB_TARGET);
  bDumpXFBTarget = Config::Get(Config::GFX_DUMP_XFB_TARGET);
  bEnableGPUTextureDecoding = Config::Get(Config::GFX_ENABLE_GPU_TEXTURE_DECODING);
  bPreferVSForLinePointExpansion = Config::Get(Config::GFX_PREFER_VS_FOR_LINE_POINT_EXPANSION);
]==] [==[
  bOverlayScissorStats = Config::Get(Config::GFX_OVERLAY_SCISSOR_STATS);
  bShowInternalResolution = Config::Get(Config::GFX_SHOW_INTERNAL_RESOLUTION);
  bDumpTextures = false;
  bDumpMipmapTextures = false;
  bDumpBaseTextures = false;
  bHiresTextures = Config::Get(Config::GFX_HIRES_TEXTURES);
  bCacheHiresTextures = Config::Get(Config::GFX_CACHE_HIRES_TEXTURES);
  bDumpEFBTarget = false;
  bDumpXFBTarget = false;
  bEnableGPUTextureDecoding = Config::Get(Config::GFX_ENABLE_GPU_TEXTURE_DECODING);
  bPreferVSForLinePointExpansion = Config::Get(Config::GFX_PREFER_VS_FOR_LINE_POINT_EXPANSION);
]==])
    emucored_replace_checked(source [==[  bLogRenderTimeToFile = Config::Get(Config::GFX_LOG_RENDER_TIME_TO_FILE);]==] [==[  bLogRenderTimeToFile = false;]==])
    file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/diagnostics/VideoCommon/VideoConfig.cpp" "${source}")
    emucored_use_source(videocommon "${CMAKE_SOURCE_DIR}/Source/Core/VideoCommon/VideoConfig.cpp" "${CMAKE_CURRENT_BINARY_DIR}/diagnostics/VideoCommon/VideoConfig.cpp")
    file(READ "${CMAKE_SOURCE_DIR}/Source/Core/VideoCommon/TextureUtils.cpp" source)
    emucored_replace_checked(source [==[
                 bool is_arbitrary)
{
  const std::string dump_dir =
      File::GetUserPath(D_DUMPTEXTURES_IDX) + SConfig::GetInstance().GetGameID();

  if (!File::IsDirectory(dump_dir))
    File::CreateDir(dump_dir);

  const std::string name = BuildDumpTextureFilename(std::move(basename), level, is_arbitrary);
  const std::string filename = fmt::format("{}/{}.png", dump_dir, name);

  if (File::Exists(filename))
    return;

  texture.Save(filename, level, Config::Get(Config::GFX_TEXTURE_PNG_COMPRESSION_LEVEL));
}

]==] [==[
                 bool is_arbitrary)
{
}

]==])
    emucored_replace_checked(source [==[
                                bool is_arbitrary)
{
  const std::string dump_dir =
      File::GetUserPath(D_DUMPTEXTURES_IDX) + SConfig::GetInstance().GetGameID();

  if (m_dumped_textures.empty())
  {
    if (!File::IsDirectory(dump_dir))
      File::CreateDir(dump_dir);

    for (auto& filename : Common::DoFileSearch(dump_dir, ".png", true))
    {
      std::string name;
      SplitPath(filename, nullptr, &name, nullptr);
      m_dumped_textures.insert(name);
    }

    NOTICE_LOG_FMT(VIDEO, "Found {} dumped textures that will not be re-dumped.",
                   m_dumped_textures.size());
  }

  const std::string name = BuildDumpTextureFilename(std::move(basename), level, is_arbitrary);
  const bool file_existed = !m_dumped_textures.insert(name).second;
  if (file_existed)
    return;

  texture.Save(fmt::format("{}/{}.png", dump_dir, name), level,
               Config::Get(Config::GFX_TEXTURE_PNG_COMPRESSION_LEVEL));
}
}  // namespace VideoCommon::TextureUtils
]==] [==[
                                bool is_arbitrary)
{
}
}  // namespace VideoCommon::TextureUtils
]==])
    file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/diagnostics/VideoCommon/TextureUtils.cpp" "${source}")
    emucored_use_source(videocommon "${CMAKE_SOURCE_DIR}/Source/Core/VideoCommon/TextureUtils.cpp" "${CMAKE_CURRENT_BINARY_DIR}/diagnostics/VideoCommon/TextureUtils.cpp")
    file(READ "${CMAKE_SOURCE_DIR}/Source/Core/Core/Debugger/PPCDebugInterface.cpp" source)
    emucored_replace_checked(source [==[Config::Get(Config::MAIN_NETWORK_SSL_DUMP_READ)]==] [==[false]==])
    emucored_replace_checked(source [==[Config::Get(Config::MAIN_NETWORK_SSL_DUMP_WRITE)]==] [==[false]==])
    emucored_replace_checked(source [==[Config::Get(Config::MAIN_NETWORK_DUMP_AS_PCAP)]==] [==[false]==])
    file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/diagnostics/Core/Debugger/PPCDebugInterface.cpp" "${source}")
    emucored_use_source(core "${CMAKE_SOURCE_DIR}/Source/Core/Core/Debugger/PPCDebugInterface.cpp" "${CMAKE_CURRENT_BINARY_DIR}/diagnostics/Core/Debugger/PPCDebugInterface.cpp")
    file(READ "${CMAKE_SOURCE_DIR}/Source/Core/Core/IOS/Network/SSL.cpp" source)
    emucored_replace_checked(source [==[Config::Get(Config::MAIN_NETWORK_SSL_DUMP_ROOT_CA)]==] [==[false]==])
    file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/diagnostics/Core/IOS/Network/SSL.cpp" "${source}")
    emucored_use_source(core "${CMAKE_SOURCE_DIR}/Source/Core/Core/IOS/Network/SSL.cpp" "${CMAKE_CURRENT_BINARY_DIR}/diagnostics/Core/IOS/Network/SSL.cpp")
    file(READ "${CMAKE_SOURCE_DIR}/Source/Core/Core/IOS/Network/Socket.cpp" source)
    emucored_replace_checked(source [==[Config::Get(Config::MAIN_NETWORK_SSL_DUMP_PEER_CERT)]==] [==[false]==])
    file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/diagnostics/Core/IOS/Network/Socket.cpp" "${source}")
    emucored_use_source(core "${CMAKE_SOURCE_DIR}/Source/Core/Core/IOS/Network/Socket.cpp" "${CMAKE_CURRENT_BINARY_DIR}/diagnostics/Core/IOS/Network/Socket.cpp")
    file(READ "${CMAKE_SOURCE_DIR}/Source/Core/Core/HW/WiimoteReal/WiimoteReal.cpp" source)
    emucored_replace_checked(source [==[Config::Get(Config::MAIN_BB_DUMP_PORT)]==] [==[0]==])
    file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/diagnostics/Core/HW/WiimoteReal/WiimoteReal.cpp" "${source}")
    emucored_use_source(core "${CMAKE_SOURCE_DIR}/Source/Core/Core/HW/WiimoteReal/WiimoteReal.cpp" "${CMAKE_CURRENT_BINARY_DIR}/diagnostics/Core/HW/WiimoteReal/WiimoteReal.cpp")
    file(READ "${CMAKE_SOURCE_DIR}/Source/Core/Core/DSP/DSPCodeUtil.cpp" source)
    emucored_replace_checked(source [==[
bool DumpDSPCode(const u8* code_be, size_t size_in_bytes, u32 crc)
{
  const std::string root_name =
      File::GetUserPath(D_DUMPDSP_IDX) + fmt::format("DSP_UC_{:08X}", crc);
  const std::string binary_file = root_name + ".bin";
  const std::string text_file = root_name + ".txt";

  if (!File::IOFile(binary_file, "wb").WriteBytes(code_be, size_in_bytes))
  {
    PanicAlertFmt("Can't dump UCode to file '{}'!!", binary_file);
    return false;
  }

  // The disassembler works in native endian
  std::vector<u16> code(size_in_bytes / 2);
  for (size_t i = 0; i < code.size(); i++)
    code[i] = Common::swap16(&code_be[i * 2]);

  std::string text;
  if (!Disassemble(code, true, text))
    return false;

  return File::WriteStringToFile(text_file, text);
}

]==] [==[
bool DumpDSPCode(const u8* code_be, size_t size_in_bytes, u32 crc)
{
  return false;
}

]==])
    file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/diagnostics/Core/DSP/DSPCodeUtil.cpp" "${source}")
    emucored_use_source(core "${CMAKE_SOURCE_DIR}/Source/Core/Core/DSP/DSPCodeUtil.cpp" "${CMAKE_CURRENT_BINARY_DIR}/diagnostics/Core/DSP/DSPCodeUtil.cpp")
    # Core targets were created before the deferred bridge include. Set the
    # include overlay on each target, not only the already-consumed directory.
    function(emucored_diagnostic_includes directory)
        get_property(targets DIRECTORY "${directory}" PROPERTY BUILDSYSTEM_TARGETS)
        foreach(target IN LISTS targets)
            get_target_property(type ${target} TYPE)
            if(NOT type STREQUAL "UTILITY" AND NOT type STREQUAL "INTERFACE_LIBRARY")
                target_include_directories(${target} BEFORE PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/diagnostics")
            endif()
        endforeach()
        get_property(children DIRECTORY "${directory}" PROPERTY SUBDIRECTORIES)
        foreach(child IN LISTS children)
            emucored_diagnostic_includes("${child}")
        endforeach()
    endfunction()
    emucored_diagnostic_includes("${CMAKE_SOURCE_DIR}/Source/Core")
endif()

if(EMUCORED_BUILD_CORE_TESTS AND NOT EMUCORED_DIAGNOSTICS AND UNIX)
    add_executable(emucored_diagnostics_contract "${CMAKE_CURRENT_LIST_DIR}/../../core-common/tests/diagnostics_contract.cpp")
    target_compile_features(emucored_diagnostics_contract PRIVATE cxx_std_23)
    target_compile_definitions(emucored_diagnostics_contract PRIVATE TEST_DOLPHIN=1)
    target_include_directories(emucored_diagnostics_contract BEFORE PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/diagnostics" "${CMAKE_SOURCE_DIR}/Source/Core")
    target_link_libraries(emucored_diagnostics_contract PRIVATE common)
endif()
