package com.jeffery.cellularmonitor.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabPosition
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.jeffery.cellularmonitor.R
import com.jeffery.cellularmonitor.data.CardKind
import com.jeffery.cellularmonitor.data.CellDetail
import com.jeffery.cellularmonitor.data.CellIdFormatter
import com.jeffery.cellularmonitor.data.CellType
import com.jeffery.cellularmonitor.data.LocationInfo
import com.jeffery.cellularmonitor.data.LteMetrics
import com.jeffery.cellularmonitor.data.NeighborCell
import com.jeffery.cellularmonitor.data.NetworkMode
import com.jeffery.cellularmonitor.data.NrMetrics
import com.jeffery.cellularmonitor.data.SlotSnapshot
import com.jeffery.cellularmonitor.data.SpeedTestPhase
import com.jeffery.cellularmonitor.data.SpeedTestProgress
import com.jeffery.cellularmonitor.data.SpeedTestResult
import kotlinx.coroutines.launch
import kotlin.math.abs

@Composable
fun MonitorScreen(controller: MonitorController) {
    val snapshots = controller.snapshots
    val status = controller.status
    val context = LocalContext.current

    // 设置状态栏颜色为白色背景，深色图标
    androidx.compose.runtime.SideEffect {
        val window = (context as? android.app.Activity)?.window
        window?.let {
            it.statusBarColor = android.graphics.Color.WHITE
            it.navigationBarColor = android.graphics.Color.WHITE
            // 设置状态栏图标为深色
            androidx.core.view.WindowCompat.getInsetsController(it, it.decorView).apply {
                isAppearanceLightStatusBars = true
                isAppearanceLightNavigationBars = true
            }
        }
    }

    // 只认 TXT 也可能被某些文件管理器报成 application/octet-stream，所以放开 MIME 由解析器兜底
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { controller.importGongcan(it) }
    }

    controller.importState?.let { state ->
        ImportResultDialog(state, onDismiss = controller::dismissImportState)
    }

    var showCardOrder by remember { mutableStateOf(false) }
    if (showCardOrder) {
        CardOrderDialog(
            order = controller.cardOrder,
            onMove = controller::moveCard,
            onReset = controller::resetCardOrder,
            onDismiss = { showCardOrder = false },
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            GongcanBar(
                onImport = { picker.launch("*/*") },
                onClear = controller::clearImportedGongcan,
                onOpenCardOrder = { showCardOrder = true },
                onOpenMap = {
                    context.startActivity(
                        android.content.Intent(
                            context,
                            com.jeffery.cellularmonitor.ui.map.MapActivity::class.java,
                        )
                    )
                },
            )
        },
    ) { padding ->
        when {
            !status.hasPhoneStatePermission || !status.hasLocationPermission -> {
                GuidePermissionMissing(Modifier.padding(padding))
            }

            !status.isLocationEnabled -> {
                GuideLocationDisabled(
                    modifier = Modifier.padding(padding),
                    onOpenSettings = { openLocationSettings(context) },
                )
            }

            status.activeSimCount == 0 -> {
                GuideNoSim(Modifier.padding(padding))
            }

            snapshots.isEmpty() -> {
                GuideLoading(Modifier.padding(padding))
            }

            else -> {
                SlotTabs(
                    snapshots = snapshots,
                    selectedIndex = controller.selectedSlotIndex,
                    gnbBits = controller.gnbBits,
                    onSelectSlot = controller::selectSlot,
                    onSetGnbBits = { bits ->
                        if (bits in CellIdFormatter.GNB_BITS_RANGE) {
                            controller.gnbBits = bits
                        }
                    },
                    speedProgress = controller.speedProgress,
                    speedResult = controller.speedResult,
                    cardOrder = controller.cardOrder,
                    onStartSpeedTest = controller::startSpeedTest,
                    onCancelSpeedTest = controller::cancelSpeedTest,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

// ---- 工参导入 ----

@Composable
private fun GongcanBar(
    onImport: () -> Unit,
    onClear: () -> Unit,
    onOpenCardOrder: () -> Unit,
    onOpenMap: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onOpenMap) {
            Text(stringResource(R.string.map_entry))
        }
        TextButton(onClick = onOpenCardOrder) {
            Text(stringResource(R.string.card_order_entry))
        }
        TextButton(onClick = onClear) {
            Text(stringResource(R.string.gongcan_clear))
        }
        TextButton(onClick = onImport) {
            Text(stringResource(R.string.gongcan_import))
        }
    }
}

// ---- 卡片排序 ----

/** 行高，拖拽时用它把手指位移换算成跨过了几行 */
private val CARD_ORDER_ROW_HEIGHT = 48.dp

@Composable
private fun CardOrderDialog(
    order: List<CardKind>,
    onMove: (Int, Int) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val rowHeightPx = with(LocalDensity.current) { CARD_ORDER_ROW_HEIGHT.toPx() }

    // 正在拖的那一行在列表里的当前下标，-1 表示没在拖
    var dragIndex by remember { mutableIntStateOf(-1) }
    // 手指相对当前所在行的位移，跨过半行就换位并把它减掉，这样偏移量始终是小量
    var dragOffset by remember { mutableFloatStateOf(0f) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.card_order_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.card_order_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                order.forEachIndexed { index, kind ->
                    CardOrderRow(
                        kind = kind,
                        dragging = dragIndex == index,
                        dragOffset = if (dragIndex == index) dragOffset else 0f,
                        canMoveUp = index > 0,
                        canMoveDown = index < order.lastIndex,
                        onMoveUp = { onMove(index, index - 1) },
                        onMoveDown = { onMove(index, index + 1) },
                        onDragStart = {
                            dragIndex = index
                            dragOffset = 0f
                        },
                        onDrag = { delta ->
                            val from = dragIndex
                            if (from < 0) return@CardOrderRow
                            dragOffset += delta
                            // 越过半行就立刻换位，手指下面始终是同一张卡
                            val steps = (dragOffset / rowHeightPx).let {
                                if (it > 0.5f) it.toInt().coerceAtLeast(1)
                                else if (it < -0.5f) it.toInt().coerceAtMost(-1)
                                else 0
                            }
                            if (steps != 0) {
                                val to = (from + steps).coerceIn(order.indices)
                                if (to != from) {
                                    onMove(from, to)
                                    dragIndex = to
                                    dragOffset -= (to - from) * rowHeightPx
                                }
                            }
                        },
                        onDragEnd = {
                            dragIndex = -1
                            dragOffset = 0f
                        },
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onReset) {
                Text(stringResource(R.string.card_order_reset))
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.card_order_done))
            }
        },
    )
}

