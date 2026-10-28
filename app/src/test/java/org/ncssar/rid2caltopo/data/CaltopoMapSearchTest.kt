package org.ncssar.rid2caltopo.data

import org.junit.Assert.*
import org.junit.Test

class CaltopoMapSearchTest {
    @Test fun globalSearchPreservesOrderAndDeduplicatesBookmarks() {
        val a=CaltopoNode.MapNode("a", "Search One",0)
        val b=CaltopoNode.MapNode("b", "Search Two",0)
        val root=listOf(CaltopoNode.Directory("f","Folder", mutableListOf(a,b)),a)
        assertEquals(listOf(a,b),searchCaltopoMaps(root,root,"search"))
        assertSame(root,searchCaltopoMaps(root,root,""))
    }
    @Test fun aLargeCatalogCanBeCancelledWhileFiltering() {
        val nodes=(0..20_000).map { CaltopoNode.MapNode("m$it", "map $it",0) }
        var checks=0
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            searchCaltopoMaps(nodes,nodes,"map") { if (++checks==3) throw java.util.concurrent.CancellationException() }
        }
        assertEquals(3,checks)
    }
    @Test fun composeIdentityDoesNotTraverseCatalogEqualityOrHashCode() {
        val nodes=object: AbstractList<CaltopoNode>() {
            override val size get()=error("UI identity walked catalog size")
            override fun get(index:Int):CaltopoNode=error("UI identity walked catalog rows")
            override fun hashCode():Int=error("UI identity hashed catalog rows")
        }
        assertEquals(CaltopoCatalogIdentity(nodes),CaltopoCatalogIdentity(nodes))
        assertEquals(System.identityHashCode(nodes),CaltopoCatalogIdentity(nodes).hashCode())
    }
}
