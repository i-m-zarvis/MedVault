package com.medvault.app
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
class LiquidDockGeometryTest{
 private fun f(x:Float,p:Float=0f,v:Float=0f,c:Boolean=false)=LiquidDockGeometryEngine.calculate(360f,320f,if(c)50f else 62f,x,p,v,5f,if(c)42f else 52f)
 @Test fun idleCapsuleIsStadium(){val g=f(180f);assertTrue(g.widthPx>g.heightPx*1.58f-0.01f)}
 @Test fun idleInsideDock(){val g=f(180f);assertTrue(g.heightPx<=62f);assertEquals(31f,g.centerY,0.001f)}
 @Test fun activeLensRounderThanRest(){val a=f(180f);val b=f(180f,1f);assertTrue(b.heightPx>a.heightPx);assertTrue(b.widthPx/b.heightPx<a.widthPx/a.heightPx)}
 @Test fun fastDragStretchesAndCompresses(){val a=f(180f,1f);val b=f(180f,1f,1f);assertTrue(b.widthPx>a.widthPx);assertTrue(b.heightPx<a.heightPx)}
 @Test fun homeClampedIncludingTrailingTail(){val g=f(-100f,1f,1f);val left=g.widthPx/2f*(1f+0.12f);assertTrue(g.centerX-left>=5f-0.001f)}
 @Test fun offlineClampedIncludingLeadingEdge(){val g=f(1000f,1f,1f);val right=g.widthPx/2f*(1f-0.12f);assertTrue(g.centerX+right<=355f+0.001f)}
 @Test fun compactHeight(){val g=f(180f,0f,0f,true);assertEquals(42f,g.heightPx,0.001f);assertEquals(25f,g.centerY,0.001f)}
 @Test fun liveTabTracksCenter(){assertEquals(0,f(45f,1f).liveActiveIndex);assertEquals(3,f(315f,1f).liveActiveIndex)}
}
