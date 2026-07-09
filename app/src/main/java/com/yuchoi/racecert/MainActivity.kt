package com.yuchoi.racecert

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.yuchoi.racecert.data.RecordStore
import com.yuchoi.racecert.ui.AddEditRecordScreen
import com.yuchoi.racecert.ui.RecordDetailScreen
import com.yuchoi.racecert.ui.RecordListScreen
import com.yuchoi.racecert.ui.ShareImportScreen
import com.yuchoi.racecert.ui.SummaryScreen
import com.yuchoi.racecert.update.UpdateChecker
import com.yuchoi.racecert.update.UpdateInfo
import com.yuchoi.racecert.update.UpdateInstaller
import kotlinx.coroutines.launch

private sealed interface Screen {
    data object List : Screen
    data class Detail(val recordId: String) : Screen
    data class AddEdit(val recordId: String?) : Screen
    data class ShareImport(val uris: kotlin.collections.List<Uri>) : Screen
}

class MainActivity : ComponentActivity() {

    private lateinit var updateInstaller: UpdateInstaller

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RecordStore.init(applicationContext)
        // Google 계정이 연결돼 있으면 시작 시 Drive와 자동 동기화
        com.yuchoi.racecert.sync.DriveSync.requestSync(applicationContext)
        updateInstaller = UpdateInstaller(this)
        updateInstaller.register()

        val sharedImages = extractSharedImages(intent)

        setContent {
            MaterialTheme {
                App(
                    initialSharedImages = sharedImages,
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

    @Suppress("DEPRECATION")
    private fun extractSharedImages(intent: Intent?): List<Uri> = when (intent?.action) {
        Intent.ACTION_SEND -> {
            val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            if (uri != null && intent.type?.startsWith("image/") == true) listOf(uri) else emptyList()
        }
        Intent.ACTION_SEND_MULTIPLE -> {
            if (intent.type?.startsWith("image/") == true) {
                intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty().filterNotNull()
            } else {
                emptyList()
            }
        }
        else -> emptyList()
    }
}

@Composable
private fun App(
    onStartUpdate: (UpdateInfo) -> Unit,
    initialSharedImages: List<Uri> = emptyList(),
) {
    var screen by remember {
        mutableStateOf<Screen>(
            if (initialSharedImages.isNotEmpty()) Screen.ShareImport(initialSharedImages)
            else Screen.List,
        )
    }

    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }
    var checkResultMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun checkForUpdate(manual: Boolean) {
        val latest = UpdateChecker.fetchLatest()
        if (latest != null && latest.versionCode > BuildConfig.VERSION_CODE) {
            updateInfo = latest
            showUpdateDialog = true
        } else if (manual) {
            checkResultMessage =
                if (latest == null) "업데이트 정보를 가져오지 못했어요. 네트워크를 확인해 주세요."
                else "지금 최신 버전(v${BuildConfig.VERSION_NAME})을 사용 중입니다."
        }
    }

    LaunchedEffect(Unit) { checkForUpdate(manual = false) }

    // 홈 하단 탭: 0 = 기록 목록, 1 = 요약·PB
    var homeTab by remember { mutableStateOf(0) }
    // 저장/수정 직후 목록에서 스크롤·강조할 기록 id
    var scrollToId by remember { mutableStateOf<String?>(null) }

    when (val current = screen) {
        is Screen.List -> {
            val bottomBar: @Composable () -> Unit = {
                NavigationBar {
                    NavigationBarItem(
                        selected = homeTab == 0,
                        onClick = { homeTab = 0 },
                        icon = { Icon(Icons.Filled.EmojiEvents, contentDescription = null) },
                        label = { Text("기록") },
                    )
                    NavigationBarItem(
                        selected = homeTab == 1,
                        onClick = { homeTab = 1 },
                        icon = { Icon(Icons.Filled.BarChart, contentDescription = null) },
                        label = { Text("요약 · PB") },
                    )
                }
            }
            if (homeTab == 0) {
                RecordListScreen(
                    onAddRecord = { screen = Screen.AddEdit(null) },
                    onOpenRecord = { screen = Screen.Detail(it) },
                    onCheckUpdate = { scope.launch { checkForUpdate(manual = true) } },
                    bottomBar = bottomBar,
                    scrollToId = scrollToId,
                    onScrolled = { scrollToId = null },
                )
            } else {
                BackHandler { homeTab = 0 }
                SummaryScreen(
                    bottomBar = bottomBar,
                    onOpenRecord = { screen = Screen.Detail(it) },
                )
            }
        }

        is Screen.Detail -> {
            BackHandler { screen = Screen.List }
            RecordDetailScreen(
                recordId = current.recordId,
                onBack = { screen = Screen.List },
                onEdit = { screen = Screen.AddEdit(current.recordId) },
                onDeleted = { screen = Screen.List },
            )
        }

        is Screen.AddEdit -> {
            // 뒤로가기(시스템/상단)는 AddEditRecordScreen 내부에서 미저장 확인 후 처리
            AddEditRecordScreen(
                recordId = current.recordId,
                onDone = { savedId ->
                    scrollToId = savedId
                    homeTab = 0
                    screen = Screen.List
                },
                onCancel = { screen = Screen.List },
            )
        }

        is Screen.ShareImport -> {
            BackHandler { screen = Screen.List }
            ShareImportScreen(
                sharedUris = current.uris,
                onDone = { screen = Screen.List },
                onOpenRecord = { screen = Screen.Detail(it) },
                onOpenEdit = { screen = Screen.AddEdit(it) },
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
