package com.yuchoi.racecert.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yuchoi.racecert.BuildConfig
import com.yuchoi.racecert.data.Purchase
import com.yuchoi.racecert.data.PurchaseCategoryStore
import com.yuchoi.racecert.data.PurchaseStore
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val purchaseDateFormatter = DateTimeFormatter.ofPattern("yy.MM.dd")

private val colDate = 56.dp
private val colCategory = 68.dp
private val colItem = 120.dp
private val colAmount = 88.dp
private val colMemo = 140.dp
private val tableWidth = colDate + colCategory + colItem + colAmount + colMemo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseListScreen(
    bottomBar: @Composable () -> Unit,
    onAddPurchase: () -> Unit,
    onOpenPurchase: (String) -> Unit,
    onOpenRecurring: () -> Unit,
    onOpenSummary: () -> Unit,
) {
    val purchases = PurchaseStore.purchases.toList()
    val sorted = remember(purchases) { purchases.sortedByDescending { it.dateEpochDay } }
    val total = purchases.sumOf { it.amount }
    val thisYear = LocalDate.now().year
    val thisYearTotal = purchases.filter { it.date.year == thisYear }.sumOf { it.amount }
    val byCategory = remember(purchases) {
        purchases.groupBy { it.category }.mapValues { (_, list) -> list.sumOf { it.amount } }
    }
    val storedCategories = PurchaseCategoryStore.categories.toList()
    val categoryOrder = remember(storedCategories, byCategory) {
        storedCategories + byCategory.keys.filter { it !in storedCategories }
    }
    var summaryExpanded by remember { mutableStateOf(false) }
    val hScroll = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("구매 내역") },
                actions = {
                    IconButton(onClick = onOpenRecurring) {
                        Icon(Icons.Filled.Repeat, contentDescription = "정기 지출")
                    }
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            // 전체 지출·올해 지출 요약은 기본적으로 접어둔다. 필요할 때만 눌러서 펼친다.
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clickable { summaryExpanded = !summaryExpanded },
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "지출 요약",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(
                            if (summaryExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (summaryExpanded) "접기" else "펼치기",
                        )
                    }
                    if (summaryExpanded) {
                        Spacer(Modifier.height(8.dp))
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
                                categoryOrder
                                    .mapNotNull { c -> byCategory[c]?.let { "$c ${"%,d".format(it)}원" } }
                                    .joinToString("  ·  "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            TextButton(onClick = onOpenSummary) { Text("연도별 자세히 보기") }
                        }
                    }
                }
            }

            if (sorted.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Text(
                        "아직 등록된 구매 내역이 없어요.\n오른쪽 아래 '구매 추가'로 첫 지출을 기록해 보세요.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                // 날짜·분류·품목·금액·비고 순서의 표. 화면보다 넓으면 좌우로 스크롤한다.
                Box(
                    modifier = Modifier
                        .horizontalScroll(hScroll)
                        .padding(horizontal = 12.dp),
                ) {
                    TableHeaderRow()
                }
                HorizontalDivider()
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(hScroll)
                        .padding(horizontal = 12.dp),
                ) {
                    LazyColumn(
                        modifier = Modifier
                            .width(tableWidth)
                            .fillMaxHeight(),
                        contentPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding() + 96.dp),
                    ) {
                        itemsIndexed(sorted, key = { _, p -> p.id }) { index, p ->
                            PurchaseTableRow(p, striped = index % 2 == 1, onClick = { onOpenPurchase(p.id) })
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderCell(text: String, width: Dp) {
    Box(modifier = Modifier.width(width).padding(horizontal = 8.dp, vertical = 10.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TableHeaderRow() {
    Row(modifier = Modifier.width(tableWidth)) {
        HeaderCell("날짜", colDate)
        HeaderCell("분류", colCategory)
        HeaderCell("품목", colItem)
        HeaderCell("금액", colAmount)
        HeaderCell("비고", colMemo)
    }
}

@Composable
private fun PurchaseTableRow(p: Purchase, striped: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .width(tableWidth)
            .background(
                if (striped) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface,
            )
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            p.date.format(purchaseDateFormatter),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(colDate).padding(horizontal = 8.dp, vertical = 10.dp),
        )
        Text(
            p.category,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(colCategory).padding(horizontal = 8.dp, vertical = 10.dp),
        )
        Text(
            p.vendor.ifBlank { "-" },
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(colItem).padding(horizontal = 8.dp, vertical = 10.dp),
        )
        Text(
            "${"%,d".format(p.amount)}원",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(colAmount).padding(horizontal = 8.dp, vertical = 10.dp),
        )
        Text(
            p.memo.ifBlank { "-" },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(colMemo).padding(horizontal = 8.dp, vertical = 10.dp),
        )
    }
}
