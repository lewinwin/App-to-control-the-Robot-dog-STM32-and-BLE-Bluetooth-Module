import Foundation
import CoreBluetooth

/// One advertising device we discovered during a scan.
struct DiscoveredDevice: Identifiable {
    let id: UUID                 // iOS peripheral identifier (not a MAC address)
    let peripheral: CBPeripheral
    var name: String
    var rssi: Int
    var hasSerialService: Bool
}

/// Drives an STM32 robot over Bluetooth Low Energy using a JDY-23 module
/// wired to the STM32 UART — the iOS counterpart of the Android `MainActivity`.
///
/// The JDY-23 exposes a transparent serial passthrough on the standard
/// FFE0 service / FFE1 characteristic (same layout as HM-10). Whatever bytes
/// we write to FFE1 come out of the module's TX pin into the STM32.
///
/// Each command is a 2-byte ASCII string (see the motion command table):
///   Stand "ll", Forward "ff", Backward "aa", Left "ls", Right "rs",
///   Sway "rr", Handshake "lu", Sit "ru", Sleep "ld", Stop "pp".
final class BLEManager: NSObject, ObservableObject {

    // 16-bit UUIDs, same as the Android app.
    static let serviceUUID = CBUUID(string: "FFE0")
    static let charUUID    = CBUUID(string: "FFE1")

    private static let scanSeconds: TimeInterval = 8

    // ---- Published UI state ----
    @Published private(set) var statusText  = "Not connected"
    @Published private(set) var statusKind: StatusKind = .error
    @Published private(set) var isConnected  = false
    @Published private(set) var isScanning   = false
    @Published private(set) var lastSent     = ""
    @Published var discovered: [DiscoveredDevice] = []
    @Published var showPicker = false

    // ---- Core Bluetooth ----
    private var central: CBCentralManager!
    private var peripheral: CBPeripheral?
    private var writeChar: CBCharacteristic?
    private var writeType: CBCharacteristicWriteType = .withoutResponse
    private var scanTimer: Timer?

    /// Keyed by peripheral identifier so repeated adverts update one entry.
    private var foundByID: [UUID: DiscoveredDevice] = [:]

    override init() {
        super.init()
        // Creating the central triggers `centralManagerDidUpdateState`, which
        // is also where iOS prompts the user for Bluetooth permission.
        central = CBCentralManager(delegate: self, queue: .main)
    }

    // MARK: - Public API

    /// Called by the "Scan & Connect" button.
    func startScan() {
        guard central.state == .poweredOn else {
            setStatus("Please turn on Bluetooth first", .error)
            return
        }
        guard !isScanning else { return }

        foundByID.removeAll()
        discovered = []
        isScanning = true
        setStatus("Scanning for BLE devices…", .warn)

        // Scan for everything (nil) so modules that omit FFE0 from their
        // advertisement are still listed; we flag the ones that do advertise it.
        central.scanForPeripherals(withServices: nil,
                                   options: [CBCentralManagerScanOptionAllowDuplicatesKey: false])

        scanTimer?.invalidate()
        scanTimer = Timer.scheduledTimer(withTimeInterval: Self.scanSeconds,
                                         repeats: false) { [weak self] _ in
            self?.stopScanAndPick()
        }
    }

    private func stopScanAndPick() {
        guard isScanning else { return }
        isScanning = false
        scanTimer?.invalidate()
        central.stopScan()

        // Most likely robot first: advertises the serial service, then the
        // strongest signal (closest to the phone).
        let list = foundByID.values.sorted { a, b in
            if a.hasSerialService != b.hasSerialService { return a.hasSerialService }
            return a.rssi > b.rssi
        }
        discovered = list

        if list.isEmpty {
            setStatus("No BLE devices found", .error)
        } else {
            setStatus("Select a device", .warn)
            showPicker = true
        }
    }

    /// Connect to the device the user chose from the picker.
    func connect(_ device: DiscoveredDevice) {
        showPicker = false
        disconnect()
        setStatus("Connecting…", .warn)
        peripheral = device.peripheral
        peripheral?.delegate = self
        central.connect(device.peripheral, options: nil)
    }

    func disconnect() {
        if let p = peripheral {
            central.cancelPeripheralConnection(p)
        }
        peripheral = nil
        writeChar = nil
        isConnected = false
    }

