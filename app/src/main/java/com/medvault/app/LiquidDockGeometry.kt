package com.medvault.app
import kotlin.math.abs
import kotlin.math.min
internal data class DockGeometryFrame(val slotWidthPx:Float,val widthPx:Float,val heightPx:Float,val centerX:Float,val centerY:Float,val liveActiveIndex:Int)
internal object LiquidDockGeometryEngine {
 fun calculate(dockWidthPx:Float,contentWidthPx:Float,dockHeightPx:Float,requestedCenterX:Float,progress:Float,velocityX:Float,horizontalInsetPx:Float,restingHeightPx:Float):DockGeometryFrame{
  val slot=(contentWidthPx/4f).coerceAtLeast(1f);val p=progress.coerceIn(0f,1f);val d=LiquidMotionPhysics.deformation(velocityX)
  val idleH=restingHeightPx.coerceAtMost(dockHeightPx);val idleW=(slot*0.84f).coerceAtLeast(idleH*1.58f)
  val lensH=(dockHeightPx*0.94f).coerceAtMost(dockHeightPx*1.02f);val lensW=slot*0.96f
  val baseH=idleH+(lensH-idleH)*p;val baseW=idleW+(lensW-idleW)*p
  val wantedW=baseW*(1f+d.horizontalStretch*p);val height=baseH*(1f-d.verticalCompression*p)
  val width=min(wantedW,(dockWidthPx-horizontalInsetPx*2f).coerceAtLeast(1f))
  val minX=horizontalInsetPx+width/2f;val maxX=(dockWidthPx-horizontalInsetPx-width/2f).coerceAtLeast(minX)
  val center=requestedCenterX.coerceIn(minX,maxX)
  val active=(0..3).minByOrNull{abs(center-(horizontalInsetPx+slot*(it+0.5f)))}?:0
  return DockGeometryFrame(slot,width,height,center,dockHeightPx/2f,active)
 }
}
