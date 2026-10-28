package com.zobo.fogdrive.car

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.RouteOption
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RouteInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.data.WaypointArrivedInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Pleos NaviHelper bridge: reads recent destinations / bookmarks (first-run map seeding),
 * the active destination (detour suggestions) and adds the detour as a waypoint.
 * While guiding it also relays arrivals (fog reveal + stamps) and the speed limit / next turn
 * for the driving screen.
 * Info APIs answer through listener callbacks, so results are exposed as flows.
 */
class NaviBridge(private val context: Context) : NaviHelperEventListener {
    private var helper: NaviHelper? = null

    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    private val _recent = MutableStateFlow<List<Place>?>(null)
    val recent: StateFlow<List<Place>?> = _recent.asStateFlow()

    private val _bookmarks = MutableStateFlow<List<Place>?>(null)
    val bookmarks: StateFlow<List<Place>?> = _bookmarks.asStateFlow()

    /** The bookmark saved as 집 in the navigation (the map's base camp), if any. */
    private val _home = MutableStateFlow<Place?>(null)
    val home: StateFlow<Place?> = _home.asStateFlow()

    private val _destination = MutableStateFlow<Place?>(null)
    val destination: StateFlow<Place?> = _destination.asStateFlow()

    private val _guidance = MutableStateFlow(Guidance())
    /**
     * The demo drives its own guidance. The real navigation keeps reporting for where the car really
     * is (its road's limit, 20-30 km/h at home), which overwrote the demo's for a moment, again and
     * again: the limit sign flickered. Its callbacks leave the guidance alone while this is set.
     */
    @Volatile private var demoGuidance = false
    val guidance: StateFlow<Guidance> = _guidance.asStateFlow()

    private val _arrivals = MutableSharedFlow<Arrival>(extraBufferCapacity = 8)
    val arrivals: SharedFlow<Arrival> = _arrivals.asSharedFlow()

    /** The fog detour went in as the first waypoint and has not been reached yet. */
    @Volatile private var detourPending = false
    @Volatile private var lastTbtRequestAt = 0L

    private val _lastMessage = MutableStateFlow<String?>(null)
    val lastMessage: StateFlow<String?> = _lastMessage.asStateFlow()

    fun start() {
        if (helper != null) return
        try {
            val h = NaviHelper(context.applicationContext)
            h.initialize()
            h.addListener(this)
            helper = h
            _connected.value = true
            refresh()
            // A route may already be guided when the service starts; no onRouteStarted will come then.
            call("routeState") { h.getRouteStateInfo() }
        } catch (e: Throwable) {
            Log.w(TAG, "NaviHelper unavailable", e)
            _connected.value = false
        }
    }

    fun stop() {
        helper?.let { h ->
            runCatching { h.removeListener(this) }
            runCatching { h.release() }
        }
        helper = null
        _connected.value = false
        detourPending = false
        _guidance.value = Guidance()
    }

    fun refresh() {
        val h = helper ?: return
        call("recent") { h.getRecentDestinationInfo() }
        call("bookmark") { h.getBookmarkInfo() }
        call("destination") { h.getDestinationInfo() }
    }

    fun refreshDestination() {
        val h = helper ?: return
        call("destination") { h.getDestinationInfo() }
    }

    /** Adds the detour point as the first waypoint of the active route. */
    fun addDetourWaypoint(lat: Double, lng: Double, name: String = DETOUR_NAME): Boolean {
        val h = helper ?: return false
        val ok = call("addWaypoint") {
            h.addWaypoint(
                RequestWaypointInfo(
                    latitude = lat,
                    longitude = lng,
                    poiName = name,
                    waypointIndex = WaypointIndex.FIRST,
                    poiId = "",
                    address = name,
                    poiSubId = "0",
                )
            )
        }
        if (ok) detourPending = true
        return ok
    }

    /** Starts navigation guidance to an unexplored place picked by the app (the "안 가본 동네" card). */
    fun routeTo(lat: Double, lng: Double, name: String): Boolean {
        val h = helper ?: return false
        return call("requestRoute") {
            h.requestRoute(
                RouteInfo(
                    latitude = lat,
                    longitude = lng,
                    displayLatitude = null,
                    displayLongitude = null,
                    poiName = name,
                    poiId = "",
                    address = name,
                    poiSubId = "0",
                    routeOption = RouteOption.RECOMMENDED,
                    poiProvider = "",
                    isMobileRequest = false,
                )
            )
        }
    }

    /** Demo mode stand-in when no navigation destination exists in the emulator. */
    fun setDemoDestination(place: Place?) {
        _destination.value = place
        if (place == null) refreshDestination()
    }

    /** Demo mode stand-in for the guidance the emulator's navigation cannot produce. */
    fun setDemoGuidance(guidance: Guidance?) {
        demoGuidance = guidance != null
        _guidance.value = guidance ?: Guidance()
    }

    /** Demo mode stand-in for a navigation arrival. */
    fun emitDemoArrival(arrival: Arrival) {
        _arrivals.tryEmit(arrival)
    }

    private inline fun call(what: String, block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: Throwable) {
        Log.w(TAG, "$what failed", e)
        _lastMessage.value = "$what 실패: ${e.message}"
        false
    }

