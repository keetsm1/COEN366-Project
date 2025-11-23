# Configuration Reference

## How Config Is Loaded
- Default file: `config.properties`.
- Override: pass `-Dconfig=<file>` or use the runner `./run_peer.ps1 ... -Config <file>`.
- Common practice: maintain peer-specific files (e.g., `config.alice.properties`, `config.bob.properties`).

## Keys
- `peer.name` — Human-readable peer name (e.g., `Alice`, `Bob`).
- `peer.role` — `OWNER` or `STORAGE`.
- `peer.ip` — Local IP the server/peers should use to reach you.
- `peer.udpPort` — Control channel port for UDP messages.
- `peer.tcpPort` — Data channel port for TCP chunk transfer (storage peers listen here).
- `peer.storageMB` — Advertised capacity for storage peers.
- `server.host` — Coordination server host/IP.
- `server.udpPort` — Coordination server UDP port.
- `heartbeat.intervalSeconds` — Period between `HEARTBEAT` messages.
- `backup.staticPeers` — Offline peer set when no server plan is available.
  - Format: `[Name@Host:TcpPort:UdpPort,Name2@Host2:Tcp2:Udp2,...]`.
- `backup.chunkSize` — Chunk size in bytes when statically chunking (e.g., `65536`).

## Examples

### Owner (Alice)
```
peer.name=Alice
peer.role=OWNER
peer.ip=127.0.0.1
peer.udpPort=5002
peer.tcpPort=5003
server.host=127.0.0.1
server.udpPort=4000
heartbeat.intervalSeconds=10
backup.staticPeers=[Bob@127.0.0.1:6002:5002]
backup.chunkSize=65536
```

### Storage (Bob)
```
peer.name=Bob
peer.role=STORAGE
peer.ip=127.0.0.1
peer.udpPort=5002
peer.tcpPort=6002
peer.storageMB=512
server.host=127.0.0.1
server.udpPort=4000
heartbeat.intervalSeconds=10
```

## Notes
- `config.properties` is optional if you always pass a peer-specific file with `-Config`.
- Ensure UDP/TCP ports are not in use by other processes.
- `backup.chunkSize` only applies to offline backup (server plans may choose sizes).
- Storage peers must keep their TCP port reachable for owners.