/* Minimal talloc-compatible allocator for proot (musl/static build).
 * Implements the subset of the Samba talloc API that proot uses:
 * context tree, reference counting, type checking, name, destructor. */
#ifndef TALLOC_H
#define TALLOC_H

#include <stddef.h>
#include <stdarg.h>

#ifndef __STRINGIZE
#define __STRINGIZE2(x) #x
#define __STRINGIZE(x) __STRINGIZE2(x)
#endif

void *talloc(const void *ctx, size_t size);
void *_talloc_zero(const void *ctx, size_t size, const char *name);
void *talloc_zero_size(const void *ctx, size_t size);
void *_talloc_array(const void *ctx, size_t el_size, unsigned count, const char *name);
void *_talloc_zero_array(const void *ctx, size_t el_size, unsigned count, const char *name);
void *talloc_realloc(const void *ctx, void *ptr, size_t size);
void *talloc_size(const void *ctx, size_t size);
size_t talloc_get_size(const void *ptr);
void talloc_free(void *ptr);
void talloc_free_children(void *ptr);
void *talloc_reference(const void *ctx, const void *ptr);
int talloc_unlink(const void *ctx, void *ptr);
int talloc_reference_count(const void *ptr);
void *talloc_reparent(const void *old_parent, const void *new_parent, void *ptr);
void *talloc_new(const void *ctx);
void talloc_set_destructor(void *ptr, int (*destructor)(void *));
void talloc_set_name_const(void *ptr, const char *name);
const char *talloc_get_name(const void *ptr);
const void *talloc_parent(const void *ptr);
void *talloc_check_name(const void *ptr, const char *name);
void *_talloc_get_type_abort(const void *ptr, const char *name, const char *location);
void *talloc_autofree_context(void);
char *talloc_strdup(const void *ctx, const char *str);
char *talloc_strndup(const void *ctx, const char *str, size_t n);
char *talloc_asprintf(const void *ctx, const char *fmt, ...);
char *talloc_vasprintf(const void *ctx, const char *fmt, va_list ap);
char *talloc_asprintf_append(char *s, const char *fmt, ...);
char *talloc_strdup_append(char *s, const char *a);
char *talloc_strdup_append_buffer(char *s, const char *a);
int talloc_report_depth_cb(const void *ptr, int depth, int max_depth,
                           void (*callback)(const void *ptr, int depth,
                                            int max_depth, void *user),
                           void *user);

#define talloc_zero(ctx, type) \
    ((type *)_talloc_zero(ctx, sizeof(type), #type))
#define talloc_zero_array(ctx, type, count) \
    ((type *)_talloc_zero_array(ctx, sizeof(type), count, #type))
#define talloc_array(ctx, type, count) \
    ((type *)_talloc_array(ctx, sizeof(type), count, #type))
#define talloc_array_length(ctx) (talloc_get_size(ctx) / sizeof(*(ctx)))
#define talloc_get_type(ctx, type) ((type *)talloc_check_name(ctx, #type))
#define talloc_get_type_abort(ctx, type) \
    ((type *)_talloc_get_type_abort(ctx, #type, __FILE__ ":" __STRINGIZE(__LINE__)))
#define talloc_hierarchy(ctx) talloc_new(ctx)
#define talloc_zero_p(ctx, type) talloc_zero(ctx, type)

#endif /* TALLOC_H */
