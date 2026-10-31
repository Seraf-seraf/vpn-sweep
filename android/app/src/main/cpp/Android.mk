LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := searchownerlock
LOCAL_SRC_FILES := search_owner_lock.c
LOCAL_CFLAGS := -Wall -Wextra -Werror
include $(BUILD_SHARED_LIBRARY)
