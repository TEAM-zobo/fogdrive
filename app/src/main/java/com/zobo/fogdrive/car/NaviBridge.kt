package com.zobo.fogdrive.car

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.WaypointIndex
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.RequestWaypointInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pleos NaviHelper bridge: reads recent destinations / bookmarks (first-run map seeding),
 * the active destination (detour suggestions) and adds the detour as a waypoint.
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

    private val _destination = MutableStateFlow<Place?>(null)
    val destination: StateFlow<Place?> = _destination.asStateFlow()

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
    fun addDetourWaypoint(lat: Double, lng: Double, name: String): Boolean {
        val h = helper ?: return false
        return call("addWaypoint") {
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
    }

    /** Demo mode stand-in when no navigation destination exists in the emulator. */
    fun setDemoDestination(place: Place?) {
        _destination.value = place
        if (place == null) refreshDestination()
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
    }

    override fun onDestinationInfo(destinationInfo: DestinationInfo) {
        _destination.value = Place(destinationInfo.name ?: "목적지", destinationInfo.latitude, destinationInfo.longitude, destinationInfo.address)
    }

    override fun onDestinationChanged() = refreshDestination()

    override fun onRoutingCompleted() = refreshDestination()

    override fun onRouteCancelled() {
        _destination.value = null
    }

    override fun onError(errorCode: NaviErrorCode) {
        _lastMessage.value = "내비 오류: $errorCode"
    }

    private companion object {
        const val TAG = "FogNavi"
    }
}
