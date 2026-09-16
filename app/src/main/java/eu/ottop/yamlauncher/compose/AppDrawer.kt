package eu.ottop.yamlauncher.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import eu.ottop.yamlauncher.R
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDrawer(vm: LauncherViewModel) {
    val open by vm.drawerOpen.collectAsState()
    if (!open) return
    val p by vm.uiPrefs.collectAsState()

    BackHandler { vm.closeDrawer() }

    // Drawer darkening overlay over the home screen (window bg stays transparent).
    val dimColor = if (p.drawerDarkening) Color(0x3F000000) else Color.Transparent

    // Swipe-down-to-close: consume downward overscroll only when the visible
    // list is already at its top, so normal list scrolling is unaffected.
    val appListState = rememberLazyListState()
    val contactListState = rememberLazyListState()
    val tabForScroll by vm.drawerTab.collectAsState()
    var overscrollAccum by remember { mutableStateOf(0f) }
    val closeConnection = remember(p.swipeThreshold) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val atTop = if (tabForScroll == 0) {
                    appListState.firstVisibleItemIndex == 0 &&
                        appListState.firstVisibleItemScrollOffset == 0
                } else {
                    contactListState.firstVisibleItemIndex == 0 &&
                        contactListState.firstVisibleItemScrollOffset == 0
                }
                if (source == NestedScrollSource.Drag && available.y > 0 && atTop) {
                    overscrollAccum += available.y
                    return available
                }
                if (source == NestedScrollSource.Drag) overscrollAccum = 0f
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (overscrollAccum > p.swipeThreshold) vm.closeDrawer()
                overscrollAccum = 0f
                return super.onPreFling(available)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(dimColor)
            .nestedScroll(closeConnection),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Spacer(modifier = Modifier.height(60.dp))
            ShortcutRenameField(vm)
            Box(modifier = Modifier.weight(1.1f).fillMaxWidth()) {
                DrawerContent(vm, appListState, contactListState)
                if (p.alphabetEnabled) {
                    AlphabetIndex(vm)
                }
            }
            SearchBar(vm)
            if (!p.searchEnabled && !p.contactsEnabled) {
                Spacer(modifier = Modifier.weight(0.1f))
            }
        }
    }

    // Auto-launch single result.
    val filtered by vm.filteredApps.collectAsState()
    LaunchedEffect(filtered) { vm.maybeAutoLaunch(filtered) }

    // Launch confirmation dialog.
    val confirm by vm.confirmLaunch.collectAsState()
    confirm?.let { entry ->
        AlertDialog(
            onDismissRequest = vm::dismissConfirm,
            title = { Text(stringResource(R.string.confirm_title)) },
            text = { Text(stringResource(R.string.launch_confirmation_text)) },
            confirmButton = {
                TextButton(onClick = vm::confirmLaunchYes) {
                    Text(stringResource(R.string.confirm_yes))
                }
            },
            dismissButton = {
                TextButton(onClick = vm::dismissConfirm) {
                    Text(stringResource(R.string.confirm_no))
                }
            },
        )
    }

    // App action bottom sheet.
    val sheetApp by vm.sheetApp.collectAsState()
    sheetApp?.let { entry ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = vm::closeSheet,
            sheetState = sheetState,
        ) {
            AppActionSheetContent(vm, entry)
        }
    }
}

@Composable
private fun DrawerContent(
    vm: LauncherViewModel,
    appListState: androidx.compose.foundation.lazy.LazyListState,
    contactListState: androidx.compose.foundation.lazy.LazyListState,
) {
    val tab by vm.drawerTab.collectAsState()
    LaunchedEffect(tab) {
        if (tab == 0) appListState.scrollToItem(0) else contactListState.scrollToItem(0)
    }
    if (tab == 0) AppList(vm, appListState) else ContactList(vm, contactListState)
}

