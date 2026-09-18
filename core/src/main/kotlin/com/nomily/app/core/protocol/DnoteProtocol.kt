package com.nomily.app.core.protocol

/**
 * BLE protocol constants for D·NOTE.
 *
 * Pure constants + pure functions, zero `android.*`.
 */
object DnoteProtocol {

    // ── GATT ──────────────────────────────────────────────────────────
    const val SERVICE_UUID = "00006001-0000-1000-8000-00805f9b34fb"

    /**
     * The 16‑bit SIG short alias of the above entry. Devices sometimes broadcast only the short form, so both must be recognized.
     */
    const val SERVICE_UUID_SHORT = "6001"
    const val RX_CHAR_UUID = "00006002-0000-1000-8000-00805f9b34fb"
    const val TX_CHAR_UUID = "00006003-0000-1000-8000-00805f9b34fb"

    /** Packet identifier at the start of each frame. */
    const val PID = 0xA0

     // ── Command codes ────────────────────────────────────────────────────────
    object Cmd {
        const val STOP_REC = 0x50
        const val START_REC = 0x51
        const val REC_STATUS = 0x56

        const val REC_STARTED = 0x54
        const val REC_STOPPED = 0x55

        const val STREAM = 0x68
        const val XFER = 0x70
        const val DEVICE_INFO = 0x80
        const val SWITCH_INFO = 0x81
        const val MASS_STORAGE = 0x82
        const val LED = 0x83

        /**
         * Do not swap these two: the readback is keyed by name, so a swap drives
         * the wrong peripheral while the readback still looks correct.
         */
        const val MOTOR = 0x84
        const val NOISE_CANCEL = 0x85

        const val BT_NAME_SHORT = 0x86
        const val SYNC_TIME = 0x87
        const val WIFI_AP = 0x88
        const val SAVE_WAV = 0x8A
        const val VAD = 0x8B
        const val MIC_GAIN = 0x8C
        const val NR_LEVEL = 0x8D

        const val BT_NAME_LONG = 0x8E
        const val IDLE_OFF = 0x8F
        const val FILE_LIST = 0x90
        const val FILE_DELETE = 0x91
        const val FACTORY_RESET = 0x93
        const val FORMAT_DISK = 0x94
        const val SHUTDOWN = 0x95

        /** v1.47 binding. The device refuses recording until an App is bound. */
        const val BIND = 0xA0
        const val QUERY_BOND = 0xA1

        /**
         * v1.47 device‑side ChaCha20 audio file encryption. Turning it off
         * requires the key currently stored on the device.
         */
        const val ENCRYPT_SET = 0xA2
        const val ENCRYPT_QUERY = 0xA3
    }

    /**
     * Longest Bluetooth name the firmware accepts, in UTF‑8 bytes. Anything longer is left unacknowledged, so clients clamp before sending.
     */
    const val BT_NAME_MAX_BYTES = 24

    /** Drops trailing characters until [text] fits [BT_NAME_MAX_BYTES], never splitting a multi‑byte character. */
    fun clampBluetoothName(text: String): String {
        var out = text
        while (out.toByteArray(Charsets.UTF_8).size > BT_NAME_MAX_BYTES) out = out.dropLast(1)
        return out
    }

    /** Switch name → command code. */
    val SWITCH_CMDS: Map<String, Int> = mapOf(
        "ms" to Cmd.MASS_STORAGE,
        "led" to Cmd.LED,
        "motor" to Cmd.MOTOR,
        "nc" to Cmd.NOISE_CANCEL,
        "wav" to Cmd.SAVE_WAV,
        "vad" to Cmd.VAD,
    )

    /** High nibble of the START byte for file transfers. */
    object Xfer {
        const val START = 0x00
        const val IN_PROGRESS = 0x10
        const val EOF = 0x20
        const val CANCEL = 0x30
        const val ERROR = 0x40
        const val NOT_FOUND = 0xF0
    }

    /** High nibble of the START byte for the real‑time OPUS stream. */
    object Stream {
        const val START = 0x00
        const val IN_PROGRESS = 0x10
        const val STOP = 0x20
        const val ERROR = 0x40
    }

    /** Length of the app‑chosen bond identifier; stored verbatim by firmware, any 16 random bytes are fine. */
    const val BOND_ID_LENGTH = 16

    /** ChaCha20 key length accepted by firmware in `ENCRYPT_SET`. */
    const val CHACHA_KEY_LENGTH = 32

    /** Sentinel value passed to `IDLE_OFF` to indicate “never auto‑shutdown”. */
    const val IDLE_OFF_NEVER = 0x0036_EE80
}
