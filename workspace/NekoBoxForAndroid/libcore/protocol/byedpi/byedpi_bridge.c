//go:build android && cgo

#include <android/log.h>
#include <errno.h>
#include <pthread.h>
#include <sched.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <sys/socket.h>
#include <unistd.h>

#include "../../../../byedpi/proxy.h"

int byedpi_embedded_main(int argc, char **argv);

#define BYEDPI_BRIDGE_TAG "NB4A-ByeDPI"

struct byedpi_runner {
    pthread_t thread;
    int argc;
    char **argv;
    int exit_code;
    int finished;
    int server_fd;
    int control_fd;
    int event_fd;
    int ready;
    int private_mode;
    char *last_error;
    pthread_mutex_t mutex;
};

static __thread struct byedpi_runner *current_runner;

int byedpi_android_take_control_fd(void) {
    struct byedpi_runner *runner = current_runner;
    if (runner == NULL || !runner->private_mode) {
        return -1;
    }
    int event_fd = runner->event_fd;
    runner->event_fd = -1;
    return event_fd;
}

int byedpi_android_control_mode(void) {
    return current_runner != NULL && current_runner->private_mode;
}

int byedpi_android_receive_client(int control_fd, int *stream_fd, int *udp_fd) {
    *stream_fd = -1;
    *udp_fd = -1;
    uint8_t command = 0;
    struct iovec iov = {
        .iov_base = &command,
        .iov_len = sizeof(command),
    };
    char control[CMSG_SPACE(sizeof(int) * 2)] = {0};
    struct msghdr message = {
        .msg_iov = &iov,
        .msg_iovlen = 1,
        .msg_control = control,
        .msg_controllen = sizeof(control),
    };
    ssize_t size = recvmsg(control_fd, &message, MSG_DONTWAIT | MSG_CMSG_CLOEXEC);
    if (size < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
        return 1;
    }
    if (size <= 0 || command == 0) {
        return -1;
    }
    struct cmsghdr *header = CMSG_FIRSTHDR(&message);
    size_t descriptor_count = 0;
    int *descriptors = NULL;
    if (header != NULL &&
            header->cmsg_level == SOL_SOCKET &&
            header->cmsg_type == SCM_RIGHTS &&
            header->cmsg_len >= CMSG_LEN(0) &&
            header->cmsg_len <= sizeof(control)) {
        descriptor_count =
            (header->cmsg_len - CMSG_LEN(0)) / sizeof(int);
        descriptors = (int *)CMSG_DATA(header);
    }
    if ((message.msg_flags & (MSG_TRUNC | MSG_CTRUNC)) != 0 ||
            (command != 1 && command != 2) ||
            descriptors == NULL ||
            descriptor_count != (command == 2 ? 2 : 1) ||
            CMSG_NXTHDR(&message, header) != NULL) {
        for (size_t i = 0; i < descriptor_count; i++) {
            close(descriptors[i]);
        }
        return 1;
    }
    *stream_fd = descriptors[0];
    *udp_fd = command == 2 ? descriptors[1] : -1;
    return 0;
}

void byedpi_android_listener_ready(int server_fd) {
    struct byedpi_runner *runner = current_runner;
    if (runner == NULL) {
        return;
    }
    pthread_mutex_lock(&runner->mutex);
    runner->server_fd = server_fd;
    runner->ready = 1;
    pthread_mutex_unlock(&runner->mutex);
    __android_log_print(ANDROID_LOG_INFO, BYEDPI_BRIDGE_TAG, "private bridge ready fd=%d", server_fd);
}

static int byedpi_runner_copy_argv(struct byedpi_runner *runner, int argc, char **argv) {
    runner->argv = calloc((size_t)argc, sizeof(*runner->argv));
    if (runner->argv == NULL) {
        return -1;
    }
    for (int i = 0; i < argc; i++) {
        size_t length = strlen(argv[i]) + 1;
        runner->argv[i] = malloc(length);
        if (runner->argv[i] == NULL) {
            return -1;
        }
        memcpy(runner->argv[i], argv[i], length);
    }
    return 0;
}

static void byedpi_runner_free_argv(struct byedpi_runner *runner) {
    if (runner->argv == NULL) {
        return;
    }
    for (int i = 0; i < runner->argc; i++) {
        free(runner->argv[i]);
    }
    free(runner->argv);
    runner->argv = NULL;
}

static void *byedpi_runner_thread(void *arg) {
    struct byedpi_runner *runner = (struct byedpi_runner *)arg;
    current_runner = runner;
    __android_log_print(ANDROID_LOG_INFO, BYEDPI_BRIDGE_TAG, "runner start argc=%d", runner->argc);
    int exit_code = byedpi_embedded_main(runner->argc, runner->argv);
    current_runner = NULL;
    __android_log_print(ANDROID_LOG_INFO, BYEDPI_BRIDGE_TAG, "runner exit=%d", exit_code);
    pthread_mutex_lock(&runner->mutex);
    runner->exit_code = exit_code;
    runner->finished = 1;
    pthread_mutex_unlock(&runner->mutex);
    return NULL;
}

