# OCPP 1.6J Server Implementation

This project includes a complete OCPP 1.6J (Open Charge Point Protocol) server implementation using Ktor WebSockets.

## Overview

The OCPP server allows electric vehicle charge points to connect and communicate using the OCPP 1.6J protocol over WebSocket connections.

## Architecture

The implementation consists of three main components:

### 1. OCPP Messages (`src/main/kotlin/ocpp/OcppMessages.kt`)
- Complete data classes for all OCPP 1.6J message types
- Support for Core Profile, Smart Charging, Remote Trigger, Firmware Management, and Reservation profiles
- Serializable message structures using Kotlinx Serialization

### 2. Session Manager (`src/main/kotlin/ocpp/OcppSessionManager.kt`)
- Manages all active charge point connections
- Tracks charge point state (connectors, transactions, configuration)
- Handles incoming requests from charge points:
  - BootNotification
  - Heartbeat
  - Authorize
  - StartTransaction / StopTransaction
  - StatusNotification
  - MeterValues
  - DataTransfer
- Supports sending remote commands to charge points:
  - RemoteStartTransaction
  - RemoteStopTransaction
  - Reset

### 3. WebSocket Server (`src/main/kotlin/ocpp/OcppServer.kt`)
- Ktor-based WebSocket server
- Message routing and processing
- Error handling and validation
- Support for multiple charge points simultaneously

## WebSocket Endpoints

The server exposes two WebSocket endpoints:

- `/ocpp/{chargePointId}` - Standard OCPP endpoint
- `/ocpp/1.6/{chargePointId}` - Version-specific endpoint

Where `{chargePointId}` is a unique identifier for each charge point.

## Configuration

OCPP server configuration is in `src/main/resources/config.yaml`:

```yaml
ocpp:
  enabled: true
  heartbeatInterval: 300
  connectionTimeout: 60
```

## Message Format

OCPP 1.6J uses JSON-RPC 2.0 over WebSocket with a specific array format:

### CALL (Request)
```json
[2, "unique-id", "Action", {"key": "value"}]
```

### CALLRESULT (Response)
```json
[3, "unique-id", {"key": "value"}]
```

### CALLERROR (Error)
```json
[4, "unique-id", "ErrorCode", "Error Description", {}]
```

## Supported OCPP Actions

### From Charge Point to Central System:
- **BootNotification** - Charge point registration
- **Heartbeat** - Keep-alive messages
- **Authorize** - ID tag authorization
- **StartTransaction** - Begin charging session
- **StopTransaction** - End charging session
- **StatusNotification** - Connector status updates
- **MeterValues** - Energy consumption data
- **DataTransfer** - Vendor-specific data

### From Central System to Charge Point:
- **RemoteStartTransaction** - Start charging remotely
- **RemoteStopTransaction** - Stop charging remotely
- **Reset** - Reboot the charge point
- **ChangeAvailability** - Change connector availability
- **ChangeConfiguration** - Modify configuration
- **GetConfiguration** - Retrieve configuration
- **UnlockConnector** - Unlock a connector

## Usage Example

### Connecting a Charge Point

A charge point connects to:
```
ws://localhost:8080/ocpp/CP001
```

### Boot Notification Example

Charge point sends:
```json
[2, "1", "BootNotification", {
  "chargePointVendor": "VendorName",
  "chargePointModel": "Model1",
  "chargePointSerialNumber": "SN123456"
}]
```

Server responds:
```json
[3, "1", {
  "status": "Accepted",
  "currentTime": "2025-11-16T15:00:00Z",
  "interval": 300
}]
```

### Start Transaction Example

Charge point sends:
```json
[2, "2", "StartTransaction", {
  "connectorId": 1,
  "idTag": "USER001",
  "meterStart": 0,
  "timestamp": "2025-11-16T15:05:00Z"
}]
```

Server responds:
```json
[3, "2", {
  "transactionId": 1,
  "idTagInfo": {
    "status": "Accepted"
  }
}]
```

## Session Management

The server maintains state for each connected charge point:
- Connection status and last heartbeat
- Connector states (Available, Charging, Faulted, etc.)
- Active transactions
- Configuration parameters

## Testing

### Automated Test Suite

The project includes a comprehensive test suite with 28+ tests covering:

**OcppServerTest** ([`src/test/kotlin/ocpp/OcppServerTest.kt`](src/test/kotlin/ocpp/OcppServerTest.kt:1)):
- BootNotification handling
- Heartbeat messages
- Authorization requests
- Transaction lifecycle (Start/Stop)
- Status notifications
- Meter values
- Data transfer
- Error handling (unsupported actions, invalid messages)
- Multiple simultaneous charge points

**OcppSessionManagerTest** ([`src/test/kotlin/ocpp/OcppSessionManagerTest.kt`](src/test/kotlin/ocpp/OcppSessionManagerTest.kt:1)):
- Session registration/unregistration
- Transaction management
- Connector state tracking
- Multiple connectors per charge point
- Heartbeat tracking
- Boot notification state updates

Run the tests:
```bash
./gradlew test --tests "io.konektis.ocpp.*"
```

### Manual Testing

You can also test the OCPP server using:

1. **OCPP Test Tools**:
   - OCPP-J Test Client
   - ChargeTime OCPP Simulator

2. **WebSocket Clients**:
   - wscat: `wscat -c ws://localhost:8080/ocpp/TEST001`
   - Postman WebSocket feature

3. **Example Test Message**:
```bash
wscat -c ws://localhost:8080/ocpp/TEST001
> [2,"1","BootNotification",{"chargePointVendor":"Test","chargePointModel":"Model1"}]
```

## Running the Server

```bash
./gradlew run
```

The server will start on port 8080 (configurable in `application.yaml`).

## Logging

The server logs all OCPP messages and events to stdout:
- `[INFO]` - Important events (connections, transactions)
- `[DEBUG]` - Message details
- `[WARN]` - Unsupported actions
- `[ERROR]` - Errors and exceptions

## Future Enhancements

Potential improvements:
1. Database persistence for transactions and meter values
2. Authentication and authorization
3. TLS/SSL support for secure WebSocket connections
4. Advanced charging profiles and smart charging
5. Firmware update management
6. Local authorization list management
7. Reservation system
8. Comprehensive logging to database
9. REST API for charge point management
10. Web dashboard for monitoring

## Standards Compliance

This implementation follows:
- OCPP 1.6 JSON Specification
- OCPP 1.6 Edition 2 (2019)
- Core Profile (required)
- Smart Charging Profile (partial)
- Remote Trigger Profile (partial)

## License

[Your License Here]