package io.github.thatsfguy.meshcore.android.ui

import android.content.Context
import io.github.thatsfguy.meshcore.android.service.MeshCoreService
import io.github.thatsfguy.meshcore.firmware.AndroidHttpFetcher
import io.github.thatsfguy.meshcore.firmware.BoardAssets
import io.github.thatsfguy.meshcore.firmware.CompanionLink
import io.github.thatsfguy.meshcore.firmware.DfuOptions
import io.github.thatsfguy.meshcore.firmware.DfuPackage
import io.github.thatsfguy.meshcore.firmware.DfuProgress
import io.github.thatsfguy.meshcore.firmware.DfuTarget
import io.github.thatsfguy.meshcore.firmware.DfuTuning
import io.github.thatsfguy.meshcore.firmware.DownloadedFirmware
import io.github.thatsfguy.meshcore.firmware.FirmwareAsset
import io.github.thatsfguy.meshcore.firmware.FirmwareDownloader
import io.github.thatsfguy.meshcore.firmware.FirmwareRole
import io.github.thatsfguy.meshcore.firmware.FirmwareVersion
import io.github.thatsfguy.meshcore.firmware.FirmwareUpdater
import io.github.thatsfguy.meshcore.firmware.FirmwareCatalog
import io.github.thatsfguy.meshcore.firmware.Recovery
import io.github.thatsfguy.meshcore.firmware.TransferLog
import io.github.thatsfguy.meshcore.platform.AndroidDfuGattClient
import io.github.thatsfguy.meshcore.platform.AndroidDfuScanner
import io.github.thatsfguy.meshcore.crypto.CryptoProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * What the firmware screen is showing.
 *
 * The flow is deliberately linear and each step is a separate state:
 * choose a package → have it in hand → confirm the board by name →
 * flash. Nothing skips to the last step, because the step before it is
 * the only thing standing between a user and a wrong image on a radio
 * they cannot reach.
 */
sealed class FirmwareUi {
    /** Nothing chosen yet. */
    data class Idle(val message: String? = null) : FirmwareUi()

    object Checking : FirmwareUi()

    /**
     * Which version to install.
     *
     * A step of its own, and not skippable to "the newest", because
     * MeshCore ships releases that turn out not to be ready. Someone
     * updating a node on a mast is choosing a version they have reason
     * to trust, which is frequently not the latest one.
     */
    data class ChoosingVersion(
        val versions: List<FirmwareVersion>,
        val currentVersion: String?,
        val boardName: String?,
    ) : FirmwareUi()

    /**
     * Assets the release index offers for this radio. [suggested] are
     * the ones that match the board it reports; [others] is everything
     * else, shown behind a disclosure so an unrecognised board is still
     * updatable.
     */
    data class Choosing(
        val suggested: List<FirmwareAsset>,
        val others: List<FirmwareAsset>,
        val boardName: String?,
        val version: String,
    ) : FirmwareUi()

    data class Downloading(val name: String) : FirmwareUi()

    /** The package is parsed and understood. Nothing has been sent. */
    data class Confirm(
        val pkg: DfuPackage,
        val source: DownloadedFirmware,
        val target: FirmwareTargetKind,
    ) : FirmwareUi()

    data class Running(val progress: DfuProgress) : FirmwareUi()

    data class Finished(val version: String?) : FirmwareUi()

    data class Failed(val message: String, val recovery: String?) : FirmwareUi()

    /**
     * Found, but too far away to risk it. Not a failure — nothing has
     * been written and the node is untouched.
     */
    data class WeakSignal(
        val peer: String,
        val rssi: Int,
        val pkg: DfuPackage,
        val source: DownloadedFirmware,
        val target: FirmwareTargetKind,
    ) : FirmwareUi()
}

/** Which node an update is aimed at. */
enum class FirmwareTargetKind {
    /** The radio this app is connected to; we write the jump over the link we hold. */
    ConnectedRadio,

