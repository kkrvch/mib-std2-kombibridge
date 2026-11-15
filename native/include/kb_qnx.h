/*
 * The few QNX 6.5 (armle-v7, soft-float) libc declarations the DisplayManager hook uses, written
 * from POSIX: the build needs no QNX SDK. QNX numeric values: O_ flags from <fcntl.h>, PROT_ and MAP_ values from
 * <sys/mman.h>, CLOCK_* from <time.h>.
 */
#ifndef KB_QNX_H
#define KB_QNX_H

typedef unsigned int size_t;
typedef int ssize_t;
typedef long off_t;
typedef unsigned char uint8_t;
typedef unsigned int uint32_t;
typedef unsigned int uintptr_t;

#define NULL ((void *)0)
#define RTLD_LAZY 0x0001

#define O_RDONLY 0x0000
#define O_WRONLY 0x0001
#define O_RDWR   0x0002
#define O_APPEND 0x0008
#define O_CREAT  0x0100
#define O_TRUNC  0x0200
#define SEEK_SET 0
#define SEEK_END 2

#define PROT_READ  0x0100
#define PROT_WRITE 0x0200
#define MAP_SHARED 0x0001
#define MAP_FAILED ((void *)-1)

#define CLOCK_REALTIME  0
#define CLOCK_MONOTONIC 2

struct timespec { long tv_sec; long tv_nsec; };

int open(const char *path, int flags, ...);
int close(int fd);
off_t lseek(int fd, off_t off, int whence);
ssize_t read(int fd, void *buf, size_t n);
ssize_t write(int fd, const void *buf, size_t n);
int unlink(const char *path);
char *getenv(const char *name);
void *dlopen(const char *path, int mode);
void *dlsym(void *handle, const char *name);
void *memcpy(void *dst, const void *src, size_t n);
size_t strlen(const char *s);
int usleep(unsigned usec);
int clock_gettime(int clock, struct timespec *ts);
int shm_open(const char *name, int flags, int mode);
void *mmap(void *addr, size_t len, int prot, int flags, int fd, off_t off);
int munmap(void *addr, size_t len);
int sem_timedwait_monotonic(void *sem, const struct timespec *abs_timeout);
typedef struct { void *base; size_t len; } iov_t;
int pthread_create(int *thr, const void *attr, void *(*fn)(void *), void *arg);
int name_open(const char *name, int flags);
int name_close(int coid);
int MsgSendv(int coid, const iov_t *siov, int sparts, const iov_t *riov, int rparts);

#endif
