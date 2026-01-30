#include <jni.h>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <string.h>
#include <ucontext.h>
#include <dlfcn.h>
#include <unwind.h>

#include "ndk_crash_handler.h"

static char g_crash_file_path[256];
static int g_crash_fd = -1;
static char g_write_buffer[CRASH_BUFFER_SIZE];
static void* g_backtrace_buffer[MAX_BACKTRACE_DEPTH];

static struct sigaction g_old_handlers[6];

static const int g_signals[] = {SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGTRAP};
static const int g_num_signals = 6;

static const char* get_signal_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS:  return "SIGBUS";
        case SIGFPE:  return "SIGFPE";
        case SIGILL:  return "SIGILL";
        case SIGTRAP: return "SIGTRAP";
        default:      return "UNKNOWN";
    }
}

static int safe_itoa(unsigned long value, char* buffer, int base) {
    static const char digits[] = "0123456789abcdef";
    char temp[INT_BUFFER_SIZE];
    int i = 0;
    int j = 0;

    if (value == 0) {
        buffer[0] = '0';
        buffer[1] = '\0';
        return 1;
    }

    while (value > 0 && i < INT_BUFFER_SIZE - 1) {
        temp[i++] = digits[value % base];
        value /= base;
    }

    while (i > 0) {
        buffer[j++] = temp[--i];
    }
    buffer[j] = '\0';

    return j;
}

static int safe_strcpy(char* dest, const char* src, int max_len) {
    int i = 0;
    while (src[i] != '\0' && i < max_len - 1) {
        dest[i] = src[i];
        i++;
    }
    dest[i] = '\0';
    return i;
}

static void safe_write(int fd, const char* str) {
    if (fd < 0 || str == NULL) return;
    size_t len = 0;
    while (str[len] != '\0') len++;
    write(fd, str, len);
}

typedef struct {
    void** buffer;
    int max_depth;
    int count;
} backtrace_state_t;

static _Unwind_Reason_Code unwind_callback(struct _Unwind_Context* context, void* arg) {
    backtrace_state_t* state = (backtrace_state_t*)arg;

    if (state->count >= state->max_depth) {
        return _URC_END_OF_STACK;
    }

    uintptr_t pc = _Unwind_GetIP(context);
    if (pc != 0) {
        state->buffer[state->count++] = (void*)pc;
    }

    return _URC_NO_REASON;
}

static int capture_backtrace(void** buffer, int max_depth) {
    backtrace_state_t state = {buffer, max_depth, 0};
    _Unwind_Backtrace(unwind_callback, &state);
    return state.count;
}

static void crash_signal_handler(int sig, siginfo_t* info, void* ucontext) {
    int fd = open(g_crash_file_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) {
        fd = g_crash_fd;
    }

    if (fd >= 0) {
        char num_buf[INT_BUFFER_SIZE];

        safe_write(fd, get_signal_name(sig));
        safe_write(fd, "\n");

        safe_write(fd, "0x");
        safe_itoa((unsigned long)info->si_addr, num_buf, 16);
        safe_write(fd, num_buf);
        safe_write(fd, "\n");

        int depth = capture_backtrace(g_backtrace_buffer, MAX_BACKTRACE_DEPTH);
        for (int i = 0; i < depth; i++) {
            safe_write(fd, "0x");
            safe_itoa((unsigned long)g_backtrace_buffer[i], num_buf, 16);
            safe_write(fd, num_buf);
            safe_write(fd, "\n");
        }

        if (fd != g_crash_fd) {
            close(fd);
        }
    }

    int sig_index = -1;
    for (int i = 0; i < g_num_signals; i++) {
        if (g_signals[i] == sig) {
            sig_index = i;
            break;
        }
    }

    if (sig_index >= 0) {
        sigaction(sig, &g_old_handlers[sig_index], NULL);
    }

    raise(sig);
}

void crash_handler_init(const char* crash_file_path) {
    safe_strcpy(g_crash_file_path, crash_file_path, sizeof(g_crash_file_path));

    g_crash_fd = open(crash_file_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_sigaction = crash_signal_handler;
    sa.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&sa.sa_mask);

    for (int i = 0; i < g_num_signals; i++) {
        sigaction(g_signals[i], &sa, &g_old_handlers[i]);
    }
}

JNIEXPORT void JNICALL
Java_com_simplecityapps_telemetry_android_NdkCrashHandler_nativeInit(
    JNIEnv *env,
    jobject thiz,
    jstring crash_file_path
) {
    const char* path = (*env)->GetStringUTFChars(env, crash_file_path, NULL);
    if (path != NULL) {
        crash_handler_init(path);
        (*env)->ReleaseStringUTFChars(env, crash_file_path, path);
    }
}
