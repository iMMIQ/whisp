// SPDX-License-Identifier: LGPL-3.0-or-later
#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <limits.h>
#include <nfsc/libnfs.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>

namespace {
jclass g_entry_class = nullptr;
jmethodID g_entry_ctor = nullptr;

constexpr size_t kMaxPathBytes = 16384;
constexpr size_t kMaxDirEntries = 100000;
constexpr jint kMaxReadBytes = 1024 * 1024;

struct Connection {
    nfs_context *nfs;
    nfsfh **files;
    size_t file_count;
    size_t file_cap;
    char *read_buffer;
    size_t read_buffer_cap;
};

struct DirEntry {
    char *name;
    size_t name_len;
    uint32_t type;
    uint64_t size;
    int64_t modified;
};

void fail(JNIEnv *env, int code) {
    if (env->ExceptionCheck())
        return;
    const char *message = "NFS：读取失败，请检查连接或重新扫描";
    if (code == -EACCES || code == -EPERM)
        message = "NFS：访问被拒绝，请检查 NAS 授权、UID/GID 与非特权端口设置";
    else if (code == -ENOENT)
        message = "NFS：目录或文件不存在";
    else if (code == -ETIMEDOUT || code == -ECONNREFUSED || code == -EHOSTUNREACH ||
             code == -ENETUNREACH)
        message = "NFS：连接失败，请检查地址和协议版本";
    else if (code == -EINVAL || code == -EPROTONOSUPPORT)
        message = "NFS：协议或目录配置无效";
    else if (code == -EFBIG)
        message = "NFS：目录文件过多，请选择 ASMR 子目录";
    else if (code == -EISDIR)
        message = "NFS：所选资源不是普通文件";
    if (auto cls = env->FindClass("java/io/IOException"))
        env->ThrowNew(cls, message);
}

void oom(JNIEnv *env) {
    if (env->ExceptionCheck())
        return;
    if (auto cls = env->FindClass("java/lang/OutOfMemoryError"))
        env->ThrowNew(cls, "NFS buffer allocation failed");
}

char *copy_path_bytes(JNIEnv *env, jbyteArray value, size_t *out_len) {
    if (env->ExceptionCheck())
        return nullptr;
    if (!value) {
        fail(env, -EINVAL);
        return nullptr;
    }
    const jsize size = env->GetArrayLength(value);
    if (size < 0 || static_cast<size_t>(size) > kMaxPathBytes) {
        fail(env, -EINVAL);
        return nullptr;
    }
    const size_t len = static_cast<size_t>(size);
    char *buf = static_cast<char *>(malloc(len + 1));
    if (!buf) {
        oom(env);
        return nullptr;
    }
    if (len > 0) {
        env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte *>(buf));
        if (env->ExceptionCheck()) {
            free(buf);
            return nullptr;
        }
        if (memchr(buf, '\0', len) != nullptr) {
            free(buf);
            fail(env, -EINVAL);
            return nullptr;
        }
    }
    buf[len] = '\0';
    if (out_len)
        *out_len = len;
    return buf;
}

Connection *connection(JNIEnv *env, jlong pointer) {
    if (!pointer) {
        fail(env, -EBADF);
        return nullptr;
    }
    return reinterpret_cast<Connection *>(static_cast<intptr_t>(pointer));
}

bool track_file(Connection *ctx, nfsfh *file) {
    if (ctx->file_count == ctx->file_cap) {
        const size_t next_cap = ctx->file_cap ? ctx->file_cap * 2 : 8;
        auto *next = static_cast<nfsfh **>(realloc(ctx->files, next_cap * sizeof(nfsfh *)));
        if (!next)
            return false;
        ctx->files = next;
        ctx->file_cap = next_cap;
    }
    ctx->files[ctx->file_count++] = file;
    return true;
}

bool has_file(const Connection *ctx, nfsfh *file) {
    for (size_t i = 0; i < ctx->file_count; ++i) {
        if (ctx->files[i] == file)
            return true;
    }
    return false;
}

bool remove_file(Connection *ctx, nfsfh *file) {
    for (size_t i = 0; i < ctx->file_count; ++i) {
        if (ctx->files[i] == file) {
            ctx->files[i] = ctx->files[--ctx->file_count];
            return true;
        }
    }
    return false;
}

void free_dir_entries(DirEntry *entries, size_t count) {
    if (!entries)
        return;
    for (size_t i = 0; i < count; ++i)
        free(entries[i].name);
    free(entries);
}
} // namespace

