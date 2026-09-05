package io.nekohasekai.sagernet.database.preference

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test

class RoomSettingsRepositoryTest {
    @Test
    fun observesInitialAndChangedValue() = runBlocking {
        val store = RoomPreferenceDataStore(InMemoryKeyValueDao())
        val repository = RoomSettingsRepository(store) { Dispatchers.Unconfined }
        val values = async { repository.observeBoolean("enabled").take(2).toList() }

        yield()
        repository.putBoolean("enabled", true)

        assertEquals(listOf(false, true), values.await())
    }

    @Test
    fun resetRefreshesAllObservedKeys() = runBlocking {
        val store = RoomPreferenceDataStore(InMemoryKeyValueDao())
        val repository = RoomSettingsRepository(store) { Dispatchers.Unconfined }
        repository.putInt("interval", 15)
        val values = async { repository.observeInt("interval", 5).take(2).toList() }

        yield()
        repository.reset()

        assertEquals(listOf(15, 5), values.await())
    }

    private class InMemoryKeyValueDao : KeyValuePair.Dao {
        private val values = linkedMapOf<String, KeyValuePair>()

        override fun all() = values.values.toList()
        override fun get(key: String) = values[key]
        override fun put(value: KeyValuePair): Long {
            values[value.key] = value
            return 1L
        }

        override fun delete(key: String) = if (values.remove(key) == null) 0 else 1
        override fun reset(): Int = values.size.also { values.clear() }
        override fun insert(list: List<KeyValuePair>) {
            list.forEach { values[it.key] = it }
        }
    }
}
