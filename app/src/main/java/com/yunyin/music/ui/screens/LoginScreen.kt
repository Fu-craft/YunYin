package com.yunyin.music.ui.screens

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yunyin.music.core.Account
import com.yunyin.music.core.NetResult
import com.yunyin.music.data.ArtworkLoader
import com.yunyin.music.data.net.NeteaseClient
import com.yunyin.music.ui.components.Artwork
import com.yunyin.music.ui.components.WebLoginHint
import com.yunyin.music.ui.components.WebLoginPanel
import com.yunyin.music.ui.icons.SfIcons
import com.yunyin.music.ui.theme.AppleShapes
import com.yunyin.music.ui.theme.AppTheme
import com.yunyin.music.ui.theme.SFPro
import com.mocharealm.gaze.capsule.ContinuousRoundedRectangle
import kotlinx.coroutines.launch

/**
 * Account sheet.
 *
 * Sign-in happens inside the app: an embedded desktop browser (see [WebLoginPanel]) loads
 * music.163.com, the user logs in there however they like — QR code or phone — and the
 * resulting `MUSIC_U` cookie is captured automatically. Nothing is typed into our own UI.
 *
 * This replaces the previous QR / SMS / password routes. Those all called the API server's
 * own login endpoints, which NetEase risk control (code 10004) blocks; using the real web
 * session sidesteps that entirely, and the account's own VIP entitlement comes with it.
 * A manual paste remains as a fallback for environments where an embedded browser is
 * unavailable.
 */
