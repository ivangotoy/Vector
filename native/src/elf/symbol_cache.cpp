#include "elf/symbol_cache.h"

#include <mutex>

#include "common/config.h"
#include "elf/elf_image.h"

namespace vector::native {

namespace {
// Each cached ElfImage gets its own unique_ptr and a mutex to guard its
// initialization.
std::unique_ptr<const ElfImage> g_art_image = nullptr;
std::mutex g_art_mutex;

std::unique_ptr<const ElfImage> g_binder_image = nullptr;
std::mutex g_binder_mutex;

std::unique_ptr<const ElfImage> g_linker_image = nullptr;
std::mutex g_linker_mutex;
}  // namespace

const ElfImage *ElfSymbolCache::GetArt() {
    // Always under the lock: the unique_ptr is not atomic, so an unlocked read races the
    // initialisation (and ClearCache's reset) in another thread.
    std::lock_guard<std::mutex> lock(g_art_mutex);
    if (!g_art_image) {
        g_art_image = std::make_unique<ElfImage>(kArtLibraryName);
        if (!g_art_image->IsValid()) {
            g_art_image.reset();  // Release if invalid.
        }
    }
    return g_art_image.get();
}

const ElfImage *ElfSymbolCache::GetLibBinder() {
    std::lock_guard<std::mutex> lock(g_binder_mutex);
    if (!g_binder_image) {
        g_binder_image = std::make_unique<ElfImage>(kBinderLibraryName);
        if (!g_binder_image->IsValid()) {
            g_binder_image.reset();
        }
    }
    return g_binder_image.get();
}

const ElfImage *ElfSymbolCache::GetLinker() {
    std::lock_guard<std::mutex> lock(g_linker_mutex);
    if (!g_linker_image) {
        g_linker_image = std::make_unique<ElfImage>(kLinkerPath);
        if (!g_linker_image->IsValid()) {
            g_linker_image.reset();
        }
    }
    return g_linker_image.get();
}

bool ElfSymbolCache::ClearCache(const ElfImage *image_to_clear) {
    if (!image_to_clear) {
        return false;
    }

    // This "lock, check, then reset" pattern must be atomic for each cache entry.
    // We check each cache one by one.

    // Check ART cache
    {
        std::lock_guard<std::mutex> lock(g_art_mutex);
        if (image_to_clear == g_art_image.get()) {
            g_art_image.reset();
            return true;  // Found and cleared, no need to check others.
        }
    }

    // Check Binder cache
    {
        std::lock_guard<std::mutex> lock(g_binder_mutex);
        if (image_to_clear == g_binder_image.get()) {
            g_binder_image.reset();
            return true;
        }
    }

    // Check Linker cache
    {
        std::lock_guard<std::mutex> lock(g_linker_mutex);
        if (image_to_clear == g_linker_image.get()) {
            g_linker_image.reset();
            return true;
        }
    }

    return false;
}

void ElfSymbolCache::ClearCache() {
    // Acquire all locks to ensure no other thread is currently initializing.
    std::lock_guard<std::mutex> art_lock(g_art_mutex);
    std::lock_guard<std::mutex> binder_lock(g_binder_mutex);
    std::lock_guard<std::mutex> linker_lock(g_linker_mutex);
    g_art_image.reset();
    g_binder_image.reset();
    g_linker_image.reset();
}

}  // namespace vector::native
