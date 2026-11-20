package org.matrix.vector.daemon.ipc

/**
 * Whether [name] can be a scope target as a module asks for it: a package name, or "system" for the
 * framework. The names go into the scope prompt's notification as the path of its action URI, and
 * the receiver reads the package list and the action back out of that URI, so a character a package
 * name cannot hold (`?`, `#`, `/`, `,`, `%` ...) must never reach it.
 */
internal fun isScopeTargetName(name: String): Boolean = SCOPE_TARGET.matches(name)

private val SCOPE_TARGET = Regex("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)*")
