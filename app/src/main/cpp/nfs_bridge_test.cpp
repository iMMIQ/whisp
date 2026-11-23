// SPDX-License-Identifier: LGPL-3.0-or-later
#include <cassert>
#include <cerrno>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <jni.h>
#include <limits>
#include <string>
#include <vector>

// Include the production bridge directly so every JNI entry point is verified under ASan/UBSan.
#include "nfs_bridge.cpp"

namespace {
struct FakeNfsState {
    int mount_status = 0;
    int open_status = 0;
    int fstat_status = 0;
    uint64_t file_mode = S_IFREG | 0444;
    uint64_t file_size = 64;
    int pread_calls = 0;
    int close_file_calls = 0;
    int umount_calls = 0;
    int destroy_calls = 0;
    std::vector<nfsdirent> dir_entries;
    size_t dir_index = 0;
} g_nfs;

struct FakeByteArray {
    std::vector<jbyte> data;
};

struct FakeLongArray {
    std::vector<jlong> data;
};

struct FakeEntry {
    std::string name;
    jint type;
    jlong size;
    jlong modified;
};

struct FakeObjectArray {
    std::vector<FakeEntry *> items;
};

struct FakeJniState {
    bool has_exception = false;
    std::string exception_class;
    std::string exception_message;
    int calls_while_exception_pending = 0;
    std::vector<FakeByteArray *> byte_arrays;
    std::vector<FakeLongArray *> long_arrays;
    std::vector<FakeObjectArray *> object_arrays;
    std::vector<FakeEntry *> entries;

    void reset() {
        has_exception = false;
        exception_class.clear();
        exception_message.clear();
        calls_while_exception_pending = 0;
    }

    void cleanup() {
        for (auto *p : byte_arrays)
            delete p;
        for (auto *p : long_arrays)
            delete p;
        for (auto *p : object_arrays)
            delete p;
        for (auto *p : entries)
            delete p;
        byte_arrays.clear();
        long_arrays.clear();
        object_arrays.clear();
        entries.clear();
    }
} g_jni;

void check_no_pending_exception() {
    if (g_jni.has_exception)
        g_jni.calls_while_exception_pending++;
}

jboolean jni_exception_check(JNIEnv *) { return g_jni.has_exception ? JNI_TRUE : JNI_FALSE; }

jclass jni_find_class(JNIEnv *, const char *name) {
    check_no_pending_exception();
    return reinterpret_cast<jclass>(const_cast<char *>(name));
}

jint jni_throw_new(JNIEnv *, jclass cls, const char *msg) {
    check_no_pending_exception();
    g_jni.has_exception = true;
    g_jni.exception_class = reinterpret_cast<const char *>(cls);
    g_jni.exception_message = msg ? msg : "";
    return 0;
}

jsize jni_get_array_length(JNIEnv *, jarray array) {
    check_no_pending_exception();
    return static_cast<jsize>(reinterpret_cast<FakeByteArray *>(array)->data.size());
}

void jni_get_byte_array_region(JNIEnv *, jbyteArray array, jsize start, jsize len, jbyte *buf) {
    check_no_pending_exception();
    auto *ba = reinterpret_cast<FakeByteArray *>(array);
    std::memcpy(buf, ba->data.data() + start, static_cast<size_t>(len));
}

void jni_set_byte_array_region(JNIEnv *, jbyteArray array, jsize start, jsize len,
                               const jbyte *buf) {
    check_no_pending_exception();
    auto *ba = reinterpret_cast<FakeByteArray *>(array);
    std::memcpy(ba->data.data() + start, buf, static_cast<size_t>(len));
}

jbyteArray jni_new_byte_array(JNIEnv *, jsize len) {
    check_no_pending_exception();
    auto *ba = new FakeByteArray{std::vector<jbyte>(static_cast<size_t>(len))};
    g_jni.byte_arrays.push_back(ba);
    return reinterpret_cast<jbyteArray>(ba);
}

jlongArray jni_new_long_array(JNIEnv *, jsize len) {
    check_no_pending_exception();
    auto *la = new FakeLongArray{std::vector<jlong>(static_cast<size_t>(len))};
    g_jni.long_arrays.push_back(la);
    return reinterpret_cast<jlongArray>(la);
}

void jni_set_long_array_region(JNIEnv *, jlongArray array, jsize start, jsize len,
                               const jlong *buf) {
    check_no_pending_exception();
    auto *la = reinterpret_cast<FakeLongArray *>(array);
    std::memcpy(la->data.data() + start, buf, static_cast<size_t>(len) * sizeof(jlong));
}

jmethodID jni_get_method_id(JNIEnv *, jclass, const char *, const char *) {
    check_no_pending_exception();
    return reinterpret_cast<jmethodID>(1);
}

jobjectArray jni_new_object_array(JNIEnv *, jsize len, jclass, jobject) {
    check_no_pending_exception();
    auto *oa = new FakeObjectArray{std::vector<FakeEntry *>(static_cast<size_t>(len), nullptr)};
    g_jni.object_arrays.push_back(oa);
    return reinterpret_cast<jobjectArray>(oa);
}

jobject jni_new_object_v(JNIEnv *, jclass, jmethodID, va_list args) {
    check_no_pending_exception();
    auto *name_arr = reinterpret_cast<FakeByteArray *>(va_arg(args, jobject));
    const jint type = va_arg(args, jint);
    const jlong size = va_arg(args, jlong);
    const jlong modified = va_arg(args, jlong);
    auto *entry = new FakeEntry{
        std::string(reinterpret_cast<const char *>(name_arr->data.data()), name_arr->data.size()),
        type, size, modified};
    g_jni.entries.push_back(entry);
    return reinterpret_cast<jobject>(entry);
}

jobject jni_new_object(JNIEnv *env, jclass cls, jmethodID method, ...) {
    va_list args;
    va_start(args, method);
    auto result = jni_new_object_v(env, cls, method, args);
    va_end(args);
    return result;
}

void jni_set_object_array_element(JNIEnv *, jobjectArray array, jsize index, jobject val) {
    check_no_pending_exception();
    reinterpret_cast<FakeObjectArray *>(array)->items[static_cast<size_t>(index)] =
        reinterpret_cast<FakeEntry *>(val);
}

void jni_delete_local_ref(JNIEnv *, jobject) {}

jbyteArray make_bytes(const std::string &value) {
    auto *ba = new FakeByteArray{
        std::vector<jbyte>(value.begin(), value.end()),
    };
    g_jni.byte_arrays.push_back(ba);
    return reinterpret_cast<jbyteArray>(ba);
}
} // namespace

