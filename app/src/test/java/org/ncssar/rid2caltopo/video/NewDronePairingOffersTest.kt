package org.ncssar.rid2caltopo.video

import org.junit.Assert.assertEquals
import org.junit.Test

class NewDronePairingOffersTest {
    private val rid = StreamTelemetryState("RID-1", "RID-1")

    @Test fun ridArrivingAfterLiveStreamOffersSetup() {
        val offers = NewDronePairingOffers()
        val shown = mutableListOf<String>()
        val present = { remote: String, stream: String -> shown.add("$remote:$stream"); true }
        offers.reconcile(listOf("stream"), listOf("stream"), emptyList(), emptySet(), present)
        assertEquals(emptyList<String>(), shown)
        offers.reconcile(listOf("stream"), listOf("stream"), listOf(rid), setOf(rid.remoteId), present)
        assertEquals(listOf("RID-1:stream"), shown)
    }

    @Test fun streamArrivingAfterRidOffersSetup() {
        val offers = NewDronePairingOffers()
        var count = 0
        val present = { _: String, _: String -> count++; true }
        offers.reconcile(emptyList(), emptyList(), listOf(rid), setOf(rid.remoteId), present)
        offers.reconcile(listOf("stream"), listOf("stream"), listOf(rid), setOf(rid.remoteId), present)
        assertEquals(1, count)
    }

    @Test fun canceledOfferDoesNotRepeatUntilPublisherEnds() {
        val offers = NewDronePairingOffers()
        var count = 0
        val present = { _: String, _: String -> count++; true }
        repeat(3) { offers.reconcile(listOf("stream"), listOf("stream"), listOf(rid), setOf(rid.remoteId), present) }
        assertEquals(1, count)
        offers.reconcile(emptyList(), emptyList(), listOf(rid), setOf(rid.remoteId), present)
        offers.reconcile(listOf("stream"), listOf("stream"), listOf(rid), setOf(rid.remoteId), present)
        assertEquals(2, count)
    }

    @Test fun busyDialogRetriesOnNextTelemetryUpdate() {
        val offers = NewDronePairingOffers()
        var accepted = false
        var count = 0
        val present = { _: String, _: String -> count++; accepted }
        offers.reconcile(listOf("stream"), listOf("stream"), listOf(rid), setOf(rid.remoteId), present)
        accepted = true
        offers.reconcile(listOf("stream"), listOf("stream"), listOf(rid), setOf(rid.remoteId), present)
        offers.reconcile(listOf("stream"), listOf("stream"), listOf(rid), setOf(rid.remoteId), present)
        assertEquals(2, count)
    }

    @Test fun ambiguousRidOrStreamDoesNotOffer() {
        val offers = NewDronePairingOffers()
        var count = 0
        val present = { _: String, _: String -> count++; true }
        offers.reconcile(listOf("stream"), listOf("stream"), listOf(rid, StreamTelemetryState("RID-2", "RID-2")), setOf(rid.remoteId), present)
        offers.reconcile(listOf("one", "two"), listOf("one", "two"), listOf(rid), setOf(rid.remoteId), present)
        assertEquals(0, count)
    }

    @Test fun knownPairedOrStaleDroneDoesNotOffer() {
        val offers = NewDronePairingOffers()
        var count = 0
        val present = { _: String, _: String -> count++; true }
        offers.reconcile(listOf("stream"), listOf("stream"), listOf(rid), emptySet(), present)
        offers.reconcile(listOf("stream"), emptyList(), listOf(rid), setOf(rid.remoteId), present)
        offers.reconcile(listOf("stream"), listOf("stream"), emptyList(), setOf(rid.remoteId), present)
        assertEquals(0, count)
    }
}
