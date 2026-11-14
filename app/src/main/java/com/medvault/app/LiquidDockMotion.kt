package com.medvault.app
import kotlin.math.abs
import kotlin.math.sign
internal enum class DockPhase{Idle,Pressed,Dragging,Travelling,Settling}
internal fun resolveDockPhase(isPressed:Boolean,isDragging:Boolean,isAnimating:Boolean):DockPhase=when{isDragging->DockPhase.Dragging;isPressed->DockPhase.Pressed;isAnimating->DockPhase.Travelling;else->DockPhase.Idle}
internal data class LiquidDeformation(val direction:Float,val speed:Float,val horizontalStretch:Float,val verticalCompression:Float,val trailingBias:Float)
internal object LiquidMotionPhysics{
 fun deformation(normalizedSignedVelocity:Float):LiquidDeformation{val v=normalizedSignedVelocity.coerceIn(-1f,1f);val s=abs(v);return LiquidDeformation(sign(v),s,0.35f*s,0.18f*s,0.12f*s)}
}
