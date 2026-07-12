package com.yuchoi.racecert.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sin

private const val TILE = 256.0

/**
 * Strava 코스(구글 encoded polyline)를 OpenStreetMap 타일 지도 위에 그린다.
 * 지도 SDK·API 키 없이 OSM 래스터 타일(z/x/y PNG)을 받아 캔버스에 깔고 경로를 오버레이한다.
 * 타일은 기기 캐시에 저장해 재방문 시 재다운로드하지 않는다.
 */
@Composable
fun RouteMap(polyline: String, modifier: Modifier = Modifier, routeColor: Color = Color(0xFFFC4C02)) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // 여러 종목(수영·바이크·러닝)의 경로가 줄바꿈으로 이어져 올 수 있으므로 각각 분리해 그린다
    val legs = remember(polyline) {
        polyline.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            .map { decodePolyline(it) }.filter { it.size >= 2 }
    }
    if (legs.isEmpty()) return
    val points = remember(legs) { legs.flatten() }
    if (points.size < 2) return

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        val wPx = with(density) { maxWidth.toPx() }.toInt()
        val hPx = with(density) { maxHeight.toPx() }.toInt()

        val data by produceState<RouteMapData?>(null, polyline, wPx, hPx) {
            value = if (wPx <= 0 || hPx <= 0) null
            else withContext(Dispatchers.IO) { buildMap(context, legs, points, wPx, hPx) }
        }

        val map = data
        if (map == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            Canvas(Modifier.fillMaxSize()) {
                map.tiles.forEach { drawImage(it.bmp, topLeft = Offset(it.left, it.top)) }
                // 각 종목 경로를 개별 선으로 그린다 (종목 간 연결선 없음)
                map.routes.forEach { leg ->
                    val path = Path()
                    leg.forEachIndexed { i, o ->
                        if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
                    }
                    drawPath(
                        path = path,
                        color = routeColor,
                        style = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
        }
    }
}

private class TileImg(val bmp: ImageBitmap, val left: Float, val top: Float)
private class RouteMapData(val tiles: List<TileImg>, val routes: List<List<Offset>>)

private fun worldX(lonDeg: Double, z: Int): Double =
    (lonDeg + 180.0) / 360.0 * TILE * (1 shl z)

private fun worldY(latDeg: Double, z: Int): Double {
    val s = sin(latDeg * PI / 180.0).coerceIn(-0.9999, 0.9999)
    val y = 0.5 - ln((1 + s) / (1 - s)) / (4 * PI)
    return y * TILE * (1 shl z)
}

private fun buildMap(
    context: Context,
    legs: List<List<Pair<Double, Double>>>,
    points: List<Pair<Double, Double>>,
    wPx: Int,
    hPx: Int,
): RouteMapData {
    val minLat = points.minOf { it.first }
    val maxLat = points.maxOf { it.first }
    val minLon = points.minOf { it.second }
    val maxLon = points.maxOf { it.second }

    // 경로가 캔버스 안에 들어오는 가장 큰(자세한) 줌 선택
    var zoom = 2
    for (z in 17 downTo 2) {
        val pxW = worldX(maxLon, z) - worldX(minLon, z)
        val pxH = worldY(minLat, z) - worldY(maxLat, z) // 위도 낮을수록 y 큼
        if (pxW <= wPx * 0.92 && pxH <= hPx * 0.92) { zoom = z; break }
    }

    val routePxW = worldX(maxLon, zoom) - worldX(minLon, zoom)
    val routePxH = worldY(minLat, zoom) - worldY(maxLat, zoom)
    // 경로를 캔버스 중앙에 오도록 원점(좌상단 월드좌표) 계산
    val originX = worldX(minLon, zoom) - (wPx - routePxW) / 2.0
    val originY = worldY(maxLat, zoom) - (hPx - routePxH) / 2.0

    val routes = legs.map { leg ->
        leg.map { (lat, lon) ->
            Offset((worldX(lon, zoom) - originX).toFloat(), (worldY(lat, zoom) - originY).toFloat())
        }
    }

    val n = 1 shl zoom
    val txMin = floor(originX / TILE).toInt()
    val txMax = floor((originX + wPx) / TILE).toInt()
    val tyMin = floor(originY / TILE).toInt()
    val tyMax = floor((originY + hPx) / TILE).toInt()

    val tiles = ArrayList<TileImg>()
    var count = 0
    for (ty in tyMin..tyMax) {
        if (ty < 0 || ty >= n) continue
        for (tx in txMin..txMax) {
            val wrappedX = ((tx % n) + n) % n
            if (count++ > 40) break
            val bmp = tileBitmap(context, zoom, wrappedX, ty) ?: continue
            val left = (tx * TILE - originX).toFloat()
            val top = (ty * TILE - originY).toFloat()
            tiles.add(TileImg(bmp, left, top))
        }
    }
    return RouteMapData(tiles, routes)
}

/** OSM 타일을 캐시에서 읽거나 없으면 내려받는다. */
private fun tileBitmap(context: Context, z: Int, x: Int, y: Int): ImageBitmap? {
    val dir = File(context.cacheDir, "osm").apply { if (!exists()) mkdirs() }
    val f = File(dir, "${z}_${x}_${y}.png")
    if (!f.exists() || f.length() == 0L) {
        runCatching {
            val conn = URL("https://tile.openstreetmap.org/$z/$x/$y.png")
                .openConnection() as HttpURLConnection
            try {
                conn.setRequestProperty("User-Agent", "MyRaceCertificate-Android (personal race app)")
                conn.connectTimeout = 12_000
                conn.readTimeout = 12_000
                if (conn.responseCode !in 200..299) return null
                conn.inputStream.use { input -> f.outputStream().use { input.copyTo(it) } }
            } finally {
                conn.disconnect()
            }
        }.getOrElse { return null }
    }
    return runCatching { BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() }.getOrNull()
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
