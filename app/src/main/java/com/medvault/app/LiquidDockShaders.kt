package com.medvault.app
internal object LiquidDockShaders{
 val REFRACTIVE_LENS=""" 
 uniform shader uBackdrop;uniform float2 uLensCenter;uniform float2 uHalfSize;uniform float2 uBackdropOffset;
 uniform float uProgress;uniform float uDirection;uniform float uTrailing;uniform float uRefraction;uniform float uChromatic;uniform float uMagnification;
 half4 main(float2 position){
  float2 local=position-uLensCenter;float2 h=max(uHalfSize,float2(1.0,1.0));float2 n=local/h;float r=dot(n,n);
  float centre=1.0-smoothstep(0.12,1.05,r);float edge=smoothstep(0.28,1.0,r);
  float2 normal=normalize(float2(local.x/(h.x*h.x),local.y/(h.y*h.y))+float2(0.0001,0.0001));
  float bias=1.0+(-uDirection*n.x)*uTrailing;float mag=uMagnification*uProgress*centre*bias;
  float2 samplePos=uBackdropOffset+position-local*mag+normal*(uRefraction*edge*uProgress);
  float2 dispersion=normal*(uChromatic*edge*uProgress);half4 base=uBackdrop.eval(samplePos);
  half red=uBackdrop.eval(samplePos+dispersion).r;half blue=uBackdrop.eval(samplePos-dispersion).b;
  half3 refracted=half3(red,base.g,blue);float fresnel=pow(clamp(edge,0.0,1.0),1.7);
  half3 tint=half3(0.035,0.055,0.085)*half(fresnel*0.18);half3 result=mix(base.rgb,refracted,half(0.72*uProgress))+tint;
  return half4(result,base.a);
 }
 """.trimIndent()
}
