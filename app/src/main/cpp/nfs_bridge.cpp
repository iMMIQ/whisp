// SPDX-License-Identifier: LGPL-3.0-or-later
#include <cerrno>
#include <cstdint>
#include <cstring>
#include <fcntl.h>
#include <jni.h>
#include <limits>
#include <new>
#include <nfsc/libnfs.h>
#include <string>
#include <sys/stat.h>
#include <unordered_set>
#include <vector>

namespace {
jclass g_entry_class = nullptr;
jmethodID g_entry_ctor = nullptr;

struct Connection {
    nfs_context *nfs;
    std::unordered_set<nfsfh *> files;
    std::vector<char> read_buffer;
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

std::string bytes(JNIEnv *env, jbyteArray value) {
    if (env->ExceptionCheck())
        return {};
    if (!value) {
        fail(env, -EINVAL);
        return {};
    }
    const auto size = env->GetArrayLength(value);
    if (size < 0 || size > 16384) {
        fail(env, -EINVAL);
        return {};
    }
    std::string result(static_cast<size_t>(size), '\0');
    if (size > 0) {
        env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte *>(result.data()));
        if (env->ExceptionCheck())
            return {};
    }
    if (result.find('\0') != std::string::npos)
        fail(env, -EINVAL);
    return result;
}

Connection *connection(JNIEnv *env, jlong pointer) {
    if (!pointer) {
        fail(env, -EBADF);
        return nullptr;
    }
    return reinterpret_cast<Connection *>(static_cast<intptr_t>(pointer));
}

struct Directory {
    nfs_context *nfs;
    nfsdir *dir;
    ~Directory() {
        if (dir)
            nfs_closedir(nfs, dir);
    }
};
} // namespace

