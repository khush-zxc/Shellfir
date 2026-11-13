package com.example.domain.concurrency

import com.example.data.database.ShellfriDatabase
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Collections
import java.util.WeakHashMap
import kotlin.coroutines.CoroutineContext

/**
 * Shared coroutine mutation coordinator serializing workspace read-compute-write operations
 * across [com.example.domain.repository.AppRepository] and [com.example.data.backup.BackupRestoreHelper].
 *
 * Locking policy:
 * - Single-level non-reentrant acquisition backed by [Mutex]. Re-entering the same coordinator
 *   on an active coroutine throws [IllegalStateException] immediately instead of self-deadlocking.
 * - Expensive external I/O (PackageManager IPC, icon decoding/preloading, JSON parsing) must run
 *   OUTSIDE the coordinator lock.
 * - Pure reactive Flow queries do not acquire the coordinator lock.
 * - Lock release is guaranteed on both thrown exceptions and coroutine cancellation.
 */
class WorkspaceMutationCoordinator {

    private val mutex = Mutex()

    private class ActiveLockElement(
        val coordinator: WorkspaceMutationCoordinator
    ) : CoroutineContext.Element {
        override val key: CoroutineContext.Key<*> = Key

        companion object Key : CoroutineContext.Key<ActiveLockElement>
    }

    val isLocked: Boolean
        get() = mutex.isLocked

    suspend fun <T> withMutationLock(block: suspend () -> T): T {
        val currentElement = currentCoroutineContext()[ActiveLockElement]
        if (currentElement?.coordinator === this) {
            throw IllegalStateException(
                "Re-entrant WorkspaceMutationCoordinator lock acquisition is prohibited to prevent deadlock"
            )
        }
        return mutex.withLock {
            withContext(ActiveLockElement(this)) {
                block()
            }
        }
    }

    companion object {
        private val databaseCoordinators =
            Collections.synchronizedMap(WeakHashMap<ShellfriDatabase, WorkspaceMutationCoordinator>())

        fun forDatabase(database: ShellfriDatabase?): WorkspaceMutationCoordinator {
            if (database == null) return WorkspaceMutationCoordinator()
            return synchronized(databaseCoordinators) {
                databaseCoordinators.getOrPut(database) { WorkspaceMutationCoordinator() }
            }
        }
    }
}
