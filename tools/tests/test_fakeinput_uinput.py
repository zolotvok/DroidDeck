import os
import re
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

SOURCE = Path(__file__).resolve().parents[2] / "app/src/main/cpp/fakeinput_steam.cpp"

# Drives the faked /dev/uinput the way Steam Input does and reads the result back
# through the faked /dev/input/eventN in the same process.
PROGRAM = r"""
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <unistd.h>

static const char *dir;

static int make_pad(const char *name, int product) {
  int fd = open("/dev/uinput", O_RDWR | O_NONBLOCK);
  assert(fd >= 0);
  assert(ioctl(fd, UI_SET_EVBIT, EV_KEY) == 0);
  assert(ioctl(fd, UI_SET_KEYBIT, BTN_SOUTH) == 0);
  assert(ioctl(fd, UI_SET_EVBIT, EV_ABS) == 0);
  assert(ioctl(fd, UI_SET_ABSBIT, ABS_X) == 0);
  struct uinput_setup setup;
  memset(&setup, 0, sizeof(setup));
  snprintf(setup.name, sizeof(setup.name), "%s", name);
  setup.id.bustype = BUS_USB;
  setup.id.vendor = 0x28de;
  setup.id.product = product;
  assert(ioctl(fd, UI_DEV_SETUP, &setup) == 0);
  assert(ioctl(fd, UI_DEV_CREATE) == 0);
  return fd;
}

static void destroy(int fd) {
  assert(ioctl(fd, UI_DEV_DESTROY) == 0);
  assert(close(fd) == 0);
}

static void emit(int fd, int type, int code, int value) {
  struct input_event ev;
  memset(&ev, 0, sizeof(ev));
  ev.type = type;
  ev.code = code;
  ev.value = value;
  assert(write(fd, &ev, sizeof(ev)) == sizeof(ev));
}

static int node_exists(int node) {
  char path[512];
  snprintf(path, sizeof(path), "%s/event%d", dir, node);
  struct stat st;
  return lstat(path, &st) == 0;
}

/* Reads until it sees EV_KEY BTN_SOUTH; returns its value, or -errno. */
static int read_south(int reader) {
  struct input_event ev[64];
  for (int tries = 0; tries < 200; tries++) {
    ssize_t n = read(reader, ev, sizeof(ev));
    if (n < 0) {
      if (errno == EAGAIN) { usleep(1000); continue; }
      return -errno;
    }
    int last = -1;
    for (size_t i = 0; i < n / sizeof(ev[0]); i++)
      if (ev[i].type == EV_KEY && ev[i].code == BTN_SOUTH) last = ev[i].value;
    if (last >= 0) return last;
  }
  return -ETIMEDOUT;
}

static void drain(int reader) {
  struct input_event ev[64];
  while (read(reader, ev, sizeof(ev)) > 0) {}
}

int main(int argc, char **argv) {
  dir = getenv("FAKE_EVDEV_DIR");
  const char *mode = argv[1];
  int pad = make_pad("Microsoft X-Box 360 pad 0", 0x11ff);
  assert(node_exists(16));
  int reader = open("/dev/input/event16", O_RDONLY | O_NONBLOCK);
  assert(reader >= 0);
  drain(reader);

  if (!strcmp(mode, "keep")) {
    emit(pad, EV_KEY, BTN_SOUTH, 1);
    emit(pad, EV_SYN, SYN_REPORT, 0);
    assert(read_south(reader) == 1);
    destroy(pad);
    assert(node_exists(16));
    /* The destroyed pad releases what it held. */
    assert(read_south(reader) == 0);
    pad = make_pad("Microsoft X-Box 360 pad 0", 0x11ff);
    assert(node_exists(16) && !node_exists(17));
    emit(pad, EV_KEY, BTN_SOUTH, 1);
    emit(pad, EV_SYN, SYN_REPORT, 0);
    assert(read_south(reader) == 1);
    usleep(2500 * 1000);
    /* Still the live pad after the window. */
    assert(node_exists(16));
    emit(pad, EV_KEY, BTN_SOUTH, 0);
    emit(pad, EV_SYN, SYN_REPORT, 0);
    assert(read_south(reader) == 0);
    destroy(pad);
    usleep(2500 * 1000);
    assert(!node_exists(16));
    assert(read_south(reader) == -ENODEV);
  } else if (!strcmp(mode, "timeout")) {
    destroy(pad);
    assert(node_exists(16));
    usleep(2500 * 1000);
    assert(!node_exists(16));
    assert(read_south(reader) == -ENODEV);
  } else if (!strcmp(mode, "other")) {
    destroy(pad);
    pad = make_pad("Microsoft X-Box 360 pad 0", 0x1234);
    /* A different device in the same slot: the old node goes at once. */
    assert(read_south(reader) == -ENODEV);
    assert(node_exists(16));
    int fresh = open("/dev/input/event16", O_RDONLY | O_NONBLOCK);
    assert(fresh >= 0);
    close(fresh);
    destroy(pad);
  } else if (!strcmp(mode, "count")) {
    /* 100 writes inside one second give one counter line for this fd. */
    for (int i = 0; i < 50; i++) {
      emit(pad, EV_ABS, ABS_X, i);
      emit(pad, EV_SYN, SYN_REPORT, 0);
    }
    assert(ioctl(pad, UI_DEV_DESTROY) == 0);
    /* An event write into the destroyed device fails as on a kernel and is counted. */
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = EV_ABS;
    ev.code = ABS_X;
    ev.value = 5;
    assert(write(pad, &ev, sizeof(ev)) < 0 && errno == EINVAL);
    int fresh = make_pad("Microsoft X-Box 360 pad 0", 0x11ff);
    emit(fresh, EV_ABS, ABS_X, 7);
    emit(fresh, EV_SYN, SYN_REPORT, 0);
    usleep(1100 * 1000);
    emit(fresh, EV_ABS, ABS_X, 8);
    emit(fresh, EV_SYN, SYN_REPORT, 0);
    printf("fds %d %d\n", pad, fresh);
    fflush(stdout);
    assert(close(pad) == 0);
    destroy(fresh);
  } else if (!strcmp(mode, "exit")) {
    /* A clean exit inside the window still removes the node. */
    destroy(pad);
    assert(node_exists(16));
    exit(0);
  }
  close(reader);
  return 0;
}
"""


