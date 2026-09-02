// SPDX-License-Identifier: BSD-2-Clause
//
// A deliberately small Zygisk module for the ZUI camera experiment.  It does
// not hook libc and it never writes the shared Android property area.  For the
// one target process it remaps only the relevant property-area pages private
// (COW), then changes existing read-only prop_info records in place.
//
// The ABI declarations below describe Magisk's public Zygisk v1 ABI.  They are
// kept local rather than importing another identity-spoofing module.

using u32 = unsigned int;
using usize = __SIZE_TYPE__;
using uptr = __UINTPTR_TYPE__;

struct JNIEnv { const void* const* functions; };
// The public Zygisk ABI stores references to these required fields. nice_name
// is the eighth reference (offset 56 on arm64); use it in pre-specialization,
// because /proc/self/cmdline is still "zygote64" in post-specialization.
struct AppSpecializeArgs {
  void* uid;
  void* gid;
  void* gids;
  void* runtime_flags;
  void* rlimits;
  void* mount_external;
  void* se_info;
  void* nice_name;
};
struct ServerSpecializeArgs;

struct ModuleBase {
  virtual void onLoad(void*, JNIEnv*) {}
  virtual void preAppSpecialize(AppSpecializeArgs*) {}
  virtual void postAppSpecialize(const AppSpecializeArgs*) {}
  virtual void preServerSpecialize(ServerSpecializeArgs*) {}
  virtual void postServerSpecialize(const ServerSpecializeArgs*) {}
};

struct ModuleAbi {
  long api_version;
  ModuleBase* impl;
  void (*pre_app)(ModuleBase*, AppSpecializeArgs*);
  void (*post_app)(ModuleBase*, const AppSpecializeArgs*);
  void (*pre_server)(ModuleBase*, ServerSpecializeArgs*);
  void (*post_server)(ModuleBase*, const ServerSpecializeArgs*);
};

struct ApiTable {
  void* impl;
  bool (*register_module)(ApiTable*, ModuleAbi*);
  void (*hook_jni_native_methods)();
  void (*plt_hook_register)();
  bool (*exempt_fd)(int);
  bool (*plt_hook_commit)();
  int (*connect_companion)(void*);
  void (*set_option)(void*, int);
};

extern "C" {
void* dlsym(void*, const char*);
int open(const char*, int, ...);
long read(int, void*, usize);
int close(int);
void* mmap(void*, usize, int, int, int, long);
int* __errno(void);
int __android_log_print(int, const char*, const char*, ...);
}

static constexpr int kLogInfo = 4;
static constexpr int kDlcloseModuleLibrary = 1;
static constexpr int kReadOnly = 0;
static constexpr int kProtReadWrite = 3;
static constexpr int kMapPrivateFixed = 0x02 | 0x10;
static constexpr usize kPropValueMax = 92;

struct PropInfo {
  u32 serial;
  char value[kPropValueMax];
};
static_assert(sizeof(PropInfo) == 96, "AOSP prop_info layout changed");

using FindProperty = const PropInfo* (*)(const char*);

struct IdentityProperty {
  const char* key;
  const char* value;
};

static const IdentityProperty kProperties[] = {
    {"ro.product.manufacturer", "Lenovo"},
    {"ro.product.brand", "Lenovo"},
    {"ro.product.model", "TB132"},
    {"ro.product.device", "TB710FU"},
};

struct CowedRange {
  uptr start;
  uptr end;
};
static CowedRange g_cowed_ranges[8];
static usize g_cowed_range_count;
// Camera loads a large Java/native stack and has nearly four thousand mappings;
// its build_prop page sits after the first 256 KiB of /proc/self/maps. This
// buffer is only dirtied in the selected app process after zygote fork.
static char g_maps[1024 * 1024];

static usize cstrlen(const char* text) {
  usize n = 0;
  while (text[n]) ++n;
  return n;
}

static bool equals(const char* a, const char* b) {
  for (;;) {
    if (*a != *b) return false;
    if (!*a) return true;
    ++a;
    ++b;
  }
}

static bool is_zui_camera_process(char* observed, usize observed_size) {
  char cmdline[128] = {};
  int fd = open("/proc/self/cmdline", kReadOnly);
  if (fd < 0) return false;
  long count = read(fd, cmdline, sizeof(cmdline) - 1);
  close(fd);
  if (observed_size) {
    usize i = 0;
    while (i + 1 < observed_size && cmdline[i]) {
      observed[i] = cmdline[i];
      ++i;
    }
    observed[i] = '\0';
  }
  return count > 0 && equals(cmdline, "com.zui.camera");
}

