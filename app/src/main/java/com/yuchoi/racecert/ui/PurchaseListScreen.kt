package com.yuchoi.racecert.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image as ForegroundImage
import com.yuchoi.racecert.BuildConfig
import com.yuchoi.racecert.data.Purchase
import com.yuchoi.racecert.data.PurchaseCategory
import com.yuchoi.racecert.data.PurchaseStore
import com.yuchoi.racecert.data.RecordStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val purchaseDateFormatter = DateTimeFormatter.ofPattern("yy.MM.dd")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseListScreen(
    bottomBar: @Composable () -> Unit,
    onAddPurchase: () -> Unit,
    onOpenPurchase: (String) -> Unit,
) {
    val purchases = PurchaseStore.purchases.toList()
    val sorted = remember(purchases) { purchases.sortedByDescending { it.dateEpochDay } }
    val total = purchases.sumOf { it.amount }
    val thisYear = LocalDate.now().year
    val thisYearTotal = purchases.filter { it.date.year == thisYear }.sumOf { it.amount }
    val byCategory = remember(purchases) {
        purchases.groupBy { it.category }.mapValues { (_, list) -> list.sumOf { it.amount } }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("구매 내역") },
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
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddPurchase,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("구매 추가") },
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 8.dp,
                bottom = innerPadding.calculateBottomPadding() + 96.dp,
                start = 16.dp,
                end = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "전체 지출",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "${"%,d".format(total)}원",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "올해 지출",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    "${"%,d".format(thisYearTotal)}원",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                        if (byCategory.isNotEmpty()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                PurchaseCategory.entries
                                    .mapNotNull { c -> byCategory[c]?.let { "${c.label} ${"%,d".format(it)}원" } }
                                    .joinToString("  ·  "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (sorted.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "아직 등록된 구매 내역이 없어요.\n오른쪽 아래 '구매 추가'로 첫 지출을 기록해 보세요.",
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            } else {
                items(sorted, key = { it.id }) { p ->
                    PurchaseCard(p, onClick = { onOpenPurchase(p.id) })
                }
            }
        }
    }
}

@Composable
private fun PurchaseCard(p: Purchase, onClick: () -> Unit) {
    val linkedTitle = remember(p.linkedRecordId) {
        p.linkedRecordId.takeIf { it.isNotBlank() }?.let { RecordStore.find(it)?.title }
    }
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (p.receiptPath.isNotBlank()) {
                val bmp = rememberSampledBitmap(p.receiptPath, reqSizePx = 160)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    if (bmp != null) {
                        ForegroundImage(
                            bitmap = bmp,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(Icons.Filled.Receipt, contentDescription = null)
                    }
                }
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(onClick = onClick, label = { Text(p.category.label) })
                    Spacer(Modifier.width(8.dp))
                    Text(p.date.format(purchaseDateFormatter), style = MaterialTheme.typography.bodyMedium)
                }
                if (p.vendor.isNotBlank() || linkedTitle != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        listOfNotNull(
                            p.vendor.takeIf { it.isNotBlank() },
                            linkedTitle?.let { "🏅 $it" },
                        ).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                "${"%,d".format(p.amount)}원",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}