@Composable
private fun CardOrderRow(
    kind: CardKind,
    dragging: Boolean,
    dragOffset: Float,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    // pointerInput(Unit) 里的 lambda 只在首次组合时捕获，用它读最新回调，避免换位后回调过期
    val dragStart by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val dragEnd by rememberUpdatedState(onDragEnd)

    Row(
        modifier = Modifier
            // 拖起来的那行要盖在别的行上面，否则往下拖会被后面的行遮住
            .zIndex(if (dragging) 1f else 0f)
            .offset { IntOffset(0, dragOffset.toInt()) }
            .fillMaxWidth()
            .height(CARD_ORDER_ROW_HEIGHT)
            .clip(MaterialTheme.shapes.small)
            .background(
                if (dragging) MaterialTheme.colorScheme.surfaceVariant
                else MaterialTheme.colorScheme.surface
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(kind.titleRes),
            modifier = Modifier
                .weight(1f)
                .padding(start = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // 用文字箭头而不是图标，省掉 material-icons-extended 依赖
        IconButton(onClick = onMoveUp, enabled = canMoveUp) { Text("↑") }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) { Text("↓") }
        // 拖拽把手。只在把手上响应拖拽，整行可拖会和对话框内容的滚动打架
        Text(
            text = "≡",
            modifier = Modifier
                .size(40.dp)
                .wrapContentSize()
                .semantics { contentDescription = "拖拽排序" }
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { dragStart() },
                        onDragEnd = { dragEnd() },
                        onDragCancel = { dragEnd() },
                        onVerticalDrag = { change, delta ->
                            change.consume()
                            drag(delta)
                        },
                    )
                },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ImportResultDialog(state: ImportState, onDismiss: () -> Unit) {
    // 解析中不给关闭入口，避免中途点掉后状态对不上
    if (state is ImportState.Loading) {
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            text = { Text(stringResource(R.string.gongcan_importing)) },
        )
        return
    }

    val title: String
    val body: String
    when (state) {
        is ImportState.Success -> {
            title = stringResource(R.string.gongcan_import_ok_title)
            body = buildString {
                appendLine(stringResource(R.string.gongcan_import_ok_count, state.cellCount))
                // 分运营商列一下，让用户能确认自己那张卡有没有工参
                state.carrierCounts.entries
                    .sortedByDescending { it.value }
                    .forEach { (name, count) -> appendLine("$name $count") }
                appendLine()
                state.typeCounts.entries
                    .sortedByDescending { it.value }
                    .forEach { (type, count) -> appendLine("$type $count") }
                if (state.skippedOtherPlmn > 0) {
                    append(
                        stringResource(
                            R.string.gongcan_import_ok_skipped,
                            state.skippedOtherPlmn,
                        )
                    )
                }
            }.trimEnd()
        }

        is ImportState.Failure -> {
            title = stringResource(R.string.gongcan_import_fail_title)
            body = state.message + "\n\n" + stringResource(R.string.gongcan_import_fail_hint)
        }

        is ImportState.Cleared -> {
            title = stringResource(R.string.gongcan_cleared_title)
            body = if (state.remainingCount > 0) {
                stringResource(R.string.gongcan_cleared_builtin, state.remainingCount)
            } else {
                stringResource(R.string.gongcan_cleared_none)
            }
        }

        ImportState.Loading -> return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.dialog_ok)) }
        },
    )
}

