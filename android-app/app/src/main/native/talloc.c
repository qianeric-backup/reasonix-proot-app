/* Minimal talloc-compatible allocator for proot (musl/static build). */
#include "talloc.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

typedef struct talloc_chunk {
    struct talloc_chunk *parent;   /* owning parent (first parent) */
    struct talloc_chunk *children; /* list of children */
    struct talloc_chunk *next, *prev; /* siblings in parent's list */
    size_t size;                   /* payload size */
    const char *name;              /* type/name tag */
    int (*destructor)(void *);     /* called before free */
    int refs;                      /* reference count (>=1) */
} talloc_chunk;

#define CHUNK_FROM_PTR(p) (((talloc_chunk *)(p)) - 1)
#define PTR_FROM_CHUNK(c) ((void *)(((talloc_chunk *)(c)) + 1))

static talloc_chunk *g_autofree = NULL;

static talloc_chunk *root(void) {
    if (g_autofree == NULL) {
        g_autofree = calloc(1, sizeof(talloc_chunk));
        g_autofree->refs = 1;
    }
    return g_autofree;
}

static void link_child(talloc_chunk *parent, talloc_chunk *child) {
    child->parent = parent;
    child->next = parent->children;
    child->prev = NULL;
    if (parent->children)
        parent->children->prev = child;
    parent->children = child;
}

static void unlink_child(talloc_chunk *child) {
    if (child->prev)
        child->prev->next = child->next;
    else if (child->parent)
        child->parent->children = child->next;
    if (child->next)
        child->next->prev = child->prev;
    child->prev = child->next = NULL;
    child->parent = NULL;
}

static void real_free(talloc_chunk *c) {
    /* recursively free children */
    while (c->children) {
        talloc_chunk *ch = c->children;
        unlink_child(ch);
        ch->refs--;
        if (ch->refs <= 0)
            real_free(ch);
    }
    if (c->destructor)
        c->destructor(PTR_FROM_CHUNK(c));
    if (c->parent)
        unlink_child(c);
    free(c);
}

void *talloc(const void *ctx, size_t size) {
    talloc_chunk *parent = ctx ? CHUNK_FROM_PTR(ctx) : root();
    talloc_chunk *c = calloc(1, sizeof(talloc_chunk) + size);
    if (!c)
        return NULL;
    c->size = size;
    c->refs = 1;
    link_child(parent, c);
    return PTR_FROM_CHUNK(c);
}

void *_talloc_zero(const void *ctx, size_t size, const char *name) {
    void *p = talloc(ctx, size);
    if (p) {
        memset(p, 0, size);
        talloc_set_name_const(p, name);
    }
    return p;
}

void *talloc_zero_size(const void *ctx, size_t size) {
    return _talloc_zero(ctx, size, "talloc_zero_size");
}

void *_talloc_array(const void *ctx, size_t el_size, unsigned count, const char *name) {
    if (el_size && count > (size_t)-1 / el_size)
        return NULL;
    void *p = talloc(ctx, el_size * count);
    if (p)
        talloc_set_name_const(p, name);
    return p;
}

void *_talloc_zero_array(const void *ctx, size_t el_size, unsigned count, const char *name) {
    if (el_size && count > (size_t)-1 / el_size)
        return NULL;
    void *p = _talloc_zero(ctx, el_size * count, name);
    return p;
}

void *talloc_realloc(const void *ctx, void *ptr, size_t size) {
    if (ptr == NULL)
        return talloc(ctx, size);
    talloc_chunk *c = CHUNK_FROM_PTR(ptr);
    talloc_chunk *parent = c->parent;
    const char *name = c->name;
    int (*destructor)(void *) = c->destructor;
    int refs = c->refs;
    unlink_child(c);
    talloc_chunk *nc = realloc(c, sizeof(talloc_chunk) + size);
    if (!nc) {
        /* restore */
        c->refs = refs;
        link_child(parent, c);
        return NULL;
    }
    nc->size = size;
    nc->name = name;
    nc->destructor = destructor;
    nc->refs = refs;
    link_child(parent, nc);
    return PTR_FROM_CHUNK(nc);
}

void *talloc_size(const void *ctx, size_t size) {
    return talloc(ctx, size);
}

size_t talloc_get_size(const void *ptr) {
    if (!ptr)
        return 0;
    return CHUNK_FROM_PTR(ptr)->size;
}

void talloc_free(void *ptr) {
    if (!ptr)
        return;
    talloc_chunk *c = CHUNK_FROM_PTR(ptr);
    c->refs--;
    if (c->refs <= 0)
        real_free(c);
}

void talloc_free_children(void *ptr) {
    if (!ptr)
        return;
    talloc_chunk *c = CHUNK_FROM_PTR(ptr);
    while (c->children) {
        talloc_chunk *ch = c->children;
        unlink_child(ch);
        ch->refs--;
        if (ch->refs <= 0)
            real_free(ch);
    }
}

void *talloc_reference(const void *ctx, const void *ptr) {
    if (!ptr)
        return NULL;
    talloc_chunk *c = CHUNK_FROM_PTR(ptr);
    c->refs++;
    /* keep the first parent as owner */
    return (void *)ptr;
}

