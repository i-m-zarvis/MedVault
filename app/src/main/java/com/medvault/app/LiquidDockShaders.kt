package com.medvault.app

/**
 * AGSL lens shader. Input coordinates are in dock-local physical pixels and
 * the input bitmap is a PixelCopy of the same dock rectangle.
 */
internal object LiquidDockShaders {
    val REFRACTIVE_LENS = """
        uniform shader uBackdrop;
        uniform float2 uLensCenter;
        uniform float2 uHalfSize;
        uniform float uProgress;
        uniform float uRefraction;
        uniform float uChromatic;

        float roundedRectDistance(float2 p, float2 halfSize, float radius) {
            float2 q = abs(p) - (halfSize - radius);
            return length(max(q, 0.0)) +
                   min(max(q.x, q.y), 0.0) - radius;
        }

        half4 main(float2 position) {
            float2 local = position - uLensCenter;
            float radius = min(uHalfSize.x, uHalfSize.y);
            float distance = roundedRectDistance(local, uHalfSize, radius);
            float edge = 1.0 - smoothstep(-2.0, 2.0, distance);
            float inside = 1.0 - smoothstep(-1.0, 1.0, distance);
            float2 safeLocal = local / max(uHalfSize, float2(1.0, 1.0));
            float radial = dot(safeLocal, safeLocal);
            float2 normal = normalize(local + float2(0.0001, 0.0001));

            // Convex lens magnification plus a restrained outward refraction.
            float magnification = 0.075 * uProgress * (1.0 - smoothstep(0.35, 1.2, radial));
            float2 samplePosition = position
                - local * magnification
                + normal * (uRefraction * edge * uProgress);

            float2 dispersion = normal * (uChromatic * edge * uProgress);
            half4 base = uBackdrop.eval(samplePosition);
            half red = uBackdrop.eval(samplePosition + dispersion).r;
            half blue = uBackdrop.eval(samplePosition - dispersion).b;

            half3 refracted = half3(red, base.g, blue);
            half alpha = inside * base.a;
            return half4(mix(base.rgb, refracted, uProgress), alpha);
        }
    """.trimIndent()
}
