package com.yuchoi.racecert.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yuchoi.racecert.data.PurchaseCategoryStore
import com.yuchoi.racecert.data.PurchaseStore

private data class YearSummary(
    val year: Int,
    val total: Long,
    val byCategory: List<Pair<String, Long>>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseYearlySummaryScreen(onBack: () -> Unit) {
    val purchases = PurchaseStore.purchases.toList()
    val categoryOrder = PurchaseCategoryStore.categories.toList()

    val grandTotal = purchases.sumOf { it.amount }
    val grandByCategory = remember(purchases) {
        val sums = purchases.groupBy { it.category }.mapValues { (_, list) -> list.sumOf { it.amount } }
        (categoryOrder + sums.keys.filter { it !in categoryOrder })
            .mapNotNull { c -> sums[c]?.let { c to it } }
            .sortedByDescending { it.second }
    }

    val yearSummaries = remember(purchases) {
        purchases.groupBy { it.date.year }
            .map { (year, list) ->
                val sums = list.groupBy { it.category }.mapValues { (_, l) -> l.sumOf { it.amount } }
                val ordered = (categoryOrder + sums.keys.filter { it !in categoryOrder })
                    .mapNotNull { c -> sums[c]?.let { c to it } }
                    .sortedByDescending { it.second }
                YearSummary(year = year, total = list.sumOf { it.amount }, byCategory = ordered)
            }
            .sortedByDescending { it.year }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("연도별 지출 상세") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        },
    ) { innerPadding ->
        if (purchases.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp)) {
                Text(
                    "아직 등록된 구매 내역이 없어요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    top = innerPadding.calculateTopPadding() + 8.dp,
                    bottom = innerPadding.calculateBottomPadding() + 24.dp,
                    start = 16.dp,
                    end = 16.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    YearSummaryCard(
                        title = "전체 기간",
                        total = grandTotal,
                        byCategory = grandByCategory,
                        emphasized = true,
                    )
                }
                items(yearSummaries, key = { it.year }) { y ->
                    YearSummaryCard(
                        title = "${y.year}년",
                        total = y.total,
                        byCategory = y.byCategory,
                        emphasized = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun YearSummaryCard(
    title: String,
    total: Long,
    byCategory: List<Pair<String, Long>>,
    emphasized: Boolean,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = if (emphasized) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${"%,d".format(total)}원",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (byCategory.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                byCategory.forEach { (category, amount) ->
                    val fraction = if (total > 0) (amount.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                    Column(modifier = Modifier.padding(bottom = 8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                category,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                "${"%,d".format(amount)}원",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(fraction)
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                }
            }
        }
    }
}
