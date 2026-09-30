# Smartphone Opportunistic Mesh

## Spec-Driven Development Document

### 1. Objective

Build a mobile prototype demonstrating that participating smartphones can form an opportunistic, Bluetooth-based mesh network and relay messages between devices without requiring internet connectivity.

The primary use case is a high-density event such as a large music festival, where many attendees already have the same official application installed but cellular connectivity may become congested.

The system must demonstrate:

> Phone A can send a message to Phone D even when A and D cannot directly communicate, by opportunistically relaying the message through Phones B and C.

```text
A → B → C → D
```

The prototype must work without an internet connection after the participating nodes have started.

---

# 2. Non-goals

The MVP must NOT attempt to implement:

* a replacement for the global internet
* a general-purpose IP network
* arbitrary internet traffic routing
* distributed compute
* distributed databases
* cryptocurrency or tokens
* economic incentives
* permanent routing tables
* GPS-based routing
* sophisticated geographic routing
* centralized message delivery
* guaranteed message delivery
* arbitrary Bluetooth device communication
* communication with devices that have not opted into the mesh

The MVP should optimize for **proof of the fundamental networking primitive**, not production completeness.

---

# 3. Core principle

Every participating phone is a network node.

A node should be able to:

1. advertise its presence
2. discover nearby participating nodes
3. establish a short-lived connection
4. exchange protocol messages
5. store messages temporarily
6. forward messages to other nodes
7. suppress duplicate messages
8. expire messages after a TTL
9. deliver messages to their destination

The network is opportunistic.

A node does not need to know the complete network topology.

A node does not need to know the physical location of the destination.

A message can travel through arbitrary intermediate nodes.

---

# 4. Example

Five participating devices:

```text
┌───┐      ┌───┐      ┌───┐      ┌───┐
│ A │ ───→ │ B │ ───→ │ C │ ───→ │ D │
└───┘      └───┘      └───┘      └───┘
```

A wants to send:

```text
"Meet at Stage 4"
```

A does not know where D is.

A broadcasts/advertises the message to nearby peers.

B receives it and stores it.

C later receives it from B and stores it.

D receives it from C.

D recognizes itself as the destination and delivers the message to the application.

A must NOT need:

* D's GPS coordinates
* D's IP address
* a direct Bluetooth connection
* an internet connection
* a central routing server

---

# 5. Architecture

The architecture consists of four logical layers.

```text
┌─────────────────────────────────────┐
│             Application             │
│                                     │
│ sendMessage / receiveMessage        │
├─────────────────────────────────────┤
│             Mesh Engine             │
│                                     │
│ routing / forwarding / TTL / dedupe │
├─────────────────────────────────────┤
│          Mesh Protocol              │
│                                     │
│ HELLO / MESSAGE / ACK / REQUEST     │
├─────────────────────────────────────┤
│        Bluetooth Transport          │
│                                     │
│ BLE advertising / scanning / GATT   │
└─────────────────────────────────────┘
```

The transport layer must be isolated from the mesh logic.

The mesh engine should not directly depend on platform-specific Bluetooth APIs.

---

# 6. Node identity

Every installation of the application must have a locally generated cryptographic identity.

At first startup:

```text
privateKey = generateKeyPair()
publicKey = derivePublicKey(privateKey)
nodeId = hash(publicKey)
```

The private key must never leave the device.

The node ID should be stable for the lifetime of the local installation unless the user explicitly resets the application identity.

For MVP purposes, node IDs can be represented as:

```text
nodeId: string
```

with a fixed-length hexadecimal representation.

---

# 7. Event/network identity

The mesh must be scoped to an event/network.

Example:

```text
eventId = primavera-2027
```

A node should only participate in a mesh when it has joined that event.

Bluetooth advertisements must contain enough information to determine whether the peer belongs to the same mesh.

The system must reject peers belonging to unrelated events.

---

# 8. Bluetooth discovery

The initial transport is Bluetooth Low Energy.

A participating device has two roles:

### Advertiser

Periodically advertises:

```text
MESH_SERVICE
eventId
nodeId
protocolVersion
```

### Scanner

Periodically scans for nearby devices advertising the mesh service.

A device can simultaneously act as:

```text
central + peripheral
```

