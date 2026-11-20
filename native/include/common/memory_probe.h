#pragma once

#include <fcntl.h>
#include <unistd.h>

#include <cerrno>
#include <cstddef>
#include <cstdint>

namespace vector::native {

/**
 * @brief Reports whether every page spanning [addr, addr + len) can be read.
 *
 * For the readers that work out an address instead of being handed one. write() copies from its
 * buffer inside the kernel and answers EFAULT where a plain read would raise SIGSEGV: for an
 * unmapped range, and equally for a mapped one that is not readable, such as a guard page or a
 * PROT_NONE reservation. msync, which this replaced, fails only for the first and calls the
 * second mapped. Protections are per page, so one byte from each page answers for the range; it
 * goes into a pipe made for the purpose and is drained again at once.
 *
 * It says nothing about writability: a caller that writes through the address still relies on its
 * own evidence that the memory is the writable structure it expects.
 */
inline bool IsReadable(uintptr_t addr, size_t len) {
    static const size_t page = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    if (page == 0 || len == 0 || addr + len < addr) return false;

    int fds[2];
    if (pipe2(fds, O_CLOEXEC) != 0) return false;
    bool readable = true;
    for (uintptr_t p = addr & ~(page - 1); p < addr + len; p += page) {
        const uintptr_t probe = p < addr ? addr : p;
        ssize_t n;
        do {
            n = write(fds[1], reinterpret_cast<const void *>(probe), 1);
        } while (n < 0 && errno == EINTR);
        if (n != 1) {
            readable = false;
            break;
        }
        char sink;
        do {
            n = read(fds[0], &sink, 1);
        } while (n < 0 && errno == EINTR);
    }
    close(fds[0]);
    close(fds[1]);
    return readable;
}

}  // namespace vector::native