    /// Send one 2-byte ASCII command to FFE1.
    func send(_ ascii: String, label: String) {
        guard let p = peripheral, let c = writeChar else {
            setStatus("Not connected", .error)
            return
        }
        let data = Data(ascii.utf8)
        p.writeValue(data, for: c, type: writeType)
        lastSent = "\(label) (\(ascii))"
        // With .withoutResponse there is no write callback, so report optimistically.
        if writeType == .withoutResponse {
            setStatus("Sent: \(label) (\(ascii))", .ok)
        }
    }

    // MARK: - Helpers

    private func setStatus(_ text: String, _ kind: StatusKind) {
        statusText = text
        statusKind = kind
    }
}

// MARK: - CBCentralManagerDelegate

extension BLEManager: CBCentralManagerDelegate {

    func centralManagerDidUpdateState(_ central: CBCentralManager) {
        switch central.state {
        case .poweredOn:  setStatus("Not connected", .error)
        case .poweredOff: setStatus("Bluetooth is off", .error)
        case .unauthorized:
            setStatus("Bluetooth permission denied — enable it in Settings", .error)
        case .unsupported:
            setStatus("This device has no Bluetooth LE", .error)
        default:
            setStatus("Bluetooth unavailable", .error)
        }
    }

    func centralManager(_ central: CBCentralManager,
                        didDiscover peripheral: CBPeripheral,
                        advertisementData: [String: Any],
                        rssi RSSI: NSNumber) {

        let advUUIDs = advertisementData[CBAdvertisementDataServiceUUIDsKey] as? [CBUUID] ?? []
        let hasSerial = advUUIDs.contains(Self.serviceUUID)

        // Prefer the local advertised name, fall back to the peripheral name.
        let advName = advertisementData[CBAdvertisementDataLocalNameKey] as? String
        let name = advName ?? peripheral.name ?? "(no name)"

        var info = foundByID[peripheral.identifier]
            ?? DiscoveredDevice(id: peripheral.identifier,
                                peripheral: peripheral,
                                name: name,
                                rssi: RSSI.intValue,
                                hasSerialService: hasSerial)
        info.name = name
        info.rssi = RSSI.intValue
        info.hasSerialService = info.hasSerialService || hasSerial
        foundByID[peripheral.identifier] = info
    }

    func centralManager(_ central: CBCentralManager, didConnect peripheral: CBPeripheral) {
        setStatus("Discovering services…", .warn)
        peripheral.discoverServices([Self.serviceUUID])
    }

    func centralManager(_ central: CBCentralManager,
                        didFailToConnect peripheral: CBPeripheral, error: Error?) {
        isConnected = false
        setStatus("Failed to connect", .error)
    }

    func centralManager(_ central: CBCentralManager,
                        didDisconnectPeripheral peripheral: CBPeripheral, error: Error?) {
        writeChar = nil
        isConnected = false
        setStatus("Disconnected", .error)
    }
}

// MARK: - CBPeripheralDelegate

extension BLEManager: CBPeripheralDelegate {

    func peripheral(_ peripheral: CBPeripheral, didDiscoverServices error: Error?) {
        // If FFE0 wasn't found, fall back to discovering all services.
        let services = peripheral.services ?? []
        if services.first(where: { $0.uuid == Self.serviceUUID }) == nil, services.isEmpty {
            peripheral.discoverServices(nil)
            return
        }
        for service in services {
            peripheral.discoverCharacteristics(nil, for: service)
        }
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didDiscoverCharacteristicsFor service: CBService, error: Error?) {
        guard let chars = service.characteristics else { return }

        // Prefer FFE1; otherwise the first writable characteristic.
        var chosen = chars.first { $0.uuid == Self.charUUID }
        if chosen == nil {
            chosen = chars.first {
                $0.properties.contains(.write) || $0.properties.contains(.writeWithoutResponse)
            }
        }
        guard let c = chosen else { return }

        writeChar = c
        writeType = c.properties.contains(.writeWithoutResponse) ? .withoutResponse : .withResponse

        // Mirror the Android app: enable notifications on the serial
        // characteristic. Some JDY-23 firmware won't pass data until this is done.
        if c.properties.contains(.notify) || c.properties.contains(.indicate) {
            peripheral.setNotifyValue(true, for: c)
        }

        isConnected = true
        setStatus("Connected – ready", .ok)
    }

    func peripheral(_ peripheral: CBPeripheral,
                    didWriteValueFor characteristic: CBCharacteristic, error: Error?) {
        if let error = error {
            setStatus("Write error: \(error.localizedDescription)", .error)
        } else {
            setStatus("Sent OK: \(lastSent)", .ok)
        }
    }
}
