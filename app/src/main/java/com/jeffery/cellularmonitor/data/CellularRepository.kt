package com.jeffery.cellularmonitor.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor

/**
 * 采集所有活动卡槽的信号数据。
 *
 * 每张卡有独立的 [TelephonyManager]（`createForSubscriptionId`）与独立监听器，
 * 两张卡的数据**同时**刷新，切 Tab 只是切换看哪一张。
 *
 * 注意：普通应用无法切换系统默认数据卡（需要 MODIFY_PHONE_STATE 系统权限），
 * 所以这里做的是并行读取而非真正切换卡槽。
 */
class CellularRepository(private val context: Context) {

    companion object {
        private const val TAG = "CellularRepository"
        private const val POLL_INTERVAL_MS = 2_000L
    }

    private val _snapshots = MutableStateFlow<List<SlotSnapshot>>(emptyList())
    val snapshots: StateFlow<List<SlotSnapshot>> = _snapshots.asStateFlow()

    private val _status = MutableStateFlow(CollectorStatus())
    val status: StateFlow<CollectorStatus> = _status.asStateFlow()

    private val subscriptionManager: SubscriptionManager? =
        ContextCompat.getSystemService(context, SubscriptionManager::class.java)
    private val telephonyManager: TelephonyManager? =
        ContextCompat.getSystemService(context, TelephonyManager::class.java)
    private val locationManager: LocationManager? =
        ContextCompat.getSystemService(context, LocationManager::class.java)

    /**
     * 工参表，用来判断宏站/室分。协程里加载、[publish] 里读，跨线程所以要 volatile。
     * 加载完成前是 [CellTypeClassifier.EMPTY]，界面先不显示覆盖类型。
     */
    @Volatile
    private var cellTypes: CellTypeClassifier = CellTypeClassifier.EMPTY

    /** 最后一次位置，所有卡槽共享。 */
    @Volatile
    private var lastLocation: LocationInfo? = null

    /** 回调统一投递到这条后台线程，避免占用主线程。 */
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    /** [start] 之前 handler 还不存在，此时就地执行，不要新建 Handler。 */
    private val executor = Executor { command ->
        val target = handler
        if (target != null) target.post(command) else command.run()
    }

    private var scope: CoroutineScope? = null
    private var pollJob: Job? = null

    /** subId -> 该卡的监听器。订阅变化回调在后台线程，轮询在协程，故用并发容器。 */
    private val monitors = ConcurrentHashMap<Int, SlotMonitor>()

    private var subscriptionListener: SubscriptionManager.OnSubscriptionsChangedListener? = null

    @Volatile
    private var running = false

    /** 开始采集。重复调用是安全的。 */
    fun start() {
        if (running) return
        running = true

        val thread = HandlerThread("cellular-monitor").also { it.start() }
        handlerThread = thread
        handler = Handler(thread.looper)

        val newScope = CoroutineScope(SupervisorJob())
        scope = newScope

        // 工参表在 IO 线程加载，加载完补发一次，让已经显示的页面填上覆盖类型
        newScope.launch { reloadCellTypes() }

        refreshStatus()
        if (!hasRequiredPermissions()) return

        registerSubscriptionListener()
        syncSubscriptions()
        startLocationUpdates()
        startPolling(newScope)
    }

    /**
     * 重新加载工参并刷新界面。导入新工参后调用，不用重启 App。
     */
    suspend fun reloadCellTypes() {
        cellTypes = CellTypeClassifier.load(context)
        publish()
    }

    /** 表里的小区数，0 表示没有可用工参。 */
    fun cellTypeCount(): Int = cellTypes.size

    /** 停止采集并释放所有监听器。 */
    fun stop() {
        if (!running) return
        running = false

        pollJob?.cancel()
        pollJob = null
        scope?.cancel()
        scope = null

        subscriptionListener?.let { listener ->
            runCatching { subscriptionManager?.removeOnSubscriptionsChangedListener(listener) }
        }
        subscriptionListener = null

        monitors.values.forEach { it.stop() }
        monitors.clear()

        handlerThread?.quitSafely()
        handlerThread = null
        handler = null
    }

    /** 权限或定位开关变化后重新评估，供 UI 从设置页返回时调用。 */
    fun retry() {
        stop()
        start()
    }

    private fun hasRequiredPermissions(): Boolean = with(_status.value) {
        hasPhoneStatePermission && hasLocationPermission
    }

    private fun granted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun refreshStatus() {
        val locationManager =
            ContextCompat.getSystemService(context, LocationManager::class.java)
        _status.value = CollectorStatus(
            hasPhoneStatePermission = granted(Manifest.permission.READ_PHONE_STATE),
            hasLocationPermission = granted(Manifest.permission.ACCESS_FINE_LOCATION) ||
                granted(Manifest.permission.ACCESS_COARSE_LOCATION),
            // Android 10+ 上系统定位总开关关闭时 getAllCellInfo() 只会返回空列表。
            isLocationEnabled = locationManager?.isLocationEnabled ?: false,
        )
    }

