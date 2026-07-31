package com.yuchoi.racecert.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image as ForegroundImage
import com.yuchoi.racecert.data.ImageCompressor
import com.yuchoi.racecert.data.Purchase
import com.yuchoi.racecert.data.PurchaseCategory
import com.yuchoi.racecert.data.PurchaseStore
import com.yuchoi.racecert.data.RecordStore
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val editDateFormatter = DateTimeFormatter.ofPattern("yy.MM.dd")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PurchaseEditScreen(
    purchaseId: String?,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val existing = remember(purchaseId) { purchaseId?.let { PurchaseStore.find(it) } }

    var date by remember { mutableStateOf(existing?.date ?: LocalDate.now()) }
    var category by remember { mutableStateOf(existing?.category ?: PurchaseCategory.OTHER) }
    var amountText by remember { mutableStateOf(existing?.amount?.takeIf { it > 0 }?.toString() ?: "") }
    var vendor by remember { mutableStateOf(existing?.vendor ?: "") }
    var memo by remember { mutableStateOf(existing?.memo ?: "") }
    var receiptPath by remember { mutableStateOf(existing?.receiptPath ?: "") }
    var linkedRecordId by remember { mutableStateOf(existing?.linkedRecordId ?: "") }
    var receiptLoading by remember { mutableStateOf(false) }

    var showDatePicker by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDiscardDialog by remember { mutableStateOf(false) }
    var linkExpanded by remember { mutableStateOf(false) }

    val records = remember { RecordStore.records.sortedByDescending { it.dateEpochDay } }
    val linkedRecord = remember(linkedRecordId) { records.firstOrNull { it.id == linkedRecordId } }

    val receiptPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            receiptLoading = true
            val paths = RecordStore.importImages(listOf(uri))
            val path = paths.firstOrNull()
            if (path != null) {
                ImageCompressor.compressInPlace(path)
                receiptPath = path
            } else {
                Toast.makeText(context, "영수증 사진을 가져오지 못했어요.", Toast.LENGTH_SHORT).show()
            }
            receiptLoading = false
        }
    }

    fun doSave() {
        val amount = amountText.filter { it.isDigit() }.toLongOrNull() ?: 0L
        if (amount <= 0) {
            Toast.makeText(context, "금액을 입력해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        val purchase = Purchase(
            id = existing?.id ?: PurchaseStore.newId(),
            dateEpochDay = date.toEpochDay(),
            category = category,
            amount = amount,
            vendor = vendor.trim(),
            memo = memo.trim(),
            receiptPath = receiptPath,
            linkedRecordId = linkedRecordId,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )
        PurchaseStore.upsert(purchase)
        com.yuchoi.racecert.sync.DriveSync.requestSync(context)
        onDone()
    }

    val dirty = if (existing == null) {
        amountText.isNotBlank() || vendor.isNotBlank() || memo.isNotBlank() ||
            receiptPath.isNotBlank() || linkedRecordId.isNotBlank() || category != PurchaseCategory.OTHER
    } else {
        date != existing.date || category != existing.category ||
            (amountText.filter { it.isDigit() }.toLongOrNull() ?: 0L) != existing.amount ||
            vendor.trim() != existing.vendor || memo.trim() != existing.memo ||
            receiptPath != existing.receiptPath || linkedRecordId != existing.linkedRecordId
    }

    fun attemptBack() {
        if (dirty) showDiscardDialog = true else onCancel()
    }

    BackHandler(enabled = true) { attemptBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "구매 추가" else "구매 수정") },
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
            OutlinedButton(
                onClick = { showDatePicker = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.CalendarMonth, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("날짜: ${date.format(editDateFormatter)}")
            }
            Spacer(Modifier.height(12.dp))

            Text("항목", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PurchaseCategory.entries.take(3).forEach { c ->
                    FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c.label) })
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PurchaseCategory.entries.drop(3).forEach { c ->
                    FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c.label) })
                }
            }
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = amountText,
                onValueChange = { amountText = it.filter(Char::isDigit) },
                label = { Text("금액 (원)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = vendor,
                onValueChange = { vendor = it },
                label = { Text("구입처 (예: 나이키, 대회 접수처)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))

            ExposedDropdownMenuBox(
                expanded = linkExpanded,
                onExpandedChange = { linkExpanded = it },
            ) {
                OutlinedTextField(
                    value = linkedRecord?.let { "${it.title.ifBlank { "(제목 없음)" }} · ${it.date.format(editDateFormatter)}" }
                        ?: "연결 안 함",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("연결된 대회 (선택)") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = linkExpanded) },
                    modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                )
                androidx.compose.material3.ExposedDropdownMenu(
                    expanded = linkExpanded,
                    onDismissRequest = { linkExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("연결 안 함") },
                        onClick = { linkedRecordId = ""; linkExpanded = false },
                    )
                    records.forEach { r ->
                        DropdownMenuItem(
                            text = { Text("${r.title.ifBlank { "(제목 없음)" }} · ${r.date.format(editDateFormatter)}") },
                            onClick = { linkedRecordId = r.id; linkExpanded = false },
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = memo,
                onValueChange = { memo = it },
                label = { Text("메모") },
                modifier = Modifier.fillMaxWidth().height(100.dp),
            )
            Spacer(Modifier.height(16.dp))

            Text("영수증 사진 (선택)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            if (receiptPath.isNotBlank()) {
                Box(modifier = Modifier.size(120.dp)) {
                    val bmp = rememberSampledBitmap(receiptPath, reqSizePx = 480)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp))
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
                    IconButton(
                        onClick = { receiptPath = "" },
                        modifier = Modifier.align(Alignment.TopEnd).size(28.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "삭제",
                                tint = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(
                onClick = { receiptPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                enabled = !receiptLoading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (receiptPath.isBlank()) "영수증 사진 추가" else "영수증 사진 변경")
            }
            Spacer(Modifier.height(24.dp))

            Button(
                onClick = { doSave() },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("저장") }
        }
    }

    if (showDatePicker) {
        val state = androidx.compose.material3.rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                    }
                    showDatePicker = false
                }) { Text("확인") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("취소") }
            },
        ) {
            DatePicker(state = state)
        }
    }

    if (showDeleteConfirm && existing != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("구매 내역 삭제") },
            text = { Text("이 구매 내역을 삭제할까요? 되돌릴 수 없어요.") },
            confirmButton = {
                TextButton(onClick = {
                    PurchaseStore.delete(existing.id)
                    com.yuchoi.racecert.sync.DriveSync.requestSync(context)
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
