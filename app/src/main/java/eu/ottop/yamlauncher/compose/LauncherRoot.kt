package eu.ottop.yamlauncher.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import eu.ottop.yamlauncher.R

@Composable
fun LauncherRoot(vm: LauncherViewModel, onOpenSettings: () -> Unit) {
    val p by vm.uiPrefs.collectAsState()
    val drawerOpen by vm.drawerOpen.collectAsState()
    val speed = p.animSpeed.toInt().coerceIn(0, 10_000)
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        // Mirrors the legacy showApps/showHome animation: the drawer slides in
        // from the bottom while the home screen fades out (and vice versa).
        AnimatedVisibility(
            visible = !drawerOpen,
            enter = fadeIn(tween(speed)),
            exit = fadeOut(tween(speed)),
        ) {
            HomeScreen(vm = vm, onOpenSettings = onOpenSettings)
        }
        AnimatedVisibility(
            visible = drawerOpen,
            enter = slideInVertically(tween(speed)) { it } + fadeIn(tween(speed)),
            exit = slideOutVertically(tween(speed)) { it } + fadeOut(tween(speed)),
        ) {
            Box(modifier = Modifier.fillMaxSize().imePadding()) {
                AppDrawer(vm = vm)
            }
        }

        // Screen-lock accessibility prompt. Lives at root level so it shows
        // from the home screen (double-tap), not only when the drawer is open.
        val showAccessPrompt by vm.accessibilityPrompt.collectAsState()
        if (showAccessPrompt) {
            AlertDialog(
                onDismissRequest = vm::dismissAccessibilityPrompt,
                title = { Text(stringResource(R.string.confirm_title)) },
                text = { Text(stringResource(R.string.screenlock_confirmation)) },
                confirmButton = {
                    TextButton(onClick = {
                        vm.dismissAccessibilityPrompt()
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }) { Text(stringResource(R.string.confirm_yes)) }
                },
                dismissButton = {
                    TextButton(onClick = vm::dismissAccessibilityPrompt) {
                        Text(stringResource(R.string.confirm_no))
                    }
                },
            )
        }
    }
}
