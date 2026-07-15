package com.yuchoi.racecert.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.yuchoi.racecert.BuildConfig
import com.yuchoi.racecert.health.HealthConnectBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val axisFormatter = DateTimeFormatter.ofPattern("yy.MM.dd")

private sealed interface BodyUi {
    data object Loading : BodyUi
    data object Unsupported : BodyUi
    data object NeedInstall : BodyUi
    data object NeedPermission : BodyUi
    data object Empty : BodyUi
    data class Data(val history: HealthConnectBody.BodyHistory) : BodyUi
}

private data class MetricSpec(
    val title: String,
    val unit: String,
    val points: List<HealthConnectBody.Point>,
    val color: Color,
    val decimals: Int,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BodyGraphScreen(bottomBar: @Composable () -> Unit) {
    val context = LocalContext.current
    var periodDays by remember { mutableLongStateOf(365L) }
    var refresh by remember { mutableIntStateOf(0) }

    val permLauncher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted ->
        if (granted.containsAll(HealthConnectBody.PERMISSIONS)) refresh++
    }

    val ui by produceState<BodyUi>(BodyUi.Loading, periodDays, refresh) {
        value = BodyUi.Loading
        value = withContext(Dispatchers.IO) {
            when (HealthConnectBody.availability(context)) {
                1 -> BodyUi.Unsupported
                2 -> BodyUi.NeedInstall
                else -> {
                    val granted = runCatching { HealthConnectBody.hasPermissions(context) }.getOrDefault(false)
                    if (!granted) {
                        BodyUi.NeedPermission
                    } else {
                        val h = runCatching { HealthConnectBody.readHistory(context, periodDays) }.getOrNull()
                        when {
                            h == null -> BodyUi.NeedPermission
                            h.isEmpty -> BodyUi.Empty
                            else -> BodyUi.Data(h)
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("몸 상태 그래프") },
                actions = {
                    Text(
                        text = "v${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(end = 16.dp),
                    )
                },
            )
        },
        bottomBar = bottomBar,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
                start = 16.dp,
                end = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    listOf(
                        90L to "3개월", 365L to "1년", 1095L to "3년", 3650L to "10년", 36500L to "전체",
                    ).forEach { (days, label) ->
                        FilterChip(
                            selected = periodDays == days,
                            onClick = { periodDays = days },
                            label = { Text(label) },
                        )
                    }
                }
            }

            when (val state = ui) {
                BodyUi.Loading -> item {
                    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center) {
                        CircularProgressIndicator()
                    }
                }
                BodyUi.Unsupported -> item {
                    InfoCard("이 기기는 헬스커넥트를 지원하지 않아 몸 상태 그래프를 표시할 수 없어요.")
                }
                BodyUi.NeedInstall -> item {
                    InfoCard("헬스커넥트 앱 설치/업데이트가 필요해요. (Play 스토어에서 'Health Connect')")
                }
                BodyUi.NeedPermission -> item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "가민·삼성헬스·InBody가 헬스커넥트로 보낸 몸 상태를 그래프로 보려면 읽기 권한이 필요해요.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { permLauncher.launch(HealthConnectBody.PERMISSIONS) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("헬스커넥트 권한 허용") }
                        }
                    }
                }
                BodyUi.Empty -> item {
                    InfoCard("최근 데이터가 없어요. 가민·삼성헬스·InBody가 헬스커넥트로 몸무게/체지방 등을 동기화했는지 확인해 주세요.")
                }
                is BodyUi.Data -> {
                    val h = state.history
                    val metrics = listOfNotNull(
                        h.weight.takeIf { it.isNotEmpty() }
                            ?.let { MetricSpec("체중", "kg", it, Color(0xFF1565C0), 1) },
                        h.bodyFat.takeIf { it.isNotEmpty() }
                            ?.let { MetricSpec("체지방률", "%", it, Color(0xFFEF6C00), 1) },
                        h.lean.takeIf { it.isNotEmpty() }
                            ?.let { MetricSpec("제지방량", "kg", it, Color(0xFF2E7D32), 1) },
                        h.bmr.takeIf { it.isNotEmpty() }
                            ?.let { MetricSpec("기초대사량", "kcal", it, Color(0xFF6A1B9A), 0) },
                    )
                    items(metrics.size) { idx -> MetricCard(metrics[idx]) }
                }
            }
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(text, modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun MetricCard(spec: MetricSpec) {
    val zone = ZoneId.systemDefault()
    val latest = spec.points.last()
    val values = spec.points.map { it.value }
    val minV = values.min()
    val maxV = values.max()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    spec.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "%.${spec.decimals}f${spec.unit}".format(latest.value),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = spec.color,
                )
            }
            Text(
                "최저 %.${spec.decimals}f · 최고 %.${spec.decimals}f · ${spec.points.size}회".format(minV, maxV),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            LineChart(
                points = spec.points,
                color = spec.color,
                modifier = Modifier.fillMaxWidth().height(140.dp),
            )
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    Instant.ofEpochMilli(spec.points.first().timeMs).atZone(zone).toLocalDate().format(axisFormatter),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    Instant.ofEpochMilli(latest.timeMs).atZone(zone).toLocalDate().format(axisFormatter),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 지도 SDK 없이 Canvas로 그리는 단순 선 그래프 */
@Composable
private fun LineChart(points: List<HealthConnectBody.Point>, color: Color, modifier: Modifier) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier = modifier) {
        if (points.isEmpty()) return@Canvas
        val pad = 8.dp.toPx()
        val w = size.width - 2 * pad
        val h = size.height - 2 * pad

        val minT = points.first().timeMs
        val maxT = points.last().timeMs
        val tRange = (maxT - minT).coerceAtLeast(1L).toDouble()
        val minV = points.minOf { it.value }
        val maxV = points.maxOf { it.value }
        val vRange = (maxV - minV).takeIf { it > 0.0 } ?: 1.0

        fun x(t: Long) = pad + ((t - minT) / tRange * w).toFloat()
        fun y(v: Double) = pad + ((maxV - v) / vRange * h).toFloat()

        // 위·아래 기준선
        drawLine(gridColor, Offset(pad, pad), Offset(pad + w, pad), strokeWidth = 1f)
        drawLine(gridColor, Offset(pad, pad + h), Offset(pad + w, pad + h), strokeWidth = 1f)

        if (points.size == 1) {
            drawCircle(color, radius = 4.dp.toPx(), center = Offset(pad + w / 2, pad + h / 2))
            return@Canvas
        }

        val path = Path()
        points.forEachIndexed { i, p ->
            val o = Offset(x(p.timeMs), y(p.value))
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        drawPath(
            path = path,
            color = color,
            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        points.forEach { p ->
            drawCircle(color, radius = 2.5.dp.toPx(), center = Offset(x(p.timeMs), y(p.value)))
        }
    }
}
