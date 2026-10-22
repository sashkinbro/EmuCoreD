#include <android/log.h>
#include <pthread.h>
#include <unistd.h>
#include <dlfcn.h>
#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <string>

#include "Platform.h"
#include "SPI_Firmware.h"

namespace EmuCoreDS {
std::string g_user_dir;
std::string g_internal_dir;
std::string g_sram_path;
std::string g_firmware_path;
void RequestStop(melonDS::Platform::StopReason reason);
void WriteNdsSave(const melonDS::u8* data, melonDS::u32 length, melonDS::u32 offset, melonDS::u32 write_length);
void WriteFirmwareData(const melonDS::u8* data, melonDS::u32 length, melonDS::u32 offset, melonDS::u32 write_length);
} // namespace EmuCoreDS

namespace melonDS::Platform {

namespace {

struct AndroidSemaphore {
    int val;
    pthread_mutex_t mutex;
    pthread_cond_t cond;
};

struct AndroidThread {
    pthread_t id;
    std::function<void()> func;
};

void* ThreadEntry(void* data) {
    auto* thread = static_cast<AndroidThread*>(data);
    thread->func();
    return nullptr;
}

constexpr char AccessMode(FileMode mode, bool fileExists) {
    if (mode & FileMode::Append) return 'a';
    if (!(mode & FileMode::Write)) return 'r';
    if (mode & FileMode::NoCreate) return 'r';
    if ((mode & FileMode::Preserve) && fileExists) return 'r';
    return 'w';
}

constexpr bool IsExtended(FileMode mode) {
    return (mode & FileMode::ReadWrite) == FileMode::ReadWrite;
}

std::string GetModeString(FileMode mode, bool fileExists) {
    std::string result;
    result += AccessMode(mode, fileExists);
    if (IsExtended(mode)) result += '+';
    if (!(mode & FileMode::Text)) result += 'b';
    return result;
}

std::string ResolvePath(const std::string& path) {
    if (path.empty()) return path;
    if (path[0] == '/') return path;
    if (!EmuCoreDS::g_user_dir.empty()) {
        if (path.rfind("user/", 0) == 0) {
            return EmuCoreDS::g_user_dir + "/" + path.substr(5);
        }
        return EmuCoreDS::g_user_dir + "/" + path;
    }
    return path;
}

} // namespace

void SignalStop(StopReason reason, void*) {
    EmuCoreDS::RequestStop(reason);
}

FileHandle* OpenFile(const std::string& path, FileMode mode) {
    if ((mode & (FileMode::ReadWrite | FileMode::Append)) == FileMode::None) {
        return nullptr;
    }
    if (path.empty()) return nullptr;
    if (path.rfind("content://", 0) == 0) return nullptr;
    const std::string resolved = ResolvePath(path);
    const bool exists = access(resolved.c_str(), F_OK) == 0;
    const bool must_exist = (mode & FileMode::NoCreate) != 0;
    if (must_exist && !exists) return nullptr;
    return reinterpret_cast<FileHandle*>(fopen(resolved.c_str(), GetModeString(mode, exists).c_str()));
}

FileHandle* OpenLocalFile(const std::string& path, FileMode mode) {
    return OpenFile(path, mode);
}

FileHandle* OpenInternalFile(const std::string path, FileMode mode) {
    if (path.empty()) return nullptr;
    std::string full = path;
    if (path[0] != '/' && !EmuCoreDS::g_internal_dir.empty()) {
        full = EmuCoreDS::g_internal_dir + "/" + path;
    }
    return OpenFile(full, mode);
}

bool CloseFile(FileHandle* file) {
    return fclose(reinterpret_cast<FILE*>(file)) == 0;
}

bool IsEndOfFile(FileHandle* file) {
    return feof(reinterpret_cast<FILE*>(file)) != 0;
}

bool FileReadLine(char* str, int count, FileHandle* file) {
    return fgets(str, count, reinterpret_cast<FILE*>(file)) != nullptr;
}

bool FileExists(const std::string& name) {
    FileHandle* file = OpenFile(name, FileMode::Read);
    if (!file) return false;
    CloseFile(file);
    return true;
}

bool LocalFileExists(const std::string& name) {
    FileHandle* file = OpenLocalFile(name, FileMode::Read);
    if (!file) return false;
    CloseFile(file);
    return true;
}

bool CheckFileWritable(const std::string& filepath) {
    return FileExists(filepath) || !filepath.empty();
}

bool CheckLocalFileWritable(const std::string& name) {
    return CheckFileWritable(name);
}

u64 FilePosition(FileHandle* file) {
    return static_cast<u64>(ftell(reinterpret_cast<FILE*>(file)));
}

bool FileSeek(FileHandle* file, s64 offset, FileSeekOrigin origin) {
    int std_origin = SEEK_SET;
    switch (origin) {
    case FileSeekOrigin::Start: std_origin = SEEK_SET; break;
    case FileSeekOrigin::Current: std_origin = SEEK_CUR; break;
    case FileSeekOrigin::End: std_origin = SEEK_END; break;
    }
    return fseek(reinterpret_cast<FILE*>(file), static_cast<long>(offset), std_origin) == 0;
}

void FileRewind(FileHandle* file) {
    rewind(reinterpret_cast<FILE*>(file));
}

u64 FileRead(void* data, u64 size, u64 count, FileHandle* file) {
    return fread(data, size, count, reinterpret_cast<FILE*>(file));
}

bool FileFlush(FileHandle* file) {
    return fflush(reinterpret_cast<FILE*>(file)) == 0;
}

u64 FileWrite(const void* data, u64 size, u64 count, FileHandle* file) {
    return fwrite(data, size, count, reinterpret_cast<FILE*>(file));
}

u64 FileWriteFormatted(FileHandle* file, const char* fmt, ...) {
    if (!fmt) return 0;
    va_list args;
    va_start(args, fmt);
    const u64 result = vfprintf(reinterpret_cast<FILE*>(file), fmt, args);
    va_end(args);
    return result;
}

u64 FileLength(FileHandle* file) {
    FILE* std_file = reinterpret_cast<FILE*>(file);
    const long position = ftell(std_file);
    fseek(std_file, 0, SEEK_END);
    const long length = ftell(std_file);
    fseek(std_file, position, SEEK_SET);
    return static_cast<u64>(length);
}

void Log(LogLevel level, const char* fmt, ...) {
    if (!fmt) return;
    va_list args;
    va_start(args, fmt);
    int priority = ANDROID_LOG_INFO;
    switch (level) {
    case LogLevel::Debug: priority = ANDROID_LOG_DEBUG; break;
    case LogLevel::Info: priority = ANDROID_LOG_INFO; break;
    case LogLevel::Warn: priority = ANDROID_LOG_WARN; break;
    case LogLevel::Error: priority = ANDROID_LOG_ERROR; break;
    }
    __android_log_vprint(priority, "EmuCoreDS", fmt, args);
    va_end(args);
}

Thread* Thread_Create(std::function<void()> func) {
    auto* thread = new AndroidThread;
    thread->func = std::move(func);
    pthread_create(&thread->id, nullptr, ThreadEntry, thread);
    return reinterpret_cast<Thread*>(thread);
}

void Thread_Free(Thread* thread) {
    delete reinterpret_cast<AndroidThread*>(thread);
}

void Thread_Wait(Thread* thread) {
    pthread_join(reinterpret_cast<AndroidThread*>(thread)->id, nullptr);
}

Semaphore* Semaphore_Create() {
    auto* semaphore = static_cast<AndroidSemaphore*>(malloc(sizeof(AndroidSemaphore)));
    pthread_mutex_init(&semaphore->mutex, nullptr);
    pthread_cond_init(&semaphore->cond, nullptr);
    semaphore->val = 0;
    return reinterpret_cast<Semaphore*>(semaphore);
}

void Semaphore_Free(Semaphore* sema) {
    auto* semaphore = reinterpret_cast<AndroidSemaphore*>(sema);
    pthread_mutex_destroy(&semaphore->mutex);
    pthread_cond_destroy(&semaphore->cond);
    free(semaphore);
}

void Semaphore_Reset(Semaphore* sema) {
    auto* semaphore = reinterpret_cast<AndroidSemaphore*>(sema);
    pthread_mutex_lock(&semaphore->mutex);
    semaphore->val = 0;
    pthread_cond_broadcast(&semaphore->cond);
    pthread_mutex_unlock(&semaphore->mutex);
}

void Semaphore_Wait(Semaphore* sema) {
    auto* semaphore = reinterpret_cast<AndroidSemaphore*>(sema);
    pthread_mutex_lock(&semaphore->mutex);
    while (semaphore->val == 0) {
        pthread_cond_wait(&semaphore->cond, &semaphore->mutex);
    }
    semaphore->val--;
    pthread_mutex_unlock(&semaphore->mutex);
}

bool Semaphore_TryWait(Semaphore* sema, int timeout_ms) {
    auto* semaphore = reinterpret_cast<AndroidSemaphore*>(sema);
    pthread_mutex_lock(&semaphore->mutex);
    bool result = false;
    if (semaphore->val > 0) {
        semaphore->val--;
        result = true;
    } else if (timeout_ms > 0) {
        timespec timeout{};
        timeout.tv_sec = timeout_ms / 1000;
        timeout.tv_nsec = static_cast<long>(timeout_ms % 1000) * 1000000;
        int wait_result = 0;
        while (semaphore->val == 0 && wait_result == 0) {
            wait_result = pthread_cond_timedwait(&semaphore->cond, &semaphore->mutex, &timeout);
        }
        if (wait_result == 0) {
            semaphore->val--;
            result = true;
        }
    }
    pthread_mutex_unlock(&semaphore->mutex);
    return result;
}

void Semaphore_Post(Semaphore* sema, int count) {
    auto* semaphore = reinterpret_cast<AndroidSemaphore*>(sema);
    pthread_mutex_lock(&semaphore->mutex);
    semaphore->val += count;
    pthread_cond_broadcast(&semaphore->cond);
    pthread_mutex_unlock(&semaphore->mutex);
}

Mutex* Mutex_Create() {
    auto* mutex = static_cast<pthread_mutex_t*>(malloc(sizeof(pthread_mutex_t)));
    pthread_mutex_init(mutex, nullptr);
    return reinterpret_cast<Mutex*>(mutex);
}

void Mutex_Free(Mutex* mutex) {
    pthread_mutex_destroy(reinterpret_cast<pthread_mutex_t*>(mutex));
    free(mutex);
}

void Mutex_Lock(Mutex* mutex) {
    pthread_mutex_lock(reinterpret_cast<pthread_mutex_t*>(mutex));
}

void Mutex_Unlock(Mutex* mutex) {
    pthread_mutex_unlock(reinterpret_cast<pthread_mutex_t*>(mutex));
}

bool Mutex_TryLock(Mutex* mutex) {
    return pthread_mutex_trylock(reinterpret_cast<pthread_mutex_t*>(mutex)) == 0;
}

AACDecoder* AAC_Init() { return nullptr; }
void AAC_DeInit(AACDecoder*) {}
bool AAC_Configure(AACDecoder*, int, int) { return false; }
bool AAC_DecodeFrame(AACDecoder*, const void*, int, void*, int) { return false; }

void WriteNDSSave(const u8* savedata, u32 savelen, u32 writeoffset, u32 writelen, void*) {
    EmuCoreDS::WriteNdsSave(savedata, savelen, writeoffset, writelen);
}

void WriteGBASave(const u8*, u32, u32, u32, void*) {}

void WriteFirmware(const Firmware& firmware, u32 writeoffset, u32 writelen, void*) {
    if (EmuCoreDS::g_firmware_path.empty()) return;
    if (firmware.GetHeader().Identifier != GENERATED_FIRMWARE_IDENTIFIER) {
        EmuCoreDS::WriteFirmwareData(firmware.Buffer(), firmware.Length(), writeoffset, writelen);
    } else {
        const u32 eapstart = firmware.GetExtendedAccessPointOffset();
        const u32 apstart = firmware.GetWifiAccessPointOffset();
        const u32 apend = apstart + sizeof(firmware.GetAccessPoints());
        if (eapstart <= writeoffset && writeoffset < apend) {
            const u8* buffer = firmware.GetExtendedAccessPointPosition();
            const u32 length = sizeof(firmware.GetExtendedAccessPoints()) +
                               sizeof(firmware.GetAccessPoints());
            EmuCoreDS::WriteFirmwareData(buffer, length, writeoffset - eapstart, writelen);
        }
    }
}

void WriteDateTime(int, int, int, int, int, int, void*) {}

void MP_Begin(void*) {}
void MP_End(void*) {}
int MP_SendPacket(u8*, int, u64, void*) { return 0; }
int MP_RecvPacket(u8*, u64*, void*) { return 0; }
int MP_SendCmd(u8*, int, u64, void*) { return 0; }
int MP_SendReply(u8*, int, u64, u16, void*) { return 0; }
int MP_SendAck(u8*, int, u64, void*) { return 0; }
int MP_RecvHostPacket(u8*, u64*, void*) { return 0; }
u16 MP_RecvReplies(u8*, u64, u16, void*) { return 0; }
int Net_SendPacket(u8*, int, void*) { return 0; }
int Net_RecvPacket(u8*, void*) { return 0; }

void Sleep(u64 usecs) {
    usleep(static_cast<useconds_t>(usecs));
}

void Mic_Start(void*) {}
void Mic_Stop(void*) {}
int Mic_ReadInput(s16* data, int maxlength, void*) {
    if (data && maxlength > 0) memset(data, 0, static_cast<size_t>(maxlength) * sizeof(s16));
    return 0;
}

u64 GetMSCount() {
    return static_cast<u64>(std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count());
}

void Camera_Start(int, void*) {}
void Camera_Stop(int, void*) {}
void Camera_CaptureFrame(int, u32* frame, int width, int height, bool, void*) {
    if (frame && width > 0 && height > 0) {
        memset(frame, 0, static_cast<size_t>(width) * height * sizeof(u32));
    }
}

bool Addon_KeyDown(KeyType, void*) { return false; }
void Addon_RumbleStart(u32, void*) {}
void Addon_RumbleStop(void*) {}
float Addon_MotionQuery(MotionQueryType type, void*) {
    return type == MotionAccelerationZ ? 9.80665f : 0.0f;
}

DynamicLibrary* DynamicLibrary_Load(const char* lib) {
    return reinterpret_cast<DynamicLibrary*>(dlopen(lib, RTLD_NOW | RTLD_LOCAL));
}

void DynamicLibrary_Unload(DynamicLibrary* lib) {
    dlclose(reinterpret_cast<void*>(lib));
}

void* DynamicLibrary_LoadFunction(DynamicLibrary* lib, const char* name) {
    return dlsym(reinterpret_cast<void*>(lib), name);
}

} // namespace melonDS::Platform
