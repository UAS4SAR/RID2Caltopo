import Foundation
import R2CCore

/// Bounded MQTT 3.1.1 session, matching Android's registration/telemetry-only broker.
final class PilotMQTT {
    private let username:String,password:String
    private var connected=false,buffer=Data(),subscriptions=Set<String>()
    private var qos2:[Int:(String,Data)]=[:]
    init(username:String,password:String){self.username=username;self.password=password}
    func consume(_ bytes:Data,received:(String,Data)throws->[(String,Data)])throws->[Data] {
        buffer.append(bytes);guard buffer.count<=512*1024 else{throw MappingMission.Failure("MQTT buffer too large.")};var replies:[Data]=[]
        while buffer.count>=2 {
            let first=buffer[0];var size=0,multiplier=1,index=1,complete=false
            while index<buffer.count && index<=4{let value=buffer[index];size+=Int(value&127)*multiplier;index+=1;if value&128==0{complete=true;break};multiplier*=128}
            guard size<=256*1024,index<=5 else{throw MappingMission.Failure("MQTT packet too large.")};if !complete{guard index<5 else{throw MappingMission.Failure("Invalid MQTT length.")};break};if buffer.count<index+size{break}
            var packet=PilotPacket(data:Data(buffer[index..<index+size]));buffer=Data(buffer.dropFirst(index+size))
            if !connected {
                guard first==0x10,try packet.string()=="MQTT",try packet.byte()==4 else{throw MappingMission.Failure("MQTT 3.1.1 required.")}
                let flags=try packet.byte();guard flags&1==0 else{throw MappingMission.Failure("Invalid MQTT flags.")};_ = try packet.number();let client=try packet.string();guard (1...128).contains(client.count) else{throw MappingMission.Failure("Invalid client ID.")}
                if flags&4 != 0{_ = try packet.string();_ = try packet.string()}
                let user=try flags&128 != 0 ? packet.string():"",pass=try flags&64 != 0 ? packet.string():""
                guard user==username,pass==password,packet.remaining==0 else{throw MappingMission.Failure("MQTT credentials rejected.")};connected=true;replies.append(Self.frame(0x20,Data([0,0])));continue
            }
            func dispatch(_ topic:String,_ payload:Data)throws {for (target,data) in try received(topic,payload) where subscriptions.contains(where:{Self.matches($0,target)}){replies.append(Self.frame(0x30,Self.text(target)+data))}}
            switch first>>4 {
            case 3:
                let topic=try packet.string(),qos=Int((first>>1)&3);guard qos<=2,Self.allowed(topic,filter:false) else{throw MappingMission.Failure("Unsupported MQTT topic.")};let id=try qos>0 ? packet.number():0,payload=packet.rest()
                if qos==2{guard qos2.count<64 || qos2[id] != nil else{throw MappingMission.Failure("Too many MQTT acknowledgements.")};qos2[id]=(topic,payload);replies.append(Self.frame(0x50,Self.short(id)))}else{if qos==1{replies.append(Self.frame(0x40,Self.short(id)))};try dispatch(topic,payload)}
            case 6:guard first==0x62 else{throw MappingMission.Failure("Invalid MQTT release.")};let id=try packet.number();if let value=qos2.removeValue(forKey:id){try dispatch(value.0,value.1)};replies.append(Self.frame(0x70,Self.short(id)))
            case 8:
                guard first==0x82 else{throw MappingMission.Failure("Invalid subscription.")};let id=try packet.number();var granted=Data()
                while packet.remaining>0{let topic=try packet.string(),qos=try packet.byte();if Self.allowed(topic,filter:true) && qos<=2 && subscriptions.count<128{subscriptions.insert(topic);granted.append(qos)}else{granted.append(128)}};replies.append(Self.frame(0x90,Self.short(id)+granted))
            case 10:guard first==0xa2 else{throw MappingMission.Failure("Invalid unsubscribe.")};let id=try packet.number();while packet.remaining>0{subscriptions.remove(try packet.string())};replies.append(Self.frame(0xb0,Self.short(id)))
            case 12:guard size==0 else{throw MappingMission.Failure("Invalid MQTT ping.")};replies.append(Self.frame(0xd0,Data()))
            case 14:throw MappingMission.Failure("Controller disconnected.")
            default:throw MappingMission.Failure("Unsupported MQTT packet.")
            }
        };return replies
    }
    static func short(_ value:Int)->Data{Data([UInt8((value>>8)&255),UInt8(value&255)])}
    static func text(_ value:String)->Data{short(value.utf8.count)+Data(value.utf8)}
    static func frame(_ type:UInt8,_ data:Data)->Data{var result=Data([type]),length=data.count;repeat{var byte=UInt8(length%128);length/=128;if length>0{byte|=128};result.append(byte)}while length>0;result.append(data);return result}
    static func allowed(_ topic:String,filter:Bool)->Bool{
        let p=topic.split(separator:"/",omittingEmptySubsequences:false).map(String.init);guard (3...5).contains(p.count),["sys","thing"].contains(p[0]),p[1]=="product" else{return false}
        func identifier(_ s:String)->Bool{(1...128).contains(s.count) && s.unicodeScalars.allSatisfy{CharacterSet(charactersIn:"abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-").contains($0)}}
        if filter{return p.dropFirst(2).allSatisfy{$0=="+" || $0=="#" || identifier($0)} && (!p.contains("#") || p.last=="#")}
        return p.count==4 && identifier(p[2]) && ["status","state","osd","events","requests"].contains(p[3])
    }
    static func matches(_ filter:String,_ topic:String)->Bool{let a=filter.split(separator:"/"),b=topic.split(separator:"/");for (i,p) in a.enumerated(){if p=="#"{return true};if i>=b.count || (p != "+" && p != b[i]){return false}};return a.count==b.count}
}
private struct PilotPacket {
    let data:Data;var offset=0
    var remaining:Int{data.count-offset}
    mutating func byte()throws->UInt8{guard remaining>0 else{throw MappingMission.Failure("Truncated MQTT packet.")};defer{offset+=1};return data[offset]}
    mutating func number()throws->Int{Int(try byte())*256+Int(try byte())}
    mutating func string()throws->String{let count=try number();guard count<=remaining,let value=String(data:data[offset..<offset+count],encoding:.utf8) else{throw MappingMission.Failure("Invalid MQTT string.")};offset+=count;return value}
    mutating func rest()->Data{defer{offset=data.count};return Data(data.dropFirst(offset))}
}
