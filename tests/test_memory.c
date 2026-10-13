/* uc3: PS4 memory manager contracts (src/runtime_memory.c): a flexible mapping split by a
 * partial protection change keeps each half's own physical pages (releasing one half leaves the
 * other's contents and pages alone: a bug inherited from the God of War III port freed the
 * left half's pages twice), and a fixed map over mapped memory tells the GPU the old mapping
 * is gone before the new one arrives.
 * Windows: ninja -C out/gpu memory-test && out/gpu/memory-test.exe
 * Linux:   gcc -std=gnu11 -O2 -Wall -Wextra -Werror -Isrc tests/test_memory.c -lpthread -o out/memory-test */
#include "../src/runtime_memory.c"

/* runtime.c pieces runtime_memory.c uses. */
uintptr_t runtime_lookup(const RuntimeExport *table, size_t count, const char *nid) { (void)table; (void)count; (void)nid; return 0; }
static __thread int32_t errno_slot;
int32_t *runtime_errno(void) { return &errno_slot; }

static int failures;
#define CHECK(cond) do { if (!(cond)) { fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); ++failures; } } while (0)

/* The GPU's view of the mappings: every notification, in order. */
enum { EVENT_MAP, EVENT_UNMAP, EVENT_INVALIDATE };
typedef struct { int kind; uintptr_t address; uint64_t size; } Event;
static Event events[256];
static size_t event_count;
static void note(int kind, uintptr_t address, uint64_t size) { if (event_count < 256) events[event_count++] = (Event){kind, address, size}; }
static void on_map(uintptr_t address, uint64_t size) { note(EVENT_MAP, address, size); }
static void on_unmap(uintptr_t address, uint64_t size) { note(EVENT_UNMAP, address, size); }
static void on_invalidate(uintptr_t address, uint64_t size) { note(EVENT_INVALIDATE, address, size); }

static const Vma *vma_at(uintptr_t address) {
    size_t i = vma_index(address);
    return i < vma_count && vmas[i].start <= address ? &vmas[i] : NULL;
}

/* A flexible mapping split by mprotect: each half keeps its own physical pages. */
static void flexible_split_keeps_each_half(void) {
    void *p = NULL;
    CHECK(map_flexible(&p, 4 * PAGE, 3, 0) == 0 && p);
    unsigned char *m = p;
    for (unsigned i = 0; i < 4; ++i) memset(m + i * PAGE, 0x11 * (i + 1), PAGE);
    CHECK(kernel_mprotect(m + 2 * PAGE, 2 * PAGE, 1) == 0); /* read-only right half: the VMA splits */
    const Vma *left = vma_at((uintptr_t)m), *right = vma_at((uintptr_t)m + 2 * PAGE);
    CHECK(left && right && left != right);
    CHECK(left->end == (uintptr_t)m + 2 * PAGE && right->start == (uintptr_t)m + 2 * PAGE);
    CHECK(right->phys == left->phys + 2 * PAGE); /* was the left half's offset */
    CHECK(release_flexible(m + 2 * PAGE, 2 * PAGE) == 0);
    /* The left half is intact: releasing the right half freed (and zeroed) its own pages. */
    CHECK(m[0] == 0x11 && m[PAGE - 1] == 0x11 && m[PAGE] == 0x22 && m[2 * PAGE - 1] == 0x22);
    /* A new flexible mapping gets other pages, never the left half's. */
    void *q = NULL;
    CHECK(map_flexible(&q, 2 * PAGE, 3, 0) == 0 && q);
    memset(q, 0xEE, 2 * PAGE);
    CHECK(m[0] == 0x11 && m[PAGE] == 0x22);
    CHECK(release_flexible(q, 2 * PAGE) == 0);
    CHECK(release_flexible(m, 2 * PAGE) == 0);
}

/* A fixed map over mapped memory: the GPU hears the old mapping is gone, then the new one. */
static void fixed_map_replaces_for_the_gpu(void) {
    int64_t phys = -1;
    CHECK(direct_allocate(0, (int64_t)POOL_SIZE, 2 * PAGE, PAGE, 0, &phys) == 0 && phys >= 0);
    void *p = NULL;
    CHECK(direct_map(&p, 2 * PAGE, 3, 0, phys, 0) == 0 && p);
    memset(p, 0x5A, 2 * PAGE);
    const size_t before = event_count, replaced = fixed_replaced;
    void *again = p;
    CHECK(direct_map(&again, 2 * PAGE, 3, MAP_FIXED_FLAG, phys, 0) == 0 && again == p);
    CHECK(fixed_replaced == replaced + 1);
    CHECK(event_count == before + 2);
    CHECK(events[before].kind == EVENT_UNMAP && events[before].address == (uintptr_t)p && events[before].size == 2 * PAGE);
    CHECK(events[before + 1].kind == EVENT_MAP && events[before + 1].address == (uintptr_t)p && events[before + 1].size == 2 * PAGE);
    CHECK(((unsigned char *)p)[0] == 0x5A); /* the same physical memory */
    CHECK(direct_unmap(p, 2 * PAGE) == 0);
    CHECK(direct_release((uint64_t)phys, 2 * PAGE) == 0);
}

/* The backing view of a region: the same bytes, through the second mapping of the pool. */
static void region_backing_reads_the_same_bytes(void) {
    void *p = NULL;
    CHECK(map_flexible(&p, PAGE, 3, 0) == 0 && p);
    memset(p, 0x3C, PAGE);
    uintptr_t start = 0, end = 0; int mapped = 0; const unsigned char *backing = NULL;
    CHECK(runtime_memory_region_backing((uintptr_t)p + 100, &start, &end, &mapped, &backing));
    CHECK(mapped && start == (uintptr_t)p && end == (uintptr_t)p + PAGE && backing && backing != (unsigned char *)p + 100);
    CHECK(backing && backing[0] == 0x3C && backing[PAGE - 101] == 0x3C);
    CHECK(release_flexible(p, PAGE) == 0);
    CHECK(runtime_memory_region_backing((uintptr_t)p + 100, &start, &end, &mapped, &backing) ? !mapped && !backing : 1);
}

int main(void) {
    runtime_memory_set_gpu_hooks(on_map, on_unmap, on_invalidate);
    flexible_split_keeps_each_half();
    fixed_map_replaces_for_the_gpu();
    region_backing_reads_the_same_bytes();
    if (failures) { fprintf(stderr, "%d failure(s)\n", failures); return 1; }
    printf("memory-test: OK (%zu GPU notifications)\n", event_count);
    return 0;
}