    /**
     * A node reached by scanning: one that has run `start ota` and is
     * advertising for an update, or one already sitting in its
     * bootloader. Which of the two it is, is settled by looking rather
     * than assumed — see [FirmwareUpdateController.flash].
     */
    NodeInUpdateMode,
}

/**
 * Drives a firmware update from the UI's point of view.
 *
 * Separate from [MeshCoreViewModel] because it is a self-contained
 * sequence with its own state machine, and because the ViewModel is
 * already the largest class in the app. The protocol work is all in
 * `shared/firmware`; this holds the Android pieces together — the
 * service hand-over, the scanner, the GATT client, and the file the
 * user picked.
 */
class FirmwareUpdateController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val crypto: CryptoProvider,
    private val serviceProvider: () -> MeshCoreService?,
) {
    private val _state = MutableStateFlow<FirmwareUi>(FirmwareUi.Idle())
    val state: StateFlow<FirmwareUi> = _state

    private val _latestVersion = MutableStateFlow<String?>(null)

    /** Latest published companion version, once the index has been read. */
    val latestVersion: StateFlow<String?> = _latestVersion

    private var target: FirmwareTargetKind = FirmwareTargetKind.ConnectedRadio
    private var otaAddress: String? = null
    private var boardName: String? = null
    private var allowWeakSignal = false
    private var slowTransfer = false

    /**
     * The package and where it came from, kept across a failure.
     *
     * A failure replaces the state, and the recovery buttons offered on
     * it are retries of the same transfer — so the thing being retried
     * has to outlive the state that described it.
     */
    private var lastConfirm: FirmwareUi.Confirm? = null
    private var role: FirmwareRole = FirmwareRole.Companion

    private val downloader by lazy { FirmwareDownloader(AndroidHttpFetcher(), crypto) }

    /**
     * [otaAddress] is the address a node reported in its own reply to
     * `start ota`. It removes the guesswork when more than one node
     * nearby is advertising for an update.
     */
    fun aimAt(kind: FirmwareTargetKind, otaAddress: String? = null) {
        target = kind
        this.otaAddress = otaAddress
    }

    fun reset() {
        allowWeakSignal = false
        slowTransfer = false
        lastConfirm = null
        _state.value = FirmwareUi.Idle()
    }

    /**
     * Try the transfer anyway over a weak link.
     *
     * Offered because the alternative — refusing outright — would strand
     * anyone who genuinely cannot get closer. It is a separate,
     * deliberate act, and it resets after the attempt.
     */
    fun retryOverWeakSignal() {
        allowWeakSignal = true
        flash()
    }

    /**
     * Retry with the packets spaced out.
     *
     * For the bootloader's receive pool overflowing — smaller batches so
     * it is asked less often, and a gap between packets so its flash
     * queue can drain. Slower, and the difference between a transfer
     * that finishes and one that stops a few hundred bytes in.
     */
    fun retrySlowly() {
        slowTransfer = true
        flash()
    }

    /**
     * Read the version list. Explicitly user-initiated — this app does
     * not poll for firmware in the background, and the only other
     * outbound traffic it makes is map tiles.
     */
    fun checkForFirmware(boardName: String?, role: FirmwareRole, currentVersion: String? = null) {
        this.boardName = boardName
        this.role = role
        _state.value = FirmwareUi.Checking
        scope.launch {
            try {
                val versions = downloader.listVersions(role)
                if (role == FirmwareRole.Companion) {
                    _latestVersion.value = versions.firstOrNull()?.version
                }
                if (versions.isEmpty()) {
                    _state.value = FirmwareUi.Failed(
                        "No ${role.name.lowercase()} releases were found.",
                        null,
                    )
                    return@launch
                }
                _state.value = FirmwareUi.ChoosingVersion(versions, currentVersion, boardName)
            } catch (e: Exception) {
                // Reading the list is not the update, and saying "update
                // failed — check your connection" for a list that could
                // not be read blames the wrong thing. Nothing has been
                // sent to any radio at this point.
                _state.value = FirmwareUi.Failed(
                    e.message ?: "Could not read the MeshCore version list.",
                    "Nothing was sent to the radio. The version list comes from GitHub; if " +
                        "it will not load, download the .zip for this board in a browser " +
                        "and open it from storage instead.",
                )
            }
        }
    }

    /**
     * Fetch one release and show what it holds for this board.
     *
     * [board] is passed in rather than read from what
     * [checkForFirmware] cached. The board can arrive *after* the check
     * — a repeater answers `board` over the mesh, which takes seconds —
     * and a snapshot taken at check time then leaves the picker showing
     * every board in the release for a node whose identity the app
     * already knows.
     */
    fun chooseVersion(version: FirmwareVersion, board: String? = null) {
        board?.let { boardName = it }
        _state.value = FirmwareUi.Checking
        scope.launch {
            try {
                val release = downloader.loadRelease(version)
                // A radio reached over BLE must stay reachable over BLE:
                // the USB companion build has no Bluetooth at all, and
                // flashing it would end the conversation permanently.
                val assets = release.assets.filter {
                    it.role != FirmwareRole.Companion || it.link == CompanionLink.Ble
                }
                val suggested = BoardAssets.suggest(boardName, assets)
                // When the board is known and the release holds exactly
                // one build for it, there is nothing to choose. Asking
                // anyway is a list of one — which is what "I still had
                // to select the board from a list" means. The
                // confirmation still names the board, the file, its size
                // and its hash, so nothing is decided behind the user's
                // back; they just are not made to pick the only option.
                val exact = suggested.singleOrNull()?.takeIf { BoardAssets.isKnown(boardName) }
                if (exact != null) {
                    choose(exact)
                    return@launch
                }
                _state.value = FirmwareUi.Choosing(
                    suggested = suggested,
                    others = assets.filterNot { it in suggested }.sortedBy { it.name },
                    boardName = boardName,
                    version = release.version,
                )
            } catch (e: Exception) {
                _state.value = FirmwareUi.Failed(
                    e.message ?: "Could not read that release.",
                    "Nothing was sent to the radio.",
                )
            }
        }
    }

    /**
     * [remember] carries the board back to the caller when the user had
     * to pick it themselves. Asking once is reasonable; asking every
     * time, for a node whose identity cannot be read because it is in
     * update mode, is not.
     */
    var onBoardChosen: ((String) -> Unit)? = null

    fun choose(asset: FirmwareAsset, remember: Boolean = false) {
        if (remember) {
            boardName = asset.boardPrefix
            onBoardChosen?.invoke(asset.boardPrefix)
        }
        _state.value = FirmwareUi.Downloading(asset.name)
        scope.launch {
            try {
                openPackage(downloader.download(asset))
            } catch (e: Exception) {
                _state.value = FirmwareUi.Failed(e.message ?: "The download failed.", null)
            }
        }
    }

    /** A `.zip` the user picked off their own device. */
    fun useLocalPackage(bytes: ByteArray, name: String) {
        scope.launch {
            try {
                openPackage(downloader.describeLocal(bytes, name))
            } catch (e: Exception) {
                _state.value = FirmwareUi.Failed(e.message ?: "That file is not usable.", null)
            }
        }
    }

    private fun openPackage(source: DownloadedFirmware) {
        _state.value = try {
            FirmwareUi.Confirm(DfuPackage.read(source.bytes), source, target)
        } catch (e: Exception) {
            FirmwareUi.Failed(e.message ?: "That package could not be read.", null)
        }
    }

    /**
     * Flash. Only reachable from [FirmwareUi.Confirm], which is the
     * screen that names the board.
     */
    fun flash() {
        // Reachable from the confirmation AND from the weak-signal
        // screen, which is a retry of the same package. Reading only
        // Confirm here made every button on that screen do nothing at
        // all — including "Transfer anyway", the one that exists for
        // people who cannot get closer.
        val confirm = when (val current = _state.value) {
            is FirmwareUi.Confirm -> current
            is FirmwareUi.WeakSignal ->
                FirmwareUi.Confirm(current.pkg, current.source, current.target)

            // The same defect one screen along, and a worse one:
            // `Failed` carries a message and a suggested recovery but
            // not the package, so "Retry more slowly" — the button
            // offered for the one failure with a documented remedy —
            // returned here and did nothing at all. Nothing appeared in
            // the log, because nothing ran. The node meanwhile is sitting
            // with its application erased, which is exactly when a dead
            // recovery button costs a trip to the node.
            is FirmwareUi.Failed -> lastConfirm ?: return

            else -> return
        }
        lastConfirm = confirm
        val service = serviceProvider()
        if (service == null) {
            _state.value = FirmwareUi.Failed("The radio service is not running.", null)
            return
        }
        scope.launch {
            try {
                service.withRadioHandedOver { handover ->
                    val dfuTarget = when (target) {
                        FirmwareTargetKind.ConnectedRadio -> {
                            val transport = handover.transport
                            if (transport == null) {
                                _state.value = FirmwareUi.Failed(
                                    "This radio is not connected over Bluetooth.",
                                    Recovery.NO_DFU_SERVICE,
                                )
                                return@withRadioHandedOver
                            }
                            DfuTarget.ConnectedRadio(
                                transport = transport,
                                address = handover.address,
                                boardName = handover.boardName,
                            )
                        }

                        // A node that has run `start ota` is still
                        // running its own firmware with BLE switched on
                        // — NOT sitting in a bootloader. It has to be
                        // told to jump before there is anything to
                        // flash, and the updater does that after it
                        // finds it.
                        // The board name matters most on exactly this
                        // path, and it was being dropped here: the node
                        // is off the mesh and cannot be asked what it
                        // is, so the stored answer is the only one left
                        // — and it is what gives the scanner a name to
                        // match on and the transfer its receipt
                        // interval.
                        FirmwareTargetKind.NodeInUpdateMode -> {
                            // Nothing in this transfer goes through the
                            // radio in the user's pocket — the node is
                            // reached directly over BLE — so it is let
                            // go of before the stream starts rather than
                            // left connected beside it. Two live BLE
                            // links share one controller, and the one
                            // carrying mesh traffic is not the one that
                            // needs a 7.5 ms connection interval.
                            handover.releaseRadio()
                            DfuTarget.AdvertisingForUpdate(
                                otaAddress = otaAddress,
                                boardName = boardName,
                            )
                        }
                    }
                    // One scanner for the whole update: it holds the
                    // BluetoothDevice objects the scan produced, and
                    // connecting with those rather than re-resolving the
                    // address is what keeps the address type intact.
                    val scanner = AndroidDfuScanner(context) {
                        service.diagnostics.log("Firmware", it)
                    }
                    val diagnostics = service.diagnostics
                    val updater = FirmwareUpdater(
                        scanner = scanner,
                        connect = { peer ->
                            diagnostics.log("Firmware", "Connecting to ${peer.address} (${peer.name})")
                            AndroidDfuGattClient(
                                context = context,
                                peer = peer,
                                scanned = scanner.deviceFor(peer.address),
                                log = { diagnostics.log("Firmware", it) },
                            )
                        },
                    )
                    // Flow control per the FAQ's own procedure — 8
                    // packets between receipt notifications on a T114,
                    // 10 elsewhere. It is the setting §7.1 step 7 tells
                    // an operator to change by hand before every flash,
                    // and it was left at the default here for every
                    // board. See [DfuTuning].
                    val boardForTransfer = dfuTarget.boardNameOrNull ?: boardName
                    val options = if (slowTransfer) {
                        // As slow as the protocol goes: one packet per
                        // acknowledgement. The automatic step-down
                        // already tried halving the window, so this is
                        // the floor, not a smaller step — and it is
                        // still the receipt interval doing the work
                        // rather than a sleep in the sender.
                        DfuOptions(allowWeakSignal = allowWeakSignal, receiptInterval = 1)
                    } else {
                        DfuOptions(
                            allowWeakSignal = allowWeakSignal,
                            receiptInterval = DfuTuning.packetsPerNotification(boardForTransfer),
                        )
                    }
                    // The settings the attempt ran with, once, before it
                    // starts. Without them a log of a failed flash does
                    // not say what was tried, so the next attempt cannot
                    // say what it changed.
                    diagnostics.log(
                        "Firmware",
                        "transfer: receipt interval ${options.receiptInterval}, " +
                            "packet size ${options.chunkSize ?: "from the link's MTU"}, " +
                            "board ${boardForTransfer ?: "unknown"}",
                    )
                    var loggedBytes = TransferLog.NOTHING_LOGGED
                    val startedAt = System.currentTimeMillis()
                    updater.update(confirm.pkg, dfuTarget, options).collect { progress ->
                        // A firmware update happens away from the phone
                        // screen and fails opaquely; without a record of
                        // which step it reached, the next attempt starts
                        // from guesswork.
                        //
                        // Progress is SAMPLED. Logging every receipt
                        // filled the 500-line buffer with the progress
                        // bar and evicted the whole of the context a
                        // failure has to be read against — see
                        // [TransferLog].
                        if (progress is DfuProgress.Transferring) {
                            if (TransferLog.shouldLog(
                                    progress.bytesSent,
                                    progress.totalBytes,
                                    loggedBytes,
                                )
                            ) {
                                loggedBytes = progress.bytesSent
                                diagnostics.log(
                                    "Firmware",
                                    TransferLog.describe(
                                        progress.bytesSent,
                                        progress.totalBytes,
                                        System.currentTimeMillis() - startedAt,
                                    ),
                                )
                            }
                        } else {
                            diagnostics.log("Firmware", progress.describeForLog())
                        }
                        _state.value = when (progress) {
                            is DfuProgress.SignalTooWeak -> FirmwareUi.WeakSignal(
                                peer = progress.peer.name ?: progress.peer.address,
                                rssi = progress.rssi,
                                pkg = confirm.pkg,
                                source = confirm.source,
                                target = target,
                            )

                            is DfuProgress.Failed ->
                                FirmwareUi.Failed(progress.message, progress.recovery)

                            DfuProgress.Finished ->
                                FirmwareUi.Finished(confirm.source.versionOrNull())

                            else -> FirmwareUi.Running(progress)
                        }
                    }
                }
            } catch (e: Exception) {
                _state.value = FirmwareUi.Failed(
                    e.message ?: "The update failed.",
                    Recovery.INTERRUPTED,
                )
            }
        }
    }
}

