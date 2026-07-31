package com.yuchoi.racecert.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

/**
 * 구매·정기 지출 항목(카테고리) 선택기. "편집"을 누르면 항목마다 삭제 버튼이 나타나고,
 * "추가" 칩으로 새 항목을 만들 수 있다. PurchaseEditScreen과 RecurringPurchaseEditScreen이 공유한다.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CategoryPicker(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val categories = PurchaseCategoryStore.categories.toList()
    var editMode by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var newCategoryText by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "항목",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { editMode = !editMode }) {
                Text(if (editMode) "완료" else "편집")
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            categories.forEach { cat ->
                FilterChip(
                    selected = selected == cat,
                    onClick = { onSelect(cat) },
                    label = { Text(cat) },
                    trailingIcon = if (editMode && cat != PurchaseCategoryStore.DEFAULT_CATEGORY) {
                        {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "'$cat' 삭제",
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { pendingDelete = cat },
                            )
                        }
                    } else {
                        null
                    },
                )
            }
            FilterChip(
                selected = false,
                onClick = { showAddDialog = true },
                label = { Text("추가") },
                leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp)) },
            )
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false; newCategoryText = "" },
            title = { Text("항목 추가") },
            text = {
                OutlinedTextField(
                    value = newCategoryText,
                    onValueChange = { newCategoryText = it },
                    label = { Text("항목 이름") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (PurchaseCategoryStore.add(newCategoryText)) {
                        onSelect(newCategoryText.trim())
                        showAddDialog = false
                        newCategoryText = ""
                    } else {
                        Toast.makeText(context, "이미 있거나 빈 이름이에요.", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("추가") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false; newCategoryText = "" }) { Text("취소") }
            },
        )
    }

    pendingDelete?.let { cat ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("항목 삭제") },
            text = {
                Text(
                    "'$cat' 항목을 삭제할까요? 이 항목을 쓰던 구매·정기 지출은 " +
                        "'${PurchaseCategoryStore.DEFAULT_CATEGORY}'로 옮겨져요.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    PurchaseCategoryStore.delete(cat)
                    if (selected == cat) onSelect(PurchaseCategoryStore.DEFAULT_CATEGORY)
                    pendingDelete = null
                }) { Text("삭제") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("취소") }
            },
        )
    }
}
