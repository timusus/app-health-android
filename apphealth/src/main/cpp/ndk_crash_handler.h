#ifndef NDK_CRASH_HANDLER_H
#define NDK_CRASH_HANDLER_H

#include <signal.h>

#define MAX_BACKTRACE_DEPTH 64
#define CRASH_BUFFER_SIZE 4096
#define INT_BUFFER_SIZE 24

typedef struct {
    int signal;
    const char* name;
} signal_info_t;

void crash_handler_init(const char* crash_file_path);

#endif