# Runs as an executable named "steam" (the library only fakes the Deck for the Steam
# client): a fake Deck on /dev/hidraw16 fed from ring0, a virtual pad that Steam
# re-creates (adopt), then Deck input with nothing written into the pad (dead mark),
# a guide tap requested through .padapply/guide, and the pad fed again.
PADAPPLY_PROGRAM = r"""
#include <assert.h>
#include <errno.h>
#include <fcntl.h>
#include <linux/input.h>
#include <linux/uinput.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

#define RING_SIZE (64 + 4096 * 24)
#define DECK_STEAM 0x00002000u

static const char *dir;
static uint8_t *ring;

static int make_pad(void) {
  int fd = open("/dev/uinput", O_RDWR | O_NONBLOCK);
  assert(fd >= 0);
  assert(ioctl(fd, UI_SET_EVBIT, EV_KEY) == 0);
  assert(ioctl(fd, UI_SET_KEYBIT, BTN_SOUTH) == 0);
  assert(ioctl(fd, UI_SET_EVBIT, EV_ABS) == 0);
  assert(ioctl(fd, UI_SET_ABSBIT, ABS_X) == 0);
  struct uinput_setup setup;
  memset(&setup, 0, sizeof(setup));
  snprintf(setup.name, sizeof(setup.name), "Microsoft X-Box 360 pad 0");
  setup.id.bustype = BUS_USB;
  setup.id.vendor = 0x28de;
  setup.id.product = 0x11ff;
  assert(ioctl(fd, UI_DEV_SETUP, &setup) == 0);
  assert(ioctl(fd, UI_DEV_CREATE) == 0);
  return fd;
}

static void emit(int fd, int type, int code, int value) {
  struct input_event ev;
  memset(&ev, 0, sizeof(ev));
  ev.type = type;
  ev.code = code;
  ev.value = value;
  assert(write(fd, &ev, sizeof(ev)) == sizeof(ev));
}

/* The app's side of ring0: one snapshot update (seqlock, as FakeInputWriter). */
static void deck_stick(int16_t y) {
  uint64_t *seq = (uint64_t *)(ring + 32);
  int16_t *axes = (int16_t *)(ring + 44);
  __atomic_store_n(seq, *seq + 1, __ATOMIC_RELEASE);
  axes[1] = y;
  __atomic_store_n(seq, *seq + 1, __ATOMIC_RELEASE);
}

static void state(char *out, size_t size) {
  char path[512];
  snprintf(path, sizeof(path), "%s/.padapply/state", dir);
  FILE *f = fopen(path, "r");
  out[0] = 0;
  if (!f) return;
  if (!fgets(out, (int)size, f)) out[0] = 0;
  fclose(f);
  out[strcspn(out, "\n")] = 0;
}

static void ms(int n) { usleep(n * 1000); }

/* The user's buttons in the snapshot (guide = bit 10, QAM = bit 11). */
static void deck_buttons(uint32_t buttons) {
  uint64_t *seq = (uint64_t *)(ring + 32);
  __atomic_store_n(seq, *seq + 1, __ATOMIC_RELEASE);
  *(uint32_t *)(ring + 40) = buttons;
  __atomic_store_n(seq, *seq + 1, __ATOMIC_RELEASE);
}

static void menu(char *out, size_t size) {
  char path[512];
  snprintf(path, sizeof(path), "%s/.padapply/menu", dir);
  FILE *f = fopen(path, "r");
  snprintf(out, size, "none");
  if (!f) return;
  if (!fgets(out, (int)size, f)) snprintf(out, size, "empty");
  fclose(f);
  out[strcspn(out, "\n")] = 0;
}

/* Drains the Deck reports; 1 if one of them holds the Steam button. */
static int saw_guide(int hid, int wait_ms) {
  int seen = 0;
  for (int t = 0; t < wait_ms; t += 2) {
    uint8_t report[64];
    ssize_t n;
    while ((n = read(hid, report, sizeof(report))) == 64) {
      uint32_t low = report[8] | report[9] << 8 | report[10] << 16 | (uint32_t)report[11] << 24;
      if (low & DECK_STEAM) seen = 1;
    }
    ms(2);
  }
  return seen;
}

int main(void) {
  dir = getenv("FAKE_EVDEV_DIR");
  char path[512];
  snprintf(path, sizeof(path), "%s/ring0", getenv("TEST_RING_DIR"));
  int rfd = open(path, O_RDWR | O_CREAT, 0644);
  assert(rfd >= 0);
  assert(ftruncate(rfd, RING_SIZE + 64 + 256) == 0);
  ring = mmap(NULL, RING_SIZE + 64 + 256, PROT_READ | PROT_WRITE, MAP_SHARED, rfd, 0);
  assert(ring != MAP_FAILED);
  uint32_t head[4] = {0x46494252, 2, sizeof(struct input_event), 4096};
  memcpy(ring, head, sizeof(head));
  *(uint64_t *)(ring + 24) = 1;

  int hid = open("/dev/hidraw16", O_RDWR | O_NONBLOCK);
  assert(hid >= 0);
  char s[256];

  /* No adopt yet: Deck input with an idle pad is no dead mark. */
  int pad = make_pad();
  deck_stick(-32767);
  ms(700);
  deck_stick(0);
  state(s, sizeof(s));
  assert(s[0] == 0);

  /* Steam re-creates its pad: the kept node is adopted and published. */
  assert(ioctl(pad, UI_DEV_DESTROY) == 0);
  close(pad);
  pad = make_pad();
  state(s, sizeof(s));
  printf("adopt %s\n", s);

  /* Stick held 700 ms, nothing written into the pad: one dead mark. */
  deck_stick(-32767);
  ms(700);
  state(s, sizeof(s));
  printf("dead %s\n", s);

  /* The agent asks for a guide tap; a Deck report thread takes it. */
  snprintf(path, sizeof(path), "%s/.padapply/guide", dir);
  int g = open(path, O_WRONLY | O_CREAT, 0600);
  assert(g >= 0);
  close(g);
  int guide = saw_guide(hid, 400);
  struct stat st;
  printf("guide %d request %s\n", guide, stat(path, &st) == 0 ? "left" : "taken");
  state(s, sizeof(s));
  printf("tapped %s\n", s);

  /* Steam writes again: fed, and no more dead marks. */
  emit(pad, EV_ABS, ABS_X, 100);
  emit(pad, EV_SYN, SYN_REPORT, 0);
  ms(1700);
  state(s, sizeof(s));
  printf("fed %s\n", s);
  deck_stick(0);

  /* The agent's guide tap above is no user press; the user's guide and QAM presses are stamped. */
  menu(s, sizeof(s));
  printf("menu-before %s\n", s);
  struct timespec t0;
  clock_gettime(CLOCK_MONOTONIC, &t0);
  deck_buttons(1u << 10);
  ms(60);
  menu(s, sizeof(s));
  printf("menu-guide %s %lld\n", s, (long long)t0.tv_sec * 1000 + t0.tv_nsec / 1000000);
  deck_buttons(0);
  ms(60);
  deck_buttons(1u << 11);
  ms(60);
  menu(s, sizeof(s));
  printf("menu-qam %s\n", s);
  deck_buttons(0);
  return 0;
}
"""


