// SPDX-License-Identifier: LGPL-3.0-or-later
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <limits.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

// clang-format off
#include <smb2/smb2.h>
#include <smb2/libsmb2.h>
// clang-format on

namespace {
jclass g_smb_entry_class = nullptr;
jmethodID g_smb_entry_ctor = nullptr;

constexpr size_t kMaxSmbPathBytes = 16384;
constexpr size_t kMaxSmbDirEntries = 100000;
constexpr jint kMaxSmbReadBytes = 1024 * 1024;

struct SmbConnection {
    smb2_context *smb2;
    smb2fh **files;
    size_t file_count;
    size_t file_cap;
    uint8_t *read_buffer;
    size_t read_buffer_cap;
};

struct SmbDirEntry {
    char *name;
    size_t name_len;
    bool directory;
    uint64_t size;
    int64_t modified;
};

void secure_zero(void *ptr, size_t len) {
    if (!ptr || len == 0)
        return;
    volatile unsigned char *p = static_cast<volatile unsigned char *>(ptr);
    while (len--)
        *p++ = 0;
}

void fail_smb(JNIEnv *env, int code, const char *raw_error) {
    if (env->ExceptionCheck())
        return;
    const char *err = raw_error ? raw_error : "";
    const char *message = "SMB：连接或读取失败，请检查地址、账号、加密设置和网络";
    if (strstr(err, "STATUS_LOGON_FAILURE") || strstr(err, "LOGON_FAILURE") ||
        strstr(err, "WRONG_PASSWORD"))
        message = "SMB：账号或密码不正确";
    else if (code == -EACCES || code == -EPERM || strstr(err, "STATUS_ACCESS_DENIED") ||
             strstr(err, "ACCESS_DENIED"))
        message = "SMB：没有共享或目录的读取权限";
    else if (strstr(err, "STATUS_BAD_NETWORK_NAME") || strstr(err, "BAD_NETWORK_NAME"))
        message = "SMB：共享名不存在";
    else if (code == -ENOENT || strstr(err, "STATUS_OBJECT_NAME_NOT_FOUND") ||
             strstr(err, "STATUS_OBJECT_PATH_NOT_FOUND"))
        message = "SMB：目录或文件不存在";
    else if (code == -ETIMEDOUT || strstr(err, "Timed out") || strstr(err, "timeout"))
        message = "SMB：连接超时，请检查 NAS 和网络";
    else if (code == -EFBIG)
        message = "SMB：目录文件过多，请选择 ASMR 子目录";
    else if (code == -EISDIR)
        message = "SMB：所选资源不是普通文件";
    else if (code == -EINVAL)
        message = "SMB：地址或参数无效";
    if (auto cls = env->FindClass("java/io/IOException"))
        env->ThrowNew(cls, message);
}

void oom_smb(JNIEnv *env) {
    if (env->ExceptionCheck())
        return;
    if (auto cls = env->FindClass("java/lang/OutOfMemoryError"))
        env->ThrowNew(cls, "SMB buffer allocation failed");
}

char *copy_smb_bytes(JNIEnv *env, jbyteArray value, size_t *out_len) {
    if (env->ExceptionCheck())
        return nullptr;
    if (!value) {
        fail_smb(env, -EINVAL, nullptr);
        return nullptr;
    }
    const jsize size = env->GetArrayLength(value);
    if (size < 0 || static_cast<size_t>(size) > kMaxSmbPathBytes) {
        fail_smb(env, -EINVAL, nullptr);
        return nullptr;
    }
    const size_t len = static_cast<size_t>(size);
    char *buf = static_cast<char *>(malloc(len + 1));
    if (!buf) {
        oom_smb(env);
        return nullptr;
    }
    if (len > 0) {
        env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte *>(buf));
        if (env->ExceptionCheck()) {
            free(buf);
            return nullptr;
        }
        if (memchr(buf, '\0', len) != nullptr) {
            secure_zero(buf, len);
            free(buf);
            fail_smb(env, -EINVAL, nullptr);
            return nullptr;
        }
    }
    buf[len] = '\0';
    if (out_len)
        *out_len = len;
    return buf;
}

