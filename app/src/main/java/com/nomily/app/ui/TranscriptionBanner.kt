package com.nomily.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nomily.app.NomiViewModel
import com.nomily.app.R
import com.nomily.app.core.clips.TranscriptionBanners
import com.nomily.app.ui.theme.AppSwitchGreen
import kotlinx.coroutines.delay

/**
 * The "transcript ready" toast that slides in under the top bar on any screen. Each one stays 4 s; tapping it opens
 * the clip's detail page. Queued toasts follow one by one; ones that went stale in the background are dropped on resume.
 */
@Composable
fun TranscriptionBanner(vm: NomiViewModel, modifier: Modifier = Modifier) {
    val banner by vm.transcribedBanner.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) vm.dropStaleBanners() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // A new timer per toast: the effect restarts whenever the shown banner changes.
    LaunchedEffect(banner?.id) {
        if (banner == null) return@LaunchedEffect
        delay(4_000)
        vm.dismissTranscribedBanner()
    }
    // Keep the last toast while it animates out.
    val shown = remember { arrayOfNulls<TranscriptionBanners.Banner>(1) }
    banner?.let { shown[0] = it }
    AnimatedVisibility(
        visible = banner != null,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
        modifier = modifier,
    ) {
        val b = shown[0] ?: return@AnimatedVisibility
        val shape = RoundedCornerShape(50)
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surface,
            shadowElevation = 6.dp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).widthIn(max = 480.dp)
                .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), shape)
                .clickable {
                    vm.openInLibrary(b.clip)
                    vm.dismissTranscribedBanner()
                },
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = AppSwitchGreen, modifier = Modifier.size(22.dp))
                Column(Modifier.padding(start = 10.dp).weight(1f, fill = false)) {
                    Text(
                        stringResource(R.string.banner_transcribe_done),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        b.title,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.size(8.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
