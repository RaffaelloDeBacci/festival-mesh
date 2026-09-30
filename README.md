# Festival Communicator SDK

Festival Communicator is an offline-first mesh communication SDK for high-density events (like festivals), where phones can relay short messages over Bluetooth without internet connectivity.

It demonstrates opportunistic multi-hop delivery:

```text
A -> B -> C -> D
```

where A and D do not need direct connectivity.

## What this project does

- creates a local node identity per installation
- discovers nearby peers in the same event mesh
- exchanges protocol messages over BLE transport
- stores and forwards messages across hops
- suppresses duplicates
- enforces TTL and expiration
- exposes diagnostics/metrics for testing and experiments

## Module structure

```text
festival-comunicator/
├── mesh-core     (Kotlin Multiplatform shared engine)
├── mesh-android  (Android BLE transport + Android SDK surface)
└── mesh-ios      (iOS BLE transport + iOS framework target)
```

### `mesh-core` (shared logic)

`mesh-core` contains the platform-neutral mesh engine and protocol:

- public API (`MeshNetwork`, `MeshConfig`, `MeshStatus`)
- routing/forwarding (`DefaultMeshNetwork`)
- protocol envelope + codecs
- TTL/expiration/duplicate suppression
- in-memory store-and-forward model
- transport abstraction (`MeshTransport`)
- in-memory transport for acceptance tests

Platform-specific pieces (identity/scheduler/locking) are provided with KMP `expect/actual`.

### `mesh-android`

Android module that depends on `mesh-core` and provides:

- BLE advertiser/scanner + GATT exchange transport
- `AndroidFestivalMesh.create(context, ...)`

This module plugs Android transport into the shared engine.

### `mesh-ios`

iOS Kotlin module that depends on `mesh-core` and provides:

- CoreBluetooth-based BLE transport
- `IosFestivalMesh.create(...)`
- iOS framework binary target (`FestivalMeshIOS`)

This module plugs iOS transport into the same shared engine.

## How components interact

1. App calls platform factory (`AndroidFestivalMesh` / `IosFestivalMesh`) to create a `MeshNetwork`.
2. Factory creates:
   - node identity
   - platform BLE transport
   - shared `DefaultMeshNetwork`
3. `MeshNetwork.start(eventId)` opens discovery/advertising in transport.
4. Transport reports peers/messages through `MeshTransportListener`.
5. Shared core validates event/protocol, deduplicates, stores, and routes.
6. If destination is local node, core delivers once to app handler.
7. Otherwise, core decrements TTL and forwards opportunistically to peers.

## Build and verification

From repository root:

```bash
./gradlew :mesh-core:jvmTest
./gradlew :mesh-android:testDebugUnitTest
./gradlew :mesh-ios:linkDebugFrameworkIosSimulatorArm64
```

## Current scope

This is an MVP mesh SDK focused on proving the networking primitive.  
It is not yet a production-grade guaranteed-delivery system.
