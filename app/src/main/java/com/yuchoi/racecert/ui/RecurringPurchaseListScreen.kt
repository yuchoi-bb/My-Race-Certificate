package com.yuchoi.racecert.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.yuchoi.racecert.data.RecurringPurchase
import com.yuchoi.racecert.data.RecurringPurchaseStore
import java.time.format.DateTimeFormatter

private val recurringDateFormatter = DateTimeFormatter.ofPattern("yy.MM.dd")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringPurchaseListScreen(
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val items = RecurringPurchaseStore.items.toList().sortedByDescending { it.createdAt }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("정기 지출") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAdd,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("정기 지출 추가") },
            )
        },
    ) { innerPadding ->
        if (items.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(16.dp),
            ) {
                Text(
                    "동호회 회비·강습료처럼 매달 같은 날 나가는 지출을 등록해두면, 시작일부터 오늘까지 해당하는 만큼 구매 탭에 자동으로 채워져요.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
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
                items(items, key = { it.id }) { r -> RecurringCard(r, onClick = { onOpen(r.id) }) }
            }
        }
    }
}

@Composable
private fun RecurringCard(r: RecurringPurchase, onClick: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    r.label.ifBlank { "(이름 없음)" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (!r.active) {
                    AssistChip(onClick = onClick, label = { Text("일시중지") })
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "${"%,d".format(r.amount)}원 · 매월 ${r.dayOfMonth}일",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            val period = buildString {
                append(r.startDate.format(recurringDateFormatter))
                append(" ~ ")
                append(
                    when {
                        r.endDate != null -> r.endDate!!.format(recurringDateFormatter)
                        r.repeatCount != null -> "총 ${r.repeatCount}회"
                        else -> "무제한"
                    },
                )
            }
            Text(period, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