@Composable
fun LoginScreen(
    currentAccount: Account?,
    client: NeteaseClient,
    artworkLoader: ArtworkLoader,
    onSignedIn: () -> Unit,
    onSignOut: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val signedIn = currentAccount != null && !currentAccount.isAnonymous
    val scope = rememberCoroutineScope()

    var manualMode by remember { mutableStateOf(false) }
    var cookieDraft by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    /** Validates a captured/pasted cookie by round-tripping it through /login/status. */
    fun authenticate(cookie: String) {
        if (submitting) return
        submitting = true
        status = "正在验证登录状态…"
        scope.launch {
            when (val r = client.loginWithCookie(cookie)) {
                is NetResult.Ok -> onSignedIn()
                is NetResult.Err -> status = "登录失败：${r.message}"
            }
            submitting = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(AppTheme.palette.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ---------------------------------------------------------------- header
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 44dp for a comfortable touch target; the glyph stays 20dp.
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    SfIcons.Xmark,
                    contentDescription = "关闭",
                    tint = AppTheme.palette.label,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            if (status != null) {
                Text(
                    text = status!!,
                    fontFamily = SFPro,
                    fontSize = 12.sp,
                    color = AppTheme.palette.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(end = 8.dp),
                )
            }
        }

        if (currentAccount != null && !currentAccount.isAnonymous) {
            // Branches on the account directly rather than on the `signedIn` boolean: the compiler then
            // knows the account is non-null here, so neither a `!!` nor a `?.` is needed — and there is no
            // second definition of "signed in" that could disagree with this one.
            SignedInPane(
                account = currentAccount,
                artworkLoader = artworkLoader,
                onSignOut = onSignOut,
            )
            return@Column
        }

        Text(
            text = if (manualMode) "手动填写 Cookie" else "登录网易云音乐",
            fontFamily = SFPro,
            fontWeight = FontWeight.Bold,
            fontSize = 24.sp,
            color = AppTheme.palette.label,
        )
        Spacer(Modifier.height(6.dp))

        if (manualMode) {
            // ------------------------------------------------------ manual fallback
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
            ) {
                Text(
                    text = "电脑浏览器打开 music.163.com 并登录 → F12 → Application → Cookies\n" +
                        "→ 复制 MUSIC_U 的值粘贴到下面（只填 MUSIC_U 的值也可以）。",
                    fontFamily = SFPro,
                    fontSize = 13.sp,
                    color = AppTheme.palette.secondaryLabel,
                )
                Spacer(Modifier.height(14.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp)
                        .clip(ContinuousRoundedRectangle(10.dp))
                        .background(AppTheme.palette.secondaryBackground)
                        .padding(12.dp),
                ) {
                    if (cookieDraft.isEmpty()) {
                        Text(
                            text = "MUSIC_U=...",
                            fontFamily = SFPro,
                            fontSize = 14.sp,
                            color = AppTheme.palette.tertiaryLabel,
                        )
                    }
                    BasicTextField(
                        value = cookieDraft,
                        onValueChange = { cookieDraft = it },
                        textStyle = TextStyle(
                            fontFamily = SFPro,
                            fontSize = 14.sp,
                            color = AppTheme.palette.label,
                        ),
                        cursorBrush = SolidColor(AppTheme.palette.accent),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.height(20.dp))
                PrimaryButton(
                    text = if (submitting) "请稍候…" else "使用 Cookie 登录",
                    enabled = cookieDraft.isNotBlank() && !submitting,
                ) { authenticate(cookieDraft) }
                Spacer(Modifier.height(12.dp))
                SecondaryButton(text = "返回网页登录") {
                    manualMode = false
                    status = null
                }
            }
        } else {
            // ------------------------------------------------------ embedded browser
            WebLoginHint()
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                WebLoginPanel(onCookieCaptured = { authenticate(it) })
                if (submitting) {
                    Box(
                        Modifier.fillMaxSize().background(AppTheme.palette.background.copy(alpha = 0.72f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            CircularProgressIndicator(
                                color = AppTheme.palette.accent,
                                strokeWidth = 2.5.dp,
                                modifier = Modifier.size(26.dp),
                            )
                            Text(
                                text = "正在完成登录…",
                                fontFamily = SFPro,
                                fontSize = 13.sp,
                                color = AppTheme.palette.secondaryLabel,
                            )
                        }
                    }
                }
            }
            Text(
                text = "网页无法加载？改用「手动填写 Cookie」",
                fontFamily = SFPro,
                fontSize = 12.sp,
                color = AppTheme.palette.accent,
                modifier = Modifier
                    .clip(ContinuousRoundedRectangle(AppleShapes.pill))
                    .clickable {
                        manualMode = true
                        status = null
                    }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SignedInPane(
    account: Account,
    artworkLoader: ArtworkLoader,
    onSignOut: () -> Unit,
) {
    Spacer(Modifier.height(24.dp))
    Text(
        text = "账号",
        fontFamily = SFPro,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        color = AppTheme.palette.label,
    )
    Spacer(Modifier.height(32.dp))
    Artwork(
        url = account.avatarUrl,
        loader = artworkLoader,
        corner = AppleShapes.pill,
        requestSize = 300,
        placeholderIcon = SfIcons.Person,
        modifier = Modifier.size(96.dp),
    )
    Spacer(Modifier.height(16.dp))
    Text(
        text = account.nickname,
        fontFamily = SFPro,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        color = AppTheme.palette.label,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Text(
        text = "网易云音乐 · ${account.userId}",
        fontFamily = SFPro,
        fontSize = 13.sp,
        color = AppTheme.palette.secondaryLabel,
    )
    Spacer(Modifier.weight(1f))
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 24.dp)
            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
            .background(AppTheme.palette.secondaryBackground)
            .clickable(onClick = onSignOut)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "退出登录",
            fontFamily = SFPro,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            color = AppTheme.palette.accent,
        )
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
            .background(if (enabled) AppTheme.palette.accent else AppTheme.palette.tertiaryBackground)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontFamily = SFPro,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
            color = if (enabled) Color.White else AppTheme.palette.secondaryLabel,
        )
    }
}

@Composable
private fun SecondaryButton(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(ContinuousRoundedRectangle(AppleShapes.pill))
            .background(AppTheme.palette.secondaryBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontFamily = SFPro,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = AppTheme.palette.accent,
        )
    }
}
