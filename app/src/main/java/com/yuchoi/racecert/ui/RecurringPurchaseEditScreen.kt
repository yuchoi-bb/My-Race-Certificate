package com.yuchoi.racecert.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.yuchoi.racecert.data.PurchaseCategoryStore
import com.yuchoi.racecert.data.RecurringPurchase
import com.yuchoi.racecert.data.RecurringPurchaseStore
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val recurEditDateFormatter = DateTimeFormatter.ofPattern("yy.MM.dd")

private enum class EndMode { UNLIMITED, END_DATE, COUNT }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringPurchaseEditScreen(
    recurringId: String?,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val existing = remember(recurringId) { recurringId?.let { RecurringPurchaseStore.find(it) } }

    var label by remember { mutableStateOf(existing?.label ?: "") }
    var category by remember { mutableStateOf(existing?.category ?: PurchaseCategoryStore.DEFAULT_CATEGORY) }
    var amountText by remember { mutableStateOf(existing?.amount?.takeIf { it > 0 }?.toString() ?: "") }
    var dayOfMonthText by remember { mutableStateOf((existing?.dayOfMonth ?: 1).toString()) }
    var startDate by remember { mutableStateOf(existing?.startDate ?: LocalDate.now()) }
    var endMode by remember {
        mutableStateOf(
            when {
                existing?.endDate != null -> EndMode.END_DATE
                existing?.repeatCount != null -> EndMode.COUNT
                else -> EndMode.UNLIMITED
            },
        )
    }
    var endDate by remember { mutableStateOf(existing?.endDate ?: startDate.plusYears(1)) }
    var repeatCountText by remember { mutableStateOf(existing?.repeatCount?.toString() ?: "12") }
    var memo by remember { mutableStateOf(existing?.memo ?: "") }
    var active by remember { mutableStateOf(existing?.active ?: true) }

    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    fun doSave() {
        val amount = amountText.filter { it.isDigit() }.toLongOrNull() ?: 0L
        if (label.isBlank()) {
            Toast.makeText(context, "이름을 입력해 주세요. (예: OO러닝클럽 회비)", Toast.LENGTH_SHORT).show()
            return
        }
        if (amount <= 0) {
            Toast.makeText(context, "금액을 입력해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val dayOfMonth = (dayOfMonthText.toIntOrNull() ?: 1).coerceIn(1, 28)
        val r = RecurringPurchase(
            id = existing?.id ?: RecurringPurchaseStore.newId(),
            label = label.trim(),
            category = category,
            amount = amount,
            dayOfMonth = dayOfMonth,
            startDateEpochDay = startDate.toEpochDay(),
            endDateEpochDay = if (endMode == EndMode.END_DATE) endDate.toEpochDay() else null,
            repeatCount = if (endMode == EndMode.COUNT) repeatCountText.toIntOrNull()?.coerceAtLeast(1) else null,
            memo = memo.trim(),
            active = active,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )
        RecurringPurchaseStore.upsert(r)
        onDone()
    }

    val dirty = if (existing == null) {
        label.isNotBlank() || amountText.isNotBlank() || memo.isNotBlank()
    } else {
        label.trim() != existing.label || category != existing.category ||
            (amountText.filter { it.isDigit() }.toLongOrNull() ?: 0L) != existing.amount ||
            (dayOfMonthText.toIntOrNull() ?: 1) != existing.dayOfMonth ||
            startDate != existing.startDate || memo.trim() != existing.memo || active != existing.active ||
            (endMode == EndMode.END_DATE && endDate != existing.endDate) ||
            (endMode == EndMode.COUNT && repeatCountText.toIntOrNull() != existing.repeatCount) ||
            (endMode == EndMode.UNLIMITED && existing.endDate != null) ||
            (endMode == EndMode.UNLIMITED && existing.repeatCount != null)
    }

    fun attemptBack() {
        if (dirty) showDiscardDialog = true else onCancel()
    }

    BackHandler(enabled = true) { attemptBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "정기 지출 추가" else "정기 지출 수정") },
                navigationIcon = {
                    IconButton(onClick = { attemptBack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "뒤로")
                    }
                },
                actions = {
                    if (existing != null) {
                        IconButton(onClick = { showDeleteConfirm = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "삭제")
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("이름 (예: OO러닝클럽 회비, 코칭 강습료)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            CategoryPicker(selected = category, onSelect = { category = it })
            Spacer(Modifier.height(12.dp))

            Row {
                OutlinedTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter(Char::isDigit) },
                    label = { Text("월 금액 (원)") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    value = dayOfMonthText,
                    onValueChange = { dayOfMonthText = it.filter(Char::isDigit).take(2) },
                    label = { Text("매월 며칠") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = { showStartDatePicker = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("시작일: ${startDate.format(recurEditDateFormatter)}")
            }
            Spacer(Modifier.height(12.dp))

            Text("종료 방식", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = endMode == EndMode.UNLIMITED, onClick = { endMode = EndMode.UNLIMITED }, label = { Text("무제한") })
                FilterChip(selected = endMode == EndMode.END_DATE, onClick = { endMode = EndMode.END_DATE }, label = { Text("종료일 지정") })
                FilterChip(selected = endMode == EndMode.COUNT, onClick = { endMode = EndMode.COUNT }, label = { Text("횟수 지정") })
            }
            Spacer(Modifier.height(8.dp))
            when (endMode) {
                EndMode.END_DATE -> OutlinedButton(
                    onClick = { showEndDatePicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.CalendarMonth, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("종료일: ${endDate.format(recurEditDateFormatter)}")
                }
                EndMode.COUNT -> OutlinedTextField(
                    value = repeatCountText,
                    onValueChange = { repeatCountText = it.filter(Char::isDigit).take(3) },
                    label = { Text("총 반복 횟수 (회)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                EndMode.UNLIMITED -> Text(
                    "종료일 없이 계속 반복돼요. 그만두게 되면 '일시중지'로 꺼두면 됩니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("활성", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "꺼두면 새로운 회차를 만들지 않아요 (지난 내역은 유지)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = active, onCheckedChange = { active = it })
            }
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = memo,
                onValueChange = { memo = it },
                label = { Text("메모") },
                modifier = Modifier.fillMaxWidth().height(100.dp),
            )
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { doSave() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("저장") }
        }
    }

    if (showStartDatePicker) {
        val state = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = startDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showStartDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        startDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showStartDatePicker = false
                }) { Text("확인") }
            },
            dismissButton = {
                TextButton(onClick = { showStartDatePicker = false }) { Text("취소") }
            },
        ) {
            DatePicker(state = state)
        }
    }

    if (showEndDatePicker) {
        val state = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = endDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showEndDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        endDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showEndDatePicker = false
                }) { Text("확인") }
            },
            dismissButton = {
                TextButton(onClick = { showEndDatePicker = false }) { Text("취소") }
            },
        ) {
            DatePicker(state = state)
        }
    }

    if (showDeleteConfirm && existing != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("정기 지출 삭제") },
            text = { Text("이 정기 지출 템플릿을 삭제할까요? 이미 생성된 지난 구매 내역은 그대로 남아요.") },
            confirmButton = {
                TextButton(onClick = {
                    RecurringPurchaseStore.delete(existing.id)
                    showDeleteConfirm = false
                    onDone()
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("취소") }
            },
        )
    }

    if (showDiscardDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDialog = false },
            title = { Text("저장하지 않고 나갈까요?") },
            text = { Text("입력한 내용이 저장되지 않았어요.") },
            confirmButton = {
                TextButton(onClick = { showDiscardDialog = false; doSave() }) { Text("저장하고 나가기") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDialog = false; onCancel() }) { Text("저장 안 함") }
            },
        )
    }
}
