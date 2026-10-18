#include <errno.h>
#include <jni.h>
#include <linux/usbdevice_fs.h>
#include <poll.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>
#include <sys/ioctl.h>

#define LOG_TAG "UacIso"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#define MAX_URBS 8
#define MAX_PACKETS 32

struct IsoReader {
    int fd;
    int packet_size;
    int packets;
    int count;
    struct usbdevfs_urb *urbs[MAX_URBS];
};

static struct usbdevfs_urb *make_urb(int endpoint, int packet_size, int packets) {
    size_t bytes = sizeof(struct usbdevfs_urb) + (size_t) packets * sizeof(struct usbdevfs_iso_packet_desc);
    struct usbdevfs_urb *urb = calloc(1, bytes);
    void *buffer;
    int i;
    if (urb == NULL) return NULL;
    buffer = malloc((size_t) packet_size * (size_t) packets);
    if (buffer == NULL) {
        free(urb);
        return NULL;
    }
    urb->type = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint = (unsigned char) endpoint;
    urb->flags = USBDEVFS_URB_ISO_ASAP;
    urb->buffer = buffer;
    urb->buffer_length = packet_size * packets;
    urb->number_of_packets = packets;
    urb->usercontext = urb;
    for (i = 0; i < packets; i++) {
        urb->iso_frame_desc[i].length = (unsigned int) packet_size;
    }
    return urb;
}

static void free_urb(struct usbdevfs_urb *urb) {
    if (urb == NULL) return;
    free(urb->buffer);
    urb->buffer = NULL;
    free(urb);
}

static void drain(struct IsoReader *reader) {
    int spins;
    for (spins = 0; spins < reader->count + 2; spins++) {
        struct pollfd pfd;
        struct usbdevfs_urb *done = NULL;
        int rc;
        pfd.fd = reader->fd;
        pfd.events = POLLOUT;
        pfd.revents = 0;
        poll(&pfd, 1, 50);
        rc = ioctl(reader->fd, USBDEVFS_REAPURBNDELAY, &done);
        if (rc < 0 && (errno == EAGAIN || errno == ENODEV || errno == EBADF)) break;
    }
}

static void shutdown_reader(struct IsoReader *reader) {
    int i;
    if (reader == NULL) return;
    for (i = 0; i < reader->count; i++) {
        if (reader->urbs[i] != NULL) {
            ioctl(reader->fd, USBDEVFS_DISCARDURB, reader->urbs[i]);
        }
    }
    drain(reader);
    for (i = 0; i < reader->count; i++) {
        free_urb(reader->urbs[i]);
        reader->urbs[i] = NULL;
    }
    free(reader);
}

static void reset_urb(struct usbdevfs_urb *urb, int packet_size) {
    int i;
    urb->status = 0;
    urb->actual_length = 0;
    urb->error_count = 0;
    for (i = 0; i < urb->number_of_packets; i++) {
        urb->iso_frame_desc[i].length = (unsigned int) packet_size;
        urb->iso_frame_desc[i].actual_length = 0;
        urb->iso_frame_desc[i].status = 0;
    }
}

JNIEXPORT jlong JNICALL
Java_com_hcc_dvdplayer_audio_IsochronousReader_nativeStart(
        JNIEnv *env, jobject thiz,
        jint fd, jint endpoint, jint packet_size, jint packets, jint urb_count,
        jint interface_number, jint alternate) {
    struct IsoReader *reader;
    int i;
    (void) env;
    (void) thiz;
    if (fd < 0 || packet_size < 1 || packet_size > 8192) return 0;
    if (packets < 1 || packets > MAX_PACKETS) return 0;
    if (urb_count < 1 || urb_count > MAX_URBS) return 0;

    if (interface_number >= 0) {
        struct usbdevfs_setinterface setting;
        setting.interface = (unsigned int) interface_number;
        setting.altsetting = (unsigned int) alternate;
        if (ioctl(fd, USBDEVFS_SETINTERFACE, &setting) < 0) {
            LOGE("SETINTERFACE if=%d alt=%d errno=%d", interface_number, alternate, errno);
        }
    }

    reader = calloc(1, sizeof(*reader));
    if (reader == NULL) return 0;
    reader->fd = fd;
    reader->packet_size = packet_size;
    reader->packets = packets;
    reader->count = urb_count;
    for (i = 0; i < urb_count; i++) {
        reader->urbs[i] = make_urb(endpoint, packet_size, packets);
        if (reader->urbs[i] == NULL || ioctl(fd, USBDEVFS_SUBMITURB, reader->urbs[i]) < 0) {
            LOGE("SUBMITURB %d errno=%d", i, errno);
            shutdown_reader(reader);
            return 0;
        }
    }
    LOGI("iso ep=0x%02x packet=%d x %d urbs=%d", endpoint & 0xff, packet_size, packets, urb_count);
    return (jlong) (intptr_t) reader;
}

JNIEXPORT jint JNICALL
Java_com_hcc_dvdplayer_audio_IsochronousReader_nativeRead(
        JNIEnv *env, jobject thiz, jlong handle, jbyteArray buffer, jint timeout_ms) {
    struct IsoReader *reader = (struct IsoReader *) (intptr_t) handle;
    struct pollfd pfd;
    struct usbdevfs_urb *done = NULL;
    jbyte *out;
    jsize cap;
    int copied = 0;
    int i;
    int rc;
    (void) thiz;
    if (reader == NULL || buffer == NULL) return -1;

    pfd.fd = reader->fd;
    pfd.events = POLLOUT;
    pfd.revents = 0;
    rc = poll(&pfd, 1, timeout_ms < 0 ? 0 : timeout_ms);
    if (rc == 0) return 0;
    if (rc < 0) return errno == EINTR ? 0 : -1;

    rc = ioctl(reader->fd, USBDEVFS_REAPURBNDELAY, &done);
    if (rc < 0) {
        if (errno == EAGAIN || errno == EINTR) return 0;
        LOGE("REAPURB errno=%d", errno);
        return -1;
    }
    if (done == NULL || done->buffer == NULL) return 0;

    cap = (*env)->GetArrayLength(env, buffer);
    out = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (out == NULL) {
        reset_urb(done, reader->packet_size);
        if (ioctl(reader->fd, USBDEVFS_SUBMITURB, done) < 0) return -1;
        return -1;
    }
    for (i = 0; i < done->number_of_packets && copied < cap; i++) {
        unsigned int n = done->iso_frame_desc[i].actual_length;
        if (n > (unsigned int) reader->packet_size) n = (unsigned int) reader->packet_size;
        if (copied + (int) n > cap) n = (unsigned int) (cap - copied);
        if (n == 0) continue;
        memcpy(out + copied, (char *) done->buffer + (size_t) i * (size_t) reader->packet_size, n);
        copied += (int) n;
    }
    (*env)->ReleaseByteArrayElements(env, buffer, out, 0);

    reset_urb(done, reader->packet_size);
    if (ioctl(reader->fd, USBDEVFS_SUBMITURB, done) < 0) {
        LOGE("resubmit errno=%d", errno);
        return -1;
    }
    return copied;
}

JNIEXPORT void JNICALL
Java_com_hcc_dvdplayer_audio_IsochronousReader_nativeStop(JNIEnv *env, jobject thiz, jlong handle) {
    (void) env;
    (void) thiz;
    shutdown_reader((struct IsoReader *) (intptr_t) handle);
}
