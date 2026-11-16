import Foundation

/// One debug-log line per btrx frame, matching Android's BtrxFrameLog.kt and close to
/// r2c_sim's output. Bluetooth addresses are cut to their last 4 characters.
public enum BtrxFrameLog {
    public static func format(_ frame: BtrxProtocol.Frame) -> String {
        let head = String(format: "%@ seq=%d pod=%08x flags=%@", frame.typeName, frame.seq, frame.podId, BtrxProtocol.frameFlagNames(frame.flags))
        let body: String
        do { body = try describe(frame.type, frame.payload) } catch let e as BtrxProtocol.PayloadError {
            body = "decode-error=\(e.message) raw=\(BtrxProtocol.hex(frame.payload))"
        } catch { body = "decode-error" }
        return body.isEmpty ? head : "\(head)  \(body)"
    }

    public static func describe(_ type: Int, _ p: [UInt8]) throws -> String {
        typealias P = BtrxProtocol
        switch type {
        case P.DESK_PAIR:
            let d = try P.parseDeskPair(p)
            let src = P.deskPairSourceName(d.source)
            return "open=\(d.open) source=\(src) seconds=\(d.secondsLeft)"
        case P.CONFIRM_REQ:
            let c = try P.parseConfirmReq(p)
            return "version=\(c.version) action=\(P.confirmActionName(c.action)) token=\(c.token) timeout_s=\(c.timeoutS) state=\(P.confirmStateName(c.state))"
        case P.CONFIRM_RSP:
            let c = try P.parseConfirmRsp(p)
            return "version=\(c.version) token=\(c.token) answer=\(P.confirmAnswerName(c.answer))"
        case P.PAIR_OPEN:
            return p.isEmpty ? "seconds=default" : "seconds=\(p[0])"
        case P.PAIR_REQ:
            let r = try P.parsePairReq(p)
            return String(format: "pod_id=%08x dev_a=%08x dev_b=%08x build_a=0x%04x build_b=0x%04x imu_ok=%d role_a=%@ role_b=%@ nonce=%@",
                          r.podId, r.devA, r.devB, r.buildA, r.buildB, r.imuOk, P.roleName(r.roleA), P.roleName(r.roleB), P.hex(r.nonce))
        case P.PAIR_ACK:
            let a = try P.parsePairAck(p)
            return "a_ok=\(a.aOk) b_ok=\(a.bOk) flags=\(P.statusFlagNames(a.flags))"
        case P.ROLE_REPORT:
            let r = try P.parseRoleReport(p)
            return String(format: "role=%d(%@) source=%d confirmed=%d flags=%@ build_hash=0x%04x dev_id=%08x peer_id=%08x pod_id=%08x imu_whoami=0x%02x tx=%@",
                          r.role, P.roleName(r.role), r.source, r.confirmed, P.statusFlagNames(r.flags), r.buildHash, r.devId, r.peerId, r.podId,
                          r.imuWhoami, P.txName(r.txPolicy))
        case P.TIME_PING, P.TIME_PONG:
            let t = try P.parseTime(p)
            return "ping_seq=\(t.pingSeq) t1=\(t.t1) t2=\(t.t2) t3=\(t.t3)"
        case P.HEALTH:
            let h = try P.parseHealth(p)
            return "uptime_s=\(h.uptimeS) build_hash=\(String(format: "0x%04x", h.buildHash)) role=\(P.roleName(h.role)) source=\(h.source) confirmed=\(h.confirmed) flags=\(P.statusFlagNames(h.flags)) b_link_age_s=\(h.bLinkAgeS == 0xFFFF ? "never" : String(h.bLinkAgeS)) imu_ok=\(h.imuOk) die_temp_c=\(h.dieTempC) adv_port=\(h.advPerSPort) adv_stbd=\(h.advPerSStbd) lora_rssi=\(h.loraRssi) lora_snr=\(h.loraSnr) clock_offset_ms=\(h.clockOffsetMs) cfg_v=\(h.cfgVersion) filter_v=\(h.filterVersion) tx=\(P.txName(h.txPolicy)) error_flags=\(String(format: "0x%02x", h.errorFlags)) scanning=\(h.scanning)"
                + (h.phoneLost.map { " phone_lost=\($0)" } ?? "")
                + (h.ext.map { e in
                    // The Desk Pod's bytes 38-43 are always 0 (PROTOCOL.md 0x0107): only reset_reason means anything there.
                    h.role == P.roleDeskPod
                        ? String(format: " reset_reason=0x%x(%@)", e.resetReason, P.resetReasonNames(e.resetReason))
                        : String(format: " reset_reason=0x%x(%@) pair_source=%@ nods_ignored=%d excluded_per_s=%d exclude_flags=%@",
                           e.resetReason, P.resetReasonNames(e.resetReason), P.pairSourceName(e.pairSource), e.nodsIgnored,
                           e.excludedPerS, P.excludeFlagNames(e.excludeFlags))
                } ?? "")
                + (h.scanLostPerS.map { " scan_lost_per_s=\($0)" } ?? "")
        case P.DETECT:
            let d = try P.parseDetect(p)
            let head = "window_end_ms=\(d.windowEndMs) roll=\(Double(d.rollX2) / 2) pitch=\(Double(d.pitchX2) / 2) roll_rate=\(d.rollRateDps) attitude=\(String(format: "0x%02x", d.attitudeFlags)) n=\(d.records.count)"
            let recs = d.records.map { r in
                String(format: "[..%@ cls=%d at=%d port=%d stbd=%d hits=%d age=%d cid=0x%04x uuid16=0x%04x%@]",
                       r.addressTail, r.deviceClass, r.addressType, r.rssiPort, r.rssiStbd, r.hits, r.ageMs, r.companyId, r.uuid16, r.isRemoteID ? " rid" : "")
            }.joined(separator: " ")
            return recs.isEmpty ? head : "\(head) \(recs)"
        case P.STEER:
            let st = try P.parseSteer(p)
            let head = "steer_seq=\(st.steerSeq) steer_flags=\(P.steerFlagNames(st.flags)) src=\(st.srcId)"
            let target = !st.target ? "" : String(
                format: " kind=%@ cid=0x%04x rssi_port=%d rssi_stbd=%d peak=%d hits_port=%d hits_stbd=%d age_ms=%d [..%@] addr_type=%d",
                P.devClassName(st.devClass), st.companyId, st.rssiPort, st.rssiStbd, st.peakRssi, st.hitsPort, st.hitsStbd, st.ageMs,
                st.addressTail, st.addrType)
            return "\(head)\(target) window_end_ms=\(st.windowEndMs) roll_x2=\(st.rollX2) pitch_x2=\(st.pitchX2)"
        case P.FILTER_ACK:
            let f = try P.parseFilterAck(p)
            return String(format: "version=%d op=%d ok=%d list_crc=0x%04x stored=%d", f.version, f.op, f.ok, f.listCrc, f.stored)
                + (f.srcId.map { String(format: " src_id=0x%04x", $0) } ?? "")
        case P.STEER_MUTE:
            let m = try P.parseSteerMute(p)
            return "op=\(P.muteOpName(m.op)) src=\(m.srcId)"
        case P.IMU_CAL:
            let c = try P.parseImuCal(p)
            return "op=\(P.imuCalOpName(c.op)) tag=\(c.tag)"
        case P.IMU_CAL_ACK:
            let k = try P.parseImuCalAck(p)
            return "op=\(P.imuCalOpName(k.op)) tag=\(k.tag) status=\(P.imuCalStatusName(k.status)) cal_flags=\(P.imuCalFlagNames(k.flags)) " +
                "roll_x2=\(k.rollX2) pitch_x2=\(k.pitchX2) tilt_x2=\(k.tiltX2) face=\(P.imuFaceName(k.face)) y=\(P.imuYName(k.yPoints)) " +
                "gyro_bias_x10=\(k.gyroBiasX10.map(String.init).joined(separator: ","))"
        default:
            return p.isEmpty ? "" : "raw=\(P.hex(p))"
        }
    }
}
