package xyz.draumaz.wristbridge.shared

/**
 * Wearable Data Layer paths, keys, and capability identifiers shared between
 * the Android phone relay (`:mobile`) and the Wear OS client (`:wear`).
 */
object DataLayerPaths {
    // Capability names advertised in res/values/wear.xml
    const val CAPABILITY_MOBILE_RELAY = "wristbridge_mobile_relay"
    const val CAPABILITY_WEAR_CLIENT = "wristbridge_wear_client"

    // DataClient paths (phone -> watch persistent synced state)
    const val DATA_CONFIG = "/ha/v1/config"
    const val DATA_ENTITY_STATES = "/ha/v1/entity_states"
    const val DATA_CAMERA_FRAME = "/ha/v1/camera_frame"

    // DataMap keys
    const val KEY_JSON = "json"
    const val KEY_UPDATED_AT = "updated_at"
    const val KEY_CAMERA_ASSET = "camera_jpeg"
    const val KEY_CAMERA_META = "camera_meta_json"

    // MessageClient paths (watch -> phone RPC commands)
    const val CMD_REFRESH_STATES = "/ha/v1/cmd/refresh_states"
    const val CMD_ENTITY_ACTION = "/ha/v1/cmd/entity_action"
    const val CMD_REQUEST_CAMERA_FRAME = "/ha/v1/cmd/request_camera_frame"
    const val CMD_PING = "/ha/v1/cmd/ping"

    // MessageClient paths (phone -> watch low-latency responses)
    const val RESP_CAMERA_FRAME_BYTES = "/ha/v1/resp/camera_frame_bytes"
    const val RESP_CAMERA_ERROR = "/ha/v1/resp/camera_error"
    const val RESP_PONG = "/ha/v1/resp/pong"

    /**
     * Maximum JPEG size (bytes) eligible for direct MessageClient fast-path delivery.
     * Larger frames still sync reliably via DataClient Asset.
     */
    const val MAX_DIRECT_MESSAGE_FRAME_BYTES = 85 * 1024
}