extern "C" JNIEXPORT jlong JNICALL Java_app_whisp_data_NfsNative_connect(
    JNIEnv *env, jobject, jbyteArray host, jbyteArray path, jint version, jint uid, jint gid,
    jint port, jint mount_port) {
    try {
        auto server = bytes(env, host);
        if (env->ExceptionCheck())
            return 0;
        auto root = bytes(env, path);
        if (env->ExceptionCheck())
            return 0;
        if (server.empty() || root.empty() || root[0] != '/' || uid < 0 || gid < 0 || port < 1 ||
            port > 65535 || mount_port < 0 || mount_port > 65535) {
            fail(env, -EINVAL);
            return 0;
        }
        auto *nfs = nfs_init_context();
        if (!nfs) {
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
        nfs_set_readmax(nfs, 1024 * 1024);
        int status = nfs_set_version(nfs, version);
        if (status == 0)
            status = nfs_mount(nfs, server.c_str(), root.c_str());
        if (status < 0) {
            nfs_destroy_context(nfs);
            fail(env, status);
            return 0;
        }
        auto *result = new (std::nothrow) Connection{nfs, {}, {}};
        if (!result) {
            nfs_destroy_context(nfs);
            oom(env);
            return 0;
        }
        return static_cast<jlong>(reinterpret_cast<intptr_t>(result));
    } catch (const std::bad_alloc &) {
        oom(env);
        return 0;
    }
}

extern "C" JNIEXPORT jobjectArray JNICALL Java_app_whisp_data_NfsNative_entries(JNIEnv *env,
                                                                                jobject,
                                                                                jlong pointer,
                                                                                jbyteArray path) {
    try {
        auto *ctx = connection(env, pointer);
        if (!ctx || env->ExceptionCheck())
            return nullptr;
        auto relative = bytes(env, path);
        if (env->ExceptionCheck())
            return nullptr;
        Directory dir{ctx->nfs, nullptr};
        const int status = nfs_opendir(ctx->nfs, relative.c_str(), &dir.dir);
        if (status < 0) {
            fail(env, status);
            return nullptr;
        }
        struct Entry {
            std::string name;
            uint32_t type;
            uint64_t size;
            int64_t modified;
        };
        std::vector<Entry> entries;
        while (auto *item = nfs_readdir(ctx->nfs, dir.dir)) {
            if (!item->name || !std::strcmp(item->name, ".") || !std::strcmp(item->name, ".."))
                continue;
            if (entries.size() >= 100000) {
                fail(env, -EFBIG);
                return nullptr;
            }
            if (item->size > static_cast<uint64_t>(std::numeric_limits<jlong>::max())) {
                fail(env, -EFBIG);
                return nullptr;
            }
            entries.push_back(
                {item->name, item->type, item->size, static_cast<int64_t>(item->mtime.tv_sec)});
        }
        jclass local_cls = nullptr;
        jclass cls = g_entry_class;
        jmethodID ctor = g_entry_ctor;
        if (!cls || !ctor) {
            local_cls = env->FindClass("app/whisp/data/NfsNativeEntry");
            if (!local_cls)
                return nullptr;
            ctor = env->GetMethodID(local_cls, "<init>", "([BIJJ)V");
            if (!ctor) {
                env->DeleteLocalRef(local_cls);
                return nullptr;
            }
            cls = local_cls;
        }
        auto result = env->NewObjectArray(static_cast<jsize>(entries.size()), cls, nullptr);
        if (!result) {
            if (local_cls)
                env->DeleteLocalRef(local_cls);
            return nullptr;
        }
        for (size_t index = 0; index < entries.size(); ++index) {
            const auto &item = entries[index];
            auto name = env->NewByteArray(static_cast<jsize>(item.name.size()));
            if (!name) {
                if (local_cls)
                    env->DeleteLocalRef(local_cls);
                return nullptr;
            }
            if (!item.name.empty()) {
                env->SetByteArrayRegion(name, 0, static_cast<jsize>(item.name.size()),
                                        reinterpret_cast<const jbyte *>(item.name.data()));
                if (env->ExceptionCheck()) {
                    env->DeleteLocalRef(name);
                    if (local_cls)
                        env->DeleteLocalRef(local_cls);
                    return nullptr;
                }
            }
            auto entry =
                env->NewObject(cls, ctor, name, static_cast<jint>(item.type),
                               static_cast<jlong>(item.size), static_cast<jlong>(item.modified));
            env->DeleteLocalRef(name);
            if (!entry) {
                if (local_cls)
                    env->DeleteLocalRef(local_cls);
                return nullptr;
            }
            env->SetObjectArrayElement(result, static_cast<jsize>(index), entry);
            env->DeleteLocalRef(entry);
            if (env->ExceptionCheck()) {
                if (local_cls)
                    env->DeleteLocalRef(local_cls);
                return nullptr;
            }
        }
        if (local_cls)
            env->DeleteLocalRef(local_cls);
        return result;
    } catch (const std::bad_alloc &) {
        oom(env);
        return nullptr;
    }
}

extern "C" JNIEXPORT jlongArray JNICALL Java_app_whisp_data_NfsNative_open(JNIEnv *env, jobject,
                                                                           jlong pointer,
                                                                           jbyteArray path) {
    try {
        auto *ctx = connection(env, pointer);
        if (!ctx || env->ExceptionCheck())
            return nullptr;
        auto relative = bytes(env, path);
        if (env->ExceptionCheck())
            return nullptr;
        nfsfh *file = nullptr;
        int status = nfs_open(ctx->nfs, relative.c_str(), O_RDONLY, &file);
        if (status < 0) {
            fail(env, status);
            return nullptr;
        }
        nfs_stat_64 st{};
        status = nfs_fstat64(ctx->nfs, file, &st);
        if (status < 0 || !S_ISREG(st.nfs_mode) ||
            st.nfs_size > static_cast<uint64_t>(std::numeric_limits<jlong>::max())) {
            nfs_close(ctx->nfs, file);
            if (status < 0)
                fail(env, status);
            else if (!S_ISREG(st.nfs_mode))
                fail(env, -EISDIR);
            else
                fail(env, -EFBIG);
            return nullptr;
        }
        auto result = env->NewLongArray(2);
        if (!result) {
            nfs_close(ctx->nfs, file);
            return nullptr;
        }
        try {
            ctx->files.insert(file);
        } catch (...) {
            nfs_close(ctx->nfs, file);
            throw;
        }
        const jlong values[] = {static_cast<jlong>(reinterpret_cast<intptr_t>(file)),
                                static_cast<jlong>(st.nfs_size)};
        env->SetLongArrayRegion(result, 0, 2, values);
        return result;
    } catch (const std::bad_alloc &) {
        oom(env);
        return nullptr;
    }
}

extern "C" JNIEXPORT jint JNICALL Java_app_whisp_data_NfsNative_read(JNIEnv *env, jobject,
                                                                     jlong pointer, jlong handle,
                                                                     jlong position,
                                                                     jbyteArray buffer, jint offset,
                                                                     jint length) {
    try {
        auto *ctx = connection(env, pointer);
        if (!ctx || env->ExceptionCheck())
            return -1;
        auto *file = reinterpret_cast<nfsfh *>(static_cast<intptr_t>(handle));
        if (!ctx->files.count(file)) {
            fail(env, -EBADF);
            return -1;
        }
        if (!buffer || position < 0 || length < 0 || length > 1024 * 1024 || offset < 0 ||
            offset > env->GetArrayLength(buffer) - length) {
            fail(env, -EINVAL);
            return -1;
        }
        if (!length)
            return 0;
        const auto required = static_cast<size_t>(length);
        if (ctx->read_buffer.size() < required)
            ctx->read_buffer.resize(required);
        const int status = nfs_pread(ctx->nfs, file, ctx->read_buffer.data(), required,
                                     static_cast<uint64_t>(position));
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
                                    reinterpret_cast<jbyte *>(ctx->read_buffer.data()));
            if (env->ExceptionCheck())
                return -1;
        }
        return status;
    } catch (const std::bad_alloc &) {
        oom(env);
        return -1;
    }
}

extern "C" JNIEXPORT void JNICALL Java_app_whisp_data_NfsNative_closeFile(JNIEnv *env, jobject,
                                                                          jlong pointer,
                                                                          jlong handle) {
    auto *ctx = connection(env, pointer);
    if (!ctx || env->ExceptionCheck())
        return;
    auto *file = reinterpret_cast<nfsfh *>(static_cast<intptr_t>(handle));
    if (ctx->files.erase(file))
        nfs_close(ctx->nfs, file);
}

extern "C" JNIEXPORT void JNICALL Java_app_whisp_data_NfsNative_close(JNIEnv *, jobject,
                                                                      jlong pointer) {
    if (!pointer)
        return;
    auto *ctx = reinterpret_cast<Connection *>(static_cast<intptr_t>(pointer));
    for (auto *file : ctx->files)
        nfs_close(ctx->nfs, file);
    nfs_umount(ctx->nfs);
    nfs_destroy_context(ctx->nfs);
    delete ctx;
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env = nullptr;
    if (!vm || vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK || !env)
        return JNI_ERR;
    auto local = env->FindClass("app/whisp/data/NfsNativeEntry");
    if (!local)
        return JNI_ERR;
    auto ctor = env->GetMethodID(local, "<init>", "([BIJJ)V");
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
