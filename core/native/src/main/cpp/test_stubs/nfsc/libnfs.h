// SPDX-License-Identifier: LGPL-3.0-or-later
#pragma once

#include <cstddef>
#include <cstdint>
#include <ctime>
#include <sys/stat.h>

struct nfs_context;
struct nfsdir;
struct nfsfh;

struct nfsdirent {
    const char *name;
    uint32_t type;
    uint64_t size;
    struct {
        int64_t tv_sec;
    } mtime;
};

struct nfs_stat_64 {
    uint64_t nfs_mode;
    uint64_t nfs_size;
};

extern "C" {
nfs_context *nfs_init_context();
void nfs_destroy_context(nfs_context *nfs);
void nfs_set_timeout(nfs_context *nfs, int timeout);
void nfs_set_retrans(nfs_context *nfs, int retrans);
void nfs_set_autoreconnect(nfs_context *nfs, int count);
void nfs_set_uid(nfs_context *nfs, int uid);
void nfs_set_gid(nfs_context *nfs, int gid);
void nfs_set_auxiliary_gids(nfs_context *nfs, uint32_t len, uint32_t *gids);
void nfs_set_nfsport(nfs_context *nfs, int port);
void nfs_set_mountport(nfs_context *nfs, int port);
void nfs_set_readonly(nfs_context *nfs, int readonly);
void nfs_set_auto_traverse_mounts(nfs_context *nfs, int enabled);
void nfs_set_readmax(nfs_context *nfs, size_t readmax);
int nfs_set_version(nfs_context *nfs, int version);
int nfs_mount(nfs_context *nfs, const char *server, const char *exportname);
int nfs_umount(nfs_context *nfs);
int nfs_opendir(nfs_context *nfs, const char *path, nfsdir **dir);
nfsdirent *nfs_readdir(nfs_context *nfs, nfsdir *dir);
void nfs_closedir(nfs_context *nfs, nfsdir *dir);
int nfs_open(nfs_context *nfs, const char *path, int flags, nfsfh **fh);
int nfs_fstat64(nfs_context *nfs, nfsfh *fh, nfs_stat_64 *st);
int nfs_pread(nfs_context *nfs, nfsfh *fh, void *buf, size_t count, uint64_t offset);
int nfs_close(nfs_context *nfs, nfsfh *fh);
}