static int hex_value(char c) {
  if (c >= '0' && c <= '9') return c - '0';
  if (c >= 'a' && c <= 'f') return c - 'a' + 10;
  if (c >= 'A' && c <= 'F') return c - 'A' + 10;
  return -1;
}

static const char* parse_hex(const char* p, uptr* out) {
  uptr value = 0;
  int digit;
  while ((digit = hex_value(*p)) >= 0) {
    value = (value << 4) | static_cast<uptr>(digit);
    ++p;
  }
  *out = value;
  return p;
}

static const char* skip_spaces(const char* p) {
  while (*p == ' ') ++p;
  return p;
}

static const char* skip_word(const char* p) {
  while (*p && *p != ' ' && *p != '\n') ++p;
  return p;
}

// Copy-on-write the exact existing file mapping containing address.  Bionic's
// property context continues to hold the same virtual prop_info pointer.
static bool privatize_mapping_for(const void* address) {
  int fd = open("/proc/self/maps", kReadOnly);
  if (fd < 0) {
    __android_log_print(kLogInfo, "ZuiNativeIdentity", "COW: cannot open maps errno=%d", *__errno());
    return false;
  }
  usize read_count = 0;
  while (read_count + 1 < sizeof(g_maps)) {
    const long chunk = read(fd, g_maps + read_count, sizeof(g_maps) - read_count - 1);
    if (chunk < 0) {
      close(fd);
      __android_log_print(kLogInfo, "ZuiNativeIdentity", "COW: maps read failed errno=%d", *__errno());
      return false;
    }
    if (chunk == 0) break;
    read_count += static_cast<usize>(chunk);
  }
  close(fd);
  if (read_count == 0 || read_count + 1 >= sizeof(g_maps)) {
    __android_log_print(kLogInfo, "ZuiNativeIdentity", "COW: maps read truncated (%zu bytes)", read_count);
    return false;
  }
  g_maps[read_count] = '\0';

  const uptr target = reinterpret_cast<uptr>(address);
  for (usize i = 0; i < g_cowed_range_count; ++i) {
    if (target >= g_cowed_ranges[i].start && target < g_cowed_ranges[i].end) return true;
  }
  const char* line = g_maps;
  while (*line) {
    uptr start = 0, end = 0, offset = 0;
    const char* p = parse_hex(line, &start);
    if (*p != '-') goto next_line;
    p = parse_hex(p + 1, &end);
    p = skip_spaces(p);
    p = skip_word(p);
    p = skip_spaces(p);
    p = parse_hex(p, &offset);
    p = skip_spaces(p);
    p = skip_word(p);
    p = skip_spaces(p);
    p = skip_word(p);
    p = skip_spaces(p);
    if (target < start || target >= end || *p != '/') goto next_line;
    {
      char path[256];
      usize path_len = 0;
      while (*p && *p != '\n' && path_len + 1 < sizeof(path)) path[path_len++] = *p++;
      path[path_len] = '\0';
      if (*p != '\n' || path_len == 0) {
        __android_log_print(kLogInfo, "ZuiNativeIdentity", "COW: malformed maps line for %p", address);
        return false;
      }
      int map_fd = open(path, kReadOnly);
      if (map_fd < 0) {
        __android_log_print(kLogInfo, "ZuiNativeIdentity", "COW: open %s failed errno=%d", path, *__errno());
        return false;
      }
      void* mapped = mmap(reinterpret_cast<void*>(start), end - start, kProtReadWrite,
                          kMapPrivateFixed, map_fd, static_cast<long>(offset));
      close(map_fd);
      if (reinterpret_cast<uptr>(mapped) != start || g_cowed_range_count == 8) {
        __android_log_print(kLogInfo, "ZuiNativeIdentity",
                            "COW: mmap %s at %p size=%zu failed result=%p errno=%d",
                            path, reinterpret_cast<void*>(start), end - start, mapped, *__errno());
        return false;
      }
      __android_log_print(kLogInfo, "ZuiNativeIdentity", "COW: private map %s at %p", path,
                          reinterpret_cast<void*>(start));
      g_cowed_ranges[g_cowed_range_count++] = {start, end};
      return true;
    }
  next_line:
    while (*line && *line != '\n') ++line;
    if (*line == '\n') ++line;
  }
  __android_log_print(kLogInfo, "ZuiNativeIdentity", "COW: no mapping for property %p", address);
  return false;
}