@Composable
private fun AppList(
    vm: LauncherViewModel,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    val p by vm.uiPrefs.collectAsState()
    val filtered by vm.filteredApps.collectAsState()
    val open by vm.drawerOpen.collectAsState()
    val renameApp by vm.renameApp.collectAsState()
    val pickSlot: Int? = vm.shortcutPickSlot.collectAsState().value
    LaunchedEffect(open) {
        if (open) {
            listState.scrollToItem(0)
            vm.refreshNotifPackages()
        }
    }
    // Shared style computed once per prefs change, not once per row.
    val style = remember(p) { launcherTextStyle(p, p.appSizeSp.sp, alignmentFor(p.appAlign)) }
    val renameKey = renameApp?.let { vm.keyOf(it) }
    val pickMode = pickSlot != null
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Bottom,
        reverseLayout = false,
    ) {
        items(filtered, key = { it.key }) { model ->
            val renameEntry = if (model.key == renameKey) vm.entryOf(model.key) else null
            if (renameEntry != null) {
                RenameRow(vm, renameEntry, model.name)
            } else {
                val onClick = remember(model, pickMode) { { vm.onRowClick(model) } }
                val onLongClick = remember(model) { { vm.onRowLongClick(model) } }
                AppRow(
                    model = model,
                    style = style,
                    strictClick = p.strictClick,
                    spacingDp = p.appSpacingDp,
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
            }
        }
    }
    AlphabetScrollEffect(vm, listState)
}

@Composable
private fun AlphabetScrollEffect(
    vm: LauncherViewModel,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    val letter = alphabetScrollTarget.value
    val filtered by vm.filteredApps.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(letter) {
        if (letter == null) return@LaunchedEffect
        val idx = vm.firstPositionForLetter(filtered, letter)
        if (idx >= 0) scope.launch { listState.scrollToItem(idx) }
        alphabetScrollTarget.value = null
    }
}

/** One-shot scroll requests from the alphabet index. */
private val alphabetScrollTarget = mutableStateOf<String?>(null)

