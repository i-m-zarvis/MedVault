package com.medvault.app
import androidx.compose.ui.graphics.Path
import kotlin.math.max
internal object LiquidDockShape{
 fun updatePath(path:Path,centerX:Float,centerY:Float,width:Float,height:Float,direction:Float,trailingBias:Float){
  path.reset();val hw=max(1f,width/2f);val hh=max(1f,height/2f);val b=trailingBias.coerceIn(0f,0.12f);val d=direction.coerceIn(-1f,1f)
  val left=hw*(1f+d*b);val right=hw*(1f-d*b);val shift=d*b*hw*0.10f;val k=0.55228475f
  path.moveTo(centerX+shift,centerY-hh)
  path.cubicTo(centerX+shift+right*k,centerY-hh,centerX+right,centerY-hh*k,centerX+right,centerY)
  path.cubicTo(centerX+right,centerY+hh*k,centerX+shift+right*k,centerY+hh,centerX-shift,centerY+hh)
  path.cubicTo(centerX-shift-left*k,centerY+hh,centerX-left,centerY+hh*k,centerX-left,centerY)
  path.cubicTo(centerX-left,centerY-hh*k,centerX-shift-left*k,centerY-hh,centerX+shift,centerY-hh);path.close()
 }
}