where supported by the platform.

The implementation must NOT require users to manually pair devices through the operating system Bluetooth UI.

The application owns the mesh connection lifecycle.

---

# 9. Bluetooth payload

Advertising packets should remain small.

Do not attempt to transmit application messages directly through advertisements.

Advertisements are primarily for:

* peer discovery
* event identification
* protocol version discovery
* node identification

Once a peer is discovered, the devices establish a short-lived connection and exchange data through the appropriate Bluetooth data channel.

---

# 10. Peer model

A node maintains a temporary local peer table.

Example:

```text
Peer {
    nodeId
    eventId
    lastSeen
    connectionState
    protocolVersion
    capabilities
}
```

Peers are ephemeral.

A node must assume that any peer can disappear at any time.

Do not require persistent connections.

---

# 11. Connection lifecycle

The preferred lifecycle is:

```text
DISCOVER
   ↓
CONNECT
   ↓
HANDSHAKE
   ↓
EXCHANGE
   ↓
DISCONNECT
```

Connections should be short-lived.

The system should not attempt to maintain a permanent connection with every discovered peer.

---

# 12. Handshake

After connection:

```text
A → B: HELLO
B → A: HELLO_ACK
```

The handshake must establish:

* protocol version
* event ID
* node ID
* capabilities
* optional cryptographic authentication

For MVP, authentication can be simplified, but the protocol must be designed so that authenticated identities can be added without changing the transport architecture.

---

# 13. Protocol messages

Define a common protocol envelope.

```text
ProtocolMessage {
    version
    type
    messageId
    sourceNodeId
    destinationNodeId
    eventId
    ttl
    createdAt
    payload
}
```

Message types:

```text
HELLO
HELLO_ACK
MESSAGE
ACK
```

Future types:

```text
HAVE
REQUEST
DATA
PEER_EXCHANGE
```

Do not implement future types unless required by the MVP.

---

# 14. Message identity

Every message must have a globally unique identifier within the event.

Example:

```text
messageId = random UUID
```

A node must maintain a `seenMessages` data structure.

When receiving a message:

```text
if messageId already seen:
    discard
else:
    mark as seen
    process message
```

This is mandatory.

Without duplicate suppression, flooding will rapidly create message storms.

---

# 15. TTL

Every forwarded message must have a TTL/hop count.

Example:

```text
ttl = 8
```

On forwarding:

```text
ttl = ttl - 1
```

If:

```text
ttl <= 0
```

the message must not be forwarded.

The original TTL should be configurable.

Suggested MVP default:

```text
8 hops
```

---

# 16. Message forwarding

MVP forwarding strategy: **controlled flooding**.

When a node receives a new message:

```text
1. Check eventId
2. Check messageId
3. Check destination
4. Mark message as seen
5. If destination == local node:
       deliver to application
6. Else if TTL > 0:
       schedule forwarding
```

Do not immediately forward to every peer simultaneously.

Introduce a small randomized forwarding delay.

Example:

```text
delay = random(50ms, 500ms)
```

Before forwarding, check whether the message has already been observed through another peer.

This provides basic duplicate reduction.

---

# 17. Why controlled flooding

The MVP intentionally uses flooding because it removes the need for a routing protocol.

The initial question being tested is:

> Can messages propagate reliably through a dynamic network of smartphones?

Do not prematurely optimize routing.

The progression after MVP should be:

```text
Controlled flooding
       ↓
TTL + deduplication
       ↓
Probabilistic forwarding
       ↓
Gossip / epidemic routing
       ↓
Peer information exchange
       ↓
Opportunistic routing
       ↓
Geographic hints
```

---

# 18. Destination discovery

The MVP must NOT require the sender to know the physical location of the destination.

The sender only needs:

```text
destinationNodeId
```

Example:

```text
A:
destination = D
```

A does not need:

```text
D.latitude
D.longitude
D.bluetoothAddress
D.ipAddress
```

The network discovers the destination opportunistically.

---

# 19. Store-and-forward

Messages must survive peer disconnection.

When a node receives a message:

```text
message → local temporary storage
```

The message remains available for forwarding until:

```text
TTL expires
```

or:

```text
message expiration time reached
```

The node may encounter another useful peer later.

