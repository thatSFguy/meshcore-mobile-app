package io.github.thatsfguy.meshcore.protocol

/**
 * MeshCore companion-protocol code values and constants.
 *
 * Source of truth: MESHCORE_PROTOCOL.md §2–§6, cross-checked against the
 * MeshCore Open reference client (`lib/connector/meshcore_protocol.dart`).
 */
object Codes {
    // Command codes (client → radio)
    const val CMD_APP_START = 1
    const val CMD_SEND_TXT_MSG = 2
    const val CMD_SEND_CHANNEL_TXT_MSG = 3
    const val CMD_GET_CONTACTS = 4
    const val CMD_GET_DEVICE_TIME = 5
    const val CMD_SET_DEVICE_TIME = 6
    const val CMD_SEND_SELF_ADVERT = 7
    const val CMD_SET_ADVERT_NAME = 8
    const val CMD_ADD_UPDATE_CONTACT = 9
    const val CMD_SYNC_NEXT_MESSAGE = 10
    const val CMD_SET_RADIO_PARAMS = 11
    const val CMD_SET_RADIO_TX_POWER = 12
    const val CMD_RESET_PATH = 13
    const val CMD_SET_ADVERT_LATLON = 14
    const val CMD_REMOVE_CONTACT = 15
    const val CMD_SHARE_CONTACT = 16
    const val CMD_EXPORT_CONTACT = 17
    const val CMD_IMPORT_CONTACT = 18
    const val CMD_REBOOT = 19
    const val CMD_GET_BATT_AND_STORAGE = 20
    const val CMD_DEVICE_QUERY = 22
    const val CMD_SEND_LOGIN = 26
    const val CMD_SEND_STATUS_REQ = 27
    const val CMD_GET_CONTACT_BY_KEY = 30
    const val CMD_GET_CHANNEL = 31
    const val CMD_SET_CHANNEL = 32
    const val CMD_SEND_TRACE_PATH = 36
    const val CMD_SET_OTHER_PARAMS = 38
    const val CMD_SEND_TELEMETRY_REQ = 39
    const val CMD_GET_CUSTOM_VAR = 40
    const val CMD_SET_CUSTOM_VAR = 41
    const val CMD_SEND_BINARY_REQ = 50
    const val CMD_SET_FLOOD_SCOPE = 54
    const val CMD_SEND_CONTROL_DATA = 55
    const val CMD_GET_STATS = 56
    const val CMD_SEND_ANON_REQ = 57
    const val CMD_SET_AUTO_ADD_CONFIG = 58
    const val CMD_GET_AUTO_ADD_CONFIG = 59
    const val CMD_SET_PATH_HASH_MODE = 61

    // Text message types
    const val TXT_TYPE_PLAIN = 0
    const val TXT_TYPE_CLI_DATA = 1
    const val TXT_TYPE_SIGNED = 2

    // Response codes (radio → client, reply to a command)
    const val RESP_CODE_OK = 0
    const val RESP_CODE_ERR = 1
    const val RESP_CODE_CONTACTS_START = 2
    const val RESP_CODE_CONTACT = 3
    const val RESP_CODE_END_OF_CONTACTS = 4
    const val RESP_CODE_SELF_INFO = 5
    const val RESP_CODE_SENT = 6
    const val RESP_CODE_CONTACT_MSG_RECV = 7
    const val RESP_CODE_CHANNEL_MSG_RECV = 8
    const val RESP_CODE_CURR_TIME = 9
    const val RESP_CODE_NO_MORE_MESSAGES = 10
    const val RESP_CODE_EXPORT_CONTACT = 11
    const val RESP_CODE_BATT_AND_STORAGE = 12
    const val RESP_CODE_DEVICE_INFO = 13
    const val RESP_CODE_CONTACT_MSG_RECV_V3 = 16
    const val RESP_CODE_CHANNEL_MSG_RECV_V3 = 17
    const val RESP_CODE_CHANNEL_INFO = 18
    const val RESP_CODE_CUSTOM_VARS = 21
    const val RESP_CODE_STATS = 24
    const val RESP_CODE_AUTO_ADD_CONFIG = 25

    const val STATS_TYPE_CORE = 0
    const val STATS_TYPE_RADIO = 1
    const val STATS_TYPE_PACKETS = 2

