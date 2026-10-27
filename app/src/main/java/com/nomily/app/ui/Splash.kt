package com.nomily.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nomily.app.R
import kotlinx.coroutines.delay

/** Brand red behind the splash, the same as the launcher icon background. */
private val SplashRed = Color(0xFFE41D25)

/**
 * The one-second branded splash on a cold start: brand red, the app mark in the middle with a soft shadow, the
 * wordmark below, then a 0.3 s fade into the app. No interaction (taps are swallowed while it is up). Shown once
 * per launch — not again on rotation.
 */
@Composable
fun SplashOverlay() {
    var visible by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        if (!visible) return@LaunchedEffect
        delay(1_000)
        visible = false
    }
    AnimatedVisibility(visible = visible, enter = androidx.compose.animation.EnterTransition.None, exit = fadeOut(tween(300))) {
        Column(
            Modifier.fillMaxSize().background(SplashRed)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterVertically),
        ) {
            val shape = RoundedCornerShape(26.dp)
            // The adaptive launcher icon's two layers (a painter can't draw the adaptive-icon XML itself).
            Box(Modifier.size(116.dp).shadow(12.dp, shape).clip(shape)) {
                Image(painterResource(R.drawable.ic_launcher_background), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Image(painterResource(R.drawable.ic_launcher_foreground), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Text(
                stringResource(R.string.app_name),
                color = Color.White.copy(alpha = 0.96f),
                fontSize = 30.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.6.sp,
            )
        }
    }
}
