import Foundation
import R2CCore

/// Streaming ZIP64: large missions never assemble a multi-gigabyte archive in RAM.
public enum MappingShareArchive {
    private struct Central {let name:Data,crc:UInt32,size,offset:UInt64}
    public static func write(root:URL,layerID:String,manifest:Data,destination:URL,progress:(String)throws->Void)throws{
        let files=try MappingReconstruction.regularFiles(root).filter{$0 != destination && $0.lastPathComponent != "share.partial" && $0.lastPathComponent != "share.zip"}
        FileManager.default.createFile(atPath:destination.path,contents:nil)
        let output=try FileHandle(forWritingTo:destination);defer{try? output.close()};var offset:UInt64=0,central:[Central]=[]
        func write(_ data:Data)throws{try output.write(contentsOf:data);offset+=UInt64(data.count)}
        func add(name:String,size:UInt64,reader:(@escaping(Data)throws->Void)throws->Void)throws{
            let name=Data(name.utf8),start=offset
            guard name.count<65536 else{throw MappingMission.Failure("Archive path is too long.")}
            var header=Data();header.u32(0x04034b50);header.u16(45);header.u16(0x0808);header.u16(0);header.u16(0);header.u16(0x21);header.u32(0);header.u32(.max);header.u32(.max);header.u16(UInt16(name.count));header.u16(20);header.append(name);header.u16(1);header.u16(16);header.u64(size);header.u64(size);try write(header)
            var crc:UInt32 = .max,done:UInt64=0
            try reader{block in
                for byte in block{crc=(crc>>8)^table[Int((crc^UInt32(byte))&255)]};try write(block);done+=UInt64(block.count)
            }
            guard done==size else{throw MappingMission.Failure("A result changed while packaging.")};crc ^= .max
            var descriptor=Data();descriptor.u32(0x08074b50);descriptor.u32(crc);descriptor.u64(size);descriptor.u64(size);try write(descriptor)
            central.append(Central(name:name,crc:crc,size:size,offset:start))
        }
        for (i,file) in files.enumerated(){let size=UInt64(try file.resourceValues(forKeys:[.fileSizeKey]).fileSize ?? 0)
            let name="mapping-layers/\(layerID)/"+(try MappingFileIO.relative(file,to:root))
            try add(name:name,size:size){emit in
                let input=try FileHandle(forReadingFrom:file);defer{try? input.close()};var done:Int64=0
                while let block=try input.read(upToCount:256*1024),!block.isEmpty{try emit(block);done+=Int64(block.count);try progress("Packaging file \(i+1)/\(files.count) · \(MappingProgressSnapshot.bytes(done,total:Int64(size)))")}
            }
        }
        try add(name:"manifest.json",size:UInt64(manifest.count)){try $0(manifest)}
        let centralOffset=offset
        for entry in central{var record=Data();record.u32(0x02014b50);record.u16(45);record.u16(45);record.u16(0x0808);record.u16(0);record.u16(0);record.u16(0x21);record.u32(entry.crc);record.u32(.max);record.u32(.max);record.u16(UInt16(entry.name.count));record.u16(28);record.u16(0);record.u16(0);record.u16(0);record.u32(0);record.u32(.max);record.append(entry.name);record.u16(1);record.u16(24);record.u64(entry.size);record.u64(entry.size);record.u64(entry.offset);try write(record)}
        let centralSize=offset-centralOffset,zip64Offset=offset
        var end=Data();end.u32(0x06064b50);end.u64(44);end.u16(45);end.u16(45);end.u32(0);end.u32(0);end.u64(UInt64(central.count));end.u64(UInt64(central.count));end.u64(centralSize);end.u64(centralOffset)
        end.u32(0x07064b50);end.u32(0);end.u64(zip64Offset);end.u32(1)
        end.u32(0x06054b50);end.u16(0);end.u16(0);end.u16(.max);end.u16(.max);end.u32(.max);end.u32(.max);end.u16(0);try write(end);try output.synchronize()
    }
    private static let table:[UInt32]=(0..<256).map{index in var value=UInt32(index);for _ in 0..<8{value=(value>>1)^((value&1)==1 ? 0xedb88320:0)};return value}
}
private extension Data{
    mutating func u16(_ value:UInt16){var v=value.littleEndian;Swift.withUnsafeBytes(of:&v){append(contentsOf:$0)}}
    mutating func u32(_ value:UInt32){var v=value.littleEndian;Swift.withUnsafeBytes(of:&v){append(contentsOf:$0)}}
    mutating func u64(_ value:UInt64){var v=value.littleEndian;Swift.withUnsafeBytes(of:&v){append(contentsOf:$0)}}
}