This creates delay-tolerant networking behavior.

Example:

```text
t0: A → B

t1:
B stores message

t2:
B moves away from A

t3:
B encounters C

t4:
B → C

t5:
C encounters D

t6:
C → D
```

---

# 20. Message expiration

Messages must have an expiration time.

Example:

```text
expiresAt = createdAt + 30 minutes
```

Expired messages must be deleted and must no longer be forwarded.

Make the expiration configurable.

---

# 21. Local message store

The MVP requires persistent local storage.

Minimum fields:

```text
messageId
eventId
sourceNodeId
destinationNodeId
payload
ttl
createdAt
expiresAt
status
```

Status:

```text
RECEIVED
FORWARDED
DELIVERED
EXPIRED
```

The implementation should be bounded.

A node must never allow mesh messages to consume unlimited storage.

---

# 22. Battery requirements

Battery consumption is a first-class constraint.

The application must NOT:

* maintain permanent Bluetooth connections
* continuously scan at maximum frequency
* continuously transmit
* continuously forward duplicate messages

The preferred behavior is:

```text
sleep
 ↓
short discovery window
 ↓
discover peers
 ↓
short connection
 ↓
exchange messages
 ↓
disconnect
 ↓
sleep
```

Scanning/advertising frequency should be configurable.

---

# 23. Adaptive participation

The architecture should support future node participation policies:

```text
NORMAL
LOW_BATTERY
CHARGING
BACKGROUND
FOREGROUND
```

For MVP, implement at least:

```text
NORMAL
LOW_BATTERY
```

A low-battery device should reduce mesh activity.

A charging device may participate more aggressively in future versions.

Do not implement sophisticated battery optimization until measurements demonstrate the need.

---

# 24. Internet independence

The mesh must continue operating when:

```text
Wi-Fi = OFF
Cellular data = OFF
```

The application should provide a test mode that explicitly disables internet-dependent functionality.

A node must not silently fall back to the cloud for message delivery.

The cloud/control plane is optional and must not be required for local message propagation.

---

# 25. Optional control plane

The architecture should allow a future backend but the MVP must not depend on it.

Potential future responsibilities:

```text
Backend
├── event configuration
├── application updates
├── identity management
├── analytics
├── network telemetry
├── synchronization
└── remote configuration
```

The backend must NOT become the message-routing dependency.

The fundamental protocol should work:

```text
Phone ↔ Phone ↔ Phone
```

without:

```text
Phone → Cloud → Phone
```

---

# 26. GPS/location

GPS must NOT be used for core message routing in the MVP.

Reason:

The sender generally does not know the destination's current location.

Location can become a future routing hint.

Potential future metadata:

```text
lastKnownLocation
lastSeenTime
movementDirection
```

This could eventually support forwarding toward the destination's last known area.

Do not implement this in the first version.

---

# 27. Future content-addressed networking

The protocol should eventually support content rather than only messages.

Future model:

```text
contentHash = SHA-256(content)
```

A node can advertise:

```text
I_HAVE(contentHash)
```

Another node can request:

```text
REQUEST(contentHash)
```

and receive:

```text
DATA(contentHash, chunk)
```

This would allow:

* offline maps
* festival schedules
* announcements
* images
* event information
* replicated content

The MVP should only implement small messages.

---

# 28. Security requirements

The mesh must assume that participating devices may be malicious.

MVP requirements:

* never trust arbitrary Bluetooth devices
* only process the application-specific mesh service
* validate event ID
* validate protocol version
* validate message structure
* enforce maximum payload size
* enforce TTL
* enforce expiration
* enforce storage limits
* deduplicate message IDs

Do not trust:

```text
payload size
TTL
timestamp
node ID
event ID
```

without validation.

---

# 29. Privacy

Do not broadcast:

* user's real name
* email
* phone number
* precise GPS location
* ticket information

The BLE advertisement should expose only the minimum information required for discovery.

A node ID must not directly reveal the user's identity.

---

# 30. Payload limits

MVP messages should be deliberately small.

Suggested maximum:

```text
4 KB
```

The protocol should reject larger messages.

Large content will require chunking and a different replication strategy later.

---

# 31. Application API

The application layer should have a simple interface.

Conceptually:

