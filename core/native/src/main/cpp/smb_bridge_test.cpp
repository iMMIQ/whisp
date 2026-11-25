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

#include "smb_bridge.cpp"

namespace {
struct FakeSmbState {
    int connect_status = 0;
    int fstat_status = 0;
    bool open_fail = false;
    bool opendir_fail = false;
    uint32_t file_type = SMB2_TYPE_FILE;
    uint64_t file_size = 512;
    uint32_t max_read_size = 65536;
    std::string error_msg;
    int seal_value = -1;
    int close_file_calls = 0;
    int disconnect_calls = 0;
    int destroy_calls = 0;
    std::vector<smb2dirent> dir_entries;
    size_t dir_index = 0;
} g_smb;

struct FakeByteArray {
    std::vector<jbyte> data;
};
struct FakeLongArray {
    std::vector<jlong> data;
};
struct FakeEntry {
    std::string name;
    jboolean directory;
    jlong size;
    jlong modified;
};
struct FakeObjectArray {
    std::vector<FakeEntry *> items;
};

struct FakeJniState {
    bool has_exception = false;
    std::string exception_message;
    int calls_while_exception_pending = 0;
    std::vector<FakeByteArray *> byte_arrays;
    std::vector<FakeLongArray *> long_arrays;
    std::vector<FakeObjectArray *> object_arrays;
    std::vector<FakeEntry *> entries;

