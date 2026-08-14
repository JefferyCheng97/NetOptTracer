package com.jeffery.cellularmonitor.ui.map

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.MapsInitializer
import com.amap.api.maps.model.BitmapDescriptorFactory
import com.amap.api.maps.model.CameraPosition
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.LatLngBounds
import com.amap.api.maps.model.Marker
import com.amap.api.maps.model.MarkerOptions
import com.amap.api.maps.model.MyLocationStyle
import com.jeffery.cellularmonitor.R
import com.jeffery.cellularmonitor.data.CellTypeClassifier
import com.jeffery.cellularmonitor.data.GroupedCell
import com.jeffery.cellularmonitor.data.SiteGroup
import com.jeffery.cellularmonitor.data.SiteMarker
import com.jeffery.cellularmonitor.ui.theme.CellularMonitorTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 独立的地图页面。用高德离线地图做底图，显示当前定位与工参里的基站分布。
 *
 * 用独立 Activity 而不是主 Screen 里的一个页面，因为：
 *   1. 高德 MapView 有自己的生命周期方法（onCreate/onResume/onPause/onDestroy），
 *      跟 Activity 生命周期绑起来最简单
 *   2. 关掉地图页时能立刻释放地图资源，不占主界面的信号采集
 */
class MapActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 高德 10.x 要求应用先声明合规——不调这两个方法会返回空白地图
        MapsInitializer.updatePrivacyShow(this, true, true)
        MapsInitializer.updatePrivacyAgree(this, true)

        setContent {
            CellularMonitorTheme {
                MapScreen(onBack = { finish() })
            }
        }
    }
}

@Composable
private fun MapScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    // 首次进入没权限的话下面的 MyLocationStyle 也没用，先记一下状态给 UI 提示
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val requestPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasLocationPermission = granted }

    val mapViewHolder = remember { MapViewHolder() }

    // 地图类型：true = 卫星图，false = 标准地图
    var isSatelliteMap by remember { mutableStateOf(false) }

    // 扇区显示开关，默认关闭——扇区绘制成本高，需要用户手动打开
    var sectorsVisible by remember { mutableStateOf(false) }

    // 搜索状态
    var searchQuery by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<SiteMarker>>(emptyList()) }

    // 站点详情 Dialog：一个基站里可能有多个小区，弹出时一次显示全部
    var selectedSite by remember { mutableStateOf<SiteGroup?>(null) }

    DisposableEffect(Unit) {
        if (!hasLocationPermission) {
            requestPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        onDispose { mapViewHolder.destroy() }
    }

    // 进入地图页后异步加载工参里的所有带坐标基站——2 万+ 条，工参加载走 IO 线程
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) {
        val classifier = withContext(Dispatchers.IO) {
            CellTypeClassifier.load(context)
        }
        // sitesWithLocation() 返回的坐标已经是 GCJ-02（工参原始是 WGS-84，函数内部做了转换）
        val sites = classifier.sitesWithLocation().toList()
        val groups = classifier.groupedSitesWithLocation()
        mapViewHolder.setSites(sites, groups)
    }

    // 把站点点击事件传给 Compose 状态
    mapViewHolder.onSiteClick = { site -> selectedSite = site }

    Column(modifier = Modifier.fillMaxSize()) {
        MapTopBar(
            onBack = onBack,
            onLocateMe = {
                // GPS 还没首次定位时按了这个按钮，Toast 提示，别让镜头飞到 (0,0) 大西洋
                if (!mapViewHolder.moveToMyLocation()) {
                    Toast.makeText(context, R.string.map_locating_wait, Toast.LENGTH_SHORT).show()
                }
            },
            onSearch = { searchActive = true },
            isSatellite = isSatelliteMap,
            onToggleMapType = {
                isSatelliteMap = !isSatelliteMap
                mapViewHolder.setMapType(isSatelliteMap)
            },
            sectorsVisible = sectorsVisible,
            onToggleSectors = {
                sectorsVisible = !sectorsVisible
                mapViewHolder.setSectorsVisible(sectorsVisible)
            },
        )
        Box(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    MapView(ctx).also { view ->
                        view.onCreate(null)
                        mapViewHolder.attach(view)
                    }
                },
            )
            if (!hasLocationPermission) {
                Text(
                    stringResource(R.string.map_need_location),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(16.dp)
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }

            // 搜索浮层：在地图上方
            if (searchActive) {
                SearchOverlay(
                    query = searchQuery,
                    onQueryChange = { q ->
                        searchQuery = q
                        val allSites = mapViewHolder.getAllSites()
                        searchResults = if (q.isBlank()) emptyList() else {
                            allSites.filter {
                                it.cellName.contains(q, ignoreCase = true) ||
                                it.siteName.contains(q, ignoreCase = true) ||
                                it.cgi.contains(q, ignoreCase = true)
                            }.take(50)  // 最多 50 条，避免列表太长卡顿
                        }
                    },
                    results = searchResults,
                    onResultClick = { site ->
                        mapViewHolder.flyToSite(site)
                        searchActive = false
                        searchQuery = ""
                        searchResults = emptyList()
                    },
                    onDismiss = {
                        searchActive = false
                        searchQuery = ""
                        searchResults = emptyList()
                    },
                )
            }

            // 站点详情 Dialog
            selectedSite?.let { site ->
                SiteDetailDialog(
                    site = site,
                    onNavigate = {
                        navigateToSite(context, site)
                    },
                    onDismiss = { selectedSite = null },
                )
            }
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME -> mapViewHolder.resume()
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> mapViewHolder.pause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun MapTopBar(
    onBack: () -> Unit,
    onLocateMe: () -> Unit,
    onSearch: () -> Unit,
    isSatellite: Boolean,
    onToggleMapType: () -> Unit,
    sectorsVisible: Boolean,
    onToggleSectors: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onBack) {
            Text(
                "<",
                style = MaterialTheme.typography.headlineMedium,  // 返回箭头大一点
            )
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onToggleMapType) {
            Text(if (isSatellite) "标准" else "卫星")
        }
        TextButton(onClick = onToggleSectors) {
            // 开启时字重加粗表示"当前生效"，关闭时正常字重
            Text(
                if (sectorsVisible) "扇区" else "扇区",
                fontWeight = if (sectorsVisible) FontWeight.Bold else FontWeight.Normal,
            )
        }
        TextButton(onClick = onSearch) {
            Text("🔍")  // 搜索图标
        }
        TextButton(onClick = onLocateMe) {
            Text(stringResource(R.string.map_locate_me))
        }
    }
}

