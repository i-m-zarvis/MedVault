package com.medvault.app
import org.junit.Assert.assertEquals
import org.junit.Test
class LiquidDockMotionTest{
 @Test fun positiveVelocityDeforms(){val d=LiquidMotionPhysics.deformation(1f);assertEquals(1f,d.direction,0.001f);assertEquals(0.35f,d.horizontalStretch,0.001f);assertEquals(0.18f,d.verticalCompression,0.001f);assertEquals(0.12f,d.trailingBias,0.001f)}
 @Test fun negativeVelocityReverses(){val d=LiquidMotionPhysics.deformation(-0.75f);assertEquals(-1f,d.direction,0.001f);assertEquals(0.75f,d.speed,0.001f)}
 @Test fun velocityClamps(){assertEquals(1f,LiquidMotionPhysics.deformation(4f).speed,0.001f)}
}
