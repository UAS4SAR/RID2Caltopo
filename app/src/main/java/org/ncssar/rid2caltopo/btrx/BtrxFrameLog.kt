package org.ncssar.rid2caltopo.btrx

import org.ncssar.rid2caltopo.btrx.BtrxProtocol as P

/**
 * One debug-log line per btrx frame, close to r2c_sim's output so a bench run can
 * be compared with an `r2c_sim listen` log. Bluetooth addresses are cut to their
 * last 4 characters: the log is shared for support and must not hold bystanders'
 * full addresses.
 */
object BtrxFrameLog {
    fun format(frame: P.Frame): String {
        val head = "${frame.typeName} seq=${frame.seq} pod=%08x flags=%s".format(frame.podId, P.frameFlagNames(frame.flags))
        val body = try {
            describe(frame.type, frame.payload)
        } catch (e: P.PayloadException) {
            "decode-error=${e.message} raw=${P.hex(frame.payload)}"
        }
        return if (body.isEmpty()) head else "$head  $body"
    }

    fun describe(type: Int, p: ByteArray): String = when (type) {
        P.DESK_PAIR -> P.parseDeskPair(p).let {
            val src = P.deskPairSourceName(it.source)
            "open=${it.open} source=$src seconds=${it.secondsLeft}"
        }
        P.PAIR_REQ -> P.parsePairReq(p).let {
            "pod_id=%08x dev_a=%08x dev_b=%08x build_a=0x%04x build_b=0x%04x imu_ok=%d role_a=%s role_b=%s nonce=%s".format(
                it.podId, it.devA, it.devB, it.buildA, it.buildB, it.imuOk, P.roleName(it.roleA), P.roleName(it.roleB), P.hex(it.nonce))
        }
        P.PAIR_ACK -> P.parsePairAck(p).let { "a_ok=${it.aOk} b_ok=${it.bOk} flags=${P.statusFlagNames(it.flags)}" }
        P.ROLE_REPORT -> P.parseRoleReport(p).let {
            "role=%d(%s) source=%d confirmed=%d flags=%s build_hash=0x%04x dev_id=%08x peer_id=%08x pod_id=%08x imu_whoami=0x%02x tx=%s".format(
                it.role, P.roleName(it.role), it.source, it.confirmed, P.statusFlagNames(it.flags), it.buildHash,
                it.devId, it.peerId, it.podId, it.imuWhoami, P.txName(it.txPolicy))
        }
        P.TIME_PING, P.TIME_PONG -> P.parseTime(p).let { "ping_seq=${it.pingSeq} t1=${it.t1} t2=${it.t2} t3=${it.t3}" }
        P.CTRL_ACK -> P.parseCtrlAck(p).let {
            "acked=${P.typeName(it.ackedType)} acked_seq=${it.ackedSeq} ok=${it.ok} session_id=${it.sessionId} actual_ms=${it.actualMs}"
        }
        P.SCAN_CFG, P.SCAN_CFG_ACK -> P.parseScanCfg(p).let {
            "interval=${it.interval625us} window=${it.window625us} phy=${it.phy} rssi_floor=${it.rssiFloor} report_ms=${it.reportMs} " +
                "dedup_ms=${it.dedupMs} channel_mask=${it.channelMask} profile=${it.profileId} active_scan=${it.activeScan}" +
                (it.result?.let { r -> " result=$r" } ?: "")
        }
        P.FILTER_ACK -> P.parseFilterAck(p).let {
            "version=${it.version} op=${it.op} ok=${it.ok} list_crc=0x%04x stored=${it.stored}".format(it.listCrc) +
                (it.srcId?.let { id -> " src_id=0x%04x".format(id) } ?: "")
        }
        P.HEALTH -> P.parseHealth(p).let {
            "uptime_s=%d build_hash=0x%04x role=%s source=%d confirmed=%d flags=%s b_link_age_s=%s imu_ok=%d die_temp_c=%d adv_port=%d adv_stbd=%d lora_rssi=%d lora_snr=%d clock_offset_ms=%d cfg_v=%d filter_v=%d tx=%s error_flags=0x%02x scanning=%d".format(
                it.uptimeS, it.buildHash, P.roleName(it.role), it.source, it.confirmed, P.statusFlagNames(it.flags),
                if (it.bLinkAgeS == 0xFFFF) "never" else it.bLinkAgeS.toString(), it.imuOk, it.dieTempC,
                it.advPerSPort, it.advPerSStbd, it.loraRssi, it.loraSnr, it.clockOffsetMs, it.cfgVersion,
                it.filterVersion, P.txName(it.txPolicy), it.errorFlags, it.scanning) +
                (it.phoneLost?.let { l -> " phone_lost=$l" } ?: "") +
                (it.ext?.let { e ->
                    // The Desk Pod's bytes 38-43 are always 0 (PROTOCOL.md 0x0107): only reset_reason means anything there.
                    if (it.role == P.ROLE_DESK_POD) " reset_reason=0x%x(%s)".format(e.resetReason, P.resetReasonNames(e.resetReason))
                    else " reset_reason=0x%x(%s) pair_source=%s nods_ignored=%d excluded_per_s=%d exclude_flags=%s".format(
                        e.resetReason, P.resetReasonNames(e.resetReason), P.pairSourceName(e.pairSource), e.nodsIgnored,
                        e.excludedPerS, P.excludeFlagNames(e.excludeFlags))
                } ?: "") +
                (it.scanLostPerS?.let { l -> " scan_lost_per_s=$l" } ?: "") +
                (it.power?.let { w -> powerFields(w) } ?: "")
        }
        P.CONFIRM_REQ -> P.parseConfirmReq(p).let {
            "version=${it.version} action=${P.confirmActionName(it.action)} token=${it.token} timeout_s=${it.timeoutS} state=${P.confirmStateName(it.state)}"
        }
        P.CONFIRM_RSP -> P.parseConfirmRsp(p).let { "version=${it.version} token=${it.token} answer=${P.confirmAnswerName(it.answer)}" }
        P.PAIR_OPEN -> if (p.isEmpty()) "seconds=default" else "seconds=${p[0].toInt() and 0xFF}"
        P.DETECT -> P.parseDetect(p).let { d ->
            val head = "window_end_ms=${d.windowEndMs} roll=${d.rollX2 / 2.0} pitch=${d.pitchX2 / 2.0} roll_rate=${d.rollRateDps} " +
                "attitude=0x%02x n=${d.records.size}".format(d.attitudeFlags)
            val recs = d.records.joinToString(" ") { r ->
                "[..%s cls=%d at=%d port=%d stbd=%d hits=%d age=%d cid=0x%04x uuid16=0x%04x%s]".format(
                    r.addressTail, r.deviceClass, r.addressType, r.rssiPort, r.rssiStbd, r.hits, r.ageMs,
                    r.companyId, r.uuid16, if (r.isRemoteId) " rid" else "")
            }
            if (recs.isEmpty()) head else "$head $recs"
        }
        P.STEER -> P.parseSteer(p).let { st ->
            val head = "steer_seq=${st.steerSeq} steer_flags=${P.steerFlagNames(st.flags)} src=${st.srcId}"
            val target = if (!st.target) "" else
                " kind=${P.devClassName(st.devClass)} cid=0x%04x rssi_port=%d rssi_stbd=%d peak=%d hits_port=%d hits_stbd=%d age_ms=%d [..%s] addr_type=%d".format(
                    st.companyId, st.rssiPort, st.rssiStbd, st.peakRssi, st.hitsPort, st.hitsStbd, st.ageMs, st.addressTail, st.addrType)
            val ext = if (st.extFormat != 0) " ext_format=${st.extFormat}" else ""
            val yaw = st.yaw?.let { " ${BtrxYawLog.steerFields(st)}" } ?: ""
            "$head$target window_end_ms=${st.windowEndMs} roll_x2=${st.rollX2} pitch_x2=${st.pitchX2}$ext$yaw"
        }
        P.STEER_MUTE -> P.parseSteerMute(p).let { "op=${P.muteOpName(it.op)} src=${it.srcId}" }
        P.IMU_CAL -> P.parseImuCal(p).let { "op=${P.imuCalOpName(it.op)} tag=${it.tag}" }
        P.IMU_CAL_ACK -> P.parseImuCalAck(p).let { k ->
            "op=${P.imuCalOpName(k.op)} tag=${k.tag} status=${P.imuCalStatusName(k.status)} cal_flags=${P.imuCalFlagNames(k.flags)} " +
                "roll_x2=${k.rollX2} pitch_x2=${k.pitchX2} tilt_x2=${k.tiltX2} face=${P.imuFaceName(k.face)} y=${P.imuYName(k.yPoints)} " +
                "gyro_bias_x10=${k.gyroBiasX10.joinToString(",")}"
        }
        else -> if (p.isEmpty()) "" else "raw=${P.hex(p)}"
    }

    /** HEALTH supply fields (build 0x010B), same text on the iPad. */
    fun powerFields(w: P.HealthPower): String =
        " vdd_mv=%d vdd_min_mv=%d vddh_mv=%d vddh_min_mv=%d vbat_mv=%d power_flags=0x%02x(%s) boot_cause=%d(%s) vbus_drops=%d".format(
            w.vddMv, w.vddMinMv, w.vddhMv, w.vddhMinMv, w.vbatMv, w.powerFlags, P.powerFlagNames(w.powerFlags),
            w.bootCause, P.bootCauseName(w.bootCause), w.vbusDrops)
}
