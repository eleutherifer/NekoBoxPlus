package io.nekohasekai.sagernet.database.preference

import androidx.preference.PreferenceDataStore
import io.nekohasekai.sagernet.app.AppGraph
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Typed, observable access to persistent application settings.
 *
 * Legacy preference delegates can continue using [RoomPreferenceDataStore] directly while new
 * presentation code depends on this interface and performs database-backed reads off the main
 * thread.
 */
interface SettingsRepository {
    suspend fun getBoolean(key: String, defaultValue: Boolean = false): Boolean
    suspend fun getFloat(key: String, defaultValue: Float = 0f): Float
    suspend fun getInt(key: String, defaultValue: Int = 0): Int
    suspend fun getLong(key: String, defaultValue: Long = 0L): Long
    suspend fun getString(key: String, defaultValue: String? = null): String?
    suspend fun getStringSet(key: String, defaultValue: Set<String> = emptySet()): Set<String>

    suspend fun putBoolean(key: String, value: Boolean)
    suspend fun putFloat(key: String, value: Float)
    suspend fun putInt(key: String, value: Int)
    suspend fun putLong(key: String, value: Long)
    suspend fun putString(key: String, value: String?)
    suspend fun putStringSet(key: String, value: Set<String>?)
    suspend fun remove(key: String)
    suspend fun reset()

    fun observeBoolean(key: String, defaultValue: Boolean = false): Flow<Boolean>
    fun observeFloat(key: String, defaultValue: Float = 0f): Flow<Float>
    fun observeInt(key: String, defaultValue: Int = 0): Flow<Int>
    fun observeLong(key: String, defaultValue: Long = 0L): Flow<Long>
    fun observeString(key: String, defaultValue: String? = null): Flow<String?>
    fun observeStringSet(key: String, defaultValue: Set<String> = emptySet()): Flow<Set<String>>
}

class RoomSettingsRepository(
    private val store: RoomPreferenceDataStore,
    private val ioDispatcher: () -> CoroutineDispatcher = { AppGraph.dispatchers.io },
) : SettingsRepository, OnPreferenceDataStoreChangeListener {
    private val revision = MutableStateFlow(0L)

    init {
        store.registerChangeListener(this)
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        invalidate()
    }

    override suspend fun getBoolean(key: String, defaultValue: Boolean) = read {
        store.getBoolean(key, defaultValue)
    }

    override suspend fun getFloat(key: String, defaultValue: Float) = read {
        store.getFloat(key, defaultValue)
    }

    override suspend fun getInt(key: String, defaultValue: Int) = read {
        store.getInt(key, defaultValue)
    }

    override suspend fun getLong(key: String, defaultValue: Long) = read {
        store.getLong(key, defaultValue)
    }

    override suspend fun getString(key: String, defaultValue: String?) = read {
        store.getString(key, defaultValue)
    }

    override suspend fun getStringSet(key: String, defaultValue: Set<String>) = read {
        store.getStringSet(key, defaultValue.toMutableSet()).orEmpty().toSet()
    }

    override suspend fun putBoolean(key: String, value: Boolean) = write {
        store.putBoolean(key, value)
    }

    override suspend fun putFloat(key: String, value: Float) = write {
        store.putFloat(key, value)
    }

    override suspend fun putInt(key: String, value: Int) = write {
        store.putInt(key, value)
    }

    override suspend fun putLong(key: String, value: Long) = write {
        store.putLong(key, value)
    }

    override suspend fun putString(key: String, value: String?) = write {
        store.putString(key, value)
    }

    override suspend fun putStringSet(key: String, value: Set<String>?) = write {
        store.putStringSet(key, value?.toMutableSet())
    }

    override suspend fun remove(key: String) = write {
        store.remove(key)
    }

    override suspend fun reset() = write {
        store.reset()
        invalidate()
    }

    override fun observeBoolean(key: String, defaultValue: Boolean) =
        observe { getBoolean(key, defaultValue) }

    override fun observeFloat(key: String, defaultValue: Float) =
        observe { getFloat(key, defaultValue) }

    override fun observeInt(key: String, defaultValue: Int) =
        observe { getInt(key, defaultValue) }

    override fun observeLong(key: String, defaultValue: Long) =
        observe { getLong(key, defaultValue) }

    override fun observeString(key: String, defaultValue: String?) =
        observe { getString(key, defaultValue) }

    override fun observeStringSet(key: String, defaultValue: Set<String>) =
        observe { getStringSet(key, defaultValue) }

    private suspend fun <T> read(block: () -> T): T = withContext(ioDispatcher()) { block() }

    private suspend fun write(block: () -> Unit) = withContext(ioDispatcher()) { block() }

    private fun <T> observe(read: suspend () -> T): Flow<T> = revision
        .map { read() }
        .distinctUntilChanged()

    private fun invalidate() {
        synchronized(revision) {
            revision.value++
        }
    }
}
