/* Link stub of the unit's libc.so.3: only the names matter (DT_NEEDED + undefined symbols that
 * the QNX run-time linker binds to the real libc). Nothing here runs. */
#define STUB(name) void name(void) {}
STUB(open) STUB(close) STUB(lseek) STUB(read) STUB(write) STUB(unlink)
STUB(dlopen) STUB(dlsym) STUB(memcpy) STUB(strlen) STUB(usleep) STUB(clock_gettime)
STUB(shm_open) STUB(mmap) STUB(sem_timedwait_monotonic)
STUB(pthread_create) STUB(name_open) STUB(name_close) STUB(MsgSendv)
