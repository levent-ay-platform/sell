package tn.malak.app.ui
internal enum class OwnerIntakeKind { Identity, Certificate, Plan, Extra }
internal object OwnerIntakePolicy {
    fun displayStep(step:Int):Int = if(step<=1)0 else step-1
    fun next(step:Int):Int = if(step<=1)2 else (step+1).coerceAtMost(3)
    fun previous(step:Int):Int = if(step<=2)0 else 2
}
