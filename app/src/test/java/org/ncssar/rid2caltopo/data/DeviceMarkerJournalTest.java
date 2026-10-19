package org.ncssar.rid2caltopo.data;
import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import static org.junit.Assert.*;
public class DeviceMarkerJournalTest {
    @Test public void survivesInterruptedPublishAndFailedDelete() throws Exception {
        File file=new File(Files.createTempDirectory("marker-journal").toFile(),"journal.json");
        DeviceMarkerJournal journal=new DeviceMarkerJournal(file);
        DeviceMarkerJournal.Entry entry=journal.arm("caltopo.com","OLDMAP","device-1");
        DeviceMarkerJournal restarted=new DeviceMarkerJournal(file);
        assertEquals("OLDMAP",restarted.entries().get(0).map);
        // No acknowledgement on timeout, offline, or authorization failure.
        assertEquals(1,new DeviceMarkerJournal(file).entries().size());
        restarted.acknowledge(entry);assertTrue(new DeviceMarkerJournal(file).entries().isEmpty());
    }
    @Test public void lateDeleteCannotForgetNewPublicationOrAnotherMap() throws Exception {
        File file=new File(Files.createTempDirectory("marker-journal").toFile(),"journal.json");
        DeviceMarkerJournal j=new DeviceMarkerJournal(file);
        DeviceMarkerJournal.Entry old=j.arm("caltopo.com","OLD","device");
        j.arm("caltopo.com","NEW","device");j.arm("caltopo.com","OLD","device");
        j.acknowledge(old);assertEquals(2,j.entries().size());
        assertFalse(new String(Files.readAllBytes(file.toPath())).contains("credential"));
    }
    @Test public void corruptJournalBlocksPublishingInsteadOfDiscardingOwnership() throws Exception {
        File file=File.createTempFile("marker-journal",".json");Files.write(file.toPath(),"broken".getBytes());
        assertThrows(Exception.class,()->new DeviceMarkerJournal(file).arm("host","MAP","id"));
        assertEquals("broken",new String(Files.readAllBytes(file.toPath())));
    }
}