// ---- 引导态 ----

@Composable
private fun GuidePermissionMissing(modifier: Modifier = Modifier) {
    CenteredMessage(
        modifier = modifier,
        title = stringResource(R.string.guide_permission_title),
        message = stringResource(R.string.guide_permission_message),
    )
}

@Composable
private fun GuideLocationDisabled(
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit,
) {
    CenteredMessage(
        modifier = modifier,
        title = stringResource(R.string.guide_location_title),
        message = stringResource(R.string.guide_location_message),
    ) {
        Button(onClick = onOpenSettings) {
            Text(stringResource(R.string.guide_location_action))
        }
    }
}

@Composable
private fun GuideNoSim(modifier: Modifier = Modifier) {
    CenteredMessage(
        modifier = modifier,
        title = stringResource(R.string.guide_no_sim_title),
        message = stringResource(R.string.guide_no_sim_message),
    )
}

@Composable
private fun GuideLoading(modifier: Modifier = Modifier) {
    CenteredMessage(
        modifier = modifier,
        title = stringResource(R.string.guide_loading),
        message = "",
    )
}

@Composable
private fun CenteredMessage(
    modifier: Modifier = Modifier,
    title: String,
    message: String,
    action: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (message.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(16.dp))
        action()
    }
}

// ---- Tab 切换卡槽（支持左右滑动）----