SmbConnection *smb_connection(JNIEnv *env, jlong pointer) {
    if (!pointer) {
        fail_smb(env, -EBADF, nullptr);
        return nullptr;
    }
    return reinterpret_cast<SmbConnection *>(static_cast<intptr_t>(pointer));
}

bool smb_track_file(SmbConnection *ctx, smb2fh *file) {
    if (ctx->file_count == ctx->file_cap) {
        const size_t next_cap = ctx->file_cap ? ctx->file_cap * 2 : 8;
        auto *next = static_cast<smb2fh **>(realloc(ctx->files, next_cap * sizeof(smb2fh *)));
        if (!next)
            return false;
        ctx->files = next;
        ctx->file_cap = next_cap;
    }
    ctx->files[ctx->file_count++] = file;
    return true;
}

bool smb_has_file(const SmbConnection *ctx, smb2fh *file) {
    for (size_t i = 0; i < ctx->file_count; ++i) {
        if (ctx->files[i] == file)
            return true;
    }
    return false;
}

bool smb_remove_file(SmbConnection *ctx, smb2fh *file) {
    for (size_t i = 0; i < ctx->file_count; ++i) {
        if (ctx->files[i] == file) {
            ctx->files[i] = ctx->files[--ctx->file_count];
            return true;
        }
    }
    return false;
}

void free_smb_entries(SmbDirEntry *entries, size_t count) {
    if (!entries)
        return;
    for (size_t i = 0; i < count; ++i)
        free(entries[i].name);
    free(entries);
}