extern "C" JNIEXPORT jlong JNICALL Java_app_whisp_data_NfsNative_connect(
    JNIEnv *env, jobject, jbyteArray host, jbyteArray path, jint version, jint uid, jint gid,
    jint port, jint mount_port) {
    size_t server_len = 0;
    char *server = copy_path_bytes(env, host, &server_len);
    if (!server)
        return 0;
    size_t root_len = 0;
    char *root = copy_path_bytes(env, path, &root_len);
    if (!root) {
        free(server);
        return 0;
    }
    if (server_len == 0 || root_len == 0 || root[0] != '/' || uid < 0 || gid < 0 || port < 1 ||
        port > 65535 || mount_port < 0 || mount_port > 65535) {
        free(server);
        free(root);
        fail(env, -EINVAL);
        return 0;
    }
    nfs_context *nfs = nfs_init_context();
    if (!nfs) {
        free(server);
        free(root);
        oom(env);
        return 0;
    }
    nfs_set_timeout(nfs, 10000);
    nfs_set_retrans(nfs, 0);
    nfs_set_autoreconnect(nfs, 1);
    nfs_set_uid(nfs, uid);
    nfs_set_gid(nfs, gid);
    nfs_set_auxiliary_gids(nfs, 0, nullptr);
    nfs_set_nfsport(nfs, port);
    if (mount_port)
        nfs_set_mountport(nfs, mount_port);
    nfs_set_readonly(nfs, 1);
    nfs_set_auto_traverse_mounts(nfs, 0);
    nfs_set_readmax(nfs, static_cast<size_t>(kMaxReadBytes));
    int status = nfs_set_version(nfs, version);
    if (status == 0)
        status = nfs_mount(nfs, server, root);
    free(server);
    free(root);
    if (status < 0) {
        nfs_destroy_context(nfs);
        fail(env, status);
        return 0;
    }
    auto *result = static_cast<Connection *>(calloc(1, sizeof(Connection)));
    if (!result) {
        nfs_umount(nfs);
        nfs_destroy_context(nfs);
        oom(env);
        return 0;
    }
    result->nfs = nfs;
    return static_cast<jlong>(reinterpret_cast<intptr_t>(result));
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_app_whisp_data_NfsNative_entries(JNIEnv *env,
                                                                                jobject,
                                                                                jlong pointer,
                                                                                jbyteArray path) {
    Connection *ctx = connection(env, pointer);
    if (!ctx || env->ExceptionCheck())
        return nullptr;
    char *relative = copy_path_bytes(env, path, nullptr);
    if (!relative)
        return nullptr;
    nfsdir *dir = nullptr;
    const int status = nfs_opendir(ctx->nfs, relative, &dir);
    free(relative);
    if (status < 0) {
        fail(env, status);
        return nullptr;
    }

    DirEntry *entries = nullptr;
    size_t count = 0;
    size_t cap = 0;
    while (auto *item = nfs_readdir(ctx->nfs, dir)) {
        if (!item->name || !strcmp(item->name, ".") || !strcmp(item->name, ".."))
            continue;
        if (count >= kMaxDirEntries) {
            nfs_closedir(ctx->nfs, dir);
            free_dir_entries(entries, count);
            fail(env, -EFBIG);
            return nullptr;
        }
        if (item->size > static_cast<uint64_t>(INT64_MAX)) {
            nfs_closedir(ctx->nfs, dir);
            free_dir_entries(entries, count);
            fail(env, -EFBIG);
            return nullptr;
        }
        if (count == cap) {
            const size_t next_cap = cap ? cap * 2 : 32;
            auto *next = static_cast<DirEntry *>(realloc(entries, next_cap * sizeof(DirEntry)));
            if (!next) {
                nfs_closedir(ctx->nfs, dir);
                free_dir_entries(entries, count);
                oom(env);
                return nullptr;
            }
            entries = next;
            cap = next_cap;
        }
        const size_t nlen = strlen(item->name);
        char *dup = static_cast<char *>(malloc(nlen + 1));
        if (!dup) {
            nfs_closedir(ctx->nfs, dir);
            free_dir_entries(entries, count);
            oom(env);
            return nullptr;
        }
        memcpy(dup, item->name, nlen + 1);
        entries[count++] =
            DirEntry{dup, nlen, item->type, item->size, static_cast<int64_t>(item->mtime.tv_sec)};
    }
    nfs_closedir(ctx->nfs, dir);

    jclass local_cls = nullptr;
    jclass cls = g_entry_class;
    jmethodID ctor = g_entry_ctor;
    if (!cls || !ctor) {
        local_cls = env->FindClass("app/whisp/data/NfsNativeEntry");
        if (!local_cls) {
            free_dir_entries(entries, count);
            return nullptr;
        }
        ctor = env->GetMethodID(local_cls, "<init>", "([BIJJ)V");
        if (!ctor) {
            env->DeleteLocalRef(local_cls);
            free_dir_entries(entries, count);
            return nullptr;
        }
        cls = local_cls;
    }

    jobjectArray result = env->NewObjectArray(static_cast<jsize>(count), cls, nullptr);
    if (!result) {
        if (local_cls)
            env->DeleteLocalRef(local_cls);
        free_dir_entries(entries, count);
        return nullptr;
    }

    for (size_t index = 0; index < count; ++index) {
        const DirEntry &item = entries[index];
        jbyteArray name = env->NewByteArray(static_cast<jsize>(item.name_len));
        if (!name) {
            if (local_cls)
                env->DeleteLocalRef(local_cls);
            free_dir_entries(entries, count);
            return nullptr;
        }
        if (item.name_len > 0) {
            env->SetByteArrayRegion(name, 0, static_cast<jsize>(item.name_len),
                                    reinterpret_cast<const jbyte *>(item.name));
            if (env->ExceptionCheck()) {
                env->DeleteLocalRef(name);
                if (local_cls)
                    env->DeleteLocalRef(local_cls);
                free_dir_entries(entries, count);
                return nullptr;
            }
        }
        jobject entry =
            env->NewObject(cls, ctor, name, static_cast<jint>(item.type),
                           static_cast<jlong>(item.size), static_cast<jlong>(item.modified));
        env->DeleteLocalRef(name);
        if (!entry) {
            if (local_cls)
                env->DeleteLocalRef(local_cls);
            free_dir_entries(entries, count);
            return nullptr;
        }
        env->SetObjectArrayElement(result, static_cast<jsize>(index), entry);
        env->DeleteLocalRef(entry);
        if (env->ExceptionCheck()) {
            if (local_cls)
                env->DeleteLocalRef(local_cls);
            free_dir_entries(entries, count);
            return nullptr;
        }
    }
    if (local_cls)
        env->DeleteLocalRef(local_cls);
    free_dir_entries(entries, count);
    return result;
}

extern "C" JNIEXPORT jlongArray JNICALL Java_app_whisp_data_NfsNative_open(JNIEnv *env, jobject,
                                                                           jlong pointer,
                                                                           jbyteArray path) {
    Connection *ctx = connection(env, pointer);
    if (!ctx || env->ExceptionCheck())
        return nullptr;
    char *relative = copy_path_bytes(env, path, nullptr);
    if (!relative)
        return nullptr;
    nfsfh *file = nullptr;
    int status = nfs_open(ctx->nfs, relative, O_RDONLY, &file);
    free(relative);
    if (status < 0) {
        fail(env, status);
        return nullptr;
    }
    nfs_stat_64 st{};
    status = nfs_fstat64(ctx->nfs, file, &st);
    if (status < 0 || !S_ISREG(st.nfs_mode) || st.nfs_size > static_cast<uint64_t>(INT64_MAX)) {
        nfs_close(ctx->nfs, file);
        if (status < 0)
            fail(env, status);
        else if (!S_ISREG(st.nfs_mode))
            fail(env, -EISDIR);
        else
            fail(env, -EFBIG);
        return nullptr;
    }
    jlongArray result = env->NewLongArray(2);
    if (!result) {
        nfs_close(ctx->nfs, file);
        return nullptr;
    }
    if (!track_file(ctx, file)) {
        nfs_close(ctx->nfs, file);
        oom(env);
        return nullptr;
    }
    const jlong values[] = {static_cast<jlong>(reinterpret_cast<intptr_t>(file)),
                            static_cast<jlong>(st.nfs_size)};
    env->SetLongArrayRegion(result, 0, 2, values);
    return result;
}

extern "C" JNIEXPORT jint JNICALL Java_app_whisp_data_NfsNative_read(JNIEnv *env, jobject,
                                                                     jlong pointer, jlong handle,
                                                                     jlong position,
                                                                     jbyteArray buffer, jint offset,
                                                                     jint length) {
    Connection *ctx = connection(env, pointer);
    if (!ctx || env->ExceptionCheck())
        return -1;
    auto *file = reinterpret_cast<nfsfh *>(static_cast<intptr_t>(handle));
    if (!has_file(ctx, file)) {
        fail(env, -EBADF);
        return -1;
    }
    if (!buffer || position < 0 || length < 0 || length > kMaxReadBytes || offset < 0 ||
        offset > env->GetArrayLength(buffer) - length) {
        fail(env, -EINVAL);
        return -1;
    }
    if (!length)
        return 0;
    const size_t required = static_cast<size_t>(length);
    if (ctx->read_buffer_cap < required) {
        char *next = static_cast<char *>(realloc(ctx->read_buffer, required));
        if (!next) {
            oom(env);
            return -1;
        }
        ctx->read_buffer = next;
        ctx->read_buffer_cap = required;
    }
    const int status =
        nfs_pread(ctx->nfs, file, ctx->read_buffer, required, static_cast<uint64_t>(position));
    if (status < 0) {
        fail(env, status);
        return -1;
    }
    if (status > length) {
        fail(env, -EIO);
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

extern "C" JNIEXPORT jint JNICALL Java_app_whisp_data_NfsNative_readDirect(
    JNIEnv *env, jobject, jlong pointer, jlong handle, jlong position, jobject direct_buffer,
    jint offset, jint length) {
    Connection *ctx = connection(env, pointer);
    if (!ctx || env->ExceptionCheck())
        return -1;
    auto *file = reinterpret_cast<nfsfh *>(static_cast<intptr_t>(handle));
    if (!has_file(ctx, file)) {
        fail(env, -EBADF);
        return -1;
    }
    if (!direct_buffer || position < 0 || length < 0 || length > kMaxReadBytes || offset < 0) {
        fail(env, -EINVAL);
        return -1;
    }
    void *addr = env->GetDirectBufferAddress(direct_buffer);
    const jlong capacity = env->GetDirectBufferCapacity(direct_buffer);
    if (!addr || capacity < 0 || static_cast<jlong>(offset) > capacity - length) {
        fail(env, -EINVAL);
        return -1;
    }
    if (!length)
        return 0;
    const int status = nfs_pread(ctx->nfs, file, static_cast<char *>(addr) + offset,
                                 static_cast<size_t>(length), static_cast<uint64_t>(position));
    if (status < 0) {
        fail(env, status);
        return -1;
    }
    if (status > length) {
        fail(env, -EIO);
        return -1;
    }
    return status;
}

extern "C" JNIEXPORT void JNICALL Java_app_whisp_data_NfsNative_closeFile(JNIEnv *env, jobject,
                                                                          jlong pointer,
                                                                          jlong handle) {
    Connection *ctx = connection(env, pointer);
    if (!ctx || env->ExceptionCheck())
        return;
    auto *file = reinterpret_cast<nfsfh *>(static_cast<intptr_t>(handle));
    if (remove_file(ctx, file))
        nfs_close(ctx->nfs, file);
}

extern "C" JNIEXPORT void JNICALL Java_app_whisp_data_NfsNative_close(JNIEnv *, jobject,
                                                                      jlong pointer) {
    if (!pointer)
        return;
    auto *ctx = reinterpret_cast<Connection *>(static_cast<intptr_t>(pointer));
    for (size_t i = 0; i < ctx->file_count; ++i)
        nfs_close(ctx->nfs, ctx->files[i]);
    free(ctx->files);
    free(ctx->read_buffer);
    nfs_umount(ctx->nfs);
    nfs_destroy_context(ctx->nfs);
    free(ctx);
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (!vm || vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK || !env)
        return JNI_ERR;
    jclass local = env->FindClass("app/whisp/data/NfsNativeEntry");
    if (!local)
        return JNI_ERR;
    jmethodID ctor = env->GetMethodID(local, "<init>", "([BIJJ)V");
    if (!ctor) {
        env->DeleteLocalRef(local);
        return JNI_ERR;
    }
    auto global = reinterpret_cast<jclass>(env->NewGlobalRef(local));
    env->DeleteLocalRef(local);
    if (!global)
        return JNI_ERR;
    g_entry_class = global;
    g_entry_ctor = ctor;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL JNI_OnUnload(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (vm && vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK && env &&
        g_entry_class) {
        env->DeleteGlobalRef(g_entry_class);
    }
    g_entry_class = nullptr;
    g_entry_ctor = nullptr;
}