extern "C" {
struct nfs_context {
    int dummy;
};
struct nfsdir {
    int dummy;
};
struct nfsfh {
    int dummy;
};

nfs_context *nfs_init_context() { return new nfs_context{1}; }
void nfs_destroy_context(nfs_context *nfs) {
    g_nfs.destroy_calls++;
    delete nfs;
}
void nfs_set_timeout(nfs_context *, int) {}
void nfs_set_retrans(nfs_context *, int) {}
void nfs_set_autoreconnect(nfs_context *, int) {}
void nfs_set_uid(nfs_context *, int) {}
void nfs_set_gid(nfs_context *, int) {}
void nfs_set_auxiliary_gids(nfs_context *, uint32_t, uint32_t *) {}
void nfs_set_nfsport(nfs_context *, int) {}
void nfs_set_mountport(nfs_context *, int) {}
void nfs_set_readonly(nfs_context *, int) {}
void nfs_set_auto_traverse_mounts(nfs_context *, int) {}
void nfs_set_readmax(nfs_context *, size_t) {}
int nfs_set_version(nfs_context *, int) { return 0; }
int nfs_mount(nfs_context *, const char *, const char *) { return g_nfs.mount_status; }
int nfs_umount(nfs_context *) {
    g_nfs.umount_calls++;
    return 0;
}
int nfs_opendir(nfs_context *, const char *, nfsdir **dir) {
    g_nfs.dir_index = 0;
    *dir = new nfsdir{1};
    return 0;
}
nfsdirent *nfs_readdir(nfs_context *, nfsdir *) {
    if (g_nfs.dir_index >= g_nfs.dir_entries.size())
        return nullptr;
    return &g_nfs.dir_entries[g_nfs.dir_index++];
}
void nfs_closedir(nfs_context *, nfsdir *dir) { delete dir; }
int nfs_open(nfs_context *, const char *, int, nfsfh **fh) {
    if (g_nfs.open_status < 0)
        return g_nfs.open_status;
    *fh = new nfsfh{1};
    return 0;
}
int nfs_fstat64(nfs_context *, nfsfh *, nfs_stat_64 *st) {
    st->nfs_mode = g_nfs.file_mode;
    st->nfs_size = g_nfs.file_size;
    return g_nfs.fstat_status;
}
int nfs_pread(nfs_context *, nfsfh *, void *buf, size_t count, uint64_t offset) {
    g_nfs.pread_calls++;
    auto *out = static_cast<uint8_t *>(buf);
    for (size_t i = 0; i < count; ++i)
        out[i] = static_cast<uint8_t>((offset + i) & 0xff);
    return static_cast<int>(count);
}
int nfs_close(nfs_context *, nfsfh *fh) {
    g_nfs.close_file_calls++;
    delete fh;
    return 0;
}
}

