package com.medvault.app
import androidx.compose.ui.graphics.Path
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class LiquidDockShapeTest{
 @Test fun pathIsNonEmpty(){val p=Path();LiquidDockShape.updatePath(p,100f,40f,84f,58f,1f,0.12f);assertFalse(p.isEmpty)}
 @Test fun zeroVelocityIsSymmetric(){val p=Path();LiquidDockShape.updatePath(p,100f,40f,80f,56f,0f,0f);val b=p.getBounds();assertTrue(kotlin.math.abs(b.left-60f)<0.01f);assertTrue(kotlin.math.abs(b.right-140f)<0.01f)}
 @Test fun directionReversalMirrorsTail(){val a=Path();val b=Path();LiquidDockShape.updatePath(a,100f,40f,80f,56f,1f,0.12f);LiquidDockShape.updatePath(b,100f,40f,80f,56f,-1f,0.12f);val x=a.getBounds();val y=b.getBounds();assertTrue(x.left<y.left);assertTrue(x.right<y.right)}
}
