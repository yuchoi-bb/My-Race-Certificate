package com.yuchoi.racecert.strava

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.yuchoi.racecert.MainActivity
import kotlinx.coroutines.launch

/**
 * Strava 로그인 후 racecert://localhost?code=... 로 돌아오는 딥링크를 받아,
 * 코드를 토큰으로 교환하고 앱으로 복귀한다. (UI 없는 투명 진입점)
 */
class StravaRedirectActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        val code = data?.getQueryParameter("code")
        val error = data?.getQueryParameter("error")

        if (code.isNullOrBlank() || error != null) {
            Toast.makeText(
                applicationContext,
                "Strava 연결이 취소됐어요 (${error ?: "코드 없음"}).",
                Toast.LENGTH_LONG,
            ).show()
            backToApp()
            return
        }

        lifecycleScope.launch {
            val ok = StravaAuth.exchangeCode(applicationContext, code)
            Toast.makeText(
                applicationContext,
                if (ok) "✅ Strava 연결 완료" else "Strava 토큰 교환 실패 — Client Secret 설정을 확인해 주세요.",
                Toast.LENGTH_LONG,
            ).show()
            backToApp()
        }
    }

    private fun backToApp() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
