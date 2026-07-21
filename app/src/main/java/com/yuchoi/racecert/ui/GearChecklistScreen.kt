package com.yuchoi.racecert.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.yuchoi.racecert.data.GearItem
import com.yuchoi.racecert.data.GearSections
import com.yuchoi.racecert.data.GearTemplateStore
import com.yuchoi.racecert.data.RaceRecord
import com.yuchoi.racecert.data.RaceType
import java.util.UUID

/**
 * 대회별 준비물 체크리스트. 종목 기본 템플릿으로 처음 채워지고, 체크하면 해당 섹션 맨 아래로 내려간다.
 * 새 항목을 추가하면 "기본 준비물에도 추가할지" → "어느 종목에 추가할지"를 순서대로 물어본다.
 */
@Composable
fun GearChecklistTab(record: RaceRecord, onUpdate: (RaceRecord) -> Unit) {
    val context = LocalContext.current

    // 처음 여는 기록이면 종목 기본 템플릿으로 초기화 (재초기화 방지 플래그)
    LaunchedEffect(record.id, record.gearInitialized) {
        if (!record.gearInitialized) {
            onUpdate(
                record.copy(
                    gearChecklist = GearTemplateStore.templateFor(record.type),
                    gearInitialized = true,
                ),
            )
        }
    }

    fun sync() = com.yuchoi.racecert.sync.DriveSync.requestSync(context)

    fun toggle(item: GearItem) {
        val updated = record.gearChecklist.map { if (it.id == item.id) it.copy(checked = !it.checked) else it }
        onUpdate(record.copy(gearChecklist = updated))
        sync()
    }

    fun removeItem(item: GearItem) {
        onUpdate(record.copy(gearChecklist = record.gearChecklist.filterNot { it.id == item.id }))
        sync()
    }

    // "기본 준비물에 추가?" → "어느 종목에?" 순서로 뜨는 다이얼로그 상태
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) } // section to label
    var showAskDefault by remember { mutableStateOf(false) }
    var showTypePicker by remember { mutableStateOf(false) }
    val selectedTypes = remember { mutableStateOf(setOf(record.type)) }

    fun addItem(section: String, rawLabel: String) {
        val label = rawLabel.trim()
        if (label.isBlank()) return
        if (record.gearChecklist.any { it.section == section && it.label.equals(label, ignoreCase = true) }) return
        val newItem = GearItem(id = UUID.randomUUID().toString(), section = section, label = label)
        onUpdate(record.copy(gearChecklist = record.gearChecklist + newItem))
        sync()
        if (!GearTemplateStore.existsInTemplate(record.type, section, label)) {
            pending = section to label
            selectedTypes.value = setOf(record.type)
            showAskDefault = true
        }
    }

    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        GearSections.ORDER.forEach { section ->
            GearSectionBlock(
                section = section,
                items = record.gearChecklist.filter { it.section == section }
                    .sortedBy { it.checked }, // 체크된 항목만 안정 정렬로 섹션 맨 아래로
                onToggle = ::toggle,
                onRemove = ::removeItem,
                onAdd = { label -> addItem(section, label) },
            )
            Spacer(Modifier.height(16.dp))
        }
    }

    if (showAskDefault && pending != null) {
        val (section, label) = pending!!
        AlertDialog(
            onDismissRequest = { showAskDefault = false; pending = null },
            title = { Text("새 항목이에요") },
            text = { Text("'$label'을(를) 앞으로 ${record.type.label} 대회를 추가할 때 기본 준비물로도 넣어둘까요?") },
            confirmButton = {
                TextButton(onClick = { showAskDefault = false; showTypePicker = true }) { Text("예") }
            },
            dismissButton = {
                TextButton(onClick = { showAskDefault = false; pending = null }) { Text("아니오") }
            },
        )
    }

    if (showTypePicker && pending != null) {
        val (section, label) = pending!!
        AlertDialog(
            onDismissRequest = { showTypePicker = false; pending = null },
            title = { Text("어느 종목에 추가할까요?") },
            text = {
                Column {
                    Text(
                        "'$label' 항목을 기본 준비물로 넣을 종목을 골라 주세요. (여러 개 선택 가능)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        RaceType.entries.take(3).forEach { t -> TypeChip(t, selectedTypes) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        RaceType.entries.drop(3).forEach { t -> TypeChip(t, selectedTypes) }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedTypes.value.isNotEmpty(),
                    onClick = {
                        GearTemplateStore.addToTemplates(selectedTypes.value, section, label)
                        showTypePicker = false
                        pending = null
                    },
                ) { Text("추가") }
            },
            dismissButton = {
                TextButton(onClick = { showTypePicker = false; pending = null }) { Text("취소") }
            },
        )
    }
}

@Composable
private fun TypeChip(type: RaceType, selected: androidx.compose.runtime.MutableState<Set<RaceType>>) {
    FilterChip(
        selected = type in selected.value,
        onClick = {
            selected.value = if (type in selected.value) selected.value - type else selected.value + type
        },
        label = { Text(type.label) },
    )
}

@Composable
private fun GearSectionBlock(
    section: String,
    items: List<GearItem>,
    onToggle: (GearItem) -> Unit,
    onRemove: (GearItem) -> Unit,
    onAdd: (String) -> Unit,
) {
    var newText by remember(section) { mutableStateOf("") }

    Text(section, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(4.dp))
    items.forEach { item ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = item.checked, onCheckedChange = { onToggle(item) })
            Text(
                item.label,
                style = MaterialTheme.typography.bodyMedium,
                textDecoration = if (item.checked) TextDecoration.LineThrough else null,
                color = if (item.checked) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { onRemove(item) }) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "삭제",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(0.dp),
                )
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = newText,
            onValueChange = { newText = it },
            placeholder = { Text("항목 추가") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onAdd(newText); newText = "" }) {
            Icon(Icons.Filled.Add, contentDescription = "추가")
        }
    }
}
