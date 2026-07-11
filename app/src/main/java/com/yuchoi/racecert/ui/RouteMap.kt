package com.yuchoi.racecert.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.min

/**
 * Strava 코스(구글 encoded polyline)를 지도 SDK 없이 Canvas에 경로 스케치로 그린다.
 * 위경도를 화면 좌표로 정규화하고, 위도에 따른 경도 축소(cos)를 반영해 비율을 맞춘다.
 */
@Composable
fun RouteMap(polyline: String, modifier: Modifier = Modifier, color: Color = Color(0xFFFC4C02)) {
    val points = remember(polyline) { decodePolyline(polyline) }
    if (points.size < 2) return

    Canvas(modifier = modifier) {
        val lats = points.map { it.first }
        val lngs = points.map { it.second }
        val minLat = lats.min(); val maxLat = lats.max()
        val minLng = lngs.min(); val maxLng = lngs.max()
        val cosLat = cos(Math.toRadians((minLat + maxLat) / 2.0))
        val geoW = ((maxLng - minLng) * cosLat).takeIf { it > 0 } ?: 1e-9
        val geoH = (maxLat - minLat).takeIf { it > 0 } ?: 1e-9

        val pad = 10.dp.toPx()
        val availW = size.width - 2 * pad
        val availH = size.height - 2 * pad
        val scale = min(availW / geoW, availH / geoH)
        // 그린 경로를 캔버스 중앙에 배치하기 위한 오프셋
        val drawnW = geoW * scale
        val drawnH = geoH * scale
        val offX = pad + (availW - drawnW) / 2
        val offY = pad + (availH - drawnH) / 2

        fun project(lat: Double, lng: Double): Offset {
            val x = offX + ((lng - minLng) * cosLat) * scale
            val y = offY + (maxLat - lat) * scale // y축 반전
            return Offset(x.toFloat(), y.toFloat())
        }

        val path = Path()
        points.forEachIndexed { i, (lat, lng) ->
            val o = project(lat, lng)
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}

/** 구글 encoded polyline → (위도, 경도) 목록 */
private fun decodePolyline(encoded: String): List<Pair<Double, Double>> {
    val poly = ArrayList<Pair<Double, Double>>()
    var index = 0
    var lat = 0
    var lng = 0
    while (index < encoded.length) {
        var shift = 0
        var result = 0
        var b: Int
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20 && index < encoded.length)
        val dlat = if (result and 1 != 0) (result shr 1).inv() else result shr 1
        lat += dlat

        shift = 0
        result = 0
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20 && index < encoded.length)
        val dlng = if (result and 1 != 0) (result shr 1).inv() else result shr 1
        lng += dlng

        poly.add(lat / 1e5 to lng / 1e5)
    }
    return poly
}
