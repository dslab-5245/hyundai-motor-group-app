package io.drivesymphony.app.signals

import ai.pleos.playground.navi.constants.CautionPointType
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.NavigationStatus
import ai.pleos.playground.navi.constants.TurnType
import ai.pleos.playground.navi.data.CameraAlert
import ai.pleos.playground.navi.data.CautionPoint
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.util.Log
import io.drivesymphony.engine.director.CautionKind
import io.drivesymphony.engine.director.SpeedEstimator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * NaviHelper SDK 3.0.5 source. Works only while the Pleos Connect Navigation app is running
 * (NaviHelper talks to it over AIDL; other navigation apps are not bridged).
 *
 * Provides: road speed limit, the navigation app's own camera over-speed flag, distance/type of the next turn,
 * nearest caution point (school zone, speed bump, ...), the road name for the trip title, and an
 * ESTIMATED vehicle speed derived from successive positions (the Vehicle SDK has no speed signal) via the
 * engine's [SpeedEstimator] (4 s chord window + EMA; measured RMS error 1.8 km/h at 3 m position noise).
 */
class PleosNaviSource(private val context: Context) : SignalSource {
    override val name = "pleos-navi"
    private var helper: NaviHelper? = null
    private var store: SignalStore? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollJob: Job? = null

    private val speed = SpeedEstimator()

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
            val st = store ?: return
            st.speedKmh = speed.push(currentLocationInfo.latitude, currentLocationInfo.longitude, System.nanoTime() / 1e9)
            roadNameFrom(currentLocationInfo.address)?.let { st.roadName = it }
            st.touchNavi()
        }

        override fun onCurrentRoadSpeedLimit(speedLimit: Int) {
            store?.speedLimitKmh = if (speedLimit > 0) speedLimit else null
            store?.touchNavi()
        }

        override fun onTBTInfo(tbtInfoList: List<TBTInfo>) {
            val st = store ?: return
            val first = tbtInfoList.firstOrNull()
            st.distanceToTurnM = first?.distance?.toInt()
            st.sharpTurnAhead = first != null && first.type in SHARP && first.distance < 150.0
            st.touchNavi()
        }

        override fun onCautionPointInfo(cautionPointList: List<CautionPoint>) {
            val st = store ?: return
            val nearest = cautionPointList.minByOrNull { it.remainDistance }
            st.cautionAhead = nearest?.let { mapCaution(it.type) } ?: CautionKind.NONE
            st.cautionDistanceM = nearest?.remainDistance
            st.touchNavi()
        }

        override fun onCameraAlert(cameraAlertList: List<CameraAlert>) {
            val st = store ?: return
            st.cameraOverSpeed = cameraAlertList.any { it.isOverSpeed || it.isOverSectionAvrSpeed }
            cameraAlertList.firstOrNull { it.speedLimit > 0 }?.let { st.speedLimitKmh = it.speedLimit }
            st.touchNavi()
        }

        override fun onNavigationStatus(navigationStatus: NavigationStatus) {
            if (navigationStatus == NavigationStatus.INACTIVE || navigationStatus == NavigationStatus.INITIALIZING) clearDerived()
        }

        override fun onError(errorCode: NaviErrorCode) { Log.w(TAG, "navi error: $errorCode") }
    }

    private fun clearDerived() {
        val st = store ?: return
        st.speedKmh = null; st.speedLimitKmh = null; st.distanceToTurnM = null
        st.sharpTurnAhead = false; st.cautionAhead = CautionKind.NONE; st.cautionDistanceM = null; st.cameraOverSpeed = false
        speed.reset()
    }

    override fun start(store: SignalStore) {
        this.store = store
        val h = NaviHelper(context)
        h.initialize()
        h.addListener(listener)
        helper = h
        // the navigation app pushes guidance events; location is polled so speed can be derived in free drive too
        pollJob = scope.launch {
            while (isActive) {
                runCatching { h.getCurrentLocationInfo() }.onFailure { Log.d(TAG, "poll: ${it.message}") }
                delay(1000)
            }
        }
        Log.i(TAG, "NaviHelper initialized")
    }

    override fun stop() {
        pollJob?.cancel(); pollJob = null
        helper?.let { runCatching { it.removeListener(listener) }; runCatching { it.release() } }
        helper = null
        clearDerived()
    }

    fun destroy() { stop(); scope.cancel() }

    override fun isLive(store: SignalStore): Boolean = System.currentTimeMillis() - store.lastNaviUpdateMs < 3000

    companion object {
        private const val TAG = "PleosNaviSource"
        private val SHARP = setOf(
            TurnType.TURN_SHARP_R, TurnType.TURN_SHARP_L, TurnType.TURN_UTURN,
            TurnType.TURN_ENTER_ROUNDABOUT_02, TurnType.TURN_ENTER_ROUNDABOUT_03, TurnType.TURN_ENTER_ROUNDABOUT_04,
            TurnType.TURN_ENTER_ROUNDABOUT_06, TurnType.TURN_ENTER_ROUNDABOUT_08, TurnType.TURN_ENTER_ROUNDABOUT_09,
            TurnType.TURN_ENTER_ROUNDABOUT_10, TurnType.TURN_ENTER_ROUNDABOUT_12,
        )

        fun mapCaution(t: CautionPointType): CautionKind = when (t) {
            CautionPointType.SCHOOL_ZONE -> CautionKind.SCHOOL_ZONE
            CautionPointType.SPEED_BUMP -> CautionKind.SPEED_BUMP
            CautionPointType.SLIPPERY_ROAD_ALERT, CautionPointType.FOG_AREA -> CautionKind.SLIPPERY
            CautionPointType.DOWNHILL_CAUTION_AREA -> CautionKind.DOWNHILL
            CautionPointType.ROAD_DAMAGE_ALERT, CautionPointType.FALLING_ROCK_AREA -> CautionKind.ROAD_DAMAGE
            CautionPointType.TRAFFIC_LIGHT_VIOLATION_ENFORCEMENT, CautionPointType.LANE_CHANGE_CAMERA_ALERT,
            CautionPointType.CUT_IN_VIOLATION_ENFORCEMENT -> CautionKind.CAMERA
            else -> CautionKind.OTHER
        }

        /** "서울특별시 강남구 테헤란로 152" -> "테헤란로". Falls back to null when no road-like token exists. */
        fun roadNameFrom(address: String?): String? {
            if (address.isNullOrBlank()) return null
            val tokens = address.split(' ', ',').filter { it.isNotBlank() }
            return tokens.lastOrNull { it.endsWith("대로") || it.endsWith("로") || it.endsWith("길") }?.replace(Regex("[0-9]+$"), "")
        }
    }
}
