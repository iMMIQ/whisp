// SPDX-License-Identifier: LGPL-3.0-or-later
#ifndef WHISP_TEST_STUB_LIBSMB2_H
#define WHISP_TEST_STUB_LIBSMB2_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define SMB2_TYPE_FILE 0x00000000
#define SMB2_TYPE_DIRECTORY 0x00000001
#define SMB2_TYPE_LINK 0x00000002

#define SMB2_NEGOTIATE_SIGNING_ENABLED 0x0001

enum smb2_sec {
    SMB2_SEC_UNDEFINED = 0,
    SMB2_SEC_NTLMSSP = 1,
};

struct smb2_context;
struct smb2dir;
struct smb2fh;

struct smb2_stat_64 {
    uint32_t smb2_type;
    uint32_t smb2_nlink;
    uint64_t smb2_ino;
    uint64_t smb2_size;
    uint64_t smb2_atime;
    uint64_t smb2_atime_nsec;
    uint64_t smb2_mtime;
    uint64_t smb2_mtime_nsec;
    uint64_t smb2_ctime;
    uint64_t smb2_ctime_nsec;
    uint64_t smb2_btime;
    uint64_t smb2_btime_nsec;
};

struct smb2dirent {
    const char *name;
    struct smb2_stat_64 st;
};

struct smb2_context *smb2_init_context(void);
void smb2_destroy_context(struct smb2_context *smb2);
void smb2_set_timeout(struct smb2_context *smb2, int seconds);
void smb2_set_security_mode(struct smb2_context *smb2, uint16_t security_mode);
void smb2_set_seal(struct smb2_context *smb2, int val);
void smb2_set_authentication(struct smb2_context *smb2, int val);
void smb2_set_user(struct smb2_context *smb2, const char *user);
void smb2_set_password(struct smb2_context *smb2, const char *password);
void smb2_set_domain(struct smb2_context *smb2, const char *domain);
int smb2_connect_share(struct smb2_context *smb2, const char *server, const char *share,
                       const char *user);
int smb2_disconnect_share(struct smb2_context *smb2);
const char *smb2_get_error(struct smb2_context *smb2);
uint32_t smb2_get_max_read_size(struct smb2_context *smb2);
struct smb2dir *smb2_opendir(struct smb2_context *smb2, const char *path);
struct smb2dirent *smb2_readdir(struct smb2_context *smb2, struct smb2dir *smb2dir);
void smb2_closedir(struct smb2_context *smb2, struct smb2dir *smb2dir);
struct smb2fh *smb2_open(struct smb2_context *smb2, const char *path, int flags);
int smb2_fstat(struct smb2_context *smb2, struct smb2fh *fh, struct smb2_stat_64 *st);
int smb2_pread(struct smb2_context *smb2, struct smb2fh *fh, uint8_t *buf, uint32_t count,
               uint64_t offset);
int smb2_close(struct smb2_context *smb2, struct smb2fh *fh);

#ifdef __cplusplus
}
#endif

#endif
