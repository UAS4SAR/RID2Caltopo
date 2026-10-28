package org.ncssar.rid2caltopo.data

import java.util.ArrayDeque
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class OrderedBooleanSettingsTest {
    @Test fun blockedDurableWriteDoesNotBlockUIReadsOrNewChoices() {
        val worker = Executors.newSingleThreadExecutor(); val ui = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val durable = ConcurrentHashMap<String, Boolean>()
        val settings = OrderedBooleanSettings(mapOf("saved" to true), { worker.execute(it) }, { key, value ->
            entered.countDown(); release.await(); durable[key] = value; true
        })
        try {
            settings.set("a", true); assertTrue(entered.await(2, TimeUnit.SECONDS))
            ui.submit {
                assertTrue(settings.get("saved").enabled)
                assertTrue(settings.get("a").pending)
                settings.set("a", false); settings.set("b", true)
                assertFalse(settings.get("a").enabled)
            }.get(500, TimeUnit.MILLISECONDS)
            release.countDown(); worker.submit {}.get(2, TimeUnit.SECONDS)
            assertEquals(false, durable["a"]); assertEquals(true, durable["b"])
            assertFalse(settings.get("a").pending)
        } finally { release.countDown(); worker.shutdownNow(); ui.shutdownNow() }
    }
    @Test fun rapidChoicesCoalesceByAircraftAndSurviveReaderRecreation() {
        val tasks = ArrayDeque<() -> Unit>(); val durable = mutableMapOf("saved" to true)
        val writes = mutableListOf<Pair<String, Boolean>>()
        val settings = OrderedBooleanSettings(durable.toMap(), { tasks.add(it) }, { key, value ->
            writes.add(key to value); durable[key] = value; true
        })
        repeat(1000) { settings.set("a", it % 2 == 0); settings.set("b", it % 2 != 0) }
        assertEquals(1, tasks.size)
        assertTrue(settings.get("saved").enabled)
        tasks.removeFirst()()
        assertEquals(listOf("a" to false, "b" to true), writes)
        val reopened = OrderedBooleanSettings(durable, { tasks.add(it) }, { _, _ -> true })
        assertFalse(reopened.get("a").enabled); assertTrue(reopened.get("b").enabled)
        assertTrue(reopened.get("saved").enabled)
    }
    @Test fun oldFailureCannotClearNewPendingChoice() {
        val worker = Executors.newSingleThreadExecutor()
        val first = CountDownLatch(1); val second = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1); val releaseSecond = CountDownLatch(1)
        val calls = AtomicInteger(); val failures = AtomicInteger()
        val settings = OrderedBooleanSettings(emptyMap(), { worker.execute(it) }, { _, _ ->
            if (calls.incrementAndGet() == 1) { first.countDown(); releaseFirst.await(); false }
            else { second.countDown(); releaseSecond.await(); true }
        }, { _, _ -> failures.incrementAndGet() })
        try {
            settings.set("a", true); assertTrue(first.await(2, TimeUnit.SECONDS))
            settings.set("a", false); releaseFirst.countDown(); assertTrue(second.await(2, TimeUnit.SECONDS))
            val pending = settings.get("a")
            assertFalse(pending.enabled); assertTrue(pending.pending); assertFalse(pending.failed)
            releaseSecond.countDown(); worker.submit {}.get(2, TimeUnit.SECONDS)
            assertFalse(settings.get("a").pending); assertFalse(settings.get("a").failed)
            assertEquals(1, failures.get())
        } finally { releaseFirst.countDown(); releaseSecond.countDown(); worker.shutdownNow() }
    }
    @Test fun failureIsVisibleAndRetryCanSaveWithoutLosingOtherAircraft() {
        val tasks = ArrayDeque<() -> Unit>(); var fail = true; val durable = mutableMapOf<String, Boolean>()
        val settings = OrderedBooleanSettings(emptyMap(), { tasks.add(it) }, { key, value ->
            if (key == "a" && fail) throw java.io.IOException("Unavailable")
            durable[key] = value; true
        })
        settings.set("a", true); settings.set("b", true); tasks.removeFirst()()
        assertTrue(settings.get("a").failed); assertTrue(settings.get("a").enabled)
        assertEquals(true, durable["b"]); assertFalse(settings.get("b").failed)
        fail = false; settings.set("a", true); tasks.removeFirst()()
        assertEquals(true, durable["a"]); assertFalse(settings.get("a").failed)
        assertFalse(settings.get("a").pending)
    }
}