    // Push codes (radio → client, async; high bit set)
    const val PUSH_CODE_ADVERT = 0x80
    const val PUSH_CODE_PATH_UPDATED = 0x81
    const val PUSH_CODE_SEND_CONFIRMED = 0x82
    const val PUSH_CODE_MSG_WAITING = 0x83
    const val PUSH_CODE_LOGIN_SUCCESS = 0x85
    const val PUSH_CODE_LOGIN_FAIL = 0x86
    const val PUSH_CODE_STATUS_RESPONSE = 0x87
    const val PUSH_CODE_LOG_RX_DATA = 0x88
    const val PUSH_CODE_TRACE_DATA = 0x89
    const val PUSH_CODE_NEW_ADVERT = 0x8A
    const val PUSH_CODE_TELEMETRY_RESPONSE = 0x8B
    const val PUSH_CODE_BINARY_RESPONSE = 0x8C
    const val PUSH_CODE_CONTROL_DATA = 0x8E

    // Contact / advert node types
    const val ADV_TYPE_CHAT = 1
    const val ADV_TYPE_REPEATER = 2
    const val ADV_TYPE_ROOM = 3
    const val ADV_TYPE_SENSOR = 4

    // Contact flags
    const val CONTACT_FLAG_FAVORITE = 0x01
    const val CONTACT_FLAG_TELE_BASE = 0x02
    const val CONTACT_FLAG_TELE_LOC = 0x04
    const val CONTACT_FLAG_TELE_ENV = 0x08

    // Over-the-air payload types (§9)
    const val PAYLOAD_TYPE_REQ = 0x00
    const val PAYLOAD_TYPE_RESPONSE = 0x01
    const val PAYLOAD_TYPE_TXT_MSG = 0x02
    const val PAYLOAD_TYPE_ACK = 0x03
    const val PAYLOAD_TYPE_ADVERT = 0x04
    const val PAYLOAD_TYPE_GRP_TXT = 0x05
    const val PAYLOAD_TYPE_GRP_DATA = 0x06
    const val PAYLOAD_TYPE_ANON_REQ = 0x07
    const val PAYLOAD_TYPE_PATH = 0x08
    const val PAYLOAD_TYPE_TRACE = 0x09
    const val PAYLOAD_TYPE_MULTIPART = 0x0A
    const val PAYLOAD_TYPE_CONTROL = 0x0B
    const val PAYLOAD_TYPE_RAW_CUSTOM = 0x0F

    // Binary request types (CMD_SEND_BINARY_REQ payload[0])
    const val REQ_TYPE_GET_STATUS = 0x01
    const val REQ_TYPE_KEEP_ALIVE = 0x02
    const val REQ_TYPE_GET_TELEMETRY = 0x03
    const val REQ_TYPE_GET_ACCESS_LIST = 0x05
    const val REQ_TYPE_GET_NEIGHBORS = 0x06

    // Auto-add config flags
    const val AUTO_ADD_OVERWRITE_OLDEST = 0x01
    const val AUTO_ADD_CHAT = 0x02
    const val AUTO_ADD_REPEATER = 0x04
    const val AUTO_ADD_ROOM = 0x08
    const val AUTO_ADD_SENSOR = 0x10

    // Sizes / protocol constants
    const val MAX_FRAME_SIZE = 172
    const val MAX_TEXT_PAYLOAD_BYTES = 160 // firmware MAX_TEXT_LEN
    const val APP_PROTOCOL_VERSION = 4
    const val CIPHER_BLOCK_SIZE = 16
    const val CIPHER_MAC_SIZE = 2
    const val PUB_KEY_SIZE = 32
    const val SIGNATURE_SIZE = 64
    const val MAX_PATH_SIZE = 64
    const val MAX_NAME_SIZE = 32
}

/** BLE advertised-name prefixes that identify a MeshCore companion radio. */
val MESHCORE_BLE_NAME_PREFIXES = listOf(
    "MeshCore-", "Whisper-", "WisCore-", "Seeed", "Lilygo", "HT-", "LowMesh_MC_",
)

/** Nordic UART Service UUIDs (shared by every MeshCore BLE radio). */
object NusUuids {
    const val SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"

    /** Client → radio characteristic (write). */
    const val RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"

    /** Radio → client characteristic (notify). */
    const val TX = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"
}