/**
 * The version in an asset filename, e.g.
 * `RAK_4631_companion_radio_ble-v1.17.0-727fc05.zip` → `v1.17.0`. Null
 * for a file the user renamed or built themselves — in which case the
 * screen says what it knows, which is the hash.
 */
fun DownloadedFirmware.versionOrNull(): String? =
    Regex("-(v\\d+\\.\\d+\\.\\d+)").find(sourceDescription)?.groupValues?.get(1)

/** One line per progress step for the diagnostics log. */
private fun DfuProgress.describeForLog(): String = when (this) {
    DfuProgress.Preparing -> "preparing"
    DfuProgress.EnteringBootloader -> "asking the node to enter its bootloader"
    DfuProgress.FindingNode -> "scanning for the node"
    is DfuProgress.Connecting -> "connected to ${peer.address} (${peer.name})"
    is DfuProgress.Transferring -> "sent $bytesSent/$totalBytes bytes"
    DfuProgress.Verifying -> "verifying"
    DfuProgress.Finished -> "finished; the node is rebooting"
    is DfuProgress.SignalTooWeak -> "signal too weak to start: $rssi dBm"
    is DfuProgress.Failed -> "FAILED: $message"
    is DfuProgress.Retrying ->
        "retrying at $receiptInterval packets per receipt after: $reason"
}