    void reset() {
        has_exception = false;
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

void check_no_exc() {
    if (g_jni.has_exception)
        g_jni.calls_while_exception_pending++;
}

jboolean jni_exc_check(JNIEnv *) { return g_jni.has_exception ? JNI_TRUE : JNI_FALSE; }
jclass jni_find_class(JNIEnv *, const char *name) {
    check_no_exc();
    return reinterpret_cast<jclass>(const_cast<char *>(name));
}
jobject jni_new_global_ref(JNIEnv *, jobject obj) {
    check_no_exc();
    return obj;
}
void jni_del_global_ref(JNIEnv *, jobject) { check_no_exc(); }
JNIEnv *g_vm_env = nullptr;
jint jvm_get_env(JavaVM *, void **penv, jint) {
    *penv = g_vm_env;
    return g_vm_env ? JNI_OK : JNI_EDETACHED;
}
jint jni_throw_new(JNIEnv *, jclass, const char *msg) {
    check_no_exc();
    g_jni.has_exception = true;
    g_jni.exception_message = msg ? msg : "";
    return 0;
}
jsize jni_get_array_len(JNIEnv *, jarray array) {
    check_no_exc();
    return static_cast<jsize>(reinterpret_cast<FakeByteArray *>(array)->data.size());
}
void jni_get_ba_region(JNIEnv *, jbyteArray array, jsize start, jsize len, jbyte *buf) {
    check_no_exc();
    auto *ba = reinterpret_cast<FakeByteArray *>(array);
    std::memcpy(buf, ba->data.data() + start, static_cast<size_t>(len));
}
void jni_set_ba_region(JNIEnv *, jbyteArray array, jsize start, jsize len, const jbyte *buf) {
    check_no_exc();
    auto *ba = reinterpret_cast<FakeByteArray *>(array);
    std::memcpy(ba->data.data() + start, buf, static_cast<size_t>(len));
}
jbyteArray jni_new_ba(JNIEnv *, jsize len) {
    check_no_exc();
    auto *ba = new FakeByteArray{std::vector<jbyte>(static_cast<size_t>(len))};
    g_jni.byte_arrays.push_back(ba);
    return reinterpret_cast<jbyteArray>(ba);
}
jlongArray jni_new_la(JNIEnv *, jsize len) {
    check_no_exc();
    auto *la = new FakeLongArray{std::vector<jlong>(static_cast<size_t>(len))};
    g_jni.long_arrays.push_back(la);
    return reinterpret_cast<jlongArray>(la);
}
void jni_set_la_region(JNIEnv *, jlongArray array, jsize start, jsize len, const jlong *buf) {
    check_no_exc();
    auto *la = reinterpret_cast<FakeLongArray *>(array);
    std::memcpy(la->data.data() + start, buf, static_cast<size_t>(len) * sizeof(jlong));
}
jmethodID jni_get_mid(JNIEnv *, jclass, const char *, const char *) {
    check_no_exc();
    return reinterpret_cast<jmethodID>(1);
}
jobjectArray jni_new_oa(JNIEnv *, jsize len, jclass, jobject) {
    check_no_exc();
    auto *oa = new FakeObjectArray{std::vector<FakeEntry *>(static_cast<size_t>(len), nullptr)};
    g_jni.object_arrays.push_back(oa);
    return reinterpret_cast<jobjectArray>(oa);
}
jobject jni_new_obj_v(JNIEnv *, jclass, jmethodID, va_list args) {
    check_no_exc();
    auto *name_arr = reinterpret_cast<FakeByteArray *>(va_arg(args, jobject));
    const jboolean dir = static_cast<jboolean>(va_arg(args, int));
    const jlong size = va_arg(args, jlong);
    const jlong modified = va_arg(args, jlong);
    auto *entry = new FakeEntry{
        std::string(reinterpret_cast<const char *>(name_arr->data.data()), name_arr->data.size()),
        dir, size, modified};
    g_jni.entries.push_back(entry);
    return reinterpret_cast<jobject>(entry);
}
jobject jni_new_obj(JNIEnv *env, jclass cls, jmethodID method, ...) {
    va_list args;
    va_start(args, method);
    auto res = jni_new_obj_v(env, cls, method, args);
    va_end(args);
    return res;
}
void jni_set_oa_elem(JNIEnv *, jobjectArray array, jsize index, jobject val) {
    check_no_exc();
    reinterpret_cast<FakeObjectArray *>(array)->items[static_cast<size_t>(index)] =
        reinterpret_cast<FakeEntry *>(val);
}
void jni_del_local_ref(JNIEnv *, jobject) {}
void *jni_direct_addr(JNIEnv *, jobject buf) {
    check_no_exc();
    auto *ba = reinterpret_cast<FakeByteArray *>(buf);
    return ba ? ba->data.data() : nullptr;
}
jlong jni_direct_cap(JNIEnv *, jobject buf) {
    check_no_exc();
    auto *ba = reinterpret_cast<FakeByteArray *>(buf);
    return ba ? static_cast<jlong>(ba->data.size()) : -1;
}
jbyteArray make_bytes(const std::string &v) {
    auto *ba = new FakeByteArray{std::vector<jbyte>(v.begin(), v.end())};
    g_jni.byte_arrays.push_back(ba);
    return reinterpret_cast<jbyteArray>(ba);
}
} // namespace

extern "C" {
struct smb2_context {
    int dummy;
};
struct smb2dir {
    int dummy;
};
struct smb2fh {
    int dummy;
};

smb2_context *smb2_init_context() { return new smb2_context{1}; }
void smb2_destroy_context(smb2_context *s) {
    g_smb.destroy_calls++;
    delete s;
}
void smb2_set_timeout(smb2_context *, int) {}
void smb2_set_security_mode(smb2_context *, uint16_t) {}
void smb2_set_seal(smb2_context *, int val) { g_smb.seal_value = val; }
void smb2_set_authentication(smb2_context *, int) {}
void smb2_set_user(smb2_context *, const char *) {}
void smb2_set_password(smb2_context *, const char *) {}
void smb2_set_domain(smb2_context *, const char *) {}
int smb2_connect_share(smb2_context *, const char *, const char *, const char *) {
    return g_smb.connect_status;
}
int smb2_disconnect_share(smb2_context *) {
    g_smb.disconnect_calls++;
    return 0;
}
const char *smb2_get_error(smb2_context *) { return g_smb.error_msg.c_str(); }
uint32_t smb2_get_max_read_size(smb2_context *) { return g_smb.max_read_size; }
smb2dir *smb2_opendir(smb2_context *, const char *) {
    if (g_smb.opendir_fail)
        return nullptr;
    g_smb.dir_index = 0;
    return new smb2dir{1};
}
smb2dirent *smb2_readdir(smb2_context *, smb2dir *) {
    if (g_smb.dir_index >= g_smb.dir_entries.size())
        return nullptr;
    return &g_smb.dir_entries[g_smb.dir_index++];
}
void smb2_closedir(smb2_context *, smb2dir *d) { delete d; }
smb2fh *smb2_open(smb2_context *, const char *, int) {
    if (g_smb.open_fail)
        return nullptr;
    return new smb2fh{1};
}
int smb2_fstat(smb2_context *, smb2fh *, smb2_stat_64 *st) {
    st->smb2_type = g_smb.file_type;
    st->smb2_size = g_smb.file_size;
    return g_smb.fstat_status;
}
int smb2_pread(smb2_context *, smb2fh *, uint8_t *buf, uint32_t count, uint64_t offset) {
    for (uint32_t i = 0; i < count; ++i)
        buf[i] = static_cast<uint8_t>((offset + i) & 0xff);
    return static_cast<int>(count);
}
int smb2_close(smb2_context *, smb2fh *fh) {
    g_smb.close_file_calls++;
    delete fh;
    return 0;
}
}

int main() {
    JNINativeInterface_ table{};
    table.ExceptionCheck = jni_exc_check;
    table.FindClass = jni_find_class;
    table.ThrowNew = jni_throw_new;
    table.GetArrayLength = jni_get_array_len;
    table.GetByteArrayRegion = jni_get_ba_region;
    table.SetByteArrayRegion = jni_set_ba_region;
    table.NewByteArray = jni_new_ba;
    table.NewLongArray = jni_new_la;
    table.SetLongArrayRegion = jni_set_la_region;
    table.GetMethodID = jni_get_mid;
    table.NewObjectArray = jni_new_oa;
    table.NewObject = jni_new_obj;
    table.NewObjectV = jni_new_obj_v;
    table.SetObjectArrayElement = jni_set_oa_elem;
    table.DeleteLocalRef = jni_del_local_ref;
    table.NewGlobalRef = jni_new_global_ref;
    table.DeleteGlobalRef = jni_del_global_ref;
    table.GetDirectBufferAddress = jni_direct_addr;
    table.GetDirectBufferCapacity = jni_direct_cap;

    JNIEnv env{&table};
    g_vm_env = &env;
    JNIInvokeInterface_ vm_table{};
    vm_table.GetEnv = jvm_get_env;
    JavaVM vm{&vm_table};

    assert(JNI_OnLoad(&vm, nullptr) == JNI_VERSION_1_6);

    // 1. Auth error maps to Chinese credential hint.
    g_smb.connect_status = -EACCES;
    g_smb.error_msg = "STATUS_LOGON_FAILURE";
    assert(Java_app_whisp_data_SmbNative_connect(&env, nullptr, make_bytes("nas.example:445"),
                                                 make_bytes("media"), make_bytes("u"),
                                                 make_bytes(""), make_bytes("bad"), JNI_TRUE) == 0);
    assert(g_jni.has_exception);
    assert(g_jni.exception_message.find("账号或密码") != std::string::npos);
    assert(g_smb.seal_value == 1);

    // 2. Successful connect, directory listing skipping symlinks/dots, open, array & direct read.
    g_jni.reset();
    g_smb.connect_status = 0;
    g_smb.error_msg.clear();
    const jlong ptr = Java_app_whisp_data_SmbNative_connect(
        &env, nullptr, make_bytes("nas.example"), make_bytes("media"), make_bytes("u"),
        make_bytes("WORKGROUP"), make_bytes("pass"), JNI_FALSE);
    assert(ptr != 0);
    assert(g_smb.seal_value == 0);

    smb2_stat_64 st_file{};
    st_file.smb2_type = SMB2_TYPE_FILE;
    st_file.smb2_size = 2048;
    st_file.smb2_mtime = 1700000000ULL;
    smb2_stat_64 st_link{};
    st_link.smb2_type = SMB2_TYPE_LINK;
    g_smb.dir_entries = {{".", st_file}, {"..", st_file}, {"sym", st_link}, {"01.flac", st_file}};
    auto *arr = reinterpret_cast<FakeObjectArray *>(
        Java_app_whisp_data_SmbNative_entries(&env, nullptr, ptr, make_bytes("")));
    assert(arr != nullptr);
    assert(arr->items.size() == 1);
    assert(arr->items[0]->name == "01.flac");
    assert(arr->items[0]->directory == JNI_FALSE);

    auto *opened = reinterpret_cast<FakeLongArray *>(
        Java_app_whisp_data_SmbNative_open(&env, nullptr, ptr, make_bytes("01.flac")));
    assert(opened != nullptr);
    const jlong fh = opened->data[0];

    auto *buf = jni_new_ba(&env, 64);
    assert(Java_app_whisp_data_SmbNative_read(&env, nullptr, ptr, fh, 5, buf, 0, 32) == 32);
    assert(static_cast<uint8_t>(reinterpret_cast<FakeByteArray *>(buf)->data[0]) == 5);

    auto *dbuf = jni_new_ba(&env, 64);
    assert(Java_app_whisp_data_SmbNative_readDirect(&env, nullptr, ptr, fh, 12, dbuf, 8, 32) == 32);
    assert(static_cast<uint8_t>(reinterpret_cast<FakeByteArray *>(dbuf)->data[8]) == 12);

    Java_app_whisp_data_SmbNative_closeFile(&env, nullptr, ptr, fh);
    Java_app_whisp_data_SmbNative_close(&env, nullptr, ptr);
    JNI_OnUnload(&vm, nullptr);
    assert(g_smb.disconnect_calls == 1);
    assert(g_jni.calls_while_exception_pending == 0);
    g_jni.cleanup();
    std::puts("smb_bridge_test passed");
    return 0;
}
