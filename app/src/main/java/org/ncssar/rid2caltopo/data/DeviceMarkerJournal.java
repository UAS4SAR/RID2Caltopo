package org.ncssar.rid2caltopo.data;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Write-ahead ownership records; never stores authentication credentials. */
public final class DeviceMarkerJournal {
    public static final class Entry {
        public final String domain, map, marker, revision;
        Entry(String domain,String map,String marker,String revision) {this.domain=domain;this.map=map;this.marker=marker;this.revision=revision;}
        public String key(){return domain+"/"+map+"/"+marker;}
    }
    private final File file;
    public DeviceMarkerJournal(File file){this.file=file;}
    public synchronized List<Entry> entries() throws Exception {
        List<Entry> result=new ArrayList<>();
        if(!file.exists())return result;
        JSONArray rows=new JSONArray(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
        for(int i=0;i<rows.length();i++){JSONObject r=rows.getJSONObject(i);result.add(new Entry(r.getString("domain"),r.getString("map"),r.getString("marker"),r.getString("revision")));}
        return result;
    }
    public synchronized Entry arm(String domain,String map,String marker) throws Exception {
        if(domain.isEmpty()||!map.matches("[A-Za-z0-9_-]+")||!marker.matches("[A-Za-z0-9_-]+"))throw new IllegalArgumentException("Invalid marker ownership record");
        Entry next=new Entry(domain,map,marker,UUID.randomUUID().toString());
        List<Entry> rows=entries();rows.removeIf(e->e.key().equals(next.key()));rows.add(next);write(rows);return next;
    }
    public synchronized void acknowledge(Entry entry) throws Exception {
        List<Entry> rows=entries();rows.removeIf(e->e.key().equals(entry.key())&&e.revision.equals(entry.revision));write(rows);
    }
    private void write(List<Entry> rows)throws Exception {
        JSONArray array=new JSONArray();
        for(Entry e:rows)array.put(new JSONObject().put("domain",e.domain).put("map",e.map).put("marker",e.marker).put("revision",e.revision));
        file.getParentFile().mkdirs();File temp=new File(file.getPath()+".tmp");
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(temp)){out.write(array.toString().getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        Files.move(temp.toPath(),file.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
}