```kotlin
interface MeshNetwork {

    fun start(eventId: String)

    fun stop()

    fun send(
        destinationNodeId: NodeId,
        payload: ByteArray
    ): MessageId

    fun observeMessages(
        handler: MessageHandler
    )

    fun getStatus(): MeshStatus
}
```

The actual language/API should follow the target mobile platform.

The application should never need to know:

* BLE details
* scanning implementation
* connection management
* forwarding algorithm
* TTL handling
* deduplication

---

# 32. Mesh status

Expose diagnostic information.

Example:

```text
MeshStatus {
    nodeId
    eventId
    discoveredPeers
    connectedPeers
    storedMessages
    forwardedMessages
    deliveredMessages
    expiredMessages
    bytesSent
    bytesReceived
}
```

This is important for experiments.

---

# 33. Developer/debug UI

The MVP must include a debug screen showing:

```text
Node ID:       ABC123
Event:         test-event
Mesh:          ACTIVE

Peers:
  DEF456       last seen 1.2s ago
  GHI789       last seen 4.1s ago
  JKL012       last seen 7.3s ago

Messages:
  Received:    42
  Forwarded:   31
  Delivered:   8
  Expired:     3

Bluetooth:
  Advertising: ON
  Scanning:    ON

Internet:
  OFF
```

This UI is primarily for development and demonstrations.

---

# 34. Test mode

The application must support a local test mode.

The developer should be able to assign:

```text
Event: test-event
Node ID: generated
```

and see nearby nodes.

A test message should display:

```text
Message ID
Source
Destination
Hop count
TTL
Created
Received
Delivered
```

---

# 35. End-to-end acceptance test

The fundamental acceptance test is:

### Setup

Four phones:

```text
A B C D
```

Arrange them so that:

```text
A ↔ B
B ↔ C
C ↔ D
```

but:

```text
A ↛ D
```

Turn off:

```text
Wi-Fi
Cellular data
```

Leave Bluetooth enabled.

### Test

A sends a message addressed to D.

Expected behavior:

```text
A
 ↓
B
 ↓
C
 ↓
D
```

Expected result:

```text
D receives the message.
```

No backend requests may be necessary.

---

# 36. Duplicate forwarding acceptance test

Topology:

```text
       B
      ↗ ↘
A          D
      ↘ ↗
       C
```

A sends a message to D.

B and C both receive the message.

D may receive the message from both paths.

Expected:

```text
D delivers message exactly once.
```

Duplicate copies must be ignored at the application layer.

---

# 37. TTL acceptance test

Configure:

```text
TTL = 2
```

Topology:

```text
A → B → C → D
```

Expected:

```text
A → B       allowed
B → C       allowed
C → D       rejected
```

D must not receive the message.

---

# 38. Expiration acceptance test

Create:

```text
message expiration = 1 second
```

Store it on B.

Wait >1 second.

Connect B to C.

Expected:

```text
B does not forward message.
```

The message is removed from the local store.

---

# 39. Offline acceptance test

After the network has started:

```text
disable Wi-Fi
disable cellular
```

The following must continue working:

```text
BLE discovery
peer connections
handshake
message exchange
message forwarding
message delivery
```

The application must not require the internet for any of these operations.

---

# 40. Network resilience acceptance test

During message propagation, randomly terminate nodes.

Example:

```text
A → B → C → D
```

Kill C.

Then:

```text
A → B
```

must continue operating normally.

The system must never assume that a previously discovered peer remains available.

---

# 41. Metrics

Every experiment must collect:

### Delivery

```text
deliveryRate
```

### Latency

```text
timeCreated → timeDelivered
```

### Hop count

```text
numberOfForwarders
```

### Network activity

```text
messagesReceived
messagesForwarded
messagesDropped
duplicatesSuppressed
```

### Bluetooth

```text
connections
connectionDuration
bytesSent
bytesReceived
```

### Battery

Record approximate battery level:

```text
startBattery
endBattery
duration
```

The first objective is empirical measurement rather than optimization.

---

# 42. Experiment progression

Once the four-phone test works:

### Experiment 1

```text
4 phones
```

Goal: end-to-end forwarding.

### Experiment 2

```text
10 phones
```

Goal: duplicate suppression.