@unittest.skipUnless(sys.platform == "linux" and shutil.which("gcc") and shutil.which("g++"),
                     "needs Linux, gcc and g++")
class FakeinputUinputTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.tmp.cleanup)
        cls.path = Path(cls.tmp.name)
        source = cls.path / "program.c"
        source.write_text(PROGRAM)
        subprocess.run(["gcc", "-o", str(cls.path / "program"), str(source)], check=True)
        subprocess.run(["g++", "-shared", "-fPIC", "-O2", "-Wall", "-Wno-attributes",
                        "-Wno-nonnull-compare", "-pthread", "-std=c++17", "-o",
                        str(cls.path / "libfakeinput.so"), str(SOURCE), "-ldl"], check=True)

    def run_mode(self, mode):
        hook = Path(tempfile.mkdtemp(dir=self.path))
        log = hook / "pad.log"
        env = dict(os.environ, LD_PRELOAD=str(self.path / "libfakeinput.so"),
                   FAKE_EVDEV_DIR=str(hook), FAKE_EVDEV_UINPUT="1", FAKE_EVDEV_LOG="1",
                   FAKE_EVDEV_LOG_FILE=str(log))
        result = subprocess.run([str(self.path / "program"), mode], env=env, check=True, timeout=20,
                                capture_output=True, text=True)
        self.stdout = result.stdout
        return hook, log.read_text() if log.exists() else ""

    def test_identical_recreate_keeps_node(self):
        _, log = self.run_mode("keep")
        self.assertIn("uinput: kept event16 across re-create", log)
        self.assertEqual(log.count("is /dev/input/event16"), 1)
        self.assertEqual(log.count("removed event16"), 1)

    def test_no_recreate_removes_after_window(self):
        _, log = self.run_mode("timeout")
        self.assertNotIn("kept event16 across", log)
        self.assertIn("removed event16", log)

    def test_other_description_unpublishes_at_once(self):
        _, log = self.run_mode("other")
        self.assertNotIn("kept event16 across", log)
        self.assertEqual(log.count("is /dev/input/event16"), 2)

    def test_write_counter_per_fd(self):
        _, log = self.run_mode("count")
        old, new = (int(x) for x in self.stdout.split()[1:3])
        lines = [l.split("uinput: ", 1)[1] for l in log.splitlines()
                 if "uinput: write " in l or "uinput: close " in l]
        name = 'name="Microsoft X-Box 360 pad 0"'
        # seq is the node's write_seq: an event sits in pending until its SYN_REPORT,
        # and the destroy adds a 2-event release frame (ABS_X 0 + SYN).
        self.maxDiff = None
        self.assertEqual(lines, [
            f"write fd={old} node=event16 gen=1 retired=0 count=1 rejected=0 seq=0 {name}",
            f"write fd={old} node=event16 gen=1 retired=1 count=100 rejected=1 seq=- {name}",
            f"write fd={new} node=event16 gen=1 retired=0 count=1 rejected=0 seq=102 {name}",
            f"write fd={new} node=event16 gen=1 retired=0 count=3 rejected=0 seq=104 {name}",
            f"close fd={old} node=event16 gen=1 retired=1 count=100 rejected=1 seq=- {name}",
            f"close fd={new} node=event16 gen=1 retired=1 count=4 rejected=0 seq=- {name}",
        ])
        self.assertIn("uinput: kept event16 across re-create", log)

    def test_padapply_adopt_dead_guide_fed(self):
        steam_dir = self.path / "padapply-bin"
        steam_dir.mkdir(exist_ok=True)
        source = steam_dir / "steam.c"
        source.write_text(PADAPPLY_PROGRAM)
        subprocess.run(["gcc", "-o", str(steam_dir / "steam"), str(source)], check=True)
        hook = Path(tempfile.mkdtemp(dir=self.path))
        rings = Path(tempfile.mkdtemp(dir=self.path))
        log = hook / "pad.log"
        env = dict(os.environ, LD_PRELOAD=str(self.path / "libfakeinput.so"),
                   FAKE_EVDEV_DIR=str(hook), FAKE_EVDEV_UINPUT="1", FAKE_EVDEV_LOG="1",
                   FAKE_EVDEV_LOG_FILE=str(log), FAKE_EVDEV_DECK="1", FAKE_EVDEV_PAD_TYPE="deck",
                   FAKE_EVDEV_MEMFD_PATHS="0=%s" % (rings / "ring0"), TEST_RING_DIR=str(rings))
        proc = subprocess.Popen([str(steam_dir / "steam")], env=env, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, text=True)
        stdout, stderr = proc.communicate(timeout=20)
        self.assertEqual(proc.returncode, 0, stderr)
        out = dict(line.split(" ", 1) for line in stdout.splitlines())
        text = log.read_text()
        # K-PADAPPLY rev 3: every state line names the Steam pid and the adopt's monotonic ms,
        # the same for all lines of one adopt.
        identity = re.search(r" pid=(\d+) at=(\d+)$", out["adopt"])
        self.assertIsNotNone(identity, out["adopt"])
        self.assertEqual(int(identity.group(1)), proc.pid)
        self.assertGreater(int(identity.group(2)), 0)
        tail = identity.group(0)
        self.assertEqual(out["adopt"], "adopts=1 node=event16 fed=0 dead=0 guide=0" + tail)
        self.assertEqual(out["dead"], "adopts=1 node=event16 fed=0 dead=1 guide=0" + tail)
        self.assertEqual(out["guide"], "1 request taken")
        self.assertEqual(out["tapped"], "adopts=1 node=event16 fed=0 dead=1 guide=1" + tail)
        # The state is written at the first fed event only (not per event); the stick is
        # still held, but fed stops the marks.
        self.assertEqual(out["fed"], "adopts=1 node=event16 fed=1 dead=1 guide=1" + tail)
        self.assertIn("padapply: adopt #1 of event16 published", text)
        self.assertIn("nothing written into event16 since adopt #1 (dead mark 1)", text)
        self.assertIn("padapply: guide tap 1 (80 ms) on request", text)
        self.assertRegex(text, r"padapply: event16 fed again \d+ ms after adopt #1 \(dead marks 1, guide taps 1\)")
        self.assertEqual(text.count("(dead mark "), 1)
        # Focus guard: only the user's own Guide/QAM presses are stamped, in CLOCK_MONOTONIC ms.
        self.assertEqual(out["menu-before"], "none")
        stamp, button, start = out["menu-guide"].split()
        self.assertEqual(button, "guide")
        self.assertGreaterEqual(int(stamp), int(start))
        self.assertLess(int(stamp) - int(start), 1000)
        self.assertRegex(out["menu-qam"], r"^\d+ qam$")

    def test_clean_exit_in_window_removes_node(self):
        hook, log = self.run_mode("exit")
        self.assertIn("removed event16", log)
        self.assertFalse((hook / "event16").exists())
        self.assertFalse((hook / ".uinput-event16").exists())


if __name__ == "__main__":
    unittest.main()
