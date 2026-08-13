package com.unistroke.ime

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * テンプレートのポリラインを「書き方の見本」として描くための共通処理。
 * ジェスチャー見本オーバーレイ（UniStrokeView）とトレーニング画面の両方から使う。
 */
object StrokeArt {

    /**
     * [points] を一辺 [side] の正方形へ収めた [Path] を [out] に組み立てる。
     * アニメーション（部分描画）したい場合はこれと [PathMeasure] を使う。
     */
    fun buildPath(
        points: List<Pt>,
        left: Float,
        top: Float,
        side: Float,
        out: Path,
    ) {
        out.reset()
        if (points.size < 2) return
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            minX = min(minX, p.x); maxX = max(maxX, p.x)
            minY = min(minY, p.y); maxY = max(maxY, p.y)
        }
        val spanX = max(0.0001f, maxX - minX)
        val spanY = max(0.0001f, maxY - minY)
        val scale = min(side / spanX, side / spanY)
        val offX = left + (side - spanX * scale) / 2f
        val offY = top + (side - spanY * scale) / 2f
        out.moveTo(offX + (points[0].x - minX) * scale, offY + (points[0].y - minY) * scale)
        for (k in 1 until points.size) {
            out.lineTo(offX + (points[k].x - minX) * scale, offY + (points[k].y - minY) * scale)
        }
    }

    /**
     * パス上の [distance] の位置に進行方向の矢じりを描く。
     * ループのどちら回りかを示すのに使う。
     */
    fun drawChevron(
        canvas: Canvas,
        measure: PathMeasure,
        distance: Float,
        paint: Paint,
        size: Float,
        pos: FloatArray,
        tan: FloatArray,
    ) {
        if (!measure.getPosTan(distance, pos, tan)) return
        val len = hypot(tan[0], tan[1])
        if (len < 1e-4f) return
        val ux = tan[0] / len
        val uy = tan[1] / len
        val x = pos[0]
        val y = pos[1]
        canvas.drawLine(
            x, y,
            x - size * (ux * 0.87f - uy * 0.5f),
            y - size * (uy * 0.87f + ux * 0.5f),
            paint,
        )
        canvas.drawLine(
            x, y,
            x - size * (ux * 0.87f + uy * 0.5f),
            y - size * (uy * 0.87f - ux * 0.5f),
            paint,
        )
    }

    /**
     * [points]（正規化ポリライン）を一辺 [side] の正方形へ収めて描く。
     * 書き始めに ● を、書き終わりに矢印を付ける。
     */
    fun draw(
        canvas: Canvas,
        points: List<Pt>,
        left: Float,
        top: Float,
        side: Float,
        strokePaint: Paint,
        dotPaint: Paint,
        path: Path,
        dotRadius: Float,
        arrowLen: Float,
    ) {
        if (points.size < 2) return

        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (p in points) {
            minX = min(minX, p.x); maxX = max(maxX, p.x)
            minY = min(minY, p.y); maxY = max(maxY, p.y)
        }
        val spanX = max(0.0001f, maxX - minX)
        val spanY = max(0.0001f, maxY - minY)
        val scale = min(side / spanX, side / spanY)
        val offX = left + (side - spanX * scale) / 2f
        val offY = top + (side - spanY * scale) / 2f

        fun px(p: Pt) = offX + (p.x - minX) * scale
        fun py(p: Pt) = offY + (p.y - minY) * scale

        path.reset()
        path.moveTo(px(points[0]), py(points[0]))
        for (k in 1 until points.size) path.lineTo(px(points[k]), py(points[k]))
        canvas.drawPath(path, strokePaint)

        // 書き始め
        canvas.drawCircle(px(points[0]), py(points[0]), dotRadius, dotPaint)

        // 書き終わりの向き
        val n = points.size
        var i = n - 2
        var prev = points[i]
        while (i > 0 && hypot(points[n - 1].x - prev.x, points[n - 1].y - prev.y) < 0.02f) {
            i--
            prev = points[i]
        }
        val ex = px(points[n - 1])
        val ey = py(points[n - 1])
        val dx = ex - px(prev)
        val dy = ey - py(prev)
        val len = hypot(dx, dy)
        if (len > 0.5f) {
            val ux = dx / len
            val uy = dy / len
            canvas.drawLine(
                ex, ey,
                ex - arrowLen * (ux * 0.87f - uy * 0.5f),
                ey - arrowLen * (uy * 0.87f + ux * 0.5f),
                strokePaint,
            )
            canvas.drawLine(
                ex, ey,
                ex - arrowLen * (ux * 0.87f + uy * 0.5f),
                ey - arrowLen * (uy * 0.87f - ux * 0.5f),
                strokePaint,
            )
        }
    }
}
