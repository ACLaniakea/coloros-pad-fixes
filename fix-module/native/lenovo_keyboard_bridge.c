/*
 * Lenovo Keyboard Pack private-key bridge.
 *
 * The TB710FU keyboard driver reports three private consumer usages and the
 * microphone key as MSC_SCAN + KEY_UNKNOWN.  The product keylayout already
 * maps the microphone key itself; this process mirrors only the three missing
 * private buttons through uinput as standard Linux key codes.  It does not
 * grab the keyboard and performs no timer polling.
 */
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/file.h>
#include <sys/inotify.h>
#include <sys/ioctl.h>
#include <unistd.h>

#define TARGET_NAME "Lenovo Keyboard Pack For Yoga Tab Keyboard"
#define BRIDGE_NAME "Lenovo Keyboard Pack Private Key Bridge"
#define LOCK_PATH "/data/adb/lenovo-keyboard-bridge.lock"

static int open_target(void) {
    DIR *dir = opendir("/dev/input");
    if (!dir) return -1;
    struct dirent *entry;
    int result = -1;
    while ((entry = readdir(dir)) != NULL) {
        if (strncmp(entry->d_name, "event", 5) != 0) continue;
        char path[128];
        snprintf(path, sizeof(path), "/dev/input/%s", entry->d_name);
        int fd = open(path, O_RDONLY | O_CLOEXEC | O_NONBLOCK);
        if (fd < 0) continue;
        char name[256] = {0};
        if (ioctl(fd, EVIOCGNAME(sizeof(name)), name) >= 0 &&
                strcmp(name, TARGET_NAME) == 0) {
            int flags = fcntl(fd, F_GETFL, 0);
            if (flags >= 0) fcntl(fd, F_SETFL, flags & ~O_NONBLOCK);
            result = fd;
            break;
        }
        close(fd);
    }
    closedir(dir);
    return result;
}

static int create_uinput(void) {
    int fd = open("/dev/uinput", O_WRONLY | O_CLOEXEC | O_NONBLOCK);
    if (fd < 0) return -1;
    if (ioctl(fd, UI_SET_EVBIT, EV_KEY) < 0 ||
            ioctl(fd, UI_SET_KEYBIT, KEY_F13) < 0 ||
            ioctl(fd, UI_SET_KEYBIT, KEY_F14) < 0 ||
            ioctl(fd, UI_SET_KEYBIT, KEY_F15) < 0) {
        close(fd);
        return -1;
    }
    struct uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    setup.id.bustype = BUS_USB;
    setup.id.vendor = 0x17ef;
    setup.id.product = 0x6271;
    setup.id.version = 0x0010;
    snprintf(setup.name, sizeof(setup.name), "%s", BRIDGE_NAME);
    if (ioctl(fd, UI_DEV_SETUP, &setup) < 0 || ioctl(fd, UI_DEV_CREATE) < 0) {
        close(fd);
        return -1;
    }
    usleep(100000);
    return fd;
}

static int mapped_key(unsigned int usage) {
    switch (usage) {
        case 0x000c0394: return KEY_F13;
        case 0x000c0395: return KEY_F14;
        case 0x000c0398: return KEY_F15;
        default: return -1;
    }
}

static int emit_key(int fd, int code, int value) {
    struct input_event events[2];
    memset(events, 0, sizeof(events));
    events[0].type = EV_KEY;
    events[0].code = (unsigned short)code;
    events[0].value = value;
    events[1].type = EV_SYN;
    events[1].code = SYN_REPORT;
    return write(fd, events, sizeof(events)) == (ssize_t)sizeof(events) ? 0 : -1;
}

static void wait_for_input_change(void) {
    int fd = inotify_init1(IN_CLOEXEC);
    if (fd < 0) return;
    if (inotify_add_watch(fd, "/dev/input", IN_CREATE | IN_ATTRIB | IN_MOVED_TO) >= 0) {
        char buffer[512];
        (void)read(fd, buffer, sizeof(buffer));
    }
    close(fd);
}

int main(void) {
    int lockfd = open(LOCK_PATH, O_CREAT | O_RDWR | O_CLOEXEC, 0600);
    if (lockfd < 0 || flock(lockfd, LOCK_EX | LOCK_NB) < 0) return 0;

    int output = create_uinput();
    if (output < 0) {
        fprintf(stderr, "lenovo-keyboard-bridge: cannot create uinput: %s\n", strerror(errno));
        return 1;
    }
    fprintf(stderr, "lenovo-keyboard-bridge: ready\n");

    for (;;) {
        int input = open_target();
        if (input < 0) {
            wait_for_input_change();
            continue;
        }
        unsigned int usage = 0;
        struct input_event event;
        while (read(input, &event, sizeof(event)) == (ssize_t)sizeof(event)) {
            if (event.type == EV_MSC && event.code == MSC_SCAN) {
                usage = (unsigned int)event.value;
            } else if (event.type == EV_KEY && event.code == KEY_UNKNOWN) {
                int code = mapped_key(usage);
                if (code >= 0) (void)emit_key(output, code, event.value);
            } else if (event.type == EV_SYN && event.code == SYN_REPORT) {
                usage = 0;
            }
        }
        close(input);
    }
}
