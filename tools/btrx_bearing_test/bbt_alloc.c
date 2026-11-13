/* Counting allocator for the host tests (the library is compiled with
 * malloc/calloc/free mapped to these). */
#include <stdatomic.h>
#include <stdlib.h>

atomic_long bbt_alloc_count;

void *bbt_malloc(size_t n) { atomic_fetch_add(&bbt_alloc_count, 1); return malloc(n); }
void *bbt_calloc(size_t n, size_t m) { atomic_fetch_add(&bbt_alloc_count, 1); return calloc(n, m); }
void bbt_free(void *p) { free(p); }