### Experiment 3

```text
25 phones
```

Goal: controlled flooding behavior.

### Experiment 4

```text
50 phones
```

Goal: measure battery and traffic.

### Experiment 5

```text
100+ phones
```

Goal: determine whether the architecture begins to suffer from message storms.

The system should log enough information to identify the bottleneck.

---

# 43. Scaling strategy

Do not assume flooding will scale to 50,000 devices.

The expected evolution is:

```text
                 MVP
                  │
          controlled flooding
                  │
          ┌───────┴────────┐
          ↓                ↓
      duplicate         TTL
      suppression
          │
          ↓
     probabilistic
      forwarding
          │
          ↓
        gossip
          │
          ↓
   peer information
      exchange
          │
          ↓
 opportunistic routing
          │
          ↓
 geographic hints
```

The architecture must make this evolution possible without rewriting the Bluetooth transport.

---

# 44. Festival architecture

The eventual festival deployment should look approximately like:

```text
                     Festival Backend
                           │
                     official app
                           │
                  ┌────────┴────────┐
                  │                 │
                 📱                 📱
                  ↕                 ↕
                📱 ↔ 📱 ↔ 📱 ↔ 📱 ↔ 📱
                  ↕    ↕    ↕    ↕
                📱 ↔ 📱 ↔ 📱 ↔ 📱 ↔ 📱
                  ↕          ↕
                📱 ↔ 📱 ↔ 📱 ↔ 📱
```

The official festival application provides the initial population of participating nodes.

The mesh provides an additional local communication layer.

---

# 45. Commercial architecture

The eventual commercial product should not depend on preventing customers from modifying client-side code.

The value should be in the complete system:

```text
                    Customer App
                         │
                     Mesh SDK
                         │
             ┌───────────┴───────────┐
             │                       │
        Local mesh              Optional cloud
             │                       │
             │                 ┌─────┴─────┐
             │                 │           │
             │             Analytics    Management
             │
             └───────────┬───────────┘
                         │
                   Your platform
```

Potential commercial functionality:

* deployment
* monitoring
* analytics
* configuration
* network health
* fleet management
* support
* integrations
* event-specific tooling

The core protocol should not require a centralized control plane.

---

# 46. Product hypothesis

The first product hypothesis is:

> In extremely high-density environments, a local phone-to-phone network can offload selected local communication from congested cellular networks.

The first technical hypothesis is:

> A voluntary population of smartphones running the same application can form a sufficiently dense opportunistic BLE network to propagate small messages without requiring direct connectivity between sender and receiver.

The MVP exists to test these hypotheses.

---

# 47. Definition of done

The MVP is complete when:

* two phones can discover each other without manual Bluetooth pairing
* phones can authenticate/identify themselves as mesh nodes
* phones can establish a mesh connection
* phones can exchange protocol messages
* messages can be stored locally
* messages can be forwarded
* duplicate messages are suppressed
* TTL is enforced
* messages expire
* the destination receives the message exactly once
* the system continues operating with Wi-Fi and cellular disabled
* nodes can disappear without crashing the network
* debug metrics are visible
* a four-node multi-hop demonstration works reliably
* the implementation is sufficiently modular to replace flooding with a more sophisticated routing strategy later

---

# 48. Implementation order

The coding agent should implement in this order:

```text
1. Domain models
       ↓
2. Node identity
       ↓
3. Mesh protocol
       ↓
4. In-memory message store
       ↓
5. Routing/TTL/deduplication
       ↓
6. Abstract transport interface
       ↓
7. BLE discovery
       ↓
8. BLE connection
       ↓
9. Protocol exchange over BLE
       ↓
10. Persistent message store
       ↓
11. Debug UI
       ↓
12. Metrics
       ↓
13. End-to-end multi-device tests
       ↓
14. Battery experiments
```

Do not begin with UI.

Do not begin with the backend.

Do not begin with GPS.

Do not begin with sophisticated routing.

The first milestone is simply:

```text
A → B → C → D
```

with the internet turned off.

---

# 49. Guiding engineering principle

The implementation should preserve this fundamental property:

> **The network must remain useful even when the infrastructure outside the phones is unavailable.**

The cloud can make the system better.

It must not be what makes the mesh possible.