@Composable
private fun AlphabetIndex(vm: LauncherViewModel) {
    val p by vm.uiPrefs.collectAsState()
    val filtered by vm.filteredApps.collectAsState()
    val tab by vm.drawerTab.collectAsState()
    if (tab != 0) return
    val available = remember(filtered) { vm.alphabetLetters(filtered) }
    if (available.isEmpty()) return
    var selected by remember { mutableStateOf<String?>(null) }
    val letters = remember { listOf("#") + ('A'..'Z').map { it.toString() } }
    BoxWithConstraints(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = if (p.alphabetPosition == "left") Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        val maxH = maxHeight
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(32.dp)
                .pointerInput(available) {
                    detectTapGestures { offset ->
                        val idx = ((offset.y / maxH.toPx()) * letters.size).toInt().coerceIn(letters.indices)
                        val snapped = snapToAvailable(letters, available, idx)
                        selected = snapped
                        alphabetScrollTarget.value = snapped
                    }
                }
                .pointerInput(available) {
                    detectVerticalDragGestures(
                        onDragEnd = { selected = null },
                        onDragCancel = { selected = null },
                    ) { change, _ ->
                        val idx = ((change.position.y / maxH.toPx()) * letters.size).toInt().coerceIn(letters.indices)
                        val snapped = snapToAvailable(letters, available, idx)
                        if (snapped != selected) {
                            selected = snapped
                            alphabetScrollTarget.value = snapped
                        }
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (letter in letters) {
                val isAvailable = available.contains(letter)
                val isSelected = selected == letter
                Text(
                    text = letter,
                    modifier = Modifier.weight(1f),
                    style = launcherTextStyle(
                        p,
                        12.sp,
                        androidx.compose.ui.text.style.TextAlign.Center,
                    ).copy(
                        color = when {
                            isSelected -> Color(p.textColor)
                            isAvailable -> Color(p.textColor)
                            else -> Color(p.textColor).copy(alpha = 0.25f)
                        },
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

private fun snapToAvailable(
    letters: List<String>,
    available: Set<String>,
    index: Int,
): String {
    if (available.contains(letters[index])) return letters[index]
    var best = letters[index]
    var bestDist = Int.MAX_VALUE
    for (i in letters.indices) {
        if (!available.contains(letters[i])) continue
        val dist = kotlin.math.abs(i - index)
        if (dist < bestDist) {
            bestDist = dist
            best = letters[i]
        }
    }
    return best
}

@Composable
private fun AppRow(
    model: AppRowModel,
    style: TextStyle,
    strictClick: Boolean,
    spacingDp: Int,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val iconRes = when {
        model.pinned && model.isWork -> R.drawable.keep_filled_15px
        model.pinned -> R.drawable.keep_15px
        model.isWork -> R.drawable.ic_work_app
        else -> R.drawable.ic_empty
    }
    val rowModifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 20.dp, vertical = spacingDp.dp)

    val label: @Composable (Modifier) -> Unit = { modifier ->
        Text(
            text = model.name,
            modifier = modifier,
            style = style,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    val icon: @Composable () -> Unit = {
        // ic_empty is a pathless placeholder vector Compose cannot parse;
        // use a same-sized spacer to keep rows aligned.
        if (iconRes == R.drawable.ic_empty) {
            Spacer(Modifier.size(15.dp))
        } else {
            Image(
                painter = painterResource(iconRes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(style.color),
            )
        }
    }

    if (strictClick) {
        // Only taps directly on the text launch the app.
        Row(modifier = rowModifier, verticalAlignment = Alignment.CenterVertically) {
            icon()
            label(
                Modifier
                    .weight(1f)
                    .combinedClickable(onClick = onClick, onLongClick = onLongClick),
            )
        }
    } else {
        Row(
            modifier = rowModifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            label(Modifier.weight(1f))
        }
    }
}

@Composable
private fun RenameRow(vm: LauncherViewModel, entry: AppEntry, currentName: String) {
    val p by vm.uiPrefs.collectAsState()
    var text by remember(entry) { mutableStateOf(currentName) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(entry) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier
                .weight(1f)
                .background(Color(0xA7000000), androidx.compose.foundation.shape.RoundedCornerShape(4.dp))
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .focusRequester(focusRequester),
            textStyle = launcherTextStyle(p, p.appSizeSp.sp, alignmentFor(p.appAlign)),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                keyboard?.hide()
                if (vm.confirmRename(entry, text)) keyboard?.hide()
            }),
            cursorBrush = SolidColor(Color(p.textColor)),
        )
        TextButton(onClick = {
            keyboard?.hide()
            vm.resetAppName(entry)
        }) {
            Text(stringResource(R.string.reset), color = Color(p.textColor))
        }
    }
}

@Composable
private fun ContactList(
    vm: LauncherViewModel,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    val p by vm.uiPrefs.collectAsState()
    val context = LocalContext.current
    val contacts by vm.contacts.collectAsState()
    val pickSlot: Int? = vm.shortcutPickSlot.collectAsState().value
    val style = launcherTextStyle(p, p.appSizeSp.sp, alignmentFor(p.appAlign))
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Bottom,
    ) {
        items(contacts, key = { it.second }) { (name, id) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = p.appSpacingDp.dp)
                    .combinedClickable(
                        onClick = {
                            if (pickSlot != null) vm.assignContactShortcut(pickSlot, id, name)
                            else vm.onContactClick(id, context)
                        },
                        onLongClick = {},
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = name,
                    modifier = Modifier.weight(1f),
                    style = style,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ShortcutRenameField(vm: LauncherViewModel) {
    val slot by vm.shortcutPickSlot.collectAsState()
    if (slot == null) return
    val p by vm.uiPrefs.collectAsState()
    val current = remember(p, slot) { vm.shortcutAt(slot!!) }
    var text by remember(slot) { mutableStateOf(current?.label.orEmpty()) }
    val keyboard = LocalSoftwareKeyboardController.current
    BasicTextField(
        value = text,
        onValueChange = { text = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 40.dp, vertical = 10.dp),
        textStyle = launcherTextStyle(p, menuTitleSize(p.appSizePreset).sp, alignmentFor(p.appAlign))
            .copy(color = Color(p.textColor).copy(alpha = 0.66f)),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = {
            keyboard?.hide()
            if (text.isBlank()) {
                // keep parity with legacy empty-rename toast
            } else if (current != null) {
                vm.renameShortcut(slot!!, text, current)
            }
        }),
        cursorBrush = SolidColor(Color(p.textColor)),
        decorationBox = { inner ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    painter = painterResource(R.drawable.edit_24px),
                    contentDescription = null,
                    tint = Color(p.textColor),
                )
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (text.isEmpty()) {
                        Text(
                            text = current?.label.orEmpty(),
                            style = launcherTextStyle(p, 33.sp).copy(
                                color = Color(p.textColor).copy(alpha = 0.5f)
                            ),
                        )
                    }
                    inner()
                }
            }
        },
    )
}

@Composable
private fun ColumnScope.SearchBar(vm: LauncherViewModel) {
    val p by vm.uiPrefs.collectAsState()
    val context = LocalContext.current
    val tab by vm.drawerTab.collectAsState()
    val query by vm.query.collectAsState()
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val open by vm.drawerOpen.collectAsState()

    // Auto-focus + keyboard like the legacy autoKeyboard / typed-char flows.
    LaunchedEffect(open) {
        if (open && (p.autoKeyboard || query.isNotEmpty())) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    val showLayout = p.searchEnabled || p.contactsEnabled
    if (!showLayout) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
            .weight(0.1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (p.searchEnabled) {
            Icon(
                painter = painterResource(R.drawable.search_24px),
                contentDescription = null,
                tint = Color(p.textColor),
            )
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = vm::setQuery,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                textStyle = launcherTextStyle(p, p.searchSizeSp.sp, alignmentFor(p.searchAlign)),
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                cursorBrush = SolidColor(Color(p.textColor)),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.search),
                                style = launcherTextStyle(p, p.searchSizeSp.sp, alignmentFor(p.searchAlign))
                                    .copy(color = Color(p.textColor).copy(alpha = 0.66f)),
                            )
                        }
                        inner()
                    }
                },
            )
            if (p.webSearchEnabled) {
                Icon(
                    painter = painterResource(R.drawable.travel_explore_24),
                    contentDescription = stringResource(R.string.internet_search),
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .combinedClickable(onClick = { vm.webSearch(query, context) }, onLongClick = {}),
                    tint = Color(p.textColor),
                )
            }
        }
        if (p.contactsEnabled) {
            Spacer(Modifier.weight(1f))
            Icon(
                painter = painterResource(if (tab == 0) R.drawable.contacts_24px else R.drawable.apps_24px),
                contentDescription = stringResource(if (tab == 0) R.string.switch_to_contacts else R.string.switch_to_apps),
                modifier = Modifier.combinedClickable(onClick = vm::switchTab, onLongClick = {}),
                tint = Color(p.textColor),
            )
        }
    }
}

