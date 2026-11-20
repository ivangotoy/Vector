package org.matrix.vector.daemon.ipc

import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import io.github.libxposed.service.IXposedService
import java.io.Serializable
import java.util.concurrent.ConcurrentHashMap
import org.matrix.vector.ipc.IModuleService
import org.matrix.vector.ipc.IRemotePreferenceCallback
import org.matrix.vector.daemon.data.ConfigCache
import org.matrix.vector.daemon.data.FileSystem
import org.matrix.vector.daemon.data.PreferenceStore
import org.matrix.vector.daemon.system.PER_USER_RANGE

private const val TAG = "VectorInjectedModuleService"

/**
 * A module's service as an **injected process** sees it — this project's `IModuleService`.
 *
 * The counterpart to [ModuleAppService], and see `IModuleService.aidl` for why the two differ: this
 * side may only read the module's remote files, because the process holding it runs as the app it
 * was injected into rather than as the module.
 */
class InjectedModuleService(private val packageName: String) : IModuleService.Stub() {

  // Tracks active RemotePreferenceCallbacks linked by config group. Preferences are stored per
  // Android user, so a registration is only interested in updates made by its own user.
  // [pid] and [seq] bound what one process can hold: see [MAX_SUBSCRIBERS_PER_PROCESS].
  private class Subscriber(
      val userId: Int,
      val callback: IRemotePreferenceCallback,
      val pid: Int,
      val seq: Long,
  ) {
    var recipient: IBinder.DeathRecipient? = null
  }

  private val nextSeq = java.util.concurrent.atomic.AtomicLong()

  private val callbacks = ConcurrentHashMap<String, MutableSet<Subscriber>>()

  override fun getFrameworkProperties(): Long {
    var prop = IXposedService.PROP_CAP_SYSTEM or IXposedService.PROP_CAP_REMOTE
    if (ConfigCache.state.isDexObfuscateEnabled) {
      prop = prop or IXposedService.PROP_RT_API_PROTECTION
    }
    return prop
  }

  override fun requestRemotePreferences(
      group: String,
      callback: IRemotePreferenceCallback?
  ): Bundle {
    val bundle = Bundle()
    val userId = Binder.getCallingUid() / PER_USER_RANGE
    bundle.putSerializable(
        "map", PreferenceStore.getModulePrefs(packageName, userId, group) as Serializable)

    if (callback != null) {
      val groupCallbacks = callbacks.getOrPut(group) { ConcurrentHashMap.newKeySet() }
      val subscriber =
          Subscriber(userId, callback, Binder.getCallingPid(), nextSeq.getAndIncrement())
      // One process may hold at most MAX_SUBSCRIBERS_PER_PROCESS per group: each hot reload
      // registers again without an unregister call, so the oldest of that process is dropped
      // instead of refusing the newest, which is the one in use.
      synchronized(groupCallbacks) {
        val mine = groupCallbacks.filter { it.pid == subscriber.pid }
        val evict = oldestBeyondCap(mine.map { it.seq }, MAX_SUBSCRIBERS_PER_PROCESS - 1).toSet()
        mine
            .filter { it.seq in evict }
            .forEach { old ->
              groupCallbacks.remove(old)
              old.recipient?.let { r -> runCatching { old.callback.asBinder().unlinkToDeath(r, 0) } }
            }
        // Linked under the lock, so a later eviction always finds the recipient to unlink.
        val recipient = IBinder.DeathRecipient { groupCallbacks.remove(subscriber) }
        subscriber.recipient = recipient
        groupCallbacks.add(subscriber)
        runCatching { callback.asBinder().linkToDeath(recipient, 0) }
            .onFailure { Log.w(TAG, "requestRemotePreferences linkToDeath failed", it) }
      }
    }
    return bundle
  }

  override fun openRemoteFile(path: String): ParcelFileDescriptor? {
    // XposedInterface#openRemoteFile documents FileNotFoundException for a missing *or* forbidden
    // path. Returning null lets VectorContext raise exactly that; throwing here surfaced a
    // RemoteException for a missing file and an IllegalArgumentException for a rejected path.
    val userId = Binder.getCallingUid() / PER_USER_RANGE
    return runCatching {
          FileSystem.ensureModuleFilePath(path)
          val dir = FileSystem.resolveModuleDir(packageName, "files", userId, -1)
          ParcelFileDescriptor.open(dir.resolve(path).toFile(), ParcelFileDescriptor.MODE_READ_ONLY)
        }
        .onFailure { Log.w(TAG, "Cannot open remote file $path for $packageName: ${it.message}") }
        .getOrNull()
  }

  override fun getRemoteFileNames(): Array<String> {
    val userId = Binder.getCallingUid() / PER_USER_RANGE
    return runCatching {
          val dir = FileSystem.resolveModuleDir(packageName, "files", userId, -1)
          dir.toFile().list() ?: emptyArray()
        }
        .getOrElse { throw RemoteException(it.message) }
  }

  // Called by ModuleAppService when the module app has changed the group for one Android user.
  fun onUpdateRemotePreferences(group: String, userId: Int, diff: Bundle) {
    val groupCallbacks = callbacks[group] ?: return
    for (subscriber in groupCallbacks) {
      if (subscriber.userId != userId) continue
      runCatching { subscriber.callback.onRemotePreferencesChanged(diff) }
          .onFailure { groupCallbacks.remove(subscriber) }
    }
  }
}

/** Subscribers one process may hold per preference group before its oldest are dropped. */
internal const val MAX_SUBSCRIBERS_PER_PROCESS = 16

/** The [keep] newest of [seqs] stay; the rest, oldest first, are returned. */
internal fun oldestBeyondCap(seqs: List<Long>, keep: Int): List<Long> {
  val sorted = seqs.sorted()
  return if (sorted.size <= keep) emptyList() else sorted.take(sorted.size - keep)
}