@Composable
private fun SlotTabs(
    snapshots: List<SlotSnapshot>,
    selectedIndex: Int,
    gnbBits: Int,
    onSelectSlot: (Int) -> Unit,
    onSetGnbBits: (Int) -> Unit,
    speedProgress: SpeedTestProgress?,
    speedResult: SpeedTestResult?,
    cardOrder: List<CardKind>,
    onStartSpeedTest: () -> Unit,
    onCancelSpeedTest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val safeIndex = selectedIndex.coerceIn(snapshots.indices)
    val pagerState = rememberPagerState(
        initialPage = safeIndex,
        pageCount = { snapshots.size },
    )
    val scope = rememberCoroutineScope()

    // 数据每 2 秒刷新一次，如果滑动过程中跟着重组，整页卡片都会重新布局，手感就卡。
    // 滑动期间冻结数据，松手后再采用最新快照。
    val stable = remember { mutableStateOf(snapshots) }
    LaunchedEffect(snapshots, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) {
            stable.value = snapshots
        }
    }
    val pages = stable.value
    if (pages.isEmpty()) return

    // 同步 pagerState 与 controller 的 selectedIndex算
    LaunchedEffect(pagerState.currentPage) {
        if (pagerState.currentPage != safeIndex) {
            onSelectSlot(pagerState.currentPage)
        }
    }
    LaunchedEffect(safeIndex) {
        if (pagerState.currentPage != safeIndex) {
            pagerState.animateScrollToPage(safeIndex)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        if (pages.size > 1) {
            val currentIndex = pagerState.currentPage.coerceIn(pages.indices)
            TabRow(
                selectedTabIndex = currentIndex,
                indicator = { tabPositions ->
                    // 默认指示器只在翻页阈值处跳变，所以蓝条不跟手。
                    // 这里直接读滑动偏移，在相邻两个 Tab 的位置之间插值，做到逐帧跟手。
                    PagerTabIndicator(tabPositions, pagerState)
                },
            ) {
                pages.forEachIndexed { index, snapshot ->
                    Tab(
                        selected = index == currentIndex,
                        onClick = {
                            scope.launch {
                                // 默认 spring 在两页之间收敛太快，观感接近瞬移，改成固定时长。
                                pagerState.animateScrollToPage(
                                    page = index,
                                    animationSpec = tween(
                                        durationMillis = 320,
                                        easing = FastOutSlowInEasing,
                                    ),
                                )
                            }
                        },
                        text = {
                            Text(
                                stringResource(
                                    R.string.slot_tab_label,
                                    snapshot.sim.slotIndex + 1,
                                    snapshot.sim.carrierName.ifEmpty { "?" },
                                ),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                    )
                }
            }
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            key = { page -> pages.getOrNull(page)?.sim?.subscriptionId ?: page },
        ) { page ->
            val snapshot = pages.getOrNull(page) ?: return@HorizontalPager
            SlotPage(
                snapshot = snapshot,
                gnbBits = gnbBits,
                onSetGnbBits = onSetGnbBits,
                speedProgress = speedProgress,
                speedResult = speedResult,
                cardOrder = cardOrder,
                onStartSpeedTest = onStartSpeedTest,
                onCancelSpeedTest = onCancelSpeedTest,
            )
        }
    }
}

/**
 * 跟手的 Tab 指示器。
 *
 * 读取 PagerState 的实时偏移，在相邻 Tab 位置之间插值，手指拖到哪蓝条就跟到哪。
 */
@Composable
private fun PagerTabIndicator(
    tabPositions: List<TabPosition>,
    pagerState: PagerState,
) {
    if (tabPositions.isEmpty()) return

    val currentPage = pagerState.currentPage.coerceIn(tabPositions.indices)
    val currentPos = tabPositions[currentPage]
    val targetPage = (currentPage + if (pagerState.currentPageOffsetFraction > 0) 1 else -1)
        .coerceIn(tabPositions.indices)
    val targetPos = tabPositions[targetPage]

    val fraction = abs(pagerState.currentPageOffsetFraction)
    val indicatorLeft = lerp(currentPos.left, targetPos.left, fraction)
    val indicatorWidth = lerp(currentPos.width, targetPos.width, fraction)

    Box(
        Modifier
            .fillMaxWidth()
            .wrapContentSize(Alignment.BottomStart)
            .offset(x = indicatorLeft)
            .width(indicatorWidth)
            .height(3.dp)
            .background(MaterialTheme.colorScheme.primary)
    )
}

/**
 * 单页内容。参数都是稳定值，快照没变时整页可以跳过重组。
 */
@Composable
private fun SlotPage(
    snapshot: SlotSnapshot,
    gnbBits: Int,
    onSetGnbBits: (Int) -> Unit,
    speedProgress: SpeedTestProgress?,
    speedResult: SpeedTestResult?,
    cardOrder: List<CardKind>,
    onStartSpeedTest: () -> Unit,
    onCancelSpeedTest: () -> Unit,
) {
    // 小区明细：4G 锚点和 5G 小区可能同时匹配到工参，各出一段，不再二选一。
    // 当前注册的制式排前面。NSA 下 NR 没有 NCI，一般只有 LTE 这一段。
    val details = buildList {
        val nrEntry = snapshot.nr?.let { nr ->
            if (nr.nci != null || nr.cellDetail != null) {
                CellDetailInfo(CellType.NR, nr.nci, nr.cellDetail)
            } else null
        }
        val lteEntry = snapshot.lte?.let { lte ->
            if (lte.eci != null || lte.cellDetail != null) {
                CellDetailInfo(CellType.LTE, lte.eci?.toLong(), lte.cellDetail)
            } else null
        }
        if (snapshot.networkMode.isNr) {
            nrEntry?.let { add(it) }
            lteEntry?.let { add(it) }
        } else {
            lteEntry?.let { add(it) }
            nrEntry?.let { add(it) }
        }
    }

    ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 按用户设置的顺序渲染。数据不存在的卡片跳过——顺序里有它不代表这张卡有内容
            cardOrder.forEach { kind ->
                when (kind) {
                    CardKind.CARRIER -> item(key = kind.key) { CarrierCard(snapshot) }

                    CardKind.SPEED -> item(key = kind.key) {
                        SpeedTestCard(
                            progress = speedProgress,
                            result = speedResult,
                            onStart = onStartSpeedTest,
                            onCancel = onCancelSpeedTest,
                        )
                    }

                    CardKind.LOCATION -> snapshot.location?.let { location ->
                        item(key = kind.key) { LocationCard(location) }
                    }

                    CardKind.NR -> snapshot.nr?.let { nr ->
                        item(key = kind.key) {
                            NrCard(
                                nr = nr,
                                mode = snapshot.networkMode,
                                gnbBits = gnbBits,
                                onSetGnbBits = onSetGnbBits,
                                // NSA 下 NR 没有 NCI 查不到类型，锚点 LTE 小区的类型即当前位置的类型
                                anchorCellType = snapshot.lte?.cellType,
                            )
                        }
                    }

                    CardKind.LTE -> snapshot.lte?.let { lte ->
                        item(key = kind.key) { LteCard(lte) }
                    }

                    CardKind.NEIGHBORS -> if (snapshot.neighbors.isNotEmpty()) {
                        item(key = kind.key) { NeighborsCard(snapshot.neighbors) }
                    }

                    CardKind.CELL_DETAIL -> if (details.isNotEmpty()) {
                        item(key = kind.key) { CellDetailCard(details) }
                    }
                }
            }
        }
    }
}

/** 小区明细包装：制式 + 小区号 + 详情。工参没匹配到时 [detail] 为 null，只显示小区号。 */
private data class CellDetailInfo(
    val type: CellType,
    val cellId: Long?,
    val detail: CellDetail?,
)

// ---- 运营商卡片 ----

