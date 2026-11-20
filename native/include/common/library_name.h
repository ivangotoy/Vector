#pragma once

#include <string_view>

namespace vector::native {

/**
 * @brief Whether the library opened as @p path is the one a module registered as @p name.
 *
 * The whole file name has to match: @p path is either @p name itself or ends in '/' followed by
 * it. A plain suffix match also took "libxfoo.so" for a module's "libfoo.so" and called that
 * module's native_init on a library that is not its own.
 */
inline bool IsModuleLibrary(std::string_view path, std::string_view name) {
    if (name.empty() || path.size() < name.size()) return false;
    if (path.compare(path.size() - name.size(), std::string_view::npos, name) != 0) return false;
    return path.size() == name.size() || path[path.size() - name.size() - 1] == '/';
}

}  // namespace vector::native