uint32_t clamp_smb_read(smb2_context *smb2, jint length) {
    uint32_t want = static_cast<uint32_t>(length);
    const uint32_t max_read = smb2_get_max_read_size(smb2);
    if (max_read > 0 && want > max_read)
        want = max_read;
    return want;
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL Java_app_whisp_data_SmbNative_connect(
    JNIEnv *env, jobject, jbyteArray host_port, jbyteArray share, jbyteArray user,
    jbyteArray domain, jbyteArray password, jboolean encrypt) {
    size_t host_len = 0, share_len = 0, user_len = 0, domain_len = 0, pass_len = 0;
    char *server = copy_smb_bytes(env, host_port, &host_len);
    if (!server)
        return 0;
    char *share_name = copy_smb_bytes(env, share, &share_len);
    if (!share_name) {
        free(server);
        return 0;
    }
    char *user_name = copy_smb_bytes(env, user, &user_len);
    if (!user_name) {
        free(server);
        free(share_name);
        return 0;
    }
    char *domain_name = copy_smb_bytes(env, domain, &domain_len);
    if (!domain_name) {
        free(server);
        free(share_name);
        free(user_name);
        return 0;
    }
    char *pass_val = copy_smb_bytes(env, password, &pass_len);
    if (!pass_val) {
        free(server);
        free(share_name);
        free(user_name);
        free(domain_name);
        return 0;
    }

    if (host_len == 0 || share_len == 0) {
        secure_zero(pass_val, pass_len);
        free(server);
        free(share_name);
        free(user_name);
        free(domain_name);
        free(pass_val);
        fail_smb(env, -EINVAL, nullptr);
        return 0;
    }

    smb2_context *smb2 = smb2_init_context();
    if (!smb2) {
        secure_zero(pass_val, pass_len);
        free(server);
        free(share_name);
        free(user_name);
        free(domain_name);
        free(pass_val);
        oom_smb(env);
        return 0;
    }

    smb2_set_timeout(smb2, 20);
    smb2_set_authentication(smb2, SMB2_SEC_NTLMSSP);
    smb2_set_security_mode(smb2, SMB2_NEGOTIATE_SIGNING_ENABLED);
    smb2_set_seal(smb2, encrypt ? 1 : 0);
    if (domain_len > 0)
        smb2_set_domain(smb2, domain_name);
    if (user_len > 0)
        smb2_set_user(smb2, user_name);
    if (pass_len > 0)
        smb2_set_password(smb2, pass_val);

    const int status = smb2_connect_share(smb2, server, share_name, user_len > 0 ? user_name : "");

    secure_zero(pass_val, pass_len);
    free(server);
    free(share_name);
    free(user_name);
    free(domain_name);
    free(pass_val);

    if (status < 0) {
        const char *err = smb2_get_error(smb2);
        fail_smb(env, status, err);
        smb2_destroy_context(smb2);
        return 0;
    }

    auto *result = static_cast<SmbConnection *>(calloc(1, sizeof(SmbConnection)));
    if (!result) {
        smb2_disconnect_share(smb2);
        smb2_destroy_context(smb2);
        oom_smb(env);
        return 0;
    }
    result->smb2 = smb2;
    return static_cast<jlong>(reinterpret_cast<intptr_t>(result));
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_app_whisp_data_SmbNative_entries(JNIEnv *env,
                                                                                jobject,
                                                                                jlong pointer,
                                                                                jbyteArray path) {
    SmbConnection *ctx = smb_connection(env, pointer);
    if (!ctx || env->ExceptionCheck())
        return nullptr;
    char *relative = copy_smb_bytes(env, path, nullptr);
    if (!relative)
        return nullptr;
    smb2dir *dir = smb2_opendir(ctx->smb2, relative);
    free(relative);
    if (!dir) {
        fail_smb(env, -EIO, smb2_get_error(ctx->smb2));
        return nullptr;
    }

    SmbDirEntry *entries = nullptr;
    size_t count = 0;
    size_t cap = 0;
    while (auto *item = smb2_readdir(ctx->smb2, dir)) {
        if (!item->name || !strcmp(item->name, ".") || !strcmp(item->name, ".."))
            continue;
        if (item->st.smb2_type != SMB2_TYPE_FILE && item->st.smb2_type != SMB2_TYPE_DIRECTORY)
            continue;
        if (count >= kMaxSmbDirEntries) {
            smb2_closedir(ctx->smb2, dir);
            free_smb_entries(entries, count);
            fail_smb(env, -EFBIG, nullptr);
            return nullptr;
        }
        if (item->st.smb2_size > static_cast<uint64_t>(INT64_MAX)) {
            smb2_closedir(ctx->smb2, dir);
            free_smb_entries(entries, count);
            fail_smb(env, -EFBIG, nullptr);
            return nullptr;
        }
        if (count == cap) {
            const size_t next_cap = cap ? cap * 2 : 32;
            auto *next =
                static_cast<SmbDirEntry *>(realloc(entries, next_cap * sizeof(SmbDirEntry)));
            if (!next) {
                smb2_closedir(ctx->smb2, dir);
                free_smb_entries(entries, count);
                oom_smb(env);
                return nullptr;
            }
            entries = next;
            cap = next_cap;
        }
        const size_t nlen = strlen(item->name);
        char *dup = static_cast<char *>(malloc(nlen + 1));
        if (!dup) {
            smb2_closedir(ctx->smb2, dir);
            free_smb_entries(entries, count);
            oom_smb(env);
            return nullptr;
        }
        memcpy(dup, item->name, nlen + 1);
        entries[count++] =
            SmbDirEntry{dup, nlen, item->st.smb2_type == SMB2_TYPE_DIRECTORY, item->st.smb2_size,
                        static_cast<int64_t>(item->st.smb2_mtime)};
    }
    smb2_closedir(ctx->smb2, dir);

    jclass local_cls = nullptr;
    jclass cls = g_smb_entry_class;
    jmethodID ctor = g_smb_entry_ctor;
    if (!cls || !ctor) {
        local_cls = env->FindClass("app/whisp/data/SmbNativeEntry");
        if (!local_cls) {
            free_smb_entries(entries, count);
            return nullptr;
        }
        ctor = env->GetMethodID(local_cls, "<init>", "([BZJJ)V");
        if (!ctor) {
            env->DeleteLocalRef(local_cls);
            free_smb_entries(entries, count);
            return nullptr;
        }
        cls = local_cls;
    }

    jobjectArray result = env->NewObjectArray(static_cast<jsize>(count), cls, nullptr);
    if (!result) {
        if (local_cls)
            env->DeleteLocalRef(local_cls);
        free_smb_entries(entries, count);
        return nullptr;
    }

    for (size_t index = 0; index < count; ++index) {
        const SmbDirEntry &item = entries[index];
        jbyteArray name = env->NewByteArray(static_cast<jsize>(item.name_len));
        if (!name) {
            if (local_cls)
                env->DeleteLocalRef(local_cls);
            free_smb_entries(entries, count);
            return nullptr;
        }
        if (item.name_len > 0) {
            env->SetByteArrayRegion(name, 0, static_cast<jsize>(item.name_len),
                                    reinterpret_cast<const jbyte *>(item.name));
            if (env->ExceptionCheck()) {
                env->DeleteLocalRef(name);
                if (local_cls)
                    env->DeleteLocalRef(local_cls);
                free_smb_entries(entries, count);
                return nullptr;
            }
        }
        jobject entry = env->NewObject(
            cls, ctor, name, static_cast<jboolean>(item.directory ? JNI_TRUE : JNI_FALSE),
            static_cast<jlong>(item.size), static_cast<jlong>(item.modified));
        env->DeleteLocalRef(name);
        if (!entry) {
            if (local_cls)
                env->DeleteLocalRef(local_cls);
            free_smb_entries(entries, count);
            return nullptr;
        }
        env->SetObjectArrayElement(result, static_cast<jsize>(index), entry);
        env->DeleteLocalRef(entry);
        if (env->ExceptionCheck()) {
            if (local_cls)
                env->DeleteLocalRef(local_cls);
            free_smb_entries(entries, count);
            return nullptr;
        }
    }
    if (local_cls)
        env->DeleteLocalRef(local_cls);
    free_smb_entries(entries, count);
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL Java_app_whisp_data_SmbNative_open(JNIEnv *env, jobject,
                                                                           jlong pointer,
                                                                           jbyteArray path) {
    SmbConnection *ctx = smb_connection(env, pointer);
    if (!ctx || env->ExceptionCheck())
        return nullptr;
    char *relative = copy_smb_bytes(env, path, nullptr);
    if (!relative)
        return nullptr;
    smb2fh *file = smb2_open(ctx->smb2, relative, O_RDONLY);
    free(relative);
    if (!file) {
        fail_smb(env, -ENOENT, smb2_get_error(ctx->smb2));
        return nullptr;
    }
    smb2_stat_64 st{};
    const int status = smb2_fstat(ctx->smb2, file, &st);
    if (status < 0 || st.smb2_type != SMB2_TYPE_FILE ||
        st.smb2_size > static_cast<uint64_t>(INT64_MAX)) {
        const char *err = smb2_get_error(ctx->smb2);
        smb2_close(ctx->smb2, file);
        if (status < 0)
            fail_smb(env, status, err);
        else if (st.smb2_type != SMB2_TYPE_FILE)
            fail_smb(env, -EISDIR, nullptr);
        else
            fail_smb(env, -EFBIG, nullptr);
        return nullptr;
    }
    jlongArray result = env->NewLongArray(2);
    if (!result) {
        smb2_close(ctx->smb2, file);
        return nullptr;
    }
    if (!smb_track_file(ctx, file)) {
        smb2_close(ctx->smb2, file);
        oom_smb(env);
        return nullptr;
    }
    const jlong values[] = {static_cast<jlong>(reinterpret_cast<intptr_t>(file)),
                            static_cast<jlong>(st.smb2_size)};
    env->SetLongArrayRegion(result, 0, 2, values);
    return result;
}

extern "C" JNIEXPORT jint JNICALL Java_app_whisp_data_SmbNative_read(JNIEnv *env, jobject,
                                                                     jlong pointer, jlong handle,
                                                                     jlong position,
                                                                     jbyteArray buffer, jint offset,
                                                                     jint length) {
    SmbConnection *ctx = smb_connection(env, pointer);
    if (!ctx || env->ExceptionCheck())
        return -1;
    auto *file = reinterpret_cast<smb2fh *>(static_cast<intptr_t>(handle));
    if (!smb_has_file(ctx, file)) {
        fail_smb(env, -EBADF, nullptr);
        return -1;
    }
    if (!buffer || position < 0 || length < 0 || length > kMaxSmbReadBytes || offset < 0 ||
        offset > env->GetArrayLength(buffer) - length) {
        fail_smb(env, -EINVAL, nullptr);
        return -1;
    }
    if (!length)
        return 0;
    const uint32_t want = clamp_smb_read(ctx->smb2, length);
    if (ctx->read_buffer_cap < want) {
        auto *next = static_cast<uint8_t *>(realloc(ctx->read_buffer, want));
        if (!next) {
            oom_smb(env);
            return -1;
        }
        ctx->read_buffer = next;
        ctx->read_buffer_cap = want;
    }
    const int status =
        smb2_pread(ctx->smb2, file, ctx->read_buffer, want, static_cast<uint64_t>(position));
    if (status < 0) {
        fail_smb(env, status, smb2_get_error(ctx->smb2));
        return -1;
    }
    if (static_cast<uint32_t>(status) > want) {
        fail_smb(env, -EIO, nullptr);
        return -1;
    }
    if (status > 0) {
        env->SetByteArrayRegion(buffer, offset, status,
                                reinterpret_cast<const jbyte *>(ctx->read_buffer));
        if (env->ExceptionCheck())
            return -1;
    }
    return status;
}

extern "C" JNIEXPORT jint JNICALL Java_app_whisp_data_SmbNative_readDirect(
    JNIEnv *env, jobject, jlong pointer, jlong handle, jlong position, jobject direct_buffer,
    jint offset, jint length) {
    SmbConnection *ctx = smb_connection(env, pointer);
    if (!ctx || env->ExceptionCheck())
        return -1;
    auto *file = reinterpret_cast<smb2fh *>(static_cast<intptr_t>(handle));
    if (!smb_has_file(ctx, file)) {
        fail_smb(env, -EBADF, nullptr);
        return -1;
    }
    if (!direct_buffer || position < 0 || length < 0 || length > kMaxSmbReadBytes || offset < 0) {
        fail_smb(env, -EINVAL, nullptr);
        return -1;
    }
    void *addr = env->GetDirectBufferAddress(direct_buffer);
    const jlong capacity = env->GetDirectBufferCapacity(direct_buffer);
    if (!addr || capacity < 0 || static_cast<jlong>(offset) > capacity - length) {
        fail_smb(env, -EINVAL, nullptr);
        return -1;
    }
    if (!length)
        return 0;
    const uint32_t want = clamp_smb_read(ctx->smb2, length);
    const int status = smb2_pread(ctx->smb2, file, static_cast<uint8_t *>(addr) + offset, want,
                                  static_cast<uint64_t>(position));
    if (status < 0) {
        fail_smb(env, status, smb2_get_error(ctx->smb2));
        return -1;
    }
    if (static_cast<uint32_t>(status) > want) {
        fail_smb(env, -EIO, nullptr);
        return -1;
    }
    return status;
}

extern "C" JNIEXPORT void JNICALL Java_app_whisp_data_SmbNative_closeFile(JNIEnv *env, jobject,
                                                                          jlong pointer,
                                                                          jlong handle) {
    SmbConnection *ctx = smb_connection(env, pointer);
    if (!ctx || env->ExceptionCheck())
        return;
    auto *file = reinterpret_cast<smb2fh *>(static_cast<intptr_t>(handle));
    if (smb_remove_file(ctx, file))
        smb2_close(ctx->smb2, file);
}

extern "C" JNIEXPORT void JNICALL Java_app_whisp_data_SmbNative_close(JNIEnv *, jobject,
                                                                      jlong pointer) {
    if (!pointer)
        return;
    auto *ctx = reinterpret_cast<SmbConnection *>(static_cast<intptr_t>(pointer));
    for (size_t i = 0; i < ctx->file_count; ++i)
        smb2_close(ctx->smb2, ctx->files[i]);
    free(ctx->files);
    free(ctx->read_buffer);
    smb2_disconnect_share(ctx->smb2);
    smb2_destroy_context(ctx->smb2);
    free(ctx);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (!vm || vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK || !env)
        return JNI_ERR;
    jclass local = env->FindClass("app/whisp/data/SmbNativeEntry");
    if (!local)
        return JNI_ERR;
    jmethodID ctor = env->GetMethodID(local, "<init>", "([BZJJ)V");
    if (!ctor) {
        env->DeleteLocalRef(local);
        return JNI_ERR;
    }
    auto global = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (!global)
        return JNI_ERR;
    g_smb_entry_class = global;
    g_smb_entry_ctor = ctor;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm && vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK && env &&
        g_smb_entry_class) {
        env->DeleteGlobalRef(g_smb_entry_class);
    }
    g_smb_entry_class = nullptr;
    g_smb_entry_ctor = nullptr;
}
