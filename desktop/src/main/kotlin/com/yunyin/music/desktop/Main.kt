package com.yunyin.music.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

/**
 * 电脑版入口。
 *
 * 第一阶段只做骨架：窗口、主题、空状态。播放、歌词、网络各自单独接入，
 * 每一块都先在 Android 端把逻辑抽成纯 Kotlin（无 android.* 依赖）再共享过来。
 */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "云音") {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                EmptyState()
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("云音 · 桌面版", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text("骨架已就绪，正在接入歌词与播放", style = MaterialTheme.typography.bodyMedium)
        }
    }
}