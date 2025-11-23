# P2PBRS — Architecture and Flows

## Big Picture
- Peers register with a coordination server over UDP, then regularly send UDP heartbeats.
- File backup uses TCP for chunk data and UDP for control acks (`CHUNK_OK`/`CHUNK_ERROR`).
- If the server cannot provide a backup plan, owners fall back to local static peers from config.

## Components
- `PeerMain` — CLI entrypoint; wires services per command.
- `PeerConfig` — Loads configuration; supports `-Dconfig=<file>` override via the runner.
- `MessageCodec` — Builds/parses protocol messages.
- `UdpControlChannel` — Sends UDP messages; listens and tracks acks.
- `TcpChunkSender` — Opens TCP connection and sends header + bytes.
- `BackupService` — Orchestrates backup, chunking, sending, retries, and `BACKUP_DONE`.
- `StorageReceiverService` — TCP listener on storage peers; verifies and stores chunks; sends acks.
- `HeartbeatService` — Schedules periodic `HEARTBEAT` messages.
- `Crc32Util` — CRC32 helpers for file and chunk integrity.

## Primary Flows
- Registration
  - Owner/Storage sends: `REGISTER RQ# Name Role IP UDP_Port TCP_Port StorageMB`.
  - Server replies `REGISTERED` or denial.

- Heartbeats
  - Peers send `HEARTBEAT` with name, number of chunks, and timestamp at `heartbeat.intervalSeconds`.
  - Storage peers typically run heartbeat loop continuously.

- Backup
  - Owner computes full-file CRC32 and sends `BACKUP_REQ`.
  - Plan selection:
    - If server responds with `BACKUP_PLAN`, use its peer list and chunk size.
    - If not, derive a plan from `backup.staticPeers` and `backup.chunkSize`.
  - Chunking and sending:
    - Split file into fixed-size chunks.
    - For each chunk: compute CRC32; send `SEND_CHUNK` header + bytes via TCP.
  - Acknowledgment and retries:
    - Wait up to 5s for `CHUNK_OK`/`CHUNK_ERROR` via UDP.
    - Retry up to 3 times on timeout or error; cancel after 3 failures.
  - Completion:
    - If all chunks succeed, owner sends `BACKUP_DONE` to the server (best effort).

## Storage Behavior
- On TCP accept: read `SEND_CHUNK` header, then exact bytes.
- Verify CRC32; save under `storage/<FileName>/chunk-<id>.bin` when valid.
- Send `CHUNK_OK`/`CHUNK_ERROR` via UDP to the owner using the owner’s UDP port from the header.

## Offline Fallback
- Owners can back up end-to-end without a server using `backup.staticPeers` and `backup.chunkSize`.
- Logs show: `BACKUP_PLAN using local static peers, chunkSize=...`.

## Clean Shutdown
- Owner stops heartbeat and storage services cleanly after backup to avoid lingering sockets.