// Logging that goes to logcat on Android and stderr on the host. Never logs user text.
#pragma once

#include <cstdio>

#ifdef __ANDROID__
#include <android/log.h>
#define mx_log_warn(tag, ...) __android_log_print(ANDROID_LOG_WARN, "ModuleX/" tag, __VA_ARGS__)
#define mx_log_info(tag, ...) __android_log_print(ANDROID_LOG_INFO, "ModuleX/" tag, __VA_ARGS__)
#else
#define mx_log_warn(tag, ...) do { fprintf(stderr, "[W/" tag "] " __VA_ARGS__); fputc('\n', stderr); } while (0)
#define mx_log_info(tag, ...) do { fprintf(stderr, "[I/" tag "] " __VA_ARGS__); fputc('\n', stderr); } while (0)
#endif
