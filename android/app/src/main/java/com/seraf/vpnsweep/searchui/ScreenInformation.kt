package com.seraf.vpnsweep.searchui

enum class ScreenEvent { SEARCH_STARTED, ATTEMPT_STARTED, ATTEMPT_FINISHED, PAUSED, CANCELLED, COMPLETE, FAILED }
enum class Fingerprint(val wireValue: String) {
    CHROME("chrome"), FIREFOX("firefox"), SAFARI("safari"), IOS("ios"), ANDROID("android"),
    EDGE("edge"), BROWSER_360("360"), QQ("qq"), RANDOM("random"), RANDOMIZED("randomized"),
}
enum class FragmentPackets(val wireValue: String) {
    TLS_HELLO("tlshello"), ONE_TO_ONE("1-1"), ONE_TO_TWO("1-2"), ONE_TO_THREE("1-3"), ONE_TO_FIVE("1-5"),
}
enum class Udp443(val wireValue: String) { REJECT("reject"), ALLOW("allow"), SKIP("skip") }
sealed interface NumericRange {
    data class Single(val value: Int) : NumericRange
    data class Pair(val first: Int, val last: Int) : NumericRange
}

data class AppliedParameters(
    val fragmentEnabled: Boolean? = null,
    val fragmentPackets: FragmentPackets? = null,
    val fragmentLengthBytes: NumericRange? = null,
    val fragmentIntervalMillis: NumericRange? = null,
    val fragmentMaxSplit: NumericRange? = null,
    val muxEnabled: Boolean? = null,
    val tcpConcurrency: Int? = null,
    val xudpConcurrency: Int? = null,
    val udp443: Udp443? = null,
    val fingerprint: Fingerprint? = null,
    val udpNoiseEnabled: Boolean? = null,
    val udpNoiseCount: Int? = null,
)

data class ScreenInformation(
    val events: List<ScreenEvent> = emptyList(),
    val applied: AppliedParameters? = null,
)

internal fun eventText(event: ScreenEvent): String = when (event) {
    ScreenEvent.SEARCH_STARTED -> "Подбор начат"
    ScreenEvent.ATTEMPT_STARTED -> "Попытка начата"
    ScreenEvent.ATTEMPT_FINISHED -> "Попытка завершена"
    ScreenEvent.PAUSED -> "Подбор приостановлен"
    ScreenEvent.CANCELLED -> "Подбор отменён"
    ScreenEvent.COMPLETE -> "Подбор завершён"
    ScreenEvent.FAILED -> "Подбор остановлен из-за ошибки"
}
