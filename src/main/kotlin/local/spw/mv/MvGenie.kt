package local.spw.mv

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.nativeCanvas
import org.jetbrains.skia.*

/** A curved, narrowing ribbon in the existing GPU canvas; no desktop capture or window resizing. */
class MvGenie : AutoCloseable {
    private val effect = RuntimeEffect.makeForShader("""
        uniform shader page;
        uniform float2 viewport;
        uniform float4 origin;
        uniform float progress;
        half4 main(float2 xy) {
            if (progress >= 0.9999) return page.eval(xy);
            float p = clamp(progress, 0.001, 1.0);
            float top = mix(origin.y, 0.0, p);
            float bottom = mix(origin.w, viewport.y, p);
            float v = (xy.y - top) / max(bottom - top, 1.0);
            if (v < 0.0 || v > 1.0) return half4(0);
            float spread = pow(p, mix(0.72, 2.4, smoothstep(0.0, 1.0, v)));
            float width = mix(origin.z - origin.x, viewport.x, spread);
            float center = mix((origin.x + origin.z) * 0.5, viewport.x * 0.5, spread);
            center += sin(v * 3.141593) * sin(p * 3.141593) * viewport.x * 0.055
                * sign(viewport.x - origin.x - origin.z);
            center = clamp(center, width * 0.5, viewport.x - width * 0.5);
            float u = (xy.x - center) / max(width, 1.0) + 0.5;
            if (u < 0.0 || u > 1.0) return half4(0);
            float2 extent = float2(width, bottom - top);
            float radius = min(min(extent.x, extent.y) * 0.5, 22.0 * (1.0 - p));
            float2 corner = abs(float2(u, v) * extent - extent * 0.5) - (extent * 0.5 - radius);
            float distance = length(max(corner, 0.0)) + min(max(corner.x, corner.y), 0.0) - radius;
            float edge = 1.0 - smoothstep(-0.6, 0.6, distance);
            return page.eval(float2(u * viewport.x, v * viewport.y)) * edge;
        }
    """.trimIndent())
    private val shader = RuntimeShaderBuilder(effect)
    private val paint = Paint()
    fun modifier(source: () -> androidx.compose.ui.geometry.Rect, progress: () -> Float, prewarm: Boolean = false) = Modifier.drawWithContent {
        val p = progress()
        if (p >= .9999f && !prewarm) drawContent()
        else if (p > 0f) {
            val start = source()
            shader.uniform("viewport", size.width, size.height)
            shader.uniform("origin", start.left, start.top, start.right, start.bottom)
            shader.uniform("progress", p)
            ImageFilter.makeRuntimeShader(shader, "page", null).use { filter ->
                paint.imageFilter = filter
                val canvas = drawContext.canvas.nativeCanvas
                canvas.saveLayer(null, paint)
                try { drawContent() } finally { canvas.restore(); paint.imageFilter = null }
            }
        }
    }
    override fun close() { paint.close(); shader.close(); effect.close() }
}
