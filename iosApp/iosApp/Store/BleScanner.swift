import CoreBluetooth
import Foundation
import Shared

/// One MeshCore radio seen in a scan.
struct DiscoveredRadio: Identifiable, Equatable {
    let id: UUID
    let name: String
    let rssi: Int
    let peripheral: CBPeripheral

    static func == (a: DiscoveredRadio, b: DiscoveredRadio) -> Bool { a.id == b.id }
}

/// Scans for MeshCore companion radios over BLE.
///
/// Mirrors Android's `BleScanner`, including the part that is easy to
/// get wrong: **the scan is unfiltered**. Some MeshCore firmwares leave
/// the Nordic UART Service UUID out of their advertisement, so a scan
/// filtered on that service silently misses them. A device qualifies
/// when it advertises NUS *or* its name matches a known MeshCore
/// prefix, and the name half of that test comes from the shared
/// `matchesMeshCoreName` so the two platforms cannot disagree.
///
/// The `CBCentralManager` created here is handed to `IosBleTransport`
/// on connect. ⚠ That transport takes over the central's delegate, so
/// scanning must stop first — see `takeCentralForConnect()`.
@MainActor
final class BleScanner: NSObject, ObservableObject, CBCentralManagerDelegate {

    @Published private(set) var radios: [DiscoveredRadio] = []
    @Published private(set) var isScanning = false
    /// Nil when nothing needs saying; otherwise why we cannot scan.
    @Published private(set) var problem: String?

    private var central: CBCentralManager!
    private var wantScan = false

    override init() {
        super.init()
        central = CBCentralManager(delegate: self, queue: nil)
    }

    func startScan() {
        radios = []
        wantScan = true
        problem = nil
        beginIfPossible()
    }

    func stopScan() {
        wantScan = false
        if central.state == .poweredOn { central.stopScan() }
        isScanning = false
    }

    /// Hand the central over to a transport that is about to connect.
    ///
    /// Scanning stops first, deliberately: `IosBleTransport` sets itself
    /// as the central's delegate, so anything this object was relying on
    /// afterwards would silently stop arriving.
    func takeCentralForConnect() -> CBCentralManager {
        stopScan()
        return central
    }

    private func beginIfPossible() {
        guard wantScan else { return }
        switch central.state {
        case .poweredOn:
            problem = nil
            isScanning = true
            // nil services = unfiltered, on purpose. See the class note.
            central.scanForPeripherals(withServices: nil, options: nil)
        case .poweredOff:
            problem = "Bluetooth is switched off."
        case .unauthorized:
            problem = "MeshCore is not allowed to use Bluetooth. Enable it in Settings → Privacy."
        case .unsupported:
            problem = "This device has no Bluetooth LE radio."
        default:
            // .resetting / .unknown — CoreBluetooth will call back.
            problem = nil
        }
    }

    // MARK: - CBCentralManagerDelegate

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        beginIfPossible()
    }

    func centralManager(
        _ central: CBCentralManager,
        didDiscover peripheral: CBPeripheral,
        advertisementData: [String: Any],
        rssi RSSI: NSNumber
    ) {
        // The advertised local name is the one to test: peripheral.name
        // can be a cached GAP name from a previous connection.
        let advertised = advertisementData[CBAdvertisementDataLocalNameKey] as? String
        let name = advertised ?? peripheral.name

        let services = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? []
        let advertisesNus = services.contains(IosBleTransport.companion.NUS_SERVICE_UUID)

        guard advertisesNus || CodesKt.matchesMeshCoreName(name: name) else { return }

        let radio = DiscoveredRadio(
            id: peripheral.identifier,
            name: name ?? "Unnamed radio",
            rssi: RSSI.intValue,
            peripheral: peripheral
        )
        if let existing = radios.firstIndex(where: { $0.id == radio.id }) {
            radios[existing] = radio
        } else {
            radios.append(radio)
        }
        // Strongest first — the radio in your hand should be at the top.
        radios.sort { $0.rssi > $1.rssi }
    }
}
