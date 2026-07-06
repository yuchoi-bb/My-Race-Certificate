package com.yuchoi.racecert

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.yuchoi.racecert.update.UpdateChecker
import com.yuchoi.racecert.update.UpdateInfo
import com.yuchoi.racecert.update.UpdateInstaller
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var updateInstaller: UpdateInstaller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        updateInstaller = UpdateInstaller(this)
        updateInstaller.register()

        setContent {
            MaterialTheme {
                HomeScreen(
                    onStartUpdate = { info ->
                        updateInstaller.startDownload(info)
                        Toast.makeText(
                            this,
                            "다운로드를 시작했어요. 완료되면 설치 화면이 열립니다.",
                            Toast.LENGTH_LONG,
                        ).show()
                    },
                )
            }
        }
    }

    override fun onDestroy() {
        updateInstaller.unregister()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onStartUpdate: (UpdateInfo) -> Unit) {
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var checkResultMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun checkForUpdate(manual: Boolean) {
        checking = true
        val latest = UpdateChecker.fetchLatest()
        checking = false
        if (latest != null && latest.versionCode > BuildConfig.VERSION_CODE) {
            updateInfo = latest
            showUpdateDialog = true
        } else if (manual) {
            checkResultMessage =
                if (latest == null) "업데이트 정보를 가져오지 못했어요. 네트워크를 확인해 주세요."
                else "지금 최신 버전을 사용 중입니다."
        }
    }

    // 앱 실행 시 자동 업데이트 확인
    LaunchedEffect(Unit) { checkForUpdate(manual = false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("나의 대회 기록증") }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.EmojiEvents,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "나의 대회 기록증",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "마라톤 · 철인3종 · 그란폰도\n대회 기록증과 나의 PB를 한곳에",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(32.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "준비 중인 기능",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "• 대회 기록 등록/수정/삭제\n" +
                            "• 기록증 사진 연결 (Google Photos)\n" +
                            "• 기록증 텍스트 자동 추출(OCR)\n" +
                            "• 개인 PB 관리\n" +
                            "• 대회 당일 날씨 자동 기입\n" +
                            "• 대회 소감 기록",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(32.dp))
            if (checking) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            } else {
                OutlinedButton(onClick = { scope.launch { checkForUpdate(manual = true) } }) {
                    Text("업데이트 확인")
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = "현재 버전 v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (showUpdateDialog) {
        updateInfo?.let { info ->
            AlertDialog(
                onDismissRequest = { showUpdateDialog = false },
                title = { Text("새 버전 v${info.versionName}") },
                text = {
                    Text(
                        if (info.releaseNotes.isBlank()) "새 버전이 준비되었습니다. 지금 업데이트할까요?"
                        else info.releaseNotes,
                    )
                },
                confirmButton = {
                    Button(onClick = {
                        showUpdateDialog = false
                        onStartUpdate(info)
                    }) { Text("지금 업데이트") }
                },
                dismissButton = {
                    TextButton(onClick = { showUpdateDialog = false }) { Text("나중에") }
                },
            )
        }
    }

    checkResultMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { checkResultMessage = null },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { checkResultMessage = null }) { Text("확인") }
            },
        )
    }
}