int main() {
    JNINativeInterface_ table{};
    table.ExceptionCheck = jni_exception_check;
    table.FindClass = jni_find_class;
    table.ThrowNew = jni_throw_new;
    table.GetArrayLength = jni_get_array_length;
    table.GetByteArrayRegion = jni_get_byte_array_region;
    table.SetByteArrayRegion = jni_set_byte_array_region;
    table.NewByteArray = jni_new_byte_array;
    table.NewLongArray = jni_new_long_array;
    table.SetLongArrayRegion = jni_set_long_array_region;
    table.GetMethodID = jni_get_method_id;
    table.NewObjectArray = jni_new_object_array;
    table.NewObject = jni_new_object;
    table.NewObjectV = jni_new_object_v;
    table.SetObjectArrayElement = jni_set_object_array_element;
    table.DeleteLocalRef = jni_delete_local_ref;

    JNIEnv env{&table};

    // 1. Embedded NUL in host must fail without issuing a second JNI call under pending exception.
    g_jni.reset();
    std::string bad_host("nas\0bad", 7);
    auto ptr = Java_app_whisp_data_NfsNative_connect(&env, nullptr, make_bytes(bad_host),
                                                     make_bytes("/export"), 4, 1000, 1000, 2049, 0);
    assert(ptr == 0);
    assert(g_jni.has_exception);
    assert(g_jni.calls_while_exception_pending == 0);

    // 2. Null pointer in entries/open must bail out before reading path array.
    g_jni.reset();
    assert(Java_app_whisp_data_NfsNative_entries(&env, nullptr, 0, make_bytes("/")) == nullptr);
    assert(g_jni.has_exception);
    assert(g_jni.calls_while_exception_pending == 0);

    g_jni.reset();
    assert(Java_app_whisp_data_NfsNative_open(&env, nullptr, 0, make_bytes("/a.mp3")) == nullptr);
    assert(g_jni.has_exception);
    assert(g_jni.calls_while_exception_pending == 0);

    // 3. Valid connect, 64-bit timestamps in entries, open, buffered read reuse, and close.
    g_jni.reset();
    ptr = Java_app_whisp_data_NfsNative_connect(&env, nullptr, make_bytes("nas.example"),
                                                make_bytes("/media"), 4, 65534, 65534, 2049, 0);
    assert(ptr != 0);
    assert(!g_jni.has_exception);

    g_nfs.dir_entries = {
        {".", 2, 0, {0}},
        {"..", 2, 0, {0}},
        {nullptr, 1, 0, {0}},
        {"future.flac", 1, 1024, {4102444800LL}}, // Year 2100 (> 32-bit signed max)
    };
    auto *entries_obj = reinterpret_cast<FakeObjectArray *>(
        Java_app_whisp_data_NfsNative_entries(&env, nullptr, ptr, make_bytes("/")));
    assert(entries_obj != nullptr);
    assert(entries_obj->items.size() == 1);
    assert(entries_obj->items[0]->name == "future.flac");
    assert(entries_obj->items[0]->modified == 4102444800LL);

    // Oversized file must report -EFBIG (not -EISDIR).
    g_jni.reset();
    g_nfs.file_mode = S_IFREG | 0444;
    g_nfs.file_size = static_cast<uint64_t>(std::numeric_limits<jlong>::max()) + 1ULL;
    assert(Java_app_whisp_data_NfsNative_open(&env, nullptr, ptr, make_bytes("/huge.bin")) ==
           nullptr);
    assert(g_jni.has_exception);
    assert(g_jni.exception_message.find("过多") != std::string::npos);

    // Normal open and repeated reads exercising reusable read_buffer.
    g_jni.reset();
    g_nfs.file_size = 512;
    auto *opened = reinterpret_cast<FakeLongArray *>(
        Java_app_whisp_data_NfsNative_open(&env, nullptr, ptr, make_bytes("/track.flac")));
    assert(opened != nullptr);
    assert(opened->data[1] == 512);
    const jlong file_handle = opened->data[0];

    auto *out_buf = jni_new_byte_array(&env, 128);
    assert(Java_app_whisp_data_NfsNative_read(&env, nullptr, ptr, file_handle, 10, out_buf, 0,
                                              64) == 64);
    assert(Java_app_whisp_data_NfsNative_read(&env, nullptr, ptr, file_handle, 74, out_buf, 64,
                                              64) == 64);
    auto *raw_out = reinterpret_cast<FakeByteArray *>(out_buf);
    assert(static_cast<uint8_t>(raw_out->data[0]) == 10);
    assert(static_cast<uint8_t>(raw_out->data[64]) == 74);

    Java_app_whisp_data_NfsNative_closeFile(&env, nullptr, ptr, file_handle);
    Java_app_whisp_data_NfsNative_close(&env, nullptr, ptr);
    assert(g_nfs.umount_calls == 1);
    assert(g_nfs.destroy_calls == 1);
    assert(g_jni.calls_while_exception_pending == 0);

    g_jni.cleanup();
    std::puts("nfs_bridge_test passed");
    return 0;
}
