package org.ncssar.rid2caltopo.mapping

/** Position fixes are priors, not a substitute for verified multi-view geometry. */
internal object MappingPositionQuality {
    fun fixed(flag:Int?,east:Double?,north:Double?,height:Double?) = flag==50 && listOf(east,north,height).all{it!=null&&it.isFinite()&&it>0&&it<1}
    fun sigma(recorded:Double?,fixed:Boolean,vertical:Boolean):Double {
        val floor=if(fixed)if(vertical).03 else .02 else if(vertical)2.0 else 1.0
        require(recorded==null || !recorded.isFinite() || recorded<=10){"Exposure position uncertainty exceeds the supported 10 m range."}
        return maxOf(recorded?.takeIf{it.isFinite()&&it>0} ?: 0.0,floor)
    }
    const val SCALE_WARNING="Nominal camera calibration used because laser height anchors were insufficient. Surface height and scale are not independently verified."
    const val WARNING="GNSS-assisted reconstruction: some photos lack a verified fixed-RTK position. Conservative position priors were used; absolute placement is not independently verified."
}
