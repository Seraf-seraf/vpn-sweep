#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <pthread.h>
#include <stdbool.h>
#include <stdlib.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#define LOCK_FILE_NAME "search-vpn-owner.lock"
#define LOCK_UNAVAILABLE (-1)
#define LOCK_BUSY (-2)

static pthread_mutex_t owner_mutex = PTHREAD_MUTEX_INITIALIZER;
static int owner_fd = -1;
static dev_t owner_device;
static ino_t owner_inode;
static bool quarantined;

static bool same_file(int fd) {
    struct stat st;
    return fstat(fd, &st) == 0 && S_ISREG(st.st_mode) && st.st_nlink == 1 &&
           st.st_dev == owner_device && st.st_ino == owner_inode;
}

static bool environment_safe(void) {
    const char *direct = getenv("xray.browser.dialer");
    const char *alternate = getenv("XRAY_BROWSER_DIALER");
    return (direct == NULL || direct[0] == '\0') &&
           (alternate == NULL || alternate[0] == '\0');
}

JNIEXPORT jint JNICALL
Java_com_seraf_vpnsweep_searchworker_SearchOwnerLock_nativeAcquire(
    JNIEnv *env, jobject self, jstring directory) {
    (void)self;
    if (directory == NULL) return LOCK_UNAVAILABLE;
    const char *path = (*env)->GetStringUTFChars(env, directory, NULL);
    if (path == NULL) return LOCK_UNAVAILABLE;

    pthread_mutex_lock(&owner_mutex);
    int result = LOCK_UNAVAILABLE;
    int dir_fd = -1;
    int fd = -1;
    if (quarantined) goto done;
    if (owner_fd >= 0) {
        result = LOCK_BUSY;
        goto done;
    }
    dir_fd = open(path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    if (dir_fd < 0) goto done;
    fd = openat(dir_fd, LOCK_FILE_NAME, O_CREAT | O_RDWR | O_NOFOLLOW | O_CLOEXEC, 0600);
    if (fd < 0) goto done;
    struct stat opened;
    struct stat named;
    if (fstat(fd, &opened) != 0 || !S_ISREG(opened.st_mode) || opened.st_nlink != 1 ||
        opened.st_uid != geteuid() || fstatat(dir_fd, LOCK_FILE_NAME, &named, AT_SYMLINK_NOFOLLOW) != 0 ||
        !S_ISREG(named.st_mode) || named.st_dev != opened.st_dev || named.st_ino != opened.st_ino) {
        goto done;
    }
    int locked;
    do {
        locked = flock(fd, LOCK_EX | LOCK_NB);
    } while (locked != 0 && errno == EINTR);
    if (locked != 0) {
        if (errno == EWOULDBLOCK || errno == EAGAIN) result = LOCK_BUSY;
        goto done;
    }
    if (fstat(fd, &opened) != 0 || opened.st_nlink != 1 ||
        fstatat(dir_fd, LOCK_FILE_NAME, &named, AT_SYMLINK_NOFOLLOW) != 0 ||
        !S_ISREG(named.st_mode) || named.st_dev != opened.st_dev || named.st_ino != opened.st_ino) {
        goto done;
    }
    owner_fd = fd;
    owner_device = opened.st_dev;
    owner_inode = opened.st_ino;
    result = fd;
    fd = -1;

done:
    if (fd >= 0 && close(fd) != 0) quarantined = true;
    if (dir_fd >= 0 && close(dir_fd) != 0) quarantined = true;
    if (quarantined) result = LOCK_UNAVAILABLE;
    pthread_mutex_unlock(&owner_mutex);
    (*env)->ReleaseStringUTFChars(env, directory, path);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_seraf_vpnsweep_searchworker_SearchOwnerLock_nativeDuplicate(
    JNIEnv *env, jobject self, jint fd) {
    (void)env;
    (void)self;
    pthread_mutex_lock(&owner_mutex);
    int result = LOCK_UNAVAILABLE;
    if (!quarantined && owner_fd == fd) {
        if (!same_file(fd)) {
            quarantined = true;
        } else {
            result = fcntl(fd, F_DUPFD_CLOEXEC, 0);
        }
    }
    pthread_mutex_unlock(&owner_mutex);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_seraf_vpnsweep_searchworker_SearchOwnerLock_nativeClose(
    JNIEnv *env, jobject self, jint fd) {
    (void)env;
    (void)self;
    pthread_mutex_lock(&owner_mutex);
    int result = LOCK_UNAVAILABLE;
    if (!quarantined && owner_fd == fd) {
        if (!same_file(fd) || close(fd) != 0) {
            quarantined = true;
        } else {
            owner_fd = -1;
            result = 0;
        }
    }
    pthread_mutex_unlock(&owner_mutex);
    return result;
}

JNIEXPORT jint JNICALL
Java_com_seraf_vpnsweep_searchworker_SearchOwnerLock_nativeDiscardDuplicate(
    JNIEnv *env, jobject self, jint fd) {
    (void)env;
    (void)self;
    pthread_mutex_lock(&owner_mutex);
    int result = LOCK_UNAVAILABLE;
    if (fd >= 0 && fd != owner_fd) {
        if (close(fd) == 0) result = 0;
        else quarantined = true;
    }
    pthread_mutex_unlock(&owner_mutex);
    return result;
}

JNIEXPORT jboolean JNICALL
Java_com_seraf_vpnsweep_searchworker_SearchOwnerLock_nativeOwnerMayLoad(
    JNIEnv *env, jobject self, jint fd) {
    (void)env;
    (void)self;
    pthread_mutex_lock(&owner_mutex);
    bool allowed = !quarantined && owner_fd == fd && same_file(fd) && environment_safe();
    pthread_mutex_unlock(&owner_mutex);
    return allowed ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_seraf_vpnsweep_searchworker_SearchOwnerLock_nativeWorkerFileAndEnvironmentValid(
    JNIEnv *env, jobject self, jstring directory, jint fd) {
    (void)self;
    if (directory == NULL || fd < 0) return JNI_FALSE;
    const char *path = (*env)->GetStringUTFChars(env, directory, NULL);
    if (path == NULL) return JNI_FALSE;
    int dir_fd = open(path, O_RDONLY | O_DIRECTORY | O_NOFOLLOW | O_CLOEXEC);
    struct stat held;
    struct stat named;
    bool valid = dir_fd >= 0 && fstat(fd, &held) == 0 && S_ISREG(held.st_mode) &&
                 held.st_nlink == 1 && held.st_uid == geteuid() &&
                 fstatat(dir_fd, LOCK_FILE_NAME, &named, AT_SYMLINK_NOFOLLOW) == 0 &&
                 S_ISREG(named.st_mode) && held.st_dev == named.st_dev && held.st_ino == named.st_ino &&
                 environment_safe();
    if (valid) {
        int locked;
        do {
            locked = flock(fd, LOCK_EX | LOCK_NB);
        } while (locked != 0 && errno == EINTR);
        valid = locked == 0 && fstat(fd, &held) == 0 && held.st_nlink == 1 &&
                fstatat(dir_fd, LOCK_FILE_NAME, &named, AT_SYMLINK_NOFOLLOW) == 0 &&
                S_ISREG(named.st_mode) && held.st_dev == named.st_dev && held.st_ino == named.st_ino;
    }
    if (dir_fd >= 0 && close(dir_fd) != 0) valid = false;
    (*env)->ReleaseStringUTFChars(env, directory, path);
    return valid ? JNI_TRUE : JNI_FALSE;
}