static bool patch_property(FindProperty find_property, const IdentityProperty& replacement) {
  const PropInfo* found = find_property(replacement.key);
  if (!found) {
    __android_log_print(kLogInfo, "ZuiNativeIdentity", "COW: property missing %s", replacement.key);
    return false;
  }
  if (!privatize_mapping_for(found)) return false;
  PropInfo* writable = const_cast<PropInfo*>(found);
  const usize length = cstrlen(replacement.value);
  if (length >= kPropValueMax) return false;
  for (usize i = 0; i <= length; ++i) writable->value[i] = replacement.value[i];
  const u32 old_serial = __atomic_load_n(&writable->serial, __ATOMIC_RELAXED);
  const u32 new_serial = (old_serial & 0x00ffffffu) | (static_cast<u32>(length) << 24);
  __atomic_store_n(&writable->serial, new_serial, __ATOMIC_RELEASE);
  return true;
}

class ZuiNativeIdentity final : public ModuleBase {
 public:
  void onLoad(void* api, JNIEnv* env) override {
    api_ = static_cast<ApiTable*>(api);
    env_ = env;
  }

  void preAppSpecialize(AppSpecializeArgs* args) override {
    target_ = false;
    // ReZygisk 515 deliberately supplies a null JNIEnv here, so nice_name
    // cannot be decoded through JNI. UID is a required public ABI field and
    // is stable for this installed ZUI camera package (u0_a366 = 10366).
    if (args && args->uid) {
      const int uid = *reinterpret_cast<const int*>(args->uid);
      target_ = (uid == 10366);
      __android_log_print(kLogInfo, "ZuiNativeIdentity", "pre: uid=%d target=%d", uid, target_);
      return;
    }
    if (!env_ || !env_->functions || !args || !args->nice_name) return;
    using GetStringUtfChars = const char* (*)(void*, void*, unsigned char*);
    using ReleaseStringUtfChars = void (*)(void*, void*, const char*);
    auto get_chars = reinterpret_cast<GetStringUtfChars>(const_cast<void*>(env_->functions[169]));
    auto release_chars = reinterpret_cast<ReleaseStringUtfChars>(const_cast<void*>(env_->functions[170]));
    if (!get_chars || !release_chars) return;
    const char* name = get_chars(env_, args->nice_name, nullptr);
    if (!name) return;
    target_ = equals(name, "com.zui.camera");
    __android_log_print(kLogInfo, "ZuiNativeIdentity", "pre: nice_name=%s target=%d", name, target_);
    release_chars(env_, args->nice_name, name);
  }

  void postAppSpecialize(const AppSpecializeArgs*) override {
    if (!target_) {
      if (api_ && api_->set_option) api_->set_option(api_->impl, kDlcloseModuleLibrary);
      return;
    }
    __android_log_print(kLogInfo, "ZuiNativeIdentity", "post: target selected from nice_name");
    auto find_property = reinterpret_cast<FindProperty>(dlsym(nullptr, "__system_property_find"));
    int patched = 0;
    if (find_property) {
      for (const auto& property : kProperties) {
        if (patch_property(find_property, property)) ++patched;
      }
    }
    __android_log_print(kLogInfo, "ZuiNativeIdentity", "COW-patched %d/4 ZUI identity properties", patched);
    // Diagnostic build: retain the target-process mapping until the next
    // launch. This gives an unambiguous /proc/<pid>/maps proof that ReZygisk
    // actually dispatched this module. Non-target processes still dlclose.
  }

 private:
  ApiTable* api_ = nullptr;
  JNIEnv* env_ = nullptr;
  bool target_ = false;
};

extern "C" __attribute__((visibility("default")))
void zygisk_module_entry(ApiTable* table, JNIEnv* env) {
  __android_log_print(kLogInfo, "ZuiNativeIdentity", "entry: ReZygisk ABI v4 registration requested");
  static ZuiNativeIdentity module;
  static ModuleAbi abi = {
      // This device uses ReZygisk 515. Its working Device Faker module
      // declares public ABI v4 (verified from its registration entry), so do
      // the same instead of assuming current upstream Magisk's v5.
      4, &module,
      [](ModuleBase* m, AppSpecializeArgs* args) {
        static_cast<ZuiNativeIdentity*>(m)->preAppSpecialize(args);
      },
      [](ModuleBase* m, const AppSpecializeArgs* args) {
        static_cast<ZuiNativeIdentity*>(m)->postAppSpecialize(args);
      },
      [](ModuleBase* m, ServerSpecializeArgs* args) {
        static_cast<ZuiNativeIdentity*>(m)->preServerSpecialize(args);
      },
      [](ModuleBase* m, const ServerSpecializeArgs* args) {
        static_cast<ZuiNativeIdentity*>(m)->postServerSpecialize(args);
      },
  };
  if (table && table->register_module && table->register_module(table, &abi)) {
    module.onLoad(table, env);
    __android_log_print(kLogInfo, "ZuiNativeIdentity", "entry: registration accepted");
  } else {
    __android_log_print(kLogInfo, "ZuiNativeIdentity", "entry: registration rejected");
  }
}