    private fun registerSubscriptionListener() {
        val manager = subscriptionManager ?: return
        val listener = object : SubscriptionManager.OnSubscriptionsChangedListener() {
            override fun onSubscriptionsChanged() {
                if (running) syncSubscriptions()
            }
        }
        subscriptionListener = listener
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                manager.addOnSubscriptionsChangedListener(executor, listener)
            } else {
                manager.addOnSubscriptionsChangedListener(listener)
            }
        }
    }

    /** 对齐当前活动卡：新增的建监听，拔掉的停掉。 */
    private fun syncSubscriptions() {
        val sims = readActiveSims()
        _status.value = _status.value.copy(activeSimCount = sims.size)

        val activeIds = sims.map { it.subscriptionId }.toSet()
        monitors.keys.filterNot { it in activeIds }.forEach { subId ->
            monitors.remove(subId)?.stop()
        }

        sims.forEach { sim ->
            val existing = monitors[sim.subscriptionId]
            if (existing == null) {
                val perSubTelephony = telephonyManager
                    ?.createForSubscriptionId(sim.subscriptionId)
                    ?: return@forEach
                val monitor = SlotMonitor(
                    sim = sim,
                    telephonyManager = perSubTelephony,
                    executor = executor,
                    onUpdate = { publish() },
                    classifierProvider = { cellTypes },
                )
                monitors[sim.subscriptionId] = monitor
                monitor.start()
            } else {
                existing.updateSim(sim)
            }
        }
        publish()
    }

    private fun readActiveSims(): List<SimSlotInfo> {
        val manager = subscriptionManager ?: return emptyList()
        val list = runCatching { manager.activeSubscriptionInfoList }.getOrNull().orEmpty()
        return list
            .sortedBy { it.simSlotIndex }
            .map { info ->
                val mcc = info.mccString
                val mnc = info.mncString
                val plmn = if (mcc != null && mnc != null) mcc + mnc else null
                android.util.Log.i(
                    TAG,
                    "【SIM信息】卡槽 ${info.simSlotIndex}: " +
                        "运营商=${info.carrierName} " +
                        "MCC=$mcc MNC=$mnc PLMN=$plmn " +
                        "subscriptionId=${info.subscriptionId}"
                )
                SimSlotInfo(
                    subscriptionId = info.subscriptionId,
                    slotIndex = info.simSlotIndex,
                    carrierName = info.carrierName?.toString().orEmpty(),
                    displayName = info.displayName?.toString().orEmpty(),
                    mcc = mcc,
                    mnc = mnc,
                )
            }
    }

    /** 回调不一定按时来，主动轮询保证界面持续刷新。 */
    private fun startPolling(scope: CoroutineScope) {
        pollJob = scope.launch {
            while (isActive) {
                monitors.values.toList().forEach { it.requestRefresh() }
                publish()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun publish() {
        val loc = lastLocation
        _snapshots.value = monitors.values
            .map {
                val s = withCellTypes(it.snapshot())
                if (loc != s.location) s.copy(location = loc) else s
            }
            .sortedBy { it.sim.slotIndex }
    }

    /**
     * 给快照补上覆盖类型和小区明细。
     *
     * 查询结果直接覆盖，包括查不到时写回 null——换了工参后，原来有类型、
     * 新工参里没有的小区必须把旧值清掉，否则会一直显示已经不成立的类型。
     *
     * 值没变时原样返回同一个对象，保住 StateFlow 的 equals 去重。
     */
    private fun withCellTypes(snapshot: SlotSnapshot): SlotSnapshot {
        val table = cellTypes
        // 无工参时统一清空，避免删掉导入文件后旧值残留
        val plmn = snapshot.sim.plmn

        val lte = snapshot.lte?.let { lte ->
            val eci = lte.eci?.toLong()
            val type = if (table.size == 0) null else eci?.let { table.classifyLte(it, plmn) }
            val detail = if (table.size == 0) null else eci?.let { table.detailLte(it, plmn) }
            if (type != lte.cellType || detail != lte.cellDetail) {
                lte.copy(cellType = type, cellDetail = detail)
            } else {
                lte
            }
        }
        val nr = snapshot.nr?.let { nr ->
            val nci = nr.nci
            val type = if (table.size == 0) null else nci?.let { table.classifyNr(it, plmn) }
            val detail = if (table.size == 0) null else nci?.let { table.detailNr(it, plmn) }
            if (type != nr.cellType || detail != nr.cellDetail) {
                nr.copy(cellType = type, cellDetail = detail)
            } else {
                nr
            }
        }
        return if (lte === snapshot.lte && nr === snapshot.nr) {
            snapshot
        } else {
            snapshot.copy(lte = lte, nr = nr)
        }
    }

    private fun startLocationUpdates() {
        val manager = locationManager ?: return
        if (!hasRequiredPermissions()) return

        val listener = object : android.location.LocationListener {
            override fun onLocationChanged(location: android.location.Location) {
                lastLocation = LocationInfo(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy,
                    timestamp = location.time,
                )
                publish()
            }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                manager.requestLocationUpdates(
                    LocationManager.FUSED_PROVIDER,
                    2000L,
                    0f,
                    executor,
                    listener,
                )
            } else {
                manager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2000L,
                    0f,
                    listener,
                )
            }
        } catch (e: SecurityException) {
            android.util.Log.w(TAG, "位置权限被拒绝", e)
        }
    }
}

/** 采集前置条件，UI 据此显示引导态。 */
data class CollectorStatus(
    val hasPhoneStatePermission: Boolean = false,
    val hasLocationPermission: Boolean = false,
    val isLocationEnabled: Boolean = false,
    val activeSimCount: Int = 0,
) {
    val isReady: Boolean
        get() = hasPhoneStatePermission && hasLocationPermission && isLocationEnabled
}