int talloc_unlink(const void *ctx, void *ptr) {
    (void)ctx;
    if (!ptr)
        return -1;
    talloc_chunk *c = CHUNK_FROM_PTR(ptr);
    c->refs--;
    if (c->refs <= 0)
        real_free(c);
    return 0;
}

int talloc_reference_count(const void *ptr) {
    if (!ptr)
        return 0;
    return CHUNK_FROM_PTR(ptr)->refs;
}

void *talloc_reparent(const void *old_parent, const void *new_parent, void *ptr) {
    (void)old_parent;
    if (!ptr)
        return NULL;
    talloc_chunk *c = CHUNK_FROM_PTR(ptr);
    talloc_chunk *np = new_parent ? CHUNK_FROM_PTR(new_parent) : root();
    unlink_child(c);
    link_child(np, c);
    return ptr;
}

void *talloc_new(const void *ctx) {
    return talloc(ctx, 0);
}

void talloc_set_destructor(void *ptr, int (*destructor)(void *)) {
    if (ptr)
        CHUNK_FROM_PTR(ptr)->destructor = destructor;
}

void talloc_set_name_const(void *ptr, const char *name) {
    if (ptr)
        CHUNK_FROM_PTR(ptr)->name = name;
}

const char *talloc_get_name(const void *ptr) {
    if (!ptr)
        return NULL;
    return CHUNK_FROM_PTR(ptr)->name;
}

const void *talloc_parent(const void *ptr) {
    if (!ptr)
        return NULL;
    talloc_chunk *c = CHUNK_FROM_PTR(ptr);
    return c->parent ? PTR_FROM_CHUNK(c->parent) : NULL;
}

void *talloc_check_name(const void *ptr, const char *name) {
    if (!ptr)
        return NULL;
    const char *n = talloc_get_name(ptr);
    if (n != NULL && strcmp(n, name) == 0)
        return (void *)ptr;
    return NULL;
}

void *_talloc_get_type_abort(const void *ptr, const char *name, const char *location) {
    void *p = talloc_check_name(ptr, name);
    if (p == NULL) {
        fprintf(stderr, "talloc type mismatch at %s: expected \"%s\", got \"%s\"\n",
                location, name, talloc_get_name(ptr));
        abort();
    }
    return p;
}

void *talloc_autofree_context(void) {
    return PTR_FROM_CHUNK(root());
}

char *talloc_strdup(const void *ctx, const char *str) {
    if (!str)
        return NULL;
    size_t len = strlen(str);
    char *p = talloc(ctx, len + 1);
    if (p)
        memcpy(p, str, len + 1);
    return p;
}

char *talloc_strndup(const void *ctx, const char *str, size_t n) {
    if (!str)
        return NULL;
    size_t len = strnlen(str, n);
    char *p = talloc(ctx, len + 1);
    if (p) {
        memcpy(p, str, len);
        p[len] = '\0';
    }
    return p;
}

char *talloc_vasprintf(const void *ctx, const char *fmt, va_list ap) {
    va_list ap2;
    va_copy(ap2, ap);
    int len = vsnprintf(NULL, 0, fmt, ap2);
    va_end(ap2);
    if (len < 0)
        return NULL;
    char *p = talloc(ctx, (size_t)len + 1);
    if (p)
        vsnprintf(p, (size_t)len + 1, fmt, ap);
    return p;
}

char *talloc_asprintf(const void *ctx, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    char *p = talloc_vasprintf(ctx, fmt, ap);
    va_end(ap);
    return p;
}

char *talloc_asprintf_append(char *s, const char *fmt, ...) {
    va_list ap;
    va_start(ap, fmt);
    size_t old_len = s ? strlen(s) : 0;
    va_list ap2;
    va_copy(ap2, ap);
    int add = vsnprintf(NULL, 0, fmt, ap2);
    va_end(ap2);
    if (add < 0) {
        va_end(ap);
        return s;
    }
    void *np = talloc_realloc(NULL, s, old_len + (size_t)add + 1);
    if (!np) {
        va_end(ap);
        return s;
    }
    vsnprintf((char *)np + old_len, (size_t)add + 1, fmt, ap);
    va_end(ap);
    return np;
}

static char *strdup_append_impl(char *s, const char *a, int buffer) {
    (void)buffer;
    if (!a)
        return s;
    size_t old_len = s ? strlen(s) : 0;
    size_t add_len = strlen(a);
    void *np = talloc_realloc(NULL, s, old_len + add_len + 1);
    if (!np)
        return s;
    memcpy((char *)np + old_len, a, add_len + 1);
    return np;
}

char *talloc_strdup_append(char *s, const char *a) {
    return strdup_append_impl(s, a, 0);
}

char *talloc_strdup_append_buffer(char *s, const char *a) {
    return strdup_append_impl(s, a, 1);
}

int talloc_report_depth_cb(const void *ptr, int depth, int max_depth,
                           void (*callback)(const void *ptr, int depth,
                                            int max_depth, void *user),
                           void *user) {
    if (depth > max_depth)
        return -1;
    const talloc_chunk *c = ptr ? CHUNK_FROM_PTR(ptr) : root();
    if (callback)
        callback(PTR_FROM_CHUNK(c), depth, max_depth, user);
    for (c = c->children; c; c = c->next)
        talloc_report_depth_cb(PTR_FROM_CHUNK(c), depth + 1, max_depth, callback, user);
    return 0;
}