struct byedpi_runner *byedpi_runner_start(int argc, char **argv) {
    struct byedpi_runner *runner = calloc(1, sizeof(*runner));
    if (runner == NULL) {
        return NULL;
    }
    runner->argc = argc;
    runner->server_fd = -1;
    runner->control_fd = -1;
    runner->event_fd = -1;
    runner->private_mode = 1;
    int control_pair[2];
    if (socketpair(
            AF_UNIX, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC,
            0, control_pair) != 0) {
        free(runner);
        return NULL;
    }
    runner->control_fd = control_pair[0];
    runner->event_fd = control_pair[1];
    if (byedpi_runner_copy_argv(runner, argc, argv) != 0) {
        close(runner->control_fd);
        close(runner->event_fd);
        byedpi_runner_free_argv(runner);
        free(runner);
        return NULL;
    }
    pthread_mutex_init(&runner->mutex, NULL);
    if (pthread_create(&runner->thread, NULL, byedpi_runner_thread, runner) != 0) {
        close(runner->control_fd);
        close(runner->event_fd);
        byedpi_runner_free_argv(runner);
        pthread_mutex_destroy(&runner->mutex);
        free(runner);
        return NULL;
    }
    return runner;
}

int byedpi_runner_wait_ready(struct byedpi_runner *runner, int timeout_ms) {
    int waited = 0;
    while (waited <= timeout_ms) {
        pthread_mutex_lock(&runner->mutex);
        int ready = runner->ready;
        int finished = runner->finished;
        pthread_mutex_unlock(&runner->mutex);
        if (ready) {
            __android_log_print(ANDROID_LOG_INFO, BYEDPI_BRIDGE_TAG, "private bridge ready after %dms", waited);
            return 1;
        }
        if (finished) {
            __android_log_print(ANDROID_LOG_WARN, BYEDPI_BRIDGE_TAG, "runner finished before private bridge was ready");
            return 0;
        }
        usleep(10 * 1000);
        waited += 10;
    }
    __android_log_print(ANDROID_LOG_WARN, BYEDPI_BRIDGE_TAG, "private bridge wait timed out after %dms", timeout_ms);
    return 0;
}

int byedpi_runner_open_connection(
        struct byedpi_runner *runner, int with_udp,
        int *stream_fd, int *udp_fd) {
    if (runner == NULL || stream_fd == NULL || udp_fd == NULL) {
        return -1;
    }
    *stream_fd = -1;
    *udp_fd = -1;
    int stream_pair[2] = {-1, -1};
    int datagram_pair[2] = {-1, -1};
    if (socketpair(
            AF_UNIX, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC,
            0, stream_pair) != 0) {
        return -1;
    }
    if (with_udp &&
            socketpair(
                AF_UNIX, SOCK_DGRAM | SOCK_NONBLOCK | SOCK_CLOEXEC,
                0, datagram_pair) != 0) {
        close(stream_pair[0]);
        close(stream_pair[1]);
        return -1;
    }

    int descriptors[2] = {stream_pair[1], datagram_pair[1]};
    uint8_t command = with_udp ? 2 : 1;
    struct iovec iov = {
        .iov_base = &command,
        .iov_len = sizeof(command),
    };
    char control[CMSG_SPACE(sizeof(descriptors))] = {0};
    struct msghdr message = {
        .msg_iov = &iov,
        .msg_iovlen = 1,
        .msg_control = control,
        .msg_controllen = CMSG_SPACE(sizeof(int) * (with_udp ? 2 : 1)),
    };
    struct cmsghdr *header = CMSG_FIRSTHDR(&message);
    header->cmsg_level = SOL_SOCKET;
    header->cmsg_type = SCM_RIGHTS;
    header->cmsg_len = CMSG_LEN(sizeof(int) * (with_udp ? 2 : 1));
    memcpy(CMSG_DATA(header), descriptors, sizeof(int) * (with_udp ? 2 : 1));

    pthread_mutex_lock(&runner->mutex);
    int control_fd = runner->control_fd;
    int finished = runner->finished;
    ssize_t sent = finished ? -1 : sendmsg(control_fd, &message, MSG_NOSIGNAL);
    pthread_mutex_unlock(&runner->mutex);
    close(stream_pair[1]);
    if (with_udp) {
        close(datagram_pair[1]);
    }
    if (sent != sizeof(command)) {
        close(stream_pair[0]);
        if (with_udp) {
            close(datagram_pair[0]);
        }
        return -1;
    }
    *stream_fd = stream_pair[0];
    *udp_fd = with_udp ? datagram_pair[0] : -1;
    return 0;
}

int byedpi_runner_stop(struct byedpi_runner *runner) {
    if (runner == NULL) {
        return -1;
    }
    pthread_mutex_lock(&runner->mutex);
    int control_fd = runner->control_fd;
    pthread_mutex_unlock(&runner->mutex);
    uint8_t command = 0;
    int result = control_fd >= 0 &&
        send(control_fd, &command, sizeof(command), MSG_NOSIGNAL) == sizeof(command)
        ? 0 : -1;
    __android_log_print(ANDROID_LOG_INFO, BYEDPI_BRIDGE_TAG, "runner stop result=%d", result);
    return result;
}

int byedpi_runner_join(struct byedpi_runner *runner) {
    int exit_code = -1;
    if (runner == NULL) {
        return exit_code;
    }
    pthread_join(runner->thread, NULL);
    pthread_mutex_lock(&runner->mutex);
    exit_code = runner->exit_code;
    pthread_mutex_unlock(&runner->mutex);
    return exit_code;
}

const char *byedpi_runner_last_error(struct byedpi_runner *runner) {
    if (runner == NULL || runner->last_error == NULL) {
        return "";
    }
    return runner->last_error;
}

void byedpi_runner_free(struct byedpi_runner *runner) {
    if (runner == NULL) {
        return;
    }
    free(runner->last_error);
    if (runner->control_fd >= 0) {
        close(runner->control_fd);
    }
    if (runner->event_fd >= 0) {
        close(runner->event_fd);
    }
    byedpi_runner_free_argv(runner);
    pthread_mutex_destroy(&runner->mutex);
    free(runner);
}
