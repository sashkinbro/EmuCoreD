EMUCORED_N64_DIR := $(call my-dir)
include $(EMUCORED_N64_DIR)/mupen64plus/libretro/jni/Android.mk

# The upstream offset recipe uses POSIX shell syntax, which NDK make cannot
# execute through Windows cmd.exe. Generate the same files with SDK CMake.
ifeq ($(HOST_OS),windows)
$(ASM_DEFINES_NASM):
	@"$(EMUCORED_CMAKE)" "-DSTRINGS=$(NDK_ROOT)/toolchains/llvm/prebuilt/windows-x86_64/bin/llvm-strings.exe" "-DOBJECT=$(ASM_DEFINES_OBJ)" "-DOUTPUT_DIR=$(AWK_DEST_DIR)" -P "$(EMUCORED_N64_DIR)/gen_offsets.cmake"
endif