/**
 * 搜索浮层：顶部一个输入框，下面是结果列表。
 *
 * 盖在地图上而不是新开一个页面——搜完点一下就要看地图，来回跳页反而碍事。
 */
@Composable
private fun SearchOverlay(
    query: String,
    onQueryChange: (String) -> Unit,
    results: List<SiteMarker>,
    onResultClick: (SiteMarker) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
            )
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.search_clear))
            }
        }

        if (query.isNotBlank()) {
            if (results.isEmpty()) {
                Text(
                    stringResource(R.string.search_no_result),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    stringResource(R.string.search_result_count, results.size),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 限高，别把整个地图盖住
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                ) {
                    items(results.size) { index ->
                        val site = results[index]
                        SearchResultRow(site, onClick = { onResultClick(site) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(site: SiteMarker, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(site.cellName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        Text(
            "${site.carrier.displayName} / ${site.type} / ${site.cgi}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * 站点详情 Dialog。显示一个基站下的所有小区，并提供「导航到此」按钮。
 * 每个小区一行，展示小区名 · 类型 · CGI · 方位角。
 */
@Composable
private fun SiteDetailDialog(
    site: SiteGroup,
    onNavigate: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.site_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // 基站名可点击复制
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.site_info_site),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = site.siteName,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { copyToClipboard(context, "基站名", site.siteName) },
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    )
                }
                InfoRow(stringResource(R.string.site_info_carrier), site.carrier.displayName)
                InfoRow(stringResource(R.string.site_info_type), site.type)
                HorizontalDivider()
                Text(
                    "小区列表（${site.cells.size}）",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                )
                // 小区多的时候能滚动查看，不至于把 Dialog 撑得超屏
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    site.cells.forEach { cell ->
                        CellRow(cell, site.carrier)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onNavigate()
                onDismiss()
            }) {
                Text(stringResource(R.string.site_navigate))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_ok))
            }
        },
    )
}

@Composable
private fun CellRow(cell: GroupedCell, carrier: com.jeffery.cellularmonitor.data.GongcanParser.Carrier) {
    val context = LocalContext.current
    val shortId = formatShortCellId(carrier, cell.cellId)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { copyToClipboard(context, "小区号", shortId) }
            .padding(vertical = 4.dp),
    ) {
        Text(cell.cellName, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        val azimuthStr = if (cell.azimuths.isNotEmpty()) {
            cell.azimuths.sorted().joinToString(", ") { "${it}°" }
        } else "-"
        Text(
            "$shortId · ${cell.type} · $azimuthStr",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 把完整小区号格式化成短格式：eNB-ID-小区号 或 gNB-ID-小区号。
 * 根据运营商判断 4G 还是 5G（移动和电信工参混合 4G/5G，但每个 cellId 只属于一种）。
 */
private fun formatShortCellId(carrier: com.jeffery.cellularmonitor.data.GongcanParser.Carrier, cellId: Long): String {
    // 用启发式规则：5G NCI 通常很大（36 bit），4G ECI 较小（28 bit）
    // 但更准确的是看类型——不过这里只有 cellId，用位数判断
    return if (cellId > 0xFFFFFFF) {
        // 大概率是 5G NCI：前 24 bit 是 gNB-ID，后 12 bit 是小区号
        val gnbId = (cellId shr 12).toInt()
        val sectorId = (cellId and 0xFFF).toInt()
        "$gnbId-$sectorId"
    } else {
        // 4G ECI：前 20 bit 是 eNB-ID，后 8 bit 是小区号
        val enbId = (cellId shr 8).toInt()
        val sectorId = (cellId and 0xFF).toInt()
        "$enbId-$sectorId"
    }
}

private fun copyToClipboard(context: android.content.Context, label: String, text: String) {
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    clipboard?.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
    Toast.makeText(context, "已复制 $label", Toast.LENGTH_SHORT).show()
}

@Composable
private fun InfoRow(label: String, value: String) {
    // 标签靠左、值靠右——Row 撑满宽度，label 定宽，value 占剩余空间并 end 对齐
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 唤起高德地图 App 导航到指定站点。
 *
 * 用高德自定义 URI Scheme：amapuri://route/plan/?dlat=纬度&dlon=经度&dname=名称&dev=0&t=0
 * - dev=0: 坐标系是 GCJ-02（跟工参一致）
 * - t=0: 驾车导航
 *
 * 如果用户没装高德，catch ActivityNotFoundException 并 Toast 提示。
 */
private fun navigateToSite(context: android.content.Context, site: SiteGroup) {
    val uri = android.net.Uri.parse(
        "amapuri://route/plan/?dlat=${site.latitude}&dlon=${site.longitude}" +
            "&dname=${android.net.Uri.encode(site.siteName)}&dev=0&t=0"
    )
    val intent = Intent(Intent.ACTION_VIEW, uri)
    try {
        context.startActivity(intent)
    } catch (e: android.content.ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.navigate_no_amap), Toast.LENGTH_SHORT).show()
    }
}

/**
 * MapView 的持有者。把高德 SDK 的命令式生命周期跟 Compose 的声明式方式桥接起来。
 *
 * 也负责基站叠加的所有状态：
 *   - allSites: 工参里所有带坐标的基站，加载一次就不变
 *   - visibleMarkers: 当前地图上显示的 Marker，视口变化时增删
 *   - didAutoCenter: 首次拿到定位时自动居中一次，之后由用户控制
 *   - onSiteClick: Marker 点击回调，传给 Compose 状态
 */
private class MapViewHolder {
    private var view: MapView? = null
    private var aMap: AMap? = null

    /** 高德定位 SDK 客户端，比地图自带定位精度高 */
    private var locationClient: AMapLocationClient? = null

    /** 工参里所有带坐标的小区；加载后不变。搜索用（按小区名/CGI 匹配）。 */
    private var allSites: List<SiteMarker> = emptyList()

    /**
     * 按基站聚合的分组。地图打点用这个——一个基站一个 Marker，避免重叠。
     * key 是 primaryCellId，视口过滤时按这个增删。
     */
    private var allGroups: List<SiteGroup> = emptyList()

    /** 小区 cellId → 所属 group 的 primaryCellId。搜索定位时用（搜到小区反查它属于哪个 marker）。 */
    private var cellToGroupPrimary: Map<Long, Long> = emptyMap()

    /** primaryCellId → Marker，视口过滤时按这个增删。 */
    private val visibleMarkers = HashMap<Long, Marker>()

    /**
     * primaryCellId → 该基站的所有扇区 Polygon。一个基站多个小区、多个方位角合并画出来。
     * 视口过滤和"扇区开关"共用这份状态：开关关掉时全部 remove，开关打开时按视口重画。
     */
    private val visibleSectors = HashMap<Long, List<com.amap.api.maps.model.Polygon>>()

    /** 扇区显示开关，UI 层同步过来。默认关闭。 */
    private var sectorsVisible = false

    /** 首次拿到定位就把镜头拉过去，之后不再自动跟随（用户手动拖动会被打断）。 */
    private var didAutoCenter = false

    /**
     * 搜索跳转的目标 group primaryCellId。镜头飞过去、Marker 建好之后要弹它的信息窗，
     * 但那一刻还在 animateCamera 途中，只能记下来等 onCameraChangeFinish 再处理。
     */
    private var pendingInfoWindowCellId: Long? = null

    /** Marker 点击回调，由 Compose 状态设置。传出整个基站分组（含所有小区）。 */
    var onSiteClick: ((SiteGroup) -> Unit)? = null

    /**
     * 最新一次定位缓存。用于「回到我的位置」——不能依赖 [AMap.getMyLocation]，
     * 它不总是同步 SDK 内部状态（尤其是 LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER 模式，
     * 以及 App 切换回来时），常常返回 null 或 (0,0) 即使地图上能看见蓝色箭头。
     * 自己在 setOnMyLocationChangeListener 里缓存最靠谱。
     */
    private var lastKnownLocation: LatLng? = null

    /**
     * 视口刷新节流用。双指缩放会短时间内触发多次 onCameraChangeFinish，
     * 每次都遍历几万个 site + 创建 marker/polygon 会把主线程卡死。
     * 用一个 Handler 合并成一次执行——只处理最后一次调用。
     */
    private val refreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val refreshRunnable = Runnable { refreshVisibleMarkers() }

    /** 定位回调：更新地图上的蓝点，缓存位置，首次自动居中 */
    private val locationListener = AMapLocationListener { location ->
        if (location == null) return@AMapLocationListener
        // 定位失败检查
        if (location.errorCode != 0) {
            android.util.Log.w("MapViewHolder", "定位失败: ${location.errorCode} ${location.errorInfo}")
            return@AMapLocationListener
        }
        val lat = location.latitude
        val lon = location.longitude
        if (lat == 0.0 && lon == 0.0) return@AMapLocationListener

        val newPos = LatLng(lat, lon)
        // 缓存最新有效位置（回到我的位置按钮用）
        lastKnownLocation = newPos

        // 首次自动居中
        if (!didAutoCenter) {
            aMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(newPos, 17f))
            didAutoCenter = true
        }
    }

    fun attach(view: MapView) {
        this.view = view
        val map = view.map
        aMap = map

        // 配置高德定位客户端（地图 SDK 内置，高精度模式：GPS + 网络 + 基站融合）
        locationClient = AMapLocationClient(view.context).apply {
            setLocationOption(AMapLocationClientOption().apply {
                // 高精度定位模式：GPS + 网络 + 基站融合，精度最高（1-5 米）
                locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                // 持续定位，每次回调间隔 2 秒
                interval = 2000
                // 单次定位超时 20 秒（首次冷启动 GPS 锁星慢，给足时间）
                httpTimeOut = 20000
                // 不需要地址信息，只要坐标
                isNeedAddress = false
                // 允许使用缓存定位（快速返回上次位置，然后更新到最新）
                isLocationCacheEnable = true
                // 关闭模拟位置检测（开发时用模拟器/假位置测试会被拦截）
                isMockEnable = true
                // 传感器开关：开启后定位算法会融合加速度计/陀螺仪，提升精度和平滑度
                isSensorEnable = true
            })
            setLocationListener(locationListener)
        }

        // 地图显示定位图层（蓝色箭头跟随定位 SDK 的位置和方向，但地图不自动居中）
        val style = MyLocationStyle()
            .myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE_NO_CENTER)
            // 不显示精度圈
            .strokeWidth(0f)
            .strokeColor(0x00_000000.toInt())
            .radiusFillColor(0x00_000000.toInt())
        map.myLocationStyle = style
        map.isMyLocationEnabled = true
        map.uiSettings.isMyLocationButtonEnabled = false
        map.uiSettings.isZoomControlsEnabled = false

        // 视口变化后重画基站：拖动/缩放停下后刷一次；
        // 用 changeFinish 而不是 change，避免拖动过程中每一帧都在增删 Marker
        // 双指连续缩放会短时间内触发多次 changeFinish，用 postDelayed 合并成一次
        map.setOnCameraChangeListener(object : AMap.OnCameraChangeListener {
            override fun onCameraChange(position: CameraPosition?) {}
            override fun onCameraChangeFinish(position: CameraPosition?) {
                scheduleRefresh()
            }
        })

        // 点击空白区域（不是 Marker）关闭当前显示的 InfoWindow
        map.setOnMapClickListener {
            // 高德不会自动关闭所有 Marker 的 InfoWindow，需要手动遍历
            visibleMarkers.values.forEach { it.hideInfoWindow() }
        }

        // 点击 Marker 时触发回调，弹出站点详情 Dialog
        map.setOnMarkerClickListener { marker ->
            val primaryCellId = visibleMarkers.entries.find { it.value == marker }?.key
            if (primaryCellId != null) {
                val group = allGroups.find { it.primaryCellId == primaryCellId }
                group?.let { onSiteClick?.invoke(it) }
            }
            true  // 消费事件，不触发 onMapClick
        }
    }

    /**
     * 工参加载完毕后调用，喂进所有小区（搜索用）和按基站聚合后的分组（打点用）。
     * 加载后立即刷一次视口。
     */
    fun setSites(sites: List<SiteMarker>, groups: List<SiteGroup>) {
        allSites = sites
        allGroups = groups
        cellToGroupPrimary = buildMap {
            for (group in groups) {
                for (cell in group.cells) {
                    put(cell.cellId, group.primaryCellId)
                }
            }
        }
        // 地图可能还没准备好 projection——但没关系，如果视口过滤时 aMap 为 null 就跳过；
        // 首次定位居中后的 onCameraChangeFinish 会再刷一次
        refreshVisibleMarkers()
    }

    /** 是否已经加载过基站数据（用于判断要不要触发重新加载）。 */
    fun hasSites(): Boolean = allSites.isNotEmpty()

    /** 供搜索用的全量小区列表。 */
    fun getAllSites(): List<SiteMarker> = allSites

    /**
     * 飞到某个小区所在的基站并弹出它的信息窗。
     *
     * 搜的可能是任何一个小区，但地图上只有它所属基站的 marker——用 cellToGroupPrimary
     * 反查所属 group 的 primaryCellId，等 onCameraChangeFinish 补出 marker 后 show。
     */
    fun flyToSite(site: SiteMarker) {
        val map = aMap ?: return
        val primary = cellToGroupPrimary[site.cellId] ?: site.cellId
        val group = allGroups.find { it.primaryCellId == primary }
        val target = group?.let { LatLng(it.latitude, it.longitude) }
            ?: LatLng(site.latitude, site.longitude)
        pendingInfoWindowCellId = primary
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(target, 17f))
    }

    /**
     * 节流的视口刷新入口。多次连续调用会合并成一次——真正的重画在
     * [REFRESH_DEBOUNCE_MS] 后执行，如果这期间又有调用，重新计时。
     * 双指缩放能连续 fire 好几次 onCameraChangeFinish，不节流会把主线程卡死。
     */
    private fun scheduleRefresh() {
        refreshHandler.removeCallbacks(refreshRunnable)
        refreshHandler.postDelayed(refreshRunnable, REFRESH_DEBOUNCE_MS)
    }

    /**
     * 按当前视口过滤基站。缩得太远时不画（几万个 Marker 卡）。
     *
     * 高德的 [AMap.getMapScreenMarkers] 只能拿到已加上去的 Marker，没法直接问
     * "视口里应该显示哪些点"，所以自己遍历 [allSites] 用 [LatLngBounds.contains] 判断。
     */
    private fun refreshVisibleMarkers() {
        val map = aMap ?: return
        if (allGroups.isEmpty()) return

        // 缩得太远（大范围看全国/全省）时几万个点会卡，直接清空
        val zoom = map.cameraPosition?.zoom ?: return
        if (zoom < MIN_MARKER_ZOOM) {
            if (visibleMarkers.isNotEmpty()) {
                visibleMarkers.values.forEach { it.remove() }
                visibleMarkers.clear()
            }
            clearAllSectors()
            return
        }

        val bounds: LatLngBounds = map.projection.visibleRegion.latLngBounds
        val shouldShow = HashSet<Long>(256)
        // 扇区比 marker 更耗性能，zoom 阈值比 marker 高一档
        val shouldDrawSectors = sectorsVisible && zoom >= MIN_SECTOR_ZOOM
        // 视野内 marker 数量上限：超过就在这一帧不再新增，避免视野宽时一次性建几千个卡死
        // 已经建出来的不动，用户再缩放/移动一下就会补齐
        var markersAddedThisFrame = 0
        for (group in allGroups) {
            val ll = LatLng(group.latitude, group.longitude)
            if (bounds.contains(ll)) {
                shouldShow.add(group.primaryCellId)
                if (!visibleMarkers.containsKey(group.primaryCellId)) {
                    if (visibleMarkers.size + markersAddedThisFrame >= MAX_VISIBLE_MARKERS) continue
                    val marker = map.addMarker(
                        MarkerOptions()
                            .position(ll)
                            .title(group.siteName)
                            .snippet("${group.cells.size} 个小区 / ${group.carrier.displayName} / ${group.type}")
                            .icon(iconFor(group.carrier, group.type))
                            .anchor(0.5f, 0.5f)
                    )
                    visibleMarkers[group.primaryCellId] = marker
                    markersAddedThisFrame++
                }
                // 扇区独立管理：开关关或 zoom 不够时不画。同基站多个小区的方位角一起画。
                if (shouldDrawSectors && group.cells.any { it.azimuths.isNotEmpty() }
                    && !visibleSectors.containsKey(group.primaryCellId)
                ) {
                    visibleSectors[group.primaryCellId] = drawSectorsForGroup(map, group)
                }
            }
        }
        // 视口移走的点：Marker 数量多的时候不能每次全清全画，那样拖动手感会闪
        val markerIter = visibleMarkers.entries.iterator()
        while (markerIter.hasNext()) {
            val entry = markerIter.next()
            if (entry.key !in shouldShow) {
                entry.value.remove()
                markerIter.remove()
            }
        }
        // 视口外的扇区，或者开关关了/zoom 不够时视口内的扇区，都清掉
        val sectorIter = visibleSectors.entries.iterator()
        while (sectorIter.hasNext()) {
            val entry = sectorIter.next()
            if (!shouldDrawSectors || entry.key !in shouldShow) {
                entry.value.forEach { it.remove() }
                sectorIter.remove()
            }
        }
        // 搜索跳过来的目标：Marker 现在肯定建好了，可以弹它的信息窗
        pendingInfoWindowCellId?.let { cellId ->
            visibleMarkers[cellId]?.showInfoWindow()
            pendingInfoWindowCellId = null
        }
    }

    /**
     * 给一个基站画所有扇区，每个方位角一个 Polygon。
     *
     * 扇区几何：以基站为顶点，从 [方位角 - SECTOR_ANGLE_DEG/2] 到 [方位角 + SECTOR_ANGLE_DEG/2]
     * 画一个扇形，半径 SECTOR_RADIUS_M。扇形用多边形近似——沿角度采样 SECTOR_STEPS 个点，
     * 加基站中心一共 SECTOR_STEPS+1 个顶点。
     *
     * 方位角是 0° 正北顺时针，跟数学里的 0° 正东逆时针不一样，转换公式是：
     *   math_angle = 90° - azimuth
     */
    /**
     * 画一个基站分组的所有扇区。基站下每个小区的每个方位角各画一片扇区，
     * 颜色按基站的主类型统一（marker 图标也是这个颜色）。
     */
    private fun drawSectorsForGroup(map: AMap, group: SiteGroup): List<com.amap.api.maps.model.Polygon> {
        val fillColor = sectorColor(group.carrier, group.type)
        val allAzimuths = group.cells.flatMap { it.azimuths }.distinct()
        val result = ArrayList<com.amap.api.maps.model.Polygon>(allAzimuths.size)
        for (azimuth in allAzimuths) {
            val opts = com.amap.api.maps.model.PolygonOptions()
                .fillColor(fillColor)
                .strokeColor(fillColor or 0xFF_000000.toInt())  // 边界不透明，扇区形状更清晰
                .strokeWidth(1f)
            opts.add(LatLng(group.latitude, group.longitude))
            val start = azimuth - SECTOR_ANGLE_DEG / 2.0
            val end = azimuth + SECTOR_ANGLE_DEG / 2.0
            for (i in 0..SECTOR_STEPS) {
                val bearing = start + (end - start) * i / SECTOR_STEPS
                val (lat, lon) = offsetLatLng(group.latitude, group.longitude, SECTOR_RADIUS_M, bearing)
                opts.add(LatLng(lat, lon))
            }
            result.add(map.addPolygon(opts))
        }
        return result
    }

    /**
     * 从 (lat, lon) 出发，向 [bearing]（0°=北，顺时针）方向前进 [distanceM] 米后的坐标。
     *
     * 用简化的平面近似：地球半径按 6378137 m 算，纬度上 1° ≈ 111320 m，经度上按 cos(lat) 缩放。
     * 50 米范围内误差在厘米级，够用。
     */
    private fun offsetLatLng(lat: Double, lon: Double, distanceM: Double, bearingDeg: Double): DoubleArray {
        val bearingRad = Math.toRadians(bearingDeg)
        val dNorth = distanceM * kotlin.math.cos(bearingRad)  // 沿纬度方向的位移，米
        val dEast = distanceM * kotlin.math.sin(bearingRad)   // 沿经度方向的位移，米
        val dLat = dNorth / 111320.0
        val dLon = dEast / (111320.0 * kotlin.math.cos(Math.toRadians(lat)))
        return doubleArrayOf(lat + dLat, lon + dLon)
    }

    /** 扇区填充色（带 30% 透明度），跟对应 marker 的色系一致。 */
    private fun sectorColor(carrier: com.jeffery.cellularmonitor.data.GongcanParser.Carrier, type: String): Int {
        // ARGB，A=0x4C 是 30% 透明度
        return when (carrier) {
            com.jeffery.cellularmonitor.data.GongcanParser.Carrier.CMCC -> {
                if ("宏" in type) 0x4C_FFCC00.toInt() else 0x4C_FF8800.toInt()
            }
            com.jeffery.cellularmonitor.data.GongcanParser.Carrier.CTCC -> {
                if ("宏" in type) 0x4C_1E88E5.toInt() else 0x4C_00BCD4.toInt()
            }
        }
    }

    /** 清掉所有扇区。 */
    private fun clearAllSectors() {
        if (visibleSectors.isEmpty()) return
        visibleSectors.values.forEach { list -> list.forEach { it.remove() } }
        visibleSectors.clear()
    }

    /** 顶栏「扇区」开关调过来，切换后要立刻刷新一次视口。 */
    fun setSectorsVisible(visible: Boolean) {
        if (sectorsVisible == visible) return
        sectorsVisible = visible
        refreshVisibleMarkers()
    }

    /**
     * 按运营商 + 覆盖类型给基站上色。
     *
     * 移动用黄橙系（宏站黄色、室分橙色）、电信用蓝色系（宏站蓝色、室分天蓝），
     * 这样地图上能一眼看出基站归属。
     *
     * 用高德默认的 Hue 变体而不是自定义位图——位图要打包资源，颜色变体几乎零成本。
     */
    private fun iconFor(carrier: com.jeffery.cellularmonitor.data.GongcanParser.Carrier, type: String): com.amap.api.maps.model.BitmapDescriptor {
        val hue = when (carrier) {
            com.jeffery.cellularmonitor.data.GongcanParser.Carrier.CMCC -> {
                // 移动：黄橙系
                if ("宏" in type) BitmapDescriptorFactory.HUE_YELLOW
                else BitmapDescriptorFactory.HUE_ORANGE  // 室分用橙色
            }
            com.jeffery.cellularmonitor.data.GongcanParser.Carrier.CTCC -> {
                // 电信：蓝色系
                if ("宏" in type) BitmapDescriptorFactory.HUE_BLUE
                else BitmapDescriptorFactory.HUE_AZURE  // 室分用天蓝
            }
        }
        return BitmapDescriptorFactory.defaultMarker(hue)
    }

    /**
     * 把镜头拉回当前 GPS 位置。
     *
     * 用自己缓存的 [lastKnownLocation] 而不是 [AMap.getMyLocation]——后者在
     * ROTATE_NO_CENTER 模式和 Activity resume 后都会莫名返回 null 或 (0,0)，
     * 哪怕地图上蓝色箭头明明还在。缓存版本只要收到过至少一次有效定位就可用。
     *
     * @return true 表示成功飞过去；false 表示还没收到过定位，调用方可以提示用户
     */
    fun moveToMyLocation(): Boolean {
        val loc = lastKnownLocation ?: return false
        aMap?.animateCamera(CameraUpdateFactory.newLatLngZoom(loc, 16f))
        return true
    }

    /**
     * 切换地图类型。
     *
     * @param isSatellite true = 卫星图（带路网标注），false = 标准矢量地图
     */
    fun setMapType(isSatellite: Boolean) {
        aMap?.mapType = if (isSatellite) AMap.MAP_TYPE_SATELLITE else AMap.MAP_TYPE_NORMAL
    }

    fun resume() {
        view?.onResume()
        // 启动定位
        locationClient?.startLocation()
        // 后台唤醒时重新启用定位组件，否则蓝点不刷新
        aMap?.isMyLocationEnabled = true
    }

    fun pause() {
        view?.onPause()
        // 暂停定位，省电
        locationClient?.stopLocation()
    }

    fun destroy() {
        // 未执行的节流刷新要清掉，否则 Activity 已销毁还在跑
        refreshHandler.removeCallbacks(refreshRunnable)
        // 停止并销毁定位客户端
        locationClient?.stopLocation()
        locationClient?.onDestroy()
        locationClient = null
        // Marker/Polygon 会随 MapView 一起释放，但显式清一下更保险
        visibleMarkers.values.forEach { it.remove() }
        visibleMarkers.clear()
        clearAllSectors()
        view?.onDestroy()
        view = null
        aMap = null
    }

    private companion object {
        /**
         * 低于这个 zoom（约地级市视野）不画基站——几万个点会明显卡顿。
         * 15 大概是"看得清街道"，14 是"看得清一整个区"。
         */
        const val MIN_MARKER_ZOOM = 12f

        /**
         * 扇区比 marker 更耗性能（一个基站可能画 3 个 Polygon），
         * zoom 阈值比 marker 高一档，只有街道级别才画。
         */
        const val MIN_SECTOR_ZOOM = 14f

        /** 扇区半径，米。50 米在街道 zoom 下大概是屏幕上 2-3cm。 */
        const val SECTOR_RADIUS_M = 50.0

        /** 扇区开角，度。天线水平半功率角一般 60-65°，用户偏好 35°更细一些。 */
        const val SECTOR_ANGLE_DEG = 35.0

        /** 扇形圆弧的采样点数。8 段足够平滑，再多性能没意义。 */
        const val SECTOR_STEPS = 8

        /**
         * 视口刷新节流延迟。双指缩放能在 100-200ms 内连续 fire 好几次
         * onCameraChangeFinish，用 150ms 把它们合并成最后一次。
         */
        const val REFRESH_DEBOUNCE_MS = 150L

        /**
         * 单次刷新最多新增的 marker 数。视野特别宽时（比如刚缩到 zoom 12），
         * 视口内可能几千个基站，一次性 addMarker 会卡死主线程。设个上限，
         * 每次只画一部分——用户拖/放一下会补齐，比卡死好。
         */
        const val MAX_VISIBLE_MARKERS = 500
    }
}