@Composable
private fun LocationCard(location: LocationInfo) {
    val context = LocalContext.current
    var useDMS by remember { mutableStateOf(false) }
    var latFirst by remember { mutableStateOf(true) }

    MetricCard(title = stringResource(R.string.location_section_title)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("格式", fontWeight = FontWeight.Medium)
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .width(140.dp)
                    .height(28.dp),
            ) {
                SegmentedButton(
                    selected = !useDMS,
                    onClick = { useDMS = false },
                    shape = SegmentedButtonDefaults.itemShape(0, 2),
                    icon = { /* 去掉勾选标记 */ },
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primary
                            .copy(alpha = 0.78f),
                        activeContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    label = {
                        Text(
                            stringResource(R.string.location_format_decimal),
                            fontSize = 11.sp,
                            maxLines = 1,
                            softWrap = false,
                        )
                    },
                )
                SegmentedButton(
                    selected = useDMS,
                    onClick = { useDMS = true },
                    shape = SegmentedButtonDefaults.itemShape(1, 2),
                    icon = { /* 去掉勾选标记 */ },
                    colors = SegmentedButtonDefaults.colors(
                        activeContainerColor = MaterialTheme.colorScheme.primary
                            .copy(alpha = 0.78f),
                        activeContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
                    label = {
                        Text(
                            stringResource(R.string.location_format_dms),
                            fontSize = 11.sp,
                            maxLines = 1,
                            softWrap = false,
                        )
                    },
                )
            }
        }

        val coord1Text: String
        val coord2Text: String
        if (useDMS) {
            coord1Text = formatDMS(if (latFirst) location.latitude else location.longitude, latFirst)
            coord2Text = formatDMS(if (latFirst) location.longitude else location.latitude, !latFirst)
        } else {
            if (latFirst) {
                coord1Text = String.format("%.6f", location.latitude)
                coord2Text = String.format("%.6f", location.longitude)
            } else {
                coord1Text = String.format("%.6f", location.longitude)
                coord2Text = String.format("%.6f", location.latitude)
            }
        }
        val coordText = "$coord1Text, $coord2Text"

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { copyToClipboard(context, "坐标", coordText) },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("坐标", fontWeight = FontWeight.Medium)
                TextButton(
                    onClick = { latFirst = !latFirst },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 0.dp),
                    modifier = Modifier.height(24.dp),
                ) {
                    Text("⇅", style = MaterialTheme.typography.titleMedium)
                }
            }
            Column(
                modifier = Modifier.weight(1f, fill = false),
                horizontalAlignment = Alignment.End,
            ) {
                Text(
                    coord1Text,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.End,
                )
                Text(
                    coord2Text,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.End,
                )
            }
        }

        location.accuracy?.let { acc ->
            Text(
                stringResource(R.string.location_accuracy, acc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---- 测速卡片 ----

@Composable
private fun SpeedTestCard(
    progress: SpeedTestProgress?,
    result: SpeedTestResult?,
    onStart: () -> Unit,
    onCancel: () -> Unit,
) {
    val context = LocalContext.current
    val testing = progress != null

    MetricCard(title = stringResource(R.string.speed_section_title)) {
        val success = result as? SpeedTestResult.Success
        val download = progress?.downloadMbps ?: success?.downloadMbps
        val upload = progress?.uploadMbps ?: success?.uploadMbps

        SpeedRow(
            label = stringResource(R.string.speed_download),
            mbps = download,
            active = progress?.phase == SpeedTestPhase.DOWNLOAD,
        ) { download?.let { copyToClipboard(context, "下载速度", formatMbps(it)) } }
        SpeedRow(
            label = stringResource(R.string.speed_upload),
            mbps = upload,
            active = progress?.phase == SpeedTestPhase.UPLOAD,
        ) { upload?.let { copyToClipboard(context, "上传速度", formatMbps(it)) } }

        (result as? SpeedTestResult.Failure)?.let { failure ->
            Text(
                stringResource(R.string.speed_failed, failure.message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (testing) {
            val phaseText = when (progress.phase) {
                SpeedTestPhase.DOWNLOAD -> stringResource(R.string.speed_phase_download)
                SpeedTestPhase.UPLOAD -> stringResource(R.string.speed_phase_upload)
            }
            Text(
                phaseText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            val label = if (testing) R.string.speed_stop else R.string.speed_test_peak
            // 默认按钮 40dp 高，比这张卡的其它数值行都高，看起来底下留白大。压到 28dp
            // 跟 SpeedRow 高度更接近；contentPadding 也从默认的 16/8 压掉，避免再撑高。
            TextButton(
                onClick = if (testing) onCancel else onStart,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                modifier = Modifier.height(28.dp),
            ) {
                Text(stringResource(label))
            }
        }
    }
}

/** 速率行。测这一项时把数字标成主色，让用户看出进度走到哪了。 */
@Composable
private fun SpeedRow(
    label: String,
    mbps: Double?,
    active: Boolean,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCopy),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, fontWeight = FontWeight.Medium)
        Text(
            mbps?.let { formatMbps(it) } ?: "–",
            fontFamily = FontFamily.Monospace,
            fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
            color = if (active) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End,
        )
    }
}

/** 慢的时候小数点后一位不够看，10 Mbps 以下多给一位。 */
private fun formatMbps(mbps: Double): String =
    if (mbps < 10) "%.2f Mbps".format(mbps) else "%.1f Mbps".format(mbps)

private fun formatDMS(decimal: Double, isLatitude: Boolean): String {
    val abs = kotlin.math.abs(decimal)
    val degrees = abs.toInt()
    val minutesDecimal = (abs - degrees) * 60
    val minutes = minutesDecimal.toInt()
    val seconds = (minutesDecimal - minutes) * 60

    val direction = when {
        isLatitude -> if (decimal >= 0) "N" else "S"
        else -> if (decimal >= 0) "E" else "W"
    }

    return String.format("%d°%d'%.2f\"%s", degrees, minutes, seconds, direction)
}

@Composable
private fun CarrierCard(snapshot: SlotSnapshot) {
    val context = LocalContext.current
    MetricCard(title = stringResource(R.string.carrier_section_title)) {
        val carrierName = snapshot.sim.carrierName.ifEmpty { "–" }
        LabelValue(
            label = stringResource(R.string.carrier_name_label),
            value = carrierName,
        ) {
            if (carrierName != "–") {
                copyToClipboard(context, "运营商", carrierName)
            }
        }
        snapshot.sim.plmn?.let { plmn ->
            LabelValue(
                label = stringResource(R.string.carrier_plmn_label),
                value = plmn,
            ) {
                copyToClipboard(context, "PLMN", plmn)
            }
        }
        val modeStr = when (snapshot.networkMode) {
            NetworkMode.LTE -> "LTE"
            NetworkMode.NR_NSA -> "5G NSA"
            NetworkMode.NR_SA -> "5G SA"
            NetworkMode.OTHER -> "2G/3G"
            NetworkMode.UNKNOWN -> "–"
        }
        LabelValue(
            label = stringResource(R.string.carrier_network_mode_label),
            value = modeStr,
        )
    }
}

// ---- NR 卡片 ----

@Composable
private fun NrCard(
    nr: NrMetrics,
    mode: NetworkMode,
    gnbBits: Int,
    onSetGnbBits: (Int) -> Unit,
    anchorCellType: String? = null,
) {
    val context = LocalContext.current
    MetricCard(title = stringResource(R.string.nr_section_title)) {
        // SA 用 NR 自己查到的类型；NSA 没有 NCI，退回锚点 LTE 小区的类型
        val cellType = nr.cellType ?: anchorCellType
        cellType?.let { type ->
            val display =
                if (nr.cellType == null) type + stringResource(R.string.cell_type_from_anchor)
                else type
            LabelValue(stringResource(R.string.cell_type_label), display)
        }
        nr.ssRsrp?.let {
            val display = "$it dBm"
            LabelValue("SS-RSRP", display) {
                copyToClipboard(context, "SS-RSRP", display)
            }
        }
        nr.ssSinr?.let {
            val display = "%.1f dB".format(it)
            LabelValue("SS-SINR", display) {
                copyToClipboard(context, "SS-SINR", display)
            }
        }
        nr.ssRsrq?.let {
            val display = "$it dB"
            LabelValue("SS-RSRQ", display) {
                copyToClipboard(context, "SS-RSRQ", display)
            }
        }

        if (mode == NetworkMode.NR_NSA) {
            Text(
                stringResource(R.string.nr_nsa_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            nr.nci?.let { nci ->
                val split = CellIdFormatter.splitNrNci(nci, gnbBits)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            // 复制分割后的值
                            copyToClipboard(context, "NCI", split ?: nci.toString())
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("NCI", fontWeight = FontWeight.Medium)
                    Column(horizontalAlignment = Alignment.End) {
                        MonoText(CellIdFormatter.plain(nci) ?: "–")
                        MonoText(split ?: "–")
                    }
                }
                GnbBitsSelector(gnbBits, onSetGnbBits)
            }
            nr.pci?.let {
                LabelValue("PCI", it.toString()) {
                    copyToClipboard(context, "PCI", it.toString())
                }
            }
            nr.tac?.let {
                LabelValue("TAC", it.toString()) {
                    copyToClipboard(context, "TAC", it.toString())
                }
            }
            nr.nrarfcn?.let { arfcn ->
                val displayValue = nr.band?.let { "$arfcn ($it)" } ?: arfcn.toString()
                LabelValue("NR-ARFCN", displayValue) {
                    copyToClipboard(context, "NR-ARFCN", arfcn.toString())
                }
            }
        }
    }
}

@Composable
private fun GnbBitsSelector(gnbBits: Int, onSetGnbBits: (Int) -> Unit) {
    val options = listOf(22, 24, 26, 28, 32)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 不指定 style，继承与上方各行标签相同的字号
        Text(
            stringResource(R.string.gnb_bits_label),
            fontWeight = FontWeight.Medium,
        )
        SingleChoiceSegmentedButtonRow(
            // 固定高度会把子项默认的 40dp 最小高度压下来
            modifier = Modifier
                .width(148.dp)
                .height(28.dp),
        ) {
            options.forEachIndexed { index, bits ->
                SegmentedButton(
                    selected = gnbBits == bits,
                    onClick = { onSetGnbBits(bits) },
                    shape = SegmentedButtonDefaults.itemShape(index, options.size),
                    icon = { /* 去掉勾选标记 */ },
                    colors = SegmentedButtonDefaults.colors(
                        // primary 直接用偏深，兑一点白往回提亮
                        activeContainerColor = MaterialTheme.colorScheme.primary
                            .copy(alpha = 0.78f),
                        activeContentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    // 默认左右各 12dp，格子窄了就把数字挤成两行，这里压到 0
                    contentPadding = PaddingValues(0.dp),
                    label = {
                        Text(
                            bits.toString(),
                            fontSize = 11.sp,
                            maxLines = 1,
                            softWrap = false,
                        )
                    },
                )
            }
        }
    }
}

// ---- LTE 卡片 ----

@Composable
private fun LteCard(lte: LteMetrics) {
    val context = LocalContext.current
    MetricCard(title = stringResource(R.string.lte_section_title)) {
        // 工参匹配到的覆盖类型，匹配不到就整行不显示
        lte.cellType?.let { type ->
            LabelValue(stringResource(R.string.cell_type_label), type)
        }
        lte.rsrp?.let {
            val display = "$it dBm"
            LabelValue("RSRP", display) {
                copyToClipboard(context, "RSRP", display)
            }
        }
        lte.sinr?.let {
            val display = "%.1f dB".format(it)
            LabelValue("SINR", display) {
                copyToClipboard(context, "SINR", display)
            }
        }
        lte.rsrq?.let {
            val display = "$it dB"
            LabelValue("RSRQ", display) {
                copyToClipboard(context, "RSRQ", display)
            }
        }
        lte.rssi?.let {
            val display = "$it dBm"
            LabelValue("RSSI", display) {
                copyToClipboard(context, "RSSI", display)
            }
        }
        lte.cqi?.let {
            LabelValue("CQI", it.toString()) {
                copyToClipboard(context, "CQI", it.toString())
            }
        }
        lte.eci?.let { eci ->
            val split = CellIdFormatter.splitLteEci(eci)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        // 复制分割后的值
                        copyToClipboard(context, "ECI", split ?: eci.toString())
                    },
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("ECI", fontWeight = FontWeight.Medium)
                Column(horizontalAlignment = Alignment.End) {
                    MonoText(CellIdFormatter.plain(eci) ?: "–")
                    MonoText(split ?: "–")
                }
            }
        }
        lte.pci?.let {
            LabelValue("PCI", it.toString()) {
                copyToClipboard(context, "PCI", it.toString())
            }
        }
        lte.tac?.let {
            LabelValue("TAC", it.toString()) {
                copyToClipboard(context, "TAC", it.toString())
            }
        }
        lte.earfcn?.let { earfcn ->
            val displayValue = lte.band?.let { "$earfcn ($it)" } ?: earfcn.toString()
            LabelValue("EARFCN", displayValue) {
                copyToClipboard(context, "EARFCN", earfcn.toString())
            }
        }
    }
}

// ---- 邻区卡片 ----

@Composable
private fun NeighborsCard(neighbors: List<NeighborCell>) {
    var expanded by remember { mutableStateOf(true) }
    val collapsedLabel = stringResource(R.string.neighbors_expand, neighbors.size)
    MetricCard(
        title = stringResource(R.string.neighbors_section_title),
        trailing = {
            ExpandToggle(
                expanded = expanded,
                collapsedLabel = collapsedLabel,
                onClick = { expanded = !expanded },
            )
        },
    ) {
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                neighbors.forEach { cell ->
                    NeighborRow(cell)
                }
            }
        }
    }
}

@Composable
private fun NeighborRow(cell: NeighborCell) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
    ) {
        // 第一行：PCI · 短格式小区号 · 小区名
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                when (cell.type) {
                    CellType.LTE -> "LTE"
                    CellType.NR -> "NR"
                },
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "PCI ${cell.pci ?: "–"}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
            // 短格式小区号：1556804-503（gNB-ID-小区号）或 12345-78（eNB-ID-小区号）
            cell.cellId?.let { cellId ->
                Text(
                    " · ${formatShortCellId(cell.type, cellId)}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            // 小区名
            cell.cellName?.let { name ->
                Text(
                    " · $name",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // 第二行：RSRP · 频段
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "RSRP ${cell.rsrp ?: "–"} dBm",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            cell.band?.let {
                Text(
                    " · $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * 把完整小区号格式化成短格式：gNB-ID-小区号 或 eNB-ID-小区号。
 * 例如 NCI 6376669687 → "1556804-503"，ECI 12345678 → "48225-78"
 */
private fun formatShortCellId(type: CellType, cellId: Long): String {
    return when (type) {
        CellType.LTE -> {
            // ECI：前 20 bit 是 eNB-ID，后 8 bit 是小区号
            val enbId = (cellId shr 8).toInt()
            val sectorId = (cellId and 0xFF).toInt()
            "$enbId-$sectorId"
        }
        CellType.NR -> {
            // NCI：前 24 bit 是 gNB-ID，后 12 bit 是小区号
            val gnbId = (cellId shr 12).toInt()
            val sectorId = (cellId and 0xFFF).toInt()
            "$gnbId-$sectorId"
        }
    }
}

// ---- 小区明细卡片 ----

@Composable
private fun CellDetailCard(infos: List<CellDetailInfo>) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(true) }
    val collapsedLabel = stringResource(R.string.cell_detail_expand)
    MetricCard(
        title = stringResource(R.string.cell_detail_section_title),
        trailing = {
            ExpandToggle(
                expanded = expanded,
                collapsedLabel = collapsedLabel,
                onClick = { expanded = !expanded },
            )
        },
    ) {
        AnimatedVisibility(expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                infos.forEachIndexed { index, info ->
                    // 多段之间加分隔线，一眼看出 4G / 5G 各自的明细
                    if (index > 0) HorizontalDivider()
                    CellDetailSection(info, context)
                }
            }
        }
    }
}

/**
 * 卡片标题右侧的折叠开关。展开时显示"收起"，收起时显示"展开"。
 *
 * 单独抽出来是因为邻区和小区明细都要塞到 MetricCard 的 trailing 里，
 * 内联写两遍不划算。
 */
@Composable
private fun ExpandToggle(expanded: Boolean, collapsedLabel: String, onClick: () -> Unit) {
    // 卡片标题行本身高度就 ~24dp，用默认 40dp 的 TextButton 会撑高一行，
    // 压到 28dp、内边距收窄，让开关跟标题对齐。
    // "收起"两个字通用，"展开"文案由外部传（邻区要带个数）。
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
        modifier = Modifier.height(28.dp),
    ) {
        Text(if (expanded) stringResource(R.string.neighbors_collapse) else collapsedLabel)
    }
}

@Composable
private fun CellDetailSection(info: CellDetailInfo, context: Context) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        // 制式标题：让"这段是 4G 还是 5G"一目了然，NSA 下也不会把锚点明细误当成 5G 的
        Text(
            when (info.type) {
                CellType.LTE -> "4G"
                CellType.NR -> "5G"
            },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
        info.cellId?.let { cellId ->
            val label = stringResource(R.string.cell_detail_cell_id)
            val value = cellId.toString()
            LabelValue(label, value) { copyToClipboard(context, label, value) }
        }
        info.detail?.let { detail ->
            val siteLabel = stringResource(R.string.cell_detail_site_name)
            val cellLabel = stringResource(R.string.cell_detail_cell_name)
            LabelValue(siteLabel, detail.siteName) {
                copyToClipboard(context, siteLabel, detail.siteName)
            }
            LabelValue(cellLabel, detail.cellName) {
                copyToClipboard(context, cellLabel, detail.cellName)
            }
        }
    }
}

// ---- 基础组件 ----

@Composable
private fun MetricCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 标题行：可选的 trailing 右侧塞可折叠等控件，标题和它同一行
            if (trailing == null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    trailing()
                }
            }
            HorizontalDivider()
            content()
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String, onCopy: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onCopy != null) Modifier.clickable(onClick = onCopy)
                else Modifier
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.35f, fill = false),  // 标签占 35%，不强制填充
        )
        MonoText(
            value,
            modifier = Modifier.weight(0.65f, fill = false),  // 值占 65%，允许换行
            textAlign = TextAlign.End,  // 右对齐
        )
    }
}

@Composable
private fun MonoText(text: String, modifier: Modifier = Modifier, textAlign: TextAlign? = null) {
    Text(
        text,
        fontFamily = FontFamily.Monospace,
        modifier = modifier,
        textAlign = textAlign,
    )
}

// ---- 工具 ----

private fun copyToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(context, "已复制 $label", Toast.LENGTH_SHORT).show()
}

private fun openLocationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
    context.startActivity(intent)
}