@Composable
private fun AppActionSheetContent(vm: LauncherViewModel, entry: AppEntry) {
    val p by vm.uiPrefs.collectAsState()
    val context = LocalContext.current
    val name = remember(p, entry) { vm.renameDisplayName(entry) }
    val pinned = remember(p, entry) { vm.isAppPinned(entry) }
    val isSystem = remember(entry) { vm.isSystemApp(entry) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = name,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            style = launcherTextStyle(p, 20.sp, androidx.compose.ui.text.style.TextAlign.Center),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        HorizontalDivider(color = Color(p.textColor).copy(alpha = 0.2f))
        // Legacy layout: a single centered row of icon-above-label actions.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Top,
        ) {
            if (p.pinEnabled) {
                SheetAction(
                    vm,
                    icon = if (pinned) R.drawable.keep_off_24px else R.drawable.keep_24px,
                    label = stringResource(if (pinned) R.string.unpin else R.string.pin),
                ) { vm.togglePin(entry) }
            }
            if (p.infoEnabled) {
                SheetAction(vm, icon = R.drawable.info_24px, label = stringResource(R.string.info)) {
                    vm.closeSheet()
                    vm.openAppInfo(entry, context)
                }
            }
            if (p.uninstallEnabled && !isSystem) {
                SheetAction(vm, icon = R.drawable.delete_24px, label = stringResource(R.string.uninstall)) {
                    vm.returnAllowed = false
                    vm.closeSheet()
                    context.startActivity(vm.uninstallIntent(entry))
                }
            }
            if (p.renameEnabled) {
                SheetAction(vm, icon = R.drawable.edit_24px, label = stringResource(R.string.rename)) {
                    vm.beginRename(entry)
                }
            }
            if (p.hideEnabled) {
                SheetAction(vm, icon = R.drawable.visibility_off_24px, label = stringResource(R.string.hide)) {
                    vm.hideApp(entry)
                }
            }
        }
    }
}

@Composable
private fun SheetAction(vm: LauncherViewModel, icon: Int, label: String, onClick: () -> Unit) {
    val p by vm.uiPrefs.collectAsState()
    Column(
        modifier = Modifier
            .combinedClickable(onClick = onClick, onLongClick = {})
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = Color(p.textColor),
            modifier = Modifier.size(24.dp),
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = launcherTextStyle(p, 12.sp, androidx.compose.ui.text.style.TextAlign.Center),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