    override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) {
        _recent.value = recentDestinationInfoList.map { Place(it.name ?: "최근 목적지", it.latitude, it.longitude, it.address) }
    }

    override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) {
        _bookmarks.value = bookmarkInfoList.map { Place(it.name ?: "즐겨찾기", it.latitude, it.longitude, it.address) }
        // An unset 집 comes through as (0, 0) (seen on the emulator): no base camp then.
        _home.value = bookmarkInfoList.firstOrNull { it.category == BookmarkType.HOME && (it.latitude != 0.0 || it.longitude != 0.0) }
            ?.let { Place(it.name ?: "집", it.latitude, it.longitude, it.address) }
        // Counts only: the places themselves stay out of the log.
        Log.i(TAG, "bookmarks: ${bookmarkInfoList.size}, by category ${bookmarkInfoList.groupingBy { it.category }.eachCount()}")
    }

    override fun onBookMarkChanged() {
        val h = helper ?: return
        call("bookmark") { h.getBookmarkInfo() }
    }

    override fun onDestinationInfo(destinationInfo: DestinationInfo) {
        _destination.value = Place(destinationInfo.name ?: "목적지", destinationInfo.latitude, destinationInfo.longitude, destinationInfo.address)
    }

    override fun onDestinationChanged() = refreshDestination()

    override fun onRoutingCompleted() = refreshDestination()

    override fun onRouteCancelled() {
        _destination.value = null
        endGuidance()
    }

    override fun onRouteStarted(routeStartInfo: RouteStartInfo) {
        if (demoGuidance) return
        _guidance.update { it.copy(routing = true) }
        requestTurnInfo(force = true)
    }

    override fun onReRouteEnded() = requestTurnInfo(force = true)

    /**
     * getTBTInfo is a one-shot query (like the other info getters), so the next turn and its distance
     * are re-read as the car moves; called on every fix and throttled to [TBT_INTERVAL_MS].
     */
    fun requestTurnInfo(force: Boolean = false) {
        val h = helper ?: return
        // The demo's own guidance is shown meanwhile; the real answer would only be dropped.
        if (demoGuidance) return
        if (!force && !_guidance.value.routing) return
        val now = System.currentTimeMillis()
        if (!force && now - lastTbtRequestAt < TBT_INTERVAL_MS) return
        lastTbtRequestAt = now
        if (force) {
            call("tbt") { h.getTBTInfo() }
        } else {
            // Periodic refresh: a failing query would otherwise overwrite the status line every second.
            try { h.getTBTInfo() } catch (e: Throwable) { Log.w(TAG, "tbt refresh failed", e) }
        }
    }

    override fun onRouteEnded() = endGuidance()

    override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) {
        if (demoGuidance || !routeStateInfo.isRouting) return
        _guidance.update { it.copy(routing = true) }
        requestTurnInfo(force = true)
    }

    override fun onNavigationStatus(navigationStatus: NavigationStatus) {
        if (demoGuidance) return
        when (navigationStatus) {
            NavigationStatus.ROUTE_DRIVE -> _guidance.update { it.copy(routing = true) }
            // The navigation is off: nothing it reported (speed limit included) is current any more.
            NavigationStatus.INACTIVE -> {
                endGuidance()
                _guidance.value = Guidance()
            }
            else -> endGuidance()
        }
    }

    override fun onCurrentRoadSpeedLimit(speedLimit: Int) {
        if (demoGuidance) return
        _guidance.update { it.copy(speedLimitKmh = speedLimit.takeIf { v -> v > 0 }) }
    }

    override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
        // A periodic query can answer after the route ended; don't bring back a stale arrow.
        if (demoGuidance || !_guidance.value.routing) return
        val next = tbtInfoList.firstOrNull()
        val kind = next?.type?.let(::turnKind)
        _guidance.update { it.copy(nextTurn = if (next != null && kind != null) TurnHint(kind, next.distance.toInt()) else null) }
    }

    override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) {
        _arrivals.tryEmit(Arrival(ArrivalKind.DESTINATION, destinationArrivedInfo.name ?: "목적지"))
        endGuidance()
    }

    override fun onWayPointArrived(waypointArrivedInfo: WaypointArrivedInfo) {
        val name = waypointArrivedInfo.name ?: "경유지"
        // The navigation may report the waypoint under a geocoded name, so the slot counts as well.
        val detour = name == DETOUR_NAME || (detourPending && waypointArrivedInfo.index == WaypointIndex.FIRST)
        if (detour) detourPending = false
        _arrivals.tryEmit(Arrival(if (detour) ArrivalKind.DETOUR else ArrivalKind.WAYPOINT, name))
    }

    private fun endGuidance() {
        detourPending = false
        if (demoGuidance) return
        _guidance.update { it.copy(routing = false, nextTurn = null) }
    }

    override fun onError(errorCode: NaviErrorCode) {
        _lastMessage.value = "내비 오류: $errorCode"
    }

    companion object {
        /** Waypoint name of the fog detour; arriving at a waypoint with this name counts as an expedition. */
        const val DETOUR_NAME = "안개 구역 탐험"
        private const val TAG = "FogNavi"
        private const val TBT_INTERVAL_MS = 1_000L

        /** Collapses the navigation's ~45 turn types into the few arrows the driving screen draws. */
        fun turnKind(type: TurnType): TurnKind? = when {
            type == TurnType.TURN_UNKNOWN -> null
            type == TurnType.TURN_ARRIVE || type == TurnType.TURN_WAY -> TurnKind.ARRIVE
            type == TurnType.TURN_UTURN -> TurnKind.UTURN
            type == TurnType.TURN_LEFT || type == TurnType.TURN_SHARP_L -> TurnKind.LEFT
            type == TurnType.TURN_RIGHT || type == TurnType.TURN_SHARP_R -> TurnKind.RIGHT
            type.name.contains("ROUNDABOUT") -> TurnKind.ROUNDABOUT
            type.name.endsWith("_L") -> TurnKind.KEEP_LEFT
            // Korean service / rest areas exit to the right.
            type.name.endsWith("_R") || type == TurnType.TURN_ENTER_SERVICEAREA || type == TurnType.TURN_RESTAREA -> TurnKind.KEEP_RIGHT
            else -> TurnKind.STRAIGHT
        }
    }
}
