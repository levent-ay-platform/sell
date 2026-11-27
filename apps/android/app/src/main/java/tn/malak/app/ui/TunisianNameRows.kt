package tn.malak.app.ui

import kotlin.math.abs

/**
 * Template-bounded, content-aligned line selection for upright Tunisian
 * CIN fronts. Pure Kotlin so synthetic layouts can be tested without Android.
 * The numeric density contains no extracted private character data.
 */
internal object TunisianNameRows {
    data class PairOfRows(val surnameCenter:Int,val givenCenter:Int)
    fun locate(density:DoubleArray):PairOfRows? {
        if(density.size<300)return null
        val h=density.size
        // Smooth the printed dark-ink row response; ignore isolated noise.
        val smooth=DoubleArray(h)
        val sigma=6.0*h/760
        val radius=(sigma*3).toInt().coerceAtLeast(2)
        for(y in 0 until h) {
            var total=0.0
            var weight=0.0
            for(delta in -radius..radius) {
                val ny=y+delta
                if(ny !in density.indices)continue
                val w=kotlin.math.exp(-.5*delta*delta/(sigma*sigma))
                total+=density[ny]*w
                weight+=w
            }
            smooth[y]=total/weight
        }
        val a=(.43*h).toInt()
        val b=(.699*h).toInt().coerceAtMost(h-1)
        val peaks=mutableListOf<Int>()
        for(y in a+2 until b-2) {
            if(smooth[y]<.05 ||
                smooth[y]<smooth[y-1] || smooth[y]<=smooth[y+1])continue
            val margin=(.025*h).toInt().coerceAtLeast(4)
            val left=(y-margin).coerceAtLeast(a)
            val right=(y+margin).coerceAtMost(b)
            if(smooth[y]-minOf(smooth[left],smooth[right])<.010)continue
            // Non-maximum suppression at a minimum row-to-row distance.
            val previous=peaks.lastOrNull()
            if(previous!=null && y-previous<(.054*h).toInt()) {
                if(smooth[y]>smooth[previous])peaks[peaks.lastIndex]=y
            }else peaks.add(y)
        }
        var selected:PairOfRows?=null
        var score=Double.NEGATIVE_INFINITY
        for((i,aRow) in peaks.withIndex()) {
            for(bRow in peaks.drop(i+1)) {
                val top=aRow.toDouble()/h
                val bottom=bRow.toDouble()/h
                val gap=bottom-top
                if(top !in .46.. .59 || bottom !in .56.. .69 ||
                    gap !in .065.. .125)continue
                val candidate=smooth[aRow]+smooth[bRow]-abs(gap-.092)*2.0
                if(candidate>score){
                    selected=PairOfRows(aRow,bRow)
                    score=candidate
                }
            }
        }
        return selected
    }
}
